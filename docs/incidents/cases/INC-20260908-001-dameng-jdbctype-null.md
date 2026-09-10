---
id: INC-20260908-001
title: 达梦驱动不支持参数类型导致押金明细查询失败（MyBatis JdbcType 为 null）
fingerprint:
  error_class: "org.apache.ibatis.type.TypeException (dm.jdbc.driver.DMException: Not support this type)"
  signature: SecurityDepositDetailDao.selectListByQuery
  span: pay-service / [HTTP] POST 获取账户余额
  status: HTTP 200 + OTel Error
  keywords: [JdbcType, 达梦, DMException, MyBatis, security_deposit_detail, 押金, getAccountBalance]
symptom: 患者账户信息获取失败，但链路根 span 无错误、下游 HTTP 仍返回 200
root_cause: "pay-service 的 SecurityDepositDetailDao.selectListByQuery 绑定参数 #1（security_deposit_item_id）时 JdbcType 为 null，达梦驱动不支持该类型，抛出 DMException Not support this type"
confidence: 日志证实
fix:
  - 为该方法参数显式指定 JdbcType（如 jdbcType=VARCHAR/DATE/NUMERIC），或统一配置 mybatis jdbc-type-for-null
  - 排查 security_deposit_item_id / security_deposit_state_code 的入参来源，确认是否传入了驱动无法识别的类型
  - 修正异常状态映射，避免业务失败仍返回 HTTP 200
verification: 重跑「获取患者账户信息」接口，pay-service span 不再出现 Error，押金明细正常返回
occurrences: 1
first_seen: '2026-09-08T09:48:45+08:00'
last_seen: '2026-09-08T09:48:45+08:00'
trace_ids: [c4ea16342cf1a0526d22fa20d57c9e2a]
services: [gateway-service, outp-settle-service, pay-service]
owner: 中台研发部
---

# 达梦驱动不支持参数类型导致押金明细查询失败

## 现象

患者账户信息获取失败。但链路根 span（`gateway-service / gateway.server.requests`）`hasError=false`、状态 `Unset`，
下游两个 HTTP span 也返回 `http.response.status_code = 200`，只有 OTel 状态是 `Error`。

即：**业务已经失败，但网关层看到的仍是成功**，靠 HTTP 状态码无法发现，告警可能漏报。

## 根因

`pay-service` 执行押金明细查询时，参数 #1 的 JdbcType 为 null，达梦驱动无法确定类型：

```text
SecurityDepositDetailDao.selectListByQuery
  → org.apache.ibatis.type.TypeException: Error setting non null for parameter #1 with JdbcType null
  → dm.jdbc.driver.DMException: Not support this type
```

失败发生在**参数绑定阶段**，SQL 尚未执行，因此该次查询没有对应的 `[SQL]` span（链路里 3 个 `[SQL] SELECT` 均为成功查询）。

失败传播路径：

```text
gateway-service (58.63ms, 无错误)
└─ [ROUTE] lb://outp-settle-service (52.60ms)
   └─ outp-settle-service [HTTP] POST 获取患者账户信息 (52.25ms, Error/200)
      └─ [Feign] 调用 [支付域] getAccountBalance (39.99ms, Error)
         └─ pay-service [HTTP] POST 获取账户余额 (35.19ms, Error/200)
            └─ [SQL] SELECT ×3（成功，问题不在这里）
```

## 关键证据

| 证据 | 说明 |
|---|---|
| `dm.jdbc.driver.DMException: Not support this type` | 直接指向达梦驱动类型不支持 |
| `TypeException: Error setting non null for parameter #1 with JdbcType null` | 定位到参数绑定阶段，且 JdbcType 未指定 |
| `The error may involve ...SecurityDepositDetailDao.selectListByQuery` | 精确定位到 DAO 方法 |
| `MyBatisSystemException` + SkyWalking `chain[...] execute error on slot[...]` / `InnerRpcException` | 错误日志侧证实，共 6 条 ERROR |
| HTTP 200 + OTel Error（根 span 无错误） | 状态矛盾：网关层会误判成功 |
| 3 个 `[SQL] SELECT` span 均无错误 | 排除 SQL 语法/慢查询，问题在参数绑定 |

## 处置步骤

1. 为 `selectListByQuery` 涉及的参数显式指定 JdbcType（`jdbcType=VARCHAR` 等），或统一配置 MyBatis `jdbc-type-for-null`，消除 `JdbcType null`。
2. 核查 `security_deposit_item_id`、`security_deposit_state_code` 的入参来源与类型，确认是否有 null 或驱动不认识的类型被传入。
3. 修正异常状态映射：业务失败时不要返回 HTTP 200，否则网关与监控都无法识别。

## 验证方式

重跑「获取患者账户信息」接口（`outp-settle-service` → Feign `getAccountBalance`），确认 `pay-service` span 不再出现 `Error`，
押金明细数据正常返回。下次同类报错时，先用 `error_class` + `signature` 比对本案例判断是否复发。

## 修订记录

<!-- 仅当后续证据推翻或修正本案例结论时追加 -->

## 关联

- trace 报告：未单独保存（本次为 MCP 直连分析）
- 相关 trace：`c4ea16342cf1a0526d22fa20d57c9e2a`（2026-09-08T09:48:45+08:00，环境 dev）
