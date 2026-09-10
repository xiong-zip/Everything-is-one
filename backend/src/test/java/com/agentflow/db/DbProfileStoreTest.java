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
                "ZOE_BASIC_SYS,OTHER", "sysdba", "secret", "ZOE_BASIC_SYS", null);
        assertTrue(store.save(p));

        DbProfile found = store.find("DM_TEST");
        assertEquals("dameng", found.type());
        assertEquals(5253, found.port());
        assertEquals("ZOE_BASIC_SYS", found.firstDatabase());
        assertEquals("secret", found.password());

        // 覆盖保存
        assertTrue(store.save(new DbProfile("DM_TEST", "dameng", "192.168.2.116", 5254,
                "ZOE_BASIC_SYS", "sysdba", "secret2", null, found.createdAt())));
        assertEquals(5254, store.find("DM_TEST").port());
        assertEquals(1, store.list().size());

        // 脱敏列表：不回传密码
        assertFalse(String.valueOf(store.listSafe()).contains("secret2"));

        assertTrue(store.delete("DM_TEST"));
        assertNull(store.find("DM_TEST"));
        assertFalse(store.delete("DM_TEST"));
    }
}
