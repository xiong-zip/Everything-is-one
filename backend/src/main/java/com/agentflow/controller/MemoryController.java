package com.agentflow.controller;

import com.agentflow.engine.MemoryStore;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 长期记忆管理：跨会话的用户偏好与事实（SQLite agent_memory 表）。
 * 工作台「记忆」面板维护；对话里说「记住/忘记 XXX」也可直接增删。
 */
@RestController
@RequestMapping("/api/memory")
public class MemoryController {

    private final MemoryStore store;

    public MemoryController(MemoryStore store) {
        this.store = store;
    }

    @GetMapping
    public Map<String, Object> list() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("items", store.list());
        out.put("autoExtract", store.isAutoExtract());
        return out;
    }

    @PostMapping
    public Map<String, Object> add(@RequestBody Map<String, String> body) {
        String content = body == null ? "" : body.getOrDefault("content", "").trim();
        if (content.isEmpty()) {
            throw new IllegalArgumentException("content 不能为空");
        }
        if (!store.add(content)) {
            throw new IllegalArgumentException("保存失败：内容重复、为空或超出记忆条数上限");
        }
        return Map.of("ok", "saved");
    }

    @DeleteMapping("/{id}")
    public Map<String, String> delete(@PathVariable("id") long id) {
        if (!store.delete(id)) {
            throw new IllegalArgumentException("记忆不存在或已删除");
        }
        return Map.of("ok", "deleted");
    }

    /** 清空全部记忆（面板的「清空」按钮） */
    @DeleteMapping
    public Map<String, Object> clear() {
        return Map.of("ok", "cleared", "removed", store.clear());
    }

    /** 自动提取开关：关闭后任务收尾不再调用 LLM 提取记忆（显式「记住」指令不受影响） */
    @PutMapping("/auto")
    public Map<String, Object> setAuto(@RequestBody Map<String, Object> body) {
        boolean enabled = body != null && Boolean.parseBoolean(String.valueOf(body.getOrDefault("enabled", "true")));
        store.setAutoExtract(enabled);
        return Map.of("ok", true, "autoExtract", enabled);
    }
}
