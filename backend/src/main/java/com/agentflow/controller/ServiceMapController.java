package com.agentflow.controller;

import com.agentflow.tool.GitLabTool;
import com.agentflow.tool.ServiceProjectStore;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 服务映射管理：SigNoz/K8s 服务名 ↔ GitLab 项目（SQLite）。
 * 工作台「服务映射」面板维护；gitlab.changes 工具按 映射表 > 服务名搜索 的顺序解析。
 */
@RestController
@RequestMapping("/api/servicemap")
public class ServiceMapController {

    private final ServiceProjectStore store;
    private final GitLabTool gitLab;

    public ServiceMapController(ServiceProjectStore store, GitLabTool gitLab) {
        this.store = store;
        this.gitLab = gitLab;
    }

    @GetMapping
    public Map<String, Object> list() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("items", store.list());
        out.put("gitlabConfigured", gitLab.isConfigured());
        return out;
    }

    /** 保存映射：projectPath 必填；projectId 缺省时按路径解析（需 GitLab 已配置） */
    @PostMapping
    public Map<String, Object> save(@RequestBody MapRequest req) {
        if (req == null || isBlank(req.service()) || isBlank(req.projectPath())) {
            throw new IllegalArgumentException("service 与 projectPath 不能为空");
        }
        long projectId = req.projectId() == null ? 0 : req.projectId();
        String path = req.projectPath().trim().replaceAll("^/+|/+$", "");
        if (projectId <= 0) {
            if (!gitLab.isConfigured()) {
                throw new IllegalArgumentException("未配置 GitLab Token，无法解析项目 ID；请先在工作台 → GitLab 账户 配置");
            }
            try {
                JsonNode p = gitLab.projectByPath(path);
                if (p == null) {
                    throw new IllegalArgumentException("GitLab 上未找到项目 " + path);
                }
                projectId = p.path("id").asLong();
            } catch (IllegalArgumentException ex) {
                throw ex;
            } catch (Exception ex) {
                throw new IllegalArgumentException("解析项目失败：" + ex.getMessage());
            }
        }
        if (!store.save(req.service().trim(), projectId, path)) {
            throw new IllegalArgumentException("保存失败，请检查后重试");
        }
        return Map.of("ok", "saved", "projectId", projectId);
    }

    @DeleteMapping("/{service}")
    public Map<String, String> delete(@PathVariable("service") String service) {
        if (!store.delete(service)) {
            throw new IllegalArgumentException("映射不存在：" + service);
        }
        return Map.of("ok", "deleted");
    }

    /** GitLab 项目搜索（供面板新增映射时选择项目） */
    @GetMapping("/search")
    public Map<String, Object> search(@RequestParam("keyword") String keyword) {
        if (isBlank(keyword)) {
            return Map.of("items", List.of());
        }
        if (!gitLab.isConfigured()) {
            throw new IllegalArgumentException("未配置 GitLab Token，无法搜索项目");
        }
        try {
            JsonNode arr = gitLab.searchProjects(keyword.trim());
            List<Map<String, Object>> items = new ArrayList<>();
            for (JsonNode p : arr) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("projectId", p.path("id").asLong());
                m.put("path", p.path("path_with_namespace").asText(p.path("name").asText("")));
                m.put("name", p.path("name").asText(""));
                items.add(m);
            }
            return Map.of("items", items);
        } catch (Exception ex) {
            throw new IllegalArgumentException("GitLab 搜索失败：" + ex.getMessage());
        }
    }

    public record MapRequest(String service, String projectPath, Long projectId) {
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
