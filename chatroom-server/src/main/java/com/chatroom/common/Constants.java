package com.chatroom.common;

public class Constants {

    // Message types
    public static final int MSG_TYPE_PRIVATE = 0;
    public static final int MSG_TYPE_GROUP = 1;

    // Content types
    public static final int CONTENT_TYPE_TEXT = 0;
    public static final int CONTENT_TYPE_IMAGE = 1;
    public static final int CONTENT_TYPE_FILE = 2;

    // Message status
    public static final int MSG_STATUS_SENT = 0;
    public static final int MSG_STATUS_DELIVERED = 1;
    public static final int MSG_STATUS_READ = 2;

    // Friend status
    public static final int FRIEND_STATUS_PENDING = 0;
    public static final int FRIEND_STATUS_ACCEPTED = 1;
    public static final int FRIEND_STATUS_REJECTED = 2;
    public static final int FRIEND_STATUS_BLOCKED = 3;

    // Group member roles
    public static final int GROUP_ROLE_MEMBER = 0;
    public static final int GROUP_ROLE_ADMIN = 1;
    public static final int GROUP_ROLE_OWNER = 2;

    // User status
    public static final int USER_STATUS_OFFLINE = 0;
    public static final int USER_STATUS_ONLINE = 1;
    public static final int USER_STATUS_BUSY = 2;

    // Message recall window (2 minutes in milliseconds)
    public static final long RECALL_WINDOW_MS = 2 * 60 * 1000;

    // Chat history retention (30 days)
    public static final int HISTORY_RETENTION_DAYS = 30;

    // Page defaults
    public static final int DEFAULT_PAGE_SIZE = 20;
    public static final int MAX_PAGE_SIZE = 100;

    // Bot status
    public static final int BOT_STATUS_ACTIVE = 1;
    public static final int BOT_STATUS_INACTIVE = 0;
    public static final int BOT_STATUS_CIRCUIT_BROKEN = 2;

    // Bot limits
    public static final int BOT_CIRCUIT_BREAK_THRESHOLD = 3;
    public static final long BOT_CIRCUIT_BREAK_SILENCE_MS = 30_000;
    public static final int BOT_MAX_QUEUE_SIZE = 10;
    public static final int BOT_MAX_CONCURRENCY = 1;

    // Distillation
    public static final int DISTILL_MIN_MESSAGES = 100;
    public static final int DISTILL_CONTEXT_WINDOW = 4;
    public static final int DISTILL_MAX_WORDS = 50;
    public static final int DISTILL_MIN_WORDS = 5;
    public static final int DISTILL_MAX_CHARS = 200;
    public static final int DISTILL_MIN_CHARS = 5;
}
