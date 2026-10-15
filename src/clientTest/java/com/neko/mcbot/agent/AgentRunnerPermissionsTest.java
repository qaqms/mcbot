package com.neko.mcbot.agent;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.neko.mcbot.agentcore.llm.AssistantTurn;
import com.neko.mcbot.agentcore.llm.Msg;
import com.neko.mcbot.agentcore.llm.ToolCall;
import com.neko.mcbot.bridge.BridgeEventCapture;
import com.neko.mcbot.common.Envelope;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AgentRunnerPermissionsTest {
    private final OfflineAgentClient client = new OfflineAgentClient();
    private final AgentRunner runner = client.create();
    private final BridgeEventCapture events = new BridgeEventCapture();
    private static final String POS = "{\"x\":2,\"y\":90,\"z\":0}";
    private static final String ID = "server-plan-7";
    private static final String SUMMARY = "目的地2,90,0；挖1格：dig@1,90,0 minecraft:stone";

    @AfterEach void close() { runner.close(); client.drain(); events.close(); }

    private void call(int turn, String name, String args) {
        client.engine().tools(turn, new ToolCall("c" + turn, name, args));
        client.drain();
    }

    private void receipt(long seq, boolean ok, String feedback, JsonObject data) {
        var body = new JsonObject();
        body.addProperty("seq", seq);
        body.addProperty("ok", ok);
        body.addProperty("feedback", feedback);
        if (data != null) body.add("data", data);
        runner.handleS2c(new Envelope("tool_result", body));
        client.drain();
    }

    private String question() {
        return events.events().stream().filter(e -> e.get("ev").getAsString().equals("question"))
                .toList().getLast().get("question_id").getAsString();
    }

    private long propose() {
        runner.start();
        long task = runner.submitTask("move and prepare a route");
        call(0, "move_to", POS);
        var data = new JsonObject();
        data.addProperty("authorization_id", ID);
        data.addProperty("authorization_summary", SUMMARY);
        receipt(client.lastTool().num("seq", -1), false, "NEED_CONFIRM:" + ID, data);
        call(1, "ask_owner", "{\"text\":\"misleading model request\",\"authorization_id\":\"" + ID + "\"}");
        return task;
    }

    private void autonomous(boolean enabled) {
        runner.reconfigure(runner.config().withPreferences(
                runner.config().preferences.withAutonomousActions(enabled)));
        client.drain();
    }

    private void observation(String mode) {
        var data = BoundedGatherTest.state(0);
        if (mode != null) data.addProperty("permission_mode", mode);
        receipt(client.lastTool().num("seq", -1), true, "server body mode", data);
    }

    @Test void autonomousTaskIsConfirmedByServerBeforeModelAndMovementNeedsNoApprovalRound() {
        autonomous(true);
        client.liveObservation = true;
        runner.start();
        long task = runner.submitTask("move through a permitted route");
        client.drain();
        var begin = client.sent.stream().filter(e -> e.kind().equals("task_begin")).findFirst().orElseThrow();
        assertTrue(begin.bool("autonomous"));
        assertFalse(begin.bool("read_only"));
        assertEquals(task, begin.num("task_id", -1));
        assertTrue(client.engine().responses.isEmpty());
        observation("autonomous");
        call(0, "move_to", POS);
        assertFalse(client.lastTool().obj("args").has("authorization_id"));
        long seq = client.lastTool().num("seq", -1);
        runner.handleS2c(AgentRunnerLifecycleTest.ack(seq));
        client.drain();
        assertEquals(1, client.engine().responses.size());
        runner.handleS2c(AgentRunnerLifecycleTest.job(seq, "done"));
        client.drain();
        observation("autonomous");
        assertEquals(2, client.engine().responses.size());
        assertNull(runner.questionSnapshot());
        assertTrue(client.sent.stream().noneMatch(e -> e.kind().equals("authorize")));
    }

    @Test void autonomousModeFailsClearlyBeforeModelWhenServerDoesNotConfirmIt() {
        autonomous(true);
        client.liveObservation = true;
        runner.start();
        for (String mode : new String[]{null, "confirm"}) {
            long task = runner.submitTask("work with autonomy");
            client.drain();
            observation(mode);
            assertTrue(client.engine().responses.isEmpty());
            assertEquals(0, runner.currentTaskId());
            assertTrue(runner.panelHistory().entries(task).stream().anyMatch(
                    entry -> entry.text().contains("MODE_UNAVAILABLE:")));
        }
        assertTrue(client.sent.stream().filter(e -> e.kind().equals("tool_call"))
                .allMatch(e -> e.str("tool").equals("status")));
    }

    @Test void explicitReadonlyDirectiveRetainsBothClientAndServerReadonlyInAutonomousPreference() {
        autonomous(true);
        client.liveObservation = true;
        runner.start();
        runner.submitTask("[只读] inspect");
        client.drain();
        var begin = client.sent.stream().filter(e -> e.kind().equals("task_begin")).findFirst().orElseThrow();
        assertTrue(begin.bool("read_only"));
        assertFalse(begin.bool("autonomous"));
        observation("read_only");
        call(0, "break_block", POS);
        assertTrue(client.sent.stream().noneMatch(e -> e.kind().equals("tool_call") && e.str("tool").equals("break_block")));
        observation("read_only");
        assertTrue(client.engine().histories.getLast().stream().anyMatch(m -> m instanceof Msg.Tool t
                && t.content().startsWith("DENIED:")));
    }

    @Test void readonlyTaskCanQueryPlacementButCannotTurnTheQueryIntoAMutation() {
        runner.start();
        runner.submitTask("[只读] check a placement site");
        client.drain();
        call(0, "place_block", "{\"x\":2,\"y\":90,\"z\":0,\"item\":\"stone\",\"query\":true}");
        assertEquals("place_block", client.lastTool().str("tool"));
        assertTrue(client.lastTool().obj("args").get("query").getAsBoolean());
        receipt(client.lastTool().num("seq", -1), true, "observation", new JsonObject());
        long calls = client.toolCount();
        call(1, "place_block", "{\"x\":2,\"y\":90,\"z\":0,\"item\":\"stone\",\"query\":false}");
        assertEquals(calls, client.toolCount());
        assertTrue(client.engine().histories.getLast().stream().anyMatch(m -> m instanceof Msg.Tool t
                && t.content().startsWith("DENIED:")));
    }

    @Test void changingModeStopsPendingConfirmationAndQueuedTasksWithoutGrantingOldScope() {
        long task = propose();
        var old = runner.questionSnapshot();
        long queued = runner.submitTask("queued work");
        client.drain();
        autonomous(true);
        assertNull(runner.questionSnapshot());
        assertEquals(0, runner.currentTaskId());
        assertEquals(0, JsonParser.parseString(runner.statusJson()).getAsJsonObject().get("queued_tasks").getAsInt());
        assertFalse(runner.answerQuestion(old.id(), "确认"));
        assertTrue(client.sent.stream().noneMatch(e -> e.kind().equals("authorize")));
        assertEquals(1, client.engines.size(), "mode changes retain the existing engine/history");
        for (long id : new long[]{task, queued})
            assertTrue(runner.panelHistory().tasks().stream().anyMatch(t -> t.id() == id && t.status().equals("cancelled")));
        runner.submitTask("fresh autonomous work");
        client.drain();
        assertTrue(client.sent.stream().filter(e -> e.kind().equals("task_begin")).toList().getLast().bool("autonomous"));
    }

    @Test void disablingAutonomyCancelsAcceptedWorkAndLateJobCannotContinueIt() {
        autonomous(true);
        runner.start();
        runner.submitTask("move then work");
        call(0, "move_to", POS);
        long seq = client.lastTool().num("seq", -1);
        runner.handleS2c(AgentRunnerLifecycleTest.ack(seq));
        client.drain();
        autonomous(false);
        long tools = client.toolCount();
        runner.handleS2c(AgentRunnerLifecycleTest.job(seq, "done"));
        client.drain();
        assertEquals(tools, client.toolCount());
        assertEquals(0, runner.currentTaskId());
        assertEquals(0, JsonParser.parseString(runner.statusJson()).getAsJsonObject().get("pending_jobs").getAsInt());
        runner.submitTask("fresh confirmation-mode work");
        client.drain();
        assertFalse(client.sent.stream().filter(e -> e.kind().equals("task_begin")).toList().getLast().bool("autonomous"));
    }

    @Test void failedModeSaveKeepsTheOldPolicyAndPendingQuestionIntact() {
        long task = propose();
        var question = runner.questionSnapshot();
        long cancellations = client.cancelCount();
        client.failSave = true;
        assertThrows(java.io.UncheckedIOException.class, () -> runner.reconfigure(
                runner.config().withPreferences(runner.config().preferences.withAutonomousActions(true))));
        client.drain();
        assertFalse(runner.config().preferences.autonomousActions());
        assertEquals(task, runner.currentTaskId());
        assertEquals(question.id(), runner.questionSnapshot().id());
        assertEquals(cancellations, client.cancelCount());
    }

    @Test void readonlyNoTargetsCannotTriggerMovementOrWorldChanges() {
        runner.start();
        long task = runner.submitTask("[只读]仅扫描附近，未发现目标就汇报，不要移动");
        call(0, "scan_area", "{}");
        receipt(client.lastTool().num("seq", -1), true, "未发现可行动目标", null);
        call(1, "move_to", "{\"x\":2,\"y\":90,\"z\":0,\"may_alter_terrain\":true,\"read_only\":false}");
        call(2, "break_block", POS);
        call(3, "place_block", "{\"x\":2,\"y\":90,\"z\":0,\"item\":\"dirt\"}");
        assertEquals(1, client.toolCount());
        for (int turn = 2; turn <= 4; turn++) {
            assertTrue(assertInstanceOf(Msg.Tool.class, client.engine().histories.get(turn).getLast())
                    .content().startsWith("DENIED:"));
        }
        var begin = client.sent.stream().filter(e -> e.kind().equals("task_begin")).findFirst().orElseThrow();
        assertEquals(task, begin.num("task_id", -1));
        assertTrue(begin.bool("read_only"));
        assertEquals(task, client.lastTool().num("task_id", -1));
        client.engine().reply(4, "只读扫描未发现目标");
        client.drain();
        assertEquals(1, client.sent.stream().filter(e -> e.kind().equals("task_end")).count());
    }

    @Test void dependentToolWaitsForAcceptedJobTerminalIncludingEarlyDispatch() {
        runner.start();
        runner.submitTask("mine then inspect inventory");
        var mining = new ToolCall("mine", "break_block", POS);
        var inventory = new ToolCall("bag", "inventory", "{}");
        client.engine().early(0, mining);
        client.drain();
        long seq = client.lastTool().num("seq", -1);
        runner.handleS2c(AgentRunnerLifecycleTest.ack(seq));
        client.engine().responses.get(0).complete(new AssistantTurn("", List.of(mining, inventory), 0, 0, -1, "tool_calls"));
        client.drain();
        assertEquals(1, client.toolCount());
        assertEquals(1, client.engine().responses.size());
        runner.handleS2c(AgentRunnerLifecycleTest.job(seq, "done"));
        client.drain();
        assertEquals(2, client.toolCount());
        assertEquals("inventory", client.lastTool().str("tool"));
        receipt(client.lastTool().num("seq", -1), true, "real inventory", null);
        var history = client.engine().histories.get(1);
        var tools = history.stream().filter(m -> m instanceof Msg.Tool).map(m -> (Msg.Tool) m).toList();
        assertEquals(List.of("mine", "bag"), tools.stream().map(Msg.Tool::callId).toList());
        assertFalse(JsonParser.parseString(runner.statusJson()).getAsJsonObject().get("parked").getAsBoolean());
    }

    @Test void ownerSeesServerScopeAndApprovalWaitsForServerReceipt() {
        long task = propose();
        var questionEvent = events.events().stream().filter(e -> e.get("ev").getAsString().equals("question"))
                .toList().getLast();
        assertTrue(questionEvent.get("text").getAsString().contains(SUMMARY));
        assertFalse(questionEvent.get("text").getAsString().contains("misleading"));
        assertEquals(questionEvent.get("text").getAsString(), runner.questionSnapshot().text());
        assertTrue(runner.questionSnapshot().authorization());
        assertEquals(task, runner.questionSnapshot().taskId());
        assertTrue(runner.answerQuestion(question(), "确认"));
        client.drain();
        assertEquals(2, client.engine().responses.size(), "授权回执前不能继续模型");
        var grant = client.sent.stream().filter(e -> e.kind().equals("authorize")).toList().getLast();
        assertEquals(task, grant.num("task_id", -1));
        assertEquals(ID, grant.str("authorization_id"));
        receipt(grant.num("seq", -1), true, "approved exact scope", null);
        call(2, "move_to", "{\"x\":2,\"y\":90,\"z\":0,\"authorization_id\":\"" + ID + "\"}");
        assertEquals(ID, client.lastTool().obj("args").get("authorization_id").getAsString());
    }

    @Test void negativeOrAmbiguousHumanAnswerDoesNotSendAuthorization() {
        propose();
        assertTrue(runner.answerQuestion(question(), "yes but don't dig"));
        client.drain();
        assertEquals(0, client.sent.stream().filter(e -> e.kind().equals("authorize")).count());
        assertTrue(assertInstanceOf(Msg.Tool.class, client.engine().histories.get(2).getLast()).content().startsWith("DENIED:"));
    }

    @Test void fabricatedAuthorizationCannotAskOwnerOrSendGrant() {
        runner.start();
        runner.submitTask("move");
        call(0, "ask_owner", "{\"text\":\"approve\",\"authorization_id\":\"fabricated\"}");
        assertTrue(events.events().stream().noneMatch(e -> e.get("ev").getAsString().equals("question")));
        assertTrue(assertInstanceOf(Msg.Tool.class, client.engine().histories.get(1).getLast()).content().startsWith("DENIED:"));
    }

    @Test void cancellationDuringGrantWaitCannotResumeOrReplayLateApproval() {
        long task = propose();
        assertTrue(runner.answerQuestion(question(), "确认"));
        client.drain();
        var grant = client.sent.stream().filter(e -> e.kind().equals("authorize")).toList().getLast();
        assertTrue(runner.cancelTask(task));
        client.drain();
        receipt(grant.num("seq", -1), true, "late approval", null);
        assertEquals(2, client.engine().responses.size());
        assertEquals(1, client.toolCount());
        runner.submitTask("[只读]inventory");
        assertTrue(client.engine().histories.get(2).stream().anyMatch(m -> m instanceof Msg.Tool t
                && t.content().startsWith("CANCELLED:")));
        call(2, "move_to", "{\"x\":2,\"y\":90,\"z\":0,\"authorization_id\":\"" + ID + "\"}");
        assertEquals(1, client.toolCount());
    }
}
