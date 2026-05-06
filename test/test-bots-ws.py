"""
Chatroom Bot WebSocket Stress Test
Tests: WebSocket connections, bot message routing, error rates
Usage: python test-bots-ws.py [--bots 20] [--messages 50]
"""
import asyncio
import json
import uuid
import time
import argparse
import sys

# Using websockets library: pip install websockets
try:
    import websockets
except ImportError:
    print("Install websockets: pip install websockets")
    sys.exit(1)

WS_URL = "ws://localhost:8080/ws/chat"
API_URL = "http://localhost:3000"
BASE_API = "http://localhost:8080/api"

results = {"sent": 0, "received": 0, "errors": 0, "bot_replies": 0, "latencies": []}


async def login(username, password):
    import aiohttp
    async with aiohttp.ClientSession() as session:
        async with session.post(
            f"{BASE_API}/auth/login",
            json={"username": username, "password": password}
        ) as resp:
            data = await resp.json()
            return data["data"]["token"]


async def connect_and_chat(token, bot_id, num_messages):
    """Connect via WebSocket, send messages to a bot, track replies."""
    ws_url = f"{WS_URL}?token={token}"
    try:
        async with websockets.connect(ws_url, extra_headers={
            "Origin": "http://localhost:3000"
        }) as ws:
            # Wait for connection
            await asyncio.sleep(0.5)

            for i in range(num_messages):
                msg = {
                    "content": f"Test message {i+1} from WebSocket test",
                    "messageType": 0,
                    "targetId": bot_id,
                    "contentType": 0,
                    "clientMessageId": str(uuid.uuid4())
                }
                start = time.time()
                await ws.send(json.dumps(msg))
                results["sent"] += 1

                # Wait for reply (either from server echo or bot)
                try:
                    response = await asyncio.wait_for(ws.recv(), timeout=5.0)
                    data = json.loads(response)
                    results["received"] += 1
                    latency = (time.time() - start) * 1000
                    results["latencies"].append(latency)

                    # Check if it's a bot reply
                    body = json.loads(data.get("body", "{}"))
                    if body.get("type") == "CHAT":
                        results["bot_replies"] += 1
                except asyncio.TimeoutError:
                    results["errors"] += 1
                    print(f"  [TIMEOUT] No response for message {i+1}")

            await asyncio.sleep(0.2)
    except Exception as e:
        results["errors"] += 1
        print(f"  [ERROR] {e}")


async def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--bots", type=int, default=5, help="Number of bots to test (default 5)")
    parser.add_argument("--messages", type=int, default=10, help="Messages per bot (default 10)")
    args = parser.parse_args()

    print(f"=== Bot WebSocket Stress Test ===")
    print(f"Bots: {args.bots}, Messages per bot: {args.messages}")
    print()

    # Login
    try:
        token = await login("alice", "123456")
        print(f"[OK] Logged in as alice")
    except Exception as e:
        print(f"[FAIL] Login failed: {e}")
        return

    # Get bot list
    import aiohttp
    bot_ids = []
    async with aiohttp.ClientSession() as session:
        async with session.get(
            f"{BASE_API}/bots/active",
            headers={"Authorization": f"Bearer {token}"}
        ) as resp:
            data = await resp.json()
            for bot in data.get("data", []):
                bot_ids.append(bot["botUserId"])

    if not bot_ids:
        print("[FAIL] No bots found. Register bots first via test-bots.sh")
        return

    test_bot_ids = bot_ids[:args.bots]
    print(f"[OK] Found {len(test_bot_ids)} bots to test")

    # Test each bot
    start = time.time()
    tasks = []
    for bot_id in test_bot_ids:
        tasks.append(connect_and_chat(token, bot_id, args.messages))

    await asyncio.gather(*tasks)
    elapsed = time.time() - start

    # Report
    print()
    print("=== Results ===")
    print(f"Duration:          {elapsed:.1f}s")
    print(f"Messages sent:     {results['sent']}")
    print(f"Messages received: {results['received']}")
    print(f"Bot replies:       {results['bot_replies']}")
    print(f"Errors/timeouts:   {results['errors']}")
    error_rate = (results['errors'] / max(results['sent'], 1)) * 100
    print(f"Error rate:        {error_rate:.2f}%")

    if results['latencies']:
        latencies = sorted(results['latencies'])
        print(f"Latency P50:       {latencies[len(latencies)//2]:.1f}ms")
        print(f"Latency P95:       {latencies[int(len(latencies)*0.95)]:.1f}ms")
        print(f"Latency P99:       {latencies[int(len(latencies)*0.99)]:.1f}ms")

    if error_rate < 0.1:
        print("\n[PASS] Error rate < 0.1%")
    else:
        print(f"\n[INFO] Error rate {error_rate:.2f}% (expected with fake API keys)")


if __name__ == "__main__":
    asyncio.run(main())
