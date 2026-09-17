# AgentFlow 学习文档：从架构广度到实现精度

> 面向想读懂、二次开发或借鉴本项目的工程师。前半部分讲「广度」——整体架构、技术选型与取舍；后半部分讲「精度」——每个核心机制与功能如何实现，精确到类、方法与关键分支。
> 配套阅读：[README.md](../README.md)（使用视角）、`docs/incidents/`（故障知识库格式）。

---

## 目录

1. [项目定位与设计哲学](#1-项目定位与设计哲学)
2. [总体架构](#2-总体架构)
3. [技术选型与取舍](#3-技术选型与取舍)
4. [核心机制精读](#4-核心机制精读)
   - 4.1 编排引擎 AgentEngine
   - 4.2 SSE 事件协议与断线续传
   - 4.3 LLM 客户端（双协议 + 多档案）
   - 4.4 工具体系（Tool SPI）
   - 4.5 存储层（一库多表）
5. [功能实现详解](#5-功能实现详解)
   - 5.1 GitLab 查询与工作报告
   - 5.2 数据库透视 db-architect（Java↔Python 桥接）
   - 5.3 SigNoz 链路分析 + 故障案例知识库
   - 5.4 变更关联（谁改坏的）
   - 5.5 K8s 运维排查
   - 5.6 长期记忆
   - 5.7 晨报机器人与通知
   - 5.8 效能热力图
   - 5.9 OpenAPI 动态工具
6. [前端架构](#6-前端架构)
7. [配置系统全景](#7-配置系统全景)
8. [测试策略](#8-测试策略)
9. [设计权衡备忘](#9-设计权衡备忘)
10. [扩展指南](#10-扩展指南)

---

## 1. 项目定位与设计哲学

AgentFlow 是一个**单机部署、面向个人/小团队的 AI 运维工作台**：用一句自然语言驱动 Agent 完成意图分析 → 任务拆解 → 工具调用 → 结果汇总，全程 SSE 流式推送。它不是一个通用聊天机器人，而是把内网基础设施（GitLab、数据库、SigNoz、K8s）包成 Agent 可调用的工具，形成「对话式驾驶舱」。

四条贯穿全项目的设计哲学，理解它们比理解代码更重要：

1. **无 LLM 也能跑**（模拟模式）：所有 LLM 参与点都有确定性兜底——启发式规划、模板生成、真实数据 API 照常工作，只是内容标注「模拟模式」。这让项目在没配 Key 时可演示、可测试，也让每个 LLM 调用点的失败都有退路。
2. **工具只读为默认，写操作必须人工放行**：`Tool.requiresConfirm()` 返回 true 的工具（GitLab 写、案例归档）无论什么模式都强制走计划确认流程。
3. **诚实降级，不编造**：工具查不到就返回 note 型提示（带真实原因与建议），绝不返回编造数据；前端用统一提示卡展示。降级信息会流入后续 LLM prompt，让模型知道「没查到」而不是「查到了个空的」。
4. **一切都是事件，事件即历史**：每次运行的全部 SSE 事件先落 SQLite 再推送，历史回放就是「把存量事件按序重放进同一套渲染管线」。

---

## 2. 总体架构

### 2.1 分层图

```
┌────────────────────────────────────────────────────────────┐
│ 前端 Vue 3 SPA（Vite 构建，产物托管进 Spring Boot static） │
│  App.vue（对话壳） ├ AgentRun（单轮执行面板） ├ StepCard    │
│  ├ ResultCard / ClarifyCard / PlanApproval（交互卡）        │
│  ├ Composer（输入区） └ WorkbenchModal（工作台 9 面板）      │
│  useAgent.js：SSE 消费 + 断线续传 + 历史回放 + 多对话       │
└──────────────────────── /api/*（REST + SSE）───────────────┘
┌────────────────────────────────────────────────────────────┐
│ Spring Boot 3 后端                                          │
│  controller/  AgentController(run/stream/cancel/history)   │
│               ServiceMap / Memory / LlmConfig / Signoz ... │
│  engine/      AgentEngine（编排） RunSession（会话/取消）   │
│               RunStore（runs+events） MemoryStore（记忆）   │
│  llm/         LlmClient（OpenAI/Anthropic 双协议）          │
│  tool/        Tool SPI：ToolRegistry + 内置/动态工具        │
│  signoz/ db/ k8s/ schedule/ notify/   领域包               │
└────────────────────────────────────────────────────────────┘
┌────────────────────────── 外部系统 ────────────────────────┐
│ DeepSeek 等 LLM │ GitLab API │ 数据库(JDBC/Python) │        │
│ SigNoz MCP │ Kuboard(K8s 代理) │ 企微/钉钉 Webhook         │
└────────────────────────────────────────────────────────────┘
```

### 2.2 一次任务的完整生命周期

```
用户输入
  → POST /api/agent/run（带最近 5 轮历史 + sessionId + 模式）
  → AgentEngine.start()：建 RunSession、落 runs 表、线程池提交 orchestrate()
  → orchestrate()（后台线程，与前端连接无关）：
      phase understand   意图（LLM 或启发式）
      phase plan         规划（LLM plan / react 跳过 / hybrid=plan）
                        └ confirm 模式或含写操作 → plan-proposal 事件 → 阻塞等确认(10min)
      phase execute      逐步执行（串行 / 同 group 并行≤3 / react 逐轮 / hybrid 受阻转 react）
                        └ 每步：step → step-state → tool → result（write 步 result-delta 流式）
                        └ 工具返回 clarify → 推候选卡 → 本轮收尾暂停
      phase merge        mergeFinal：write 步已有产出则原文复用；否则 LLM 汇总/模板
      done               事件 + 异步记忆提取
  前端：fetch SSE /api/agent/stream/{taskId}?afterSeq=N 增量消费；断线重连带 lastSeq 续读
```

关键点：**执行与订阅解耦**。任务提交即后台运行，前端断线、关页面都不影响执行；重连时用 `afterSeq` 只补发缺失事件（`RunRecorder.send` 先写 SQLite 再推 SSE，seq 天然单调）。

### 2.3 SQLite 单库多表（./data/agentflow.db）

| 表 | 归属 | 内容 |
|---|---|---|
| `runs` / `events` | RunStore | 任务元数据（含 session_id 分组）/ 全量事件流（seq 有索引） |
| `llm_config` `llm_profiles` `llm_active` | LlmConfigStore | 模型多档案（双协议），运行时可切换 |
| `gitlab_accounts(_meta)` | GitLabAccountStore | 多 GitLab Token + 默认账户（token 内存缓存，改即生效） |
| `db_profiles(_meta)` | DbProfileStore | 数据库连接（密码落库，列表接口脱敏） |
| `service_project_map` | ServiceProjectStore | 服务名→GitLab 项目映射（变更关联用） |
| `agent_memory(_meta)` | MemoryStore | 长期记忆 + 自动提取开关 |
| `trace_analysis` | TraceAnalysisStore | 链路分析记录（按 trace_id 去重累加次数） |
| `schedule_tasks` / `schedule_runs(_meta)` | ScheduleStore | 定时任务（一条指令一个任务，各自开关）+ 执行记录（带 command 列，按任务归类）+ 总开关与推送形态 |
| `notify_channels(_meta)` | NotifyChannelStore | 推送通道（多通道，含脱敏前的完整 Webhook）+ 多选集合 |
| 动态工具 | ToolStore | OpenAPI 导入的工具定义 |

所有 Store 共用 `agentflow.storage.path`，路径经 `StoragePaths.resolve()` 锚定项目根（向上找 `.env`/`.git`，解决 IDE 工作目录不一致问题）。各自 `@PostConstruct` 建表——没有统一 migration，因为单机 SQLite 演进成本可控。

---

## 3. 技术选型与取舍

| 维度 | 选型 | 理由与放弃项 |
|---|---|---|
| 前端框架 | Vue 3 组合式 API + Vite，**无 UI 组件库** | 对话流界面高度定制（气泡/时间线/折叠卡），组件库反而碍事；`style.css` 自建薄荷青绿设计系统（CSS 变量主题）。放弃 React：单人项目选心智负担最低的 |
| 前后端通信 | **SSE**（fetch 流式读取） | 单向推送足够；放弃 WebSocket：不需要客户端上行流，SSE 走普通 HTTP 更简单、代理友好 |
| 后端 | Spring Boot 3.5 / Java 17 | 团队栈；`spring-boot-starter-web` 一个依赖搞定 REST + SseEmitter |
| 构建 | Maven Wrapper + frontend-maven-plugin | **本机零预装**（JDK 除外）：自动下载 Maven、Node、npm，构建前端进 static 随 jar 启动。对「双击 start.bat 就能跑」至关重要 |
| 存储 | **SQLite**（纯 JDBC，无 ORM） | 单机单用户，零运维；所有领域各自建表，手写 SQL + `PRAGMA busy_timeout=5000`。放弃 MySQL/H2：前者要装服务，后者混合模式兼容性差 |
| LLM | DeepSeek（OpenAI 兼容）+ Anthropic 协议双支持 | 协议抽象在 `LlmClient.provider` 字段；多档案存 SQLite 运行时切换。key 缺失自动进模拟模式而非拒绝启动 |
| LLM 输出 | JSON mode（`response_format`）+ 容错解析 | 规划/决策要结构化；`readJsonObject()` 取首 `{` 到末 `}` 的子串，容忍模型包裹废话 |
| 工具协议 | 自定义 `Tool` SPI；SigNoz 走 **MCP**（Streamable HTTP JSON-RPC） | 内部工具不需要 MCP 的通用性，一个接口三个方法最轻；SigNoz 已有 MCP server 就直接消费 |
| K8s 访问 | 经 **Kuboard** 的 `/k8s-api` 代理，只读 | 不在集群内、不想管 kubeconfig 分发；Kuboard 天然是带鉴权的代理层 |
| 数据库透视 | **Java 管连接配置，Python 执行扫描**（子进程） | 复用成熟的 db-architect Python 脚本（jaydebeapi 多方言含达梦）；每次调用生成临时 .env 传密码，跑完即删——密码不进命令行不残留 |
| 进程模型 | 任务级线程池 + RunSession 引用隔离 | 无队列/消息中间件：单机场景 `Executors.newCachedThreadPool()` 足够，会话状态在 `ConcurrentHashMap<taskId, RunSession>` |

---

## 4. 核心机制精读

### 4.1 编排引擎 AgentEngine（~1400 行，项目心脏）

位置：`backend/src/main/java/com/agentflow/engine/AgentEngine.java`

#### 主流程 `orchestrate(RunSession)`

一个方法串起四阶段，所有状态变化都通过内部类 `RunRecorder.send(event, data)` 发事件（先落库后推送）。要点：

- `histBlock = memoryBlock() + historyBlock(session.history())`——**长期记忆 + 最近 5 轮对话**拼成上下文块，注入后续所有 LLM prompt；
- 记忆快速通道在 try 之前：`parseMemoryCommand()` 识别「记住/忘记 XXX」→ 直接操作 MemoryStore → done 收尾返回，**不规划不花 LLM**；
- 取消/异常路径引用 try 外的 `writeOutput` 局部变量，保证收尾时能带上已产出的部分内容。

#### 三种执行模式

| 模式 | 入口条件 | 行为 |
|---|---|---|
| `plan`（默认） | — | `plan()` → LLM 规划（GitLab 报告类专线走启发式，因为 LLM 会编造日期参数）→ 逐步执行 |
| `react` | mode=react 且 LLM 可用 | 跳过规划，`runReactLoop()` 逐轮问 LLM「tool/write/finish」，上限 8 步 |
| `hybrid` | mode=hybrid 且 LLM 可用 | 先按 plan 执行；任一工具步返回 note 型失败（`isStepFailed()`：resultType=json 且含 note 键且无 clarify）→ 发 reason+status 事件 → 放弃剩余计划 → `runReactLoop()` 预算 4 步接续 |

`runReactLoop()` 是 react 与 hybrid 共用的循环，参数 `startIdx` 让 hybrid 的步骤编号衔接计划已完成的部分；循环结束仍无交付物时兜底补一个 write 步。

三条刻意边界：clarify 候选优先于 hybrid 切换（点选重跑确定性更高）；报告类任务不切换（格式固定，模板兜底即可）；LLM 不可用时 hybrid 退化为 plan。

#### 步骤执行 `executeStep()`

按 `PlanStep.kind()` 三分支：

- **tool**：`toolRegistry.execute()` → 失败/未知工具统一转 `ToolResult.note()` → 结果摘要（1600 字截断）存入 `toolResults` map（key=`工具名#步骤号`，同名工具多次调用不覆盖）→ 发 `result` 事件 → 若带 clarify 则暂停本轮；
- **think**：LLM 按 `THINK_SYSTEM` 的固定要点格式（目标拆解/关键要素/数据解读/思路选择/结论）生成分析行，逐行发 `reason` 事件；失败用 `heuristicThinkLines()` 兜底；
- **write**：报告类用专用 `reportWriteSystem()`（时间窗、星期对照、纯文本规则），其余用 `WRITE_SYSTEM`；`chatStream` 流式生成，增量经 `recorder.streamDelta()` 缓冲（≥16 字符 flush）发 `result-delta`；流式失败退非流式，再失败退模板。取消信号在流回调里抛 `CancelledException` 中断生成。

#### 并行执行

`parallelEnd()` 找「连续、tool、同 group>0」的区段 → `executeParallel()` 按 3 个一批 `invokeAll`。每个并行步用**独立的 partial toolResults**（避免并发写），完成后按序 merge 回主 map。`ExecutionException` 解包重抛 → 整个 run 走取消/异常收尾。

#### 计划确认（Human-in-the-loop）

`awaitPlanApproval()`：发 `plan-proposal`（含每步可编辑的 argsJson）→ `session.awaitConfirm()` 拿 `CompletableFuture` 阻塞最多 10 分钟 → 前端 `POST /run/{taskId}/confirm` 回传 → `session.confirm(steps)` 完成 future → 过滤 skip 步继续。触发条件：请求 `mode=confirm` **或** 计划含 `requiresConfirm()` 工具（写操作强制，与模式无关）。

#### 失败处理全景（现状）

工具抛异常→吞掉转 note；note 型结果→照常流入后续 prompt；clarify→暂停本轮等点选；取消→协作式检查点（步骤边界/流回调）抛 CancelledException。**没有重试**——hybrid 模式本质上是「用 LLM 智能重路由」替代「机械重试」。

### 4.2 SSE 事件协议与断线续传

事件目录（每个都带 `seq`）：

| 事件 | 载荷 | 说明 |
|---|---|---|
| `phase` | name: understand/plan/execute/merge, state: active/done | 四阶段进度条 |
| `status` | text, cls | 顶行状态文字 |
| `intent` | summary, entities[] | 意图卡 |
| `step` / `step-state` | index/total/kind/tag/title；state: running/done | 步骤时间线 |
| `tool` | index, name, args | 工具调用参数 |
| `reason` | index, line | think 步推理行（前端打字机） |
| `result` / `result-delta` | resultType/result/list；delta | 步骤结果 / write 步流式增量 |
| `clarify` | question, options[{label,action}] | 候选反问卡 |
| `plan-proposal` / `plan-confirmed` | steps / total | 计划确认 |
| `done` | summary, output, meta[], cancelled? | 收尾卡 |

**断线续传**：前端 `consumeWithResume()` 捕获流异常后重试 3 次，每次带 `run.lastSeq`；后端 `engine.stream(taskId, afterSeq)` 从 events 表补发 `seq > afterSeq` 的部分，已结束的任务补发完自然收流。**历史回放**：`replayRun()`/`openSession()` 把存量事件灌进与实时消费**同一套** `handlersFor(run, instant)` 处理器（instant=true 跳过打字动画），保证回放与实况像素级一致。

### 4.3 LlmClient（双协议 + 多档案）

- `provider=openai`：`/chat/completions`，Bearer 头，JSON mode 用 `response_format:{type:"json_object"}`；
- `provider=anthropic`：`/v1/messages`，`x-api-key` + `anthropic-version`，JSON mode 退化为 system 追加「只输出 JSON」；
- 配置全部 `volatile`，`LlmConfigController.applyStored()` 启动时应用 SQLite 活动档案，无则回退 `.env`——**运行时切换模型不重启**；
- 方法面：`chat`（0.9 温度创作）/ `reason`（带思维链）/ `chatJson`（0.2 温度结构化）/ `chatStream`（SSE 增量，两个协议分别实现；零内容抛错，中途异常带部分内容返回）；
- 非流式走 RestClient、流式走裸 HttpClient，**共用同一个 HttpClient 实例**保证代理配置一致。

### 4.4 工具体系（Tool SPI）

```java
public interface Tool {
    String name();                  // 注册键，如 gitlab.query
    String description();           // 动态！注入规划 prompt（DbArchitectTool 用它列出已配置连接）
    String argsHint();              // 自由文本参数说明（非 JSON Schema）
    ToolResult execute(Map<String,Object> args, String userCommand);
    default boolean requiresConfirm() { return false; }
}
```

- **注册**：`@Component implements Tool` 即被 `ToolRegistry`（构造注入 `List<Tool>`）收集；运行期 `register/unregister` 支撑动态工具；
- **prompt 注入**：`describeForPrompt()` 拼成 `- name（description）参数 argsHint` 清单进规划/ReAct 模板。description 动态生成是关键设计——工具可以把「当前配置状态」（有哪些连接、哪些集群、知识库几条案例）直接告诉规划器，避免无效调用；
- **结果**：`ToolResult(resultType, result, list, summary, clarify)`。resultType 是前端渲染判别器（list/copy/changes/json-note）；clarify 是候选反问协议（K8s、变更关联共用）；
- **兜底链**：工具内部 args 缺失时从 `userCommand` 正则抽取（`resolveTraceId`、`inferAction` 等）——LLM 参数不完美也能跑。

### 4.5 存储层

无 ORM、无连接池（SQLite 单文件，`DriverManager.getConnection` + busy_timeout 足够）。每个 Store 三件套：`@PostConstruct` 建表 / CRUD 静默失败（log.warn 返回空，查询类故障不炸业务）/ 敏感字段列表接口脱敏（token 留末 4 位、密码不回传）。保留策略集中在 RunStore 启动清理（天数 + 条数上限），领域表各自封顶（分析记录 2000、记忆 40）。

---

## 5. 功能实现详解

### 5.1 GitLab 查询与工作报告

**类**：`GitLabTool`（只读 `gitlab.query`）、`GitLabWriteTool`（`gitlab.action`，requiresConfirm）、`GitLabAccountStore`。

- **Token 解析链**：账户库活动 token → `.env` GITLAB_TOKEN；token 身份由 `/api/v4/user` 实时解析（账户名只是显示用）；
- **时间窗解析**是重头戏：`parseWindow()` 支持今天/昨天/本周/上周/近 N 天周/「8月1日至8月15日」等中文时间词，返回 `Window(since, until, scopeZh)`；跨度封顶 186 天。**GitLab 的 `after` 参数是严格晚于**，所以拉事件时多退一天、边界自己卡——这是踩过坑的注释级经验；
- **日报/周报素材**：`listMineCommits(Window)` 先拉 token 用户的 push 事件圈出候选项目（分页），再逐项目 `/repository/commits?since&until` 拉明细按作者过滤（作者名或邮箱匹配），明细为空回退 push 事件概要——两层兜底；
- **报告成稿**：`AgentEngine.executeStep` write 分支检测 `detectReportKind()` → 专用 prompt（星期对照、纯文本、按天分点）→ 素材为空（无「共提交」字样）直接走 `templateReport()` 模板，**不让 LLM 自由发挥破坏固定格式**；
- **规划专线**：`plan()` 里报告类跳过 LLM 规划直接启发式——注释写明原因「LLM 会自行编造日期参数，曾把年份写错」。这是全项目「确定性优先」哲学的典型例子。

### 5.2 数据库透视 db-architect

**类**：`DbArchitectTool`（`db.inspect`）、`DbScannerRunner`（进程桥）、`DbProfileStore`；脚本在 `tools/db-architect/scripts/db_scanner.py`。

- Java 侧不连数据库！`DbScannerRunner.run()` 每次调用：把界面管理的连接写成**临时 .env**（`DB_<NAME>_TYPE/HOST/PORT/...` + 默认连接 `DB_PROFILE`）→ `ProcessBuilder` 起子进程 `python db_scanner.py <args> --env <临时文件> --output <临时目录>` → 守护线程读输出（120K 截断）→ 150s 超时强杀 → 取输出目录**最大的 .md** 作为主报告（9K 截断）→ finally 删临时文件和目录（密码不落命令行、不留盘）；
- **列名自愈**：export-row 的 where 条件用错列名时，工具解析报错给出正确列名并提示重试（`DbArchitectToolHealTest` 覆盖）；
- description 动态列出全部连接与环境标签，规划器据它选 profile；用户不点名时用默认连接。

### 5.3 SigNoz 链路分析 + 故障案例知识库

**类**：`SigNozTraceTool`（`signoz.trace`）、`TraceFetcher`、`TraceDigest`、`IncidentKb`、`SigNozCaseTool`（`signoz.case`，requiresConfirm）、`TraceAnalysisStore`。

一次 `signoz.trace` 的完整流水：

1. **取数**：`TraceFetcher.fetch()` 经 MCP（`SigNozMcpClient` 无状态 JSON-RPC over HTTP）调 `signoz_get_trace_details`；24h 查不到自动扩 7d，服务端提示（trace 真实时间段）原样透出；
2. **指纹**：`TraceDigest.fingerprint(spans)` = errorClass（最深异常类）+ signature（MyBatis 方法名 → SQL 表 → span 位置的优先级提取）+ status（含「HTTP 200 但业务失败」矛盾检测）+ keywords；
3. **KB 匹配**：`IncidentKb.match()` 多信号打分——签名相似 +3 / 特定异常 +2 / 同失败 span +2 / 关键词重叠每 +1 封顶 2 / 服务重叠 +0.5；STRONG≥3、MEDIUM≥2；**无服务重叠最高 WEAK**（防误报护栏）。命中即把历史根因与处置注入摘要；
4. **渲染**：`TraceDigest.render()` 根因优先排序：结论 → 判定 → KB 命中段 → 失败传播链（`pathToRoot`）→ 源头异常 → 慢 span Top5 → ERROR/WARN 日志（失败链路才补查）→ span 清单（封顶 40 行）；
5. **落库**：`TraceAnalysisStore.record()` 按 trace_id upsert（重复分析累加 analyze_count）；
6. **结果 map** 含 `services`、`traceTime`——专为下一步 `gitlab.changes` 的衔接准备。

**归档**（`signoz.case`，需确认）：重新拉 trace 程序化取指纹（人只填判断类字段），`IncidentKb.archive()` 命中已有案例则幂等累加（指纹永不重写，正文只追加关联行）；新建则按 `_template.md` 生成 frontmatter + 章节文件，重写 `index.yaml`。

### 5.4 变更关联（gitlab.changes）

**类**：`ChangeCorrelationTool`、`ServiceProjectStore`、`ServiceMapController`；前端 `ResultCard` 的 `changes` 分支 + `ServiceMapPane`。

- **输入两路**：传 `traceId`（工具自己经 TraceFetcher 解析涉及服务/故障时间/失败点服务，不依赖 LLM 传参）或 `services + time` 独立使用；
- **映射三层解析**：显式 mapping 参数（`svc=group/proj`，收到即固化进映射表）→ 映射表已配置 → 按服务名搜 GitLab（唯一精确命中才标「推断」采用）；歧义/未命中 → **复用 Clarify 候选卡**反问，点选后带 mapping 重跑并固化——越用越准；
- **拉数**：逐项目 `fetchProjectCommits(since, until)` + `fetchPipelines`（窗口内 failed/canceled 记为流水线失败信号）；
- **评分**（静态方法 `scoreCommit`，纯函数可单测）：距故障 <6h+3/<24h+2/<48h+1；fix/修复/hotfix/revert 类 +2、perf/优化/SQL 类 +1；失败点服务 +2/其他涉及服务 +1；流水线失败 +1。**每条附理由标签**，前端渲染成 chips——可解释性是一等公民；
- **衔接**：启发式规划里链路分析专线自动追加变更关联步；LLM 模式靠 `signoz.trace` description 里的显式提示（「分析完成后应紧接着用 gitlab.changes 传同一个 traceId」）引导规划器。

### 5.5 K8s 运维排查（k8s.query）

**类**：`K8sTool`、`KuboardClient`。经 Kuboard `/k8s-api` 代理只读访问：Pod/Deployment/Service/Job 列表（CrashLoop/Pending/未就绪自动标 ⚠）、Pod 日志（含崩溃前 previous 日志、关键词过滤）、Warning 事件、节点状态、跨命名空间按服务名前缀反查实例。

两个值得学的实现：

- **资源名未命中澄清**：`buildResourceClarify()` 全集群模糊找相近工作负载（Pod 名先 `workloadBase()` 剥掉 ReplicaSet 哈希/StatefulSet 序号归并），相似度 = 子串命中 100 否则编辑距离折算，阈值 45，Top4 出候选卡；
- **指令兜底**：`inferAction/inferNamespace` 从用户指令正则抽取，LLM 参数缺失时不至于卡死。

### 5.6 长期记忆

**类**：`MemoryStore`、`AgentEngine` 的记忆段、`MemoryController`；前端 `MemoryPane`。

- **注入**：`memoryBlock()`（最多 20 条）+ `historyBlock()` 拼接，进规划/ReAct/think/write/merge 五处 prompt；块头声明「与当前指令冲突时以当前指令为准」；
- **显式快速通道**：`parseMemoryCommand()` 正则识别句首「记住：/忘记：/删除记忆：」→ `handleMemoryCommand()` 直接增删（忘记按子串模糊删，可能多条，逐条汇报）→ done 收尾。确定性、零 LLM、模拟模式可用；
- **自动提取**：`scheduleMemoryExtraction()` 在 run 成功收尾后 `executor.submit` 异步——`EXTRACT_SYSTEM` prompt（输入本轮指令+产出+现有记忆；输出 `{add[], remove[]}`，每条 ≤80 字最多 3 条，remove 仅限明确否定）→ `chatJson` 解析应用。开关存 meta 表，面板可关；
- **总开关** `agentflow.memory.enabled`：关则不注入不提取，显式指令也拦截。

### 5.7 定时任务与通知

**类**：`ScheduleService`、`ScheduleStore`、`NotifyService`、`NotifyChannelStore`。Spring `@Scheduled`（cron 在 .env，全局一个）→ 总开关开着就遍历所有<b>已启用</b>的任务 → 每个任务复用 `engine.executeHeadless()` 无界面跑它的指令 → 产物按推送形态发给所有勾选的通道 → 执行记录落 `schedule_runs`。headless 与正常 start 走同一条 orchestrate 管线——**没有为定时任务开小灶**，这保证了行为一致性。

**任务模型**：一个任务 = 一条指令（`schedule_tasks.command` 唯一），执行记录表加了 `command` 列作为**归类键**，所以界面上的「最近执行」是分组在每个任务下的，每个分类能单独开关、单独删除。生效条件是<b>总开关 && 该任务开关</b>：总开关沿用老的单任务时代语义作全局闸门，任务开关让你能停掉某一条而不删掉它。删除任务默认连它的执行记录一起删；改指令时会把记录的 `command` 一起迁移，否则老记录会变成没人认领的孤儿。

**两个容易搞错的地方**：一，`schedule_runs.task_id` 存的是 **AgentEngine 的任务 UUID**，不是 `schedule_tasks` 的主键 ID，所以按任务删记录只能靠 `command`，不能用 `task_id` 关联（写错过一次）。二，`ScheduleStore.init()` 里有一段幂等加列（查 `PRAGMA table_info` 缺了才 `ALTER`）和 `ScheduleService.@PostConstruct` 的迁移：老库升级前只有一条全局指令、记录里没有 `command`，首次启动会用它建出第一个任务并**回填**历史记录——那时确实只跑这一条，回填是准确的，不回填这些记录就成了无归属的孤儿。

**推送**：`NotifyChannelStore`（`notify_channels` 表）存多条通道，「选中哪些」作为**集合**存在 meta 的 `selected` 键里（JSON 数组），与通道本体分开——改 URL 不影响勾选状态，删通道/改名能精确地把选中项一起摘掉或跟随。`NotifyService` 遍历生效通道逐个推送，**任一通道成功即算推送成功**（多通道下「部分成功」不该记成失败）。生效目标是「界面配了 → 用勾选的；界面一条都没配 → 回退 .env」，注意**界面配了但一个都没勾选时不回退 .env**，否则「取消勾选」这个动作会失效。@ 人的字段位置两种机器人不同（企微在 `text.mentioned_mobile_list`，钉钉在顶层 `at`），放错不会报错、只会安静地不 @ 到人，所以两种类型都有单测。

**推送形态**由 `NotifyMode`（text/markdown/file）表达，存在 `schedule_meta` 的 `notify_mode`，面板下拉即改即生效。三档对应群机器人的三种能力上限：text 2048 字节（静默截断）、markdown 4096、file 走附件不受正文限制。两条刻意的约束：**告警强制 text**——@ 值班人只有文本消息支持得可靠，而告警卡片本来就该短；**file 模式发两条**——先发摘要文本再发附件，因为 file 消息本身带不了文字说明。附件走 `webhook/upload_media` 拿 `media_id` 再发（3 天有效，现传现发不缓存），上传地址由 send 地址推导。

两处踩过的坑记在代码注释里：一，**别用 `MultipartBodyBuilder`**——它在 spring-web 里但会引用 `reactivestreams.Publisher`，本项目只有 spring-web，类加载直接 `NoClassDefFoundError`；改用 `MultiValueMap<String,Object>` + `ByteArrayResource`（覆写 `getFilename()`）。二，`NotifyService.push()` 的每通道循环**捕获 `Throwable` 而非 `Exception`**——上面那个 `NoClassDefFoundError` 是 `Error`，逃出去后把整个任务打成 500、执行记录都没落库。推送是任务的收尾动作，这里是「通知边界」，绝不能让推送反过来打挂任务。

### 5.8 效能热力图

`StatsController.heatmap` → `GitLabTool.dailyCommitCounts(days)`。关键口径修正（代码注释里有实测数据）：**不能累加 push 事件的 commit_count**（分支同步会一次带上大量他人提交，实测 193 vs 页面 57），改为按 GitLab 贡献日历口径「每事件计 1」。结果按 token 指纹缓存 10 分钟——多账户切换缓存自动失效。

### 5.9 OpenAPI 动态工具

`ToolsController` POST `/api/tools/openapi {url}` → `OpenApiImporter` 解析 Swagger → 每个 GET 接口生成一个 `DynamicTool`（路径参数/查询参数映射到 args）→ `ToolStore` 持久化 + `ToolRegistry.register()`，重启由 `DynamicToolLoader` 重放。前端「工具管理」面板可删（内置不可删）。

### 5.10 个人知识库（RAG）

**类**：`kb/KbStore`（kb_files/kb_chunks 表）、`kb/KbChunker`（分块纯函数）、`kb/KbTextExtractor`（多格式解析）、`kb/KbSearchTool`（`kb.query`）、`controller/KbController`；前端 Composer 📎 + `KbPane` + ResultCard `kb` 分支。

流水线：上传（multipart，≤20MB）→ 落盘 `./data/kb-files`（原名+时间戳防覆盖）→ `KbTextExtractor` 按扩展名抽取纯文本（PDFBox/POI；旧版 doc/xls 报可读错误引导转存）→ `KbChunker` 分块（空行/Markdown 标题切边界，~600 字/块 + 80 字重叠保证跨块句子可命中）→ 入库。同名重传 = 整体替换。

**检索没有用 embedding**（DeepSeek 仅对话接口，且个人规模无需向量库）：`KbSearchTool.tokenize()` 把查询切成中文 2-gram + 西文小写词（停用词表剔除「知识库/怎么」类无区分度词），块得分 = `Σ 词频 × log(1 + 总块数/含词块数)`（罕见词权重高），全量内存打分毫秒级取 Top5。工具三模式：search（默认）/list（清单）/read（读全文，即「只读浏览」能力）。description 动态注入文件清单引导规划器；启发式规划在指令提到知识库/资料/文档时直接加检索步（模拟模式同样生效）。作答链路：检索命中作为工具产物流入 write 步 prompt，要求注明来源文件。

---

## 6. 前端架构

```
App.vue（440 行对话壳：消息流 + 欢迎页 + 工作台挂载 + 计划确认/候选点选处理）
 ├ ChatHeader（品牌 / 模型选择器 / 状态）
 ├ AgentRun（单轮面板：阶段条 + 步骤时间线 + 收尾卡；processExpanded 完成后自动折叠）
 │   └ StepCard（步骤卡） ├ ResultCard（结果卡：list/copy/changes/note 四型）
 │                        ├ ClarifyCard（候选反问，点选 emit clarify-run → runAgent 重跑）
 │                        └ PlanApproval（计划确认：勾选跳过 / 编辑 argsJson）
 ├ Composer（输入区：自动增高 + 示例建议 + 确认模式开关 + 数据库快切）
 ├ WorkbenchModal（工作台：左侧 9 菜单右侧面板）
 │   TraceAnalysisPane / SettingsPane / DbDrawer / GitlabPane(账户+热力图) / ServiceMapPane
 │   MemoryPane / KbPane / ScheduleDrawer / ToolsDrawer / LlmPane
 └ useAgent.js（组合式函数：唯一状态源）
```

**useAgent.js 是前端心脏**（465 行）：runs 数组 = 对话消息模型；`handlersFor(run, instant)` 返回事件名→处理器的映射，**实时消费与历史回放共用**；手写 SSE 缓冲解析（`consumeSseBuffer` 按 `\n\n` 切帧）；断线重连 `consumeWithResume` 带 lastSeq；confirm 等待用 Promise resolver 挂起主循环、确认到达后续流。多对话模型：sessionId 存 localStorage，会话消息分页加载（打开取最近 20 条，「加载更早」以最早 run id 为游标往回翻）。

无路由、无状态库（Pinia）、无 UI 框架——一个组合式函数 + 十几个组件就是全部，这是刻意的极简。

---

## 7. 配置系统全景

三层优先级：**SQLite 运行时配置 > `.env`（启动脚本加载）> application.yml 默认值**。

- `.env`：进程级（LLM Key、GitLab、Kuboard、SigNoz MCP、cron…），改了要重启；启动脚本 `set -a; source .env; set +a` 加载；
- SQLite：用户级（模型档案、GitLab 账户、数据库连接、服务映射、记忆、动态工具），界面改即生效；
- `application.yml`：每个可调项都写成 `${ENV_VAR:默认值}` 形态——env 缺失用默认，默认值即文档。

配置项速查见 README 环境变量表。原则：**能界面化的配置不进 .env**（连接、账户、模型都做到了），只有「部署环境相关」的才留 env。

---

## 8. 测试策略

16 个测试类 79 用例，全部**无网络、无 Spring 上下文**的纯 JUnit 单测：

- **Store 测试**（`@TempDir` 独立 SQLite 文件）：持久化往返、脱敏、幂等、开关持久化；
- **纯函数测试**：评分规则（`ChangeCorrelationToolTest.scoreRules` 逐权重断言）、时间窗解析、记忆指令正则反例（「帮我记住这个问题的根因」不匹配）、`isStepFailed` 边界（clarify 不算失败）；
- **解析器测试**：`TraceFetcherTest`/`TraceDigestTest` 喂录制的 MCP payload 断言指纹与摘要；`DbArchitectToolHealTest` 验证列名自愈；
- 没有集成测试/E2E——单机项目，人工冒烟 + GUI 截图目录（`gui-test-screenshots/`）承担验收。测试的选型标准是：**逻辑复杂到值得测的都在纯函数里**（评分、解析、判定），这本身就是架构约束的结果。

---

## 9. 设计权衡备忘

读代码时值得记住的七个决策及其原因：

1. **报告类规划不走 LLM**——LLM 编造过日期参数（年份写错），时间语义必须代码解析；
2. **报告素材为空不走 LLM**——固定格式交付物宁可空得诚实，不让模型自由发挥；
3. **gitlab 写操作与案例归档强制确认**——写操作的破坏面大于便利收益；
4. **clarify 优先于 hybrid 切换**——确定性的人机交互优于概率性的自主探索；
5. **贡献数按事件计不按 commit_count 累加**——实测分支同步会让数字虚高 3 倍；
6. **临时 .env 传密码给 Python 子进程**——密码不进 ps 命令行、不残留磁盘；
7. **事件先落库再推送**——seq 单调 + 存量重放，断线续传与历史回放零额外机制。

反模式（现状局限，二开时留意）：无重试机制（工具瞬时失败只能等 hybrid 重路由或用户重发）；LlmClient 无超时（依赖进程级兜底）；无鉴权（单机内网前提，暴露公网前必须加）；toolSummary 无总量上限（超多步骤的 run 上下文会膨胀）。

---

## 10. 扩展指南

### 加一个内置工具（后端 3 步，前端 0~1 步）

1. 新建 `@Component implements Tool`（参考 `ChangeCorrelationTool`）：name/description（动态写配置状态）/argsHint/execute；只读工具返回 `ToolResult("list"|"changes"|..., map, lines, summary)`，写工具 `requiresConfirm()=true`；
2. 需要特殊卡片时在 `ResultCard.vue` 加一个 `result.type` 分支 + `style.css` 样式；不需要就用现成 list 型（零前端改动）；
3. 需要配置就建 Store（抄 `ServiceProjectStore`）+ Controller + 工作台面板（`WorkbenchModal.vue` 三处：tabs/import/分支）。

注册、prompt 注入、规划可见性全自动——这就是 Tool SPI 的收益。

### 加一个工作台面板

`WorkbenchModal.vue`：tabs 数组加 `{key, icon, label}` → import 组件 → v-else-if 分支。数据面板抄 `MemoryPane`（REST CRUD + 消息条），只读面板抄 `TraceAnalysisPane`。

**面板内有多级页面时用面包屑而非返回按钮**：hd-head 里放 `nav.crumbs`（根级 `.crumb` 可点返回、当前级 `.crumb-cur` 不可点且超长省略），参考 `KbPane.vue` 的列表 → 详情切换；主从同屏布局（列表左/详情右，无页面跳转）则不需要，参考 `TraceAnalysisPane`。

### 加一种结果卡片

后端 `ToolResult` 第一个参数用新 type → 前端 `ResultCard.vue` 加分支 → 历史回放自动兼容（回放走同一渲染管线，无需额外处理）。

### 改编排行为

入口都在 `AgentEngine`：改规划模板 → 顶部 `*_SYSTEM_TEMPLATE` 常量；改失败响应 → `isStepFailed()` + orchestrate 的 hybrid 段；改上下文注入 → `memoryBlock()/historyBlock()` 及各 prompt 拼接点。

---

*文档基于 2026-09 的代码现状撰写；类名/方法名以代码为准，行为描述若与代码冲突，请以代码为准并更新本文。*
