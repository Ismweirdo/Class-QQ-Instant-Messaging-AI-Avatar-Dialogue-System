package com.chatroom.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Redis List-based message queue for bot message processing.
 * Only activated when Redis is available.
 *
 * Key structure:
 *   bot:queue:{botUserId}  -> Redis List (FIFO)
 *   bot:lock:{botUserId}   -> String (SETNX for distributed lock)
 *   bot:circuit:{botUserId} -> String (circuit breaker state JSON)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BotMessageQueueService {

    private static final String QUEUE_PREFIX = "bot:queue:";
    private static final String LOCK_PREFIX = "bot:lock:";
    private static final String CIRCUIT_PREFIX = "bot:circuit:";

    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;

    @Value("${bot.queue.max-size:100}")
    private int maxQueueSize;

    @Value("${bot.queue.poll-timeout-ms:5000}")
    private long pollTimeoutMs;

    private volatile boolean redisAvailable = true;

    private boolean isRedisAvailable() {
        if (!redisAvailable) return false;
        try {
            stringRedisTemplate.getConnectionFactory().getConnection().ping();
            return true;
        } catch (Throwable e) {
            redisAvailable = false;
            log.warn("Redis unavailable, using in-memory fallback for bot message queue");
            return false;
        }
    }

    /** Enqueue a message for a bot. Returns false if queue is full. */
    public boolean enqueue(Long botUserId, Long senderId, String senderName, String content) {
        if (!isRedisAvailable()) return false;
        String key = QUEUE_PREFIX + botUserId;
        Long size = stringRedisTemplate.opsForList().size(key);
        if (size != null && size >= maxQueueSize) {
            log.warn("Bot {} queue full ({}), dropping message", botUserId, size);
            return false;
        }
        try {
            Map<String, Object> msg = Map.of(
                "senderId", senderId,
                "senderName", senderName != null ? senderName : "用户",
                "content", content,
                "ts", System.currentTimeMillis()
            );
            String json = objectMapper.writeValueAsString(msg);
            stringRedisTemplate.opsForList().leftPush(key, json);
            stringRedisTemplate.expire(key, 24, TimeUnit.HOURS);
            return true;
        } catch (Exception e) {
            log.error("Failed to enqueue message for bot {}", botUserId, e);
            return false;
        }
    }

    /** Dequeue the next message for a bot. Returns null if queue is empty. */
    public Map<String, Object> dequeue(Long botUserId) {
        if (!isRedisAvailable()) return null;
        String key = QUEUE_PREFIX + botUserId;
        try {
            String json = stringRedisTemplate.opsForList().rightPop(key);
            if (json == null) return null;
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            log.error("Failed to dequeue message for bot {}", botUserId, e);
            return null;
        }
    }

    /** Blocking dequeue with timeout. Returns null if timeout. */
    public Map<String, Object> dequeueBlocking(Long botUserId) {
        if (!isRedisAvailable()) return null;
        String key = QUEUE_PREFIX + botUserId;
        try {
            String json = stringRedisTemplate.opsForList().rightPop(key, pollTimeoutMs, TimeUnit.MILLISECONDS);
            if (json == null) return null;
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            log.error("Failed to blocking-dequeue for bot {}", botUserId, e);
            return null;
        }
    }

    /** Get current queue size. */
    public long queueSize(Long botUserId) {
        if (!isRedisAvailable()) return 0;
        String key = QUEUE_PREFIX + botUserId;
        Long size = stringRedisTemplate.opsForList().size(key);
        return size != null ? size : 0;
    }

    /** Clear queue for a bot. */
    public void clearQueue(Long botUserId) {
        if (!isRedisAvailable()) return;
        stringRedisTemplate.delete(QUEUE_PREFIX + botUserId);
    }

    // ==================== Circuit Breaker (Redis-backed) ====================

    /** Set circuit breaker state. */
    public void setCircuitOpen(Long botUserId, boolean open) {
        if (!isRedisAvailable()) return;
        try {
            Map<String, Object> state = Map.of(
                "open", open,
                "openedAt", open ? System.currentTimeMillis() : 0
            );
            stringRedisTemplate.opsForValue().set(CIRCUIT_PREFIX + botUserId,
                    objectMapper.writeValueAsString(state), 24, TimeUnit.HOURS);
        } catch (Exception e) {
            log.error("Failed to set circuit state for bot {}", botUserId, e);
        }
    }

    /** Check if circuit breaker is open (and within silence period). */
    public boolean isCircuitOpen(Long botUserId, long silenceMs) {
        if (!isRedisAvailable()) return false;
        try {
            String json = stringRedisTemplate.opsForValue().get(CIRCUIT_PREFIX + botUserId);
            if (json == null) return false;
            Map<String, Object> state = objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
            if (Boolean.TRUE.equals(state.get("open"))) {
                long openedAt = ((Number) state.get("openedAt")).longValue();
                return System.currentTimeMillis() - openedAt < silenceMs;
            }
        } catch (Exception e) {
            log.warn("Failed to read circuit state for bot {}", botUserId, e);
        }
        return false;
    }
}
