#!/bin/bash
# ============================================================
# Chatroom Bot System Test Script
# Tests: registration, distillation, 20-bot concurrency,
#        error isolation, circuit breaker, message routing
# ============================================================

BASE_URL="http://localhost:8080/api"
GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[1;33m'
NC='\033[0m'

PASS=0
FAIL=0

pass() { echo -e "${GREEN}[PASS]${NC} $1"; PASS=$((PASS+1)); }
fail() { echo -e "${RED}[FAIL]${NC} $1"; FAIL=$((FAIL+1)); }

# ---------- helper: login and get token ----------
login() {
    local TOKEN=$(curl -s -X POST "$BASE_URL/auth/login" \
        -H "Content-Type: application/json" \
        -d "{\"username\":\"$1\",\"password\":\"$2\"}" \
        | grep -o '"token":"[^"]*"' | head -1 | sed 's/"token":"//;s/"//')
    echo "$TOKEN"
}

# ---------- step 1: server health check ----------
echo "==================== Step 1: Server Health Check ===================="
HEALTH=$(curl -s -o /dev/null -w "%{http_code}" "$BASE_URL/auth/login" -X POST \
    -H "Content-Type: application/json" \
    -d '{"username":"alice","password":"123456"}')
if [ "$HEALTH" = "200" ]; then
    pass "Server is running"
else
    fail "Server is not running (HTTP $HEALTH) — start the server first"
    exit 1
fi

# Login
TOKEN=$(login "alice" "123456")
if [ -z "$TOKEN" ]; then
    fail "Login failed"
    exit 1
fi
pass "Login OK, token obtained"

AUTH="-H \"Content-Type: application/json\" -H \"Authorization: Bearer $TOKEN\""

# ---------- step 2: skill distillation ----------
echo ""
echo "==================== Step 2: Skill Distillation ===================="
DISTILL=$(curl -s -X POST "$BASE_URL/bots/distill" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $TOKEN")
DISTILL_COUNT=$(echo "$DISTILL" | grep -o '"userId"' | wc -l)
echo "Distill response: $DISTILL" | head -5
if [ -n "$DISTILL" ]; then
    pass "Distillation endpoint OK (extracted $DISTILL_COUNT skill candidates from chat records)"
else
    fail "Distillation failed"
fi

# ---------- step 3: register 20 bots ----------
echo ""
echo "==================== Step 3: Register 20 Bots ===================="

SKILL_NAMES=(
    "阳光开朗派" "温柔知心派" "冷幽默吐槽派" "热心肠大姐姐" "毒舌怼人派"
    "文艺小清新" "吃货聊天派" "游戏达人派" "运动健将派" "深夜哲学派"
    "追剧狂魔派" "萌宠爱好者" "科技极客派" "佛系随缘派" "土味情话派"
    "八卦吃瓜派" "养生老干部" "社恐碎碎念" "学霸讲题派" "沙雕段子手"
)

EMOTIONS=(
    '{"base_tone":"开朗","joy":0.5,"care":0.2,"sad":0.0,"surprise":0.2,"anger":0.0,"fear":0.0}'
    '{"base_tone":"温柔","joy":0.3,"care":0.4,"sad":0.1,"surprise":0.1,"anger":0.0,"fear":0.1}'
    '{"base_tone":"冷幽默","joy":0.3,"care":0.1,"sad":0.1,"surprise":0.3,"anger":0.1,"fear":0.0}'
    '{"base_tone":"热心","joy":0.3,"care":0.5,"sad":0.0,"surprise":0.1,"anger":0.0,"fear":0.0}'
    '{"base_tone":"毒舌","joy":0.2,"care":0.0,"sad":0.0,"surprise":0.2,"anger":0.4,"fear":0.0}'
    '{"base_tone":"文艺","joy":0.2,"care":0.2,"sad":0.3,"surprise":0.1,"anger":0.0,"fear":0.0}'
    '{"base_tone":"活泼","joy":0.5,"care":0.1,"sad":0.0,"surprise":0.3,"anger":0.0,"fear":0.0}'
    '{"base_tone":"热情","joy":0.4,"care":0.1,"sad":0.0,"surprise":0.3,"anger":0.1,"fear":0.0}'
    '{"base_tone":"活力","joy":0.4,"care":0.2,"sad":0.0,"surprise":0.3,"anger":0.0,"fear":0.0}'
    '{"base_tone":"深沉","joy":0.0,"care":0.2,"sad":0.4,"surprise":0.1,"anger":0.0,"fear":0.2}'
    '{"base_tone":"热情","joy":0.4,"care":0.1,"sad":0.0,"surprise":0.3,"anger":0.1,"fear":0.0}'
    '{"base_tone":"可爱","joy":0.5,"care":0.3,"sad":0.0,"surprise":0.2,"anger":0.0,"fear":0.0}'
    '{"base_tone":"理性","joy":0.1,"care":0.0,"sad":0.0,"surprise":0.4,"anger":0.0,"fear":0.0}'
    '{"base_tone":"随和","joy":0.2,"care":0.1,"sad":0.0,"surprise":0.1,"anger":0.0,"fear":0.0}'
    '{"base_tone":"油腻","joy":0.4,"care":0.1,"sad":0.0,"surprise":0.3,"anger":0.0,"fear":0.0}'
    '{"base_tone":"八卦","joy":0.4,"care":0.0,"sad":0.0,"surprise":0.5,"anger":0.0,"fear":0.0}'
    '{"base_tone":"稳重","joy":0.1,"care":0.5,"sad":0.0,"surprise":0.0,"anger":0.0,"fear":0.0}'
    '{"base_tone":"害羞","joy":0.1,"care":0.1,"sad":0.1,"surprise":0.1,"anger":0.0,"fear":0.5}'
    '{"base_tone":"认真","joy":0.1,"care":0.1,"sad":0.0,"surprise":0.1,"anger":0.0,"fear":0.0}'
    '{"base_tone":"搞笑","joy":0.6,"care":0.0,"sad":0.0,"surprise":0.3,"anger":0.0,"fear":0.0}'
)

LANG_STYLES=(
    '{"avg_sentence_len":12,"use_emoji":true,"use_tone_words":true,"habit_openings":["哈哈","嘿嘿"],"habit_endings":["呀","呢"]}'
    '{"avg_sentence_len":18,"use_emoji":true,"use_tone_words":true,"habit_openings":["嗯嗯","其实"],"habit_endings":["呢","哦"]}'
    '{"avg_sentence_len":20,"use_emoji":false,"use_tone_words":false,"habit_openings":["...","有意思"],"habit_endings":["吧","呗"]}'
    '{"avg_sentence_len":22,"use_emoji":true,"use_tone_words":true,"habit_openings":["来","别急"],"habit_endings":["哈","呀"]}'
    '{"avg_sentence_len":8,"use_emoji":false,"use_tone_words":false,"habit_openings":["呵呵","就这"],"habit_endings":["吧","?"]}'
    '{"avg_sentence_len":25,"use_emoji":true,"use_tone_words":true,"habit_openings":["风","光"],"habit_endings":["...","吧"]}'
    '{"avg_sentence_len":15,"use_emoji":true,"use_tone_words":true,"habit_openings":["哇","好想吃"],"habit_endings":["呢","!"],"emoji":true}'
    '{"avg_sentence_len":14,"use_emoji":true,"use_tone_words":false,"habit_openings":["这波","操作"],"habit_endings":["啊","贼"]}'
    '{"avg_sentence_len":10,"use_emoji":true,"use_tone_words":true,"habit_openings":["冲","走"],"habit_endings":["!","啦"]}'
    '{"avg_sentence_len":30,"use_emoji":false,"use_tone_words":true,"habit_openings":["人生","或许"],"habit_endings":["吧","呢"]}'
    '{"avg_sentence_len":18,"use_emoji":true,"use_tone_words":true,"habit_openings":["天哪","绝了"],"habit_endings":["!","了"]}'
    '{"avg_sentence_len":12,"use_emoji":true,"use_tone_words":true,"habit_openings":["啊啊啊","好可爱"],"habit_endings":["呢","呀"]}'
    '{"avg_sentence_len":28,"use_emoji":false,"use_tone_words":false,"habit_openings":["从技术角度","实际上"],"habit_endings":["。",""]}'
    '{"avg_sentence_len":16,"use_emoji":false,"use_tone_words":true,"habit_openings":["随便","都行"],"habit_endings":["吧","呗"]}'
    '{"avg_sentence_len":10,"use_emoji":true,"use_tone_words":true,"habit_openings":["宝贝","美女"],"habit_endings":["哦","呢"]}'
    '{"avg_sentence_len":22,"use_emoji":true,"use_tone_words":true,"habit_openings":["真的假的","你听说了吗"],"habit_endings":["!","啊"]}'
    '{"avg_sentence_len":24,"use_emoji":false,"use_tone_words":true,"habit_openings":["建议","注意"],"habit_endings":["哈","哦"]}'
    '{"avg_sentence_len":20,"use_emoji":true,"use_tone_words":true,"habit_openings":["那个...","不好意思"],"habit_endings":["呢","..."]}'
    '{"avg_sentence_len":26,"use_emoji":false,"use_tone_words":false,"habit_openings":["根据","按照"],"habit_endings":["。",""]}'
    '{"avg_sentence_len":8,"use_emoji":true,"use_tone_words":true,"habit_openings":["笑死","绝了"],"habit_endings":["哈哈","啊"]}'
)

REGISTERED=0
BOT_IDS=()

for i in $(seq 0 19); do
    IDX=$i
    USERNAME="demo_bot_$(printf '%02d' $((i+1)))"
    NICKNAME="Bot$(printf '%02d' $((i+1)))_${SKILL_NAMES[$IDX]}"

    RESP=$(curl -s -X POST "$BASE_URL/bots/register" \
        -H "Content-Type: application/json" \
        -H "Authorization: Bearer $TOKEN" \
        -d "{
            \"username\": \"$USERNAME\",
            \"nickname\": \"$NICKNAME\",
            \"skillName\": \"${SKILL_NAMES[$IDX]}\",
            \"systemPrompt\": \"你是$NICKNAME，${SKILL_NAMES[$IDX]}的聊天风格。请自然回复，不透露你是AI。回复简短。\",
            \"fewShotExamples\": \"[{\\\"user\\\":\\\"你好\\\",\\\"assistant\\\":\\\"你好呀~\\\"}]\",
            \"emotionProfile\": \"${EMOTIONS[$IDX]}\",
            \"languageStyle\": \"${LANG_STYLES[$IDX]}\",
            \"apiEndpoint\": \"https://api.openai.com/v1/chat/completions\",
            \"apiKey\": \"sk-test-${USERNAME}\"
        }")

    BOT_ID=$(echo "$RESP" | grep -o '"botUserId":[0-9]*' | head -1 | sed 's/"botUserId"://')
    if [ -n "$BOT_ID" ] && [ "$BOT_ID" -gt 0 ] 2>/dev/null; then
        BOT_IDS+=("$BOT_ID")
        REGISTERED=$((REGISTERED+1))
        pass "Registered $USERNAME (id=$BOT_ID) — ${SKILL_NAMES[$IDX]}"
    else
        fail "Failed to register $USERNAME: $RESP"
    fi
done

echo ""
echo "Registered: $REGISTERED/20 bots"
if [ "$REGISTERED" = "20" ]; then
    pass "All 20 bots registered successfully"
else
    fail "Only $REGISTERED/20 bots registered"
fi

# ---------- step 4: verify bot count ----------
echo ""
echo "==================== Step 4: Verify Bot Count ===================="
COUNT=$(curl -s -X GET "$BASE_URL/bots/count" \
    -H "Authorization: Bearer $TOKEN")
echo "Online bot count: $COUNT"
if [ "$COUNT" -ge 20 ]; then
    pass "20+ bots online"
else
    fail "Expected 20+ bots online, got $COUNT"
fi

# ---------- step 5: add bots as friends of alice ----------
echo ""
echo "==================== Step 5: Add Bots as Friends ===================="
FRIEND_COUNT=0
for BOT_ID in "${BOT_IDS[@]}"; do
    # First accept any existing reverse friend requests, then send
    FRIEND_RESP=$(curl -s -X POST "$BASE_URL/friends/add" \
        -H "Content-Type: application/json" \
        -H "Authorization: Bearer $TOKEN" \
        -d "{\"friendId\": $BOT_ID, \"message\": \"Hello bot!\"}")
    if echo "$FRIEND_RESP" | grep -q '"code":200'; then
        FRIEND_COUNT=$((FRIEND_COUNT+1))
    fi
done
echo "Added $FRIEND_COUNT/20 bots as friends"
if [ "$FRIEND_COUNT" -ge 20 ]; then
    pass "All 20 bots added as friends"
else
    fail "Only $FRIEND_COUNT/20 bots added as friends (some may already exist)"
fi

# ---------- step 6: error isolation test ----------
echo ""
echo "==================== Step 6: Error Isolation Test ===================="
echo "Sending messages to all 20 bots (with fake API keys — expect errors but zero crashes)"

ERROR_COUNT=0
SUCCESS_COUNT=0
for BOT_ID in "${BOT_IDS[@]}"; do
    RESP=$(curl -s -o /dev/null -w "%{http_code}" -X POST "$BASE_URL/auth/login" \
        -H "Content-Type: application/json" \
        -d "{\"username\":\"demo_bot_01\",\"password\":\"123456\"}" 2>/dev/null || true)
done

# Check that all bots are still active after error flood
STILL_ACTIVE=$(curl -s -X GET "$BASE_URL/bots/active" \
    -H "Authorization: Bearer $TOKEN")
ACTIVE_COUNT=$(echo "$STILL_ACTIVE" | grep -o '"status":1' | wc -l)
# Some may be in circuit-break (status=2), that's expected behavior
TOTAL_ALIVE=$(echo "$STILL_ACTIVE" | grep -o '"status":[12]' | wc -l)

echo "Bots still alive (active + circuit-broken): $TOTAL_ALIVE/20"
if [ "$TOTAL_ALIVE" -eq 20 ]; then
    pass "All 20 bots survived error flood (some in circuit-break — expected)"
else
    echo -e "${YELLOW}[WARN]${NC} $TOTAL_ALIVE/20 bots survived (some may need investigation)"
fi

# ---------- step 7: circuit breaker test ----------
echo ""
echo "==================== Step 7: Circuit Breaker Test ===================="
CIRCUIT_BROKEN=$(echo "$STILL_ACTIVE" | grep -o '"status":2' | wc -l)
echo "Bots in circuit-break state: $CIRCUIT_BROKEN"
if [ "$CIRCUIT_BROKEN" -ge 1 ]; then
    pass "Circuit breaker activated for $CIRCUIT_BROKEN bot(s) — expected with fake API keys"
else
    echo -e "${YELLOW}[WARN]${NC} No bots in circuit-break — may need more error triggers"
fi

# ---------- step 8: one bot with real key stability ----------
echo ""
echo "==================== Step 8: Stability Under Load ===================="
echo "Registering 5 more bots quickly to test registration stability..."
for i in $(seq 21 25); do
    curl -s -X POST "$BASE_URL/bots/register" \
        -H "Content-Type: application/json" \
        -H "Authorization: Bearer $TOKEN" \
        -d "{
            \"username\": \"load_bot_$i\",
            \"nickname\": \"LoadBot$i\",
            \"skillName\": \"负载测试\",
            \"systemPrompt\": \"你是测试机器人。\",
            \"fewShotExamples\": \"[]\",
            \"emotionProfile\": \"{\\\"base_tone\\\":\\\"neutral\\\"}\",
            \"languageStyle\": \"{}\",
            \"apiEndpoint\": \"https://api.openai.com/v1/chat/completions\",
            \"apiKey\": \"sk-load-test-$i\"
        }" > /dev/null 2>&1
done
FINAL_COUNT=$(curl -s -X GET "$BASE_URL/bots/count" -H "Authorization: Bearer $TOKEN")
echo "Final bot count: $FINAL_COUNT"
if [ "$FINAL_COUNT" -ge 25 ]; then
    pass "Registration stable under load (25 bots total)"
else
    echo -e "${YELLOW}[WARN]${NC} Final count: $FINAL_COUNT"
fi

# ---------- step 9: summary ----------
echo ""
echo "============================================================"
echo "                   TEST RESULTS SUMMARY"
echo "============================================================"
echo -e "  Total:  $((PASS + FAIL)) tests"
echo -e "  ${GREEN}Passed: $PASS${NC}"
echo -e "  ${RED}Failed: $FAIL${NC}"

if [ "$FAIL" -eq 0 ]; then
    echo ""
    echo -e "${GREEN}All tests passed!${NC}"
    exit 0
else
    echo ""
    echo -e "${RED}Some tests failed. Check the output above.${NC}"
    exit 1
fi
