package com.neko.mcbot.agent;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.neko.mcbot.bridge.BridgeEventCapture;
import com.neko.mcbot.common.Envelope;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(10)
class AgentRunnerDeathTest {
    private final OfflineAgentClient client = new OfflineAgentClient();
    private final AgentRunner runner = client.create();
    private final BridgeEventCapture events = new BridgeEventCapture();
    private final UUID companion = UUID.randomUUID(), body = UUID.randomUUID();

    @AfterEach void close() { runner.close(); client.drain(); events.close(); }

    private Envelope life(UUID incarnation, String state) {
        var data = new JsonObject();
        data.addProperty("companion", "same_companion");
        data.addProperty("companion_uuid", companion.toString());
        data.addProperty("body_id", incarnation.toString());
        data.addProperty("body_state", state);
        data.addProperty("text", state);
        return new Envelope("companion_state", data);
    }

    @Test void deathFailsTheActiveAndQueuedTasksBeforeAnotherModelTurn() {
        runner.handleS2c(life(body, "alive"));
        runner.start();
        long active = runner.submitTask("work");
        var queued = runner.askNext("queued work");
        client.drain();
        runner.handleS2c(life(body, "dead"));
        client.drain();
        assertEquals(0, runner.currentTaskId(), "a dead body must end the original task");
        assertTrue(queued.isCompletedExceptionally());
        assertEquals(0, JsonParser.parseString(runner.statusJson()).getAsJsonObject()
                .get("queued_tasks").getAsInt());
        var terminal = events.events().stream().filter(e -> e.get("ev").getAsString().equals("done")
                && e.get("task_id").getAsLong() == active).toList();
        assertEquals(1, terminal.size());
        assertEquals("failed", terminal.getFirst().get("status").getAsString());
        assertEquals(1, client.engine().responses.size());
    }

    @ParameterizedTest @EnumSource(AgentRunnerLifecycleTest.Wait.class)
    void everyWaitIsDrainedAndLateWorkCannotReachARebuiltSameUuidBody(AgentRunnerLifecycleTest.Wait wait) {
        runner.handleS2c(life(body, "alive"));
        runner.start();
        runner.submitTask("old work");
        var engine = client.engine();
        var move = new com.neko.mcbot.agentcore.llm.ToolCall("move", "move_to", "{\"x\":2,\"y\":63,\"z\":4}");
        long seq = -1;
        if (wait == AgentRunnerLifecycleTest.Wait.TOOL) {
            engine.tools(0, move);
            client.drain(); seq = client.lastTool().num("seq", -1);
        } else if (wait == AgentRunnerLifecycleTest.Wait.EARLY_ACCEPT || wait == AgentRunnerLifecycleTest.Wait.PARK) {
            if (wait == AgentRunnerLifecycleTest.Wait.EARLY_ACCEPT) engine.early(0, move);
            else engine.tools(0, move);
            client.drain(); seq = client.lastTool().num("seq", -1);
            runner.handleS2c(AgentRunnerLifecycleTest.ack(seq));
        } else if (wait == AgentRunnerLifecycleTest.Wait.QUESTION) {
            engine.tools(0, new com.neko.mcbot.agentcore.llm.ToolCall("ask", "ask_owner", "{\"text\":\"where?\"}"));
            client.drain();
        }
        var question = runner.questionSnapshot();
        runner.refreshCompanion();
        runner.handleS2c(life(body, "dead"));
        client.drain();
        var dead = JsonParser.parseString(runner.statusJson()).getAsJsonObject();
        for (String key : new String[]{"pending_tools", "pending_jobs", "pending_questions", "pending_asks", "current_task"})
            assertEquals(0, dead.get(key).getAsInt(), key);
        assertTrue(runner.askNext("during death").isCompletedExceptionally());
        assertFalse(runner.compactContext().join());
        runner.handleS2c(life(body, "alive"));
        assertTrue(runner.askNext("same corpse cannot revive").isCompletedExceptionally());
        var replacement = UUID.randomUUID();
        runner.handleS2c(life(replacement, "alive"));
        long next = runner.submitTask("fresh work");
        engine.early(0, move); engine.tools(0, move);
        runner.handleS2c(AgentRunnerLifecycleTest.result(seq));
        runner.handleS2c(AgentRunnerLifecycleTest.ack(seq));
        runner.handleS2c(AgentRunnerLifecycleTest.job(seq, "done"));
        if (question != null) assertFalse(runner.answerQuestion(question.id(), "late"));
        client.drain();
        assertEquals(next, runner.currentTaskId());
        assertEquals(2, engine.responses.size());
        engine.tools(1, move);
        client.drain();
        assertEquals(replacement.toString(), client.lastTool().str("body_id"));
        assertEquals(1, client.sent.stream().filter(e -> e.kind().equals("task_begin")
                && replacement.toString().equals(e.str("body_id"))).count());
    }

    @Test void queuedControlsInspectionsAndToolsCannotBeRetargetedAfterReconstruction() {
        runner.handleS2c(life(body, "alive"));
        runner.start();
        runner.submitTask("old work");
        runner.inspect("status");
        var old = runner.execute("move_to", "{\"x\":2,\"y\":63,\"z\":4}");
        runner.requestCancel();
        UUID next = UUID.randomUUID();
        runner.handleS2c(life(next, "alive"));
        client.drain();
        assertTrue(old.isDone());
        assertTrue(client.sent.isEmpty(), "old queued task controls, cancel and inspection must all be discarded");
        assertEquals(0, runner.currentTaskId());
    }

    @Test void deathDuringLongGoalEndsTheGoalAndNewWorkMustWaitForFreshObservation() {
        runner.handleS2c(life(body, "alive"));
        client.liveObservation = true;
        runner.start();
        runner.submitTask("long work");
        client.drain();
        var data = BoundedGatherTest.state(0);
        var result = new JsonObject();
        result.addProperty("seq", client.lastTool().num("seq", -1));
        result.addProperty("ok", true);
        result.add("data", data);
        runner.handleS2c(new Envelope("tool_result", result));
        client.drain();
        client.engine().tools(0, new com.neko.mcbot.agentcore.llm.ToolCall("goal", "set_goal",
                "{\"item\":\"dirt\",\"count\":3,\"lifecycle\":\"long_term\"}"));
        client.drain();
        runner.handleS2c(life(body, "dead"));
        runner.handleS2c(life(UUID.randomUUID(), "alive"));
        runner.submitTask("fresh work");
        client.drain();
        assertEquals(1, client.engine().responses.size(), "fresh status must precede any new model request");
        assertEquals("status", client.lastTool().str("tool"));
        assertNull(runner.taskBubbleSnapshot().progress());
    }

    @Test void programWaitingForAnAtomicActionCannotRunItsNextChildAfterDeath() {
        runner.handleS2c(life(body, "alive"));
        runner.start(); runner.submitTask("program work");
        client.engine().tools(0, new com.neko.mcbot.agentcore.llm.ToolCall("program", "run_program",
                "{\"code\":\"mc.call('wait',{seconds=2}); return mc.call('inventory',{})\"}"));
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(3);
        while (client.toolCount() == 0 && System.nanoTime() < deadline) {
            client.drain();
            java.util.concurrent.locks.LockSupport.parkNanos(100000);
        }
        assertEquals("wait", client.lastTool().str("tool"));
        long seq = client.lastTool().num("seq", -1);
        runner.handleS2c(AgentRunnerLifecycleTest.ack(seq));
        runner.handleS2c(life(body, "dead"));
        runner.handleS2c(life(UUID.randomUUID(), "alive"));
        runner.handleS2c(AgentRunnerLifecycleTest.job(seq, "done"));
        client.drain();
        assertEquals(1, client.toolCount());
        assertEquals(0, runner.currentTaskId());
        assertEquals(1, client.engine().responses.size());
    }
}
