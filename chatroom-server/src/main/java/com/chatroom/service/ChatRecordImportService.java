package com.chatroom.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.chatroom.model.entity.BotSkill;
import com.chatroom.model.entity.Friend;
import com.chatroom.model.entity.User;
import com.chatroom.mapper.FriendMapper;
import com.chatroom.mapper.UserMapper;
import com.chatroom.common.Constants;
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
    private final UserMapper userMapper;
    private final ObjectMapper objectMapper = new ObjectMapper();

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

    private static final List<String> SLANG_WORDS = List.of(
            "哈哈", "hh", "笑死", "绝了", "离谱", "牛", "牛逼", "好家伙", "啊这", "emo", "无语", "麻了");

    private static final List<String> AVOID_WORDS = List.of("请问", "抱歉", "不好意思", "必须", "立刻", "绝对");

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

    /** Generate bots from already-parsed messages (used by QQ import flow) */
    public List<Map<String, Object>> generateBotsFromMessages(List<Map<String, String>> messages, Long creatorUserId) {
        log.info("generateBotsFromMessages called: {} messages, creatorUserId={}", messages.size(), creatorUserId);
        return generateBots(messages, creatorUserId);
    }

    /**
     * Import chat records and generate bots.
     * Supports: QQ Chat Exporter JSON, QQCE TXT, WeChat TXT, JSONL, generic JSON
     */
    public List<Map<String, Object>> importAndGenerate(MultipartFile file, Long creatorUserId) throws Exception {
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
        return generateBots(messages, creatorUserId);
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

    private List<Map<String, Object>> generateBots(List<Map<String, String>> messages, Long creatorUserId) {
        Map<String, List<String>> bySender = messages.stream()
                .collect(Collectors.groupingBy(
                        m -> m.get("sender"),
                        Collectors.mapping(m -> m.get("content"), Collectors.toList())));

        log.info("Found {} unique senders", bySender.size());

        // Build set of existing bot nicknames already added as friends
        Set<String> existingBotNames = new HashSet<>();
        if (creatorUserId != null) {
            List<Friend> friends = friendMapper.selectList(
                    new LambdaQueryWrapper<Friend>()
                            .eq(Friend::getStatus, Constants.FRIEND_STATUS_ACCEPTED)
                            .and(w -> w.eq(Friend::getUserId, creatorUserId).or().eq(Friend::getFriendId, creatorUserId)));
            for (Friend f : friends) {
                Long friendUserId = f.getUserId().equals(creatorUserId) ? f.getFriendId() : f.getUserId();
                User u = userMapper.selectById(friendUserId);
                if (u != null && u.getIsBot() != null && u.getIsBot() == 1) {
                    existingBotNames.add(u.getNickname());
                }
            }
        }

        List<Map<String, Object>> results = new ArrayList<>();
        int botIndex = 0;

        for (Map.Entry<String, List<String>> entry : bySender.entrySet()) {
            String senderName = entry.getKey();
            List<String> senderMsgs = entry.getValue();

            if (existingBotNames.contains(senderName)) {
                log.info("Skipping {} (bot already exists)", senderName);
                continue;
            }
            if (senderMsgs.size() < 10) {
                log.info("Skipping {} ({} messages, need >= 10)", senderName, senderMsgs.size());
                continue;
            }
            if (botIndex >= 20) break;

            Map<String, Double> emotions = extractEmotions(senderMsgs);
            Map<String, Object> style = extractStyle(senderMsgs);
            String systemPrompt = buildPrompt(senderName, emotions, style);
            Map<String, Object> emotionProfileDoc = buildEmotionProfileDoc(emotions);
            Map<String, Object> languageStyleDoc = buildLanguageStyleDoc(style);
            String fewShotJson = buildFewShotExamples(senderMsgs);
            String emotionJson = toJson(emotionProfileDoc);
            String styleJson = toJson(languageStyleDoc);

            try {
                String username = "imp_" + sanitize(senderName) + "_" + (System.currentTimeMillis() % 10000);
                BotSkill skill = botManager.registerBot(
                        username, senderName, "导入_" + senderName,
                        systemPrompt, fewShotJson, emotionJson, styleJson,
                        null, null, null);

                // Auto-create friend relationship so bot appears in contact list
                // Only one record: creatorUserId -> botUserId (matching the normal friend flow)
                if (creatorUserId != null && !creatorUserId.equals(skill.getBotUserId())) {
                    Friend f1 = new Friend();
                    f1.setUserId(creatorUserId);
                    f1.setFriendId(skill.getBotUserId());
                    f1.setStatus(Constants.FRIEND_STATUS_ACCEPTED);
                    friendMapper.insert(f1);
                    log.info("Created friend relationship: user {} -> bot {} ({})", creatorUserId, skill.getBotUserId(), senderName);
                } else {
                    log.warn("Skipped friend creation for bot {}: creatorUserId={}, botUserId={}", senderName, creatorUserId, skill.getBotUserId());
                }

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
        List<Integer> lens = texts.stream().map(String::length).toList();
        double avgLen = lens.stream().mapToInt(Integer::intValue).average().orElse(15.0);
        int minLen = lens.stream().mapToInt(Integer::intValue).min().orElse(6);
        int maxLen = lens.stream().mapToInt(Integer::intValue).max().orElse(22);
        int totalChars = lens.stream().mapToInt(Integer::intValue).sum();
        long commaCount = texts.stream().mapToLong(t -> t.chars().filter(c -> c == '，' || c == ',').count()).sum();
        long exclaimCount = texts.stream().mapToLong(t -> t.chars().filter(c -> c == '！' || c == '!').count()).sum();
        s.put("avgSentenceLen", Math.round(avgLen * 10.0) / 10.0);
        s.put("sentenceLenMedian", median(lens));
        s.put("sentenceLenRange", minLen + "-" + maxLen);
        s.put("commaDensity", Math.round(commaCount * 100.0 / Math.max(1, totalChars)) / 100.0);
        s.put("exclaimRatio", Math.round(exclaimCount * 100.0 / Math.max(1, texts.size())) / 100.0);
        long emojiCount = texts.stream().filter(t -> t.matches(".*[😀-🙏🌀-🗿🚀-🛿☀-⛿✂-➰].*")).count();
        s.put("useEmoji", emojiCount > 0);
        s.put("emojiRate", Math.round(emojiCount * 100.0 / Math.max(1, texts.size())) / 100.0);
        long toneWordCount = texts.stream().filter(t -> TONE_WORDS.stream().anyMatch(t::contains)).count();
        s.put("useToneWords", toneWordCount > 0);
        long questionCount = texts.stream().filter(t -> t.contains("?") || t.contains("？") || t.contains("吗")).count();
        s.put("questionRatio", Math.round(questionCount * 100.0 / Math.max(1, texts.size())) / 100.0);
        long slangCount = texts.stream().filter(t -> SLANG_WORDS.stream().anyMatch(t::contains)).count();
        s.put("slangRatio", Math.round(slangCount * 100.0 / Math.max(1, texts.size())) / 100.0);
        s.put("habitOpenings", topAffixes(texts, true, 4));
        s.put("habitEndings", topAffixes(texts, false, 3));
        s.put("avoidWords", new ArrayList<>(AVOID_WORDS));
        s.put("responsePacing", questionCount > texts.size() * 0.3 ? "爱追问" : "直接");
        return s;
    }

    private Map<String, Object> buildEmotionProfileDoc(Map<String, Double> emotions) {
        String dominant = emotions.entrySet().stream()
                .max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse("joy");
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("base_tone", mapBaseTone(dominant));
        doc.put("distribution", new LinkedHashMap<>(emotions));
        doc.put("emotion_variance", Math.round(calcVariance(emotions.values()) * 100.0) / 100.0);
        return doc;
    }

    private Map<String, Object> buildLanguageStyleDoc(Map<String, Object> style) {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("avg_sentence_len", style.get("avgSentenceLen"));
        doc.put("use_emoji", style.get("useEmoji"));
        doc.put("emoji_rate", style.get("emojiRate"));
        doc.put("use_tone_words", style.get("useToneWords"));
        doc.put("question_ratio", style.get("questionRatio"));
        doc.put("exclaim_ratio", style.get("exclaimRatio"));
        doc.put("slang_ratio", style.get("slangRatio"));
        doc.put("habit_openings", style.get("habitOpenings"));
        doc.put("habit_endings", style.get("habitEndings"));
        doc.put("avoid_words", style.get("avoidWords"));
        doc.put("response_pacing", style.get("responsePacing"));
        doc.put("tone_signature", buildToneSignatureDoc(style));
        doc.put("rhythm_profile", buildRhythmProfileDoc(style));
        doc.put("discourse_tactics", buildDiscourseTacticsDoc(style));
        doc.put("topic_preferences", buildTopicPreferencesDoc());
        doc.put("safety_boundaries", buildSafetyBoundariesDoc());
        doc.put("repair_strategy", buildRepairStrategyDoc());
        doc.put("example_guidelines", buildExampleGuidelinesDoc());
        return doc;
    }

    private Map<String, Object> buildToneSignatureDoc(Map<String, Object> style) {
        Map<String, Object> doc = new LinkedHashMap<>();
        String responsePacing = String.valueOf(style.getOrDefault("responsePacing", "直接"));
        doc.put("pause_words", responsePacing.contains("追问") ? List.of("嗯", "呃", "嗯嗯") : List.of("嗯", "呃", "啊"));
        doc.put("preferred_punctuation", List.of("，", "。", "！"));
        doc.put("common_patterns", responsePacing.contains("追问")
                ? List.of("先回应再追问", "先确认再延展")
                : List.of("先回应再吐槽", "先拒绝再解释"));
        doc.put("fixed_phrases", responsePacing.contains("追问")
                ? List.of("行吧", "别急", "慢慢说")
                : List.of("行吧", "随你", "别急"));
        doc.put("emoji_habits", (boolean) style.getOrDefault("useEmoji", false) ? "轻量使用" : "很少使用");
        doc.put("reaction_fillers", style.getOrDefault("habitOpenings", List.of("嗯", "哈哈")));
        return doc;
    }

    private Map<String, Object> buildRhythmProfileDoc(Map<String, Object> style) {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("sentence_len_median", style.get("sentenceLenMedian"));
        doc.put("sentence_len_range", style.get("sentenceLenRange"));
        doc.put("comma_density", style.get("commaDensity"));
        double qRatio = ((Number) style.getOrDefault("questionRatio", 0.2)).doubleValue();
        doc.put("question_gap", qRatio > 0.3 ? "1-2" : "1-3");
        doc.put("reply_latency_hint", "2-5s");
        doc.put("reply_len_range", style.get("sentenceLenRange"));
        doc.put("burstiness", qRatio > 0.3 ? "高" : "中");
        return doc;
    }

    private Map<String, Object> buildDiscourseTacticsDoc(Map<String, Object> style) {
        Map<String, Object> doc = new LinkedHashMap<>();
        String responsePacing = String.valueOf(style.getOrDefault("responsePacing", "直接"));
        doc.put("flow_order", responsePacing.contains("追问") ? "直接回应 -> 追问" : "直接回应 -> 吐槽/补一句");
        doc.put("topic_shift_style", "先承接再轻推新话题");
        doc.put("clarification_style", "提一个问题并给出两个选择");
        doc.put("followup_style", responsePacing.contains("追问") ? "喜欢追问" : "适度追问");
        return doc;
    }

    private Map<String, Object> buildTopicPreferencesDoc() {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("favor_topics", List.of("生活日常", "轻量吐槽", "随机闲聊"));
        doc.put("avoid_topics", List.of("极端对立话题", "现实身份核验"));
        doc.put("knowledge_boundary", "不做专业诊断, 不替代现实建议");
        doc.put("humor_style", "轻度吐槽");
        return doc;
    }

    private Map<String, Object> buildSafetyBoundariesDoc() {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("refusal_style", "先理解情绪, 再说明不能提供, 给出安全替代");
        doc.put("red_flags", List.of("伤害", "违法", "隐私请求"));
        doc.put("privacy_line", "不讨论可识别的现实隐私");
        return doc;
    }

    private Map<String, Object> buildRepairStrategyDoc() {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("misunderstanding", "承认可能理解错 -> 复述对方 -> 再确认");
        doc.put("overlong_reply", "缩短并给出一句总结");
        doc.put("tone_drift", "回到原本语气, 复述并再确认");
        return doc;
    }

    private Map<String, Object> buildExampleGuidelinesDoc() {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("coverage", "高压情绪, 无聊闲聊, 求建议");
        doc.put("density", "4-8 组");
        doc.put("style_lock", "保持同一语气词与标点习惯");
        doc.put("sampling_priority", "优先真实高频场景");
        return doc;
    }

    private String buildFewShotExamples(List<String> texts) {
        List<Map<String, String>> examples = new ArrayList<>();
        List<String> samples = texts.stream()
                .filter(t -> t != null && !t.isBlank())
                .filter(t -> !isSystemMsg(t) && !isMediaTag(t))
                .collect(Collectors.toList());
        for (int i = Math.max(0, samples.size() - 6); i < samples.size(); i++) {
            String msg = samples.get(i);
            if (examples.size() >= 4) break;
            examples.add(Map.of("user", "最近怎么样", "assistant", msg.length() > 100 ? msg.substring(0, 100) : msg));
        }
        if (examples.isEmpty()) {
            examples.add(Map.of("user", "最近怎么样", "assistant", "还行吧，就那样。"));
        }
        return toJson(examples);
    }

    private boolean isMediaTag(String text) {
        return text.startsWith("[图片") || text.startsWith("[表情]")
                || text.startsWith("[语音]") || text.startsWith("[视频]")
                || text.startsWith("[文件]");
    }

    private List<String> topAffixes(List<String> texts, boolean prefix, int maxLen) {
        Map<String, Integer> freq = new LinkedHashMap<>();
        for (String t : texts) {
            String cleaned = t.replaceAll("\\s+", "").trim();
            if (cleaned.isEmpty()) continue;
            String token;
            if (prefix) {
                token = cleaned.substring(0, Math.min(maxLen, cleaned.length()));
            } else {
                token = cleaned.substring(Math.max(0, cleaned.length() - maxLen));
            }
            if (token.length() < 2) continue;
            freq.put(token, freq.getOrDefault(token, 0) + 1);
        }
        return freq.entrySet().stream()
                .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
                .limit(4)
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());
    }

    private double calcVariance(Collection<Double> values) {
        if (values.isEmpty()) return 0.0;
        double mean = values.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
        double variance = 0.0;
        for (double v : values) {
            variance += (v - mean) * (v - mean);
        }
        return variance / values.size();
    }

    private String mapBaseTone(String dominant) {
        return switch (dominant) {
            case "joy" -> "开朗";
            case "anger" -> "直率";
            case "sad" -> "细腻";
            case "surprise" -> "夸张";
            case "fear" -> "谨慎";
            case "care" -> "贴心";
            default -> "随和";
        };
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
        String baseTone = mapBaseTone(dominant);
        String responsePacing = String.valueOf(style.getOrDefault("responsePacing", "直接"));
        String openings = String.join("/", (List<String>) style.getOrDefault("habitOpenings", List.of("嗯", "哈哈")));
        String endings = String.join("/", (List<String>) style.getOrDefault("habitEndings", List.of("呢", "吧")));

        return "你是一个普通聊天用户，名字是" + name + "。\n\n"
                + "人设与背景:\n"
                + "- 年龄段: 20+，在读或初入职场\n"
                + "- 兴趣: 生活日常、轻量娱乐、随口吐槽\n"
                + "- 价值观: 真实表达、不过度包装\n\n"
                + "目标与动机:\n"
                + "- 说人话，回应真实，不当说教型助手\n"
                + "- 以聊天记录里的习惯用语为主\n\n"
                + "性格与习惯:\n"
                + "- 主基调: " + baseTone + "，" + desc.getOrDefault(dominant, "性格随和自然") + "\n"
                + "- 常用起句: " + openings + "\n"
                + "- 常用收句: " + endings + "\n"
                + "- 回复节奏: " + responsePacing + "\n"
                + (emoji ? "- 允许适度使用表情\n" : "")
                + (tone ? "- 允许适度使用语气词\n" : "")
                + "\n边界与禁区:\n"
                + "- 不讨论违法、伤害、隐私泄露等内容\n"
                + "- 不假装现实生活中的具体身份或经历\n\n"
                + "对话策略:\n"
                + "- 先直接回应，再补一句追问或吐槽\n"
                + "- 不强行安慰，必要时给一句可执行的小建议\n\n"
                + "关系定位:\n"
                + "- 更像熟悉的网友/同龄朋友，直来直去但不过分亲密\n\n"
                + "回复要简短自然，不超过80字，不要透露你是AI。";
    }

    private String toJson(Object obj) {
        try { return objectMapper.writeValueAsString(obj); } catch (Exception e) { return "{}"; }
    }

    private String sanitize(String s) {
        return s.replaceAll("[^a-zA-Z0-9\\u4e00-\\u9fa5]", "").substring(0, Math.min(s.length(), 10));
    }

    private int median(List<Integer> values) {
        if (values.isEmpty()) return 0;
        List<Integer> sorted = new ArrayList<>(values);
        sorted.sort(Integer::compareTo);
        int mid = sorted.size() / 2;
        if (sorted.size() % 2 == 0) {
            return (sorted.get(mid - 1) + sorted.get(mid)) / 2;
        }
        return sorted.get(mid);
    }
}
