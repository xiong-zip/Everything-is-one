package com.agentflow.engine;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * SQLite 接入统一入口：集中连接参数，避免各 Store 各写一份 PRAGMA。
 *
 * <p>journal_mode 持久化在库文件头，成功设置一次即对后续所有连接生效，故按 url 去重；
 * synchronous 是连接级参数，每次建连都要重设。
 *
 * <p>WAL + synchronous=NORMAL 用「掉电可能丢最后几个事务」换取「提交不再逐次 fsync」，
 * 对会话历史这类可重建数据是合适的取舍；要强持久化就把 synchronous 改回 FULL。
 */
public final class Sqlite {

    private static final Logger log = LoggerFactory.getLogger(Sqlite.class);

    private static final int BUSY_TIMEOUT_MS = 5000;
    private static final Set<String> WAL_READY = ConcurrentHashMap.newKeySet();

    private Sqlite() {
    }

    /** 打开一条已配置好的连接；调用方负责关闭。 */
    public static Connection open(String url) throws SQLException {
        ensureWal(url);
        Connection c = DriverManager.getConnection(url);
        try (Statement st = c.createStatement()) {
            st.execute("PRAGMA busy_timeout=" + BUSY_TIMEOUT_MS);
            st.execute("PRAGMA synchronous=NORMAL");
        } catch (SQLException ex) {
            c.close();
            throw ex;
        }
        return c;
    }

    public static String jdbcUrl(String dbPath) {
        return "jdbc:sqlite:" + dbPath;
    }

    /** 回滚并忽略异常：连接已坏时回滚同样会失败，不应让它掩盖真正的错误原因 */
    public static void rollbackQuietly(Connection c) {
        if (c == null) {
            return;
        }
        try {
            c.rollback();
        } catch (Exception ignore) {
            // 连接不可用，交给 closeQuietly 收尾
        }
    }

    public static void closeQuietly(Connection c) {
        if (c == null) {
            return;
        }
        try {
            c.close();
        } catch (Exception ignore) {
            // 关闭失败无补救手段
        }
    }

    private static void ensureWal(String url) {
        if (!WAL_READY.add(url)) {
            return;
        }
        try (Connection c = DriverManager.getConnection(url); Statement st = c.createStatement()) {
            st.execute("PRAGMA journal_mode=WAL");
        } catch (Exception ex) {
            // 去掉标记，下次建连时再试；失败不影响功能，只是退回默认回滚日志模式
            WAL_READY.remove(url);
            log.warn("启用 SQLite WAL 失败，回退默认日志模式：{}", ex.getMessage());
        }
    }
}
