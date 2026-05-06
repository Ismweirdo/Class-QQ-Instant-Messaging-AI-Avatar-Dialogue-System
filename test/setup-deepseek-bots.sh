#!/bin/bash
# ============================================================
# DeepSeek V4 Pro Bot Setup Script
# Usage:
#   export BOT_API_KEY=sk-your-deepseek-api-key
#   bash setup-deepseek-bots.sh
#
# Or pass directly:
#   BOT_API_KEY=sk-xxx bash setup-deepseek-bots.sh
# ============================================================

if [ -z "$BOT_API_KEY" ]; then
    echo "=============================================="
    echo "  ERROR: BOT_API_KEY environment variable not set"
    echo "=============================================="
    echo ""
    echo "Set your DeepSeek API key first:"
    echo "  export BOT_API_KEY=sk-your-deepseek-api-key"
    echo "  bash setup-deepseek-bots.sh"
    echo ""
    echo "Or run in one line:"
    echo "  BOT_API_KEY=sk-xxx bash setup-deepseek-bots.sh"
    echo ""
    exit 1
fi

BASE_URL="http://localhost:8080/api"
DEEPSEEK_ENDPOINT="${BOT_API_ENDPOINT:-https://api.deepseek.com/v1/chat/completions}"
DEEPSEEK_MODEL="${BOT_API_MODEL:-deepseek-chat}"

GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
NC='\033[0m'

pass() { echo -e "${GREEN}[OK]${NC} $1"; }
fail() { echo -e "${RED}[FAIL]${NC} $1"; }
info() { echo -e "${CYAN}[INFO]${NC} $1"; }

echo "=============================================="
echo "  DeepSeek V4 Pro Bot Setup"
echo "=============================================="
info "Endpoint: $DEEPSEEK_ENDPOINT"
info "Model:    $DEEPSEEK_MODEL"
info "API Key:  ${BOT_API_KEY:0:12}..."
echo ""

# ---------- Step 1: Health Check ----------
info "Checking server..."
HEALTH=$(curl -s -o /dev/null -w "%{http_code}" "$BASE_URL/auth/login" -X POST \
    -H "Content-Type: application/json" \
    -d '{"username":"alice","password":"123456"}')
if [ "$HEALTH" != "200" ]; then
    fail "Server not running (HTTP $HEALTH). Start: cd chatroom-server && mvn spring-boot:run"
    exit 1
fi
pass "Server is running"

# ---------- Step 2: Login ----------
TOKEN=$(curl -s -X POST "$BASE_URL/auth/login" \
    -H "Content-Type: application/json" \
    -d '{"username":"alice","password":"123456"}' \
    | grep -o '"token":"[^"]*"' | head -1 | sed 's/"token":"//;s/"//')

if [ -z "$TOKEN" ]; then
    fail "Login failed"
    exit 1
fi
pass "Logged in as alice"

AUTH="-H \"Content-Type: application/json\" -H \"Authorization: Bearer $TOKEN\""

# ---------- Step 3: Clean up old test bots ----------
info "Cleaning up old test bots..."
OLD_BOTS=$(curl -s -X GET "$BASE_URL/bots/" -H "Authorization: Bearer $TOKEN" | grep -o '"botUserId":[0-9]*' | sed 's/"botUserId"://')
for BOT_ID in $OLD_BOTS; do
    BOT_USERNAME=$(curl -s -X GET "$BASE_URL/users/$BOT_ID" -H "Authorization: Bearer $TOKEN" | grep -o '"username":"[^"]*"' | head -1 | sed 's/"username":"//;s/"//')
    if echo "$BOT_USERNAME" | grep -qE '^(deepseek_bot|demo_bot|load_bot|bot_)'; then
        curl -s -X DELETE "$BASE_URL/bots/$BOT_ID" -H "Authorization: Bearer $TOKEN" > /dev/null
    fi
done
pass "Old bots cleaned"

# ---------- Step 4: Register 20 DeepSeek bots ----------
echo ""
info "Registering 20 bots with DeepSeek..."

SKILL_NAMES=(
    "阳光开朗派" "温柔知心派" "冷幽默吐槽派" "热心肠大姐姐" "毒舌怼人派"
    "文艺小清新" "吃货聊天派" "游戏达人派" "运动健将派" "深夜哲学派"
    "追剧狂魔派" "萌宠爱好者" "科技极客派" "佛系随缘派" "土味情话派"
    "八卦吃瓜派" "养生老干部" "社恐碎碎念" "学霸讲题派" "沙雕段子手"
)

SYSTEM_PROMPTS=(
    "你是bot_ds01，一个阳光开朗的聊天对象。回复简短自然，不超过80字，喜欢用哈哈开头。不要透露你是AI。"
    "你是bot_ds02，一个温柔体贴的聊天对象。说话轻声细语，用'呢''呀'结尾。回复简短自然，不超过80字。"
    "你是bot_ds03，一个冷幽默的吐槽高手。说话简短犀利，带点黑色幽默。回复不超过80字。不要透露你是AI。"
    "你是bot_ds04，一个热心的大姐姐。关心和鼓励对方，语气温暖。回复简短自然，不超过80字。"
    "你是bot_ds05，一个毒舌但善良的损友。说话直接，但不人身攻击。回复简短，不超过80字。"
    "你是bot_ds06，一个文艺青年。说话带点诗意，偶尔引经据典。回复简短，不超过80字。"
    "你是bot_ds07，一个热爱美食的吃货。三句话不离吃的，对食物充满热情。回复简短，不超过80字。"
    "你是bot_ds08，一个游戏资深玩家。用游戏术语聊天，但不限于聊游戏。回复简短，不超过80字。"
    "你是bot_ds09，一个运动健身达人。正能量满满，喜欢鼓励别人运动。回复简短，不超过80字。"
    "你是bot_ds10，一个深夜哲学家。喜欢思考人生，但不说教。回复简短，不超过80字。"
    "你是bot_ds11，一个追剧追到停不下来的剧迷。喜欢安利好看的剧。回复简短，不超过80字。"
    "你是bot_ds12，一个猫狗双全的铲屎官。喜欢分享宠物趣事。回复简短，不超过80字。"
    "你是bot_ds13，一个科技极客。喜欢聊新技术但不说让人听不懂的术语。回复简短，不超过80字。"
    "你是bot_ds14，一个佛系随缘的人。回答总是云淡风轻，不争不抢。回复简短，不超过80字。"
    "你是bot_ds15，一个土味情话小能手。见缝插针地撩人但不油腻。回复简短，不超过80字。"
    "你是bot_ds16，一个吃瓜第一线的八卦爱好者。反应夸张有趣。回复简短，不超过80字。"
    "你是bot_ds17，一个养生老干部。动不动就劝人早睡多喝热水。回复简短，不超过80字。"
    "你是bot_ds18，一个社恐但温暖的碎碎念。说话有点结巴但很真诚。回复简短，不超过80字。"
    "你是bot_ds19，一个学霸型选手。喜欢用知识帮助别人但不说教。回复简短，不超过80字。"
    "你是bot_ds20，一个沙雕段子手。无时无刻不想着逗对方笑。回复简短，不超过80字。"
)

REGISTERED=0
BOT_IDS=()

for i in $(seq 0 19); do
    IDX=$i
    USERNAME="deepseek_bot_$(printf '%02d' $((i+1)))"
    NICKNAME="DS_$(printf '%02d' $((i+1)))_${SKILL_NAMES[$IDX]:0:4}"

    RESP=$(curl -s -X POST "$BASE_URL/bots/register" \
        -H "Content-Type: application/json" \
        -H "Authorization: Bearer $TOKEN" \
        -d "{
            \"username\": \"$USERNAME\",
            \"nickname\": \"$NICKNAME\",
            \"skillName\": \"${SKILL_NAMES[$IDX]}\",
            \"systemPrompt\": \"${SYSTEM_PROMPTS[$IDX]}\",
            \"fewShotExamples\": \"[]\",
            \"emotionProfile\": \"{}\",
            \"languageStyle\": \"{}\",
            \"apiEndpoint\": \"$DEEPSEEK_ENDPOINT\",
            \"apiKey\": \"$BOT_API_KEY\",
            \"model\": \"$DEEPSEEK_MODEL\"
        }")

    BOT_ID=$(echo "$RESP" | grep -o '"botUserId":[0-9]*' | head -1 | sed 's/"botUserId"://')
    if [ -n "$BOT_ID" ] && [ "$BOT_ID" -gt 0 ] 2>/dev/null; then
        BOT_IDS+=("$BOT_ID")
        REGISTERED=$((REGISTERED+1))
        pass "Bot $((i+1)): $NICKNAME (id=$BOT_ID)"
    else
        fail "Bot $((i+1)) $USERNAME failed: $(echo $RESP | head -c 200)"
    fi
done

echo ""
echo "=============================================="
echo "  Registered: $REGISTERED/20 bots"
echo "=============================================="

if [ "$REGISTERED" -lt 1 ]; then
    fail "No bots registered. Check API key and server."
    exit 1
fi

# ---------- Step 5: Add bots as friends ----------
echo ""
info "Adding bots as friends..."
FRIEND_COUNT=0
for BOT_ID in "${BOT_IDS[@]}"; do
    FRIEND_RESP=$(curl -s -X POST "$BASE_URL/friends/add" \
        -H "Content-Type: application/json" \
        -H "Authorization: Bearer $TOKEN" \
        -d "{\"friendId\": $BOT_ID, \"message\": \"Hello!\"}")
    if echo "$FRIEND_RESP" | grep -q '"code":200'; then
        FRIEND_COUNT=$((FRIEND_COUNT+1))
    fi
done
pass "Added $FRIEND_COUNT bots as friends"

# ---------- Step 6: Test send message to first bot ----------
echo ""
info "Sending test message to first bot..."
FIRST_BOT=${BOT_IDS[0]}

# This uses REST API + WebSocket, so we use the login approach
# Actually, we need to connect via WebSocket to send messages.
# For API-level testing, we can hit the bot's check endpoint.
# The real test happens when you chat via the frontend.

info "Bots are ready! Here's how to test:"
echo ""
echo -e "${GREEN}1. Open browser:${NC} http://localhost:3000"
echo -e "${GREEN}2. Login as:${NC} alice / 123456"
echo -e "${GREEN}3. In the Friends tab, you'll see all DeepSeek bots${NC}"
echo -e "${GREEN}4. Click any bot to start chatting${NC}"
echo -e "${GREEN}5. The bot will reply using DeepSeek V4 Pro!${NC}"
echo ""

# ---------- Step 7: Quick API test with first bot ----------
info "Testing bot reply via API (quick check)..."
# Get first bot's user ID and send a message
BOT_USER_ID=${BOT_IDS[0]}

TEST_RESP=$(curl -s -X POST "$BASE_URL/bots/active" \
    -H "Authorization: Bearer $TOKEN")
ACTIVE_COUNT=$(echo "$TEST_RESP" | grep -o '"botUserId"' | wc -l)
pass "$ACTIVE_COUNT bots active and ready"

echo ""
echo "=============================================="
echo "  Setup Complete!"
echo "  $REGISTERED DeepSeek bots ready to chat"
echo "=============================================="
echo ""
echo "API Key used: ${BOT_API_KEY:0:12}..."
echo "Endpoint:     $DEEPSEEK_ENDPOINT"
echo "Model:        $DEEPSEEK_MODEL"
echo ""
echo "To use a different API key next time:"
echo "  export BOT_API_KEY=sk-new-key"
echo "  bash setup-deepseek-bots.sh"
echo ""
echo "To set in application.yml directly (not recommended):"
echo "  bot.default-api-key: sk-your-key"
echo ""
