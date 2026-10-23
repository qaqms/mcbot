package com.neko.mcbot.agent;

import com.google.gson.JsonObject;
import com.neko.mcbot.agentcore.llm.Msg;
import com.neko.mcbot.agentcore.llm.ToolCall;
import com.neko.mcbot.agentcore.loop.ToolExecutor.ToolOutcome;
import com.neko.mcbot.common.Envelope;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(10)
class AgentRunnerFailureMemoryTest {
    @Test void originalTaskUsesFreshTerrainEvidenceAcrossDirectAndProgramEntryPoints() {
        long task = start();
        var state = BoundedGatherTest.state(0);
        state.add("spatial_facts", com.google.gson.JsonParser.parseString(
                "{\"dimension\":\"minecraft:overworld\",\"chunks\":{\"0,0\":\"chunk:1\"}}"));
        runner.execute("status", "{\"details\":true}"); client.drain();
        runner.handleS2c(receipt(client.lastTool().num("seq", -1), true, "fresh", state));
        exhaust();
        var denied = runner.execute("move_to", POINT); client.drain();
        assertFalse(denied.isDone());
        fail(6);
        assertTrue(denied.join().feedback().startsWith("NO_PATH:"));
        state.getAsJsonObject("spatial_facts").getAsJsonObject("chunks").addProperty("0,0", "chunk:2");
        runner.execute("status", "{\"details\":true}"); client.drain();
        runner.handleS2c(receipt(client.lastTool().num("seq", -1), true, "fresh terrain", state));
        var program = new JsonObject();
        program.addProperty("code", "return mc.try_call('move_to',{x=2,y=90,z=0})");
        var result = runner.execute("run_program", program.toString());
        until(() -> client.lastTool().str("tool").equals("move_to"));
        fail(99);
        until(result::isDone);
        assertEquals(task, runner.currentTaskId());
        assertEquals(1, client.sent.stream().filter(e -> e.kind().equals("task_begin")).count());
        assertFalse(result.join().feedback().contains("REPEATED_FAILURE:"));
    }
    private static final String POINT = "{\"x\":2,\"y\":90,\"z\":0}";
    private final OfflineAgentClient client = new OfflineAgentClient();
    private final AgentRunner runner = client.create();

    @AfterEach void close() { runner.close(); client.drain(); }

    private long start() {
        runner.start();
        long task = runner.submitTask("approach the worksite");
        client.drain();
        return task;
    }

    private void until(BooleanSupplier condition) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            client.drain();
            java.util.concurrent.locks.LockSupport.parkNanos(100000);
        }
        assertTrue(condition.getAsBoolean(), "runner did not settle");
    }

    private Envelope receipt(long seq, boolean ok, String feedback, JsonObject data) {
        var body = new JsonObject();
        body.addProperty("seq", seq);
        body.addProperty("ok", ok);
        body.addProperty("feedback", feedback);
        body.add("data", data);
        return new Envelope("tool_result", body);
    }

    private void fail(int n) {
        runner.handleS2c(receipt(client.lastTool().num("seq", -1), false,
                "NO_PATH:8001 nodes; elapsed=" + n, new JsonObject()));
        client.drain();
    }

    private void exhaust() {
        for (int i = 0; i < 5; i++) {
            var result = runner.execute("move_to", POINT);
            client.drain();
            fail(i);
            assertFalse(result.join().ok());
        }
    }

    @Test void directAndProgramAttemptsContinueBeyondFiveRealFailures() {
        start();
        for (int i = 0; i < 6; i++) {
            if (i % 2 == 0) {
                client.engine().tools(i, new ToolCall("attempt" + i, "move_to", POINT));
            } else {
                var program = new JsonObject();
                program.addProperty("code", "return mc.try_call('move_to',{z=0,y=90,x=2})");
                client.engine().tools(i, new ToolCall("attempt" + i, "run_program", program.toString()));
            }
            client.drain();
            long expected = i + 1;
            until(() -> client.toolCount() == expected);
            fail(i);
            int nextTurn = i + 2;
            until(() -> client.engine().responses.size() == nextTurn);
        }
        assertEquals(6, client.toolCount(), "the sixth attempt must receive a real server result");
        var result = client.engine().histories.getLast().stream().filter(Msg.Tool.class::isInstance)
                .map(Msg.Tool.class::cast).filter(t -> t.callId().equals("attempt5")).findFirst().orElseThrow();
        assertTrue(result.ok());
        assertTrue(result.content().contains("NO_PATH:"), result.content());
    }

    @Test void jobAcknowledgmentsProgressAndDuplicateTerminalsDoNotSpendExtraAttempts() {
        start();
        for (int i = 0; i < 5; i++) {
            var result = runner.execute("move_to", POINT);
            client.drain();
            long seq = client.lastTool().num("seq", -1);
            String job = "move" + i;
            var body = new JsonObject();
            body.addProperty("seq", seq);
            body.addProperty("job_id", job);
            body.addProperty("cap_ticks", 3600);
            runner.handleS2c(new Envelope("job_ack", body));
            assertTrue(result.join().accepted());
            body.addProperty("phase", "progress");
            body.addProperty("text", "NO_PATH:intermediate text is not a terminal");
            runner.handleS2c(new Envelope("job_event", body));
            body.addProperty("phase", "failed");
            body.addProperty("text", "NO_PATH:elapsed=" + i);
            runner.handleS2c(new Envelope("job_event", body));
            runner.handleS2c(new Envelope("job_event", body));
        }
        var blocked = runner.execute("move_to", POINT);
        client.drain();
        assertEquals(6, client.toolCount());
        assertFalse(blocked.isDone());
        fail(6);
        assertTrue(blocked.join().feedback().startsWith("NO_PATH:"));
    }

    @Test void freshBodyFactsPermitReevaluationButClockAndNoopDoNot() {
        start();
        var state = BoundedGatherTest.state(0);
        var observation = runner.execute("status", "{\"details\":true}");
        client.drain();
        runner.handleS2c(receipt(client.lastTool().num("seq", -1), true, "fresh", state));
        assertTrue(observation.join().ok());
        exhaust();
        state.addProperty("game_tick", 100);
        state.addProperty("hp", 19);
        var check = runner.execute("status", "{\"details\":true}");
        client.drain();
        runner.handleS2c(receipt(client.lastTool().num("seq", -1), true, "fresh", state));
        check.join();
        var noop = runner.execute("craft", "{\"item\":\"stick\",\"query\":true}");
        client.drain();
        runner.handleS2c(receipt(client.lastTool().num("seq", -1), true, "query", new JsonObject()));
        noop.join();
        var blocked = runner.execute("move_to", POINT);
        client.drain();
        assertFalse(blocked.isDone(), "failure evidence must not prohibit dispatch");
        fail(6);
        assertTrue(blocked.join().feedback().startsWith("NO_PATH:"));
        state.addProperty("x", state.get("x").getAsInt() + 1);
        runner.execute("status", "{\"details\":true}");
        client.drain();
        runner.handleS2c(receipt(client.lastTool().num("seq", -1), true, "new location", state));
        var retry = runner.execute("move_to", POINT);
        client.drain();
        assertFalse(retry.isDone(), "changed full body facts allow one new real attempt");
        fail(99);
    }

    @Test void cancelledOldResultsCannotPoisonOrReplenishTheNextTask() {
        long task = start();
        exhaust();
        var old = runner.execute("move_to", "{\"x\":3,\"y\":90,\"z\":0}");
        client.drain();
        long seq = client.lastTool().num("seq", -1);
        assertTrue(runner.cancelTask(task));
        client.drain();
        assertTrue(old.isDone());
        runner.submitTask("new approach");
        client.drain();
        exhaust();
        var changed = new JsonObject();
        changed.addProperty("removed_blocks", 1);
        runner.handleS2c(receipt(seq, false, "PARTIAL:old terrain effect", changed));
        var blocked = runner.execute("move_to", POINT);
        client.drain();
        assertFalse(blocked.isDone());
        fail(6);
        assertTrue(blocked.join().feedback().startsWith("NO_PATH:"));
        assertEquals(12, client.toolCount());
    }

    @Test void nestedWorkflowUsesTheSamePrimitiveLedgerAndCannotContinueAfterRefusal() {
        start();
        String plan = "{\"steps\":[{\"tool\":\"break_block\",\"args\":" + POINT
                + "},{\"tool\":\"inventory\",\"args\":{}}]}";
        for (int i = 0; i < 5; i++) {
            var result = runner.execute(i % 2 == 0 ? "workflow" : "break_block", i % 2 == 0 ? plan : POINT);
            client.drain();
            assertEquals(i + 1, client.toolCount());
            runner.handleS2c(receipt(client.lastTool().num("seq", -1), false,
                    "TARGET_BLOCKED:elapsed=" + i, new JsonObject()));
            client.drain();
            assertTrue(result.isDone());
            assertFalse(result.join().ok());
        }
        var blocked = runner.execute("workflow", plan);
        client.drain();
        assertFalse(blocked.isDone());
        runner.handleS2c(receipt(client.lastTool().num("seq", -1), false,
                "TARGET_BLOCKED:actual sixth failure", new JsonObject()));
        client.drain();
        assertTrue(blocked.isDone());
        assertTrue(blocked.join().feedback().contains("TARGET_BLOCKED:actual sixth failure"));
        assertEquals(6, client.toolCount());
        assertTrue(client.sent.stream().noneMatch(e -> e.kind().equals("tool_call") && e.str("tool").equals("inventory")));
    }

    @Test void realPartialTerrainEffectAllowsReevaluationAndPreservesTheOriginalReceipt() {
        start();
        exhaust();
        var edit = runner.execute("break_block", POINT);
        client.drain();
        var data = new JsonObject();
        data.addProperty("cleared_blocks", 1);
        runner.handleS2c(receipt(client.lastTool().num("seq", -1), false, "PARTIAL:cleared one", data));
        client.drain();
        assertEquals(new ToolOutcome(false, "PARTIAL:cleared one", data), edit.join());
        var retry = runner.execute("move_to", POINT);
        client.drain();
        assertEquals(7, client.toolCount());
        assertFalse(retry.isDone());
        fail(9);
    }

    private void body(String uuid) {
        var data = new JsonObject();
        data.addProperty("companion", "fixture");
        data.addProperty("companion_uuid", uuid);
        runner.handleS2c(new Envelope("companion_state", data));
        client.drain();
    }

    @Test void sameNameNewBodyRejectsQueuedOldWorkAndIgnoresOldEffects() {
        start();
        body("00000000-0000-0000-0000-000000000001");
        var old = runner.execute("break_block", POINT);
        client.drain();
        long seq = client.lastTool().num("seq", -1);
        var queued = runner.execute("move_to", POINT);
        body("00000000-0000-0000-0000-000000000002");
        assertTrue(queued.isDone());
        assertTrue(queued.join().feedback().startsWith("CANCELLED:"));
        exhaust();
        var changed = new JsonObject();
        changed.addProperty("removed", true);
        runner.handleS2c(receipt(seq, true, "old removal", changed));
        assertTrue(old.isDone());
        var blocked = runner.execute("move_to", POINT);
        client.drain();
        assertFalse(blocked.isDone());
        fail(6);
        assertTrue(blocked.join().feedback().startsWith("NO_PATH:"));
        assertEquals(7, client.toolCount());
    }

    @Test void aLongGoalCheckpointRetainsFailureEvidenceWhenBodyFactsAreUnchanged() {
        client.liveObservation = true;
        start();
        var state = BoundedGatherTest.state(0);
        runner.handleS2c(receipt(client.lastTool().num("seq", -1), true, "initial", state));
        client.drain();
        long task = runner.currentTaskId();
        client.engine().tools(0, new ToolCall("goal", "set_goal",
                "{\"item\":\"dirt\",\"count\":3,\"lifecycle\":\"long_term\"}"));
        client.drain();
        runner.handleS2c(receipt(client.lastTool().num("seq", -1), true, "goal state", state));
        client.drain();
        exhaust();
        client.engine().reply(1, "end of this bounded stage");
        client.drain();
        runner.handleS2c(receipt(client.lastTool().num("seq", -1), true, "checkpoint", state));
        client.drain();
        runner.handleS2c(receipt(client.lastTool().num("seq", -1), true, "next stage", state));
        client.drain();
        assertEquals(task, runner.currentTaskId());
        assertEquals(3, client.engine().responses.size());
        var blocked = runner.execute("move_to", POINT);
        client.drain();
        assertFalse(blocked.isDone());
        fail(6);
        assertTrue(blocked.join().feedback().startsWith("NO_PATH:"));
        assertEquals(1, client.sent.stream().filter(e -> e.kind().equals("task_begin")).count());
    }

    private void gatherToMining() {
        var state = BoundedGatherTest.state(0);
        runner.handleS2c(receipt(client.lastTool().num("seq", -1), true, "body", state));
        client.drain();
        var empty = new JsonObject();
        empty.add("item_targets", new com.google.gson.JsonArray());
        runner.handleS2c(receipt(client.lastTool().num("seq", -1), true, "drops", empty));
        client.drain();
        var found = new JsonObject();
        var blocks = new com.google.gson.JsonArray();
        blocks.add(BoundedGatherTest.block(true));
        found.add("targets", blocks);
        runner.handleS2c(receipt(client.lastTool().num("seq", -1), true, "resource", found));
        client.drain();
    }

    @Test void gatherContinuesToUseRealPrimitiveResultsBeyondFiveFailures() {
        start();
        String args = "{\"item\":\"dirt\",\"targets\":[\"dirt\"],\"count\":1,"
                + "\"scope\":\"subtask\",\"explore_steps\":0}";
        var first = runner.execute("gather", args);
        client.drain();
        gatherToMining();
        assertEquals("break_block", client.lastTool().str("tool"));
        String primitive = client.lastTool().obj("args").toString();
        fail(0);
        assertTrue(first.isDone());
        assertFalse(first.join().ok());
        for (int i = 1; i < 5; i++) {
            var direct = runner.execute("break_block", primitive);
            client.drain();
            fail(i);
            assertFalse(direct.join().ok());
        }
        long before = client.toolCount();
        var next = runner.execute("gather", args);
        client.drain();
        gatherToMining();
        assertFalse(next.isDone());
        fail(6);
        assertTrue(next.isDone());
        assertTrue(next.join().feedback().contains("NO_PATH:"), next.join().feedback());
        assertEquals(before + 4, client.toolCount(), "fresh queries and the actual dig all run");
    }
}
