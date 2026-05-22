package com.chatroom.model.vo;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class FriendVO {
    private Long id;
    private Long friendId;
    private String username;
    private String nickname;
    private String avatar;
    private Integer status;
    private Integer friendStatus;
    private Integer isBot;
    private String remark;
    private LocalDateTime createdAt;
}
