package com.chatroom.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.chatroom.mapper.UserMapper;
import com.chatroom.model.dto.LoginDTO;
import com.chatroom.model.dto.RegisterDTO;
import com.chatroom.model.entity.User;
import com.chatroom.model.vo.LoginVO;
import com.chatroom.model.vo.UserVO;
import com.chatroom.security.JwtUtil;
import com.chatroom.service.AuthService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService {

    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;

    @Override
    public LoginVO register(RegisterDTO dto) {
        String accountNumber = generateAccountNumber();

        User user = new User();
        user.setUsername(accountNumber);
        user.setPassword(passwordEncoder.encode(dto.getPassword()));
        user.setNickname(dto.getNickname());
        user.setAvatar("/avatars/default.png");
        user.setStatus(0);
        user.setLastLoginTime(LocalDateTime.now());
        userMapper.insert(user);

        LoginVO loginVO = new LoginVO();
        loginVO.setToken(jwtUtil.generateToken(user.getId(), user.getUsername()));
        loginVO.setUser(toUserVO(user));
        return loginVO;
    }

    private String generateAccountNumber() {
        java.util.Random random = new java.util.Random();
        for (int attempt = 0; attempt < 10; attempt++) {
            String account = String.format("%08d", random.nextInt(100000000));
            LambdaQueryWrapper<User> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(User::getUsername, account);
            if (userMapper.selectCount(wrapper) == 0) {
                return account;
            }
        }
        throw new RuntimeException("账号生成失败，请重试");
    }

    @Override
    public LoginVO login(LoginDTO dto) {
        LambdaQueryWrapper<User> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(User::getUsername, dto.getUsername());
        User user = userMapper.selectOne(wrapper);

        if (user == null || !passwordEncoder.matches(dto.getPassword(), user.getPassword())) {
            throw new RuntimeException("用户名或密码错误");
        }

        user.setLastLoginTime(LocalDateTime.now());
        user.setStatus(1);
        userMapper.updateById(user);

        LoginVO loginVO = new LoginVO();
        loginVO.setToken(jwtUtil.generateToken(user.getId(), user.getUsername()));
        loginVO.setUser(toUserVO(user));
        return loginVO;
    }

    @Override
    public UserVO getCurrentUser(Long userId) {
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new RuntimeException("用户不存在");
        }
        return toUserVO(user);
    }

    public UserVO toUserVO(User user) {
        UserVO vo = new UserVO();
        BeanUtils.copyProperties(user, vo);
        return vo;
    }
}
