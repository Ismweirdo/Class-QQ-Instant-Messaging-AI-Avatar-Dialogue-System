"""
Chatroom 综合负载压测 v2
=====================
压测维度:
  L1: REST API 登录 QPS + 延迟分布
  L2: WebSocket 连接并发上限
  L3: WebSocket 消息吞吐 (msg/s)
  L4: Bot 并发回复 (需要API Key)
  L5: 极限并发 — 逐步提升直到出现失败

Usage:
  python load-test.py                    # 全量压测
  python load-test.py --quick            # 快速压测(小并发)
  python load-test.py --max              # 极限压测(逐级提升)
"""

import asyncio
import json
import uuid
import time
import argparse
import sys
import os
import statistics
from dataclasses import dataclass, field

try:
    import aiohttp
except ImportError:
    print("pip install aiohttp"); sys.exit(1)

try:
    import websockets
except ImportError:
    print("pip install websockets"); sys.exit(1)

BASE_API = "http://localhost:8080/api"
WS_URL = "ws://localhost:8080/ws/chat"
PASS = "test123456"

# ============ Stats ============
@dataclass
class Result:
    name: str = ""
    total: int = 0
    success: int = 0
    failures: int = 0
    latencies_ms: list = field(default_factory=list)
    wall_seconds: float = 0

    def p(self, pct): return _pct(self.latencies_ms, pct)
    @property
    def qps(self): return self.success / max(self.wall_seconds, 0.001)
    @property
    def avg(self): return statistics.mean(self.latencies_ms) if self.latencies_ms else 0
    @property
    def err_rate(self): return self.failures / max(self.total, 1) * 100
    @property
    def min_lat(self): return min(self.latencies_ms) if self.latencies_ms else 0
    @property
    def max_lat(self): return max(self.latencies_ms) if self.latencies_ms else 0

    def report(self):
        if self.total == 0: return ""
        return (f"total={self.total} ok={self.success} fail={self.failures} err={self.err_rate:.1f}% "
                f"wall={self.wall_seconds:.1f}s qps={self.qps:.0f}\n"
                f"  lat(ms) avg={self.avg:.1f} p50={self.p(50):.1f} p95={self.p(95):.1f} "
                f"p99={self.p(99):.1f} min={self.min_lat:.1f} max={self.max_lat:.1f}")

def _pct(data, p):
    if not data: return 0
    s = sorted(data)
    return s[min(int(len(s) * p / 100), len(s) - 1)]

# ============ HTTP helpers ============
async def _api(session, method, path, data=None, token=None, timeout=15):
    headers = {"Content-Type": "application/json"}
    if token: headers["Authorization"] = f"Bearer {token}"
    try:
        async with asyncio.timeout(timeout):
            if method == "POST":
                async with session.post(f"{BASE_API}{path}", json=data, headers=headers) as r:
                    return await r.json(), r.status
            else:
                async with session.get(f"{BASE_API}{path}", headers=headers) as r:
                    return await r.json(), r.status
    except asyncio.TimeoutError:
        return {"error": "timeout"}, 0
    except Exception as e:
        return {"error": str(e)}, 0

# ============ L1: REST Login QPS ============
async def L1_rest_login(concurrency: int) -> Result:
    """并发登录 + 获取用户信息"""
    print(f"\n{'─'*50}")
    print(f"  L1: REST API 登录负载 (并发={concurrency})")
    r = Result(name="登录")

    # Prepare: register users first
    users = []
    async with aiohttp.ClientSession() as s:
        batch = []
        for i in range(concurrency):
            uname = f"perf_{uuid.uuid4().hex[:10]}"
            batch.append((uname, f"Perf_{i}"))
        print(f"  预注册 {len(batch)} 个用户...")

        reg_ok = 0
        for uname, nick in batch:
            d, c = await _api(s, "POST", "/auth/register",
                {"username": uname, "password": PASS, "nickname": nick})
            if c == 200 and d.get("code") == 200:
                actual_uname = d["data"].get("user", {}).get("username", uname)
                users.append({"username": actual_uname, "token": d["data"]["token"]})
                reg_ok += 1
        print(f"  预注册完成: {reg_ok}/{len(batch)}")

    if len(users) < 10:
        print(f"  [SKIP] 用户不足 ({len(users)})")
        return r

    # Concurrent login
    conn = aiohttp.TCPConnector(limit=concurrency, force_close=True)
    t0 = time.time()

    async def login_one(u):
        t1 = time.time()
        d, c = await _api(s, "POST", "/auth/login",
            {"username": u["username"], "password": PASS})
        lat = (time.time() - t1) * 1000
        is_ok = (c == 200 and d.get("code") == 200)
        tok = d.get("data", {}).get("token") if is_ok else None

        # me
        lat2 = 0
        if tok:
            t2 = time.time()
            d2, c2 = await _api(s, "GET", "/auth/me", token=tok)
            lat2 = (time.time() - t2) * 1000
        return is_ok, lat, lat2

    # Login in batches to avoid connection limit
    results = []
    batch_size = 10
    async with aiohttp.ClientSession(connector=conn) as s:
        for offset in range(0, len(users), batch_size):
            chunk = users[offset:offset + batch_size]
            tasks = [login_one(u) for u in chunk]
            batch_results = await asyncio.gather(*tasks)
            results.extend(batch_results)

    r.wall_seconds = time.time() - t0
    r.total = len(results)

    for ok, lat1, lat2 in results:
        if ok:
            r.success += 1
            r.latencies_ms.append(lat1)
        else:
            r.failures += 1

    await conn.close()
    print(f"  {r.report()}")
    return r

# ============ L2: WebSocket 连接上限 ============
async def L2_ws_connections(seq_concurrency: int, token: str) -> Result:
    """逐批增加 WS 连接，找到断裂点"""
    print(f"\n{'─'*50}")
    print(f"  L2: WebSocket 连接上限测试 (逐级={seq_concurrency})")

    r = Result(name="WS连接")

    if not token:
        print(f"  [SKIP] need token")
        return r

    established = []
    t0 = time.time()

    async def connect_one(idx):
        try:
            async with asyncio.timeout(8):
                ws = await websockets.connect(
                    f"{WS_URL}?token={token}",
                    extra_headers={"Origin": "http://localhost:3000"},
                    ping_interval=None, close_timeout=2,
                )
                raw = await asyncio.wait_for(ws.recv(), timeout=5)
                ok = (isinstance(raw, bytes) and b"CONNECTED" in raw) or \
                     (isinstance(raw, str) and "CONNECTED" in raw)
                if ok:
                    return ws
                try: await ws.close()
                except: pass
        except Exception:
            pass
        return None

    # Step up: try 10, 20, 50, 100, 200, 500, 1000
    levels = [10, 20, 50, 100, 200, 500, 1000]
    for level in levels:
        if level > seq_concurrency * 5:
            break

        print(f"  -> 尝试 {level} 并发连接...")
        tasks = [connect_one(i) for i in range(level)]
        sockets = await asyncio.gather(*tasks)
        count = sum(1 for s in sockets if s is not None)
        established.append((level, count))
        print(f"     成功: {count}/{level}")

        # Close all
        for ws in sockets:
            if ws:
                try: await ws.close()
                except: pass

        if count < level * 0.9:
            print(f"  [!] 在 {level} 处出现显著失败，停止提升")
            break

        await asyncio.sleep(1)  # let server breathe

    r.wall_seconds = time.time() - t0
    if established:
        best = max(established, key=lambda x: x[1])
        r.total = best[0]
        r.success = best[1]
        r.failures = best[0] - best[1]
        print(f"\n  峰值: {best[1]}/{best[0]} 连接成功")

    return r

# ============ L3: 消息吞吐 ============
async def L3_msg_throughput(token: str, user_id: int, conn_count: int, msgs_per: int) -> Result:
    """N条WS连接同时发送M条消息"""
    print(f"\n{'─'*50}")
    print(f"  L3: 消息吞吐 (连接={conn_count}, 每连接={msgs_per}条)")

    r = Result(name="消息吞吐")
    if not token:
        print(f"  [SKIP] need token")
        return r

    async def sender(idx):
        sent = 0
        start = time.time()
        lats = []
        ws = None
        try:
            async with asyncio.timeout(20):
                ws = await websockets.connect(
                    f"{WS_URL}?token={token}",
                    extra_headers={"Origin": "http://localhost:3000"},
                    ping_interval=None, close_timeout=2,
                )
                raw = await ws.recv()
                if b"CONNECTED" not in (raw if isinstance(raw, bytes) else raw.encode()):
                    return 0, []

                # Subscribe
                sub = f"SUBSCRIBE\nid:s{idx}\ndestination:/user/queue/private/chat\n\n\0"
                await ws.send(sub)

                # Send messages rapidly
                for i in range(msgs_per):
                    msg = {
                        "content": f"perf_{idx}_{i}",
                        "messageType": 0,
                        "targetId": user_id,
                        "contentType": 0,
                        "clientMessageId": f"p_{uuid.uuid4().hex[:16]}",
                    }
                    body = json.dumps(msg)
                    frame = f"SEND\ndestination:/app/chat.send\ncontent-type:application/json\ncontent-length:{len(body.encode())}\n\n{body}\0"
                    t1 = time.time()
                    await ws.send(frame)
                    lats.append((time.time() - t1) * 1000)
                    sent += 1

                # Drain replies
                try:
                    while True:
                        raw = await asyncio.wait_for(ws.recv(), timeout=1.0)
                except asyncio.TimeoutError:
                    pass

        except Exception:
            pass
        finally:
            if ws:
                try: await ws.close()
                except: pass
        return sent, lats

    t0 = time.time()
    tasks = [sender(i) for i in range(conn_count)]
    results = await asyncio.gather(*tasks)
    r.wall_seconds = time.time() - t0

    for sent, lats in results:
        r.total += msgs_per
        r.success += sent
        r.failures += (msgs_per - sent)
        r.latencies_ms.extend(lats)

    r.total = conn_count * msgs_per
    print(f"  {r.report()}")
    return r

# ============ L4: Bot 并发 ============
async def L4_bot_concurrency(token: str, user_id: int, bot_ids: list, msgs_per_bot: int) -> Result:
    """同时向N个Bot发消息"""
    print(f"\n{'─'*50}")
    print(f"  L4: Bot 并发 ({len(bot_ids)} Bots, 每Bot {msgs_per_bot} 轮)")

    r = Result(name="Bot并发")
    replies = 0

    if not bot_ids:
        print(f"  [SKIP] 无Bot可用")
        return r

    async def bot_session():
        nonlocal replies
        ws = None
        sent = 0
        ok = 0
        lats = []
        try:
            ws = await websockets.connect(
                f"{WS_URL}?token={token}",
                extra_headers={"Origin": "http://localhost:3000"},
                ping_interval=None, close_timeout=2,
            )
            raw = await asyncio.wait_for(ws.recv(), timeout=5)
            if b"CONNECTED" not in (raw if isinstance(raw, bytes) else raw.encode()):
                return 0, 0, []

            await ws.send(f"SUBSCRIBE\nid:bot\nndestination:/user/queue/private/chat\n\n\0")

            # Recv loop (fire and forget)
            async def recv():
                nonlocal replies
                buf = ""
                try:
                    while True:
                        raw = await asyncio.wait_for(ws.recv(), timeout=10)
                        text = raw.decode("utf-8") if isinstance(raw, bytes) else raw
                        if "CHAT" in text:
                            replies += 1
                except Exception:
                    pass

            recv_task = asyncio.create_task(recv())

            # Send to all bots in one burst
            # Diverse message pool — cycling to avoid cache hits
            queries = [
                "嗨！今天有什么新鲜事吗？", "最近在忙什么项目呢？", "天气转凉了注意保暖哦～",
                "突然好想吃火锅，你最喜欢什么汤底？", "给我推荐一首你最近单曲循环的歌吧！",
                "你觉得自己是早起鸟还是夜猫子？", "讲一个你最近遇到的搞笑事情！",
                "周末有什么计划吗？出去玩还是宅？", "你最想去旅行的地方是哪里？",
                "你觉得AI会改变未来的工作方式吗？", "有什么电影让你看完久久不能平静？",
                "你喜欢养宠物吗？猫还是狗？", "最近有没有学到什么新东西？",
                "你对自己现在的生活状态满意吗？", "如果可以穿越到任何时代你想去哪？",
                "今天心情怎么样？用三个词形容一下。", "你有拖延症吗？怎么克服的？",
                "最喜欢哪个季节？为什么？", "有什么话你一直想说但没人听？",
                "如果能变一种动物你想变什么？", "深夜睡不着的时候你在想什么？",
                "最让你感动的电影场景是什么？", "你觉得自己20年后会是什么样？",
                "上一次大笑到肚子疼是什么时候？", "你觉得人生的意义是什么？",
                "说说你印象最深的一次旅行吧。", "你有没有什么奇怪的收集癖好？",
                "你觉得友谊中最重要的是什么？", "你相不相信一见钟情？",
                "最让你崩溃的一次经历是什么？", "如果中了彩票第一件事做什么？",
                "你的手机里有多少张照片？最舍不得删的是哪张？", "你喜欢热闹还是安静？",
                "有没有一样食物让你想起家的味道？", "你觉得现在的自己够勇敢吗？",
                "最近有什么八卦想分享一下？", "你的解压方式是什么？",
                "如果能给十年前自己发短信你写什么？", "你觉得自己最大的成长是什么？",
                "夏天和冬天你更怕哪个？", "如果让你设计一个节日你会设计什么？",
                "你做过最疯狂的一件事是什么？", "有没有一本书改变了你的想法？",
                "你最佩服的人是谁？为什么？", "如果开一家店你想卖什么？",
                "你觉得善良需要有锋芒吗？", "现在最想做但还没做的事是什么？",
                "你被什么话真正地激励过？", "你觉得幸福是目标还是过程？",
                "第一次离开家是什么感觉？", "如果今天是世界末日你会做什么？",
                "最近有没有觉得被什么治愈了？", "你对现在的工作/学习满意吗？",
            ]
            for bid in bot_ids:
                for i in range(msgs_per_bot):
                    msg = {
                        "content": queries[i % len(queries)],
                        "messageType": 0, "targetId": bid,
                        "contentType": 0,
                        "clientMessageId": f"b_{uuid.uuid4().hex[:12]}",
                    }
                    body = json.dumps(msg)
                    frame = f"SEND\ndestination:/app/chat.send\ncontent-type:application/json\ncontent-length:{len(body.encode())}\n\n{body}\0"
                    t1 = time.time()
                    await ws.send(frame)
                    lats.append((time.time() - t1) * 1000)
                    sent += 1
                ok += 1
                await asyncio.sleep(0.5)

            # Wait for bots to reply
            await asyncio.sleep(8)
            recv_task.cancel()
            try: await recv_task
            except: pass

        except Exception:
            pass
        finally:
            if ws:
                try: await ws.close()
                except: pass
        return sent, ok, lats

    t0 = time.time()
    sent, ok, lats = await bot_session()
    r.wall_seconds = time.time() - t0
    r.total = sent
    r.success = sent
    r.latencies_ms = lats

    print(f"  {r.report()}")
    print(f"  Bot 回复: {replies} / {sent}")
    return r

# ============ L5: 极限并发 (逐级) ============
async def L5_stress_ramp(token: str, user_id: int, start=10, step=10, max_level=200):
    """逐级增加并发直到失败率 > 10%"""
    print(f"\n{'─'*50}")
    print(f"  L5: 极限并发梯度测试 (start={start}, step={step}, max={max_level})")

    results = []
    for level in range(start, max_level + 1, step):
        conn = aiohttp.TCPConnector(limit=level * 2, limit_per_host=level * 2, force_close=True)
        t0 = time.time()
        ok = 0
        fail = 0
        lats = []

        async def hit(idx):
            async with aiohttp.ClientSession(connector=conn) as s:
                t1 = time.time()
                d, c = await _api(s, "GET", "/bots/count", token=token)
                lat = (time.time() - t1) * 1000
                return (c == 200 and d.get("code") == 200), lat

        async with aiohttp.ClientSession(connector=conn) as s:
            for batch_start in range(0, level, 50):
                batch_size = min(50, level - batch_start)
                batch = [hit(batch_start + i) for i in range(batch_size)]
                batch_results = await asyncio.gather(*batch)
                for is_ok, lat in batch_results:
                    if is_ok: ok += 1
                    else: fail += 1
                    lats.append(lat)

        wall = time.time() - t0
        err_rate = fail / max(level, 1) * 100
        qps = ok / max(wall, 0.001)
        p95 = _pct(lats, 95) if lats else 0
        print(f"  并发={level:4d}: qps={qps:6.0f} err={err_rate:5.1f}% p95={p95:5.0f}ms (wall={wall:.1f}s)")

        results.append({
            "level": level, "qps": qps, "err_rate": err_rate,
            "p95": p95, "wall": wall
        })

        await conn.close()

        if err_rate > 20:
            print(f"  [!] 错误率 {err_rate:.1f}% > 20%，停止提升")
            break

        await asyncio.sleep(0.5)

    # Print max QPS
    if results:
        best = max(results, key=lambda x: x["qps"])
        stable = [x for x in results if x["err_rate"] < 5]
        max_stable = max(stable, key=lambda x: x["level"]) if stable else results[0]
        print(f"\n  最大QPS: {best['qps']:.0f} (并发={best['level']})")
        print(f"  稳定最大并发: {max_stable['level']} (错误率 {max_stable['err_rate']:.1f}%)")

    return results

# ============ Setup ============
async def setup():
    """Create test user & bots."""
    print("\n=== 环境准备 ===")
    async with aiohttp.ClientSession() as s:
        # Register main test user
        uname = f"perfmain_{uuid.uuid4().hex[:6]}"
        d, c = await _api(s, "POST", "/auth/register",
            {"username": uname, "password": PASS, "nickname": "PerfTester"})
        if c != 200 or d.get("code") != 200:
            print(f"  [FAIL] 注册失败: {d}")
            return None, None, None, []

        token = d["data"]["token"]
        print(f"  测试账号: {uname}")

        # Get user_id
        d, c = await _api(s, "GET", "/auth/me", token=token)
        user_id = d.get("data", {}).get("id") if c == 200 and d.get("code") == 200 else None
        print(f"  userId: {user_id}")

        # Check bot config
        d, c = await _api(s, "GET", "/bots/config", token=token)
        api_ok = d.get("data", {}).get("apiKeyConfigured", False)
        print(f"  Bot API: {'已配置' if api_ok else '未配置'}")

        # Register bots if API available
        bot_ids = []
        if api_ok:
            print(f"  注册测试Bots...")
            for i in range(1, min(21, 21)):
                d, c = await _api(s, "POST", "/bots/register", {
                    "username": f"perfbot_{uuid.uuid4().hex[:6]}",
                    "nickname": f"PerfBot{i:02d}",
                    "skillName": f"压测Bot_{i}",
                    "systemPrompt": f"你是压测Bot{i}。像真人朋友一样聊天，回复简短≤50字，不透露你是AI。",
                    "fewShotExamples": "[]",
                    "emotionProfile": "{}",
                    "languageStyle": "{}",
                    "password": PASS,
                }, token=token)
                if d.get("code") == 200:
                    bid = d["data"].get("botUserId")
                    if bid:
                        bot_ids.append(bid)
                        # Add friend
                        await _api(s, "POST", "/friends/add",
                            {"friendId": bid, "message": "hi"}, token=token)
            print(f"  创建 {len(bot_ids)} 个Bot并添加好友")

        return uname, token, user_id, bot_ids

async def cleanup():
    """Clean up test users/bots from previous runs."""
    print("=== 清理旧测试数据 ===")
    async with aiohttp.ClientSession() as s:
        # Get all bots and delete test ones
        d, c = await _api(s, "GET", "/bots/")
        if c == 200 and d.get("code") == 200:
            bots = d.get("data", [])
            deleted = 0
            for b in bots:
                bid = b.get("botUserId")
                if not bid: continue
                # Check username via /users/{id}
                ud, _ = await _api(s, "GET", f"/users/{bid}")
                uname = ud.get("data", {}).get("username", "")
                if any(uname.startswith(p) for p in ("perf_", "perfbot_", "perfmain_", "stress_", "load_")):
                    await _api(s, "DELETE", f"/bots/{bid}")
                    deleted += 1
            if deleted:
                print(f"  删除 {deleted} 个旧测试Bot")

# ============ MAIN ============
async def main():
    parser = argparse.ArgumentParser(description="Chatroom 负载压测 v2")
    parser.add_argument("--quick", action="store_true", help="快速模式")
    parser.add_argument("--max", action="store_true", help="极限压测模式")
    parser.add_argument("--skip-bot", action="store_true", help="跳过Bot测试")
    args = parser.parse_args()

    print("=" * 60)
    print("  Chatroom 负载压测 v2")
    print(f"  Server: {BASE_API}")
    print("=" * 60)

    total_t0 = time.time()

    # Cleanup old data
    await cleanup()

    # Setup
    uname, token, user_id, bot_ids = await setup()
    if not token:
        print("[FAIL] 无法创建测试账号，服务器是否在运行？")
        return

    # ─── quick mode ───
    if args.quick:
        print("\n>>> 快速模式 <<<")
        await L1_rest_login(10)
        await L2_ws_connections(20, token)
        await L3_msg_throughput(token, user_id, 5, 10)
        if bot_ids and not args.skip_bot:
            await L4_bot_concurrency(token, user_id, bot_ids[:5], 2)
        print(f"\n总耗时: {time.time() - total_t0:.0f}s")
        return

    # ─── max/stress mode ───
    if args.max:
        print("\n>>> 极限压测模式 <<<")
        await L1_rest_login(50)
        await L2_ws_connections(200, token)
        await L3_msg_throughput(token, user_id, 30, 20)
        if bot_ids and not args.skip_bot:
            await L4_bot_concurrency(token, user_id, bot_ids[:20], 3)
        await L5_stress_ramp(token, user_id, start=10, step=10, max_level=200)
        print(f"\n总耗时: {time.time() - total_t0:.0f}s")
        return

    # ─── normal mode (full) ───
    print("\n>>> 全量压测 <<<")

    # L1
    r1 = await L1_rest_login(20)

    # L2
    r2 = await L2_ws_connections(100, token)

    # L3
    r3 = await L3_msg_throughput(token, user_id, 20, 15)

    # L4
    if bot_ids and not args.skip_bot:
        await L4_bot_concurrency(token, user_id, bot_ids[:20], 3)

    # L5 (light)
    await L5_stress_ramp(token, user_id, start=10, step=10, max_level=100)

    # ─── Summary ───
    print(f"\n{'='*60}")
    print(f"  压测总结 (总耗时 {time.time() - total_t0:.0f}s)")
    print(f"{'='*60}")

    print(f"\n  L1 REST API:")
    print(f"    登录QPS: {r1.qps:.0f} | p95={r1.p(95):.0f}ms | 错误率={r1.err_rate:.1f}%")

    ws_max = r2.success if r2.success > 0 else "N/A"
    print(f"\n  L2 WebSocket连接:")
    if isinstance(ws_max, int):
        print(f"    峰值并发连接: {ws_max} | 成功率: {r2.success/max(r2.total,1)*100:.0f}%")
    else:
        print(f"    结果: {ws_max}")

    print(f"\n  L3 消息吞吐:")
    print(f"    消息QPS: {r3.qps:.0f} | 发送p95={r3.p(95):.0f}ms | 错误率={r3.err_rate:.1f}%")

    print()
    print("  ── 容量评估 ──")
    print(f"  REST API 稳健QPS: ~{r1.qps:.0f}")
    if isinstance(ws_max, int):
        print(f"  WS 并发连接上限: ~{ws_max}")
    else:
        print(f"  WS 连接测试: 需进一步测试")
    msg_cap = r3.qps
    if msg_cap > 500: print(f"  消息吞吐: {msg_cap:.0f} msg/s ✓ (满足500+ QPS目标)")
    elif msg_cap > 200: print(f"  消息吞吐: {msg_cap:.0f} msg/s (基本满足)")
    else: print(f"  消息吞吐: {msg_cap:.0f} msg/s (建议优化)")

    print()

if __name__ == "__main__":
    asyncio.run(main())
