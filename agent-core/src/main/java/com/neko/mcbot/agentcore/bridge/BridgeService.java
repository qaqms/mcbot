package com.neko.mcbot.agentcore.bridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

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

    public BridgeService(BridgeBackend backend, EventRing ring) {
        this(backend, ring, 60_000);
    }

    public BridgeService(BridgeBackend backend, EventRing ring, long askTimeoutMs) {
        this.backend = backend;
        this.ring = ring;
        this.askTimeoutMs = askTimeoutMs;
    }

    public EventRing ring() {
        return ring;
    }

    /** 返回 null = 本服务不管这条路由（交给适配层的 SSE/静态页）。 */
    public Rest handle(String method, String path, String body) {
        try {
            if (path.equals("/v1/task") && method.equals("POST")) return restTask(body);
            if (path.matches("/v1/task/\\d+/cancel") && method.equals("POST")) {
                long id = Long.parseLong(path.replaceAll("\\D+", ""));
                boolean ok = backend.cancel(id);
                return json(200, "{\"ok\":" + ok + "}");
            }
            if (path.equals("/v1/ask") && method.equals("POST")) return restAsk(body);
            if (path.equals("/v1/answer") && method.equals("POST")) return restAnswer(body);
            if (path.equals("/v1/status") && method.equals("GET"))
                return json(200, backend.statusJson());
            if (path.equals("/mcp") && method.equals("POST")) return mcp(body);
            return null;
        } catch (TimeoutException e) {
            return json(504, err("等待同伴回答超时"));
        } catch (Exception e) {
            return json(500, err(String.valueOf(e.getMessage())));
        }
    }

    public static Rest json(int status, String body) {
        return new Rest(status, "application/json", body);
    }

    // ---- REST ----

    private Rest restTask(String body) throws Exception {
        JsonObject o = parse(body);
        String text = str(o, "text");
        if (text.isBlank()) return json(400, err("text 不能为空"));
        int waitS = clampInt(o, "wait_s", 8, 0, 120);
        long cursor = ring.lastId();
        long taskId = backend.submitTask(text);
        return json(200, collectTask(taskId, cursor, waitS * 1000L).toString());
    }

    /** 投出去，窗口内盯事件流：progress 收片段，同任务的 done 即停。 */
    private JsonObject collectTask(long taskId, long cursor, long windowMs) throws InterruptedException {
        List<String> fragments = new ArrayList<>();
        boolean done = false;
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
                String ev = str(d, "ev");
                String text = str(d, "text");
                if (ev.equals("done") || ev.equals("state")) {
                    done = true;
                }
                if (!text.isBlank()) {
                    fragments.add(text);
                }
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
        JsonArray fr = new JsonArray();
        fragments.forEach(fr::add);
        r.add("fragments", fr);
        return r;
    }

    private Rest restAsk(String body) throws Exception {
        String text = str(parse(body), "text");
        if (text.isBlank()) return json(400, err("text 不能为空"));
        CompletableFuture<String> f = backend.ask(text);
        String answer = f.get(askTimeoutMs, TimeUnit.MILLISECONDS);
        JsonObject r = new JsonObject();
        r.addProperty("answer", answer);
        return json(200, r.toString());
    }

    private Rest restAnswer(String body) throws Exception {
        JsonObject o = parse(body);
        String qid = str(o, "question_id");
        String text = str(o, "text");
        if (qid.isBlank() || text.isBlank()) return json(400, err("question_id 与 text 必填"));
        boolean ok = backend.answer(qid, text);
        JsonObject r = new JsonObject();
        r.addProperty("ok", ok);
        if (!ok) r.addProperty("error", "没有等待回答的问题 " + qid);
        return json(ok ? 200 : 404, r.toString());
    }

    // ---- MCP（JSON-RPC 2.0：initialize / tools/list / tools/call）----

    private static final String MCP_PROTOCOL = "2025-03-26";

    private Rest mcp(String body) throws Exception {
        JsonObject req = parse(body);
        JsonElement id = req.has("id") ? req.get("id") : null;
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
                JsonObject p = objGet(req, "params");
                return json(200, rpcOk(id, mcpCall(str(p, "name"), objGet(p, "arguments"))));
            }
            default -> {
                return json(200, rpcErr(id, -32601, "unknown method: " + str(req, "method")));
            }
        }
    }

    private JsonArray toolsList() {
        JsonArray a = new JsonArray();
        a.add(tool("mcbot_task", "派同伴去办一件事（老板级指令，非原子操作）。返回 task_id 与窗口内的进度片段",
                "{\"type\":\"object\",\"properties\":{\"text\":{\"type\":\"string\"},"
                        + "\"wait_s\":{\"type\":\"integer\",\"description\":\"0-120，默认8\"}},\"required\":[\"text\"]}"));
        a.add(tool("mcbot_ask", "和同伴闲聊一句，同步等它的回答（≤60 秒）",
                "{\"type\":\"object\",\"properties\":{\"companion\":{\"type\":\"string\"},"
                        + "\"text\":{\"type\":\"string\"}},\"required\":[\"text\"]}"));
        a.add(tool("mcbot_answer", "回答同伴的反问（question 事件里的 question_id）",
                "{\"type\":\"object\",\"properties\":{\"question_id\":{\"type\":\"string\"},"
                        + "\"text\":{\"type\":\"string\"}},\"required\":[\"question_id\",\"text\"]}"));
        a.add(tool("mcbot_status", "同伴与大脑的当前状态 JSON", "{\"type\":\"object\",\"properties\":{}}"));
        a.add(tool("mcbot_cancel", "叫停同伴当前在做的事",
                "{\"type\":\"object\",\"properties\":{\"task_id\":{\"type\":\"integer\"}}}"));
        return a;
    }

    private JsonObject tool(String name, String desc, String schema) {
        JsonObject o = new JsonObject();
        o.addProperty("name", name);
        o.addProperty("description", desc);
        o.add("inputSchema", JsonParser.parseString(schema));
        return o;
    }

    private JsonObject mcpCall(String name, JsonObject args) throws Exception {
        JsonObject payload = new JsonObject();
        switch (name) {
            case "mcbot_task" -> {
                String text = str(args, "text");
                if (text.isBlank()) return mcpErrText("text 不能为空");
                int waitS = clampInt(args, "wait_s", 8, 0, 120);
                long cursor = ring.lastId();
                long taskId = backend.submitTask(text);
                payload = collectTask(taskId, cursor, waitS * 1000L);
            }
            case "mcbot_ask" -> {
                String text = str(args, "text");
                if (text.isBlank()) return mcpErrText("text 不能为空");
                String answer;
                try {
                    answer = backend.ask(text).get(askTimeoutMs, TimeUnit.MILLISECONDS);
                } catch (Exception e) {
                    answer = "(同伴 " + (askTimeoutMs / 1000) + " 秒内没能回答)";
                }
                payload.addProperty("answer", answer);
            }
            case "mcbot_answer" -> payload.addProperty("ok",
                    backend.answer(str(args, "question_id"), str(args, "text")));
            case "mcbot_status" -> payload = JsonParser.parseString(backend.statusJson()).getAsJsonObject();
            case "mcbot_cancel" -> payload.addProperty("ok",
                    backend.cancel(args.has("task_id") ? args.get("task_id").getAsLong() : 0));
            default -> {
                return mcpErrText("unknown tool: " + name);
            }
        }
        return mcpText(payload.toString());
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

    private JsonObject mcpErrText(String text) {
        JsonObject o = mcpText(text);
        o.addProperty("isError", true);
        return o;
    }

    // ---- 小工具 ----

    private static String rpcOk(JsonElement id, JsonObject result) {
        JsonObject o = new JsonObject();
        o.addProperty("jsonrpc", "2.0");
        if (id != null) o.add("id", id);
        o.add("result", result);
        return o.toString();
    }

    private static String rpcErr(JsonElement id, int code, String message) {
        JsonObject o = new JsonObject();
        o.addProperty("jsonrpc", "2.0");
        if (id != null) o.add("id", id);
        JsonObject e = new JsonObject();
        e.addProperty("code", code);
        e.addProperty("message", message);
        o.add("error", e);
        return o.toString();
    }

    private static String err(String message) {
        JsonObject o = new JsonObject();
        o.addProperty("error", message);
        return o.toString();
    }

    private static JsonObject parse(String body) {
        if (body == null || body.isBlank()) return new JsonObject();
        return JsonParser.parseString(body).getAsJsonObject();
    }

    private static JsonObject obj(String json) {
        try {
            JsonElement e = JsonParser.parseString(json);
            return e.isJsonObject() ? e.getAsJsonObject() : new JsonObject();
        } catch (RuntimeException e) {
            return new JsonObject();
        }
    }

    private static JsonObject objGet(JsonObject o, String k) {
        return o.has(k) && o.get(k).isJsonObject() ? o.getAsJsonObject(k) : new JsonObject();
    }

    private static String str(JsonObject o, String k) {
        try {
            return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsString() : "";
        } catch (RuntimeException e) {
            return "";
        }
    }

    private static int clampInt(JsonObject o, String k, int def, int lo, int hi) {
        try {
            int v = o.has(k) ? o.get(k).getAsInt() : def;
            return Math.max(lo, Math.min(hi, v));
        } catch (RuntimeException e) {
            return def;
        }
    }
}
