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
    public static final int BOT_CIRCUIT_BREAK_THRESHOLD = 5;
    public static final long BOT_CIRCUIT_BREAK_SILENCE_MS = 15_000;
    public static final int BOT_MAX_QUEUE_SIZE = 10;
    public static final int BOT_MAX_CONCURRENCY = 1;

    // Bot LLM parameters
    public static final int BOT_DEFAULT_MAX_TOKENS = 4096;
    public static final double BOT_DEFAULT_TEMPERATURE = 0.8;
    public static final int BOT_DEFAULT_MEMORY_SIZE = 10;
    public static final int BOT_MAX_MEMORY_SIZE = 50;

    // Multi-level memory
    public static final int BOT_WORKING_MEMORY_SIZE = 5;        // max exchanges in working memory
    public static final int BOT_WORKING_MEMORY_MAX_CHARS = 3000; // max chars per exchange in working memory
    public static final int BOT_SHORT_TERM_MAX = 30;            // max messages in short-term Redis list
    public static final int BOT_LTM_CONSOLIDATION_THRESHOLD = 20; // trigger consolidation when short-term exceeds this
    public static final int BOT_LTM_MAX_PER_PAIR = 50;          // max long-term memory entries per bot-user pair
    public static final int BOT_LTM_CONTEXT_LIMIT = 5;          // max LTM entries injected into context
    public static final int BOT_LTM_IMPORTANCE_MIN = 1;         // minimum importance score (1-5)

    // Distillation
    public static final int DISTILL_MIN_MESSAGES = 100;
    public static final int DISTILL_CONTEXT_WINDOW = 4;
    public static final int DISTILL_MAX_WORDS = 50;
    public static final int DISTILL_MIN_WORDS = 5;
    public static final int DISTILL_MAX_CHARS = 200;
    public static final int DISTILL_MIN_CHARS = 5;
}
