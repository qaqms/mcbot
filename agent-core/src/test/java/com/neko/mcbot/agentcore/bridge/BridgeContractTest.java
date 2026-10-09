package com.neko.mcbot.agentcore.bridge;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.LongConsumer;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

class BridgeContractTest {
    private static final class Backend implements BridgeBackend {
        long tasks;
        int answers;
        int cancels;
        long cancelled;
        LongConsumer submitted = id -> {};
        CompletableFuture<String> reply = new CompletableFuture<>();

        @Override public long submitTask(String text) { submitted.accept(++tasks); return tasks; }
        @Override public CompletableFuture<String> ask(String text) { tasks++; return reply; }
        @Override public boolean answer(String id, String text) { answers++; return id.equals("q8"); }
        @Override public String statusJson() {
            return "{\"in_game\":true,\"brain_enabled\":true,\"companion\":\"steve\",\"current_task\":0}";
        }
        @Override public boolean cancel(long id) { cancels++; cancelled = id; return id == 0; }
    }

    private static JsonObject json(String text) {
        return JsonParser.parseString(text).getAsJsonObject();
    }

    private static JsonObject resource(String name) throws Exception {
        try (var stream = BridgeContract.class.getResourceAsStream(name)) {
            assertNotNull(stream);
            return JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }

    private static JsonObject call(BridgeService service, String name, String args) {
        return json(service.handle("POST", "/mcp",
                "{\"jsonrpc\":\"2.0\",\"id\":\"connector-1\",\"method\":\"tools/call\","
                        + "\"params\":{\"name\":\"" + name + "\",\"arguments\":" + args + "}}").body())
                .getAsJsonObject("result");
    }

    private static JsonObject payload(JsonObject result) {
        return json(result.getAsJsonArray("content").get(0).getAsJsonObject().get("text").getAsString());
    }

    @Test
    void mcpDiscoveryUsesTheHandoffSchemasAndTaskDescriptions() throws Exception {
        var service = new BridgeService(new Backend(), new EventRing(200));
        var list = json(service.handle("POST", "/mcp",
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}").body())
                .getAsJsonObject("result").getAsJsonArray("tools");
        var definitions = resource("bridge-v1.schema.json").getAsJsonObject("$defs");
        List<String> names = List.of("mcbot_task", "mcbot_ask", "mcbot_answer", "mcbot_status", "mcbot_cancel");
        List<String> inputs = List.of("taskInput", "askInput", "answerInput", "statusInput", "cancelInput");
        assertEquals(names.size(), list.size());
        for (int i = 0; i < names.size(); i++) {
            var tool = list.get(i).getAsJsonObject();
            assertEquals(names.get(i), tool.get("name").getAsString());
            assertEquals(definitions.get(inputs.get(i)), tool.get("inputSchema"));
        }
        assertFalse(list.get(1).getAsJsonObject().get("description").getAsString().contains("闲聊一句"));
        JsonObject schema = BridgeContract.inputSchema("mcbot_task");
        schema.remove("required");
        assertTrue(BridgeContract.inputSchema("mcbot_task").has("required"), "callers cannot mutate discovery");
    }

    @Test
    void handoffTranscriptProducesTheSameRestAndMcpWindow() throws Exception {
        JsonObject examples = resource("bridge-v1.examples.json");
        for (boolean mcp : List.of(false, true)) {
            var ring = new EventRing(200);
            var backend = new Backend();
            var service = new BridgeService(backend, ring);
            backend.submitted = id -> {
                for (JsonElement raw : examples.getAsJsonArray("events")) {
                    var event = raw.getAsJsonObject();
                    service.recordEvent(event.get("ev").getAsString(), event);
                }
            };
            JsonObject result = mcp
                    ? payload(call(service, "mcbot_task", examples.get("task").toString()))
                    : json(service.handle("POST", "/v1/task", examples.get("task").toString()).body());
            assertEquals(examples.get("taskResult"), result);
            assertEquals(1, backend.tasks);
            for (var event : ring.since(0)) {
                var wire = json(event.dataJson());
                assertEquals(event.type(), wire.get("ev").getAsString());
                assertEquals("1.0", wire.get("contract_version").getAsString());
                UUID.fromString(wire.get("session_id").getAsString());
            }
        }
    }

    @Test
    void sessionIsSharedByStatusMcpAndEventsButChangesWithANewBridge() {
        var backend = new Backend();
        var service = new BridgeService(backend, new EventRing(200));
        var status = json(service.handle("GET", "/v1/status", null).body());
        assertEquals(status, payload(call(service, "mcbot_status", "{}")));
        var data = json("{\"task_id\":1,\"text\":\"receipt\"}");
        var event = json(service.recordEvent("progress", data).dataJson());
        assertEquals(status.get("session_id"), event.get("session_id"));
        assertFalse(data.has("session_id"), "stamping must not mutate producer data");
        var newService = new BridgeService(backend, new EventRing(200));
        assertNotEquals(status.get("session_id"),
                json(newService.handle("GET", "/v1/status", null).body()).get("session_id"));
    }

    @Test
    void invalidTaskParametersNeverSubmitOrEchoInput() {
        var backend = new Backend();
        var service = new BridgeService(backend, new EventRing(200));
        for (String body : List.of("{", "[]", "null", "{\"text\":\"secret\"} trailing",
                "{text:'secret'}", "{}", "{\"text\":12}", "{\"text\":true}", "{\"text\":null}",
                "{\"text\":\"  \"}", "{\"text\":\"secret\",\"wait_s\":\"0\"}",
                "{\"text\":\"secret\",\"wait_s\":0.5}", "{\"text\":\"secret\",\"wait_s\":null}")) {
            var result = service.handle("POST", "/v1/task", body);
            assertEquals(400, result.status(), body);
            assertEquals("INVALID_REQUEST", json(result.body()).get("error_code").getAsString());
            assertFalse(result.body().contains("secret"));
        }
        assertEquals(0, backend.tasks);
        for (String args : List.of("{}", "{\"text\":true}", "{\"text\":\"work\",\"wait_s\":false}")) {
            var result = call(service, "mcbot_task", args);
            assertTrue(result.get("isError").getAsBoolean());
            assertEquals("INVALID_REQUEST", payload(result).get("error_code").getAsString());
        }
        assertEquals(0, backend.tasks);
    }

    @Test
    void integerWindowsClampWithoutOverflowAndUnknownFieldsRemainCompatible() {
        var ring = new EventRing(200);
        var backend = new Backend();
        var service = new BridgeService(backend, ring);
        backend.submitted = id -> ring.add("done", "{\"task_id\":" + id + ",\"status\":\"completed\"}");
        for (String wait : List.of("-5", "0", "0.0", "99999999999999", "1e9999")) {
            assertEquals(200, service.handle("POST", "/v1/task",
                    "{\"text\":\"work\",\"wait_s\":" + wait + ",\"future_field\":true}").status());
        }
        assertEquals(200, service.handle("POST", "/v1/task", "{\"text\":\"work\"}").status());
    }

    @Test
    void cancellationIdsAreExactAndInvalidValuesNeverReachBackend() {
        var backend = new Backend();
        var service = new BridgeService(backend, new EventRing(200));
        for (String path : List.of("/v1/task/-1/cancel", "/v1/task/9223372036854775808/cancel")) {
            assertEquals(400, service.handle("POST", path, "").status());
        }
        for (String id : List.of("-1", "null", "\"0\"", "true", "1.3", "9223372036854775808", "1e9999")) {
            var result = call(service, "mcbot_cancel", "{\"task_id\":" + id + "}");
            assertTrue(result.get("isError").getAsBoolean());
        }
        assertEquals(0, backend.cancels);
        call(service, "mcbot_cancel", "{\"task_id\":9223372036854775807}");
        assertEquals(Long.MAX_VALUE, backend.cancelled);
        call(service, "mcbot_cancel", "{}");
        assertEquals(0, backend.cancelled);
    }

    @Test
    void answerValidationAndStaleQuestionsAgreeAcrossTransports() {
        var backend = new Backend();
        var service = new BridgeService(backend, new EventRing(200));
        for (String args : List.of("{}", "{\"question_id\":\"q8\",\"text\":\" \"}",
                "{\"question_id\":8,\"text\":\"answer\"}")) {
            assertEquals(400, service.handle("POST", "/v1/answer", args).status());
            assertTrue(call(service, "mcbot_answer", args).get("isError").getAsBoolean());
        }
        assertEquals(0, backend.answers);
        String stale = "{\"question_id\":\"q404\",\"text\":\"answer\"}";
        assertEquals(404, service.handle("POST", "/v1/answer", stale).status());
        var error = call(service, "mcbot_answer", stale);
        assertTrue(error.get("isError").getAsBoolean());
        assertEquals("NOT_FOUND", payload(error).get("error_code").getAsString());
        String valid = "{\"question_id\":\"q8\",\"text\":\"answer\"}";
        assertEquals("{\"ok\":true}", service.handle("POST", "/v1/answer", valid).body());
        assertFalse(call(service, "mcbot_answer", valid).get("isError").getAsBoolean());
    }

    @Test
    void legacyFragmentsIncludePublicAndQuestionTextButNotOtherTasks() {
        var ring = new EventRing(200);
        var backend = new Backend();
        var service = new BridgeService(backend, ring);
        backend.submitted = id -> {
            ring.add("progress", "{\"task_id\":99,\"text\":\"other\"}");
            ring.add("state", "{\"task_id\":0,\"text\":\"public\"}");
            ring.add("question", "{\"task_id\":1,\"question_id\":\"q8\",\"text\":\"question\"}");
            ring.add("state", "{\"task_id\":1,\"text\":\"answer accepted\"}");
        };
        var result = json(service.handle("POST", "/v1/task", "{\"text\":\"work\",\"wait_s\":0}").body());
        assertFalse(result.get("done").getAsBoolean());
        assertFalse(result.has("status"));
        assertEquals(JsonParser.parseString("[\"public\",\"question\",\"answer accepted\"]"), result.get("fragments"));
    }

    @Test
    void backendFailuresAreSanitizedAndDoNotResubmit() {
        var backend = new Backend();
        backend.submitted = id -> { throw new IllegalStateException("private credentials and request"); };
        var service = new BridgeService(backend, new EventRing(200));
        var rest = service.handle("POST", "/v1/task", "{\"text\":\"work\",\"wait_s\":0}");
        assertEquals(500, rest.status());
        assertFalse(rest.body().contains("private"));
        var mcp = call(service, "mcbot_task", "{\"text\":\"work\",\"wait_s\":0}");
        assertTrue(mcp.get("isError").getAsBoolean());
        assertEquals("INTERNAL", payload(mcp).get("error_code").getAsString());
        assertFalse(mcp.toString().contains("private"));
        assertEquals(2, backend.tasks);
    }

    @Test
    void askTimeoutReleasesWaiterWithoutCancellingTaskOrResubmitting() {
        for (boolean mcp : List.of(false, true)) {
            var backend = new Backend();
            var service = new BridgeService(backend, new EventRing(200), 5);
            if (mcp) {
                var result = call(service, "mcbot_ask", "{\"text\":\"work\"}");
                assertTrue(result.get("isError").getAsBoolean());
                assertEquals("ANSWER_TIMEOUT", payload(result).get("error_code").getAsString());
            } else {
                var result = service.handle("POST", "/v1/ask", "{\"text\":\"work\"}");
                assertEquals(504, result.status());
                assertEquals("ANSWER_TIMEOUT", json(result.body()).get("error_code").getAsString());
            }
            assertTrue(backend.reply.isCancelled());
            assertEquals(1, backend.tasks);
            assertEquals(0, backend.cancels);
        }
    }

    @Test
    void failedAskIsNotAnAnswerAndDoesNotLeakFailureText() {
        var backend = new Backend();
        backend.reply.completeExceptionally(new IllegalStateException("private endpoint"));
        var service = new BridgeService(backend, new EventRing(200));
        var result = service.handle("POST", "/v1/ask", "{\"text\":\"work\"}");
        assertEquals(500, result.status());
        assertEquals("TASK_FAILED", json(result.body()).get("error_code").getAsString());
        assertFalse(result.body().contains("private"));
    }

    @Test
    void rpcEnvelopeErrorsRemainRpcErrorsWithNullOrMatchingIds() {
        var service = new BridgeService(new Backend(), new EventRing(200));
        for (String body : List.of("{", "", "not json")) {
            var result = json(service.handle("POST", "/mcp", body).body());
            assertEquals(-32700, result.getAsJsonObject("error").get("code").getAsInt());
            assertTrue(result.get("id").isJsonNull());
        }
        for (String body : List.of("[]", "null", "{}", "{\"method\":\"tools/list\",\"id\":1}",
                "{\"jsonrpc\":2.0,\"method\":\"tools/list\",\"id\":1}",
                "{\"jsonrpc\":\"2.0\",\"method\":\"tools/list\",\"id\":true}")) {
            var result = json(service.handle("POST", "/mcp", body).body());
            assertEquals(-32600, result.getAsJsonObject("error").get("code").getAsInt());
        }
        var invalid = json(service.handle("POST", "/mcp",
                "{\"jsonrpc\":\"2.0\",\"id\":\"x\",\"method\":\"tools/call\",\"params\":{\"name\":\"mcbot_task\",\"arguments\":[]}}").body());
        assertEquals(-32602, invalid.getAsJsonObject("error").get("code").getAsInt());
        assertEquals("x", invalid.get("id").getAsString());
        assertEquals(202, service.handle("POST", "/mcp",
                "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}").status());
    }

    @Test
    void handoffAskAnswerAndCancelExamplesMatchBothTransports() throws Exception {
        var examples = resource("bridge-v1.examples.json");
        var backend = new Backend();
        backend.reply.complete("report");
        var service = new BridgeService(backend, new EventRing(200));
        assertEquals(json("{\"answer\":\"report\"}"), json(service.handle("POST", "/v1/ask",
                examples.get("ask").toString()).body()));
        assertEquals(json("{\"answer\":\"report\"}"), payload(call(service, "mcbot_ask",
                examples.get("ask").toString())));
        assertEquals(json("{\"ok\":true}"), json(service.handle("POST", "/v1/answer",
                examples.get("answer").toString()).body()));
        assertEquals(json("{\"ok\":true}"), payload(call(service, "mcbot_answer",
                examples.get("answer").toString())));
        assertEquals(json("{\"ok\":false}"), json(service.handle("POST", "/v1/task/1/cancel", "").body()));
        assertEquals(json("{\"ok\":false}"), payload(call(service, "mcbot_cancel",
                examples.get("cancel").toString())));
    }

    @Test
    void schemaNonBlankStringsMatchTheRuntimeUnicodeWhitespaceRule() {
        String expression = BridgeContract.inputSchema("mcbot_task").getAsJsonObject("properties")
                .getAsJsonObject("text").get("pattern").getAsString();
        var pattern = Pattern.compile(expression);
        for (String tool : List.of("mcbot_ask", "mcbot_answer")) {
            var properties = BridgeContract.inputSchema(tool).getAsJsonObject("properties");
            assertEquals(expression, properties.getAsJsonObject("text").get("pattern").getAsString());
            if (tool.equals("mcbot_answer"))
                assertEquals(expression, properties.getAsJsonObject("question_id").get("pattern").getAsString());
        }
        // Explicit ranges are portable; regex engines disagree on the shorthand \S.
        for (int point = 0; point <= Character.MAX_VALUE; point++) {
            String text = String.valueOf((char) point);
            assertEquals(!text.isBlank(), pattern.matcher(text).find(), "code point " + point);
        }
        assertFalse(pattern.matcher("").find());
        var backend = new Backend();
        var service = new BridgeService(backend, new EventRing(200));
        assertEquals(400, service.handle("POST", "/v1/task",
                "{\"text\":\"\\u3000\",\"wait_s\":0}").status());
        assertEquals(200, service.handle("POST", "/v1/task",
                "{\"text\":\"\\u00a0\",\"wait_s\":0}").status());
        assertEquals(1, backend.tasks);
    }
}
