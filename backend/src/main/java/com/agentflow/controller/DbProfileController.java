package com.agentflow.controller;

import com.agentflow.db.DbProfile;
import com.agentflow.db.DbProfileStore;
import com.agentflow.db.DbScannerRunner;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** 数据库连接管理：连接配置存程序内（SQLite），前端「数据库连接」抽屉维护 */
@RestController
@RequestMapping("/api/dbprofiles")
public class DbProfileController {

    private final DbProfileStore store;
    private final DbScannerRunner runner;

    public DbProfileController(DbProfileStore store, DbScannerRunner runner) {
        this.store = store;
        this.runner = runner;
    }

    @GetMapping
    public List<Map<String, Object>> list() {
        return store.listSafe();
    }

    /** 保存（按 name 覆盖）；password 留空表示沿用原密码 */
    @PostMapping
    public Map<String, String> save(@RequestBody DbProfileRequest req) {
        validate(req);
        DbProfile existing = store.find(req.name);
        String password = req.password == null || req.password.isBlank()
                ? (existing == null ? "" : existing.password())
                : req.password;
        DbProfile p = new DbProfile(req.name, req.type, req.host, req.port,
                req.databases, req.username, password, emptyToNull(req.schema),
                existing == null ? null : existing.createdAt());
        if (!store.save(p)) {
            throw new IllegalArgumentException("保存失败，请检查后重试");
        }
        return Map.of("ok", "saved");
    }

    @DeleteMapping("/{name}")
    public Map<String, String> delete(@PathVariable("name") String name) {
        if (!store.delete(name)) {
            throw new IllegalArgumentException("连接不存在：" + name);
        }
        return Map.of("ok", "deleted");
    }

    /** 测试连接：跑一次最小扫描（连接 + 读取 1 张表元数据），约 5~20 秒 */
    @PostMapping("/{name}/test")
    public Map<String, Object> test(@PathVariable("name") String name) {
        if (store.find(name) == null) {
            throw new IllegalArgumentException("连接不存在：" + name);
        }
        DbScannerRunner.ScanResult result = runner.run(
                List.of("scan", "--profile", name, "--max-tables", "1"), name);
        String tail = tail(result.output(), 600);
        return Map.of("ok", result.ok(), "message",
                result.ok() ? "连接成功，可正常读取库表元数据" : "连接失败：" + tail);
    }

    private static void validate(DbProfileRequest req) {
        if (req == null || isBlank(req.name) || isBlank(req.type) || isBlank(req.host)
                || isBlank(req.databases) || isBlank(req.username) || req.port <= 0) {
            throw new IllegalArgumentException("name/type/host/port/databases/username 均不能为空");
        }
        if (!req.name.matches("[A-Za-z0-9_]{1,32}")) {
            throw new IllegalArgumentException("连接名仅限字母/数字/下划线，最长 32 位（如 DM_TEST）");
        }
        if (!List.of("mysql", "oracle", "postgresql", "dameng").contains(req.type.toLowerCase())) {
            throw new IllegalArgumentException("type 仅支持 mysql / oracle / postgresql / dameng");
        }
    }

    private static String tail(String s, int max) {
        if (s == null || s.length() <= max) {
            return s == null ? "" : s;
        }
        return "…" + s.substring(s.length() - max);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static String emptyToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    public record DbProfileRequest(String name, String type, String host, int port,
                                   String databases, String username, String password, String schema) {
    }
}
