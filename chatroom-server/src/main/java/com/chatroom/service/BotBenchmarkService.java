package com.chatroom.service;

import com.chatroom.model.dto.ChatMessageDTO;
import com.chatroom.model.entity.BotSkill;
import com.chatroom.model.vo.MessageVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Built-in bot performance benchmark service.
 * Measures message send latency, bot reply latency, and end-to-end response times.
 * Reports p50, p90, p99, mean, min, max latency metrics.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BotBenchmarkService {

    private final MessageService messageService;
    private final BotManager botManager;

    /**
     * Run a benchmark by sending N messages to a bot and measuring latencies.
     * Returns p50/p90/p99/mean/min/max metrics.
     */
    public Map<String, Object> runBenchmark(Long botUserId, Long senderId, String senderName,
                                             int messageCount, int concurrency) {
        BotSkill skill = botManager.getBotSkill(botUserId);
        if (skill == null) {
            return Map.of("error", "Bot not found: " + botUserId);
        }

        messageCount = Math.min(messageCount, 100);
        concurrency = Math.min(concurrency, 10);

        List<Long> sendLatencies = Collections.synchronizedList(new ArrayList<>());
        List<Long> replyLatencies = Collections.synchronizedList(new ArrayList<>());
        List<Long> e2eLatencies = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount = new AtomicInteger(0);
        List<String> errors = Collections.synchronizedList(new ArrayList<>());

        long totalStart = System.currentTimeMillis();

        // Send messages with limited concurrency
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        java.util.concurrent.Semaphore sem = new java.util.concurrent.Semaphore(concurrency);

        for (int i = 0; i < messageCount; i++) {
            final int idx = i;
            try {
                sem.acquire();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }

            CompletableFuture<Void> f = CompletableFuture.runAsync(() -> {
                try {
                    long sendStart = System.nanoTime();

                    // Step 1: Save the user message (simulating WebSocket send)
                    ChatMessageDTO dto = new ChatMessageDTO();
                    dto.setContent("Benchmark test message #" + idx + " - hello");
                    dto.setMessageType(0);
                    dto.setTargetId(botUserId);
                    dto.setContentType(0);
                    dto.setClientMessageId("BK_" + UUID.randomUUID().toString().replace("-", "").substring(0, 32));
                    MessageVO userMsg = messageService.sendAndSaveMessage(senderId, dto);

                    long sendEnd = System.nanoTime();
                    sendLatencies.add((sendEnd - sendStart) / 1_000_000);

                    // Step 2: Get bot reply
                    long replyStart = System.nanoTime();
                    String reply = botManager.handleBotMessage(botUserId, senderId, senderName,
                            "Benchmark test #" + idx + " - hello");
                    long replyEnd = System.nanoTime();

                    if (reply != null && !reply.trim().isEmpty()) {
                        replyLatencies.add((replyEnd - replyStart) / 1_000_000);
                        e2eLatencies.add((replyEnd - sendStart) / 1_000_000);
                        successCount.incrementAndGet();
                    } else {
                        failCount.incrementAndGet();
                    }
                } catch (Exception e) {
                    failCount.incrementAndGet();
                    if (errors.size() < 5) errors.add(e.getClass().getSimpleName() + ": " + e.getMessage());
                    log.warn("Benchmark message failed: {}", e.getMessage());
                } finally {
                    sem.release();
                }
            });
            futures.add(f);
        }

        // Wait for all to complete (max 120 seconds)
        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                    .get(120, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("Benchmark timeout: {}", e.getMessage());
        }

        long totalEnd = System.currentTimeMillis();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("botUserId", botUserId);
        result.put("messageCount", messageCount);
        result.put("concurrency", concurrency);
        result.put("successCount", successCount.get());
        result.put("failCount", failCount.get());
        result.put("totalDurationMs", totalEnd - totalStart);
        result.put("throughputPerSec", Math.round(successCount.get() * 1000.0 / Math.max(totalEnd - totalStart, 1) * 100.0) / 100.0);
        if (!errors.isEmpty()) result.put("errors", errors);

        // Latency percentiles
        result.put("sendLatency", computeStats(sendLatencies, "ms"));
        result.put("replyLatency", computeStats(replyLatencies, "ms"));
        result.put("e2eLatency", computeStats(e2eLatencies, "ms"));

        return result;
    }

    /** Run a lightweight benchmark: just pings the bot manager and returns basic health. */
    public Map<String, Object> runQuickBenchmark(Long botUserId, Long senderId) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("botUserId", botUserId);

        // Measure messageService performance
        long start = System.nanoTime();
        ChatMessageDTO dto = new ChatMessageDTO();
        dto.setContent("quick benchmark test");
        dto.setMessageType(0);
        dto.setTargetId(botUserId);
        dto.setContentType(0);
        dto.setClientMessageId("QB_" + UUID.randomUUID().toString().replace("-", "").substring(0, 32));
        messageService.sendAndSaveMessage(senderId, dto);
        result.put("messageSaveLatencyMs", (System.nanoTime() - start) / 1_000_000);

        // Measure bot context build (without LLM call)
        start = System.nanoTime();
        BotSkill skill = botManager.getBotSkill(botUserId);
        result.put("botSkillFetchLatencyMs", (System.nanoTime() - start) / 1_000_000);

        result.put("botSkillExists", skill != null);
        if (skill != null) {
            result.put("conversationMode", skill.getConversationMode() != null ? skill.getConversationMode() : "default");
            result.put("memorySize", skill.getMemorySize());
            result.put("maxTokens", skill.getMaxTokens());
        }

        // Queue stats
        result.put("queueStats", botManager.getQueueStats());

        return result;
    }

    private Map<String, Object> computeStats(List<Long> latencies, String unit) {
        if (latencies.isEmpty()) {
            return Map.of("count", 0, "unit", unit);
        }

        List<Long> sorted = new ArrayList<>(latencies);
        Collections.sort(sorted);

        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("count", sorted.size());
        stats.put("unit", unit);
        stats.put("min", sorted.get(0));
        stats.put("max", sorted.get(sorted.size() - 1));
        stats.put("mean", Math.round(sorted.stream().mapToLong(Long::longValue).average().orElse(0)));
        stats.put("p50", percentile(sorted, 50));
        stats.put("p90", percentile(sorted, 90));
        stats.put("p99", percentile(sorted, 99));
        return stats;
    }

    private long percentile(List<Long> sorted, double percentile) {
        if (sorted.isEmpty()) return 0;
        int index = (int) Math.ceil(percentile / 100.0 * sorted.size()) - 1;
        index = Math.max(0, Math.min(index, sorted.size() - 1));
        return sorted.get(index);
    }
}
