-- PostgreSQL：扫描外键关系
SELECT
    tc.table_name AS TABLE_NAME,
    kcu.column_name AS COLUMN_NAME,
    ccu.table_name AS REFERENCED_TABLE_NAME,
    ccu.column_name AS REFERENCED_COLUMN_NAME,
    tc.constraint_name AS CONSTRAINT_NAME
FROM information_schema.table_constraints tc
JOIN information_schema.key_column_usage kcu
    ON tc.constraint_name = kcu.constraint_name
    AND tc.table_schema = kcu.table_schema
JOIN information_schema.constraint_column_usage ccu
    ON ccu.constraint_name = tc.constraint_name
    AND ccu.table_schema = tc.table_schema
WHERE tc.constraint_type = 'FOREIGN KEY'
  AND tc.table_schema = %s
ORDER BY tc.table_name, tc.constraint_name;
