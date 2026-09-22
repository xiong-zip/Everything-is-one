package com.agentflow.tool;

import com.agentflow.engine.Sqlite;
import com.agentflow.engine.StoragePaths;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 工具停用名单（SQLite disabled_tools 表）。
 *
 * <p>停用而不是删除：内置工具是编译在代码里的，删不掉但可以关掉。工具清单会随每次规划注入
 * prompt，接入的工具越多输入 token 越大（实测某服务端 38 个工具约 7k token/次），
 * 关掉用不上的工具能直接省下每一次调用的成本，也让规划更聚焦。
 */
@Component
public class ToolDisableStore {

    private static final Logger log = LoggerFactory.getLogger(ToolDisableStore.class);

    private final String url;
    /** 内存镜像：规划路径每次都要读，不走库 */
    private volatile Set<String> disabled = Set.of();

    public ToolDisableStore(@Value("${agentflow.storage.path:./data/agentflow.db}") String path) {
        String dbPath = StoragePaths.resolve(path);
        try {
            Path parent = Path.of(dbPath).toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
        } catch (Exception ex) {
            log.warn("创建存储目录失败：{}", ex.getMessage());
        }
        this.url = "jdbc:sqlite:" + dbPath;
    }

    @PostConstruct
    public void init() {
        try (Connection c = open(); Statement st = c.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS disabled_tools (name TEXT PRIMARY KEY)");
        } catch (Exception ex) {
            log.error("初始化 disabled_tools 表失败，工具停用状态将不可用：{}", ex.getMessage());
        }
        reload();
    }

    public boolean isDisabled(String name) {
        return name != null && disabled.contains(name);
    }

    public Set<String> all() {
        return disabled;
    }

    /** 设置停用状态并持久化；返回是否变更成功 */
    public boolean setDisabled(String name, boolean off) {
        if (name == null || name.isBlank()) {
            return false;
        }
        try (Connection c = open()) {
            if (off) {
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT OR IGNORE INTO disabled_tools(name) VALUES(?)")) {
                    ps.setString(1, name);
                    ps.executeUpdate();
                }
            } else {
                try (PreparedStatement ps = c.prepareStatement("DELETE FROM disabled_tools WHERE name = ?")) {
                    ps.setString(1, name);
                    ps.executeUpdate();
                }
            }
        } catch (Exception ex) {
            log.warn("保存工具停用状态失败：{}", ex.getMessage());
            return false;
        }
        reload();
        return true;
    }

    private void reload() {
        Set<String> next = new LinkedHashSet<>();
        try (Connection c = open(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT name FROM disabled_tools")) {
            while (rs.next()) {
                String n = rs.getString(1);
                if (n != null && !n.isBlank()) {
                    next.add(n);
                }
            }
        } catch (Exception ex) {
            log.warn("读取工具停用名单失败：{}", ex.getMessage());
        }
        this.disabled = Set.copyOf(next);
    }

    private Connection open() throws SQLException {
        return Sqlite.open(url);
    }
}
