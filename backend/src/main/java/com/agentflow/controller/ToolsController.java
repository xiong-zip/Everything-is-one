package com.agentflow.controller;

import com.agentflow.tool.Tool;
import com.agentflow.tool.ToolDisableStore;
import com.agentflow.tool.ToolRegistry;
import com.agentflow.tool.dynamic.DynamicTool;
import com.agentflow.tool.dynamic.DynamicToolConfig;
import com.agentflow.tool.dynamic.OpenApiImporter;
import com.agentflow.tool.dynamic.ToolStore;
import com.agentflow.tool.ToolHttpClient;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 工具管理：查看已注册工具、停用/启用、从 OpenAPI 文档导入动态工具、编辑与删除动态工具 */
@RestController
@RequestMapping("/api/tools")
public class ToolsController {

    private final ToolRegistry toolRegistry;
    private final ToolDisableStore disableStore;
    private final OpenApiImporter importer;
    private final ToolStore toolStore;
    private final ToolHttpClient httpClient;

    public ToolsController(ToolRegistry toolRegistry, ToolDisableStore disableStore, OpenApiImporter importer,
                           ToolStore toolStore, ToolHttpClient httpClient) {
        this.toolRegistry = toolRegistry;
        this.disableStore = disableStore;
        this.importer = importer;
        this.toolStore = toolStore;
        this.httpClient = httpClient;
    }

    @GetMapping
    public List<Map<String, Object>> list() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Tool t : toolRegistry.all()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", t.name());
            m.put("description", t.description());
            m.put("argsHint", t.argsHint());
            m.put("requiresConfirm", t.requiresConfirm());
            // 内置工具编译在代码里、删不掉，但可以停用；动态工具两者都支持
            m.put("dynamic", t instanceof DynamicTool);
            m.put("disabled", disableStore.isDisabled(t.name()));
            out.add(m);
        }
        // 停用的排在后面：默认清单里先看到在用的
        out.sort((a, b) -> Boolean.compare((Boolean) a.get("disabled"), (Boolean) b.get("disabled")));
        return out;
    }

    /** 停用/启用任意工具（含内置）：停用后不进规划 prompt，执行入口保留 */
    @PutMapping("/{name}/disabled")
    public Map<String, Object> setDisabled(@PathVariable("name") String name,
                                           @RequestBody Map<String, Object> body) {
        Tool tool = toolRegistry.get(name);
        if (tool == null) {
            throw new IllegalArgumentException("工具不存在：" + name);
        }
        boolean disabled = body != null && Boolean.TRUE.equals(body.get("disabled"));
        if (!disableStore.setDisabled(name, disabled)) {
            throw new IllegalArgumentException("保存失败，请重试");
        }
        toolRegistry.refreshPrompt();
        return Map.of("ok", "saved", "name", name, "disabled", disabled);
    }

    /** 编辑动态工具的名称与描述（描述即规划 prompt 里给模型看的内容，改它能提升工具选择准确度） */
    @PutMapping("/{name}")
    public Map<String, Object> update(@PathVariable("name") String name,
                                      @RequestBody Map<String, String> body) {
        DynamicToolConfig existing = toolStore.find(name);
        if (existing == null) {
            throw new IllegalArgumentException("内置工具不可编辑：" + name);
        }
        String newName = body == null ? null : trimToNull(body.get("name"));
        String newDesc = body == null ? null : trimToNull(body.get("description"));
        String target = newName == null ? name : newName;
        if (!target.matches("[A-Za-z0-9_.\\-]{1,64}")) {
            throw new IllegalArgumentException("工具名仅限字母/数字/下划线/点/中划线，最长 64 位");
        }
        if (!target.equals(name)) {
            // 存储与注册表都要查：只查注册表的话，库里有但当前未注册的同名工具会被静默覆盖
            if (toolRegistry.get(target) != null || toolStore.find(target) != null) {
                throw new IllegalArgumentException("工具名已存在：" + target);
            }
            toolStore.delete(name);
            disableStore.setDisabled(name, false);
        }
        DynamicToolConfig updated = new DynamicToolConfig(target,
                newDesc == null ? existing.description() : newDesc,
                existing.argsHint(), existing.baseUrl(), existing.method(), existing.pathTemplate(),
                existing.pathParams(), existing.queryParams());
        toolStore.save(updated);
        if (!target.equals(name)) {
            toolRegistry.unregister(name);
        }
        toolRegistry.register(new DynamicTool(httpClient, updated));
        return Map.of("ok", "saved", "name", target);
    }

    /** 从 OpenAPI/Swagger 文档 URL 导入 GET 操作为动态工具（保存并立即注册） */
    @PostMapping("/openapi")
    public Map<String, Object> importOpenApi(@RequestBody Map<String, String> body) throws Exception {
        String url = body == null ? null : body.get("url");
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("url 不能为空");
        }
        List<DynamicToolConfig> configs = importer.importFrom(url.trim());
        int created = 0;
        List<Map<String, String>> names = new ArrayList<>();
        for (DynamicToolConfig cfg : configs) {
            if (toolStore.save(cfg)) {
                created++;
            }
            toolRegistry.register(new DynamicTool(httpClient, cfg));
            names.add(Map.of("name", cfg.name(), "description", cfg.description()));
        }
        return Map.of("imported", configs.size(), "created", created, "tools", names);
    }

    /** 只允许删除动态工具；内置工具不可删（需要的话用停用） */
    @DeleteMapping("/{name}")
    public Map<String, String> delete(@PathVariable("name") String name) {
        if (toolStore.find(name) == null) {
            throw new IllegalArgumentException("内置工具不可删除，可改为停用：" + name);
        }
        toolStore.delete(name);
        toolRegistry.unregister(name);
        disableStore.setDisabled(name, false);
        toolRegistry.refreshPrompt();
        return Map.of("ok", "deleted");
    }

    private static String trimToNull(String s) {
        if (s == null) {
            return null;
        }
        String v = s.trim();
        return v.isEmpty() ? null : v;
    }
}
