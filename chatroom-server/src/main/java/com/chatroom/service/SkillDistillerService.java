package com.chatroom.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.chatroom.common.Constants;
import com.chatroom.mapper.MessageMapper;
import com.chatroom.mapper.UserMapper;
import com.chatroom.model.entity.Message;
import com.chatroom.model.entity.User;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class SkillDistillerService {

    private final MessageMapper messageMapper;
    private final UserMapper userMapper;
    private final ObjectMapper objectMapper = new ObjectMapper();

    // Simple Chinese emotion dictionary
    private static final Map<String, String> EMOTION_DICT = new LinkedHashMap<>() {{
        put("joy", "开心|高兴|快乐|哈哈|嘿嘿|笑|嘻嘻|棒|太棒|赞|好开心|笑死|哈哈笑|kk|hh|牛|牛逼|厉害|绝了");
        put("anger", "气|烦|讨厌|滚|恶心|无语|sb|沙比|卧槽|擦|tmd|cnm|fuck|去死|恶心人|麻了");
        put("sad", "难过|伤心|哭|sad|emo|难受|心痛|郁闷|崩溃|泪|唉|哎|想哭|不开心|好难");
        put("surprise", "天哪|我去|哇|震惊|竟然|居然|没想到|吓|惊|omg|wtf|woc|我靠|离谱");
        put("fear", "怕|害怕|恐怖|吓人|不敢|紧张|担心|好怕|慌|焦虑");
        put("care", "关心|注意|小心|保重|照顾|还好吧|没事吧|吃点|休息|注意身体|别太累");
    }};

    // Common tone words
    private static final List<String> TONE_WORDS = List.of(
            "呢", "哦", "呀", "吧", "嘛", "哈", "啊", "啦", "噢", "哟", "咯", "哎"
    );

    /**
     * Distill chat records from the past 30 days into skill configurations.
     * Returns a list of generated skill configs (one per active user with sufficient messages).
     */
    public List<Map<String, Object>> distillSkills() {
        LocalDateTime since = LocalDateTime.now().minusDays(Constants.HISTORY_RETENTION_DAYS);
        List<Message> messages = messageMapper.selectList(
                new LambdaQueryWrapper<Message>()
                        .ge(Message::getCreatedAt, since)
                        .eq(Message::getMessageType, Constants.MSG_TYPE_PRIVATE)
                        .orderByAsc(Message::getCreatedAt));

        log.info("Distilling skills from {} messages in the past 30 days", messages.size());

        // Group by sender
        Map<Long, List<Message>> bySender = messages.stream()
                .collect(Collectors.groupingBy(Message::getSenderId));

        List<Map<String, Object>> skills = new ArrayList<>();
        for (Map.Entry<Long, List<Message>> entry : bySender.entrySet()) {
            Long userId = entry.getKey();
            List<Message> userMessages = entry.getValue();

            if (userMessages.size() < Constants.DISTILL_MIN_MESSAGES) continue;

            User user = userMapper.selectById(userId);
            if (user == null || user.getIsBot() != null && user.getIsBot() == 1) continue;

            Map<String, Object> skill = generateSkillForUser(user, userMessages);
            if (skill != null) {
                skills.add(skill);
            }
        }

        log.info("Generated {} skill configs from chat records", skills.size());
        return skills;
    }

    private Map<String, Object> generateSkillForUser(User user, List<Message> messages) {
        // Extract all text content
        List<String> texts = messages.stream()
                .map(Message::getContent)
                .filter(c -> c != null && c.length() >= Constants.DISTILL_MIN_CHARS
                        && c.length() <= Constants.DISTILL_MAX_CHARS)
                .collect(Collectors.toList());

        if (texts.isEmpty()) return null;

        // Emotion distribution
        Map<String, Double> emotionDist = extractEmotionDistribution(texts);

        // Language style
        Map<String, Object> languageStyle = extractLanguageStyle(texts);

        // Generate system prompt
        String systemPrompt = generateSystemPrompt(user.getNickname(), emotionDist, languageStyle);

        // Extract few-shot examples
        List<Map<String, String>> examples = extractFewShotExamples(messages);

        Map<String, Object> skill = new LinkedHashMap<>();
        skill.put("userId", user.getId());
        skill.put("nickname", user.getNickname());
        skill.put("emotionProfile", emotionDist);
        skill.put("languageStyle", languageStyle);
        skill.put("systemPrompt", systemPrompt);
        skill.put("fewShotExamples", examples);
        skill.put("messageCount", texts.size());

        return skill;
    }

    private Map<String, Double> extractEmotionDistribution(List<String> texts) {
        Map<String, Double> dist = new LinkedHashMap<>();
        int total = 0;

        for (Map.Entry<String, String> entry : EMOTION_DICT.entrySet()) {
            String[] keywords = entry.getValue().split("\\|");
            int count = 0;
            for (String text : texts) {
                for (String kw : keywords) {
                    if (text.contains(kw)) {
                        count++;
                        break;
                    }
                }
            }
            dist.put(entry.getKey(), (double) count);
            total += count;
        }

        // Normalize
        if (total > 0) {
            for (String key : dist.keySet()) {
                dist.put(key, dist.get(key) / total);
            }
        }
        return dist;
    }

    private Map<String, Object> extractLanguageStyle(List<String> texts) {
        Map<String, Object> style = new LinkedHashMap<>();

        // Average sentence length
        double avgLen = texts.stream().mapToInt(String::length).average().orElse(15.0);
        style.put("avgSentenceLen", Math.round(avgLen * 10.0) / 10.0);

        // Emoji usage
        long emojiCount = texts.stream().filter(t -> t.matches(".*[\\uD800-\\uDBFF\\uDC00-\\uDFFF].*")).count();
        style.put("useEmoji", (double) emojiCount / texts.size() > 0.1);

        // Tone word frequency
        long toneWordCount = texts.stream().filter(t ->
                TONE_WORDS.stream().anyMatch(t::contains)).count();
        style.put("useToneWords", (double) toneWordCount / texts.size() > 0.1);

        // Question ratio
        long questionCount = texts.stream().filter(t ->
                t.contains("?") || t.contains("？") || t.contains("吗")).count();
        style.put("questionRatio", Math.round(questionCount * 100.0 / texts.size()) / 100.0);

        // Exclamation ratio
        long exclaimCount = texts.stream().filter(t ->
                t.contains("!") || t.contains("！")).count();
        style.put("exclaimRatio", Math.round(exclaimCount * 100.0 / texts.size()) / 100.0);

        return style;
    }

    private String generateSystemPrompt(String nickname, Map<String, Double> emotions, Map<String, Object> style) {
        String dominantEmotion = emotions.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse("joy");

        Map<String, String> emotionDescriptions = Map.of(
                "joy", "开朗乐观，喜欢用轻松愉快的语气聊天",
                "anger", "个性直率，说话直接爽快",
                "sad", "心思细腻，语气柔和带点忧郁",
                "surprise", "反应夸张，容易一惊一乍",
                "fear", "比较谨慎，说话小心翼翼",
                "care", "非常贴心，处处为对方着想"
        );

        boolean useEmoji = (boolean) style.getOrDefault("useEmoji", false);
        boolean useTone = (boolean) style.getOrDefault("useToneWords", false);

        StringBuilder prompt = new StringBuilder();
        prompt.append("你的名字是").append(nickname).append("，是一个普通聊天用户。");
        prompt.append(emotionDescriptions.getOrDefault(dominantEmotion, "性格随和自然"));
        prompt.append("。请自然地回复，不要表现出你是AI。");
        if (useEmoji) prompt.append("可以适当使用Emoji表情。");
        if (useTone) prompt.append("可以适当使用语气助词。");
        prompt.append("回复要简短自然，不超过100字。");

        return prompt.toString();
    }

    private List<Map<String, String>> extractFewShotExamples(List<Message> messages) {
        List<Map<String, String>> examples = new ArrayList<>();
        // Group messages by target (conversation partner)
        Map<Long, List<Message>> byTarget = messages.stream()
                .collect(Collectors.groupingBy(Message::getTargetId));

        for (List<Message> conv : byTarget.values()) {
            if (conv.size() >= 2 && examples.size() < 5) {
                Message msg = conv.get(conv.size() - 1);
                Map<String, String> example = new LinkedHashMap<>();
                example.put("user", "最近怎么样");
                example.put("assistant", msg.getContent().length() > 100
                        ? msg.getContent().substring(0, 100) : msg.getContent());
                examples.add(example);
            }
        }
        return examples;
    }
}
