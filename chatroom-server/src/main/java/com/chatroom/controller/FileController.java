package com.chatroom.controller;

import com.chatroom.common.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.*;
import java.util.Map;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/files")
public class FileController {

    @Value("${app.upload-dir:./data/uploads}")
    private String uploadDir;

    @PostMapping("/upload")
    public Result<Map<String, Object>> upload(@RequestParam("file") MultipartFile file) {
        try {
            Path dir = Paths.get(uploadDir);
            Files.createDirectories(dir);

            String ext = "";
            String originalName = file.getOriginalFilename();
            if (originalName != null && originalName.contains(".")) {
                ext = originalName.substring(originalName.lastIndexOf("."));
            }
            String filename = UUID.randomUUID().toString().replace("-", "").substring(0, 16) + ext;
            Path dest = dir.resolve(filename);
            file.transferTo(dest);

            boolean isImage = ext.matches("\\.(png|jpg|jpeg|gif|webp|bmp)$");
            String url = "/api/files/" + filename;

            return Result.ok(Map.of(
                "filename", filename,
                "originalName", originalName,
                "url", url,
                "isImage", isImage,
                "size", file.getSize()
            ));
        } catch (IOException e) {
            log.error("File upload failed", e);
            return Result.error(500, "上传失败: " + e.getMessage());
        }
    }

    @GetMapping("/{filename}")
    public byte[] download(@PathVariable String filename) throws IOException {
        Path file = Paths.get(uploadDir).resolve(filename).normalize();
        if (!Files.exists(file)) throw new RuntimeException("文件不存在");
        return Files.readAllBytes(file);
    }
}
