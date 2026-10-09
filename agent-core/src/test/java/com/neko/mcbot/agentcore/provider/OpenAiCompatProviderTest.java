package com.neko.mcbot.agentcore.provider;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.neko.mcbot.agentcore.llm.AssistantTurn;
import com.neko.mcbot.agentcore.llm.Msg;
import com.neko.mcbot.agentcore.llm.ToolSpec;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertFalse;

class OpenAiCompatProviderTest {

    private final OpenAiCompatProvider p =
            new OpenAiCompatProvider("t", "https://api.deepseek.com/", "key", "deepseek-chat");

    @Test
    void assemblesStreamedToolCallAcrossChunks() {
        TurnBuilder b = new TurnBuilder();
        JsonObject c1 = new JsonObject();
        JsonArray ch1 = new JsonArray();
        JsonObject d1 = new JsonObject();
        d1.addProperty("content", "好的，");
        ch1.add(wrapDelta(d1));
        c1.add("choices", ch1);
        p.acceptChunk(c1, b);

        JsonObject c2 = new JsonObject();
        JsonArray ch2 = new JsonArray();
        JsonObject d2 = new JsonObject();
        JsonArray tcs = new JsonArray();
        tcs.add(toolCallChunk(0, "call_1", "add", "{\"a\":"));
        d2.add("tool_calls", tcs);
        ch2.add(wrapDelta(d2));
        c2.add("choices", ch2);
        p.acceptChunk(c2, b);

        JsonObject c3 = new JsonObject();
        JsonArray tcs3 = new JsonArray();
        tcs3.add(toolCallChunk(0, null, null, "1,\"b\":2}"));
        JsonObject d3 = new JsonObject();
        d3.add("tool_calls", tcs3);
        JsonArray ch3 = new JsonArray();
        ch3.add(wrapDelta(d3));
        c3.add("choices", ch3);
        JsonObject usage = new JsonObject();
        usage.addProperty("prompt_tokens", 100);
        usage.addProperty("completion_tokens", 5);
        c3.add("usage", usage);
        p.acceptChunk(c3, b);

        AssistantTurn t = b.build();
        assertEquals("好的，", t.text());
        assertEquals(1, t.toolCalls().size());
        assertEquals("add", t.toolCalls().get(0).name());
        assertEquals("{\"a\":1,\"b\":2}", t.toolCalls().get(0).argsJson());
        assertEquals(100, t.promptTokens());
        assertEquals(5, t.completionTokens());
    }

    @Test
    void buildsWireMessagesAndTools() {
        var body = p.buildBody("你是助手", List.of(
                new Msg.User("1加2"),
                new Msg.Assistant("", List.of(new com.neko.mcbot.agentcore.llm.ToolCall("c1", "add", "{\"a\":1,\"b\":2}"))),
                new Msg.Tool("c1", "add", "结果 = 3", true)),
                List.of(new ToolSpec("add", "求和", "{\"type\":\"object\"}")));

        assertEquals("deepseek-chat", body.get("model").getAsString());
        assertTrue(body.get("stream").getAsBoolean());
        JsonArray msgs = body.getAsJsonArray("messages");
        assertEquals("system", msgs.get(0).getAsJsonObject().get("role").getAsString());
        assertEquals("assistant", msgs.get(2).getAsJsonObject().get("role").getAsString());
        assertEquals("tool", msgs.get(3).getAsJsonObject().get("role").getAsString());
        assertEquals("c1", msgs.get(3).getAsJsonObject().get("tool_call_id").getAsString());
        JsonArray calls = msgs.get(2).getAsJsonObject().getAsJsonArray("tool_calls");
        assertEquals("add", calls.get(0).getAsJsonObject()
                .getAsJsonObject("function").get("name").getAsString());
        assertTrue(body.getAsJsonArray("tools").get(0).getAsJsonObject()
                .getAsJsonObject("function").has("parameters"));
    }

    @Test
    void endpointNormalizesBaseUrl() {
        assertEquals("https://api.deepseek.com/chat/completions", p.endpoint());
    }

    @Test
    void fullChatEndpointDoesNotGetThePathAppendedTwice() {
        assertEquals("https://example.test/v1/chat/completions",
                new OpenAiCompatProvider("test", " https://example.test/v1/chat/completions/// ",
                        "key", "model").endpoint());
    }

    @Test
    void urlValidationNeverEchoesCredentials() {
        for (String url : List.of("https://private:password@example.test/v1",
                "https://example.test/v1?token=private", "not-a-url")) {
            var failure = assertThrows(IllegalArgumentException.class,
                    () -> new OpenAiCompatProvider("test", url, "key", "model"));
            assertFalse(failure.getMessage().contains("private"));
            assertFalse(failure.getMessage().contains(url));
        }
    }

    @Test
    void longModelNameIsSentVerbatim() {
        String model = "custom-model-with-a-long-expiration-suffix";
        var body = new OpenAiCompatProvider("test", "https://example.test/v1", "key", model)
                .buildBody("sys", List.of(), List.of());
        assertEquals(model, body.get("model").getAsString());
    }

    private static JsonObject wrapDelta(JsonObject delta) {
        JsonObject choice = new JsonObject();
        choice.add("delta", delta);
        return choice;
    }

    private static JsonObject toolCallChunk(int index, String id, String name, String args) {
        JsonObject tc = new JsonObject();
        tc.addProperty("index", index);
        if (id != null) {
            tc.addProperty("id", id);
        }
        JsonObject fn = new JsonObject();
        if (name != null) {
            fn.addProperty("name", name);
        }
        if (args != null) {
            fn.addProperty("arguments", args);
        }
        tc.add("function", fn);
        return tc;
    }
}
