package com.chatroom.websocket;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.chatroom.common.Constants;
import com.chatroom.mapper.FriendMapper;
import com.chatroom.mapper.GroupMemberMapper;
import com.chatroom.mapper.MessageMapper;
import com.chatroom.mapper.UserMapper;
import com.chatroom.model.dto.ChatMessageDTO;
import com.chatroom.model.entity.Friend;
import com.chatroom.model.entity.Message;
import com.chatroom.model.entity.User;
import com.chatroom.model.vo.MessageVO;
import com.chatroom.service.BotManager;
import com.chatroom.service.MessageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.stereotype.Controller;
import org.springframework.web.socket.messaging.SessionConnectEvent;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

import java.security.Principal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

@Slf4j
@Controller
@RequiredArgsConstructor
public class ChatWebSocketHandler {

    private final SimpMessagingTemplate messagingTemplate;
    private final MessageService messageService;
    private final MessageMapper messageMapper;
    private final UserMapper userMapper;
    private final FriendMapper friendMapper;
    private final GroupMemberMapper groupMemberMapper;
    private final OnlineStatusManager onlineStatusManager;
    private final BotManager botManager;

    @org.springframework.beans.factory.annotation.Qualifier("botTaskExecutor")
    private final Executor botTaskExecutor;

    // sessionId -> userId
    private final ConcurrentHashMap<String, Long> sessionUserMap = new ConcurrentHashMap<>();

    @EventListener
    public void handleConnectEvent(SessionConnectEvent event) {
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(event.getMessage());
        Principal principal = accessor.getUser();
        if (principal != null) {
            Long userId = Long.valueOf(principal.getName());
            String sessionId = accessor.getSessionId();
            sessionUserMap.put(sessionId, userId);
            onlineStatusManager.userOnline(userId, sessionId);

            // Update user status in DB
            User user = userMapper.selectById(userId);
            if (user != null) {
                user.setStatus(Constants.USER_STATUS_ONLINE);
                userMapper.updateById(user);
            }

            // Broadcast online status to friends
            broadcastPresence(userId, true);

            log.info("User {} connected, session: {}", userId, sessionId);
        }
    }

    @EventListener
    public void handleDisconnectEvent(SessionDisconnectEvent event) {
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(event.getMessage());
        String sessionId = accessor.getSessionId();
        Long userId = sessionUserMap.remove(sessionId);
        if (userId != null) {
            onlineStatusManager.userOffline(userId);

            // Update user status in DB
            User user = userMapper.selectById(userId);
            if (user != null) {
                user.setStatus(Constants.USER_STATUS_OFFLINE);
                userMapper.updateById(user);
            }

            // Broadcast offline status to friends
            broadcastPresence(userId, false);

            log.info("User {} disconnected, session: {}", userId, sessionId);
        }
    }

    private void broadcastPresence(Long userId, boolean online) {
        // Find all friends of this user
        LambdaQueryWrapper<Friend> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Friend::getStatus, Constants.FRIEND_STATUS_ACCEPTED)
                .and(w -> w.eq(Friend::getUserId, userId).or().eq(Friend::getFriendId, userId));
        List<Friend> friends = friendMapper.selectList(wrapper);

        Map<String, Object> presence = new HashMap<>();
        presence.put("type", "PRESENCE");
        presence.put("userId", userId);
        presence.put("status", online ? "ONLINE" : "OFFLINE");

        for (Friend f : friends) {
            Long friendId = f.getUserId().equals(userId) ? f.getFriendId() : f.getUserId();
            messagingTemplate.convertAndSendToUser(
                    String.valueOf(friendId), "/queue/private/presence", presence);
        }
    }

    @MessageMapping("/chat.send")
    public void handleChatMessage(@Payload ChatMessageDTO dto, Principal principal) {
        Long senderId = Long.valueOf(principal.getName());
        log.info("Chat message from {} to {} (type={})", senderId, dto.getTargetId(), dto.getMessageType());

        MessageVO messageVO = messageService.sendAndSaveMessage(senderId, dto);
        Map<String, Object> payload = buildWsPayload(messageVO);

        if (dto.getMessageType() == Constants.MSG_TYPE_PRIVATE) {
            messagingTemplate.convertAndSendToUser(
                    String.valueOf(senderId), "/queue/private/chat", payload);
            messagingTemplate.convertAndSendToUser(
                    String.valueOf(dto.getTargetId()), "/queue/private/chat", payload);

            // Bot routing: check if target is a bot
            User targetUser = userMapper.selectById(dto.getTargetId());
            if (targetUser != null && targetUser.getIsBot() != null && targetUser.getIsBot() == 1) {
                handleBotReply(senderId, dto.getTargetId(), messageVO);
            }
        } else if (dto.getMessageType() == Constants.MSG_TYPE_GROUP) {
            messagingTemplate.convertAndSend(
                    "/topic/group/" + dto.getTargetId(), payload);

            // Bot routing: check if message contains @mention of a bot
            handleGroupBotReply(senderId, dto.getTargetId(), dto.getContent(), messageVO);
        }
    }

    private void handleBotReply(Long senderId, Long botUserId, MessageVO userMessage) {
        CompletableFuture.runAsync(() -> {
            try {
                User sender = userMapper.selectById(senderId);
                String senderName = sender != null ? sender.getNickname() : "用户";
                // Send stream start marker
                var streamPayload = new java.util.HashMap<String, Object>();
                streamPayload.put("type", "BOT_STREAM_START");
                streamPayload.put("botUserId", botUserId);
                streamPayload.put("targetId", senderId);
                messagingTemplate.convertAndSendToUser(String.valueOf(senderId), "/queue/bot/stream", streamPayload);
                messagingTemplate.convertAndSendToUser(String.valueOf(botUserId), "/queue/bot/stream", streamPayload);

                String fullReply = botManager.handleBotMessageStream(botUserId, senderId, senderName,
                    userMessage.getContent(), token -> {
                        var chunk = new java.util.HashMap<String, Object>();
                        chunk.put("type", "BOT_STREAM_CHUNK");
                        chunk.put("botUserId", botUserId);
                        chunk.put("targetId", senderId);
                        chunk.put("token", token);
                        messagingTemplate.convertAndSendToUser(String.valueOf(senderId), "/queue/bot/stream", chunk);
                        messagingTemplate.convertAndSendToUser(String.valueOf(botUserId), "/queue/bot/stream", chunk);
                    });

                // Send stream end with full message
                if (fullReply != null && !fullReply.isEmpty()) {
                    var endPayload = new java.util.HashMap<String, Object>();
                    endPayload.put("type", "BOT_STREAM_END");
                    endPayload.put("botUserId", botUserId);
                    endPayload.put("targetId", senderId);
                    endPayload.put("content", fullReply);
                    messagingTemplate.convertAndSendToUser(String.valueOf(senderId), "/queue/bot/stream", endPayload);
                    messagingTemplate.convertAndSendToUser(String.valueOf(botUserId), "/queue/bot/stream", endPayload);
                    // Save to DB without pushing via CHAT channel (already delivered via stream)
                    saveBotMessageToDb(botUserId, senderId, fullReply, Constants.MSG_TYPE_PRIVATE);
                }
            } catch (Exception e) {
                log.error("Bot {} reply error", botUserId, e);
            }
        }, botTaskExecutor);
    }

    private void handleGroupBotReply(Long senderId, Long groupId, String content, MessageVO userMessage) {
        // Only get bots that are members of this group
        List<com.chatroom.model.entity.GroupMember> members = groupMemberMapper.selectList(
            new LambdaQueryWrapper<com.chatroom.model.entity.GroupMember>().eq(com.chatroom.model.entity.GroupMember::getGroupId, groupId));
        List<User> bots = new ArrayList<>();
        for (var m : members) {
            User u = userMapper.selectById(m.getUserId());
            if (u != null && u.getIsBot() != null && u.getIsBot() == 1) bots.add(u);
        }
        log.info("Group {} bot reply check: {} bots in group, content='{}'", groupId, bots.size(), content != null ? content.substring(0, Math.min(50, content.length())) : "null");
        if (bots.isEmpty()) return;

        boolean atAll = content != null && (content.contains("@全体成员")
                || content.contains("@everyone") || content.contains("@all"));
        String cleanedContent = atAll
                ? content.replace("@全体成员", "").replace("@everyone", "").replace("@all", "").trim()
                : content;

        // Random chance (30%) a bot chimes in even without explicit mention — makes groups lively
        java.util.Random rng = new java.util.Random();

        CompletableFuture.runAsync(() -> {
            for (User bot : bots) {
                boolean mentioned = content != null && (content.contains("@" + bot.getNickname())
                        || content.contains("@" + bot.getUsername()));
                boolean randomChimeIn = !atAll && !mentioned && rng.nextDouble() < 0.30;
                if (!atAll && !mentioned && !randomChimeIn) continue;

                try {
                    User sender = userMapper.selectById(senderId);
                    String senderName = sender != null ? sender.getNickname() : "用户";
                    String msgForBot = mentioned
                            ? content.replace("@" + bot.getNickname(), "").replace("@" + bot.getUsername(), "").trim()
                            : cleanedContent;

                    // Stream group reply
                    var streamStart = new java.util.HashMap<String, Object>();
                    streamStart.put("type", "BOT_STREAM_START");
                    streamStart.put("botUserId", bot.getId());
                    streamStart.put("targetId", groupId);
                    streamStart.put("isGroup", true);
                    messagingTemplate.convertAndSend("/topic/group/" + groupId + "/stream", streamStart);

                    String fullReply = botManager.handleBotMessageStream(bot.getId(), senderId, senderName,
                        msgForBot, token -> {
                            var chunk = new java.util.HashMap<String, Object>();
                            chunk.put("type", "BOT_STREAM_CHUNK");
                            chunk.put("botUserId", bot.getId());
                            chunk.put("token", token);
                            messagingTemplate.convertAndSend("/topic/group/" + groupId + "/stream", chunk);
                        });

                    if (fullReply != null && !fullReply.trim().isEmpty()) {
                        var streamEnd = new java.util.HashMap<String, Object>();
                        streamEnd.put("type", "BOT_STREAM_END");
                        streamEnd.put("botUserId", bot.getId());
                        streamEnd.put("content", fullReply);
                        messagingTemplate.convertAndSend("/topic/group/" + groupId + "/stream", streamEnd);
                        saveBotMessageToDb(bot.getId(), groupId, fullReply, Constants.MSG_TYPE_GROUP);
                        try { Thread.sleep(800); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
                    }
                } catch (Exception e) {
                    log.error("Bot {} group reply error", bot.getId(), e);
                }
            }
        }, botTaskExecutor);
    }

    private void saveBotMessageToDb(Long botUserId, Long targetId, String content, int messageType) {
        ChatMessageDTO botDto = new ChatMessageDTO();
        botDto.setContent(content);
        botDto.setMessageType(messageType);
        botDto.setTargetId(targetId);
        botDto.setContentType(Constants.CONTENT_TYPE_TEXT);
        botDto.setClientMessageId("BOT_" + UUID.randomUUID().toString().replace("-", ""));
        messageService.sendAndSaveMessage(botUserId, botDto);
    }

    @MessageMapping("/chat.ack")
    public void handleAck(@Payload Map<String, Object> ack, Principal principal) {
        Long messageId = Long.valueOf(ack.get("messageId").toString());
        Message msg = messageMapper.selectById(messageId);
        if (msg != null && msg.getStatus() != Constants.MSG_STATUS_READ) {
            msg.setStatus(Constants.MSG_STATUS_READ);
            messageMapper.updateById(msg);
        }
    }

    private Map<String, Object> buildWsPayload(MessageVO vo) {
        Map<String, Object> map = new HashMap<>();
        map.put("type", "CHAT");
        map.put("messageId", vo.getId());
        map.put("messageType", vo.getMessageType());
        map.put("senderId", vo.getSenderId());
        map.put("senderName", vo.getSenderName());
        map.put("senderAvatar", vo.getSenderAvatar());
        map.put("targetId", vo.getTargetId());
        map.put("replyToId", vo.getReplyToId());
        map.put("replyToContent", vo.getReplyToContent());
        map.put("replyToSenderName", vo.getReplyToSenderName());
        map.put("content", vo.getContent());
        map.put("contentType", vo.getContentType());
        map.put("status", vo.getStatus());
        map.put("createdAt", vo.getCreatedAt() != null ? vo.getCreatedAt().toString() : null);
        return map;
    }
}
