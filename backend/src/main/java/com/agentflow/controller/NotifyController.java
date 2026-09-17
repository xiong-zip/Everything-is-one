package com.agentflow.controller;

import com.agentflow.notify.NotifyChannel;
import com.agentflow.notify.NotifyChannelStore;
import com.agentflow.notify.NotifyService;
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

/**
 * 推送通道管理：多通道 + 多选（工作台「推送通道」弹窗维护）。
 * Webhook 地址含 key，属敏感信息——列表接口一律回传脱敏值，保存时留空表示沿用原地址。
 */
@RestController
@RequestMapping("/api/notify")
public class NotifyController {

    private final NotifyChannelStore store;
    private final NotifyService notify;

    public NotifyController(NotifyChannelStore store, NotifyService notify) {
        this.store = store;
        this.notify = notify;
    }

    @GetMapping("/channels")
    public Map<String, Object> list() {
        List<Map<String, Object>> channels = new ArrayList<>();
        for (NotifyChannel ch : store.list()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", ch.name());
            m.put("type", ch.type());
            m.put("typeName", ch.typeName());
            m.put("url", ch.maskedUrl());
            m.put("createdAt", ch.createdAt());
            channels.add(m);
        }
        List<String> selected = store.selected();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("channels", channels);
        out.put("selected", selected);
        // 界面一条都没配时推送回退到 .env，界面需要据此提示
        out.put("usingEnvFallback", store.isEmpty() && notify.isConfigured());
        out.put("effectiveCount", notify.effectiveChannels().size());
        return out;
    }

    /** 新增/保存通道；url 留空表示沿用原地址（避免把脱敏值当成真地址又存回去） */
    @PostMapping("/channels")
    public Map<String, Object> save(@RequestBody Map<String, String> body) {
        if (body == null) {
            throw new IllegalArgumentException("请求体不能为空");
        }
        String name = trim(body.get("name"));
        String original = trim(body.get("originalName"));
        String url = trim(body.get("url"));
        String type = trim(body.get("type"));

        if (name.isEmpty()) {
            throw new IllegalArgumentException("通道名不能为空");
        }
        if (name.length() > 32) {
            throw new IllegalArgumentException("通道名最长 32 字");
        }
        String from = original.isEmpty() ? name : original;
        NotifyChannel existing = store.find(from);
        if (!from.equals(name) && store.find(name) != null) {
            throw new IllegalArgumentException("通道名已存在：" + name);
        }
        if (url.isEmpty()) {
            if (existing == null) {
                throw new IllegalArgumentException("Webhook 地址不能为空");
            }
            url = existing.url();
        }
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            throw new IllegalArgumentException("Webhook 地址需以 http:// 或 https:// 开头");
        }
        if (type.isEmpty()) {
            type = NotifyChannel.detectType(url);
        }
        if (!NotifyChannel.WECOM.equals(type) && !NotifyChannel.DINGTALK.equals(type)) {
            throw new IllegalArgumentException("通道类型仅支持 wecom / dingtalk");
        }

        boolean firstChannel = store.isEmpty();
        NotifyChannel channel = new NotifyChannel(name, type, url, existing == null ? null : existing.createdAt());
        if (!store.saveRenamed(channel, from)) {
            throw new IllegalArgumentException("保存失败，请重试");
        }
        // 第一条通道自动勾选：否则「新增完成但没勾选」会让人以为推送坏了
        if (firstChannel) {
            store.setSelected(List.of(name));
        }
        return Map.of("ok", "saved");
    }

    @DeleteMapping("/channels/{name}")
    public Map<String, Object> delete(@PathVariable("name") String name) {
        if (!store.delete(name)) {
            throw new IllegalArgumentException("通道不存在：" + name);
        }
        return Map.of("ok", "deleted", "selected", store.selected());
    }

    /** 多选：整体提交选中的通道名集合 */
    @PutMapping("/selected")
    public Map<String, Object> setSelected(@RequestBody Map<String, Object> body) {
        Object raw = body == null ? null : body.get("names");
        List<String> names = new ArrayList<>();
        if (raw instanceof List<?> list) {
            for (Object o : list) {
                names.add(o == null ? "" : String.valueOf(o));
            }
        }
        for (String n : names) {
            if (!n.isBlank() && store.find(n.trim()) == null) {
                throw new IllegalArgumentException("通道不存在：" + n);
            }
        }
        store.setSelected(names);
        return Map.of("ok", true, "selected", store.selected());
    }

    /** 单通道测试推送：立刻发一条到该通道，用于验证地址是否有效 */
    @PostMapping("/channels/{name}/test")
    public Map<String, Object> test(@PathVariable("name") String name) {
        NotifyChannel channel = store.find(name);
        if (channel == null) {
            throw new IllegalArgumentException("通道不存在：" + name);
        }
        boolean ok = notify.test(channel);
        return Map.of("ok", ok, "message", ok
                ? "测试消息已发送，请到群里确认"
                : "推送失败：地址无效、key 被停用或网络不通（详见服务端日志）");
    }

    private static String trim(String s) {
        return s == null ? "" : s.trim();
    }
}
