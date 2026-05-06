-- Chatroom Database Schema
-- H2 initializion script (also serves as MySQL DDL reference)

CREATE TABLE IF NOT EXISTS users (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    username VARCHAR(50) NOT NULL UNIQUE,
    password VARCHAR(255) NOT NULL,
    nickname VARCHAR(50),
    avatar VARCHAR(255),
    status TINYINT DEFAULT 0,
    is_bot TINYINT DEFAULT 0,
    last_login_time DATETIME,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS friends (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    friend_id BIGINT NOT NULL,
    status TINYINT DEFAULT 0,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_user_friend (user_id, friend_id),
    INDEX idx_user_status (user_id, status),
    INDEX idx_friend_status (friend_id, status)
);

CREATE TABLE IF NOT EXISTS `groups` (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    avatar VARCHAR(255),
    owner_id BIGINT NOT NULL,
    announcement TEXT,
    max_members INT DEFAULT 200,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS group_members (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    group_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    role TINYINT DEFAULT 0,
    nickname_in_group VARCHAR(50),
    joined_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_group_user (group_id, user_id),
    INDEX idx_user_groups (user_id)
);

CREATE TABLE IF NOT EXISTS messages (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    message_type TINYINT NOT NULL,
    sender_id BIGINT NOT NULL,
    target_id BIGINT NOT NULL,
    reply_to_id BIGINT,
    content TEXT NOT NULL,
    content_type TINYINT DEFAULT 0,
    status TINYINT DEFAULT 0,
    client_message_id VARCHAR(36),
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_target_time (message_type, target_id, created_at),
    INDEX idx_private_query (sender_id, target_id, created_at),
    INDEX idx_created_at (created_at),
    INDEX idx_reply (reply_to_id),
    UNIQUE KEY uk_client_msg (client_message_id)
);

CREATE TABLE IF NOT EXISTS bot_skills (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    bot_user_id BIGINT NOT NULL,
    skill_name VARCHAR(100),
    emotion_profile_json TEXT,
    language_style_json TEXT,
    system_prompt TEXT,
    few_shot_examples TEXT,
    api_endpoint VARCHAR(255),
    api_key VARCHAR(255),
    model VARCHAR(100),
    status TINYINT DEFAULT 1,
    error_count INT DEFAULT 0,
    last_active_at DATETIME,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_bot_user (bot_user_id),
    INDEX idx_bot_status (status)
);
