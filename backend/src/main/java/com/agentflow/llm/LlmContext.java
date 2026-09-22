package com.agentflow.llm;

/**
 * 当前正在执行的任务 ID（线程内可见），供 LLM 埋点归属。
 *
 * <p>为什么用线程变量而不是把 taskId 一路传下去：{@link LlmClient} 的调用点散布在
 * 规划、ReAct 决策、步骤执行、结果汇总等多个私有方法里，逐个加参数会一路改动签名，
 * 而「这个调用属于哪个任务」是<b>整条执行链共有的环境事实</b>，不是某一层的业务参数。
 *
 * <p>约束必须守住：任务执行全程在同一个线程上（{@code AgentEngine.orchestrate} 内部同步调用），
 * 所以线程变量成立；而写入方<b>必须</b>在 finally 里 {@link #clear()}，
 * 否则线程池复用线程时会把下一次任务的花费记到上一个任务头上——这类错误不会报错，
 * 只会让账单悄悄算错。异步分支（如后台记忆提取）拿不到 taskId，记为空串，属预期行为。
 */
public final class LlmContext {

    private static final ThreadLocal<String> TASK_ID = new ThreadLocal<>();

    private LlmContext() {
    }

    public static void set(String taskId) {
        TASK_ID.set(taskId == null ? "" : taskId);
    }

    /** 当前任务 ID，无归属时返回空串（不是 null，免去调用方判空） */
    public static String taskId() {
        String v = TASK_ID.get();
        return v == null ? "" : v;
    }

    public static void clear() {
        TASK_ID.remove();
    }
}
