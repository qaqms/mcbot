package com.neko.mcbot.agentcore.bridge;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/** Shared wire definitions keep MCP discovery aligned with the connector handoff. */
public final class BridgeContract {
    public static final String VERSION = "1.0";
    private static final JsonObject SCHEMA = load();

    private BridgeContract() {}

    public static JsonObject inputSchema(String tool) {
        String definition = switch (tool) {
            case "mcbot_task" -> "taskInput";
            case "mcbot_ask" -> "askInput";
            case "mcbot_answer" -> "answerInput";
            case "mcbot_status" -> "statusInput";
            case "mcbot_cancel" -> "cancelInput";
            default -> throw new IllegalArgumentException("unknown bridge tool");
        };
        return SCHEMA.getAsJsonObject("$defs").getAsJsonObject(definition).deepCopy();
    }

    private static JsonObject load() {
        var stream = BridgeContract.class.getResourceAsStream("bridge-v1.schema.json");
        if (stream == null) throw new IllegalStateException("missing bridge contract");
        try (var reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        } catch (IOException failure) {
            throw new IllegalStateException("cannot load bridge contract", failure);
        }
    }
}
