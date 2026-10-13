package com.neko.mcbot.agentcore.provider;

import com.neko.mcbot.agentcore.llm.ToolSpec;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class StreamingTurnReaderTest {
    private static final ToolSpec WORKFLOW = ToolSpec.of("workflow", "",
            "{\"type\":\"object\",\"required\":[\"steps\"]}");
    private static final ToolSpec STATUS = ToolSpec.of("status", "",
            "{\"type\":\"object\"}");
    private static final ToolSpec MOVE = ToolSpec.of("move_to", "",
            "{\"type\":\"object\",\"required\":[\"x\",\"y\",\"z\"]}");
    private static final String POSITION = "{\"x\":1,\"y\":2,\"z\":3}";

    @Test
    void firstCallUsesNamedToolRatherThanFirstCatalogEntry() {
        var reader = new StreamingTurnReader(List.of(WORKFLOW, STATUS, MOVE), true);
        reader.toolCallDelta(0, "c0", "move_to", POSITION);
        var ready = reader.drainReady();
        assertEquals(1, ready.size());
        assertEquals("move_to", ready.getFirst().call().name());
        assertEquals(POSITION, ready.getFirst().call().argsJson());
        assertTrue(reader.drainReady().isEmpty());
    }

    @Test
    void optionalFirstCatalogEntryCannotRelaxAnotherToolsRequiredFields() {
        var reader = new StreamingTurnReader(List.of(STATUS, MOVE), true);
        assertNull(reader.toolCallDeltaChecked(0, "c0", "move_to", "{\"x\":1}"));
        assertTrue(reader.drainReady().isEmpty());
    }

    @Test
    void repeatedCallsBeyondCatalogSizeStillUseTheNamedSchema() {
        var reader = new StreamingTurnReader(List.of(MOVE), true);
        reader.toolCallDelta(0, "c0", "move_to", POSITION);
        reader.toolCallDelta(1, "c1", "move_to", "{\"x\":1}");
        reader.toolCallDelta(2, "c2", "move_to", POSITION);
        assertEquals(List.of(0, 2),
                reader.drainReady().stream().map(StreamingTurnReader.ReadyCall::index).toList());
    }

    @Test
    void lateNameReplaysArgumentsAgainstItsOwnSchema() {
        var reader = new StreamingTurnReader(List.of(WORKFLOW, MOVE), true);
        reader.toolCallDelta(0, "c0", null, "{\"x\":1,");
        assertTrue(reader.drainReady().isEmpty());
        reader.toolCallDelta(0, null, "move_to", null);
        assertTrue(reader.drainReady().isEmpty());
        reader.toolCallDelta(0, null, null, "\"y\":2,\"z\":3}");
        assertEquals(POSITION, reader.drainReady().getFirst().call().argsJson());
    }

    @Test
    void lateNameCannotBypassRequiredFields() {
        var reader = new StreamingTurnReader(List.of(STATUS, MOVE), true);
        reader.toolCallDelta(0, "c0", null, "{\"x\":1}");
        reader.toolCallDelta(0, null, "move_to", null);
        assertTrue(reader.drainReady().isEmpty());
    }

    @Test
    void unknownToolWaitsForNormalTurnHandling() {
        var reader = new StreamingTurnReader(List.of(STATUS), true);
        reader.toolCallDelta(0, "c0", "unknown", "{}");
        assertTrue(reader.drainReady().isEmpty());
        assertEquals("unknown", reader.builder().build().toolCalls().getFirst().name());
    }

    @Test
    void readinessUsesActualIndicesInAscendingOrder() {
        var reader = new StreamingTurnReader(List.of(MOVE), true);
        reader.toolCallDelta(4, "c4", "move_to", POSITION);
        reader.toolCallDelta(0, "c0", "move_to", POSITION);
        assertEquals(List.of(0, 4),
                reader.drainReady().stream().map(StreamingTurnReader.ReadyCall::index).toList());
    }

    @Test
    void replacementObjectCannotHideMalformedAccumulatedArguments() {
        var reader = new StreamingTurnReader(List.of(MOVE), true);
        reader.toolCallDelta(0, "c0", "move_to", "{\"x\":1}");
        assertNull(reader.toolCallDeltaChecked(0, null, null, POSITION));
        assertTrue(reader.drainReady().isEmpty());
    }

    @Test
    void lateCallIdIsRequiredBeforePublishingReadiness() {
        var reader = new StreamingTurnReader(List.of(MOVE), true);
        reader.toolCallDelta(0, null, "move_to", POSITION);
        assertTrue(reader.drainReady().isEmpty());
        reader.toolCallDelta(0, "c0", null, null);
        assertEquals("c0", reader.drainReady().getFirst().call().id());
    }
}
