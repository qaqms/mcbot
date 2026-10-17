package com.neko.mcbot.agentcore.loop;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SpatialFactsTest {
    private JsonObject facts(String chunks) {
        return SpatialFacts.read(JsonParser.parseString("{\"spatial_facts\":{"
                + "\"dimension\":\"minecraft:overworld\",\"chunks\":" + chunks + "}}").getAsJsonObject());
    }

    @Test void unloadedAndMissingFactsInvalidateCoverageWithoutReplenishingFailures() {
        var old = facts("{\"0,0\":\"loaded:1\"}");
        var unloaded = facts("{\"0,0\":null}");
        assertFalse(SpatialFacts.retained(old, unloaded));
        assertFalse(SpatialFacts.changed(old, unloaded));
        assertFalse(SpatialFacts.changed(old, facts("{\"1,0\":\"other:2\"}")));
        assertTrue(SpatialFacts.changed(old, facts("{\"0,0\":\"replacement:0\"}")));
        assertFalse(SpatialFacts.retained(old, facts("{\"0,0\":\"replacement:0\"}")));
        assertTrue(SpatialFacts.retained(old, facts("{\"0,0\":\"loaded:1\",\"1,0\":\"other:2\"}")));
    }

    @Test void malformedOversizedAndNoncanonicalVersionsNeverEstablishFacts() {
        assertNull(facts("{\"00,0\":\"bad\"}"));
        assertNull(facts("{\"0,0\":12}"));
        assertNull(facts("{\"0,0\":\"\"}"));
        var chunks = new JsonObject();
        for (int i = 0; i < 82; i++) chunks.addProperty(i + ",0", "stamp");
        assertNull(facts(chunks.toString()));
        assertNull(SpatialFacts.read(new JsonObject()));
    }

    @Test void miningEvidenceExcludesUnrelatedChunkChanges() {
        var old = facts("{\"0,0\":\"a:1\",\"8,0\":\"b:1\"}");
        var args = JsonParser.parseString("{\"x\":4,\"z\":4}").getAsJsonObject();
        var local = SpatialFacts.forWork(old, "break_block", args);
        assertFalse(SpatialFacts.changed(local, facts("{\"0,0\":\"a:1\",\"8,0\":\"b:2\"}")));
        assertTrue(SpatialFacts.changed(local, facts("{\"0,0\":\"a:2\",\"8,0\":\"b:2\"}")));
        assertEquals(1, local.getAsJsonObject("chunks").size());
    }
}
