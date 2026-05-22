package com.chatroom.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.chatroom.common.Constants;
import com.chatroom.mapper.BotSkillMapper;
import com.chatroom.mapper.FriendMapper;
import com.chatroom.mapper.MessageMapper;
import com.chatroom.mapper.UserMapper;
import com.chatroom.model.dto.ChatMessageDTO;
import com.chatroom.model.entity.BotSkill;
import com.chatroom.model.entity.Friend;
import com.chatroom.model.entity.Message;
import com.chatroom.model.entity.User;
import com.chatroom.model.vo.MessageVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;

@Slf4j
@Service
@RequiredArgsConstructor
public class BotManager {

    private final BotSkillMapper botSkillMapper;
    private final UserMapper userMapper;
    private final MessageMapper messageMapper;
    private final FriendMapper friendMapper;
    private final LLMApiClient llmApiClient;
    private final BotSkillDocService botSkillDocService;
    private final SkillFolderService skillFolderService;
    private final PasswordEncoder passwordEncoder;
    private final MessageService messageService;
    private final SimpMessagingTemplate messagingTemplate;

    @org.springframework.beans.factory.annotation.Qualifier("botTaskExecutor")
    private final java.util.concurrent.Executor botTaskExecutor;

    // Per-bot semaphore for concurrency control
    private final ConcurrentHashMap<Long, Semaphore> botSemaphores = new ConcurrentHashMap<>();
    // Per-bot message queues
    private final ConcurrentHashMap<Long, LinkedList<Map<String, Object>>> botQueues = new ConcurrentHashMap<>();
    // Per-bot circuit breaker state: true = open (broken)
    private final ConcurrentHashMap<Long, Boolean> circuitBreakers = new ConcurrentHashMap<>();
    // Circuit breaker timestamps
    private final ConcurrentHashMap<Long, Long> circuitOpenTime = new ConcurrentHashMap<>();
    // Per-bot active mode: botUserId -> ActiveModeConfig
    private final ConcurrentHashMap<Long, ActiveModeConfig> activeModeConfigs = new ConcurrentHashMap<>();

    /** Active mode configuration for a bot */
    public static class ActiveModeConfig {
        public boolean enabled;
        public int intervalSeconds;
        public long lastSentTime;
        public ActiveModeConfig(boolean enabled, int intervalSeconds) {
            this.enabled = enabled;
            this.intervalSeconds = intervalSeconds;
            this.lastSentTime = 0;
        }
    }

    @Value("${bot.default-api-endpoint:https://api.deepseek.com/v1/chat/completions}")
    private String defaultApiEndpoint;

    @Value("${bot.default-api-key:}")
    private String defaultApiKey;

    @Value("${bot.default-model:deepseek-chat}")
    private String defaultModel;

    /**
     * Register a new bot user and its skill configuration.
     * Returns a map with the BotSkill and the generated password so callers can log the bot in.
     */
    public Map<String, Object> registerBot(String username, String nickname, String skillName,
                                 String systemPrompt, String fewShotExamples,
                                 String emotionProfile, String languageStyle,
                                 String apiEndpoint, String apiKey, String model,
                                 String password) {
        User bot = new User();
        bot.setUsername(username);
        String botPassword = (password != null && !password.isEmpty())
                ? password
                : "BOT_" + UUID.randomUUID().toString().substring(0, 8);
        bot.setPassword(passwordEncoder.encode(botPassword));
        bot.setNickname(nickname);
        bot.setAvatar("https://api.dicebear.com/7.x/bottts/svg?seed=" + username);
        bot.setStatus(Constants.USER_STATUS_ONLINE);
        bot.setIsBot(1);
        bot.setLastLoginTime(LocalDateTime.now());
        userMapper.insert(bot);

        BotSkill skill = new BotSkill();
        skill.setBotUserId(bot.getId());
        skill.setSkillName(skillName);
        skill.setSystemPrompt(systemPrompt);
        skill.setFewShotExamples(fewShotExamples);
        skill.setEmotionProfileJson(emotionProfile);
        skill.setLanguageStyleJson(languageStyle);
        skill.setApiEndpoint(apiEndpoint != null && !apiEndpoint.isEmpty() ? apiEndpoint : defaultApiEndpoint);
        String effectiveKey = (apiKey != null && !apiKey.isEmpty()) ? apiKey : defaultApiKey;
        skill.setApiKey(effectiveKey);
        skill.setModel(model != null && !model.isEmpty() ? model : defaultModel);
        skill.setStatus(Constants.BOT_STATUS_ACTIVE);
        skill.setErrorCount(0);
        skill.setLastActiveAt(LocalDateTime.now());
        botSkillMapper.insert(skill);

        botSkillDocService.exportSkillDoc(skill);

        botSemaphores.put(bot.getId(), new Semaphore(Constants.BOT_MAX_CONCURRENCY));
        botQueues.put(bot.getId(), new LinkedList<>());
        circuitBreakers.put(bot.getId(), false);

        log.info("Bot registered: id={}, name={}, skill={}", bot.getId(), nickname, skillName);
        Map<String, Object> result = new HashMap<>();
        result.put("skill", skill);
        result.put("botPassword", botPassword);
        result.put("botUserId", bot.getId());
        return result;
    }

    /**
     * Register a bot from a skill document (URL or file import).
     * Uses the skill's system prompt as-is and defaults for other fields.
     */
    public Map<String, Object> registerBotFromSkill(String skillName, String systemPrompt,
                                                     String model, String apiEndpoint,
                                                     String apiKey, String description) {
        String username = "skill_" + UUID.randomUUID().toString().substring(0, 8);
        String nickname = skillName != null ? skillName : "SkillBot";
        String botPassword = "BOT_" + UUID.randomUUID().toString().substring(0, 8);

        Map<String, Object> result = registerBot(username, nickname, skillName,
                systemPrompt != null ? systemPrompt : "",
                "[]",
                "{}",
                "{}",
                apiEndpoint, apiKey, model,
                botPassword);

        // Create skill folder and link it
        BotSkill skill = (BotSkill) result.get("skill");
        skillFolderService.createSkillFolder(skillName);
        skill.setSkillFolder(skillName);
        botSkillMapper.updateById(skill);

        return result;
    }

    /**
     * Handle an incoming message targeting a bot. Called by the WebSocket handler.
     * Returns the bot's reply message text, or null if the bot is in circuit-break state.
     */
    public String handleBotMessage(Long botUserId, Long senderId, String senderName, String content) {
        BotSkill skill = getBotSkill(botUserId);
        if (skill == null) return null;

        // Lazy-init in-memory structures (survives server restart)
        botSemaphores.computeIfAbsent(botUserId, k -> new Semaphore(Constants.BOT_MAX_CONCURRENCY));
        botQueues.computeIfAbsent(botUserId, k -> new LinkedList<>());
        circuitBreakers.computeIfAbsent(botUserId, k -> false);

        // Circuit breaker check
        if (Boolean.TRUE.equals(circuitBreakers.get(botUserId))) {
            long openTime = circuitOpenTime.getOrDefault(botUserId, 0L);
            if (System.currentTimeMillis() - openTime < Constants.BOT_CIRCUIT_BREAK_SILENCE_MS) {
                return null; // Still in silence period
            }
            // Half-open: allow one probe
            circuitBreakers.put(botUserId, false);
            log.info("Bot {} circuit half-open, probing", botUserId);
        }

        Semaphore sem = botSemaphores.get(botUserId);
        if (!sem.tryAcquire()) {
            // Busy, queue or drop
            enqueueMessage(botUserId, senderId, senderName, content);
            return null;
        }

        try {
            List<Map<String, String>> messages = buildContext(botUserId, senderName, content);
            String model = skill.getModel() != null ? skill.getModel() : defaultModel;
            String reply = llmApiClient.chat(
                    skill.getApiEndpoint(), skill.getApiKey(), model,
                    skill.getSystemPrompt(), messages);

            if (reply != null) {
                skill.setErrorCount(0);
                skill.setLastActiveAt(LocalDateTime.now());
                skill.setStatus(Constants.BOT_STATUS_ACTIVE);
            } else {
                recordError(botUserId, skill);
            }
            botSkillMapper.updateById(skill);
            return reply;

        } catch (Exception e) {
            log.error("Bot {} API call failed", botUserId, e);
            recordError(botUserId, skill);
            botSkillMapper.updateById(skill);
            return null;
        } finally {
            sem.release();
            processQueue(botUserId);
        }
    }

    private void recordError(Long botUserId, BotSkill skill) {
        int errors = skill.getErrorCount() + 1;
        skill.setErrorCount(errors);
        if (errors >= Constants.BOT_CIRCUIT_BREAK_THRESHOLD) {
            circuitBreakers.put(botUserId, true);
            circuitOpenTime.put(botUserId, System.currentTimeMillis());
            skill.setStatus(Constants.BOT_STATUS_CIRCUIT_BROKEN);
            log.warn("Bot {} circuit breaker OPEN after {} errors", botUserId, errors);
        }
    }

    private void enqueueMessage(Long botUserId, Long senderId, String senderName, String content) {
        LinkedList<Map<String, Object>> queue = botQueues.get(botUserId);
        if (queue != null && queue.size() < Constants.BOT_MAX_QUEUE_SIZE) {
            queue.offer(Map.of("senderId", senderId, "senderName", senderName, "content", content));
        }
    }

    private void processQueue(Long botUserId) {
        LinkedList<Map<String, Object>> queue = botQueues.get(botUserId);
        if (queue == null || queue.isEmpty()) return;

        Map<String, Object> next = queue.poll();
        if (next == null) return;

        Long senderId = (Long) next.get("senderId");
        String senderName = (String) next.get("senderName");
        String content = (String) next.get("content");

        // Process queued message asynchronously on the dedicated bot thread pool
        java.util.concurrent.CompletableFuture.runAsync(() -> {
            String reply = handleBotMessage(botUserId, senderId, senderName, content);
            if (reply != null && !reply.trim().isEmpty()) {
                pushBotReply(botUserId, senderId, reply, Constants.MSG_TYPE_PRIVATE);
            }
        }, botTaskExecutor);
    }

    /** Push a bot reply message to both sender and bot via WebSocket. */
    private void pushBotReply(Long botUserId, Long targetId, String content, int messageType) {
        com.chatroom.model.dto.ChatMessageDTO botDto = new com.chatroom.model.dto.ChatMessageDTO();
        botDto.setContent(content);
        botDto.setMessageType(messageType);
        botDto.setTargetId(targetId);
        botDto.setContentType(Constants.CONTENT_TYPE_TEXT);
        botDto.setClientMessageId("BQ_" + UUID.randomUUID().toString().replace("-", "").substring(0, 31));

        MessageVO botMsg = messageService.sendAndSaveMessage(botUserId, botDto);
        Map<String, Object> botPayload = buildWsPayload(botMsg);

        messagingTemplate.convertAndSendToUser(
                String.valueOf(targetId), "/queue/private/chat", botPayload);
        messagingTemplate.convertAndSendToUser(
                String.valueOf(botUserId), "/queue/private/chat", botPayload);
    }

    private List<Map<String, String>> buildContext(Long botUserId, String senderName, String content) {
        List<Map<String, String>> messages = new ArrayList<>();

        // Load full system prompt from skill folder
        BotSkill skill = getBotSkill(botUserId);
        if (skill != null && skill.getSkillFolder() != null && !skill.getSkillFolder().isBlank()) {
            String folderPrompt = skillFolderService.buildSystemPrompt(skill.getSkillFolder());
            if (!folderPrompt.isEmpty()) {
                messages.add(Map.of("role", "system", "content", folderPrompt));
            }
        } else if (skill != null && skill.getSystemPrompt() != null && !skill.getSystemPrompt().isBlank()) {
            // Fallback to old flat system prompt
            messages.add(Map.of("role", "system", "content", skill.getSystemPrompt()));
        }

        messages.add(Map.of("role", "user", "content", (senderName != null ? senderName : "用户") + "说: " + content));
        return messages;
    }

    public void deactivateBot(Long botUserId) {
        BotSkill skill = botSkillMapper.selectList(
                new LambdaQueryWrapper<BotSkill>()
                        .eq(BotSkill::getBotUserId, botUserId)).stream().findFirst().orElse(null);
        if (skill != null) {
            skill.setStatus(Constants.BOT_STATUS_INACTIVE);
            botSkillMapper.updateById(skill);
        }
        botSemaphores.remove(botUserId);
        botQueues.remove(botUserId);
        circuitBreakers.remove(botUserId);
        log.info("Bot {} deactivated", botUserId);
    }

    /** Permanently delete bot: remove user, skills, and all friend relationships */
    @org.springframework.transaction.annotation.Transactional
    public void permanentDelete(Long botUserId) {
        // Delete all friend relationships involving this bot
        friendMapper.delete(new LambdaQueryWrapper<Friend>()
                .eq(Friend::getUserId, botUserId)
                .or().eq(Friend::getFriendId, botUserId));

        // Delete bot skills and their folders
        List<BotSkill> skills = botSkillMapper.selectList(new LambdaQueryWrapper<BotSkill>()
                .eq(BotSkill::getBotUserId, botUserId));
        for (BotSkill skill : skills) {
            if (skill.getSkillFolder() != null && !skill.getSkillFolder().isBlank()) {
                skillFolderService.deleteSkillFolder(skill.getSkillFolder());
            }
            botSkillDocService.deleteSkillDoc(skill);
        }
        botSkillMapper.delete(new LambdaQueryWrapper<BotSkill>()
                .eq(BotSkill::getBotUserId, botUserId));

        // Delete the user record
        userMapper.deleteById(botUserId);

        // Clean up in-memory state
        botSemaphores.remove(botUserId);
        botQueues.remove(botUserId);
        circuitBreakers.remove(botUserId);
        circuitOpenTime.remove(botUserId);

        log.info("Bot {} permanently deleted", botUserId);
    }

    public List<BotSkill> getActiveBots() {
        return botSkillMapper.selectList(
                new LambdaQueryWrapper<BotSkill>()
                        .eq(BotSkill::getStatus, Constants.BOT_STATUS_ACTIVE));
    }

    public List<BotSkill> getAllBots() {
        return botSkillMapper.selectList(null);
    }

    public int getOnlineBotCount() {
        return (int) getActiveBots().size();
    }

    public BotSkill getBotSkill(Long botUserId) {
        return botSkillMapper.selectList(
                new LambdaQueryWrapper<BotSkill>()
                        .eq(BotSkill::getBotUserId, botUserId)).stream().findFirst().orElse(null);
    }

    // ==================== Active Mode ====================

    /** Enable or disable active mode for a bot. */
    public ActiveModeConfig setActiveMode(Long botUserId, boolean enabled, int intervalSeconds) {
        if (intervalSeconds < 15) intervalSeconds = 15; // minimum 15s to avoid API spam
        if (intervalSeconds > 600) intervalSeconds = 600; // max 10 minutes
        ActiveModeConfig config = new ActiveModeConfig(enabled, intervalSeconds);
        activeModeConfigs.put(botUserId, config);
        log.info("Bot {} active mode: enabled={}, interval={}s", botUserId, enabled, intervalSeconds);
        return config;
    }

    /** Get active mode config for a bot. */
    public ActiveModeConfig getActiveModeConfig(Long botUserId) {
        return activeModeConfigs.getOrDefault(botUserId, new ActiveModeConfig(false, 60));
    }

    /** Get all active mode configs. */
    public Map<String, Object> getAllActiveModeInfos() {
        List<Map<String, Object>> list = new ArrayList<>();
        for (Map.Entry<Long, ActiveModeConfig> e : activeModeConfigs.entrySet()) {
            BotSkill skill = getBotSkill(e.getKey());
            User botUser = userMapper.selectById(e.getKey());
            if (skill != null && botUser != null) {
                list.add(Map.of(
                    "botUserId", e.getKey(),
                    "nickname", botUser.getNickname(),
                    "enabled", e.getValue().enabled,
                    "intervalSeconds", e.getValue().intervalSeconds
                ));
            }
        }
        return Map.of("activeBots", list);
    }

    /** Scheduled task: every 10 seconds, check if any active-mode bot should send a message. */
    @Scheduled(fixedRate = 10_000)
    public void processActiveBots() {
        long now = System.currentTimeMillis();
        for (Map.Entry<Long, ActiveModeConfig> e : activeModeConfigs.entrySet()) {
            Long botUserId = e.getKey();
            ActiveModeConfig config = e.getValue();
            if (!config.enabled) continue;

            long elapsed = now - config.lastSentTime;
            if (elapsed < config.intervalSeconds * 1000L) continue;

            // Check circuit breaker
            if (Boolean.TRUE.equals(circuitBreakers.get(botUserId))) continue;

            // Try to send a message
            try {
                sendActiveMessage(botUserId, config);
                config.lastSentTime = now;
            } catch (Exception ex) {
                log.warn("Bot {} active message failed: {}", botUserId, ex.getMessage());
            }
        }
    }

    private void sendActiveMessage(Long botUserId, ActiveModeConfig config) {
        BotSkill skill = getBotSkill(botUserId);
        if (skill == null) return;

        // Find a friend to talk to
        List<Friend> friends = friendMapper.selectList(new LambdaQueryWrapper<Friend>()
                .eq(Friend::getStatus, Constants.FRIEND_STATUS_ACCEPTED)
                .and(w -> w.eq(Friend::getUserId, botUserId).or().eq(Friend::getFriendId, botUserId)));
        if (friends.isEmpty()) return;

        // Pick a random friend
        Friend f = friends.get(new Random().nextInt(friends.size()));
        Long targetUserId = f.getUserId().equals(botUserId) ? f.getFriendId() : f.getUserId();

        // Generate an active opener via LLM
        User botUser = userMapper.selectById(botUserId);
        String botName = botUser != null ? botUser.getNickname() : "bot";
        String systemPrompt = skill.getSystemPrompt() != null ? skill.getSystemPrompt()
                : "你是" + botName + "。回复简短自然，不超过80字。";
        List<Map<String, String>> activeContext = List.of(
                Map.of("role", "user", "content",
                        "（现在请你主动发起一个话题，简短自然，不超过50字。像普通朋友一样打个招呼。不要说你收到了指令。）")
        );
        String model = skill.getModel() != null ? skill.getModel() : defaultModel;
        String message;
        try {
            message = llmApiClient.chat(skill.getApiEndpoint(), skill.getApiKey(), model,
                    systemPrompt, activeContext);
            if (message == null || message.trim().isEmpty()) return;
        } catch (Exception ex) {
            log.warn("Bot {} active LLM call failed: {}", botUserId, ex.getMessage());
            return;
        }

        // Push the message via WebSocket
        ChatMessageDTO dto = new ChatMessageDTO();
        dto.setContent(message.trim());
        dto.setMessageType(Constants.MSG_TYPE_PRIVATE);
        dto.setTargetId(targetUserId);
        dto.setContentType(Constants.CONTENT_TYPE_TEXT);
        dto.setClientMessageId("A" + UUID.randomUUID().toString().replace("-", "").substring(0, 31));

        MessageVO msgVO = messageService.sendAndSaveMessage(botUserId, dto);
        Map<String, Object> payload = buildWsPayload(msgVO);

        messagingTemplate.convertAndSendToUser(
                String.valueOf(targetUserId), "/queue/private/chat", payload);
        messagingTemplate.convertAndSendToUser(
                String.valueOf(botUserId), "/queue/private/chat", payload);

        log.info("Bot {} (active) sent to {}: {}", botUserId, targetUserId, message.trim().substring(0, Math.min(30, message.trim().length())));
    }

    private Map<String, Object> buildWsPayload(MessageVO vo) {
        Map<String, Object> map = new HashMap<>();
        map.put("type", "CHAT");
        map.put("messageId", vo.getId());
        map.put("messageType", vo.getMessageType());
        map.put("senderId", vo.getSenderId());
        map.put("senderName", vo.getSenderName());
        map.put("senderAvatar", vo.getSenderAvatar());
        map.put("targetId", vo.getTargetId());
        map.put("replyToId", vo.getReplyToId());
        map.put("replyToContent", vo.getReplyToContent());
        map.put("replyToSenderName", vo.getReplyToSenderName());
        map.put("content", vo.getContent());
        map.put("contentType", vo.getContentType());
        map.put("status", vo.getStatus());
        map.put("createdAt", vo.getCreatedAt() != null ? vo.getCreatedAt().toString() : null);
        return map;
    }
}
