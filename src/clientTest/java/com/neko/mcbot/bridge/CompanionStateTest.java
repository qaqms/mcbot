package com.neko.mcbot.bridge;

import com.google.gson.JsonObject;
import com.neko.mcbot.agent.AgentRunner;
import com.neko.mcbot.cfg.ClientConfig;
import com.neko.mcbot.common.Envelope;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CompanionStateTest {
    private final List<JsonObject> events = new ArrayList<>();
    private final BridgeEvents.Sink sink = (type, data) -> {
        assertEquals("state", type);
        events.add(data.deepCopy());
    };

    @AfterEach
    void detach() {
        BridgeEvents.detach(sink);
    }

    private static Envelope state(String name, String text) {
        JsonObject body = new JsonObject();
        body.addProperty("companion", name);
        body.addProperty("text", text);
        return new Envelope("companion_state", body);
    }

    @Test
    void noCompanionSyncUpdatesPanelAndBridgeWithoutTouchingChat() {
        BridgeEvents.attach(sink);
        AgentRunner runner = new AgentRunner(new ClientConfig("", "", "", ""));
        runner.handleS2c(state("", "当前世界尚未召唤伙伴。"));
        assertEquals("", runner.companionName());
        assertEquals("当前世界尚未召唤伙伴。", runner.lifecycleResult());
        assertTrue(runner.transcriptSnapshot().isEmpty());
        assertEquals(1, events.size());
        assertEquals(0, events.getFirst().get("task_id").getAsLong());
        assertEquals("当前世界尚未召唤伙伴。", events.getFirst().get("text").getAsString());
        // No Minecraft instance exists in this test; a call to say() would fail here.
    }

    @Test
    void restoredCompanionSyncIsAlsoSilentAndClearsStaleName() {
        AgentRunner runner = new AgentRunner(new ClientConfig("", "", "", ""));
        runner.handleS2c(state("alex", "当前世界伙伴：alex"));
        assertEquals("alex", runner.companionName());
        assertEquals("当前世界伙伴：alex", runner.lifecycleResult());
        runner.handleS2c(state("", "当前世界尚未召唤伙伴。"));
        assertEquals("", runner.companionName());
        assertTrue(runner.transcriptSnapshot().isEmpty());
    }
}
