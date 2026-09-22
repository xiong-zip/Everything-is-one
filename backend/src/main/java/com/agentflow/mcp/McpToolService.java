package com.agentflow.mcp;

import com.agentflow.tool.Tool;
import com.agentflow.tool.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * MCP 工具注册与刷新：把「已落库的远端工具清单」变成 ToolRegistry 里真正可调用的工具。
 *
 * <p>启动时<b>只读缓存、不连服务端</b>（见 {@link McpServerStore} 里说明的理由），
 * 所以本服务同时承担 ApplicationRunner 的职责——它本来就是「把配置变成注册项」这件事的唯一入口。
 *
 * <p>名字冲突必须挡住：{@link ToolRegistry#register} 是<b>重名覆盖</b>语义（动态工具更新时依赖这一点），
 * 如果放任 MCP 工具用 {@code gitlab.query} 这样的名字注册进来，就会把内置工具静默替换掉，
 * 而且症状只在某个功能突然失灵时才暴露。所以服务端名不得与已有工具前缀重合，注册时也要逐个查名。
 */
@Service
public class McpToolService implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(McpToolService.class);
    /** 服务端名会作为工具名前缀，限成安全的字符集与长度 */
    private static final Pattern NAME_PATTERN = Pattern.compile("[a-z0-9][a-z0-9_-]{0,31}");
    /** 超过这个工具数的服务端会被提示可能挤占规划 prompt（不自动过滤，由用户决定） */
    private static final int MANY_TOOLS = 20;

    private final McpServerStore store;
    private final ToolRegistry registry;
    private final McpClientFactory clients;

    public McpToolService(McpServerStore store, ToolRegistry registry, McpClientFactory clients) {
        this.store = store;
        this.registry = registry;
        this.clients = clients;
    }

    @Override
    public void run(ApplicationArguments args) {
        int servers = 0;
        int tools = 0;
        for (McpServer server : store.servers()) {
            if (!server.enabled()) {
                continue;
            }
            int n = registerFromCache(server);
            if (n > 0) {
                servers++;
                tools += n;
            }
        }
        if (tools > 0) {
            log.info("已从缓存注册 {} 个 MCP 服务端的 {} 个远端工具", servers, tools);
        }
    }

    /* ---------- 注册 ---------- */

    /** 按缓存清单注册（零网络）；返回注册成功的工具数 */
    public int registerFromCache(McpServer server) {
        if (!server.enabled()) {
            return 0;
        }
        int ok = 0;
        for (McpServerStore.StoredTool tool : store.tools(server.name())) {
            String localName = McpTool.localName(server.name(), tool.name());
            Tool existing = registry.get(localName);
            if (existing != null && !(existing instanceof McpTool)) {
                log.warn("MCP 工具 {} 与已有工具重名，已跳过注册（避免覆盖）", localName);
                continue;
            }
            registry.register(new McpTool(server, tool, clients));
            ok++;
        }
        return ok;
    }

    /** 注销该服务端当前已注册的工具（按落库清单反推名字，不必记住内存状态） */
    public void unregister(McpServer server) {
        for (McpServerStore.StoredTool tool : store.tools(server.name())) {
            registry.unregister(McpTool.localName(server.name(), tool.name()));
        }
    }

    /* ---------- 增删改 ---------- */

    /**
     * 保存服务端配置。改名时连同已缓存的工具清单一起迁移，并在注册表里改名前后的工具都清掉
     * （旧名的注销、新名的注册都由下一次刷新完成）。
     */
    /**
     * 保存服务端配置。改名时连同已缓存的工具清单一起迁移，并在注册表里改名前后的工具都清掉
     * （旧名的注销、新名的注册都由下一次刷新完成）。
     *
     * @param headers 为 null 表示<b>不改请求头</b>（界面上不回显令牌值，只改地址时不能把令牌抹掉），
     *                非 null 则整体替换
     */
    public McpServer save(String name, String url, Map<String, String> headers, Boolean confirmAll,
                          Boolean enabled, String originalName) {
        String clean = normalizeName(name);
        if (url == null || !url.trim().startsWith("http")) {
            throw new IllegalArgumentException("MCP 地址必须以 http(s):// 开头");
        }
        String previous = originalName == null || originalName.isBlank() ? clean : originalName.trim();
        boolean renaming = !previous.equals(clean);
        if (store.find(clean) == null) {
            guardNameCollision(clean);
        }
        // 请求头缺省时沿用原值：优先取改名前的行，其次取同名行
        Map<String, String> effectiveHeaders = headers;
        if (effectiveHeaders == null) {
            McpServer source = renaming ? store.find(previous) : store.find(clean);
            effectiveHeaders = source == null ? Map.of() : source.headers();
        }
        // 改名前先把旧清单取出来：store.save 会把它迁到新名字下，之后就查不到了，
        // 于是旧前缀注册的工具会永远留在注册表里（表现为改名后工具出现两份）
        List<McpServerStore.StoredTool> staleTools = renaming ? store.tools(previous) : List.of();

        store.save(new McpServer(clean, url.trim(), effectiveHeaders,
                confirmAll == null || confirmAll,
                enabled == null || enabled,
                null, null, null), renaming ? previous : null);
        clients.invalidate(previous);
        clients.invalidate(clean);

        McpServer saved = store.find(clean);
        if (saved != null) {
            for (McpServerStore.StoredTool t : staleTools) {
                registry.unregister(McpTool.localName(previous, t.name()));
            }
            // 配置变了，之前按旧配置注册的工具不再可信，先全部摘掉再按新配置注册
            unregister(saved);
            if (saved.enabled()) {
                registerFromCache(saved);
            }
        }
        return saved;
    }

    public void setEnabled(String name, boolean enabled) {
        McpServer server = store.find(name);
        if (server == null) {
            throw new IllegalArgumentException("MCP 服务不存在：" + name);
        }
        store.setEnabled(name, enabled);
        McpServer updated = store.find(name);
        if (enabled) {
            unregister(updated);
            registerFromCache(updated);
        } else {
            unregister(server);
        }
    }

    public void delete(String name) {
        McpServer server = store.find(name);
        if (server == null) {
            throw new IllegalArgumentException("MCP 服务不存在：" + name);
        }
        unregister(server);
        store.delete(name);
        clients.invalidate(name);
    }

    /* ---------- 刷新 ---------- */

    /**
     * 连服务端拉最新工具清单并落库、重新注册。
     * 失败只记到 lastError（面板可见）并抛异常给调用方，不改变已注册的工具——
     * 一个暂时连不上的服务端不该让它本来能用的工具消失。
     */
    public Map<String, Object> refresh(String name) {
        McpServer server = store.find(name);
        if (server == null) {
            throw new IllegalArgumentException("MCP 服务不存在：" + name);
        }
        List<McpToolInfo> tools;
        try {
            tools = clients.forServer(server).listTools();
        } catch (Exception ex) {
            store.recordRefresh(name, ex.getMessage());
            log.warn("刷新 MCP 服务「{}」失败：{}", name, ex.getMessage());
            throw new IllegalArgumentException("连接 MCP 服务失败：" + ex.getMessage());
        }
        McpServer updated = store.find(name);
        unregister(updated);
        store.replaceTools(name, tools);
        store.recordRefresh(name, "");
        int registered = updated.enabled() ? registerFromCache(updated) : 0;
        log.info("MCP 服务「{}」刷新完成：发现 {} 个工具，注册 {}", name, tools.size(), registered);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("server", name);
        out.put("discovered", tools.size());
        out.put("registered", registered);
        if (tools.size() > MANY_TOOLS) {
            // 工具清单会整体注入每次规划 prompt，工具太多的服务端会挤占额度并淹没其它工具，
            // 这里如实提示，让用户自己决定要不要停用它，而不是替他偷偷过滤掉工具
            out.put("notice", "该服务暴露 " + tools.size() + " 个工具，会明显占用每次规划的 prompt 空间；"
                    + "若多数用不上，建议在列表里停用它，或改用暴露工具更少的服务。");
        }
        out.put("tools", tools.stream().map(t -> Map.of(
                "name", McpTool.localName(name, t.name()),
                "description", t.description() == null ? "" : t.description(),
                "argsHint", t.argsHint())).toList());
        return out;
    }

    /* ---------- 面板视图 ---------- */

    public List<Map<String, Object>> overview() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (McpServer server : store.servers()) {
            List<McpServerStore.StoredTool> tools = store.tools(server.name());
            List<Map<String, Object>> toolViews = new ArrayList<>();
            for (McpServerStore.StoredTool t : tools) {
                String localName = McpTool.localName(server.name(), t.name());
                Map<String, Object> tv = new LinkedHashMap<>();
                tv.put("name", t.name());
                tv.put("localName", localName);
                tv.put("description", t.description());
                tv.put("argsHint", McpToolInfo.renderArgsHintFromSchema(t.inputSchema(), t.requiredArgs()));
                tv.put("readOnly", t.readOnlyHint());
                tv.put("registered", registry.get(localName) != null);
                tv.put("requiresConfirm", server.requiresConfirm(t.readOnlyHint()));
                toolViews.add(tv);
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", server.name());
            m.put("url", server.url());
            m.put("headers", List.copyOf(server.headers().keySet()));
            m.put("confirmAll", server.confirmAll());
            m.put("enabled", server.enabled());
            m.put("createdAt", server.createdAt());
            m.put("lastRefreshAt", server.lastRefreshAt());
            m.put("lastError", server.lastError());
            m.put("toolCount", tools.size());
            m.put("tools", toolViews);
            out.add(m);
        }
        return out;
    }

    /* ---------- 校验 ---------- */

    static String normalizeName(String name) {
        String clean = name == null ? "" : name.trim().toLowerCase();
        if (!NAME_PATTERN.matcher(clean).matches()) {
            throw new IllegalArgumentException(
                    "服务名只能用 1-32 位小写字母、数字、下划线或短横线（它会作为工具名前缀）");
        }
        return clean;
    }

    /**
     * 服务端名会成为工具名前缀，若与已有工具前缀重合就可能覆盖内置工具。
     * 内置工具名形如 {@code gitlab.query}，所以这里按「第一段」比对。
     */
    private void guardNameCollision(String name) {
        for (Tool t : registry.all()) {
            if (t instanceof McpTool) {
                continue;
            }
            String existing = t.name();
            int dot = existing.indexOf('.');
            String prefix = dot > 0 ? existing.substring(0, dot) : existing;
            if (prefix.equals(name)) {
                throw new IllegalArgumentException("服务名「" + name + "」与内置工具 " + existing
                        + " 的前缀冲突，换一个名字（例如 " + name + "-mcp）");
            }
        }
    }
}
