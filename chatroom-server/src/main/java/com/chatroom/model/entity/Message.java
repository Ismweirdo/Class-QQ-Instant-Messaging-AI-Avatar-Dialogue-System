package com.chatroom.model.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("messages")
public class Message {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Integer messageType;
    private Long senderId;
    private Long targetId;
    private Long replyToId;
    private String content;
    private Integer contentType;
    private Integer status;
    private String clientMessageId;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
