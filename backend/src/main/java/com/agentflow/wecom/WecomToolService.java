package com.agentflow.wecom;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 企微能力发现：解析 wecom-cli 的 --help 输出，把「服务 → 方法（含资源型两段式）」
 * 挖出来落库。刷新由用户在面板显式触发（要跑几十个子进程），启动零子进程。
 */
@Service
public class WecomToolService {

    private static final Logger log = LoggerFactory.getLogger(WecomToolService.class);

    /** help 输出里 Commands: 区段中的命令行：两空格缩进的「名字 + 至少两空格 + 说明」 */
    private static final Pattern COMMAND_LINE = Pattern.compile("^\\s{2,}([a-z][a-z0-9-]*)\\s{2,}(.+)$");

    private final WecomCliRunner cli;
    private final WecomStore store;

    public WecomToolService(WecomCliRunner cli, WecomStore store) {
        this.cli = cli;
        this.store = store;
    }

    /** 刷新能力清单；返回发现的条数。失败原因落库（面板可见），不清空已有清单 */
    public Map<String, Object> refresh() {
        WecomCliRunner.CliResult top = cli.exec(List.of("--help"));
        if (top.cliMissing()) {
            store.recordRefresh("wecom-cli 未安装");
            return Map.of("discovered", 0, "error", "wecom-cli 未安装（npm install -g @wecom/cli）");
        }
        if (top.timeout()) {
            store.recordRefresh("wecom-cli --help 超时");
            return Map.of("discovered", 0, "error", "wecom-cli 响应超时");
        }
        List<WecomStore.Capability> caps = new ArrayList<>();
        for (String[] cmd : parseCommands(top.output())) {
            if ("help".equals(cmd[0]) || "auth".equals(cmd[0])) {
                continue;
            }
            WecomCliRunner.CliResult svc = cli.exec(List.of(cmd[0], "--help"));
            if (!svc.ok()) {
                continue;
            }
            for (String[] sub : parseCommands(svc.output())) {
                if ("help".equals(sub[0])) {
                    continue;
                }
                if (isResourceCommand(sub[1])) {
                    expandResource(cmd[0], sub[0], caps);
                } else {
                    caps.add(new WecomStore.Capability(cmd[0], sub[0], sub[1]));
                }
            }
        }
        if (caps.isEmpty()) {
            store.recordRefresh("未能从 wecom-cli 帮助输出解析到任何命令");
            return Map.of("discovered", 0, "error", "未能解析到任何命令（wecom-cli 版本可能不兼容）");
        }
        store.replaceCapabilities(caps);
        store.recordRefresh(null);
        store.putState("cliVersion", cli.version());
        long services = caps.stream().map(WecomStore.Capability::service).distinct().count();
        log.info("企微能力刷新完成：{} 个服务 {} 个方法", services, caps.size());
        return Map.of("discovered", caps.size(), "services", services);
    }

    /** 资源型命令（如 doc contents）再下钻一层，展开成 contents.get / contents.append 等 */
    private void expandResource(String service, String resource, List<WecomStore.Capability> caps) {
        WecomCliRunner.CliResult res = cli.exec(List.of(service, resource, "--help"));
        if (!res.ok()) {
            return;
        }
        for (String[] m : parseCommands(res.output())) {
            if (!"help".equals(m[0])) {
                caps.add(new WecomStore.Capability(service, resource + "." + m[0], m[1]));
            }
        }
    }

    /** 解析 help 输出的 Commands: 区段 → [name, description]（纯函数，单测覆盖） */
    static List<String[]> parseCommands(String helpText) {
        List<String[]> out = new ArrayList<>();
        if (helpText == null || helpText.isBlank()) {
            return out;
        }
        boolean inSection = false;
        for (String line : helpText.split("\n")) {
            if (line.strip().equals("Commands:")) {
                inSection = true;
                continue;
            }
            if (!inSection) {
                continue;
            }
            String stripped = line.strip();
            if (stripped.isEmpty()) {
                continue;
            }
            // 区段结束：撞上不再缩进的标题行（Options: / 文档: 等）
            if (!Character.isWhitespace(line.charAt(0))) {
                break;
            }
            Matcher m = COMMAND_LINE.matcher(line);
            if (m.matches()) {
                out.add(new String[]{m.group(1), m.group(2).strip()});
            }
        }
        return out;
    }

    /** 资源型子命令的说明形如「管理 'contents' 资源」 */
    static boolean isResourceCommand(String description) {
        return description != null && description.startsWith("管理 ") && description.contains("资源");
    }
}
