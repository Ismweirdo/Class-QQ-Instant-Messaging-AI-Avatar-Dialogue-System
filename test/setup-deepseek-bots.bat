@echo off
REM ============================================================
REM DeepSeek V4 Pro Bot Setup (Windows)
REM Usage:
REM   set BOT_API_KEY=sk-your-deepseek-api-key
REM   setup-deepseek-bots.bat
REM ============================================================
if "%BOT_API_KEY%"=="" (
    echo ==============================================
    echo   ERROR: BOT_API_KEY environment variable not set
    echo ==============================================
    echo.
    echo Set your DeepSeek API key first:
    echo   set BOT_API_KEY=sk-your-deepseek-api-key
    echo   setup-deepseek-bots.bat
    echo.
    exit /b 1
)

set BASE_URL=http://localhost:8080/api
if "%BOT_API_ENDPOINT%"=="" set BOT_API_ENDPOINT=https://api.deepseek.com/v1/chat/completions
if "%BOT_API_MODEL%"=="" set BOT_API_MODEL=deepseek-chat

echo ==============================================
echo   DeepSeek V4 Pro Bot Setup
echo ==============================================
echo Endpoint: %BOT_API_ENDPOINT%
echo Model:    %BOT_API_MODEL%
echo API Key:  %BOT_API_KEY:~0,12%...
echo.

REM Step 1: Health Check
echo [INFO] Checking server...
curl -s -o nul -w "%%{http_code}" -X POST "%BASE_URL%/auth/login" -H "Content-Type: application/json" -d "{\"username\":\"alice\",\"password\":\"123456\"}" > nul 2>&1
if %errorlevel% neq 0 (
    echo [FAIL] Server not running
    exit /b 1
)
echo [OK] Server is running

REM Step 2: Login
for /f "tokens=*" %%i in ('curl -s -X POST "%BASE_URL%/auth/login" -H "Content-Type: application/json" -d "{\"username\":\"alice\",\"password\":\"123456\"}" ^| findstr /r "token"') do set TOKEN_LINE=%%i

if "%TOKEN_LINE%"=="" (
    echo [FAIL] Login failed
    exit /b 1
)
echo [OK] Logged in as alice

REM Step 3: Register 5 test bots (use setup-deepseek-bots.sh for all 20)
echo.
echo [INFO] Registering 5 DeepSeek test bots...

for /l %%n in (1,1,5) do (
    set NUM=00%%n
    set NUM=!NUM:~-2!
    curl -s -X POST "%BASE_URL%/bots/register" -H "Content-Type: application/json" -H "Authorization: Bearer %TOKEN_LINE%" -d "{\"username\":\"ds_bot_!NUM!\",\"nickname\":\"DS_Bot!NUM!\",\"skillName\":\"Test\",\"systemPrompt\":\"Reply briefly in Chinese, under 80 chars. Do not reveal you are AI.\",\"fewShotExamples\":\"[]\",\"emotionProfile\":\"{}\",\"languageStyle\":\"{}\",\"apiEndpoint\":\"%BOT_API_ENDPOINT%\",\"apiKey\":\"%BOT_API_KEY%\",\"model\":\"%BOT_API_MODEL%\"}" > nul 2>&1
    echo [OK] Registered ds_bot_!NUM!
)

echo.
echo ==============================================
echo   Setup Complete!
echo   5 DeepSeek bots registered
echo ==============================================
echo.
echo Open http://localhost:3000, login as alice / 123456
echo Find the bots in your Friends list and start chatting!
echo.
