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

rem Load optional .env from project root (DEEPSEEK_API_KEY etc.)
if exist ".env" (
  echo [INFO] Loaded .env
  for /f "usebackq eol=# tokens=1,* delims==" %%a in (".env") do set "%%a=%%b"
)

if not defined DEEPSEEK_API_KEY (
  echo [WARN] DEEPSEEK_API_KEY not set - LLM features disabled. Configure it in .env
)

echo [INFO] Starting... first run will auto-download Maven, Node.js and dependencies.
echo [INFO] When ready, open http://localhost:8080
echo.

cd backend
call mvnw.cmd spring-boot:run

endlocal
pause
