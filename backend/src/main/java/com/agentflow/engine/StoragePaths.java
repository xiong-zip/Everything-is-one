package com.agentflow.engine;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 存储路径解析：相对路径锚定到项目根（从当前目录向上查找 .env / .git 标志），
 * 避免 IDE（backend 目录）、脚本/jar（任意目录）等不同启动方式落到不同的库文件。
 * 绝对路径原样返回；找不到项目根时保持当前目录语义。
 */
public final class StoragePaths {

    private StoragePaths() {
    }

    public static String resolve(String path) {
        String p = path == null || path.isBlank() ? "./data/agentflow.db" : path.trim();
        Path relative = Path.of(p);
        if (relative.isAbsolute()) {
            return p;
        }
        Path dir = Path.of("").toAbsolutePath();
        for (int i = 0; i < 5 && dir != null; i++) {
            if (Files.exists(dir.resolve(".env")) || Files.exists(dir.resolve(".git"))) {
                return dir.resolve(relative).toString();
            }
            dir = dir.getParent();
        }
        return p;
    }
}
