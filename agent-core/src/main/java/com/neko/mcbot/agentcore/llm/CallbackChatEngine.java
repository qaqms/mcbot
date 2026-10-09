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
        TurnSink queued = new TurnSink() {
            @Override public void onTextDelta(String delta) {
                client.execute(() -> sink.onTextDelta(delta));
            }
            @Override public void onToolCallReady(int index, ToolCall call) {
                client.execute(() -> sink.onToolCallReady(index, call));
            }
            @Override public void onComplete(AssistantTurn turn, Throwable error) {
                client.execute(() -> sink.onComplete(turn, error));
            }
            @Override public void onCounters(int chunks, int deltas, int ready) {
                client.execute(() -> {
                    try {
                        sink.onCounters(chunks, deltas, ready);
                    } catch (RuntimeException ignored) {
                        // Queuing must preserve the transport's observation-only contract.
                    }
                });
            }
            @Override public void onTimings(TurnTimings.Snapshot timings) {
                client.execute(() -> {
                    try {
                        sink.onTimings(timings);
                    } catch (RuntimeException ignored) {
                        // Logging failures must not interrupt delivery of the final future.
                    }
                });
            }
        };
        return deliver(delegate.chat(system, history, tools, queued, accumulate));
    }

    private CompletableFuture<AssistantTurn> deliver(CompletableFuture<AssistantTurn> source) {
        var result = new CompletableFuture<AssistantTurn>();
        source.whenComplete((turn, error) -> client.execute(() -> {
            if (error == null) result.complete(turn);
            else result.completeExceptionally(error);
        }));
        return result;
    }
}
