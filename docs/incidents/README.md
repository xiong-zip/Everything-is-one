# 故障案例知识库（Incident Knowledge Base）

把每次链路分析中发现的**故障模式**沉淀下来，形成可复用的诊断经验。

- **唯一真相源**：本目录下的 Markdown 文件（YAML frontmatter 承载结构化字段），随代码走 git。
- **检索入口**：`index.yaml` —— 分析 trace 前先匹配这里，不要一上来就通读全部案例文件。
- **一条案例 = 一个故障模式**，不是一次分析。同一指纹多次发生只累加 `occurrences`，不新建文件。

## 目录结构

```text
docs/incidents/
├── README.md       # 本文件：schema 与维护约定
├── index.yaml      # 案例索引（Agent 检索入口，按 last_seen 倒序）
├── _template.md    # 新案例模板
└── cases/          # 案例正文，一个故障模式一个文件
    └── INC-YYYYMMDD-NNN-<语义短名>.md
```

## 核心概念：故障指纹（fingerprint）

指纹是"这两次故障是不是同一个毛病"的判据，也是自动检索能成立的前提。四个要素：

| 字段 | 含义 | 示例 |
|---|---|---|
| `error_class` | 异常类或错误类型 | `UnknownHostException` |
| `signature` | 最能定位问题的关键标识 | `dict-service`（服务名/表名/接口片段/错误码） |
| `span` | 失败 span 的 `service / operation` | `inp-drug-service / ResponseConverter.fetchPrimaryDataIndex` |
| `status` | 状态特征，含 HTTP 与 OTel 的矛盾状态 | `HTTP 200 + OTel Error` |

匹配强度约定（多信号计分，相关度由代码保证、不由 AI 判断）：

| 信号 | 分值 |
|---|---|
| 识别特征一致（DAO 方法/主表，辨识度最高） | +3 |
| 特定异常类一致（如 `DMException`、`UnknownHostException`） | +2 |
| 通用异常类一致（NPE、TimeoutException 等，全系统常见） | +1 |
| 失败 span 的服务+操作一致 | +2 |
| 每个关键词命中（封顶） | +2 |
| 涉及服务有交集 | +0.5 |

**强 ≥3**（要么单一高辨识特征，要么至少两个独立信号）；**中 ≥2**（复用但要求复核）；**弱 >0**（仅参考线索，不进报告）。两条护栏：与本次链路**服务零交集**的案例最多弱命中（跨业务域不算同一故障）；同一证据不重复计分（异常类命中后，其简名不再计入关键词）。

**指纹相同不等于根因必然相同**：命中后仍要以本次 trace/日志证据为准，不一致时在案例里记「修订记录」。

## 案例字段（frontmatter）

| 字段 | 必填 | 说明 |
|---|---|---|
| `id` | ✅ | `INC-<YYYYMMDD>-<NNN>`，日期取**故障发生日**（即 `first_seen` 的日期），不是归档日；同日多例按 `001` 递增 |
| `title` | ✅ | 一句话故障模式标题 |
| `fingerprint` | ✅ | 见上，四要素 + `keywords` |
| `symptom` | ✅ | 业务/用户视角看到的现象 |
| `root_cause` | ✅ | 根因一句话 |
| `confidence` | ✅ | `日志证实` / `span推断` / `证据不足` |
| `fix` | ✅ | 处置动作列表，P0 在前 |
| `verification` | ✅ | 怎么确认修好了 |
| `occurrences` | ✅ | 命中同一指纹的累计次数 |
| `first_seen` / `last_seen` | ✅ | ISO8601 带时区，如 `2026-09-10T09:13:00+08:00` |
| `trace_ids` | ✅ | 关联 trace ID，追加不覆盖 |
| `services` | ✅ | 涉及服务 |
| `owner` | ⬜ | 归属团队 |

## 新增案例

1. 复制 `_template.md` 为 `cases/INC-<YYYYMMDD>-<NNN>-<语义短名>.md`。
2. 填 frontmatter 与正文；只写 trace/日志里**真实出现**的异常类、服务名、操作名。
3. 在 `index.yaml` 的 `cases` 列表新增一条，并更新 `updated`。
4. 按 `last_seen` 倒序维护列表顺序。

## 更新已有案例（去重）

同一个指纹再次发生时：

- `occurrences + 1`，刷新 `last_seen`，`trace_ids` 追加本次 trace。
- 「关联」区追加本次 trace 报告路径。
- 若本次证据推翻原根因：改写 `root_cause`，并在文件内加一行「修订记录」说明何时因何修订。

## 手工编辑注意：YAML 会吃掉没加引号的值

frontmatter 是 YAML，有两类值**必须加引号**，否则会被静默截断：

| 情况 | 写法 | 不加引号的后果 |
|---|---|---|
| 值里有 `#`（尤其 ` 空格+#`） | `root_cause: "绑定参数 #1 时 JdbcType 为 null"` | `#` 之后全被当注释丢弃 |
| 值里有 `: `（冒号+空格） | `error_class: "TypeException (DMException: Not support this type)"` | 解析报错或值被截断 |
| 时间戳 | `first_seen: '2026-09-08T09:48:45+08:00'` | 被解析成 Date，显示成 `Tue Sep 08 09:48:45 CST 2026` |

由 `signoz.case` / skill 自动生成的文件已正确加引号；**手写或改字段时要自己注意**。
时间戳没加引号不会丢数据（读取时会还原成 ISO 格式），但 `#` 造成的截断是不可逆的。

## 不建案例的情况

- 成功链路且无异常、无稳定可复用的慢点 —— 只保留性能报告。
- 证据不足（`confidence: 证据不足`）—— 可以建，但必须在标题或正文标注「待证实」，避免把猜测当经验。

## 冷启动

目录初始为空是正常的：第一条案例来自第一次成功的故障归档。在此之前，检索步骤会直接跳过，不影响正常分析。
