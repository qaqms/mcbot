package com.neko.mcbot.agentcore.provider;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.neko.mcbot.agentcore.llm.Msg;
import com.neko.mcbot.agentcore.llm.ToolCall;
import com.neko.mcbot.agentcore.llm.ToolSpec;

import java.util.List;
import java.util.Map;

/**
 * OpenAI 兼容协议（DeepSeek / Qwen / Kimi / GLM / OpenAI / OpenRouter / 硅基流动… 同族）。
 * baseUrl 传根地址（如 https://api.deepseek.com），自动补 /chat/completions。
 */
public final class OpenAiCompatProvider implements ChatProvider {

    private final String name;
    private final String baseUrl;
    private final String apiKey;
    private final String model;

    public OpenAiCompatProvider(String name, String baseUrl, String apiKey, String model) {
        this.name = name;
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.apiKey = apiKey;
        this.model = model;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public String endpoint() {
        return baseUrl + "/chat/completions";
    }

    @Override
    public Map<String, String> authHeaders() {
        return Map.of("Authorization", "Bearer " + apiKey);
    }

    @Override
    public JsonObject buildBody(String systemPrompt, List<Msg> convo, List<ToolSpec> tools) {
        JsonObject body = new JsonObject();
        body.addProperty("model", model);
        body.addProperty("stream", true);
        JsonObject streamOptions = new JsonObject();
        streamOptions.addProperty("include_usage", true);
        body.add("stream_options", streamOptions);

        JsonArray messages = new JsonArray();
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            messages.add(textMessage("system", systemPrompt));
        }
        for (Msg m : convo) {
            messages.add(toWire(m));
        }
        body.add("messages", messages);

        if (tools != null && !tools.isEmpty()) {
            JsonArray arr = new JsonArray();
            for (ToolSpec t : tools) {
                JsonObject fn = new JsonObject();
                fn.addProperty("name", t.name());
                fn.addProperty("description", t.description());
                fn.add("parameters", JsonParser.parseString(t.paramsJsonSchema()));
                JsonObject wrap = new JsonObject();
                wrap.addProperty("type", "function");
                wrap.add("function", fn);
                arr.add(wrap);
            }
            body.add("tools", arr);
        }
        return body;
    }

    private static JsonObject textMessage(String role, String content) {
        JsonObject o = new JsonObject();
        o.addProperty("role", role);
        o.addProperty("content", content);
        return o;
    }

    private static JsonObject toWire(Msg m) {
        if (m instanceof Msg.User u) {
            return textMessage("user", u.text());
        } else if (m instanceof Msg.Nudge n) {
            return textMessage("user", n.text());
        } else if (m instanceof Msg.Tool t) {
            JsonObject o = new JsonObject();
            o.addProperty("role", "tool");
            o.addProperty("tool_call_id", t.callId());
            o.addProperty("name", t.name());
            o.addProperty("content", t.content());
            return o;
        } else if (m instanceof Msg.Assistant a) {
            JsonObject o = new JsonObject();
            o.addProperty("role", "assistant");
            o.add("content", a.text() == null || a.text().isEmpty()
                    ? com.google.gson.JsonNull.INSTANCE
                    : new com.google.gson.JsonPrimitive(a.text()));
            if (!a.toolCalls().isEmpty()) {
                JsonArray calls = new JsonArray();
                for (ToolCall tc : a.toolCalls()) {
                    JsonObject fn = new JsonObject();
                    fn.addProperty("name", tc.name());
                    fn.addProperty("arguments", tc.argsJson());
                    JsonObject wrap = new JsonObject();
                    wrap.addProperty("id", tc.id());
                    wrap.addProperty("type", "function");
                    wrap.add("function", fn);
                    calls.add(wrap);
                }
                o.add("tool_calls", calls);
            }
            return o;
        }
        throw new IllegalStateException("未知消息类型 " + m);
    }

    @Override
    public void acceptChunk(JsonObject chunk, TurnBuilder b) {
        if (chunk.has("usage") && chunk.get("usage").isJsonObject()) {
            JsonObject usage = chunk.getAsJsonObject("usage");
            b.usage(usage.has("prompt_tokens") ? usage.get("prompt_tokens").getAsLong() : 0,
                    usage.has("completion_tokens") ? usage.get("completion_tokens").getAsLong() : 0);
        }
        JsonArray choices = chunk.getAsJsonArray("choices");
        if (choices == null || choices.isEmpty()) {
            return;
        }
        JsonObject choice = choices.get(0).getAsJsonObject();
        if (choice.has("finish_reason") && !choice.get("finish_reason").isJsonNull()) {
            b.finishReason(choice.get("finish_reason").getAsString());
        }
        JsonElement deltaEl = choice.get("delta");
        if (deltaEl == null || !deltaEl.isJsonObject()) {
            return;
        }
        JsonObject delta = deltaEl.getAsJsonObject();
        if (delta.has("content") && !delta.get("content").isJsonNull()) {
            b.appendText(delta.get("content").getAsString());
        }
        JsonArray tcs = delta.getAsJsonArray("tool_calls");
        if (tcs != null) {
            for (JsonElement el : tcs) {
                JsonObject tc = el.getAsJsonObject();
                int index = tc.has("index") ? tc.get("index").getAsInt() : 0;
                String id = tc.has("id") && !tc.get("id").isJsonNull() ? tc.get("id").getAsString() : null;
                String name = null;
                String args = null;
                if (tc.has("function") && tc.get("function").isJsonObject()) {
                    JsonObject fn = tc.getAsJsonObject("function");
                    if (fn.has("name") && !fn.get("name").isJsonNull()) {
                        name = fn.get("name").getAsString();
                    }
                    if (fn.has("arguments") && !fn.get("arguments").isJsonNull()) {
                        args = fn.get("arguments").getAsString();
                    }
                }
                b.toolCallDelta(index, id, name, args);
            }
        }
    }

    @Override
    public boolean isTerminalData(String dataPayload) {
        return "[DONE]".equals(dataPayload.trim());
    }
}
