package com.neko.mcbot.server.tools;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SmeltToolArgumentsTest {
    private static SmeltTool.Arguments parse(String suffix) {
        return SmeltTool.arguments(JsonParser.parseString("{\"x\":2,\"y\":90,\"z\":-3" + suffix + "}")
                .getAsJsonObject());
    }

    @Test
    void defaultsToReadonlyQueryAndValidatesStorageSlotsAndCounts() {
        var query = parse("");
        assertNotNull(query);
        assertEquals("query", query.action());
        assertEquals(-3, query.pos().getZ());
        for (int slot = 0; slot <= 35; slot++) {
            var load = parse(",\"action\":\"load\",\"input_slot\":" + slot
                    + ",\"input_count\":64,\"fuel_slot\":" + slot + ",\"fuel_count\":1");
            assertNotNull(load);
            assertEquals(slot, load.inputSlot());
            assertEquals(slot, load.fuelSlot());
            assertEquals(64, load.inputCount());
        }
        var defaults = parse(",\"action\":\"load\",\"input_slot\":13.0");
        assertEquals(13, defaults.inputSlot());
        assertEquals(1, defaults.inputCount());
        assertEquals(10, parse(",\"action\":\"load\",\"fuel_slot\":1e1").fuelSlot());
    }

    @Test
    void takeUsesNamedMachineSlotAndAtMostCount() {
        assertEquals(2, parse(",\"action\":\"take\"").takeSlot());
        assertEquals(64, parse(",\"action\":\"take\"").count());
        assertEquals(0, parse(",\"action\":\"take\",\"slot\":\"input\",\"count\":1").takeSlot());
        assertEquals(1, parse(",\"action\":\"take\",\"slot\":\"fuel\",\"count\":2e1").takeSlot());
        for (int count = 1; count <= 64; count++) {
            assertEquals(count, parse(",\"action\":\"take\",\"count\":" + count).count());
        }
    }

    @Test
    void malformedOrMisplacedFieldsAreDeniedBeforeAccessingTheBody() {
        assertNull(SmeltTool.arguments(null));
        for (String suffix : new String[]{
                ",\"action\":null", ",\"action\":true", ",\"action\":[]", ",\"action\":\"LOAD\"",
                ",\"action\":\"load\"", ",\"action\":\"load\",\"input_count\":2",
                ",\"action\":\"load\",\"input_slot\":36", ",\"action\":\"load\",\"fuel_slot\":-1",
                ",\"action\":\"load\",\"input_slot\":null", ",\"action\":\"load\",\"input_slot\":\"3\"",
                ",\"action\":\"load\",\"fuel_slot\":true", ",\"action\":\"load\",\"input_slot\":1.5",
                ",\"action\":\"load\",\"input_slot\":4294967297", ",\"action\":\"load\",\"input_slot\":1e100",
                ",\"action\":\"load\",\"input_slot\":0,\"input_count\":0",
                ",\"action\":\"load\",\"input_slot\":0,\"input_count\":65",
                ",\"action\":\"load\",\"fuel_slot\":0,\"fuel_count\":\"2\"",
                ",\"action\":\"load\",\"fuel_slot\":0,\"fuel_count\":2.5",
                ",\"action\":\"take\",\"slot\":2", ",\"action\":\"take\",\"slot\":null",
                ",\"action\":\"take\",\"slot\":\"all\"", ",\"action\":\"take\",\"count\":0",
                ",\"action\":\"take\",\"count\":65", ",\"action\":\"take\",\"count\":true",
                ",\"input_slot\":1", ",\"query\":true", ",\"action\":\"take\",\"input_slot\":1",
                ",\"action\":\"load\",\"input_slot\":1,\"slot\":\"output\"",
                ",\"action\":\"load\",\"fuel_slot\":1,\"fuel_count\":null", ",\"unexpected\":false"
        }) {
            var json = JsonParser.parseString("{\"x\":2,\"y\":90,\"z\":-3" + suffix + "}").getAsJsonObject();
            var result = new SmeltTool().runAsync(null, json, null);
            assertTrue(result.isDone(), suffix);
            assertFalse(result.join().ok(), suffix);
            assertTrue(result.join().feedback().startsWith("DENIED:"), suffix);
        }
        for (String coordinates : new String[]{"{}", "{\"x\":1,\"y\":2}", "{\"x\":\"1\",\"y\":2,\"z\":3}",
                "{\"x\":1,\"y\":null,\"z\":3}", "{\"x\":1,\"y\":2,\"z\":[]}",
                "{\"x\":2147483648,\"y\":2,\"z\":3}", "{\"x\":1,\"y\":2.5,\"z\":3}"}) {
            assertNull(SmeltTool.arguments(JsonParser.parseString(coordinates).getAsJsonObject()), coordinates);
        }
    }
}
