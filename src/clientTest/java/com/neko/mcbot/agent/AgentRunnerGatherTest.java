package com.neko.mcbot.agent;

import com.google.gson.*;
import com.neko.mcbot.agentcore.llm.ToolCall;
import com.neko.mcbot.common.Envelope;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AgentRunnerGatherTest {
    private final OfflineAgentClient client = new OfflineAgentClient();
    private final AgentRunner runner = client.create();
    @AfterEach void close() { runner.close(); client.drain(); }
    private void reply(JsonObject data) {
        reply(true, "world receipt", data);
    }
    private void reply(boolean ok, String feedback, JsonObject data) {
        var body = new JsonObject();
        body.addProperty("seq", client.lastTool().num("seq", -1));
        body.addProperty("ok", ok);
        body.addProperty("feedback", feedback);
        body.add("data", data);
        runner.handleS2c(new Envelope("tool_result", body));
        client.drain();
    }
    private void start() {
        client.liveObservation = true;
        runner.start();
        runner.submitTask("采集新增1个泥土");
        client.drain();
        reply(BoundedGatherTest.state(0));
        client.engine().tools(0, new ToolCall("g", "gather",
                "{\"item\":\"dirt\",\"targets\":[\"dirt\"],\"count\":1}"));
        client.drain();
    }
    private void reachMining() {
        start();
        assertEquals("status", client.lastTool().str("tool"));
        reply(BoundedGatherTest.state(0));
        reply(JsonParser.parseString("{\"item_targets\":[]}").getAsJsonObject());
        var targets = new JsonArray();
        targets.add(BoundedGatherTest.block(true));
        var data = new JsonObject();
        data.add("targets", targets);
        reply(data);
        assertEquals("break_block", client.lastTool().str("tool"));
    }

    @Test void workRecoveryReusesOneModelTurnAndWaitsForMovementTerminalBeforeMining() {
        start();
        reply(BoundedGatherTest.state(0));
        reply(JsonParser.parseString("{\"item_targets\":[]}").getAsJsonObject());
        var target = BoundedGatherTest.block(false);
        target.getAsJsonArray("stand_positions").add(JsonParser.parseString("{\"x\":3,\"y\":90,\"z\":0}"));
        var targets = new JsonArray();
        targets.add(target);
        var found = new JsonObject();
        found.add("targets", targets);
        reply(found);
        assertEquals("move_to", client.lastTool().str("tool"));
        assertTrue(client.lastTool().obj("args").get("exact").getAsBoolean());
        assertEquals(2, client.lastTool().obj("args").getAsJsonObject("work_target").get("x").getAsInt());
        reply(false, "TARGET_BLOCKED:arrival scene changed", new JsonObject());
        assertEquals("move_to", client.lastTool().str("tool"));
        assertEquals(3, client.lastTool().obj("args").get("x").getAsInt());
        long seq = client.lastTool().num("seq", -1);
        var ack = new JsonObject();
        ack.addProperty("seq", seq);
        ack.addProperty("job_id", "second-stance");
        ack.addProperty("cap_ticks", 3600);
        ack.addProperty("tool", "move_to");
        runner.handleS2c(new Envelope("job_ack", ack));
        client.drain();
        assertEquals("move_to", client.lastTool().str("tool"));
        assertEquals(1, client.engine().responses.size());
        var terminal = new JsonObject();
        terminal.addProperty("seq", seq);
        terminal.addProperty("job_id", "second-stance");
        terminal.addProperty("phase", "done");
        terminal.addProperty("text", "arrived and can work");
        terminal.add("data", JsonParser.parseString("{\"arrived\":true,\"can_work_here\":true}"));
        runner.handleS2c(new Envelope("job_event", terminal));
        client.drain();
        assertEquals("break_block", client.lastTool().str("tool"));
        assertEquals("minecraft:dirt", client.lastTool().obj("args").get("expected_block").getAsString());
        assertEquals(1, client.engine().responses.size(), "local recovery must not ask the model again");
        reply(JsonParser.parseString("{\"removed\":true}").getAsJsonObject());
        reply(BoundedGatherTest.state(1));
        reply(BoundedGatherTest.state(1));
        assertEquals(2, client.engine().responses.size());
    }

    @Test void missingLocalMaterialExploresAndResumesGatherWithinOneModelTurn() {
        start();
        reply(BoundedGatherTest.state(0));
        reply(JsonParser.parseString("{\"item_targets\":[]}").getAsJsonObject());
        reply(JsonParser.parseString("{\"targets\":[],\"next_offset\":-1,"
                + "\"exploration_points\":[{\"x\":8,\"y\":90,\"z\":0}]}").getAsJsonObject());
        assertEquals("move_to", client.lastTool().str("tool"));
        assertTrue(client.lastTool().obj("args").get("walk_only").getAsBoolean());
        reply(JsonParser.parseString("{\"arrived\":true}").getAsJsonObject());
        var actual = BoundedGatherTest.state(0);
        actual.addProperty("x", 8);
        reply(actual);
        reply(JsonParser.parseString("{\"item_targets\":[]}").getAsJsonObject());
        var target = BoundedGatherTest.block(true);
        target.addProperty("x", 10);
        var found = new JsonObject();
        var targets = new JsonArray(); targets.add(target); found.add("targets", targets);
        reply(found);
        assertEquals("break_block", client.lastTool().str("tool"));
        assertEquals(1, client.engine().responses.size());
        reply(JsonParser.parseString("{\"removed\":true}").getAsJsonObject());
        actual = BoundedGatherTest.state(1); actual.addProperty("x", 8);
        reply(actual);
        reply(actual);
        assertEquals(2, client.engine().responses.size());
    }

    @Test void independentExplorationKeepsTaskBudgetAndCancellationDiscardsLateMovement() {
        runner.start();
        runner.submitTask("寻找新的观察区域");
        client.drain();
        var first = runner.execute("explore", "{\"max_steps\":1}");
        client.drain();
        reply(BoundedGatherTest.state(0));
        reply(JsonParser.parseString("{\"exploration_points\":[{\"x\":8,\"y\":90,\"z\":0}]}").getAsJsonObject());
        reply(new JsonObject());
        var atEight = BoundedGatherTest.state(0); atEight.addProperty("x", 8);
        reply(atEight);
        assertTrue(first.join().ok());
        assertTrue(runner.execute("explore", "{}").join().feedback().startsWith("DENIED:"),
                "default parameters cannot widen the established task budget");
        var exhausted = runner.execute("explore", "{\"max_steps\":1}");
        client.drain(); reply(atEight);
        assertTrue(exhausted.join().feedback().startsWith("EXPLORATION_LIMIT:"));
        runner.cancelTask(0); client.drain();
        runner.submitTask("另一个寻找任务"); client.drain();
        var next = runner.execute("explore", "{\"max_steps\":1}");
        client.drain(); reply(atEight);
        reply(JsonParser.parseString("{\"exploration_points\":[{\"x\":16,\"y\":90,\"z\":0}]}").getAsJsonObject());
        assertEquals("move_to", client.lastTool().str("tool"));
        assertFalse(next.isDone());
        long seq = client.lastTool().num("seq", -1);
        runner.cancelTask(0); client.drain();
        long calls = client.toolCount();
        var late = new JsonObject();
        late.addProperty("seq", seq); late.addProperty("ok", true); late.addProperty("feedback", "late");
        late.add("data", new JsonObject());
        runner.handleS2c(new Envelope("tool_result", late)); client.drain();
        assertFalse(next.join().ok());
        assertEquals(calls, client.toolCount());
    }

    @Test void childAcceptanceWaitsForRealTerminalAndCompletionWaitsForGoalVerification() {
        reachMining();
        var ack = new JsonObject();
        ack.addProperty("seq", client.lastTool().num("seq", -1));
        ack.addProperty("job_id", "child1");
        ack.addProperty("cap_ticks", 1200);
        ack.addProperty("tool", "break_block");
        runner.handleS2c(new Envelope("job_ack", ack));
        client.drain();
        assertEquals("break_block", client.lastTool().str("tool"));
        assertEquals(1, client.engine().responses.size());
        var done = new JsonObject();
        done.addProperty("seq", ack.get("seq").getAsLong());
        done.addProperty("job_id", "child1");
        done.addProperty("phase", "done");
        done.addProperty("text", "removed; ground drops remain");
        done.add("data", JsonParser.parseString("{\"removed\":true}"));
        runner.handleS2c(new Envelope("job_event", done));
        client.drain();
        assertEquals("status", client.lastTool().str("tool"));
        assertEquals(1, client.engine().responses.size());
        reply(BoundedGatherTest.state(1));
        assertEquals("status", client.lastTool().str("tool"), "next model turn needs fresh observation");
        reply(BoundedGatherTest.state(1));
        assertEquals(2, client.engine().responses.size());
        client.engine().reply(1, "finished");
        client.drain();
        assertEquals("status", client.lastTool().str("tool"), "done requires a final physical count");
        reply(BoundedGatherTest.state(1));
        assertEquals(0, JsonParser.parseString(runner.statusJson()).getAsJsonObject().get("current_task").getAsInt());
    }

    @Test void occluderClearingUsesOneModelTurnAndItsRealTerminalBeforeInventoryVerification() {
        start();
        reply(BoundedGatherTest.state(0));
        reply(JsonParser.parseString("{\"item_targets\":[]}").getAsJsonObject());
        var target = BoundedGatherTest.block(false);
        target.addProperty("can_clear_here", true);
        var targets = new JsonArray();
        targets.add(target);
        var found = new JsonObject();
        found.add("targets", targets);
        reply(found);
        assertEquals("break_block", client.lastTool().str("tool"));
        assertTrue(client.lastTool().obj("args").get("clear_obstructions").getAsBoolean());
        assertEquals(3, client.lastTool().obj("args").get("clear_limit").getAsInt());
        reply(JsonParser.parseString("{\"removed\":true,\"cleared_blocks\":2}").getAsJsonObject());
        assertEquals("status", client.lastTool().str("tool"));
        assertEquals(1, client.engine().responses.size());
        reply(BoundedGatherTest.state(1));
        reply(BoundedGatherTest.state(1));
        assertEquals(2, client.engine().responses.size());
    }

    @Test void clearingConsentReturnsTheOriginalServerProposalToTheModel() {
        reachMining();
        reply(false, "NEED_CONFIRM:authorization_id=clear-plan", JsonParser.parseString(
                "{\"removed\":false,\"cleared_blocks\":0,\"authorization_id\":\"clear-plan\","
                        + "\"authorization_summary\":\"server cells\"}").getAsJsonObject());
        assertEquals("status", client.lastTool().str("tool"));
        reply(BoundedGatherTest.state(0));
        assertEquals(2, client.engine().responses.size());
        assertTrue(client.engine().histories.getLast().stream().anyMatch(message ->
                message instanceof com.neko.mcbot.agentcore.llm.Msg.Tool tool
                        && tool.content().contains("clear-plan")));
    }

    @Test void cancellingLocalGatherPreventsAnyLateChildFromContinuing() {
        reachMining();
        long seq = client.lastTool().num("seq", -1);
        int modelCalls = client.engine().responses.size();
        assertTrue(runner.cancelTask(0));
        client.drain();
        long tools = client.toolCount();
        var body = new JsonObject();
        body.addProperty("seq", seq);
        body.addProperty("ok", true);
        body.addProperty("feedback", "late");
        body.add("data", JsonParser.parseString("{\"removed\":true}"));
        runner.handleS2c(new Envelope("tool_result", body));
        client.drain();
        assertEquals(tools, client.toolCount());
        assertEquals(modelCalls, client.engine().responses.size());
        assertEquals(0, JsonParser.parseString(runner.statusJson()).getAsJsonObject().get("pending_tools").getAsInt());
    }

    @Test void aMaterialGatherUsesFreshStartAndCannotCompleteTheFinalCraftedItemGoal() {
        client.liveObservation = true;
        runner.start();
        runner.submitTask("制作一把钻石剑");
        client.drain();
        reply(BoundedGatherTest.state(0));
        client.engine().tools(0, new ToolCall("final", "set_goal",
                "{\"item\":\"diamond_sword\",\"count\":1}"));
        client.drain();
        reply(BoundedGatherTest.state(5));
        client.engine().tools(1, new ToolCall("material", "gather",
                "{\"item\":\"dirt\",\"targets\":[\"dirt\"],\"count\":1,\"scope\":\"subtask\"}"));
        client.drain();
        assertEquals("status", client.lastTool().str("tool"));
        reply(BoundedGatherTest.state(5));
        assertEquals("scan_area", client.lastTool().str("tool"), "old acquired materials must not satisfy this subtask");
        reply(JsonParser.parseString("{\"item_targets\":[]}").getAsJsonObject());
        var targets = new JsonArray();
        targets.add(BoundedGatherTest.block(true));
        var data = new JsonObject();
        data.add("targets", targets);
        reply(data);
        assertEquals("break_block", client.lastTool().str("tool"));
        reply(JsonParser.parseString("{\"removed\":true}").getAsJsonObject());
        reply(BoundedGatherTest.state(6));
        reply(BoundedGatherTest.state(6));
        assertEquals(3, client.engine().responses.size());
        client.engine().reply(2, "finished");
        client.drain();
        reply(BoundedGatherTest.state(6));
        assertEquals("status", client.lastTool().str("tool"), "continuation reobserves the current body");
        reply(BoundedGatherTest.state(6));
        assertEquals(4, client.engine().responses.size(), "material success must continue the owner's final goal");
        assertNotEquals(0, JsonParser.parseString(runner.statusJson()).getAsJsonObject().get("current_task").getAsLong());
        assertTrue(client.engine().histories.getLast().stream().anyMatch(m ->
                m instanceof com.neko.mcbot.agentcore.llm.Msg.Nudge n && n.text().contains("minecraft:diamond_sword")));
        client.engine().reply(3, "finished");
        client.drain();
        var completed = BoundedGatherTest.state(6);
        var sword = completed.getAsJsonObject("inventory").getAsJsonArray("slots").get(1).getAsJsonObject();
        sword.addProperty("item", "minecraft:diamond_sword");
        sword.addProperty("count", 1);
        reply(completed);
        assertEquals(0, JsonParser.parseString(runner.statusJson()).getAsJsonObject().get("current_task").getAsLong());
    }
}
