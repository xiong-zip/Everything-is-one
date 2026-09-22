package com.agentflow.engine;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** SQLite 接入层：WAL 与 synchronous 必须真的生效，否则并发下会退化成逐次 fsync 的回滚日志模式 */
class SqliteTest {

    @TempDir
    Path tempDir;

    private String url() {
        return "jdbc:sqlite:" + tempDir.resolve("sqlite-test-" + System.nanoTime() + ".db");
    }

    @Test
    void enablesWalAndNormalSynchronous() throws Exception {
        String url = url();
        try (Connection c = Sqlite.open(url); Statement st = c.createStatement()) {
            st.execute("CREATE TABLE t (id INTEGER)");
            assertEquals("wal", query(st, "PRAGMA journal_mode"));
            // synchronous：0=OFF 1=NORMAL 2=FULL
            assertEquals("1", query(st, "PRAGMA synchronous"));
        }
        // journal_mode 持久化在库文件里：重开连接依然是 WAL，不需要再设一遍
        try (Connection c = Sqlite.open(url); Statement st = c.createStatement()) {
            assertEquals("wal", query(st, "PRAGMA journal_mode"));
        }
    }

    @Test
    void busyTimeoutIsSetPerConnection() throws Exception {
        try (Connection c = Sqlite.open(url()); Statement st = c.createStatement()) {
            assertEquals("5000", query(st, "PRAGMA busy_timeout"));
        }
    }

    @Test
    void writesAreVisibleToOtherConnections() throws Exception {
        String url = url();
        try (Connection c = Sqlite.open(url); Statement st = c.createStatement()) {
            st.execute("CREATE TABLE t (id INTEGER, v TEXT)");
            st.executeUpdate("INSERT INTO t(id, v) VALUES(1, 'a')");
        }
        try (Connection c = Sqlite.open(url); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT v FROM t WHERE id = 1")) {
            assertTrue(rs.next());
            assertEquals("a", rs.getString(1));
        }
    }

    /** PRAGMA 返回一行一列，取值统一走这里 */
    private static String query(Statement st, String pragma) throws Exception {
        try (ResultSet rs = st.executeQuery(pragma)) {
            return rs.next() ? String.valueOf(rs.getObject(1)) : "";
        }
    }
}
