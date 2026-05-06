package com.chatroom.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.*;

@Slf4j
@Service
public class QQChatExporterClient {

    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${bot.qqce-url:http://localhost:40653}")
    private String qqceBaseUrl;

    /** Check if QQCE is running and accessible */
    public Map<String, Object> healthCheck() {
        try {
            ResponseEntity<Map> resp = restTemplate.getForEntity(qqceBaseUrl + "/health", Map.class);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("connected", resp.getStatusCode().is2xxSuccessful());
            result.put("url", qqceBaseUrl);
            return result;
        } catch (Exception e) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("connected", false);
            result.put("url", qqceBaseUrl);
            result.put("error", "无法连接到QQ Chat Exporter，请确保已启动并扫码登录: " + e.getMessage());
            return result;
        }
    }

    /** Get friend list from QQ */
    public List<Map<String, Object>> getFriends() {
        try {
            ResponseEntity<Map> resp = restTemplate.getForEntity(qqceBaseUrl + "/api/friends", Map.class);
            Map<String, Object> data = (Map<String, Object>) resp.getBody().get("data");
            if (data == null) data = resp.getBody();
            List<Map<String, Object>> friends = (List<Map<String, Object>>) data.get("friends");
            if (friends == null) friends = List.of();
            return friends;
        } catch (Exception e) {
            log.error("Failed to get QQ friends: {}", e.getMessage());
            throw new RuntimeException("获取QQ好友列表失败: " + e.getMessage());
        }
    }

    /** Get group list from QQ */
    public List<Map<String, Object>> getGroups() {
        try {
            ResponseEntity<Map> resp = restTemplate.getForEntity(qqceBaseUrl + "/api/groups", Map.class);
            Map<String, Object> data = (Map<String, Object>) resp.getBody().get("data");
            if (data == null) data = resp.getBody();
            List<Map<String, Object>> groups = (List<Map<String, Object>>) data.get("groups");
            if (groups == null) groups = List.of();
            return groups;
        } catch (Exception e) {
            log.error("Failed to get QQ groups: {}", e.getMessage());
            throw new RuntimeException("获取QQ群列表失败: " + e.getMessage());
        }
    }

    /** Fetch messages for a specific chat (friend or group) */
    public List<Map<String, Object>> fetchMessages(String chatType, String peerUid, int count) {
        try {
            Map<String, Object> body = Map.of(
                "peer", Map.of("chatType", chatType.equals("group") ? 2 : 1, "peerUid", peerUid),
                "batchSize", count,
                "page", 1,
                "limit", Math.min(count, 5000)
            );

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);

            ResponseEntity<Map> resp = restTemplate.postForEntity(
                    qqceBaseUrl + "/api/messages/fetch", request, Map.class);

            Map<String, Object> data = (Map<String, Object>) resp.getBody().get("data");
            if (data == null) data = resp.getBody();

            Object messagesObj = data.get("messages");
            if (messagesObj instanceof List) {
                return (List<Map<String, Object>>) messagesObj;
            }
            return List.of();
        } catch (Exception e) {
            log.error("Failed to fetch messages for {}: {}", peerUid, e.getMessage());
            throw new RuntimeException("获取聊天记录失败: " + e.getMessage());
        }
    }
}
