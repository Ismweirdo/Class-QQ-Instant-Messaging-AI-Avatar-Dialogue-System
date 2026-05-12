package com.chatroom.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.chatroom.mapper.*;
import com.chatroom.model.dto.UpdateProfileDTO;
import com.chatroom.model.entity.*;
import com.chatroom.model.vo.UserVO;
import com.chatroom.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class UserServiceImpl implements UserService {

    private final UserMapper userMapper;
    private final FriendMapper friendMapper;
    private final MessageMapper messageMapper;
    private final GroupMemberMapper groupMemberMapper;
    private final GroupMapper groupMapper;
    private final BotSkillMapper botSkillMapper;

    @Override
    public List<UserVO> searchUsers(String keyword) {
        LambdaQueryWrapper<User> wrapper = new LambdaQueryWrapper<>();
        wrapper.like(User::getUsername, keyword).or().like(User::getNickname, keyword);
        return userMapper.selectList(wrapper).stream()
                .map(this::toUserVO).collect(Collectors.toList());
    }

    @Override
    public UserVO getUserVO(Long userId) {
        User user = userMapper.selectById(userId);
        if (user == null) return null;
        return toUserVO(user);
    }

    @Override
    public UserVO updateProfile(Long userId, UpdateProfileDTO dto) {
        User user = userMapper.selectById(userId);
        if (user == null) throw new RuntimeException("用户不存在");
        user.setNickname(dto.getNickname());
        userMapper.updateById(user);
        return toUserVO(user);
    }

    @Override
    public String uploadAvatar(Long userId, MultipartFile file) {
        User user = userMapper.selectById(userId);
        if (user == null) throw new RuntimeException("用户不存在");

        String originalFilename = file.getOriginalFilename();
        String extension = "";
        if (originalFilename != null && originalFilename.contains(".")) {
            extension = originalFilename.substring(originalFilename.lastIndexOf(".")).toLowerCase();
        }
        if (!extension.matches("\\.(jpg|jpeg|png|gif|webp)")) {
            throw new RuntimeException("不支持的图片格式，仅支持 jpg、png、gif、webp");
        }

        String newFilename = UUID.randomUUID().toString() + extension;
        Path uploadPath = Path.of("./data/avatars");
        try {
            Files.createDirectories(uploadPath);
            file.transferTo(uploadPath.resolve(newFilename));
        } catch (IOException e) {
            throw new RuntimeException("头像上传失败");
        }

        String avatarUrl = "/avatars/" + newFilename;
        user.setAvatar(avatarUrl);
        userMapper.updateById(user);
        log.info("User {} avatar updated: {}", userId, avatarUrl);
        return avatarUrl;
    }

    @Override
    @Transactional
    public void deleteAccount(Long userId) {
        // 1. Delete all friend relationships
        friendMapper.delete(new LambdaQueryWrapper<Friend>()
                .eq(Friend::getUserId, userId).or().eq(Friend::getFriendId, userId));

        // 2. Delete all messages sent by user
        messageMapper.delete(new LambdaQueryWrapper<Message>()
                .eq(Message::getSenderId, userId));

        // 3. Find groups where user is a member
        List<GroupMember> memberships = groupMemberMapper.selectList(
                new LambdaQueryWrapper<GroupMember>().eq(GroupMember::getUserId, userId));
        List<Long> groupIds = memberships.stream().map(GroupMember::getGroupId).distinct().toList();

        // 4. Remove user from all groups
        groupMemberMapper.delete(new LambdaQueryWrapper<GroupMember>()
                .eq(GroupMember::getUserId, userId));

        // 5. Delete orphaned groups (0 members) or transfer ownership
        for (Long groupId : groupIds) {
            long memberCount = groupMemberMapper.selectCount(
                    new LambdaQueryWrapper<GroupMember>().eq(GroupMember::getGroupId, groupId));
            if (memberCount == 0) {
                groupMapper.deleteById(groupId);
                log.info("Deleted orphaned group {}", groupId);
            } else {
                // Transfer ownership if user was the owner
                Group group = groupMapper.selectById(groupId);
                if (group != null && group.getOwnerId().equals(userId)) {
                    GroupMember newOwner = groupMemberMapper.selectList(
                            new LambdaQueryWrapper<GroupMember>()
                                    .eq(GroupMember::getGroupId, groupId)
                                    .ne(GroupMember::getUserId, userId)
                                    .last("LIMIT 1")).stream().findFirst().orElse(null);
                    if (newOwner != null) {
                        group.setOwnerId(newOwner.getUserId());
                        newOwner.setRole(2); // GROUP_ROLE_OWNER
                        groupMapper.updateById(group);
                        groupMemberMapper.updateById(newOwner);
                        log.info("Transferred group {} ownership to user {}", groupId, newOwner.getUserId());
                    }
                }
            }
        }

        // 6. Delete bot skills if user is a bot
        botSkillMapper.delete(new LambdaQueryWrapper<BotSkill>()
                .eq(BotSkill::getBotUserId, userId));

        // 7. Delete the user
        userMapper.deleteById(userId);
        log.info("User {} account permanently deleted", userId);
    }

    private UserVO toUserVO(User user) {
        UserVO vo = new UserVO();
        BeanUtils.copyProperties(user, vo);
        return vo;
    }
}
