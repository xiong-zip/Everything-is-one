# db-architect 规范检查规则

> 本文件定义数据库架构透视器的所有内置规范检查规则。
> 用户可通过 `--rules` 参数指定自定义规则文件覆盖默认规则。

## 规则分级

| 级别 | 含义 | 处理建议 |
|------|------|---------|
| CRITICAL | 红线违规，必须修复 | 阻断发布 |
| HIGH | 严重问题，应尽快修复 | 纳入迭代 |
| MEDIUM | 规范偏离，建议修复 | 下次迭代处理 |
| LOW | 优化建议，可选修复 | 视情况处理 |

---

## CRITICAL 级规则

### amount-decimal

**名称**：金额字段类型检查

**描述**：金额类字段必须使用 `decimal`/`numeric` 类型，禁止使用 `double`、`float`、`int`、`bigint`。

**匹配条件**：字段名包含以下关键词之一，且类型不是 `decimal`/`numeric`：

- amount, price, fee, cost, money, pay, salary, deposit, refund, balance, total, subtotal

**修复建议**：

```sql
ALTER TABLE `{table}` MODIFY `{column}` decimal(10,2) NOT NULL DEFAULT '0.00' COMMENT '{comment}';
```

**依据**：浮点数存在精度丢失（如 `0.1 + 0.2 != 0.3`），金融/金额场景必须使用精确数值类型。

---

## HIGH 级规则

### uuid-pk

**名称**：主键 UUID 规范

**描述**：业务表主键应为 `varchar(36)` UUID，不使用自增整数。自增整数在分布式场景下有冲突风险，且暴露数据量。

**匹配条件**：主键字段类型为 `int`/`bigint`/`number` 等整数类型。

**例外**：日志表、临时表可使用自增主键。

**修复建议**：

```sql
ALTER TABLE `{table}` MODIFY `{pk_column}` varchar(36) NOT NULL COMMENT '主键UUID';
```

### valid-flag

**名称**：软删除字段检查

**描述**：业务表应包含 `valid_flag varchar(1) DEFAULT '1'` 软删除字段，物理删除存在数据恢复风险。

**匹配条件**：表中不存在 `valid_flag` 或 `is_valid` 字段。

**例外**：配置表、字典表等极少删除的表可豁免。

**修复建议**：

```sql
ALTER TABLE `{table}` ADD `valid_flag` varchar(1) DEFAULT '1' COMMENT '有效标志(1有效 0无效)';
```

### utf8mb4

**名称**：字符集规范（MySQL 专属）

**描述**：MySQL 表字符集应为 `utf8mb4`，`utf8`（3字节）无法存储 emoji 和部分中文生僻字。

**匹配条件**：表的 `TABLE_COLLATION` 不以 `utf8mb4` 开头。

**修复建议**：

```sql
ALTER TABLE `{table}` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
```

---

## MEDIUM 级规则

### auto-timestamp

**名称**：时间字段自动更新（MySQL 专属）

**描述**：表应包含 `created_time` 和 `modified_time` 字段，且 `modified_time` 应设置 `ON UPDATE CURRENT_TIMESTAMP`。

**匹配条件**：时间字段存在但缺少 `ON UPDATE CURRENT_TIMESTAMP`，或缺少时间字段。

**修复建议**：

```sql
ALTER TABLE `{table}` ADD `created_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间';
ALTER TABLE `{table}` ADD `modified_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '修改时间';
```

### idx-prefix

**名称**：索引命名规范

**描述**：非主键、非唯一约束的索引应以 `idx_` 前缀命名，便于区分和检索。

**匹配条件**：索引名不以 `idx_`、`uk_`、`PRIMARY` 开头。

**修复建议**：重命名索引（各数据库语法不同）。

---

## LOW 级规则

### field-comment

**名称**：字段注释覆盖率

**描述**：超过 50% 的字段缺少注释时告警。注释是团队协作的基础，缺少注释会增加理解成本。

**匹配条件**：表中无注释字段占比 > 50%（排除主键字段）。

### table-comment

**名称**：表注释检查

**描述**：每张表应有注释说明其业务含义。

**匹配条件**：表的 `TABLE_COMMENT` 为空。

---

## 自定义规则文件格式

用户可通过 `--rules custom_rules.json` 指定自定义规则，格式如下：

```json
{
  "overrides": [
    {
      "rule": "uuid-pk",
      "enabled": false,
      "reason": "本项目使用自增主键"
    }
  ],
  "extras": [
    {
      "id": "custom-prefix",
      "level": "MEDIUM",
      "name": "表名前缀检查",
      "description": "业务表名应以模块前缀开头（如 sys_, biz_）",
      "check": "table_name_prefix",
      "params": {
        "allowed_prefixes": ["sys_", "biz_", "log_", "tmp_"]
      }
    }
  ]
}
```

| 字段 | 说明 |
|------|------|
| `overrides` | 覆盖内置规则（禁用或调整级别） |
| `extras` | 新增自定义规则 |
| `rule`/`id` | 规则标识 |
| `enabled` | 是否启用（仅 overrides） |
| `level` | CRITICAL / HIGH / MEDIUM / LOW |
| `check` | 检查逻辑标识（需在 db_scanner.py 中实现对应函数） |
| `params` | 检查参数 |
