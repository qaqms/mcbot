package com.neko.mcbot.agentcore.loop;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BodyFactsTest {
    @Test void sharedUnsafeFactIsAuthoritativeEvenWhenTheBodyIsAlreadyDry() {
        assertTrue(BodyFacts.environmentUnsafe(JsonParser.parseString(
                "{\"environment_work_safe\":false,\"eyes_in_water\":false}").getAsJsonObject()));
        assertFalse(BodyFacts.environmentUnsafe(JsonParser.parseString(
                "{\"environment_work_safe\":true,\"on_fire\":false}").getAsJsonObject()));
    }

    @Test void olderServersKeepNativeDangerChecksWithoutInventingMissingFacts() {
        assertFalse(BodyFacts.environmentUnsafe(JsonParser.parseString("{}").getAsJsonObject()));
        for (String key : new String[]{"eyes_in_water", "in_lava", "on_fire"}) {
            assertTrue(BodyFacts.environmentUnsafe(JsonParser.parseString(
                    "{\"" + key + "\":true}").getAsJsonObject()));
        }
    }

    @Test void malformedSignalsAreUnknownRatherThanAnImplicitPermissionToContinue() {
        assertThrows(IllegalArgumentException.class, () -> BodyFacts.environmentUnsafe(
                JsonParser.parseString("{\"environment_work_safe\":\"true\"}").getAsJsonObject()));
        assertThrows(IllegalArgumentException.class, () -> BodyFacts.environmentUnsafe(
                JsonParser.parseString("{\"in_lava\":null}").getAsJsonObject()));
    }
}
