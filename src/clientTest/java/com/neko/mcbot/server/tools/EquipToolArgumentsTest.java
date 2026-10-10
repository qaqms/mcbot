package com.neko.mcbot.server.tools;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class EquipToolArgumentsTest {
    @Test
    void acceptsEveryStorageSlotIncludingNumericIntegerRepresentations() {
        for (int slot = 0; slot < 36; slot++) {
            JsonObject args = new JsonObject();
            args.addProperty("slot", slot);
            assertEquals(slot, EquipTool.sourceSlot(args));
        }
        assertEquals(13, EquipTool.sourceSlot(JsonParser.parseString("{\"slot\":13.0}").getAsJsonObject()));
        assertEquals(10, EquipTool.sourceSlot(JsonParser.parseString("{\"slot\":1e1}").getAsJsonObject()));
    }

    @Test
    void rejectsMalformedOrEquipmentSlotsBeforeAccessingTheBody() {
        assertNull(EquipTool.sourceSlot(null));
        for (String text : new String[]{"{}", "{\"slot\":null}", "{\"slot\":true}", "{\"slot\":\"13\"}",
                "{\"slot\":[]}", "{\"slot\":{}}", "{\"slot\":13.01}", "{\"slot\":-1}",
                "{\"slot\":36}", "{\"slot\":40}", "{\"slot\":42}", "{\"slot\":1e100}",
                "{\"slot\":4294967296}", "{\"slot\":-4294967283}", "{\"slot\":1e-100}"}) {
            JsonObject args = JsonParser.parseString(text).getAsJsonObject();
            var future = new EquipTool().runAsync(null, args, null);
            assertTrue(future.isDone(), text);
            assertFalse(future.join().ok(), text);
            assertTrue(future.join().feedback().startsWith("DENIED:"), text);
        }
    }
}
