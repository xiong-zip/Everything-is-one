package com.agentflow.tool;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 工具注册表：Spring 自动收集所有 Tool Bean（新工具加 @Component 即生效），
 * 并支持运行期注册/注销动态工具（如 OpenAPI 导入生成的工具）。
 *
 * <p>注入 LLM 的工具清单是预先生成好的：{@code description()}/{@code argsHint()} 允许实现查库或
 * 做其它 IO（如知识库、数据库连接工具），而这份清单每轮规划与 ReAct 决策都要读一次，
 * 若每次都在注册表的锁内现问一遍，等于把「所有在飞任务」串行化在一把锁上并附带一次查库。
 */
@Component
public class ToolRegistry {

    private final Map<String, Tool> tools = new LinkedHashMap<>();
    /**
     * 停用判定：只影响规划提示词，执行入口仍然可用（历史计划不该因中途停用而失败）。
     * 依赖判定函数而不是具体存储，纯单元测试就不必为了构造注册表去建一个 SQLite 库。
     */
    private final Predicate<String> disabledCheck;
    /** 工具清单文本：只在注册表变更或停用状态变化时重建，读路径无锁无 IO */
    private volatile String promptBlock = "";
    /** 并发注册时用版本号判定，避免旧快照的构建结果后到、覆盖掉新快照 */
    private final AtomicLong version = new AtomicLong();

    @Autowired
    public ToolRegistry(List<Tool> springTools, ToolDisableStore disableStore) {
        this(springTools, disableStore::isDisabled);
    }

    /** 无停用存储的场景（测试）：全部视为启用 */
    public ToolRegistry(List<Tool> springTools) {
        this(springTools, name -> false);
    }

    private ToolRegistry(List<Tool> springTools, Predicate<String> disabledCheck) {
        this.disabledCheck = disabledCheck;
        for (Tool t : springTools) {
            tools.put(t.name(), t);
        }
        refreshPromptBlock();
    }

    /** 运行期注册；重名覆盖（动态工具更新时复用） */
    public void register(Tool tool) {
        synchronized (this) {
            tools.put(tool.name(), tool);
        }
        refreshPromptBlock();
    }

    public void unregister(String name) {
        synchronized (this) {
            tools.remove(name);
        }
        refreshPromptBlock();
    }

    public synchronized Tool get(String name) {
        return tools.get(name);
    }

    /** 按注册顺序返回工具，供规划 prompt、文档与工具管理页使用 */
    public synchronized List<Tool> all() {
        return new ArrayList<>(tools.values());
    }

    /** 生成注入 LLM 规划 prompt 的工具清单（已排除停用工具） */
    public String describeForPrompt() {
        return promptBlock;
    }

    /** 停用/启用后由调用方触发重建提示词清单 */
    public void refreshPrompt() {
        refreshPromptBlock();
    }

    public boolean isDisabled(String name) {
        return disabledCheck.test(name);
    }

    /** 锁外构建：description()/argsHint() 可能查库或走网络，持锁构建会拖住所有任务 */
    private void refreshPromptBlock() {
        List<Tool> snapshot;
        synchronized (this) {
            snapshot = new ArrayList<>(tools.values());
        }
        long v = version.incrementAndGet();
        StringBuilder sb = new StringBuilder();
        for (Tool t : snapshot) {
            if (disabledCheck.test(t.name())) {
                continue;
            }
            sb.append("- ").append(t.name()).append("（").append(t.description()).append("）参数 ")
                    .append(t.argsHint()).append("\n");
        }
        if (version.get() == v) {
            promptBlock = sb.toString();
        }
    }

    /** 结构化参数执行；args 为空时由工具自行从指令抽取 */
    public ToolResult execute(String name, Map<String, Object> args, String userCommand) {
        Tool tool = get(name);
        if (tool == null) {
            return null;
        }
        return tool.execute(args == null ? Map.of() : args, userCommand);
    }
}
