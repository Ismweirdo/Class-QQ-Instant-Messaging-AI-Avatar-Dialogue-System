package com.chatroom.service;

import com.chatroom.mapper.BotSkillMapper;
import com.chatroom.model.entity.BotSkill;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

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

    // ==================== helpers ====================

    /** Extract body content after frontmatter (everything after the second ---). */
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
        return body.toString().trim();
    }

    /** Fetch SKILL.md content from a URL. Handles GitHub repo URLs by converting to raw. */
    private String fetchSkillContent(String url) throws Exception {
        String rawUrl = convertToRawUrl(url);
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.ALWAYS)
                .build();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(rawUrl))
                .timeout(Duration.ofSeconds(30))
                .header("User-Agent", "Chatroom-Skill-Importer/1.0")
                .GET()
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IllegalArgumentException("获取失败: HTTP " + response.statusCode() + " from " + rawUrl);
        }
        return response.body();
    }

    /** Convert a GitHub repo URL to raw SKILL.md URL. Supports multiple formats. */
    private String convertToRawUrl(String url) {
        // Already a raw URL?
        if (url.contains("raw.githubusercontent.com")) {
            return url;
        }

        // Strip .git suffix
        String clean = url.replaceAll("\\.git$", "");
        // Strip trailing slash
        clean = clean.replaceFirst("/$", "");

        // github.com/X/Y -> raw.githubusercontent.com/X/Y/main/SKILL.md
        Pattern ghPattern = Pattern.compile("https?://github\\.com/([^/]+)/([^/]+)/?.*");
        Matcher m = ghPattern.matcher(clean);
        if (m.find()) {
            String owner = m.group(1);
            String repo = m.group(2);
            return "https://raw.githubusercontent.com/" + owner + "/" + repo + "/main/SKILL.md";
        }

        // Assume direct URL to SKILL.md
        return url;
    }

    /** Update an existing BotSkill from parsed doc content. */
    private BotSkill updateExistingSkill(BotSkill skill, Map<String, String> frontMatter,
                                          Map<String, String> sections, String systemPrompt,
                                          String model, String apiEndpoint) throws Exception {
        Map<String, Object> emotionProfile = parseKeyValueSection(sections.get("emotion_profile"));
        Map<String, Object> languageStyle = parseKeyValueSection(sections.get("language_style"));
        Map<String, Object> toneSignature = parseKeyValueSection(sections.get("tone_signature"));
        Map<String, Object> rhythmProfile = parseKeyValueSection(sections.get("rhythm_profile"));
        Map<String, Object> discourseTactics = parseKeyValueSection(sections.get("discourse_tactics"));
        Map<String, Object> topicPreferences = parseKeyValueSection(sections.get("topic_preferences"));
        Map<String, Object> safetyBoundaries = parseKeyValueSection(sections.get("safety_boundaries"));
        Map<String, Object> repairStrategy = parseKeyValueSection(sections.get("repair_strategy"));
        Map<String, Object> exampleGuidelines = parseKeyValueSection(sections.get("example_guidelines"));
        List<Map<String, String>> fewShot = parseFewShotExamples(sections.get("few_shot_examples"));

        if (!toneSignature.isEmpty()) languageStyle.put("tone_signature", toneSignature);
        if (!rhythmProfile.isEmpty()) languageStyle.put("rhythm_profile", rhythmProfile);
        if (!discourseTactics.isEmpty()) languageStyle.put("discourse_tactics", discourseTactics);
        if (!topicPreferences.isEmpty()) languageStyle.put("topic_preferences", topicPreferences);
        if (!safetyBoundaries.isEmpty()) languageStyle.put("safety_boundaries", safetyBoundaries);
        if (!repairStrategy.isEmpty()) languageStyle.put("repair_strategy", repairStrategy);
        if (!exampleGuidelines.isEmpty()) languageStyle.put("example_guidelines", exampleGuidelines);

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
        botSkillMapper.updateById(skill);
        log.info("Imported skill doc: {} -> botUserId={}", skill.getId(), skill.getBotUserId());
        return skill;
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

        botSkillMapper.updateById(skill);
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
