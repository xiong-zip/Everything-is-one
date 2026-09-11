package com.agentflow.db;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 数据库连接配置：持久化往返与脱敏列表 */
class DbProfileStoreTest {

    @TempDir
    Path tempDir;

    @Test
    void saveFindDeleteRoundtrip() {
        DbProfileStore store = new DbProfileStore(tempDir.resolve("db-" + System.nanoTime() + ".db").toString());
        store.init();

        DbProfile p = new DbProfile("DM_TEST", "dameng", "192.168.2.116", 5253,
                "ZOE_BASIC_SYS,OTHER", "sysdba", "secret", "ZOE_BASIC_SYS", "测试", null);
        assertTrue(store.save(p));

        DbProfile found = store.find("DM_TEST");
        assertEquals("dameng", found.type());
        assertEquals(5253, found.port());
        assertEquals("ZOE_BASIC_SYS", found.firstDatabase());
        assertEquals("secret", found.password());
        assertEquals("测试", found.environment());

        // 覆盖保存
        assertTrue(store.save(new DbProfile("DM_TEST", "dameng", "192.168.2.116", 5254,
                "ZOE_BASIC_SYS", "sysdba", "secret2", null, "预发", found.createdAt())));
        assertEquals(5254, store.find("DM_TEST").port());
        assertEquals(1, store.list().size());

        // 脱敏列表：不回传密码，但回传环境标签
        String safe = String.valueOf(store.listSafe());
        assertFalse(safe.contains("secret2"));
        assertTrue(safe.contains("预发"), safe);

        assertTrue(store.delete("DM_TEST"));
        assertNull(store.find("DM_TEST"));
        assertFalse(store.delete("DM_TEST"));
    }

    @Test
    void renameKeepsDataAndFollowsActivePointer() {
        DbProfileStore store = new DbProfileStore(tempDir.resolve("db-" + System.nanoTime() + ".db").toString());
        store.init();
        DbProfile p = new DbProfile("DM_OLD", "dameng", "h", 5253, "db1", "u", "p", null, "测试", null);
        store.save(p);
        store.setActive("DM_OLD");

        // 改名：原名消失、新名可查、密码等字段保留、默认指针跟随
        DbProfile renamed = new DbProfile("DM_NEW", p.type(), p.host(), p.port(), p.databases(),
                p.username(), p.password(), p.schemaName(), p.environment(), p.createdAt());
        assertTrue(store.saveRenamed(renamed, "DM_OLD"));

        assertNull(store.find("DM_OLD"));
        DbProfile got = store.find("DM_NEW");
        assertEquals("测试", got.environment());
        assertEquals("p", got.password());
        assertEquals(1, store.list().size());
        assertEquals("DM_NEW", store.getActive(), "改名后默认连接应跟随到新名字");
    }

    @Test
    void renameToSameNameIsPlainSave() {
        DbProfileStore store = new DbProfileStore(tempDir.resolve("db-" + System.nanoTime() + ".db").toString());
        store.init();
        DbProfile p = new DbProfile("SAME", "mysql", "h", 3306, "db1", "u", "p", null, null, null);
        store.save(p);
        assertTrue(store.saveRenamed(p, "SAME"));
        assertEquals(1, store.list().size());
    }

    @Test
    void activeProfileLifecycle() {
        DbProfileStore store = new DbProfileStore(tempDir.resolve("db-" + System.nanoTime() + ".db").toString());
        store.init();
        assertNull(store.getActive());

        store.save(new DbProfile("A", "mysql", "h1", 3306, "db1", "u", "p", null, null, null));
        store.save(new DbProfile("B", "dameng", "h2", 5253, "db2", "u", "p", null, null, null));

        store.setActive("B");
        assertEquals("B", store.getActive());

        // 删除默认连接后自动失效
        assertTrue(store.delete("B"));
        assertNull(store.getActive());

        // 清空默认
        store.setActive("A");
        store.setActive(null);
        assertNull(store.getActive());
    }
}
