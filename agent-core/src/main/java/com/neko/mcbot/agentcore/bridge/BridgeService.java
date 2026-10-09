package com.neko.mcbot.agentcore.bridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.math.BigDecimal;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.ExecutionException;

/**
 * 桥接双接口一套内核（设计 §9）：REST(/v1/*) 与 MCP(/mcp) 都收敛到这里。
 * 纯 JVM——鉴权与 SSE 帧格式由 HTTP 适配层负责；
 * 这里只做路由、参数校验、任务窗口收集、JSON-RPC 信封。
 */
public final class BridgeService {

    public record Rest(int status, String contentType, String body) {
    }

    private static final long POLL_MS = 100;

    private final BridgeBackend backend;
    private final EventRing ring;
    private final long askTimeoutMs;
    private final String sessionId = UUID.randomUUID().toString();

    public BridgeService(BridgeBackend backend, EventRing ring) {
        this(backend, ring, 135_000);
    }

    public BridgeService(BridgeBackend backend, EventRing ring, long askTimeoutMs) {
        this.backend = backend;
        this.ring = ring;
        this.askTimeoutMs = askTimeoutMs;
    }

    public EventRing ring() {
        return ring;
    }

    public EventRing.Event recordEvent(String type, JsonObject data) {
        JsonObject wire = data.deepCopy();
        wire.addProperty("ev", type);
        stamp(wire);
        return ring.add(type, wire.toString());
    }

    private JsonObject status() {
        JsonObject result = JsonParser.parseString(backend.statusJson()).getAsJsonObject();
        stamp(result);
        return result;
    }

    private void stamp(JsonObject data) {
        data.addProperty("contract_version", BridgeContract.VERSION);
        data.addProperty("session_id", sessionId);
    }

    /** 返回 null = 本服务不管这条路由（交给适配层的 SSE/静态页）。 */
    public Rest handle(String method, String path, String body) {
        try {
            if (path.equals("/v1/task") && method.equals("POST")) return restTask(body);
            if (path.matches("/v1/task/-?\\d+/cancel") && method.equals("POST")) {
                long id;
                try {
                    id = Long.parseLong(path.substring("/v1/task/".length(), path.length() - "/cancel".length()));
                } catch (NumberFormatException invalid) {
                    throw new BadRequest("task_id 必须是非负 64 位整数");
                }
                if (id < 0) throw new BadRequest("task_id 必须是非负 64 位整数");
                boolean ok = backend.cancel(id);
                return json(200, "{\"ok\":" + ok + "}");
            }
            if (path.equals("/v1/ask") && method.equals("POST")) return restAsk(body);
            if (path.equals("/v1/answer") && method.equals("POST")) return restAnswer(body);
            if (path.equals("/v1/status") && method.equals("GET"))
                return json(200, status().toString());
            if (path.equals("/mcp") && method.equals("POST")) return mcp(body);
            return null;
        } catch (TimeoutException e) {
            return json(504, err("ANSWER_TIMEOUT", "等待同伴回答超时；任务可能仍在执行"));
        } catch (ExecutionException e) {
            return json(500, err("TASK_FAILED", "同伴任务未正常完成"));
        } catch (BadRequest e) {
            return json(400, err("INVALID_REQUEST", e.getMessage()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return json(500, err("INTERNAL", "桥接等待已中断"));
        } catch (Exception e) {
            return json(500, err("INTERNAL", "桥接内部错误；请求结果可能未知"));
        }
    }

    public static Rest json(int status, String body) {
        return new Rest(status, "application/json", body);
    }

    // ---- REST ----

    private Rest restTask(String body) throws Exception {
        JsonObject o = parse(body);
        String text = requiredString(o, "text");
        int waitS = clampInt(o, "wait_s", 8, 0, 120);
        long cursor = ring.lastId();
        long taskId = backend.submitTask(text);
        return json(200, collectTask(taskId, cursor, waitS * 1000L).toString());
    }

    /** Legacy fragments include all matching/public text; only a matching done ends the window. */
    private JsonObject collectTask(long taskId, long cursor, long windowMs) throws InterruptedException {
        List<String> fragments = new ArrayList<>();
        boolean done = false;
        String status = null;
        long deadline = System.currentTimeMillis() + windowMs;
        while (true) {
            for (EventRing.Event e : ring.since(cursor)) {
                cursor = e.id();
                JsonObject d = obj(e.dataJson());
                long t = d.has("task_id") && !d.get("task_id").isJsonNull()
                        ? d.get("task_id").getAsLong() : 0;
                if (t != 0 && t != taskId) {
                    continue; // 别的任务的私货不收
                }
                String text = str(d, "text");
                if (e.type().equals("done") && t == taskId && t > 0) {
                    done = true;
                    status = str(d, "status");
                    if (status.isBlank()) status = "completed";
                }
                if (!text.isBlank()) {
                    fragments.add(text);
                }
                if (done) break;
            }
            long left = deadline - System.currentTimeMillis();
            if (done || left <= 0 || windowMs == 0) {
                break;
            }
            Thread.sleep(Math.min(POLL_MS, left));
        }
        JsonObject r = new JsonObject();
        r.addProperty("task_id", taskId);
        r.addProperty("done", done);
        if (status != null) r.addProperty("status", status);
        JsonArray fr = new JsonArray();
        fragments.forEach(fr::add);
        r.add("fragments", fr);
        return r;
    }

    private Rest restAsk(String body) throws Exception {
        JsonObject args = parse(body);
        String text = requiredString(args, "text");
        optionalString(args, "companion");
        CompletableFuture<String> f = backend.ask(text);
        String answer;
        try {
            answer = f.get(askTimeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            f.cancel(false);
            throw e;
        }
        JsonObject r = new JsonObject();
        r.addProperty("answer", answer);
        return json(200, r.toString());
    }

    private Rest restAnswer(String body) throws Exception {
        JsonObject o = parse(body);
        String qid = requiredString(o, "question_id");
        String text = requiredString(o, "text");
        boolean ok = backend.answer(qid, text);
        JsonObject r = new JsonObject();
        r.addProperty("ok", ok);
        if (!ok) {
            r.addProperty("error", "没有等待回答的问题");
            r.addProperty("error_code", "NOT_FOUND");
        }
        return json(ok ? 200 : 404, r.toString());
    }

    // ---- MCP（JSON-RPC 2.0：initialize / tools/list / tools/call）----

    private static final String MCP_PROTOCOL = "2025-03-26";

    private Rest mcp(String body) throws Exception {
        JsonObject req;
        try {
            JsonElement parsed = readJson(body);
            if (!parsed.isJsonObject()) return json(200, rpcErr(null, -32600, "invalid request"));
            req = parsed.getAsJsonObject();
        } catch (BadRequest invalid) {
            return json(200, rpcErr(null, -32700, "invalid JSON"));
        }
        JsonElement id = req.has("id") ? req.get("id") : null;
        if (!isString(req.get("jsonrpc")) || !"2.0".equals(str(req, "jsonrpc")) || !isString(req.get("method"))
                || (id != null && !id.isJsonNull() && (!id.isJsonPrimitive()
                || id.getAsJsonPrimitive().isBoolean()))) {
            return json(200, rpcErr(null, -32600, "invalid request"));
        }
        if (!req.has("id") && !"notifications/initialized".equals(str(req, "method"))) {
            return json(200, rpcErr(null, -32600, "request id required"));
        }
        switch (str(req, "method")) {
            case "initialize" -> {
                JsonObject r = new JsonObject();
                r.addProperty("protocolVersion", MCP_PROTOCOL);
                JsonObject caps = new JsonObject();
                caps.add("tools", new JsonObject());
                r.add("capabilities", caps);
                JsonObject info = new JsonObject();
                info.addProperty("name", "mcbot");
                info.addProperty("version", "1");
                r.add("serverInfo", info);
                return json(200, rpcOk(id, r));
            }
            case "notifications/initialized" -> {
                return new Rest(202, "", "");
            }
            case "tools/list" -> {
                JsonObject r = new JsonObject();
                r.add("tools", toolsList());
                return json(200, rpcOk(id, r));
            }
            case "tools/call" -> {
                try {
                    JsonObject p = objectField(req, "params", false);
                    String name = requiredString(p, "name");
                    return json(200, rpcOk(id, mcpCall(name, objectField(p, "arguments", true))));
                } catch (BadRequest invalid) {
                    return json(200, rpcErr(id, -32602, invalid.getMessage()));
                }
            }
            default -> {
                return json(200, rpcErr(id, -32601, "unknown method"));
            }
        }
    }

    private JsonArray toolsList() {
        JsonArray a = new JsonArray();
        a.add(tool("mcbot_task", "投递任务级指令，返回 task_id、窗口文字片段及是否结束；后续追踪 SSE"));
        a.add(tool("mcbot_ask", "兼容入口：投递任务并同步等待本次最终回答（≤135 秒）；不是独立闲聊能力"));
        a.add(tool("mcbot_answer", "回答同伴的反问（question 事件里的 question_id）"));
        a.add(tool("mcbot_status", "同伴、大脑与桥会话的当前状态 JSON"));
        a.add(tool("mcbot_cancel", "取消指定任务；task_id 缺省或 0 时取消当前任务和全部排队任务"));
        return a;
    }

    private JsonObject tool(String name, String desc) {
        JsonObject o = new JsonObject();
        o.addProperty("name", name);
        o.addProperty("description", desc);
        o.add("inputSchema", BridgeContract.inputSchema(name));
        return o;
    }

    private JsonObject mcpCall(String name, JsonObject args) {
        JsonObject payload = new JsonObject();
        try {
            switch (name) {
                case "mcbot_task" -> {
                    String text = requiredString(args, "text");
                    int waitS = clampInt(args, "wait_s", 8, 0, 120);
                    long cursor = ring.lastId();
                    long taskId = backend.submitTask(text);
                    payload = collectTask(taskId, cursor, waitS * 1000L);
                }
                case "mcbot_ask" -> {
                    String text = requiredString(args, "text");
                    optionalString(args, "companion");
                    String answer;
                    CompletableFuture<String> response = backend.ask(text);
                    try {
                        answer = response.get(askTimeoutMs, TimeUnit.MILLISECONDS);
                    } catch (TimeoutException e) {
                        response.cancel(false);
                        return mcpErrText("ANSWER_TIMEOUT", "同伴回答超时；任务可能仍在执行");
                    } catch (InterruptedException e) {
                        throw e;
                    } catch (Exception e) {
                        return mcpErrText("TASK_FAILED", "同伴任务未正常完成");
                    }
                    payload.addProperty("answer", answer);
                }
                case "mcbot_answer" -> {
                    boolean ok = backend.answer(requiredString(args, "question_id"), requiredString(args, "text"));
                    if (!ok) return mcpErrText("NOT_FOUND", "没有等待回答的问题");
                    payload.addProperty("ok", true);
                }
                case "mcbot_status" -> payload = status();
                case "mcbot_cancel" -> payload.addProperty("ok", backend.cancel(taskId(args)));
                default -> {
                    return mcpErrText("NOT_FOUND", "unknown tool");
                }
            }
            return mcpText(payload.toString());
        } catch (BadRequest invalid) {
            return mcpErrText("INVALID_REQUEST", invalid.getMessage());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return mcpErrText("INTERNAL", "桥接等待已中断");
        } catch (Exception failure) {
            return mcpErrText("INTERNAL", "桥接内部错误；请求结果可能未知");
        }
    }

    private JsonObject mcpText(String text) {
        JsonObject o = new JsonObject();
        JsonArray content = new JsonArray();
        JsonObject t = new JsonObject();
        t.addProperty("type", "text");
        t.addProperty("text", text);
        content.add(t);
        o.add("content", content);
        o.addProperty("isError", false);
        return o;
    }

    private JsonObject mcpErrText(String code, String text) {
        JsonObject o = mcpText(err(code, text));
        o.addProperty("isError", true);
        return o;
    }

    // ---- 小工具 ----

    private static String rpcOk(JsonElement id, JsonObject result) {
        JsonObject o = new JsonObject();
        o.addProperty("jsonrpc", "2.0");
        o.add("id", id == null ? com.google.gson.JsonNull.INSTANCE : id);
        o.add("result", result);
        return o.toString();
    }

    private static String rpcErr(JsonElement id, int code, String message) {
        JsonObject o = new JsonObject();
        o.addProperty("jsonrpc", "2.0");
        o.add("id", id == null ? com.google.gson.JsonNull.INSTANCE : id);
        JsonObject e = new JsonObject();
        e.addProperty("code", code);
        e.addProperty("message", message);
        o.add("error", e);
        return o.toString();
    }

    public static String err(String code, String message) {
        JsonObject o = new JsonObject();
        o.addProperty("error", message);
        o.addProperty("error_code", code);
        return o.toString();
    }

    private static JsonObject parse(String body) {
        if (body == null || body.isBlank()) return new JsonObject();
        JsonElement parsed = readJson(body);
        if (parsed.isJsonObject()) return parsed.getAsJsonObject();
        throw new BadRequest("请求必须是合法 JSON 对象");
    }

    private static JsonElement readJson(String body) {
        if (body == null || body.isBlank()) throw new BadRequest("请求必须是合法 JSON 对象");
        try (var reader = new JsonReader(new StringReader(body))) {
            reader.setStrictness(Strictness.STRICT);
            JsonElement parsed = JsonParser.parseReader(reader);
            if (reader.peek() == JsonToken.END_DOCUMENT) return parsed;
        } catch (Exception invalid) {
            throw new BadRequest("请求必须是合法 JSON 对象");
        }
        throw new BadRequest("请求必须是合法 JSON 对象");
    }

    private static JsonObject obj(String json) {
        try {
            JsonElement e = JsonParser.parseString(json);
            return e.isJsonObject() ? e.getAsJsonObject() : new JsonObject();
        } catch (RuntimeException invalid) {
            return new JsonObject();
        }
    }

    private static JsonObject objectField(JsonObject o, String k, boolean optional) {
        if (!o.has(k) && optional) return new JsonObject();
        if (o.has(k) && o.get(k).isJsonObject()) return o.getAsJsonObject(k);
        throw new BadRequest(k + " 必须是 JSON 对象");
    }

    private static boolean isString(JsonElement value) {
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString();
    }

    private static String requiredString(JsonObject o, String k) {
        if (!isString(o.get(k)) || o.get(k).getAsString().isBlank())
            throw new BadRequest(k + " 必须是非空字符串");
        return o.get(k).getAsString();
    }

    private static void optionalString(JsonObject o, String k) {
        if (o.has(k) && !isString(o.get(k))) throw new BadRequest(k + " 必须是字符串");
    }

    private static String str(JsonObject o, String k) {
        try {
            return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsString() : "";
        } catch (RuntimeException e) {
            return "";
        }
    }

    private static int clampInt(JsonObject o, String k, int def, int lo, int hi) {
        if (!o.has(k)) return def;
        return integer(o, k).max(BigDecimal.valueOf(lo)).min(BigDecimal.valueOf(hi)).intValue();
    }

    private static long taskId(JsonObject o) {
        if (!o.has("task_id")) return 0;
        BigDecimal id = integer(o, "task_id");
        if (id.signum() < 0 || id.compareTo(BigDecimal.valueOf(Long.MAX_VALUE)) > 0)
            throw new BadRequest("task_id 必须是非负 64 位整数");
        return id.longValue();
    }

    private static BigDecimal integer(JsonObject o, String k) {
        try {
            JsonElement value = o.get(k);
            if (value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber()) {
                BigDecimal number = value.getAsBigDecimal().stripTrailingZeros();
                if (number.scale() <= 0) return number;
            }
        } catch (RuntimeException e) {
            throw new BadRequest(k + " 必须是整数");
        }
        throw new BadRequest(k + " 必须是整数");
    }

    private static final class BadRequest extends RuntimeException {
        BadRequest(String message) { super(message); }
    }
}
