"""
Chatroom Bot 模块压力测试 v1
=============================
测试维度:
  B1: Bot 注册并发 — 同时注册 N 个 Bot
  B2: Bot 消息并发 — 同时向 N 个 Bot 发送消息
  B3: 单 Bot 队列压测 — 向单个 Bot 快速发送 M 条消息 (测试信号量+队列)
  B4: Bot 熔断器测试 — 触发错误看熔断行为
  B5: Bot 极限并发 — 逐级提升找到断裂点

Usage:
  python bot-stress-test.py              # 全量测试
  python bot-stress-test.py --quick      # 快速模式 (5 Bot)
  python bot-stress-test.py --blast      # 爆破模式 (100 Bot)
"""
import asyncio, json, uuid, time, argparse, sys, statistics, random
from dataclasses import dataclass, field

try:
    import aiohttp
except ImportError:
    print("pip install aiohttp"); sys.exit(1)
try:
    import websockets
except ImportError:
    print("pip install websockets"); sys.exit(1)

BASE = "http://localhost:8080/api"
WS   = "ws://localhost:8080/ws/chat"
PASS = "test123456"

DEEPSEEK_KEY = "sk-6fe13faf47894dbdae05df6dd87f6cf4"
DEEPSEEK_ENDPOINT = "https://api.deepseek.com/v1/chat/completions"
DEEPSEEK_MODEL = "deepseek-chat"

# ==================== STOMP ====================
def stomp_connect(token):
    return f"CONNECT\naccept-version:1.1,1.2\nhost:localhost\nlogin:{token}\n\n\0"

def stomp_sub(dest, sid="0"):
    return f"SUBSCRIBE\nid:{sid}\ndestination:{dest}\n\n\0"

def stomp_send(dest, body_dict):
    body = json.dumps(body_dict, ensure_ascii=False)
    return f"SEND\ndestination:{dest}\ncontent-type:application/json\ncontent-length:{len(body.encode())}\n\n{body}\0"

# ==================== API helpers ====================
async def api(session, method, path, data=None, token=None, timeout=30):
    headers = {"Content-Type": "application/json"}
    if token: headers["Authorization"] = f"Bearer {token}"
    try:
        async with asyncio.timeout(timeout):
            if method == "POST":
                async with session.post(f"{BASE}{path}", json=data, headers=headers) as r:
                    return await r.json(), r.status
            elif method == "DELETE":
                async with session.delete(f"{BASE}{path}", headers=headers) as r:
                    return await r.json(), r.status
            else:
                async with session.get(f"{BASE}{path}", headers=headers) as r:
                    return await r.json(), r.status
    except Exception as e:
        return {"error": str(e)}, 0

# Diverse message pool for B2/B3 tests (avoids cache hits)
STRESS_MSG_POOL = [
    "今天有什么计划吗？说来听听～",
    "如果你能瞬移，最想去哪个城市？",
    "最近在追什么好剧？安利一下！",
    "突然好想喝奶茶，推荐个口味？",
    "周末过得怎么样？有没有什么趣事？",
    "你对未来五年有什么规划？",
    "什么歌你最近单曲循环了？",
    "有猫和没猫的日子差别有多大？",
    "分享一下你最近学到的新东西！",
    "晚饭吃了什么？有没有踩雷？",
    "你觉得什么是真正的自由？",
    "最近有没有什么事情让你特别开心？",
    "如果给你100万你会怎么花？",
    "你相信命中注定吗？",
    "最想去但没有去的地方是哪里？",
    "有没有一句话改变了你的人生？",
    "熬夜最狠的一次到几点？因为什么？",
    "你觉得内向和外向哪个更有优势？",
    "做过最疯狂的事是什么？分享下。",
    "最近有没有觉得被什么治愈到？",
    "你对自己的颜值打几分？诚实回答。",
    "如果可以换一个职业你最想做什么？",
    "有没有一本书让你看了好几遍？",
    "你和父母最近一次聊天是什么时候？",
    "觉得自己现在的生活满意吗？不满意的地方在哪？",
    "有没有什么童年阴影现在想起来还怕？",
    "旅途中遇到过最崩溃的事是什么？",
    "你觉得AI最终会变成什么样？",
    "说一个你隐藏很久的秘密吧（不重要的那种就行）。",
    "早晨起床第一件事是看手机吗？",
    "最喜欢的动漫角色是谁？为什么？",
    "你最害怕失去什么？",
    "有没有一瞬间想辞职/退学？因为什么？",
    "你觉得钱能买到幸福吗？",
    "如果可以拥有超能力，你要什么？",
    "最近有什么热搜让你觉得很无语？",
    "你最近一次哭是为什么？",
    "你最好的朋友认识多久了？怎么认识的？",
    "你觉得幸福的关键是什么？",
    "如果你消失了，会有人发现吗？开个玩笑。",
    "最让你觉得温暖的一句话是什么？",
    "你有什么奇怪的强迫症吗？",
    "最近有没有人让你刮目相看？",
    "你是怎么缓解焦虑的？",
    "你觉得恋爱中最重要的是什么？",
    "有没有什么小众爱好分享一下？",
    "你最喜欢什么类型的音乐？",
    "咖啡和茶你更喜欢哪个？",
    "如果世界末日只剩24小时，你会怎么过？",
    "今天穿的是什么颜色的衣服？回答不要照镜子。",
]

# ==================== B1: Bot 注册并发 ====================
async def test_bot_registration(count: int, token: str):
    """同时注册 N 个 Bot"""
    print(f"\n{'='*60}")
    print(f"  B1: Bot 注册并发测试 (数量={count})")
    print(f"{'='*60}")

    t0 = time.time()
    ok = 0; fail = 0; lats = []

    async def register_one(idx):
        t1 = time.time()
        d, c = await api(s, "POST", "/bots/register", {
            "username": f"bstress_{uuid.uuid4().hex[:6]}",
            "nickname": f"BSBot{idx:03d}",
            "skillName": f"压测Bot_{idx}",
            "systemPrompt": f"你是压测Bot{idx}。回复简短≤50字。不透露你是AI。",
            "fewShotExamples": "[]",
            "emotionProfile": "{}",
            "languageStyle": "{}",
            "apiEndpoint": DEEPSEEK_ENDPOINT,
            "apiKey": DEEPSEEK_KEY,
            "model": DEEPSEEK_MODEL,
            "password": PASS,
        }, token=token)
        lat = (time.time() - t1) * 1000
        return (c == 200 and d.get("code") == 200), lat, d

    # 分批 10 个并发
    batch_size = 10
    bot_ids = []
    async with aiohttp.ClientSession() as s:
        for offset in range(0, count, batch_size):
            chunk = min(batch_size, count - offset)
            tasks = [register_one(offset + i) for i in range(chunk)]
            results = await asyncio.gather(*tasks)
            for is_ok, lat, d in results:
                lats.append(lat)
                if is_ok:
                    ok += 1
                    bid = d.get("data", {}).get("botUserId")
                    if bid: bot_ids.append(bid)
                else:
                    fail += 1
                    print(f"    FAIL: {d.get('message', d)}")

    wall = time.time() - t0
    lats.sort()
    p50 = lats[len(lats)//2] if lats else 0
    p95 = lats[int(len(lats)*0.95)] if lats else 0
    err = fail / max(count, 1) * 100
    qps = ok / max(wall, 0.001)

    print(f"  OK={ok}/{count} FAIL={fail} ERR={err:.1f}% WALL={wall:.1f}s QPS={qps:.0f}")
    print(f"  Lat(ms): avg={statistics.mean(lats):.0f} p50={p50:.0f} p95={p95:.0f} max={max(lats):.0f}")
    return bot_ids

# ==================== B2: Bot 消息并发 ====================
async def test_bot_messages(token: str, my_user_id: int, bot_ids: list, msgs_per_bot: int = 1):
    """同时向所有 Bot 发送消息"""
    print(f"\n{'='*60}")
    print(f"  B2: Bot 消息并发测试 (Bots={len(bot_ids)}, 每Bot={msgs_per_bot}条)")
    print(f"{'='*60}")

    if not bot_ids:
        print("  [SKIP] No bots")
        return

    reply_count = 0
    reply_lock = asyncio.Lock()
    send_lats = []
    reply_lats = {}
    send_times = {}  # clientMessageId -> send_time

    async def run_session():
        nonlocal reply_count
        ws = None
        sent = 0; ok = 0
        try:
            ws = await websockets.connect(
                f"{WS}?token={token}",
                ping_interval=None, close_timeout=2,
            )
            await ws.send(stomp_connect(token))
            raw = await asyncio.wait_for(ws.recv(), timeout=5)
            if isinstance(raw, bytes): raw = raw.decode()
            if "CONNECTED" not in raw:
                return 0, 0

            # Subscribe to private chat
            await ws.send(stomp_sub("/user/queue/private/chat", "b2"))
            await asyncio.sleep(0.2)

            # Recv loop
            async def recv_loop():
                nonlocal reply_count
                buf = ""
                try:
                    while True:
                        raw = await asyncio.wait_for(ws.recv(), timeout=30)
                        if isinstance(raw, bytes): raw = raw.decode()
                        buf += raw
                        while "\0" in buf:
                            idx = buf.index("\0")
                            frame = buf[:idx+1]; buf = buf[idx+1:]
                            # Parse frame
                            lines = frame.strip("\0").split("\n")
                            if lines and "CHAT" in frame:
                                reply_count += 1
                            # Extract clientMessageId from JSON body
                            try:
                                # Find JSON in frame
                                for line in lines:
                                    if line.startswith("{"):
                                        body = json.loads(line)
                                        cid = body.get("clientMessageId") or body.get("replyTo")
                                        if cid and cid in send_times:
                                            rlat = (time.time() - send_times[cid]) * 1000
                                            async with reply_lock:
                                                reply_lats[cid] = rlat
                                        break
                            except: pass
                except (asyncio.TimeoutError, Exception):
                    pass

            recv_task = asyncio.create_task(recv_loop())

            # Send messages to all bots
            queries = ["嗨！", "在干嘛呢？", "今天天气真好", "你叫什么名字呀",
                       "推荐一首歌吧", "说个笑话", "你最喜欢的食物是什么",
                       "周末愉快！", "好无聊啊", "晚上吃什么"]
            for bid in bot_ids:
                for i in range(msgs_per_bot):
                    cid = f"b2_{uuid.uuid4().hex[:8]}"
                    msg = {"content": queries[i % len(queries)],
                           "messageType": 0, "targetId": bid,
                           "contentType": 0, "clientMessageId": cid}
                    t1 = time.time()
                    await ws.send(stomp_send("/app/chat.send", msg))
                    send_lats.append((time.time() - t1) * 1000)
                    send_times[cid] = time.time()
                    sent += 1
                ok += 1

            # Wait for bot replies
            wait = min(30, len(bot_ids) * 3)
            await asyncio.sleep(wait)
            recv_task.cancel()
            try: await recv_task
            except: pass

        except Exception as e:
            print(f"  WS error: {e}")
        finally:
            if ws:
                try: await ws.close()
                except: pass
        return sent, ok

    t0 = time.time()
    _, bots_ok = await run_session()
    wall = time.time() - t0

    send_lats.sort()
    p50_s = send_lats[len(send_lats)//2] if send_lats else 0
    reply_lat_vals = list(reply_lats.values())
    reply_lat_vals.sort()
    p50_r = reply_lat_vals[len(reply_lat_vals)//2] if reply_lat_vals else 0
    p95_r = reply_lat_vals[int(len(reply_lat_vals)*0.95)] if reply_lat_vals else 0

    total_sent = len(bot_ids) * msgs_per_bot
    print(f"  Sent={total_sent} Replied={reply_count} ReplyRate={reply_count/max(total_sent,1)*100:.0f}%")
    print(f"  WALL={wall:.1f}s  BotSendLat(ms): p50={p50_s:.0f}")
    if reply_lat_vals:
        print(f"  BotReplyLat(ms): avg={statistics.mean(reply_lat_vals):.0f} p50={p50_r:.0f} p95={p95_r:.0f} max={max(reply_lat_vals):.0f}")
    return {"sent": total_sent, "replied": reply_count, "wall": wall}

# ==================== B3: 单 Bot 队列压测 ====================
async def test_single_bot_queue(token: str, my_user_id: int, bot_id: int, burst: int = 20):
    """向单个 Bot 高速发送 M 条消息，测试信号量 + 队列"""
    print(f"\n{'='*60}")
    print(f"  B3: 单 Bot 队列压测 (Bot={bot_id}, 爆发={burst}条)")
    print(f"{'='*60}")

    reply_count = 0
    send_times = {}
    reply_lats = {}
    reply_lock = asyncio.Lock()

    ws = None
    try:
        ws = await websockets.connect(
            f"{WS}?token={token}",
            ping_interval=None, close_timeout=2,
        )
        await ws.send(stomp_connect(token))
        raw = await asyncio.wait_for(ws.recv(), timeout=5)
        if isinstance(raw, bytes): raw = raw.decode()
        if "CONNECTED" not in raw:
            print("  [FAIL] STOMP connect failed")
            return

        await ws.send(stomp_sub("/user/queue/private/chat", "b3"))
        await asyncio.sleep(0.2)

        async def recv_loop():
            nonlocal reply_count
            buf = ""
            try:
                while True:
                    raw = await asyncio.wait_for(ws.recv(), timeout=60)
                    if isinstance(raw, bytes): raw = raw.decode()
                    buf += raw
                    while "\0" in buf:
                        idx = buf.index("\0")
                        frame = buf[:idx+1]; buf = buf[idx+1:]
                        if "CHAT" in frame: reply_count += 1
                        for line in frame.split("\n"):
                            if line.startswith("{"):
                                try:
                                    body = json.loads(line)
                                    cid = body.get("clientMessageId") or body.get("replyTo")
                                    if cid and cid in send_times:
                                        rlat = (time.time() - send_times[cid]) * 1000
                                        async with reply_lock:
                                            reply_lats[cid] = rlat
                                except: pass
            except (asyncio.TimeoutError, Exception):
                pass

        recv_task = asyncio.create_task(recv_loop())

        # Rapid fire!
        print(f"  开始爆发发送 {burst} 条消息...")
        t0_send = time.time()
        send_lats = []
        for i in range(burst):
            cid = f"b3_{i:03d}"
            msg_text = STRESS_MSG_POOL[i % len(STRESS_MSG_POOL)]
            msg = {"content": msg_text,
                   "messageType": 0, "targetId": bot_id,
                   "contentType": 0, "clientMessageId": cid}
            t1 = time.time()
            await ws.send(stomp_send("/app/chat.send", msg))
            send_lats.append((time.time() - t1) * 1000)
            send_times[cid] = time.time()

        send_wall = time.time() - t0_send
        print(f"  发送完成: {burst}条 in {send_wall:.2f}s ({burst/send_wall:.0f} msg/s)")

        # Wait for bot to process queue
        wait_time = max(60, burst * 3)
        print(f"  等待 Bot 处理队列 (最长 {wait_time}s)...")
        await asyncio.sleep(wait_time)
        recv_task.cancel()
        try: await recv_task
        except: pass

    except Exception as e:
        print(f"  Error: {e}")
    finally:
        if ws:
            try: await ws.close()
            except: pass

    reply_lat_vals = list(reply_lats.values())
    reply_lat_vals.sort()
    p50 = reply_lat_vals[len(reply_lat_vals)//2] if reply_lat_vals else 0
    p95 = reply_lat_vals[int(len(reply_lat_vals)*0.95)] if reply_lat_vals else 0

    print(f"  结果: Sent={burst} Replied={reply_count}/{burst} "
          f"QueueDrop={burst - reply_count}")
    if reply_lat_vals:
        print(f"  ReplyLat(ms): avg={statistics.mean(reply_lat_vals):.0f} p50={p50:.0f} "
              f"p95={p95:.0f} max={max(reply_lat_vals):.0f}")
    return {"sent": burst, "replied": reply_count, "dropped": burst - reply_count}

# ==================== B4: 熔断器测试 ====================
async def test_circuit_breaker(token: str, bot_id: int):
    """给 Bot 设置错误的 API Key，触发连续错误看熔断行为"""
    print(f"\n{'='*60}")
    print(f"  B4: 熔断器测试 (Bot={bot_id})")
    print(f"{'='*60}")

    # 1. 设置错误的 API Key
    async with aiohttp.ClientSession() as s:
        d, c = await api(s, "PUT", f"/bots/{bot_id}/config", {
            "apiKey": "sk-invalid-key-fortesting-purposes",
            "model": DEEPSEEK_MODEL,
        }, token=token)
        print(f"  设置无效Key: {c} - {d.get('message', 'OK')}")

    # 2. 快速发送 5 条消息触发熔断
    ws = None
    try:
        ws = await websockets.connect(
            f"{WS}?token={token}",
            ping_interval=None, close_timeout=2,
        )
        await ws.send(stomp_connect(token))
        raw = await asyncio.wait_for(ws.recv(), timeout=5)
        if isinstance(raw, bytes): raw = raw.decode()
        if "CONNECTED" not in raw:
            print("  [FAIL] connect")
            return

        for i in range(5):
            msg = {"content": f"测试{i+1}",
                   "messageType": 0, "targetId": bot_id,
                   "contentType": 0,
                   "clientMessageId": f"cb_{uuid.uuid4().hex[:8]}"}
            await ws.send(stomp_send("/app/chat.send", msg))
            print(f"  -> 发送消息 {i+1}/5")
            await asyncio.sleep(1)

    finally:
        if ws:
            try: await ws.close()
            except: pass

    # 3. 检查 Bot 状态
    await asyncio.sleep(2)
    async with aiohttp.ClientSession() as s:
        d, c = await api(s, "GET", f"/bots/{bot_id}", token=token)
        if c == 200 and d.get("code") == 200:
            bot = d.get("data", {})
            status = bot.get("status", "?")
            error_count = bot.get("errorCount", "?")
            status_map = {1: "ACTIVE", 0: "INACTIVE", 2: "CIRCUIT_BROKEN"}
            print(f"  Bot状态: {status_map.get(status, status)} (errorCount={error_count})")

        # 4. 恢复正确的 API Key
        d, _ = await api(s, "PUT", f"/bots/{bot_id}/config", {
            "apiKey": DEEPSEEK_KEY,
            "model": DEEPSEEK_MODEL,
        }, token=token)
        print(f"  恢复正确Key")

    # 5. 等熔断器半开恢复...
    print(f"  等待 35s 让熔断器进入半开...")
    await asyncio.sleep(35)

    # 6. 发送探测消息
    try:
        ws = await websockets.connect(
            f"{WS}?token={token}",
            ping_interval=None, close_timeout=2,
        )
        await ws.send(stomp_connect(token))
        raw = await asyncio.wait_for(ws.recv(), timeout=5)
        msg_text = random.choice(STRESS_MSG_POOL)
        msg = {"content": msg_text,
               "messageType": 0, "targetId": bot_id,
               "contentType": 0,
               "clientMessageId": f"probe_{uuid.uuid4().hex[:8]}"}
        await ws.send(stomp_send("/app/chat.send", msg))
        print(f"  -> 发送探测消息")

        # Wait for reply
        await ws.send(stomp_sub("/user/queue/private/chat", "b4"))
        try:
            raw = await asyncio.wait_for(ws.recv(), timeout=20)
            print(f"  <- 收到回复: 熔断器已恢复" if "CHAT" in (raw.decode() if isinstance(raw, bytes) else raw) else f"  <- {raw[:100]}")
        except asyncio.TimeoutError:
            print(f"  <- 无回复 - 熔断器可能未恢复")
    finally:
        if ws:
            try: await ws.close()
            except: pass

# ==================== B5: Bot 极限并发梯度 ====================
async def test_bot_ramp(token: str, my_user_id: int, start=5, step=5, max_bots=100):
    """逐级增加活跃 Bot 数量"""
    print(f"\n{'='*60}")
    print(f"  B5: Bot 极限并发梯度 (start={start} step={step} max={max_bots})")
    print(f"{'='*60}")

    # Get all active bot IDs
    async with aiohttp.ClientSession() as s:
        d, _ = await api(s, "GET", "/bots/", token=token)
        all_bots = d.get("data", [])
        active_bot_ids = [b["botUserId"] for b in all_bots if b.get("status") == 1]
        print(f"  可用活跃Bot: {len(active_bot_ids)}")

    results = []
    for level in range(start, min(max_bots, len(active_bot_ids)) + 1, step):
        test_bots = active_bot_ids[:level]
        t0 = time.time()
        reply_count = 0
        send_times = {}
        reply_lats = {}
        reply_lock = asyncio.Lock()

        ws = None
        try:
            ws = await websockets.connect(
                f"{WS}?token={token}",
                ping_interval=None, close_timeout=2,
            )
            await ws.send(stomp_connect(token))
            raw = await asyncio.wait_for(ws.recv(), timeout=5)
            if isinstance(raw, bytes): raw = raw.decode()
            if "CONNECTED" not in raw:
                continue

            await ws.send(stomp_sub("/user/queue/private/chat", "b5"))
            await asyncio.sleep(0.2)

            async def recv_loop():
                nonlocal reply_count
                buf = ""
                try:
                    while True:
                        raw = await asyncio.wait_for(ws.recv(), timeout=90)
                        if isinstance(raw, bytes): raw = raw.decode()
                        buf += raw
                        while "\0" in buf:
                            idx = buf.index("\0")
                            frame = buf[:idx+1]; buf = buf[idx+1:]
                            if "CHAT" in frame: reply_count += 1
                            for line in frame.split("\n"):
                                if line.startswith("{"):
                                    try:
                                        body = json.loads(line)
                                        cid = body.get("clientMessageId") or body.get("replyTo")
                                        if cid and cid in send_times:
                                            rlat = (time.time() - send_times[cid]) * 1000
                                            async with reply_lock:
                                                reply_lats[cid] = rlat
                                    except: pass
                except (asyncio.TimeoutError, Exception):
                    pass

            recv_task = asyncio.create_task(recv_loop())

            # Send to all bots at once
            for bid in test_bots:
                cid = f"r_{uuid.uuid4().hex[:8]}"
                msg = {"content": "嗨！最近怎么样？",
                       "messageType": 0, "targetId": bid,
                       "contentType": 0, "clientMessageId": cid}
                await ws.send(stomp_send("/app/chat.send", msg))
                send_times[cid] = time.time()

            wait = max(30, level * 2)
            await asyncio.sleep(wait)
            recv_task.cancel()
            try: await recv_task
            except: pass

        finally:
            if ws:
                try: await ws.close()
                except: pass

        wall = time.time() - t0
        err = (level - reply_count) / max(level, 1) * 100
        reply_lat_vals = list(reply_lats.values())
        reply_lat_vals.sort()
        p95 = reply_lat_vals[int(len(reply_lat_vals)*0.95)] if reply_lat_vals else 0

        bar = "█" * (reply_count // max(level // 10, 1))
        print(f"  Bots={level:3d} │{bar:<15s}│ Replied={reply_count}/{level} "
              f"ERR={err:.0f}% p95={p95:.0f}ms wall={wall:.0f}s")

        results.append({"level": level, "replied": reply_count, "err": err, "p95": p95})

        if err > 30:
            print(f"  [!] 回复率 < 70%，停止")
            break

    if results:
        good = [r for r in results if r["err"] < 10]
        max_stable = max(good, key=lambda x: x["level"]) if good else results[0]
        print(f"\n  ★ 稳定最大Bot并发: {max_stable['level']} (回复率={100-max_stable['err']:.0f}%)")
    return results

# ==================== MAIN ====================
async def main():
    parser = argparse.ArgumentParser(description="Chatroom Bot 模块压力测试")
    parser.add_argument("--quick", action="store_true", help="快速模式 (5 Bots)")
    parser.add_argument("--blast", action="store_true", help="爆破模式 (100 Bots)")
    parser.add_argument("--skip-cb", action="store_true", help="跳过熔断器测试")
    args = parser.parse_args()

    print("=" * 60)
    print("  Chatroom Bot 模块压力测试 v1")
    print(f"  API: {BASE}")
    print("=" * 60)

    total_t0 = time.time()

    # ── Setup ──
    async with aiohttp.ClientSession() as s:
        # Register test user
        uname = f"bstresstest_{uuid.uuid4().hex[:4]}"
        print(f"\n── 准备测试账号: {uname} ──")
        d, _ = await api(s, "POST", "/auth/register",
            {"username": uname, "password": PASS, "nickname": "BotStress"})
        if d.get("code") != 200:
            # Try login
            d, _ = await api(s, "POST", "/auth/login",
                {"username": uname, "password": PASS})
            if d.get("code") != 200:
                print(f"  [FAIL] {d}")
                return
        token = d["data"]["token"]
        d2, _ = await api(s, "GET", "/auth/me", token=token)
        my_id = d2.get("data", {}).get("id")
        print(f"  账号: {uname}  ID={my_id}")

    # ── B1: Bot 注册 ──
    if args.quick:
        bot_ids = await test_bot_registration(5, token)
    elif args.blast:
        bot_ids = await test_bot_registration(100, token)
    else:
        bot_ids = await test_bot_registration(20, token)

    if not bot_ids:
        print("[FAIL] No bots registered, cannot continue")
        return

    # Give server a moment
    await asyncio.sleep(1)

    # ── B2: Bot 消息并发 ──
    await test_bot_messages(token, my_id, bot_ids, msgs_per_bot=1)

    # ── B3: 单 Bot 队列压测 ──
    burst = 5 if args.quick else 20
    await test_single_bot_queue(token, my_id, bot_ids[0], burst=burst)

    # ── B4: 熔断器测试 ──
    if not args.skip_cb and not args.quick:
        test_bot = bot_ids[-1]  # Use last bot
        await test_circuit_breaker(token, test_bot)

    # ── B5: Bot 极限并发梯度 ──
    if not args.quick:
        # Use all active bots
        await test_bot_ramp(token, my_id, start=10, step=10, max_bots=len(bot_ids))

    # ── Summary ──
    elapsed = time.time() - total_t0
    print(f"\n{'='*60}")
    print(f"  Bot 压力测试完成 (总耗时 {elapsed:.0f}s)")
    print(f"{'='*60}")
    print()

if __name__ == "__main__":
    asyncio.run(main())
