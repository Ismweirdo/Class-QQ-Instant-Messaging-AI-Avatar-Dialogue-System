package com.chatroom.controller;

import com.chatroom.common.Result;
import com.chatroom.model.dto.CreateGroupDTO;
import com.chatroom.model.vo.GroupVO;
import com.chatroom.security.SecurityUtil;
import com.chatroom.service.GroupService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Tag(name = "群组模块")
@RestController
@RequestMapping("/api/groups")
@RequiredArgsConstructor
public class GroupController {

    private final GroupService groupService;
    private final com.chatroom.service.BotManager botManager;

    @Operation(summary = "创建群组")
    @PostMapping
    public Result<GroupVO> createGroup(@Valid @RequestBody CreateGroupDTO dto) {
        return Result.ok(groupService.createGroup(SecurityUtil.getCurrentUserId(), dto));
    }

    @Operation(summary = "获取群详情")
    @GetMapping("/{id}")
    public Result<GroupVO> getGroupDetail(@PathVariable Long id) {
        return Result.ok(groupService.getGroupDetail(id));
    }

    @Operation(summary = "获取我的群列表")
    @GetMapping
    public Result<List<GroupVO>> getMyGroups() {
        return Result.ok(groupService.getMyGroups(SecurityUtil.getCurrentUserId()));
    }

    @Operation(summary = "获取群成员列表")
    @GetMapping("/{id}/members")
    public Result<List<Map<String, Object>>> getMembers(@PathVariable Long id) {
        return Result.ok(groupService.getMembers(id));
    }

    @Operation(summary = "邀请成员")
    @PostMapping("/{id}/members")
    public Result<Void> addMember(@PathVariable Long id, @RequestBody Map<String, Long> body) {
        groupService.addMember(id, SecurityUtil.getCurrentUserId(), body.get("userId"));
        return Result.ok();
    }

    @Operation(summary = "移除成员")
    @DeleteMapping("/{id}/members/{memberId}")
    public Result<Void> removeMember(@PathVariable Long id, @PathVariable Long memberId) {
        groupService.removeMember(id, SecurityUtil.getCurrentUserId(), memberId);
        return Result.ok();
    }

    @Operation(summary = "解散群组（仅群主）")
    @DeleteMapping("/{id}")
    public Result<Void> disbandGroup(@PathVariable Long id) {
        groupService.disbandGroup(id, SecurityUtil.getCurrentUserId());
        return Result.ok();
    }

    @Operation(summary = "退出群组")
    @PostMapping("/{id}/quit")
    public Result<Void> quitGroup(@PathVariable Long id) {
        groupService.quitGroup(id, SecurityUtil.getCurrentUserId());
        return Result.ok();
    }

    @Operation(summary = "更新群信息")
    @PutMapping("/{id}")
    public Result<Void> updateGroup(@PathVariable Long id, @RequestBody Map<String, String> body) {
        groupService.updateGroupInfo(id, SecurityUtil.getCurrentUserId(), body.get("name"), body.get("announcement"));
        return Result.ok();
    }

    @Operation(summary = "获取群Bot自动聊天配置")
    @GetMapping("/{id}/bot-auto-chat")
    public Result<Map<String, Object>> getBotAutoChat(@PathVariable Long id) {
        return Result.ok(botManager.getGroupAutoChatConfig(id));
    }

    @Operation(summary = "批量设置群Bot自动聊天开关")
    @PutMapping("/{id}/bot-auto-chat")
    public Result<Map<String, Object>> setBotAutoChat(@PathVariable Long id,
                                                       @RequestBody Map<String, Object> body) {
        @SuppressWarnings("unchecked")
        List<Integer> ids = (List<Integer>) body.get("botUserIds");
        Set<Long> botIds = ids != null ? ids.stream().map(Integer::longValue).collect(java.util.stream.Collectors.toSet()) : Set.of();
        botManager.setGroupAutoChatConfig(id, botIds);
        return Result.ok(Map.of("groupId", id, "enabled", botIds.size()));
    }
}
