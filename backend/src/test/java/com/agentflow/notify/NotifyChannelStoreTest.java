package com.agentflow.notify;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 推送通道：持久化、多选集合、改名与删除时选中状态的跟随 */
class NotifyChannelStoreTest {

    @TempDir
    Path tempDir;

    private NotifyChannelStore newStore() {
        NotifyChannelStore s = new NotifyChannelStore(tempDir.resolve("nt-" + System.nanoTime() + ".db").toString());
        s.init();
        return s;
    }

    private static NotifyChannel channel(String name, String url) {
        return new NotifyChannel(name, NotifyChannel.detectType(url), url, null);
    }

    @Test
    void saveListFindDelete() {
        NotifyChannelStore store = newStore();
        assertTrue(store.isEmpty());
        assertNull(store.find("研发群"));

        assertTrue(store.save(channel("研发群", "https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=a")));
        assertTrue(store.save(channel("告警群", "https://oapi.dingtalk.com/robot/send?access_token=b")));

        List<NotifyChannel> all = store.list();
        assertEquals(2, all.size());
        assertNotNull(all.get(0).createdAt());
        assertEquals(NotifyChannel.DINGTALK, store.find("告警群").type());

        assertTrue(store.delete("研发群"));
        assertFalse(store.delete("研发群"));
        assertEquals(1, store.list().size());
        assertFalse(store.isEmpty());
    }

    @Test
    void selectedSetRoundtripsAndIgnoresUnknownNames() {
        NotifyChannelStore store = newStore();
        store.save(channel("A", "https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=a"));
        store.save(channel("B", "https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=b"));
        store.save(channel("C", "https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=c"));

        assertTrue(store.selected().isEmpty(), "默认一个都不勾选");

        store.setSelected(List.of("A", "C", "不存在的通道"));
        // 不存在的名字被过滤，避免删掉通道后选中集合里留垃圾
        assertEquals(List.of("A", "C"), store.selected());

        // 去重 + 去空
        store.setSelected(List.of("B", "B", "  ", ""));
        assertEquals(List.of("B"), store.selected());

        store.setSelected(null);
        assertTrue(store.selected().isEmpty());
    }

    @Test
    void deletingChannelDropsItFromSelection() {
        NotifyChannelStore store = newStore();
        store.save(channel("A", "https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=a"));
        store.save(channel("B", "https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=b"));
        store.setSelected(List.of("A", "B"));

        store.delete("A");
        assertEquals(List.of("B"), store.selected());
        // 原始持久化值里也要摘干净，否则已删通道名会永久残留在 meta 里
        assertEquals(List.of("B"), store.selectedRaw());
    }

    @Test
    void renameKeepsSelection() {
        NotifyChannelStore store = newStore();
        store.save(channel("老名字", "https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=a"));
        store.setSelected(List.of("老名字"));

        NotifyChannel renamed = new NotifyChannel("新名字", NotifyChannel.WECOM,
                "https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=a", null);
        assertTrue(store.saveRenamed(renamed, "老名字"));

        // 改个名不该把通道从推送目标里摘掉
        assertEquals(List.of("新名字"), store.selected());
        assertNull(store.find("老名字"));
        assertNotNull(store.find("新名字"));
    }

    @Test
    void renameOfUnselectedChannelDoesNotSelectIt() {
        NotifyChannelStore store = newStore();
        store.save(channel("A", "https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=a"));
        store.save(channel("B", "https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=b"));
        store.setSelected(List.of("A"));

        store.saveRenamed(new NotifyChannel("B2", NotifyChannel.WECOM,
                "https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=b", null), "B");

        assertEquals(List.of("A"), store.selected(), "改名不应顺带把它勾上");
    }
}
