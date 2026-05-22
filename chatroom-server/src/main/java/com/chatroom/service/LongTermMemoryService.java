package com.chatroom.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.chatroom.common.Constants;
import com.chatroom.mapper.LongTermMemoryMapper;
import com.chatroom.model.entity.LongTermMemory;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * Long-term memory service: stores LLM-summarized facts, preferences, and conversation
 * digests for persistent bot-user memory across sessions.
 *
 * Redis cache key: conv:ltm:{min}:{max} -> JSON array of memory entries
 * DB table: bot_long_term_memory
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LongTermMemoryService {

    private static final String LTM_CACHE_PREFIX = "conv:ltm:";
    private static final int LTM_CACHE_TTL_MINUTES = 120;

    private final LongTermMemoryMapper ltmMapper;
    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${bot.default-api-endpoint:}")
    private String defaultApiEndpoint;
    @Value("${bot.default-api-key:}")
    private String defaultApiKey;
    @Value("${bot.default-model:deepseek-chat}")
    private String defaultModel;

    // Inject lazily to avoid circular dependency
    @org.springframework.context.annotation.Lazy
    @org.springframework.beans.factory.annotation.Autowired
    private LLMApiClient llmApiClient;

    private volatile boolean redisAvailable = true;
    private volatile long lastRedisCheck = 0;
    private static final long REDIS_CHECK_INTERVAL_MS = 10_000;

    private boolean isRedisAvailable() {
        long now = System.currentTimeMillis();
        if (now - lastRedisCheck < REDIS_CHECK_INTERVAL_MS) {
            return redisAvailable;
        }
        try {
            stringRedisTemplate.getConnectionFactory().getConnection().ping();
            redisAvailable = true;
        } catch (Throwable e) {
            redisAvailable = false;
            log.warn("Redis unavailable, LTM cache disabled");
        }
        lastRedisCheck = now;
        return redisAvailable;
    }

    private String buildCacheKey(Long userId1, Long userId2) {
        long min = Math.min(userId1, userId2);
        long max = Math.max(userId1, userId2);
        return LTM_CACHE_PREFIX + min + ":" + max;
    }

    /** Store a new long-term memory entry. */
    public LongTermMemory storeMemory(Long botUserId, Long userId, String type,
                                       String content, int importance, String sourceMsgIds) {
        LongTermMemory mem = new LongTermMemory();
        mem.setBotUserId(botUserId);
        mem.setUserId(userId);
        mem.setMemoryType(type);
        mem.setContent(content);
        mem.setImportance(Math.max(1, Math.min(5, importance)));
        mem.setSourceMessageIds(sourceMsgIds);
        mem.setCreatedAt(LocalDateTime.now());
        ltmMapper.insert(mem);

        // Prune if exceeding max per pair
        LambdaQueryWrapper<LongTermMemory> countWrapper = new LambdaQueryWrapper<LongTermMemory>()
                .eq(LongTermMemory::getBotUserId, botUserId)
                .eq(LongTermMemory::getUserId, userId);
        Long count = ltmMapper.selectCount(countWrapper);
        if (count != null && count > Constants.BOT_LTM_MAX_PER_PAIR) {
            LambdaQueryWrapper<LongTermMemory> deleteWrapper = new LambdaQueryWrapper<LongTermMemory>()
                    .eq(LongTermMemory::getBotUserId, botUserId)
                    .eq(LongTermMemory::getUserId, userId)
                    .orderByAsc(LongTermMemory::getId)
                    .last("LIMIT " + (count - Constants.BOT_LTM_MAX_PER_PAIR));
            ltmMapper.delete(deleteWrapper);
        }

        invalidateLtmCache(botUserId, userId);
        log.debug("Stored LTM: type={}, importance={}, bot={}, user={}", type, importance, botUserId, userId);
        return mem;
    }

    /** Get long-term memory entries for a bot-user pair (Redis cache first, DB fallback).
     *  Uses SETNX lock to prevent cache stampede — only one thread rebuilds on miss. */
    public List<Map<String, Object>> getMemories(Long botUserId, Long userId, int limit) {
        // Try Redis cache first
        if (isRedisAvailable()) {
            String key = buildCacheKey(botUserId, userId);
            String cached = stringRedisTemplate.opsForValue().get(key);
            if (cached != null && !cached.isEmpty()) {
                try {
                    List<Map<String, Object>> list = objectMapper.readValue(cached,
                            new TypeReference<List<Map<String, Object>>>() {});
                    return list.stream().limit(limit).toList();
                } catch (Exception e) {
                    log.warn("Failed to parse LTM cache", e);
                }
            }

            // Cache miss — try stampede lock to rebuild
            String lockKey = key + ":lock";
            Boolean acquired = stringRedisTemplate.opsForValue()
                    .setIfAbsent(lockKey, "1", 5, TimeUnit.SECONDS);

            if (Boolean.TRUE.equals(acquired)) {
                // We hold the lock: rebuild cache
                try {
                    List<LongTermMemory> entries = ltmMapper.selectList(
                            new LambdaQueryWrapper<LongTermMemory>()
                                    .eq(LongTermMemory::getBotUserId, botUserId)
                                    .eq(LongTermMemory::getUserId, userId)
                                    .orderByDesc(LongTermMemory::getImportance)
                                    .orderByDesc(LongTermMemory::getCreatedAt)
                                    .last("LIMIT " + Math.min(limit, Constants.BOT_LTM_MAX_PER_PAIR)));

                    List<Map<String, Object>> result = entries.stream().map(this::toMap).toList();
                    if (!result.isEmpty()) {
                        String json = objectMapper.writeValueAsString(result);
                        stringRedisTemplate.opsForValue().set(key, json, LTM_CACHE_TTL_MINUTES, TimeUnit.MINUTES);
                    }
                    return result;
                } catch (Exception e) {
                    log.warn("Failed to rebuild LTM cache", e);
                } finally {
                    stringRedisTemplate.delete(lockKey);
                }
            }
            // Lock not acquired: another thread is rebuilding, read DB directly
        }

        // DB fallback (no Redis or lock not acquired)
        List<LongTermMemory> entries = ltmMapper.selectList(
                new LambdaQueryWrapper<LongTermMemory>()
                        .eq(LongTermMemory::getBotUserId, botUserId)
                        .eq(LongTermMemory::getUserId, userId)
                        .orderByDesc(LongTermMemory::getImportance)
                        .orderByDesc(LongTermMemory::getCreatedAt)
                        .last("LIMIT " + Math.min(limit, Constants.BOT_LTM_MAX_PER_PAIR)));

        return entries.stream().map(this::toMap).toList();
    }

    /** Build LTM context snippet for injection into LLM system prompt. */
    public String buildLtmContext(Long botUserId, Long userId) {
        List<Map<String, Object>> memories = getMemories(botUserId, userId, Constants.BOT_LTM_CONTEXT_LIMIT);
        if (memories.isEmpty()) return "";

        StringBuilder sb = new StringBuilder();
        sb.append("【关于该用户的长期记忆】\n");
        int i = 1;
        for (Map<String, Object> m : memories) {
            String type = String.valueOf(m.getOrDefault("memoryType", "summary"));
            String content = String.valueOf(m.getOrDefault("content", ""));
            if (content.isBlank()) continue;
            String prefix = switch (type) {
                case "fact" -> "[事实] ";
                case "preference" -> "[偏好] ";
                default -> "[摘要] ";
            };
            sb.append(i++).append(". ").append(prefix).append(content).append("\n");
        }
        return sb.toString().trim();
    }

    /**
     * Consolidate short-term conversation into long-term memory via LLM summarization.
     * Called asynchronously when short-term memory exceeds threshold.
     */
    public void consolidateFromMessages(Long botUserId, Long userId,
                                         List<Map<String, String>> messages,
                                         String apiEndpoint, String apiKey, String model) {
        if (messages == null || messages.size() < 5) return;
        if (llmApiClient == null) {
            log.debug("LLMApiClient not available, skipping consolidation");
            return;
        }

        String ep = (apiEndpoint != null && !apiEndpoint.isBlank()) ? apiEndpoint : defaultApiEndpoint;
        String key = (apiKey != null && !apiKey.isBlank()) ? apiKey : defaultApiKey;
        String mdl = (model != null && !model.isBlank()) ? model : defaultModel;

        // Build conversation transcript
        StringBuilder transcript = new StringBuilder();
        for (Map<String, String> msg : messages) {
            String role = "user".equals(msg.get("role")) ? "用户" : "AI";
            transcript.append(role).append(": ").append(msg.get("content")).append("\n");
        }

        String systemPrompt = "你是一个记忆整理助手。从以下对话中提取关键信息，输出 JSON。\n"
                + "提取三类记忆：\n"
                + "1. facts: 关于用户的事实信息（姓名、年龄、职业、经历等）\n"
                + "2. preferences: 用户的偏好（喜欢/不喜欢什么、习惯等）\n"
                + "3. summary: 对话主题摘要（1-2句话概括）\n\n"
                + "输出格式（纯 JSON，不要有其他文字）：\n"
                + "{\"facts\": [{\"content\": \"...\", \"importance\": 3}, ...],\n"
                + " \"preferences\": [{\"content\": \"...\", \"importance\": 3}, ...],\n"
                + " \"summary\": \"一句话摘要\"}\n\n"
                + "importance 范围 1-5，5=极其重要（如姓名、重大事件），1=琐碎细节。只提取有价值的信息，不要编造。如果没有可提取的内容，返回空数组。";

        List<Map<String, String>> llmMessages = List.of(
                Map.of("role", "system", "content", systemPrompt),
                Map.of("role", "user", "content", "请分析以下对话并提取记忆：\n\n" + transcript.toString())
        );

        try {
            String response = llmApiClient.chat(ep, key, mdl, llmMessages, 1024, 0.3);
            if (response == null || response.isBlank()) return;

            // Parse JSON response (may be wrapped in markdown code block)
            String json = response.trim();
            if (json.startsWith("```")) {
                json = json.replaceAll("```json\\s*", "").replaceAll("```\\s*", "").trim();
            }
            Map<String, Object> parsed = objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});

            // Store facts
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> facts = (List<Map<String, Object>>) parsed.getOrDefault("facts", List.of());
            for (Map<String, Object> f : facts) {
                String content = String.valueOf(f.getOrDefault("content", ""));
                int importance = f.get("importance") instanceof Number n ? n.intValue() : 1;
                if (!content.isBlank()) {
                    storeMemory(botUserId, userId, "fact", content, importance, null);
                }
            }

            // Store preferences
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> prefs = (List<Map<String, Object>>) parsed.getOrDefault("preferences", List.of());
            for (Map<String, Object> p : prefs) {
                String content = String.valueOf(p.getOrDefault("content", ""));
                int importance = p.get("importance") instanceof Number n ? n.intValue() : 1;
                if (!content.isBlank()) {
                    storeMemory(botUserId, userId, "preference", content, importance, null);
                }
            }

            // Store summary
            String summary = String.valueOf(parsed.getOrDefault("summary", ""));
            if (!summary.isBlank() && !"null".equals(summary)) {
                storeMemory(botUserId, userId, "summary", summary, 3, null);
            }

            log.info("Consolidated LTM for bot={}, user={}: {} facts, {} prefs, 1 summary",
                    botUserId, userId, facts.size(), prefs.size());
        } catch (Exception e) {
            log.warn("LTM consolidation failed for bot={}, user={}: {}", botUserId, userId, e.getMessage());
        }
    }

    /** Clear long-term memory for a pair, or all for a bot if userId is null. */
    public void clearMemory(Long botUserId, Long userId) {
        LambdaQueryWrapper<LongTermMemory> wrapper = new LambdaQueryWrapper<LongTermMemory>()
                .eq(LongTermMemory::getBotUserId, botUserId);
        if (userId != null) {
            wrapper.eq(LongTermMemory::getUserId, userId);
        }
        ltmMapper.delete(wrapper);
        if (userId != null) {
            invalidateLtmCache(botUserId, userId);
        }
        // If clearing all for bot, also clear all Redis keys (best-effort)
        if (userId == null && isRedisAvailable()) {
            try {
                var keys = stringRedisTemplate.keys(LTM_CACHE_PREFIX + botUserId + ":*");
                if (keys != null && !keys.isEmpty()) stringRedisTemplate.delete(keys);
            } catch (Exception ignored) {}
        }
        log.info("Cleared LTM for bot={}, user={}", botUserId, userId != null ? userId : "ALL");
    }

    /** Get LTM stats for a bot. */
    public Map<String, Object> getStats(Long botUserId) {
        Long count = ltmMapper.selectCount(
                new LambdaQueryWrapper<LongTermMemory>().eq(LongTermMemory::getBotUserId, botUserId));
        return Map.of("botUserId", botUserId, "longTermMemoryCount", count != null ? count : 0);
    }

    private void invalidateLtmCache(Long botUserId, Long userId) {
        if (isRedisAvailable()) {
            try {
                stringRedisTemplate.delete(buildCacheKey(botUserId, userId));
            } catch (Exception ignored) {}
        }
    }

    private Map<String, Object> toMap(LongTermMemory m) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", m.getId());
        map.put("memoryType", m.getMemoryType());
        map.put("content", m.getContent());
        map.put("importance", m.getImportance());
        map.put("createdAt", m.getCreatedAt() != null ? m.getCreatedAt().toString() : null);
        return map;
    }
}
