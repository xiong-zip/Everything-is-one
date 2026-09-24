# AgentFlow 优化策略与 backlog

> 目的：把散落在性能、前端流畅度、UI 布局、工程结构四个方向的问题收敛成一份可排序、可验收的清单。
> 原则：按**故障半径**排序，而不是按"哪个更酷"。挂死线程、丢记录、成本泄漏属于正确性问题，优先于观感问题。

## 一、现状基线（已核实）

| 位置 | 现状 | 后果 |
|---|---|---|
| `AgentEngine.java:1543-1558` | 每个 SSE 事件调一次 `RunStore.saveEvent`，内部 `DriverManager.getConnection()` 新建连接；`emitter.send` 在 `synchronized(session)` 内 | 一次 3000 字回答约 190 次建连 + 写盘，全程持会话锁 |
| `AgentEngine.java:49` | `STREAM_FLUSH_CHARS = 16` | 每 16 字符产生一个持久化事件 |
| `LlmClient.java:87-95` | `HttpClient.newBuilder().build()` 无 connectTimeout；`JdkClientHttpRequestFactory` 无 readTimeout；`HttpRequest` 无 timeout | **网关挂起将永久占用引擎线程**（最严重） |
| 14 个 Store | 每次操作新建 SQLite 连接，仅设 `busy_timeout`，无 WAL、无共享连接 | 并发读写互相阻塞 |
| `RunStore.java:62-90` | 仅 `idx_events_run(run_id, seq)` | `runs.task_id`、`runs.session_id` 查询走全表扫描 |
| `AgentController.java:100-107` | 分页后逐条 `getRun`，每次返回该 run 全量事件（无 LIMIT） | `limit=20` 时 41 次查询 + 未分页事件流 |
| `ToolRegistry.java:44-50` | 全局 `synchronized` 内调用所有工具 `description()`，其中 `KbSearchTool:66`、`DbArchitectTool:41` 查 SQLite | 每次 plan / ReAct 轮把所有在飞任务串行化 + 锁内 IO |
| `AgentEngine.java:117` | `Executors.newCachedThreadPool()` 无界；run 执行与并行步骤共用同一池 | 无背压，可自我饿死 |
| `AgentEngine.java:173-194` | `executeHeadless` 超时后不取消，调用方记 `error` 但 run 继续烧 token | 告警/定时任务误报 + 成本泄漏 |
| `AgentEngine.java:966-1002` | 取消路径 `checkCancelled` 在补发 `result` 之前抛出 | 取消后的部分产出只存在于增量事件里 |
| `App.vue:289-311` | 每次滚动 N 次 `querySelector` + 2N 次 `getBoundingClientRect()`（强制重排） | 长对话滚动卡顿 |
| `App.vue:275-286` | `lineWidth()` 内 `findIndex`，被模板对每个 mark 调用 | 悬停时为 O(n²) |
| `App.vue:400-402` | resize 监听无 `onUnmounted` 清理 | 泄漏 |
| `TraceAnalysisPane.vue:141-144` | 300ms 防抖定时器无清理 | 泄漏 |
| `WorkbenchModal.vue:23-35` | `v-if/v-else-if` 切面板 → 整块卸载重挂载 | 切 tab 丢状态 + 重复请求 |
| `useAgent.js:195-198` + `ReportText.vue` | `dispatch` 不 await → N 个 14ms 打字定时器并发；每个 `result-delta` 全量重解析 Markdown | 输出越长越卡（二次复杂度） |
| `App.vue` / `useAgent.js` | 61 处 `catch {}` 静默吞异常（40 处带 `/* 静默 */` 注释） | 请求失败与"空列表"不可区分，无重试入口 |
| `style.css` | 19 种硬编码字号、无间距 token、37 处写死 rgba/hex（不随暗色主题变）、约 50 组死选择器、10 处魔法 z-index、`style.css:1645` 已是覆盖层 | 改样式靠叠加覆盖 |
| 仓库 | `alarm/`、`mcp/`、`LlmUsage*` 已完成但未提交，README 已宣传 | HEAD 落后于文档，无法 bisect/回滚 |

## 二、优先级与理由

- **P0 性能与稳定性** —— 挂死线程、丢记录、成本泄漏是正确性问题，且多数改法风险低（加超时、加索引、开 WAL）。
- **P1 前端流畅度** —— 直接影响每次使用的体感；纯前端改动，可视觉对拍验证。
- **P2 UI 布局与设计系统** —— 本次只做**等价替换**（同数值换 token、删死代码），不改变视觉尺寸，杜绝回归。
- **P3 工程结构治理** —— 长期成本最高、改造面最大，本次只做止血（统一 API client、常量配置化），大拆分留后。

## 三、第一批：已实施

### Wave A · 后端正确性与吞吐

1. **SQLite 接入层统一**（`engine/Sqlite.java`）：`busy_timeout` + `synchronous=NORMAL`，`journal_mode=WAL` 按 url 只设一次（该设置持久化在库文件头）；14 个 Store 的 `open()` 全部委托给它。
2. **索引补齐**：`runs(task_id)`、`runs(session_id, id)`。
3. **LLM 超时**（`llm/LlmClient.java`）：connectTimeout / readTimeout / `HttpRequest.timeout` 三项配置化。另加**流式空闲看门狗**——`HttpRequest.timeout` 只覆盖到「拿到响应头」，之后 TCP 活着但不吐数据时阻塞中的 `readLine` 不会自己超时，只能靠关流打断。
4. **工具与 MCP 超时分离**（`tool/ToolHttpClient.java`）：普通工具 5s/10s，远程 MCP 60s（链路查询本身就可能跑几十秒，10s 会把正常请求误杀）。
5. **事件写入瘦身**：`STREAM_FLUSH_CHARS` 16 → 配置化默认 64；`emitter.send` 移出 `synchronized(session)`（慢客户端不再连带卡住 attach/detach 与后续落库）。
6. **取消路径补发完整 `result`**：write 分支累积已流出文本，取消时补发一次。这是「增量不落库」的前提，本身也修了「取消后回放只剩半截增量」。
7. **会话分页去 N+1**：新增 `RunStore.listEventsByRunIds()` 单查询 `WHERE run_id IN (...)`；分页查询顺带返回 `output`，调用方不必再逐条 `getRun`。查询数 41 → 2。
8. **引擎线程池有界化 + 命名**（`agent-run` / `agent-step` / `agent-memory`）：队列满时新任务立即失败并如实收尾，而非无限建线程；**并行步骤必须用独立的池**，否则运行池有界后 `invokeAll` 会互相等待成死锁。
9. **`ToolRegistry` 提示词缓存**：读路径不再持锁、不再触发工具查库；锁外构建 + 版本号防止并发注册时旧快照覆盖新快照。
10. **事务与 UPSERT**：`deleteSession`/`deleteRun`/`clearAll`/`KbStore.saveFile`/`McpServerStore.save` 包事务；`TraceAnalysisStore.record` 改单语句 `INSERT … ON CONFLICT(trace_id) DO UPDATE`，消除并发分析同一链路时静默丢记录的竞态。
11. **`executeHeadless` 超时即取消**：原先超时只返回当前状态，调用方已按失败记录并推送，任务却还在后台继续跑、继续烧额度。

### Wave B · 前端流畅度

12. **流式 Markdown 节流**（`ReportText.vue`）：流式期 150ms 节流重解析，结束解析一次。原先每个增量事件都全量重解析，累计是平方级。
13. **打字动画串行化**（`useAgent.js`）：单 run 一个串行队列，且只给前 2 条推理行做动画。原先 N 个 14ms 定时器并发写响应式状态。
14. **滚动/悬停热路径**（`App.vue`）：消息元素加缓存（不再每帧每条 `querySelector`）、容器 rect 每帧只读一次；横线宽度在 computed 里一次算好（原先模板对每个 mark 回头 `findIndex`，是 O(n²)）；两个 `runs.map(...).join()` 监听换成单调递增的标量。
15. **修监听与定时器泄漏**：`window resize` 监听换成绑定在滚动容器上的 `ResizeObserver`（顺带覆盖侧栏收起）；`TraceAnalysisPane` 的防抖定时器在卸载时清理。
16. **滚动节流闩锁修复**：原先 `mmRaf = requestAnimationFrame(...)` 只在回调里清零，一旦 rAF 不触发（标签页被遮挡、嵌入式 webview），闩锁被永久占住、此后滚动高亮再也不更新。改为 60ms 时间节流。
17. **工作台面板保活**（`WorkbenchModal.vue`）：`KeepAlive` + `:max=6`，只挂载访问过的面板。顺带修掉一个副作用——`AlarmPane` 被缓存后不再卸载，其 15 秒轮询会变成后台常驻，已改用 `onActivated`/`onDeactivated` 控制。

### Wave C · UI token 与结构止血

18. **设计 token 扩充**（`style.css`）：新增 `--z-*` 层级尺度（取代 10 处靠注释说明的魔法数字）、`--space-*` 间距尺度、`--hover-tint`/`--hover-tint-soft`/`--mask`/`--mask-soft`/`--select-bg` 交互与遮罩 token。
19. **主题盲区修正**：hover 反馈与遮罩原先写死深色半透明，深色主题下等于看不见；现在随主题翻转（浅色压暗 / 深色提亮）。另修正 `.pa-step` 写死 `#fff`、`.nc-type.dingtalk`、`.chg-row.top`、`.chg-rank.r1` 四处不随主题变的固定色。
20. **死代码清理**：删除零引用组件 `ChatHeader.vue`；用「选择器含有的类名在源码中不存在 ⇒ 该选择器永远匹配不到元素」这一可靠判据删除 107 条规则，CSS 78.67 KB → 69.36 KB。注意 `r1/r2/r3` 这类**模板拼接生成**的类名静态扫描查不出，已专门补回 `.chg-rank.r1` 并留注释。
21. **统一 API client**（`api/client.js`）：统一传输与错误规范化，8 个面板复制的 `loading → fetch → 静默 catch` 模式收敛；失败不再伪装成「空列表」，改为抛出并提示。
22. **常量配置化**：`stream-flush-chars`、线程池三项、SSE 连接存活时长、会话数上限、确认计划超时、ReAct 步数上限、并行度、LLM/工具超时全部进 `application.yml` + `.env.example`；`llm.base-url`/`model` 允许 env 覆盖。

### 与计划的偏差（都是实施中发现后调整的）

- **不做常驻写连接**：原计划让 `RunStore` 复用一条写连接以省掉每事件建连。实测它会让进程在整个生命周期里占着库文件（Windows 上连临时目录都删不掉，直接让 `RunStoreTest` 的 `@TempDir` 清理失败）。真正的收益来自 WAL + `synchronous=NORMAL` 消除每次提交的 fsync，连接复用只是次要收益，因此放弃。
- **增量事件暂时仍落库**：第 6 项落地后才具备「完全不落库」的前提，留到第二批。
- **未做**：`window.confirm` ×15 改应用内确认组件、模态焦点陷阱、API client 覆盖全部 79 处 fetch、字号尺度收敛（19 种硬编码 px → 8~9 级，只加了 `--space-*` 与 `--z-*`，字号仍待收敛）—— 均留第二批，理由见第六节。

### 验收结果

- 后端：`./mvnw test` **205 项全绿**（原 197 + 新增 8：SQLite 层 3、批量事件 4、ToolRegistry 缓存 1）。
- 前端：`npm run build` 通过；CSS 78.67 → 69.36 KB，JS 200.88 → 204.08 KB。
- 运行时：浏览器实测 13 个面板全部满高渲染（648×924）、无运行时错误或警告；面板保活经「填搜索词 → 切走 → 切回」验证状态保留，且未访问的面板不会被提前创建；滚动高亮在「rAF 完全不触发」的环境下仍能正确定位（scrollTop 0/500/1250 分别命中第 1/2/3 条）。
- **未能完成**：端到端的截图肉眼确认。IAB 截图通道在本次会话中不可用（30s 超时），且当前模型不支持读图，派出的子代理同样读不到图。已用像素级测量与计算样式断言替代，但**外观的主观判断仍建议你自己重启应用看一眼**。
- 两点提醒：① 你本地 8888 端口跑的是改动前的进程，重启后才会加载新前端；② 启动时会按保留策略清理旧记录（30 天 / 最多 1000 条），我验证前把当时的库备份到了 `%TEMP%` 下的 `agentflow-db-backup-*.db`，需要回滚可从这里取。另外想拿到上面两项推导指标的真实数字，跑一次真实任务后 `SELECT run_id, COUNT(*) FROM events GROUP BY run_id` 即可。

## 四、量化验收（第一批）

**真正观测到的**（构建输出、测试报告、浏览器实测）：

| 指标 | 结果 |
|---|---|
| `./mvnw test` | 205/205 全绿（原 197 + 新增 8） |
| `npm run build` | 通过；CSS 78.67 → 69.36 KB，JS 200.88 → 204.08 KB |
| 删除的死 CSS | 107 条规则 / 283 行 / 9.3 KB |
| 工作台 13 个面板渲染高度 | 全部 648 × 924，无塌陷、无溢出 |
| 面板保活 | 填入搜索词 → 切走 → 切回，值仍在；未访问的面板不提前创建 |
| 滚动高亮 | 在「rAF 完全不触发」的环境下，scrollTop 0/500/1250 分别命中第 1/2/3 条 |
| 运行时错误/警告 | 覆盖会话切换、滚动、工作台开关与 4 面板往返，0 条 |
| 主题 token | 浅色 `--hover-tint: rgba(20,24,28,.06)` / 深色 `rgba(255,255,255,.08)`，遮罩与选中色同步翻转 |

**按代码推导的**（未做端到端埋点，属算术推导，别当成实测值）：

| 指标 | 推导过程 |
|---|---|
| 会话分页 SQL 查询数 41 → 2 | 原先 1 次分页 + 每 run 一次 `getRun`（各 2 条 SQL，含事件流）；现在 1 次分页 + 1 次 `IN` 批量取事件 |
| 一次约 3000 字 write 步的事件行数约 190 → 约 48 | 行数 ≈ 输出字数 ÷ 刷写阈值；阈值 16 → 64，故降为约 1/4（不含 `result`/`step-state` 等固定事件） |

要拿到这两项的真实数字，需要跑一次真实任务并查 `events` 表计数（见第五节最后一条的提醒）。



## 五、风险与回退

- **WAL**：会多出 `-wal`/`-shm` 文件（`data/` 已在 `.gitignore`）；回退只需改回 `journal_mode`。
- **写连接复用**：写操作串行化在一把锁下；异常时可回退为每操作新建连接。
- **线程池有界化**：唯一会改变并发行为的改动，默认给足（核心 8 / 最大 32 / 队列 256）+ `CallerRunsPolicy`，先观察再收紧。
- **面板保活**：只挂载访问过的面板，限制常驻内存增长。
- **token 化**：只做同数值等价替换，不改变实际尺寸。

## 六、第二批 backlog

- **企微智能机器人接入对外 MCP 服务（已具备、暂缓启用）**：对外 MCP 端点（`POST /api/mcp/server`，Bearer 令牌）已实现并在本机验证（tools/list 15 个工具、写操作拒绝、调用记录落库），但企微后台的机器人只能访问**内网可达地址**——本地开发机没有固定内网入口，等部署到内网服务器后只需：管理后台建机器人 → MCP 配置填 `http://<部署机>:8888/api/mcp/server` + 请求头 `Authorization: Bearer <AGENTFLOW_MCP_SERVER_TOKEN>`。端点本身无需再开发；届时可选增强：`?token=` 查询参数鉴权（若企微配置界面不支持自定义请求头）、按工具粒度的暴露白名单。

- `result-delta` 完全不落库（依赖第一批第 7 项）
- 前端状态从 `App.vue` 拆出（11 个 ref + 2 个映射 join watcher）
- 15 处 `window.confirm` → 统一应用内确认组件
- 模态焦点陷阱 / 自动聚焦 / 关闭后焦点归还；抽屉补 `aria-modal`
- `reactive()` 深代理改 `shallowRef` + `markRaw`；`v-for` 加 `v-memo`
- 长列表虚拟化（`TraceAnalysisPane` 200 条、`AlarmPane` 100 条、`GitlabPane` 366 格）
- `prefers-color-scheme` 自动主题
- 面板错误态全覆盖 + 重试入口（依赖第 18 项）
- `IncidentKb` 每次 archive 全量重写 `index.yaml` → 增量写
- `KbStore.allChunks()` 每次搜索全量载入 → 缓存 + 失效

## 七、第三批 backlog

- `AgentEngine`（1586 行）按 规划 / 执行 / 记录 拆分
- CI 门禁（需确定 GitHub 还是内网 GitLab）
- 12.5MB JDBC driver jar 治理（LFS 或构建期下载）
- `maven-settings.xml` 的本地代理 `127.0.0.1:18080`、`application.yml` 的内网 IP 从仓库移出
- 提交尚未入库的 `alarm/`、`mcp/`、`LlmUsage*` 功能，使 HEAD 与 README 一致
- `AgentEngine.orchestrate`、`LlmClient` 协议解析补行为测试（当前 197 个单测几乎不覆盖编排主流程）
