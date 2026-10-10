package com.neko.mcbot.agent;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.neko.mcbot.agentcore.llm.Msg;
import com.neko.mcbot.agentcore.llm.ToolCall;
import com.neko.mcbot.common.Envelope;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AgentRunnerItemTransferTest {
    private final OfflineAgentClient client = new OfflineAgentClient();
    private final AgentRunner runner = client.create();

    @AfterEach
    void close() {
        runner.close();
        client.drain();
    }

    private void call(int turn, String id, String tool, String args) {
        client.engine().tools(turn, new ToolCall(id, tool, args));
        client.drain();
        assertEquals(tool, client.lastTool().str("tool"));
        assertEquals(JsonParser.parseString(args), client.lastTool().obj("args"));
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
    void schemasExplainPartialMovesAndBoundCollectRadius() {
        runner.start();
        runner.submitTask("inspect available tools");
        var definitions = client.engine().definitions.getFirst();
        var transfer = definitions.stream().filter(spec -> spec.name().equals("transfer")).findFirst().orElseThrow();
        var collect = definitions.stream().filter(spec -> spec.name().equals("collect")).findFirst().orElseThrow();
        assertTrue(transfer.description().contains("ok=false"));
        assertTrue(transfer.description().contains("留在来源"));
        assertTrue(collect.description().contains("ok=false"));
        var radius = JsonParser.parseString(collect.paramsJsonSchema()).getAsJsonObject()
                .getAsJsonObject("properties").getAsJsonObject("r");
        assertEquals(1, radius.get("minimum").getAsInt());
        assertEquals(12, radius.get("maximum").getAsInt());
    }

    @Test
    void partialTransferAndPickupReceiptsReachNextTurnWithoutAutomaticReplay() {
        runner.start();
        runner.submitTask("take and collect what fits");
        call(0, "take", "transfer", "{\"x\":2,\"y\":90,\"z\":0,\"dir\":\"out\"}");
        String transfer = "从容器取出 4 个物品。仍有 6 个留在来源，未丢弃。";
        receipt(false, transfer);
        var take = assertInstanceOf(Msg.Tool.class, client.engine().histories.get(1).getLast());
        assertEquals("take", take.callId());
        assertEquals(transfer, take.content());
        assertEquals(1, client.toolCount());
        call(1, "pick", "collect", "{}");
        String pickup = "捡起 4 个；仍有 6 个留在地上，先腾出空间。";
        receipt(false, pickup);
        var pick = assertInstanceOf(Msg.Tool.class, client.engine().histories.get(2).getLast());
        assertEquals("pick", pick.callId());
        assertEquals(pickup, pick.content());
        assertEquals(2, client.toolCount());
        call(2, "check", "inventory", "{}");
        receipt(true, "inventory now contains 8 collected items");
        client.engine().reply(3, "8 taken, 12 remain at their sources");
        client.drain();
        assertEquals(3, client.toolCount());
    }

    @Test
    void busyAndCancelledTransferCannotAutomaticallyResendOrContinueOnLateResult() {
        runner.start();
        long task = runner.submitTask("put items away");
        call(0, "busy", "transfer", "{\"x\":2,\"y\":90,\"z\":0,\"dir\":\"in\"}");
        receipt(false, "BUSY:wait or cancel before transferring");
        assertEquals(1, client.toolCount());
        call(1, "send", "transfer", "{\"x\":2,\"y\":90,\"z\":0,\"dir\":\"in\"}");
        long seq = client.lastTool().num("seq", -1);
        assertTrue(runner.cancelTask(task));
        client.drain();
        JsonObject late = new JsonObject();
        late.addProperty("seq", seq);
        late.addProperty("ok", false);
        late.addProperty("feedback", "4 moved, 6 remain");
        runner.handleS2c(new Envelope("tool_result", late));
        client.drain();
        assertEquals(2, client.toolCount());
        assertEquals(2, client.engine().responses.size());
        runner.submitTask("check actual inventory before another move");
        call(2, "check", "inventory", "{}");
        assertEquals(3, client.toolCount());
        assertTrue(client.engine().histories.get(2).stream().anyMatch(message -> message instanceof Msg.Tool tool
                && tool.callId().equals("send") && tool.content().startsWith("CANCELLED:")));
        assertFalse(client.engine().histories.get(2).stream().anyMatch(message -> message instanceof Msg.Tool tool
                && tool.content().equals("4 moved, 6 remain")));
    }
}
