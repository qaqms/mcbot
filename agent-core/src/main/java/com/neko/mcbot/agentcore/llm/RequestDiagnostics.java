package com.neko.mcbot.agentcore.llm;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Counts the actual wire body without retaining its values, IDs, names, or content hashes. */
public record RequestDiagnostics(long requestId, int attempt, long bytes, int messages,
                                 int system, int user, int assistant, int tool, int other,
                                 long systemChars, long userChars, long assistantChars, long toolChars,
                                 int nullAssistant, int calls, int toolDefinitions, long definitionBytes,
                                 int missingResults, int orphanResults, int duplicateCalls,
                                 int duplicateResults, int nameMismatches, int invalidCalls, int invalidArguments,
                                 int interruptedGroups, Role lastRole, Setting stream,
                                 Setting includeUsage, ToolChoice toolChoice, Setting parallelToolCalls) {
    public enum Role { SYSTEM, USER, ASSISTANT, TOOL, OTHER, ABSENT }
    public enum Setting { TRUE, FALSE, ABSENT, OTHER }
    public enum ToolChoice { AUTO, NONE, REQUIRED, NAMED, ABSENT, OTHER }

    public static RequestDiagnostics from(long id, int attempt, JsonObject body, long bytes) {
        Analysis analysis = new Analysis();
        for (JsonElement element : array(body.get("messages"))) analysis.message(element);
        analysis.missing += analysis.pending.size();
        JsonArray definitions = array(body.get("tools"));
        JsonObject options = object(body.get("stream_options"));
        return new RequestDiagnostics(id, attempt, bytes, analysis.messages, analysis.system,
                analysis.user, analysis.assistant, analysis.tool, analysis.other, analysis.systemChars,
                analysis.userChars, analysis.assistantChars, analysis.toolChars, analysis.nullAssistant,
                analysis.calls, definitions.size(),
                definitions.isEmpty() ? 0 : definitions.toString().getBytes(StandardCharsets.UTF_8).length,
                analysis.missing, analysis.orphans, analysis.duplicateCalls, analysis.duplicateResults,
                analysis.nameMismatches, analysis.invalidCalls, analysis.invalidArguments, analysis.interrupted,
                analysis.lastRole, setting(body.get("stream")), setting(options.get("include_usage")),
                toolChoice(body.get("tool_choice")), setting(body.get("parallel_tool_calls")));
    }

    public String summary() {
        return "request=" + requestId + " attempt=" + attempt + " bytes=" + bytes
                + " messages=" + messages + " system=" + system + " user=" + user
                + " assistant=" + assistant + " tool=" + tool + " other=" + other
                + " system_chars=" + systemChars + " user_chars=" + userChars
                + " assistant_chars=" + assistantChars + " tool_chars=" + toolChars
                + " null_assistant=" + nullAssistant + " calls=" + calls
                + " definitions=" + toolDefinitions + " definition_bytes=" + definitionBytes
                + " missing_results=" + missingResults + " orphan_results=" + orphanResults
                + " duplicate_calls=" + duplicateCalls + " duplicate_results=" + duplicateResults
                + " name_mismatches=" + nameMismatches + " invalid_calls=" + invalidCalls
                + " invalid_arguments=" + invalidArguments
                + " interrupted_groups=" + interruptedGroups + " last_role=" + lastRole
                + " stream=" + stream + " include_usage=" + includeUsage
                + " tool_choice=" + toolChoice + " parallel_tool_calls=" + parallelToolCalls;
    }

    private static final class Analysis {
        int messages, system, user, assistant, tool, other, nullAssistant, calls;
        int missing, orphans, duplicateCalls, duplicateResults, nameMismatches, invalidCalls, invalidArguments, interrupted;
        long systemChars, userChars, assistantChars, toolChars;
        Role lastRole = Role.ABSENT;
        final Map<String, String> pending = new HashMap<>();
        final Set<String> answered = new HashSet<>();

        void message(JsonElement value) {
            JsonObject message = object(value);
            lastRole = switch (string(message.get("role"))) {
                case "system" -> Role.SYSTEM;
                case "user" -> Role.USER;
                case "assistant" -> Role.ASSISTANT;
                case "tool" -> Role.TOOL;
                default -> Role.OTHER;
            };
            messages++;
            if (lastRole != Role.TOOL && !pending.isEmpty()) {
                missing += pending.size();
                interrupted++;
                pending.clear();
            }
            if (lastRole != Role.TOOL) answered.clear();
            int chars = string(message.get("content")).length();
            switch (lastRole) {
                case SYSTEM -> { system++; systemChars += chars; }
                case USER -> { user++; userChars += chars; }
                case ASSISTANT -> {
                    assistant++;
                    assistantChars += chars;
                    JsonElement content = message.get("content");
                    if (content == null || content.isJsonNull()) nullAssistant++;
                    for (JsonElement call : array(message.get("tool_calls"))) call(object(call));
                }
                case TOOL -> {
                    tool++;
                    toolChars += chars;
                    String id = string(message.get("tool_call_id"));
                    if (id.isBlank() || !pending.containsKey(id)) {
                        if (answered.contains(id)) duplicateResults++;
                        else orphans++;
                    } else {
                        String name = string(message.get("name"));
                        if (!name.isEmpty() && !name.equals(pending.get(id))) nameMismatches++;
                        pending.remove(id);
                        answered.add(id);
                    }
                }
                default -> other++;
            }
        }

        void call(JsonObject call) {
            calls++;
            String id = string(call.get("id"));
            JsonObject function = object(call.get("function"));
            if (id.isBlank() || string(function.get("name")).isBlank()
                    || !"function".equals(string(call.get("type")))) invalidCalls++;
            if (pending.putIfAbsent(id, string(function.get("name"))) != null) duplicateCalls++;
            try {
                String arguments = string(function.get("arguments"));
                if (!JsonParser.parseString(arguments).isJsonObject()) invalidArguments++;
            } catch (RuntimeException ignored) {
                invalidArguments++;
            }
        }
    }

    private static Setting setting(JsonElement value) {
        if (value == null || value.isJsonNull()) return Setting.ABSENT;
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) return Setting.OTHER;
        return value.getAsBoolean() ? Setting.TRUE : Setting.FALSE;
    }

    private static ToolChoice toolChoice(JsonElement value) {
        if (value == null || value.isJsonNull()) return ToolChoice.ABSENT;
        if (value.isJsonObject()) return ToolChoice.NAMED;
        return switch (string(value)) {
            case "auto" -> ToolChoice.AUTO;
            case "none" -> ToolChoice.NONE;
            case "required" -> ToolChoice.REQUIRED;
            default -> ToolChoice.OTHER;
        };
    }

    private static String string(JsonElement value) {
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()
                ? value.getAsString() : "";
    }

    private static JsonObject object(JsonElement value) {
        return value != null && value.isJsonObject() ? value.getAsJsonObject() : new JsonObject();
    }

    private static JsonArray array(JsonElement value) {
        return value != null && value.isJsonArray() ? value.getAsJsonArray() : new JsonArray();
    }
}
