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
        var alternative = target.deepCopy();
        alternative.addProperty("x", 4);
        targets.add(alternative);
        var found = new JsonObject();
        found.add("targets", targets);
        reply(found);
        assertEquals("move_to", client.lastTool().str("tool"));
        assertTrue(client.lastTool().obj("args").get("exact").getAsBoolean());
        assertEquals("work", client.lastTool().obj("args").get("arrival").getAsString());
        assertEquals(2, client.lastTool().obj("args").getAsJsonObject("work_target").get("x").getAsInt());
        reply(false, "TARGET_BLOCKED:arrival scene changed", new JsonObject());
        assertEquals("status", client.lastTool().str("tool"));
        assertEquals(1, client.engine().responses.size());
        reply(BoundedGatherTest.state(0));
        assertEquals("scan_area", client.lastTool().str("tool"));
        reply(JsonParser.parseString("{\"item_targets\":[]}").getAsJsonObject());
        assertEquals("find_resource", client.lastTool().str("tool"));
        reply(found);
        assertEquals("move_to", client.lastTool().str("tool"));
        assertEquals(4, client.lastTool().obj("args").get("x").getAsInt(),
                "a failed work goal advances to another resource, not another exact stand for the same block");
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
        assertEquals("status", client.lastTool().str("tool"));
        var actual = BoundedGatherTest.state(0);
        actual.addProperty("x", 3.5);
        actual.addProperty("z", .5);
        reply(actual);
        assertEquals("break_block", client.lastTool().str("tool"));
        assertEquals("minecraft:dirt", client.lastTool().obj("args").get("expected_block").getAsString());
        assertEquals(1, client.engine().responses.size(), "local recovery must not ask the model again");
        reply(JsonParser.parseString("{\"removed\":true}").getAsJsonObject());
        actual = BoundedGatherTest.state(1);
        actual.addProperty("x", 3.5);
        actual.addProperty("z", .5);
        reply(actual);
        reply(actual);
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

    @Test void sameStanceBatchWaitsForEachTrueTerminalAndStaysInsideOneModelTurn() {
        client.liveObservation = true;
        runner.start();
        runner.submitTask("采集新增2个泥土");
        client.drain();
        reply(BoundedGatherTest.state(0));
        client.engine().tools(0, new ToolCall("batch", "gather",
                "{\"item\":\"dirt\",\"targets\":[\"dirt\"],\"count\":2}"));
        client.drain();
        reply(BoundedGatherTest.state(0));
        reply(JsonParser.parseString("{\"item_targets\":[]}").getAsJsonObject());
        var first = BoundedGatherTest.block(true);
        var second = first.deepCopy(); second.addProperty("x", 3);
        var found = new JsonObject();
        var targets = new JsonArray(); targets.add(first); targets.add(second);
        found.add("targets", targets);
        reply(found);
        assertEquals("break_block", client.lastTool().str("tool"));
        long seq = client.lastTool().num("seq", -1);
        var ack = new JsonObject();
        ack.addProperty("seq", seq); ack.addProperty("job_id", "batch-first");
        ack.addProperty("cap_ticks", 1200); ack.addProperty("tool", "break_block");
        runner.handleS2c(new Envelope("job_ack", ack)); client.drain();
        assertEquals("break_block", client.lastTool().str("tool"));
        assertEquals(1, client.engine().responses.size());
        var terminal = new JsonObject();
        terminal.addProperty("seq", seq); terminal.addProperty("job_id", "batch-first");
        terminal.addProperty("phase", "done"); terminal.addProperty("text", "actual removal");
        terminal.add("data", JsonParser.parseString("{\"removed\":true}"));
        runner.handleS2c(new Envelope("job_event", terminal)); client.drain();
        assertEquals("status", client.lastTool().str("tool"));
        reply(BoundedGatherTest.state(1));
        assertEquals("break_block", client.lastTool().str("tool"));
        assertEquals(3, client.lastTool().obj("args").get("x").getAsInt());
        assertEquals(1, client.engine().responses.size());
        reply(JsonParser.parseString("{\"removed\":true}").getAsJsonObject());
        reply(BoundedGatherTest.state(2));
        reply(BoundedGatherTest.state(2));
        assertEquals(2, client.engine().responses.size());
    }

    @Test void disappearingPickupKeepsTheModelParkedUntilFreshInventoryAndContinuesLocally() {
        disappearingPickup(false);
    }

    @Test void disappearingPickupCanSatisfyTheGoalOnlyAfterIndependentInventoryVerification() {
        disappearingPickup(true);
    }

    private void disappearingPickup(boolean pickedUp) {
        start();
        reply(BoundedGatherTest.state(0));
        reply(JsonParser.parseString("{\"item_targets\":[{\"x\":2,\"y\":90,\"z\":0,"
                + "\"item\":\"minecraft:dirt\",\"target_uuid\":\"cbe003b9-481c-454e-8656-d4532d88a5ab\","
                + "\"pickup_allowed\":true,\"can_touch_here\":false,\"stand_positions\":[]}]}").getAsJsonObject());
        assertEquals("move_to", client.lastTool().str("tool"));
        int turns = client.engine().responses.size();
        long seq = client.lastTool().num("seq", -1);
        var ack = new JsonObject();
        ack.addProperty("seq", seq); ack.addProperty("job_id", "pickup");
        ack.addProperty("cap_ticks", -1); ack.addProperty("tool", "move_to");
        runner.handleS2c(new Envelope("job_ack", ack)); client.drain();
        assertEquals("move_to", client.lastTool().str("tool"));
        assertEquals(turns, client.engine().responses.size());
        var terminal = new JsonObject();
        terminal.addProperty("seq", seq); terminal.addProperty("job_id", "pickup");
        terminal.addProperty("phase", "failed"); terminal.addProperty("text", "TARGET_LOST:drop is gone");
        terminal.add("data", JsonParser.parseString("{\"pickup_target_missing\":true,"
                + "\"target_uuid\":\"cbe003b9-481c-454e-8656-d4532d88a5ab\",\"removed_blocks\":0}"));
        runner.handleS2c(new Envelope("job_event", terminal)); client.drain();
        assertEquals("status", client.lastTool().str("tool"));
        assertEquals(turns, client.engine().responses.size(), "disappearance is reconciled within this gather");
        if (pickedUp) {
            reply(BoundedGatherTest.state(1));
            assertEquals("status", client.lastTool().str("tool"), "outer task independently verifies its goal");
            reply(BoundedGatherTest.state(1));
            assertEquals(turns + 1, client.engine().responses.size());
        } else {
            reply(BoundedGatherTest.state(0));
            assertEquals("scan_area", client.lastTool().str("tool"));
            reply(JsonParser.parseString("{\"item_targets\":[]}").getAsJsonObject());
            assertEquals("find_resource", client.lastTool().str("tool"));
            assertEquals(turns, client.engine().responses.size());
        }
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
        var changed = runner.execute("explore", "{\"max_steps\":8}").join();
        assertTrue(changed.feedback().startsWith("DENIED:"),
                "explicit parameters cannot widen the established task budget");
        assertEquals(1, changed.data().get("exploration_max_steps").getAsInt());
        assertEquals(1, changed.data().get("exploration_attempts").getAsInt());
        assertEquals(0, changed.data().get("exploration_remaining_steps").getAsInt());
        assertTrue(changed.feedback().contains("省略探索参数"));
        var exhausted = runner.execute("explore", "{}");
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

    @Test void exploreAndRepeatedMaterialGatherShareVisitedGroundAndOneTaskMovementBudget() {
        runner.start();
        runner.submitTask("look for materials");
        client.drain();
        var first = runner.execute("explore", "{\"max_steps\":2}");
        client.drain();
        reply(BoundedGatherTest.state(0));
        reply(JsonParser.parseString("{\"exploration_points\":[{\"x\":8,\"y\":90,\"z\":0}]}").getAsJsonObject());
        reply(new JsonObject());
        var here = BoundedGatherTest.state(0); here.addProperty("x", 8);
        reply(here);
        assertTrue(first.join().ok());
        String material = "{\"item\":\"dirt\",\"targets\":[\"dirt\"],\"count\":1,\"scope\":\"subtask\"}";
        var gathering = runner.execute("gather", material);
        client.drain(); reply(here);
        reply(JsonParser.parseString("{\"item_targets\":[]}").getAsJsonObject());
        var empty = JsonParser.parseString("{\"targets\":[],\"next_offset\":-1,"
                + "\"exploration_points\":[{\"x\":0,\"y\":90,\"z\":0},{\"x\":16,\"y\":90,\"z\":0}]}").getAsJsonObject();
        reply(empty);
        assertEquals("move_to", client.lastTool().str("tool"));
        assertEquals(16, client.lastTool().obj("args").get("x").getAsInt(), "do not return to explore's departure");
        reply(new JsonObject());
        here.addProperty("x", 16);
        reply(here);
        reply(JsonParser.parseString("{\"item_targets\":[]}").getAsJsonObject());
        reply(empty);
        assertTrue(gathering.join().feedback().startsWith("EXPLORATION_LIMIT:"));
        assertEquals(2, gathering.join().data().get("exploration_attempts").getAsInt());
        long moves = client.sent.stream().filter(e -> "move_to".equals(e.str("tool"))).count();
        var again = runner.execute("gather", material);
        client.drain(); reply(here);
        reply(JsonParser.parseString("{\"item_targets\":[]}").getAsJsonObject());
        reply(empty);
        assertTrue(again.join().feedback().startsWith("EXPLORATION_LIMIT:"));
        var exhausted = runner.execute("explore", "{}");
        client.drain(); reply(here);
        assertTrue(exhausted.join().feedback().startsWith("EXPLORATION_LIMIT:"));
        assertEquals(moves, client.sent.stream().filter(e -> "move_to".equals(e.str("tool"))).count());
    }

    @Test void explicitLocalGatherAtANewWorksiteKeepsTheRunnersOriginalExplorationLedger() {
        runner.start();
        runner.submitTask("在不同工区复用近处采集");
        client.drain();
        var trip = runner.execute("explore", "{\"max_steps\":1}");
        client.drain();
        reply(BoundedGatherTest.state(0));
        reply(JsonParser.parseString("{\"exploration_points\":[{\"x\":8,\"y\":90,\"z\":0}]}").getAsJsonObject());
        reply(new JsonObject());
        var here = BoundedGatherTest.state(0);
        here.addProperty("x", 8);
        reply(here);
        assertTrue(trip.join().ok());
        String local = "{\"item\":\"dirt\",\"targets\":[\"dirt\"],\"count\":1,"
                + "\"scope\":\"subtask\",\"mode\":\"total\",\"explore_steps\":0}";
        var gathering = runner.execute("gather", local);
        client.drain();
        var outside = BoundedGatherTest.state(0);
        outside.addProperty("x", 100);
        reply(outside);
        reply(JsonParser.parseString("{\"item_targets\":[]}").getAsJsonObject());
        var target = BoundedGatherTest.block(true);
        target.addProperty("x", 102);
        var targets = new JsonArray();
        targets.add(target);
        var found = new JsonObject();
        found.add("targets", targets);
        reply(found);
        assertEquals("break_block", client.lastTool().str("tool"));
        reply(JsonParser.parseString("{\"removed\":true}").getAsJsonObject());
        var achieved = BoundedGatherTest.state(1);
        achieved.addProperty("x", 100);
        reply(achieved);
        assertTrue(gathering.join().ok(), gathering.join().feedback());
        assertEquals(1, gathering.join().data().get("exploration_attempts").getAsInt());
        assertEquals(1, gathering.join().data().get("exploration_max_steps").getAsInt());
        var repeated = runner.execute("gather", local);
        client.drain();
        reply(achieved);
        assertTrue(repeated.join().ok());
        assertEquals(1, repeated.join().data().get("exploration_attempts").getAsInt());
        var back = runner.execute("explore", "{}");
        client.drain();
        reply(here);
        assertTrue(back.join().feedback().startsWith("EXPLORATION_LIMIT:"));
        assertEquals(1, back.join().data().get("exploration_attempts").getAsInt());
        assertFalse(runner.execute("explore", "{\"max_steps\":2}").join().ok());
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
        assertEquals(Integer.MAX_VALUE - 1, client.lastTool().obj("args").get("clear_limit").getAsInt());
        reply(JsonParser.parseString("{\"removed\":true,\"cleared_blocks\":2}").getAsJsonObject());
        assertEquals("status", client.lastTool().str("tool"));
        assertEquals(1, client.engine().responses.size());
        reply(BoundedGatherTest.state(1));
        reply(BoundedGatherTest.state(1));
        assertEquals(2, client.engine().responses.size());
    }

    private void proposeClearing() {
        reachMining();
        reply(false, "NEED_CONFIRM:authorization_id=clear-plan", JsonParser.parseString(
                "{\"removed\":false,\"cleared_blocks\":0,\"authorization_id\":\"clear-plan\","
                        + "\"authorization_summary\":\"server cells\"}").getAsJsonObject());
    }

    private void approve() {
        assertTrue(runner.answerFromPanel(runner.questionSnapshot().id(), "确认"));
        client.drain();
        var grant = client.sent.getLast();
        assertEquals("authorize", grant.kind());
        assertEquals("clear-plan", grant.str("authorization_id"));
        var body = new JsonObject();
        body.addProperty("seq", grant.num("seq", -1));
        body.addProperty("ok", true);
        body.addProperty("feedback", "approved exact cells");
        runner.handleS2c(new Envelope("tool_result", body));
        client.drain();
    }

    @Test void clearingConsentWaitsForHumanAndServerThenContinuesTheSameGather() {
        proposeClearing();
        assertEquals(1, client.engine().responses.size());
        assertTrue(runner.questionSnapshot().text().contains("server cells"));
        assertEquals("等待你的确认", runner.taskState());
        long count = client.toolCount();
        assertTrue(runner.answerFromPanel(runner.questionSnapshot().id(), "确认"));
        client.drain();
        var grant = client.sent.getLast();
        assertEquals("authorize", grant.kind());
        assertEquals(count, client.toolCount(), "a human answer is not yet a server grant");
        assertEquals(1, client.engine().responses.size());
        var body = new JsonObject();
        body.addProperty("seq", grant.num("seq", -1));
        body.addProperty("ok", true);
        body.addProperty("feedback", "approved exact cells");
        runner.handleS2c(new Envelope("tool_result", body));
        client.drain();
        assertEquals("break_block", client.lastTool().str("tool"));
        assertEquals("clear-plan", client.lastTool().obj("args").get("authorization_id").getAsString());
        assertEquals("minecraft:dirt", client.lastTool().obj("args").get("expected_block").getAsString());
        assertEquals(Integer.MAX_VALUE - 1, client.lastTool().obj("args").get("clear_limit").getAsInt());
        assertTrue(runner.taskState().contains("挖掘目标"));
        reply(JsonParser.parseString("{\"removed\":true,\"cleared_blocks\":2}").getAsJsonObject());
        reply(BoundedGatherTest.state(1));
        reply(BoundedGatherTest.state(1));
        assertEquals(2, client.engine().responses.size());
        var receipt = client.engine().histories.getLast().stream()
                .filter(m -> m instanceof com.neko.mcbot.agentcore.llm.Msg.Tool)
                .map(m -> (com.neko.mcbot.agentcore.llm.Msg.Tool) m).toList().getLast();
        assertTrue(receipt.ok(), receipt.content());
        assertTrue(receipt.content().contains("子调用=6"), "the approved reissue consumes another primitive");
        assertTrue(runner.taskState().startsWith("等待模型响应 · "));
    }

    @Test void deniedConsentStopsGatherWithoutReplayingAnyWorldAction() {
        proposeClearing();
        long count = client.toolCount();
        assertTrue(runner.answerFromPanel(runner.questionSnapshot().id(), "不要挖"));
        client.drain();
        assertEquals(count + 1, client.toolCount(), "only the next model observation may be sent");
        assertEquals("status", client.lastTool().str("tool"));
        assertTrue(client.sent.stream().noneMatch(e -> e.kind().equals("authorize")));
        reply(BoundedGatherTest.state(0));
        assertEquals(2, client.engine().responses.size());
        assertTrue(client.engine().histories.getLast().stream().anyMatch(message ->
                message instanceof com.neko.mcbot.agentcore.llm.Msg.Tool tool
                        && !tool.ok() && tool.content().startsWith("DENIED:")));
    }

    @Test void changedProposalAfterOneApprovedReissueYieldsInsteadOfAskingForever() {
        proposeClearing();
        approve();
        reply(false, "NEED_CONFIRM:changed cells", JsonParser.parseString(
                "{\"authorization_id\":\"new-plan\",\"authorization_summary\":\"changed cells\"}").getAsJsonObject());
        assertNull(runner.questionSnapshot());
        assertEquals("status", client.lastTool().str("tool"));
        reply(BoundedGatherTest.state(0));
        assertEquals(2, client.engine().responses.size());
        assertTrue(client.engine().histories.getLast().stream().anyMatch(message ->
                message instanceof com.neko.mcbot.agentcore.llm.Msg.Tool tool
                        && tool.content().startsWith("NEED_CONFIRM:changed cells")));
    }

    @Test void timeoutAndCancellationInvalidateTheAutomaticQuestion() {
        proposeClearing();
        var question = runner.questionSnapshot();
        client.now += 120_001;
        runner.tick(); client.drain();
        assertNull(runner.questionSnapshot());
        assertFalse(runner.answerFromPanel(question.id(), "确认"));
        assertEquals("status", client.lastTool().str("tool"));
        reply(BoundedGatherTest.state(0));
        assertTrue(client.engine().histories.getLast().stream().anyMatch(message ->
                message instanceof com.neko.mcbot.agentcore.llm.Msg.Tool tool
                        && tool.content().startsWith("TIMEOUT:")));
    }

    @Test void cancellationWhileWaitingForGrantDiscardsLateApproval() {
        proposeClearing();
        assertTrue(runner.answerFromPanel(runner.questionSnapshot().id(), "确认"));
        client.drain();
        long seq = client.sent.getLast().num("seq", -1);
        assertTrue(runner.cancelTask(0));
        client.drain();
        long count = client.toolCount();
        var body = new JsonObject();
        body.addProperty("seq", seq); body.addProperty("ok", true); body.addProperty("feedback", "late approval");
        runner.handleS2c(new Envelope("tool_result", body)); client.drain();
        assertEquals(count, client.toolCount());
        assertNull(runner.questionSnapshot());
        assertEquals(1, client.engine().responses.size());
    }

    @Test void gatherDeadlineWithdrawsItsQuestionWithoutExtendingTheBudget() {
        proposeClearing();
        var question = runner.questionSnapshot();
        client.now += 600_001;
        runner.tick(); client.drain();
        assertNull(runner.questionSnapshot());
        assertFalse(runner.answerFromPanel(question.id(), "确认"));
        assertTrue(client.sent.stream().noneMatch(e -> e.kind().equals("authorize")));
        assertEquals("status", client.lastTool().str("tool"));
    }

    @Test void pickupRouteConsentUsesTheSameLocalContinuation() {
        start();
        reply(BoundedGatherTest.state(0));
        var item = BoundedGatherTest.block(false);
        item.addProperty("item", "minecraft:dirt");
        item.addProperty("target_uuid", "ground-drop");
        item.addProperty("can_touch_here", false);
        item.addProperty("pickup_allowed", true);
        var drops = new JsonArray(); drops.add(item);
        var scan = new JsonObject(); scan.add("item_targets", drops);
        reply(scan);
        assertEquals("move_to", client.lastTool().str("tool"));
        var original = client.lastTool().obj("args").deepCopy();
        reply(false, "NEED_CONFIRM:route", JsonParser.parseString(
                "{\"authorization_id\":\"clear-plan\",\"authorization_summary\":\"one leaf on pickup route\"}").getAsJsonObject());
        approve();
        original.addProperty("authorization_id", "clear-plan");
        assertEquals(original, client.lastTool().obj("args"));
        reply(new JsonObject());
        reply(BoundedGatherTest.state(1));
        reply(BoundedGatherTest.state(1));
        assertEquals(2, client.engine().responses.size());
    }

    @Test void missingServerSummaryDoesNotInventAConsentQuestion() {
        reachMining();
        reply(false, "NEED_CONFIRM:incomplete proposal", JsonParser.parseString(
                "{\"authorization_id\":\"incomplete\"}").getAsJsonObject());
        assertNull(runner.questionSnapshot());
        assertEquals("status", client.lastTool().str("tool"));
        reply(BoundedGatherTest.state(0));
        assertEquals(2, client.engine().responses.size());
    }

    @Test void anAlreadyRemovedTargetCannotBeReplayedThroughAutomaticConsent() {
        reachMining();
        reply(false, "NEED_CONFIRM:partial effects", JsonParser.parseString(
                "{\"removed\":true,\"cleared_blocks\":1,\"authorization_id\":\"partial\","
                        + "\"authorization_summary\":\"partial effects\"}").getAsJsonObject());
        assertNull(runner.questionSnapshot());
        assertEquals("status", client.lastTool().str("tool"));
        reply(BoundedGatherTest.state(0));
        assertTrue(client.engine().histories.getLast().stream().anyMatch(message ->
                message instanceof com.neko.mcbot.agentcore.llm.Msg.Tool tool
                        && tool.content().startsWith("NEED_CONFIRM:partial effects")
                        && tool.content().contains("实际目标挖掘=1")));
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
                "{\"item\":\"dirt\",\"targets\":[\"dirt\"],\"count\":1,\"scope\":\"subtask\",\"mode\":\"additional\"}"));
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
