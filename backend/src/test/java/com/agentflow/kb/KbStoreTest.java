package com.agentflow.kb;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 知识库持久化：文件 CRUD、重传替换、删除级联 */
class KbStoreTest {

    @TempDir
    Path tempDir;

    private KbStore newStore() {
        KbStore s = new KbStore(tempDir.resolve("kb-" + System.nanoTime() + ".db").toString());
        s.init();
        return s;
    }

    @Test
    void saveListDeleteRoundtrip() {
        KbStore store = newStore();
        long id = store.saveFile("deploy.md", "123-deploy.md", 1024, 5200,
                List.of("块一内容", "块二内容", "块三内容"));
        assertTrue(id > 0);

        KbStore.KbFile f = store.find(id);
        assertEquals("deploy.md", f.filename());
        assertEquals(3, f.chunkCount());
        assertEquals(5200, f.charCount());

        List<KbStore.KbChunk> chunks = store.chunksOf(id);
        assertEquals(3, chunks.size());
        assertEquals(1, chunks.get(0).seq());
        assertEquals("块一内容", chunks.get(0).content());
        assertEquals(3, store.allChunks().size());
        assertEquals(1, store.list().size());
        assertEquals("deploy.md", store.findByName("deploy.md").filename());

        // 删除：级联删块
        assertNotNull(store.delete(id));
        assertNull(store.find(id));
        assertTrue(store.chunksOf(id).isEmpty());
        assertTrue(store.allChunks().isEmpty());
        assertNull(store.delete(id));
    }

    @Test
    void reuploadSameNameReplacesChunks() {
        KbStore store = newStore();
        store.saveFile("guide.txt", "a-guide.txt", 100, 800, List.of("旧块1", "旧块2", "旧块3", "旧块4"));

        // 同名重传：新内容替换，旧块全部消失
        store.saveFile("guide.txt", "a-guide.txt", 120, 300, List.of("新块1", "新块2"));
        assertEquals(1, store.list().size());
        KbStore.KbFile f = store.findByName("guide.txt");
        assertEquals(2, f.chunkCount());
        List<KbStore.KbChunk> chunks = store.chunksOf(f.id());
        assertEquals("新块1", chunks.get(0).content());
        assertTrue(store.allChunks().stream().noneMatch(c -> c.content().startsWith("旧块")));
    }

    @Test
    void multipleFilesKeepChunksIsolated() {
        KbStore store = newStore();
        long a = store.saveFile("a.md", "1-a.md", 10, 100, List.of("A1", "A2"));
        long b = store.saveFile("b.md", "2-b.md", 10, 100, List.of("B1"));
        assertEquals(3, store.allChunks().size());
        assertEquals(2, store.chunksOf(a).size());
        assertEquals(1, store.chunksOf(b).size());
        // 删 a 不影响 b
        store.delete(a);
        assertEquals(1, store.allChunks().size());
        assertEquals("B1", store.allChunks().get(0).content());
    }
}
