package com.neko.mcbot.agentcore.loop;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.neko.mcbot.agentcore.loop.ToolExecutor.ToolOutcome;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class WorkFailureMemoryTest {
    private static final JsonObject POINT = json("{\"x\":4,\"y\":70,\"z\":0}");
    private static JsonObject json(String text) { return JsonParser.parseString(text).getAsJsonObject(); }
    private static JsonObject state() {
        var state = AgentLoopGoalTest.state(1);
        state.addProperty("x", 0);
        state.addProperty("y", 70);
        state.addProperty("z", 0);
        state.addProperty("dimension", "minecraft:overworld");
        return state;
    }
    private void exhaust(WorkFailureMemory memory) {
        for (int i = 0; i < 5; i++) {
            assertNull(memory.refusal("move_to", POINT));
            memory.terminal("move_to", POINT, new ToolOutcome(false, "NO_PATH:elapsed=" + i));
        }
        assertNotNull(memory.refusal("move_to", POINT));
    }
    private void observe(WorkFailureMemory memory, JsonObject state) {
        memory.terminal("status", json("{\"details\":true}"), new ToolOutcome(true, "fresh", state));
    }

    @Test void structuredArgumentsAndStableReasonIgnoreTimingAndKeyOrderWithoutChangingResults() {
        var memory = new WorkFailureMemory();
        exhaust(memory);
        var blocked = memory.refusal("move_to", json("{\"z\":0.0,\"y\":70.0,\"x\":4.0}"));
        assertTrue(blocked.feedback().startsWith("REPEATED_FAILURE:"));
        var evidence = blocked.data().getAsJsonObject("failure_memory");
        assertEquals("NO_PATH", evidence.get("reason").getAsString());
        assertEquals(5, evidence.get("failures").getAsInt());
        assertFalse(evidence.get("dispatched").getAsBoolean());
        assertNull(memory.refusal("move_to", json("{\"x\":5,\"y\":70,\"z\":0}")));
        assertNull(memory.refusal("break_block", POINT));
    }

    @Test void bodyObservationHistoryNeverReplenishesAttemptsEvenWithRepeatedInventoryOrDamageFacts() {
        var memory = new WorkFailureMemory();
        exhaust(memory);
        var data = json("{\"body_delta\":{\"body_id\":\"body\",\"from_seq\":0,\"through_seq\":8,"
                + "\"net\":{\"carried_items\":[{\"item\":\"minecraft:stone\",\"count_delta\":3}],"
                + "\"selected_slot\":[0,1],\"health\":[20,18]}}}");
        for (int i = 0; i < 8; i++) {
            memory.terminal("move_to", POINT, new ToolOutcome(true, "same observed interval", data));
            memory.terminal("status", json("{\"details\":true}"), new ToolOutcome(true, "incomplete", data));
        }
        assertNotNull(memory.refusal("move_to", POINT));
    }

    @Test void argumentsAreFrozenAndOnlyKnownLocalFailuresCount() {
        var memory = new WorkFailureMemory();
        var args = POINT.deepCopy();
        memory.terminal("move_to", args, new ToolOutcome(false, "NO_PATH:first"));
        args.addProperty("x", 999);
        for (int i = 0; i < 8; i++) {
            for (String reason : new String[]{"DENIED", "NEED_CONFIRM", "WRONG_TOOL", "TIMEOUT",
                    "CANCELLED", "SCOPE_LIMIT", "UNSUPPORTED", "UNKNOWN"})
                memory.terminal("move_to", POINT, new ToolOutcome(false, reason + ":unchanged"));
            memory.terminal("move_to", POINT, ToolOutcome.accepted("job", "NO_PATH:not terminal"));
        }
        for (int i = 0; i < 3; i++)
            memory.terminal("move_to", POINT, new ToolOutcome(false, "NO_PATH:again"));
        assertNull(memory.refusal("move_to", POINT));
        memory.terminal("move_to", POINT, new ToolOutcome(false, "NO_PATH:fifth"));
        assertNotNull(memory.refusal("move_to", POINT));
        assertNull(memory.refusal("move_to", args));
    }

    @Test void onlyCompleteChangedFactsInvalidateFailuresNotFirstOrIncompleteObservation() {
        var memory = new WorkFailureMemory();
        exhaust(memory);
        var state = state();
        observe(memory, state);
        assertNotNull(memory.refusal("move_to", POINT), "first observation cannot prove a change");
        var incomplete = state.deepCopy();
        incomplete.addProperty("x", 2);
        incomplete.getAsJsonObject("inventory").getAsJsonArray("slots").remove(0);
        observe(memory, incomplete);
        assertNotNull(memory.refusal("move_to", POINT));
        state.addProperty("game_tick", 999);
        state.addProperty("hp", 18);
        state.addProperty("selected_slot", 2);
        var slots = state.getAsJsonObject("inventory").getAsJsonArray("slots");
        slots.get(0).getAsJsonObject().addProperty("damage", 100);
        var first = slots.get(0); var second = slots.get(1);
        slots.set(0, second); slots.set(1, first);
        observe(memory, state);
        assertNotNull(memory.refusal("move_to", POINT));
        state.addProperty("x", 0.9);
        observe(memory, state);
        assertNotNull(memory.refusal("move_to", POINT), "same foot cell is not a new approach");
        state.addProperty("x", 1);
        observe(memory, state);
        assertNull(memory.refusal("move_to", POINT));
    }

    @Test void inventoryAndDimensionChangesAlsoAllowReevaluation() {
        var memory = new WorkFailureMemory();
        var state = state();
        observe(memory, state);
        exhaust(memory);
        state.getAsJsonObject("inventory").getAsJsonArray("slots").get(0).getAsJsonObject()
                .addProperty("count", 2);
        observe(memory, state);
        assertNull(memory.refusal("move_to", POINT));
        exhaust(memory);
        state.addProperty("dimension", "minecraft:the_nether");
        observe(memory, state);
        assertNull(memory.refusal("move_to", POINT));
    }

    @Test void successNoopQueriesAndAggregateCountersCannotReplenishAttempts() {
        var memory = new WorkFailureMemory();
        exhaust(memory);
        memory.terminal("move_to", POINT, new ToolOutcome(true, "already there"));
        memory.terminal("craft", json("{\"query\":true}"),
                new ToolOutcome(true, "query", json("{\"crafted_count\":2}")));
        memory.terminal("status", json("{\"details\":true}"),
                new ToolOutcome(true, "not a body", json("{\"removed_blocks\":1}")));
        memory.terminal("gather", new JsonObject(),
                new ToolOutcome(true, "aggregate", json("{\"actual_progress\":10,\"collected_count\":10}")));
        memory.terminal("break_block", POINT,
                new ToolOutcome(false, "PARTIAL:unproved", json("{\"cleared_blocks\":0.5}")));
        assertNotNull(memory.refusal("move_to", POINT));
    }

    @Test void realPrimitiveEffectsIncludingPartialTerrainChangesInvalidateOldEvidence() {
        for (String[] effect : new String[][]{
                {"move_to", "{\"removed_blocks\":1}"}, {"move_to", "{\"placed_blocks\":1}"},
                {"break_block", "{\"removed\":true}"}, {"break_block", "{\"cleared_blocks\":1}"},
                {"place_block", "{\"changed\":true}"}, {"craft", "{\"crafted_count\":4}"},
                {"collect", "{\"collected_count\":1}"}, {"smelt", "{\"loaded_input_count\":1}"},
                {"smelt", "{\"loaded_fuel_count\":1}"}, {"smelt", "{\"taken_count\":1}"},
                {"transfer", "{\"moved_items\":1}"}}) {
            var memory = new WorkFailureMemory();
            exhaust(memory);
            var args = POINT.deepCopy();
            if (effect[0].equals("smelt"))
                args.addProperty("action", effect[1].contains("taken_count") ? "take" : "load");
            memory.terminal(effect[0], args, new ToolOutcome(false, "PARTIAL:actual effect", json(effect[1])));
            assertNull(memory.refusal("move_to", POINT), effect[0] + effect[1]);
        }
    }

    @Test void boundedStorageDoesNotEvictOldFailuresOrRestrictObservationsAndOtherCapabilities() {
        var memory = new WorkFailureMemory();
        exhaust(memory);
        for (int x = 5; x < 132; x++)
            memory.terminal("move_to", json("{\"x\":" + x + "}"), new ToolOutcome(false, "NO_PATH:other"));
        assertTrue(memory.refusal("move_to", POINT).feedback().startsWith("REPEATED_FAILURE:"));
        assertTrue(memory.refusal("move_to", json("{\"x\":1000}")).feedback().startsWith("FAILURE_MEMORY_LIMIT:"));
        assertNull(memory.refusal("status", new JsonObject()));
        assertNull(memory.refusal("place_block", POINT));
        memory.terminal("break_block", POINT, new ToolOutcome(true, "actual", json("{\"removed\":true}")));
        assertNull(memory.refusal("move_to", POINT));
    }
}
