-- PostgreSQL：扫描索引信息
SELECT
    t.relname AS TABLE_NAME,
    i.relname AS INDEX_NAME,
    a.attname AS COLUMN_NAME,
    CASE WHEN ix.indisunique THEN 0 ELSE 1 END AS NON_UNIQUE,
    array_position(ix.indkey, a.attnum) AS SEQ_IN_INDEX
FROM pg_index ix
JOIN pg_class t ON t.oid = ix.indrelid
JOIN pg_class i ON i.oid = ix.indexrelid
JOIN pg_attribute a ON a.attrelid = t.oid AND a.attnum = ANY(ix.indkey)
JOIN pg_namespace n ON n.oid = t.relnamespace
WHERE n.nspname = %s
ORDER BY t.relname, i.relname, array_position(ix.indkey, a.attnum);
