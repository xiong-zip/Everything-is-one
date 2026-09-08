package com.agentflow.controller;

import com.agentflow.tool.Tool;
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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 工具管理：查看已注册工具、从 OpenAPI 文档导入动态工具、删除动态工具 */
@RestController
@RequestMapping("/api/tools")
public class ToolsController {

    private final ToolRegistry toolRegistry;
    private final OpenApiImporter importer;
    private final ToolStore toolStore;
    private final ToolHttpClient httpClient;

    public ToolsController(ToolRegistry toolRegistry, OpenApiImporter importer,
                           ToolStore toolStore, ToolHttpClient httpClient) {
        this.toolRegistry = toolRegistry;
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
            m.put("dynamic", t instanceof DynamicTool);
            out.add(m);
        }
        return out;
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

    /** 只允许删除动态工具；内置工具不可删 */
    @DeleteMapping("/{name}")
    public Map<String, String> delete(@PathVariable("name") String name) {
        if (toolStore.find(name) == null) {
            throw new IllegalArgumentException("内置工具不可删除：" + name);
        }
        toolStore.delete(name);
        toolRegistry.unregister(name);
        return Map.of("ok", "deleted");
    }
}
