package com.chatroom.controller;

import com.chatroom.common.Result;
import com.chatroom.model.dto.UpdateProfileDTO;
import com.chatroom.model.vo.UserVO;
import com.chatroom.security.SecurityUtil;
import com.chatroom.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@Tag(name = "用户模块")
@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    @Operation(summary = "搜索用户")
    @GetMapping("/search")
    public Result<List<UserVO>> searchUsers(@RequestParam String keyword) {
        return Result.ok(userService.searchUsers(keyword));
    }

    @Operation(summary = "获取用户详情")
    @GetMapping("/{id}")
    public Result<UserVO> getUser(@PathVariable Long id) {
        UserVO vo = userService.getUserVO(id);
        if (vo == null) {
            return Result.notFound("用户不存在");
        }
        return Result.ok(vo);
    }

    @Operation(summary = "更新个人资料")
    @PutMapping("/profile")
    public Result<UserVO> updateProfile(@Valid @RequestBody UpdateProfileDTO dto) {
        return Result.ok(userService.updateProfile(SecurityUtil.getCurrentUserId(), dto));
    }

    @Operation(summary = "上传头像")
    @PostMapping(value = "/avatar", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Result<String> uploadAvatar(@RequestParam("file") MultipartFile file) {
        if (file.isEmpty()) {
            return Result.error("文件为空");
        }
        String contentType = file.getContentType();
        if (contentType == null || !contentType.startsWith("image/")) {
            return Result.error("只支持图片文件");
        }
        if (file.getSize() > 5 * 1024 * 1024) {
            return Result.error("头像文件不能超过5MB");
        }
        String avatarUrl = userService.uploadAvatar(SecurityUtil.getCurrentUserId(), file);
        return Result.ok(avatarUrl);
    }

    @Operation(summary = "注销当前用户账户")
    @DeleteMapping("/me")
    public Result<Void> deleteAccount() {
        userService.deleteAccount(SecurityUtil.getCurrentUserId());
        return Result.ok();
    }
}
