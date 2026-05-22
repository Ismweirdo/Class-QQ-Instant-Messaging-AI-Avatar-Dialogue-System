package com.chatroom.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.hc.client5.http.classic.HttpClient;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.impl.classic.HttpClientBuilder;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.springframework.http.*;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

@Slf4j
@Service
public class LLMApiClient {

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public LLMApiClient() {
        // Connection pool: sized for stress testing (200+ concurrent bots)
        PoolingHttpClientConnectionManager cm = new PoolingHttpClientConnectionManager();
        cm.setMaxTotal(300);
        cm.setDefaultMaxPerRoute(300);
        cm.setDefaultConnectionConfig(ConnectionConfig.custom()
                .setConnectTimeout(30, TimeUnit.SECONDS)
                .setSocketTimeout(180, TimeUnit.SECONDS)
                .build());

        HttpClient httpClient = HttpClientBuilder.create()
                .setConnectionManager(cm)
                .build();

        HttpComponentsClientHttpRequestFactory factory = new HttpComponentsClientHttpRequestFactory(httpClient);
        this.restTemplate = new RestTemplate(factory);
    }

    public String chat(String apiEndpoint, String apiKey, String model,
                       List<Map<String, String>> messages,
                       int maxTokens, double temperature) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setBearerAuth(apiKey);

            Map<String, Object> body = Map.of(
                "model", model, "messages", messages,
                "max_tokens", maxTokens, "temperature", temperature
            );

            ResponseEntity<Map> response = restTemplate.postForEntity(
                    apiEndpoint, new HttpEntity<>(body, headers), Map.class);

            if (response.getBody() != null) {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> choices = (List<Map<String, Object>>) response.getBody().get("choices");
                if (choices != null && !choices.isEmpty()) {
                    return (String) ((Map<String, Object>) choices.get(0).get("message")).get("content");
                }
            }
        } catch (Exception e) {
            log.error("LLM API call failed: {}", e.getMessage());
            throw new RuntimeException("LLM API error: " + e.getMessage());
        }
        return null;
    }

    public String chatStream(String apiEndpoint, String apiKey, String model,
                              List<Map<String, String>> messages,
                              int maxTokens, double temperature,
                              Consumer<String> onChunk) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setBearerAuth(apiKey);

            Map<String, Object> body = Map.of(
                "model", model, "messages", messages,
                "max_tokens", maxTokens, "temperature", temperature,
                "stream", true
            );

            // Use same pooled client for streaming
            StringBuilder fullText = new StringBuilder();
            restTemplate.execute(apiEndpoint, HttpMethod.POST,
                req -> {
                    req.getHeaders().setContentType(MediaType.APPLICATION_JSON);
                    req.getHeaders().setBearerAuth(apiKey);
                    req.getBody().write(objectMapper.writeValueAsBytes(body));
                },
                res -> {
                    try (BufferedReader reader = new BufferedReader(new InputStreamReader(res.getBody()))) {
                        String line;
                        while ((line = reader.readLine()) != null) {
                            if (line.startsWith("data: ")) {
                                String data = line.substring(6);
                                if ("[DONE]".equals(data)) break;
                                try {
                                    @SuppressWarnings("unchecked")
                                    Map<String, Object> chunk = objectMapper.readValue(data, Map.class);
                                    @SuppressWarnings("unchecked")
                                    List<Map<String, Object>> choices = (List<Map<String, Object>>) chunk.get("choices");
                                    if (choices != null && !choices.isEmpty()) {
                                        Map<String, Object> delta = (Map<String, Object>) choices.get(0).get("delta");
                                        if (delta != null && delta.containsKey("content")) {
                                            String token = (String) delta.get("content");
                                            fullText.append(token);
                                            onChunk.accept(token);
                                        }
                                    }
                                } catch (Exception ignored) {}
                            }
                        }
                    }
                    return fullText.toString();
                });

            return fullText.toString();
        } catch (Exception e) {
            log.error("LLM stream call failed: {}", e.getMessage());
            throw new RuntimeException("LLM stream error: " + e.getMessage());
        }
    }
}
