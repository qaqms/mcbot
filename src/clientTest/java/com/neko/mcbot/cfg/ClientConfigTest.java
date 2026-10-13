package com.neko.mcbot.cfg;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ClientConfigTest {
    @Test void oldConfigKeepsTheExistingCompactionBudgetAndStopgap() {
        var json = new JsonObject();
        json.addProperty("accept_mode", false);
        var config = ClientConfig.fromJson(json, Map.of());
        assertEquals(6000, config.preferences.compactThreshold());
        assertEquals(1500, config.preferences.recentBudget());
        assertFalse(config.acceptMode);
        assertTrue(config.preferences.autoCompact());
    }
    @Test void preferencesRoundTripWithoutChangingTheConnection() {
        var config = new ClientConfig("http://127.0.0.1", "offline", "synthetic", "persona", false)
                .withPreferences(new ClientConfig.Preferences(64000, 48000, 8000, false, false, true, false));
        var restored = ClientConfig.fromJson(ClientConfig.toJson(config), Map.of());
        assertEquals(config.preferences, restored.preferences);
        assertFalse(config.connectionChanged(restored));
        assertEquals("persona", restored.persona);
        assertFalse(restored.acceptMode);
    }
    @Test void invalidOrNonIntegerPreferencesFallBackAsAGroup() {
        for (Object value : new Object[]{-1, 2.5, "48000", true}) {
            var json = new JsonObject();
            if (value instanceof Number number) json.addProperty("compact_threshold", number);
            else if (value instanceof String string) json.addProperty("compact_threshold", string);
            else json.addProperty("compact_threshold", (Boolean) value);
            assertEquals(ClientConfig.Preferences.defaults(), ClientConfig.fromJson(json, Map.of()).preferences);
        }
        assertThrows(IllegalArgumentException.class, () -> new ClientConfig.Preferences(64000, 64000, 1500, true, true, false, true));
        assertThrows(IllegalArgumentException.class, () -> new ClientConfig.Preferences(64000, 48000, 48000, true, true, false, true));
    }
    @Test void environmentOverrideNamesSurviveDraftChangesWithoutExposingValues() {
        var config = ClientConfig.fromJson(new JsonObject(), Map.of(
                "MCBOT_MODEL", "override-model", "MCBOT_API_KEY", "synthetic-private"));
        assertEquals(java.util.Set.of("model", "api_key"), config.environmentOverrides);
        assertEquals(config.environmentOverrides, config.withPreferences(config.preferences).environmentOverrides);
        assertEquals("override-model", config.model);
    }
}
