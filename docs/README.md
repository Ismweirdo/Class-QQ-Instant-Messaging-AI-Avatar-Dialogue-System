# Chatroom — AI 聊天室

> v3.2 | 2026-05-19

Spring Boot + Vue 3 实时聊天应用，核心为 **多 AI 机器人共存系统**：从聊天记录蒸馏语言风格，一键生成风格迥异的 Bot，20+ Bot 同时在线。

## 技术栈

| 层 | 技术 |
|---|---|
| 后端 | Spring Boot 3.2, MyBatis-Plus, STOMP WebSocket, Redis(可选) |
| 前端 | Vue 3 + Element Plus + Pinia + SockJS/STOMP.js |
| 数据库 | H2(开发) / MySQL 8.0(生产) |
| LLM | DeepSeek / Kimi / Qwen / Mimo / GPT / GLM 多厂商切换 |

## 快速启动

```bash
# 后端
cd chatroom-server && mvn spring-boot:run

# 前端
cd chatroom-client && npm install && npm run dev
```

配置 API Key: 环境变量 `BOT_API_KEY` 或前端设置页配置每Bot独立Key。

---

## Bot 系统架构

### Bot 生命周期

```
注册 → 配置Skill → 绑定厂商/Key → 上线运行 ↔ 熔断保护
  │                    │
  ├── 聊天记录导入(QQ/微信/JSON) → 特征提取 → 自动生成Bot
  └── GitHub Skill导入 / MD文件导入 → 补充设定
```

### 消息处理流程

```
用户消息 → STOMP /app/chat.send → 持久化 + WS推送
  │
  └── 目标是Bot? → botTaskExecutor 异步
       ├── 熔断检查 (3次失败→30s静默→半开探测)
       ├── 信号量获取 (每Bot 1并发)
       ├── buildContext() 构建LLM上下文
       ├── LLM API 调用
       └── WS推送回复
```

### LLM 上下文构建 (buildContext)

```
System Prompt (5层组合):
  ├── Skill文件夹 (SKILL.md + examples/ + references/ + custom/)
  ├── 情感设定指令 (emotion_profile → 自然语言)
  ├── 语言风格指令 (language_style → 自然语言)
  └── 对话模式指令 (casual/roleplay/assistant)

Few-Shot 示例 (交替user/assistant消息)

滑动窗口记忆 (最近N条, Redis/DB)

RAG 长期记忆 (向量相似度 / 关键词兜底)

当前消息
```

### 对话模式

| 模式 | 触发词 | 效果 |
|------|--------|------|
| casual | 轻松闲聊 | 简短自然 ≤100字 |
| roleplay | 沉浸扮演 | *动作描写*, 第一人称 |
| assistant | 专业助手 | 准确有用, 语气正式 |

### 熔断器

```
CLOSED → (3次连续失败) → OPEN(静默30s) → HALF-OPEN → (探测成功) → CLOSED
```

状态持久化: Redis (可选) / DB error_count 降级

### 主动聊天模式

Bot 定时向随机好友发起话题，间隔 15s~600s 可配，熔断Bot自动跳过。

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

### conversation_embeddings 表

RAG 向量存储: bot_user_id, user_id, message_id, content, embedding_json (向量JSON)

---

## API

### Bot 管理

| 端点 | 说明 |
|------|------|
| GET /api/bots/config | 默认LLM配置 |
| GET /api/bots/ | Bot列表 |
| POST /api/bots/register | 注册Bot |
| DELETE /api/bots/{id} | 永久删除 |
| GET /api/bots/list-simple | 简要列表(选择器用) |
| POST /api/bots/import | 聊天记录导入生成Bot |
| POST /api/bots/skills/import | MD文件导入Skill |
| POST /api/bots/skills/import-url | GitHub URL导入 |
| PUT /api/bots/{id}/skill | MD更新已有Bot(合并模式) |
| GET /api/bots/{id}/skills/{id}/files | Skill文件夹文件列表 |
| POST /api/bots/{id}/skills/{id}/custom | 上传自定义规则文件 |

### 厂商 & 模式

| 端点 | 说明 |
|------|------|
| GET /api/bots/providers | AI厂商预设列表 |
| GET /api/bots/{id}/provider-config | Bot当前厂商配置 |
| PUT /api/bots/{id}/provider-config | 更新厂商/Key/模型 |
| PUT /api/bots/{id}/active-mode | 主动聊天开关/间隔 |
| PUT /api/bots/{id}/rag-config | RAG开关/检索条数 |
| GET /api/bots/{id}/rag-stats | RAG存储统计 |
| DELETE /api/bots/{id}/rag-memory | 清除RAG记忆 |

### 监控

| 端点 | 说明 |
|------|------|
| GET /api/bots/queue-stats | Redis队列统计 |
| GET /api/bots/{id}/benchmark/quick | 快速延迟检查 |
| POST /api/bots/{id}/benchmark | 全量压测(p50/p90/p99) |

### 好友备注

| 端点 | 说明 |
|------|------|
| PUT /api/friends/{id}/remark | 设置好友/Bot备注 |

---

## AI 厂商预设

| ID | 名称 | 默认模型 |
|----|------|---------|
| deepseek | DeepSeek | deepseek-chat |
| kimi | Kimi (月之暗面) | moonshot-v1-8k |
| qwen | 通义千问 | qwen-plus |
| mimo | Mimo (小米) | mimo-chat |
| gpt | OpenAI GPT | gpt-4o-mini |
| zhipu | 智谱GLM | glm-4-flash |
| custom | 自定义 | 手动输入 |

---

## 常量配置

```java
// 熔断
BOT_CIRCUIT_BREAK_THRESHOLD = 3
BOT_CIRCUIT_BREAK_SILENCE_MS = 30_000

// LLM
BOT_DEFAULT_MAX_TOKENS = 200
BOT_DEFAULT_TEMPERATURE = 0.8
BOT_DEFAULT_MEMORY_SIZE = 10
BOT_MAX_MEMORY_SIZE = 50

// 消息
HISTORY_RETENTION_DAYS = 30
RECALL_WINDOW_MS = 120_000
```

---

## 性能优化 (v3.2)

| 优化 | 机制 | 效果 |
|------|------|------|
| **Skill 缓存** | `ConcurrentHashMap` TTL 60s, key=botUserId | Skill读取 10ms → 0ms |
| **LLM 响应缓存** | hash(botUserId+content) TTL 180s | 相同问题 2s → 2ms |
| **Prompt 压缩** | 长系统提示词截取前1500+后1000字符 | 输入token减半 |
| **消息批量写入** | `batchSaveMessages()` | 批量插入就绪 |
| **HTTP 连接池** | Apache HttpClient 5, 50总/20每路由 | TLS复用, 省200-400ms |
| **流式响应** | SSE over WebSocket | 首token 500-800ms |

---

## 性能基准

> 详见 [test-results.md](test-results.md) 完整压力测试报告

| 场景 | 指标 | p50 | p90 | p99 | avg |
|------|------|-----|-----|-----|-----|
| 非LLM | 消息入库 | 2ms | 3ms | 3ms | 2.6ms |
| 非LLM | Skill读取 | 2ms | 3ms | 3ms | 2.4ms |
| LLM串行(3次) | 端到端 | 2910ms | 4701ms | 4701ms | 3335ms |
| LLM并发=2(4次) | 端到端 | 3196ms | 3196ms | 3196ms | 3196ms |
| API | list-simple | - | - | - | ~95ms |
| API | providers | - | - | - | ~80ms |

> LLM延迟受API服务端影响波动较大(2.4s~4.7s)。流式响应(v3.2)首个token 500-800ms。

---

## Redis 消息队列 (可选)

可用时提供: 消息队列持久化(跨重启)、熔断状态持久化、对话记忆缓存

不可用时: `@ConditionalOnBean` 自动跳过, 退回内存处理

启用: 确保 Redis 运行于 `localhost:6379`，移除 `application.yml` 中 `autoconfigure.exclude`

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
max_tokens: 200
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
