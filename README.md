# Everything-is-one · AgentFlow AI 任务助手

一个基于 **Vue 3 + Spring Boot + DeepSeek** 的端到端 AI 任务助手：**任意自然语言指令**交给服务端 Agent 编排引擎，自动完成意图分析 → 任务拆解 → 工具调用 → 结果汇总，全程通过 SSE 流式推送到对话式前端。

> 想深入了解架构与实现？见 [docs/learning-guide.md](docs/learning-guide.md)（学习文档：技术选型、核心机制精读、各功能实现详解与扩展指南）。

界面为清新的薄荷青绿风格对话流单页：指令气泡 + Agent 执行过程（意图、阶段、步骤时间线、结果卡片）+ 最终答案，支持多轮任务历史。

## 核心能力

| 能力 | 说明 |
|---|---|
| **任务后台执行** | 提交即后台运行，关页面不影响；随时连流查看，断线自动续传（按事件序号补发） |
| **流式中断** | 执行中可随时「停止」，引擎在最近的检查点安全收尾 |
| **计划人工放行** | 「确认模式」：任务规划后先出计划卡，可勾选跳过、编辑工具参数再执行；写操作工具**强制**走此流程 |
| **无依赖步骤并行** | 规划时标注同一并行组的 tool 步并发执行（上限 3） |
| **ReAct 自主循环 / 混合模式** | `AGENTFLOW_AGENT_MODE=react` 开启逐轮决策；`hybrid` 混合模式先按计划执行，工具步未命中/失败时自动放弃剩余计划、转 ReAct 自主找路（预算 4 步，需 LLM） |
| **长期记忆** | 跨会话记住用户偏好与事实（常用集群、署名、习惯说法），注入每次规划/推理/生成/汇总；对话说「记住 XXX / 忘记 XXX」直接增删（不花 LLM）；任务收尾自动提取值得记住的偏好（可在工作台关闭）；工作台 → 知识与记忆 → 记忆 面板可视化管理 |
| **个人知识库** | 输入框 📎 或工作台上传文档（md/txt/csv/json/log/html/pdf/docx/xlsx，同名重传即替换），解析分块入库；聊天问「根据知识库，XXX」自动检索 Top5 相关片段作答（答案注明来源文件）；`kb.query` 工具支持 search/list/read 三模式，面板可试搜与全文阅读。**检索双模式自动切换**：默认关键词打分（中文 2-gram + 逆文档频率加权）；.env 配置嵌入模型后升级为「语义向量 + 关键词」混合排序（余弦 0.65 + 关键词 0.35），口语提问也能命中术语文档，嵌入服务故障时单次自动退回关键词模式 |
| **OpenAPI 一键转工具** | 贴一个 Swagger/OpenAPI 文档地址，GET 接口自动变成 Agent 可调用的工具（持久化，重启不丢） |
| **MCP 服务即插即用** | 工作台 → 工具与自动化 → MCP 服务 面板登记任意 **MCP（Model Context Protocol）** 服务，它暴露的工具**自动变成 Agent 工具**（命名 `<服务名>.<工具名>`，与内置工具同构，直接参与任务规划），不必为每个系统写代码。保存只落配置、不连网，「刷新工具」才去拉清单；清单缓存落库，重启与离线都不丢。支持自定义鉴权请求头；远端声明的 `readOnlyHint` 会被采纳，未声明时默认**所有工具走人工确认**（远端行为未知，宁可按一次确认）。服务名与内置工具前缀冲突会被拒绝，避免静默覆盖内置工具 |
| **GitLab 只读查询 + 受控写操作** | 提交/项目/Issue/MR/流水线查询；创建 Issue、评论等低风险写操作需人工确认 |
| **数据库透视（内置 db-architect）** | 自然语言查库表结构 / DDL / 按中文名找表 / 导出数据为 INSERT / ER 图 / 跨库结构比对，支持达梦、MySQL、Oracle、PostgreSQL；**连接配置在界面里管理**（工作台 → 外部接入 → 数据库连接），无需改任何配置文件 |
| **SigNoz 链路分析 + 故障案例知识库** | 给一个 trace ID 自动查 span 树与 ERROR/WARN 日志，输出根因、失败传播链、耗时与状态矛盾（如 HTTP 200 但业务失败）；默认 24h 查不到自动扩到 7d；分析前自动比对 `docs/incidents/` 知识库，命中已知故障直接复用历史处置；可将结论归档为案例（同一故障模式累加次数，不重复建档，归档需人工放行）。另有 `signoz.analyses` 工具让 Agent 能查**自己的排查历史**（过去 N 小时分析了哪些链路、哪些失败、哪些指纹反复出现），于是「汇总过去 12 小时失败链路，生成值班交接摘要」这类需求不需要新代码——在定时任务面板加一条自然语言指令即可 |
| **变更关联（谁改坏的）** | 内置 `gitlab.changes` 工具：链路分析后自动衔接，拉取涉及服务对应 GitLab 项目在故障前 N 小时（默认 48，可调）的提交，按可疑度排序——时间接近、修复/回滚类提交、失败点服务、流水线失败加权，**每条附理由，规则透明**；服务名↔GitLab 项目映射在工作台「服务映射」面板管理，未配置的服务按名称搜索自动推断，歧义时候选卡反问、选中即固化（越用越准）；也可独立使用（「查 pay-service 最近谁改的」） |
| **K8s 运维排查（只读）** | 内置 `k8s.query` 工具，经 **Kuboard** 面板的 `/k8s-api` 代理访问集群：Pod/Deployment/Service/Job 列表与异常状态（CrashLoop/Pending/未就绪自动标 ⚠）、Pod 日志（含崩溃前的 previous 日志、关键词过滤、按服务名跨命名空间反查实例）、命名空间 Warning 事件、节点状态；**关键字未命中时自动停止后续步骤**，按名称相似度（子串/编辑距离）给出相近候选卡片反问用户，点选即重跑；与链路分析联动可实现「trace 定位服务 → 看 Pod 状态/日志/事件」一条龙排查 |
| **主动健康巡检** | 内置 `signoz.health` 工具：一次调用扫全部服务的错误率/P99/调用量（SigNoz）+ K8s 异常 Pod（CrashLoop/Pending/未就绪/高重启）+ 近窗口告警值守记录，输出按高/中分级的风险清单（阈值可调、小样本服务不误报）；不等告警上门主动发现风险。对话里说「巡检/体检」即用，也适合加一条定时任务「对全部服务做健康巡检，生成晨间风险预告」到点自动推送 |
| **一键故障报告** | 内置 `signoz.report` 工具 + 链路分析面板按钮：给一个 trace ID 自动聚合四路证据——链路分析（根因/传播链）、变更关联（谁改坏的）、K8s 实例状态、告警值守时间线——生成结构化故障报告（影响面/时间线/根因分析含置信度/处置建议/待办事项）；配置 LLM 用模型归纳（只用给定证据、不确定标「待确认」），未配置用模板如实拼装；报告可复制/下载 .md，归档仍走 signoz.case 人工放行 |
| **告警自动值守** | 给监控平台配一个 webhook 地址（`POST /api/hooks/alarm`）即可：**告警一到就自动排查**——有 trace ID 就分析链路并关联代码变更，只有服务名就查 Pod 状态与异常事件，结论推送并 @ 值班人，全程无需有人在。跨平台载荷通用解析（SigNoz / Alertmanager / 纯文本都能收）；**同一告警在窗口内只排查一次**（防告警风暴），已恢复的告警只留痕不排查；排查出的故障指纹若此前已出现过，会在推送里标注**疑似回归**并附历史链路。工作台 → 可观测 → 告警值守 面板可见接入地址、模拟验证、全部记录与结论全文 |
| **@ 点名工具直达** | 输入框打 `@` 唤起工具下拉（名称/说明过滤，↑↓ 选择、Enter 插入）；指令里 `@工具名`（如 `@wecom.daily 提交今天的工作日报`）**跳过 LLM 规划直接执行该工具**——规划器偶尔会把「执行工具 X」改写成惯用取数路径，点名即免；定时任务里同样可用 @ 指令 |
| **一键工作日报（wecom.daily）** | 拉取指定日期（默认今天）的 GitLab 提交 → 生成「今日工作总结/明日工作计划」→ **写入团队智能表格指定子表**（目标表格在 .env 固定，`AGENTFLOW_DAILY_DOCID/SHEET_ID/SUBMITTER`）。写入**幂等**：同日机器人自己的记录已存在则更新而非新增（按 creator 识别，重复跑定时任务不刷屏）；当日无提交默认不写。免确认设计：LLM 只能控制日期与正文、目标写死、写入幂等，适合挂定时任务无人值守 |
| **企业微信接入（wecom.\* 工具）** | 经部署机上的 [wecom-cli](https://github.com/WecomTeam/wecom-cli) 操作企业微信：文档检索/读取（`wecom.doc.query`，只读）、文档新建/追加/覆盖（`wecom.doc.write`）、发送消息（`wecom.message.send`）、通用兜底 `wecom.call`（邮件/待办/日程/微盘/通讯录等全部服务，方法名 `service.method` 两段式）。写操作一律强制人工确认；扫码授权在部署机终端完成，凭据只留本机，服务端不接触任何 Secret。工作台 → 外部接入 → 企业微信 面板可看授权状态与能力清单（从 CLI 帮助解析，实测 13 服务 94 方法） |
| **对外 MCP 服务** | 把 Agent 工具（查链路、巡检、知识库、数据库透视等）以 MCP 服务形式暴露给外部客户端（`POST /api/mcp/server`，Streamable HTTP 无状态）。典型用法：企微管理后台「智能机器人 → MCP 配置」填入接入地址，之后在企微里对话即可调用。写操作工具在外部通道无法人工放行，一律拒绝执行（readOnlyHint 如实标注）；未配令牌时仅本机可访问，调用记录落库可在 工作台 → 工具与自动化 → 对外服务 面板查看 |
| **工作台（窗口弹窗）** | 入口在左下角输入区，打开为居中窗口：**左侧菜单 + 右侧内容**。菜单按域收敛为五个分组，组内用二级 tab 切换，不必为每个面板各记一个入口：**可观测**（链路分析记录、告警值守、服务映射）、**工具与自动化**（工具管理、MCP 服务、定时任务——内含推送通道管理）、**外部接入**（数据库连接、GitLab 效能）、**知识与记忆**（知识库、记忆）、**模型与成本**（模型接入、效能与成本）；底部另有独立的**通用设置**（主题切换）；支持 Esc / 点遮罩 / 关闭按钮退出 |
| **链路分析记录** | 每次链路分析自动落库（按 trace ID 去重，重复分析累加次数）：失败点、指纹、涉及服务、耗时、环境、命中的案例、分析摘要全文；可按 trace ID / 失败点 / 指纹 / 服务 / 案例号检索，并统计失败数与高频故障指纹 |
| **GitLab 效能日报/周报** | 拉取时间窗内逐条提交，LLM 归纳成固定格式报告，支持「今天/昨天/本周」 |
| **定时任务（多任务）** | 工作台 → 工具与自动化 → 定时任务 面板管理任意多条指令，到点（默认工作日 9 点）**依次执行所有已启用的任务**并推送到勾选的通道；**每个任务可单独开关**（停掉某条而不删掉它），可编辑指令、可删除（连同它的执行记录）；执行记录**按指令归类**在每个任务下，展开即可看历史、也能删单条。与 `signoz.health` 巡检组合可建「晨间风险预告」任务（如「对全部服务做健康巡检，汇总高风险项生成晨间风险预告」） |
| **推送通道（多通道多选）** | 工作台 → 工具与自动化 → 定时任务 → 点「推送通道」弹出管理窗：可添加任意多个企微/钉钉群机器人，**勾选哪些就推给哪些（可多选）**，晨报与告警同时发往所有勾选通道；地址含密钥，列表只回显脱敏值（留末 4 位），支持单通道「测试」实发一条验证；一条都没配时自动回退 `.env` 的 `AGENTFLOW_NOTIFY_WEBHOOK` |
| **推送方式（三档）** | 面板下拉切换，即改即生效：**纯文本**（上限 2048 字节，超出被企微静默截断；告警固定用这档，@ 值班人只有文本可靠）／**Markdown 长文**（上限 4096 字节，可读性更好）／**摘要 + 文件附件**（群里发几条摘要 + 完整 `.md` 附件，绕开字节上限，实测企微附件上限 20MB）。钉钉自定义机器人发不了文件，该档会自动降级为 Markdown |
| **效能热力图** | 半年 GitLab 提交分布一图可见：总提交、活跃天、最长连续（数据缓存 10 分钟） |
| **LLM 效能与成本** | 每次模型调用的**用途、token、耗时、成败**都落库（用途由调用方显式标注：任务规划 / 自主决策 / 推理思考 / 内容生成 / 结果汇总 / 记忆提取 / 连通测试）；工作台 → 模型与成本 → 效能与成本 面板按时间窗给出总量、**按阶段与按模型的分布**、任务花费排行、20 条调用流水与单次任务的分阶段明细——回答「钱和时间花在哪个环节」「哪个定时任务最贵」。非流式响应取服务端精确用量；流式默认按文本长度估算并**如实标记为估算值**（可用 `AGENTFLOW_LLM_STREAM_USAGE=true` 请求精确值）；金额需配单价才显示，不内置价目表 |
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
        │   ├── AgentflowApplication.java   # Spring Boot 入口（@EnableScheduling，启动加载 .env）
        │   ├── config/                    # WebConfig(CORS) / GlobalExceptionHandler(400)
        │   ├── controller/                # Agent / Tools / Schedule / Notify / Alarm / Mcp / LlmUsage 等
        │   ├── engine/                    # AgentEngine(编排) / RunSession(会话) / RunStore(SQLite)
        │   ├── alarm/                     # 告警值守（AlarmParser 跨平台解析 / AlarmService 去重与后台排查 / AlarmStore）
        │   ├── mcp/                       # 通用 MCP 客户端（McpClient 协议与传输 / McpTool 适配 / McpToolService 注册 / McpServerStore）
│   ├── wecom/                     # 企微接入（WecomCliRunner 进程执行 / wecom.* 工具 / 能力发现与缓存）
│   ├── mcpserver/                 # 对外 MCP 服务端（JSON-RPC 处理 / 调用记录）
        │   ├── schedule/                  # 定时任务（ScheduleService 调度 / ScheduleStore 任务与执行记录）
        │   ├── notify/                    # 推送通道（NotifyService 多通道分发 / NotifyChannelStore 多选配置）
        │   ├── llm/                       # LlmClient（双协议 + 用量埋点）/ LlmContext(任务归属) / LlmUsageStore / LlmUsageService
        │   ├── model/                     # PlanStep / ToolCall / RunRequest / PlanConfirmRequest
        │   ├── signoz/                    # SigNoz 链路分析（MCP 客户端 / 摘要 / 故障案例知识库 / 分析记录）
        │   └── tool/                      # 内置工具 + dynamic/（OpenAPI 导入的动态工具）+ ResponseDigest(响应摘要)
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
| `AGENTFLOW_AGENT_MODE` | 否 | `plan`（默认，先规划再执行）/ `react`（自主循环逐步决策）/ `hybrid`（计划受阻自动转 ReAct，需 LLM） |
| `AGENTFLOW_MEMORY` | 否 | 长期记忆总开关（默认 `true`；关闭后不注入 prompt、不自动提取） |
| `AGENTFLOW_KB_MAX_FILE_MB` | 否 | 知识库单文件上传上限（默认 `20` MB） |
| `AGENTFLOW_KB_MAX_FILES` | 否 | 知识库文件数上限（默认 `200`） |
| `AGENTFLOW_KB_CHUNK_CHARS` / `AGENTFLOW_KB_CHUNK_OVERLAP` / `AGENTFLOW_KB_TOP_K` | 否 | 知识库分块大小 / 相邻块重叠 / 检索返回命中数（默认 `600` / `80` / `5`） |
| `AGENTFLOW_EMBED_URL` / `AGENTFLOW_EMBED_MODEL` / `AGENTFLOW_EMBED_KEY` | 否 | 知识库**向量检索**（可选）：OpenAI 兼容 `/v1/embeddings` 接口地址 / 模型名 / Key。配置后检索升级为「语义 + 关键词」混合排序（DeepSeek 无 embedding 接口，需另配兼容网关或本地 Ollama）；配好在工作台 → 知识库 点「重建索引」，新上传文档自动向量化，换模型重建时自动清空旧向量 |
| `AGENTFLOW_NOTIFY_WEBHOOK` | 否 | 企微/钉钉**群机器人** Webhook（群聊 → 群机器人 → 添加 → 复制地址）。**仅在界面没配过推送通道时作为兜底**；界面配了就以界面为准 |
| `AGENTFLOW_NOTIFY_MENTION` | 否 | 告警推送要 @ 的值班人（企微手机号，逗号分隔；`@all` 表示所有人）。只作用于告警，晨报不 @ 人 |
| `AGENTFLOW_MORNING_REPORT` | 否 | **总开关**初始值（默认 `false`）。工作台 → 工具与自动化 → 定时任务 面板可随时切换，界面切过之后以界面为准 |
| `AGENTFLOW_MORNING_CRON` | 否 | 任务执行时间（默认 `0 0 9 * * MON-FRI`，本地时区）。**全局统一、且只能在 .env 改**，改后需重启；到点后所有启用的任务依次执行 |
| `AGENTFLOW_MORNING_COMMAND` | 否 | 首个任务的**初始**指令（默认「根据我的 GitLab 提交记录生成昨天的工作日报」）。只在首次启动、任务表为空时用来建第一个任务，之后任务以面板为准 |
| `AGENTFLOW_DB` | 否 | SQLite 数据库路径（默认 `./data/agentflow.db`，相对路径自动锚定到项目根） |
| `AGENTFLOW_RETENTION_DAYS` | 否 | 历史保留天数，超期记录启动时清理（默认 30，0 = 不清理） |
| `AGENTFLOW_MAX_RUNS` | 否 | 历史条数上限（默认 1000，0 = 不限制） |
| `AGENTFLOW_DEPT` | 否 | 报告抬头部门名（默认 `中台研发部`） |
| `AGENTFLOW_CORRELATE_WINDOW_HOURS` | 否 | 变更关联（gitlab.changes）默认回溯故障前的小时数（默认 `48`，上限 168） |
| `SIGNOZ_MCP_URL` | 否 | SigNoz MCP 地址，用于链路分析工具（默认 `http://192.168.2.111:18000/mcp`，需内网可达） |
| `KUBOARD_URL` / `KUBOARD_USERNAME` / `KUBOARD_PASSWORD` | 否 | Kuboard 面板地址与账号，启用 `k8s.query` 运维排查工具（经其代理只读访问集群） |
| `KUBOARD_CLUSTER` | 否 | 默认操作的集群名（Kuboard 导入时的名称，默认 `dev`；完整列表可用 `k8s.query` 的 `clusters` 查询） |
| `SIGNOZ_TIME_RANGE` / `SIGNOZ_FALLBACK_RANGE` / `SIGNOZ_LOG_LIMIT` | 否 | 链路查询时间窗、查不到时的降级窗口、补查日志条数（默认 `24h` / `7d` / `10`；两天前的链路只有 `7d` 才查得到） |
| `AGENTFLOW_INCIDENT_KB` | 否 | 故障案例知识库目录（默认 `./docs/incidents`，相对路径自动锚定项目根） |
| `AGENTFLOW_ANALYSIS_MAX` | 否 | 链路分析记录保留条数上限（默认 `2000`，工作台 → 可观测 → 链路分析 面板的数据） |
| `AGENTFLOW_ALARM_ENABLED` | 否 | 告警自动值守总开关（默认 `true`）。关闭后 webhook 仍可接收但不会触发排查 |
| `AGENTFLOW_ALARM_TOKEN` | 否 | 值守入口的共享令牌。该端点能触发真实排查与推送，暴露到内网以外时务必配置；请求头 `X-AgentFlow-Token` 或 `?token=` 均可，留空则不校验 |
| `AGENTFLOW_ALARM_TIMEOUT_MS` | 否 | 单次自动排查的超时毫秒数（默认 `180000`），超时按当前进度收尾并推送 |
| `AGENTFLOW_ALARM_DEDUP_SECONDS` | 否 | 去重窗口秒数（默认 `600`）。同一告警（同名+同服务+同链路+同状态）在此窗口内只排查一次，防告警风暴 |
| `AGENTFLOW_ALARM_MAX_CONCURRENT` | 否 | 同时进行的排查数上限（默认 `2`），超出进队列，队列满则拒绝并落库 |
| `AGENTFLOW_ALARM_MAX_RECORDS` | 否 | 值守记录保留条数上限（默认 `1000`） |
| `AGENTFLOW_ALARM_COMMAND` | 否 | 自定义排查指令模板，占位符 `{traceId}` `{service}` `{alertName}` `{severity}` `{message}` `{title}`；留空按「有链路 → 有服务 → 都没有」自动选择 |
| `AGENTFLOW_LLM_STREAM_USAGE` | 否 | 流式响应是否向服务端请求精确 token 用量（默认 `false`）。部分兼容网关不认该参数会直接 400；关闭时按文本长度估算并标记为估算值 |
| `AGENTFLOW_LLM_PRICE_INPUT` / `AGENTFLOW_LLM_PRICE_OUTPUT` | 否 | 输入/输出单价（元/百万 token，默认 `0` = 不显示金额只显示 token）。不内置价目表：各家价格会变、网关常有折扣，写死的数字只会给出看似精确的错误结论 |
| `AGENTFLOW_LLM_USAGE_MAX` | 否 | LLM 调用埋点保留条数上限（默认 `20000`） |
| `AGENTFLOW_WECOM_ENABLED` / `AGENTFLOW_WECOM_CLI_CMD` / `AGENTFLOW_WECOM_TIMEOUT_MS` | 否 | 企微接入：总开关（默认 `true`）/ CLI 命令名或完整路径（默认 `wecom-cli`，Windows 自动解析 npm 平台二进制）/ 单次调用超时毫秒（默认 `60000`）。前置：部署机 `npm install -g @wecom/cli` + 终端 `wecom-cli auth init` 扫码 |
| `AGENTFLOW_DAILY_DOCID` / `AGENTFLOW_DAILY_SHEET_ID` / `AGENTFLOW_DAILY_SUBMITTER` | 否 | 一键工作日报（`wecom.daily` 工具）目标：智能表格 docid / 子表 ID / 提交人 userid（提交人字段对机器人身份只读，判重实际按记录创建者）。字段名默认「今日工作总结/明日工作计划/日报提交日期」，可用 `AGENTFLOW_DAILY_*_FIELD` 覆盖 |
| `AGENTFLOW_MCP_SERVER_ENABLED` / `AGENTFLOW_MCP_SERVER_TOKEN` | 否 | 对外 MCP 服务：总开关（默认 `true`）/ Bearer 令牌（**留空仅本机可访问**，内网/公网使用必须配置）；另有 `AGENTFLOW_MCP_SERVER_BLACKLIST`（不暴露的工具名前缀）与 `AGENTFLOW_MCP_SERVER_CALL_LOG_MAX`（调用记录上限，默认 500） |

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
| POST | `/api/signoz/report` | **一键故障报告**：body `{"traceId": "..."}`，聚合链路/变更/K8s/告警时间线生成 postmortem（含 LLM 归纳或模板拼装的 Markdown 全文）；链路分析面板「故障报告」按钮即此入口 |
| GET/POST/DELETE | `/api/dbprofiles` `/{name}` `/{name}/test` | 数据库连接管理（保存/删除/测试连接） |
| GET/POST/DELETE | `/api/servicemap` `/{service}` `/search?keyword=` | 服务映射管理（服务名↔GitLab 项目，供变更关联解析；search 为 GitLab 项目搜索） |
| GET/POST/DELETE | `/api/memory` `/{id}`、PUT `/api/memory/auto` | 长期记忆管理（列表/新增/删除/清空/自动提取开关） |
| POST/GET/DELETE | `/api/kb/files` `/{id}`、GET `/api/kb/search?q=` | 个人知识库（multipart 上传并索引 / 文件管理 / 详情含全部分块 / 检索预览） |
| GET | `/api/kb/vector` | 向量检索状态（是否启用、模型、已索引块数 / 总块数） |
| POST | `/api/kb/vector/rebuild` | 重建向量索引（补齐缺失向量；换过嵌入模型自动先清空旧向量） |
| POST | `/api/hooks/alarm` | **告警值守入口**：接收任意监控平台的告警载荷（SigNoz/Alertmanager/纯文本均可），自动排查并推送结论。令牌用请求头 `X-AgentFlow-Token` 或 `?token=`；响应 `action` 说明是开始排查 / 去重跳过 / 已恢复跳过 |
| GET | `/api/hooks/alarm/status` | 值守配置与记录汇总（开关、令牌、去重窗口、并发、按状态计数、Top 服务） |
| GET/DELETE | `/api/hooks/alarm/records` `/{id}` | 值守记录列表（含结论全文）/ 删除单条 / 清空 |
| POST | `/api/hooks/alarm/simulate` | 模拟一条告警走完整链路（受理 → 排查 → 推送），上线前验证配置是否通 |
| GET/POST | `/api/mcp/servers` | MCP 服务列表（含已接入工具、最近刷新时间与失败原因）/ 新增或保存（`headers` 缺省表示沿用原请求头，便于只改地址） |
| POST/DELETE | `/api/mcp/servers/{name}/refresh` `/{name}` | 拉取该服务的工具清单并注册 / 删除服务（连同其工具） |
| PUT | `/api/mcp/servers/{name}/enabled` | 启用或停用（停用只摘工具，配置与缓存保留） |
| GET | `/api/llm/usage?hours=24` | LLM 成本与耗时看板：总量、按阶段/按模型分布、小时序列、任务花费排行、最近调用流水 |
| GET | `/api/llm/usage/tasks/{taskId}` | 单次任务的分阶段明细（各阶段 token 与耗时） |
| DELETE | `/api/llm/usage` | 清空全部调用埋点 |
| GET | `/api/tools` | 已注册工具列表（含动态工具、MCP 远端工具、写操作标记） |
| POST | `/api/tools/openapi` | 从 OpenAPI/Swagger 文档导入工具（body: `{"url": "..."}`） |
| DELETE | `/api/tools/{name}` | 删除动态工具（内置工具不可删） |
| GET | `/api/schedule/status` | 定时任务状态（总开关、执行时间、推送方式、任务列表及各任务归类的执行记录） |
| POST | `/api/schedule/enabled` | 切换**总开关**（body: `{"enabled": true}`，即点即生效；关闭后所有任务都不自动执行） |
| POST/PUT | `/api/schedule/tasks` `/{id}` | 新增任务／修改任务指令与开关（body: `{"command": "...", "enabled": true}`，指令上限 500 字且唯一） |
| PUT/DELETE | `/api/schedule/tasks/{id}/enabled` `/{id}` | 单个任务的自动执行开关／删除任务（`?withRuns=false` 可保留执行记录） |
| DELETE | `/api/schedule/runs?taskId=&createdAt=` | 删除单条执行记录 |
| POST | `/api/schedule/mode` | 修改推送方式（body: `{"mode": "text\|markdown\|file"}`，非法值落回 `text`） |
| GET/POST | `/api/notify/channels` | 推送通道列表（含脱敏地址与已选集合）/ 新增或保存（`url` 留空表示沿用原地址，`originalName` 非空表示改名） |
| DELETE | `/api/notify/channels/{name}` | 删除通道（自动从已选集合中摘除） |
| PUT | `/api/notify/selected` | 多选：整体提交选中集合（body: `{"names": ["通道A","通道B"]}`） |
| POST | `/api/notify/channels/{name}/test` | 单通道测试推送（立即实发一条，验证地址是否有效） |
| POST | `/api/schedule/run-now` | 立即试跑（body 可带 `{"taskId": 1}` 指定任务，不带则跑第一个；同步返回，约 1 分钟） |
| GET | `/api/agent/scenarios` / `/api/agent/info` | 示例建议 / 服务信息 |
| GET | `/api/wecom/status` | 企微接入状态：CLI 版本 / 授权状态 / 能力清单（按服务分组）/ 最近刷新与失败原因 |
| POST | `/api/wecom/capabilities/refresh` / `/api/wecom/auth/check` | 重新解析 wecom-cli 帮助刷新能力清单 / 立即重查扫码授权状态 |
| POST | `/api/mcp/server` | **对外 MCP 服务端点**（JSON-RPC：initialize / tools/list / tools/call）；鉴权 `Authorization: Bearer <令牌>`，未配令牌仅本机 |
| GET/DELETE | `/api/mcp/expose/status` / `/api/mcp/expose/calls` | 对外服务面板：端点与令牌状态、暴露工具数、最近调用记录 / 清空记录 |

SSE 事件：`status` / `phase` / `intent` / `plan` / `plan-proposal`（计划提案，等待确认）/ `plan-confirmed` / `step` / `step-state` / `tool` / `reason` / `result-delta`（流式增量）/ `result` / `done`；每个事件携带 `seq` 序号供断线续传。

### 接入企微智能机器人（在企微里用 AgentFlow）

1. 部署机配置令牌：`.env` 设 `AGENTFLOW_MCP_SERVER_TOKEN=<自定令牌>` 后重启（不配则端点只允许本机）；
2. 打开 工作台 → 工具与自动化 → 对外服务，复制接入地址（`http://<部署机>:8888/api/mcp/server`）；
3. 企微管理后台 → 智能机器人 → MCP 配置，填入地址与请求头 `Authorization: Bearer <令牌>`；
4. 在企微里与机器人对话即可调用 AgentFlow 的只读工具（查链路、巡检、知识库检索等）；写操作会被拒绝并提示到界面执行。

### 接入告警自动值守

在监控平台建一个自定义 webhook，指向 `http://<部署机>:8888/api/hooks/alarm`（若配了 `AGENTFLOW_ALARM_TOKEN`，再带上请求头 `X-AgentFlow-Token`）。载荷结构不限，解析器按字段名归一化查找，认得 `alertname` / `service_name` / `severity` / `status` 等常见写法，并从整段报文里正则提取 32 位十六进制 trace ID。手动验证：

```bash
curl -X POST http://localhost:8888/api/hooks/alarm \
  -H 'Content-Type: application/json' \
  -d '{"alertName":"支付服务错误率升高","severity":"critical","status":"firing",
       "labels":{"service_name":"pay-service"},
       "annotations":{"summary":"5xx 比例超过 5%","description":"链路 c4ea16342cf1a0526d22fa20d57c9e2a"}}'
# => {"accepted":true,"action":"investigating","recordId":1,"command":"分析链路 c4ea… 再用 gitlab.changes 关联…"}
```

`action` 取值说明：`investigating` 已受理并后台排查；`deduped` 去重窗口内重复上报，不重复排查；`skipped` 已恢复的告警只留痕不排查；`disabled` 值守已关闭；`invalid` 载荷为空；`rejected` 排查队列已满。注意请求体必须是 **UTF-8**（中文告警名按 UTF-8 解析）。

## 运行测试

```bash
cd backend && ./mvnw test    # 287 个单测：编排引擎辅助逻辑（含 @ 工具点名解析）/ 工具与动态工具 / MCP 客户端与注册 /
                            # 告警解析去重 / 通知与推送形态 / 定时任务 / 链路分析与故障报告 /
                            # 健康巡检分级 / 知识库与向量检索 / 记忆 / LLM 埋点
```

## 配置

服务端口等配置见 `backend/src/main/resources/application.yml`，默认 **8888**（可用环境变量 `AGENTFLOW_PORT` 覆盖）；前端开发端口见 `frontend/vite.config.js`（默认 **5173**）。
