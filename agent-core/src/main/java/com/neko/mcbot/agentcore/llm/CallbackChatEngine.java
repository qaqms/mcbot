package com.neko.mcbot.agentcore.llm;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/** 用同一串行执行队列承接流式回调、整轮结果与历史压缩；调用方提供有序 Executor。 */
public final class CallbackChatEngine implements ChatEngine {
    private final ChatEngine delegate;
    private final Executor client;

    public CallbackChatEngine(ChatEngine delegate, Executor client) {
        this.delegate = delegate;
        this.client = client;
    }

    @Override
    public CompletableFuture<AssistantTurn> chat(String system, List<Msg> history, List<ToolSpec> tools) {
        return deliver(delegate.chat(system, history, tools));
    }

    @Override
    public CompletableFuture<AssistantTurn> chat(String system, List<Msg> history, List<ToolSpec> tools,
                                                 TurnSink sink, boolean accumulate) {
        if (sink == null) return deliver(delegate.chat(system, history, tools, null, accumulate));
        var result = new CompletableFuture<AssistantTurn>();
        TurnSink queued = new TurnSink() {
            private void enqueue(Runnable action) {
                if (!result.isCancelled()) client.execute(() -> {
                    if (!result.isCancelled()) action.run();
                });
            }
            @Override public void onTextDelta(String delta) {
                enqueue(() -> sink.onTextDelta(delta));
            }
            @Override public void onToolCallReady(int index, ToolCall call) {
                enqueue(() -> sink.onToolCallReady(index, call));
            }
            @Override public void onComplete(AssistantTurn turn, Throwable error) {
                enqueue(() -> sink.onComplete(turn, error));
            }
            @Override public void onCounters(int chunks, int deltas, int ready) {
                enqueue(() -> {
                    try {
                        sink.onCounters(chunks, deltas, ready);
                    } catch (RuntimeException ignored) {
                        // Queuing must preserve the transport's observation-only contract.
                    }
                });
            }
            @Override public void onTimings(TurnTimings.Snapshot timings) {
                enqueue(() -> {
                    try {
                        sink.onTimings(timings);
                    } catch (RuntimeException ignored) {
                        // Logging failures must not interrupt delivery of the final future.
                    }
                });
            }
        };
        return deliver(delegate.chat(system, history, tools, queued, accumulate), result);
    }

    private CompletableFuture<AssistantTurn> deliver(CompletableFuture<AssistantTurn> source) {
        return deliver(source, new CompletableFuture<>());
    }

    private CompletableFuture<AssistantTurn> deliver(CompletableFuture<AssistantTurn> source,
                                                     CompletableFuture<AssistantTurn> result) {
        result.whenComplete((v, t) -> {
            if (result.isCancelled()) source.cancel(true);
        });
        source.whenComplete((turn, error) -> client.execute(() -> {
            if (error == null) result.complete(turn);
            else result.completeExceptionally(error);
        }));
        return result;
    }
}
