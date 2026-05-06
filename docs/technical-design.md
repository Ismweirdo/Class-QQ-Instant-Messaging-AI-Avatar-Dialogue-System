# Chatroom 聊天室 — 技术设计文档

> v2.1 | 2026-05-06

## 1. 系统架构

```
┌──────────┐     ┌─────────────────────────┐     ┌──────────┐
│ Browser  │────▶│ Spring Boot Server(8080)│────▶│  MySQL   │
│ (Vue 3)  │◀────│ ├ REST API              │◀────│          │
│          │     │ ├ WebSocket (STOMP)      │     └──────────┘
│ SockJS/  │     │ ├ Spring Security + JWT  │
│ STOMP.js │     │ └ MyBatis-Plus           │
└──────────┘     └─────────────────────────┘
```

### 生产环境扩展架构
```
                 ┌──────────┐  ┌──────────┐  ┌──────────────┐
                 │  Redis   │  │ RabbitMQ │  │  LLM API群   │
                 │(缓存/Pub)│  │(消息队列)│  │(多Key多模型)  │
                 └──────────┘  └──────────┘  └──────────────┘
                      ▲             ▲               ▲
                      │             │               │
┌──────────┐  ┌──────────────────────────────┐  ┌──────────┐
│ Browser  │◀▶│  Spring Boot Cluster         │◀▶│  MySQL   │
│          │  │  + Bot Manager (N个机器人)    │  │  (主从)  │
└──────────┘  └──────────────────────────────┘  └──────────┘
```

## 2. 数据库设计

### 核心表
- `users` — (id, username, password, nickname, avatar, status, is_bot, last_login_time, created_at)
- `friends` — (id, user_id, friend_id, status, created_at)
- `groups` — (id, name, avatar, owner_id, announcement, max_members, created_at)
- `group_members` — (id, group_id, user_id, role, joined_at)
- `messages` — (id, message_type, sender_id, target_id, reply_to_id, content, content_type, status, client_message_id, created_at)
- `bot_skills` — (id, bot_user_id, skill_config_json, api_endpoint, api_key_hash, status, error_count, created_at)

### 消息表索引
- PRIMARY KEY (id) — 全局自增，保证消息顺序
- UNIQUE INDEX idx_client_msg (client_message_id) — 幂等去重
- INDEX idx_target_time (message_type, target_id, created_at) — 会话查询
- INDEX idx_created_at (created_at) — 定时清理

## 3. 实时通信设计

### WebSocket 连接流程
1. 客户端 SockJS 连接 `/ws/chat?token={jwt}`
2. 握手拦截器验证 JWT，提取 userId
3. 订阅 `/user/queue/private/chat`（私聊）和 `/topic/group/{id}`（群聊）
4. 发送消息通过 `/app/chat.send`

### 消息协议
```json
// Client → Server
{
  "content": "...", "messageType": 0, "targetId": 2,
  "replyToId": null, "contentType": 0, "clientMessageId": "uuid"
}
// Server → Client  
{
  "type": "CHAT", "messageId": 12345, "messageType": 0,
  "senderId": 1, "senderName": "Alice", "targetId": 2,
  "content": "...", "createdAt": "2026-05-05T12:00:00"
}
```

### 消息可靠性
1. 客户端发送 → 服务端推送+持久化 → 返回ACK
2. 接收方在线 → 实时推送；离线 → 上线后从DB拉取
3. clientMessageId 去重: 布隆过滤器 → Redis → DB唯一索引 三层校验

## 4. 高并发设计

### 缓存策略 (10万用户规模)
| 缓存项 | 类型 | TTL |
|--------|------|-----|
| 在线状态 | Redis Hash | 心跳30s续期 |
| 最近消息 | Redis ZSet | 1h |
| JWT Session | Redis String | 7d |
| 好友列表 | Redis Set | 30min |
| 群成员 | Redis Set | 10min |
| 限流计数 | Redis String INCR | 窗口制 |

### 限流
- WebSocket: 每IP 5个连接
- 消息发送: 每用户 10条/s，每IP 100条/s
- API: Guava RateLimiter (单机) / Sentinel (分布式)

### 异步处理
- 消息先推后存: WS推送 → 异步写DB (批量: 100条/500ms)
- 生产环境: WS → RabbitMQ → Batch Insert DB

## 5. 消息一致性

### 幂等设计
clientMessageId (UUID v4) → 布隆过滤器快速判定 → Redis去重缓存(24h) → DB UNIQUE索引兜底。重复消息返回已有messageId，不重复推送。

### 消息有序
DB自增ID作为全局单调递增排序依据，避免NTP时钟回拨问题。

### 消息状态机
```
SENDING → SENT → DELIVERED → READ
              ↘ FAILED (30s超时，重试3次)
```

### 群组广播
先写DB → 在线成员实时推送 → 离线成员 Redis 记录未读计数。500人大群采用异步分批推送，避免广播风暴。

## 6. Redis 架构

### 数据结构
| Key | 类型 | 用途 |
|-----|------|------|
| `user:online:{id}` | String | 在线状态+所在节点 |
| `msg:dedup:{clientId}` | String | 去重(24h) |
| `msg:recent:{type}:{targetId}` | ZSet | 最近消息缓存 |
| `bot:status:{botId}` | Hash | 机器人状态/错误计数 |

### Pub/Sub 跨节点通信
```
Node-1 ──publish──▶ Redis ──subscribe──▶ Node-2
Channels: ws:user:{id} | ws:group:{id} | ws:system | ws:bot
```

### 高可用
Sentinel 1主2从3哨兵，RDB+AOF持久化，故障转移 < 30s。扩展路径: Redis Cluster 16K槽位分片。

## 7. 安全设计
- 密码: BCrypt (cost=10)
- 认证: JWT HMAC-SHA256，7天过期
- API: Spring Security + @PreAuthorize
- WebSocket: 握手阶段JWT验证
- 防护: 前端XSS过滤 + MyBatis参数化防注入

## 8. 聊天记录蒸馏 — 多机器人Skill系统

### 8.1 设计目标
从真实聊天记录中**提取语言风格和情绪模式**作为Skill，不涉及模型训练。每个Skill = System Prompt + Few-shot Examples。支持两种数据来源：
- **数据库蒸馏**: 定时任务分析30天聊天记录
- **文件导入**: 用户上传聊天记录文件，即时生成机器人

### 8.2 导入聊天记录格式
支持三种格式：JSONL（每行一个JSON对象）、JSON数组、TXT（微信/QQ导出格式 `昵称: 消息`）。每条记录需包含 `sender`（发送者）和 `content`（消息内容）。

### 8.3 蒸馏流水线

```
┌──────────┐    ┌──────────┐    ┌──────────┐    ┌──────────┐
│ 数据导出  │───▶│ 用户聚类  │───▶│ 特征提取  │───▶│ Skill生成 │
│ (30天)   │    │ (按sender)│    │(情绪/句式│    │(Prompt+  │
│          │    │          │    │ /用词)   │    │ Examples)│
└──────────┘    └──────────┘    └──────────┘    └─────┬────┘
                                                      │
                                                      ▼
                                               ┌──────────┐
                                               │ 注册机器人 │
                                               │ (20+Bot) │
                                               └──────────┘
```

### 8.3 特征提取 (纯数据分析，不训练)

**情绪分布提取**:
- 扫描用户所有消息，基于情绪词典统计 喜/怒/哀/惊/恐/厌 六维分布
- 统计语气助词使用频率 (呢/哦/呀/吧/嘛/哈)
- 统计Emoji和表情包使用偏好

**句式模式提取**:
- 平均句长、句长方差
- 问句/感叹句/陈述句比例
- 高频开头词和结尾词

**用词偏好提取**:
- TF-IDF提取Top 50高频词
- 统计叠词、网络用语、英文混用比例

**对话节奏提取**:
- 平均回复字数、回复延迟分布
- 是否倾向承接上文 vs 开启新话题

### 8.4 Skill配置
```json
{
  "skill_id": "skill_001",
  "name": "温柔知心派",
  "emotion_profile": {
    "base_tone": "温和",
    "distribution": {"joy": 0.3, "care": 0.4, "sad": 0.1, "surprise": 0.1, "anger": 0.0, "fear": 0.1}
  },
  "language_style": {
    "avg_sentence_len": 15,
    "use_emoji": true,
    "habit_openings": ["哈哈", "嗯嗯", "其实"],
    "habit_endings": ["呢", "哦", "呀"]
  },
  "system_prompt": "你是一个温柔体贴的聊天对象，说话轻声细语，喜欢用'呢'和'哦'结尾。回答时总是先安慰对方情绪，再慢慢聊开...",
  "few_shot_examples": [
    {"role": "user", "content": "今天好累"},
    {"role": "assistant", "content": "辛苦了呀，好好休息一下呢~"},
    {"role": "user", "content": "好无聊"},
    {"role": "assistant", "content": "哈哈，那我陪你聊聊天呀，想聊什么呢？"}
  ]
}
```

### 8.5 多机器人并发管理

```
                     ┌─────────────────┐
                     │   Bot Manager   │
                     │                 │
                     │ botList: [      │
                     │  {id:1,skill:A, │
                     │   api:"openai", │
                     │   key:"sk-xxx"} │
                     │  {id:2,skill:B, │
                     │   api:"qwen",   │
                     │   key:"sk-yyy"} │
                     │  ...x20        │
                     │ ]               │
                     └────────┬────────┘
                              │
              ┌───────────────┼───────────────┐
              │               │               │
         ┌────▼───┐     ┌────▼───┐     ┌────▼───┐
         │ Bot-1  │     │ Bot-2  │     │ Bot-20 │
         │ WS连接  │     │ WS连接  │     │ WS连接  │
         │ API-A  │     │ API-B  │     │ API-X  │
         └────────┘     └────────┘     └────────┘
```

**并发控制策略**:
```
信号量(per Bot)=1 → 同一Bot串行处理消息，避免状态混乱
消息队列(per Bot)=10 → 超出丢弃最旧，防止堆积
熔断器: 连续3次失败 → 静默30s → 半开探测 → 恢复或继续熔断
```

### 8.6 错误隔离与容错

| 故障场景 | 处理方式 | 影响范围 |
|---------|---------|---------|
| 单个Bot API超时 | 熔断该Bot 30s，返回静默 | 仅该Bot |
| 单个Bot API Key失效 | 标记INACTIVE，告警通知 | 仅该Bot |
| 某厂商API全面故障 | 使用该厂商的所有Bot熔断 | 使用该厂商的Bot |
| Bot Manager宕机 | Bot账号WS断连，在线用户列表移除 | 所有Bot暂时离线 |

**错误率保障**:
- 每个Bot独立API Key，故障隔离
- 支持多厂商 (OpenAI / Qwen / ChatGLM / DeepSeek等)，分散风险
- 20个Bot分散到至少3个不同API厂商
- Bot错误率 < 0.1%: 即每1000条消息中 < 1次API错误

### 8.7 监控指标
| 指标 | 采集 | 告警阈值 |
|------|------|---------|
| Bot回复延迟 | Bot Manager | > 3s (P95) |
| Bot错误率 | 错误计数/总消息 | > 5%/min |
| Bot在线数 | 心跳检测 | < 20 |
| API调用频率 | 各API厂商 | 接近限频上限 |

## 9. 水平扩展

### 扩容阶段
| 阶段 | 用户量 | 节点 | Redis | MySQL |
|------|--------|------|-------|-------|
| V1 Demo | < 100 | 1 (H2) | 无 | H2 |
| V2 小规模 | < 1000 | 1-2 (2C4G) | 单机 | 单机 |
| V3 中规模 | 1-10万 | 3-5 (4C8G) | Sentinel | 主从 |
| V4 大规模 | 10万+ | 5-10 (8C16G) | Cluster | 主从+分表 |

### 数据库扩展路径
```
H2 → MySQL单机 → 主从读写分离 → 消息表按月分区 → ShardingSphere分库分表(user_id取模)
```

### Nginx负载均衡
```nginx
upstream chatroom_backend {
    ip_hash;  # WebSocket需会话保持
    server server-1:8080 weight=1;
    server server-2:8080 weight=1;
    keepalive 64;
}
```

### 自动扩缩
- CPU > 70% (5min) → 扩容
- WS连接 > 800/节点 → 扩容
- API P95 > 300ms → 扩容或优化
