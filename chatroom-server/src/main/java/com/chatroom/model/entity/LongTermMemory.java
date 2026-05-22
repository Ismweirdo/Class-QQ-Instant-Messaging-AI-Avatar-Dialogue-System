package com.chatroom.model.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("bot_long_term_memory")
public class LongTermMemory {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long botUserId;
    private Long userId;
    private String memoryType;   // summary / fact / preference
    private String content;
    private Integer importance;  // 1-5
    private String sourceMessageIds;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
