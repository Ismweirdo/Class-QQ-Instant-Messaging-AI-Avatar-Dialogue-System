package com.chatroom.service;

import com.chatroom.common.Constants;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.chatroom.model.vo.MessageVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Multi-level Redis-backed conversation memory cache.
 *
 * Level 0 — Working memory:  conv:work:{min}:{max}  (String, last ~5 exchanges)
 * Level 1 — Short-term memory: conv:short:{min}:{max} (List, last N messages)
 *
 * Only activated when Redis is available.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConversationMemoryCache {

    private static final String WORK_PREFIX = "conv:work:";
    private static final String SHORT_PREFIX = "conv:short:";

    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;

    // Per-key mutexes to serialize working memory read-modify-write within JVM
    private final ConcurrentHashMap<String, Object> workLocks = new ConcurrentHashMap<>();

    @Value("${bot.memory.cache-ttl-minutes:60}")
    private int cacheTtlMinutes;
    @Value("${bot.memory.work-ttl-minutes:30}")
    private int workTtlMinutes;

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
            log.warn("Redis unavailable, conversation memory cache disabled");
        }
        lastRedisCheck = now;
        return redisAvailable;
    }

    private String buildKey(String prefix, Long userId1, Long userId2) {
        long min = Math.min(userId1, userId2);
        long max = Math.max(userId1, userId2);
        return prefix + min + ":" + max;
    }

    // ==================== Level 0: Working Memory ====================

    /**
     * Add a complete exchange (user message + bot reply) to working memory.
     * Stores as JSON array of exchanges, each with user/bot content.
     * Capped at BOT_WORKING_MEMORY_SIZE exchanges.
     */
    public void addToWorkingMemory(Long userId1, Long userId2,
                                    String userContent, String botContent) {
        if (!isRedisAvailable()) return;
        String redisKey = buildKey(WORK_PREFIX, userId1, userId2);
        // Serialize per-key to avoid read-modify-write race within JVM
        Object lock = workLocks.computeIfAbsent(redisKey, k -> new Object());
        synchronized (lock) {
            try {
                String existing = stringRedisTemplate.opsForValue().get(redisKey);
                List<Map<String, String>> exchanges;
                if (existing != null && !existing.isEmpty()) {
                    exchanges = objectMapper.readValue(existing,
                            new TypeReference<List<Map<String, String>>>() {});
                } else {
                    exchanges = new ArrayList<>();
                }

                String uc = truncate(userContent, Constants.BOT_WORKING_MEMORY_MAX_CHARS);
                String bc = truncate(botContent, Constants.BOT_WORKING_MEMORY_MAX_CHARS);

                exchanges.add(Map.of("user", uc, "bot", bc));

                int maxSize = Constants.BOT_WORKING_MEMORY_SIZE;
                if (exchanges.size() > maxSize) {
                    exchanges = exchanges.subList(exchanges.size() - maxSize, exchanges.size());
                }

                String json = objectMapper.writeValueAsString(exchanges);
                stringRedisTemplate.opsForValue().set(redisKey, json, workTtlMinutes, TimeUnit.MINUTES);
            } catch (Exception e) {
                log.warn("Failed to update working memory", e);
            }
        }
    }

    /**
     * Get working memory exchanges as formatted messages for LLM context.
     * Returns alternating user/assistant message maps.
     */
    public List<Map<String, String>> getWorkingMemory(Long userId1, Long userId2) {
        if (!isRedisAvailable()) return List.of();
        try {
            String key = buildKey(WORK_PREFIX, userId1, userId2);
            String json = stringRedisTemplate.opsForValue().get(key);
            if (json == null || json.isEmpty()) return List.of();

            List<Map<String, String>> exchanges = objectMapper.readValue(json,
                    new TypeReference<List<Map<String, String>>>() {});
            List<Map<String, String>> result = new ArrayList<>();
            for (Map<String, String> ex : exchanges) {
                String user = ex.get("user");
                String bot = ex.get("bot");
                if (user != null && !user.isBlank()) {
                    result.add(Map.of("role", "user", "content", user));
                }
                if (bot != null && !bot.isBlank()) {
                    result.add(Map.of("role", "assistant", "content", bot));
                }
            }
            return result;
        } catch (Exception e) {
            log.warn("Failed to read working memory", e);
            return List.of();
        }
    }

    // ==================== Level 1: Short-Term Memory ====================

    /**
     * Cache a message in short-term memory (Redis List).
     * Pushes to the right, trims to maxSize.
     */
    public void addMessage(Long userId1, Long userId2, MessageVO msg, int maxSize) {
        if (!isRedisAvailable()) return;
        try {
            String key = buildKey(SHORT_PREFIX, userId1, userId2);
            String role = msg.getSenderId().equals(userId1) ? "user" : "assistant";
            Map<String, Object> entry = Map.of(
                "role", role,
                "senderId", msg.getSenderId(),
                "senderName", msg.getSenderName() != null ? msg.getSenderName() : "",
                "content", msg.getContent()
            );
            String json = objectMapper.writeValueAsString(entry);
            stringRedisTemplate.opsForList().rightPush(key, json);
            stringRedisTemplate.opsForList().trim(key, -maxSize, -1);
            stringRedisTemplate.expire(key, cacheTtlMinutes, TimeUnit.MINUTES);
        } catch (Exception e) {
            log.warn("Failed to cache message to short-term memory", e);
        }
    }

    /**
     * Get cached short-term memory. Returns entries in chronological order.
     */
    public List<Map<String, String>> getMemory(Long userId1, Long userId2, int count) {
        if (!isRedisAvailable()) return List.of();
        try {
            String key = buildKey(SHORT_PREFIX, userId1, userId2);
            List<String> jsons = stringRedisTemplate.opsForList().range(key, -count, -1);
            if (jsons == null || jsons.isEmpty()) return List.of();

            List<Map<String, String>> result = new ArrayList<>();
            for (String json : jsons) {
                Map<String, Object> entry = objectMapper.readValue(json,
                        new TypeReference<Map<String, Object>>() {});
                result.add(Map.of(
                    "role", String.valueOf(entry.get("role")),
                    "content", String.valueOf(entry.get("content"))
                ));
            }
            return result;
        } catch (Exception e) {
            log.warn("Failed to read short-term memory", e);
            return List.of();
        }
    }

    /**
     * Get the current short-term memory count for a pair.
     * Used to check if consolidation threshold is reached.
     */
    public long getMemoryCount(Long userId1, Long userId2) {
        if (!isRedisAvailable()) return 0;
        try {
            String key = buildKey(SHORT_PREFIX, userId1, userId2);
            Long size = stringRedisTemplate.opsForList().size(key);
            return size != null ? size : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * Trim short-term memory to a target size by removing oldest entries.
     * Returns the removed entries for consolidation.
     */
    public List<Map<String, String>> trimMemory(Long userId1, Long userId2, int targetSize) {
        if (!isRedisAvailable()) return List.of();
        try {
            String key = buildKey(SHORT_PREFIX, userId1, userId2);
            Long currentSize = stringRedisTemplate.opsForList().size(key);
            if (currentSize == null || currentSize <= targetSize) return List.of();

            long removeCount = currentSize - targetSize;
            // Read the entries to be removed (oldest)
            List<String> jsons = stringRedisTemplate.opsForList().range(key, 0, removeCount - 1);
            // Trim the list
            stringRedisTemplate.opsForList().trim(key, -targetSize, -1);

            if (jsons == null || jsons.isEmpty()) return List.of();
            List<Map<String, String>> removed = new ArrayList<>();
            for (String json : jsons) {
                try {
                    Map<String, Object> entry = objectMapper.readValue(json,
                            new TypeReference<Map<String, Object>>() {});
                    removed.add(Map.of(
                        "role", String.valueOf(entry.get("role")),
                        "content", String.valueOf(entry.get("content"))
                    ));
                } catch (Exception ignored) {}
            }
            return removed;
        } catch (Exception e) {
            log.warn("Failed to trim short-term memory", e);
            return List.of();
        }
    }

    /** Clear all memory for a pair (working + short-term). */
    public void clearMemory(Long userId1, Long userId2) {
        if (!isRedisAvailable()) return;
        stringRedisTemplate.delete(buildKey(WORK_PREFIX, userId1, userId2));
        stringRedisTemplate.delete(buildKey(SHORT_PREFIX, userId1, userId2));
    }

    private String truncate(String text, int maxChars) {
        if (text == null || text.length() <= maxChars) return text;
        return text.substring(0, maxChars - 3) + "...";
    }
}
