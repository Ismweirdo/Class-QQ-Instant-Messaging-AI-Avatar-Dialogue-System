package com.chatroom.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class UpdateProfileDTO {
    @NotBlank(message = "昵称不能为空")
    @Size(min = 1, max = 50, message = "昵称长度为1-50个字符")
    private String nickname;
}
