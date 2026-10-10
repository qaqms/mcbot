package com.neko.mcbot.agentcore.loop;

import com.google.gson.JsonObject;
import com.neko.mcbot.agentcore.llm.*;
import com.neko.mcbot.agentcore.loop.ToolExecutor.ToolOutcome;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

class AgentLoopSearchGuardTest {
    private final List<CompletableFuture<AssistantTurn>> responses = new ArrayList<>();
    private final List<String> executed = new ArrayList<>();
    private final List<AgentLoop.TaskStatus> statuses = new ArrayList<>();
    private final JsonObject empty = new JsonObject();
    private ToolOutcome next = new ToolOutcome(true, "empty", empty);

    private AgentLoop create() {
        empty.addProperty("no_targets", true);
        next = new ToolOutcome(true, "empty", empty);
        return new AgentLoop((s, h, t) -> {
            var f = new CompletableFuture<AssistantTurn>();
            responses.add(f);
            return f;
        }, List.of(), (n, a) -> {
            executed.add(n);
            return CompletableFuture.completedFuture(n.equals("status") || n.equals("inventory")
                    ? new ToolOutcome(true, "state changed") : next);
        }, AgentLoop.Config.defaults(), new AgentLoop.Listener() {
            @Override public void onTaskFinished(long id, AgentLoop.TaskStatus status, String text) {
                statuses.add(status);
            }
        }, () -> "system", 1000000);
    }
    private void call(String tool, String args) {
        responses.getLast().complete(new AssistantTurn("", List.of(new ToolCall(
                "call" + responses.size(), tool, args)), 0, 0, -1, "tool_calls"));
    }

    @Test void changedParametersAndInterleavedStateDoNotResetEmptySearchBudget() {
        var loop = create();
        loop.submit(1, "search");
        call("scan_area", "{\"r\":2}");
        call("status", "{}");
        call("find_resource", "{\"r\":8,\"targets\":[\"oak_log\"]}");
        call("inventory", "{}");
        call("scan_area", "{\"r\":32}");
        assertEquals(5, responses.size());
        assertEquals(List.of(AgentLoop.TaskStatus.FAILED), statuses);
        assertEquals(0, loop.currentTaskId());
        loop.submit(2, "new search");
        call("scan_area", "{}");
        assertEquals(2, loop.currentTaskId());
    }

    @Test void thirdEmptySearchPreventsLaterToolsInTheSameTurnAndPairsEveryCall() {
        var loop = create();
        loop.submit(1, "search");
        var calls = List.of(new ToolCall("a", "scan_area", "{}"), new ToolCall("b", "find_resource", "{}"),
                new ToolCall("c", "scan_area", "{\"r\":3}"), new ToolCall("d", "break_block", "{}"));
        responses.getFirst().complete(new AssistantTurn("", calls, 0, 0, -1, "tool_calls"));
        assertEquals(List.of("scan_area", "find_resource", "scan_area"), executed);
        assertEquals(4, loop.conversation().history().stream().filter(Msg.Tool.class::isInstance).count());
        assertEquals(List.of(AgentLoop.TaskStatus.FAILED), statuses);
    }

    @Test void genuineItemsOrFoundTargetsResetBudgetButProseDoesNotControlIt() {
        var loop = create();
        loop.submit(1, "search");
        call("scan_area", "{}");
        call("scan_area", "{\"r\":3}");
        var found = new JsonObject();
        found.addProperty("no_targets", false);
        next = new ToolOutcome(true, "NO_TARGETS:words are not flags", found);
        call("find_resource", "{}");
        next = new ToolOutcome(true, "empty", empty);
        call("scan_area", "{}");
        call("find_resource", "{}");
        var items = new JsonObject();
        items.addProperty("crafted_count", 4);
        next = new ToolOutcome(true, "crafted", items);
        call("craft", "{}");
        next = new ToolOutcome(true, "empty", empty);
        call("scan_area", "{}");
        call("find_resource", "{\"r\":9}");
        var partial = new JsonObject();
        partial.addProperty("crafted_count", 4);
        partial.addProperty("no_targets", true);
        next = new ToolOutcome(false, "crafted then stopped on an empty search", partial);
        call("workflow", "{}");
        next = new ToolOutcome(true, "empty", empty);
        call("scan_area", "{\"r\":10}");
        assertEquals(1, loop.currentTaskId());
        assertTrue(statuses.isEmpty());
    }

    @Test void canceledTaskCancelsModelFutureAndQueuedTaskStillStarts() {
        var loop = create();
        loop.submit(1, "first");
        loop.submit(2, "second");
        assertTrue(loop.cancelTask(1));
        assertTrue(responses.getFirst().isCancelled());
        assertEquals(2, loop.currentTaskId());
        loop.close();
        assertTrue(responses.getLast().isCancelled());
    }

    @Test void outcomeDataIsCopiedAtBothBoundaries() {
        var data = new JsonObject();
        data.addProperty("no_targets", true);
        var receipt = new ToolOutcome(true, "empty", data);
        data.addProperty("no_targets", false);
        receipt.data().addProperty("no_targets", false);
        assertTrue(receipt.data().get("no_targets").getAsBoolean());
    }
}
