package com.chatroom.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.chatroom.common.Constants;
import com.chatroom.mapper.BotSkillMapper;
import com.chatroom.mapper.FriendMapper;
import com.chatroom.mapper.GroupMemberMapper;
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

import jakarta.annotation.PostConstruct;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.CompletableFuture;
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
    private final GroupMemberMapper groupMemberMapper;
    private final LLMApiClient llmApiClient;
    private final SkillFolderService skillFolderService;
    private final PasswordEncoder passwordEncoder;
    private final MessageService messageService;
    private final SimpMessagingTemplate messagingTemplate;
    private final RagMemoryService ragMemoryService;
    private final EmbeddingService embeddingService;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private BotMessageQueueService queueService;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private ConversationMemoryCache memoryCache;
    @org.springframework.context.annotation.Lazy
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private LongTermMemoryService ltmService;

    @org.springframework.beans.factory.annotation.Qualifier("botTaskExecutor")
    private final java.util.concurrent.Executor botTaskExecutor;

    // Per-bot semaphore for concurrency control
    private final ConcurrentHashMap<Long, Semaphore> botSemaphores = new ConcurrentHashMap<>();
    // Per-group semaphore: only 1 bot sends to a group at a time
    private final ConcurrentHashMap<Long, Semaphore> groupSemaphores = new ConcurrentHashMap<>();
    // Per-bot active mode: botUserId -> ActiveModeConfig
    private final ConcurrentHashMap<Long, ActiveModeConfig> activeModeConfigs = new ConcurrentHashMap<>();
    // Per-group bot auto-chat: groupId -> set of botUserIds with auto-chat enabled
    private final ConcurrentHashMap<Long, Set<Long>> groupBotAutoChat = new ConcurrentHashMap<>();

    // === Optimization: Caches (LRU with TTL) ===
    private static final int SKILL_CACHE_MAX = 500;
    private static final int RESPONSE_CACHE_MAX = 2000;
    private final Map<Long, CacheEntry<BotSkill>> skillCache = Collections.synchronizedMap(
        new LinkedHashMap<>(16, 0.75f, true) {
            @Override protected boolean removeEldestEntry(Map.Entry<Long, CacheEntry<BotSkill>> e) { return size() > SKILL_CACHE_MAX; }
        });
    private final Map<String, CacheEntry<String>> responseCache = Collections.synchronizedMap(
        new LinkedHashMap<>(16, 0.75f, true) {
            @Override protected boolean removeEldestEntry(Map.Entry<String, CacheEntry<String>> e) { return size() > RESPONSE_CACHE_MAX; }
        });

    private static class CacheEntry<T> { T value; long expiresAt; CacheEntry(T v, long e) { value = v; expiresAt = e; } }

    private BotSkill getCachedSkill(Long botUserId) {
        CacheEntry<BotSkill> entry = skillCache.get(botUserId);
        if (entry != null && System.currentTimeMillis() < entry.expiresAt) return entry.value;
        BotSkill skill = botSkillMapper.selectOne(new LambdaQueryWrapper<BotSkill>()
                .eq(BotSkill::getBotUserId, botUserId));
        if (skill != null) skillCache.put(botUserId, new CacheEntry<>(skill, System.currentTimeMillis() + 60_000));
        return skill;
    }

    // Startup pre-warm: load all bot skills into cache
    @PostConstruct
    public void preWarmSkills() {
        List<BotSkill> all = botSkillMapper.selectList(null);
        for (BotSkill s : all) {
            skillCache.put(s.getBotUserId(), new CacheEntry<>(s, System.currentTimeMillis() + 60_000));
        }
        log.info("Pre-warmed {} bot skills into cache", all.size());
    }

    /** Normalize message content for cache key: trim, collapse whitespace, lower case. */
    private String normalizeContent(String content) {
        if (content == null) return "";
        return content.trim().replaceAll("\\s+", " ");
    }

    // Prompt compression: only trim when extremely long (merged imports can be large).
    // Keep head and tail so newly merged content (between old prompt and style sections) is preserved.
    private String compressPrompt(String prompt) {
        if (prompt == null || prompt.length() <= 8000) return prompt;
        int keepHead = 5000;
        int keepTail = 3000;
        return prompt.substring(0, keepHead) + "\n\n...(中间内容已压缩)...\n\n" + prompt.substring(prompt.length() - keepTail);
    }

    // Multi-provider failback: fallback endpoint if primary fails
    private static final String FALLBACK_ENDPOINT = "https://api.deepseek.com/v1/chat/completions";
    private String effectiveEndpoint(BotSkill skill) {
        String ep = skill.getApiEndpoint();
        return (ep != null && !ep.isBlank()) ? ep : (defaultApiEndpoint != null && !defaultApiEndpoint.isBlank() ? defaultApiEndpoint : FALLBACK_ENDPOINT);
    }

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
                                 String password,
                                 Integer maxTokens, Double temperature,
                                 String conversationMode, Integer memorySize,
                                 Integer ragEnabled, Integer ragTopK) {
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
        skill.setMaxTokens(maxTokens != null ? maxTokens : Constants.BOT_DEFAULT_MAX_TOKENS);
        skill.setTemperature(temperature != null ? temperature : Constants.BOT_DEFAULT_TEMPERATURE);
        skill.setConversationMode(conversationMode);
        skill.setMemorySize(memorySize != null ? memorySize : Constants.BOT_DEFAULT_MEMORY_SIZE);
        skill.setRagEnabled(ragEnabled != null ? ragEnabled : 0);
        skill.setRagTopK(ragTopK != null ? ragTopK : 3);
        skill.setStatus(Constants.BOT_STATUS_ACTIVE);
        skill.setErrorCount(0);
        skill.setLastActiveAt(LocalDateTime.now());
        botSkillMapper.insert(skill);

        // Create skill folder and generate SKILL.md on disk
        try {
            skillFolderService.createSkillFolder(skillName);
            String skillMd = skillFolderService.generateSkillMd(skill);
            skillFolderService.writeSkillMd(skillName, skillMd);
            skill.setSkillFolder(skillName);
            botSkillMapper.updateById(skill);
        } catch (Exception e) {
            log.warn("Failed to create skill folder for bot {}: {}", bot.getId(), e.getMessage());
        }

        botSemaphores.put(bot.getId(), new Semaphore(Constants.BOT_MAX_CONCURRENCY));

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
                botPassword,
                Constants.BOT_DEFAULT_MAX_TOKENS,
                Constants.BOT_DEFAULT_TEMPERATURE,
                null,
                Constants.BOT_DEFAULT_MEMORY_SIZE,
                0, 3);

        // Create skill folder and link it
        BotSkill skill = (BotSkill) result.get("skill");
        skillFolderService.createSkillFolder(skillName);
        skill.setSkillFolder(skillName);
        botSkillMapper.updateById(skill);

        return result;
    }

    /** Handle message with streaming callback. onToken receives each token. Returns full text. */
    public String handleBotMessageStream(Long botUserId, Long senderId, String senderName, String content,
                                          java.util.function.Consumer<String> onToken) {
        BotSkill skill = getBotSkill(botUserId);
        if (skill == null) return null;

        // Check cache for streaming (normalize content to improve hit rate)
        String cacheKey = botUserId + ":" + normalizeContent(content);
        CacheEntry<String> cached = responseCache.get(cacheKey);
        if (cached != null && System.currentTimeMillis() < cached.expiresAt) {
            for (char c : cached.value.toCharArray()) onToken.accept(String.valueOf(c));
            return cached.value;
        }

        botSemaphores.computeIfAbsent(botUserId, k -> new Semaphore(Constants.BOT_MAX_CONCURRENCY));
        if (queueService != null && queueService.isCircuitOpen(botUserId, Constants.BOT_CIRCUIT_BREAK_SILENCE_MS)) return null;

        Semaphore sem = botSemaphores.get(botUserId);
        if (!sem.tryAcquire()) {
            if (queueService != null) queueService.enqueue(botUserId, senderId, senderName, content);
            return null;
        }

        try {
            List<Map<String, String>> messages = buildContext(botUserId, senderId, senderName, content);
            String model = skill.getModel() != null ? skill.getModel() : defaultModel;
            String reply = llmApiClient.chatStream(
                    skill.getApiEndpoint(), skill.getApiKey(), model, messages,
                    effectiveMaxTokens(skill),
                    skill.getTemperature() != null ? skill.getTemperature() : Constants.BOT_DEFAULT_TEMPERATURE,
                    onToken);

            if (reply != null && !reply.isEmpty()) {
                skill.setErrorCount(0);
                skill.setLastActiveAt(LocalDateTime.now());
                skill.setStatus(Constants.BOT_STATUS_ACTIVE);
                responseCache.put(cacheKey, new CacheEntry<>(reply, System.currentTimeMillis() + 180_000));
                cacheConversationExchange(botUserId, senderId, senderName, content, reply);
            } else {
                recordError(botUserId, skill);
            }
            botSkillMapper.updateById(skill);
            return reply;
        } catch (Exception e) {
            log.error("Bot {} stream call failed", botUserId, e);
            recordError(botUserId, skill);
            botSkillMapper.updateById(skill);
            return null;
        } finally {
            sem.release();
            processQueue(botUserId);
        }
    }

    /**
     * Handle an incoming message targeting a bot. Called by the WebSocket handler.
     * Returns the bot's reply message text, or null if the bot is in circuit-break state.
     */
    public String handleBotMessage(Long botUserId, Long senderId, String senderName, String content) {
        BotSkill skill = getBotSkill(botUserId);
        if (skill == null) return null;

        // Lazy-init semaphore (in-memory, survives single node)
        botSemaphores.computeIfAbsent(botUserId, k -> new Semaphore(Constants.BOT_MAX_CONCURRENCY));

        // Circuit breaker check (Redis-backed if available)
        if (queueService != null && queueService.isCircuitOpen(botUserId, Constants.BOT_CIRCUIT_BREAK_SILENCE_MS)) {
            return null;
        }

        Semaphore sem = botSemaphores.get(botUserId);
        if (!sem.tryAcquire()) {
            // Busy, enqueue to Redis if available
            if (queueService != null) {
                queueService.enqueue(botUserId, senderId, senderName, content);
            }
            return null;
        }

        try {
            // Check LLM response cache (3 min TTL, normalized content key)
            String cacheKey = botUserId + ":" + normalizeContent(content);
            CacheEntry<String> cached = responseCache.get(cacheKey);
            if (cached != null && System.currentTimeMillis() < cached.expiresAt) {
                sem.release();
                processQueue(botUserId);
                return cached.value;
            }

            List<Map<String, String>> messages = buildContext(botUserId, senderId, senderName, content);
            String model = skill.getModel() != null ? skill.getModel() : defaultModel;
            String reply = llmApiClient.chat(
                    skill.getApiEndpoint(), skill.getApiKey(), model,
                    messages,
                    effectiveMaxTokens(skill),
                    skill.getTemperature() != null ? skill.getTemperature() : Constants.BOT_DEFAULT_TEMPERATURE);

            if (reply != null) {
                skill.setErrorCount(0);
                skill.setLastActiveAt(LocalDateTime.now());
                skill.setStatus(Constants.BOT_STATUS_ACTIVE);
                responseCache.put(cacheKey, new CacheEntry<>(reply, System.currentTimeMillis() + 180_000));
                cacheConversationExchange(botUserId, senderId, senderName, content, reply);
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
            if (queueService != null) queueService.setCircuitOpen(botUserId, true);
            skill.setStatus(Constants.BOT_STATUS_CIRCUIT_BROKEN);
            log.warn("Bot {} circuit breaker OPEN after {} errors", botUserId, errors);
        }
    }

    private void processQueue(Long botUserId) {
        if (queueService == null) return;
        Map<String, Object> next = queueService.dequeue(botUserId);
        if (next == null) return;

        Long senderId = (Long) next.get("senderId");
        String senderName = (String) next.get("senderName");
        String content = (String) next.get("content");

        java.util.concurrent.CompletableFuture.runAsync(() -> {
            String reply = handleBotMessage(botUserId, senderId, senderName, content);
            if (reply != null && !reply.trim().isEmpty()) {
                pushBotReply(botUserId, senderId, reply, Constants.MSG_TYPE_PRIVATE);
            }
        }, botTaskExecutor);
    }

    /** Push a bot reply, auto-splitting long messages into sequential chunks. */
    private void pushBotReply(Long botUserId, Long targetId, String content, int messageType) {
        if (content.length() <= 500) {
            sendSingleReply(botUserId, targetId, content, messageType);
            return;
        }
        List<String> chunks = splitLongMessage(content);
        for (int i = 0; i < chunks.size(); i++) {
            sendSingleReply(botUserId, targetId, (chunks.size() > 1 && i == 0 ? "📤 " : "") + chunks.get(i), messageType);
            if (i < chunks.size() - 1) {
                try { Thread.sleep(300); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
            }
        }
    }

    private List<String> splitLongMessage(String text) {
        List<String> chunks = new ArrayList<>();
        StringBuilder buf = new StringBuilder();
        int maxLen = 400;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            buf.append(c);
            if (buf.length() >= maxLen && (c == '。' || c == '！' || c == '？' || c == '\n' || c == '.' || c == '!' || c == '?')) {
                chunks.add(buf.toString().trim());
                buf.setLength(0);
            }
        }
        if (buf.length() > 0) chunks.add(buf.toString().trim());
        return chunks.isEmpty() ? List.of(text) : chunks;
    }

    private void sendSingleReply(Long botUserId, Long targetId, String content, int messageType) {
        com.chatroom.model.dto.ChatMessageDTO botDto = new com.chatroom.model.dto.ChatMessageDTO();
        botDto.setContent(content);
        botDto.setMessageType(messageType);
        botDto.setTargetId(targetId);
        botDto.setContentType(Constants.CONTENT_TYPE_TEXT);
        botDto.setClientMessageId("BQ_" + UUID.randomUUID().toString().replace("-", "").substring(0, 32));

        // Async DB write: don't block WebSocket push on DB
        final String cid = botDto.getClientMessageId();
        java.util.concurrent.CompletableFuture.runAsync(() -> {
            messageService.sendAndSaveMessage(botUserId, botDto);
        }, botTaskExecutor);

        // Push immediately via WebSocket (DB write happens async)
        Map<String, Object> botPayload = new HashMap<>();
        botPayload.put("type", "CHAT");
        botPayload.put("clientMessageId", cid);
        botPayload.put("senderId", botUserId);
        botPayload.put("targetId", targetId);
        botPayload.put("content", content);
        botPayload.put("contentType", Constants.CONTENT_TYPE_TEXT);
        botPayload.put("messageType", messageType);
        botPayload.put("createdAt", LocalDateTime.now().toString());
        User botUser = userMapper.selectById(botUserId);
        if (botUser != null) {
            botPayload.put("senderName", botUser.getNickname());
            botPayload.put("senderAvatar", botUser.getAvatar());
        }

        messagingTemplate.convertAndSendToUser(String.valueOf(targetId), "/queue/private/chat", botPayload);
        messagingTemplate.convertAndSendToUser(String.valueOf(botUserId), "/queue/private/chat", botPayload);
    }

    private List<Map<String, String>> buildContext(Long botUserId, Long senderId,
                                                    String senderName, String content) {
        List<Map<String, String>> messages = new ArrayList<>();
        BotSkill skill = getBotSkill(botUserId);
        if (skill == null) return messages;

        // 1. Build enriched system prompt from skill folder + emotion + language + mode
        String systemPrompt = buildEnrichedSystemPrompt(skill);
        if (!systemPrompt.isEmpty()) {
            messages.add(Map.of("role", "system", "content", systemPrompt));
        }

        // 2. Working memory: very recent exchanges in full detail (Level 0)
        if (senderId != null && memoryCache != null) {
            List<Map<String, String>> working = memoryCache.getWorkingMemory(botUserId, senderId);
            if (!working.isEmpty()) {
                messages.add(Map.of("role", "system",
                        "content", "以下是最近的对话上下文（工作记忆），请优先参考："));
                messages.addAll(working);
            }
        }

        // 3. Long-term memory: summarized facts & preferences (Level 2)
        if (senderId != null && ltmService != null) {
            String ltmContext = ltmService.buildLtmContext(botUserId, senderId);
            if (!ltmContext.isEmpty()) {
                messages.add(Map.of("role", "system", "content", ltmContext));
            }
        }

        // 4. Few-shot examples as alternating user/assistant messages
        if (skill.getFewShotExamples() != null && !skill.getFewShotExamples().isBlank()) {
            messages.addAll(parseFewShotMessages(skill.getFewShotExamples()));
        }

        // 5. Short-term memory: recent history from Redis or DB (Level 1)
        int memorySize = skill.getMemorySize() != null ? skill.getMemorySize() : Constants.BOT_DEFAULT_MEMORY_SIZE;
        memorySize = Math.min(memorySize, Constants.BOT_SHORT_TERM_MAX);
        if (memorySize > 0 && senderId != null) {
            // Collect existing IDs to dedup against working memory
            Set<String> existingContent = messages.stream()
                    .filter(m -> "user".equals(m.get("role")) || "assistant".equals(m.get("role")))
                    .map(m -> m.get("content"))
                    .collect(java.util.stream.Collectors.toSet());

            List<Map<String, String>> shortTerm = memoryCache != null
                    ? memoryCache.getMemory(botUserId, senderId, memorySize) : List.of();
            if (!shortTerm.isEmpty()) {
                for (Map<String, String> m : shortTerm) {
                    String c = m.get("content");
                    if (c != null && !existingContent.contains(c)) {
                        messages.add(m);
                    }
                }
            } else {
                List<MessageVO> history = messageService.getRecentMessages(botUserId, senderId, memorySize);
                for (MessageVO msg : history) {
                    String role = msg.getSenderId().equals(botUserId) ? "assistant" : "user";
                    String prefix = role.equals("user")
                            ? (msg.getSenderName() != null ? msg.getSenderName() : "用户") + "说: "
                            : "";
                    String c = prefix + msg.getContent();
                    if (!existingContent.contains(c)) {
                        messages.add(Map.of("role", role, "content", c));
                    }
                }
            }
        }

        // 6. RAG retrieval: semantically similar past messages (Level 3)
        if (skill.getRagEnabled() != null && skill.getRagEnabled() == 1 && senderId != null) {
            int ragTopK = skill.getRagTopK() != null ? skill.getRagTopK() : 3;
            try {
                List<Double> queryEmbedding = embeddingService.embed(
                        skill.getApiEndpoint(), skill.getApiKey(), skill.getModel(), content);
                List<Map<String, String>> ragResults = ragMemoryService.retrieveRelevant(
                        botUserId, senderId, content, ragTopK, queryEmbedding);

                if (!ragResults.isEmpty()) {
                    // Dedup against all existing context
                    Set<String> seen = messages.stream()
                            .map(m -> m.get("content"))
                            .collect(java.util.stream.Collectors.toSet());
                    List<Map<String, String>> filtered = ragResults.stream()
                            .filter(r -> !seen.contains(r.get("content")))
                            .toList();
                    if (!filtered.isEmpty()) {
                        messages.add(Map.of("role", "system",
                                "content", "以下是从历史对话中检索到的相关记忆，请参考这些内容来理解上下文："));
                        messages.addAll(filtered);
                    }
                }
            } catch (Exception e) {
                log.debug("RAG retrieval skipped: {}", e.getMessage());
            }
        }

        // 7. Current user message — handle image/file content
        String displayName = senderName != null ? senderName : "用户";
        String enrichedContent = enrichFileContent(content, skill.getApiEndpoint(), skill.getApiKey());
        if (enrichedContent != null) {
            messages.add(Map.of("role", "user", "content", displayName + "说: " + enrichedContent));
        } else {
            messages.add(Map.of("role", "user", "content", displayName + "说: " + content));
        }

        return messages;
    }

    /** Try to read file content for bot context. Returns enriched text, or null to use original.
     *  Supports: .txt/.md/.json (plain text), .docx/.doc (Word via POI). */
    private String enrichFileContent(String content, String apiEndpoint, String apiKey) {
        if (content == null) return null;
        // Image: [图片] /api/files/xxx.jpg
        if (content.startsWith("[图片]")) {
            String url = extractUrl(content);
            if (url != null) {
                return content + "\n（这是一张图片，请根据你的视觉能力描述或回应它。图片链接: " + url + "）";
            }
        }
        // File: [文件] name /api/files/xxx.ext
        if (content.startsWith("[文件]")) {
            String url = extractUrl(content);
            if (url != null) {
                try {
                    Path filePath = Path.of("./data/uploads", url.replace("/api/files/", ""));
                    if (!Files.exists(filePath) || Files.size(filePath) >= 500_000) {
                        return content + "\n（这是一个文件，链接: " + url + "）";
                    }
                    String name = content.replaceFirst("\\[文件\\]\\s+", "").replaceFirst("\\s+/api/files/\\S+", "");
                    String text = extractFileText(filePath);
                    if (text != null && !text.isBlank()) {
                        if (text.length() > 3000) text = text.substring(0, 3000) + "\n...(内容过长已截断)";
                        return name + " 的内容:\n```\n" + text + "\n```";
                    }
                } catch (Exception ignored) {}
                return content + "\n（这是一个文件，链接: " + url + "）";
            }
        }
        return null;
    }

    /** Extract readable text from a file based on its extension. */
    private String extractFileText(Path filePath) throws Exception {
        String fileName = filePath.getFileName().toString().toLowerCase();

        if (fileName.endsWith(".docx")) {
            return extractDocxText(filePath);
        }
        if (fileName.endsWith(".doc")) {
            return extractDocText(filePath);
        }
        // Plain text: .txt, .md, .json, .xml, .csv, .log, etc.
        return Files.readString(filePath);
    }

    /** Extract text from .docx (OOXML format) using Apache POI. */
    private String extractDocxText(Path filePath) {
        try (java.io.InputStream is = Files.newInputStream(filePath);
             org.apache.poi.xwpf.usermodel.XWPFDocument doc = new org.apache.poi.xwpf.usermodel.XWPFDocument(is)) {
            StringBuilder sb = new StringBuilder();
            for (org.apache.poi.xwpf.usermodel.XWPFParagraph para : doc.getParagraphs()) {
                String text = para.getText();
                if (text != null && !text.isBlank()) {
                    sb.append(text).append("\n");
                }
            }
            return sb.toString().trim();
        } catch (Exception e) {
            log.warn("Failed to extract .docx text: {}", e.getMessage());
            return null;
        }
    }

    /** Extract text from .doc (binary format) using Apache POI. */
    private String extractDocText(Path filePath) {
        try (java.io.InputStream is = Files.newInputStream(filePath);
             org.apache.poi.hwpf.HWPFDocument doc = new org.apache.poi.hwpf.HWPFDocument(is);
             org.apache.poi.hwpf.extractor.WordExtractor extractor = new org.apache.poi.hwpf.extractor.WordExtractor(doc)) {
            StringBuilder sb = new StringBuilder();
            for (String para : extractor.getParagraphText()) {
                if (para != null && !para.trim().isBlank()) {
                    sb.append(para.trim()).append("\n");
                }
            }
            return sb.toString().trim();
        } catch (Exception e) {
            log.warn("Failed to extract .doc text: {}", e.getMessage());
            return null;
        }
    }

    private String extractUrl(String content) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("/api/files/[^\\s]+").matcher(content);
        return m.find() ? m.group() : null;
    }

    // ==================== System Prompt Enrichment ====================

    private String buildEnrichedSystemPrompt(BotSkill skill) {
        StringBuilder sb = new StringBuilder();

        if (skill.getSkillFolder() != null && !skill.getSkillFolder().isBlank()) {
            String folderPrompt = skillFolderService.buildSystemPrompt(skill.getSkillFolder());
            if (!folderPrompt.isEmpty()) {
                sb.append(folderPrompt);
            }
        }
        // Fallback: if folder produced nothing, use DB systemPrompt
        if (sb.isEmpty() && skill.getSystemPrompt() != null && !skill.getSystemPrompt().isBlank()) {
            sb.append(skill.getSystemPrompt());
        }

        if (skill.getEmotionProfileJson() != null && !skill.getEmotionProfileJson().isBlank()) {
            String emotion = buildEmotionInstructions(skill.getEmotionProfileJson());
            if (!emotion.isEmpty()) {
                sb.append("\n\n").append(emotion);
            }
        }

        if (skill.getLanguageStyleJson() != null && !skill.getLanguageStyleJson().isBlank()) {
            String style = buildLanguageStyleInstructions(skill.getLanguageStyleJson());
            if (!style.isEmpty()) {
                sb.append("\n\n").append(style);
            }
        }

        if (skill.getConversationMode() != null && !skill.getConversationMode().isBlank()) {
            String modePrompt = getConversationModePrompt(skill.getConversationMode());
            if (!modePrompt.isEmpty()) {
                sb.append("\n\n").append(modePrompt);
            }
        }

        // If system prompt contains supplement sections, add conflict resolution rule
        String built = sb.toString();
        if (built.contains("## 补充设定")) {
            sb.append("\n\n【信息优先级规则】补充设定中带日期的信息是用户后续更新。"
                    + "如果补充设定与基础设定冲突（如基础设定说\"初入职场\"但补充设定说\"大三在读\"），"
                    + "以日期最新的补充设定为准。补充设定的优先级高于基础设定。");
        }

        return compressPrompt(sb.toString().trim());
    }

    private String buildEmotionInstructions(String json) {
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            @SuppressWarnings("unchecked")
            Map<String, Object> profile = mapper.readValue(json, Map.class);
            if (profile.isEmpty()) return "";

            StringBuilder sb = new StringBuilder("【情感设定】\n");
            if (profile.containsKey("baseline")) {
                sb.append("基础情感基调：").append(profile.get("baseline")).append("\n");
            }
            if (profile.containsKey("description")) {
                sb.append(profile.get("description")).append("\n");
            }
            Object distObj = profile.get("distribution");
            if (distObj instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> dist = (Map<String, Object>) distObj;
                sb.append("情感分布：");
                Map<String, String> emotionNames = Map.of(
                    "joy", "喜悦", "care", "关心", "sad", "悲伤",
                    "surprise", "惊讶", "anger", "愤怒", "fear", "恐惧"
                );
                List<String> parts = new java.util.ArrayList<>();
                for (Map.Entry<String, Object> e : dist.entrySet()) {
                    String cnName = emotionNames.getOrDefault(e.getKey(), e.getKey());
                    Object val = e.getValue();
                    String pct;
                    if (val instanceof Number) {
                        double d = ((Number) val).doubleValue();
                        pct = d < 1.0 ? Math.round(d * 100) + "%" : Math.round(d) + "%";
                    } else {
                        pct = String.valueOf(val);
                    }
                    parts.add(cnName + pct);
                }
                sb.append(String.join("，", parts)).append("。请按此分布自然表达情感。\n");
            }
            return sb.toString().trim();
        } catch (Exception e) {
            log.warn("Failed to parse emotion profile JSON", e);
            return "";
        }
    }

    private String buildLanguageStyleInstructions(String json) {
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            @SuppressWarnings("unchecked")
            Map<String, Object> style = mapper.readValue(json, Map.class);
            if (style.isEmpty()) return "";

            StringBuilder sb = new StringBuilder("【语言风格】\n");
            appendStyleSection(sb, style, "tone_signature", "语气特征");
            appendStyleSection(sb, style, "rhythm_profile", "节奏特征");
            appendStyleSection(sb, style, "discourse_tactics", "话语策略");
            appendStyleSection(sb, style, "topic_preferences", "话题偏好");
            appendStyleSection(sb, style, "safety_boundaries", "安全边界");
            appendStyleSection(sb, style, "repair_strategy", "修复策略");
            appendStyleSection(sb, style, "example_guidelines", "示例准则");

            // Top-level style descriptors
            for (String key : List.of("avg_sentence_length", "emoji_usage", "question_ratio",
                    "exclaim_ratio", "slang_ratio", "habit_openings", "habit_endings")) {
                if (style.containsKey(key)) {
                    Object val = style.get(key);
                    sb.append(key).append(": ").append(val).append("\n");
                }
            }

            return sb.toString().trim();
        } catch (Exception e) {
            log.warn("Failed to parse language style JSON", e);
            return "";
        }
    }

    private void appendStyleSection(StringBuilder sb, Map<String, Object> style,
                                     String key, String label) {
        Object val = style.get(key);
        if (val instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> section = (Map<String, Object>) val;
            sb.append(label).append("：");
            List<String> parts = new java.util.ArrayList<>();
            for (Map.Entry<String, Object> e : section.entrySet()) {
                parts.add(e.getKey() + "=" + e.getValue());
            }
            sb.append(String.join("，", parts)).append("\n");
        }
    }

    private String getConversationModePrompt(String mode) {
        return switch (mode.toLowerCase()) {
            case "casual" -> "【对话模式：轻松闲聊】保持轻松自然的对话风格，像朋友一样聊天。如果发现自己在重复表达，换个角度或话题。";
            case "roleplay" -> "【对话模式：沉浸角色扮演】严格遵循角色设定，始终保持在角色中。可以细致描写动作、神态和心理活动。用第一人称视角扮演，适当使用星号描述动作（如 *微微一笑*）。避免重复相同的动作描写。";
            case "assistant" -> "【对话模式：专业助手】以专业、有帮助的方式回答问题。提供准确、有用的信息。语气正式但不失亲切。如果回答过长，分点说明。";
            default -> "";
        };
    }

    private List<Map<String, String>> parseFewShotMessages(String json) {
        List<Map<String, String>> result = new ArrayList<>();
        if (json == null || json.isBlank()) return result;
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            @SuppressWarnings("unchecked")
            List<Map<String, String>> examples = mapper.readValue(json, List.class);
            for (Map<String, String> ex : examples) {
                if (ex.containsKey("user")) {
                    result.add(Map.of("role", "user", "content", ex.get("user")));
                }
                if (ex.containsKey("assistant")) {
                    result.add(Map.of("role", "assistant", "content", ex.get("assistant")));
                }
            }
        } catch (Exception e) {
            log.warn("Failed to parse few-shot examples JSON", e);
        }
        return result;
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
        if (queueService != null) queueService.clearQueue(botUserId);
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
        }
        botSkillMapper.delete(new LambdaQueryWrapper<BotSkill>()
                .eq(BotSkill::getBotUserId, botUserId));

        // Delete the user record
        userMapper.deleteById(botUserId);

        // Clean up in-memory state
        botSemaphores.remove(botUserId);

        // Clean up Redis keys if available
        if (queueService != null) queueService.clearQueue(botUserId);

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

    public List<Map<String, Object>> getBotsSimple() {
        List<BotSkill> skills = getAllBots();
        List<Map<String, Object>> result = new ArrayList<>();
        for (BotSkill s : skills) {
            User user = userMapper.selectById(s.getBotUserId());
            if (user != null) {
                result.add(Map.of(
                    "botUserId", s.getBotUserId(),
                    "nickname", user.getNickname(),
                    "skillName", s.getSkillName(),
                    "skillId", s.getId(),
                    "conversationMode", s.getConversationMode() != null ? s.getConversationMode() : ""
                ));
            }
        }
        return result;
    }

    public int getOnlineBotCount() {
        return (int) getActiveBots().size();
    }

    public BotSkill getBotSkill(Long botUserId) {
        return getCachedSkill(botUserId);
    }

    /** Invalidate skill cache entry (call after external update). */
    public void refreshSkillCache(Long botUserId) {
        skillCache.remove(botUserId);
    }

    /** Effective max_tokens: if stored value <= old default (200), use new default (4096). */
    private int effectiveMaxTokens(BotSkill skill) {
        int val = skill.getMaxTokens() != null ? skill.getMaxTokens() : Constants.BOT_DEFAULT_MAX_TOKENS;
        return val <= 200 ? Constants.BOT_DEFAULT_MAX_TOKENS : val;
    }

    /** Update a bot's skill configuration (apiEndpoint, apiKey, model, etc.). */
    public String getBotAvatar(Long botUserId) {
        User bot = userMapper.selectById(botUserId);
        return bot != null && bot.getAvatar() != null ? bot.getAvatar() : "";
    }

    public void updateBotSkill(BotSkill skill) {
        botSkillMapper.updateById(skill);
        log.info("Bot {} skill updated: endpoint={}, model={}", skill.getBotUserId(),
                skill.getApiEndpoint(), skill.getModel());
    }

    public Map<String, Object> getRagStats(Long botUserId) {
        return ragMemoryService.getStats(botUserId);
    }

    public void clearRagMemory(Long botUserId) {
        ragMemoryService.clearMemory(botUserId, null);
        log.info("RAG memory cleared for bot {}", botUserId);
    }

    /** Get/set group bot auto-chat config. */
    public Map<String, Object> getGroupAutoChatConfig(Long groupId) {
        Set<Long> enabled = groupBotAutoChat.getOrDefault(groupId, Set.of());
        List<Map<String, Object>> bots = new ArrayList<>();
        for (Long botId : enabled) {
            User u = userMapper.selectById(botId);
            if (u != null) bots.add(Map.of("botUserId", botId, "nickname", u.getNickname()));
        }
        return Map.of("groupId", groupId, "enabledBots", bots);
    }

    public void setGroupAutoChatConfig(Long groupId, Set<Long> botUserIds) {
        groupBotAutoChat.put(groupId, ConcurrentHashMap.newKeySet());
        groupBotAutoChat.get(groupId).addAll(botUserIds);
        log.info("Group {} auto-chat: {} bots enabled", groupId, botUserIds.size());
    }

    public void updateBotAvatar(Long botUserId, String avatarUrl) {
        User bot = userMapper.selectById(botUserId);
        if (bot != null) {
            bot.setAvatar(avatarUrl);
            userMapper.updateById(bot);
            log.info("Bot {} avatar updated", botUserId);
        }
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

    /** Scheduled task: poll Redis queues for pending messages (handles restarted messages). */
    @Scheduled(fixedDelay = 5_000)
    public void pollRedisQueues() {
        if (queueService == null) return;
        try {
            for (Map<String, Object> bot : getBotsSimple()) {
                Long botUserId = (Long) bot.get("botUserId");
                try {
                    Map<String, Object> msg = queueService.dequeue(botUserId);
                    if (msg != null) {
                        Long senderId = (Long) msg.get("senderId");
                        String senderName = (String) msg.get("senderName");
                        String content = (String) msg.get("content");
                        java.util.concurrent.CompletableFuture.runAsync(() -> {
                            String reply = handleBotMessage(botUserId, senderId, senderName, content);
                            if (reply != null && !reply.trim().isEmpty()) {
                                pushBotReply(botUserId, senderId, reply, Constants.MSG_TYPE_PRIVATE);
                            }
                        }, botTaskExecutor);
                    }
                } catch (Exception e) {
                    // Skip individual bot failures
                }
            }
        } catch (Exception e) {
            // Redis not available — queues will be handled in-memory
        }
    }

    /** Get queue statistics for monitoring. */
    public Map<String, Object> getQueueStats() {
        if (queueService == null) {
            return Map.of("totalQueued", 0, "bots", List.of(), "redisAvailable", false);
        }
        try {
            List<BotSkill> bots = getAllBots();
            long totalQueued = 0;
            List<Map<String, Object>> perBot = new ArrayList<>();
            for (BotSkill s : bots) {
                long size = queueService.queueSize(s.getBotUserId());
                if (size > 0) {
                    totalQueued += size;
                    User user = userMapper.selectById(s.getBotUserId());
                    perBot.add(Map.of(
                        "botUserId", s.getBotUserId(),
                        "nickname", user != null ? user.getNickname() : "?",
                        "queueSize", size
                    ));
                }
            }
            return Map.of("totalQueued", totalQueued, "bots", perBot, "redisAvailable", true);
        } catch (Exception e) {
            return Map.of("totalQueued", 0, "bots", List.of(), "redisAvailable", false,
                    "note", "Redis not available, using in-memory fallback");
        }
    }

    /** Scheduled task: every 10 seconds, check if any active-mode bot should send a message. */
    @Scheduled(fixedRate = 5_000)
    public void processActiveBots() {
        long now = System.currentTimeMillis();
        for (Map.Entry<Long, ActiveModeConfig> e : activeModeConfigs.entrySet()) {
            Long botUserId = e.getKey();
            ActiveModeConfig config = e.getValue();
            if (!config.enabled) continue;

            long elapsed = now - config.lastSentTime;
            if (elapsed < config.intervalSeconds * 1000L) continue;

            // Check circuit breaker (Redis-backed if available)
            if (queueService != null && queueService.isCircuitOpen(botUserId, Constants.BOT_CIRCUIT_BREAK_SILENCE_MS)) continue;

            // Try to send a message (group or private)
            try {
                // Check if bot is in any groups
                List<com.chatroom.model.entity.GroupMember> memberships = groupMemberMapper.selectList(
                    new LambdaQueryWrapper<com.chatroom.model.entity.GroupMember>()
                        .eq(com.chatroom.model.entity.GroupMember::getUserId, botUserId));
                // Pick a random group where this bot has auto-chat enabled
                List<com.chatroom.model.entity.GroupMember> autoChatGroups = memberships.stream()
                    .filter(m -> {
                        Set<Long> enabled = groupBotAutoChat.get(m.getGroupId());
                        return enabled != null && enabled.contains(botUserId);
                    }).toList();
                if (!autoChatGroups.isEmpty()) {
                    sendActiveGroupMessage(botUserId, config, autoChatGroups);
                } else {
                    sendActiveMessage(botUserId, config);
                }
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
        String systemPrompt = buildEnrichedSystemPrompt(skill);
        if (systemPrompt.isEmpty()) {
            systemPrompt = "你是" + botName + "。以真实自然的风格交流。如果发现自己开始重复，换个话题或表达方式。";
        }

        List<Map<String, String>> activeMessages = new ArrayList<>();
        activeMessages.add(Map.of("role", "system", "content", systemPrompt));

        if (skill.getFewShotExamples() != null && !skill.getFewShotExamples().isBlank()) {
            activeMessages.addAll(parseFewShotMessages(skill.getFewShotExamples()));
        }

        activeMessages.add(Map.of("role", "user", "content",
                "（现在请你主动发起一个话题，像普通朋友一样打个招呼。不要说你收到了指令。如果不知道该说什么，分享一个随意的想法或观察。）"));

        String model = skill.getModel() != null ? skill.getModel() : defaultModel;
        String message;
        try {
            message = llmApiClient.chat(
                    skill.getApiEndpoint(), skill.getApiKey(), model,
                    activeMessages,
                    effectiveMaxTokens(skill),
                    skill.getTemperature() != null ? skill.getTemperature() : Constants.BOT_DEFAULT_TEMPERATURE);
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
        dto.setClientMessageId("A" + UUID.randomUUID().toString().replace("-", "").substring(0, 32));

        MessageVO msgVO = messageService.sendAndSaveMessage(botUserId, dto);
        Map<String, Object> payload = buildWsPayload(msgVO);

        messagingTemplate.convertAndSendToUser(
                String.valueOf(targetUserId), "/queue/private/chat", payload);
        messagingTemplate.convertAndSendToUser(
                String.valueOf(botUserId), "/queue/private/chat", payload);

        log.info("Bot {} (active) sent to {}: {}", botUserId, targetUserId, message.trim().substring(0, Math.min(30, message.trim().length())));
    }

    private void sendActiveGroupMessage(Long botUserId, ActiveModeConfig config,
                                         List<com.chatroom.model.entity.GroupMember> memberships) {
        BotSkill skill = getBotSkill(botUserId);
        if (skill == null) return;

        // Pick a random group the bot is in
        var member = memberships.get(new Random().nextInt(memberships.size()));
        Long groupId = member.getGroupId();

        // Find other bots in the same group to @mention
        List<com.chatroom.model.entity.GroupMember> groupMembers = groupMemberMapper.selectList(
            new LambdaQueryWrapper<com.chatroom.model.entity.GroupMember>()
                .eq(com.chatroom.model.entity.GroupMember::getGroupId, groupId));
        List<Long> otherBotIds = new ArrayList<>();
        for (var gm : groupMembers) {
            if (!gm.getUserId().equals(botUserId)) {
                User u = userMapper.selectById(gm.getUserId());
                if (u != null && u.getIsBot() != null && u.getIsBot() == 1) {
                    otherBotIds.add(gm.getUserId());
                }
            }
        }

        // Generate message with group context
        User botUser = userMapper.selectById(botUserId);
        String botName = botUser != null ? botUser.getNickname() : "bot";
        String systemPrompt = buildEnrichedSystemPrompt(skill);
        if (systemPrompt.isEmpty()) {
            systemPrompt = "你是" + botName + "。以真实自然的风格交流。你在一个群聊里。";
        }

        List<Map<String, String>> activeMessages = new ArrayList<>();
        activeMessages.add(Map.of("role", "system", "content", systemPrompt
                + "\n你现在在一个群聊里，请主动发起话题。避免重复自己说过的话。"));

        // Inject recent group conversation history so messages are contextually relevant
        try {
            List<MessageVO> recentGroupMsgs = messageService.getRecentGroupMessages(groupId, 8);
            if (!recentGroupMsgs.isEmpty()) {
                StringBuilder ctx = new StringBuilder("最近群聊记录：\n");
                for (MessageVO m : recentGroupMsgs) {
                    String name = m.getSenderName() != null ? m.getSenderName() : "成员";
                    ctx.append(name).append("：").append(m.getContent()).append("\n");
                }
                activeMessages.add(Map.of("role", "system", "content", ctx.toString()));
            }
        } catch (Exception ignored) {}

        if (skill.getFewShotExamples() != null && !skill.getFewShotExamples().isBlank()) {
            activeMessages.addAll(parseFewShotMessages(skill.getFewShotExamples()));
        }

        // If there's another bot in the group, ask to @mention them
        String targetMention = "";
        if (!otherBotIds.isEmpty()) {
            User otherBot = userMapper.selectById(otherBotIds.get(new Random().nextInt(otherBotIds.size())));
            if (otherBot != null) {
                targetMention = "@" + (otherBot.getNickname() != null ? otherBot.getNickname() : otherBot.getUsername());
                activeMessages.add(Map.of("role", "user", "content",
                    "（群聊记录如上。请基于最近的话题主动发起一条消息。可以在消息中包含 "
                    + targetMention + " 和对方互动。不要说你收到了指令。直接当消息发。）"));
            }
        } else {
            activeMessages.add(Map.of("role", "user", "content",
                "（群聊记录如上。请基于最近的话题主动发起一条消息，像朋友一样参与讨论。不要说你收到了指令。直接当消息发。）"));
        }

        String model = skill.getModel() != null ? skill.getModel() : defaultModel;
        String message;
        try {
            message = llmApiClient.chat(skill.getApiEndpoint(), skill.getApiKey(), model,
                    activeMessages, effectiveMaxTokens(skill),
                    skill.getTemperature() != null ? skill.getTemperature() : Constants.BOT_DEFAULT_TEMPERATURE);
            if (message == null || message.trim().isEmpty()) return;
        } catch (Exception ex) {
            log.warn("Bot {} active group LLM call failed: {}", botUserId, ex.getMessage());
            return;
        }

        // Group rate limiting: acquire semaphore or skip
        Semaphore groupSem = groupSemaphores.computeIfAbsent(groupId, k -> new Semaphore(1));
        if (!groupSem.tryAcquire()) return;
        try {
            pushGroupMessage(botUserId, groupId, message.trim());
        } finally {
            groupSem.release();
        }
    }

    private void pushGroupMessage(Long botUserId, Long groupId, String message) {
        ChatMessageDTO dto = new ChatMessageDTO();
        dto.setContent(message);
        dto.setMessageType(Constants.MSG_TYPE_GROUP);
        dto.setTargetId(groupId);
        dto.setContentType(Constants.CONTENT_TYPE_TEXT);
        dto.setClientMessageId("AG_" + UUID.randomUUID().toString().replace("-", "").substring(0, 31));

        // Async DB write
        CompletableFuture.runAsync(() -> messageService.sendAndSaveMessage(botUserId, dto), botTaskExecutor);

        Map<String, Object> payload = new HashMap<>();
        payload.put("type", "CHAT");
        payload.put("clientMessageId", dto.getClientMessageId());
        payload.put("senderId", botUserId);
        payload.put("targetId", groupId);
        payload.put("content", message);
        payload.put("contentType", Constants.CONTENT_TYPE_TEXT);
        payload.put("messageType", Constants.MSG_TYPE_GROUP);
        payload.put("createdAt", LocalDateTime.now().toString());
        User bu = userMapper.selectById(botUserId);
        if (bu != null) { payload.put("senderName", bu.getNickname()); payload.put("senderAvatar", bu.getAvatar()); }

        messagingTemplate.convertAndSend("/topic/group/" + groupId, payload);
        log.info("Bot {} (active group) sent to group {}: {}", botUserId, groupId, message.substring(0, Math.min(30, message.length())));
    }

    private void cacheConversationExchange(Long botUserId, Long senderId,
                                             String senderName, String userContent, String botReply) {
        if (memoryCache != null) {
            try {
                // Level 0: Working memory — store the complete exchange
                memoryCache.addToWorkingMemory(botUserId, senderId, userContent, botReply);

                // Level 1: Short-term memory — individual messages
                int maxSize = Constants.BOT_SHORT_TERM_MAX;
                com.chatroom.model.vo.MessageVO userMsg = new com.chatroom.model.vo.MessageVO();
                userMsg.setSenderId(senderId);
                userMsg.setSenderName(senderName);
                userMsg.setContent(userContent);
                memoryCache.addMessage(botUserId, senderId, userMsg, maxSize);

                com.chatroom.model.vo.MessageVO botMsg = new com.chatroom.model.vo.MessageVO();
                botMsg.setSenderId(botUserId);
                botMsg.setContent(botReply);
                memoryCache.addMessage(botUserId, senderId, botMsg, maxSize);
            } catch (Exception e) {
                log.warn("Failed to cache conversation exchange: {}", e.getMessage());
            }
        }

        // Level 2: Trigger memory consolidation if short-term exceeds threshold
        maybeConsolidateMemory(botUserId, senderId);

        // Level 3: Async RAG embedding storage
        BotSkill skill = getBotSkill(botUserId);
        if (skill != null && skill.getRagEnabled() != null && skill.getRagEnabled() == 1) {
            java.util.concurrent.CompletableFuture.runAsync(() -> {
                try {
                    List<Double> userEmb = embeddingService.embed(
                            skill.getApiEndpoint(), skill.getApiKey(), skill.getModel(), userContent);
                    ragMemoryService.storeEmbedding(botUserId, senderId, 0L, userContent, userEmb);

                    List<Double> botEmb = embeddingService.embed(
                            skill.getApiEndpoint(), skill.getApiKey(), skill.getModel(), botReply);
                    ragMemoryService.storeEmbedding(botUserId, senderId, 0L, botReply, botEmb);
                } catch (Exception e) {
                    log.debug("RAG embedding storage skipped: {}", e.getMessage());
                }
            }, botTaskExecutor);
        }
    }

    /**
     * Check if short-term memory needs consolidation into long-term memory.
     * If exceeding threshold, trim oldest messages and async summarize via LLM.
     */
    private void maybeConsolidateMemory(Long botUserId, Long senderId) {
        if (memoryCache == null || ltmService == null) return;
        try {
            long count = memoryCache.getMemoryCount(botUserId, senderId);
            if (count > Constants.BOT_LTM_CONSOLIDATION_THRESHOLD) {
                // Trim oldest half and send to LLM for summarization
                int keepSize = Constants.BOT_LTM_CONSOLIDATION_THRESHOLD / 2;
                List<Map<String, String>> trimmed = memoryCache.trimMemory(botUserId, senderId, keepSize);
                if (!trimmed.isEmpty()) {
                    BotSkill skill = getBotSkill(botUserId);
                    if (skill != null) {
                        java.util.concurrent.CompletableFuture.runAsync(() -> {
                            ltmService.consolidateFromMessages(botUserId, senderId, trimmed,
                                    skill.getApiEndpoint(), skill.getApiKey(), skill.getModel());
                        }, botTaskExecutor);
                    }
                }
            }
        } catch (Exception e) {
            log.debug("Memory consolidation check skipped: {}", e.getMessage());
        }
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
