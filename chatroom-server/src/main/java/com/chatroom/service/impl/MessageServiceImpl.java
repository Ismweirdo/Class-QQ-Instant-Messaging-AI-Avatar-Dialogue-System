package com.chatroom.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.chatroom.common.Constants;
import com.chatroom.mapper.MessageMapper;
import com.chatroom.mapper.UserMapper;
import com.chatroom.model.dto.ChatMessageDTO;
import com.chatroom.model.entity.Message;
import com.chatroom.model.entity.User;
import com.chatroom.model.vo.MessageVO;
import com.chatroom.service.MessageService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class MessageServiceImpl implements MessageService {

    private final MessageMapper messageMapper;
    private final UserMapper userMapper;

    @Override
    @Transactional
    public MessageVO sendAndSaveMessage(Long senderId, ChatMessageDTO dto) {
        // Dedup: check clientMessageId first
        if (dto.getClientMessageId() != null && !dto.getClientMessageId().isEmpty()) {
            Message existing = messageMapper.selectOne(
                    new LambdaQueryWrapper<Message>()
                            .eq(Message::getClientMessageId, dto.getClientMessageId()));
            if (existing != null) {
                return toMessageVO(existing);
            }
        }

        Message msg = new Message();
        msg.setMessageType(dto.getMessageType());
        msg.setSenderId(senderId);
        msg.setTargetId(dto.getTargetId());
        msg.setReplyToId(dto.getReplyToId());
        msg.setContent(dto.getContent());
        msg.setContentType(dto.getContentType() != null ? dto.getContentType() : Constants.CONTENT_TYPE_TEXT);
        msg.setStatus(Constants.MSG_STATUS_SENT);
        msg.setClientMessageId(dto.getClientMessageId());
        msg.setCreatedAt(LocalDateTime.now());
        messageMapper.insert(msg);

        return toMessageVO(msg);
    }

    @Override
    public List<MessageVO> getPrivateHistory(Long userId, Long friendId, int page, int size) {
        LambdaQueryWrapper<Message> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Message::getMessageType, Constants.MSG_TYPE_PRIVATE)
                .and(w -> w
                    .and(w1 -> w1.eq(Message::getSenderId, userId).eq(Message::getTargetId, friendId))
                    .or(w2 -> w2.eq(Message::getSenderId, friendId).eq(Message::getTargetId, userId))
                )
                .ge(Message::getCreatedAt, LocalDateTime.now().minusDays(Constants.HISTORY_RETENTION_DAYS))
                .orderByDesc(Message::getId);

        Page<Message> pageResult = new Page<>(page, size);
        Page<Message> result = messageMapper.selectPage(pageResult, wrapper);

        List<MessageVO> vos = new ArrayList<>();
        // Reverse to chronological order
        List<Message> records = result.getRecords();
        for (int i = records.size() - 1; i >= 0; i--) {
            vos.add(toMessageVO(records.get(i)));
        }
        return vos;
    }

    @Override
    public List<MessageVO> getGroupHistory(Long groupId, int page, int size) {
        LambdaQueryWrapper<Message> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Message::getMessageType, Constants.MSG_TYPE_GROUP)
                .eq(Message::getTargetId, groupId)
                .ge(Message::getCreatedAt, LocalDateTime.now().minusDays(Constants.HISTORY_RETENTION_DAYS))
                .orderByDesc(Message::getId);

        Page<Message> pageResult = new Page<>(page, size);
        Page<Message> result = messageMapper.selectPage(pageResult, wrapper);

        List<MessageVO> vos = new ArrayList<>();
        List<Message> records = result.getRecords();
        for (int i = records.size() - 1; i >= 0; i--) {
            vos.add(toMessageVO(records.get(i)));
        }
        return vos;
    }

    @Override
    @Transactional
    public void recallMessage(Long messageId, Long userId) {
        Message msg = messageMapper.selectById(messageId);
        if (msg == null) {
            throw new RuntimeException("消息不存在");
        }
        if (!msg.getSenderId().equals(userId)) {
            throw new RuntimeException("只能撤回自己的消息");
        }

        long elapsed = java.time.Duration.between(msg.getCreatedAt(), LocalDateTime.now()).toMillis();
        if (elapsed > Constants.RECALL_WINDOW_MS) {
            throw new RuntimeException("超过2分钟的消息无法撤回");
        }

        msg.setContent("[消息已撤回]");
        msg.setContentType(Constants.CONTENT_TYPE_TEXT);
        messageMapper.updateById(msg);
    }

    @Override
    public MessageVO getMessageContext(Long messageId) {
        Message msg = messageMapper.selectById(messageId);
        if (msg == null) {
            throw new RuntimeException("消息不存在");
        }
        MessageVO vo = toMessageVO(msg);
        if (msg.getReplyToId() != null) {
            Message replied = messageMapper.selectById(msg.getReplyToId());
            if (replied != null) {
                vo.setReplyToContent(replied.getContent());
                User replySender = userMapper.selectById(replied.getSenderId());
                if (replySender != null) {
                    vo.setReplyToSenderName(replySender.getNickname());
                }
            }
        }
        return vo;
    }

    @Override
    public Message getById(Long messageId) {
        return messageMapper.selectById(messageId);
    }

    public MessageVO toMessageVO(Message msg) {
        MessageVO vo = new MessageVO();
        vo.setId(msg.getId());
        vo.setMessageType(msg.getMessageType());
        vo.setSenderId(msg.getSenderId());
        vo.setTargetId(msg.getTargetId());
        vo.setReplyToId(msg.getReplyToId());
        vo.setContent(msg.getContent());
        vo.setContentType(msg.getContentType());
        vo.setStatus(msg.getStatus());
        vo.setCreatedAt(msg.getCreatedAt());

        User sender = userMapper.selectById(msg.getSenderId());
        if (sender != null) {
            vo.setSenderName(sender.getNickname());
            vo.setSenderAvatar(sender.getAvatar());
        }

        if (msg.getReplyToId() != null) {
            Message replied = messageMapper.selectById(msg.getReplyToId());
            if (replied != null) {
                vo.setReplyToContent(replied.getContent());
                User replySender = userMapper.selectById(replied.getSenderId());
                if (replySender != null) {
                    vo.setReplyToSenderName(replySender.getNickname());
                }
            }
        }

        return vo;
    }
}
