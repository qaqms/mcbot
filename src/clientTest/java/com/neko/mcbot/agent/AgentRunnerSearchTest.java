package com.neko.mcbot.agent;

import com.google.gson.JsonObject;
import com.neko.mcbot.agentcore.llm.Msg;
import com.neko.mcbot.agentcore.llm.ToolCall;
import com.neko.mcbot.common.Envelope;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AgentRunnerSearchTest {
    private final OfflineAgentClient client = new OfflineAgentClient();
    private final AgentRunner runner = client.create();
    @AfterEach void close() { runner.close(); client.drain(); }

    private void start() {
        runner.start();
        runner.submitTask("[只读] 查询附近原木");
        client.engine().tools(0, new ToolCall("search", "search_resource",
                "{\"targets\":[\"#minecraft:logs\"]}"));
        client.drain();
    }
    private void receipt(long seq, JsonObject data) {
        var body = new JsonObject();
        body.addProperty("seq", seq);
        body.addProperty("ok", true);
        body.addProperty("feedback", "actual page");
        body.add("data", data);
        runner.handleS2c(new Envelope("tool_result", body));
        client.drain();
    }

    @Test void readonlyCompleteSearchReturnsOneModelResultAfterAllFiveActualPages() {
        start();
        for (int page = 0; page < 5; page++) {
            assertEquals(page + 1, client.toolCount());
            assertEquals("find_resource", client.lastTool().str("tool"));
            assertEquals(page * 4096, client.lastTool().obj("args").get("offset").getAsInt());
            assertEquals(16, client.lastTool().obj("args").get("r").getAsInt());
            assertEquals(1, client.engine().responses.size());
            receipt(client.lastTool().num("seq", -1), BoundedSearchTest.page(page * 4096, page == 4));
        }
        assertEquals(2, client.engine().responses.size());
        var results = client.engine().histories.getLast().stream().filter(Msg.Tool.class::isInstance)
                .map(Msg.Tool.class::cast).toList();
        assertEquals(1, results.size());
        assertEquals("search", results.getFirst().callId());
        assertTrue(results.getFirst().ok());
        assertTrue(results.getFirst().content().contains("minecraft:oak_log"));
        assertTrue(results.getFirst().content().contains("已读=17077"));
    }

    @Test void cancelledSearchCannotPageAgainAfterLateReceiptOrAReplacementTask() {
        start();
        long oldSeq = client.lastTool().num("seq", -1);
        assertTrue(runner.cancelTask(0));
        client.drain();
        runner.submitTask("[只读] 查看当前状态");
        client.drain();
        receipt(oldSeq, BoundedSearchTest.page(0, false));
        assertEquals(1, client.toolCount());
        assertEquals(2, client.engine().responses.size());
        client.engine().tools(1, new ToolCall("status", "status", "{}"));
        client.drain();
        assertEquals(2, client.toolCount());
        assertEquals("status", client.lastTool().str("tool"));
    }
}
