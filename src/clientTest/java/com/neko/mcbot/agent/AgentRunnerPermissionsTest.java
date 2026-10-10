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
