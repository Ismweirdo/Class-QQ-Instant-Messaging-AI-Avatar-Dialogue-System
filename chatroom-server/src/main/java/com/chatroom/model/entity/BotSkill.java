package com.chatroom.model.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("bot_skills")
public class BotSkill {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long botUserId;
    private String skillName;
    private String emotionProfileJson;
    private String languageStyleJson;
    private String systemPrompt;
    private String fewShotExamples;
    private String apiEndpoint;
    private String apiKey;
    private String model;
    private Integer status;         // 1=active, 0=inactive, 2=circuit_broken
    private Integer errorCount;
    private LocalDateTime lastActiveAt;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
