package com.chatroom.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

/**
 * Calls LLM provider's embedding API to generate vector embeddings.
 * Uses OpenAI-compatible /v1/embeddings endpoint.
 * Falls back gracefully when embedding API is not available.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EmbeddingService {

    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Generate embedding vector for text.
     * Returns null if embedding API is not available or fails.
     */
    public List<Double> embed(String apiEndpoint, String apiKey,
                               String model, String text) {
        // Derive embedding endpoint from chat endpoint
        String embeddingEndpoint = apiEndpoint
                .replace("/chat/completions", "/embeddings")
                .replace("/compatible-mode/v1/chat/completions", "/compatible-mode/v1/embeddings");
        String embeddingModel = inferEmbeddingModel(model);

        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            if (apiKey != null && !apiKey.isBlank()) {
                headers.setBearerAuth(apiKey);
            }

            Map<String, Object> body = Map.of(
                "model", embeddingModel,
                "input", text.length() > 8000 ? text.substring(0, 8000) : text
            );

            HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);
            ResponseEntity<Map> response = restTemplate.postForEntity(
                    embeddingEndpoint, request, Map.class);

            if (response.getBody() != null) {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> data = (List<Map<String, Object>>) response.getBody().get("data");
                if (data != null && !data.isEmpty()) {
                    @SuppressWarnings("unchecked")
                    List<Double> embedding = (List<Double>) data.get(0).get("embedding");
                    if (embedding != null && !embedding.isEmpty()) {
                        log.debug("Generated embedding: {} dimensions", embedding.size());
                        return embedding;
                    }
                }
            }
        } catch (Exception e) {
            log.debug("Embedding API not available for {}: {}", embeddingEndpoint, e.getMessage());
        }
        return null;
    }

    private String inferEmbeddingModel(String chatModel) {
        if (chatModel == null) return "text-embedding-3-small";
        String m = chatModel.toLowerCase();
        if (m.contains("gpt") || m.contains("openai")) return "text-embedding-3-small";
        if (m.contains("qwen")) return "text-embedding-v2";
        if (m.contains("glm")) return "embedding-2";
        if (m.contains("deepseek")) return "text-embedding-3-small"; // DeepSeek doesn't have embeddings
        return "text-embedding-3-small";
    }
}
