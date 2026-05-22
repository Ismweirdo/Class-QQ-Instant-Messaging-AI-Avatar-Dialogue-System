package com.chatroom.controller;

import com.chatroom.common.Result;
import com.chatroom.model.entity.BotSkill;
import com.chatroom.security.SecurityUtil;
import com.chatroom.service.BotManager;
import com.chatroom.service.ChatRecordImportService;
import com.chatroom.service.QQChatExporterClient;
import com.chatroom.service.SkillDistillerService;
import com.chatroom.service.SkillDocImportService;
import lombok.extern.slf4j.Slf4j;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/bots")
@RequiredArgsConstructor
public class BotController {

    private final BotManager botManager;
    private final SkillDistillerService skillDistillerService;
    private final ChatRecordImportService chatRecordImportService;
    private final QQChatExporterClient qqChatExporterClient;
    private final SkillDocImportService skillDocImportService;
    private final com.chatroom.service.SkillFolderService skillFolderService;

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
    public Result<Map<String, Object>> register(@RequestBody Map<String, String> body) {
        Map<String, Object> result = botManager.registerBot(
                body.get("username"),
                body.get("nickname"),
                body.get("skillName"),
                body.get("systemPrompt"),
                body.get("fewShotExamples"),
                body.get("emotionProfile"),
                body.get("languageStyle"),
                body.get("apiEndpoint"),
                body.get("apiKey"),
                body.get("model"),
                body.get("password"));
        return Result.ok(result);
    }

    @DeleteMapping("/{userId}")
    public Result<String> deleteBot(@PathVariable Long userId) {
        botManager.permanentDelete(userId);
        return Result.ok("Bot deleted");
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

    // ==================== Active Mode ====================

    @PutMapping("/{botUserId}/active-mode")
    public Result<Map<String, Object>> setActiveMode(
            @PathVariable Long botUserId,
            @RequestBody Map<String, Object> body) {
        boolean enabled = Boolean.TRUE.equals(body.get("enabled"));
        int intervalSeconds = body.get("intervalSeconds") instanceof Number n
                ? n.intValue() : 60;
        BotManager.ActiveModeConfig config = botManager.setActiveMode(botUserId, enabled, intervalSeconds);
        return Result.ok(Map.of(
            "botUserId", botUserId,
            "enabled", config.enabled,
            "intervalSeconds", config.intervalSeconds
        ));
    }

    @GetMapping("/{botUserId}/active-mode")
    public Result<Map<String, Object>> getActiveMode(@PathVariable Long botUserId) {
        BotManager.ActiveModeConfig config = botManager.getActiveModeConfig(botUserId);
        return Result.ok(Map.of(
            "botUserId", botUserId,
            "enabled", config.enabled,
            "intervalSeconds", config.intervalSeconds
        ));
    }

    @GetMapping("/active-mode/list")
    public Result<Map<String, Object>> activeModeList() {
        return Result.ok(botManager.getAllActiveModeInfos());
    }

    @PostMapping("/distill")
    public Result<List<Map<String, Object>>> distill() {
        return Result.ok(skillDistillerService.distillSkills());
    }

    @PostMapping("/import")
    public Result<List<Map<String, Object>>> importRecords(@RequestParam("file") MultipartFile file) {
        try {
            Long userId = SecurityUtil.getCurrentUserId();
            List<Map<String, Object>> results = chatRecordImportService.importAndGenerate(file, userId);
            return Result.ok(results);
        } catch (Exception e) {
            return Result.error(500, "导入失败: " + e.getMessage());
        }
    }

    @PostMapping("/skills/import")
    public Result<Map<String, Object>> importSkillDoc(@RequestParam("file") MultipartFile file) {
        try {
            BotSkill skill = skillDocImportService.importSkillDoc(file);
            return Result.ok(Map.of(
                    "skillId", skill.getId(),
                    "botUserId", skill.getBotUserId(),
                    "skillName", skill.getSkillName()
            ));
        } catch (Exception e) {
            return Result.error(500, "导入失败: " + e.getMessage());
        }
    }

    @PostMapping("/skills/import-url")
    public Result<Map<String, Object>> importSkillFromUrl(@RequestBody Map<String, String> body) {
        try {
            String url = body.get("url");
            if (url == null || url.isBlank()) {
                return Result.error(400, "URL 不能为空");
            }
            BotSkill skill = skillDocImportService.importFromUrl(url.trim());
            Map<String, Object> data = new java.util.LinkedHashMap<>();
            data.put("skillId", skill.getId());
            data.put("botUserId", skill.getBotUserId());
            data.put("skillName", skill.getSkillName());
            data.put("botUsername", "skill_" + skill.getBotUserId());
            return Result.ok(data);
        } catch (Exception e) {
            log.error("URL skill import failed", e);
            return Result.error(500, "导入失败: " + e.getMessage());
        }
    }

    @GetMapping("/skills/{botUserId}/files")
    public Result<List<Map<String, Object>>> listSkillFiles(@PathVariable Long botUserId) {
        List<BotSkill> skills = botManager.getAllBots().stream()
                .filter(s -> s.getBotUserId().equals(botUserId))
                .toList();
        if (skills.isEmpty() || skills.get(0).getSkillFolder() == null) {
            return Result.error(404, "Bot 没有关联的 skill 文件夹");
        }
        return Result.ok(skillFolderService.listFiles(skills.get(0).getSkillFolder()));
    }

    @PostMapping("/skills/{botUserId}/custom")
    public Result<Map<String, Object>> uploadCustomFile(@PathVariable Long botUserId,
                                                         @RequestParam("file") MultipartFile file) {
        try {
            List<BotSkill> skills = botManager.getAllBots().stream()
                    .filter(s -> s.getBotUserId().equals(botUserId))
                    .toList();
            if (skills.isEmpty() || skills.get(0).getSkillFolder() == null) {
                return Result.error(404, "Bot 没有关联的 skill 文件夹");
            }
            String folder = skills.get(0).getSkillFolder();
            Path dest = skillFolderService.addCustomFile(folder,
                    file.getOriginalFilename(), file.getBytes());
            Map<String, Object> data = new java.util.LinkedHashMap<>();
            data.put("path", folder + "/custom/" + file.getOriginalFilename());
            data.put("botUserId", botUserId);
            return Result.ok(data);
        } catch (Exception e) {
            log.error("Custom file upload failed", e);
            return Result.error(500, "上传失败: " + e.getMessage());
        }
    }

    // ==================== QQ Chat Exporter Integration ====================

    @GetMapping("/qq/health")
    public Result<Map<String, Object>> qqHealth(@RequestHeader(value = "X-QQCE-Token", required = false) String qqceToken) {
        log.debug("QQ health check - token received: {}", qqceToken != null ? "yes (len=" + qqceToken.length() + ")" : "no");
        return Result.ok(qqChatExporterClient.healthCheck(qqceToken));
    }

    @GetMapping("/qq/friends")
    public Result<List<Map<String, Object>>> qqFriends(@RequestHeader(value = "X-QQCE-Token", required = false) String qqceToken) {
        log.debug("QQ friends request - token received: {}", qqceToken != null ? "yes (len=" + qqceToken.length() + ")" : "no");
        return Result.ok(qqChatExporterClient.getFriends(qqceToken));
    }

    @GetMapping("/qq/groups")
    public Result<List<Map<String, Object>>> qqGroups(@RequestHeader(value = "X-QQCE-Token", required = false) String qqceToken) {
        return Result.ok(qqChatExporterClient.getGroups(qqceToken));
    }

    @PostMapping("/qq/import")
    public Result<List<Map<String, Object>>> qqImport(@RequestBody Map<String, Object> body,
                                                       @RequestHeader(value = "X-QQCE-Token", required = false) String qqceToken) {
        Long userId = SecurityUtil.getCurrentUserId();
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
                List<Map<String, Object>> messages = qqChatExporterClient.fetchMessages(chatType, peerUid, msgCount, qqceToken);
                log.info("QQCE raw message count: {}", messages.size());
                // Convert to simple format
                List<Map<String, String>> simple = new ArrayList<>();
                int emptyCount = 0, filteredCount = 0;
                boolean isFriendChat = "friend".equals(chatType);
                if (isFriendChat) log.info("QQCE friend chat filter: peerUid={}", peerUid);
                for (int i = 0; i < messages.size(); i++) {
                    Map<String, Object> msg = messages.get(i);
                    String sender = extractSenderName(msg);
                    String text = extractMessageText(msg);
                    // For 1-on-1 friend chats, only keep messages from the selected friend
                    if (isFriendChat && peerUid != null) {
                        String senderUid = (String) msg.get("senderUid");
                        if (i < 2) log.info("QQCE msg[{}]: senderUid={}, peerUid={}", i, senderUid, peerUid);
                        if (senderUid == null || !senderUid.equals(peerUid)) {
                            filteredCount++;
                            continue;
                        }
                    }
                    if (i < 3) {
                        log.info("QQCE msg[{}]: sender=[{}], text=[{}]", i, sender, text);
                    }
                    if (!text.isEmpty()) {
                        simple.add(Map.of("sender", sender, "content", text));
                    } else {
                        emptyCount++;
                    }
                }
                log.info("QQCE conversion: {} total, {} converted, {} empty, {} filtered (not selected friend)", messages.size(), simple.size(), emptyCount, filteredCount);
                // Generate bots from messages
                List<Map<String, Object>> botResults = chatRecordImportService.generateBotsFromMessages(simple, userId);
                results.add(Map.of(
                    "name", name,
                    "chatType", chatType,
                    "messageCount", simple.size(),
                    "botsGenerated", botResults.size(),
                    "bots", botResults,
                    "status", "imported"
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
        // QQCE v5+ format: sendNickName, sendRemarkName, sendMemberName
        String name = (String) msg.get("sendNickName");
        if (name != null && !name.isEmpty()) return name;
        name = (String) msg.get("sendRemarkName");
        if (name != null && !name.isEmpty()) return name;
        name = (String) msg.get("sendMemberName");
        if (name != null && !name.isEmpty()) return name;
        // Old QQCE format: sender { name, uid, uin }
        Object senderObj = msg.get("sender");
        if (senderObj instanceof Map) {
            Map<String, Object> s = (Map<String, Object>) senderObj;
            return String.valueOf(s.getOrDefault("name",
                    s.getOrDefault("uid", s.getOrDefault("uin", "unknown"))));
        }
        return String.valueOf(msg.getOrDefault("senderName",
                msg.getOrDefault("sender", msg.getOrDefault("senderUid", "unknown"))));
    }

    private String extractMessageText(Map<String, Object> msg) {
        // QQCE v5+ format: elements[{textElement: {content: "..."}}]
        Object elementsObj = msg.get("elements");
        if (elementsObj instanceof List) {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> elements = (List<Map<String, Object>>) elementsObj;
            StringBuilder sb = new StringBuilder();
            for (Map<String, Object> elem : elements) {
                Object te = elem.get("textElement");
                if (te instanceof Map) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> textElem = (Map<String, Object>) te;
                    Object c = textElem.get("content");
                    if (c instanceof String && !((String) c).isEmpty()) {
                        if (sb.length() > 0) sb.append(" ");
                        sb.append((String) c);
                    }
                }
            }
            if (sb.length() > 0) return sb.toString();
        }
        // Old QQCE format: content { text: "..." } or content: "..."
        Object contentObj = msg.get("content");
        if (contentObj instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> cm = (Map<String, Object>) contentObj;
            return String.valueOf(cm.getOrDefault("text", ""));
        }
        if (contentObj instanceof String) return (String) contentObj;
        return String.valueOf(msg.getOrDefault("text", msg.getOrDefault("content", "")));
    }
}
