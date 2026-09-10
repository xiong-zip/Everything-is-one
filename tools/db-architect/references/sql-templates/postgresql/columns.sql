-- PostgreSQL：扫描字段信息
SELECT
    c.table_name AS TABLE_NAME,
    c.column_name AS COLUMN_NAME,
    c.data_type || CASE
        WHEN c.character_maximum_length IS NOT NULL
        THEN '(' || c.character_maximum_length || ')'
        WHEN c.numeric_precision IS NOT NULL AND c.numeric_scale IS NOT NULL
        THEN '(' || c.numeric_precision || ',' || c.numeric_scale || ')'
        ELSE ''
    END AS COLUMN_TYPE,
    c.is_nullable AS IS_NULLABLE,
    c.column_default AS COLUMN_DEFAULT,
    CASE
        WHEN EXISTS (
            SELECT 1
            FROM information_schema.table_constraints tc
            JOIN information_schema.key_column_usage kcu
                ON tc.constraint_name = kcu.constraint_name
                AND tc.table_schema = kcu.table_schema
                AND tc.table_name = kcu.table_name
            WHERE tc.constraint_type = 'PRIMARY KEY'
              AND kcu.table_name = c.table_name
              AND kcu.column_name = c.column_name
              AND tc.table_schema = c.table_schema
        ) THEN 'PRI' ELSE ''
    END AS COLUMN_KEY,
    col_description(cls.oid, attr.attnum) AS COLUMN_COMMENT,
    c.ordinal_position AS ORDINAL_POSITION
FROM information_schema.columns c
LEFT JOIN pg_namespace ns
    ON ns.nspname = c.table_schema
LEFT JOIN pg_class cls
    ON cls.relname = c.table_name
    AND cls.relnamespace = ns.oid
LEFT JOIN pg_attribute attr
    ON attr.attrelid = cls.oid
    AND attr.attname = c.column_name
WHERE c.table_schema = %s
ORDER BY c.table_name, c.ordinal_position;
