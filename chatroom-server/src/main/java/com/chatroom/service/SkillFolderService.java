package com.chatroom.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Manages skill folders with multi-file structure:
 *   data/skills/{skill-name}/
 *     SKILL.md
 *     examples/
 *     references/
 *     custom/
 *
 * Builds the full LLM system prompt by assembling all files.
 */
@Slf4j
@Service
public class SkillFolderService {

    private static final String SKILLS_ROOT = "data/skills";

    /** Resolve the absolute path to a skill folder. */
    public Path resolveSkillDir(String skillFolder) {
        Path base = Paths.get(System.getProperty("user.dir"));
        Path dir = base.resolve(SKILLS_ROOT).resolve(sanitize(skillFolder));
        if (!Files.exists(dir) && base.getFileName() != null
                && "chatroom-server".equalsIgnoreCase(base.getFileName().toString())) {
            dir = base.getParent().resolve(SKILLS_ROOT).resolve(sanitize(skillFolder));
        }
        return dir;
    }

    /** Create an empty skill folder with SKILL.md skeleton. */
    public Path createSkillFolder(String skillName) {
        Path dir = resolveSkillDir(skillName);
        try {
            Files.createDirectories(dir.resolve("examples"));
            Files.createDirectories(dir.resolve("references"));
            Files.createDirectories(dir.resolve("custom"));
            // Write skeleton SKILL.md
            String skeleton = "---\nname: " + skillName + "\nversion: 1.0.0\n---\n\n"
                    + "# " + skillName + "\n\n"
                    + "## system_prompt\n\n(在此编写人物系统提示词)\n";
            Files.writeString(dir.resolve("SKILL.md"), skeleton, StandardCharsets.UTF_8);
            log.info("Created skill folder: {}", dir);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to create skill folder: " + skillName, e);
        }
        return dir;
    }

    /**
     * Import skill from a git repository URL.
     * Clones the repo to a temp dir, then copies all content (SKILL.md, examples/, references/)
     * into the skill folder. Does NOT copy .git, LICENSE, README.md.
     */
    public Path importFromGitUrl(String skillName, String gitUrl) {
        Path skillDir = resolveSkillDir(skillName);
        Path tempDir = null;
        try {
            // Clone to temp directory
            tempDir = Files.createTempDirectory("skill-import-");
            log.info("Cloning {} to {}", gitUrl, tempDir);
            ProcessBuilder pb = new ProcessBuilder(
                    "git", "clone", "--depth", "1", gitUrl, tempDir.toString());
            pb.inheritIO();
            Process p = pb.start();
            int exitCode = p.waitFor();
            if (exitCode != 0) {
                throw new RuntimeException("git clone failed with exit code " + exitCode);
            }

            // Remove old skill folder if exists
            if (Files.exists(skillDir)) {
                deleteRecursively(skillDir);
            }
            Files.createDirectories(skillDir);

            // Copy relevant files: SKILL.md, examples/, references/
            copySkillContent(tempDir, skillDir);

            // Ensure required dirs exist
            Files.createDirectories(skillDir.resolve("examples"));
            Files.createDirectories(skillDir.resolve("references"));
            Files.createDirectories(skillDir.resolve("custom"));

            log.info("Imported skill from URL: {} -> {}", gitUrl, skillDir);
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException("Failed to import from git URL: " + gitUrl, e);
        } finally {
            if (tempDir != null) {
                try { deleteRecursively(tempDir); } catch (Exception ignored) {}
            }
        }
        return skillDir;
    }

    /** Add a custom .md file uploaded by user to the skill's custom/ folder. */
    public Path addCustomFile(String skillFolder, String filename, byte[] content) {
        Path dir = resolveSkillDir(skillFolder);
        Path customDir = dir.resolve("custom");
        try {
            Files.createDirectories(customDir);
            Path dest = customDir.resolve(sanitizeFilename(filename));
            Files.write(dest, content);
            log.info("Added custom file to {}: {}", skillFolder, filename);
            return dest;
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to add custom file", e);
        }
    }

    /** Delete a skill folder recursively. */
    public void deleteSkillFolder(String skillFolder) {
        Path dir = resolveSkillDir(skillFolder);
        try {
            if (Files.exists(dir)) {
                deleteRecursively(dir);
                log.info("Deleted skill folder: {}", dir);
            }
        } catch (IOException e) {
            log.warn("Failed to delete skill folder: {}", dir, e);
        }
    }

    /** List all files in a skill folder. */
    public List<Map<String, Object>> listFiles(String skillFolder) {
        Path dir = resolveSkillDir(skillFolder);
        List<Map<String, Object>> result = new ArrayList<>();
        if (!Files.exists(dir)) return result;

        try (Stream<Path> stream = Files.walk(dir)) {
            stream.filter(Files::isRegularFile)
                    .forEach(f -> {
                        Map<String, Object> item = new LinkedHashMap<>();
                        String relative = dir.relativize(f).toString().replace('\\', '/');
                        item.put("path", relative);
                        item.put("size", f.toFile().length());
                        try {
                            item.put("modified", Files.getLastModifiedTime(f).toString());
                        } catch (IOException ignored) {}
                        result.add(item);
                    });
        } catch (IOException e) {
            log.warn("Failed to list skill files: {}", skillFolder, e);
        }
        return result;
    }

    /**
     * Build the complete system prompt for the LLM by assembling all skill files.
     * Order: SKILL.md body (without frontmatter) → examples/ → references/ → custom/
     */
    public String buildSystemPrompt(String skillFolder) {
        Path dir = resolveSkillDir(skillFolder);
        if (!Files.exists(dir)) {
            log.warn("Skill folder not found: {}", skillFolder);
            return "";
        }

        StringBuilder sb = new StringBuilder();

        // 1. SKILL.md body (strip YAML frontmatter)
        Path skillMd = dir.resolve("SKILL.md");
        if (Files.exists(skillMd)) {
            try {
                String content = Files.readString(skillMd, StandardCharsets.UTF_8);
                String body = stripFrontMatter(content);
                sb.append(body).append("\n\n");
            } catch (IOException e) {
                log.warn("Failed to read SKILL.md for {}", skillFolder, e);
            }
        }

        // 2. Examples
        Path examplesDir = dir.resolve("examples");
        if (Files.exists(examplesDir)) {
            sb.append("\n## 对话示例\n\n");
            appendDirContents(sb, examplesDir);
        }

        // 3. References (knowledge base)
        Path refsDir = dir.resolve("references");
        if (Files.exists(refsDir)) {
            sb.append("\n## 知识库参考资料\n\n");
            appendDirContents(sb, refsDir);
        }

        // 4. Custom (user customizations — highest priority)
        Path customDir = dir.resolve("custom");
        if (Files.exists(customDir)) {
            sb.append("\n## 用户自定义规则（优先级最高）\n\n");
            appendDirContents(sb, customDir);
        }

        return sb.toString().trim();
    }

    // ==================== private helpers ====================

    private void copySkillContent(Path source, Path dest) throws IOException {
        // Copy SKILL.md if exists
        Path srcSkillMd = source.resolve("SKILL.md");
        if (Files.exists(srcSkillMd)) {
            Files.copy(srcSkillMd, dest.resolve("SKILL.md"), StandardCopyOption.REPLACE_EXISTING);
        }

        // Copy examples/ directory
        Path srcExamples = source.resolve("examples");
        if (Files.exists(srcExamples)) {
            copyDir(srcExamples, dest.resolve("examples"));
        }

        // Copy references/ directory
        Path srcRefs = source.resolve("references");
        if (Files.exists(srcRefs)) {
            copyDir(srcRefs, dest.resolve("references"));
        }

        // Also copy any SKILL.md in root with different casing
        try (Stream<Path> stream = Files.list(source)) {
            stream.filter(f -> f.getFileName().toString().equalsIgnoreCase("SKILL.md")
                            && !f.equals(srcSkillMd))
                    .forEach(f -> {
                        try {
                            Files.copy(f, dest.resolve("SKILL.md"), StandardCopyOption.REPLACE_EXISTING);
                        } catch (IOException ignored) {}
                    });
        }
    }

    private void copyDir(Path src, Path dst) throws IOException {
        Files.createDirectories(dst);
        try (Stream<Path> stream = Files.walk(src)) {
            stream.forEach(source -> {
                try {
                    Path target = dst.resolve(src.relativize(source));
                    if (Files.isDirectory(source)) {
                        Files.createDirectories(target);
                    } else {
                        Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
                    }
                } catch (IOException e) {
                    log.warn("Failed to copy {} -> {}", source, e.getMessage());
                }
            });
        }
    }

    private void appendDirContents(StringBuilder sb, Path dir) {
        try (Stream<Path> stream = Files.walk(dir)) {
            stream.filter(Files::isRegularFile)
                    .filter(f -> f.getFileName().toString().endsWith(".md"))
                    .sorted()
                    .forEach(f -> {
                        try {
                            String content = Files.readString(f, StandardCharsets.UTF_8);
                            String relative = dir.getParent().relativize(f).toString().replace('\\', '/');
                            sb.append("--- ").append(relative).append(" ---\n");
                            sb.append(content).append("\n\n");
                        } catch (IOException ignored) {}
                    });
        } catch (IOException ignored) {}
    }

    /** Strip YAML frontmatter (content between --- delimiters). */
    private String stripFrontMatter(String content) {
        String[] lines = content.split("\r?\n");
        if (lines.length == 0 || !lines[0].trim().equals("---")) {
            return content;
        }
        int end = -1;
        for (int i = 1; i < lines.length; i++) {
            if (lines[i].trim().equals("---")) {
                end = i;
                break;
            }
        }
        if (end < 0) return content;
        return Arrays.stream(lines, end + 1, lines.length)
                .collect(Collectors.joining("\n"));
    }

    private void deleteRecursively(Path path) throws IOException {
        if (!Files.exists(path)) return;
        Files.walkFileTree(path, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }
            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                Files.delete(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private String sanitize(String name) {
        return name.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
    }

    private String sanitizeFilename(String name) {
        return name.replaceAll("[\\\\/:*?\"<>|]", "_");
    }
}
