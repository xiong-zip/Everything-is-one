package com.agentflow.tool;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 账户存储的新旧共存契约：config_json 固定 name/token/createdAt 三字段
 * （旧进程按固定字段反序列化，多一个未知字段会让整行乃至整个列表读取失败——实测踩过），
 * 别名走独立列；读取对 config_json 里的未知字段完全免疫。
 */
class GitLabAccountStoreTest {

    @TempDir
    Path tempDir;

    private GitLabAccountStore newStore() {
        GitLabAccountStore s = new GitLabAccountStore(tempDir.resolve("acc-" + System.nanoTime() + ".db").toString());
        s.init();
        return s;
    }

    @Test
    void authorsRoundTripViaColumn() {
        GitLabAccountStore store = newStore();
        store.save(new GitLabAccount("XX", "tok-1", null, List.of("xiaoxiong", "2665684431@qq.com")));
        GitLabAccount back = store.find("XX");
        assertEquals(List.of("xiaoxiong", "2665684431@qq.com"), back.authors());
        assertEquals(List.of("xiaoxiong", "2665684431@qq.com"), store.list().get(0).authors());
    }

    /** 存进 config_json 的内容必须保持旧三字段形状，别名只能出现在独立列 */
    @Test
    void configJsonKeepsLegacyShape() throws Exception {
        GitLabAccountStore store = newStore();
        store.save(new GitLabAccount("XX", "tok-1", null, List.of("xiaoxiong")));
        try (Connection c = java.sql.DriverManager.getConnection(
                "jdbc:sqlite:" + tempDir.resolve(latestDb()).toString());
             java.sql.Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT config_json, authors FROM gitlab_accounts")) {
            assertTrue(rs.next());
            String configJson = rs.getString("config_json");
            assertFalse(configJson.contains("authors"), "config_json 不能出现 authors 字段：\n" + configJson);
            assertTrue(configJson.contains("\"name\"") && configJson.contains("\"token\"") && configJson.contains("\"createdAt\""));
            assertTrue(rs.getString("authors").contains("xiaoxiong"), "别名应存放在独立列");
        }
    }

    /** 任何版本写出的 config_json（含未知字段）都不能让读取失败 */
    @Test
    void toleratesUnknownFieldsInConfigJson() throws Exception {
        GitLabAccountStore store = newStore();
        store.save(new GitLabAccount("kun", "tok-2", null));
        String db = tempDir.resolve(latestDb()).toString();
        try (Connection c = java.sql.DriverManager.getConnection("jdbc:sqlite:" + db);
             java.sql.Statement st = c.createStatement()) {
            st.executeUpdate("UPDATE gitlab_accounts SET config_json='" +
                    "{\"name\":\"kun\",\"token\":\"tok-2\",\"createdAt\":\"x\",\"surprise\":\"?\"}'" +
                    " WHERE name='kun'");
        }
        assertEquals("tok-2", store.find("kun").token());
        assertEquals(1, store.list().size());
    }

    @Test
    void activeAuthorsFollowsActiveAccount() {
        GitLabAccountStore store = newStore();
        store.save(new GitLabAccount("kun", "tok-1", null, List.of("a@x.com")));
        store.save(new GitLabAccount("XX", "tok-2", null, List.of("b@x.com")));
        store.setActive("XX");
        assertEquals(List.of("b@x.com"), store.activeAuthors());
    }

    /** init() 用 nanoTime 命名库文件，这里取目录里唯一的那个 */
    private String latestDb() {
        try (var s = java.nio.file.Files.list(tempDir)) {
            return s.filter(p -> p.toString().endsWith(".db")).findFirst().orElseThrow().getFileName().toString();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
