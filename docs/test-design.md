# Chatroom 测试用例设计文档

> v1.2 | 2026-05-20 | 实测更新：1000 WS + 800 DeepSeek + 290 稳定并发 + v3 题库

## 1. 设计理念

测试套件采用 **4 层压力测试金字塔** 架构，从底层协议诊断到全系统极限压测层层递进：

```
Layer 4 (容量极限)  →  rag8-max-accounts-test.py / max-qps-test.py / deepseek-concurrency-test.py
Layer 3 (系统集成)  →  chatroom-stress-test.py / load-test.py
Layer 2 (模块专项)  →  bot-stress-test.py
Layer 1 (外部依赖)  →  llm-benchmark.py
Layer 0 (调试卷)    →  debug-bot-reply.py / test-bots-ws.py
```

**核心原则**：

- **分层独立**：每一层不依赖其他层的结果，可单独执行
- **逐级递增 + 自动停止**：关键测试采用梯度模式，错误率超阈值自动停止
- **指标统一**：所有测试输出 avg/p50/p95/p99/min/max 延迟 + 成功率，可横向对比
- **多档模式**：大部分脚本支持 `--quick` / 默认 / `--max` 三档，适配不同场景
- **防缓存命中**：v3 起所有测试采用 200+ 条不重复题库，避免响应缓存干扰测试结果

---

## 2. 防缓存设计 (v3 新增)

### 背景问题

旧版测试使用 10-15 条固定消息循环发送。Bot 管理系统内置 **响应缓存**（`normalizeContent` + LRU，TTL 3 分钟），相同问题直接返回缓存结果。这导致：

- 压力测试数据失真（缓存命中延迟 ~1ms vs LLM 调用延迟 ~2-5s）
- 无法测量真实的 LLM 并发处理能力
- `chatroom-stress-test.py` 3 轮 × 20 bot = 60 次发送，仅 15 条 unique 消息 → ~75% 缓存命中率

### 解决方案

| 脚本 | 旧题库 | 新题库 | 防缓存策略 |
|------|--------|--------|-----------|
| `chatroom-stress-test.py` | 15 条通用 + 10 条 opener | **200+ 通用 + 20×15 个性匹配 + 30 opener** | 按 bot 个性匹配专属题库，`MessageSelector` 跟踪全局已用消息，3 轮 60 条消息 **0 重复** |
| `load-test.py` L4 | 10 条循环 | **50 条** | 50 条不重复，`msgs_per_bot=10` 时仍有余量 |
| `bot-stress-test.py` B3 | `f"测试消息{i+1}: 你好吗？"` | **50 条多样化题库** | `STRESS_MSG_POOL` 随机选取，burst=50 时恰好每条一次 |

### 题库结构

```
GENERIC_POOL (40 条)     → 日常闲聊兜底
TOPIC_POOLS (200 条)     → 10 个主题分类: hobby/food/tech/emotion/drama/game/sports/travel/work/creative
PERSONALITY_POOLS        → 20 种 bot 个性 × 15 条专属问题，匹配 bot 人设
ACTIVE_OPENERS (30 条)   → bot 主动发起对话的独立开场白
```

- **个性匹配**：阳光派收到快乐相关、温柔派收到倾诉相关、毒舌派收到怼人相关 → 更容易触发 LLM 生成契合回复
- **轮次轮换**：每轮按 `(round + bot_index) % topic_count` 切换主题类别，确保同 bot 不同轮次聊不同话题
- **全局去重**：`MessageSelector.used_messages` 跟踪 session 内所有已用消息，绝不重复发送

---

## 3. Layer 0 — 调试 / 诊断工具

用于验证协议正确性和基本连通性，不测试性能。

### 3.1 debug-bot-reply.py

| 项目 | 说明 |
|------|------|
| 目的 | 单次消息完整帧捕获，排查 STOMP 协议问题 |
| 方法 | 注册用户 → 注册 Bot → 加好友 → WS 连接 → 发送 1 条消息 → 逐帧打印所有回复 |
| 输出 | 每帧的 COMMAND、type、senderId、clientMessageId、content |
| 超时 | 30s |
| 适用场景 | Bot 回复异常时定位是 STOMP 帧格式问题还是业务逻辑问题 |

### 3.2 test-bots-ws.py / test-bots.sh / test-bots.bat

| 项目 | 说明 |
|------|------|
| 目的 | Bot WebSocket 基础连通性验证 |
| 方法 | 注册→登录→WS连接→消息收发全链路 |
| 适用场景 | 环境搭建后的快速冒烟验证 |

---

## 4. Layer 1 — 外部依赖基准测试

排除自身系统干扰，先测外部 API 的裸性能上限。

### 4.1 deepseek-concurrency-test.py

| 项目 | 说明 |
|------|------|
| 目的 | 裸测 DeepSeek API 最大并发能力，找速率限制阈值 |
| 方法 | 直接调用 `api.deepseek.com/v1/chat/completions`，不经过 Chatroom 服务 |
| 提示词 | `"Say 'OK' and nothing else."` — 最小化 token 消耗 |
| 梯度 | `--start 10 --step 10 --max 500`（默认），`--quick` 跳级测试 |
| 停止条件 | 成功率 < 100% 或出现 429 |
| 核心指标 | 成功率、延迟分布、吞吐量(req/s)、429 触发并发数 |
| 关键结论 | DeepSeek 付费 API Key 无任何速率限制，550 并发全过 |

### 4.2 llm-benchmark.py

| 项目 | 说明 |
|------|------|
| 目的 | 测量端到端 LLM 回复延迟（串行 vs 并发 vs 新消息） |
| 方法 | 通过 `/user/queue/bot/stream` 订阅 `BOT_STREAM_END` 帧精确计时 |

**子测试**：

| 子测试 | 消息数 | 发送方式 | 测量目标 |
|--------|--------|----------|----------|
| 串行 | 3 条 | 逐条发送，等回复后再发下一条 | 单次 LLM 端到端延迟 |
| 并发 | 4 条 | 突发发送（burst） | Bot 信号量 + 队列并发处理能力 |
| 多样化 | 5 条不同问题 | 逐条发送 | 排除缓存干扰后的真实 LLM 延迟 |

---

## 5. Layer 2 — 模块专项压力测试

### 5.1 bot-stress-test.py (v3 更新)

5 维 bot 模块专项压力测试，带多样化题库防缓存：

| 子测试 | 目的 | 方法 | 消息策略 |
|--------|------|------|----------|
| B1 | 注册并发 | 分批注册 N 个 Bot（每批 10 个并发） | - |
| B2 | 消息并发 | 同时向所有 Bot 发送，等待 LLM 回复 | 每个 Bot 收到不同消息 |
| B3 | 单 Bot 队列 | 对单个 Bot 快速发送 M 条消息，测信号量+队列深度 | `STRESS_MSG_POOL` 50 条轮换 |
| B4 | 熔断器 | 设错误 API Key → 触发熔断 → 等待恢复 → 发送探测 | 随机选用题库消息 |
| B5 | 极限并发梯度 | 逐步增加活跃 Bot 数，直到回复率 < 70% | 每个新 Bot 首次消息唯一 |

运行模式：`--quick`（5 bots）、默认（20 bots）、`--blast`（100 bots）、`--skip-cb`（跳过熔断器）

---

## 6. Layer 3 — 系统集成压力测试

### 6.1 chatroom-stress-test.py (v3 重写)

**20 种人格 Bot 并发聊天压力测试**，核心变更：

| 特性 | v2 (旧) | v3 (新) |
|------|---------|---------|
| 题库大小 | 15 条通用 + 10 条 opener | 200+ 通用 + 20×15 个性 + 30 opener |
| 消息选择 | `random.choice()` 循环 | `MessageSelector` 全局去重 + 个性匹配 |
| 缓存干扰 | ~75% 命中率（15 unique / 60 sent） | ~0%（60 unique / 60 sent） |
| 统计指标 | sent / received / bot_replies | + unique_messages / uniqueness_rate |

**被动模式**：主用户连接 WS → 每轮向每个 Bot 发送 1 条独有个性匹配消息 → 等待回复
**主动模式**：每个 Bot 独立 WS 连接 → 按间隔主动向用户发消息（opener 轮换不重复）

```
Usage:
  python chatroom-stress-test.py --mode both --bots 20 --rounds 3
  python chatroom-stress-test.py --mode passive --rounds 5  # 100 条不重复消息
  python chatroom-stress-test.py --mode active --interval 15 --duration 120
```

### 6.2 load-test.py (v3 更新)

5 维综合负载压测，L4 Bot 并发使用 50 条多样化题库：

| 维度 | 目的 | 配置 |
|------|------|------|
| L1 | REST API 登录 QPS | 预注册用户并发登录 + `/auth/me` |
| L2 | WebSocket 连接上限 | 梯级 [10, 20, 50, 100, 200, 500, 1000] |
| L3 | 消息吞吐 | N 个 WS 连接 × M 条消息/连接 |
| L4 | Bot 并发回复 | 同时向多个 Bot 发独有消息 |
| L5 | 极限并发梯度 | REST API 并发递增直到错误率 > 20% |

```
Usage:
  python load-test.py           # 全量
  python load-test.py --quick   # 快速冒烟
  python load-test.py --max     # 极限查找
```

---

## 7. Layer 4 — 容量极限测试

### 7.1 max-qps-test.py

纯 QPS 查找器：L1 50 并发登录、L2 最大 5000 WS 连接、L3 30×20 消息吞吐、L4 注册+登录梯度压测直到 `--ramp-max 300`。

### 7.2 rag8-max-accounts-test.py

RAG-8 模式下最大并行账户数。每账户创建 20 个启用 RAG 的 Bot，发送消息等待回复。`--auto` 模式从 5 逐步增加到 50 账户（步长 5），失败率 > 10% 时停止。

已知记录：50 账户 × 20 Bot = 1000 RAG-8 Bot，100% 成功率。

---

## 8. 统一指标规范

所有测试统一输出以下指标：

| 指标 | 计算方式 | 说明 |
|------|----------|------|
| total | 总发送数 | - |
| ok/success | 成功数 | - |
| fail/errors | 失败/错误数 | - |
| err% | fail / total × 100 | 错误率 |
| wall | 总耗时 (s) | - |
| QPS | ok / wall | 吞吐量 |
| avg | statistics.mean(latencies) | 平均延迟 |
| p50 | latencies[len//2] | 中位数延迟 |
| p95 | latencies[int(len×0.95)] | 95 分位延迟 |
| p99 | latencies[int(len×0.99)] | 99 分位延迟 |
| min | min(latencies) | 最小延迟 |
| max | max(latencies) | 最大延迟 |
| unique | 唯一消息数 (v3 新增) | 防缓存指标 |
| uniqueness% | unique / sent × 100 | 唯一率，越高缓存干扰越少 |

---

## 9. 运行环境要求

- Python 3.10+
- `pip install aiohttp websockets`
- Chatroom 服务器运行在 `localhost:8080`
- 有效的 DeepSeek API Key（环境变量 `BOT_API_KEY`）
- Redis 可用（可选，降级到 DB 模式）
