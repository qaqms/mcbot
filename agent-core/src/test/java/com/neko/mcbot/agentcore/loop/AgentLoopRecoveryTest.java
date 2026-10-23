package com.neko.mcbot.agentcore.loop;

import com.google.gson.JsonObject;
import com.neko.mcbot.agentcore.llm.*;
import com.neko.mcbot.agentcore.loop.ToolExecutor.ToolOutcome;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

class AgentLoopRecoveryTest {
    static JsonObject state(int count) {
        var data = AgentLoopGoalTest.state(count);
        data.addProperty("x", 0); data.addProperty("y", 90); data.addProperty("z", 0);
        data.addProperty("dimension", "minecraft:overworld"); data.addProperty("alive", true);
        data.addProperty("body_id", "body-one");
        int id = 1;
        for (String field : List.of("world_id", "owner_uuid", "companion_uuid", "body_continuity_id"))
            data.addProperty(field, "10000000-0000-0000-0000-%012d".formatted(id++));
        return data;
    }
    static final class Fixture implements ChatEngine, ToolExecutor, AgentLoop.Listener {
        JsonObject state = state(5), saved;
        CompletableFuture<ToolOutcome> deferred;
        boolean failSave;
        final List<CompletableFuture<AssistantTurn>> requests = new ArrayList<>();
        final List<List<Msg>> histories = new ArrayList<>();
        final List<AgentLoop.TaskStatus> finishes = new ArrayList<>();
        final List<String> reasons = new ArrayList<>(), actions = new ArrayList<>();
        final AgentLoop loop = new AgentLoop(this, List.of(), this, AgentLoop.Config.defaults(), this,
                () -> "system", 1000000);
        @Override public CompletableFuture<AssistantTurn> chat(String s, List<Msg> h, List<ToolSpec> t) {
            histories.add(h); var result = new CompletableFuture<AssistantTurn>(); requests.add(result); return result;
        }
        @Override public CompletableFuture<ToolOutcome> observe(long task) {
            if (deferred != null) { var next = deferred; deferred = null; return next; }
            return CompletableFuture.completedFuture(new ToolOutcome(true, "fresh", state));
        }
        @Override public CompletableFuture<ToolOutcome> execute(String name, String args) {
            actions.add(name); return CompletableFuture.completedFuture(new ToolOutcome(true, "actual"));
        }
        @Override public void checkpoint(long task, JsonObject value) {
            if (failSave) throw new IllegalStateException();
            saved = value.deepCopy();
        }
        @Override public void onTaskFinished(long id, AgentLoop.TaskStatus status, String reason) {
            finishes.add(status); reasons.add(reason);
        }
        void tool(String name, String args) {
            requests.getLast().complete(new AssistantTurn("", List.of(new ToolCall("call", name, args)), 0, 0, -1, "tool_calls"));
        }
        void reply() { requests.getLast().complete(new AssistantTurn("done", List.of(), 0, 0, -1, "stop")); }
        void seed() {
            loop.submit(51, "Collect three additional diamonds");
            tool("set_goal", "{\"item\":\"diamond\",\"count\":3,\"lifecycle\":\"long_term\",\"max_runs\":4}");
            assertNotNull(saved);
        }
    }

    @Test void recoveryKeepsTheOriginalBaselineAndChargesInterruptedModelSteps() {
        var original = new Fixture(); original.seed();
        var saved = original.saved.deepCopy();
        original.loop.close();
        var fresh = new Fixture();
        fresh.state = state(6); fresh.state.addProperty("body_id", "new-transport-body");
        fresh.loop.resume(91, saved);
        assertEquals(saved.get("steps").getAsInt() + 1, fresh.loop.steps());
        assertEquals(1, fresh.requests.size());
        assertEquals(91, fresh.loop.currentTaskId());
        assertTrue(fresh.histories.getFirst().stream().anyMatch(m -> m instanceof Msg.Nudge n
                && n.text().contains("任务开始基线=5") && n.text().contains("实际进度=1")));
        assertEquals(saved.get("goal_id"), fresh.saved.get("goal_id"));
        fresh.state = state(8); fresh.state.addProperty("body_id", "new-transport-body");
        fresh.reply();
        assertEquals(List.of(AgentLoop.TaskStatus.COMPLETED), fresh.finishes);
        assertTrue(fresh.actions.isEmpty());
    }
    @Test void alreadySatisfiedGoalCompletesWithFreshEvidenceWithoutARequest() {
        var original = new Fixture(); original.seed();
        var fresh = new Fixture(); fresh.state = state(8);
        fresh.loop.resume(91, original.saved);
        assertEquals(List.of(AgentLoop.TaskStatus.COMPLETED), fresh.finishes);
        assertTrue(fresh.requests.isEmpty());
    }
    @ParameterizedTest @ValueSource(strings = {"world_id", "owner_uuid", "companion_uuid", "body_continuity_id", "dimension"})
    void changedIdentityCannotReachTheModel(String field) {
        var original = new Fixture(); original.seed();
        var fresh = new Fixture();
        fresh.state.addProperty(field, field.equals("dimension") ? "minecraft:the_nether"
                : "20000000-0000-0000-0000-000000000001");
        fresh.loop.resume(91, original.saved);
        assertEquals(List.of(AgentLoop.TaskStatus.FAILED), fresh.finishes);
        assertTrue(fresh.reasons.getFirst().startsWith("RECOVERY_UNVERIFIABLE:"));
        assertTrue(fresh.requests.isEmpty()); assertTrue(fresh.actions.isEmpty());
    }
    @Test void oldModelAllowanceMigratesToUnlimitedWithoutLosingFinalVerification() {
        var original = new Fixture(); original.seed();
        var saved = original.saved.deepCopy(); saved.addProperty("steps", 40); saved.addProperty("step_limit", 40);
        var fresh = new Fixture(); fresh.loop.resume(91, saved);
        assertTrue(fresh.reasons.isEmpty()); assertEquals(1, fresh.requests.size());
        assertEquals(41, fresh.loop.steps());
        fresh.loop.close();
        var met = new Fixture(); met.state = state(8); met.loop.resume(92, saved);
        assertEquals(List.of(AgentLoop.TaskStatus.COMPLETED), met.finishes);
    }
    @Test void repeatedStagesAndTheStageCapSurviveRecovery() {
        var original = new Fixture(); original.seed();
        original.reply();
        var saved = original.saved.deepCopy();
        assertEquals(1, saved.getAsJsonObject("continuation").get("stalled").getAsInt());
        var fresh = new Fixture(); fresh.loop.resume(91, saved); fresh.reply();
        assertTrue(fresh.reasons.isEmpty());
        assertEquals(2, fresh.requests.size());
        fresh.loop.close();
        var capped = saved.deepCopy();
        capped.getAsJsonObject("continuation").addProperty("completed_runs", 4);
        var exhausted = new Fixture(); exhausted.loop.resume(92, capped);
        assertTrue(exhausted.reasons.getFirst().startsWith("RECOVERY_LIMIT:"));
    }
    @Test void cancellationDuringFreshVerificationRejectsTheLateObservation() {
        var original = new Fixture(); original.seed();
        var fresh = new Fixture(); var observation = new CompletableFuture<ToolOutcome>(); fresh.deferred = observation;
        fresh.loop.resume(91, original.saved); fresh.loop.cancelTask(91);
        observation.complete(new ToolOutcome(true, "late", state(8)));
        assertEquals(List.of(AgentLoop.TaskStatus.CANCELLED), fresh.finishes);
        assertTrue(fresh.requests.isEmpty()); assertTrue(fresh.actions.isEmpty());
    }
    @Test void failedPersistenceStopsBeforeAnySubsequentActionOrModelTurn() {
        var f = new Fixture(); f.failSave = true; f.loop.submit(51, "collect diamonds");
        f.requests.getFirst().complete(new AssistantTurn("", List.of(
                new ToolCall("goal", "set_goal", "{\"item\":\"diamond\",\"count\":3,\"lifecycle\":\"long_term\"}"),
                new ToolCall("edit", "break_block", "{\"x\":1,\"y\":90,\"z\":0}")), 0, 0, -1, "tool_calls"));
        assertEquals(1, f.requests.size()); assertTrue(f.actions.isEmpty());
        assertTrue(f.reasons.getFirst().startsWith("CHECKPOINT_UNAVAILABLE:"));
    }
    @Test void sharedFailureCountsDoNotDisappearWhenSpatialVersionsExpire() {
        var memory = new WorkFailureMemory(); var args = new JsonObject(); args.addProperty("x", 2);
        for (int i = 0; i < 5; i++) memory.terminal("move_to", args, new ToolOutcome(false, "NO_PATH:blocked"));
        var copy = WorkFailureMemory.restore(memory.snapshot());
        assertEquals(5, copy.failures("move_to", args));
        assertEquals(memory.snapshot(), copy.snapshot());
    }
    @Test void sharedTransferBalancesSurviveButPendingEffectsCannotBeSnapshotted() {
        var ledger = new DeliveryLedger();
        assertEquals(ledger.snapshot(), DeliveryLedger.restore(ledger.snapshot()).snapshot());
        ledger.started();
        assertThrows(IllegalStateException.class, ledger::snapshot);
    }
}
