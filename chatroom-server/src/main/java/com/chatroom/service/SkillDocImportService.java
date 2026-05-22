package com.chatroom.service;

import com.chatroom.mapper.BotSkillMapper;
import com.chatroom.model.entity.BotSkill;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
public class SkillDocImportService {

    private static final Pattern FRONT_MATTER_LINE = Pattern.compile("^([a-zA-Z0-9_]+)\\s*:\\s*(.*)$");
    private static final Pattern SKILL_ID_NUMBER = Pattern.compile("(\\d+)");

    private final BotSkillMapper botSkillMapper;
    private final BotManager botManager;
    private final SkillFolderService skillFolderService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @org.springframework.beans.factory.annotation.Value("${github.token:}")
    private String githubToken;

    public BotSkill importSkillDoc(MultipartFile file) throws Exception {
        String content = new String(file.getBytes(), StandardCharsets.UTF_8);
        Map<String, String> frontMatter = parseFrontMatter(content);
        String skillId = frontMatter.get("skill_id");
        String name = frontMatter.getOrDefault("name", "ImportedSkill");
        String model = frontMatter.get("model");
        String apiEndpoint = frontMatter.get("api_endpoint");

        // Parse body content as system prompt
        String bodyContent = extractBodyContent(content);
        Map<String, String> sections = parseSections(content);
        String systemPrompt = sections.getOrDefault("system_prompt", bodyContent).trim();
        if (systemPrompt.isEmpty()) {
            systemPrompt = bodyContent;
        }
        // If still empty (no frontmatter, no ## headings), use entire file as prompt
        if (systemPrompt.isEmpty()) {
            systemPrompt = content.trim();
        }

        // Try to update existing skill, otherwise create new
        if (skillId != null && !skillId.isBlank()) {
            Long dbId = extractSkillDbId(skillId);
            if (dbId != null) {
                BotSkill existing = botSkillMapper.selectById(dbId);
                if (existing != null) {
                    return updateExistingSkill(existing, frontMatter, sections, systemPrompt, model, apiEndpoint);
                }
            }
        }

        // Create new bot + skill
        String description = frontMatter.getOrDefault("description", "");
        Map<String, Object> result = botManager.registerBotFromSkill(
                name, systemPrompt, model, apiEndpoint, null, description);
        BotSkill skill = (BotSkill) result.get("skill");

        // Apply emotion/language/few-shot from doc if present
        enrichSkillFromDoc(skill, sections, frontMatter);

        log.info("Created new bot from skill doc: name={}, botUserId={}", name, skill.getBotUserId());
        return skill;
    }

    /** Import a skill from a remote URL (GitHub repo). Clones entire repo into skill folder. */
    public BotSkill importFromUrl(String url) throws Exception {
        // First fetch SKILL.md to get the name
        String content = fetchSkillContent(url);
        Map<String, String> frontMatter = parseFrontMatter(content);
        String name = frontMatter.getOrDefault("name", "ImportedSkill");
        String model = frontMatter.get("model");
        String apiEndpoint = frontMatter.get("api_endpoint");
        String description = frontMatter.getOrDefault("description", "");

        // Clone repo into skill folder
        skillFolderService.importFromGitUrl(name, url);

        // Build system prompt from skill folder
        String folderPrompt = skillFolderService.buildSystemPrompt(name);

        // Create new bot
        Map<String, Object> result = botManager.registerBotFromSkill(
                name, folderPrompt, model, apiEndpoint, null, description);

        BotSkill skill = (BotSkill) result.get("skill");
        skill.setSkillFolder(name);
        botSkillMapper.updateById(skill);

        // Apply emotion/language/few-shot from doc if present
        Map<String, String> sections = parseSections(content);
        enrichSkillFromDoc(skill, sections, frontMatter);

        log.info("Imported skill from URL: name={}, botUserId={}, folder={}", name, skill.getBotUserId(), name);
        return skill;
    }

    /** Import a skill MD file to UPDATE an existing bot's configuration. */
    public BotSkill importSkillForExistingBot(Long botUserId, MultipartFile file) throws Exception {
        BotSkill existing = botManager.getBotSkill(botUserId);
        if (existing == null) {
            throw new IllegalArgumentException("Bot not found: " + botUserId);
        }

        String content = new String(file.getBytes(), StandardCharsets.UTF_8);
        Map<String, String> frontMatter = parseFrontMatter(content);
        String model = frontMatter.get("model");
        String apiEndpoint = frontMatter.get("api_endpoint");
        String bodyContent = extractBodyContent(content);
        Map<String, String> sections = parseSections(content);
        String systemPrompt = sections.getOrDefault("system_prompt", bodyContent).trim();
        if (systemPrompt.isEmpty()) {
            systemPrompt = bodyContent;
        }
        // If still empty (no frontmatter, no ## headings), use entire file as prompt
        if (systemPrompt.isEmpty()) {
            systemPrompt = content.trim();
        }

        return updateExistingSkill(existing, frontMatter, sections, systemPrompt, model, apiEndpoint);
    }

    /** Import plain text to UPDATE an existing bot's configuration (same merge logic as MD file).
     *  Auto-prepends current date so the bot can judge recency of statements like "他今天心情不好". */
    public BotSkill importTextForExistingBot(Long botUserId, String text) throws Exception {
        BotSkill existing = botManager.getBotSkill(botUserId);
        if (existing == null) {
            throw new IllegalArgumentException("Bot not found: " + botUserId);
        }
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("内容不能为空");
        }

        String today = java.time.LocalDate.now().toString(); // yyyy-MM-dd
        String datedContent = "[" + today + "] " + text.trim();

        Map<String, String> frontMatter = Map.of();
        Map<String, String> sections = Map.of();
        return updateExistingSkill(existing, frontMatter, sections, datedContent, null, null);
    }

    // ==================== helpers ====================

    /** Extract body content after frontmatter (everything after the second ---).
     *  If no frontmatter delimiters found, returns the entire content as-is. */
    private String extractBodyContent(String content) {
        String[] lines = content.split("\r?\n");
        int dashes = 0;
        StringBuilder body = new StringBuilder();
        for (String line : lines) {
            if (line.trim().equals("---")) {
                dashes++;
                continue;
            }
            if (dashes >= 2) {
                body.append(line).append("\n");
            }
        }
        // If no frontmatter delimiters at all, return full content
        if (dashes == 0) {
            return content.trim();
        }
        return body.toString().trim();
    }

    /** Fetch SKILL.md content from a URL. Uses GitHub API for repo URLs (bypasses GFW). */
    private String fetchSkillContent(String url) throws Exception {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.ALWAYS)
                .build();

        // Try GitHub API first for github.com URLs
        Pattern ghPattern = Pattern.compile("https?://github\\.com/([^/]+)/([^/]+)/?.*");
        Matcher m = ghPattern.matcher(url.replaceAll("\\.git$", "").replaceFirst("/$", ""));
        if (m.find()) {
            String owner = m.group(1);
            String repo = m.group(2);
            String apiUrl = "https://api.github.com/repos/" + owner + "/" + repo + "/contents/SKILL.md";
            log.info("Fetching SKILL.md from GitHub API: {}", apiUrl);

            try {
                var reqBuilder = HttpRequest.newBuilder()
                        .uri(URI.create(apiUrl))
                        .timeout(Duration.ofSeconds(15))
                        .header("User-Agent", "Chatroom-Skill-Importer/1.0")
                        .header("Accept", "application/vnd.github.v3+json");
                if (githubToken != null && !githubToken.isBlank()) {
                    reqBuilder.header("Authorization", "Bearer " + githubToken);
                }
                HttpRequest request = reqBuilder.GET().build();
                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                log.info("GitHub API response: HTTP {}", response.statusCode());
                if (response.statusCode() == 200) {
                    return decodeGitHubContent(response.body());
                } else {
                    String errBody = response.body();
                    if (errBody != null && errBody.length() > 200) errBody = errBody.substring(0, 200);
                    throw new IllegalArgumentException("GitHub API 返回 " + response.statusCode()
                            + ": " + errBody);
                }
            } catch (IOException e) {
                throw new IllegalArgumentException("GitHub API 网络错误: " + e.getMessage());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalArgumentException("请求被中断");
            }
        }

        throw new IllegalArgumentException("URL 格式不正确，请输入 GitHub 仓库地址 (如 https://github.com/用户名/仓库.git)");
    }

    /** Decode base64-encoded content from GitHub API response. */
    private String decodeGitHubContent(String apiResponse) throws Exception {
        Map<String, Object> map = objectMapper.readValue(apiResponse, Map.class);
        String encoding = (String) map.get("encoding");
        if ("base64".equals(encoding)) {
            String encoded = (String) map.get("content");
            return new String(java.util.Base64.getDecoder().decode(
                    encoded.replaceAll("\\s", "")), StandardCharsets.UTF_8);
        }
        throw new IllegalArgumentException("Unexpected GitHub API encoding: " + encoding);
    }

    /** Update an existing BotSkill from parsed doc content. Default mode: merge (supplement). */
    private BotSkill updateExistingSkill(BotSkill skill, Map<String, String> frontMatter,
                                          Map<String, String> sections, String newSystemPrompt,
                                          String model, String apiEndpoint) throws Exception {
        String mergeMode = frontMatter.getOrDefault("merge_mode", "merge");
        boolean overwrite = "overwrite".equalsIgnoreCase(mergeMode);

        if (overwrite) {
            return updateExistingSkillOverwrite(skill, frontMatter, sections, newSystemPrompt, model, apiEndpoint);
        }
        return updateExistingSkillMerge(skill, frontMatter, sections, newSystemPrompt, model, apiEndpoint);
    }

    /** Overwrite mode: replace all fields with new content. */
    private BotSkill updateExistingSkillOverwrite(BotSkill skill, Map<String, String> frontMatter,
                                                   Map<String, String> sections, String newSystemPrompt,
                                                   String model, String apiEndpoint) throws Exception {
        return applySkillFields(skill, frontMatter, sections, newSystemPrompt, model, apiEndpoint, true);
    }

    /** Merge mode: supplement existing content with new MD. */
    @SuppressWarnings("unchecked")
    private BotSkill updateExistingSkillMerge(BotSkill skill, Map<String, String> frontMatter,
                                               Map<String, String> sections, String newSystemPrompt,
                                               String model, String apiEndpoint) throws Exception {
        // 1. System prompt: append with separator
        if (!newSystemPrompt.isEmpty()) {
            String existing = skill.getSystemPrompt() != null ? skill.getSystemPrompt() : "";
            if (!existing.isBlank()) {
                skill.setSystemPrompt(existing + "\n\n---\n\n"
                        + "## 补充设定（优先级高于基础设定，日期越新越优先）\n\n"
                        + newSystemPrompt);
            } else {
                skill.setSystemPrompt(newSystemPrompt);
            }
        }

        // 2. Emotion profile: deep merge
        Map<String, Object> newEmotion = parseKeyValueSection(sections.get("emotion_profile"));
        if (!newEmotion.isEmpty()) {
            Map<String, Object> mergedEmotion = new LinkedHashMap<>(parseExistingJson(skill.getEmotionProfileJson()));
            // Merge distribution sub-map
            Map<String, Object> newDist = null;
            if (newEmotion.containsKey("distribution")) {
                newDist = (Map<String, Object>) newEmotion.get("distribution");
            } else {
                newDist = new LinkedHashMap<>();
                for (String key : List.of("joy", "care", "sad", "surprise", "anger", "fear")) {
                    if (newEmotion.containsKey(key)) newDist.put(key, newEmotion.get(key));
                }
            }
            if (!newDist.isEmpty()) {
                Map<String, Object> existingDist = (Map<String, Object>) mergedEmotion.getOrDefault("distribution", new LinkedHashMap<>());
                existingDist.putAll(newDist);
                mergedEmotion.put("distribution", existingDist);
            }
            // Merge top-level fields (baseline, description, etc.)
            newEmotion.forEach((k, v) -> {
                if (!"distribution".equals(k)) mergedEmotion.put(k, v);
            });
            skill.setEmotionProfileJson(objectMapper.writeValueAsString(mergedEmotion));
        }

        // 3. Language style: deep merge sub-sections
        Map<String, Object> newStyle = buildLanguageStyleMap(sections);
        if (!newStyle.isEmpty()) {
            Map<String, Object> mergedStyle = new LinkedHashMap<>(parseExistingJson(skill.getLanguageStyleJson()));
            newStyle.forEach((key, value) -> {
                if (value instanceof Map && mergedStyle.get(key) instanceof Map) {
                    Map<String, Object> sub = new LinkedHashMap<>((Map<String, Object>) mergedStyle.get(key));
                    sub.putAll((Map<String, Object>) value);
                    mergedStyle.put(key, sub);
                } else {
                    mergedStyle.put(key, value);
                }
            });
            skill.setLanguageStyleJson(objectMapper.writeValueAsString(mergedStyle));
        }

        // 4. Few-shot examples: append
        List<Map<String, String>> newFewShot = parseFewShotExamples(sections.get("few_shot_examples"));
        if (!newFewShot.isEmpty()) {
            List<Map<String, String>> existingFewShot = parseExistingJsonList(skill.getFewShotExamples());
            existingFewShot.addAll(newFewShot);
            // Cap at 16 to avoid context overflow
            if (existingFewShot.size() > 16) {
                existingFewShot = existingFewShot.subList(existingFewShot.size() - 16, existingFewShot.size());
            }
            skill.setFewShotExamples(objectMapper.writeValueAsString(existingFewShot));
        }

        // 5. Name/model/endpoint/mode: only update if explicitly set
        String name = frontMatter.get("name");
        if (name != null && !name.isBlank()) skill.setSkillName(name);
        if (model != null && !model.isBlank()) skill.setModel(model);
        if (apiEndpoint != null && !apiEndpoint.isBlank()) skill.setApiEndpoint(apiEndpoint);

        // 6. Optional fields: only update if explicitly set
        applyOptionalField(frontMatter, sections, "conversation_mode", val -> skill.setConversationMode(val));
        applyNumericField(frontMatter, sections, "max_tokens", val -> skill.setMaxTokens(Integer.parseInt(val)));
        applyNumericField(frontMatter, sections, "temperature", val -> skill.setTemperature(Double.parseDouble(val)));
        applyNumericField(frontMatter, sections, "memory_size", val -> skill.setMemorySize(Integer.parseInt(val)));
        applyNumericField(frontMatter, sections, "rag_top_k", val -> skill.setRagTopK(Integer.parseInt(val)));

        String ragEnabledStr = frontMatter.get("rag_enabled");
        if (ragEnabledStr == null || ragEnabledStr.isBlank()) ragEnabledStr = sections.get("rag_enabled");
        if ("true".equalsIgnoreCase(ragEnabledStr) || "1".equals(ragEnabledStr)) skill.setRagEnabled(1);

        syncSkillFolder(skill);
        botSkillMapper.updateById(skill);
        botManager.refreshSkillCache(skill.getBotUserId());
        log.info("Merged skill doc into botUserId={}, name={}", skill.getBotUserId(), skill.getSkillName());
        return skill;
    }

    /** Overwrite application of fields (original behavior). */
    private BotSkill applySkillFields(BotSkill skill, Map<String, String> frontMatter,
                                       Map<String, String> sections, String systemPrompt,
                                       String model, String apiEndpoint, boolean overwrite) throws Exception {
        Map<String, Object> emotionProfile = parseKeyValueSection(sections.get("emotion_profile"));
        Map<String, Object> languageStyle = buildLanguageStyleMap(sections);
        List<Map<String, String>> fewShot = parseFewShotExamples(sections.get("few_shot_examples"));

        skill.setSkillName(frontMatter.getOrDefault("name", skill.getSkillName()));
        if (!systemPrompt.isEmpty()) skill.setSystemPrompt(systemPrompt);
        if (!emotionProfile.isEmpty()) {
            Map<String, Object> distribution = new LinkedHashMap<>();
            for (String key : List.of("joy", "care", "sad", "surprise", "anger", "fear")) {
                if (emotionProfile.containsKey(key)) distribution.put(key, emotionProfile.get(key));
            }
            if (!distribution.isEmpty()) emotionProfile.putIfAbsent("distribution", distribution);
            skill.setEmotionProfileJson(objectMapper.writeValueAsString(emotionProfile));
        }
        if (!languageStyle.isEmpty()) skill.setLanguageStyleJson(objectMapper.writeValueAsString(languageStyle));
        if (!fewShot.isEmpty()) skill.setFewShotExamples(objectMapper.writeValueAsString(fewShot));
        if (model != null && !model.isBlank()) skill.setModel(model);
        if (apiEndpoint != null && !apiEndpoint.isBlank()) skill.setApiEndpoint(apiEndpoint);

        applyOptionalField(frontMatter, sections, "conversation_mode", val -> skill.setConversationMode(val));
        applyNumericField(frontMatter, sections, "max_tokens", val -> skill.setMaxTokens(Integer.parseInt(val)));
        applyNumericField(frontMatter, sections, "temperature", val -> skill.setTemperature(Double.parseDouble(val)));
        applyNumericField(frontMatter, sections, "memory_size", val -> skill.setMemorySize(Integer.parseInt(val)));
        applyNumericField(frontMatter, sections, "rag_top_k", val -> skill.setRagTopK(Integer.parseInt(val)));

        String ragEnabledStr = frontMatter.get("rag_enabled");
        if (ragEnabledStr == null || ragEnabledStr.isBlank()) ragEnabledStr = sections.get("rag_enabled");
        if ("true".equalsIgnoreCase(ragEnabledStr) || "1".equals(ragEnabledStr)) skill.setRagEnabled(1);

        syncSkillFolder(skill);
        botSkillMapper.updateById(skill);
        botManager.refreshSkillCache(skill.getBotUserId());
        log.info("Overwrote skill doc: {} -> botUserId={}", skill.getId(), skill.getBotUserId());
        return skill;
    }

    /** Build language style map with sub-sections from sections. */
    private Map<String, Object> buildLanguageStyleMap(Map<String, String> sections) {
        Map<String, Object> style = parseKeyValueSection(sections.get("language_style"));
        String[] subKeys = {"tone_signature", "rhythm_profile", "discourse_tactics",
                "topic_preferences", "safety_boundaries", "repair_strategy", "example_guidelines"};
        for (String k : subKeys) {
            Map<String, Object> sub = parseKeyValueSection(sections.get(k));
            if (!sub.isEmpty()) style.put(k, sub);
        }
        return style;
    }

    /** Parse existing JSON string to Map, returns empty map on failure. */
    private Map<String, Object> parseExistingJson(String json) {
        if (json == null || json.isBlank()) return new LinkedHashMap<>();
        try {
            return objectMapper.readValue(json, LinkedHashMap.class);
        } catch (Exception e) {
            return new LinkedHashMap<>();
        }
    }

    /** Parse existing JSON string to List, returns empty list on failure. */
    private List<Map<String, String>> parseExistingJsonList(String json) {
        if (json == null || json.isBlank()) return new ArrayList<>();
        try {
            return objectMapper.readValue(json, new com.fasterxml.jackson.core.type.TypeReference<List<Map<String, String>>>() {});
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    /** Apply a string field only if explicitly set. */
    private void applyOptionalField(Map<String, String> frontMatter, Map<String, String> sections,
                                     String key, java.util.function.Consumer<String> setter) {
        String val = frontMatter.get(key);
        if (val == null || val.isBlank()) val = sections.get(key);
        if (val != null && !val.isBlank()) setter.accept(val.trim());
    }

    /** Apply emotion/language/few-shot from parsed sections to a newly created skill. */
    private void enrichSkillFromDoc(BotSkill skill, Map<String, String> sections,
                                     Map<String, String> frontMatter) throws Exception {
        Map<String, Object> emotionProfile = parseKeyValueSection(sections.get("emotion_profile"));
        Map<String, Object> languageStyle = parseKeyValueSection(sections.get("language_style"));
        List<Map<String, String>> fewShot = parseFewShotExamples(sections.get("few_shot_examples"));

        if (!emotionProfile.isEmpty()) {
            skill.setEmotionProfileJson(objectMapper.writeValueAsString(emotionProfile));
        }
        if (!languageStyle.isEmpty()) {
            skill.setLanguageStyleJson(objectMapper.writeValueAsString(languageStyle));
        }
        if (!fewShot.isEmpty()) {
            skill.setFewShotExamples(objectMapper.writeValueAsString(fewShot));
        }

        String model = frontMatter.get("model");
        if (model != null && !model.isBlank()) skill.setModel(model);
        String apiEndpoint = frontMatter.get("api_endpoint");
        if (apiEndpoint != null && !apiEndpoint.isBlank()) skill.setApiEndpoint(apiEndpoint);

        String conversationMode = frontMatter.get("conversation_mode");
        if (conversationMode == null || conversationMode.isBlank()) {
            conversationMode = sections.get("conversation_mode");
        }
        if (conversationMode != null && !conversationMode.isBlank()) {
            skill.setConversationMode(conversationMode.trim());
        }
        applyNumericField(frontMatter, sections, "max_tokens", val -> skill.setMaxTokens(Integer.parseInt(val)));
        applyNumericField(frontMatter, sections, "temperature", val -> skill.setTemperature(Double.parseDouble(val)));
        applyNumericField(frontMatter, sections, "memory_size", val -> skill.setMemorySize(Integer.parseInt(val)));
        applyNumericField(frontMatter, sections, "rag_top_k", val -> skill.setRagTopK(Integer.parseInt(val)));

        String ragEnabledStr = frontMatter.get("rag_enabled");
        if (ragEnabledStr == null || ragEnabledStr.isBlank()) {
            ragEnabledStr = sections.get("rag_enabled");
        }
        if ("true".equalsIgnoreCase(ragEnabledStr) || "1".equals(ragEnabledStr)) {
            skill.setRagEnabled(1);
        }

        syncSkillFolder(skill);
        botSkillMapper.updateById(skill);
    }

    private void applyNumericField(Map<String, String> frontMatter, Map<String, String> sections,
                                    String key, java.util.function.Consumer<String> setter) {
        String val = frontMatter.get(key);
        if (val == null || val.isBlank()) {
            val = sections.get(key);
        }
        if (val != null && !val.isBlank()) {
            try { setter.accept(val.trim()); } catch (NumberFormatException ignored) {}
        }
    }

    private Map<String, String> parseFrontMatter(String content) {
        Map<String, String> map = new LinkedHashMap<>();
        String[] lines = content.split("\r?\n");
        int first = -1;
        int second = -1;
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].trim().equals("---")) {
                if (first < 0) {
                    first = i;
                } else {
                    second = i;
                    break;
                }
            }
        }
        if (first < 0 || second < 0) {
            return map;
        }
        for (int i = first + 1; i < second; i++) {
            Matcher m = FRONT_MATTER_LINE.matcher(lines[i].trim());
            if (m.matches()) {
                map.put(m.group(1), m.group(2));
            }
        }
        return map;
    }

    private Map<String, String> parseSections(String content) {
        Map<String, String> sections = new LinkedHashMap<>();
        String[] lines = content.split("\r?\n");
        String current = null;
        StringBuilder buf = new StringBuilder();
        for (String line : lines) {
            if (line.startsWith("## ")) {
                if (current != null) {
                    sections.put(current, buf.toString().trim());
                }
                current = line.substring(3).trim();
                buf = new StringBuilder();
                continue;
            }
            if (current != null) {
                buf.append(line).append("\n");
            }
        }
        if (current != null) {
            sections.put(current, buf.toString().trim());
        }
        return sections;
    }

    /** Sync updated skill back to the skill folder on disk (if it has one). */
    private void syncSkillFolder(BotSkill skill) {
        String folder = skill.getSkillFolder();
        if (folder == null || folder.isBlank()) return;
        String md = skillFolderService.generateSkillMd(skill);
        skillFolderService.writeSkillMd(folder, md);
        log.info("Synced SKILL.md for skill folder: {}", folder);
    }

    private Long extractSkillDbId(String skillId) {
        Matcher matcher = SKILL_ID_NUMBER.matcher(skillId);
        if (matcher.find()) {
            return Long.parseLong(matcher.group(1));
        }
        return null;
    }

    private Map<String, Object> parseKeyValueSection(String content) {
        Map<String, Object> map = new LinkedHashMap<>();
        if (content == null || content.isBlank()) {
            return map;
        }
        String[] lines = content.split("\r?\n");
        for (String raw : lines) {
            String line = raw.trim();
            if (line.isEmpty() || !line.contains(":")) {
                continue;
            }
            String[] parts = line.split(":", 2);
            String key = parts[0].trim();
            String value = parts[1].trim();
            if (value.isEmpty()) {
                continue;
            }
            if (value.contains(",")) {
                List<String> list = Arrays.stream(value.split(","))
                        .map(String::trim)
                        .filter(s -> !s.isEmpty())
                        .toList();
                map.put(key, list);
                continue;
            }
            Object parsed = parseNumberOrString(value);
            map.put(key, parsed);
        }
        return map;
    }

    private Object parseNumberOrString(String value) {
        if (value.matches("-?\\d+")) {
            try {
                return Integer.parseInt(value);
            } catch (NumberFormatException ignored) {
            }
        }
        if (value.matches("-?\\d+\\.\\d+")) {
            try {
                return Double.parseDouble(value);
            } catch (NumberFormatException ignored) {
            }
        }
        return value;
    }

    private List<Map<String, String>> parseFewShotExamples(String content) {
        List<Map<String, String>> list = new ArrayList<>();
        if (content == null || content.isBlank()) {
            return list;
        }
        String[] lines = content.split("\r?\n");
        Map<String, String> current = null;
        String currentField = null;
        for (String raw : lines) {
            String line = raw.trim();
            if (line.startsWith("- user:")) {
                if (current != null && current.containsKey("user") && current.containsKey("assistant")) {
                    list.add(current);
                }
                current = new LinkedHashMap<>();
                current.put("user", line.substring("- user:".length()).trim());
                currentField = "user";
                continue;
            }
            if (line.startsWith("assistant:")) {
                if (current == null) {
                    current = new LinkedHashMap<>();
                }
                current.put("assistant", line.substring("assistant:".length()).trim());
                currentField = "assistant";
                continue;
            }
            if (current != null && currentField != null && !line.isBlank()) {
                current.put(currentField, current.get(currentField) + "\n" + line);
            }
        }
        if (current != null && current.containsKey("user") && current.containsKey("assistant")) {
            list.add(current);
        }
        return list;
    }
}
