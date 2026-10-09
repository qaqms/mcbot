package com.neko.mcbot.agentcore.llm;

import com.neko.mcbot.agentcore.loop.AgentLoop;
import com.neko.mcbot.agentcore.loop.ToolExecutor.ToolOutcome;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

class CallbackChatEngineTest {
    private static final AssistantTurn TEXT = new AssistantTurn("done", List.of(), 0, 0, -1, "stop");

    @Test
    void responseAndFailureAreDeliveredOnlyOnTheProvidedQueue() {
        var queue = new ArrayDeque<Runnable>();
        var source = new CompletableFuture<AssistantTurn>();
        var engine = new CallbackChatEngine((s, h, t) -> source, queue::add);
        var result = engine.chat("SYS", List.of(), List.of());
        source.complete(TEXT);
        assertFalse(result.isDone());
        queue.remove().run();
        assertEquals(TEXT, result.join());

        var failed = new CallbackChatEngine((s, h, t) -> CompletableFuture.failedFuture(
                new IllegalStateException("offline")), queue::add).chat("SYS", List.of(), List.of());
        assertFalse(failed.isDone());
        queue.remove().run();
        assertTrue(failed.isCompletedExceptionally());
    }

    @Test
    void streamCallbacksRemainOrderedBeforeTheFullTurn() {
        var queue = new ArrayDeque<Runnable>();
        var order = new ArrayList<String>();
        var source = new CompletableFuture<AssistantTurn>();
        var sinks = new ArrayList<TurnSink>();
        ChatEngine delegate = new ChatEngine() {
            @Override public CompletableFuture<AssistantTurn> chat(String s, List<Msg> h, List<ToolSpec> t) {
                return source;
            }
            @Override public CompletableFuture<AssistantTurn> chat(String s, List<Msg> h, List<ToolSpec> t,
                                                                     TurnSink sink, boolean accumulate) {
                sinks.add(sink);
                return source;
            }
        };
        var engine = new CallbackChatEngine(delegate, queue::add);
        var result = engine.chat("SYS", List.of(), List.of(), new TurnSink() {
            @Override public void onToolCallReady(int index, ToolCall call) { order.add("tool"); }
            @Override public void onComplete(AssistantTurn turn, Throwable error) { order.add("complete"); }
        }, true);
        result.thenRun(() -> order.add("turn"));
        sinks.getFirst().onToolCallReady(0, new ToolCall("c1", "status", "{}"));
        sinks.getFirst().onComplete(TEXT, null);
        source.complete(TEXT);
        assertTrue(order.isEmpty());
        while (!queue.isEmpty()) queue.remove().run();
        assertEquals(List.of("tool", "complete", "turn"), order);
    }

    @Test
    void alreadyQueuedEarlyDispatchIsInertAfterCancellation() {
        var queue = new ArrayDeque<Runnable>();
        var source = new CompletableFuture<AssistantTurn>();
        var sinks = new ArrayList<TurnSink>();
        ChatEngine delegate = new ChatEngine() {
            @Override public CompletableFuture<AssistantTurn> chat(String s, List<Msg> h, List<ToolSpec> t) {
                return source;
            }
            @Override public CompletableFuture<AssistantTurn> chat(String s, List<Msg> h, List<ToolSpec> t,
                                                                     TurnSink sink, boolean accumulate) {
                sinks.add(sink);
                return source;
            }
        };
        var executed = new ArrayList<String>();
        var loop = new AgentLoop(new CallbackChatEngine(delegate, queue::add), List.of(), (n, a) -> {
            executed.add(n);
            return CompletableFuture.completedFuture(new ToolOutcome(true, "ok"));
        }, AgentLoop.Config.defaults(), new AgentLoop.Listener() {}, () -> "SYS", 6000);
        loop.submit(11, "work");
        sinks.getFirst().onToolCallReady(0, new ToolCall("c1", "move_to", "{}"));
        loop.cancelTask(11);
        source.complete(TEXT);
        while (!queue.isEmpty()) queue.remove().run();
        assertTrue(executed.isEmpty());
        assertEquals(0, loop.currentTaskId());
    }
}
