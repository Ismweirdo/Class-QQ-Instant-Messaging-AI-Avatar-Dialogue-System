package com.chatroom.controller;

import com.chatroom.common.Result;
import com.chatroom.model.vo.MessageVO;
import com.chatroom.security.SecurityUtil;
import com.chatroom.service.MessageService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "消息模块")
@RestController
@RequestMapping("/api/messages")
@RequiredArgsConstructor
public class MessageController {

    private final MessageService messageService;

    @Operation(summary = "获取私聊历史消息")
    @GetMapping("/private/{friendId}")
    public Result<List<MessageVO>> getPrivateHistory(@PathVariable Long friendId,
                                                      @RequestParam(defaultValue = "1") int page,
                                                      @RequestParam(defaultValue = "20") int size) {
        return Result.ok(messageService.getPrivateHistory(SecurityUtil.getCurrentUserId(), friendId, page, size));
    }

    @Operation(summary = "获取群聊历史消息")
    @GetMapping("/group/{groupId}")
    public Result<List<MessageVO>> getGroupHistory(@PathVariable Long groupId,
                                                    @RequestParam(defaultValue = "1") int page,
                                                    @RequestParam(defaultValue = "20") int size) {
        return Result.ok(messageService.getGroupHistory(groupId, page, size));
    }

    @Operation(summary = "撤回消息（2分钟内标记为已撤回）")
    @DeleteMapping("/{id}")
    public Result<Void> recallMessage(@PathVariable Long id) {
        messageService.recallMessage(id, SecurityUtil.getCurrentUserId());
        return Result.ok();
    }

    @Operation(summary = "永久删除单条消息")
    @DeleteMapping("/{id}/permanent")
    public Result<Void> deleteMessage(@PathVariable Long id) {
        messageService.permanentDelete(id, SecurityUtil.getCurrentUserId());
        return Result.ok();
    }

    @Operation(summary = "清除与好友的聊天记录")
    @DeleteMapping("/private/{friendId}")
    public Result<java.util.Map<String, Object>> clearPrivateHistory(@PathVariable Long friendId) {
        int deleted = messageService.clearPrivateHistory(SecurityUtil.getCurrentUserId(), friendId);
        return Result.ok(java.util.Map.of("deleted", deleted, "friendId", friendId));
    }

    @Operation(summary = "获取消息上下文(含引用消息)")
    @GetMapping("/{id}/context")
    public Result<MessageVO> getMessageContext(@PathVariable Long id) {
        return Result.ok(messageService.getMessageContext(id));
    }
}
