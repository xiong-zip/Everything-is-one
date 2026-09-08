package com.agentflow.tool.dynamic;

import com.agentflow.tool.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import com.agentflow.tool.ToolHttpClient;

/** 启动时把库里的动态工具重新注册进 ToolRegistry（重启不丢） */
@Component
public class DynamicToolLoader implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DynamicToolLoader.class);

    private final ToolStore toolStore;
    private final ToolRegistry toolRegistry;
    private final ToolHttpClient httpClient;

    public DynamicToolLoader(ToolStore toolStore, ToolRegistry toolRegistry, ToolHttpClient httpClient) {
        this.toolStore = toolStore;
        this.toolRegistry = toolRegistry;
        this.httpClient = httpClient;
    }

    @Override
    public void run(ApplicationArguments args) {
        int count = 0;
        for (DynamicToolConfig cfg : toolStore.list()) {
            try {
                toolRegistry.register(new DynamicTool(httpClient, cfg));
                count++;
            } catch (Exception ex) {
                log.warn("注册动态工具 {} 失败: {}", cfg.name(), ex.getMessage());
            }
        }
        if (count > 0) {
            log.info("已从存储加载 {} 个动态工具", count);
        }
    }
}
