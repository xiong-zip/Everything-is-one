-- PostgreSQL：扫描表清单
SELECT
    t.table_name AS TABLE_NAME,
    obj_description(c.oid) AS TABLE_COMMENT,
    pg_stat_get_tuples_returned(c.oid) AS TABLE_ROWS
FROM information_schema.tables t
LEFT JOIN pg_class c ON c.relname = t.table_name
WHERE t.table_schema = %s
  AND t.table_type = 'BASE TABLE'
ORDER BY t.table_name;
