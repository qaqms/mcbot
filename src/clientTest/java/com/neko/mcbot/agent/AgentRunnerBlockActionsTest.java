package com.neko.mcbot.agent;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.neko.mcbot.agentcore.llm.Msg;
import com.neko.mcbot.agentcore.llm.ToolCall;
import com.neko.mcbot.common.Envelope;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AgentRunnerBlockActionsTest {
    private final OfflineAgentClient client = new OfflineAgentClient();
    private final AgentRunner runner = client.create();
    private static final String POS = "{\"x\":2,\"y\":90,\"z\":0}";

    @AfterEach void close() { runner.close(); client.drain(); }

    private void call(int turn, String id, String tool, String args) {
        client.engine().tools(turn, new ToolCall(id, tool, args));
        client.drain();
        assertEquals(tool, client.lastTool().str("tool"));
        assertEquals(JsonParser.parseString(args), client.lastTool().obj("args"));
    }

    private void receipt(boolean ok, String text) {
        var body = new JsonObject();
        body.addProperty("seq", client.lastTool().num("seq", -1));
        body.addProperty("ok", ok);
        body.addProperty("feedback", text);
        runner.handleS2c(new Envelope("tool_result", body));
        client.drain();
    }

    private Envelope ack(long seq) {
        var body = new JsonObject();
        body.addProperty("seq", seq);
        body.addProperty("job_id", "mining-job");
        body.addProperty("cap_ticks", 1200);
        body.addProperty("text", "ACCEPTED:mining");
        return new Envelope("job_ack", body);
    }

    private Envelope done(long seq, String text) {
        var body = new JsonObject();
        body.addProperty("seq", seq);
        body.addProperty("job_id", "mining-job");
        body.addProperty("tool", "break_block");
        body.addProperty("phase", "done");
        body.addProperty("ok", true);
        body.addProperty("text", text);
        return new Envelope("job_event", body);
    }

    @Test void schemasExplainHarvestFaceExactTargetAndSafePathMaterials() {
        runner.start();
        runner.submitTask("inspect tools");
        var specs = client.engine().definitions.getFirst();
        var mine = specs.stream().filter(s -> s.name().equals("break_block")).findFirst().orElseThrow();
        var place = specs.stream().filter(s -> s.name().equals("place_block")).findFirst().orElseThrow();
        var move = specs.stream().filter(s -> s.name().equals("move_to")).findFirst().orElseThrow();
        assertTrue(mine.description().contains("采收资格"));
        assertTrue(mine.description().contains("耐久"));
        assertTrue(place.description().contains("准确目标格"));
        assertTrue(place.description().contains("实际消耗"));
        assertTrue(move.description().contains("无额外组件"));
        var schema = JsonParser.parseString(place.paramsJsonSchema()).getAsJsonObject();
        assertEquals(6, schema.getAsJsonObject("properties").getAsJsonObject("face")
                .getAsJsonArray("enum").size());
        assertEquals(128, schema.getAsJsonObject("properties").getAsJsonObject("item")
                .get("maxLength").getAsInt());
        assertFalse(schema.getAsJsonArray("required").contains(JsonParser.parseString("\"face\"")));
    }

    @Test void acceptedMiningWaitsForActualTerminalThenSuppliesLootToNextTurn() {
        runner.start();
        runner.submitTask("mine once and inspect");
        call(0, "mine", "break_block", POS);
        long seq = client.lastTool().num("seq", -1);
        runner.handleS2c(ack(seq));
        client.drain();
        assertEquals(1, client.engine().responses.size());
        assertEquals(1, client.toolCount());
        String text = "已移除目标方块，收到 minecraft:cobblestone × 1，留地 0";
        runner.handleS2c(done(seq, text));
        client.drain();
        var outcome = assertInstanceOf(Msg.Tool.class, client.engine().histories.get(1).getLast());
        assertEquals("mine", outcome.callId());
        assertEquals(text, outcome.content());
        call(1, "check", "inventory", "{}");
        receipt(true, "inventory contains cobblestone");
        client.engine().reply(2, "done");
        client.drain();
        assertEquals(2, client.toolCount());
    }

    @Test void wrongToolBusyAndPartialPlacementAreVisibleWithoutAutomaticReplay() {
        runner.start();
        runner.submitTask("mine and place");
        call(0, "wrong", "break_block", POS);
        receipt(false, "WRONG_TOOL:target untouched; equip a pickaxe");
        assertEquals(1, client.toolCount());
        assertTrue(assertInstanceOf(Msg.Tool.class, client.engine().histories.get(1).getLast())
                .content().startsWith("WRONG_TOOL:"));
        String args = "{\"x\":2,\"y\":90,\"z\":0,\"item\":\"cobblestone\",\"face\":\"east\"}";
        call(1, "busy", "place_block", args);
        receipt(false, "BUSY:wait or cancel");
        assertEquals(2, client.toolCount());
        call(2, "place", "place_block", args);
        String partial = "PLACE_FAILED:1 consumed and target changed; inspect before retry";
        receipt(false, partial);
        assertEquals(partial, assertInstanceOf(Msg.Tool.class, client.engine().histories.get(3).getLast()).content());
        assertEquals(3, client.toolCount());
        client.engine().reply(3, "inspect actual state first");
        client.drain();
    }

    @Test void cancellingBeforeAckOrDuringMiningIgnoresLateSuccessAndPairsHistory() {
        for (boolean accepted : new boolean[]{false, true}) {
            var isolated = new OfflineAgentClient();
            var active = isolated.create();
            try {
                active.start();
                long task = active.submitTask("old mining");
                isolated.engine().tools(0, new ToolCall("old-mine", "break_block", POS));
                isolated.drain();
                long seq = isolated.lastTool().num("seq", -1);
                if (accepted) active.handleS2c(ack(seq));
                assertTrue(active.cancelTask(task));
                isolated.drain();
                active.handleS2c(ack(seq));
                active.handleS2c(done(seq, "late removal"));
                isolated.drain();
                assertEquals(1, isolated.toolCount());
                assertEquals(1, isolated.engine().responses.size());
                active.submitTask("check actual inventory");
                var history = isolated.engine().histories.get(1);
                assertTrue(history.stream().anyMatch(message -> message instanceof Msg.Tool tool
                        && tool.callId().equals("old-mine") && tool.content().startsWith("CANCELLED:")));
                assertFalse(history.stream().anyMatch(message -> message instanceof Msg.Tool tool
                        && tool.content().equals("late removal")));
                isolated.engine().tools(1, new ToolCall("check", "inventory", "{}"));
                isolated.drain();
                assertEquals(2, isolated.toolCount());
            } finally {
                active.close();
                isolated.drain();
            }
        }
    }
}
