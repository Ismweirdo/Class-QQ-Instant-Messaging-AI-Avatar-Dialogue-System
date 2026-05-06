package com.chatroom.config;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.chatroom.common.Constants;
import com.chatroom.mapper.MessageMapper;
import com.chatroom.model.entity.Message;
import com.chatroom.service.SkillDistillerService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

@Slf4j
@Component
@RequiredArgsConstructor
public class CleanupScheduler {

    private final MessageMapper messageMapper;
    private final SkillDistillerService skillDistillerService;

    // Run daily at 2:00 AM — distill skills from chat records
    @Scheduled(cron = "0 0 2 * * ?")
    public void distillSkillsFromChat() {
        try {
            log.info("Starting chat record distillation for skill generation");
            var skills = skillDistillerService.distillSkills();
            log.info("Distillation complete: {} skill configs generated", skills.size());
        } catch (Exception e) {
            log.error("Skill distillation failed", e);
        }
    }

    // Run daily at 3:00 AM — clean expired messages
    @Scheduled(cron = "0 0 3 * * ?")
    public void cleanExpiredMessages() {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(Constants.HISTORY_RETENTION_DAYS);
        LambdaQueryWrapper<Message> wrapper = new LambdaQueryWrapper<>();
        wrapper.lt(Message::getCreatedAt, cutoff);
        long deleted = messageMapper.delete(wrapper);
        log.info("Cleaned {} messages older than {} days", deleted, Constants.HISTORY_RETENTION_DAYS);
    }
}
