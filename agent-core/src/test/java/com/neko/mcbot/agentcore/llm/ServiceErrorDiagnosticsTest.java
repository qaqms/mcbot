package com.neko.mcbot.agentcore.llm;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import static com.neko.mcbot.agentcore.llm.ServiceErrorDiagnostics.*;
import static org.junit.jupiter.api.Assertions.*;

class ServiceErrorDiagnosticsTest {
    private static ServiceErrorDiagnostics error(String json) {
        return ServiceErrorDiagnostics.from(JsonParser.parseString(json));
    }

    @Test
    void structuredCodesSeparateRequestQuotaAndUpstreamFailures() {
        String[] codes = {"invalid_api_key", "permission_error", "rate_limit_exceeded",
                "insufficient_quota", "model_not_found", "context_length_exceeded",
                "invalid_tool_call", "unsupported_parameter", "invalid_request_error",
                "content_policy_violation", "upstream_timeout", "upstream_error"};
        Category[] categories = {Category.AUTHENTICATION, Category.PERMISSION, Category.RATE_LIMIT,
                Category.QUOTA, Category.MODEL_NOT_FOUND, Category.CONTEXT_LIMIT, Category.TOOL_PROTOCOL,
                Category.UNSUPPORTED_PARAMETER, Category.INVALID_REQUEST, Category.CONTENT_POLICY,
                Category.TIMEOUT, Category.UPSTREAM};
        for (int i = 0; i < codes.length; i++) {
            var detail = error("{\"code\":\"" + codes[i] + "\",\"message\":\"private-value\"}");
            assertEquals(categories[i], detail.category());
            assertEquals(Source.CODE, detail.source());
            assertFalse(detail.toString().contains("private-value"));
        }
    }

    @Test
    void unknownCodeAndParameterNeverCrossTheBoundary() {
        var detail = error("{\"code\":\"private-code\",\"type\":\"private-type\","
                + "\"message\":\"Authorization: Bearer private-value\","
                + "\"param\":\"private-field\",\"status\":123456}");
        assertEquals(Category.UNKNOWN, detail.category());
        assertEquals(Source.UNKNOWN, detail.source());
        assertEquals(Category.UNKNOWN, detail.code());
        assertEquals(Category.UNKNOWN, detail.type());
        assertEquals(Param.OTHER, detail.param());
        assertEquals(0, detail.status());
        assertFalse(detail.summary().contains("private"));
        assertFalse(detail.toString().contains("private"));
    }

    @Test
    void typeAndBoundedStatusAreSeparateSignals() {
        var typed = error("{\"code\":null,\"type\":\"insufficient_quota\",\"status\":429}");
        assertEquals(Category.QUOTA, typed.category());
        assertEquals(Source.TYPE, typed.source());
        assertEquals(Category.NONE, typed.code());
        assertEquals(429, typed.status());
        var numeric = error("{\"code\":503}");
        assertEquals(Category.UPSTREAM, numeric.category());
        assertEquals(Source.STATUS, numeric.source());
        assertEquals(503, numeric.status());
        assertEquals(Category.NOT_FOUND, error("{\"status\":404}").category());
        assertEquals(0, error("{\"status\":429.5}").status());
    }

    @Test
    void protocolMessageHintIsWeakerThanStructuredEvidence() {
        var hint = error("{\"type\":\"invalid_request_error\",\"param\":\"messages[3].role\","
                + "\"message\":\"assistant tool_calls must be followed by tool_call_id private-value\"}");
        assertEquals(Category.TOOL_PROTOCOL, hint.category());
        assertEquals(Category.INVALID_REQUEST, hint.type());
        assertEquals(Source.MESSAGE_HINT, hint.source());
        assertEquals(Param.MESSAGES, hint.param());
        var explicit = error("{\"code\":\"invalid_api_key\","
                + "\"message\":\"maximum context length private-value\"}");
        assertEquals(Category.AUTHENTICATION, explicit.category());
        assertEquals(Source.CODE, explicit.source());
        assertFalse(hint.summary().contains("private"));
    }

    @Test
    void malformedAndOversizedFieldsRemainSafeAndUnclassified() {
        for (String json : new String[]{"null", "true", "42", "[]", "\"private-value\"",
                "{\"code\":{},\"type\":[],\"param\":{},\"status\":\"401\"}"}) {
            var detail = error(json);
            assertFalse(detail.summary().contains("private-value"));
            assertEquals(json.equals("null") ? Category.NONE : Category.UNKNOWN, detail.category());
        }
        var detail = error("{\"message\":\"" + "private-value".repeat(400)
                + " maximum context length\",\"param\":\"" + "x".repeat(200) + "\"}");
        assertEquals(Category.UNKNOWN, detail.category());
        assertEquals(Param.OTHER, detail.param());
        assertFalse(detail.toString().contains("private"));
    }

    @Test
    void differingFramesAreNotHiddenByTheFirstError() {
        var first = error("{\"code\":\"insufficient_quota\"}");
        var second = error("{\"type\":\"upstream_error\"}");
        assertEquals(first, NONE.merge(first));
        assertEquals(first, first.merge(NONE));
        assertEquals(first, first.merge(first));
        assertEquals(Category.MIXED, first.merge(second).category());
        assertEquals(Source.MIXED, first.merge(second).source());
    }
}
