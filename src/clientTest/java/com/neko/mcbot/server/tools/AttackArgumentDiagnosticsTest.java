package com.neko.mcbot.server.tools;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.neko.mcbot.server.ServerTool;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AttackArgumentDiagnosticsTest {
    private static final String UUID = "12345678-abcd-4321-abcd-123456789abc";

    private static JsonObject json(String text) {
        return JsonParser.parseString(text).getAsJsonObject();
    }

    private static ServerTool.Result denied(JsonObject args, String code, String field) {
        var pending = new AttackTool().runAuthorized(null, args, null, null, null);
        assertTrue(pending.isDone(), "invalid arguments must finish before accessing a body or scheduler");
        var result = pending.join();
        assertFalse(result.ok());
        assertTrue(result.feedback().startsWith("DENIED:"));
        assertTrue(result.feedback().contains("[" + code + "]"));
        assertNotNull(result.data(), "a parameter rejection must identify its cause");
        var error = result.data().getAsJsonObject("argument_error");
        assertEquals(code, error.get("code").getAsString());
        assertEquals(field, error.get("field").getAsString());
        assertFalse(error.get("expected").getAsString().isBlank());
        assertEquals(3, error.size());
        assertEquals(1, result.data().size());
        return result;
    }

    @Test void absentArgumentsAndUnknownFieldsHaveSafeDistinctReasons() {
        denied(null, "ARGUMENTS_REQUIRED", "args");
        denied(json("{}"), "ENTITY_ID_REQUIRED", "entity_id");
        var args = json("{\"entity_id\":\"hostile_nearby\"}");
        args.addProperty("private-unrecognized-field", "private-unrecognized-value");
        var result = denied(args, "UNKNOWN_FIELD", "args");
        assertFalse(result.feedback().contains("private-unrecognized"));
        assertFalse(result.data().toString().contains("private-unrecognized"));
    }

    @Test void entityIdTypeAndValueAreDistinguishedWithoutCoercion() {
        for (String value : new String[]{"\"47\"", "null", "true", "{}", "[]", "\"nearby\""}) {
            denied(json("{\"entity_id\":" + value + ",\"target_uuid\":\"" + UUID + "\"}"),
                    "ENTITY_ID_TYPE", "entity_id");
        }
        for (String value : new String[]{"0", "-1", "1.5", "2147483648", "1e100"}) {
            denied(json("{\"entity_id\":" + value + ",\"target_uuid\":\"" + UUID + "\"}"),
                    "ENTITY_ID_VALUE", "entity_id");
        }
    }

    @Test void uuidMissingTypeAndFormatAreDistinguished() {
        denied(json("{\"entity_id\":47}"), "TARGET_UUID_REQUIRED", "target_uuid");
        for (String value : new String[]{"null", "false", "47", "{}", "[]"}) {
            denied(json("{\"entity_id\":47,\"target_uuid\":" + value + "}"),
                    "TARGET_UUID_TYPE", "target_uuid");
        }
        for (String value : new String[]{"", "bad", "1-1-1-1-1", UUID.toUpperCase(), UUID + " "}) {
            denied(json("{\"entity_id\":47,\"target_uuid\":\"" + value + "\"}"),
                    "TARGET_UUID_FORMAT", "target_uuid");
        }
    }

    @Test void nearbySelectorCannotCarryAnExplicitIdentityOrApproval() {
        denied(json("{\"entity_id\":\"hostile_nearby\",\"target_uuid\":\"" + UUID + "\"}"),
                "NEARBY_TARGET_UUID", "target_uuid");
        denied(json("{\"entity_id\":\"hostile_nearby\",\"authorization_id\":\"proposal\"}"),
                "NEARBY_AUTHORIZATION", "authorization_id");
    }

    @Test void hitBudgetTypeAndValueAreDistinguished() {
        for (String value : new String[]{"\"2\"", "null", "false", "{}", "[]"}) {
            denied(json("{\"entity_id\":\"hostile_nearby\",\"max_hits\":" + value + "}"),
                    "MAX_HITS_TYPE", "max_hits");
        }
        for (String value : new String[]{"0", "-1", "11", "2.1", "2147483648", "1e100"}) {
            denied(json("{\"entity_id\":\"hostile_nearby\",\"max_hits\":" + value + "}"),
                    "MAX_HITS_VALUE", "max_hits");
        }
    }

    @Test void approvalTypeAndValueAreDistinguishedWithoutEchoingTokens() {
        for (String value : new String[]{"null", "true", "47", "{}", "[]"}) {
            denied(json("{\"entity_id\":47,\"target_uuid\":\"" + UUID + "\",\"authorization_id\":" + value + "}"),
                    "AUTHORIZATION_ID_TYPE", "authorization_id");
        }
        for (String value : new String[]{"", " ", "private-token-".repeat(6)}) {
            var args = json("{\"entity_id\":47,\"target_uuid\":\"" + UUID + "\"}");
            args.addProperty("authorization_id", value);
            var result = denied(args, "AUTHORIZATION_ID_VALUE", "authorization_id");
            assertFalse(result.feedback().contains("private-token"));
            assertFalse(result.data().toString().contains("private-token"));
            assertFalse(result.data().toString().contains(UUID));
        }
    }

    @Test void validContractsAndCallerArgumentsRemainUnchanged() {
        var explicit = json("{\"entity_id\":47,\"target_uuid\":\"" + UUID
                + "\",\"max_hits\":10,\"authorization_id\":\"proposal\"}");
        var original = explicit.deepCopy();
        var input = AttackTool.arguments(explicit);
        assertNotNull(input);
        assertEquals(47, input.entityId());
        assertEquals(java.util.UUID.fromString(UUID), input.uuid());
        assertEquals(10, input.maxHits());
        assertEquals("proposal", input.authorizationId());
        assertEquals(original, explicit);
        assertNotNull(AttackTool.arguments(json("{\"entity_id\":47,\"target_uuid\":\"" + UUID + "\"}")));
        var nearby = AttackTool.arguments(json("{\"entity_id\":\"hostile_nearby\"}"));
        assertNotNull(nearby);
        assertTrue(nearby.nearby());
        assertEquals(1, nearby.maxHits());
    }
}
