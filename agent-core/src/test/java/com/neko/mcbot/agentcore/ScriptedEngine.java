package com.neko.mcbot.agentcore;

import com.neko.mcbot.agentcore.llm.AssistantTurn;
import com.neko.mcbot.agentcore.llm.ChatEngine;
import com.neko.mcbot.agentcore.llm.Msg;
import com.neko.mcbot.agentcore.llm.ToolCall;
import com.neko.mcbot.agentcore.llm.ToolSpec;
import com.neko.mcbot.agentcore.llm.TurnSink;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * 测试替身：按脚本逐轮吐 turn，脚本耗尽后重复最后一轮（用于卡死护栏测试）。
 *
 * <p>R2-A 起它覆盖带 sink 的重载：先把每个 tool_call 通过
 * {@link TurnSink#onToolCallReady} 报出去（模拟"参数写完"，早派发的触发器），
 * 再整轮 onComplete。于是 AgentLoop 的早派发路径在纯单测里也能被真实走到，
 * 而不是靠"默认实现不报就绪"绕过去。
 */
public final class ScriptedEngine implements ChatEngine {

    private final Deque<AssistantTurn> script = new ArrayDeque<>();
    private AssistantTurn last = new AssistantTurn("…", List.of(), 0, 0, -1, "stop");
    public int calls;
    /** 每轮回调之间插的等待：0 = 同步完成；>0 用于观察"工具先跑、整轮后到"。 */
    public volatile long streamingDelayMs;
    /** 只报前 N 个工具就绪（其余留到整轮落地才派发），用来测混合路径。-1 = 全报。 */
    public volatile int readyReportLimit = -1;

    public ScriptedEngine queue(AssistantTurn... turns) {
        script.addAll(List.of(turns));
        return this;
    }

    @Override
    public CompletableFuture<AssistantTurn> chat(String systemPrompt, List<Msg> convo, List<ToolSpec> tools) {
        calls++;
        AssistantTurn t = script.isEmpty() ? last : script.poll();
        last = t;
        return CompletableFuture.completedFuture(t);
    }

    @Override
    public CompletableFuture<AssistantTurn> chat(String systemPrompt, List<Msg> convo, List<ToolSpec> tools,
                                                TurnSink sink, boolean accumulate) {
        calls++;
        AssistantTurn t = script.isEmpty() ? last : script.poll();
        last = t;
        if (sink == null) {
            return CompletableFuture.completedFuture(t);
        }
        if (streamingDelayMs <= 0) {
            reportReady(t, sink);
            sink.onComplete(t, null);
            return CompletableFuture.completedFuture(t);
        }
        return CompletableFuture.supplyAsync(() -> {
            reportReady(t, sink);
            sleep(streamingDelayMs);
            sink.onComplete(t, null);
            return t;
        });
    }

    private void reportReady(AssistantTurn t, TurnSink sink) {
        int limit = readyReportLimit < 0 ? t.toolCalls().size() : readyReportLimit;
        for (int i = 0; i < t.toolCalls().size() && i < limit; i++) {
            ToolCall tc = t.toolCalls().get(i);
            sink.onToolCallReady(i, tc);
            if (streamingDelayMs > 0) {
                sleep(streamingDelayMs / 2);
            }
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
