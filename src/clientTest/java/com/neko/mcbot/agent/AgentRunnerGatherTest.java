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
        var body = new JsonObject();
        body.addProperty("seq", client.lastTool().num("seq", -1));
        body.addProperty("ok", true);
        body.addProperty("feedback", "world receipt");
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
}
