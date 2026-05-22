package com.chatroom.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.chatroom.common.Constants;
import com.chatroom.mapper.GroupMapper;
import com.chatroom.mapper.GroupMemberMapper;
import com.chatroom.mapper.UserMapper;
import com.chatroom.model.dto.CreateGroupDTO;
import com.chatroom.model.entity.Group;
import com.chatroom.model.entity.GroupMember;
import com.chatroom.model.entity.User;
import com.chatroom.model.vo.GroupVO;
import com.chatroom.service.GroupService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class GroupServiceImpl implements GroupService {

    private final GroupMapper groupMapper;
    private final GroupMemberMapper groupMemberMapper;
    private final UserMapper userMapper;
    private final com.chatroom.mapper.MessageMapper messageMapper;

    @Override
    @Transactional
    public GroupVO createGroup(Long ownerId, CreateGroupDTO dto) {
        Group group = new Group();
        group.setName(dto.getName());
        group.setOwnerId(ownerId);
        group.setMaxMembers(200);
        groupMapper.insert(group);

        // Add owner as member
        GroupMember ownerMember = new GroupMember();
        ownerMember.setGroupId(group.getId());
        ownerMember.setUserId(ownerId);
        ownerMember.setRole(Constants.GROUP_ROLE_OWNER);
        User owner = userMapper.selectById(ownerId);
        ownerMember.setNicknameInGroup(owner != null ? owner.getNickname() : "");
        groupMemberMapper.insert(ownerMember);

        // Add invited members
        if (dto.getMemberIds() != null) {
            for (Long memberId : dto.getMemberIds()) {
                if (!memberId.equals(ownerId)) {
                    GroupMember gm = new GroupMember();
                    gm.setGroupId(group.getId());
                    gm.setUserId(memberId);
                    gm.setRole(Constants.GROUP_ROLE_MEMBER);
                    User user = userMapper.selectById(memberId);
                    gm.setNicknameInGroup(user != null ? user.getNickname() : "");
                    groupMemberMapper.insert(gm);
                }
            }
        }

        return getGroupDetail(group.getId());
    }

    @Override
    public GroupVO getGroupDetail(Long groupId) {
        Group group = groupMapper.selectById(groupId);
        if (group == null) {
            throw new RuntimeException("群组不存在");
        }

        GroupVO vo = new GroupVO();
        vo.setId(group.getId());
        vo.setName(group.getName());
        vo.setAvatar(group.getAvatar());
        vo.setOwnerId(group.getOwnerId());
        vo.setAnnouncement(group.getAnnouncement());
        vo.setMaxMembers(group.getMaxMembers());
        vo.setCreatedAt(group.getCreatedAt());

        User owner = userMapper.selectById(group.getOwnerId());
        if (owner != null) {
            vo.setOwnerName(owner.getNickname());
        }

        LambdaQueryWrapper<GroupMember> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(GroupMember::getGroupId, groupId);
        List<GroupMember> members = groupMemberMapper.selectList(wrapper);
        vo.setMemberCount(members.size());

        List<GroupVO.MemberVO> memberVOs = new ArrayList<>();
        for (GroupMember gm : members) {
            GroupVO.MemberVO mvo = new GroupVO.MemberVO();
            mvo.setUserId(gm.getUserId());
            mvo.setRole(gm.getRole());
            mvo.setNicknameInGroup(gm.getNicknameInGroup());
            mvo.setJoinedAt(gm.getJoinedAt());

            User user = userMapper.selectById(gm.getUserId());
            if (user != null) {
                mvo.setUsername(user.getUsername());
                mvo.setNickname(user.getNickname());
                mvo.setAvatar(user.getAvatar());
            }
            memberVOs.add(mvo);
        }
        vo.setMembers(memberVOs);
        return vo;
    }

    @Override
    public List<GroupVO> getMyGroups(Long userId) {
        LambdaQueryWrapper<GroupMember> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(GroupMember::getUserId, userId);
        List<GroupMember> memberships = groupMemberMapper.selectList(wrapper);

        List<GroupVO> result = new ArrayList<>();
        for (GroupMember gm : memberships) {
            Group group = groupMapper.selectById(gm.getGroupId());
            if (group != null) {
                GroupVO vo = getGroupDetail(group.getId());
                result.add(vo);
            }
        }
        return result;
    }

    @Override
    @Transactional
    public void addMember(Long groupId, Long operatorId, Long userId) {
        checkOwnershipOrAdmin(groupId, operatorId);

        LambdaQueryWrapper<GroupMember> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(GroupMember::getGroupId, groupId).eq(GroupMember::getUserId, userId);
        if (groupMemberMapper.selectCount(wrapper) > 0) {
            throw new RuntimeException("用户已在群中");
        }

        GroupMember gm = new GroupMember();
        gm.setGroupId(groupId);
        gm.setUserId(userId);
        gm.setRole(Constants.GROUP_ROLE_MEMBER);
        groupMemberMapper.insert(gm);
    }

    @Override
    @Transactional
    public void removeMember(Long groupId, Long operatorId, Long userId) {
        checkOwnershipOrAdmin(groupId, operatorId);

        if (isOwner(groupId, userId)) {
            throw new RuntimeException("不能移除群主");
        }

        LambdaQueryWrapper<GroupMember> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(GroupMember::getGroupId, groupId).eq(GroupMember::getUserId, userId);
        groupMemberMapper.delete(wrapper);
    }

    @Override
    @Transactional
    public void quitGroup(Long groupId, Long userId) {
        if (isOwner(groupId, userId)) {
            throw new RuntimeException("群主不能退群，请先转让群主或解散群");
        }

        LambdaQueryWrapper<GroupMember> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(GroupMember::getGroupId, groupId).eq(GroupMember::getUserId, userId);
        groupMemberMapper.delete(wrapper);
    }

    @Override
    @Transactional
    public void disbandGroup(Long groupId, Long userId) {
        if (!isOwner(groupId, userId)) {
            throw new RuntimeException("只有群主才能解散群组");
        }

        // 1. Delete all group members
        LambdaQueryWrapper<GroupMember> memberWrapper = new LambdaQueryWrapper<>();
        memberWrapper.eq(GroupMember::getGroupId, groupId);
        groupMemberMapper.delete(memberWrapper);

        // 2. Delete all group messages
        LambdaQueryWrapper<com.chatroom.model.entity.Message> msgWrapper =
            new LambdaQueryWrapper<>();
        msgWrapper.eq(com.chatroom.model.entity.Message::getMessageType, Constants.MSG_TYPE_GROUP)
                  .eq(com.chatroom.model.entity.Message::getTargetId, groupId);
        messageMapper.delete(msgWrapper);

        // 3. Delete the group itself
        groupMapper.deleteById(groupId);
    }

    @Override
    public void updateGroupInfo(Long groupId, Long userId, String name, String announcement) {
        checkOwnershipOrAdmin(groupId, userId);

        Group group = groupMapper.selectById(groupId);
        if (group == null) {
            throw new RuntimeException("群组不存在");
        }
        if (name != null && !name.isBlank()) {
            group.setName(name);
        }
        if (announcement != null) {
            group.setAnnouncement(announcement);
        }
        groupMapper.updateById(group);
    }

    private void checkOwnershipOrAdmin(Long groupId, Long userId) {
        LambdaQueryWrapper<GroupMember> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(GroupMember::getGroupId, groupId).eq(GroupMember::getUserId, userId);
        GroupMember member = groupMemberMapper.selectOne(wrapper);
        if (member == null || member.getRole() == Constants.GROUP_ROLE_MEMBER) {
            throw new RuntimeException("没有权限执行此操作");
        }
    }

    private boolean isOwner(Long groupId, Long userId) {
        Group group = groupMapper.selectById(groupId);
        return group != null && group.getOwnerId().equals(userId);
    }

    @Override
    public List<java.util.Map<String, Object>> getMembers(Long groupId) {
        List<GroupMember> members = groupMemberMapper.selectList(
            new LambdaQueryWrapper<GroupMember>().eq(GroupMember::getGroupId, groupId));
        List<java.util.Map<String, Object>> result = new ArrayList<>();
        for (GroupMember gm : members) {
            User user = userMapper.selectById(gm.getUserId());
            if (user != null) {
                result.add(java.util.Map.of(
                    "userId", gm.getUserId(),
                    "nickname", user.getNickname() != null ? user.getNickname() : user.getUsername(),
                    "username", user.getUsername(),
                    "avatar", user.getAvatar() != null ? user.getAvatar() : "",
                    "isBot", user.getIsBot() != null ? user.getIsBot() : 0,
                    "role", gm.getRole()
                ));
            }
        }
        return result;
    }
}
