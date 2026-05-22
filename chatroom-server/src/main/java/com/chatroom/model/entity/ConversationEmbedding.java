package com.chatroom.model.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("conversation_embeddings")
public class ConversationEmbedding {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long botUserId;
    private Long userId;
    private Long messageId;
    private String content;
    private String embeddingJson;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
