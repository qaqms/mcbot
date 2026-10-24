package com.neko.mcbot.path;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PathSpecTest {
    @Test void perToolReserveSurvivesSnapshotsAndRejectsInvalidWireValues() {
        var args = JsonParser.parseString(
                "{\"underground\":true,\"max_digs\":80,\"reserve_durability\":16}").getAsJsonObject();
        var spec = PathSpec.parse(args);
        assertEquals(16, spec.reserveDurability());
        assertEquals(spec, PathSpec.restore(spec.snapshot()));
        assertEquals(0, PathSpec.DEFAULT.reserveDurability());
        for (String value : java.util.List.of("-1", "1025", "1.5", "\"16\"", "null")) {
            args.add("reserve_durability", JsonParser.parseString(value));
            assertThrows(RuntimeException.class, () -> PathSpec.parse(args));
        }
    }
    @Test void scopeIncludesEveryCoordinateAndCannotBeCoercedFromStrings() {
        var spec = PathSpec.parse(JsonParser.parseString(
                "{\"underground\":true,\"max_digs\":12,\"bounds\":{\"x\":0,\"y\":64,\"z\":0,\"radius\":8}}")
                .getAsJsonObject());
        assertTrue(spec.contains(0, 56, 0));
        assertFalse(spec.contains(8, 63, 0));
        assertTrue(spec.underground());
        for (String text : new String[]{"{\"max_digs\":-1}", "{\"max_digs\":2147483648}", "{\"underground\":\"true\"}",
                "{\"arrival\":\"work\"}", "{\"bounds\":{\"x\":0,\"y\":0,\"z\":0,\"radius\":0}}"}) {
            assertThrows(RuntimeException.class, () -> PathSpec.parse(JsonParser.parseString(text).getAsJsonObject()));
        }
    }

    @Test void omittedDigLimitIsUnlimitedAndExplicitLimitsSurviveSnapshots() {
        assertEquals(Integer.MAX_VALUE, PathSpec.parse(new com.google.gson.JsonObject()).maxDigs());
        for (int count : new int[]{0, 129, 500, Integer.MAX_VALUE}) {
            var spec = PathSpec.parse(JsonParser.parseString("{\"max_digs\":" + count + "}").getAsJsonObject());
            assertEquals(count, PathSpec.restore(spec.snapshot()).maxDigs());
        }
    }

    @Test void explicitLargeScopeSurvivesRestoreAndOmittedScopeDoesNotCreateATaskSphere() {
        var unlimited = PathSpec.parse(new com.google.gson.JsonObject());
        assertTrue(unlimited.contains(1000, -58, 1000));
        var finite = PathSpec.parse(JsonParser.parseString(
                "{\"bounds\":{\"x\":0,\"y\":64,\"z\":0,\"radius\":256}}").getAsJsonObject());
        var restored = PathSpec.restore(finite.snapshot());
        assertTrue(restored.contains(120, -58, 0));
        assertFalse(restored.contains(257, 64, 0));
    }
}
