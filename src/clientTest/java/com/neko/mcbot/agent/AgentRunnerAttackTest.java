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

class AgentRunnerAttackTest {
    private final OfflineAgentClient client = new OfflineAgentClient();
    private final AgentRunner runner = client.create();
    private static final String ARGS = "{\"entity_id\":47,\"target_uuid\":\"00000000-0000-0000-0000-000000000047\",\"max_hits\":2}";

    @AfterEach void close() { runner.close(); client.drain(); }

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

    private Envelope ack(long seq) {
        var body = new JsonObject();
        body.addProperty("seq", seq);
        body.addProperty("job_id", "attack-job");
        body.addProperty("cap_ticks", 400);
        body.addProperty("text", "ACCEPTED:attack");
        return new Envelope("job_ack", body);
    }

    private Envelope done(long seq, boolean ok, String text) {
        var body = new JsonObject();
        body.addProperty("seq", seq);
        body.addProperty("job_id", "attack-job");
        body.addProperty("tool", "attack");
        body.addProperty("phase", ok ? "done" : "failed");
        body.addProperty("ok", ok);
        body.addProperty("text", text);
        return new Envelope("job_event", body);
    }

    @Test void modelReceivesBoundedIdentityApprovalAndTerminalSemantics() {
        runner.start();
        runner.submitTask("inspect melee tools");
        var attack = client.engine().definitions.getFirst().stream().filter(s -> s.name().equals("attack"))
                .findFirst().orElseThrow();
        var schema = JsonParser.parseString(attack.paramsJsonSchema()).getAsJsonObject();
        assertEquals(10, schema.getAsJsonObject("properties").getAsJsonObject("max_hits").get("maximum").getAsInt());
        assertFalse(schema.get("additionalProperties").getAsBoolean());
        assertTrue(attack.description().contains("不追击"));
        assertTrue(attack.description().contains("target_uuid"));
        assertTrue(attack.description().contains("ask_owner"));
        assertTrue(attack.description().contains("挥击次数不等于"));
        var scan = client.engine().definitions.getFirst().stream().filter(s -> s.name().equals("scan_area"))
                .findFirst().orElseThrow();
        assertTrue(scan.description().contains("entity_id/target_uuid"));
    }

    @Test void acceptedAttackBlocksSameTurnFollowupUntilActualTerminal() {
        runner.start();
        runner.submitTask("strike twice then inspect inventory");
        var attack = new ToolCall("melee", "attack", ARGS);
        var check = new ToolCall("bag", "inventory", "{}");
        client.engine().early(0, attack);
        client.drain();
        long seq = client.lastTool().num("seq", -1);
        runner.handleS2c(ack(seq));
        client.engine().responses.getFirst().complete(new AssistantTurn("", List.of(attack, check), 0, 0, -1, "tool_calls"));
        client.drain();
        assertEquals(1, client.toolCount());
        assertEquals(JsonParser.parseString(ARGS), client.lastTool().obj("args"));
        String text = "本次出手2次，生命减少6，目标仍存活";
        runner.handleS2c(done(seq, true, text));
        client.drain();
        assertEquals(2, client.toolCount());
        assertEquals("inventory", client.lastTool().str("tool"));
        receipt(client.lastTool().num("seq", -1), true, "native wear observed", null);
        var history = client.engine().histories.get(1);
        var calls = history.stream().filter(m -> m instanceof Msg.Tool).map(m -> (Msg.Tool) m).toList();
        assertEquals(List.of("melee", "bag"), calls.stream().map(Msg.Tool::callId).toList());
        assertEquals(text, calls.getFirst().content());
    }

    @Test void deniedReadonlyAndFailedAttacksNeverAutomaticallyReplay() {
        runner.start();
        runner.submitTask("[只读]只观察周围生物");
        call(0, "attack", ARGS);
        assertEquals(0, client.toolCount());
        assertTrue(assertInstanceOf(Msg.Tool.class, client.engine().histories.get(1).getLast())
                .content().startsWith("DENIED:"));
        client.engine().reply(1, "observed only");
        client.drain();
        runner.submitTask("one attack");
        call(2, "attack", ARGS);
        receipt(client.lastTool().num("seq", -1), false, "ATTACK_FAILED:1 strike; no observed damage", null);
        assertEquals(1, client.toolCount());
        assertTrue(assertInstanceOf(Msg.Tool.class, client.engine().histories.get(3).getLast())
                .content().startsWith("ATTACK_FAILED:"));
    }

    @Test void neutralTargetUsesServerConfirmationAndWaitsForAuthorizationReceipt() {
        try (var events = new BridgeEventCapture()) {
            runner.start();
            long task = runner.submitTask("attack the selected pig");
            call(0, "attack", ARGS);
            var data = new JsonObject();
            String summary = "服务器清单：entity_id47，UUID47，最多2次，可能杀死目标";
            data.addProperty("authorization_id", "server-attack");
            data.addProperty("authorization_summary", summary);
            receipt(client.lastTool().num("seq", -1), false, "NEED_CONFIRM:neutral", data);
            call(1, "ask_owner", "{\"text\":\"unrelated model wording\",\"authorization_id\":\"server-attack\"}");
            var question = events.events().stream().filter(e -> e.get("ev").getAsString().equals("question"))
                    .toList().getLast();
            assertTrue(question.get("text").getAsString().contains(summary));
            assertFalse(question.get("text").getAsString().contains("unrelated"));
            assertTrue(runner.answerQuestion(question.get("question_id").getAsString(), "确认"));
            client.drain();
            assertEquals(2, client.engine().responses.size());
            var grant = client.sent.stream().filter(e -> e.kind().equals("authorize")).toList().getLast();
            assertEquals(task, grant.num("task_id", -1));
            receipt(grant.num("seq", -1), true, "exact attack approved", null);
            var approved = JsonParser.parseString(ARGS).getAsJsonObject();
            approved.addProperty("authorization_id", "server-attack");
            call(2, "attack", approved.toString());
            assertEquals(approved, client.lastTool().obj("args"));
        }
    }

    @Test void cancellationBeforeOrAfterAckNeverResumesWithLateDamageResult() {
        for (boolean accepted : new boolean[]{false, true}) {
            var isolated = new OfflineAgentClient();
            var active = isolated.create();
            try {
                active.start();
                long task = active.submitTask("old attack");
                isolated.engine().tools(0, new ToolCall("melee", "attack", ARGS));
                isolated.drain();
                long seq = isolated.lastTool().num("seq", -1);
                if (accepted) active.handleS2c(ack(seq));
                isolated.drain();
                assertTrue(active.cancelTask(task));
                isolated.drain();
                active.handleS2c(ack(seq));
                active.handleS2c(done(seq, true, "late damage"));
                isolated.drain();
                assertEquals(1, isolated.toolCount());
                assertEquals(1, isolated.engine().responses.size());
                active.submitTask("[只读]inspect actual state");
                var history = isolated.engine().histories.get(1);
                assertTrue(history.stream().anyMatch(m -> m instanceof Msg.Tool t && t.callId().equals("melee")
                        && t.content().startsWith("CANCELLED:")));
                assertFalse(history.stream().anyMatch(m -> m instanceof Msg.Tool t && t.content().equals("late damage")));
            } finally {
                active.close();
                isolated.drain();
            }
        }
    }

    @Test void acceptedJobTimeoutUsesServerCapAndNoAutomaticResend() {
        runner.start();
        runner.submitTask("attack");
        call(0, "attack", ARGS);
        long seq = client.lastTool().num("seq", -1);
        runner.handleS2c(ack(seq));
        client.drain();
        client.now += 34_999;
        runner.tick();
        client.drain();
        assertEquals(1, client.engine().responses.size());
        client.now += 2;
        runner.tick();
        client.drain();
        assertEquals(2, client.engine().responses.size());
        assertTrue(assertInstanceOf(Msg.Tool.class, client.engine().histories.get(1).getLast())
                .content().startsWith("TIMEOUT:"));
        assertEquals(1, client.toolCount());
    }
}
