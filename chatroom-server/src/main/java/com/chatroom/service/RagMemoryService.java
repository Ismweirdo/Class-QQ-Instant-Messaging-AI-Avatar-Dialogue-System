package com.chatroom.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.chatroom.mapper.ConversationEmbeddingMapper;
import com.chatroom.model.entity.ConversationEmbedding;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * RAG (Retrieval-Augmented Generation) memory service.
 *
 * Two retrieval modes:
 * 1. Vector similarity: Uses embedding vectors + cosine similarity (when embedding API available)
 * 2. Keyword overlap: Jaccard similarity on tokenized content (always available as fallback)
 *
 * Embeddings are stored in DB as JSON arrays. Retrieval loads all embeddings for
 * a conversation pair into memory and computes similarity scores.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RagMemoryService {

    private static final int MAX_STORED_EMBEDDINGS_PER_PAIR = 500;

    private final ConversationEmbeddingMapper embeddingMapper;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Store a message embedding for RAG retrieval.
     */
    public void storeEmbedding(Long botUserId, Long userId, Long messageId,
                                String content, List<Double> embedding) {
        try {
            String json = objectMapper.writeValueAsString(embedding);
            ConversationEmbedding ce = new ConversationEmbedding();
            ce.setBotUserId(botUserId);
            ce.setUserId(userId);
            ce.setMessageId(messageId);
            ce.setContent(content);
            ce.setEmbeddingJson(json);
            ce.setCreatedAt(LocalDateTime.now());
            embeddingMapper.insert(ce);

            // Prune old entries if exceeding max
            var wrapper = new LambdaQueryWrapper<ConversationEmbedding>()
                    .eq(ConversationEmbedding::getBotUserId, botUserId)
                    .eq(ConversationEmbedding::getUserId, userId)
                    .orderByDesc(ConversationEmbedding::getId);
            Long count = embeddingMapper.selectCount(wrapper);
            if (count != null && count > MAX_STORED_EMBEDDINGS_PER_PAIR) {
                // Delete oldest entries beyond max
                var deleteWrapper = new LambdaQueryWrapper<ConversationEmbedding>()
                        .eq(ConversationEmbedding::getBotUserId, botUserId)
                        .eq(ConversationEmbedding::getUserId, userId)
                        .orderByAsc(ConversationEmbedding::getId)
                        .last("LIMIT " + (count - MAX_STORED_EMBEDDINGS_PER_PAIR));
                embeddingMapper.delete(deleteWrapper);
            }
        } catch (Exception e) {
            log.warn("Failed to store embedding: {}", e.getMessage());
        }
    }

    /**
     * Retrieve top-K relevant past messages using vector similarity.
     * Falls back to keyword-based retrieval if embeddings are not available.
     */
    public List<Map<String, String>> retrieveRelevant(
            Long botUserId, Long userId, String query, int topK,
            List<Double> queryEmbedding) {

        // Try vector similarity first if embedding available
        if (queryEmbedding != null && !queryEmbedding.isEmpty()) {
            return retrieveByVector(botUserId, userId, queryEmbedding, topK);
        }

        // Fallback to keyword-based
        return retrieveByKeyword(botUserId, userId, query, topK);
    }

    /**
     * Vector similarity retrieval using cosine similarity.
     */
    private List<Map<String, String>> retrieveByVector(
            Long botUserId, Long userId, List<Double> queryVec, int topK) {

        List<ConversationEmbedding> all = embeddingMapper.selectList(
                new LambdaQueryWrapper<ConversationEmbedding>()
                        .eq(ConversationEmbedding::getBotUserId, botUserId)
                        .eq(ConversationEmbedding::getUserId, userId)
                        .isNotNull(ConversationEmbedding::getEmbeddingJson)
                        .ne(ConversationEmbedding::getEmbeddingJson, "")
                        .orderByDesc(ConversationEmbedding::getId)
                        .last("LIMIT 200"));

        if (all.isEmpty()) return List.of();

        // Compute cosine similarity
        record ScoredEntry(ConversationEmbedding entry, double score) {}
        List<ScoredEntry> scored = new ArrayList<>();
        for (ConversationEmbedding ce : all) {
            try {
                List<Double> vec = objectMapper.readValue(ce.getEmbeddingJson(),
                        new TypeReference<List<Double>>() {});
                double sim = cosineSimilarity(queryVec, vec);
                if (sim > 0.3) { // Minimum relevance threshold
                    scored.add(new ScoredEntry(ce, sim));
                }
            } catch (Exception ignored) {}
        }

        scored.sort((a, b) -> Double.compare(b.score, a.score));

        return scored.stream()
                .limit(topK)
                .map(s -> Map.of(
                    "role", "user",
                    "content", "[记忆] " + s.entry.getContent(),
                    "_score", String.format("%.2f", s.score)))
                .collect(Collectors.toList());
    }

    /**
     * Keyword-based retrieval using Jaccard similarity on tokenized content.
     * Works without any embedding API.
     */
    private List<Map<String, String>> retrieveByKeyword(
            Long botUserId, Long userId, String query, int topK) {

        List<ConversationEmbedding> all = embeddingMapper.selectList(
                new LambdaQueryWrapper<ConversationEmbedding>()
                        .eq(ConversationEmbedding::getBotUserId, botUserId)
                        .eq(ConversationEmbedding::getUserId, userId)
                        .orderByDesc(ConversationEmbedding::getId)
                        .last("LIMIT 200"));

        if (all.isEmpty()) return List.of();

        Set<String> queryTokens = tokenize(query);

        record ScoredEntry(ConversationEmbedding entry, double score) {}
        List<ScoredEntry> scored = new ArrayList<>();
        for (ConversationEmbedding ce : all) {
            Set<String> tokens = tokenize(ce.getContent());
            double sim = jaccardSimilarity(queryTokens, tokens);
            if (sim > 0.05) {
                scored.add(new ScoredEntry(ce, sim));
            }
        }

        scored.sort((a, b) -> Double.compare(b.score, a.score));

        return scored.stream()
                .limit(topK)
                .map(s -> Map.of(
                    "role", "user",
                    "content", "[记忆] " + s.entry.getContent(),
                    "_score", String.format("%.2f", s.score)))
                .collect(Collectors.toList());
    }

    /**
     * Simple Chinese/English tokenizer: split by common delimiters + bigram characters.
     */
    private Set<String> tokenize(String text) {
        Set<String> tokens = new HashSet<>();
        if (text == null || text.isBlank()) return tokens;

        // Word-level tokens
        String[] words = text.split("[\\s，。！？,.!?、：:；;()（）\"'\\[\\]{}]+");
        for (String w : words) {
            if (w.length() >= 2) tokens.add(w.toLowerCase());
        }

        // Bigram characters for Chinese
        String cleaned = text.replaceAll("[\\s，。！？,.!?、：:；;()（）\"'\\[\\]{}]+", "");
        for (int i = 0; i < cleaned.length() - 1; i++) {
            tokens.add(cleaned.substring(i, i + 2).toLowerCase());
        }

        return tokens;
    }

    private double jaccardSimilarity(Set<String> a, Set<String> b) {
        if (a.isEmpty() && b.isEmpty()) return 0;
        Set<String> intersection = new HashSet<>(a);
        intersection.retainAll(b);
        Set<String> union = new HashSet<>(a);
        union.addAll(b);
        return union.isEmpty() ? 0 : (double) intersection.size() / union.size();
    }

    private double cosineSimilarity(List<Double> a, List<Double> b) {
        if (a.size() != b.size()) return 0;
        double dot = 0, normA = 0, normB = 0;
        for (int i = 0; i < a.size(); i++) {
            dot += a.get(i) * b.get(i);
            normA += a.get(i) * a.get(i);
            normB += b.get(i) * b.get(i);
        }
        if (normA == 0 || normB == 0) return 0;
        return dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }

    /** Clear RAG memory for a conversation pair, or all for a bot if userId is null. */
    public void clearMemory(Long botUserId, Long userId) {
        var wrapper = new LambdaQueryWrapper<ConversationEmbedding>()
                .eq(ConversationEmbedding::getBotUserId, botUserId);
        if (userId != null) {
            wrapper.eq(ConversationEmbedding::getUserId, userId);
        }
        embeddingMapper.delete(wrapper);
    }

    /** Get RAG memory stats. */
    public Map<String, Object> getStats(Long botUserId) {
        var wrapper = new LambdaQueryWrapper<ConversationEmbedding>()
                .eq(ConversationEmbedding::getBotUserId, botUserId);
        Long count = embeddingMapper.selectCount(wrapper);
        return Map.of("botUserId", botUserId, "storedEmbeddings", count != null ? count : 0);
    }
}
