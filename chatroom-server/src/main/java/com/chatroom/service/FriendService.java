package com.chatroom.service;

import com.chatroom.model.vo.FriendVO;
import java.util.List;

public interface FriendService {
    void sendFriendRequest(Long userId, Long friendId, String message);
    void acceptFriendRequest(Long userId, Long friendId);
    void rejectFriendRequest(Long userId, Long friendId);
    void deleteFriend(Long userId, Long friendId);
    List<FriendVO> getFriendList(Long userId);
    List<FriendVO> getPendingRequests(Long userId);
    void setRemark(Long userId, Long friendId, String remark);
}
