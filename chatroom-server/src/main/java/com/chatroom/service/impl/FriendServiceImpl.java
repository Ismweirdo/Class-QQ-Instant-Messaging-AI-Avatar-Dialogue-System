package com.chatroom.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.chatroom.common.Constants;
import com.chatroom.mapper.FriendMapper;
import com.chatroom.mapper.UserMapper;
import com.chatroom.model.entity.Friend;
import com.chatroom.model.entity.User;
import com.chatroom.model.vo.FriendVO;
import com.chatroom.service.FriendService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class FriendServiceImpl implements FriendService {

    private final FriendMapper friendMapper;
    private final UserMapper userMapper;

    @Override
    @Transactional
    public void sendFriendRequest(Long userId, Long friendId, String message) {
        if (userId.equals(friendId)) {
            throw new RuntimeException("不能添加自己为好友");
        }

        // Check existing relationship
        LambdaQueryWrapper<Friend> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Friend::getUserId, userId).eq(Friend::getFriendId, friendId);
        if (friendMapper.selectCount(wrapper) > 0) {
            throw new RuntimeException("已经发送过好友申请");
        }

        // Check reverse direction
        wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Friend::getUserId, friendId).eq(Friend::getFriendId, userId);
        Friend reverse = friendMapper.selectOne(wrapper);
        if (reverse != null) {
            if (reverse.getStatus() == Constants.FRIEND_STATUS_PENDING) {
                // Accept directly if the other party already requested
                reverse.setStatus(Constants.FRIEND_STATUS_ACCEPTED);
                friendMapper.updateById(reverse);
                return;
            }
            if (reverse.getStatus() == Constants.FRIEND_STATUS_ACCEPTED) {
                throw new RuntimeException("已经是好友了");
            }
        }

        // If target is a bot, auto-accept the friend request
        User targetUser = userMapper.selectById(friendId);
        if (targetUser != null && targetUser.getIsBot() != null && targetUser.getIsBot() == 1) {
            Friend friend = new Friend();
            friend.setUserId(userId);
            friend.setFriendId(friendId);
            friend.setStatus(Constants.FRIEND_STATUS_ACCEPTED);
            friendMapper.insert(friend);
            return;
        }

        Friend friend = new Friend();
        friend.setUserId(userId);
        friend.setFriendId(friendId);
        friend.setStatus(Constants.FRIEND_STATUS_PENDING);
        friendMapper.insert(friend);
    }

    @Override
    @Transactional
    public void acceptFriendRequest(Long userId, Long friendId) {
        Friend request = getPendingRequest(friendId, userId);
        request.setStatus(Constants.FRIEND_STATUS_ACCEPTED);
        friendMapper.updateById(request);
    }

    @Override
    @Transactional
    public void rejectFriendRequest(Long userId, Long friendId) {
        Friend request = getPendingRequest(friendId, userId);
        request.setStatus(Constants.FRIEND_STATUS_REJECTED);
        friendMapper.updateById(request);
    }

    @Override
    public void deleteFriend(Long userId, Long friendId) {
        LambdaQueryWrapper<Friend> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Friend::getUserId, userId).eq(Friend::getFriendId, friendId)
                .or().eq(Friend::getUserId, friendId).eq(Friend::getFriendId, userId);
        friendMapper.delete(wrapper);
    }

    @Override
    public List<FriendVO> getFriendList(Long userId) {
        LambdaQueryWrapper<Friend> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Friend::getStatus, Constants.FRIEND_STATUS_ACCEPTED)
                .and(w -> w.eq(Friend::getUserId, userId).or().eq(Friend::getFriendId, userId));
        List<Friend> friends = friendMapper.selectList(wrapper);

        List<FriendVO> result = new ArrayList<>();
        for (Friend f : friends) {
            Long friendUserId = f.getUserId().equals(userId) ? f.getFriendId() : f.getUserId();
            User user = userMapper.selectById(friendUserId);
            if (user != null) {
                FriendVO vo = new FriendVO();
                vo.setId(f.getId());
                vo.setFriendId(friendUserId);
                vo.setUsername(user.getUsername());
                vo.setNickname(user.getNickname());
                vo.setAvatar(user.getAvatar());
                vo.setStatus(user.getStatus());
                vo.setFriendStatus(f.getStatus());
                vo.setIsBot(user.getIsBot());
                vo.setRemark(f.getRemark());
                vo.setCreatedAt(f.getCreatedAt());
                result.add(vo);
            }
        }
        log.info("getFriendList for user {}: found {} friends ({} Friend records, {} with valid User)",
                userId, result.size(), friends.size(), result.size());
        return result;
    }

    @Override
    public List<FriendVO> getPendingRequests(Long userId) {
        LambdaQueryWrapper<Friend> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Friend::getFriendId, userId).eq(Friend::getStatus, Constants.FRIEND_STATUS_PENDING);
        List<Friend> requests = friendMapper.selectList(wrapper);

        List<FriendVO> result = new ArrayList<>();
        for (Friend f : requests) {
            User user = userMapper.selectById(f.getUserId());
            if (user != null) {
                FriendVO vo = new FriendVO();
                vo.setId(f.getId());
                vo.setFriendId(f.getUserId());
                vo.setUsername(user.getUsername());
                vo.setNickname(user.getNickname());
                vo.setAvatar(user.getAvatar());
                vo.setStatus(user.getStatus());
                vo.setFriendStatus(f.getStatus());
                vo.setIsBot(user.getIsBot());
                vo.setRemark(f.getRemark());
                vo.setCreatedAt(f.getCreatedAt());
                result.add(vo);
            }
        }
        return result;
    }

    @Override
    public void setRemark(Long userId, Long friendId, String remark) {
        LambdaQueryWrapper<Friend> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Friend::getUserId, userId).eq(Friend::getFriendId, friendId);
        Friend f = friendMapper.selectOne(wrapper);
        if (f == null) {
            // Try reverse direction
            wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(Friend::getUserId, friendId).eq(Friend::getFriendId, userId);
            f = friendMapper.selectOne(wrapper);
        }
        if (f == null) {
            throw new RuntimeException("好友关系不存在");
        }
        f.setRemark(remark != null && !remark.isBlank() ? remark.trim() : null);
        friendMapper.updateById(f);
        log.info("Remark updated for friend {} by user {}: {}", friendId, userId, remark);
    }

    private Friend getPendingRequest(Long userId, Long friendId) {
        LambdaQueryWrapper<Friend> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Friend::getUserId, userId)
                .eq(Friend::getFriendId, friendId)
                .eq(Friend::getStatus, Constants.FRIEND_STATUS_PENDING);
        Friend request = friendMapper.selectOne(wrapper);
        if (request == null) {
            throw new RuntimeException("好友申请不存在");
        }
        return request;
    }
}
