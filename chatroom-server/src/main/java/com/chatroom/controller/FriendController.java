package com.chatroom.controller;

import com.chatroom.common.Result;
import com.chatroom.model.dto.AddFriendDTO;
import com.chatroom.model.vo.FriendVO;
import com.chatroom.security.SecurityUtil;
import com.chatroom.service.FriendService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@Tag(name = "好友模块")
@RestController
@RequestMapping("/api/friends")
@RequiredArgsConstructor
public class FriendController {

    private final FriendService friendService;

    @Operation(summary = "发送好友申请")
    @PostMapping("/add")
    public Result<Void> addFriend(@Valid @RequestBody AddFriendDTO dto) {
        friendService.sendFriendRequest(SecurityUtil.getCurrentUserId(), dto.getFriendId(), dto.getMessage());
        return Result.ok();
    }

    @Operation(summary = "接受好友申请")
    @PutMapping("/{friendId}/accept")
    public Result<Void> acceptRequest(@PathVariable Long friendId) {
        friendService.acceptFriendRequest(SecurityUtil.getCurrentUserId(), friendId);
        return Result.ok();
    }

    @Operation(summary = "拒绝好友申请")
    @PutMapping("/{friendId}/reject")
    public Result<Void> rejectRequest(@PathVariable Long friendId) {
        friendService.rejectFriendRequest(SecurityUtil.getCurrentUserId(), friendId);
        return Result.ok();
    }

    @Operation(summary = "删除好友")
    @DeleteMapping("/{friendId}")
    public Result<Void> deleteFriend(@PathVariable Long friendId) {
        friendService.deleteFriend(SecurityUtil.getCurrentUserId(), friendId);
        return Result.ok();
    }

    @Operation(summary = "获取好友列表")
    @GetMapping
    public Result<List<FriendVO>> getFriendList() {
        return Result.ok(friendService.getFriendList(SecurityUtil.getCurrentUserId()));
    }

    @Operation(summary = "设置好友备注")
    @PutMapping("/{friendId}/remark")
    public Result<Map<String, Object>> setRemark(@PathVariable Long friendId,
                                                  @RequestBody Map<String, String> body) {
        String remark = body.get("remark");
        friendService.setRemark(SecurityUtil.getCurrentUserId(), friendId, remark);
        return Result.ok(Map.of("friendId", friendId, "remark", remark != null ? remark : ""));
    }

    @Operation(summary = "获取待处理的好友申请")
    @GetMapping("/requests")
    public Result<List<FriendVO>> getPendingRequests() {
        return Result.ok(friendService.getPendingRequests(SecurityUtil.getCurrentUserId()));
    }
}
