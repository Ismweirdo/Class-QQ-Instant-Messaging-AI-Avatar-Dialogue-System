package com.chatroom.websocket;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class OnlineStatusManager {

    // userId -> sessionId (simplified; production should use Redis)
    private final ConcurrentHashMap<Long, String> onlineUsers = new ConcurrentHashMap<>();

    public void userOnline(Long userId, String sessionId) {
        onlineUsers.put(userId, sessionId);
    }

    public void userOffline(Long userId) {
        onlineUsers.remove(userId);
    }
}
