package com.neko.mcbot.agentcore.loop;

import com.neko.mcbot.agentcore.llm.*;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

class AgentLoopContextBudgetTest {
    @Test void definitionPressureDoesNotEmitNoOpNoticesOrDuplicateObservations() {
        int[] observations = {0};
        var notices = new ArrayList<String>();
        var responses = new ArrayList<CompletableFuture<AssistantTurn>>();
        var loop = new AgentLoop((s, h, t) -> {
            assertFalse(t.isEmpty(), "short history must never request a summary");
            var response = new CompletableFuture<AssistantTurn>();
            responses.add(response);
            return response;
        }, List.of(ToolSpec.of("status", "界".repeat(7000), "{}")), new ToolExecutor() {
            @Override public CompletableFuture<ToolOutcome> execute(String n, String a) {
                return CompletableFuture.completedFuture(new ToolOutcome(true, "fact"));
            }
            @Override public CompletableFuture<ToolOutcome> observe(long taskId) {
                observations[0]++;
                return CompletableFuture.completedFuture(new ToolOutcome(true, "current body"));
            }
        }, AgentLoop.Config.defaults(), new AgentLoop.Listener() {
            @Override public void onNotice(String text) { notices.add(text); }
        }, () -> "system", 6000);
        for (int i = 0; i < 12; i++) loop.conversation().add(new Msg.User("old short event"));
        loop.submit(1, "work");
        for (int i = 0; i < 5; i++) responses.getLast().complete(new AssistantTurn("", List.of(
                new ToolCall("c" + i, "status", "{\"details\":" + (i % 2 == 0) + "}")),
                0, 0, -1, "tool_calls"));
        assertEquals(6, responses.size());
        assertEquals(6, observations[0]);
        assertTrue(notices.isEmpty());
        loop.close();
    }

    @Test void taskCompactsAtASettledToolBoundaryAndThenResumesWithoutEndingTheTask() {
        var definitions = new ArrayList<List<ToolSpec>>();
        var requests = new ArrayList<CompletableFuture<AssistantTurn>>();
        ChatEngine engine = (s, h, tools) -> {
            definitions.add(tools);
            var future = new CompletableFuture<AssistantTurn>();
            requests.add(future);
            return future;
        };
        var loop = new AgentLoop(engine, List.of(ToolSpec.of("status", "", "{}")),
                (n, a) -> CompletableFuture.completedFuture(
                        new ToolExecutor.ToolOutcome(true, "事实".repeat(130))),
                AgentLoop.Config.defaults(), new AgentLoop.Listener() { }, () -> "system", 700);
        loop.configureContext(700, 100, true);
        loop.submit(3, "work");
        assertTrue(loop.awaitingModel());
        assertFalse(loop.compacting());
        for (int i = 0; i < 4; i++) {
            requests.getLast().complete(new AssistantTurn("", List.of(
                    new ToolCall("c" + i, "status", "{}")), 0, 0, -1, "tool_calls"));
        }
        assertTrue(definitions.getLast().isEmpty(), "summary happens during the task");
        assertTrue(loop.compacting());
        assertFalse(loop.awaitingModel());
        assertEquals(3, loop.currentTaskId());
        requests.getLast().complete(new AssistantTurn("保留实际事实", List.of(), 0, 0, -1, "stop"));
        assertEquals(1, definitions.getLast().size());
        assertFalse(loop.compacting());
        assertTrue(loop.awaitingModel());
        assertEquals(3, loop.currentTaskId());
        assertTrue(loop.conversation().history().stream().anyMatch(m -> m instanceof Msg.User u
                && u.text().contains("保留实际事实")));
        loop.close();
        assertFalse(loop.compacting());
        assertFalse(loop.awaitingModel());
    }

    @Test void fullBudgetIncludesToolsSystemObservationAndOutputReserve() {
        int[] modelCalls = {0};
        ChatEngine engine = (s, h, t) -> {
            modelCalls[0]++;
            return new CompletableFuture<>();
        };
        var statuses = new ArrayList<AgentLoop.TaskStatus>();
        var loop = new AgentLoop(engine, List.of(ToolSpec.of("status", "界".repeat(450), "{}")),
                (n, a) -> CompletableFuture.completedFuture(new ToolExecutor.ToolOutcome(true, "")),
                AgentLoop.Config.defaults(), new AgentLoop.Listener() {
                    @Override public void onTaskFinished(long id, AgentLoop.TaskStatus status, String text) {
                        statuses.add(status);
                        assertTrue(text.startsWith("CONTEXT_LIMIT:"));
                    }
                }, () -> "系统".repeat(200), 1_000_000);
        loop.configureWindow(1024);
        loop.submit(4, "work");
        assertEquals(0, modelCalls[0]);
        assertEquals(List.of(AgentLoop.TaskStatus.FAILED), statuses);
    }
}
