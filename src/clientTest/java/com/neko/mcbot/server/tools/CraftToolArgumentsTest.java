package com.neko.mcbot.server.tools;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CraftToolArgumentsTest {
    @Test
    void acceptsCanonicalIdsDefaultsAndBoundedNumericIntegers() {
        var defaults = CraftTool.arguments(JsonParser.parseString("{\"item\":\"stick\"}").getAsJsonObject());
        assertNotNull(defaults);
        assertEquals("minecraft:stick", defaults.item().toString());
        assertEquals(1, defaults.count());
        assertFalse(defaults.query());
        assertNull(defaults.recipe());
        for (int count = 1; count <= 64; count++) {
            var args = JsonParser.parseString("{\"item\":\"example:part\",\"count\":" + count
                    + ",\"query\":true,\"recipe\":\"example:part_from_wood\"}").getAsJsonObject();
            var parsed = CraftTool.arguments(args);
            assertEquals(count, parsed.count());
            assertTrue(parsed.query());
            assertEquals("example:part_from_wood", parsed.recipe().toString());
        }
        assertEquals(10, CraftTool.arguments(JsonParser.parseString(
                "{\"item\":\"stick\",\"count\":1e1}").getAsJsonObject()).count());
        assertEquals(13, CraftTool.arguments(JsonParser.parseString(
                "{\"item\":\"stick\",\"count\":13.0}").getAsJsonObject()).count());
    }

    @Test
    void rejectsMalformedArgumentsWithoutAccessingTheBody() {
        assertNull(CraftTool.arguments(null));
        for (String text : new String[]{"{}", "{\"item\":null}", "{\"item\":false}", "{\"item\":12}",
                "{\"item\":[]}", "{\"item\":{}}", "{\"item\":\"\"}", "{\"item\":\" \"}",
                "{\"item\":\"MINECRAFT:stick\"}", "{\"item\":\"invalid space\"}",
                "{\"item\":\"stick\",\"recipe\":null}", "{\"item\":\"stick\",\"recipe\":false}",
                "{\"item\":\"stick\",\"recipe\":[]}", "{\"item\":\"stick\",\"recipe\":\"\"}",
                "{\"item\":\"stick\",\"query\":null}", "{\"item\":\"stick\",\"query\":\"true\"}",
                "{\"item\":\"stick\",\"query\":1}", "{\"item\":\"stick\",\"query\":{}}",
                "{\"item\":\"stick\",\"count\":null}", "{\"item\":\"stick\",\"count\":\"3\"}",
                "{\"item\":\"stick\",\"count\":true}", "{\"item\":\"stick\",\"count\":[]}",
                "{\"item\":\"stick\",\"count\":0}", "{\"item\":\"stick\",\"count\":65}",
                "{\"item\":\"stick\",\"count\":-1}", "{\"item\":\"stick\",\"count\":2.5}",
                "{\"item\":\"stick\",\"count\":1e100}", "{\"item\":\"stick\",\"count\":4294967297}",
                "{\"item\":\"" + "x".repeat(129) + "\"}",
                "{\"item\":\"stick\",\"recipe\":\"" + "x".repeat(129) + "\"}"}) {
            var future = new CraftTool().runAsync(null, JsonParser.parseString(text).getAsJsonObject(), null);
            assertTrue(future.isDone(), text);
            assertFalse(future.join().ok(), text);
            assertTrue(future.join().feedback().startsWith("DENIED:"), text);
        }
    }
}
