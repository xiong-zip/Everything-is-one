# Everything-is-one · AgentFlow AI 任务助手

一个基于 **Vue 3 + Spring Boot + DeepSeek** 的端到端 AI 任务助手：**任意自然语言指令**交给服务端 Agent 编排引擎，自动完成意图分析 → 任务拆解 → 工具调用 → 结果汇总，全程通过 SSE 流式推送到对话式前端。

界面为清新的薄荷青绿风格对话流单页：指令气泡 + Agent 执行过程（意图、阶段、步骤时间线、结果卡片）+ 最终答案，支持多轮任务历史。

## 核心能力

| 能力 | 说明 |
|---|---|
| **任务后台执行** | 提交即后台运行，关页面不影响；随时连流查看，断线自动续传（按事件序号补发） |
| **流式中断** | 执行中可随时「停止」，引擎在最近的检查点安全收尾 |
| **计划人工放行** | 「确认模式」：任务规划后先出计划卡，可勾选跳过、编辑工具参数再执行；写操作工具**强制**走此流程 |
| **无依赖步骤并行** | 规划时标注同一并行组的 tool 步并发执行（上限 3） |
| **ReAct 自主循环** | `AGENTFLOW_AGENT_MODE=react` 开启：Agent 逐轮决策下一步动作，而非一次规划到底 |
| **OpenAPI 一键转工具** | 贴一个 Swagger/OpenAPI 文档地址，GET 接口自动变成 Agent 可调用的工具（持久化，重启不丢） |
| **GitLab 只读查询 + 受控写操作** | 提交/项目/Issue/MR/流水线查询；创建 Issue、评论等低风险写操作需人工确认 |
| **数据库透视（内置 db-architect）** | 自然语言查库表结构 / DDL / 按中文名找表 / 导出数据为 INSERT / ER 图 / 跨库结构比对，支持达梦、MySQL、Oracle、PostgreSQL；**连接配置在界面里管理**（工作台 → 数据库连接），无需改任何配置文件 |
| **SigNoz 链路分析 + 故障案例知识库** | 给一个 trace ID 自动查 span 树与 ERROR/WARN 日志，输出根因、失败传播链、耗时与状态矛盾（如 HTTP 200 但业务失败）；默认 24h 查不到自动扩到 7d；分析前自动比对 `docs/incidents/` 知识库，命中已知故障直接复用历史处置；可将结论归档为案例（同一故障模式累加次数，不重复建档，归档需人工放行） |
| **K8s 运维排查（只读）** | 内置 `k8s.query` 工具，经 **Kuboard** 面板的 `/k8s-api` 代理访问集群：Pod/Deployment/Service/Job 列表与异常状态（CrashLoop/Pending/未就绪自动标 ⚠）、Pod 日志（含崩溃前的 previous 日志、关键词过滤、按服务名跨命名空间反查实例）、命名空间 Warning 事件、节点状态；**关键字未命中时自动停止后续步骤**，按名称相似度（子串/编辑距离）给出相近候选卡片反问用户，点选即重跑；与链路分析联动可实现「trace 定位服务 → 看 Pod 状态/日志/事件」一条龙排查 |
| **工作台（窗口弹窗）** | 入口在左下角输入区，打开为居中窗口：**左侧菜单 + 右侧内容**，含链路分析记录、数据库连接、GitLab 账户、效能热力图、晨报机器人、工具管理；支持 Esc / 点遮罩 / 关闭按钮退出 |
| **链路分析记录** | 每次链路分析自动落库（按 trace ID 去重，重复分析累加次数）：失败点、指纹、涉及服务、耗时、环境、命中的案例、分析摘要全文；可按 trace ID / 失败点 / 指纹 / 服务 / 案例号检索，并统计失败数与高频故障指纹 |
| **GitLab 效能日报/周报** | 拉取时间窗内逐条提交，LLM 归纳成固定格式报告，支持「今天/昨天/本周」 |
| **自动晨报机器人** | 定时（默认工作日 9 点）生成昨日日报并推送到企微/钉钉 Webhook，全程无人值守 |
| **效能热力图** | 半年 GitLab 提交分布一图可见：总提交、活跃天、最长连续（数据缓存 10 分钟） |
| **历史回放 + 搜索** | 每个任务的完整事件流落 SQLite，可搜索指令/摘要、分页加载、随时原样回放；超期历史自动清理 |
| **报告导出** | 结果一键下载 .txt / .md，或打印为 PDF |

## 两种运行模式

| 模式 | 触发条件 | 行为 |
|---|---|---|
| **LLM 模式** | 配置 `DEEPSEEK_API_KEY` | LLM 动态规划任意任务、真实推理与内容生成、AI 汇总 |
| **模拟模式** | 未配置 Key | 启发式拆解任意指令；GitLab / 数据库 / 链路分析仍取**真实数据**；LLM 类内容以模板生成并明确标注 |

不限于任何预置场景——"根据我的提交生成周报"、"分析链路 c4ea1634…"、"写封请假邮件"都可以直接执行。

## 项目结构

```
Everything-is-one/
├── start.bat / start.sh        # 一键启动脚本（推荐）
├── .env.example                # 环境变量配置示例（复制为 .env 使用）
├── .agents/                    # Agent 扩展（随 git 提交，团队共享）
│   ├── mcp.json                # MCP 服务配置（signoz）
│   └── skills/                 # 项目级 skill
│       └── signoz-analyzing-traces/   # SigNoz 链路分析 + 故障案例沉淀
├── docs/incidents/             # 故障案例知识库（trace 分析的沉淀产物）
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
│           ├── ResultCard.vue        # 结果卡片（列表/文案/通用提示）
│           └── Composer.vue          # 底部输入区（自动增高 + 示例建议）
└── backend/                    # 后端（Spring Boot 3）
    ├── pom.xml                 # Maven 依赖 + frontend-maven-plugin（自动下载 Node 并构建前端）
    ├── mvnw / mvnw.cmd         # Maven Wrapper，无需预装 Maven
    └── src/main/
        ├── java/com/agentflow/
        │   ├── AgentflowApplication.java   # Spring Boot 入口（@EnableScheduling）
        │   ├── config/                    # WebConfig(CORS) / GlobalExceptionHandler(400)
        │   ├── controller/                # AgentController / ToolsController / ScheduleController
        │   ├── engine/                    # AgentEngine(编排) / RunSession(会话) / RunStore(SQLite)
        │   ├── schedule/                  # 晨报机器人（ScheduleService / ScheduleStore）
        │   ├── notify/                    # Webhook 推送（NotifyService）
        │   ├── llm/LlmClient.java         # DeepSeek 客户端（JSON mode + 流式）
        │   ├── model/                     # PlanStep / ToolCall / RunRequest / PlanConfirmRequest
        │   ├── signoz/                    # SigNoz 链路分析（MCP 客户端 / 摘要 / 故障案例知识库）
        │   └── tool/                      # 内置工具 + dynamic/（OpenAPI 导入的动态工具）
        └── resources/
            ├── application.yml             # 配置（端口 8888 等）
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

> http://localhost:8888

## 环境变量

启动脚本会自动加载根目录的 `.env` 文件（参考 `.env.example` 复制一份）：

| 变量 | 必填 | 说明 |
|---|---|---|
| `DEEPSEEK_API_KEY` | 启用 LLM 必填 | DeepSeek API Key，缺失时以模拟模式运行（流程演示不受影响） |
| `LLM_PROXY_HOST` / `LLM_PROXY_PORT` | 否 | LLM 出站代理 |
| `GITLAB_URL` / `GITLAB_TOKEN` | 否 | 公司内部 GitLab（Personal Access Token，scope 选 `read_api`；写操作需 `api`） |
| `AGENTFLOW_AGENT_MODE` | 否 | `plan`（默认，先规划再执行）/ `react`（自主循环逐步决策） |
| `AGENTFLOW_NOTIFY_WEBHOOK` | 否 | 企微/钉钉机器人 Webhook，晨报生成后推送 |
| `AGENTFLOW_MORNING_REPORT` | 否 | 自动晨报开关（默认 `false`） |
| `AGENTFLOW_MORNING_CRON` | 否 | 晨报 cron（默认 `0 0 9 * * MON-FRI`，本地时区） |
| `AGENTFLOW_MORNING_COMMAND` | 否 | 晨报指令（默认「根据我的 GitLab 提交记录生成昨天的工作日报」） |
| `AGENTFLOW_DB` | 否 | SQLite 数据库路径（默认 `./data/agentflow.db`，相对路径自动锚定到项目根） |
| `AGENTFLOW_RETENTION_DAYS` | 否 | 历史保留天数，超期记录启动时清理（默认 30，0 = 不清理） |
| `AGENTFLOW_MAX_RUNS` | 否 | 历史条数上限（默认 1000，0 = 不限制） |
| `AGENTFLOW_DEPT` | 否 | 报告抬头部门名（默认 `中台研发部`） |
| `SIGNOZ_MCP_URL` | 否 | SigNoz MCP 地址，用于链路分析工具（默认 `http://192.168.2.111:18000/mcp`，需内网可达） |
| `KUBOARD_URL` / `KUBOARD_USERNAME` / `KUBOARD_PASSWORD` | 否 | Kuboard 面板地址与账号，启用 `k8s.query` 运维排查工具（经其代理只读访问集群） |
| `KUBOARD_CLUSTER` | 否 | 默认操作的集群名（Kuboard 导入时的名称，默认 `dev`；完整列表可用 `k8s.query` 的 `clusters` 查询） |
| `SIGNOZ_TIME_RANGE` / `SIGNOZ_FALLBACK_RANGE` / `SIGNOZ_LOG_LIMIT` | 否 | 链路查询时间窗、查不到时的降级窗口、补查日志条数（默认 `24h` / `7d` / `10`；两天前的链路只有 `7d` 才查得到） |
| `AGENTFLOW_INCIDENT_KB` | 否 | 故障案例知识库目录（默认 `./docs/incidents`，相对路径自动锚定项目根） |
| `AGENTFLOW_ANALYSIS_MAX` | 否 | 链路分析记录保留条数上限（默认 `2000`，工作台 → 链路分析 面板的数据） |

## 前端开发模式（可选）

日常开发前端时，用 Vite 热更新更方便（需要本机已装 Node.js 18+；`/api` 已代理到 8888 后端）：

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
| POST | `/api/agent/run` | 发起任务（body 可带 `mode: auto/confirm`） |
| GET | `/api/agent/stream/{taskId}?afterSeq=N` | SSE 订阅执行过程（断线重连带 afterSeq 只补发缺失事件） |
| POST | `/api/agent/cancel/{taskId}` | 手动停止任务 |
| POST | `/api/agent/run/{taskId}/confirm` | confirm 模式回传确认/编辑后的计划 |
| GET/DELETE | `/api/agent/history` `/{id}` | 任务历史列表（支持 `keyword` 关键词搜索）/ 回放 / 删除 / 清空 |
| GET | `/api/stats/heatmap?days=182` | GitLab 提交热力图数据（缓存 10 分钟） |
| GET | `/api/signoz/analyses?keyword=&limit=&offset=` | 链路分析记录列表（keyword 匹配 trace ID / 失败点 / 指纹 / 服务 / 案例号） |
| GET/DELETE | `/api/signoz/analyses` `/{id}` | 分析记录详情 / 删除单条 / 清空全部 |
| GET | `/api/signoz/stats` | 链路分析汇总（总数、失败数、未查到、命中案例、Top 服务与指纹） |
| GET/POST/DELETE | `/api/dbprofiles` `/{name}` `/{name}/test` | 数据库连接管理（保存/删除/测试连接） |
| GET | `/api/tools` | 已注册工具列表（含动态工具、写操作标记） |
| POST | `/api/tools/openapi` | 从 OpenAPI/Swagger 文档导入工具（body: `{"url": "..."}`） |
| DELETE | `/api/tools/{name}` | 删除动态工具（内置工具不可删） |
| GET | `/api/schedule/status` | 晨报机器人配置与最近执行 |
| POST | `/api/schedule/run-now` | 立即试跑晨报（同步返回，约 1 分钟） |
| GET | `/api/agent/scenarios` / `/api/agent/info` | 示例建议 / 服务信息 |

SSE 事件：`status` / `phase` / `intent` / `plan` / `plan-proposal`（计划提案，等待确认）/ `plan-confirmed` / `step` / `step-state` / `tool` / `reason` / `result-delta`（流式增量）/ `result` / `done`；每个事件携带 `seq` 序号供断线续传。

## 运行测试

```bash
cd backend && ./mvnw test    # 10 个单测：工具注册表 / 动态工具 / 任务存储（含 afterSeq 增量）
```

## 配置

服务端口等配置见 `backend/src/main/resources/application.yml`，默认 **8888**（可用环境变量 `AGENTFLOW_PORT` 覆盖）；前端开发端口见 `frontend/vite.config.js`（默认 **5173**）。
