package com.neko.mcbot.path;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PathSpecTest {
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
}
