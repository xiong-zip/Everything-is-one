# Everything-is-one · AgentFlow AI 任务助手

一个基于 **Vue 3 + Spring Boot + DeepSeek** 的端到端 AI 任务助手：**任意自然语言指令**交给服务端 Agent 编排引擎，自动完成意图分析 → 任务拆解 → 工具调用 → 结果汇总，全程通过 SSE 流式推送到对话式前端。

界面为清新的薄荷青绿风格对话流单页：指令气泡 + Agent 执行过程（意图、阶段、步骤时间线、结果卡片）+ 最终答案，支持多轮任务历史。

## 两种运行模式

| 模式 | 触发条件 | 行为 |
|---|---|---|
| **LLM 模式** | 配置 `DEEPSEEK_API_KEY` | LLM 动态规划任意任务、真实推理与内容生成、AI 汇总 |
| **模拟模式** | 未配置 Key | 启发式拆解任意指令；天气/股价仍调**真实数据 API**；LLM 类内容以模板生成并明确标注 |

不限于任何预置场景——"查北京天气写首诗"、"贵州茅台股价点评"、"写封请假邮件"都可以直接执行。

## 项目结构

```
Everything-is-one/
├── start.bat / start.sh        # 一键启动脚本（推荐）
├── .env.example                # 环境变量配置示例（复制为 .env 使用）
├── frontend/                   # 前端工程（Vue 3 + Vite，对话流单页）
│   ├── package.json            # npm 依赖清单
│   ├── vite.config.js          # 构建输出到 backend static / 开发代理
│   ├── index.html
│   └── src/
│       ├── main.js             # 入口
│       ├── style.css           # 全局样式（清新薄荷青绿主题）
│       ├── App.vue             # 对话壳（欢迎页 + 多轮消息流）
│       ├── composables/useAgent.js   # 多轮对话状态（SSE 流式消费）
│       └── components/
│           ├── ChatHeader.vue        # 顶栏（品牌 / LLM 状态 / 清空对话）
│           ├── AgentRun.vue          # 单轮 Agent 执行面板（意图/阶段/时间线/汇总）
│           ├── StepCard.vue          # 执行步骤卡片
│           ├── ResultCard.vue        # 结果卡片（天气/股票/交通/列表/文案/通用提示）
│           └── Composer.vue          # 底部输入区（自动增高 + 示例建议）
└── backend/                    # 后端（Spring Boot 3）
    ├── pom.xml                 # Maven 依赖 + frontend-maven-plugin（自动下载 Node 并构建前端）
    ├── mvnw / mvnw.cmd         # Maven Wrapper，无需预装 Maven
    └── src/main/
        ├── java/com/agentflow/
        │   ├── AgentflowApplication.java   # Spring Boot 入口
        │   ├── config/WebConfig.java       # CORS 配置
        │   ├── controller/AgentController.java  # REST API（/scenarios 返回示例建议）
        │   ├── engine/AgentEngine.java     # 通用编排：意图分析 → LLM/启发式规划 → 执行 → 汇总
        │   ├── llm/LlmClient.java          # DeepSeek 客户端（含 JSON mode）
        │   ├── model/                      # PlanStep / ToolCall / RunRequest
        │   └── tool/                       # 工具层（天气/股票为真实 API；交通/POI 走 LLM，模拟模式优雅降级）
        └── resources/
            ├── application.yml             # 配置（端口 8080 等）
            └── static/                     # 前端构建产物（自动生成，勿手改）
```

## 技术栈

- **前端**：Vue 3（组合式 API）+ Vite，SSE 流式渲染，无 UI 框架依赖
- **后端**：Java 17+、Spring Boot 3.5.3（spring-boot-starter-web）、Maven
- **LLM**：DeepSeek Chat API（`deepseek-v4-flash`）
- **构建链**：Maven Wrapper + frontend-maven-plugin（自动下载 Node.js / npm 并执行前端构建，本机无需安装 Node）

## 一键启动（推荐）

唯一前置条件：本机安装 **JDK 17+**。不需要安装 Maven、Node.js、npm——首次启动会自动下载：

- Maven 与后端依赖
- Node.js 运行时与前端依赖
- 构建前端并随服务一起启动

**Windows**：双击根目录的 `start.bat`，或在命令行执行：

```bat
start.bat
```

**Linux / macOS / Git Bash**：

```bash
./start.sh
```

首次运行因下载依赖较慢，之后启动会很快。启动完成后访问：

> http://localhost:8080

## 环境变量

启动脚本会自动加载根目录的 `.env` 文件（参考 `.env.example` 复制一份）：

| 变量 | 必填 | 说明 |
|---|---|---|
| `DEEPSEEK_API_KEY` | 启用 LLM 必填 | DeepSeek API Key，缺失时以模拟模式运行（流程演示不受影响） |
| `LLM_PROXY_HOST` | 否 | LLM 出站代理地址 |
| `LLM_PROXY_PORT` | 否 | LLM 出站代理端口 |

## 前端开发模式（可选）

日常开发前端时，用 Vite 热更新更方便（需要本机已装 Node.js 18+；`/api` 已代理到 8080 后端）：

```bash
# 终端 1：先启动后端
cd backend && mvnw spring-boot:run

# 终端 2：前端开发服务器（热更新）
cd frontend && npm install && npm run dev
# 访问 http://localhost:5173
```

改完执行 `npm run build`（或直接重启后端）即可把产物同步到后端托管。

## 传统启动方式

```bash
cd backend
./mvnw spring-boot:run            # 开发运行（Windows 用 mvnw.cmd），会自动先构建前端

./mvnw package                    # 打包（含前端构建）
java -jar target/agentflow-backend-0.0.1-SNAPSHOT.jar   # 运行 jar
```

## API 一览

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/agent/run` | 发起任务（任意自然语言指令） |
| GET | `/api/agent/stream/{taskId}` | SSE 流式获取执行过程 |
| GET | `/api/agent/scenarios` | 示例建议（不限制可执行指令） |
| GET | `/api/agent/info` | 服务信息 |

SSE 事件：`status` / `phase` / `intent`（意图摘要+实体）/ `plan` / `step` / `step-state` / `tool` / `reason` / `result` / `done`。

## 配置

服务端口等配置见 `backend/src/main/resources/application.yml`，默认 **8080**；前端开发端口见 `frontend/vite.config.js`（默认 **5173**）。
