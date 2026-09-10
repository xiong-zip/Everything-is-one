---
name: signoz-analyzing-traces
description: >
  Analyze one SigNoz distributed trace by trace ID / 链路 ID using SigNoz MCP,
  then produce a short root-cause-first report, and accumulate the failure
  pattern into the incident knowledge base at docs/incidents/. Use this whenever
  the user asks "分析这个 trace", "这个链路为什么失败/慢", "trace_id xxx 怎么回事",
  or provides a single trace ID for drill-down. The report must put the root
  cause or performance conclusion in the first screen, and before analyzing it
  must first check docs/incidents/ for an already-known failure pattern. Do not
  use for aggregate metrics, dashboard creation, docs lookup, MCP setup, or
  ClickHouse query writing.
---

# SigNoz Trace Analysis

根据单个 trace ID / 链路 ID 查询 SigNoz，并生成**根因优先、短而可执行**的分析报告。

核心目标：用户第一屏就能看到“为什么失败/慢、证据是什么、下一步怎么处理”。

## Prerequisites

依赖 SigNoz MCP 工具（MCP 服务名 `signoz`）：

- `signoz_get_trace_details`：主调用，获取完整 span 树。
- `signoz_search_logs`：仅失败链路需要，用 trace_id 拉关键 ERROR/WARN 日志。
- `signoz_search_traces`：仅 trace 详情查不到时用于反查。

工具全名随客户端而变：ZCode 中为 `mcp__signoz__signoz_get_trace_details`，其他客户端可能是 `signoz:signoz_get_trace_details`。按当前会话 MCP 工具列表里的实际名字调用，不要手动拼前缀。

工具不可用时，直接说明 SigNoz MCP 当前不可用；不要绕过 MCP 直接调用 SigNoz HTTP API。

## 故障案例知识库

本 skill 只分析单条 trace，**故障经验的沉淀靠知识库**，两者配合才形成闭环：

| 阶段 | 作用 |
|---|---|
| 分析前（Step 0） | 查知识库，指纹命中就直接复用历史根因与处置方案 |
| 分析后（Step 5） | 抽取指纹归档，同一故障模式累加次数而非重复建档 |

知识库位置（相对项目根）：

```text
docs/incidents/
├── README.md       # schema 与维护约定，字段含义以它为准
├── index.yaml      # 检索入口：分析前只读这里
├── _template.md    # 新案例模板
└── cases/          # 案例正文
```

- 检索只读 `index.yaml`，命中强/中匹配才打开对应案例文件读全文，避免通读整个目录。
- 字段定义、指纹匹配强度、去重规则以 `docs/incidents/README.md` 为准；本 skill 不重复定义 schema。
- 知识库不存在或为空时不要报错，按冷启动处理：跳过 Step 0，正常分析后在 Step 5 建首条案例。

## When to Use

✅ 使用本 skill：

| 用户提问 | 示例 |
|---|---|
| 给出 trace ID 要求分析 | “分析 trace `a3b693b9...`” |
| 问某条链路为什么失败 | “这个链路为啥报错 `2ee9e12d...`” |
| 问某条链路为什么慢 | “查一下 trace_id `f62db8...` 慢在哪里” |
| 要根据链路生成分析报告 | “根据这个链路 ID 出一份报告” |
| 问这个故障是否历史出现过 | “这个报错以前是不是遇到过” |

❌ 不使用本 skill：

- 统计错误率、p99、吞吐量、Top errors。
- 创建/修改 dashboard。
- 编写 ClickHouse SQL。
- 查询 SigNoz 文档。
- 配置 MCP 或接入观测。

## Workflow

### Step 0：先查故障案例知识库

分析之前先读 `docs/incidents/index.yaml` 的 `cases` 列表，用当前线索去匹配每条案例的 `fingerprint`。

线索来源（按可用性取）：用户口述的现象、trace 的服务名与接口/操作名、用户贴出的异常或报错关键字。

匹配强度（判定标准见 `docs/incidents/README.md`）：

| 强度 | 处理 |
|---|---|
| 强 / 中 | 打开对应案例文件读全文，按 Template D 输出，复用历史根因与处置方案 |
| 弱 | 只作为参考线索，仍按常规流程分析 |
| 无 | 跳过，按常规流程分析 |

命中后**不要直接照抄历史结论**：本次 trace 与日志证据优先，一致则引用，不一致则在报告中写明差异，并在 Step 5 记录修订。

索引文件不存在、`cases` 为空、或只有弱匹配时，静默跳过本步，不向用户报错。

### Step 1：获取 trace 详情

先调用：

```yaml
tool: signoz:signoz_get_trace_details
traceId: <用户提供的 trace ID>
timeRange: 24h
includeSpans: true
```

查不到时按顺序降级：

1. `signoz_get_trace_details` 扩到 `7d`。
2. `signoz_search_traces(query: "trace_id = '<traceId>'")` 反查。
3. `signoz_search_logs(query: "trace_id = '<traceId>'")` 查是否有日志残留。
4. 全部失败时输出“Trace 未找到”模板。

### Step 2：判断链路类型

基于 root span 和关键 span 判断：

| 类型 | 判定 |
|---|---|
| 失败链路 | `hasError=true`、`statusCode=ERROR`、存在异常日志或错误 span |
| 成功链路 | root span 无错误，主要关注耗时与慢 span |
| 数据不足 | span 缺失、日志缺失、状态矛盾且证据不足 |

失败链路优先找根因；成功链路只做性能判断，不要强行找问题。

### Step 3：失败链路拉关键日志

仅失败链路执行：

```yaml
tool: signoz:signoz_search_logs
query: "trace_id = '<traceId>' AND severity_text IN ('ERROR', 'WARN')"
limit: 10
timeRange: 24h
```

报告中只引用最关键的 1-3 条日志证据；不要复制完整框架堆栈。

### Step 4：计算必要指标

只基于已返回 span 数据计算，不为了凑报告继续发起聚合查询。

| 指标 | 算法 |
|---|---|
| 总耗时 | root span `durationNano / 1e6` ms |
| 最慢 span | duration 最大的关键 span |
| span 占比 | `span.durationNano / root.durationNano * 100%` |
| 状态矛盾 | 例如 HTTP 200 但 OTel Error |
| 失败传播 | 从错误 span 向 root span 回溯父子关系 |

### Step 5：归档到故障案例知识库

报告输出后执行。目标是**按故障模式去重**，不是每分析一次写一份文档。

先判断是否值得归档：

| 情况 | 是否归档 |
|---|---|
| 失败链路，能归纳出根因或可疑模式 | ✅ 必须 |
| 成功链路，但有稳定可复用的慢点/隐患 | ✅ 归档 |
| 成功链路，耗时正常、无异常 | ❌ 不建案例 |
| 证据不足（`confidence: 证据不足`） | ⚠️ 可建，但必须标注「待证实」 |

归档步骤：

1. 抽取指纹四要素：`error_class`（异常类/错误类型）、`signature`（服务名/表名/接口片段/错误码）、`span`（`service / operation`）、`status`（HTTP 与 OTel 状态，矛盾状态必须写明）。
2. 在 `docs/incidents/index.yaml` 中查找指纹相同或强匹配的已有案例：
   - **命中**：`occurrences + 1`、刷新 `last_seen`、`trace_ids` 追加本次 trace；案例文件「关联」区追加本次 trace 报告路径；若本次证据推翻原根因，改写 `root_cause` 并加一行「修订记录」。
   - **未命中**：按 `_template.md` 新建 `docs/incidents/cases/INC-<YYYYMMDD>-<NNN>-<语义短名>.md`（`YYYYMMDD` 取**故障发生日**而非归档日，同日多例序号递增），`occurrences: 1`，并在索引中新增一条。
3. 更新 `index.yaml` 的 `updated`，并保持 `cases` 按 `last_seen` 倒序。

约束：

- 指纹只取 trace / 日志里**真实出现**的异常类、服务名、操作名，禁止编造。
- 归档失败（目录不可写等）必须明确告知用户「本次未能归档」并给出建议路径，不要静默跳过。
- 报告里只提一句归档结果（案例编号 + 本次是新建还是累加），不要输出知识库全文。

## Output Budget

默认输出短报告，不写长篇复盘。

| 项 | 默认要求 |
|---|---|
| 失败链路 | 40-60 行以内 |
| 成功链路 | 25-40 行以内 |
| 根因/结论位置 | 必须在前 10 行 |
| 目录 | 不写 |
| Mermaid | 不写，除非用户明确要求画图 |
| 代码修复示例 | 不写，除非用户明确要求给代码/SQL/YAML |
| 行动项 | 最多 3 条 |
| 附录/完整 span 表 | 不写，除非用户要求详细版 |

如果证据不足，第一屏直接写“暂不能确认根因”，然后列缺失证据；禁止脑补。

用户明确要求“详细报告 / 复盘文档 / 带图 / 带代码修复”时，才展开长格式。

## Report Templates

### Template A：失败链路，根因优先

```markdown
# Trace 根因分析：<接口或语义标签>

## 结论
- 根因：<一句话根因；不能确认则写“疑似”或“暂不能确认”>
- 失败点：<service / span / operation>
- 影响：<影响的接口、业务动作或下游>
- 证据强度：<日志证实 / span 推断 / 证据不足>

## 关键证据
| 证据 | 说明 |
|---|---|
| <异常类型或错误日志> | <如何支持根因> |
| <失败 span> | <服务、操作、耗时、状态> |
| <排除项或状态矛盾> | <例如 SQL 正常；或 HTTP 200 但 OTel Error> |

## 链路摘要
| 字段 | 值 |
|---|---|
| Trace ID | `<traceId>` |
| 接口 | `<METHOD path 或 root operation>` |
| 总耗时 | `<ms>` |
| 状态 | `<HTTP / OTel>` |
| 涉及服务 | `<service list>` |

## 处理建议
1. <P0 立即动作：直接处理根因>
2. <P1 防复发动作：告警、兜底、配置校验或异常处理>
3. <验证方式：如何确认修复有效>

## 可选下钻
- <最多 1-2 条；例如统计同根因 24h 出现次数。没有必要就写“暂无”。>
```

### Template B：成功链路，性能结论优先

```markdown
# Trace 性能分析：<接口或语义标签>

## 结论
- 性能判断：<正常 / 偏慢 / 异常慢>
- 主要耗时：<最慢服务或 span>
- 是否需要处理：<需要 / 暂不需要 + 理由>

## 关键指标
| 指标 | 值 | 判断 |
|---|---|---|
| 总耗时 | <ms> | <判断> |
| 最慢 span | <service / operation / ms> | <判断> |
| 下游占比 | <percent> | <判断> |

## 链路摘要
| 字段 | 值 |
|---|---|
| Trace ID | `<traceId>` |
| 接口 | `<METHOD path 或 root operation>` |
| 涉及服务 | `<service list>` |
| 状态 | `<HTTP / OTel>` |

## 建议
1. <最重要的一条优化或观测建议；无优化点则写“暂无明显优化必要”。>
2. <是否需要看同接口 p95/p99 或同类 trace；无必要则省略。>
```

### Template C：Trace 未找到

```markdown
# Trace 未找到

## 结论
未在 SigNoz 中找到 trace：`<traceId>`

## 已尝试
| 查询 | 结果 |
|---|---|
| get_trace_details 24h | 未找到 |
| get_trace_details 7d | 未找到 |
| search_traces | 未找到 |
| search_logs | 未找到 |

## 可能原因
1. trace ID 输入错误。
2. 数据超过保留期。
3. 采样未保留。
4. trace 未成功写入 SigNoz。
5. 查询环境与 trace 所在环境不一致。

## 需要补充
- 大概发生时间。
- 来源服务或接口。
- 环境。
- 原始 trace ID 截图或日志片段。
```

### Template D：命中已知故障案例

Step 0 强/中匹配命中时使用。比从头分析更短，重点是「还是不是同一个毛病」。

```markdown
# Trace 分析：<接口或语义标签>（已知故障 #INC-xxx）

## 结论
- 已知模式：<案例标题>（历史第 <occurrences> 次，最近 <last_seen>）
- 本次判定：<与历史一致 / 与历史不同>
- 失败点：<service / span / operation>
- 证据强度：<日志证实 / span 推断 / 证据不足>

## 历史处置方案
1. <复用案例中的 P0 动作>
2. <复用案例中的 P1 防复发动作>

## 本次差异
| 项 | 历史案例 | 本次 trace |
|---|---|---|
| <异常/耗时/影响面等> | <值> | <值> |

## 归档
- 案例：`docs/incidents/cases/INC-xxx.md`（本次已累加至 <n> 次）
```

若本次与历史不一致，按 Template A/B 完整分析，并在「归档」处说明将写入修订记录。

## Evidence Rules

| 场景 | 表述规则 |
|---|---|
| 日志明确异常 | 可以写“根因：...” |
| 只有错误 span，无日志 | 写“疑似根因：...” |
| span 和日志矛盾 | 先写矛盾，再写需要补充什么证据 |
| 查不到字段 | 写“该字段未上报” |
| HTTP 200 但 OTel Error | 必须显式指出，避免网关层误判成功 |

## Anti-Patterns

❌ 禁止：

| 反模式 | 替代做法 |
|---|---|
| 根因藏在中间章节 | 根因必须在 `## 结论` 第一条 |
| 为了完整性写长篇复盘 | 默认短报告；详细版等用户要求 |
| 默认生成 Mermaid | 用户明确要求图时才画 |
| 默认给 Java/SQL/YAML 代码修复 | 用户要求代码时才给 |
| 复制完整框架堆栈 | 只摘关键异常、业务类、关键组件 |
| 成功链路强行找问题 | 耗时正常就写“暂无明显优化必要” |
| 失败链路不查日志 | 失败链路必须尝试查 ERROR/WARN 日志 |
| 编造服务名、包名、表名 | 只使用 span/log 里真实出现的信息 |
| 跳过 Step 0 直接分析 | 先读 `docs/incidents/index.yaml`，命中已知模式就复用 |
| 每次分析都新建案例文件 | 按指纹去重，同一模式只累加 `occurrences` |
| 照抄历史案例结论 | 本次 trace/日志证据优先，不一致要写差异并记录修订 |
| 把整份报告塞进案例文件 | 案例只留故障模式（指纹/根因/处置/验证）+ 报告路径 |
| 知识库为空时报错或停下来问 | 按冷启动跳过 Step 0，正常分析后建首条案例 |

## Examples

### 示例 1：失败链路根因第一屏

用户：

```text
2ee9e12d2f2aecd0d9c78313adb06f8b 这个链路为啥失败
```

期望输出开头：

```markdown
# Trace 根因分析：票据生成失败

## 结论
- 根因：`inp-drug-service` 字典翻译阶段调用 `dict-service` 失败，底层异常是 `UnknownHostException: dict-service`。
- 失败点：`inp-drug-service` / `ResponseConverter.fetchPrimaryDataIndex`
- 影响：票据生成链路失败，但 HTTP 仍返回 200，网关层可能误判成功。
- 证据强度：日志证实。
```

### 示例 2：成功链路不凑字数

用户：

```text
分析 trace f62db875993e6ef933daaa96f7861e28 性能如何
```

行为：

1. 获取 trace 详情。
2. 判断 `hasError=false`。
3. 计算总耗时、最慢 span、下游占比。
4. 如果耗时正常，直接写“暂无明显优化必要”。
5. 不画 Mermaid，不写代码修复，不写长篇行动计划。

### 示例 3：查不到 trace

用户：

```text
分析 a5dc3bb21a8fe42ee635bc07c4a2154e
```

行为：

1. 24h 查不到。
2. 扩到 7d 仍查不到。
3. `search_traces` 和 `search_logs` 仍无结果。
4. 输出“Trace 未找到”模板，要求用户补充发生时间、服务和环境。

### 示例 4：命中已知故障案例

用户：

```text
分析 2ee9e12d2f2aecd0d9c78313adb06f8b 为啥失败
```

行为：

1. Step 0 读 `docs/incidents/index.yaml`，按 `UnknownHostException` + `dict-service` 命中 `INC-20260910-001`。
2. 读该案例，本次 trace 校验根因一致。
3. 按 Template D 输出：已知模式 + 历史处置方案 + 本次差异，不重复从零推理。
4. Step 5 把该案例 `occurrences` 从 3 累加到 4，刷新 `last_seen`，追加本次 trace ID。
5. 报告末尾只交代一句：`已归档：INC-20260910-001（累计第 4 次）`。

## Saving Reports & Archiving

分析产出两类文件，行为不同，不要混淆：

| 产物 | 位置 | 触发 |
|---|---|---|
| **trace 报告**（单次分析原文） | `docs/trace-<短traceId>-<语义标签>.md` | 询问后按用户意愿保存 |
| **故障案例**（按故障模式去重的经验） | `docs/incidents/` | Step 5 自动归档，无需询问 |

### trace 报告

分析完成后，默认先在对话中输出短报告，然后在末尾询问是否需要保存为 Markdown 文件。

末尾固定追加一句：

```text
是否需要我将这份分析保存为 Markdown 文档？如需保存，默认路径为 `docs/trace-<短traceId>-<语义标签>.md`。
```

保存规则：

- 用户一开始明确说“保存为 md / 输出 md / 保存到 docs/ / 生成分析文档”时，分析完成后直接写入项目 `docs/` 目录，并在对话里返回文件路径。
- 用户一开始没有要求保存时，不自动写文件，只询问是否保存。
- 用户回答“保存 / 是 / 输出 md”后，再写入 Markdown 文件。
- 保存前如目标文件已存在，先提示并更换文件名或等待用户确认，避免覆盖。

不要默认写入 `outputs/reports/`。

### 故障案例归档

Step 5 的归档**默认自动执行**，这是知识库能积累起来的前提：

- 不询问、不等确认；归档结果只在报告末尾用一句话交代（案例编号 + 新建或累加至第几次）。
- 归档的是**故障模式**，不是本次报告全文；本次 trace 报告路径记入案例的「关联」区即可。
- 用户明确说“不要归档 / 只在对话里看”时才跳过。

## 需要补充什么

分析开始前若缺关键输入，一次性问清，不要边分析边追问：

| 缺失项 | 为什么需要 |
|---|---|
| trace ID | 没有就无法查询 |
| 大概发生时间 | 决定 `timeRange`（默认 24h，可扩 7d）；时间差太多会误判为「Trace 未找到」 |
| 环境 | 多环境 SigNoz 时避免查错环境 |
| 期望的分析角度 | 失败根因还是性能，决定走 Template A 还是 B |

用户已经给全的不要重复问。trace ID 之外全缺时，先按默认值（24h、当前环境、自动判断失败/成功）跑，只在失败时再回头问。

