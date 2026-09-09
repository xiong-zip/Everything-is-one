package com.agentflow;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

@SpringBootApplication
@EnableScheduling
public class AgentflowApplication {

    public static void main(String[] args) {
        loadDotEnv();
        SpringApplication.run(AgentflowApplication.class, args);
    }

    /**
     * 启动时自动加载 .env（工作目录、上级、上上级逐级查找），IDE 直跑 / jar / 脚本启动行为一致。
     * 仅设置未定义的变量：真实环境变量优先于 .env。
     */
    private static void loadDotEnv() {
        for (String p : List.of(".env", "../.env", "../../.env")) {
            Path file = Path.of(p);
            if (!Files.isRegularFile(file)) {
                continue;
            }
            try {
                for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                    String t = line.trim();
                    if (t.isEmpty() || t.startsWith("#")) {
                        continue;
                    }
                    int eq = t.indexOf('=');
                    if (eq <= 0) {
                        continue;
                    }
                    String key = t.substring(0, eq).trim();
                    String val = t.substring(eq + 1).trim();
                    if (System.getenv(key) == null && System.getProperty(key) == null) {
                        System.setProperty(key, val);
                    }
                }
            } catch (Exception ignored) {
                // .env 读取失败不阻塞启动，仅少了可选配置
            }
        }
    }
}
