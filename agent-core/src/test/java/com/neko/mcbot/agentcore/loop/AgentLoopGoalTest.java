package com.neko.mcbot.agentcore.loop;

import com.google.gson.JsonObject;
import com.neko.mcbot.agentcore.llm.*;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

class AgentLoopGoalTest {
    static JsonObject state(int count) {
        var state = new JsonObject();
        var inventory = new JsonObject();
        var slots = new com.google.gson.JsonArray();
        for (int i = 0; i < 36; i++) {
            var slot = new JsonObject();
            slot.addProperty("slot", i);
            slot.addProperty("item", i == 0 ? "minecraft:diamond" : "");
            slot.addProperty("count", i == 0 ? count : 0);
            slots.add(slot);
        }
        inventory.add("slots", slots);
        state.add("inventory", inventory);
        return state;
    }

    private static final class Fixture implements ChatEngine, ToolExecutor {
        int count = 5;
        final List<CompletableFuture<AssistantTurn>> requests = new ArrayList<>();
        final List<List<Msg>> histories = new ArrayList<>();
        final List<AgentLoop.TaskStatus> statuses = new ArrayList<>();
        final AgentLoop loop = new AgentLoop(this,
                List.of(ToolSpec.of("set_goal", "", "{}"), ToolSpec.of("report_blocked", "", "{}")),
                this, AgentLoop.Config.defaults(), new AgentLoop.Listener() {
                    @Override public void onTaskFinished(long id, AgentLoop.TaskStatus status, String text) {
                        statuses.add(status);
                    }
                }, () -> "system", 1_000_000);
        @Override public CompletableFuture<AssistantTurn> chat(String s, List<Msg> history, List<ToolSpec> tools) {
            histories.add(history);
            var future = new CompletableFuture<AssistantTurn>();
            requests.add(future);
            return future;
        }
        @Override public CompletableFuture<ToolOutcome> observe(long id) {
            return CompletableFuture.completedFuture(new ToolOutcome(true, "fresh", state(count)));
        }
        @Override public CompletableFuture<ToolOutcome> execute(String name, String args) {
            return CompletableFuture.completedFuture(new ToolOutcome(true, "world receipt"));
        }
        void goal() {
            requests.getLast().complete(new AssistantTurn("", List.of(
                    new ToolCall("goal", "set_goal", "{\"item\":\"diamond\",\"count\":3}")), 0, 0, -1, "tool_calls"));
        }
        void reply() {
            requests.getLast().complete(new AssistantTurn("completed", List.of(), 0, 0, -1, "stop"));
        }
    }

    @Test void earlyCompletionCannotProduceDoneAndUsesFreshInventoryBeforeFinalCompletion() {
        var f = new Fixture();
        f.loop.submit(7, "collect 3 additional diamonds");
        f.goal();
        f.count = 6;
        f.reply();
        assertTrue(f.statuses.isEmpty());
        assertTrue(f.loop.busy());
        assertTrue(f.histories.getLast().stream().anyMatch(m -> m instanceof Msg.Nudge n
                && n.text().contains("任务开始基线=5") && n.text().contains("实际进度=1")));
        f.count = 8;
        f.reply();
        assertEquals(List.of(AgentLoop.TaskStatus.COMPLETED), f.statuses);
        f.loop.close();
    }

    @Test void repeatedFalseFinishesStopWithoutPretendingSuccess() {
        var f = new Fixture();
        f.loop.submit(8, "collect diamonds");
        f.goal();
        f.reply();
        f.reply();
        f.reply();
        assertEquals(List.of(AgentLoop.TaskStatus.FAILED), f.statuses);
        assertFalse(f.loop.busy());
    }

    @Test void loweredGoalIsRejectedAndCancellationNeverRestartsTheGoal() {
        var f = new Fixture();
        f.loop.submit(9, "collect diamonds");
        f.goal();
        f.requests.getLast().complete(new AssistantTurn("", List.of(
                new ToolCall("lower", "set_goal", "{\"item\":\"diamond\",\"count\":1}")), 0, 0, -1, "tool_calls"));
        var receipt = f.loop.conversation().history().stream().filter(m -> m instanceof Msg.Tool t
                && t.callId().equals("lower")).map(m -> (Msg.Tool) m).findFirst().orElseThrow();
        assertFalse(receipt.ok());
        int requests = f.requests.size();
        f.loop.cancelTask(9);
        f.count = 99;
        f.reply();
        assertEquals(requests, f.requests.size());
        assertEquals(List.of(AgentLoop.TaskStatus.CANCELLED), f.statuses);
    }

    @Test void quantitiesUseNetInventoryAndNeverCountGroundDropsOrEquipment() {
        var baseline = state(5);
        var goal = new ItemGoal(ItemGoal.Target.parse("{\"item\":\"diamond\",\"count\":3}"), baseline);
        var after = state(6);
        after.addProperty("collected_count", 100);
        after.addProperty("remaining_count", 100);
        goal.observe(after);
        assertEquals(1, goal.progress());
        assertFalse(goal.satisfied());
        goal.observe(state(3));
        assertEquals(0, goal.progress());
        var total = new ItemGoal(ItemGoal.Target.parse("{\"item\":\"diamond\",\"count\":5,\"mode\":\"total\"}"), baseline);
        assertTrue(total.satisfied());
    }

    @Test void incompleteOrDuplicatedSlotsCannotServeAsGoalEvidence() {
        var incomplete = state(5);
        incomplete.getAsJsonObject("inventory").getAsJsonArray("slots").remove(35);
        assertThrows(IllegalArgumentException.class, () -> ItemGoal.count(incomplete, "minecraft:diamond"));
        var duplicate = state(5);
        duplicate.getAsJsonObject("inventory").getAsJsonArray("slots").get(35).getAsJsonObject().addProperty("slot", 0);
        assertThrows(IllegalArgumentException.class, () -> ItemGoal.count(duplicate, "minecraft:diamond"));
    }
}
