package com.neko.mcbot.agentcore.loop;

import com.neko.mcbot.agentcore.llm.*;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import static org.junit.jupiter.api.Assertions.*;

class AgentLoopObservationTest {
    private static final class Harness implements ToolExecutor, ChatEngine, AgentLoop.Listener {
        final List<List<Msg>> histories = new ArrayList<>();
        final List<CompletableFuture<AssistantTurn>> responses = new ArrayList<>();
        final List<CompletableFuture<ToolOutcome>> snapshots = new ArrayList<>();
        final List<AgentLoop.TaskStatus> statuses = new ArrayList<>();
        final AgentLoop loop = new AgentLoop(this, List.of(), this, AgentLoop.Config.defaults(), this, () -> "fixed", 100000);
        @Override public CompletableFuture<ToolOutcome> observe(long taskId) {
            var snapshot = new CompletableFuture<ToolOutcome>();
            snapshots.add(snapshot);
            return snapshot;
        }
        @Override public CompletableFuture<ToolOutcome> execute(String name, String args) {
            return CompletableFuture.completedFuture(new ToolOutcome(true, "actual result"));
        }
        @Override public CompletableFuture<AssistantTurn> chat(String system, List<Msg> history, List<ToolSpec> tools) {
            assertEquals("fixed", system);
            histories.add(history);
            var response = new CompletableFuture<AssistantTurn>();
            responses.add(response);
            return response;
        }
        @Override public void onTaskFinished(long id, AgentLoop.TaskStatus status, String text) { statuses.add(status); }
    }

    @Test void waitsForFreshSnapshotEachTurnAndNeverAccumulatesItInHistory() {
        var h = new Harness();
        h.loop.submit(1, "make something");
        assertTrue(h.responses.isEmpty());
        h.snapshots.getFirst().complete(new ToolExecutor.ToolOutcome(true, "position A inventory A"));
        assertEquals(1, h.responses.size());
        h.responses.getFirst().complete(new AssistantTurn("", List.of(new ToolCall("a", "craft", "{}")), 0, 0, -1, "tools"));
        assertEquals(2, h.snapshots.size());
        assertEquals(1, h.responses.size());
        h.snapshots.get(1).complete(new ToolExecutor.ToolOutcome(true, "position B inventory B"));
        var history = h.histories.get(1);
        assertTrue(assertInstanceOf(Msg.Nudge.class, history.getLast()).text().contains("position B"));
        assertFalse(history.stream().anyMatch(m -> m instanceof Msg.Nudge n && n.text().contains("position A")));
        assertEquals("actual result", history.stream().filter(m -> m instanceof Msg.Tool).map(m -> (Msg.Tool)m).findFirst().orElseThrow().content());
    }

    @Test void cancellationWhileRefreshingDoesNotCallModelOnLateSnapshot() {
        var h = new Harness();
        h.loop.submit(1, "one");
        assertTrue(h.loop.cancelTask(1));
        h.snapshots.getFirst().complete(new ToolExecutor.ToolOutcome(true, "late body"));
        assertTrue(h.responses.isEmpty());
        assertEquals(List.of(AgentLoop.TaskStatus.CANCELLED), h.statuses);
    }

    @Test void oldSnapshotCannotCrossIntoNewTask() {
        var h = new Harness();
        h.loop.submit(1, "one");
        h.loop.cancelTask(1);
        h.loop.submit(2, "two");
        h.snapshots.getFirst().complete(new ToolExecutor.ToolOutcome(true, "old"));
        assertTrue(h.responses.isEmpty());
        h.snapshots.get(1).complete(new ToolExecutor.ToolOutcome(true, "new"));
        assertTrue(assertInstanceOf(Msg.Nudge.class, h.histories.getFirst().getLast()).text().contains("task_id=2"));
    }

    @Test void invalidFailedAndOversizeSnapshotsFailClosed() {
        for (var outcome : List.of(new ToolExecutor.ToolOutcome(false, "unavailable"),
                ToolExecutor.ToolOutcome.accepted("j1", "not a snapshot"),
                new ToolExecutor.ToolOutcome(true, "界".repeat(6000)))) {
            var h = new Harness();
            h.loop.submit(1, "one");
            h.snapshots.getFirst().complete(outcome);
            assertTrue(h.responses.isEmpty());
            assertEquals(List.of(AgentLoop.TaskStatus.FAILED), h.statuses);
        }
    }

    @Test void observationExceptionDoesNotLeakMessageOrRetry() {
        var h = new Harness();
        h.loop.submit(1, "one");
        h.snapshots.getFirst().completeExceptionally(new IllegalStateException("private test diagnostic"));
        assertEquals(1, h.snapshots.size());
        assertTrue(h.responses.isEmpty());
        assertEquals(List.of(AgentLoop.TaskStatus.FAILED), h.statuses);
    }
}
