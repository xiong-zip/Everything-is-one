package com.agentflow.wecom;

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
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * wecom-cli 进程执行器：所有企微操作都经由本机安装的 wecom-cli 完成，
 * 认证（扫码）与凭据完全留在 CLI 自己的加密存储里，服务端不接触任何 Secret。
 *
 * <p>超时与输出上限沿用 DbScannerRunner 的纪律：waitFor 超时后 destroyForcibly，
 * 守护线程截断输出，避免一份大文档把引擎线程或内存拖垮。
 */
@Component
public class WecomCliRunner {

    private static final Logger log = LoggerFactory.getLogger(WecomCliRunner.class);
    private static final long AUTH_TIMEOUT_MS = 10_000;

    /** CLI 调用结果；cliMissing=命令不存在（未安装），timeout=超时被终止 */
    public record CliResult(int exitCode, String output, boolean cliMissing, boolean timeout) {
        public boolean ok() {
            return exitCode == 0 && !cliMissing && !timeout;
        }
    }

    private final String cliCmd;
    private final boolean enabled;
    private final long timeoutMs;
    private final int maxOutputChars;

    public WecomCliRunner(@Value("${agentflow.wecom.cli-cmd:wecom-cli}") String cliCmd,
                          @Value("${agentflow.wecom.enabled:true}") boolean enabled,
                          @Value("${agentflow.wecom.timeout-ms:60000}") long timeoutMs,
                          @Value("${agentflow.wecom.max-output-chars:60000}") int maxOutputChars) {
        this.cliCmd = cliCmd == null || cliCmd.isBlank() ? "wecom-cli" : cliCmd.trim();
        this.enabled = enabled;
        this.timeoutMs = Math.max(5000, timeoutMs);
        this.maxOutputChars = maxOutputChars;
    }

    public boolean enabled() {
        return enabled;
    }

    public String cliCmd() {
        return cliCmd;
    }

    /**
     * 授权状态。CLI 约定输出整行 authorized / unauthorized——注意 unauthorized
     * 也包含 "authorized" 子串，必须整行严格比较（且退出码并不可靠）。
     */
    public String authStatus() {
        CliResult r = exec(List.of("auth", "show", "--status"), AUTH_TIMEOUT_MS);
        if (r.cliMissing()) {
            return "cli-missing";
        }
        return authorizedStrict(r.output()) ? "authorized" : "unauthorized";
    }

    public String version() {
        CliResult r = exec(List.of("--version"), AUTH_TIMEOUT_MS);
        if (!r.ok()) {
            return "";
        }
        String first = r.output().strip();
        int nl = first.indexOf('\n');
        return nl > 0 ? first.substring(0, nl) : first;
    }

    /** 执行一次 wecom-cli 子命令（args 不含 cli 本身） */
    public CliResult exec(List<String> args) {
        return exec(args, timeoutMs, maxOutputChars);
    }

    public CliResult exec(List<String> args, long timeoutMs) {
        return exec(args, timeoutMs, maxOutputChars);
    }

    /** 带输出上限的执行：个别调用（如全表拉取做幂等比对）需要比默认更大的截断阈值 */
    public CliResult exec(List<String> args, long timeoutMs, int outputCap) {
        String resolved = resolve();
        if (resolved == null) {
            return new CliResult(127, "", true, false);
        }
        List<String> cmd = new ArrayList<>();
        cmd.add(resolved);
        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
        // Windows：Java 拼命令行时会外包引号但不转义内部双引号（实测），
        // 引号会被子进程当作定界符吃掉、参数在空格处断裂；这里按 MSVCRT 规则预转义。
        for (String a : args) {
            cmd.add(windows ? escapeWinArg(a) : a);
        }
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.environment().put("PYTHONIOENCODING", "utf-8");
            pb.redirectErrorStream(true);
            Process proc = pb.start();
            proc.getOutputStream().close();

            StringBuilder out = new StringBuilder();
            final int cap = outputCap;
            Thread reader = new Thread(() -> {
                try (BufferedReader r = new BufferedReader(
                        new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = r.readLine()) != null) {
                        if (out.length() < cap) {
                            out.append(line).append('\n');
                        }
                    }
                } catch (Exception ignored) {
                }
            });
            reader.setDaemon(true);
            reader.start();

            if (!proc.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                proc.destroyForcibly();
                return new CliResult(1, out + "\nwecom-cli 执行超时（>" + timeoutMs + "ms），已终止", false, true);
            }
            reader.join(2000);
            return new CliResult(proc.exitValue(), out.toString(), false, false);
        } catch (Exception ex) {
            String msg = ex.getMessage() == null ? "" : ex.getMessage();
            if (msg.contains("Cannot run program") || msg.contains("error=2") || msg.contains("No such file")) {
                return new CliResult(127, "", true, false);
            }
            log.warn("wecom-cli 执行失败: {}", msg);
            return new CliResult(1, "wecom-cli 执行失败：" + msg, false, false);
        }
    }

    /**
     * Windows 参数预转义（在外层 Java 自动包引号的前提下）：字面双引号写成 {@code \"}，
     * 引号前的反斜杠翻倍，串尾反斜杠也翻倍（后面跟着 Java 加的收尾引号）。
     * 实测经 Java→CreateProcess→Rust clap 整链路可还原完整 JSON 参数（纯函数，单测覆盖）。
     */
    static String escapeWinArg(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 8);
        int backslashes = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\') {
                backslashes++;
                continue;
            }
            if (c == '"') {
                sb.append("\\".repeat(backslashes * 2 + 1)).append('"');
            } else {
                if (backslashes > 0) {
                    sb.append("\\".repeat(backslashes));
                }
                sb.append(c);
            }
            backslashes = 0;
        }
        if (backslashes > 0) {
            sb.append("\\".repeat(backslashes * 2));
        }
        return sb.toString();
    }

    /* ---------- 可执行文件解析 ----------
       npm 装的 wecom-cli 在 PATH 上只是个 shell/cmd shim，Java 的 ProcessBuilder 不会解析
       .cmd/.sh，必须找到真正的平台二进制（@wecom/cli-<platform>/bin/wecom-cli.exe）。 */

    private volatile String resolvedCmd;

    /** 解析可执行文件；找不到返回 null。结果缓存（PATH 与磁盘不会中途变） */
    String resolve() {
        if (resolvedCmd != null) {
            return resolvedCmd;
        }
        String found = findExecutable(cliCmd, pathDirs());
        if (found != null) {
            resolvedCmd = found;
        }
        return found;
    }

    /** PATH 环境变量切分成目录列表（Windows 的分号与 Unix 的冒号都认） */
    static List<Path> pathDirs() {
        String path = System.getenv("PATH");
        List<Path> out = new ArrayList<>();
        if (path == null || path.isBlank()) {
            return out;
        }
        for (String dir : path.split(java.io.File.pathSeparator)) {
            String d = dir.strip();
            if (!d.isEmpty()) {
                out.add(Path.of(d));
            }
        }
        return out;
    }

    /**
     * 按「显式路径 → PATH 上的裸名（含 .exe）→ npm 全局包内的平台二进制」顺序查找。
     * 最后一条是关键：Windows 上 PATH 命中的只是 shim，真正能被 ProcessBuilder 执行的
     * 二进制在 @wecom/cli 包嵌套的 @wecom/cli-&lt;platform&gt;/bin/ 下（纯函数，单测覆盖）。
     */
    static String findExecutable(String cliCmd, List<Path> dirs) {
        String cmd = cliCmd == null || cliCmd.isBlank() ? "wecom-cli" : cliCmd.trim();
        // 显式给了路径或带扩展名：直接用（找不到就如实 cliMissing）
        if (cmd.contains("/") || cmd.contains("\\") || cmd.endsWith(".exe")) {
            return Path.of(cmd).toFile().isFile() ? cmd : null;
        }
        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
        for (Path dir : dirs) {
            Path direct = windows ? dir.resolve(cmd + ".exe") : dir.resolve(cmd);
            if (direct.toFile().isFile()) {
                return direct.toString();
            }
            // npm 全局包：node_modules/@wecom/cli/node_modules/@wecom/cli-*/bin/wecom-cli(.exe)
            Path nestedRoot = dir.resolve("node_modules").resolve("@wecom").resolve("cli")
                    .resolve("node_modules").resolve("@wecom");
            File[] pkgs = nestedRoot.toFile().listFiles(f -> f.isDirectory()
                    && f.getName().startsWith("cli-"));
            if (pkgs != null) {
                for (File pkg : pkgs) {
                    Path bin = pkg.toPath().resolve("bin")
                            .resolve(windows ? cmd + ".exe" : cmd);
                    if (bin.toFile().isFile()) {
                        return bin.toString();
                    }
                }
            }
        }
        return null;
    }

    /** 授权判定：输出里存在整行 equals("authorized") 且不存在整行 unauthorized（纯函数，单测覆盖） */
    static boolean authorizedStrict(String output) {
        if (output == null || output.isBlank()) {
            return false;
        }
        boolean hasAuthorized = false;
        for (String line : output.split("\n")) {
            String t = line.strip();
            if (t.equals("unauthorized")) {
                return false;
            }
            if (t.equals("authorized")) {
                hasAuthorized = true;
            }
        }
        return hasAuthorized;
    }
}
