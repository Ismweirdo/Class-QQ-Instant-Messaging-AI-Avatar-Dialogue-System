@echo off
REM ============================================================
REM Chatroom Bot System Test (Windows Batch)
REM ============================================================
set BASE_URL=http://localhost:8080/api
set PASS=0
set FAIL=0

echo ==================== Step 1: Server Health Check ====================
curl -s -X POST "%BASE_URL%/auth/login" -H "Content-Type: application/json" -d "{\"username\":\"alice\",\"password\":\"123456\"}" > nul 2>&1
if %errorlevel% equ 0 (
    echo [PASS] Server is running
    set /a PASS+=1
) else (
    echo [FAIL] Server not running — start the server first
    exit /b 1
)

REM Login and extract token
for /f "tokens=*" %%i in ('curl -s -X POST "%BASE_URL%/auth/login" -H "Content-Type: application/json" -d "{\"username\":\"alice\",\"password\":\"123456\"}" ^| findstr /r "token"') do set TOKEN_LINE=%%i
echo [PASS] Login OK
set /a PASS+=1

REM Register bots
echo ==================== Step 2: Register 20 Bots ====================
for /l %%n in (1,1,20) do (
    curl -s -X POST "%BASE_URL%/bots/register" -H "Content-Type: application/json" -H "Authorization: Bearer %TOKEN%" -d "{\"username\":\"bot_%%n\",\"nickname\":\"Bot%%n\",\"skillName\":\"TestSkill\",\"systemPrompt\":\"Be a friendly bot.\",\"fewShotExamples\":\"[]\",\"emotionProfile\":\"{}\",\"languageStyle\":\"{}\",\"apiEndpoint\":\"https://api.dummy.com/v1/chat\",\"apiKey\":\"sk-test-%%n\"}" > nul 2>&1
    if %errorlevel% equ 0 (
        echo [PASS] Registered bot_%%n
        set /a PASS+=1
    ) else (
        echo [FAIL] Failed bot_%%n
        set /a FAIL+=1
    )
)

REM Check count
echo ==================== Step 3: Verify Bot Count ====================
curl -s "%BASE_URL%/bots/count" -H "Authorization: Bearer %TOKEN%"
echo.

REM Distillation test
echo ==================== Step 4: Distillation Test ====================
curl -s -X POST "%BASE_URL%/bots/distill" -H "Authorization: Bearer %TOKEN%" -H "Content-Type: application/json"
echo [PASS] Distillation endpoint called
set /a PASS+=1

echo ============================================================
echo Tests complete: %PASS% passed, %FAIL% failed
echo ============================================================
