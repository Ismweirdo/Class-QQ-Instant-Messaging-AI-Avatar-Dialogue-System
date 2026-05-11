package com.chatroom.controller;

import com.chatroom.common.Result;
import com.chatroom.model.entity.BotSkill;
import com.chatroom.security.SecurityUtil;
import com.chatroom.service.BotManager;
import com.chatroom.service.ChatRecordImportService;
import com.chatroom.service.QQChatExporterClient;
import com.chatroom.service.SkillDistillerService;
import lombok.extern.slf4j.Slf4j;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/bots")
@RequiredArgsConstructor
@Slf4j
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
            Long currentUserId = SecurityUtil.getCurrentUserId();
            List<Map<String, Object>> results = chatRecordImportService.importAndGenerate(file, currentUserId);
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
        Long currentUserId = SecurityUtil.getCurrentUserId();
        log.info("[QQ_IMPORT] start, currentUserId={}, selectionsCount={}, messageCount={}",
                currentUserId, selections == null ? 0 : selections.size(), msgCount);

        if (selections == null || selections.isEmpty()) {
            return Result.error(400, "请选择要导入的好友或群");
        }

        for (Map<String, Object> sel : selections) {
            String chatType = (String) sel.get("chatType"); // "friend" or "group"
            String peerUid = (String) sel.get("peerUid");
            String name = (String) sel.get("name");
            log.info("[QQ_IMPORT] selection, type={}, id={}, name={}", chatType, peerUid, name);

            try {
                List<Map<String, Object>> messages = qqChatExporterClient.fetchMessages(chatType, peerUid, msgCount);
                log.info("[QQ_IMPORT] fetched raw messages count={}, type={}, id={}",
                        messages == null ? 0 : messages.size(), chatType, peerUid);
                // Convert to simple format
                List<Map<String, String>> simple = new ArrayList<>();
                for (Map<String, Object> msg : messages) {
                    String sender = markQqSender(extractSenderName(msg));
                    String text = extractMessageText(msg);
                    if (!text.isEmpty()) {
                        simple.add(Map.of("sender", sender, "content", text));
                    }
                }
                log.info("[QQ_IMPORT] simple messages count={}, type={}, id={}", simple.size(), chatType, peerUid);
                for (int i = 0; i < Math.min(simple.size(), 5); i++) {
                    Map<String, String> item = simple.get(i);
                    String preview = item.getOrDefault("content", "");
                    if (preview.length() > 30) {
                        preview = preview.substring(0, 30) + "...";
                    }
                    log.info("[QQ_IMPORT] simple preview[{}], sender={}, content={}",
                            i, item.getOrDefault("sender", ""), preview);
                }
                Map<String, Long> senderCounts = new java.util.LinkedHashMap<>();
                for (Map<String, String> item : simple) {
                    String sender = item.getOrDefault("sender", "unknown");
                    senderCounts.put(sender, senderCounts.getOrDefault(sender, 0L) + 1);
                }
                for (Map.Entry<String, Long> entry : senderCounts.entrySet()) {
                    log.info("[QQ_IMPORT] sender stats, sender={}, count={}", entry.getKey(), entry.getValue());
                }
                Map<String, Object> generation = chatRecordImportService.generateBotsWithDiagnosticsFromMessages(simple, currentUserId);
                List<Map<String, Object>> generated = (List<Map<String, Object>>) generation.getOrDefault("generated", List.of());
                List<Map<String, Object>> senderDiagnostics = (List<Map<String, Object>>) generation.getOrDefault("senderDiagnostics", List.of());
                int minRequired = (int) generation.getOrDefault("minRequired", 10);
                log.info("[QQ_IMPORT] generatedCount={}, type={}, id={}", generated.size(), chatType, peerUid);
                int rawCount = messages == null ? 0 : messages.size();
                int simpleCount = simple.size();
                int generatedCount = generated.size();

                String status;
                String message;
                if (rawCount == 0) {
                    status = "no_messages";
                    message = "未生成机器人：QQCE 返回消息为空";
                } else if (simpleCount == 0) {
                    status = "no_messages";
                    message = "未生成机器人：消息转换后为空（无可用文本消息）";
                } else if (generatedCount == 0) {
                    status = "no_bots_generated";
                    String reason = senderDiagnostics.stream()
                            .map(d -> String.valueOf(d.getOrDefault("skippedReason", "")))
                            .filter(s -> s != null && !s.isBlank())
                            .findFirst()
                            .orElse("没有可用发送者满足生成条件");
                    message = "未生成机器人：" + reason + "（阈值=" + minRequired + "）";
                } else {
                    status = "generated";
                    message = "已生成 " + generatedCount + " 个机器人";
                }

                Map<String, Object> resultItem = new LinkedHashMap<>();
                resultItem.put("selectionName", name);
                resultItem.put("selectionType", chatType);
                resultItem.put("selectionId", peerUid);
                resultItem.put("rawMessageCount", rawCount);
                resultItem.put("simpleMessageCount", simpleCount);
                resultItem.put("senderStats", senderCounts);
                resultItem.put("senderDiagnostics", senderDiagnostics);
                resultItem.put("minRequired", minRequired);
                resultItem.put("generatedCount", generatedCount);
                resultItem.put("status", status);
                resultItem.put("message", message);
                if (rawCount > 0 && simpleCount == 0) {
                    resultItem.put("rawPreview", buildRawPreview(messages));
                }
                if (generatedCount > 0) {
                    resultItem.put("bots", generated);
                }
                results.add(resultItem);
            } catch (Exception e) {
                Map<String, Object> errorItem = new LinkedHashMap<>();
                errorItem.put("selectionName", name);
                errorItem.put("selectionType", chatType);
                errorItem.put("selectionId", peerUid);
                errorItem.put("rawMessageCount", 0);
                errorItem.put("simpleMessageCount", 0);
                errorItem.put("senderStats", Map.of());
                errorItem.put("generatedCount", 0);
                errorItem.put("status", "error");
                errorItem.put("message", "导入失败：" + e.getMessage());
                results.add(errorItem);
            }
        }

        return Result.ok(results);
    }

    private String extractSenderName(Map<String, Object> msg) {
        String candidate = firstNonBlank(
                msg.get("sendRemarkName"),
                msg.get("sendMemberName"),
                msg.get("sendNickName"),
                msg.get("senderUin"),
                msg.get("senderUid")
        );
        if (candidate != null) return candidate;

        Object senderObj = msg.get("sender");
        if (senderObj instanceof Map<?, ?>) {
            Map<String, Object> s = (Map<String, Object>) senderObj;
            String nested = firstNonBlank(
                    s.get("name"),
                    s.get("nick"),
                    s.get("remark"),
                    s.get("uin"),
                    s.get("uid")
            );
            if (nested != null) return nested;
        }
        return "unknown";
    }

    private String extractMessageText(Map<String, Object> msg) {
        Object contentObj = msg.get("content");
        if (contentObj instanceof Map) {
            String fromMap = firstNonBlank(
                    ((Map) contentObj).get("content"),
                    ((Map) contentObj).get("text"),
                    ((Map) contentObj).get("textContent"),
                    ((Map) contentObj).get("atText"),
                    ((Map) contentObj).get("value")
            );
            if (fromMap != null) return fromMap.trim();
        }
        if (contentObj instanceof String && !((String) contentObj).isBlank()) return ((String) contentObj).trim();

        String legacy = firstNonBlank(
                msg.get("text"),
                msg.get("message"),
                msg.get("rawMessage")
        );
        if (legacy != null) return legacy.trim();

        Object elementsObj = msg.get("elements");
        if (elementsObj instanceof List<?> elements) {
            StringBuilder sb = new StringBuilder();
            for (Object elementObj : elements) {
                if (!(elementObj instanceof Map<?, ?> element)) continue;
                Object textElementObj = element.get("textElement");
                String textPiece = extractTextFromTextElement(textElementObj);
                if (textPiece != null && !textPiece.isBlank()) {
                    if (sb.length() > 0) sb.append(" ");
                    sb.append(textPiece.trim());
                } else if (element.containsKey("faceElement")) {
                    if (sb.length() > 0) sb.append(" ");
                    sb.append("[表情]");
                }
            }
            return sb.toString().trim();
        }

        return "";
    }

    private String extractTextFromTextElement(Object textElementObj) {
        if (textElementObj == null) return null;
        if (textElementObj instanceof String s) return s;
        if (textElementObj instanceof Map<?, ?> textMap) {
            return firstNonBlank(
                    textMap.get("content"),
                    textMap.get("text"),
                    textMap.get("textContent"),
                    textMap.get("atText"),
                    textMap.get("value")
            );
        }
        return null;
    }

    private String firstNonBlank(Object... values) {
        if (values == null) return null;
        for (Object value : values) {
            if (value == null) continue;
            String s = String.valueOf(value).trim();
            if (!s.isEmpty() && !"null".equalsIgnoreCase(s)) {
                return s;
            }
        }
        return null;
    }

    private String markQqSender(String sender) {
        if (sender == null || sender.isBlank()) return "QQ_unknown";
        String normalized = sender.trim();
        if (normalized.startsWith("QQ_")) return normalized;
        return "QQ_" + normalized;
    }

    private List<Map<String, Object>> buildRawPreview(List<Map<String, Object>> messages) {
        List<Map<String, Object>> preview = new ArrayList<>();
        if (messages == null || messages.isEmpty()) return preview;

        int limit = Math.min(messages.size(), 3);
        for (int i = 0; i < limit; i++) {
            Map<String, Object> msg = messages.get(i);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("index", i);
            item.put("topLevelKeys", new ArrayList<>(msg.keySet()));
            item.put("senderHints", extractSenderHints(msg));
            item.put("contentHints", extractContentHints(msg));
            preview.add(item);
        }
        return preview;
    }

    private Map<String, Object> extractSenderHints(Map<String, Object> msg) {
        Map<String, Object> senderHints = new LinkedHashMap<>();
        senderHints.put("sender", summarizeValue(msg.get("sender")));
        senderHints.put("senderName", summarizeValue(msg.get("senderName")));
        senderHints.put("senderUid", summarizeValue(msg.get("senderUid")));
        senderHints.put("from", summarizeValue(msg.get("from")));
        return senderHints;
    }

    private Map<String, Object> extractContentHints(Map<String, Object> msg) {
        Map<String, Object> contentHints = new LinkedHashMap<>();
        contentHints.put("content", summarizeValue(msg.get("content")));
        contentHints.put("text", summarizeValue(msg.get("text")));
        contentHints.put("message", summarizeValue(msg.get("message")));
        contentHints.put("rawMessage", summarizeValue(msg.get("rawMessage")));
        contentHints.put("elements", summarizeValue(msg.get("elements")));
        contentHints.put("messageChain", summarizeValue(msg.get("messageChain")));
        return contentHints;
    }

    private Object summarizeValue(Object value) {
        if (value == null) return null;
        if (value instanceof String s) {
            return truncate(s, 80);
        }
        if (value instanceof Map<?, ?> m) {
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("type", "map");
            summary.put("keys", new ArrayList<>(m.keySet()));
            return summary;
        }
        if (value instanceof List<?> list) {
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("type", "list");
            summary.put("size", list.size());
            if (!list.isEmpty()) {
                summary.put("firstItem", summarizeValue(list.get(0)));
            }
            return summary;
        }
        return truncate(String.valueOf(value), 80);
    }

    private String truncate(String text, int maxLen) {
        if (text == null) return null;
        return text.length() > maxLen ? text.substring(0, maxLen) + "..." : text;
    }
}
