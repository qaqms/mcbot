package com.neko.mcbot.agent;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.neko.mcbot.agentcore.llm.Msg;
import com.neko.mcbot.agentcore.llm.ToolCall;
import com.neko.mcbot.common.Envelope;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AgentRunnerSmeltTest {
    private final OfflineAgentClient client = new OfflineAgentClient();
    private final AgentRunner runner = client.create();

    @AfterEach
    void close() {
        runner.close();
        client.drain();
    }

    private Msg.Tool receipt(String id, boolean ok, String feedback, int turn) {
        JsonObject body = new JsonObject();
        body.addProperty("seq", client.lastTool().num("seq", -1));
        body.addProperty("ok", ok);
        body.addProperty("feedback", feedback);
        runner.handleS2c(new Envelope("tool_result", body));
        client.drain();
        var result = assertInstanceOf(Msg.Tool.class, client.engine().histories.get(turn).getLast());
        assertEquals(id, result.callId());
        assertEquals(feedback, result.content());
        return result;
    }

    private void call(int turn, String id, String name, String args) {
        client.engine().tools(turn, new ToolCall(id, name, args));
        client.drain();
        assertEquals(name, client.lastTool().str("tool"));
        assertEquals(JsonParser.parseString(args), client.lastTool().obj("args"));
    }

    @Test
    void actualSchemaAdvertisesTheThreeModesAndSlotBoundaries() {
        runner.start();
        runner.submitTask("smelt");
        var spec = client.engine().definitions.getFirst().stream()
                .filter(tool -> tool.name().equals("smelt")).findFirst().orElseThrow();
        assertEquals(java.util.List.of("x", "y", "z"), spec.requiredFields());
        var properties = JsonParser.parseString(spec.paramsJsonSchema()).getAsJsonObject()
                .getAsJsonObject("properties");
        assertEquals(JsonParser.parseString("[\"query\",\"load\",\"take\"]"),
                properties.getAsJsonObject("action").get("enum"));
        for (String slot : java.util.List.of("input_slot", "fuel_slot")) {
            assertEquals(0, properties.getAsJsonObject(slot).get("minimum").getAsInt());
            assertEquals(35, properties.getAsJsonObject(slot).get("maximum").getAsInt());
        }
        assertTrue(spec.description().contains("取消任务不熄炉"));
    }

    @Test
    void loadWaitQueryTakeInventoryArePairedAndDoNotInventEarlyOutput() {
        runner.start();
        runner.submitTask("smelt iron then check inventory");
        call(0, "load", "smelt", "{\"x\":2,\"y\":90,\"z\":0,\"action\":\"load\","
                + "\"input_slot\":0,\"input_count\":3,\"fuel_slot\":1}");
        receipt("load", true, "loaded 3 raw iron; output empty; not finished; wait 30 seconds", 1);
        assertEquals(1, client.toolCount());
        call(1, "wait", "wait", "{\"seconds\":30}");
        receipt("wait", true, "wait finished; check real output", 2);
        call(2, "query", "smelt", "{\"x\":2,\"y\":90,\"z\":0}");
        receipt("query", true, "minecraft:iron_ingot x3 in output; cooking 0/200; input empty", 3);
        call(3, "take", "smelt", "{\"x\":2,\"y\":90,\"z\":0,\"action\":\"take\",\"count\":3}");
        receipt("take", true, "taken 3 iron ingots into inventory", 4);
        call(4, "inventory", "inventory", "{}");
        receipt("inventory", true, "minecraft:iron_ingot x3", 5);
        client.engine().reply(5, "3 ingots taken");
        client.drain();
        assertEquals(5, client.toolCount());
        assertEquals(0, JsonParser.parseString(runner.statusJson()).getAsJsonObject()
                .get("current_task").getAsLong());
    }

    @Test
    void notReadyAndBusyDoNotAutomaticallyRetryOrMoveOtherSlots() {
        runner.start();
        runner.submitTask("take output");
        call(0, "take", "smelt", "{\"x\":2,\"y\":90,\"z\":0,\"action\":\"take\"}");
        receipt("take", false, "NOT_READY:output empty; input still x3; wait then query", 1);
        assertEquals(1, client.toolCount());
        call(1, "load", "smelt", "{\"x\":2,\"y\":90,\"z\":0,\"action\":\"load\",\"fuel_slot\":1}");
        receipt("load", false, "BUSY:wait for current task; query still allowed", 2);
        assertEquals(2, client.toolCount());
        client.engine().reply(2, "still waiting");
        client.drain();
        assertEquals(2, client.toolCount());
    }
}
