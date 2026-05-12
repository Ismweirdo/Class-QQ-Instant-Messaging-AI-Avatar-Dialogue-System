package com.chatroom.service;

import com.chatroom.model.dto.UpdateProfileDTO;
import com.chatroom.model.vo.UserVO;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

public interface UserService {
    List<UserVO> searchUsers(String keyword);
    UserVO getUserVO(Long userId);
    UserVO updateProfile(Long userId, UpdateProfileDTO dto);
    String uploadAvatar(Long userId, MultipartFile file);
    void deleteAccount(Long userId);
}
