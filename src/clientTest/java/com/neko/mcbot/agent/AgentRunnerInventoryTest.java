package com.neko.mcbot.agent;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.neko.mcbot.agentcore.llm.Msg;
import com.neko.mcbot.agentcore.llm.ToolCall;
import com.neko.mcbot.agentcore.llm.ToolSpec;
import com.neko.mcbot.common.Envelope;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AgentRunnerInventoryTest {
    private final OfflineAgentClient client = new OfflineAgentClient();
    private final AgentRunner runner = client.create();

    @AfterEach
    void close() {
        runner.close();
        client.drain();
    }

    private ToolSpec definition(String name) {
        return client.engine().definitions.getFirst().stream()
                .filter(spec -> spec.name().equals(name)).findFirst().orElseThrow();
    }

    private void receipt(boolean ok, String feedback) {
        JsonObject body = new JsonObject();
        body.addProperty("seq", client.lastTool().num("seq", -1));
        body.addProperty("ok", ok);
        body.addProperty("feedback", feedback);
        var data = new JsonObject();
        data.addProperty("selected_slot", 4);
        body.add("data", data);
        runner.handleS2c(new Envelope("tool_result", body));
        client.drain();
    }

    @Test
    void advertisesInventoryAndStrictMainhandSlotSchemaToTheActualRunner() {
        runner.start();
        runner.submitTask("inspect tools");
        assertEquals(List.of(), definition("inventory").requiredFields());
        assertEquals(List.of("slot"), definition("equip").requiredFields());
        var slot = JsonParser.parseString(definition("equip").paramsJsonSchema()).getAsJsonObject()
                .getAsJsonObject("properties").getAsJsonObject("slot");
        assertEquals("integer", slot.get("type").getAsString());
        assertEquals(0, slot.get("minimum").getAsInt());
        assertEquals(35, slot.get("maximum").getAsInt());
        var definitions = client.engine().definitions.getFirst();
        assertEquals(definitions.size(), definitions.stream().map(ToolSpec::name).distinct().count());
    }

    @Test
    void inventoryFeedbackReachesTheNextModelTurnBeforeEquipAndFinalReport() {
        runner.start();
        runner.submitTask("select the pickaxe");
        client.engine().tools(0, new ToolCall("inventory-call", "inventory", "{}"));
        client.drain();
        assertEquals("inventory", client.lastTool().str("tool"));
        assertTrue(client.lastTool().obj("args").entrySet().isEmpty());
        String details = "slot 13: minecraft:iron_pickaxe x1 durability 233/250; selected slot 4";
        receipt(true, details);
        var firstReceipt = assertInstanceOf(Msg.Tool.class, client.engine().histories.get(1).getLast());
        assertEquals("inventory-call", firstReceipt.callId());
        assertEquals(details, firstReceipt.content(), "data alone is not exposed to the model");
        client.engine().tools(1, new ToolCall("equip-call", "equip", "{\"slot\":13}"));
        client.drain();
        assertEquals("equip", client.lastTool().str("tool"));
        assertEquals(13, client.lastTool().obj("args").get("slot").getAsInt());
        receipt(true, "selected slot 4 holds minecraft:iron_pickaxe; old hand moved to slot 13");
        var secondReceipt = assertInstanceOf(Msg.Tool.class, client.engine().histories.get(2).getLast());
        assertEquals("equip-call", secondReceipt.callId());
        assertTrue(secondReceipt.content().contains("old hand moved to slot 13"));
        client.engine().reply(2, "ready");
        client.drain();
        assertEquals(2, client.toolCount());
        assertEquals(0, JsonParser.parseString(runner.statusJson()).getAsJsonObject().get("current_task").getAsLong());
    }

    @Test
    void busyEquipReceiptIsPairedAndDoesNotCauseAnAutomaticResend() {
        runner.start();
        runner.submitTask("switch tool");
        client.engine().tools(0, new ToolCall("equip-call", "equip", "{\"slot\":35}"));
        client.drain();
        receipt(false, "BUSY:wait for the current task or cancel it first");
        var result = assertInstanceOf(Msg.Tool.class, client.engine().histories.get(1).getLast());
        assertEquals("equip-call", result.callId());
        assertTrue(result.content().contains("BUSY:"));
        assertEquals(1, client.toolCount());
        client.engine().reply(1, "waiting");
        client.drain();
        assertEquals(1, client.toolCount());
    }
}
