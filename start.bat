@echo off
setlocal
cd /d "%~dp0"

echo ================================================
echo   AgentFlow - One-click startup
echo ================================================

where java >nul 2>nul
if errorlevel 1 (
  echo [ERROR] Java not found. Please install JDK 17+: https://adoptium.net/
  pause
  exit /b 1
)

rem Switch console to UTF-8 before reading .env. The file is UTF-8, and under the default
rem GBK code page cmd mangles non-ASCII values (e.g. AGENTFLOW_DEPT=中台研发部 arrives in the
rem JVM as mojibake). With 65001 the JVM reads them intact.
chcp 65001 >nul

rem Load optional .env from project root (DEEPSEEK_API_KEY etc.)
if exist ".env" (
  echo [INFO] Loaded .env
  for /f "usebackq eol=# tokens=1,* delims==" %%a in (".env") do set "%%a=%%b"
)

if not defined DEEPSEEK_API_KEY (
  echo [WARN] DEEPSEEK_API_KEY not set - LLM features disabled. Configure it in .env
)

echo [INFO] Starting... first run will auto-download Maven, Node.js and dependencies.
echo [INFO] When ready, open http://localhost:8888
echo.

cd backend
call mvnw.cmd spring-boot:run

endlocal
pause
