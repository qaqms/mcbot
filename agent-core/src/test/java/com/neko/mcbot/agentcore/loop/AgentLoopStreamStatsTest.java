package com.neko.mcbot.agentcore.loop;

import com.neko.mcbot.agentcore.llm.*;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

class AgentLoopStreamStatsTest {
    private static final ToolCall A = new ToolCall("a", "status", "{}");
    private static final ToolCall B = new ToolCall("b", "scan_area", "{}");
    private static final AssistantTurn TEXT = new AssistantTurn("done", List.of(), 0, 0, -1, "stop");
    private static final AssistantTurn TOOLS = new AssistantTurn("", List.of(A, B), 0, 0, -1, "tool_calls");

    private static class ManualEngine implements ChatEngine {
        final List<TurnSink> sinks = new ArrayList<>();
        final List<CompletableFuture<AssistantTurn>> responses = new ArrayList<>();

        @Override public CompletableFuture<AssistantTurn> chat(String s, List<Msg> h, List<ToolSpec> t) {
            throw new AssertionError("expected streaming call");
        }

        @Override public CompletableFuture<AssistantTurn> chat(String s, List<Msg> h, List<ToolSpec> t,
                                                                 TurnSink sink, boolean accumulate) {
            sinks.add(sink);
            var response = new CompletableFuture<AssistantTurn>();
            responses.add(response);
            return response;
        }
    }

    private static TurnTimings.Snapshot snapshot(long origin, int ready) {
        return new TurnTimings.Snapshot(origin, 10, 2, 3, ready == 0 ? -1 : 5,
                1, ready == 0 ? -1 : 3, 4, 2, ready);
    }

    @Test
    void transportSnapshotSurvivesClientQueueAndReadyCountIsNotDispatchCount() {
        var engine = new ManualEngine();
        var queue = new ArrayDeque<Runnable>();
        var stats = new ArrayList<AgentLoop.StreamStats>();
        var executed = new ArrayList<String>();
        var waiting = new CompletableFuture<ToolExecutor.ToolOutcome>();
        var loop = new AgentLoop(new CallbackChatEngine(engine, queue::add), List.of(), (name, args) -> {
            executed.add(name);
            return waiting;
        }, AgentLoop.Config.defaults(), new AgentLoop.Listener() {
            @Override public void onStreamStats(AgentLoop.StreamStats s) { stats.add(s); }
        }, () -> "sys", 1_000_000);
        loop.submit(1, "work");
        var transport = snapshot(System.nanoTime() - 2_000_000_000L, 2);
        engine.sinks.getFirst().onToolCallReady(1, B);
        engine.sinks.getFirst().onToolCallReady(0, A);
        engine.sinks.getFirst().onToolCallReady(0, A);
        engine.sinks.getFirst().onTimings(transport);
        engine.responses.getFirst().complete(TOOLS);
        assertTrue(executed.isEmpty());
        assertTrue(stats.isEmpty());
        while (!queue.isEmpty()) queue.remove().run();
        assertEquals(List.of("status"), executed);
        assertEquals(1, stats.size());
        var s = stats.getFirst();
        assertEquals(2, s.ttfb());
        assertEquals(3, s.ttft());
        assertEquals(5, s.firstTool());
        assertEquals(1, s.afterChunk());
        assertEquals(3, s.afterTool());
        assertEquals(4, s.chunks());
        assertEquals(2, s.deltas());
        assertEquals(2, s.toolsReady());
        assertEquals(1, s.earlyDispatched());
        assertTrue(s.firstDispatch() >= 2000, "执行器排队时间不能被传输就绪时间覆盖");
        assertEquals(10, s.streamDuration());
        assertTrue(s.firstDispatch() > s.streamDuration(), "排队可能耗尽早派发的收益");
        assertTrue(s.format().contains("ready=2 early=1"));
        loop.close();
    }

    @Test
    void failedTurnReportsItsPartialStatsOnceWithoutPreviousCacheWaste() {
        var engine = new ManualEngine();
        var stats = new ArrayList<AgentLoop.StreamStats>();
        var statuses = new ArrayList<AgentLoop.TaskStatus>();
        var loop = new AgentLoop(engine, List.of(),
                (n, a) -> CompletableFuture.completedFuture(new ToolExecutor.ToolOutcome(true, "ok")),
                AgentLoop.Config.defaults(), new AgentLoop.Listener() {
                    @Override public void onStreamStats(AgentLoop.StreamStats s) { stats.add(s); }
                    @Override public void onTaskFinished(long id, AgentLoop.TaskStatus status, String text) {
                        statuses.add(status);
                    }
                }, () -> "sys", 1_000_000);
        loop.submit(1, "work");
        engine.sinks.getFirst().onTimings(snapshot(System.nanoTime(), 0));
        engine.responses.getFirst().completeExceptionally(new IllegalStateException("offline"));
        assertEquals(List.of(AgentLoop.TaskStatus.FAILED), statuses);
        assertEquals(1, stats.size());
        assertEquals(4, stats.getFirst().chunks());
        assertEquals(2, stats.getFirst().deltas());
        assertEquals(-1, stats.getFirst().firstDispatch());
        assertEquals(-1, stats.getFirst().cacheWaste());
        assertEquals(0, stats.getFirst().earlyDispatched());
    }

    @Test
    void cancelledAndClosedSessionsCannotPublishOldStatsOrDispatchOldTools() {
        for (boolean close : List.of(false, true)) {
            var engine = new ManualEngine();
            var queue = new ArrayDeque<Runnable>();
            var stats = new ArrayList<AgentLoop.StreamStats>();
            var executed = new ArrayList<String>();
            var loop = new AgentLoop(new CallbackChatEngine(engine, queue::add), List.of(), (n, a) -> {
                executed.add(n);
                return CompletableFuture.completedFuture(new ToolExecutor.ToolOutcome(true, "ok"));
            }, AgentLoop.Config.defaults(), new AgentLoop.Listener() {
                @Override public void onStreamStats(AgentLoop.StreamStats s) { stats.add(s); }
            }, () -> "sys", 1_000_000);
            loop.submit(1, "old");
            engine.sinks.getFirst().onToolCallReady(0, A);
            engine.sinks.getFirst().onTimings(snapshot(System.nanoTime(), 1));
            if (close) loop.close();
            else {
                loop.cancelTask(1);
                loop.submit(2, "new");
                engine.sinks.get(1).onTimings(snapshot(System.nanoTime(), 0));
                engine.responses.get(1).complete(TEXT);
            }
            engine.responses.getFirst().complete(TOOLS);
            while (!queue.isEmpty()) queue.remove().run();
            assertTrue(executed.isEmpty());
            assertEquals(close ? 0 : 1, stats.size());
            if (!close) assertEquals(0, stats.getFirst().toolsReady());
        }
    }

    @Test
    void throwingStatsListenerDoesNotChangeTaskOutcome() {
        var engine = new ManualEngine();
        var statuses = new ArrayList<AgentLoop.TaskStatus>();
        var loop = new AgentLoop(engine, List.of(),
                (n, a) -> CompletableFuture.completedFuture(new ToolExecutor.ToolOutcome(true, "ok")),
                AgentLoop.Config.defaults(), new AgentLoop.Listener() {
                    @Override public void onStreamStats(AgentLoop.StreamStats s) {
                        throw new IllegalStateException("observer");
                    }
                    @Override public void onTaskFinished(long id, AgentLoop.TaskStatus status, String text) {
                        statuses.add(status);
                    }
                }, () -> "sys", 1_000_000);
        loop.submit(1, "work");
        engine.sinks.getFirst().onTimings(snapshot(System.nanoTime(), 0));
        engine.responses.getFirst().complete(TEXT);
        assertEquals(List.of(AgentLoop.TaskStatus.COMPLETED), statuses);
    }
}
