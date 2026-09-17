#!/usr/bin/env bash
# AgentFlow 一键启动脚本（Linux / macOS / Git Bash）
set -e
cd "$(dirname "$0")"

echo "================================================"
echo "  AgentFlow - AI 任务助手 一键启动"
echo "================================================"

if ! command -v java >/dev/null 2>&1; then
  echo "[错误] 未检测到 Java，请先安装 JDK 17 或更高版本：https://adoptium.net/"
  exit 1
fi

# 从根目录 .env 读取环境变量（可选，用于配置 DEEPSEEK_API_KEY 等）
if [ -f .env ]; then
  echo "[信息] 已加载 .env 配置"
  set -a
  # shellcheck disable=SC1091
  source .env
  set +a
fi

if [ -z "${DEEPSEEK_API_KEY:-}" ]; then
  echo "[提示] 未设置 DEEPSEEK_API_KEY，LLM 智能规划功能将被禁用，可在 .env 中配置。"
fi

echo "[信息] 正在启动，首次运行会自动下载 Maven、Node.js 与依赖，请耐心等待..."
echo "[信息] 启动完成后请访问 http://localhost:8888"
echo

cd backend
./mvnw spring-boot:run
