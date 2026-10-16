package com.neko.mcbot.agent;

import com.neko.mcbot.agentcore.provider.StreamingTurnReader;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ClientToolStreamingTest {
    @Test void reducedDescriptionsKeepProductionEarlyDispatchForProgramsAndHelp() {
        var reader = new StreamingTurnReader(ToolCatalog.MODEL_SPECS, true);
        reader.toolCallDelta(0, "program", "run_program", "{\"code\":\"return ");
        assertTrue(reader.drainReady().isEmpty());
        reader.toolCallDelta(0, null, null, "true\"}");
        assertEquals("run_program", reader.drainReady().getFirst().call().name());
        assertTrue(reader.drainReady().isEmpty());
        reader.toolCallDelta(1, "help", "tool_help", "{\"names\":[\"craft\"]}");
        assertEquals("tool_help", reader.drainReady().getFirst().call().name());
        assertTrue(reader.drainReady().isEmpty());
    }

    @Test
    void productionCatalogAllowsInventoryAndCompleteMiningAtFirstCallIndex() {
        var inventory = new StreamingTurnReader(ClientToolDefs.SPECS, true);
        inventory.toolCallDelta(0, "inventory0", "inventory", "{}");
        assertEquals("inventory", inventory.drainReady().getFirst().call().name());

        var mining = new StreamingTurnReader(ClientToolDefs.SPECS, true);
        mining.toolCallDelta(0, "mining0", "break_block", "{\"x\":7,\"y\":-61,");
        assertTrue(mining.drainReady().isEmpty());
        mining.toolCallDelta(0, null, null, "\"z\":-1}");
        assertEquals("break_block", mining.drainReady().getFirst().call().name());
        assertTrue(mining.drainReady().isEmpty());
    }
}
