package com.agentflow.db;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** export-row 无效列名自愈：报错解析、条件剔除、名称列兜底 */
class DbArchitectToolHealTest {

    @Test
    void removesInvalidColumnClauses() {
        String err = "dm.jdbc.driver.DMException: 第1 行附近出现错误:\n无效的列名[USER_CODE]";
        DbArchitectTool.HealedWhere h = DbArchitectTool.healWhere(err,
                "USER_NAME LIKE '%xiaoxiong%' OR USER_CODE LIKE '%xiaoxiong%'");
        assertNotNull(h);
        assertEquals("USER_NAME LIKE '%xiaoxiong%'", h.where());
        assertEquals("USER_CODE", h.badColumns());
    }

    @Test
    void andSeparatorKept() {
        DbArchitectTool.HealedWhere h = DbArchitectTool.healWhere("无效的列名[FOO]",
                "USER_NAME LIKE '%x%' AND FOO = 'y'");
        assertNotNull(h);
        assertEquals("USER_NAME LIKE '%x%'", h.where());
    }

    @Test
    void fallsBackToNameColumnsWhenAllRemoved() {
        DbArchitectTool.HealedWhere h = DbArchitectTool.healWhere("无效的列名[FOO]",
                "FOO LIKE '%zoe%'");
        assertNotNull(h);
        assertTrue(h.where().contains("USER_NAME LIKE '%zoe%'"));
        assertTrue(h.where().contains("SPELL_CODE LIKE '%zoe%'"));
    }

    @Test
    void returnsNullWhenNoInvalidColumn() {
        assertNull(DbArchitectTool.healWhere("connection refused", "USER_NAME LIKE '%x%'"));
        assertNull(DbArchitectTool.healWhere("无效的列名[A]", null));
        assertNull(DbArchitectTool.healWhere("无效的列名[A]", ""));
    }
}
