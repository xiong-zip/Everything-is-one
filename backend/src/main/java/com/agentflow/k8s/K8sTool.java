package com.agentflow.k8s;

import com.agentflow.tool.Tool;
import com.agentflow.tool.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * K8s 运维排查工具（只读，经 Kuboard 代理通道访问）：
 * Pod/Deployment/Service 等资源列表与异常状态、Pod 日志、Warning 事件、节点状态。
 * 适合与链路分析联动：trace 定位到异常服务后，用本工具看对应 Pod 的状态、日志与事件。
 */
@Component
public class K8sTool implements Tool {

    private static final int MAX_ITEMS = 20;
    private static final int MAX_LOG_CHARS = 4000;

    private static final Map<String, String> CORE_KINDS = Map.of(
            "pods", "pods", "services", "services", "events", "events",
            "configmaps", "configmaps", "persistentvolumeclaims", "persistentvolumeclaims");
    private static final Map<String, String> APPS_KINDS = Map.of(
            "deployments", "deployments", "statefulsets", "statefulsets",
            "daemonsets", "daemonsets", "replicasets", "replicasets");
    private static final Map<String, String> BATCH_KINDS = Map.of(
            "jobs", "jobs", "cronjobs", "cronjobs");

    private final KuboardClient client;
    private final ObjectMapper mapper = new ObjectMapper();
    private final String defaultCluster;

    public K8sTool(KuboardClient client,
                   @Value("${agentflow.kuboard.cluster:dev}") String defaultCluster) {
        this.client = client;
        this.defaultCluster = defaultCluster == null || defaultCluster.isBlank() ? "dev" : defaultCluster.trim();
    }

    @Override
    public String name() {
        return "k8s.query";
    }

    @Override
    public String description() {
        String clusters = client.isConfigured() ? "集群以 clusters 查询结果为准（默认 " + defaultCluster + "）" : "默认 " + defaultCluster;
        return "K8s 运维排查（只读，经 Kuboard 代理）：" + clusters
                + "：查 Pod/Deployment/StatefulSet/Service/Job 列表与异常状态（CrashLoop/Pending/未就绪）、"
                + "看 Pod 日志、查命名空间 Warning 事件、节点状态；给出服务名即可按 Pod 名前缀反查实例";
    }

    @Override
    public String argsHint() {
        return "{\"action\": \"list|describe|logs|events|nodes|namespaces|clusters\", "
                + "\"cluster\": \"集群名（默认 " + defaultCluster + "）\", \"namespace\": \"命名空间\", "
                + "\"kind\": \"pods|deployments|statefulsets|services|jobs（list 时默认 pods）\", "
                + "\"name\": \"Pod/工作负载名（logs/describe 必填，可填服务名按前缀匹配）\", "
                + "\"filter\": \"problem（list pods 时仅看异常）\", \"labelSelector\": \"标签选择器\", "
                + "\"tail\": \"日志行数（默认 100）\", \"container\": \"容器名\", \"keyword\": \"日志关键词过滤\", "
                + "\"previous\": \"true=看上一个已崩溃容器的日志（排查重启用）\"}";
    }

    @Override
    public ToolResult execute(Map<String, Object> args, String userCommand) {
        if (!client.isConfigured()) {
            return ToolResult.note("K8s 工具未配置：请在 .env 设置 KUBOARD_URL / KUBOARD_USERNAME / KUBOARD_PASSWORD（Kuboard 面板地址与账号）后重启");
        }
        Map<String, Object> a = args == null ? Map.of() : args;
        String cluster = str(a.get("cluster"), defaultCluster);
        String action = str(a.get("action"), inferAction(userCommand));
        try {
            switch (action) {
                case "clusters": return clusters();
                case "namespaces": return namespaces(cluster);
                case "nodes": return nodes(cluster);
                case "events": return events(cluster, str(a.get("namespace"), "default"));
                case "logs": return logs(cluster, a, userCommand);
                case "describe": return describe(cluster, a, userCommand);
                case "list":
                default: return list(cluster, a, userCommand);
            }
        } catch (Exception ex) {
            return ToolResult.note("K8s 查询失败：" + ex.getMessage());
        }
    }

    /** Kuboard 上已导入的集群清单 */
    private ToolResult clusters() throws Exception {
        JsonNode root = client.listClusters();
        List<String> items = new ArrayList<>();
        for (JsonNode c : root.path("items")) {
            String name = c.path("metadata").path("name").asText("");
            String desc = c.path("metadata").path("annotations").path("description").asText("");
            String ver = c.path("status").path("kubernetesVersion").path("gitVersion").asText("");
            items.add(name + "（" + (desc.isBlank() ? "K8s " + ver : desc + "，K8s " + ver) + "）");
        }
        if (items.isEmpty()) {
            return ToolResult.note("Kuboard 上没有已导入的集群");
        }
        return new ToolResult("list", null, items, "Kuboard 管理的集群：" + items.size() + " 个");
    }

    private ToolResult namespaces(String cluster) throws Exception {
        JsonNode root = mapper.readTree(client.get(cluster, "/api/v1/namespaces"));
        List<String> items = new ArrayList<>();
        for (JsonNode ns : root.path("items")) {
            if (items.size() >= MAX_ITEMS) {
                items.add("…（共 " + root.path("items").size() + " 个，仅展示前 " + MAX_ITEMS + " 个）");
                break;
            }
            String phase = ns.path("status").path("phase").asText("");
            items.add(ns.path("metadata").path("name").asText("") + ("".equals(phase) || "Active".equals(phase) ? "" : " [" + phase + "]"));
        }
        return new ToolResult("list", null, items, cluster + " 集群命名空间：" + root.path("items").size() + " 个");
    }

    private ToolResult nodes(String cluster) throws Exception {
        JsonNode root = mapper.readTree(client.get(cluster, "/api/v1/nodes"));
        List<String> items = new ArrayList<>();
        for (JsonNode n : root.path("items")) {
            String ready = condition(n.path("status").path("conditions"), "Ready");
            String ver = n.path("status").path("nodeInfo").path("kubeletVersion").asText("");
            items.add(n.path("metadata").path("name").asText("") + "  Ready=" + ready + "  " + ver + "  " + age(n));
        }
        return new ToolResult("list", null, items, cluster + " 集群节点：" + items.size() + " 台");
    }

    private ToolResult events(String cluster, String namespace) throws Exception {
        JsonNode root = mapper.readTree(client.get(cluster,
                "/api/v1/namespaces/" + namespace + "/events?limit=200"));
        List<JsonNode> events = new ArrayList<>();
        root.path("items").forEach(events::add);
        // Warning 优先，再按最近时间排序
        events.sort(Comparator
                .comparing((JsonNode e) -> "Warning".equals(e.path("type").asText()) ? 0 : 1)
                .thenComparing(e -> e.path("lastTimestamp").asText(""), Comparator.reverseOrder()));
        List<String> items = new ArrayList<>();
        for (JsonNode e : events) {
            if (items.size() >= MAX_ITEMS) {
                items.add("…（共 " + events.size() + " 条，仅展示前 " + MAX_ITEMS + " 条）");
                break;
            }
            String obj = e.path("involvedObject").path("kind").asText("") + "/" + e.path("involvedObject").path("name").asText("");
            int count = e.path("count").asInt(1);
            items.add("[" + e.path("type").asText("?") + "] " + e.path("reason").asText("?") + "  " + obj
                    + (count > 1 ? "（x" + count + "）" : "") + "：" + truncate(e.path("message").asText(""), 150));
        }
        if (items.isEmpty()) {
            return ToolResult.note(namespace + " 命名空间近段时间没有事件记录");
        }
        long warnings = events.stream().filter(e -> "Warning".equals(e.path("type").asText())).count();
        return new ToolResult("list", null, items,
                namespace + " 事件 " + events.size() + " 条（Warning " + warnings + " 条，已排前）");
    }

    private ToolResult list(String cluster, Map<String, Object> a, String userCommand) throws Exception {
        String kind = str(a.get("kind"), "pods");
        String namespace = str(a.get("namespace"), inferNamespace(userCommand));
        String path = listPath(kind, namespace);
        if (path == null) {
            return ToolResult.note("暂不支持的资源类型 " + kind + "，可选：pods/deployments/statefulsets/daemonsets/replicasets/services/jobs/cronjobs/configmaps");
        }
        String query = "limit=100";
        String selector = str(a.get("labelSelector"), "");
        if (!selector.isBlank()) {
            query += "&labelSelector=" + selector;
        }
        JsonNode root = mapper.readTree(client.get(cluster, path + "?" + query));
        List<JsonNode> itemsList = new ArrayList<>();
        root.path("items").forEach(itemsList::add);

        boolean problemOnly = "problem".equalsIgnoreCase(str(a.get("filter"), ""));
        List<String> items = new ArrayList<>();
        for (JsonNode it : itemsList) {
            String line = summarizeItem(kind, it);
            if (line == null || (problemOnly && !line.contains("⚠"))) {
                continue;
            }
            if (items.size() >= MAX_ITEMS) {
                items.add("…（共 " + itemsList.size() + " 条，仅展示前 " + MAX_ITEMS + " 条）");
                break;
            }
            items.add(line);
        }
        if (itemsList.isEmpty()) {
            // 一条记录都没有：多半是命名空间/名称没对上 → 给相近候选反问
            ToolResult.Clarify clarify = null;
            String nameHint = str(a.get("name"), "");
            if (!nameHint.isBlank()) {
                clarify = buildResourceClarify(cluster, nameHint, "list");
            }
            if (clarify == null) {
                clarify = buildNamespaceClarify(cluster, namespace);
            }
            if (clarify != null) {
                return ToolResult.withClarify(
                        namespace + " 命名空间下没有查到 " + kind + "，已找出相近候选，请选择：", clarify);
            }
            return ToolResult.note(namespace + " 命名空间没有 " + kind + " 记录（可用 namespaces 查看全部命名空间）");
        }
        if (items.isEmpty()) {
            return ToolResult.note(problemOnly ? namespace + " 命名空间没有发现异常 Pod，全部运行正常"
                    : namespace + " 命名空间的 " + kind + " 没有匹配记录");
        }
        String summary;
        if (problemOnly) {
            summary = cluster + " / " + namespace + " 共扫描 " + itemsList.size() + " 个 " + kind + "，发现异常 " + items.size() + " 个";
        } else {
            summary = cluster + " / " + namespace + " 的 " + kind + "：共 " + itemsList.size() + " 条";
        }
        return new ToolResult("list", null, items, summary);
    }

    private ToolResult describe(String cluster, Map<String, Object> a, String userCommand) throws Exception {
        String kind = str(a.get("kind"), "pods");
        boolean isPods = "pods".equals(kind);
        String namespace = str(a.get("namespace"), inferNamespace(userCommand));
        String name = str(a.get("name"), "");
        if (name.isBlank()) {
            return ToolResult.note("describe 需要 name 参数（资源名或服务名，Pod 支持按前缀匹配）");
        }
        String path = listPath(kind, namespace);
        if (path == null) {
            return ToolResult.note("describe 暂不支持 " + kind);
        }
        JsonNode it = null;
        try {
            it = mapper.readTree(client.get(cluster, path + "/" + name));
            if (!it.has("metadata")) {
                it = null;
            }
        } catch (Exception ignore) {
            // 落到跨命名空间反查
        }
        if (it == null) {
            // 指定命名空间查不到：全集群按名字反查（Pod 支持前缀，常用于 Deployment 名反查实例）
            String[] found = resolveAcrossNamespaces(cluster, kind, name, isPods);
            if (found != null) {
                namespace = found[0];
                name = found[1];
                it = mapper.readTree(client.get(cluster, listPath(kind, namespace) + "/" + name));
            }
        }
        if (it == null || !it.has("metadata")) {
            ToolResult.Clarify clarify = buildResourceClarify(cluster, name, "describe");
            if (clarify != null) {
                return ToolResult.withClarify(
                        "没有找到 " + kind + "「" + name + "」，已按名称相似度找出候选，请选择：", clarify);
            }
            return ToolResult.note("查不到 " + kind + "/" + name + "：名称或命名空间可能不对，可先用 list 确认");
        }
        List<String> items = new ArrayList<>();
        if ("pods".equals(kind)) {
            items.addAll(podDetail(it));
            // 顺带带上该 Pod 的相关事件
            try {
                JsonNode ev = mapper.readTree(client.get(cluster,
                        "/api/v1/namespaces/" + namespace + "/events?fieldSelector=involvedObject.name%3D" + name));
                for (JsonNode e : ev.path("items")) {
                    if (items.size() < MAX_ITEMS + 8) {
                        items.add("事件 [" + e.path("type").asText("?") + "] " + e.path("reason").asText("?")
                                + "：" + truncate(e.path("message").asText(""), 150));
                    }
                }
            } catch (Exception ignore) {
                // 事件查询失败不影响主信息
            }
        } else {
            items.add(summarizeItem(kind, it));
            JsonNode spec = it.path("spec").path("template").path("spec").path("containers");
            for (JsonNode c : spec) {
                items.add("容器 " + c.path("name").asText("") + " 镜像 " + c.path("image").asText(""));
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("kind", kind);
        result.put("name", name);
        result.put("namespace", namespace);
        return new ToolResult("list", result, items, kind + "/" + name + " 详情（" + namespace + "）");
    }

    private ToolResult logs(String cluster, Map<String, Object> a, String userCommand) throws Exception {
        String namespace = str(a.get("namespace"), inferNamespace(userCommand));
        String name = str(a.get("name"), "");
        if (name.isBlank()) {
            return ToolResult.note("查日志需要 name 参数：Pod 名或服务名（服务名会按 Pod 名前缀匹配最新实例）");
        }
        int tail = parseInt(a.get("tail"), 100);
        String container = str(a.get("container"), "");
        String keyword = str(a.get("keyword"), "");
        boolean previous = "true".equalsIgnoreCase(str(a.get("previous"), ""))
                || "是".equals(str(a.get("previous"), ""));

        String pod = name;
        String podNamespace = namespace;
        String podPath = "/api/v1/namespaces/" + podNamespace + "/pods";
        // 直接按 Pod 名查；404 则按前缀匹配服务名，取最新实例；仍没有则全集群跨命名空间兜底
        try {
            JsonNode p = mapper.readTree(client.get(cluster, podPath + "/" + pod));
            if (!p.has("metadata")) {
                throw new IllegalStateException("not found");
            }
        } catch (Exception ex) {
            JsonNode list = mapper.readTree(client.get(cluster, podPath + "?limit=200"));
            JsonNode newest = null;
            for (JsonNode p : list.path("items")) {
                if (p.path("metadata").path("name").asText("").startsWith(name)
                        && (newest == null || p.path("metadata").path("creationTimestamp").asText("")
                                .compareTo(newest.path("metadata").path("creationTimestamp").asText("")) > 0)) {
                    newest = p;
                }
            }
            String foundIn = namespace;
            if (newest == null) {
                // 指定命名空间没有：全集群搜一遍（常见于指令里没写命名空间）
                String[] found = resolveAcrossNamespaces(cluster, "pods", name, true);
                if (found != null) {
                    foundIn = found[0];
                    newest = mapper.readTree(client.get(cluster,
                            "/api/v1/namespaces/" + foundIn + "/pods/" + found[1]));
                }
            }
            if (newest == null) {
                ToolResult.Clarify clarify = buildResourceClarify(cluster, name, "logs");
                if (clarify != null) {
                    return ToolResult.withClarify(
                            "全集群没有找到「" + name + "」或以其开头的 Pod，已按名称相似度找出候选，请选择：", clarify);
                }
                return ToolResult.note("全集群没有找到名称为 " + name + " 或以其开头的 Pod（服务可能已下线或改名）");
            }
            pod = newest.path("metadata").path("name").asText();
            podNamespace = foundIn;
            podPath = "/api/v1/namespaces/" + podNamespace + "/pods";
        }

        String query = "tailLines=" + tail;
        if (!container.isBlank()) {
            query += "&container=" + container;
        }
        if (previous) {
            // 上一个已退出容器的日志：重启类问题的崩溃现场在这里
            query += "&previous=true";
        }
        String body;
        try {
            body = client.get(cluster, podPath + "/" + pod + "/log?" + query);
        } catch (Exception ex) {
            if (previous) {
                return ToolResult.note(pod + " 没有上一个容器的日志（容器未曾重启或已被 GC），可去掉 previous 看当前日志");
            }
            throw ex;
        }
        if (body == null || body.isBlank()) {
            return ToolResult.note(pod + " 日志为空");
        }
        List<String> lines = new ArrayList<>();
        for (String line : body.split("\n")) {
            if (keyword.isBlank() || line.toLowerCase().contains(keyword.toLowerCase())) {
                lines.add(truncate(line, 300));
            }
        }
        if (lines.isEmpty()) {
            return ToolResult.note(pod + " 最近 " + tail + " 行日志中没有匹配「" + keyword + "」的内容");
        }
        int total = lines.size();
        if (lines.size() > 60) {
            lines = new ArrayList<>(lines.subList(lines.size() - 60, lines.size()));
            lines.add(0, "…（匹配 " + total + " 行，仅展示最后 60 行）");
        }
        String summary = pod + " 最近 " + tail + " 行日志" + (previous ? "（上一个已崩溃容器）" : "")
                + (!pod.equals(name) ? "（按服务名 " + name + " 前缀匹配，命名空间 " + podNamespace + "）" : "")
                + (!keyword.isBlank() ? "，匹配「" + keyword + "」" + total + " 行" : "");
        return new ToolResult("list", Map.of("pod", pod, "namespace", podNamespace), lines, summary);
    }

    // ---------- 摘要与路径映射 ----------

    /**
     * 资源名未命中时，全集群模糊找相近的工作负载（Pod 按工作负载基名去重 + Deployment），
     * 产出候选澄清卡。评分与 db.inspect 一致：子串命中 100，编辑距离相似度次之。
     * want 取 logs / describe / list，决定点选后重跑的指令措辞；找不到候选返回 null。
     */
    private ToolResult.Clarify buildResourceClarify(String cluster, String keyword, String want) {
        try {
            String kw = keyword.toLowerCase().replaceAll("[\\s_-]", "");
            if (kw.isEmpty()) {
                return null;
            }
            record Candidate(String name, String namespace, String kind, int score) {
            }
            List<Candidate> best = new ArrayList<>();
            java.util.Set<String> seen = new java.util.LinkedHashSet<>();
            // Pod（全集群，按工作负载基名去重）
            JsonNode pods = mapper.readTree(client.get(cluster, "/api/v1/pods?limit=500"));
            for (JsonNode p : pods.path("items")) {
                String ns = p.path("metadata").path("namespace").asText("");
                String base = workloadBase(p.path("metadata").path("name").asText(""));
                if (base.isEmpty() || !seen.add(ns + "/pods/" + base)) {
                    continue;
                }
                int score = similarity(base, kw);
                if (score >= 45) {
                    best.add(new Candidate(base, ns, "Pod", score));
                }
            }
            // Deployment（全集群）
            JsonNode deps = mapper.readTree(client.get(cluster, "/apis/apps/v1/deployments?limit=500"));
            for (JsonNode d : deps.path("items")) {
                String ns = d.path("metadata").path("namespace").asText("");
                String nm = d.path("metadata").path("name").asText("");
                if (nm.isEmpty() || !seen.add(ns + "/deployments/" + nm)) {
                    continue;
                }
                int score = similarity(nm, kw);
                if (score >= 45) {
                    best.add(new Candidate(nm, ns, "Deployment", score));
                }
            }
            if (best.isEmpty()) {
                return null;
            }
            best.sort(Comparator.comparingInt(Candidate::score).reversed());
            List<Map<String, String>> options = new ArrayList<>();
            for (Candidate c : best.subList(0, Math.min(4, best.size()))) {
                String action;
                switch (want) {
                    case "logs" -> action = "在 " + cluster + " 集群查看 " + c.namespace() + " 命名空间 " + c.name() + " 的 Pod 日志";
                    case "describe" -> action = "describe " + cluster + " 集群 " + c.namespace() + " 命名空间的 " + c.name();
                    default -> action = "查看 " + cluster + " 集群 " + c.namespace() + " 命名空间的 Pod 列表（重点看 " + c.name() + "）";
                }
                options.add(Map.of("label", c.name() + "（" + c.kind() + " · " + c.namespace() + "）", "action", action));
            }
            return new ToolResult.Clarify("没有找到与「" + keyword + "」直接匹配的资源，你想查的是不是：", options);
        } catch (Exception ex) {
            return null;
        }
    }

    /** 命名空间未命中时，给出相近的命名空间候选 */
    private ToolResult.Clarify buildNamespaceClarify(String cluster, String wrongNs) {
        try {
            JsonNode root = mapper.readTree(client.get(cluster, "/api/v1/namespaces"));
            String kw = wrongNs.toLowerCase().replaceAll("[\\s_-]", "");
            List<String> hits = new ArrayList<>();
            for (JsonNode ns : root.path("items")) {
                String name = ns.path("metadata").path("name").asText("");
                if (!name.isEmpty() && similarity(name, kw) >= 45) {
                    hits.add(name);
                    if (hits.size() >= 4) {
                        break;
                    }
                }
            }
            if (hits.isEmpty()) {
                return null;
            }
            List<Map<String, String>> options = new ArrayList<>();
            for (String h : hits) {
                options.add(Map.of("label", h, "action", "在 " + cluster + " 集群查看 " + h + " 命名空间的资源"));
            }
            return new ToolResult.Clarify("集群里没有「" + wrongNs + "」这个命名空间，你想找的是不是：", options);
        } catch (Exception ex) {
            return null;
        }
    }

    /** Pod 名还原工作负载基名：去掉 ReplicaSet 的两段哈希或 StatefulSet 的序号 */
    private static String workloadBase(String podName) {
        if (podName == null || podName.isEmpty()) {
            return "";
        }
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("^(.+)-[a-z0-9]{5,}-[a-z0-9]{4,}$").matcher(podName);
        if (m.matches()) {
            return m.group(1);
        }
        m = java.util.regex.Pattern.compile("^(.+)-\\d+$").matcher(podName);
        if (m.matches()) {
            return m.group(1);
        }
        return podName;
    }

    /** 相似度 0-100：子串命中满分，否则按编辑距离折算 */
    private static int similarity(String name, String kw) {
        String normName = name.toLowerCase().replaceAll("[\\s_-]", "");
        if (normName.contains(kw) || kw.contains(normName)) {
            return 100;
        }
        int dist = levenshtein(normName, kw);
        int maxLen = Math.max(normName.length(), kw.length());
        return maxLen == 0 ? 0 : (int) (80.0 * (maxLen - dist) / maxLen);
    }

    private static int levenshtein(String a, String b) {
        int[][] dp = new int[a.length() + 1][b.length() + 1];
        for (int i = 0; i <= a.length(); i++) {
            dp[i][0] = i;
        }
        for (int j = 0; j <= b.length(); j++) {
            dp[0][j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                dp[i][j] = Math.min(Math.min(dp[i - 1][j] + 1, dp[i][j - 1] + 1), dp[i - 1][j - 1] + cost);
            }
        }
        return dp[a.length()][b.length()];
    }

    /** 全集群（跨命名空间）列表路径，如 /api/v1/pods、/apis/apps/v1/deployments；null 表示不支持的类型 */
    private String allNamespacesPath(String kind) {
        if (CORE_KINDS.containsKey(kind)) {
            return "/api/v1/" + kind;
        }
        if (APPS_KINDS.containsKey(kind)) {
            return "/apis/apps/v1/" + kind;
        }
        if (BATCH_KINDS.containsKey(kind)) {
            return "/apis/batch/v1/" + kind;
        }
        return null;
    }

    /**
     * 指定命名空间没命中时，全集群按名字反查（Pod 支持 Deployment 名前缀匹配，取最新实例）。
     * 返回 {namespace, name}；没找到返回 null。用于「指令里没写命名空间 / 用服务名查 Pod」的兜底。
     */
    private String[] resolveAcrossNamespaces(String cluster, String kind, String name, boolean prefixMatch) throws Exception {
        String basePath = allNamespacesPath(kind);
        if (basePath == null) {
            return null;
        }
        JsonNode list = mapper.readTree(client.get(cluster, basePath + "?limit=500"));
        JsonNode best = null;
        for (JsonNode it : list.path("items")) {
            String n = it.path("metadata").path("name").asText("");
            boolean match = prefixMatch ? n.startsWith(name) : n.equals(name);
            if (match && (best == null || it.path("metadata").path("creationTimestamp").asText("")
                    .compareTo(best.path("metadata").path("creationTimestamp").asText()) > 0)) {
                best = it;
            }
        }
        if (best == null) {
            return null;
        }
        return new String[]{best.path("metadata").path("namespace").asText(), best.path("metadata").path("name").asText()};
    }

    /** 列表 API 路径：core / apps / batch 三组 API 前缀，null 表示不支持的类型 */
    private String listPath(String kind, String namespace) {
        if (CORE_KINDS.containsKey(kind)) {
            return "/api/v1/namespaces/" + namespace + "/" + kind;
        }
        if (APPS_KINDS.containsKey(kind)) {
            return "/apis/apps/v1/namespaces/" + namespace + "/" + kind;
        }
        if (BATCH_KINDS.containsKey(kind)) {
            return "/apis/batch/v1/namespaces/" + namespace + "/" + kind;
        }
        return null;
    }

    /** 单条资源的列表行摘要；Pod 会标注异常（⚠） */
    private String summarizeItem(String kind, JsonNode it) {
        String name = it.path("metadata").path("name").asText("");
        switch (kind) {
            case "pods": {
                String phase = it.path("status").path("phase").asText("Unknown");
                int restarts = 0;
                int ready = 0;
                int total = 0;
                String waiting = "";
                for (JsonNode cs : it.path("status").path("containerStatuses")) {
                    total++;
                    restarts += cs.path("restartCount").asInt(0);
                    if (cs.path("ready").asBoolean(false)) {
                        ready++;
                    }
                    String reason = cs.path("state").path("waiting").path("reason").asText("");
                    if (!reason.isBlank()) {
                        waiting = reason;
                    }
                }
                String node = it.path("spec").path("nodeName").asText("");
                boolean problem = !phase.equals("Running") || ready < total || !waiting.isBlank() || restarts >= 10;
                return (problem ? "⚠ " : "") + name + "  " + phase + "  就绪 " + ready + "/" + total
                        + "  重启 " + restarts + "次  " + age(it)
                        + (waiting.isBlank() ? "" : "  [" + waiting + "]")
                        + (node.isBlank() ? "" : "  节点 " + node);
            }
            case "deployments": {
                int ready = it.path("status").path("readyReplicas").asInt(0);
                int desired = it.path("spec").path("replicas").asInt(0);
                boolean problem = ready < desired;
                StringBuilder images = new StringBuilder();
                for (JsonNode c : it.path("spec").path("template").path("spec").path("containers")) {
                    if (images.length() > 0) {
                        images.append(", ");
                    }
                    images.append(c.path("image").asText(""));
                }
                return (problem ? "⚠ " : "") + name + "  副本 " + ready + "/" + desired + "  " + age(it)
                        + "  镜像 " + images;
            }
            case "statefulsets":
            case "daemonsets": {
                int ready = it.path("status").path("readyReplicas").asInt(it.path("status").path("numberReady").asInt(0));
                int desired = it.path("spec").path("replicas").asInt(it.path("status").path("desiredNumberScheduled").asInt(0));
                return (ready < desired ? "⚠ " : "") + name + "  副本 " + ready + "/" + desired + "  " + age(it);
            }
            case "jobs": {
                int succeeded = it.path("status").path("succeeded").asInt(0);
                boolean failed = it.path("status").path("failed").asInt(0) > 0;
                JsonNode jobCond = it.path("status").path("conditions");
                String reason = "";
                for (JsonNode c : jobCond) {
                    if ("Failed".equals(c.path("type").asText()) && "True".equals(c.path("status").asText())) {
                        reason = c.path("reason").asText("");
                    }
                }
                return (failed ? "⚠ " : "") + name + "  成功 " + succeeded + (failed ? "  失败[" + reason + "]" : "") + "  " + age(it);
            }
            default: {
                return name + "  " + age(it);
            }
        }
    }

    /** Pod 详情：节点/状态/容器清单 */
    private List<String> podDetail(JsonNode pod) {
        List<String> out = new ArrayList<>();
        out.add("节点 " + pod.path("spec").path("nodeName").asText("-")
                + "  状态 " + pod.path("status").path("phase").asText("?")
                + "  " + age(pod)
                + "  重启策略 " + pod.path("spec").path("restartPolicy").asText(""));
        for (JsonNode cs : pod.path("status").path("containerStatuses")) {
            String state = cs.path("state").has("running") ? "Running"
                    : cs.path("state").has("waiting") ? "Waiting[" + cs.path("state").path("waiting").path("reason").asText("") + "]"
                    : cs.path("state").has("terminated") ? "Terminated[" + cs.path("state").path("terminated").path("reason").asText("") + "]"
                    : "?";
            out.add("容器 " + cs.path("name").asText("") + "  " + state
                    + "  就绪 " + (cs.path("ready").asBoolean(false) ? "是" : "否")
                    + "  重启 " + cs.path("restartCount").asInt(0) + "次");
            // 上一次终止的退出码与原因：诊断重启/OOMKill 的关键证据
            JsonNode last = cs.path("lastState").path("terminated");
            if (last.isObject() && !last.isEmpty()) {
                out.add("  └ 上次终止 " + last.path("reason").asText("?")
                        + "  ExitCode=" + last.path("exitCode").asInt(-1)
                        + (last.path("finishedAt").asText("").isBlank() ? "" : "  于 " + last.path("finishedAt").asText("")));
            }
        }
        for (JsonNode c : pod.path("spec").path("containers")) {
            out.add("镜像 " + c.path("image").asText(""));
        }
        return out;
    }

    // ---------- 参数兜底抽取 ----------

    private static final Pattern NS_PATTERN = Pattern.compile("(?:命名空间|namespace|ns)[:=\\s]+([a-z0-9][-a-z0-9]*)");

    private String inferAction(String userCommand) {
        String c = userCommand == null ? "" : userCommand;
        if (c.contains("日志")) {
            return "logs";
        }
        if (c.contains("事件")) {
            return "events";
        }
        if (c.contains("节点")) {
            return "nodes";
        }
        if (c.contains("详情") || c.contains("describe")) {
            return "describe";
        }
        return "list";
    }

    private String inferNamespace(String userCommand) {
        Matcher m = NS_PATTERN.matcher(userCommand == null ? "" : userCommand);
        if (m.find()) {
            return m.group(1);
        }
        return "default";
    }

    private static String condition(JsonNode conditions, String type) {
        for (JsonNode c : conditions) {
            if (type.equals(c.path("type").asText())) {
                return c.path("status").asText("");
            }
        }
        return "?";
    }

    private static String age(JsonNode item) {
        String ts = item.path("metadata").path("creationTimestamp").asText("");
        if (ts.isBlank()) {
            return "年龄?";
        }
        try {
            Duration d = Duration.between(OffsetDateTime.parse(ts), OffsetDateTime.now());
            long days = d.toDays();
            long hours = d.toHours() % 24;
            long minutes = d.toMinutes() % 60;
            if (days > 0) {
                return days + "天" + hours + "小时前创建";
            }
            if (hours > 0) {
                return hours + "小时" + minutes + "分前创建";
            }
            return Math.max(minutes, 1) + "分钟前创建";
        } catch (Exception e) {
            return ts;
        }
    }

    private static String str(Object v, String def) {
        if (v == null) {
            return def;
        }
        String s = String.valueOf(v).trim();
        return s.isEmpty() ? def : s;
    }

    private static int parseInt(Object v, int def) {
        try {
            return Integer.parseInt(String.valueOf(v).trim());
        } catch (Exception e) {
            return def;
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
