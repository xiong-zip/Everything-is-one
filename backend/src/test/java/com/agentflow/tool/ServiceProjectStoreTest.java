package com.agentflow.tool;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 服务↔GitLab 项目映射：持久化往返 */
class ServiceProjectStoreTest {

    @TempDir
    Path tempDir;

    @Test
    void saveFindDeleteRoundtrip() {
        ServiceProjectStore store = new ServiceProjectStore(tempDir.resolve("map-" + System.nanoTime() + ".db").toString());
        store.init();

        assertTrue(store.save("pay-service", 101L, "middle/pay-service"));
        ServiceProjectStore.ServiceProject found = store.find("pay-service");
        assertEquals(101L, found.projectId());
        assertEquals("middle/pay-service", found.projectPath());

        // 覆盖保存（同服务换项目）
        assertTrue(store.save("pay-service", 202L, "core/pay"));
        assertEquals(202L, store.find("pay-service").projectId());
        assertEquals(1, store.list().size());

        assertTrue(store.delete("pay-service"));
        assertNull(store.find("pay-service"));
        assertFalse(store.delete("pay-service"));
        assertNull(store.find(null));
    }
}
