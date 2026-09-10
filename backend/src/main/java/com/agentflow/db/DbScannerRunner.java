package com.agentflow.db;

import com.agentflow.engine.StoragePaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * db_scanner.py 进程执行器：每次调用把程序内管理的连接配置写成临时 .env 传给 --env，
 * 跑完即删（密码不落命令行、不留盘）；同时回收输出目录里的主报告内容。
 */
@Component
public class DbScannerRunner {

    private static final Logger log = LoggerFactory.getLogger(DbScannerRunner.class);
    private static final long TIMEOUT_S = 150;
    private static final int MAX_OUTPUT_CHARS = 120_000;
    private static final int MAX_REPORT_CHARS = 9_000;

    /** 扫描结果：exitCode 0 为成功；reportText 为主报告（无则为空） */
    public record ScanResult(int exitCode, String output, String reportText) {
        public boolean ok() {
            return exitCode == 0;
        }
    }

    private final DbProfileStore store;
    private final String pythonCmd;
    private final String scriptsDir;

    public DbScannerRunner(DbProfileStore store,
                           @Value("${agentflow.db.python-cmd:python}") String pythonCmd,
                           @Value("${agentflow.db.scripts-path:./tools/db-architect/scripts}") String scriptsDir) {
        this.store = store;
        this.pythonCmd = pythonCmd == null || pythonCmd.isBlank() ? "python" : pythonCmd.trim();
        this.scriptsDir = StoragePaths.resolve(scriptsDir == null || scriptsDir.isBlank()
                ? "./tools/db-architect/scripts" : scriptsDir);
    }

    public String scriptsDir() {
        return scriptsDir;
    }

    /**
     * 执行一次扫描。args 为 db_scanner.py 的参数（不含 python/脚本/--env/--output），
     * defaultProfile 写入临时 env 的 DB_PROFILE（未显式 --profile 时的默认连接）。
     */
    public ScanResult run(List<String> args, String defaultProfile) {
        Path envFile = null;
        Path outDir = null;
        try {
            if (store.list().isEmpty()) {
                return new ScanResult(1, "还没有配置数据库连接，请先在「工作台 → 数据库连接」中添加", "");
            }
            envFile = writeEnvFile(defaultProfile);
            outDir = Files.createTempDirectory("af-db-out");

            List<String> cmd = new ArrayList<>();
            cmd.add(pythonCmd);
            cmd.add(Path.of(scriptsDir, "db_scanner.py").toString());
            cmd.addAll(args);
            cmd.addAll(List.of("--env", envFile.toString(), "--output", outDir.toString()));

            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.directory(new File(scriptsDir));
            pb.environment().put("PYTHONIOENCODING", "utf-8");
            pb.redirectErrorStream(true);
            Process proc = pb.start();

            StringBuilder out = new StringBuilder();
            Thread reader = new Thread(() -> {
                try (BufferedReader r = new BufferedReader(
                        new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = r.readLine()) != null) {
                        if (out.length() < MAX_OUTPUT_CHARS) {
                            out.append(line).append('\n');
                        }
                    }
                } catch (Exception ignored) {
                }
            });
            reader.setDaemon(true);
            reader.start();

            if (!proc.waitFor(TIMEOUT_S, TimeUnit.SECONDS)) {
                proc.destroyForcibly();
                return new ScanResult(1, out + "\n扫描超时（>" + TIMEOUT_S + "s），已终止", "");
            }
            reader.join(2000);
            return new ScanResult(proc.exitValue(), out.toString(), readMainReport(outDir));
        } catch (Exception ex) {
            log.warn("db_scanner 执行失败: {}", ex.getMessage());
            return new ScanResult(1, "db_scanner 执行失败：" + ex.getMessage()
                    + "（检查 Python 环境与依赖 jaydebeapi/JPype1）", "");
        } finally {
            deleteQuietly(envFile);
            deleteDirQuietly(outDir);
        }
    }

    /** 把程序内管理的全部连接写成临时 .env（DB_<NAME>_* + DB_PROFILE），只存活于本次调用 */
    private Path writeEnvFile(String defaultProfile) throws Exception {
        StringBuilder sb = new StringBuilder();
        if (defaultProfile != null && !defaultProfile.isBlank()) {
            sb.append("DB_PROFILE=").append(defaultProfile).append('\n');
        }
        for (DbProfile p : store.list()) {
            String key = "DB_" + p.name().toUpperCase();
            sb.append(key).append("_TYPE=").append(p.type()).append('\n');
            sb.append(key).append("_HOST=").append(p.host()).append('\n');
            sb.append(key).append("_PORT=").append(p.port()).append('\n');
            sb.append(key).append("_NAME=").append(p.databases() == null ? "" : p.databases()).append('\n');
            sb.append(key).append("_USER=").append(p.username() == null ? "" : p.username()).append('\n');
            sb.append(key).append("_PASS=").append(p.password() == null ? "" : p.password()).append('\n');
            if (p.schemaName() != null && !p.schemaName().isBlank()) {
                sb.append(key).append("_SCHEMA=").append(p.schemaName()).append('\n');
            }
        }
        Path f = Files.createTempFile("af-db-env", ".env");
        Files.writeString(f, sb.toString(), StandardCharsets.UTF_8);
        return f;
    }

    /** 取输出目录中最大的 .md 报告（scan/analyze/compare/domain 模式的主交付物） */
    private String readMainReport(Path outDir) {
        try (Stream<Path> files = Files.list(outDir)) {
            Path report = files.filter(f -> f.toString().endsWith(".md"))
                    .max(Comparator.comparingLong(f -> f.toFile().length()))
                    .orElse(null);
            if (report == null) {
                return "";
            }
            String text = Files.readString(report, StandardCharsets.UTF_8);
            return text.length() > MAX_REPORT_CHARS ? text.substring(0, MAX_REPORT_CHARS) + "\n…（报告过长已截断）" : text;
        } catch (Exception ex) {
            log.warn("读取扫描报告失败：{}", ex.getMessage());
            return "";
        }
    }

    private static void deleteQuietly(Path f) {
        if (f != null) {
            try {
                Files.deleteIfExists(f);
            } catch (Exception ignored) {
            }
        }
    }

    private static void deleteDirQuietly(Path dir) {
        if (dir == null) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (Exception ignored) {
                }
            });
        } catch (Exception ignored) {
        }
    }
}
