package com.neko.mcbot.agent;

import com.google.gson.JsonObject;
import com.neko.mcbot.agentcore.llm.Msg;
import com.neko.mcbot.agentcore.llm.ToolCall;
import com.neko.mcbot.common.Envelope;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AgentRunnerObservationTest {
    private final OfflineAgentClient client = new OfflineAgentClient();
    private final AgentRunner runner = client.create();
    @AfterEach void close() { runner.close(); client.drain(); }
    private void reply(long seq, boolean ok, String text) {
        var body = new JsonObject();
        body.addProperty("seq", seq); body.addProperty("ok", ok); body.addProperty("feedback", text);
        runner.handleS2c(new Envelope("tool_result", body)); client.drain();
    }
    private void start() { client.liveObservation = true; runner.start(); runner.submitTask("find wood"); client.drain(); }
    @Test void initialObservationIsTaskBoundAndFetchedBeforeModelRequest() {
        start();
        assertTrue(client.engine().responses.isEmpty());
        assertEquals("status", client.lastTool().str("tool"));
        assertTrue(client.lastTool().obj("args").get("details").getAsBoolean());
        assertTrue(client.lastTool().num("task_id", 0) > 0);
        assertEquals("task_begin", client.sent.getFirst().kind());
        reply(client.lastTool().num("seq", -1), true, "position-one oak_log7");
        assertTrue(assertInstanceOf(Msg.Nudge.class, client.engine().histories.getFirst().getLast()).text().contains("oak_log7"));
        assertEquals(0, client.engine().histories.getFirst().stream().filter(m -> m instanceof Msg.Tool).count());
    }
    @Test void eachToolTerminalRequiresAnotherFreshSnapshotWithoutChangingReceipt() {
        start();
        reply(client.lastTool().num("seq", -1), true, "old-position");
        client.engine().tools(0, new ToolCall("wood", "find_resource", "{\"targets\":[\"oak_log\"]}"));
        client.drain();
        reply(client.lastTool().num("seq", -1), true, "wood coordinate");
        assertEquals("status", client.lastTool().str("tool"));
        assertEquals(1, client.engine().responses.size());
        reply(client.lastTool().num("seq", -1), true, "new-position");
        var history = client.engine().histories.get(1);
        assertFalse(history.stream().anyMatch(m -> m instanceof Msg.Nudge n && n.text().contains("old-position")));
        assertEquals("wood coordinate", history.stream().filter(m -> m instanceof Msg.Tool).map(m -> (Msg.Tool)m).findFirst().orElseThrow().content());
    }
    @Test void cancelledObservationAndLateReplyCannotResumeModel() {
        start();
        long seq = client.lastTool().num("seq", -1);
        assertTrue(runner.cancelTask(0)); client.drain();
        reply(seq, true, "late");
        assertTrue(client.engine().responses.isEmpty());
        assertEquals(0, com.google.gson.JsonParser.parseString(runner.statusJson()).getAsJsonObject().get("pending_tools").getAsInt());
    }
    @Test void failedOrTimedOutObservationStopsTaskWithoutModelRequest() {
        start();
        reply(client.lastTool().num("seq", -1), false, "DENIED:no companion");
        assertTrue(client.engine().responses.isEmpty());
        assertEquals(0, com.google.gson.JsonParser.parseString(runner.statusJson()).getAsJsonObject().get("current_task").getAsInt());
        runner.submitTask("second"); client.drain();
        client.now += 90001; runner.tick(); client.drain();
        assertTrue(client.engine().responses.isEmpty());
    }
}
