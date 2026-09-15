package com.agentflow.engine;

import com.agentflow.model.PlanStep;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 一次任务的运行会话：执行线程与 SSE 连接解耦。
 * 事件始终先落 SQLite（断线后可按 seq 补发），订阅连接断了任务照常执行；
 * cancel 由前端触发，引擎在步骤边界与流式生成回调处响应。
 */
public class RunSession {

    private final String taskId;
    private final long runId;
    private final String command;
    private final List<Map<String, String>> history;

    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private final AtomicBoolean started = new AtomicBoolean(false);
    private final AtomicBoolean finished = new AtomicBoolean(false);
    private final boolean confirmMode;

    /** 候选澄清暂停：工具未直接命中并给出候选后，剩余步骤不再执行，等待用户点选后重跑 */
    private volatile String clarifyQuestion;

    /** 当前订阅连接（0 或 1 个）；attach/detach 与事件推送共用 session 锁保证不重不漏 */
    private volatile SseEmitter emitter;

    /** 计划放行（confirm 模式）：规划完成后等待用户确认或修改计划 */
    private CompletableFuture<List<PlanStep>> confirmFuture;

    RunSession(String taskId, long runId, String command, List<Map<String, String>> history, boolean confirmMode) {
        this.taskId = taskId;
        this.runId = runId;
        this.command = command;
        this.history = history;
        this.confirmMode = confirmMode;
    }

    String taskId() {
        return taskId;
    }

    long runId() {
        return runId;
    }

    String command() {
        return command;
    }

    List<Map<String, String>> history() {
        return history;
    }

    boolean isConfirmMode() {
        return confirmMode;
    }

    SseEmitter emitter() {
        return emitter;
    }

    synchronized void attach(SseEmitter next) {
        SseEmitter old = this.emitter;
        this.emitter = next;
        if (old != null && old != next) {
            try {
                old.complete();
            } catch (Exception ignored) {
            }
        }
    }

    /** 只在当前订阅就是该连接时摘除，避免旧连接的 onCompletion 误摘新订阅 */
    synchronized void detachEmitter(SseEmitter which) {
        if (this.emitter == which) {
            this.emitter = null;
        }
    }

    boolean isCancelled() {
        return cancelled.get();
    }

    /** 工具返回候选澄清时记录问题；后续步骤与 LLM 汇总跳过，等待用户点选后重跑 */
    void pauseForClarify(String question) {
        this.clarifyQuestion = question;
    }

    boolean isClarifyPaused() {
        return clarifyQuestion != null;
    }

    String clarifyQuestion() {
        return clarifyQuestion;
    }

    void cancel() {
        cancelled.set(true);
        CompletableFuture<List<PlanStep>> f = confirmFuture;
        if (f != null) {
            f.cancel(true);
        }
    }

    boolean markStarted() {
        return started.compareAndSet(false, true);
    }

    boolean isFinished() {
        return finished.get();
    }

    void markFinished() {
        finished.set(true);
    }

    /** confirm 模式下注册放行句柄；返回前若已取消则立即失败 */
    synchronized CompletableFuture<List<PlanStep>> awaitConfirm() {
        CompletableFuture<List<PlanStep>> f = new CompletableFuture<>();
        this.confirmFuture = f;
        if (cancelled.get()) {
            f.cancel(true);
        }
        return f;
    }

    synchronized void confirm(List<PlanStep> steps) {
        CompletableFuture<List<PlanStep>> f = this.confirmFuture;
        if (f != null && !f.isDone()) {
            f.complete(steps);
        }
    }
}
