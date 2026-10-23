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
        assertFalse(config.preferences.autonomousActions());
        assertTrue(config.preferences.showHud());
        assertEquals(17897, config.bridgePort);
    }

    @Test void autonomousPreferenceIsPersistedAndSurvivesUnrelatedDraftEdits() {
        var original = new ClientConfig("http://127.0.0.1", "offline", "synthetic", "persona")
                .withPreferences(ClientConfig.Preferences.defaults().withAutonomousActions(true));
        var restored = ClientConfig.fromJson(ClientConfig.toJson(original), Map.of());
        assertTrue(restored.preferences.autonomousActions());
        assertEquals(original.preferences, restored.preferences);
        assertTrue(restored.withConnection("http://127.0.0.1", "other", "synthetic", "updated")
                .preferences.autonomousActions());
    }

    @Test void malformedAutonomyNeverEnablesItAndContextFallbackDoesNotChangeItsExplicitValue() {
        for (String value : new String[]{"\"true\"", "1", "null", "{}", "[]"}) {
            var json = com.google.gson.JsonParser.parseString("{\"autonomous_actions\":" + value + "}").getAsJsonObject();
            assertFalse(ClientConfig.fromJson(json, Map.of()).preferences.autonomousActions());
        }
        var json = new JsonObject();
        json.addProperty("autonomous_actions", true);
        json.addProperty("compact_threshold", -1);
        var preferences = ClientConfig.fromJson(json, Map.of()).preferences;
        assertEquals(6000, preferences.compactThreshold());
        assertTrue(preferences.autonomousActions());
    }
    @Test void preferencesRoundTripWithoutChangingTheConnection() {
        var config = new ClientConfig("http://127.0.0.1", "offline", "synthetic", "persona", false)
                .withPreferences(new ClientConfig.Preferences(64000, 48000, 8000, false, false, true, false));
        var restored = ClientConfig.fromJson(ClientConfig.toJson(config), Map.of());
        assertEquals(config.preferences, restored.preferences);
        assertFalse(config.connectionChanged(restored));
        assertEquals("persona", restored.persona);
        assertFalse(restored.acceptMode);
        assertFalse(restored.preferences.showHud());
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

    @Test void bridgePortRoundTripsAndSurvivesConnectionAndPreferenceEdits() {
        var original = new ClientConfig("http://127.0.0.1", "offline", "synthetic", "persona", false)
                .withBridgePort(17898);
        var restored = ClientConfig.fromJson(ClientConfig.toJson(original), Map.of());
        assertEquals(17898, restored.bridgePort);
        assertEquals(17898, restored.withConnection("http://127.0.0.1", "other", "synthetic", "").bridgePort);
        assertEquals(17898, restored.withPreferences(ClientConfig.Preferences.defaults()).bridgePort);
        assertFalse(original.connectionChanged(restored));
        assertFalse(original.connectionChanged(original.withBridgePort(17899)));
        assertFalse(restored.acceptMode);
    }

    @Test void bridgePortRejectsInvalidUiValuesAndLoadsMalformedFilesWithoutEchoingThem() {
        for (int value : new int[]{0, -1, 65536, Integer.MAX_VALUE})
            assertThrows(IllegalArgumentException.class,
                    () -> new ClientConfig("", "", "", "").withBridgePort(value));
        for (String value : new String[]{"0", "-1", "65536", "1.5", "\"private-port\"", "null", "true", "[]"})
            assertEquals(17897, ClientConfig.fromJson(com.google.gson.JsonParser.parseString(
                    "{\"bridge_port\":" + value + "}").getAsJsonObject(), Map.of()).bridgePort);
        for (int value : new int[]{1, 65535})
            assertEquals(value, ClientConfig.fromJson(ClientConfig.toJson(
                    new ClientConfig("", "", "", "").withBridgePort(value)), Map.of()).bridgePort);
    }
}
