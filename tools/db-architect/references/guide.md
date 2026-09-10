# 数据库透视助手使用手册

> 不用打开数据库工具，一句话透视库表结构与数据。
>
> 把“打开数据库软件、切换连接、切换库、切换模式、找表、写 SQL、整理结果”的重复操作，压缩成一句自然语言。

`db-architect` 是一个面向研发、架构、实施和运维同学的数据库透视 Skill。它不是替代数据库客户端，而是把打开客户端之后的低价值重复路径自动化：查结构、出 DDL、查数据、找表、画 ER 图、比对差异，都优先通过自然语言触发。

## 1. 适用场景

`db-architect` 适合以下场景：

- 看单表或多表结构
- 快速拿到近似 DDL
- 导出配置类 `INSERT SQL`
- 扫描整个数据库做摸底
- 对比两个环境 / 两个库的结构差异
- 按业务域透视核心表、关联表和主流程

支持数据库：

- MySQL
- PostgreSQL
- Oracle
- 达梦

它重点省掉这些重复操作：

| 传统操作 | 使用数据库透视助手 |
|---|---|
| 打开数据库客户端 | 直接自然语言描述 |
| 找连接、切环境 | 自动按 profile 识别 |
| 切库、切 schema | 从语义中提取 schema |
| 展开表清单找表 | 支持表名前缀、中文注释搜索 |
| 手写字段 / 索引元数据 SQL | 自动扫描字段、注释、类型、主键、索引 |
| 手写建表语法 | 直接生成近似 DDL |
| 手写 SELECT 查配置 | 支持按字段条件查询并表格展示 |
| 手动画 ER 图 | 自动生成 Mermaid / draw.io |
| 人工比对环境结构 | 自动输出结构差异报告 |

---

## 2. 使用原则

### 2.1 默认入口

优先使用自然语言或 `/db-architect ...`，不要默认直接跑 Python 命令。

### 2.2 默认输出风格

- 默认先给结果，再补必要说明
- 用户只要 `DDL`、`SQL`、差异清单、字段列表时，直接返回对应内容
- 不要长篇背景说明

### 2.3 安全边界

- 只读分析
- 默认不修改数据库结构，不执行 INSERT / UPDATE / DELETE
- `export-row` 可查询展示数据，也可生成 SQL，但不自动执行
- `compare` 只比较结构，不比较数据内容

---

## 3. 环境准备

### 3.1 Python 依赖

```bash
pip install jaydebeapi JPype1
```

### 3.2 JDBC 驱动目录

```text
C:\Users\56479\.claude\skills\db-architect\scripts\jdbc_drivers\
```

常见驱动：

| 数据库 | 驱动 |
|---|---|
| MySQL | `mysql-connector-j-*.jar` |
| PostgreSQL | `postgresql-*.jar` |
| Oracle | `ojdbc8.jar` |
| 达梦 | `DmJdbcDriver.jar` / `DmJdbcDriver18.jar` |

### 3.3 `.env` 配置

单 profile 示例：

```ini
DB_PROFILE=MYSQL_DEFAULT

DB_MYSQL_DEFAULT_TYPE=mysql
DB_MYSQL_DEFAULT_HOST=192.168.5.143
DB_MYSQL_DEFAULT_PORT=3306
DB_MYSQL_DEFAULT_NAME=zoe_lw
DB_MYSQL_DEFAULT_USER=root
DB_MYSQL_DEFAULT_PASS=
DB_MYSQL_DEFAULT_SCHEMA=zoe_lw
```

多数据库名示例：

```ini
DB_PROFILE=PG_TEST

DB_PG_TEST_TYPE=postgresql
DB_PG_TEST_HOST=192.168.5.233
DB_PG_TEST_PORT=5432
DB_PG_TEST_NAME=skyprod,test,dev
DB_PG_TEST_USER=postgres
DB_PG_TEST_PASS=
DB_PG_TEST_SCHEMA=zoe_basic_sys
```

说明：

- `DB_PROFILE` 是默认 profile
- `DB_<PROFILE>_NAME` 支持逗号分隔多个数据库名
- 不传数据库名时默认取第一个
- 明确指定数据库名时切换数据库

---

## 4. 七种模式

### 4.1 `analyze`

适合：

- 看 1 张或几张表的结构
- 要字段说明、索引、主键、关系
- 要近似 DDL
- 要 Mermaid / draw.io 图

典型问法：

```text
帮我分析 PG_TEST 的 param_info 表，输出到 output/pg-param-info。
```

```text
/db-architect 分析 MYSQL_DEFAULT 的 lw_user_info 和 lw_user_extend，输出到 output/mysql-user，并生成 draw.io 图。
```

如果只要 DDL，可以直接这样说：

```text
只给我 PG_TEST 的 param_info 表 DDL，不要解释。
```

默认产出：

- `focus-analysis.md`
- `er-diagram.mmd`（按需）
- `er-diagram.drawio`（按需）

---

### 4.2 `export-row`

适合：

- 配置迁移
- 测试数据复制
- 想先拿 SQL 再人工执行

典型问法：

```text
从 DM_TEST 的 PARAM_INFO 表导出 param_code like 'USER_INIT%' 的 INSERT SQL，输出到 output/param_info_insert.sql。
```

```text
/db-architect 从 PG_TEST 的 dev 库导出 param_info 表里 param_code like 'USER_INIT%' 的数据。
```

默认产出：

- `xxx_insert.sql`

说明：

- 只生成 SQL
- 不执行插入
- 达梦 / Oracle 默认按小写表名和字段名输出 SQL

---

### 4.3 `scan`

适合：

- 第一次接手陌生库
- 做迁移摸底
- 看全库规模、关系、规范问题

典型问法：

```text
扫描 MYSQL_DEFAULT 整个库，输出到 output/mysql-scan。
```

```text
/db-architect 扫描 PG_TEST 整个库，最多展示 50 张表，不要生成 Mermaid。
```

默认产出：

- `db-report.md`
- `db-structure.json`
- `er-diagram.mmd`（如果不跳过）

---

### 4.4 `compare`

适合：

- 比较 dev / test / prod 结构漂移
- 发版前核对结构差异
- 看同名表在两个环境的字段、索引、外键变化

典型问法：

```text
帮我对比 PG_TEST 的 test 和 dev 库里 param_info 相关表的差异，输出到 output/pg-diff。
```

```text
/db-architect 对比 MYSQL_DEFAULT 的两个环境，检查 lw_user_info 和 lw_user_extend 的字段、索引和外键差异。
```

如果只要差异清单，可以直接这样说：

```text
只给我差异清单，不要报告说明。
```

默认产出：

- `compare-report.md`
- `compare-diff.json`
- `compare-fix.sql`（仅在显式要求时输出）

默认比较内容：

- 表是否存在
- 字段增删改
- 类型变化
- 是否可空变化
- 默认值变化
- 注释变化
- 主键变化
- 索引变化
- 外键变化
- 表注释变化

不做：

- 数据内容 diff
- 自动执行修复 SQL

---

### 4.5 `domain`

适合：

- 看某个业务域由哪些表组成
- 找核心表、关联表、中间表、字典表、日志表
- 做领域结构说明
- 接手新模块时快速建立业务视图

典型问法：

```text
帮我透视 PG_TEST 里 param 域的数据库结构，整理核心表、关系和主流程。
```

```text
/db-architect 分析 MYSQL_DEFAULT 的用户域，输出核心表、关联表和域内关系图。
```

如果只要核心表，可以直接这样说：

```text
只给我用户域的核心表清单。
```

默认产出：

- `domain-analysis.md`
- `domain-summary.json`
- `domain-er.mmd`
- `core-tables.md`

说明：

- `domain` 关注业务域视角，不是简单罗列表
- 主流程属于基于关系图的推断，不代表完整业务事实

### 4.6 `ddl`

适合：

- 只要某张表或一组表的建表语法
- 不需要完整分析报告
- 达梦 / Oracle / MySQL / PostgreSQL 的迁移评审

典型问法：

```text
查达梦 zoe_basic_sys 下 SYS_USER 表建表语法，只要 DDL。
```

```text
/db-architect 给我达梦 ZOE_BASIC_SYS 下 PARAM_INFO 表的建表语法，不要解释。
```

默认产出：

- stdout 直接输出 DDL
- `--output-ddl` 指定时写入 DDL 文件

说明：

- DDL 基于元数据近似生成，不承诺包含表空间、分区、存储参数等数据库专有细节
- 达梦 / Oracle 会生成 `COMMENT ON TABLE/COLUMN` 注释语句

---

### 4.7 `find-table`

适合：

- 按表名前缀找表
- 按表名关键字找表
- 按中文名 / 表注释找表

典型问法：

```text
查达梦里中文名包含 用户 的表。
```

```text
/db-architect 查达梦 ZOE_BASIC_SYS 下以 SYS 开头的表。
```

默认产出：

- Markdown 表格，包含表名和中文名/注释
- `--output-data` 指定时写入文件

---

## 5. 高频达梦查询

### 5.1 查建表语法

用户自然语言：

```text
查达梦 zoe_basic_sys 下 XXX 表建表语法，只要 DDL。
```

内部映射：

| 用户表达 | 内部参数 |
|---|---|
| 达梦 | `--profile DM_TEST` 或当前达梦 profile |
| `zoe_basic_sys` 下 | `--schema ZOE_BASIC_SYS` |
| `XXX` 表 | `--table XXX` |
| 建表语法 / 只要 DDL | `ddl` |

输出原则：

- 直接返回 DDL
- 不输出完整 `focus-analysis.md` 报告说明

### 5.2 按 code 查数据

用户自然语言：

```text
查达梦 zoe_basic 下 XXX 表 code 字段为 YYY 的数据，直接展示。
```

内部映射：

| 用户表达 | 内部参数 |
|---|---|
| `zoe_basic` 下 | `--schema ZOE_BASIC` |
| `XXX` 表 | `--table XXX` |
| `code 字段为 YYY` | `--filter-column CODE --filter-value YYY` |
| 直接展示 | `--format table` |

输出原则：

- 默认 Markdown 表格展示
- 可按用户要求导出 `CSV`、`JSON` 或 `INSERT SQL`
- 只读查询，不执行插入

### 5.3 按中文名或前缀找表

用户自然语言：

```text
查达梦中中文名包含 用户 的表。
```

```text
查达梦中以 SYS 开头的表。
```

内部映射：

| 用户表达 | 内部参数 |
|---|---|
| 中文名 / 中文为 / 表注释包含 | `--table-comment-like` |
| 以 SYS 开头 | `--table-prefix SYS` |
| 表名包含 SYS | `--table-like SYS` |

说明：

- `--table-prefix` 是严格前缀匹配
- `--table-like` 是包含匹配
- 中文名通常来自表注释 `TABLE_COMMENT` / `COMMENTS`

### 5.4 达梦 schema 口径

用户说“达梦某库下”时，优先理解为 schema：

| 用户说法 | 内部 schema |
|---|---|
| `zoe_basic_sys` | `ZOE_BASIC_SYS` |
| `zoe_basic` | `ZOE_BASIC` |

如果 profile 中还配置了多个数据库名，再根据上下文映射 `--db`。

---

## 6. 常用表达与内部参数映射

| 用户表达 | 内部参数 |
|---|---|
| 数据库环境 | `--profile` |
| 数据库名 | `--db` |
| schema | `--schema` |
| 单表 | `--table` |
| 多表 | `--tables` |
| 表名关键字 | `--table-like` |
| 表名前缀 | `--table-prefix` |
| 表中文名 / 表注释 | `--table-comment-like` |
| 只要 DDL / 建表语法 | `ddl` |
| code 字段为 xxx | `--filter-column CODE --filter-value xxx` |
| 直接展示数据 | `--format table` |
| 导出 CSV / JSON / INSERT | `--format csv/json/insert` |
| 输出 DDL 文件 | `--output-ddl` |
| 数据输出文件 | `--output-data` |
| 左侧 profile | `--left-profile` |
| 右侧 profile | `--right-profile` |
| 左侧数据库名 | `--left-db` |
| 右侧数据库名 | `--right-db` |
| 左侧 schema | `--left-schema` |
| 右侧 schema | `--right-schema` |
| compare 生成 SQL 草案 | `--with-sql` |
| domain 名称 | `--domain-name` |
| 输出目录 | `--output` |
| 生成 Mermaid | `--with-er` |
| 生成 draw.io | `--with-drawio` |
| 导出过滤条件 | `--where` |
| SQL 输出路径 | `--output-sql` |
| 跳过 Mermaid | `--no-mermaid` |
| 扫描数量限制 | `--max-tables` |

---

## 7. 典型输出文件

| 文件 | 说明 |
|---|---|
| `focus-analysis.md` | 聚焦表分析报告 |
| `db-report.md` | 全库扫描报告 |
| `db-structure.json` | 原始结构数据 |
| `er-diagram.mmd` | Mermaid 图 |
| `er-diagram.drawio` | draw.io 图 |
| `xxx_insert.sql` | 导出的 INSERT SQL |
| `compare-report.md` | 差异报告 |
| `compare-diff.json` | 结构化差异 |
| `compare-fix.sql` | 修复 SQL 草案 |
| `domain-analysis.md` | 业务域分析报告 |
| `domain-summary.json` | 业务域结构化结果 |
| `domain-er.mmd` | 业务域 ER 图 |
| `core-tables.md` | 核心表清单 |

---

## 8. 输出简洁规则

遇到以下要求时，直接给结果：

- “给我 DDL”
- “只要建表语法”
- “只要差异清单”
- “只要 SQL”
- “只给核心表”
- “不要解释”

推荐响应方式：

- 先给目标内容
- 必要时只补 1 到 3 行说明
- 不重复描述数据库背景、模式说明、使用原则

---

## 9. 常见故障排查

### 8.1 连接失败

优先检查：

- `.env` 是否存在
- `profile` 是否写对
- JDBC 驱动是否放到正确目录
- 数据库名 / schema 是否匹配
- IP、端口、账号密码是否正确

### 8.2 驱动缺失

确认目录中是否存在对应 JAR：

```text
C:\Users\56479\.claude\skills\db-architect\scripts\jdbc_drivers\
```

### 8.3 找不到表

优先检查：

- 表名大小写
- 当前 `db` 是否正确
- `schema` 是否正确
- `table-like` 是否过窄

### 8.4 关系图不完整

原因通常是：

- 数据库没有显式外键
- 命名不规范，推断关系不够强
- 需要手工补充业务解释

---

## 10. 维护者直连命令

只在维护和排障时使用。

### analyze

```bash
python db_scanner.py analyze --env .env --profile PG_TEST --table param_info --output output/pg-param-info
```

### export-row

```bash
python db_scanner.py export-row --env .env --profile DM_TEST --table PARAM_INFO --where "param_code like 'USER_INIT%'" --output-sql output/param_info_insert.sql
```

### scan

```bash
python db_scanner.py scan --env .env --profile MYSQL_DEFAULT --output output/mysql-scan
```

### compare

```bash
python db_scanner.py compare --env .env --left-profile PG_TEST --left-db test --right-profile PG_TEST --right-db dev --table-like param --output output/pg-diff --with-sql
```

### ddl

```bash
python db_scanner.py ddl --env .env --profile DM_TEST --schema ZOE_BASIC_SYS --table SYS_USER
```

### find-table

```bash
python db_scanner.py find-table --env .env --profile DM_TEST --schema ZOE_BASIC_SYS --table-comment-like 用户
```

```bash
python db_scanner.py find-table --env .env --profile DM_TEST --schema ZOE_BASIC_SYS --table-prefix SYS
```

### export-row table 展示

```bash
python db_scanner.py export-row --env .env --profile DM_TEST --schema ZOE_BASIC --table PARAM_INFO --filter-column CODE --filter-value USER_INIT --format table --limit 50
```

