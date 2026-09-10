---
id: INC-YYYYMMDD-NNN
title: <一句话故障模式标题>
fingerprint:
  error_class: <异常类或错误类型，如 UnknownHostException>
  signature: <关键标识：服务名/表名/接口片段/错误码>
  span: <service / operation>
  status: <HTTP 状态 + OTel 状态，如 "HTTP 200 + OTel Error">
  keywords: [<检索关键词>, <...>]
symptom: <业务/用户视角看到的现象>
root_cause: <根因一句话>
confidence: <日志证实 | span推断 | 证据不足>
fix:
  - <P0 立即处理动作>
  - <P1 防复发动作>
verification: <怎么确认修好了>
occurrences: 1
first_seen: <YYYY-MM-DDTHH:mm:ss+08:00>
last_seen: <YYYY-MM-DDTHH:mm:ss+08:00>
trace_ids: [<trace id>]
services: [<service>, <...>]
owner: <归属团队>
---

# <标题>

## 现象

<用户或业务侧观察到的表现；有矛盾状态（如 HTTP 200 但业务失败）必须写明。>

## 根因

<一句话根因 + 必要的失败传播路径：哪个 span 先失败、如何传到 root。>

## 关键证据

| 证据 | 说明 |
|---|---|
| <异常类 / 关键日志> | <如何支持根因> |
| <失败 span> | <服务、操作、耗时、状态> |
| <排除项或状态矛盾> | <例如 SQL 正常；或 HTTP 200 但 OTel Error> |

## 处置步骤

1. <P0：直接消除根因>
2. <P1：告警 / 兜底 / 配置校验 / 异常处理，防复发>

## 验证方式

<如何确认修复有效；下次同类故障如何快速确认是同一模式。>

## 修订记录

<!-- 仅当后续证据推翻或修正了本案例结论时追加，格式：- <日期> 因 <新证据> 修订 <原结论> -->

## 关联

- trace 报告：<docs/trace-xxx.md>
- 相关 trace：<trace id>
