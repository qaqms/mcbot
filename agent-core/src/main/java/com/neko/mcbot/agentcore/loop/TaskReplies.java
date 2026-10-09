package com.neko.mcbot.agentcore.loop;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/** 桥接 ask 的应答按任务编号配对，不消费其他任务的作答。 */
public final class TaskReplies {
    private final Map<Long, CompletableFuture<String>> waiting = new ConcurrentHashMap<>();

    public CompletableFuture<String> register(long taskId) {
        var future = new CompletableFuture<String>();
        if (waiting.putIfAbsent(taskId, future) != null) {
            throw new IllegalArgumentException("duplicate task id");
        }
        future.whenComplete((v, t) -> waiting.remove(taskId, future));
        return future;
    }

    public void finish(long taskId, AgentLoop.TaskStatus status, String text) {
        var future = waiting.remove(taskId);
        if (future == null) return;
        if (status == AgentLoop.TaskStatus.COMPLETED) {
            future.complete(text);
        } else {
            future.completeExceptionally(new IllegalStateException(
                    status.name().toLowerCase(Locale.ROOT) + ": " + text));
        }
    }

    public int size() {
        return waiting.size();
    }
}
