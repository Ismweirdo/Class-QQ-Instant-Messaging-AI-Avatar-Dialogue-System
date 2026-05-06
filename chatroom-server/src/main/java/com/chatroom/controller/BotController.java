package com.chatroom.controller;

import com.chatroom.common.Result;
import com.chatroom.model.entity.BotSkill;
import com.chatroom.service.BotManager;
import com.chatroom.service.ChatRecordImportService;
import com.chatroom.service.QQChatExporterClient;
import com.chatroom.service.SkillDistillerService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/bots")
@RequiredArgsConstructor
public class BotController {

    private final BotManager botManager;
    private final SkillDistillerService skillDistillerService;
    private final ChatRecordImportService chatRecordImportService;
    private final QQChatExporterClient qqChatExporterClient;

    @Value("${bot.default-api-endpoint:}")
    private String defaultApiEndpoint;

    @Value("${bot.default-api-key:}")
    private String defaultApiKey;

    @Value("${bot.default-model:}")
    private String defaultModel;

    @GetMapping("/config")
    public Result<Map<String, Object>> config() {
        String maskedKey = "";
        if (defaultApiKey != null && defaultApiKey.length() > 8) {
            maskedKey = defaultApiKey.substring(0, 8) + "..." + defaultApiKey.substring(defaultApiKey.length() - 4);
        }
        return Result.ok(Map.of(
            "endpoint", defaultApiEndpoint,
            "model", defaultModel,
            "apiKeyConfigured", defaultApiKey != null && !defaultApiKey.isEmpty(),
            "apiKeyPreview", maskedKey
        ));
    }

    @PostMapping("/register")
    public Result<BotSkill> register(@RequestBody Map<String, String> body) {
        BotSkill skill = botManager.registerBot(
                body.get("username"),
                body.get("nickname"),
                body.get("skillName"),
                body.get("systemPrompt"),
                body.get("fewShotExamples"),
                body.get("emotionProfile"),
                body.get("languageStyle"),
                body.get("apiEndpoint"),
                body.get("apiKey"),
                body.get("model"));
        return Result.ok(skill);
    }

    @DeleteMapping("/{userId}")
    public Result<String> deactivate(@PathVariable Long userId) {
        botManager.deactivateBot(userId);
        return Result.ok("Bot deactivated");
    }

    @GetMapping("/")
    public Result<List<BotSkill>> list() {
        return Result.ok(botManager.getAllBots());
    }

    @GetMapping("/active")
    public Result<List<BotSkill>> active() {
        return Result.ok(botManager.getActiveBots());
    }

    @GetMapping("/count")
    public Result<Integer> count() {
        return Result.ok(botManager.getOnlineBotCount());
    }

    @PostMapping("/distill")
    public Result<List<Map<String, Object>>> distill() {
        return Result.ok(skillDistillerService.distillSkills());
    }

    @PostMapping("/import")
    public Result<List<Map<String, Object>>> importRecords(@RequestParam("file") MultipartFile file) {
        try {
            List<Map<String, Object>> results = chatRecordImportService.importAndGenerate(file);
            return Result.ok(results);
        } catch (Exception e) {
            return Result.error(500, "导入失败: " + e.getMessage());
        }
    }

    // ==================== QQ Chat Exporter Integration ====================

    @GetMapping("/qq/health")
    public Result<Map<String, Object>> qqHealth() {
        return Result.ok(qqChatExporterClient.healthCheck());
    }

    @GetMapping("/qq/friends")
    public Result<List<Map<String, Object>>> qqFriends() {
        return Result.ok(qqChatExporterClient.getFriends());
    }

    @GetMapping("/qq/groups")
    public Result<List<Map<String, Object>>> qqGroups() {
        return Result.ok(qqChatExporterClient.getGroups());
    }

    @PostMapping("/qq/import")
    public Result<List<Map<String, Object>>> qqImport(@RequestBody Map<String, Object> body) {
        List<Map<String, Object>> results = new ArrayList<>();
        List<Map<String, Object>> selections = (List<Map<String, Object>>) body.get("selections");
        int msgCount = (int) body.getOrDefault("messageCount", 500);

        if (selections == null || selections.isEmpty()) {
            return Result.error(400, "请选择要导入的好友或群");
        }

        for (Map<String, Object> sel : selections) {
            String chatType = (String) sel.get("chatType"); // "friend" or "group"
            String peerUid = (String) sel.get("peerUid");
            String name = (String) sel.get("name");

            try {
                List<Map<String, Object>> messages = qqChatExporterClient.fetchMessages(chatType, peerUid, msgCount);
                // Convert to simple format
                List<Map<String, String>> simple = new ArrayList<>();
                for (Map<String, Object> msg : messages) {
                    String sender = extractSenderName(msg);
                    String text = extractMessageText(msg);
                    if (!text.isEmpty()) {
                        simple.add(Map.of("sender", sender, "content", text));
                    }
                }
                // Use import service to generate bot from messages
                // We call the internal logic directly
                results.add(Map.of(
                    "name", name,
                    "chatType", chatType,
                    "messageCount", simple.size(),
                    "status", "fetched"
                ));
            } catch (Exception e) {
                results.add(Map.of(
                    "name", name,
                    "chatType", chatType,
                    "status", "error",
                    "error", e.getMessage()
                ));
            }
        }

        return Result.ok(results);
    }

    private String extractSenderName(Map<String, Object> msg) {
        Object senderObj = msg.get("sender");
        if (senderObj instanceof Map) {
            Map<String, Object> s = (Map<String, Object>) senderObj;
            return String.valueOf(s.getOrDefault("name",
                    s.getOrDefault("uid", s.getOrDefault("uin", "unknown"))));
        }
        return String.valueOf(msg.getOrDefault("senderName", msg.getOrDefault("sender", "unknown")));
    }

    private String extractMessageText(Map<String, Object> msg) {
        Object contentObj = msg.get("content");
        if (contentObj instanceof Map) {
            return String.valueOf(((Map) contentObj).getOrDefault("text", ""));
        }
        if (contentObj instanceof String) return (String) contentObj;
        return String.valueOf(msg.getOrDefault("text", msg.getOrDefault("content", "")));
    }
}
