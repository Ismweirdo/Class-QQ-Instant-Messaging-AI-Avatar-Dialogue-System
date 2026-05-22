# Chatroom Bot 多级记忆系统设计文档

## 概述

Bot 记忆系统采用四层架构，模拟人类记忆模型：工作记忆 → 短期记忆 → 长期记忆 → 语义记忆。每层有不同的存储介质、容量限制、时效性和访问优先级，协同工作使 Bot 具备跨会话的上下文感知能力。

## 架构总览

```
┌─────────────────────────────────────────────────────────────┐
│                    LLM Context Assembly                     │
│  ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌──────────┐       │
│  │  System  │ │ Working  │ │Long-Term │ │Few-Shot  │       │
│  │ Prompt   │→│ Memory   │→│ Memory   │→│Examples  │→ ...  │
│  └──────────┘ └──────────┘ └──────────┘ └──────────┘       │
│  ┌──────────┐ ┌──────────┐ ┌──────────┐                    │
│  │Short-Term│ │   RAG    │ │ Current  │                    │
│  │  Memory  │→│ Retrieval│→│ Message  │                    │
│  └──────────┘ └──────────┘ └──────────┘                    │
└─────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────┐
│                     Storage Backends                        │
│                                                             │
│  Level 0 (Working)   Redis String    TTL: 30min  容量: 5轮 │
│  Level 1 (Short-Term) Redis List     TTL: 60min  容量: 30条│
│  Level 2 (Long-Term)  MySQL + Redis  TTL: 120min 容量: 50条│
│  Level 3 (Semantic)   MySQL          持久化      容量: 500条│
└─────────────────────────────────────────────────────────────┘
```

## 各层详解

### Level 0: 工作记忆 (Working Memory)

**定位**：最近几轮对话的完整记录，等同于"我正在想的事"。

| 属性 | 值 |
|------|-----|
| 存储 | Redis String (`conv:work:{min}:{max}`) |
| 结构 | JSON 数组，每项 `{user, bot}` |
| 容量 | 5 轮完整对话 |
| 单条上限 | 3000 字符 |
| TTL | 30 分钟 |
| 优先级 | 最高 — 始终注入上下文，不被压缩 |

**数据流**：
```
用户发消息 → Bot 回复
     ↓
cacheConversationExchange()
     ↓
memoryCache.addToWorkingMemory(userContent, botContent)
     ↓
超过 5 轮时移除最旧条目
```

**上下文注入格式**：
```
以下是最近的对话上下文（工作记忆），请优先参考：
[user]: 我今天去看了电影
[assistant]: 什么电影？好看吗？
[user]: 流浪地球3，特效很震撼
[assistant]: 我也想看！听说口碑不错
```

---

### Level 1: 短期记忆 (Short-Term Memory)

**定位**：最近一段时间内的消息历史，等同于"刚才聊了什么"。

| 属性 | 值 |
|------|-----|
| 存储 | Redis List (`conv:short:{min}:{max}`) |
| 结构 | JSON 条目，每项 `{role, senderId, senderName, content}` |
| 容量 | 30 条消息（可配置，默认取 `memorySize`，上限 `BOT_SHORT_TERM_MAX`） |
| TTL | 60 分钟 |
| DB 回退 | `messages` 表，30 天内记录 |

**数据流**：
```
cacheConversationExchange()
     ↓
memoryCache.addMessage(userMsg, maxSize)
memoryCache.addMessage(botMsg, maxSize)
     ↓
超过阈值 → maybeConsolidateMemory() → Level 2
```

**去重**：注入上下文时，自动排除已存在于工作记忆中的消息，避免重复。

**记忆整合触发**：当短期记忆条目数 > 20（`BOT_LTM_CONSOLIDATION_THRESHOLD`），触发异步整合流程。

---

### Level 2: 长期记忆 (Long-Term Memory)

**定位**：跨会话持久化的用户画像和对话摘要，等同于"我了解这个人什么"。

| 属性 | 值 |
|------|-----|
| 存储 | MySQL `bot_long_term_memory` + Redis 缓存 (`conv:ltm:{min}:{max}`) |
| 容量 | 每对 (bot, user) 最多 50 条 |
| TTL (缓存) | 120 分钟 |
| 每次注入上限 | 5 条（`BOT_LTM_CONTEXT_LIMIT`） |

#### 数据库表结构

```sql
CREATE TABLE bot_long_term_memory (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    bot_user_id     BIGINT NOT NULL,
    user_id         BIGINT NOT NULL,
    memory_type     VARCHAR(20) NOT NULL,  -- 'summary' / 'fact' / 'preference'
    content         TEXT NOT NULL,
    importance      INT DEFAULT 1,         -- 1-5 重要性评分
    source_message_ids TEXT,               -- 来源消息ID（逗号分隔）
    created_at      DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_bot_user_pair (bot_user_id, user_id),
    INDEX idx_bot_user_type (bot_user_id, user_id, memory_type)
);
```

#### 记忆类型

| 类型 | 说明 | 示例 |
|------|------|------|
| `fact` | 关于用户的事实信息 | "用户名叫张三，在北京工作，职业是程序员" |
| `preference` | 用户的偏好和习惯 | "用户喜欢简洁直接的回复，不喜欢客套话" |
| `summary` | 对话主题摘要 | "上次聊了关于AI发展趋势的话题，用户对多模态模型很感兴趣" |

#### 重要性评分 (1-5)

| 分数 | 含义 | 示例 |
|------|------|------|
| 5 | 极其重要 | 用户姓名、重大事件、强烈偏好 |
| 4 | 重要 | 职业、长期兴趣、常用习惯 |
| 3 | 有价值 | 对话主题、一般偏好 |
| 2 | 参考 | 一次性话题、临时信息 |
| 1 | 琐碎 | 闲聊细节、重复内容 |

#### 记忆整合流程

```
短期记忆 > 20条
     ↓
maybeConsolidateMemory()
     ↓
memoryCache.trimMemory() → 裁剪最旧50%
     ↓
CompletableFuture.runAsync()
     ↓
LongTermMemoryService.consolidateFromMessages()
     ↓
LLM 分析对话文本 → 提取 JSON:
  {
    "facts": [{"content": "...", "importance": 4}, ...],
    "preferences": [{"content": "...", "importance": 3}, ...],
    "summary": "对话摘要"
  }
     ↓
逐条 storeMemory() → DB + Redis
     ↓
超过 50 条时淘汰最旧记录
```

**手动触发**：`POST /api/bots/{botUserId}/consolidate`

#### 上下文注入格式

```
【关于该用户的长期记忆】
1. [事实] 用户名叫小明，住在上海
2. [偏好] 用户喜欢带emoji的回复风格
3. [摘要] 上次聊到用户最近在学习Rust语言，进展到生命周期概念
```

---

### Level 3: 语义记忆 (Semantic Memory / RAG)

**定位**：基于向量相似度的历史对话检索，等同于"我记得以前聊过类似的话题"。

| 属性 | 值 |
|------|-----|
| 存储 | MySQL `conversation_embeddings` |
| 容量 | 每对 500 条 embedding |
| 检索方式 | 向量余弦相似度（主） / 关键词 Jaccard（回退） |
| 每次检索 | 最近 200 条中取 top-K（默认 3） |
| 相似度阈值 | 向量: 0.3 / 关键词: 0.05 |

**去重增强**：RAG 结果注入前自动排除与工作记忆和短期记忆中重复的内容。

**异步存储**：每次对话后通过 `CompletableFuture` 异步计算并存储 embedding，不阻塞消息回复。

---

## 上下文组装流程

`BotManager.buildContext()` 按以下顺序组装 LLM 消息列表：

```
1. System Prompt
   └─ buildEnrichedSystemPrompt()
      ├─ skillFolder → SKILL.md 的 system_prompt 段
      ├─ fallback → DB systemPrompt
      ├─ emotionProfile → 格式化情感设定
      ├─ languageStyle → 格式化语言风格
      └─ conversationMode → 模式提示词
      └─ compressPrompt() (>8000字符时保留头5000+尾3000)

2. Working Memory (Level 0)
   └─ memoryCache.getWorkingMemory()
      └─ 最近5轮完整用户↔Bot对话

3. Long-Term Memory (Level 2)
   └─ ltmService.buildLtmContext()
      └─ 重要性前5条的事实/偏好/摘要

4. Few-Shot Examples
   └─ parseFewShotMessages()
      └─ 交替 user/assistant 消息

5. Short-Term Memory (Level 1)
   └─ memoryCache.getMemory() / messageService.getRecentMessages()
      └─ 去重后注入剩余消息

6. RAG Retrieval (Level 3)
   └─ ragMemoryService.retrieveRelevant()
      └─ 向量/关键词检索 → 去重 → 注入

7. Current Message
   └─ 用户当前输入（含文件/图片内容提取）
```

## 配置参数

```yaml
# application.yml
bot:
  memory:
    cache-ttl-minutes: 60      # 短期记忆 Redis TTL
    work-ttl-minutes: 30       # 工作记忆 Redis TTL
```

```java
// Constants.java
BOT_WORKING_MEMORY_SIZE = 5          // 工作记忆最大轮数
BOT_WORKING_MEMORY_MAX_CHARS = 3000  // 单条消息最大字符数
BOT_SHORT_TERM_MAX = 30              // 短期记忆最大条目数
BOT_LTM_CONSOLIDATION_THRESHOLD = 20 // 触发整合的阈值
BOT_LTM_MAX_PER_PAIR = 50            // 长期记忆每对最大条目
BOT_LTM_CONTEXT_LIMIT = 5            // 每次注入上下文的最大条目
```

## REST API

| 方法 | 路径 | 说明 |
|------|------|------|
| `GET` | `/api/bots/{botUserId}/long-term-memory` | 获取 LTM 条目列表 |
| `DELETE` | `/api/bots/{botUserId}/long-term-memory` | 清除当前用户的 LTM |
| `POST` | `/api/bots/{botUserId}/consolidate` | 手动触发记忆整合 |
| `PUT` | `/api/bots/{botUserId}/rag-config` | 配置 RAG（已有） |
| `GET` | `/api/bots/{botUserId}/rag-stats` | RAG 统计（已有） |

## 文件清单

| 文件 | 说明 |
|------|------|
| `service/LongTermMemoryService.java` | 长期记忆核心服务 |
| `service/ConversationMemoryCache.java` | 工作记忆 + 短期记忆 Redis 缓存 |
| `service/BotManager.java` | 上下文组装 + 记忆整合调度 |
| `service/RagMemoryService.java` | 语义检索（已有，增强去重） |
| `service/EmbeddingService.java` | Embedding API 客户端 |
| `model/entity/LongTermMemory.java` | LTM 实体 |
| `mapper/LongTermMemoryMapper.java` | LTM Mapper |
| `controller/BotController.java` | LTM REST 端点 |
| `common/Constants.java` | 记忆相关常量 |
| `resources/sql/schema.sql` | `bot_long_term_memory` 表定义 |

## 降级策略

所有 Redis 依赖均带有 DB 回退和异常容错：

| 场景 | 行为 |
|------|------|
| Redis 不可用 | 工作记忆跳过；短期记忆回退 MySQL `messages` 表；LTM 直接查 DB |
| LLM 整合失败 | 日志记录警告，不阻塞消息处理；下次对话时重新触发 |
| Embedding API 失败 | RAG 回退关键词 Jaccard 检索 |
| DB 写入失败 | 异常向上传播，API 返回 500 错误 |
