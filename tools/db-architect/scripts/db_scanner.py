#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
db_scanner.py - 数据库架构透视器核心扫描脚本

通过 JDBC（jaydebeapi）连接数据库，扫描 information_schema，
输出 JSON 结构数据供 SKILL.md 生成报告和 ER 图。

支持数据库：MySQL / Oracle / PostgreSQL / 达梦
"""

import argparse
import csv
import io
import json
import os
import sys
import re
from datetime import date, datetime, time
from decimal import Decimal
from pathlib import Path
from typing import Any

def text_or_empty(value: Any) -> str:
    """将 JDBC 返回的 NULL/Java 对象安全转换为字符串"""
    if value is None:
        return ""
    return str(value)


def normalize_jdbc_value(value: Any) -> Any:
    """将 JDBC 返回值转换为 Python/JSON 友好类型"""
    if value is None:
        return None
    if isinstance(value, (str, int, float, bool)):
        return value
    if isinstance(value, Decimal):
        return str(value)
    if isinstance(value, datetime):
        return value.strftime("%Y-%m-%d %H:%M:%S")
    if isinstance(value, date):
        return value.strftime("%Y-%m-%d")
    if isinstance(value, time):
        return value.strftime("%H:%M:%S")

    text = str(value)
    if re.fullmatch(r"-?\d+", text):
        try:
            return int(text)
        except ValueError:
            return text
    if re.fullmatch(r"-?\d+\.\d+", text):
        return text
    return text


FIELD_LABEL_OVERRIDES = {
    "AFFILIATED_INST_ID": "归属机构ID",
    "ALLOW_CUST_DISTRIBUTING": "允许定制分发",
    "AUDITOR_NAME": "审核人姓名",
    "AUDIT_STATE_CODE": "审核状态编码",
    "AUDIT_TIME": "审核时间",
    "CONFIG_LAYERED_CODE": "配置层级编码",
    "CREATE_TIME": "创建时间",
    "CREATE_USER_ID": "创建人ID",
    "CUSTOM_MEMO": "定制备注",
    "DATA_ARCHIVE_ID": "数据归档ID",
    "DATA_STATE_CODE": "数据状态编码",
    "DE_ID": "数据元ID",
    "DELIVER_THEME_CODE": "分发主题编码",
    "DESC_INFO": "描述信息",
    "EFFECTIVE_CONFIG": "生效配置",
    "ITEM_CAN_MODIFY": "条目可修改",
    "MEMO_INFO": "备注信息",
    "MODIFY_TIME": "修改时间",
    "MODIFY_USER_ID": "修改人ID",
    "ONLINE_MUST_BE_CHANGED_FLAG": "上线必须变更标志",
    "PARAM_AFFILIATED_ELEMENT": "参数归属元素",
    "PARAM_AFFILIATED_ELEMENT_VALUE": "参数归属元素值",
    "PARAM_CHINESE_NAME": "参数中文名",
    "PARAM_CLASS_CODE": "参数分类编码",
    "PARAM_CLASS_MODE_CODE": "参数分类模式编码",
    "PARAM_CODE": "参数编码",
    "PARAM_CONFIG": "参数配置",
    "PARAM_CREATE_NEW_MODE": "参数新建模式",
    "PARAM_CUST_ID": "参数定制ID",
    "PARAM_DEFAULT_VALUE": "参数默认值",
    "PARAM_HIST_ID": "参数历史ID",
    "PARAM_ID": "参数ID",
    "PARAM_LEVEL_BUSINESS_VALUE": "参数层级业务值",
    "PARAM_LEVEL_CODE": "参数层级编码",
    "PARAM_LOG_ID": "参数日志ID",
    "PARAM_MULT_SEL_FLAG": "参数多选标志",
    "PARAM_TAG_CUST_ID": "参数标签定制ID",
    "PARAM_TAG_CODE": "参数标签编码",
    "PARAM_TYPE_CODE": "参数类型编码",
    "PARAM_VALUE": "参数值",
    "PARAM_VALUE_DICT_DESC": "参数值字典说明",
    "PARAM_VALUE_TYPE": "参数值类型",
    "PARENT_PARAM_ID": "父参数ID",
    "ROLLBACK_REASON": "回滚原因",
    "SORT_NO": "排序号",
    "TENANT_ID": "租户ID",
}

TOKEN_LABELS = {
    "AFFILIATED": "归属",
    "ALLOW": "允许",
    "ARCHIVE": "归档",
    "AUDIT": "审核",
    "AUDITOR": "审核人",
    "BUSINESS": "业务",
    "CAN": "可",
    "CHANGED": "变更",
    "CHINESE": "中文",
    "CLASS": "分类",
    "CODE": "编码",
    "CONFIG": "配置",
    "CREATE": "创建",
    "CUST": "定制",
    "CUSTOM": "定制",
    "DATA": "数据",
    "DEFAULT": "默认",
    "DELIVER": "分发",
    "DESC": "描述",
    "DE": "数据元",
    "EFFECTIVE": "生效",
    "ELEMENT": "元素",
    "FLAG": "标志",
    "HIST": "历史",
    "ID": "ID",
    "INFO": "信息",
    "INST": "机构",
    "ITEM": "条目",
    "LAYERED": "层级",
    "LEVEL": "层级",
    "LOG": "日志",
    "MEMO": "备注",
    "MODIFY": "修改",
    "MULT": "多",
    "MUST": "必须",
    "NAME": "名称",
    "NEW": "新建",
    "NO": "号",
    "ONLINE": "上线",
    "PARAM": "参数",
    "PARENT": "父",
    "REASON": "原因",
    "ROLLBACK": "回滚",
    "SEL": "选",
    "SORT": "排序",
    "STATE": "状态",
    "TAG": "标签",
    "TENANT": "租户",
    "THEME": "主题",
    "TIME": "时间",
    "TYPE": "类型",
    "USER": "人",
    "VALUE": "值",
}


def infer_column_label(column_name: str) -> str:
    """按字段名推断中文说明，作为缺注释时的兜底"""
    normalized = text_or_empty(column_name).upper()
    if not normalized:
        return ""
    if normalized in FIELD_LABEL_OVERRIDES:
        return FIELD_LABEL_OVERRIDES[normalized]

    parts = [part for part in normalized.split("_") if part]
    translated_parts = [TOKEN_LABELS.get(part, part) for part in parts]
    label = "".join(translated_parts)
    replacements = {
        "参数中文名称": "参数中文名",
        "审核人名称": "审核人姓名",
        "创建人ID": "创建人ID",
        "修改人ID": "修改人ID",
        "排序号": "排序号",
    }
    return replacements.get(label, label)



def get_column_label(column: dict[str, Any]) -> str:
    """优先返回数据库注释，缺失时返回推断中文说明"""
    comment = text_or_empty(column.get("COLUMN_COMMENT", column.get("COMMENTS", ""))).strip()
    if comment:
        return comment
    inferred = infer_column_label(column.get("COLUMN_NAME", ""))
    if inferred:
        return f"{inferred}（推断）"
    return "-"


# ---------------------------------------------------------------------------
# JDBC 驱动配置
# ---------------------------------------------------------------------------

SCRIPT_DIR = Path(__file__).resolve().parent
DRIVERS_DIR = SCRIPT_DIR / "jdbc_drivers"

JDBC_CONFIG = {
    "mysql": {
        "driver_class": "com.mysql.cj.jdbc.Driver",
        "jar_pattern": "mysql-connector-j-*.jar",
        "default_port": 3306,
        "jdbc_url_tpl": "jdbc:mysql://{host}:{port}/{db}?useSSL=false&characterEncoding=utf8",
    },
    "oracle": {
        "driver_class": "oracle.jdbc.OracleDriver",
        "jar_pattern": "ojdbc8.jar",
        "default_port": 1521,
        "jdbc_url_tpl": "jdbc:oracle:thin:@//{host}:{port}/{db}",
    },
    "postgresql": {
        "driver_class": "org.postgresql.Driver",
        "jar_pattern": "postgresql-*.jar",
        "default_port": 5432,
        "jdbc_url_tpl": "jdbc:postgresql://{host}:{port}/{db}",
    },
    "dameng": {
        "driver_class": "dm.jdbc.driver.DmDriver",
        "jar_pattern": "DmJdbcDriver*.jar",
        "default_port": 5236,
        "jdbc_url_tpl": "jdbc:dm://{host}:{port}/{db}",
    },
}


def find_jar(db_type: str) -> str:
    """在 jdbc_drivers/ 目录下查找匹配的 JAR 文件"""
    cfg = JDBC_CONFIG[db_type]
    pattern = cfg["jar_pattern"]
    matches = list(DRIVERS_DIR.glob(pattern))
    if not matches:
        raise FileNotFoundError(
            f"未找到 {db_type} 的 JDBC 驱动 JAR。"
            f"请将 {pattern} 放到 {DRIVERS_DIR} 目录。"
        )
    return str(matches[0])


def build_jdbc_url(db_type: str, host: str, port: int, db: str) -> str:
    cfg = JDBC_CONFIG[db_type]
    return cfg["jdbc_url_tpl"].format(host=host, port=port, db=db)


# ---------------------------------------------------------------------------
# .env 解析
# ---------------------------------------------------------------------------

def parse_env(env_path: str) -> dict[str, str]:
    """解析 .env 文件，返回键值字典"""
    env = {}
    if not os.path.isfile(env_path):
        return env
    with open(env_path, encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line or line.startswith("#"):
                continue
            if "=" in line:
                k, v = line.split("=", 1)
                env[k.strip()] = v.strip()
    return env


def resolve_profile_config(env: dict[str, str], profile: str | None) -> dict[str, str]:
    """支持多数据库 profile，兼容原有单连接写法"""
    if not env:
        return {}

    selected_profile = profile or env.get("DB_PROFILE", "").strip()
    if not selected_profile:
        return env

    prefix = f"DB_{selected_profile.upper()}_"
    profile_keys = {}
    for key, value in env.items():
        if key.startswith(prefix):
            profile_key = "DB_" + key[len(prefix):]
            profile_keys[profile_key] = value

    merged = dict(env)
    merged.update(profile_keys)

    db_name = text_or_empty(merged.get("DB_NAME", "")).strip()
    if db_name and "," in db_name:
        db_names = [item.strip() for item in db_name.split(",") if item.strip()]
        merged["DB_NAMES"] = ",".join(db_names)
        merged["DB_NAME"] = db_names[0] if db_names else ""

    merged["DB_PROFILE"] = selected_profile
    return merged


# ---------------------------------------------------------------------------
# SQL 模板加载
# ---------------------------------------------------------------------------

SQL_TEMPLATES_DIR = SCRIPT_DIR.parent / "references" / "sql-templates"


def load_sql(db_type: str, query_name: str) -> str:
    """从 sql-templates/ 目录加载 SQL 模板"""
    sql_file = SQL_TEMPLATES_DIR / f"{db_type}" / f"{query_name}.sql"
    if not sql_file.is_file():
        # 降级到通用模板
        sql_file = SQL_TEMPLATES_DIR / "common" / f"{query_name}.sql"
    if not sql_file.is_file():
        raise FileNotFoundError(f"SQL 模板不存在：{query_name}（db_type={db_type}）")
    return sql_file.read_text(encoding="utf-8")


# ---------------------------------------------------------------------------
# 数据库扫描
# ---------------------------------------------------------------------------

def get_connection(db_type: str, host: str, port: int, db: str, user: str, password: str):
    """建立 JDBC 连接"""
    try:
        import jaydebeapi
    except ImportError:
        print("错误：缺少依赖 jaydebeapi，请执行：pip install jaydebeapi JPype1")
        sys.exit(1)

    cfg = JDBC_CONFIG[db_type]
    jar_path = find_jar(db_type)
    jdbc_url = build_jdbc_url(db_type, host, port, db)

    conn = jaydebeapi.connect(
        cfg["driver_class"],
        jdbc_url,
        [user, password],
        jar_path,
    )
    return conn


def execute_query(conn, sql: str, params: tuple = ()) -> list[dict[str, Any]]:
    """执行 SQL 查询，返回字典列表"""
    curs = conn.cursor()
    normalized_sql = sql.replace("%s", "?").replace(":1", "?")
    normalized_sql = re.sub(r"--[^\n]*(\n|$)", " ", normalized_sql)
    normalized_sql = normalized_sql.strip().rstrip(";")
    curs.execute(normalized_sql, params)
    columns = [text_or_empty(desc[0]).upper() for desc in curs.description]
    rows = curs.fetchall()
    curs.close()
    return [
        {col_name: normalize_jdbc_value(value) for col_name, value in zip(columns, row)}
        for row in rows
    ]


def quote_sql_identifier(db_type: str, identifier: str) -> str:
    """按数据库类型包装标识符"""
    if db_type == "mysql":
        return f"`{identifier}`"
    return f'"{identifier}"'


def sql_literal(value: Any) -> str:
    """将 Python/JDBC 值转换为 SQL 字面量"""
    if value is None:
        return "NULL"
    if isinstance(value, bool):
        return "1" if value else "0"
    if isinstance(value, (int, float, Decimal)):
        return str(value)
    if isinstance(value, datetime):
        return f"'{value.strftime('%Y-%m-%d %H:%M:%S')}'"
    if isinstance(value, date):
        return f"'{value.strftime('%Y-%m-%d')}'"
    if isinstance(value, time):
        return f"'{value.strftime('%H:%M:%S')}'"

    text = str(value)
    text = text.replace("'", "''")
    return f"'{text}'"


def build_row_insert_sql(db_type: str, table_name: str, rows: list[dict[str, Any]], schema: str = "", lowercase: bool = False) -> str:
    """将查询结果转换为 INSERT SQL 文本

    Args:
        lowercase: 若为 True，表名和字段名均输出小写
    """
    if not rows:
        return f"-- 未查询到数据：{table_name}\n"

    columns = list(rows[0].keys())
    if lowercase:
        out_columns = [c.lower() for c in columns]
        out_table = table_name.lower()
        out_schema = schema.lower() if schema else ""
    else:
        out_columns = columns
        out_table = table_name
        out_schema = schema

    quoted_columns = ", ".join(quote_sql_identifier(db_type, c) for c in out_columns)
    quoted_table = quote_sql_identifier(db_type, out_table)

    if out_schema:
        quoted_schema = quote_sql_identifier(db_type, out_schema)
        full_table = f"{quoted_schema}.{quoted_table}"
    else:
        full_table = quoted_table

    statements = [f"-- 导出表：{table_name}", f"-- 记录数：{len(rows)}", ""]
    for row in rows:
        values = ", ".join(sql_literal(row.get(col)) for col in columns)
        statements.append(f"INSERT INTO {full_table} ({quoted_columns}) VALUES ({values});")
    statements.append("")
    return "\n".join(statements)


def resolve_connection_spec(env: dict[str, str], profile: str | None,
                            db_type: str = "", host: str = "", port: int | None = None,
                            db_name: str = "", user: str = "", password: str = "",
                            schema: str = "") -> dict[str, Any]:
    """从 .env profile 和命令行参数解析单侧连接信息"""
    resolved = resolve_profile_config(env, profile)
    resolved_db_type = db_type or resolved.get("DB_TYPE", "mysql")
    resolved_host = host or resolved.get("DB_HOST", "localhost")
    resolved_port = port or int(resolved.get("DB_PORT", JDBC_CONFIG[resolved_db_type]["default_port"]))
    resolved_db_name = db_name or resolved.get("DB_NAME", "")
    resolved_db_names = [item.strip() for item in text_or_empty(resolved.get("DB_NAMES", "")).split(",") if item.strip()]
    if not resolved_db_name:
        if resolved_db_names:
            resolved_db_name = resolved_db_names[0]
        else:
            raise ValueError("未指定数据库名。请在 .env 中设置 DB_NAME 或使用 --db 参数。")

    return {
        "profile": text_or_empty(profile or resolved.get("DB_PROFILE", "")).strip(),
        "db_type": resolved_db_type,
        "host": resolved_host,
        "port": resolved_port,
        "db_name": resolved_db_name,
        "db_names": resolved_db_names,
        "user": user or resolved.get("DB_USER", ""),
        "password": password or resolved.get("DB_PASS", ""),
        "schema": schema or resolved.get("DB_SCHEMA", ""),
    }


def build_where_clause(db_type: str, filter_column: str = "", filter_value: str = "", where_clause: str = "") -> str:
    """构建只读查询条件，优先使用结构化等值条件"""
    if filter_column:
        safe_column = quote_sql_identifier(db_type, filter_column)
        return f"{safe_column} = {sql_literal(filter_value)}"
    return where_clause or ""


def build_select_sql(db_type: str, table_name: str, schema: str = "",
                     where_clause: str = "", limit: int | None = None) -> str:
    """构建表数据查询 SQL"""
    safe_table = quote_sql_identifier(db_type, table_name)
    if schema:
        safe_schema = quote_sql_identifier(db_type, schema)
        full_from = f"{safe_schema}.{safe_table}"
    else:
        full_from = safe_table

    sql = f"SELECT * FROM {full_from}"
    # WHERE 子句整体加括号：OR 条件下 ROWNUM 限行才不会因优先级失效
    if where_clause:
        sql += f" WHERE ({where_clause})"
    if limit and limit > 0:
        if db_type in ("oracle", "dameng"):
            if where_clause:
                sql += f" AND ROWNUM <= {limit}"
            else:
                sql += f" WHERE ROWNUM <= {limit}"
        else:
            sql += f" LIMIT {limit}"
    return sql


def query_table_rows(conn, db_type: str, table_name: str, schema: str = "",
                     where_clause: str = "", limit: int | None = None) -> list[dict[str, Any]]:
    """按条件查询表数据，只读返回行列表"""
    sql = build_select_sql(db_type, table_name, schema=schema, where_clause=where_clause, limit=limit)
    return execute_query(conn, sql)


def render_rows_markdown(rows: list[dict[str, Any]]) -> str:
    """将查询结果渲染为 Markdown 表格"""
    if not rows:
        return "未查询到数据。"

    columns = list(rows[0].keys())
    lines = [
        "| " + " | ".join(columns) + " |",
        "| " + " | ".join("---" for _ in columns) + " |",
    ]
    for row in rows:
        values = []
        for column in columns:
            value = row.get(column)
            text = "" if value is None else str(value)
            text = text.replace("|", "\\|").replace("\r", " ").replace("\n", " ")
            values.append(text)
        lines.append("| " + " | ".join(values) + " |")
    return "\n".join(lines)


def render_rows_csv(rows: list[dict[str, Any]]) -> str:
    """将查询结果渲染为 CSV 文本"""
    if not rows:
        return ""
    output = io.StringIO()
    columns = list(rows[0].keys())
    writer = csv.DictWriter(output, fieldnames=columns, lineterminator="\n")
    writer.writeheader()
    writer.writerows(rows)
    return output.getvalue()


def render_rows_output(rows: list[dict[str, Any]], output_format: str,
                       db_type: str, table_name: str, schema: str = "",
                       lowercase: bool = False) -> str:
    """按指定格式渲染行数据"""
    if output_format == "insert":
        return build_row_insert_sql(db_type, table_name, rows, schema=schema, lowercase=lowercase)
    if output_format == "table":
        return render_rows_markdown(rows)
    if output_format == "json":
        return json.dumps(rows, ensure_ascii=False, indent=2)
    if output_format == "csv":
        return render_rows_csv(rows)
    raise ValueError(f"不支持的输出格式：{output_format}")


def scan_tables(conn, db_type: str, db_name: str, schema: str = None) -> list[dict]:
    """扫描表清单"""
    sql = load_sql(db_type, "tables")
    params = ()
    if db_type == "mysql":
        params = (db_name,)
    elif db_type in ("oracle", "dameng"):
        s = schema or db_name.upper()
        params = (s,)
    elif db_type == "postgresql":
        s = schema or "public"
        params = (s,)
    return execute_query(conn, sql, params)


def scan_columns(conn, db_type: str, db_name: str, schema: str = None) -> list[dict]:
    """扫描字段清单"""
    sql = load_sql(db_type, "columns")
    params = ()
    if db_type == "mysql":
        params = (db_name,)
    elif db_type in ("oracle", "dameng"):
        s = schema or db_name.upper()
        params = (s,)
    elif db_type == "postgresql":
        s = schema or "public"
        params = (s,)
    return execute_query(conn, sql, params)


def scan_indexes(conn, db_type: str, db_name: str, schema: str = None) -> list[dict]:
    """扫描索引清单"""
    sql = load_sql(db_type, "indexes")
    params = ()
    if db_type == "mysql":
        params = (db_name,)
    elif db_type in ("oracle", "dameng"):
        s = schema or db_name.upper()
        params = (s,)
    elif db_type == "postgresql":
        s = schema or "public"
        params = (s,)
    return execute_query(conn, sql, params)


def scan_foreign_keys(conn, db_type: str, db_name: str, schema: str = None) -> list[dict]:
    """扫描外键关系"""
    sql = load_sql(db_type, "foreign_keys")
    params = ()
    if db_type == "mysql":
        params = (db_name,)
    elif db_type in ("oracle", "dameng"):
        s = schema or db_name.upper()
        params = (s,)
    elif db_type == "postgresql":
        s = schema or "public"
        params = (s,)
    return execute_query(conn, sql, params)


def build_snapshot(db_type: str, host: str, port: int, db_name: str,
                   schema: str, tables: list[dict], columns: list[dict],
                   indexes: list[dict], foreign_keys: list[dict],
                   inferred_relations: list[dict], profile: str = "") -> dict[str, Any]:
    """构建统一快照对象，供 compare/domain 复用"""
    return {
        "db_type": db_type,
        "host": host,
        "port": port,
        "profile": profile,
        "database": db_name,
        "schema": schema,
        "tables": tables,
        "columns": columns,
        "indexes": indexes,
        "foreign_keys": foreign_keys,
        "inferred_relations": inferred_relations,
    }


def summarize_issues(issues: list[dict[str, Any]]) -> tuple[int, int, int, int]:
    """汇总规范问题数量"""
    critical = sum(1 for i in issues if i["level"] == "CRITICAL")
    high = sum(1 for i in issues if i["level"] == "HIGH")
    medium = sum(1 for i in issues if i["level"] == "MEDIUM")
    low = sum(1 for i in issues if i["level"] == "LOW")
    return critical, high, medium, low


def build_scan_result(snapshot: dict[str, Any], issues: list[dict[str, Any]]) -> dict[str, Any]:
    """构建兼容现有 analyze/scan 的结果对象"""
    result = dict(snapshot)
    result["issues"] = issues
    return result


def scan_snapshot(conn, db_type: str, host: str, port: int, db_name: str,
                  schema: str = "", profile: str = "") -> dict[str, Any]:
    """执行一次完整扫描并返回统一快照"""
    print("扫描表清单...")
    tables = scan_tables(conn, db_type, db_name, schema or None)
    print(f"  发现 {len(tables)} 张表")

    print("扫描字段信息...")
    columns = scan_columns(conn, db_type, db_name, schema or None)
    print(f"  发现 {len(columns)} 个字段")

    print("扫描索引信息...")
    indexes = scan_indexes(conn, db_type, db_name, schema or None)
    print(f"  发现 {len(indexes)} 个索引")

    print("扫描外键关系...")
    foreign_keys = scan_foreign_keys(conn, db_type, db_name, schema or None)
    print(f"  发现 {len(foreign_keys)} 个显式外键")

    print("推断隐式关联...")
    table_names = [t.get("TABLE_NAME", "") for t in tables]
    inferred = infer_relations(table_names, columns)
    print(f"  推断 {len(inferred)} 个隐式关联")

    return build_snapshot(
        db_type=db_type,
        host=host,
        port=port,
        db_name=db_name,
        schema=schema,
        tables=tables,
        columns=columns,
        indexes=indexes,
        foreign_keys=foreign_keys,
        inferred_relations=inferred,
        profile=profile,
    )


def normalize_name(value: Any) -> str:
    """统一名称比较口径，避免大小写导致误判"""
    return text_or_empty(value).strip().upper()


def normalize_nullable(value: Any) -> str:
    """统一可空标识"""
    nullable = text_or_empty(value).strip().upper()
    if nullable in ("N", "NO", "FALSE", "0"):
        return "NO"
    return "YES"


def normalize_comment(value: Any) -> str:
    """统一注释口径"""
    comment = text_or_empty(value).strip()
    return comment


def normalize_default(value: Any) -> str:
    """统一默认值口径，减少表达差异噪音"""
    default = text_or_empty(value).strip()
    if not default:
        return ""
    normalized = default.strip("'").strip('"').strip()
    return re.sub(r"\s+", " ", normalized).upper()


def parse_column_type(column_type: str) -> dict[str, Any]:
    """拆分字段类型，便于 compare 做结构化比较"""
    raw = text_or_empty(column_type).strip()
    upper = raw.upper()
    match = re.match(r"^([A-Z0-9_]+)\s*(?:\(([^)]*)\))?", upper)
    if not match:
        return {
            "raw": raw,
            "base": upper,
            "length": None,
            "precision": None,
            "scale": None,
        }

    base = match.group(1)
    params = [item.strip() for item in text_or_empty(match.group(2)).split(",") if item.strip()]
    length = None
    precision = None
    scale = None
    if len(params) == 1 and params[0].isdigit():
        length = int(params[0])
    elif len(params) >= 2 and params[0].isdigit() and params[1].isdigit():
        precision = int(params[0])
        scale = int(params[1])

    return {
        "raw": raw,
        "base": base,
        "length": length,
        "precision": precision,
        "scale": scale,
    }


def normalize_column_meta(column: dict[str, Any]) -> dict[str, Any]:
    """字段归一化元数据"""
    column_type = parse_column_type(column.get("COLUMN_TYPE", column.get("DATA_TYPE", "")))
    return {
        "name": text_or_empty(column.get("COLUMN_NAME", "")),
        "key": normalize_name(column.get("COLUMN_NAME", "")),
        "type": column_type,
        "nullable": normalize_nullable(column.get("IS_NULLABLE", "Y")),
        "default": normalize_default(column.get("COLUMN_DEFAULT", "")),
        "comment": normalize_comment(column.get("COLUMN_COMMENT", column.get("COMMENTS", ""))),
        "is_pk": column.get("COLUMN_KEY", "") == "PRI" or column.get("IS_PK") == "YES",
        "ordinal": column.get("ORDINAL_POSITION", 0),
    }


def normalize_table_meta(table: dict[str, Any]) -> dict[str, Any]:
    """表归一化元数据"""
    return {
        "name": text_or_empty(table.get("TABLE_NAME", "")),
        "key": normalize_name(table.get("TABLE_NAME", "")),
        "comment": normalize_comment(table.get("TABLE_COMMENT", table.get("COMMENTS", ""))),
    }


def normalize_index_meta(index_name: str, rows: list[dict[str, Any]]) -> dict[str, Any]:
    """索引归一化元数据"""
    ordered_rows = sorted(rows, key=lambda item: item.get("SEQ_IN_INDEX", 0))
    columns = [normalize_name(item.get("COLUMN_NAME", "")) for item in ordered_rows]
    unique = str(ordered_rows[0].get("NON_UNIQUE", 1)) == "0" if ordered_rows else False
    return {
        "name": index_name,
        "key": normalize_name(index_name),
        "columns": columns,
        "unique": unique,
    }


def normalize_fk_meta(fk: dict[str, Any]) -> dict[str, Any]:
    """外键归一化元数据"""
    return {
        "source_table": normalize_name(fk.get("TABLE_NAME", "")),
        "source_column": normalize_name(fk.get("COLUMN_NAME", "")),
        "target_table": normalize_name(fk.get("REFERENCED_TABLE_NAME", "")),
        "target_column": normalize_name(fk.get("REFERENCED_COLUMN_NAME", "")),
    }


def normalize_snapshot(snapshot: dict[str, Any]) -> dict[str, Any]:
    """快照归一化，供 compare 结构比对使用"""
    normalized_tables = {}
    normalized_columns = {}
    normalized_indexes = {}
    normalized_foreign_keys = {}

    for table in snapshot["tables"]:
        table_meta = normalize_table_meta(table)
        normalized_tables[table_meta["key"]] = table_meta

    for column in snapshot["columns"]:
        table_key = normalize_name(column.get("TABLE_NAME", ""))
        column_meta = normalize_column_meta(column)
        normalized_columns.setdefault(table_key, {})[column_meta["key"]] = column_meta

    grouped_indexes = group_indexes(snapshot["indexes"])
    for index_name, rows in grouped_indexes.items():
        if not rows:
            continue
        table_key = normalize_name(rows[0].get("TABLE_NAME", ""))
        normalized_indexes.setdefault(table_key, {})[normalize_name(index_name)] = normalize_index_meta(index_name, rows)

    for fk in snapshot["foreign_keys"]:
        table_key = normalize_name(fk.get("TABLE_NAME", ""))
        fk_meta = normalize_fk_meta(fk)
        fk_key = "|".join([
            fk_meta["source_table"],
            fk_meta["source_column"],
            fk_meta["target_table"],
            fk_meta["target_column"],
        ])
        normalized_foreign_keys.setdefault(table_key, {})[fk_key] = fk_meta

    return {
        "meta": {
            "profile": snapshot.get("profile", ""),
            "database": snapshot.get("database", ""),
            "schema": snapshot.get("schema", ""),
            "db_type": snapshot.get("db_type", ""),
        },
        "tables": normalized_tables,
        "columns": normalized_columns,
        "indexes": normalized_indexes,
        "foreign_keys": normalized_foreign_keys,
    }


def compare_type_meta(left_type: dict[str, Any], right_type: dict[str, Any]) -> dict[str, Any]:
    """比较字段类型元数据"""
    changes = {}
    for key in ("base", "length", "precision", "scale"):
        if left_type.get(key) != right_type.get(key):
            changes[key] = {
                "left": left_type.get(key),
                "right": right_type.get(key),
            }
    return changes


def diff_columns(left_columns: dict[str, dict[str, Any]], right_columns: dict[str, dict[str, Any]]) -> dict[str, Any]:
    """字段级 diff"""
    left_keys = set(left_columns.keys())
    right_keys = set(right_columns.keys())
    modified = []

    for column_key in sorted(left_keys & right_keys):
        left_meta = left_columns[column_key]
        right_meta = right_columns[column_key]
        changes = {}
        type_changes = compare_type_meta(left_meta["type"], right_meta["type"])
        if type_changes:
            changes["type"] = type_changes
        if left_meta["nullable"] != right_meta["nullable"]:
            changes["nullable"] = {"left": left_meta["nullable"], "right": right_meta["nullable"]}
        if left_meta["default"] != right_meta["default"]:
            changes["default"] = {"left": left_meta["default"], "right": right_meta["default"]}
        if left_meta["comment"] != right_meta["comment"]:
            changes["comment"] = {"left": left_meta["comment"], "right": right_meta["comment"]}
        if left_meta["is_pk"] != right_meta["is_pk"]:
            changes["is_pk"] = {"left": left_meta["is_pk"], "right": right_meta["is_pk"]}
        if changes:
            modified.append({
                "column": left_meta["name"],
                "changes": changes,
            })

    return {
        "added": [right_columns[key]["name"] for key in sorted(right_keys - left_keys)],
        "removed": [left_columns[key]["name"] for key in sorted(left_keys - right_keys)],
        "modified": modified,
    }


def diff_indexes(left_indexes: dict[str, dict[str, Any]], right_indexes: dict[str, dict[str, Any]]) -> dict[str, Any]:
    """索引级 diff"""
    left_keys = set(left_indexes.keys())
    right_keys = set(right_indexes.keys())
    modified = []

    for index_key in sorted(left_keys & right_keys):
        left_meta = left_indexes[index_key]
        right_meta = right_indexes[index_key]
        if left_meta["columns"] != right_meta["columns"] or left_meta["unique"] != right_meta["unique"]:
            modified.append({
                "index": left_meta["name"],
                "left": left_meta,
                "right": right_meta,
            })

    return {
        "added": [right_indexes[key]["name"] for key in sorted(right_keys - left_keys)],
        "removed": [left_indexes[key]["name"] for key in sorted(left_keys - right_keys)],
        "modified": modified,
    }


def diff_foreign_keys(left_fks: dict[str, dict[str, Any]], right_fks: dict[str, dict[str, Any]]) -> dict[str, Any]:
    """外键级 diff"""
    left_keys = set(left_fks.keys())
    right_keys = set(right_fks.keys())
    return {
        "added": [right_fks[key] for key in sorted(right_keys - left_keys)],
        "removed": [left_fks[key] for key in sorted(left_keys - right_keys)],
    }


def diff_snapshots(left_snapshot: dict[str, Any], right_snapshot: dict[str, Any],
                   focus_tables: set[str] | None = None) -> dict[str, Any]:
    """计算两个快照的结构差异"""
    left_normalized = normalize_snapshot(left_snapshot)
    right_normalized = normalize_snapshot(right_snapshot)

    left_keys = set(left_normalized["tables"].keys())
    right_keys = set(right_normalized["tables"].keys())
    if focus_tables:
        normalized_focus = {normalize_name(table_name) for table_name in focus_tables}
        left_keys &= normalized_focus
        right_keys &= normalized_focus

    all_table_keys = sorted(left_keys | right_keys)
    changed_tables = []
    unchanged_tables = []

    for table_key in all_table_keys:
        left_table = left_normalized["tables"].get(table_key)
        right_table = right_normalized["tables"].get(table_key)
        if not left_table or not right_table:
            continue

        table_changes = {}
        if left_table["comment"] != right_table["comment"]:
            table_changes["comment"] = {
                "left": left_table["comment"],
                "right": right_table["comment"],
            }

        column_diff = diff_columns(
            left_normalized["columns"].get(table_key, {}),
            right_normalized["columns"].get(table_key, {}),
        )
        index_diff = diff_indexes(
            left_normalized["indexes"].get(table_key, {}),
            right_normalized["indexes"].get(table_key, {}),
        )
        fk_diff = diff_foreign_keys(
            left_normalized["foreign_keys"].get(table_key, {}),
            right_normalized["foreign_keys"].get(table_key, {}),
        )

        has_column_changes = bool(column_diff["added"] or column_diff["removed"] or column_diff["modified"])
        has_index_changes = bool(index_diff["added"] or index_diff["removed"] or index_diff["modified"])
        has_fk_changes = bool(fk_diff["added"] or fk_diff["removed"])

        if table_changes or has_column_changes or has_index_changes or has_fk_changes:
            changed_tables.append({
                "table": left_table["name"],
                "table_changes": table_changes,
                "columns": column_diff,
                "indexes": index_diff,
                "foreign_keys": fk_diff,
            })
        else:
            unchanged_tables.append(left_table["name"])

    added_table_keys = sorted(right_keys - left_keys)
    removed_table_keys = sorted(left_keys - right_keys)
    added_tables = [right_normalized["tables"][key]["name"] for key in added_table_keys]
    removed_tables = [left_normalized["tables"][key]["name"] for key in removed_table_keys]

    summary = {
        "added_tables": len(added_tables),
        "removed_tables": len(removed_tables),
        "changed_tables": len(changed_tables),
        "unchanged_tables": len(unchanged_tables),
        "added_columns": sum(len(item["columns"]["added"]) for item in changed_tables),
        "removed_columns": sum(len(item["columns"]["removed"]) for item in changed_tables),
        "modified_columns": sum(len(item["columns"]["modified"]) for item in changed_tables),
    }

    return {
        "left": left_normalized["meta"],
        "right": right_normalized["meta"],
        "summary": summary,
        "added_tables": added_tables,
        "removed_tables": removed_tables,
        "changed_tables": changed_tables,
        "unchanged_tables": unchanged_tables,
    }


def build_compare_fix_sql(diff_result: dict[str, Any], db_type: str) -> str:
    """生成 compare 修复 SQL 草案，仅输出安全变更"""
    lines = ["-- compare 修复 SQL 草案", "-- 仅供人工评审，不自动执行", ""]
    for table_diff in diff_result["changed_tables"]:
        table_name = table_diff["table"]
        for added_column in table_diff["columns"]["added"]:
            lines.append(
                f"-- 待补充字段定义：ALTER TABLE {quote_sql_identifier(db_type, table_name)} "
                f"ADD COLUMN {quote_sql_identifier(db_type, added_column)} <TYPE>;"
            )
        if "comment" in table_diff["table_changes"]:
            right_comment = text_or_empty(table_diff["table_changes"]["comment"]["right"])
            escaped_table_comment = right_comment.replace("'", "''")
            if db_type == "mysql":
                lines.append(
                    f"-- 表注释变更：ALTER TABLE {quote_sql_identifier(db_type, table_name)} "
                    f"COMMENT = '{escaped_table_comment}';"
                )
            else:
                lines.append(
                    f"-- 表注释变更：COMMENT ON TABLE {quote_sql_identifier(db_type, table_name)} "
                    f"IS '{escaped_table_comment}';"
                )
        for column_diff in table_diff["columns"]["modified"]:
            comment_change = column_diff["changes"].get("comment")
            if comment_change:
                comment = text_or_empty(comment_change["right"])
                escaped_column_comment = comment.replace("'", "''")
                if db_type == "mysql":
                    lines.append(
                        f"-- 字段注释变更：ALTER TABLE {quote_sql_identifier(db_type, table_name)} MODIFY COLUMN "
                        f"{quote_sql_identifier(db_type, column_diff['column'])} <TYPE> COMMENT '{escaped_column_comment}';"
                    )
                else:
                    lines.append(
                        f"-- 字段注释变更：COMMENT ON COLUMN {quote_sql_identifier(db_type, table_name)}."
                        f"{quote_sql_identifier(db_type, column_diff['column'])} IS '{escaped_column_comment}';"
                    )
        lines.append("")
    return "\n".join(lines)


def render_compare_report(diff_result: dict[str, Any]) -> str:
    """渲染 compare Markdown 报告"""
    lines = [
        "# 数据库结构差异报告",
        "",
        "## 比较对象",
        "",
        f"- 左侧：`{diff_result['left'].get('profile', '')}` / `{diff_result['left'].get('database', '')}` / `{diff_result['left'].get('schema', '')}`",
        f"- 右侧：`{diff_result['right'].get('profile', '')}` / `{diff_result['right'].get('database', '')}` / `{diff_result['right'].get('schema', '')}`",
        "",
        "## 差异统计",
        "",
        "| 指标 | 数量 |",
        "|------|------|",
        f"| 新增表 | {diff_result['summary']['added_tables']} |",
        f"| 删除表 | {diff_result['summary']['removed_tables']} |",
        f"| 变更表 | {diff_result['summary']['changed_tables']} |",
        f"| 新增字段 | {diff_result['summary']['added_columns']} |",
        f"| 删除字段 | {diff_result['summary']['removed_columns']} |",
        f"| 修改字段 | {diff_result['summary']['modified_columns']} |",
        "",
    ]

    lines.extend(["## 左侧独有表", ""])
    if diff_result["removed_tables"]:
        lines.extend([f"- `{table_name}`" for table_name in diff_result["removed_tables"]])
    else:
        lines.append("- 无")
    lines.append("")

    lines.extend(["## 右侧独有表", ""])
    if diff_result["added_tables"]:
        lines.extend([f"- `{table_name}`" for table_name in diff_result["added_tables"]])
    else:
        lines.append("- 无")
    lines.append("")

    lines.extend(["## 变更表明细", ""])
    if not diff_result["changed_tables"]:
        lines.append("- 未发现结构差异")
        lines.append("")
        return "\n".join(lines)

    for table_diff in diff_result["changed_tables"]:
        lines.extend([f"### {table_diff['table']}", ""])
        if table_diff["table_changes"]:
            lines.append("- 表级变化：")
            for change_name, change_value in table_diff["table_changes"].items():
                lines.append(f"  - {change_name}: `{change_value['left']}` -> `{change_value['right']}`")
        if table_diff["columns"]["added"]:
            lines.append(f"- 新增字段：{', '.join(table_diff['columns']['added'])}")
        if table_diff["columns"]["removed"]:
            lines.append(f"- 删除字段：{', '.join(table_diff['columns']['removed'])}")
        for modified in table_diff["columns"]["modified"]:
            lines.append(f"- 字段变更：`{modified['column']}`")
            for change_name, change_value in modified["changes"].items():
                lines.append(f"  - {change_name}: `{change_value['left']}` -> `{change_value['right']}`")
        if table_diff["indexes"]["added"]:
            lines.append(f"- 新增索引：{', '.join(table_diff['indexes']['added'])}")
        if table_diff["indexes"]["removed"]:
            lines.append(f"- 删除索引：{', '.join(table_diff['indexes']['removed'])}")
        for modified_index in table_diff["indexes"]["modified"]:
            lines.append(f"- 索引变更：`{modified_index['index']}`")
        if table_diff["foreign_keys"]["added"]:
            lines.append(f"- 新增外键：{len(table_diff['foreign_keys']['added'])} 个")
        if table_diff["foreign_keys"]["removed"]:
            lines.append(f"- 删除外键：{len(table_diff['foreign_keys']['removed'])} 个")
        lines.append("")

    return "\n".join(lines)


def classify_domain_tables(snapshot: dict[str, Any], focus_tables: set[str]) -> dict[str, list[str]]:
    """按业务域视角分类表角色"""
    filtered = filter_result(build_scan_result(snapshot, []), focus_tables)
    fk_count_by_table: dict[str, int] = {}
    for fk in filtered["foreign_keys"]:
        table_name = fk.get("TABLE_NAME", "")
        fk_count_by_table[table_name] = fk_count_by_table.get(table_name, 0) + 1

    relation_count_by_table: dict[str, int] = {}
    for rel in filtered["foreign_keys"]:
        relation_count_by_table[rel.get("TABLE_NAME", "")] = relation_count_by_table.get(rel.get("TABLE_NAME", ""), 0) + 1
        relation_count_by_table[rel.get("REFERENCED_TABLE_NAME", "")] = relation_count_by_table.get(rel.get("REFERENCED_TABLE_NAME", ""), 0) + 1
    for rel in filtered["inferred_relations"]:
        relation_count_by_table[rel.get("source_table", "")] = relation_count_by_table.get(rel.get("source_table", ""), 0) + 1
        relation_count_by_table[rel.get("target_table", "")] = relation_count_by_table.get(rel.get("target_table", ""), 0) + 1

    categories = {
        "core_tables": [],
        "relation_tables": [],
        "bridge_tables": [],
        "dict_tables": [],
        "log_tables": [],
        "other_tables": [],
    }

    for table in filtered["tables"]:
        table_name = table.get("TABLE_NAME", "")
        table_upper = table_name.upper()
        table_columns = [c for c in filtered["columns"] if c.get("TABLE_NAME", "") == table_name]
        fk_count = fk_count_by_table.get(table_name, 0)
        relation_count = relation_count_by_table.get(table_name, 0)
        lower_name = table_name.lower()

        if any(token in lower_name for token in ("log", "record", "history", "trace")):
            categories["log_tables"].append(table_name)
            continue
        if any(token in lower_name for token in ("dict", "code", "enum", "type")):
            categories["dict_tables"].append(table_name)
            continue
        if any(token in lower_name for token in ("rel", "map", "link", "bridge", "relation")) or fk_count >= 2:
            categories["bridge_tables"].append(table_name)
            continue
        if relation_count >= 3 or (len(table_columns) >= 8 and relation_count >= 2):
            categories["core_tables"].append(table_name)
            continue
        if relation_count >= 1:
            categories["relation_tables"].append(table_name)
            continue
        categories["other_tables"].append(table_name)

    if not categories["core_tables"] and filtered["tables"]:
        ranked = sorted(
            filtered["tables"],
            key=lambda item: relation_count_by_table.get(item.get("TABLE_NAME", ""), 0),
            reverse=True,
        )
        categories["core_tables"] = [ranked[0].get("TABLE_NAME", "")]
        for table_name in categories["core_tables"]:
            if table_name in categories["relation_tables"]:
                categories["relation_tables"].remove(table_name)
            if table_name in categories["other_tables"]:
                categories["other_tables"].remove(table_name)

    return categories


def build_domain_flow_text(categories: dict[str, list[str]]) -> list[str]:
    """生成业务域主流程说明，明确哪些内容属于推断"""
    core_tables = categories["core_tables"]
    bridge_tables = categories["bridge_tables"]
    relation_tables = categories["relation_tables"]
    log_tables = categories["log_tables"]
    dict_tables = categories["dict_tables"]

    steps = ["以下主流程为基于表关系的推断，不代表完整业务事实。"]
    if core_tables:
        steps.append(f"通常先围绕核心表 `{', '.join(core_tables)}` 发生主业务数据写入。")
    if relation_tables:
        steps.append(f"随后会通过关联表 `{', '.join(relation_tables)}` 连接上下游业务实体。")
    if bridge_tables:
        steps.append(f"中间表 `{', '.join(bridge_tables)}` 多用于绑定多对多关系或承接域内映射。")
    if dict_tables:
        steps.append(f"字典表 `{', '.join(dict_tables)}` 提供编码、类型或状态类辅助信息。")
    if log_tables:
        steps.append(f"日志/历史表 `{', '.join(log_tables)}` 用于记录轨迹、变更或操作留痕。")
    return steps


def render_domain_report(snapshot: dict[str, Any], focus_tables: set[str], domain_name: str) -> str:
    """渲染 domain Markdown 报告"""
    filtered = filter_result(build_scan_result(snapshot, []), focus_tables)
    categories = classify_domain_tables(snapshot, focus_tables)
    flow_steps = build_domain_flow_text(categories)
    relation_count = len(filtered["foreign_keys"]) + len(filtered["inferred_relations"])

    lines = [
        f"# 业务域透视：{domain_name}",
        "",
        "## 域概览",
        "",
        f"- 数据库：`{snapshot['database']}`",
        f"- 数据库类型：`{snapshot['db_type']}`",
        f"- 域表数量：`{len(filtered['tables'])}`",
        f"- 关系数量：`{relation_count}`",
        "",
        "## 表分类",
        "",
    ]

    category_labels = {
        "core_tables": "核心表",
        "relation_tables": "关联表",
        "bridge_tables": "中间表",
        "dict_tables": "字典表",
        "log_tables": "日志表",
        "other_tables": "其他表",
    }
    for category_key, label in category_labels.items():
        values = categories[category_key]
        lines.append(f"### {label}")
        lines.append("")
        if values:
            lines.extend([f"- `{value}`" for value in values])
        else:
            lines.append("- 无")
        lines.append("")

    lines.extend(["## 主流程推断", ""])
    lines.extend([f"- {step}" for step in flow_steps])
    lines.append("")

    lines.extend(["## 域内关系图", "", "```mermaid"])
    lines.append(generate_mermaid(
        filtered["tables"],
        filtered["columns"],
        filtered["foreign_keys"],
        filtered["inferred_relations"],
        max_tables=len(filtered["tables"]),
    ))
    lines.extend(["```", ""])

    return "\n".join(lines)


def render_domain_json(snapshot: dict[str, Any], focus_tables: set[str], domain_name: str) -> dict[str, Any]:
    """输出 domain 结构化结果"""
    filtered = filter_result(build_scan_result(snapshot, []), focus_tables)
    categories = classify_domain_tables(snapshot, focus_tables)
    return {
        "domain_name": domain_name,
        "database": snapshot["database"],
        "db_type": snapshot["db_type"],
        "tables": [t.get("TABLE_NAME", "") for t in filtered["tables"]],
        "categories": categories,
        "relation_count": len(filtered["foreign_keys"]) + len(filtered["inferred_relations"]),
    }


def render_core_tables_markdown(snapshot: dict[str, Any], focus_tables: set[str]) -> str:
    """输出核心表简表"""
    filtered = filter_result(build_scan_result(snapshot, []), focus_tables)
    categories = classify_domain_tables(snapshot, focus_tables)
    core_set = set(categories["core_tables"])
    lines = ["# 核心表清单", "", "| 表名 | 注释 | 字段数 |", "|------|------|--------|"]
    for table in filtered["tables"]:
        table_name = table.get("TABLE_NAME", "")
        if table_name not in core_set:
            continue
        table_comment = text_or_empty(table.get("TABLE_COMMENT", table.get("COMMENTS", "")))
        field_count = sum(1 for column in filtered["columns"] if column.get("TABLE_NAME", "") == table_name)
        lines.append(f"| {table_name} | {table_comment or '-'} | {field_count} |")
    if len(lines) == 4:
        lines.append("| - | 未识别到核心表 | - |")
    return "\n".join(lines)


# ---------------------------------------------------------------------------
# 关系推断
# ---------------------------------------------------------------------------

def infer_relations(tables: list[str], columns: list[dict]) -> list[dict]:
    """
    通过命名约定推断隐式关联关系。

    推断策略：
    1. 非主键字段名直接匹配其他表的主键名（如 PARAM_ID → PARAM_INFO.PARAM_ID）
    2. 去掉常见前缀后再匹配主键名（如 PARENT_PARAM_ID → PARAM_INFO.PARAM_ID）
    3. 兼容旧规则：字段以 _id 结尾，尝试匹配短表名（如 user_id → sys_user.id）
    4. 中间表：表名连接两个已知表名（如 sys_user_role → sys_user + sys_role）
    """
    short_name_map = {}
    for t in tables:
        parts = t.split("_")
        if len(parts) >= 2:
            short_name_map[parts[-1].lower()] = t
        short_name_map[t.lower()] = t

    inferred = []
    seen = set()

    pk_flags = {"PRI", "YES"}
    ignored_fk_columns = {
        "CREATE_USER_ID",
        "MODIFY_USER_ID",
        "TENANT_ID",
        "AFFILIATED_INST_ID",
        "DE_ID",
        "DATA_ARCHIVE_ID",
    }

    pk_by_table: dict[str, list[str]] = {}
    tables_by_pk: dict[str, list[str]] = {}
    for col in columns:
        is_pk = col.get("COLUMN_KEY", "") in pk_flags or col.get("IS_PK") == "YES"
        if is_pk:
            table_name = col.get("TABLE_NAME", "")
            pk_name = col.get("COLUMN_NAME", "").upper()
            pk_by_table.setdefault(table_name, []).append(pk_name)
            tables_by_pk.setdefault(pk_name, []).append(table_name)

    def normalize_column_name(column_name: str) -> str:
        normalized = column_name.upper()
        for prefix in ("PARENT_", "OLD_", "PRE_", "SRC_", "TARGET_"):
            if normalized.startswith(prefix):
                return normalized[len(prefix):]
        return normalized

    def pick_best_table(source_table: str, source_column: str, candidate_tables: list[str]) -> str | None:
        if not candidate_tables:
            return None

        column_upper = normalize_column_name(source_column)
        base_name = column_upper[:-3] if column_upper.endswith("_ID") else column_upper
        source_prefix = source_table.upper().split("_")[0]

        priority_candidates = []
        for candidate in candidate_tables:
            candidate_upper = candidate.upper()
            score = 100

            if candidate_upper == f"{base_name}_INFO":
                score = 0
            elif candidate_upper.endswith(f"_{base_name}_INFO"):
                score = 1
            elif candidate_upper == base_name:
                score = 2
            elif candidate_upper.startswith(f"{base_name}_"):
                score = 3
            elif candidate_upper.startswith(f"{source_prefix}_"):
                score = 4
            elif candidate_upper.endswith("_INFO"):
                score = 5
            elif candidate_upper.endswith("_RECORD"):
                score = 6

            priority_candidates.append((score, len(candidate_upper), candidate))

        priority_candidates.sort()
        return priority_candidates[0][2]

    def add_relation(source_table: str, source_column: str, target_table: str,
                     target_column: str, relation_type: str) -> None:
        if not source_table or not target_table:
            return
        relation_key = (source_table, source_column, target_table, target_column, relation_type)
        if relation_key in seen:
            return
        if source_table.lower() == target_table.lower() and not source_column.upper().startswith("PARENT_"):
            return
        seen.add(relation_key)
        inferred.append({
            "source_table": source_table,
            "source_column": source_column,
            "target_table": target_table,
            "target_column": target_column,
            "type": relation_type,
        })

    # 策略1/2：列名匹配主键名，优先适配 PARAM_ID -> PARAM_INFO.PARAM_ID 这类企业库设计
    for col in columns:
        source_table = col.get("TABLE_NAME", "")
        source_column = col.get("COLUMN_NAME", "")
        source_column_upper = source_column.upper()
        is_pk = col.get("COLUMN_KEY", "") in pk_flags or col.get("IS_PK") == "YES"

        if is_pk or source_column_upper in ignored_fk_columns:
            continue
        if not source_column_upper.endswith("_ID"):
            continue

        candidate_pk_names = [source_column_upper]
        normalized_name = normalize_column_name(source_column_upper)
        if normalized_name != source_column_upper:
            candidate_pk_names.append(normalized_name)

        for candidate_pk in candidate_pk_names:
            candidate_tables = tables_by_pk.get(candidate_pk, [])
            if source_column_upper.startswith("PARENT_"):
                candidate_tables = [t for t in candidate_tables]
            else:
                candidate_tables = [t for t in candidate_tables if t.lower() != source_table.lower()]
            target_table = pick_best_table(source_table, source_column_upper, candidate_tables)
            if target_table:
                add_relation(source_table, source_column, target_table, candidate_pk, "inferred_pk_match")
                break

    # 策略3：兼容旧规则 user_id -> sys_user.id
    id_pattern = re.compile(r"^(.+)_id$", re.I)
    for col in columns:
        source_table = col.get("TABLE_NAME", "")
        source_column = col.get("COLUMN_NAME", "")
        source_column_upper = source_column.upper()
        is_pk = col.get("COLUMN_KEY", "") in pk_flags or col.get("IS_PK") == "YES"

        if is_pk or source_column_upper in ignored_fk_columns:
            continue

        match = id_pattern.match(source_column)
        if not match:
            continue

        ref_short = match.group(1).lower()
        ref_table = short_name_map.get(ref_short)
        if not ref_table or ref_table.lower() == source_table.lower():
            continue

        target_pk_names = pk_by_table.get(ref_table, ["ID"])
        target_column = target_pk_names[0] if target_pk_names else "ID"
        add_relation(source_table, source_column, ref_table, target_column, "inferred_id")

    # 策略4：中间表推断
    for tbl in tables:
        parts = tbl.lower().split("_")
        if len(parts) >= 3:
            for i in range(1, len(parts) - 1):
                left = "_".join(parts[:i])
                right = "_".join(parts[i:])
                left_full = short_name_map.get(left)
                right_full = short_name_map.get(right)
                if left_full and right_full and left_full != right_full:
                    add_relation(left_full, "id", tbl, f"{left}_id", "inferred_bridge")
                    add_relation(right_full, "id", tbl, f"{right}_id", "inferred_bridge")

    return inferred


# ---------------------------------------------------------------------------
# 规范检查
# ---------------------------------------------------------------------------

def check_rules(tables: list[dict], columns: list[dict], db_type: str) -> list[dict]:
    """
    执行规范检查，返回问题列表。

    规则定义详见 references/rules.md
    """
    issues = []
    cols_by_table: dict[str, list[dict]] = {}
    for col in columns:
        tbl = col.get("TABLE_NAME", "")
        cols_by_table.setdefault(tbl, []).append(col)

    for tbl_info in tables:
        tbl_name = tbl_info.get("TABLE_NAME", "")
        tbl_cols = cols_by_table.get(tbl_name, [])

        # 检查1：金额字段是否为 decimal/numeric
        amount_keywords = ["amount", "price", "fee", "cost", "money", "pay", "salary"]
        for col in tbl_cols:
            col_name = col.get("COLUMN_NAME", "").lower()
            col_type = col.get("COLUMN_TYPE", col.get("DATA_TYPE", "")).lower()
            if any(kw in col_name for kw in amount_keywords):
                if any(t in col_type for t in ["double", "float", "int", "bigint", "number"]):
                    if "decimal" not in col_type and "numeric" not in col_type:
                        issues.append({
                            "level": "CRITICAL",
                            "rule": "amount-decimal",
                            "table": tbl_name,
                            "column": col.get("COLUMN_NAME", ""),
                            "message": f"金额字段使用了 {col_type}，应改为 decimal/numeric",
                        })

        # 检查2：主键是否为 UUID（varchar(36)）
        pk_cols = [c for c in tbl_cols if c.get("COLUMN_KEY", "") == "PRI" or c.get("IS_PK") == "YES"]
        for pk in pk_cols:
            pk_type = pk.get("COLUMN_TYPE", pk.get("DATA_TYPE", "")).lower()
            if "int" in pk_type or "number" in pk_type:
                issues.append({
                    "level": "HIGH",
                    "rule": "uuid-pk",
                    "table": tbl_name,
                    "column": pk.get("COLUMN_NAME", ""),
                    "message": f"主键使用了自增整数 {pk_type}，建议改为 varchar(36) UUID",
                })

        # 检查3：是否存在 valid_flag 软删除字段
        col_names_lower = [c.get("COLUMN_NAME", "").lower() for c in tbl_cols]
        if "valid_flag" not in col_names_lower and "is_valid" not in col_names_lower:
            issues.append({
                "level": "HIGH",
                "rule": "valid-flag",
                "table": tbl_name,
                "column": "-",
                "message": "缺少 valid_flag 软删除字段",
            })

        # 检查4：时间字段自动更新（MySQL 专属）
        if db_type == "mysql":
            time_cols = [c for c in tbl_cols if "time" in c.get("COLUMN_NAME", "").lower()]
            has_auto_update = any("on update" in c.get("EXTRA", "").lower() for c in time_cols)
            if time_cols and not has_auto_update:
                issues.append({
                    "level": "MEDIUM",
                    "rule": "auto-timestamp",
                    "table": tbl_name,
                    "column": "-",
                    "message": "时间字段缺少 ON UPDATE CURRENT_TIMESTAMP",
                })

        # 检查5：字段注释
        no_comment_cols = [
            c for c in tbl_cols
            if not text_or_empty(c.get("COLUMN_COMMENT", c.get("COMMENTS", ""))).strip()
            and c.get("COLUMN_KEY", "") != "PRI"
        ]
        if no_comment_cols and len(no_comment_cols) > len(tbl_cols) * 0.5:
            issues.append({
                "level": "LOW",
                "rule": "field-comment",
                "table": tbl_name,
                "column": f"{len(no_comment_cols)} 个字段",
                "message": f"超过 50% 字段缺少注释（{len(no_comment_cols)}/{len(tbl_cols)}）",
            })

    return issues


# ---------------------------------------------------------------------------
# Mermaid ER 图生成
# ---------------------------------------------------------------------------

def generate_mermaid(tables: list[dict], columns: list[dict],
                     foreign_keys: list[dict], inferred: list[dict],
                     max_tables: int = 50) -> str:
    """生成 Mermaid ER 图文本"""
    lines = ["erDiagram"]

    # 限制表数量
    table_names = [t.get("TABLE_NAME", "") for t in tables[:max_tables]]
    cols_by_table: dict[str, list[dict]] = {}
    for col in columns:
        tbl = col.get("TABLE_NAME", "")
        if tbl in table_names:
            cols_by_table.setdefault(tbl, []).append(col)

    # 生成表定义
    for tbl_name in table_names:
        tbl_cols = cols_by_table.get(tbl_name, [])
        if not tbl_cols:
            continue
        safe_name = tbl_name.replace("-", "_")
        lines.append(f"    {safe_name} {{")
        for col in tbl_cols:
            col_name = col.get("COLUMN_NAME", "")
            col_type = col.get("COLUMN_TYPE", col.get("DATA_TYPE", ""))
            col_comment = text_or_empty(col.get("COLUMN_COMMENT", col.get("COMMENTS", "")))
            is_pk = col.get("COLUMN_KEY", "") == "PRI" or col.get("IS_PK") == "YES"
            pk_mark = " PK" if is_pk else ""
            # 截断过长类型
            col_type_short = col_type.split("(")[0] if "(" in col_type else col_type
            comment_str = f' "{col_comment}"' if col_comment else ""
            lines.append(f"        {col_type_short} {col_name}{pk_mark}{comment_str}")
        lines.append("    }")

    # 生成关系线（显式外键）
    all_relations = []
    for fk in foreign_keys:
        src = fk.get("TABLE_NAME", "").replace("-", "_")
        tgt = fk.get("REFERENCED_TABLE_NAME", "").replace("-", "_")
        if src in [t.replace("-", "_") for t in table_names] and tgt in [t.replace("-", "_") for t in table_names]:
            all_relations.append((src, tgt, "||--o{"))

    # 生成关系线（推断）
    for inf in inferred:
        src = inf.get("source_table", "").replace("-", "_")
        tgt = inf.get("target_table", "").replace("-", "_")
        if src in [t.replace("-", "_") for t in table_names] and tgt in [t.replace("-", "_") for t in table_names]:
            rel_type = "}|--o{" if inf.get("type") == "inferred_bridge" else "||--o{"
            all_relations.append((src, tgt, rel_type))

    for src, tgt, rel in all_relations:
        lines.append(f"    {src} {rel} {tgt} : \"\"")

    return "\n".join(lines)


# ---------------------------------------------------------------------------
# 主流程
# ---------------------------------------------------------------------------

def main():
    parser = argparse.ArgumentParser(description="db-architect 数据库架构透视器")
    parser.add_argument("mode", nargs="?", default="scan", choices=["scan", "analyze", "export-row", "compare", "domain", "ddl", "find-table"], help="运行模式：scan/analyze/export-row/compare/domain/ddl/find-table")
    parser.add_argument("--env", help=".env 文件路径")
    parser.add_argument("--profile", help="数据库 profile 名称，对应 DB_<PROFILE>_* 配置")
    parser.add_argument("--db-type", choices=["mysql", "oracle", "postgresql", "dameng"], help="数据库类型")
    parser.add_argument("--host", help="数据库主机")
    parser.add_argument("--port", type=int, help="数据库端口")
    parser.add_argument("--db", help="数据库名")
    parser.add_argument("--user", help="数据库用户")
    parser.add_argument("--password", help="数据库密码")
    parser.add_argument("--schema", help="Schema 名称（Oracle/PG/达梦适用）")
    parser.add_argument("--output", default=".", help="输出目录")
    parser.add_argument("--max-tables", type=int, default=50, help="最大扫描表数量")
    parser.add_argument("--no-mermaid", action="store_true", help="跳过 Mermaid 生成")
    parser.add_argument("--rules", help="自定义规范规则文件路径")
    parser.add_argument("--table", help="聚焦单表或导出数据时的目标表")
    parser.add_argument("--tables", help="聚焦多表，逗号分隔")
    parser.add_argument("--table-like", help="按表名关键字过滤，如 PARAM")
    parser.add_argument("--table-prefix", help="按表名前缀过滤，如 SYS")
    parser.add_argument("--table-comment-like", help="按表中文名/表注释模糊过滤")
    parser.add_argument("--output-ddl", help="ddl 模式下输出的 DDL 文件路径")
    parser.add_argument("--format", choices=["insert", "table", "json", "csv"], default="insert", help="export-row 输出格式：insert/table/json/csv")
    parser.add_argument("--filter-column", help="export-row 模式下等值过滤字段，如 CODE")
    parser.add_argument("--filter-value", help="export-row 模式下等值过滤值")
    parser.add_argument("--limit", type=int, help="export-row table/json/csv 展示或导出行数限制")
    parser.add_argument("--output-data", help="export-row 非 insert 格式输出文件路径")
    parser.add_argument("--with-er", action="store_true", help="显式生成 ER 图")
    parser.add_argument("--with-drawio", action="store_true", help="显式生成 draw.io ER 图")
    parser.add_argument("--where", help="export-row 模式下的 WHERE 条件，不带 WHERE 关键字")
    parser.add_argument("--output-sql", help="export-row 模式下输出的 SQL 文件路径")
    parser.add_argument("--lowercase", action="store_true", help="export-row 模式下表名和字段名输出小写（达梦/Oracle 已默认开启）")
    parser.add_argument("--left-profile", help="compare 模式下左侧数据库 profile")
    parser.add_argument("--right-profile", help="compare 模式下右侧数据库 profile")
    parser.add_argument("--left-db", help="compare 模式下左侧数据库名")
    parser.add_argument("--right-db", help="compare 模式下右侧数据库名")
    parser.add_argument("--left-schema", help="compare 模式下左侧 schema")
    parser.add_argument("--right-schema", help="compare 模式下右侧 schema")
    parser.add_argument("--with-sql", action="store_true", help="compare 模式下生成修复 SQL 草案")
    parser.add_argument("--domain-name", help="domain 模式下业务域名称")
    args = parser.parse_args()

    env_config = {}
    if args.env:
        env_config = parse_env(args.env)

    output_dir = Path(args.output)
    output_dir.mkdir(parents=True, exist_ok=True)

    if args.mode == "compare":
        try:
            left_spec = resolve_connection_spec(
                env=env_config,
                profile=args.left_profile or args.profile,
                db_type=args.db_type,
                host=args.host or "",
                port=args.port,
                db_name=args.left_db or args.db or "",
                user=args.user or "",
                password=args.password or "",
                schema=args.left_schema or args.schema or "",
            )
            right_spec = resolve_connection_spec(
                env=env_config,
                profile=args.right_profile or args.profile or args.left_profile,
                db_type="",
                host="",
                port=None,
                db_name=args.right_db or "",
                user="",
                password="",
                schema=args.right_schema or "",
            )
        except ValueError as exc:
            print(f"错误：{exc}")
            sys.exit(1)

        if left_spec["db_names"] and not args.left_db and not args.db:
            print(f"  左侧可选数据库：{', '.join(left_spec['db_names'])}（默认使用第一个：{left_spec['db_name']}）")
        if right_spec["db_names"] and not args.right_db:
            print(f"  右侧可选数据库：{', '.join(right_spec['db_names'])}（默认使用第一个：{right_spec['db_name']}）")

        print(f"正在连接左侧 {left_spec['db_type']}://{left_spec['host']}:{left_spec['port']}/{left_spec['db_name']} ...")
        left_conn = get_connection(
            left_spec["db_type"],
            left_spec["host"],
            left_spec["port"],
            left_spec["db_name"],
            left_spec["user"],
            left_spec["password"],
        )
        try:
            left_snapshot = scan_snapshot(
                left_conn,
                left_spec["db_type"],
                left_spec["host"],
                left_spec["port"],
                left_spec["db_name"],
                schema=left_spec["schema"],
                profile=left_spec["profile"],
            )
        finally:
            left_conn.close()

        print(f"正在连接右侧 {right_spec['db_type']}://{right_spec['host']}:{right_spec['port']}/{right_spec['db_name']} ...")
        right_conn = get_connection(
            right_spec["db_type"],
            right_spec["host"],
            right_spec["port"],
            right_spec["db_name"],
            right_spec["user"],
            right_spec["password"],
        )
        try:
            right_snapshot = scan_snapshot(
                right_conn,
                right_spec["db_type"],
                right_spec["host"],
                right_spec["port"],
                right_spec["db_name"],
                schema=right_spec["schema"],
                profile=right_spec["profile"],
            )
        finally:
            right_conn.close()

        union_result = {
            "tables": left_snapshot["tables"] + [
                table for table in right_snapshot["tables"]
                if normalize_name(table.get("TABLE_NAME", "")) not in {
                    normalize_name(item.get("TABLE_NAME", "")) for item in left_snapshot["tables"]
                }
            ]
        }
        focus_tables = build_focus_tables(union_result, args.table, args.tables, args.table_like, args.table_prefix, args.table_comment_like)
        diff_result = diff_snapshots(left_snapshot, right_snapshot, focus_tables or None)

        report_path = output_dir / "compare-report.md"
        with open(report_path, "w", encoding="utf-8") as f:
            f.write(render_compare_report(diff_result))
        print(f"  差异报告已保存：{report_path}")

        diff_json_path = output_dir / "compare-diff.json"
        with open(diff_json_path, "w", encoding="utf-8") as f:
            json.dump(diff_result, f, ensure_ascii=False, indent=2)
        print(f"  差异 JSON 已保存：{diff_json_path}")

        if args.with_sql:
            compare_sql_path = output_dir / "compare-fix.sql"
            with open(compare_sql_path, "w", encoding="utf-8") as f:
                f.write(build_compare_fix_sql(diff_result, right_spec["db_type"]))
            print(f"  修复 SQL 草案已保存：{compare_sql_path}")

        print(f"\n✅ 差异比对完成！输出目录：{output_dir.resolve()}")
        return

    try:
        spec = resolve_connection_spec(
            env=env_config,
            profile=args.profile,
            db_type=args.db_type or "",
            host=args.host or "",
            port=args.port,
            db_name=args.db or "",
            user=args.user or "",
            password=args.password or "",
            schema=args.schema or "",
        )
    except ValueError as exc:
        print(f"错误：{exc}")
        sys.exit(1)

    if spec["db_names"] and not args.db:
        print(f"  可选数据库：{', '.join(spec['db_names'])}（默认使用第一个：{spec['db_name']}）")

    print(f"正在连接 {spec['db_type']}://{spec['host']}:{spec['port']}/{spec['db_name']} ...")
    conn = get_connection(
        spec["db_type"],
        spec["host"],
        spec["port"],
        spec["db_name"],
        spec["user"],
        spec["password"],
    )
    try:
        if args.mode == "export-row":
            if not args.table:
                print("错误：export-row 模式必须指定 --table。")
                sys.exit(1)
            print(f"查询表数据：{args.table}")
            use_lowercase = args.lowercase or spec["db_type"] in ("dameng", "oracle")
            where_clause = build_where_clause(
                spec["db_type"],
                filter_column=args.filter_column or "",
                filter_value=args.filter_value or "",
                where_clause=args.where or "",
            )
            row_limit = args.limit if args.format in ("table", "json", "csv") else None
            rows = query_table_rows(
                conn,
                spec["db_type"],
                args.table,
                schema=spec["schema"],
                where_clause=where_clause,
                limit=row_limit,
            )
            output_text = render_rows_output(
                rows,
                args.format,
                spec["db_type"],
                args.table,
                schema=spec["schema"],
                lowercase=use_lowercase,
            )
            if args.format == "insert":
                output_file = args.output_sql or str(output_dir / f"{args.table.lower()}_insert.sql")
            else:
                suffix = "md" if args.format == "table" else args.format
                output_file = args.output_data or ""
                if args.output_data is None and args.output != ".":
                    output_file = str(output_dir / f"{args.table.lower()}_rows.{suffix}")
            if output_file:
                output_path = Path(output_file)
                output_path.parent.mkdir(parents=True, exist_ok=True)
                with open(output_path, "w", encoding="utf-8", newline="") as f:
                    f.write(output_text)
                print(f"  已查询 {len(rows)} 条记录")
                print(f"  输出文件：{output_path}")
            else:
                print(output_text)
                print(f"\n已查询 {len(rows)} 条记录")
            print("\n✅ 数据查询完成")
            return

        snapshot = scan_snapshot(
            conn,
            spec["db_type"],
            spec["host"],
            spec["port"],
            spec["db_name"],
            schema=spec["schema"],
            profile=spec["profile"],
        )

        print("执行规范检查...")
        issues = check_rules(snapshot["tables"], snapshot["columns"], spec["db_type"])
        critical, high, medium, low = summarize_issues(issues)
        print(f"  发现 {critical} CRITICAL / {high} HIGH / {medium} MEDIUM / {low} LOW")

        result = build_scan_result(snapshot, issues)
        focus_tables = build_focus_tables(result, args.table, args.tables, args.table_like, args.table_prefix, args.table_comment_like)
        available_table_names = {item.get("TABLE_NAME", "") for item in snapshot["tables"]}
        if args.mode in ("analyze", "domain", "ddl") and not focus_tables:
            print(f"错误：{args.mode} 模式请至少指定 --table / --tables / --table-like / --table-prefix / --table-comment-like 之一。")
            sys.exit(1)
        if args.mode == "find-table" and not focus_tables:
            print(render_table_matches(result, set()))
            return

        if focus_tables:
            missing_tables = [table_name for table_name in focus_tables if table_name not in available_table_names]
            if missing_tables and args.mode in ("analyze", "domain"):
                print(f"警告：以下表未找到：{', '.join(missing_tables)}")
            focus_tables = {table_name for table_name in focus_tables if table_name in available_table_names}

        if args.mode == "ddl":
            ddl_text = render_ddl_only(result, focus_tables)
            if args.output_ddl:
                ddl_path = Path(args.output_ddl)
                ddl_path.parent.mkdir(parents=True, exist_ok=True)
                with open(ddl_path, "w", encoding="utf-8") as f:
                    f.write(ddl_text)
                print(f"  DDL 已保存：{ddl_path}")
            else:
                print(ddl_text)
            print("\n✅ DDL 生成完成")
            return

        if args.mode == "find-table":
            table_matches = render_table_matches(result, focus_tables)
            if args.output_data:
                output_path = Path(args.output_data)
                output_path.parent.mkdir(parents=True, exist_ok=True)
                with open(output_path, "w", encoding="utf-8") as f:
                    f.write(table_matches)
                print(f"  表清单已保存：{output_path}")
            else:
                print(table_matches)
            print(f"\n✅ 找到 {len(focus_tables)} 张匹配表")
            return

        if args.mode == "analyze":
            report = generate_focus_report(result, focus_tables, args.with_er)
            report_path = output_dir / "focus-analysis.md"
            with open(report_path, "w", encoding="utf-8") as f:
                f.write(report)
            print(f"  聚焦分析报告已保存：{report_path}")
            if args.with_er:
                mermaid = generate_mermaid(
                    [t for t in snapshot["tables"] if t.get("TABLE_NAME", "") in focus_tables],
                    [c for c in snapshot["columns"] if c.get("TABLE_NAME", "") in focus_tables],
                    [fk for fk in snapshot["foreign_keys"] if fk.get("TABLE_NAME", "") in focus_tables or fk.get("REFERENCED_TABLE_NAME", "") in focus_tables],
                    [rel for rel in snapshot["inferred_relations"] if rel.get("source_table", "") in focus_tables or rel.get("target_table", "") in focus_tables],
                    max_tables=len(focus_tables),
                )
                mmd_path = output_dir / "er-diagram.mmd"
                with open(mmd_path, "w", encoding="utf-8") as f:
                    f.write(mermaid)
                print(f"  ER 图已保存：{mmd_path}")
            if args.with_drawio:
                filtered_for_drawio = filter_result(result, focus_tables)
                drawio_xml = generate_drawio(
                    filtered_for_drawio["tables"],
                    filtered_for_drawio["columns"],
                    filtered_for_drawio["foreign_keys"],
                    filtered_for_drawio["inferred_relations"],
                )
                drawio_path = output_dir / "er-diagram.drawio"
                with open(drawio_path, "w", encoding="utf-8") as f:
                    f.write(drawio_xml)
                print(f"  draw.io ER 图已保存：{drawio_path}")
            print(f"\n✅ 聚焦分析完成！输出目录：{output_dir.resolve()}")
            return

        if args.mode == "domain":
            domain_name = args.domain_name or args.table_like or ", ".join(sorted(focus_tables))
            domain_report_path = output_dir / "domain-analysis.md"
            with open(domain_report_path, "w", encoding="utf-8") as f:
                f.write(render_domain_report(snapshot, focus_tables, domain_name))
            print(f"  业务域报告已保存：{domain_report_path}")

            domain_json_path = output_dir / "domain-summary.json"
            with open(domain_json_path, "w", encoding="utf-8") as f:
                json.dump(render_domain_json(snapshot, focus_tables, domain_name), f, ensure_ascii=False, indent=2)
            print(f"  业务域 JSON 已保存：{domain_json_path}")

            filtered_domain = filter_result(result, focus_tables)
            domain_mmd_path = output_dir / "domain-er.mmd"
            with open(domain_mmd_path, "w", encoding="utf-8") as f:
                f.write(generate_mermaid(
                    filtered_domain["tables"],
                    filtered_domain["columns"],
                    filtered_domain["foreign_keys"],
                    filtered_domain["inferred_relations"],
                    max_tables=len(filtered_domain["tables"]),
                ))
            print(f"  业务域 ER 图已保存：{domain_mmd_path}")

            core_tables_path = output_dir / "core-tables.md"
            with open(core_tables_path, "w", encoding="utf-8") as f:
                f.write(render_core_tables_markdown(snapshot, focus_tables))
            print(f"  核心表清单已保存：{core_tables_path}")

            if args.with_drawio:
                drawio_xml = generate_drawio(
                    filtered_domain["tables"],
                    filtered_domain["columns"],
                    filtered_domain["foreign_keys"],
                    filtered_domain["inferred_relations"],
                )
                drawio_path = output_dir / "domain-er.drawio"
                with open(drawio_path, "w", encoding="utf-8") as f:
                    f.write(drawio_xml)
                print(f"  业务域 draw.io ER 图已保存：{drawio_path}")

            print(f"\n✅ 业务域透视完成！输出目录：{output_dir.resolve()}")
            return

        json_path = output_dir / "db-structure.json"
        with open(json_path, "w", encoding="utf-8") as f:
            json.dump(result, f, ensure_ascii=False, indent=2)
        print(f"  结构数据已保存：{json_path}")

        if not args.no_mermaid:
            print("生成 Mermaid ER 图...")
            mermaid = generate_mermaid(snapshot["tables"], snapshot["columns"], snapshot["foreign_keys"], snapshot["inferred_relations"], args.max_tables)
            mmd_path = output_dir / "er-diagram.mmd"
            with open(mmd_path, "w", encoding="utf-8") as f:
                f.write(mermaid)
            print(f"  ER 图已保存：{mmd_path}")

        print("生成架构报告...")
        report = generate_report(result)
        report_path = output_dir / "db-report.md"
        with open(report_path, "w", encoding="utf-8") as f:
            f.write(report)
        print(f"  报告已保存：{report_path}")

        print(f"\n✅ 扫描完成！输出目录：{output_dir.resolve()}")

    finally:
        conn.close()


def generate_report(result: dict) -> str:
    """生成 Markdown 架构报告"""
    db_name = result["database"]
    db_type = result["db_type"]
    tables = result["tables"]
    columns = result["columns"]
    indexes = result["indexes"]
    foreign_keys = result["foreign_keys"]
    inferred = result["inferred_relations"]
    issues = result["issues"]

    lines = [
        f"# 数据库架构报告：{db_name}",
        "",
        "## 概览",
        "",
        "| 指标 | 值 |",
        "|------|------|",
        f"| 数据库类型 | {db_type} |",
        f"| 表数量 | {len(tables)} |",
        f"| 总字段数 | {len(columns)} |",
        f"| 外键关系 | {len(foreign_keys)}（显式）+ {len(inferred)}（推断） |",
        f"| 索引数量 | {len(indexes)} |",
    ]

    # 规范问题统计
    if issues:
        critical = sum(1 for i in issues if i["level"] == "CRITICAL")
        high = sum(1 for i in issues if i["level"] == "HIGH")
        medium = sum(1 for i in issues if i["level"] == "MEDIUM")
        low = sum(1 for i in issues if i["level"] == "LOW")
        lines.append(f"| 规范问题 | {critical} CRITICAL / {high} HIGH / {medium} MEDIUM / {low} LOW |")
    lines.append("")

    # 表清单
    lines.extend(["## 表清单", "", "| 表名 | 注释 | 字段数 | 索引数 | 规范状态 |", "|------|------|--------|--------|---------|"])

    cols_by_table: dict[str, list] = {}
    for col in columns:
        cols_by_table.setdefault(col.get("TABLE_NAME", ""), []).append(col)

    idx_by_table: dict[str, list] = {}
    for idx in indexes:
        idx_by_table.setdefault(idx.get("TABLE_NAME", ""), []).append(idx)

    issues_by_table: dict[str, list] = {}
    for issue in issues:
        issues_by_table.setdefault(issue.get("table", ""), []).append(issue)

    for tbl in tables:
        tbl_name = tbl.get("TABLE_NAME", "")
        tbl_comment = text_or_empty(tbl.get("TABLE_COMMENT", tbl.get("COMMENTS", "")))
        col_count = len(cols_by_table.get(tbl_name, []))
        idx_count = len(idx_by_table.get(tbl_name, []))
        tbl_issues = issues_by_table.get(tbl_name, [])
        if any(i["level"] in ("CRITICAL",) for i in tbl_issues):
            status = "🔴 CRITICAL"
        elif any(i["level"] == "HIGH" for i in tbl_issues):
            status = "⚠️ HIGH"
        elif tbl_issues:
            status = "🟡 MEDIUM"
        else:
            status = "✅"
        lines.append(f"| {tbl_name} | {tbl_comment} | {col_count} | {idx_count} | {status} |")
    lines.append("")

    # 关系图（嵌入 Mermaid）
    lines.extend(["## 关系图", "", "```mermaid"])
    mermaid = generate_mermaid(tables, columns, foreign_keys, inferred)
    lines.append(mermaid)
    lines.extend(["```", ""])

    # 规范问题
    if issues:
        for level in ["CRITICAL", "HIGH", "MEDIUM", "LOW"]:
            level_issues = [i for i in issues if i["level"] == level]
            if not level_issues:
                continue
            lines.extend([f"## {level}", "", "| # | 表 | 字段 | 问题 | 建议 |", "|---|------|------|------|------|"])
            for idx, issue in enumerate(level_issues, 1):
                lines.append(f"| {idx} | {issue['table']} | {issue['column']} | {issue['message']} | 见规则 {issue['rule']} |")
            lines.append("")

    return "\n".join(lines)


def filter_result(result: dict, focus_tables: set[str]) -> dict:
    """按目标表集合裁剪扫描结果"""
    filtered = dict(result)
    filtered["tables"] = [t for t in result["tables"] if t.get("TABLE_NAME", "") in focus_tables]
    filtered["columns"] = [c for c in result["columns"] if c.get("TABLE_NAME", "") in focus_tables]
    filtered["indexes"] = [i for i in result["indexes"] if i.get("TABLE_NAME", "") in focus_tables]
    filtered["foreign_keys"] = [
        fk for fk in result["foreign_keys"]
        if fk.get("TABLE_NAME", "") in focus_tables and fk.get("REFERENCED_TABLE_NAME", "") in focus_tables
    ]
    filtered["inferred_relations"] = [
        rel for rel in result["inferred_relations"]
        if rel.get("source_table", "") in focus_tables and rel.get("target_table", "") in focus_tables
    ]
    filtered["issues"] = [issue for issue in result["issues"] if issue.get("table", "") in focus_tables]
    return filtered


def build_focus_tables(result: dict, table_arg: str, tables_arg: str, table_like: str,
                       table_prefix: str = "", table_comment_like: str = "") -> set[str]:
    """根据 table/tables/table-like/prefix/comment 生成聚焦表集合，表名大小写不敏感"""
    available_tables = result["tables"]
    available = [t.get("TABLE_NAME", "") for t in available_tables]
    available_by_upper = {table_name.upper(): table_name for table_name in available}
    focus_tables: set[str] = set()

    def add_table_name(raw_name: str) -> None:
        normalized = raw_name.strip()
        if not normalized:
            return
        actual_name = available_by_upper.get(normalized.upper(), normalized)
        focus_tables.add(actual_name)

    if table_arg:
        add_table_name(table_arg)

    if tables_arg:
        for item in tables_arg.split(","):
            add_table_name(item)

    if table_like:
        keyword = table_like.upper()
        for table_name in available:
            if keyword in table_name.upper():
                focus_tables.add(table_name)

    if table_prefix:
        prefix = table_prefix.upper()
        for table_name in available:
            if table_name.upper().startswith(prefix):
                focus_tables.add(table_name)

    if table_comment_like:
        keyword = table_comment_like.upper()
        for table in available_tables:
            table_name = table.get("TABLE_NAME", "")
            table_comment = text_or_empty(table.get("TABLE_COMMENT", table.get("COMMENTS", ""))).upper()
            if keyword in table_comment:
                focus_tables.add(table_name)

    return focus_tables


def ddl_column_comment(db_type: str, column: dict[str, Any]) -> str:
    """生成近似 DDL 中的字段注释片段"""
    label = get_column_label(column)
    if not label or label == "-":
        return ""
    label = label.replace("（推断）", "")
    escaped = label.replace("'", "''")
    if db_type == "mysql":
        return f" COMMENT '{escaped}'"
    return ""


def get_table_comment(table: dict[str, Any]) -> str:
    """获取表注释"""
    return text_or_empty(table.get("TABLE_COMMENT", table.get("COMMENTS", ""))).strip()


def qualify_table_name(db_type: str, table_name: str, schema: str = "") -> str:
    """生成带 schema 的表名"""
    quoted_table = quote_sql_identifier(db_type, table_name)
    if schema:
        return f"{quote_sql_identifier(db_type, schema)}.{quoted_table}"
    return quoted_table


def generate_table_ddl_like(table_name: str, columns: list[dict], indexes: list[dict], db_type: str,
                            schema: str = "", tables: list[dict] | None = None) -> str:
    """生成近似 DDL，避免依赖各数据库专有元数据函数"""
    table_columns = [c for c in columns if c.get("TABLE_NAME", "") == table_name]
    table_indexes = [i for i in indexes if i.get("TABLE_NAME", "") == table_name]
    full_table_name = qualify_table_name(db_type, table_name, schema)
    lines = [f"CREATE TABLE {full_table_name} ("]

    column_defs = []
    for col in sorted(table_columns, key=lambda x: x.get("ORDINAL_POSITION", 0)):
        col_name = quote_sql_identifier(db_type, col.get("COLUMN_NAME", ""))
        col_type = text_or_empty(col.get("COLUMN_TYPE", col.get("DATA_TYPE", "")))
        nullable = text_or_empty(col.get("IS_NULLABLE", "Y")).upper()
        default_value = text_or_empty(col.get("COLUMN_DEFAULT", "")).strip()
        col_def = f"  {col_name} {col_type}"
        if default_value:
            col_def += f" DEFAULT {default_value}"
        if nullable in ("N", "NO"):
            col_def += " NOT NULL"
        col_def += ddl_column_comment(db_type, col)
        column_defs.append(col_def)

    pk_columns = [quote_sql_identifier(db_type, c.get("COLUMN_NAME", "")) for c in table_columns if c.get("COLUMN_KEY", "") == "PRI" or c.get("IS_PK") == "YES"]
    if pk_columns:
        column_defs.append(f"  PRIMARY KEY ({', '.join(pk_columns)})")

    lines.append(",\n".join(column_defs))
    lines.append(");")

    for index_name, grouped in group_indexes(table_indexes).items():
        if index_name == "PRIMARY":
            continue
        cols = ", ".join(quote_sql_identifier(db_type, idx.get("COLUMN_NAME", "")) for idx in grouped)
        unique = "UNIQUE " if str(grouped[0].get("NON_UNIQUE", 1)) == "0" else ""
        lines.append(f"CREATE {unique}INDEX {quote_sql_identifier(db_type, index_name)} ON {full_table_name} ({cols});")

    if db_type != "mysql":
        table_comment = ""
        if tables:
            for table in tables:
                if table.get("TABLE_NAME", "") == table_name:
                    table_comment = get_table_comment(table)
                    break
        if table_comment:
            escaped_table_comment = table_comment.replace("'", "''")
            lines.append(f"COMMENT ON TABLE {full_table_name} IS '{escaped_table_comment}';")
        for col in sorted(table_columns, key=lambda x: x.get("ORDINAL_POSITION", 0)):
            label = get_column_label(col)
            if not label or label == "-":
                continue
            label = label.replace("（推断）", "")
            escaped_label = label.replace("'", "''")
            column_name = quote_sql_identifier(db_type, col.get("COLUMN_NAME", ""))
            lines.append(f"COMMENT ON COLUMN {full_table_name}.{column_name} IS '{escaped_label}';")

    return "\n".join(lines)


def render_ddl_only(result: dict, focus_tables: set[str]) -> str:
    """只渲染 DDL 文本"""
    ddl_blocks = []
    schema = text_or_empty(result.get("schema", ""))
    for table_name in sorted(focus_tables):
        ddl_blocks.append(generate_table_ddl_like(
            table_name,
            result["columns"],
            result["indexes"],
            result["db_type"],
            schema=schema,
            tables=result.get("tables", []),
        ))
    return "\n\n".join(ddl_blocks)


def render_table_matches(result: dict, focus_tables: set[str]) -> str:
    """渲染匹配表清单"""
    table_map = {table.get("TABLE_NAME", ""): table for table in result["tables"]}
    lines = ["| 表名 | 中文名/注释 |", "|---|---|"]
    for table_name in sorted(focus_tables):
        table = table_map.get(table_name, {})
        lines.append(f"| {table_name} | {get_table_comment(table) or '-'} |")
    if len(lines) == 2:
        lines.append("| - | 未找到匹配表 |")
    return "\n".join(lines)


def group_indexes(indexes: list[dict]) -> dict[str, list[dict]]:
    """按索引名分组并按顺序排序"""
    grouped: dict[str, list[dict]] = {}
    for idx in indexes:
        grouped.setdefault(idx.get("INDEX_NAME", ""), []).append(idx)
    for index_name in grouped:
        grouped[index_name].sort(key=lambda x: x.get("SEQ_IN_INDEX", 0))
    return grouped


def escape_xml(value: Any) -> str:
    """转义 XML 属性值"""
    text = text_or_empty(value)
    return (
        text.replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace('"', "&quot;")
    )


def generate_drawio(tables: list[dict], columns: list[dict],
                    foreign_keys: list[dict], inferred: list[dict]) -> str:
    """生成原生 draw.io XML"""
    cells = [
        '<mxCell id="0"/>',
        '<mxCell id="1" parent="0"/>',
    ]

    cols_by_table: dict[str, list[dict]] = {}
    for col in columns:
        cols_by_table.setdefault(col.get("TABLE_NAME", ""), []).append(col)

    table_ids: dict[str, str] = {}
    table_width = 300
    row_height = 26
    header_height = 34
    gap_x = 380
    gap_y = 220

    for index, table in enumerate(tables):
        table_name = table.get("TABLE_NAME", "")
        table_id = f"table_{index + 1}"
        table_ids[table_name] = table_id
        table_columns = sorted(cols_by_table.get(table_name, []), key=lambda x: x.get("ORDINAL_POSITION", 0))
        height = header_height + max(len(table_columns), 1) * row_height
        x = 40 + (index % 2) * gap_x
        y = 40 + (index // 2) * max(gap_y, height + 40)
        table_comment = text_or_empty(table.get("TABLE_COMMENT", table.get("COMMENTS", "")))
        label = table_name if not table_comment else f"{table_name}\n{table_comment}"

        cells.append(
            f'<mxCell id="{table_id}" value="{escape_xml(label)}" '
            f'style="swimlane;whiteSpace=wrap;html=1;startSize={header_height};'
            f'fillColor=#dae8fc;strokeColor=#6c8ebf;fontStyle=1;container=1;collapsible=0;" '
            f'vertex="1" parent="1"><mxGeometry x="{x}" y="{y}" width="{table_width}" height="{height}" as="geometry"/></mxCell>'
        )

        for row_index, col in enumerate(table_columns):
            col_name = col.get("COLUMN_NAME", "")
            col_type = text_or_empty(col.get("COLUMN_TYPE", col.get("DATA_TYPE", "")))
            is_pk = col.get("COLUMN_KEY", "") == "PRI" or col.get("IS_PK") == "YES"
            mark = "PK " if is_pk else ""
            field_label = f"{mark}{col_name} : {col_type}"
            fill = "#d5e8d4" if is_pk else "#ffffff"
            cells.append(
                f'<mxCell id="{table_id}_field_{row_index + 1}" value="{escape_xml(field_label)}" '
                f'style="text;html=1;strokeColor=none;fillColor={fill};align=left;verticalAlign=middle;spacingLeft=8;whiteSpace=wrap;rounded=0;" '
                f'vertex="1" parent="{table_id}"><mxGeometry x="0" y="{header_height + row_index * row_height}" width="{table_width}" height="{row_height}" as="geometry"/></mxCell>'
            )

    edge_index = 1
    for fk in foreign_keys:
        source_table = fk.get("TABLE_NAME", "")
        target_table = fk.get("REFERENCED_TABLE_NAME", "")
        if source_table not in table_ids or target_table not in table_ids:
            continue
        cells.append(
            f'<mxCell id="edge_{edge_index}" value="{escape_xml(fk.get("COLUMN_NAME", ""))}" '
            f'style="edgeStyle=orthogonalEdgeStyle;rounded=0;orthogonalLoop=1;jettySize=auto;html=1;endArrow=ERmandOne;startArrow=ERmany;endFill=0;startFill=0;" '
            f'edge="1" parent="1" source="{table_ids[source_table]}" target="{table_ids[target_table]}"><mxGeometry relative="1" as="geometry" /></mxCell>'
        )
        edge_index += 1

    for rel in inferred:
        source_table = rel.get("source_table", "")
        target_table = rel.get("target_table", "")
        if source_table not in table_ids or target_table not in table_ids:
            continue
        edge_label = rel.get("source_column", "")
        cells.append(
            f'<mxCell id="edge_{edge_index}" value="{escape_xml(edge_label)}" '
            f'style="edgeStyle=orthogonalEdgeStyle;rounded=0;orthogonalLoop=1;jettySize=auto;html=1;endArrow=ERmandOne;startArrow=ERmany;endFill=0;startFill=0;dashed=1;" '
            f'edge="1" parent="1" source="{table_ids[source_table]}" target="{table_ids[target_table]}"><mxGeometry relative="1" as="geometry" /></mxCell>'
        )
        edge_index += 1

    body = "\n    ".join(cells)
    return f'''<mxfile host="app.diagrams.net">
  <diagram name="ER Diagram">
    <mxGraphModel adaptiveColors="auto" dx="1200" dy="800" grid="1" gridSize="10" guides="1" tooltips="1" connect="1" arrows="1" fold="1" page="1" pageScale="1" pageWidth="1169" pageHeight="827" math="0" shadow="0">
      <root>
    {body}
      </root>
    </mxGraphModel>
  </diagram>
</mxfile>
'''


def generate_focus_report(result: dict, focus_tables: set[str], include_er: bool) -> str:
    """生成聚焦表分析报告"""
    filtered = filter_result(result, focus_tables)
    lines = [
        f"# 聚焦表分析：{', '.join(sorted(focus_tables))}",
        "",
        f"- 数据库：`{result['database']}`",
        f"- 数据库类型：`{result['db_type']}`",
        f"- 表数量：`{len(filtered['tables'])}`",
        "",
    ]

    relations = filtered["foreign_keys"] + filtered["inferred_relations"]

    for table in filtered["tables"]:
        table_name = table.get("TABLE_NAME", "")
        table_comment = text_or_empty(table.get("TABLE_COMMENT", table.get("COMMENTS", "")))
        table_columns = [c for c in filtered["columns"] if c.get("TABLE_NAME", "") == table_name]
        table_indexes = [i for i in filtered["indexes"] if i.get("TABLE_NAME", "") == table_name]
        pk_columns = [c.get("COLUMN_NAME", "") for c in table_columns if c.get("COLUMN_KEY", "") == "PRI" or c.get("IS_PK") == "YES"]

        lines.extend([
            f"## {table_name}",
            "",
            "### 基础信息",
            "",
            "| 项目 | 值 |",
            "|------|------|",
            f"| 表名 | `{table_name}` |",
            f"| 中文名 | {table_comment or '-'} |",
            f"| 主键 | {', '.join(pk_columns) if pk_columns else '-'} |",
            f"| 字段数 | {len(table_columns)} |",
            f"| 索引数 | {len(group_indexes(table_indexes))} |",
            "",
            "### DDL（近似）",
            "",
            "```sql",
            generate_table_ddl_like(table_name, filtered["columns"], filtered["indexes"], result["db_type"], schema=text_or_empty(result.get("schema", "")), tables=filtered.get("tables", [])),
            "```",
            "",
            "### 字段信息",
            "",
            "| 英文字段 | 中文说明 | 类型 | 主键 | 可空 |",
            "|----------|----------|------|------|------|",
        ])

        for col in sorted(table_columns, key=lambda x: x.get("ORDINAL_POSITION", 0)):
            lines.append(
                f"| {col.get('COLUMN_NAME', '')} | {get_column_label(col)} | {text_or_empty(col.get('COLUMN_TYPE', col.get('DATA_TYPE', '')))} | {'是' if col.get('COLUMN_KEY', '') == 'PRI' or col.get('IS_PK') == 'YES' else '否'} | {'否' if text_or_empty(col.get('IS_NULLABLE', 'Y')).upper() in ('N', 'NO') else '是'} |"
            )
        lines.append("")

    lines.extend(["## 表间关系", "", "| 来源表 | 来源字段 | 目标表 | 目标字段 | 类型 |", "|--------|---------|--------|---------|------|"])
    if relations:
        for rel in relations:
            if "TABLE_NAME" in rel:
                rel_type = "显式外键"
                source_table = rel.get("TABLE_NAME", "")
                source_column = rel.get("COLUMN_NAME", "")
                target_table = rel.get("REFERENCED_TABLE_NAME", "")
                target_column = rel.get("REFERENCED_COLUMN_NAME", "")
            else:
                rel_type = rel.get("type", "推断关系")
                source_table = rel.get("source_table", "")
                source_column = rel.get("source_column", "")
                target_table = rel.get("target_table", "")
                target_column = rel.get("target_column", "")
                if rel_type in ("inferred_pk_match", "inferred_id") and source_column.upper().endswith("_ID"):
                    rel_type = "推断引用"
                elif rel_type == "inferred_bridge":
                    rel_type = "推断中间表"
            lines.append(f"| {source_table} | {source_column} | {target_table} | {target_column} | {rel_type} |")
    else:
        lines.append("| - | - | - | - | 未识别到关系 |")
    lines.append("")

    if include_er:
        lines.extend(["## ER 图", "", "```mermaid"])
        lines.append(generate_mermaid(filtered["tables"], filtered["columns"], filtered["foreign_keys"], filtered["inferred_relations"], max_tables=len(filtered["tables"])))
        lines.extend(["```", ""])

    return "\n".join(lines)


if __name__ == "__main__":
    main()
