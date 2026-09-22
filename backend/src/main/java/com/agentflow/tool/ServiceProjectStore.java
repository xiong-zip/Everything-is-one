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
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * 服务名 ↔ GitLab 项目映射持久化（SQLite service_project_map 表）。
 * 由工作台「服务映射」面板维护；gitlab.changes 工具在用户显式给出映射
 * （澄清卡点选重跑 / mapping 参数）时也会自动固化到这里，越用越准。
 */
@Component
public class ServiceProjectStore {

    private static final Logger log = LoggerFactory.getLogger(ServiceProjectStore.class);

    /** 一条映射：service 为 SigNoz/K8s 里的服务名，projectPath 为 GitLab 的 path_with_namespace */
    public record ServiceProject(String service, long projectId, String projectPath, String createdAt) {
    }

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final String url;

    public ServiceProjectStore(@Value("${agentflow.storage.path:./data/agentflow.db}") String path) {
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
            st.execute("CREATE TABLE IF NOT EXISTS service_project_map (" +
                    "service TEXT PRIMARY KEY," +
                    "project_id INTEGER NOT NULL," +
                    "project_path TEXT NOT NULL," +
                    "created_at TEXT NOT NULL)");
        } catch (Exception ex) {
            log.error("初始化 service_project_map 表失败：{}", ex.getMessage());
        }
    }

    /** 保存（按服务名 upsert）；projectId 传 0 表示暂不知道 ID，下次使用时按路径解析 */
    public boolean save(String service, long projectId, String projectPath) {
        String createdAt = LocalDateTime.now().format(TS);
        try (Connection c = open();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO service_project_map(service, project_id, project_path, created_at) VALUES(?, ?, ?, ?) " +
                             "ON CONFLICT(service) DO UPDATE SET project_id=excluded.project_id, project_path=excluded.project_path")) {
            ps.setString(1, service.trim());
            ps.setLong(2, projectId);
            ps.setString(3, projectPath.trim());
            ps.setString(4, createdAt);
            ps.executeUpdate();
            return true;
        } catch (Exception ex) {
            log.warn("保存服务映射失败：{}", ex.getMessage());
            return false;
        }
    }

    public List<ServiceProject> list() {
        List<ServiceProject> out = new ArrayList<>();
        try (Connection c = open(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT service, project_id, project_path, created_at FROM service_project_map ORDER BY service")) {
            while (rs.next()) {
                out.add(new ServiceProject(rs.getString("service"), rs.getLong("project_id"),
                        rs.getString("project_path"), rs.getString("created_at")));
            }
        } catch (Exception ex) {
            log.warn("读取服务映射失败：{}", ex.getMessage());
        }
        return out;
    }

    public ServiceProject find(String service) {
        if (service == null || service.isBlank()) {
            return null;
        }
        try (Connection c = open();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT service, project_id, project_path, created_at FROM service_project_map WHERE service = ?")) {
            ps.setString(1, service.trim());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? new ServiceProject(rs.getString("service"), rs.getLong("project_id"),
                        rs.getString("project_path"), rs.getString("created_at")) : null;
            }
        } catch (Exception ex) {
            log.warn("查询服务映射失败：{}", ex.getMessage());
            return null;
        }
    }

    public boolean delete(String service) {
        try (Connection c = open();
             PreparedStatement ps = c.prepareStatement("DELETE FROM service_project_map WHERE service = ?")) {
            ps.setString(1, service);
            return ps.executeUpdate() > 0;
        } catch (Exception ex) {
            log.warn("删除服务映射失败：{}", ex.getMessage());
            return false;
        }
    }

    private Connection open() throws SQLException {
        return Sqlite.open(url);
    }
}
