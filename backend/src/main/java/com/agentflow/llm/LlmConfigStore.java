package com.agentflow.llm;

import com.agentflow.engine.StoragePaths;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;

/** LLM 运行时配置持久化（SQLite llm_config 单行表）：工作台「模型接入」保存后即生效 */
@Component
public class LlmConfigStore {

    private static final Logger log = LoggerFactory.getLogger(LlmConfigStore.class);

    private final String url;

    public LlmConfigStore(@Value("${agentflow.storage.path:./data/agentflow.db}") String path) {
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
    void init() {
        try (Connection c = open(); Statement st = c.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS llm_config (" +
                    "id INTEGER PRIMARY KEY CHECK (id = 1)," +
                    "provider TEXT NOT NULL," +
                    "base_url TEXT NOT NULL," +
                    "api_key TEXT NOT NULL," +
                    "model TEXT NOT NULL)");
        } catch (Exception ex) {
            log.error("初始化 llm_config 表失败：{}", ex.getMessage());
        }
    }

    private Connection open() throws SQLException {
        Connection c = DriverManager.getConnection(url);
        try (Statement st = c.createStatement()) {
            st.execute("PRAGMA busy_timeout=5000");
        }
        return c;
    }

    /** 读取运行时配置；未配置过返回 null（回退 .env 默认） */
    public Map<String, String> load() {
        try (Connection c = open();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT provider, base_url, api_key, model FROM llm_config WHERE id = 1");
             ResultSet rs = ps.executeQuery()) {
            if (rs.next()) {
                Map<String, String> m = new LinkedHashMap<>();
                m.put("provider", rs.getString("provider"));
                m.put("baseUrl", rs.getString("base_url"));
                m.put("apiKey", rs.getString("api_key"));
                m.put("model", rs.getString("model"));
                return m;
            }
        } catch (Exception ex) {
            log.warn("读取 LLM 配置失败：{}", ex.getMessage());
        }
        return null;
    }

    public void save(String provider, String baseUrl, String apiKey, String model) {
        try (Connection c = open();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO llm_config(id, provider, base_url, api_key, model) VALUES(1, ?, ?, ?, ?) " +
                             "ON CONFLICT(id) DO UPDATE SET provider=excluded.provider, base_url=excluded.base_url, " +
                             "api_key=excluded.api_key, model=excluded.model")) {
            ps.setString(1, provider);
            ps.setString(2, baseUrl);
            ps.setString(3, apiKey);
            ps.setString(4, model);
            ps.executeUpdate();
        } catch (Exception ex) {
            log.warn("保存 LLM 配置失败：{}", ex.getMessage());
        }
    }

    /** 清除运行时配置，回退 .env 默认 */
    public void clear() {
        try (Connection c = open(); Statement st = c.createStatement()) {
            st.executeUpdate("DELETE FROM llm_config WHERE id = 1");
        } catch (Exception ex) {
            log.warn("清除 LLM 配置失败：{}", ex.getMessage());
        }
    }
}
