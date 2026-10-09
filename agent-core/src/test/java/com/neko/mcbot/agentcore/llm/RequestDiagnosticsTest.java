package com.neko.mcbot.agentcore.llm;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.neko.mcbot.agentcore.provider.OpenAiCompatProvider;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RequestDiagnosticsTest {
    private static final OpenAiCompatProvider PROVIDER = new OpenAiCompatProvider(
            "test", "https://example.test", "private-key", "private-model");
    private static final ToolSpec SPEC = ToolSpec.of("private-tool", "private-description", "{}");
    private static final ToolCall CALL = new ToolCall("private-call", "private-tool", "{\"x\":1}");

    private static RequestDiagnostics request(List<Msg> history) {
        return request(PROVIDER.buildBody("private-system", history, List.of(SPEC)));
    }

    private static RequestDiagnostics request(JsonObject body) {
        return RequestDiagnostics.from(42, 1, body, body.toString().getBytes(StandardCharsets.UTF_8).length);
    }

    @Test
    void freshAndToolFollowupRequestsHaveDifferentSafeCounts() {
        var fresh = request(List.of(new Msg.User("private-user")));
        var followup = request(List.of(new Msg.User("private-user"),
                new Msg.Assistant("", List.of(CALL)),
                new Msg.Tool(CALL.id(), CALL.name(), "private-result", true)));
        assertEquals(42, fresh.requestId());
        assertEquals(2, fresh.messages());
        assertEquals(1, fresh.system());
        assertEquals(1, fresh.user());
        assertEquals(1, fresh.toolDefinitions());
        assertTrue(fresh.definitionBytes() > 0);
        assertEquals(RequestDiagnostics.Role.USER, fresh.lastRole());
        assertEquals(RequestDiagnostics.Role.TOOL, followup.lastRole());
        assertEquals(1, followup.calls());
        assertEquals(1, followup.nullAssistant());
        assertEquals(1, followup.tool());
        assertEquals(0, followup.missingResults());
        assertEquals(0, followup.orphanResults());
        assertEquals(0, followup.invalidCalls());
        assertEquals(0, followup.invalidArguments());
        assertTrue(followup.bytes() > fresh.bytes());
        assertFalse(followup.summary().contains("private"));
        assertFalse(followup.toString().contains("private"));
    }

    @Test
    void missingOrphanAndInterruptedToolGroupsAreCountedWithoutRewritingHistory() {
        JsonObject body = PROVIDER.buildBody("sys", List.of(
                new Msg.Assistant("", List.of(CALL)),
                new Msg.User("private-user"),
                new Msg.Tool(CALL.id(), CALL.name(), "private-result", true)), List.of(SPEC));
        String before = body.toString();
        var detail = request(body);
        assertEquals(1, detail.missingResults());
        assertEquals(1, detail.orphanResults());
        assertEquals(1, detail.interruptedGroups());
        assertEquals(before, body.toString());
        assertEquals(1, request(List.of(new Msg.Assistant("", List.of(CALL)))).missingResults());
    }

    @Test
    void duplicateCallsDuplicateResultsAndMismatchedNamesAreSeparate() {
        var duplicateCalls = request(List.of(new Msg.Assistant("", List.of(CALL, CALL)),
                new Msg.Tool(CALL.id(), "private-other", "private-result", true),
                new Msg.Tool(CALL.id(), CALL.name(), "private-result", true)));
        assertEquals(1, duplicateCalls.duplicateCalls());
        assertEquals(1, duplicateCalls.duplicateResults());
        assertEquals(1, duplicateCalls.nameMismatches());
        assertEquals(0, duplicateCalls.orphanResults());
        assertEquals(0, duplicateCalls.missingResults());
    }

    @Test
    void callIdsCanBeReusedAcrossCompleteHistoricalGroups() {
        var detail = request(List.of(new Msg.Assistant("", List.of(CALL)),
                new Msg.Tool(CALL.id(), CALL.name(), "private-result", true),
                new Msg.User("private-user"),
                new Msg.Assistant("", List.of(CALL)),
                new Msg.Tool(CALL.id(), CALL.name(), "private-result", true)));
        assertEquals(0, detail.duplicateCalls());
        assertEquals(0, detail.duplicateResults());
        assertEquals(0, detail.missingResults());
        assertEquals(0, detail.orphanResults());
    }

    @Test
    void invalidArgumentsAreCountedSeparatelyFromInvalidCallShape() {
        for (String arguments : new String[]{"not-json", "[]", "null", "\"private-value\""}) {
            var detail = request(List.of(new Msg.Assistant("", List.of(
                    new ToolCall("private-call", "private-tool", arguments)))));
            assertEquals(1, detail.invalidArguments());
            assertEquals(0, detail.invalidCalls());
        }
        var blankId = request(List.of(new Msg.Assistant("", List.of(new ToolCall("", "private-tool", "{}")))));
        assertEquals(1, blankId.invalidCalls());
        assertEquals(0, blankId.invalidArguments());
    }

    @Test
    void optionsAndUnknownRolesRemainFixedEnums() {
        JsonObject body = JsonParser.parseString("{\"model\":\"private-model\",\"stream\":true,"
                + "\"stream_options\":{\"include_usage\":true},\"tool_choice\":\"required\","
                + "\"parallel_tool_calls\":false,\"messages\":[{\"role\":\"private-role\","
                + "\"content\":\"private-value\"}]}").getAsJsonObject();
        var detail = request(body);
        assertEquals(RequestDiagnostics.Setting.TRUE, detail.stream());
        assertEquals(RequestDiagnostics.Setting.TRUE, detail.includeUsage());
        assertEquals(RequestDiagnostics.Setting.FALSE, detail.parallelToolCalls());
        assertEquals(RequestDiagnostics.ToolChoice.REQUIRED, detail.toolChoice());
        assertEquals(RequestDiagnostics.Role.OTHER, detail.lastRole());
        assertEquals(1, detail.other());
        assertEquals(0, detail.toolDefinitions());
        body.addProperty("stream", "private-stream");
        body.addProperty("tool_choice", "private-choice");
        var unknown = request(body);
        assertEquals(RequestDiagnostics.Setting.OTHER, unknown.stream());
        assertEquals(RequestDiagnostics.ToolChoice.OTHER, unknown.toolChoice());
        assertFalse(unknown.summary().contains("private"));
    }
}
