package com.neko.mcbot.agent;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.neko.mcbot.agentcore.llm.Msg;
import com.neko.mcbot.agentcore.llm.ToolCall;
import com.neko.mcbot.common.Envelope;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AgentRunnerCraftTest {
    private final OfflineAgentClient client = new OfflineAgentClient();
    private final AgentRunner runner = client.create();

    @AfterEach
    void close() {
        runner.close();
        client.drain();
    }

    private void receipt(boolean ok, String text) {
        JsonObject body = new JsonObject();
        body.addProperty("seq", client.lastTool().num("seq", -1));
        body.addProperty("ok", ok);
        body.addProperty("feedback", text);
        runner.handleS2c(new Envelope("tool_result", body));
        client.drain();
    }

    @Test
    void actualRunnerAdvertisesQueryAndAtLeastOutputCount() {
        runner.start();
        runner.submitTask("craft");
        var spec = client.engine().definitions.getFirst().stream()
                .filter(tool -> tool.name().equals("craft")).findFirst().orElseThrow();
        assertEquals(java.util.List.of("item"), spec.requiredFields());
        var properties = JsonParser.parseString(spec.paramsJsonSchema()).getAsJsonObject()
                .getAsJsonObject("properties");
        assertEquals("boolean", properties.getAsJsonObject("query").get("type").getAsString());
        assertEquals("integer", properties.getAsJsonObject("count").get("type").getAsString());
        assertEquals(1, properties.getAsJsonObject("count").get("minimum").getAsInt());
        assertEquals(64, properties.getAsJsonObject("count").get("maximum").getAsInt());
        assertTrue(properties.has("recipe"));
    }

    @Test
    void queryThenCraftThenInventoryRemainPairedAndFeedbackVisible() {
        runner.start();
        runner.submitTask("make sticks and check");
        client.engine().tools(0, new ToolCall("query-call", "craft",
                "{\"item\":\"stick\",\"count\":5,\"query\":true}"));
        client.drain();
        assertTrue(client.lastTool().obj("args").get("query").getAsBoolean());
        receipt(true, "query only; minecraft:stick recipe requires 4 planks; 2 batches produce 8 sticks");
        var query = assertInstanceOf(Msg.Tool.class, client.engine().histories.get(1).getLast());
        assertEquals("query-call", query.callId());
        assertTrue(query.content().contains("2 batches"));
        client.engine().tools(1, new ToolCall("craft-call", "craft",
                "{\"item\":\"stick\",\"count\":5,\"recipe\":\"minecraft:stick\"}"));
        client.drain();
        assertEquals("craft", client.lastTool().str("tool"));
        assertEquals(5, client.lastTool().obj("args").get("count").getAsInt());
        receipt(true, "crafted 8 sticks; all output stored");
        var crafted = assertInstanceOf(Msg.Tool.class, client.engine().histories.get(2).getLast());
        assertEquals("craft-call", crafted.callId());
        assertTrue(crafted.content().contains("crafted 8"));
        client.engine().tools(2, new ToolCall("inventory-call", "inventory", "{}"));
        client.drain();
        receipt(true, "minecraft:stick x8");
        assertEquals("inventory-call", assertInstanceOf(Msg.Tool.class,
                client.engine().histories.get(3).getLast()).callId());
        client.engine().reply(3, "done");
        client.drain();
        assertEquals(3, client.toolCount());
        assertEquals(0, JsonParser.parseString(runner.statusJson()).getAsJsonObject()
                .get("current_task").getAsLong());
    }

    @Test
    void missingMaterialsDoesNotAutomaticallyRetryOrReportSuccess() {
        runner.start();
        runner.submitTask("make pickaxe");
        client.engine().tools(0, new ToolCall("craft-call", "craft", "{\"item\":\"stone_pickaxe\"}"));
        client.drain();
        receipt(false, "MISSING_MATERIALS:need 3 cobblestone and 2 sticks; inventory unchanged");
        var result = assertInstanceOf(Msg.Tool.class, client.engine().histories.get(1).getLast());
        assertEquals("craft-call", result.callId());
        assertTrue(result.content().contains("MISSING_MATERIALS:"));
        assertEquals(1, client.toolCount());
        client.engine().reply(1, "missing materials");
        client.drain();
        assertEquals(1, client.toolCount());
    }
}
