package com.neko.mcbot.agentcore.loop;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.neko.mcbot.agentcore.loop.ToolExecutor.ToolOutcome;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

class WorkFailureMemoryTest {
    private static final JsonObject POINT = json("{\"x\":4,\"y\":70,\"z\":0}");
    private static JsonObject json(String text) { return JsonParser.parseString(text).getAsJsonObject(); }
    private static JsonObject state() {
        var value = AgentLoopGoalTest.state(1);
        value.addProperty("x", 0); value.addProperty("y", 70); value.addProperty("z", 0);
        value.addProperty("dimension", "minecraft:overworld");
        return value;
    }
    private static void observe(WorkFailureMemory memory, JsonObject state) {
        memory.terminal("status", json("{\"details\":true}"), new ToolOutcome(true, "fresh", state));
    }
    private static void record(WorkFailureMemory memory, String tool, int count) {
        for (int i = 0; i < count; i++)
            memory.terminal(tool, POINT, new ToolOutcome(false, "NO_PATH:elapsed=" + i));
    }

    @ParameterizedTest @ValueSource(strings = {"move_to", "follow", "break_block"})
    void failuresBeyondFiveRemainDiagnosticAndSurviveRecovery(String tool) {
        var memory = new WorkFailureMemory();
        record(memory, tool, 27);
        assertEquals(27, memory.failures(tool, POINT));
        var copy = WorkFailureMemory.restore(memory.snapshot());
        record(copy, tool, 1);
        assertEquals(28, copy.failures(tool, POINT));
        assertEquals(27, memory.failures(tool, POINT));
    }

    @Test void structuredArgumentsAreFrozenAndNumericKeyOrderDoesNotChangeIdentity() {
        var memory = new WorkFailureMemory();
        var args = POINT.deepCopy();
        memory.terminal("move_to", args, new ToolOutcome(false, "NO_PATH:first"));
        args.addProperty("x", 999);
        assertEquals(1, memory.failures("move_to", json("{\"z\":0.0,\"y\":70.0,\"x\":4.0}")));
        assertEquals(0, memory.failures("move_to", args));
        assertEquals(0, memory.failures("break_block", POINT));
    }

    @Test void acceptedProgressPermissionsAndUnknownResultsAreNotLocalFailures() {
        var memory = new WorkFailureMemory();
        for (String reason : new String[]{"DENIED", "NEED_CONFIRM", "WRONG_TOOL", "TIMEOUT",
                "CANCELLED", "SCOPE_LIMIT", "UNSUPPORTED", "UNKNOWN"})
            memory.terminal("move_to", POINT, new ToolOutcome(false, reason + ":unchanged"));
        memory.terminal("move_to", POINT, ToolOutcome.accepted("job", "NO_PATH:not terminal"));
        assertEquals(0, memory.failures("move_to", POINT));
        assertTrue(WorkFailureMemory.localFailure("follow", "NO_PATH:dry gap"));
    }

    @Test void firstIncompleteAndCosmeticObservationsCannotInventChangedFacts() {
        var memory = new WorkFailureMemory();
        record(memory, "move_to", 9);
        var fresh = state();
        observe(memory, fresh);
        var incomplete = fresh.deepCopy();
        incomplete.addProperty("x", 2);
        incomplete.getAsJsonObject("inventory").getAsJsonArray("slots").remove(0);
        observe(memory, incomplete);
        fresh.addProperty("game_tick", 999); fresh.addProperty("hp", 18);
        fresh.addProperty("selected_slot", 2);
        fresh.getAsJsonObject("inventory").getAsJsonArray("slots").get(0).getAsJsonObject().addProperty("damage", 100);
        observe(memory, fresh);
        assertEquals(9, memory.failures("move_to", POINT));
        fresh.addProperty("x", 1);
        observe(memory, fresh);
        assertEquals(0, memory.failures("move_to", POINT));
    }

    @ParameterizedTest @ValueSource(strings = {"inventory", "dimension", "terrain"})
    void changedFactsInvalidateOutdatedDiagnostics(String kind) {
        var memory = new WorkFailureMemory();
        var fresh = state();
        fresh.add("spatial_facts", json("{\"dimension\":\"minecraft:overworld\",\"chunks\":{\"0,0\":\"c:1\"}}"));
        observe(memory, fresh);
        record(memory, "move_to", 7);
        switch (kind) {
            case "inventory" -> fresh.getAsJsonObject("inventory").getAsJsonArray("slots").get(0)
                    .getAsJsonObject().addProperty("count", 2);
            case "dimension" -> fresh.addProperty("dimension", "minecraft:the_nether");
            default -> fresh.getAsJsonObject("spatial_facts").getAsJsonObject("chunks").addProperty("0,0", "c:2");
        }
        observe(memory, fresh);
        assertEquals(0, memory.failures("move_to", POINT));
    }

    @Test void queryNoopsAggregateAndHistoricalEffectsDoNotEraseFailures() {
        var memory = new WorkFailureMemory();
        record(memory, "move_to", 7);
        memory.terminal("move_to", POINT, new ToolOutcome(true, "already there"));
        memory.terminal("craft", json("{\"query\":true}"), new ToolOutcome(true, "query", json("{\"crafted_count\":2}")));
        memory.terminal("gather", new JsonObject(), new ToolOutcome(true, "aggregate", json("{\"collected_count\":10}")));
        memory.terminal("break_block", POINT, new ToolOutcome(false, "PARTIAL:unproved", json("{\"cleared_blocks\":0.5}")));
        memory.terminal("move_to", POINT, new ToolOutcome(true, "historical",
                json("{\"body_delta\":{\"net\":{\"health\":[20,18]}},\"drop_accounting\":{\"picked_up_self_count\":64}}")));
        assertEquals(7, memory.failures("move_to", POINT));
    }

    @Test void actualPrimitiveEffectsInvalidateOldEvidenceIncludingPartialActions() {
        for (String[] effect : new String[][]{
                {"move_to", "{\"removed_blocks\":1}"}, {"move_to", "{\"placed_blocks\":1}"},
                {"break_block", "{\"removed\":true}"}, {"break_block", "{\"cleared_blocks\":1}"},
                {"place_block", "{\"changed\":true}"}, {"craft", "{\"crafted_count\":4}"},
                {"collect", "{\"collected_count\":1}"}, {"smelt", "{\"loaded_input_count\":1}"},
                {"smelt", "{\"loaded_fuel_count\":1}"}, {"smelt", "{\"taken_count\":1}"},
                {"transfer", "{\"moved_items\":1}"}}) {
            var memory = new WorkFailureMemory();
            record(memory, "move_to", 7);
            var args = POINT.deepCopy();
            if (effect[0].equals("smelt"))
                args.addProperty("action", effect[1].contains("taken_count") ? "take" : "load");
            memory.terminal(effect[0], args, new ToolOutcome(false, "PARTIAL:actual", json(effect[1])));
            assertEquals(0, memory.failures("move_to", POINT), effect[0] + effect[1]);
        }
    }

    @Test void historyCapacityRollsWithoutBlockingNewWork() {
        var memory = new WorkFailureMemory();
        for (int x = 0; x < 200; x++)
            memory.terminal("move_to", json("{\"x\":" + x + "}"), new ToolOutcome(false, "NO_PATH:other"));
        assertEquals(128, memory.snapshot().getAsJsonArray("entries").size());
        assertEquals(1, memory.failures("move_to", json("{\"x\":199}")));
        assertEquals(0, memory.failures("move_to", json("{\"x\":0}")));
        assertEquals(memory.snapshot(), WorkFailureMemory.restore(memory.snapshot()).snapshot());
    }
}
