package com.chatroom.service;

import com.chatroom.model.dto.CreateGroupDTO;
import com.chatroom.model.vo.GroupVO;
import java.util.List;

public interface GroupService {
    GroupVO createGroup(Long ownerId, CreateGroupDTO dto);
    GroupVO getGroupDetail(Long groupId);
    List<GroupVO> getMyGroups(Long userId);
    void addMember(Long groupId, Long operatorId, Long userId);
    void removeMember(Long groupId, Long operatorId, Long userId);
    void quitGroup(Long groupId, Long userId);
    void disbandGroup(Long groupId, Long userId);
    void updateGroupInfo(Long groupId, Long userId, String name, String announcement);
    List<java.util.Map<String, Object>> getMembers(Long groupId);
}
