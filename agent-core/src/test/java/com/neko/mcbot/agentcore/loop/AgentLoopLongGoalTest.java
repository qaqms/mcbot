package com.neko.mcbot.agentcore.loop;

import com.google.gson.JsonObject;
import com.neko.mcbot.agentcore.llm.*;
import com.neko.mcbot.agentcore.loop.ToolExecutor.ToolOutcome;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

class AgentLoopLongGoalTest {
    @Test void defaultLoopAndLongGoalContinueBeyondOldRoundAndStageCaps() {
        assertEquals(0, AgentLoop.Config.defaults().maxStepsPerDirective());
        var f = new Fixture(0);
        f.goal("");
        for (int i = 1; i <= 48; i++) {
            f.material(i);
            f.reply();
            assertTrue(f.finishes.isEmpty(), f.endings.toString());
        }
        f.state.getAsJsonObject("inventory").getAsJsonArray("slots").get(0).getAsJsonObject().addProperty("count", 8);
        f.reply();
        assertEquals(List.of(AgentLoop.TaskStatus.COMPLETED), f.finishes);
    }
    private static final String GOAL = "{\"item\":\"diamond\",\"count\":3,\"lifecycle\":\"long_term\"";
    private static final class Fixture implements ChatEngine, ToolExecutor, AgentLoop.Listener {
        final List<CompletableFuture<AssistantTurn>> requests = new ArrayList<>();
        final List<List<Msg>> histories = new ArrayList<>();
        final List<Long> starts = new ArrayList<>();
        final List<AgentLoop.TaskStatus> finishes = new ArrayList<>();
        final List<String> endings = new ArrayList<>(), checkpoints = new ArrayList<>();
        JsonObject state = state();
        CompletableFuture<ToolOutcome> nextObservation;
        final AgentLoop loop;
        Fixture() { this(40); }
        Fixture(int steps) {
            loop = new AgentLoop(this, List.of(), this, new AgentLoop.Config(steps, 3, 5), this,
                    () -> "system", 1_000_000);
            loop.submit(51, "Prepare tools, then obtain three additional diamonds");
        }
        static JsonObject state() {
            var value = AgentLoopGoalTest.state(5);
            value.addProperty("x", 0); value.addProperty("y", 70); value.addProperty("z", 0);
            value.addProperty("dimension", "minecraft:overworld");
            return value;
        }
        void material(int count) {
            var slot = state.getAsJsonObject("inventory").getAsJsonArray("slots").get(1).getAsJsonObject();
            slot.addProperty("item", "minecraft:oak_log"); slot.addProperty("count", count);
        }
        @Override public CompletableFuture<AssistantTurn> chat(String s, List<Msg> h, List<ToolSpec> tools) {
            histories.add(h);
            var future = new CompletableFuture<AssistantTurn>(); requests.add(future); return future;
        }
        @Override public CompletableFuture<ToolOutcome> observe(long id) {
            if (nextObservation != null) {
                var future = nextObservation; nextObservation = null; return future;
            }
            return CompletableFuture.completedFuture(new ToolOutcome(true, "fresh", state.deepCopy()));
        }
        @Override public CompletableFuture<ToolOutcome> execute(String name, String args) {
            return CompletableFuture.completedFuture(new ToolOutcome(true, "actual subtask"));
        }
        @Override public void onTaskStarted(long id) { starts.add(id); }
        @Override public void onTaskFinished(long id, AgentLoop.TaskStatus status, String text) {
            finishes.add(status); endings.add(text);
        }
        @Override public void onGoalCheckpoint(long id, String facts) {
            assertEquals(51, id); checkpoints.add(facts);
        }
        void tool(String name, String args) {
            requests.getLast().complete(new AssistantTurn("", List.of(
                    new ToolCall("call" + requests.size(), name, args)), 0, 0, -1, "tool_calls"));
        }
        void goal(String suffix) { tool("set_goal", GOAL + suffix + "}"); }
        void reply() { requests.getLast().complete(new AssistantTurn("stage result", List.of(), 0, 0, -1, "stop")); }
        Msg.Tool receipt() { return (Msg.Tool) loop.conversation().history().getLast(); }
    }

    @Test void intermediateResultsContinueTheSameTaskAndFinalInventoryUsesOriginalBaseline() {
        var f = new Fixture(); f.goal("");
        for (int quantity : List.of(2, 4, 7)) {
            f.material(quantity); f.reply();
            assertTrue(f.finishes.isEmpty());
            assertEquals(51, f.loop.currentTaskId());
        }
        assertEquals(List.of(51L), f.starts);
        assertEquals(3, f.checkpoints.size());
        assertTrue(f.histories.getLast().stream().anyMatch(m -> m instanceof Msg.Nudge n
                && n.text().contains("任务开始基线=5") && n.text().contains("阶段已核对=3/不限")));
        f.state.getAsJsonObject("inventory").getAsJsonArray("slots").get(0).getAsJsonObject().addProperty("count", 8);
        f.reply();
        assertEquals(List.of(AgentLoop.TaskStatus.COMPLETED), f.finishes);
        assertEquals(List.of(51L), f.starts);
    }

    @Test void unchangedFactsDoNotAutomaticallyEndTheGoal() {
        var f = new Fixture(); f.goal("");
        f.state.addProperty("game_tick", 999);
        f.reply();
        var slots = f.state.getAsJsonObject("inventory").getAsJsonArray("slots");
        slots.get(1).getAsJsonObject().addProperty("item", "minecraft:diamond");
        slots.get(1).getAsJsonObject().addProperty("count", 5);
        slots.get(0).getAsJsonObject().addProperty("count", 0);
        slots.get(1).getAsJsonObject().addProperty("damage", 12);
        f.state.addProperty("hp", 18);
        f.reply();
        assertTrue(f.finishes.isEmpty());
        assertEquals(51, f.loop.currentTaskId());
        f.loop.close();
    }

    @Test void revisitingOldFactsDoesNotCountAsFreshProgress() {
        var f = new Fixture(); f.goal("");
        f.state.addProperty("x", 4); f.reply();
        f.state.addProperty("x", 0); f.reply();
        f.state.addProperty("x", 4); f.reply();
        assertTrue(f.finishes.isEmpty());
        f.loop.close();
    }

    @Test void stageLimitIsImmutableAndGatherCanInheritWithoutChangingIt() {
        var f = new Fixture(); f.goal(",\"max_runs\":2");
        f.tool("set_goal", GOAL + ",\"max_runs\":16}");
        assertFalse(f.receipt().ok());
        f.tool("set_goal", "{\"item\":\"diamond\",\"count\":3,\"lifecycle\":\"task\"}");
        assertFalse(f.receipt().ok());
        f.tool("gather", "{\"item\":\"diamond\",\"count\":3}");
        assertTrue(f.receipt().ok(), f.receipt().content());
        f.material(1); f.reply();
        f.material(2); f.reply();
        assertTrue(f.endings.getLast().startsWith("GOAL_RUN_LIMIT:"));
        assertEquals(1, f.starts.size());
    }

    @Test void completionIsCheckedBeforeTheLastStageLimit() {
        var f = new Fixture(); f.goal(",\"max_runs\":1");
        f.state.getAsJsonObject("inventory").getAsJsonArray("slots").get(0).getAsJsonObject().addProperty("count", 8);
        f.reply();
        assertEquals(List.of(AgentLoop.TaskStatus.COMPLETED), f.finishes);
    }

    @Test void continuationNeverRefreshesTheModelStepBudget() {
        var f = new Fixture(4); f.goal(",\"max_runs\":16");
        for (int quantity : List.of(1, 2, 3)) { f.material(quantity); f.reply(); }
        assertEquals(4, f.requests.size());
        assertEquals(List.of(AgentLoop.TaskStatus.FAILED), f.finishes);
        assertTrue(f.endings.getLast().contains("步数超限"));
    }

    @Test void cancellationDuringCheckpointDiscardsTheLateObservation() {
        var f = new Fixture(); f.goal("");
        var pending = new CompletableFuture<ToolOutcome>(); f.nextObservation = pending;
        f.reply();
        int requests = f.requests.size();
        f.loop.cancelTask(51);
        f.material(7);
        pending.complete(new ToolOutcome(true, "late", f.state));
        assertEquals(requests, f.requests.size());
        assertTrue(f.checkpoints.isEmpty());
        assertEquals(List.of(AgentLoop.TaskStatus.CANCELLED), f.finishes);
    }

    @Test void blockedOrFailedModelTerminatesWithoutAutoRestart() {
        var blocked = new Fixture(); blocked.goal("");
        blocked.tool("report_blocked", "{\"reason\":\"no safe route\"}");
        assertTrue(blocked.endings.getLast().startsWith("BLOCKED:"));
        assertFalse(blocked.loop.busy());
        var failed = new Fixture(); failed.goal("");
        failed.requests.getLast().completeExceptionally(new IllegalStateException("offline"));
        assertFalse(failed.loop.busy());
        assertTrue(failed.checkpoints.isEmpty());
        assertEquals(1, failed.finishes.size());
    }

    @Test void goalFactsAreIndependentOfRetainedConversationHistory() {
        var f = new Fixture(); f.goal("");
        f.loop.conversation().clear();
        f.material(2); f.reply();
        assertTrue(f.histories.getLast().stream().anyMatch(m -> m instanceof Msg.Nudge n
                && n.text().contains("物品目标=minecraft:diamond") && n.text().contains("阶段已核对=1/不限")));
        f.loop.close();
    }

    @Test void malformedLifecycleOptionsCannotRegisterOrWidenTheGoal() {
        for (String suffix : List.of(",\"max_runs\":-1", ",\"max_runs\":2147483648", ",\"max_runs\":1.5",
                ",\"max_runs\":\"8\"", ",\"max_runs\":null")) {
            var f = new Fixture(); f.goal(suffix);
            assertFalse(f.receipt().ok(), suffix);
            f.loop.close();
        }
        var f = new Fixture();
        f.tool("set_goal", "{\"item\":\"diamond\",\"count\":3,\"max_runs\":8}");
        assertFalse(f.receipt().ok());
        f.loop.close();
    }
}
