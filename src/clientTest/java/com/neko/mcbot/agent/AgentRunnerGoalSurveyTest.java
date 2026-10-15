package com.neko.mcbot.agent;

import com.google.gson.*;
import com.neko.mcbot.agentcore.llm.ToolCall;
import com.neko.mcbot.common.Envelope;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AgentRunnerGoalSurveyTest {
    private final OfflineAgentClient client = new OfflineAgentClient();
    private final AgentRunner runner = client.create();
    @AfterEach void close() { runner.close(); client.drain(); }
    private static JsonObject json(String value) { return JsonParser.parseString(value).getAsJsonObject(); }
    private void reply(JsonObject data) { reply(true, "actual", data); }
    private void reply(boolean ok, String text, JsonObject data) {
        var body = new JsonObject();
        body.addProperty("seq", client.lastTool().num("seq", -1));
        body.addProperty("ok", ok); body.addProperty("feedback", text); body.add("data", data);
        runner.handleS2c(new Envelope("tool_result", body)); client.drain();
    }
    private void search(boolean found) {
        for (int offset : new int[]{0,4096,8192,12288,16384}) {
            assertEquals("find_resource", client.lastTool().str("tool"));
            reply(BoundedSearchTest.page(offset, found && offset == 16384));
        }
    }
    private java.util.concurrent.CompletableFuture<com.neko.mcbot.agentcore.loop.ToolExecutor.ToolOutcome> survey() {
        runner.start(); runner.submitTask("有限地下找资源"); client.drain();
        var future = runner.execute("explore", "{\"mode\":\"underground\",\"direction\":\"east\","
                + "\"target_y\":70,\"targets\":[\"copper_ore\"],\"r\":16,\"max_steps\":4}");
        client.drain();
        reply(new BoundedSurveyTest.World().state());
        search(false);
        assertEquals("move_to", client.lastTool().str("tool"));
        assertTrue(client.lastTool().obj("args").get("underground").getAsBoolean());
        return future;
    }

    @Test void longGoalCheckpointKeepsServerTaskScopeAndOriginalExplorationBudget() {
        client.liveObservation = true;
        runner.start(); runner.submitTask("最终新增3个泥土，分阶段准备"); client.drain();
        reply(BoundedGatherTest.state(0));
        long task = runner.currentTaskId();
        client.engine().tools(0, new ToolCall("goal", "set_goal",
                "{\"item\":\"dirt\",\"count\":3,\"lifecycle\":\"long_term\"}"));
        client.drain(); reply(BoundedGatherTest.state(0));
        var trip = runner.execute("explore", "{\"max_steps\":1}");
        client.drain(); reply(BoundedGatherTest.state(0));
        reply(json("{\"exploration_points\":[{\"x\":8,\"y\":90,\"z\":0}]}"));
        reply(json("{\"arrived\":true}"));
        var here = BoundedGatherTest.state(0); here.addProperty("x", 8);
        reply(here);
        assertTrue(trip.join().ok());
        client.engine().reply(1, "第一阶段已到新区域"); client.drain();
        reply(here); // Finish verification.
        assertEquals(task, runner.currentTaskId());
        assertEquals(1, client.sent.stream().filter(e -> e.kind().equals("task_begin")).count());
        assertEquals(0, client.sent.stream().filter(e -> e.kind().equals("task_end")).count());
        reply(here); // Next stage observation.
        assertEquals(3, client.engine().responses.size());
        var exhausted = runner.execute("explore", "{}"); client.drain(); reply(here);
        assertTrue(exhausted.join().feedback().startsWith("EXPLORATION_LIMIT:"));
        assertEquals(1, exhausted.join().data().get("exploration_attempts").getAsInt());
        client.engine().reply(2, "最终成果"); client.drain();
        reply(BoundedGatherTest.state(3));
        assertEquals(0, runner.currentTaskId());
        assertEquals(1, client.sent.stream().filter(e -> e.kind().equals("task_end")).count());
        assertEquals("completed", runner.panelHistory().tasks().getFirst().status());
    }

    @Test void surveyConsentWaitsForBothApprovalsAndMovementTerminalBeforeRescanning() {
        var future = survey();
        var original = client.lastTool().obj("args").deepCopy();
        reply(false, "NEED_CONFIRM:review cells", json("{\"authorization_id\":\"survey-plan\","
                + "\"authorization_summary\":\"exact stair cells\",\"removed_blocks\":0,\"placed_blocks\":0}"));
        assertTrue(runner.questionSnapshot().text().contains("exact stair cells"));
        long count = client.toolCount();
        assertTrue(runner.answerFromPanel(runner.questionSnapshot().id(), "确认")); client.drain();
        assertEquals(count, client.toolCount());
        var grant = client.sent.getLast();
        assertEquals("authorize", grant.kind());
        var approved = json("{\"ok\":true,\"feedback\":\"approved\"}");
        approved.addProperty("seq", grant.num("seq", -1));
        runner.handleS2c(new Envelope("tool_result", approved)); client.drain();
        original.addProperty("authorization_id", "survey-plan");
        assertEquals(original, client.lastTool().obj("args"));
        long seq = client.lastTool().num("seq", -1);
        var ack = json("{\"job_id\":\"survey-move\",\"cap_ticks\":3600}");
        ack.addProperty("seq", seq);
        runner.handleS2c(new Envelope("job_ack", ack)); client.drain();
        assertFalse(future.isDone());
        assertEquals("move_to", client.lastTool().str("tool"));
        assertEquals(1, client.engine().responses.size());
        var terminal = json("{\"job_id\":\"survey-move\",\"phase\":\"done\",\"text\":\"actual stair\","
                + "\"data\":{\"removed_blocks\":12,\"placed_blocks\":0}}");
        terminal.addProperty("seq", seq);
        runner.handleS2c(new Envelope("job_event", terminal)); client.drain();
        assertEquals("status", client.lastTool().str("tool"));
        var actual = new BoundedSurveyTest.World().state();
        actual.addProperty("x", 4); actual.addProperty("y", 86);
        reply(actual);
        search(true);
        assertTrue(future.join().ok(), future.join().feedback());
        assertEquals(1, future.join().data().get("return_steps").getAsInt());
        assertFalse(future.join().data().has("goal_satisfied"));
        assertEquals(1, client.engine().responses.size(), "discovery is a tool result, not a task finish");
    }

    @Test void cancellingSurveyQuestionDoesNotAuthorizeOrContinueOnLateAnswer() {
        var future = survey();
        reply(false, "NEED_CONFIRM:review", json("{\"authorization_id\":\"survey-plan\","
                + "\"authorization_summary\":\"stair cells\",\"removed_blocks\":0,\"placed_blocks\":0}"));
        var question = runner.questionSnapshot();
        runner.cancelTask(0); client.drain();
        long count = client.toolCount();
        assertNull(runner.questionSnapshot());
        assertFalse(runner.answerFromPanel(question.id(), "确认")); client.drain();
        assertFalse(future.join().ok());
        assertEquals(count, client.toolCount());
        assertTrue(client.sent.stream().noneMatch(e -> e.kind().equals("authorize")));
    }

    @Test void invalidUndergroundRequestCannotPinTheTaskExplorationConfiguration() {
        runner.start(); runner.submitTask("explore"); client.drain();
        assertFalse(runner.execute("explore", "{\"mode\":\"underground\",\"max_steps\":1}").join().ok());
        var valid = runner.execute("explore", "{\"max_steps\":4}"); client.drain();
        assertFalse(valid.isDone(), "the rejected request must not establish max_steps=1");
        assertEquals("status", client.lastTool().str("tool"));
    }
}
