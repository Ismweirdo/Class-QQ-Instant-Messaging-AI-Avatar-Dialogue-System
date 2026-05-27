# Chatroom — AI 聊天室

> v3.3 | 2026-05-22

Spring Boot + Vue 3 实时聊天应用，核心为 **多 AI 机器人共存系统**：从聊天记录蒸馏语言风格，一键生成风格迥异的 Bot，20+ Bot 同时在线。

## 技术栈

| 层 | 技术 |
|---|---|
| 后端 | Spring Boot 3.2, MyBatis-Plus, STOMP WebSocket, Redis |
| 前端 | Vue 3 + Element Plus + Pinia + SockJS/STOMP.js |
| 数据库 | MySQL 8.0 |
| LLM | DeepSeek / Kimi / Qwen / Mimo / GPT / GLM 多厂商切换 |

## 快速启动

```bash
# 后端
cd chatroom-server && mvn spring-boot:run

# 前端
cd chatroom-client && npm install && npm run dev
```

配置 API Key: 环境变量 `BOT_API_KEY` 或前端设置页配置每 Bot 独立 Key。

---

## Bot 系统架构

### Bot 生命周期

```
注册 → 配置Skill → 绑定厂商/Key → 上线运行 ↔ 熔断保护
  │                    │
  ├── 聊天记录导入(QQ/微信/JSON) → 特征提取 → 自动生成Bot
  ├── GitHub Skill导入 / MD文件导入 → 补充设定
  └── 基准测试 → 延迟/并发评估
```

### 消息处理流程

```
用户消息 → STOMP /app/chat.send → 持久化 + WS推送
  │
  └── 目标是Bot? → botTaskExecutor 异步
       ├── 熔断检查 (5次失败→15s静默→半开探测)
       ├── 信号量获取 (每Bot 1并发)
       ├── buildContext() 构建LLM上下文
       ├── LLM API 调用 (流式 SSE over WebSocket)
       └── WS推送回复 → 异步记忆缓存
```

### LLM 上下文构建 (buildContext)

```
1. System Prompt (5层组合):
     ├── Skill文件夹 (SKILL.md + examples/ + references/ + custom/)
     ├── 情感设定指令 (emotion_profile → 自然语言)
     ├── 语言风格指令 (language_style → 自然语言)
     └── 对话模式指令 (casual/roleplay/assistant)
     └── compressPrompt() (>8000字符时保留头5000+尾3000)

2. Working Memory (Level 0) — 最近5轮完整对话，Redis缓存TTL 30min

3. Long-Term Memory (Level 2) — 跨会话用户画像，重要性Top 5注入

4. Few-Shot Examples — 交替user/assistant消息

5. Short-Term Memory (Level 1) — 最近30条消息，Redis TTL 60min

6. RAG Retrieval (Level 3) — 向量相似度/关键词检索，去重后注入

7. Current Message — 用户当前输入
```

### 对话模式

| 模式 | 触发词 | 效果 |
|------|--------|------|
| casual | 轻松闲聊 | 简短自然 ≤100字 |
| roleplay | 沉浸扮演 | *动作描写*, 第一人称 |
| assistant | 专业助手 | 准确有用, 语气正式 |

### 熔断器

```
CLOSED → (5次连续失败) → OPEN(静默15s) → HALF-OPEN → (探测成功) → CLOSED
```

状态持久化: Redis / DB error_count 降级

### 主动聊天模式

Bot 定时向随机好友发起话题，间隔 15s~600s 可配，熔断 Bot 自动跳过。支持群聊自动回复。

---

## 多级记忆系统 (L0-L3)

模拟人类记忆模型：工作记忆 → 短期记忆 → 长期记忆 → 语义记忆。

| 层级 | 名称 | 存储 | 容量 | TTL | 说明 |
|------|------|------|------|-----|------|
| L0 | 工作记忆 | Redis String | 5轮 | 30min | 最近完整对话，始终注入 |
| L1 | 短期记忆 | Redis List | 30条 | 60min | 近期消息历史，DB回退 |
| L2 | 长期记忆 | MySQL + Redis | 50条/对 | 120min缓存 | LLM提取事实/偏好/摘要 |
| L3 | 语义记忆 | MySQL | 500条/对 | 持久化 | 向量相似度检索，关键词兜底 |

### 记忆整合流程

```
短期记忆 > 20条
     ↓
maybeConsolidateMemory()
     ↓
memoryCache.trimMemory() — 裁剪最旧50%
     ↓
LLM 分析对话 → 提取 JSON:
  {facts: [...], preferences: [...], summary: "..."}
     ↓
逐条 storeMemory() → DB + Redis 缓存
     ↓
超过 50 条时淘汰最旧记录
```

### L2 记忆类型

| 类型 | 说明 | 示例 |
|------|------|------|
| `fact` | 用户事实信息 | "用户名叫张三，在北京工作，职业是程序员" |
| `preference` | 用户偏好习惯 | "用户喜欢简洁直接的回复，不喜欢客套话" |
| `summary` | 对话主题摘要 | "上次聊了关于AI发展趋势的话题" |

### 降级策略

Redis 不可用时：工作记忆跳过；短期记忆回退 MySQL `messages` 表；LTM 直接查 DB。Embedding API 失败时 RAG 回退关键词 Jaccard 检索。

> 详见 [docs/memory-system-design.md](docs/memory-system-design.md)

---

## Skill 体系

### 数据分区 (11维)

| 分区 | 说明 |
|------|------|
| system_prompt | 角色扮演核心提示词 |
| emotion_profile | 六维情绪分布(joy/care/sad/surprise/anger/fear) |
| language_style | 句长/表情率/语气词率/问句比例/习惯用语 |
| tone_signature | 口癖、标点偏好、固定短语 |
| rhythm_profile | 句长分布、断句节奏、追问密度 |
| discourse_tactics | 对话策略、话题转移风格 |
| topic_preferences | 话题偏好/回避、知识边界 |
| safety_boundaries | 拒绝场景话术、隐私底线 |
| repair_strategy | 误解修正、语气回拉 |
| example_guidelines | few-shot采样原则 |
| few_shot_examples | 4-8组对话示例 |

### 特征提取 (聊天记录导入)

**情绪关键词匹配** (六维正则): 每维度8-15个中文关键词，归一化得分布比

**语言风格统计**: 句长中位数/范围、emoji率、语气词频率、问句比例、高频开头/结尾词

### Skill 存储

双存储模型: `bot_skills` 表 (运行时) + `data/skills/{name}/` 文件夹 (源文件)

MD 导入默认**合并模式**，追加 system_prompt、合并情感/风格参数、追加 few-shot。设置 `merge_mode: overwrite` 恢复覆盖。

---

## 数据库

### bot_skills 表

| 字段 | 类型 | 说明 |
|------|------|------|
| bot_user_id | BIGINT FK | 关联 users.id |
| skill_name | VARCHAR | 技能名称 |
| skill_folder | VARCHAR | 文件夹路径 |
| system_prompt | TEXT | 核心系统提示词 |
| emotion_profile_json | TEXT | 六维情绪JSON |
| language_style_json | TEXT | 语言风格JSON |
| few_shot_examples | TEXT | Few-shot JSON |
| api_endpoint / api_key / model | VARCHAR | LLM配置 |
| max_tokens / temperature | INT/DOUBLE | LLM参数 |
| conversation_mode | VARCHAR | casual/roleplay/assistant |
| memory_size | INT | 滑动窗口记忆条数(默认10) |
| rag_enabled / rag_top_k | INT | RAG开关/检索条数 |
| status | INT | 1=活跃 0=停用 2=熔断 |
| error_count | INT | 连续错误计数 |

### bot_long_term_memory 表

| 字段 | 类型 | 说明 |
|------|------|------|
| bot_user_id | BIGINT FK | Bot ID |
| user_id | BIGINT FK | 对话用户ID |
| memory_type | VARCHAR(20) | summary / fact / preference |
| content | TEXT | 记忆内容 |
| importance | INT | 重要性 1-5 |
| source_message_ids | TEXT | 来源消息ID |

### conversation_embeddings 表

RAG 向量存储: bot_user_id, user_id, message_id, content, embedding_json (向量JSON)

---

## API

### Bot 管理

| 端点 | 说明 |
|------|------|
| GET /api/bots/config | 默认LLM配置 |
| GET /api/bots/ | Bot列表 |
| GET /api/bots/active | 活跃Bot列表 |
| GET /api/bots/count | Bot数量统计 |
| POST /api/bots/register | 注册Bot |
| DELETE /api/bots/{id} | 永久删除 |
| GET /api/bots/list-simple | 简要列表(选择器用) |
| POST /api/bots/{id}/avatar | 上传Bot头像 |
| POST /api/bots/import | 聊天记录导入生成Bot |
| POST /api/bots/distill | 聊天记录蒸馏分析 |

### Skill 导入

| 端点 | 说明 |
|------|------|
| POST /api/bots/skills/import | MD文件导入Skill |
| POST /api/bots/skills/import-url | GitHub URL导入 |
| PUT /api/bots/{id}/skill | MD更新已有Bot(合并模式) |
| PUT /api/bots/{id}/skill/text | 纯文本更新Skill |
| GET /api/bots/skills/{id}/files | Skill文件夹文件列表 |
| POST /api/bots/skills/{id}/custom | 上传自定义规则文件 |

### 厂商 & 模式

| 端点 | 说明 |
|------|------|
| GET /api/bots/providers | AI厂商预设列表(含模型选项) |
| GET /api/bots/{id}/provider-config | Bot当前厂商配置 |
| PUT /api/bots/{id}/provider-config | 更新厂商/Key/模型 |
| PUT /api/bots/{id}/active-mode | 主动聊天开关/间隔 |
| GET /api/bots/{id}/active-mode | 查询主动聊天状态 |
| GET /api/bots/active-mode/list | 主动聊天Bot列表 |
| PUT /api/bots/{id}/rag-config | RAG开关/检索条数 |
| GET /api/bots/{id}/rag-stats | RAG存储统计 |
| DELETE /api/bots/{id}/rag-memory | 清除RAG记忆 |

### 长期记忆

| 端点 | 说明 |
|------|------|
| GET /api/bots/{id}/long-term-memory | 查询LTM条目列表 |
| DELETE /api/bots/{id}/long-term-memory | 清除当前用户LTM |
| POST /api/bots/{id}/consolidate | 手动触发记忆整合 |

### 监控

| 端点 | 说明 |
|------|------|
| GET /api/bots/queue-stats | 消息队列统计 |
| GET /api/bots/health | 系统健康检查 |
| GET /api/bots/{id}/benchmark/quick | 快速延迟检查 |
| POST /api/bots/{id}/benchmark | 全量压测(p50/p90/p99) |

### 文件

| 端点 | 说明 |
|------|------|
| POST /api/files/upload | 上传文件(图片/文件) |
| GET /api/files/{filename} | 下载/查看文件 |

### 好友

| 端点 | 说明 |
|------|------|
| GET /api/friends | 好友列表 |
| POST /api/friends/add | 添加好友 |
| PUT /api/friends/{id}/accept | 接受好友请求 |
| PUT /api/friends/{id}/reject | 拒绝好友请求 |
| DELETE /api/friends/{id} | 删除好友 |
| PUT /api/friends/{id}/remark | 设置好友/Bot备注 |
| GET /api/friends/requests | 好友请求列表 |

---

## AI 厂商预设

| ID | 名称 | 默认模型 | 可用模型 |
|----|------|---------|---------|
| deepseek | DeepSeek | deepseek-chat | deepseek-chat, deepseek-reasoner |
| kimi | Kimi (月之暗面) | moonshot-v1-8k | moonshot-v1-8k, moonshot-v1-32k, moonshot-v1-128k |
| qwen | 通义千问 | qwen-plus | qwen-turbo, qwen-plus, qwen-max, qwen-max-longcontext |
| mimo | Mimo (小米) | mimo-chat | mimo-chat, mimo-pro |
| gpt | OpenAI GPT | gpt-4o-mini | gpt-4o, gpt-4o-mini, gpt-4-turbo, gpt-3.5-turbo |
| zhipu | 智谱GLM | glm-4-flash | glm-4-plus, glm-4-flash, glm-4-air |
| custom | 自定义 | 手动输入 | 手动输入 |

---

## 常量配置

```java
// 熔断
BOT_CIRCUIT_BREAK_THRESHOLD = 5
BOT_CIRCUIT_BREAK_SILENCE_MS = 15_000

// LLM
BOT_DEFAULT_MAX_TOKENS = 4096
BOT_DEFAULT_TEMPERATURE = 0.8
BOT_DEFAULT_MEMORY_SIZE = 10
BOT_MAX_MEMORY_SIZE = 50

// 消息
HISTORY_RETENTION_DAYS = 30
RECALL_WINDOW_MS = 120_000

// 多级记忆
BOT_WORKING_MEMORY_SIZE = 5           // 工作记忆最大轮数
BOT_SHORT_TERM_MAX = 30               // 短期记忆最大条目
BOT_LTM_CONSOLIDATION_THRESHOLD = 20  // 触发记忆整合阈值
BOT_LTM_MAX_PER_PAIR = 50             // 每对长期记忆上限
BOT_LTM_CONTEXT_LIMIT = 5             // 每次注入上下文上限

// 蒸馏
DISTILL_MIN_MESSAGES = 100
DISTILL_CONTEXT_WINDOW = 4
DISTILL_MAX_CHARS = 200
```

---

## 性能优化 (v3.3)

| 优化 | 机制 | 效果 |
|------|------|------|
| **多级记忆** | L0-L3 四层记忆 + LLM自动整合 | 跨会话上下文感知 |
| **Skill 缓存** | `ConcurrentHashMap` TTL 60s, key=botUserId | Skill读取 10ms → 0ms |
| **LLM 响应缓存** | hash(botUserId+content) TTL 180s | 相同问题 2s → 2ms |
| **Prompt 压缩** | 长系统提示词截取前5000+后3000字符 | 输入token减半 |
| **HTTP 连接池** | Apache HttpClient 5, 300总/300每路由 | TLS复用, 省200-400ms |
| **流式响应** | SSE over WebSocket | 首token 500-800ms |
| **消息队列** | Redis List, 容量200, 轮询3s | 削峰填谷 |

---

## 性能基准

> 详见 [docs/test-results.md](docs/test-results.md) 完整压力测试报告 | 测试设计见 [docs/test-design.md](docs/test-design.md)

| 场景 | 指标 | 实测值 |
|------|------|--------|
| REST API | QPS | **324** |
| REST API | p95 延迟 | **53ms** |
| WebSocket | 最大并发连接 | **1000/1000** |
| 消息吞吐 | msg/s | **951** |
| 稳定并发(0%错误) | Bot回复率 | **290并发, 100%** |
| DeepSeek 裸测 | 并发上限 | **800+** (零限流) |
| Bot 回复率 | 20bot×3轮 | **100%** (0%缓存命中) |

> 测试环境: Intel i7-13700H, 32GB DDR5, MySQL 9.3, Docker Redis 7, Java 21, DeepSeek API

---

## Redis 消息队列

可用时提供: 消息队列持久化(跨重启)、熔断状态持久化、多级记忆缓存

不可用时: `@ConditionalOnBean` 自动跳过, 退回内存处理

启用: 确保 Redis 运行于 `localhost:6379`

---

## 相关文档

| 文档 | 说明 |
|------|------|
| [docs/memory-system-design.md](docs/memory-system-design.md) | 多级记忆系统设计 |
| [docs/test-design.md](docs/test-design.md) | 测试用例设计 |
| [docs/test-results.md](docs/test-results.md) | 压力测试报告 |

---

## Skill Markdown 模板

```yaml
---
skill_id: skill_001
name: 角色名
version: 1.0.0
model: deepseek-chat
api_endpoint: https://api.deepseek.com/v1/chat/completions
conversation_mode: casual
max_tokens: 4096
temperature: 0.8
memory_size: 10
rag_enabled: false
rag_top_k: 3
merge_mode: merge
---

## system_prompt
你是... (角色设定)

## emotion_profile
base_tone: 直率
joy: 0.25 | care: 0.2 | sad: 0.1 | surprise: 0.05 | anger: 0.3 | fear: 0.1

## few_shot_examples
- user: 今天好累
  assistant: 累了就歇会，硬撑没用。被啥事拖住了？
```
