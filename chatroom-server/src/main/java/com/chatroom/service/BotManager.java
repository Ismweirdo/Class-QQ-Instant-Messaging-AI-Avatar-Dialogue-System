package com.chatroom.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.chatroom.common.Constants;
import com.chatroom.mapper.BotSkillMapper;
import com.chatroom.mapper.MessageMapper;
import com.chatroom.mapper.UserMapper;
import com.chatroom.model.entity.BotSkill;
import com.chatroom.model.entity.Message;
import com.chatroom.model.entity.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
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
    private final LLMApiClient llmApiClient;

    // Per-bot semaphore for concurrency control
    private final ConcurrentHashMap<Long, Semaphore> botSemaphores = new ConcurrentHashMap<>();
    // Per-bot message queues
    private final ConcurrentHashMap<Long, LinkedList<Map<String, Object>>> botQueues = new ConcurrentHashMap<>();
    // Per-bot circuit breaker state: true = open (broken)
    private final ConcurrentHashMap<Long, Boolean> circuitBreakers = new ConcurrentHashMap<>();
    // Circuit breaker timestamps
    private final ConcurrentHashMap<Long, Long> circuitOpenTime = new ConcurrentHashMap<>();

    @Value("${bot.default-api-endpoint:https://api.deepseek.com/v1/chat/completions}")
    private String defaultApiEndpoint;

    @Value("${bot.default-api-key:}")
    private String defaultApiKey;

    @Value("${bot.default-model:deepseek-chat}")
    private String defaultModel;

    /**
     * Register a new bot user and its skill configuration.
     */
    public BotSkill registerBot(String username, String nickname, String skillName,
                                 String systemPrompt, String fewShotExamples,
                                 String emotionProfile, String languageStyle,
                                 String apiEndpoint, String apiKey, String model) {
        User bot = new User();
        bot.setUsername(username);
        bot.setPassword("BOT_" + UUID.randomUUID().toString().substring(0, 8));
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

        botSemaphores.put(bot.getId(), new Semaphore(Constants.BOT_MAX_CONCURRENCY));
        botQueues.put(bot.getId(), new LinkedList<>());
        circuitBreakers.put(bot.getId(), false);

        log.info("Bot registered: id={}, name={}, skill={}", bot.getId(), nickname, skillName);
        return skill;
    }

    /**
     * Handle an incoming message targeting a bot. Called by the WebSocket handler.
     * Returns the bot's reply message text, or null if the bot is in circuit-break state.
     */
    public String handleBotMessage(Long botUserId, Long senderId, String senderName, String content) {
        BotSkill skill = getBotSkill(botUserId);
        if (skill == null) return null;

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
        if (sem == null || !sem.tryAcquire()) {
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
        if (queue != null && !queue.isEmpty()) {
            Map<String, Object> next = queue.poll();
            // Queue processing is deferred to next message arrival
        }
    }

    private List<Map<String, String>> buildContext(Long botUserId, String senderName, String content) {
        List<Map<String, String>> messages = new ArrayList<>();
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
}
