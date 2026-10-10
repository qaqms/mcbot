package com.neko.mcbot.agentcore.loop;

import com.neko.mcbot.agentcore.llm.AssistantTurn;
import com.neko.mcbot.agentcore.llm.ChatEngine;
import com.neko.mcbot.agentcore.llm.Msg;
import com.neko.mcbot.agentcore.llm.ToolCall;
import com.neko.mcbot.agentcore.llm.ToolSpec;
import com.neko.mcbot.agentcore.llm.TurnSink;
import com.neko.mcbot.agentcore.loop.ToolExecutor.ToolOutcome;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

class AgentLoopLifecycleTest {
    @Test
    void laterIndexCannotStartBeforeTheFirstToolEvenIfItsStreamSignalArrivesFirst() {
        var engine = new Engine();
        var rec = new Recorder();
        var executed = new ArrayList<String>();
        var first = new CompletableFuture<ToolOutcome>();
        var loop = loop(engine, rec, (name, args) -> {
            executed.add(name);
            return name.equals("move_to") ? first
                    : CompletableFuture.completedFuture(new ToolOutcome(true, "ok"));
        });
        loop.submit(11, "A");
        ToolCall later = new ToolCall("c2", "dig", "{}");
        engine.sinks.getFirst().onToolCallReady(1, later);
        assertTrue(executed.isEmpty());
        engine.sinks.getFirst().onToolCallReady(0, tool().toolCalls().getFirst());
        engine.responses.getFirst().complete(new AssistantTurn("", List.of(
                tool().toolCalls().getFirst(), later), 0, 0, -1, "tool_calls"));
        assertEquals(List.of("move_to"), executed);
        first.complete(new ToolOutcome(true, "arrived"));
        assertEquals(List.of("move_to", "dig"), executed);
    }

    @Test
    void closedLoopCannotStartQueuedTaskAfterLateCompaction() {
        var engine = new Engine();
        var rec = new Recorder();
        var loop = new AgentLoop(engine, List.of(), (n, a) -> fail("no tools"),
                AgentLoop.Config.defaults(), rec, () -> "SYS", 10);
        for (int i = 0; i < 10; i++) loop.conversation().add(new Msg.User("x".repeat(4000)));
        loop.submit(11, "A");
        loop.submit(22, "B");
        engine.responses.getFirst().complete(text("A done"));
        assertEquals(2, engine.responses.size(), "second call is the history summary");
        loop.close();
        engine.responses.get(1).complete(text("summary"));
        assertEquals(2, engine.responses.size());
        assertEquals(List.of(11L), rec.started);
        assertTrue(rec.finished.contains(new Finished(22, AgentLoop.TaskStatus.CANCELLED)));
    }

    private record Finished(long id, AgentLoop.TaskStatus status) { }

    private static final class Engine implements ChatEngine {
        final List<CompletableFuture<AssistantTurn>> responses = new ArrayList<>();
        final List<TurnSink> sinks = new ArrayList<>();
        @Override
        public CompletableFuture<AssistantTurn> chat(String s, List<Msg> h, List<ToolSpec> t) {
            var response = new CompletableFuture<AssistantTurn>();
            responses.add(response);
            return response;
        }
        @Override
        public CompletableFuture<AssistantTurn> chat(String s, List<Msg> h, List<ToolSpec> t,
                                                     TurnSink sink, boolean accumulate) {
            sinks.add(sink);
            return chat(s, h, t);
        }
    }

    private static final class Recorder implements AgentLoop.Listener {
        final List<Long> started = new ArrayList<>();
        final List<Finished> finished = new ArrayList<>();
        final List<Integer> parkedCounts = new ArrayList<>();
        @Override public void onTaskStarted(long id) { started.add(id); }
        @Override public void onTaskFinished(long id, AgentLoop.TaskStatus status, String text) {
            finished.add(new Finished(id, status));
        }
        @Override public void onParked(boolean parked, int outstanding) {
            parkedCounts.add(parked ? outstanding : 0);
        }
    }

    private static AssistantTurn text(String text) {
        return new AssistantTurn(text, List.of(), 0, 0, -1, "stop");
    }
    private static AssistantTurn tool() {
        return new AssistantTurn("", List.of(new ToolCall("c1", "move_to", "{}")),
                0, 0, -1, "tool_calls");
    }
    private static AgentLoop loop(Engine engine, Recorder recorder, ToolExecutor executor) {
        return new AgentLoop(engine, List.of(), executor, AgentLoop.Config.defaults(),
                recorder, () -> "SYS", 1_000_000);
    }

    @Test
    void queuedTaskDoesNotChangeActiveIdentity() {
        var engine = new Engine();
        var rec = new Recorder();
        var loop = loop(engine, rec, (n, a) -> fail("no tools"));
        loop.submit(11, "A");
        loop.submit(22, "B");
        assertEquals(11, loop.currentTaskId());
        assertEquals(1, loop.queuedTasks());
        assertEquals(List.of(11L), rec.started);
        engine.responses.get(0).complete(text("A done"));
        assertEquals(List.of(new Finished(11, AgentLoop.TaskStatus.COMPLETED)), rec.finished);
        assertEquals(22, loop.currentTaskId());
        engine.responses.get(1).complete(text("B done"));
        assertEquals(List.of(11L, 22L), rec.started);
        assertEquals(0, loop.currentTaskId());
        assertEquals(new Finished(22, AgentLoop.TaskStatus.COMPLETED), rec.finished.get(1));
    }

    @Test
    void failedModelTerminatesExactlyItsTaskAndStartsTheNext() {
        var engine = new Engine();
        var rec = new Recorder();
        var loop = loop(engine, rec, (n, a) -> fail("no tools"));
        loop.submit(11, "A");
        loop.submit(22, "B");
        engine.responses.get(0).completeExceptionally(new IllegalStateException("offline"));
        assertEquals(List.of(new Finished(11, AgentLoop.TaskStatus.FAILED)), rec.finished);
        assertEquals(22, loop.currentTaskId());
    }

    @Test
    void cancelActiveIsImmediateAndLateStreamCannotAffectNextTask() {
        var engine = new Engine();
        var rec = new Recorder();
        var loop = loop(engine, rec, (n, a) -> fail("stale tools must not run"));
        loop.submit(11, "A");
        loop.submit(22, "B");
        assertTrue(loop.cancelTask(11));
        assertEquals(new Finished(11, AgentLoop.TaskStatus.CANCELLED), rec.finished.getFirst());
        assertEquals(22, loop.currentTaskId());
        engine.sinks.get(0).onToolCallReady(0, tool().toolCalls().getFirst());
        engine.responses.get(0).complete(tool());
        assertEquals(2, engine.responses.size());
        assertEquals(22, loop.currentTaskId());
        engine.responses.get(1).complete(text("B done"));
        assertEquals(2, rec.finished.size());
        assertEquals(new Finished(22, AgentLoop.TaskStatus.COMPLETED), rec.finished.get(1));
    }

    @Test
    void cancelQueuedDoesNotStopActiveAndUnknownIdDoesNothing() {
        var engine = new Engine();
        var rec = new Recorder();
        var loop = loop(engine, rec, (n, a) -> fail("no tools"));
        loop.submit(11, "A");
        loop.submit(22, "B");
        assertFalse(loop.cancelTask(99));
        assertTrue(loop.cancelTask(22));
        assertEquals(11, loop.currentTaskId());
        assertEquals(0, loop.queuedTasks());
        assertEquals(List.of(new Finished(22, AgentLoop.TaskStatus.CANCELLED)), rec.finished);
        engine.responses.get(0).complete(text("A done"));
        assertEquals(1, engine.responses.size());
    }

    @Test
    void globalCancelTerminatesActiveAndQueueOnlyOnce() {
        var engine = new Engine();
        var rec = new Recorder();
        var loop = loop(engine, rec, (n, a) -> fail("no tools"));
        loop.submit(11, "A");
        loop.submit(22, "B");
        assertTrue(loop.cancelTask(0));
        assertFalse(loop.cancelTask(0));
        engine.responses.get(0).complete(text("late"));
        assertEquals(2, rec.finished.size());
        assertTrue(rec.finished.stream().allMatch(f -> f.status() == AgentLoop.TaskStatus.CANCELLED));
        assertEquals(0, loop.currentTaskId());
        assertEquals(0, loop.queuedTasks());
    }

    @Test
    void cancelPendingToolPairsHistoryWithoutWaitingForIt() {
        var engine = new Engine();
        var rec = new Recorder();
        var receipt = new CompletableFuture<ToolOutcome>();
        var loop = loop(engine, rec, (n, a) -> receipt);
        loop.submit(11, "A");
        engine.responses.getFirst().complete(tool());
        loop.cancelTask(11);
        var history = loop.conversation().history();
        var paired = assertInstanceOf(Msg.Tool.class, history.getLast());
        assertEquals("c1", paired.callId());
        assertTrue(paired.content().startsWith("CANCELLED:"));
        assertEquals(1, rec.finished.size());
        receipt.complete(new ToolOutcome(true, "late"));
        assertEquals(history, loop.conversation().history());
        assertEquals(1, engine.responses.size());
    }

    @Test
    void parkedTaskAndItsQueueAreSupersededByNewDirective() {
        var engine = new Engine();
        var rec = new Recorder();
        var loop = loop(engine, rec, (n, a) -> CompletableFuture.completedFuture(
                ToolOutcome.accepted("j1", "ACCEPTED:started")));
        loop.submit(11, "A");
        loop.submit(22, "B");
        engine.responses.getFirst().complete(tool());
        assertTrue(loop.isParked());
        loop.submit(33, "C");
        assertEquals(33, loop.currentTaskId());
        assertTrue(rec.finished.contains(new Finished(11, AgentLoop.TaskStatus.SUPERSEDED)));
        assertTrue(rec.finished.contains(new Finished(22, AgentLoop.TaskStatus.SUPERSEDED)));
        loop.onJobEvent("j1", new ToolOutcome(true, "late"));
        assertEquals(2, engine.responses.size());
        assertFalse(loop.isParked());
    }

    @Test
    void closeTerminatesTasksAndMakesOldCallbacksInert() {
        var engine = new Engine();
        var rec = new Recorder();
        var loop = loop(engine, rec, (n, a) -> fail("closed tools must not run"));
        loop.submit(11, "A");
        loop.submit(22, "B");
        loop.close();
        loop.close();
        engine.sinks.getFirst().onToolCallReady(0, tool().toolCalls().getFirst());
        engine.responses.getFirst().complete(tool());
        assertEquals(2, rec.finished.size());
        assertEquals(1, engine.responses.size());
        assertThrows(IllegalStateException.class, () -> loop.submit(33, "C"));
    }

    @Test
    void earlyAcceptedJobCanFinishBeforeFullModelTurnArrives() {
        var engine = new Engine();
        var rec = new Recorder();
        var loop = loop(engine, rec, (n, a) -> CompletableFuture.completedFuture(
                ToolOutcome.accepted("j1", "ACCEPTED:started")));
        loop.submit(11, "A");
        engine.sinks.getFirst().onToolCallReady(0, tool().toolCalls().getFirst());
        loop.onJobEvent("j1", new ToolOutcome(true, "arrived"));
        engine.responses.getFirst().complete(tool());
        assertFalse(loop.isParked(), "a terminal event received during streaming must not be lost");
        assertEquals(2, engine.responses.size());
        var paired = assertInstanceOf(Msg.Tool.class, loop.conversation().history().getLast());
        assertEquals("arrived", paired.content());
        engine.responses.get(1).complete(text("done"));
        assertEquals(List.of(new Finished(11, AgentLoop.TaskStatus.COMPLETED)), rec.finished);
    }

    @Test
    void partialJobCompletionUpdatesParkCountWithoutRequestingTheModel() {
        var engine = new Engine();
        var rec = new Recorder();
        var loop = loop(engine, rec, (name, args) -> CompletableFuture.completedFuture(
                ToolOutcome.accepted("j-" + name, "ACCEPTED:started")));
        loop.submit(11, "A");
        engine.responses.getFirst().complete(new AssistantTurn("", List.of(
                new ToolCall("c1", "move_to", "{}"),
                new ToolCall("c2", "break_block", "{}")), 0, 0, -1, "tool_calls"));
        assertEquals(List.of(2), rec.parkedCounts);

        loop.onJobEvent("j-break_block", new ToolOutcome(true, "broken"));
        assertTrue(loop.isParked());
        assertEquals(List.of(2, 1), rec.parkedCounts);
        assertEquals(1, engine.responses.size());
        assertTrue(loop.conversation().history().stream().noneMatch(Msg.Tool.class::isInstance),
                "the later result must not overtake the earlier call");
        loop.onJobEvent("j-break_block", new ToolOutcome(true, "duplicate"));
        assertEquals(List.of(2, 1), rec.parkedCounts);

        loop.onJobEvent("j-move_to", new ToolOutcome(true, "arrived"));
        assertFalse(loop.isParked());
        assertEquals(List.of(2, 1, 0), rec.parkedCounts);
        assertEquals(2, engine.responses.size());
        var receipts = loop.conversation().history().stream()
                .filter(Msg.Tool.class::isInstance).map(Msg.Tool.class::cast).toList();
        assertEquals(List.of("c1", "c2"), receipts.stream().map(Msg.Tool::callId).toList());
        assertEquals(List.of("arrived", "broken"), receipts.stream().map(Msg.Tool::content).toList());
        engine.responses.get(1).complete(text("done"));
    }

    @Test
    void closeWhileParkedPairsAllCallsAndIgnoresEventsAfterSessionReplacement() {
        var engine = new Engine();
        var rec = new Recorder();
        var loop = loop(engine, rec, (name, args) -> CompletableFuture.completedFuture(
                ToolOutcome.accepted("j-" + name, "ACCEPTED:started")));
        loop.submit(11, "A");
        loop.submit(22, "B");
        engine.responses.getFirst().complete(new AssistantTurn("", List.of(
                new ToolCall("c1", "move_to", "{}"),
                new ToolCall("c2", "break_block", "{}")), 0, 0, -1, "tool_calls"));
        assertTrue(loop.isParked());

        loop.close();
        loop.close();
        assertFalse(loop.isParked());
        assertEquals(0, loop.currentTaskId());
        assertEquals(0, loop.queuedTasks());
        assertEquals(2, rec.finished.size());
        assertTrue(rec.finished.stream().allMatch(f -> f.status() == AgentLoop.TaskStatus.CANCELLED));
        var history = loop.conversation().history();
        var receipts = history.stream().filter(Msg.Tool.class::isInstance)
                .map(Msg.Tool.class::cast).toList();
        assertEquals(List.of("c1", "c2"), receipts.stream().map(Msg.Tool::callId).toList());
        assertTrue(receipts.stream().allMatch(t -> !t.ok() && t.content().startsWith("CANCELLED:")));

        var nextEngine = new Engine();
        var nextRec = new Recorder();
        var next = loop(nextEngine, nextRec, (name, args) -> fail("no tools"));
        next.submit(33, "new session");
        loop.onJobEvent("j-move_to", new ToolOutcome(true, "late arrival"));
        loop.onJobEvent("j-break_block", new ToolOutcome(true, "late break"));
        assertEquals(history, loop.conversation().history());
        assertEquals(1, engine.responses.size());
        assertEquals(33, next.currentTaskId());
        assertEquals(List.of(new Msg.User("new session")), next.conversation().history());
        nextEngine.responses.getFirst().complete(text("new done"));
        assertEquals(List.of(new Finished(33, AgentLoop.TaskStatus.COMPLETED)), nextRec.finished);
    }

    @Test
    void cancelBeforeAcceptanceCannotParkOrResumeTheFollowingTask() {
        var engine = new Engine();
        var rec = new Recorder();
        var acceptance = new CompletableFuture<ToolOutcome>();
        var loop = loop(engine, rec, (name, args) -> acceptance);
        loop.submit(11, "A");
        loop.submit(22, "B");
        engine.responses.getFirst().complete(tool());
        assertTrue(loop.cancelTask(11));
        assertEquals(22, loop.currentTaskId());
        var history = loop.conversation().history();

        acceptance.complete(ToolOutcome.accepted("old-job", "ACCEPTED:late"));
        loop.onJobEvent("old-job", new ToolOutcome(true, "late result"));
        assertEquals(history, loop.conversation().history());
        assertTrue(rec.parkedCounts.isEmpty());
        assertEquals(2, engine.responses.size());
        engine.responses.get(1).complete(text("B done"));
        assertEquals(List.of(new Finished(11, AgentLoop.TaskStatus.CANCELLED),
                new Finished(22, AgentLoop.TaskStatus.COMPLETED)), rec.finished);
    }

    @Test
    void failedOrTimedOutJobUnlocksParkOnceAndKeepsTheFailedReceipt() {
        for (String failure : List.of("INTERNAL:job failed", "TIMEOUT:job expired")) {
            var engine = new Engine();
            var rec = new Recorder();
            var executed = new ArrayList<String>();
            var loop = loop(engine, rec, (name, args) -> {
                executed.add(name);
                return CompletableFuture.completedFuture(
                        ToolOutcome.accepted("j1", "ACCEPTED:started"));
            });
            loop.submit(11, "A");
            engine.responses.getFirst().complete(tool());
            assertTrue(loop.isParked());
            loop.onJobEvent("j1", new ToolOutcome(false, failure));
            assertFalse(loop.isParked());
            assertEquals(List.of(1, 0), rec.parkedCounts);
            assertEquals(2, engine.responses.size());
            var receipt = assertInstanceOf(Msg.Tool.class, loop.conversation().history().getLast());
            assertEquals("c1", receipt.callId());
            assertFalse(receipt.ok());
            assertEquals(failure, receipt.content());

            loop.onJobEvent("j1", new ToolOutcome(true, "late success"));
            assertEquals(receipt, loop.conversation().history().getLast());
            assertEquals(2, engine.responses.size());
            assertEquals(List.of("move_to"), executed);
            engine.responses.get(1).complete(text("reported failure"));
            assertEquals(List.of(new Finished(11, AgentLoop.TaskStatus.COMPLETED)), rec.finished,
                    "a failed tool is distinct from the final task report");
        }
    }
}
