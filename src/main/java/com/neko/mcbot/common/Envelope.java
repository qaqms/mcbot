package com.neko.mcbot.common;

import com.google.gson.JsonObject;

/**
 * 线上信封：单通道 JSON 包，kind 区分语义。
 * C2S kind：summon / dismiss / tool_call / cancel / answer
 * S2C kind：tool_result / event / roster
 * 例：{"kind":"tool_call","seq":7,"task_id":0,"tool":"scan_area","args":{"r":16}}
 */
public record Envelope(String kind, JsonObject body) {

    public static final int MAX_BYTES = 32 * 1024;

    public String str(String field) {
        return body != null && body.has(field) ? body.get(field).getAsString() : "";
    }

    public int num(String field, int def) {
        return body != null && body.has(field) ? body.get(field).getAsInt() : def;
    }

    public boolean bool(String field) {
        return body != null && body.has(field) && body.get(field).getAsBoolean();
    }

    public JsonObject obj(String field) {
        return body != null && body.has(field) && body.get(field).isJsonObject()
                ? body.getAsJsonObject(field) : new JsonObject();
    }

    public String encode() {
        JsonObject o = body == null ? new JsonObject() : body.deepCopy();
        o.addProperty("kind", kind);
        return o.toString();
    }

    /** 解析失败返回 null（调用方按闸①处理）。 */
    public static Envelope decode(String json) {
        try {
            var el = com.google.gson.JsonParser.parseString(json);
            if (!el.isJsonObject()) {
                return null;
            }
            JsonObject obj = el.getAsJsonObject();
            if (!obj.has("kind")) {
                return null;
            }
            String kind = obj.get("kind").getAsString();
            obj.remove("kind");
            return new Envelope(kind, obj);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
