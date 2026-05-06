package com.chatroom.service;

import com.chatroom.common.Constants;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.client.HttpStatusCodeException;

import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class LLMApiClient {

    private final RestTemplate restTemplate = new RestTemplate();

    public String chat(String apiEndpoint, String apiKey, String model,
                       String systemPrompt, List<Map<String, String>> messages) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setBearerAuth(apiKey);

            List<Map<String, String>> fullMessages = new java.util.ArrayList<>();
            fullMessages.add(Map.of("role", "system", "content", systemPrompt));
            fullMessages.addAll(messages);

            Map<String, Object> body = Map.of(
                "model", model,
                "messages", fullMessages,
                "max_tokens", 150,
                "temperature", 0.8
            );

            HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);
            ResponseEntity<Map> response = restTemplate.postForEntity(apiEndpoint, request, Map.class);

            if (response.getBody() != null) {
                List<Map<String, Object>> choices = (List<Map<String, Object>>) response.getBody().get("choices");
                if (choices != null && !choices.isEmpty()) {
                    Map<String, Object> message = (Map<String, Object>) choices.get(0).get("message");
                    return (String) message.get("content");
                }
            }
        } catch (HttpStatusCodeException e) {
            log.error("LLM API error: {} - {}", e.getStatusCode(), e.getResponseBodyAsString());
            throw new RuntimeException("LLM API error: " + e.getStatusCode());
        } catch (Exception e) {
            log.error("LLM API call failed", e);
            throw new RuntimeException("LLM API call failed: " + e.getMessage());
        }
        return null;
    }

    public boolean healthCheck(String apiEndpoint, String apiKey) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setBearerAuth(apiKey);
            HttpEntity<Void> request = new HttpEntity<>(headers);
            restTemplate.exchange(apiEndpoint.replace("/chat/completions", "/models"),
                    HttpMethod.GET, request, String.class);
            return true;
        } catch (Exception e) {
            log.warn("API health check failed for {}", apiEndpoint);
            return false;
        }
    }
}
