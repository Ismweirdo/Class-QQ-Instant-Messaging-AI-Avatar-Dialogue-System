package com.chatroom.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.chatroom.common.Constants;
import com.chatroom.mapper.FriendMapper;
import com.chatroom.model.entity.BotSkill;
import com.chatroom.model.entity.Friend;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ChatRecordImportService {

    private final BotManager botManager;
    private final FriendMapper friendMapper;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private static final int MIN_MESSAGES_PER_BOT = 10;
    private static final int MAX_BOTS_PER_IMPORT = 20;

    // Emotion dictionary
    private static final Map<String, String> EMOTION_DICT = new LinkedHashMap<>() {{
        put("joy", "开心|高兴|快乐|哈哈|嘿嘿|笑|嘻嘻|棒|太棒|赞|好开心|笑死|牛|牛逼|厉害|绝了|hh");
        put("anger", "气|烦|讨厌|滚|恶心|sb|沙比|卧槽|tmd|fuck|去死|麻了|cnm");
        put("sad", "难过|伤心|哭|emo|难受|心痛|郁闷|崩溃|泪|唉|哎|不开心|好难|sad");
        put("surprise", "天哪|我去|哇|震惊|竟然|居然|没想到|离谱|omg|woc|我靠|惊了");
        put("fear", "怕|害怕|恐怖|吓人|不敢|紧张|担心|慌|焦虑");
        put("care", "关心|注意|小心|保重|照顾|还好吧|没事吧|多吃点|休息|注意身体|别太累");
    }};

    private static final List<String> TONE_WORDS = List.of(
            "呢", "哦", "呀", "吧", "嘛", "哈", "啊", "啦", "噢", "哟", "咯", "哎");

    // QQ Chat Exporter TXT: "2024-01-01 12:00:00 昵称: 消息"
    private static final Pattern QQCE_TXT_MSG = Pattern.compile(
            "^\\d{4}[-/]\\d{2}[-/]\\d{2}\\s+\\d{2}:\\d{2}:\\d{2}\\s+(.+?)[:：]\\s*(.+)$");
    // WeChat TXT: "昵称  2024-01-01 12:00:00"
    private static final Pattern WECHAT_HEADER = Pattern.compile(
            "^(.+?)\\s{2,}\\d{4}[-/]\\d{2}[-/]\\d{2}\\s+\\d{2}:\\d{2}:\\d{2}$");
    // Generic: "昵称: 消息"
    private static final Pattern GENERIC_MSG = Pattern.compile(
            "^(.{1,30})[:：]\\s*(.+)$");
    // QQCE TXT header/footer markers
    private static final Pattern QQCE_MARKER = Pattern.compile(
            "^\\[QQChatExporter|^====|^聊天对象|^导出时间|^消息总数|^\\[导出完成]");

    /**
     * Import chat records and generate bots.
     * Supports: QQ Chat Exporter JSON, QQCE TXT, WeChat TXT, JSONL, generic JSON
     */
    public List<Map<String, Object>> importAndGenerate(MultipartFile file, Long currentUserId) throws Exception {
        String filename = file.getOriginalFilename();
        String content = new String(file.getBytes(), StandardCharsets.UTF_8);

        List<Map<String, String>> messages;

        if (filename != null && (filename.endsWith(".json") || filename.endsWith(".jsonl"))) {
            messages = parseJson(content);
        } else {
            messages = parseTextFormat(content);
        }

        if (messages.isEmpty()) {
            throw new RuntimeException("未能从文件中解析出消息。支持的格式：\n"
                    + "1. QQ Chat Exporter 导出的 JSON/TXT\n"
                    + "2. 微信聊天记录导出的 TXT\n"
                    + "3. 通用 JSONL (每行 {\"sender\":\"名\",\"content\":\"消息\"})\n"
                    + "4. 通用格式 TXT (昵称: 消息)");
        }

        log.info("Parsed {} messages from {}", messages.size(), filename);
        return generateBotsFromMessages(messages, currentUserId);
    }

    /**
     * Generate bots directly from parsed sender/content messages.
     * Used by both file import and QQ import flows.
     */
    public List<Map<String, Object>> generateBotsFromMessages(List<Map<String, String>> messages, Long currentUserId) {
        if (messages == null || messages.isEmpty()) {
            return List.of();
        }
        return generateBots(messages, currentUserId);
    }

    /**
     * QQ import diagnostics: generate bots and return per-sender eligibility/result details.
     */
    public Map<String, Object> generateBotsWithDiagnosticsFromMessages(List<Map<String, String>> messages, Long currentUserId) {
        Map<String, Object> result = new LinkedHashMap<>();
        List<Map<String, Object>> generated = new ArrayList<>();
        List<Map<String, Object>> senderDiagnostics = new ArrayList<>();
        result.put("minRequired", MIN_MESSAGES_PER_BOT);

        if (messages == null || messages.isEmpty()) {
            result.put("generated", generated);
            result.put("senderDiagnostics", senderDiagnostics);
            return result;
        }

        Map<String, List<String>> bySender = messages.stream()
                .collect(Collectors.groupingBy(
                        m -> m.get("sender"),
                        Collectors.mapping(m -> m.get("content"), Collectors.toList())));

        int botIndex = 0;
        for (Map.Entry<String, List<String>> entry : bySender.entrySet()) {
            String senderName = entry.getKey();
            List<String> senderMsgs = entry.getValue();
            int messageCount = senderMsgs.size();
            boolean eligible = messageCount >= MIN_MESSAGES_PER_BOT;

            Map<String, Object> diag = new LinkedHashMap<>();
            diag.put("sender", senderName);
            diag.put("messageCount", messageCount);
            diag.put("minRequired", MIN_MESSAGES_PER_BOT);
            diag.put("eligible", eligible);

            if (!eligible) {
                diag.put("skippedReason", "消息数不足，当前" + messageCount + "条，需要" + MIN_MESSAGES_PER_BOT + "条");
                senderDiagnostics.add(diag);
                continue;
            }
            if (botIndex >= MAX_BOTS_PER_IMPORT) {
                diag.put("eligible", false);
                diag.put("skippedReason", "超出单次最多生成" + MAX_BOTS_PER_IMPORT + "个机器人的限制");
                senderDiagnostics.add(diag);
                continue;
            }

            Map<String, Double> emotions = extractEmotions(senderMsgs);
            Map<String, Object> style = extractStyle(senderMsgs);
            String systemPrompt = buildPrompt(senderName, emotions, style);
            String emotionJson = toJson(emotions);
            String styleJson = toJson(style);

            try {
                String username = "imp_" + sanitize(senderName) + "_" + (System.currentTimeMillis() % 10000);
                BotSkill skill = botManager.registerBot(
                        username, senderName, "导入_" + senderName,
                        systemPrompt, "[]", emotionJson, styleJson,
                        null, null, null);
                ensureFriendRelation(currentUserId, skill.getBotUserId());

                Map<String, Object> g = new LinkedHashMap<>();
                g.put("botUserId", skill.getBotUserId());
                g.put("nickname", senderName);
                g.put("messageCount", messageCount);
                g.put("dominantEmotion", emotions.entrySet().stream()
                        .max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse("joy"));
                g.put("systemPrompt", systemPrompt);
                g.put("emotionProfile", emotions);
                g.put("languageStyle", style);
                generated.add(g);
                botIndex++;
                diag.put("skippedReason", "");
            } catch (Exception e) {
                diag.put("eligible", false);
                diag.put("skippedReason", "Bot注册失败：" + e.getMessage());
            }
            senderDiagnostics.add(diag);
        }

        result.put("generated", generated);
        result.put("senderDiagnostics", senderDiagnostics);
        return result;
    }

    // ==================== JSON Parsing ====================

    private List<Map<String, String>> parseJson(String content) throws Exception {
        // Try QQ Chat Exporter format first (has "messages" array with "sender" objects)
        try {
            Map<String, Object> root = objectMapper.readValue(content, new TypeReference<>() {});
            if (root.containsKey("messages") && root.get("messages") instanceof List) {
                return parseQQCEJson((List<Map<String, Object>>) root.get("messages"));
            }
        } catch (Exception ignored) {}

        // Try JSONL (one JSON object per line)
        if (content.trim().startsWith("{")) {
            return parseJsonl(content);
        }

        // Try JSON array
        try {
            List<Map<String, Object>> list = objectMapper.readValue(content, new TypeReference<>() {});
            if (list != null && !list.isEmpty()) {
                return parseMessageList(list);
            }
        } catch (Exception e) {
            log.warn("Failed to parse as JSON array: {}", e.getMessage());
        }

        return List.of();
    }

    private List<Map<String, String>> parseQQCEJson(List<Map<String, Object>> rawMessages) {
        List<Map<String, String>> messages = new ArrayList<>();
        for (Map<String, Object> msg : rawMessages) {
            try {
                String sender;
                Object senderObj = msg.get("sender");
                if (senderObj instanceof Map) {
                    Map<String, Object> s = (Map<String, Object>) senderObj;
                    sender = String.valueOf(s.getOrDefault("name",
                            s.getOrDefault("uid", s.getOrDefault("uin", "unknown"))));
                } else {
                    sender = String.valueOf(senderObj);
                }

                String text;
                Object contentObj = msg.get("content");
                if (contentObj instanceof Map) {
                    Map<String, Object> c = (Map<String, Object>) contentObj;
                    text = String.valueOf(c.getOrDefault("text", ""));
                } else if (contentObj instanceof String) {
                    text = (String) contentObj;
                } else {
                    text = String.valueOf(msg.getOrDefault("content", ""));
                }

                if (!text.isEmpty() && !text.equals("null")) {
                    messages.add(Map.of("sender", sender, "content", text));
                }
            } catch (Exception ignored) {}
        }
        log.info("Parsed {} messages from QQ Chat Exporter JSON", messages.size());
        return messages;
    }

    private List<Map<String, String>> parseJsonl(String content) {
        List<Map<String, String>> messages = new ArrayList<>();
        for (String line : content.split("\n")) {
            line = line.trim();
            if (line.isEmpty()) continue;
            try {
                Map<String, Object> obj = objectMapper.readValue(line, new TypeReference<>() {});
                messages.addAll(parseMessageList(List.of(obj)));
            } catch (Exception ignored) {}
        }
        return messages;
    }

    private List<Map<String, String>> parseMessageList(List<Map<String, Object>> list) {
        List<Map<String, String>> messages = new ArrayList<>();
        for (Map<String, Object> obj : list) {
            // Try sender object first (QQCE format)
            String sender = null;
            Object senderObj = obj.get("sender");
            if (senderObj instanceof Map) {
                Map<String, Object> s = (Map<String, Object>) senderObj;
                sender = String.valueOf(s.getOrDefault("name",
                        s.getOrDefault("uid", s.getOrDefault("uin", ""))));
            }
            if (sender == null || sender.isEmpty() || sender.equals("null")) {
                sender = String.valueOf(obj.getOrDefault("sender",
                        obj.getOrDefault("name", obj.getOrDefault("user", "unknown"))));
            }

            // Try content object first (QQCE format)
            String text = null;
            Object contentObj = obj.get("content");
            if (contentObj instanceof Map) {
                Map<String, Object> c = (Map<String, Object>) contentObj;
                text = String.valueOf(c.getOrDefault("text", ""));
            }
            if (text == null || text.isEmpty() || text.equals("null")) {
                text = String.valueOf(obj.getOrDefault("content",
                        obj.getOrDefault("message", obj.getOrDefault("text", ""))));
            }

            if (!text.isEmpty() && !text.equals("null") && !sender.isEmpty() && !sender.equals("null")) {
                messages.add(Map.of("sender", sender, "content", text));
            }
        }
        return messages;
    }

    // ==================== TXT Parsing ====================

    private List<Map<String, String>> parseTextFormat(String content) {
        List<Map<String, String>> messages = new ArrayList<>();

        // Detect format type
        boolean isQQCE = content.contains("[QQChatExporter");

        if (isQQCE) {
            messages = parseQQCEText(content);
        }

        // If QQCE parsing yielded nothing, try WeChat/generic
        if (messages.isEmpty()) {
            messages = parseWeChatOrGeneric(content);
        }

        return messages;
    }

    private List<Map<String, String>> parseQQCEText(String content) {
        List<Map<String, String>> messages = new ArrayList<>();
        for (String line : content.split("\n")) {
            line = line.trim();
            if (line.isEmpty() || QQCE_MARKER.matcher(line).find()) continue;

            // "2024-01-01 12:00:00 昵称: 消息内容"
            Matcher m = QQCE_TXT_MSG.matcher(line);
            if (m.matches()) {
                String sender = m.group(1).trim();
                String text = m.group(2).trim();
                if (!text.isEmpty() && !isSystemMsg(text)) {
                    messages.add(Map.of("sender", sender, "content", text));
                }
            }
        }
        log.info("Parsed {} messages from QQ Chat Exporter TXT", messages.size());
        return messages;
    }

    private List<Map<String, String>> parseWeChatOrGeneric(String content) {
        List<Map<String, String>> messages = new ArrayList<>();
        String currentSender = null;
        String[] lines = content.split("\n");

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.isEmpty()) continue;

            // WeChat: "昵称  2024-01-01 12:00:00" → next line(s) are messages
            Matcher wh = WECHAT_HEADER.matcher(line);
            if (wh.matches()) {
                currentSender = wh.group(1).trim();
                // Read following lines as messages until next header or blank
                StringBuilder msgBuf = new StringBuilder();
                i++;
                while (i < lines.length) {
                    String next = lines[i].trim();
                    if (next.isEmpty() || WECHAT_HEADER.matcher(next).matches()) {
                        i--; // backtrack so outer loop processes the header
                        break;
                    }
                    if (msgBuf.length() > 0) msgBuf.append(" ");
                    msgBuf.append(next);
                    i++;
                }
                String text = msgBuf.toString().trim();
                if (!text.isEmpty() && currentSender != null && !isSystemMsg(text)) {
                    messages.add(Map.of("sender", currentSender, "content", text));
                }
                continue;
            }

            // Generic: "昵称: 消息内容"
            Matcher gm = GENERIC_MSG.matcher(line);
            if (gm.matches()) {
                String sender = gm.group(1).trim();
                String text = gm.group(2).trim();
                if (!text.isEmpty() && !isSystemMsg(text)
                        && !sender.contains("http") && sender.length() < 20) {
                    messages.add(Map.of("sender", sender, "content", text));
                }
            }
        }
        log.info("Parsed {} messages from WeChat/Generic TXT", messages.size());
        return messages;
    }

    private boolean isSystemMsg(String text) {
        return text.startsWith("[图片]") && text.length() < 10
                || text.startsWith("[文件]") && text.length() < 10
                || text.equals("[表情]")
                || text.equals("[语音]")
                || text.equals("[视频]");
    }

    // ==================== Bot Generation ====================

    private List<Map<String, Object>> generateBots(List<Map<String, String>> messages, Long currentUserId) {
        Map<String, List<String>> bySender = messages.stream()
                .collect(Collectors.groupingBy(
                        m -> m.get("sender"),
                        Collectors.mapping(m -> m.get("content"), Collectors.toList())));

        log.info("Found {} unique senders", bySender.size());

        List<Map<String, Object>> results = new ArrayList<>();
        int botIndex = 0;

        for (Map.Entry<String, List<String>> entry : bySender.entrySet()) {
            String senderName = entry.getKey();
            List<String> senderMsgs = entry.getValue();

            if (senderMsgs.size() < MIN_MESSAGES_PER_BOT) {
                log.info("Skipping {} ({} messages, need >= {})", senderName, senderMsgs.size(), MIN_MESSAGES_PER_BOT);
                continue;
            }
            if (botIndex >= MAX_BOTS_PER_IMPORT) break;

            Map<String, Double> emotions = extractEmotions(senderMsgs);
            Map<String, Object> style = extractStyle(senderMsgs);
            String systemPrompt = buildPrompt(senderName, emotions, style);
            String emotionJson = toJson(emotions);
            String styleJson = toJson(style);

            try {
                String username = "imp_" + sanitize(senderName) + "_" + (System.currentTimeMillis() % 10000);
                BotSkill skill = botManager.registerBot(
                        username, senderName, "导入_" + senderName,
                        systemPrompt, "[]", emotionJson, styleJson,
                        null, null, null);
                ensureFriendRelation(currentUserId, skill.getBotUserId());

                Map<String, Object> r = new LinkedHashMap<>();
                r.put("botUserId", skill.getBotUserId());
                r.put("nickname", senderName);
                r.put("messageCount", senderMsgs.size());
                r.put("dominantEmotion", emotions.entrySet().stream()
                        .max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse("joy"));
                r.put("systemPrompt", systemPrompt);
                r.put("emotionProfile", emotions);
                r.put("languageStyle", style);
                results.add(r);
                botIndex++;
            } catch (Exception e) {
                log.error("Failed to register bot for {}", senderName, e);
            }
        }

        log.info("Generated {} bots from imported records", results.size());
        return results;
    }

    private void ensureFriendRelation(Long currentUserId, Long botUserId) {
        if (currentUserId == null || botUserId == null) return;

        LambdaQueryWrapper<Friend> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Friend::getUserId, currentUserId)
                .eq(Friend::getFriendId, botUserId);
        Friend existing = friendMapper.selectOne(wrapper);
        if (existing != null) {
            if (existing.getStatus() != Constants.FRIEND_STATUS_ACCEPTED) {
                existing.setStatus(Constants.FRIEND_STATUS_ACCEPTED);
                friendMapper.updateById(existing);
            }
            return;
        }

        Friend relation = new Friend();
        relation.setUserId(currentUserId);
        relation.setFriendId(botUserId);
        relation.setStatus(Constants.FRIEND_STATUS_ACCEPTED);
        friendMapper.insert(relation);
    }

    // ==================== Feature Extraction ====================

    private Map<String, Double> extractEmotions(List<String> texts) {
        Map<String, Double> dist = new LinkedHashMap<>();
        int total = 0;
        for (Map.Entry<String, String> e : EMOTION_DICT.entrySet()) {
            String[] keywords = e.getValue().split("\\|");
            int count = 0;
            for (String t : texts) {
                for (String kw : keywords) {
                    if (t.contains(kw)) { count++; break; }
                }
            }
            dist.put(e.getKey(), (double) count);
            total += count;
        }
        if (total > 0) {
            for (String k : dist.keySet()) {
                dist.put(k, Math.round(dist.get(k) / total * 1000.0) / 1000.0);
            }
        }
        return dist;
    }

    private Map<String, Object> extractStyle(List<String> texts) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("avgSentenceLen", Math.round(texts.stream().mapToInt(String::length).average().orElse(15.0) * 10.0) / 10.0);
        s.put("useEmoji", texts.stream().anyMatch(t -> t.matches(".*[😀-🙏🌀-🗿🚀-🛿☀-⛿✂-➰].*")));
        s.put("useToneWords", texts.stream().anyMatch(t -> TONE_WORDS.stream().anyMatch(t::contains)));
        s.put("questionRatio", Math.round(texts.stream().filter(t -> t.contains("?") || t.contains("？") || t.contains("吗")).count() * 100.0 / texts.size()) / 100.0);
        return s;
    }

    private String buildPrompt(String name, Map<String, Double> emotions, Map<String, Object> style) {
        String dominant = emotions.entrySet().stream()
                .max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse("joy");
        Map<String, String> desc = Map.of(
                "joy", "开朗乐观，喜欢用轻松愉快的语气聊天",
                "anger", "个性直率，说话直接爽快",
                "sad", "心思细腻，语气柔和带点忧郁",
                "surprise", "反应夸张，容易一惊一乍",
                "fear", "比较谨慎，说话小心翼翼",
                "care", "非常贴心，处处为对方着想");
        boolean emoji = (boolean) style.getOrDefault("useEmoji", false);
        boolean tone = (boolean) style.getOrDefault("useToneWords", false);

        return "你的名字是" + name + "。" + desc.getOrDefault(dominant, "性格随和自然。")
                + "回复要简短自然，不超过80字。"
                + (emoji ? "可以适当使用Emoji。" : "")
                + (tone ? "可以适当使用语气词。" : "")
                + "不要透露你是AI。";
    }

    private String toJson(Object obj) {
        try { return objectMapper.writeValueAsString(obj); } catch (Exception e) { return "{}"; }
    }

    private String sanitize(String s) {
        String cleaned = s == null ? "" : s.replaceAll("[^a-zA-Z0-9\\u4e00-\\u9fa5]", "");
        if (cleaned.isEmpty()) return "user";
        return cleaned.substring(0, Math.min(cleaned.length(), 10));
    }
}
