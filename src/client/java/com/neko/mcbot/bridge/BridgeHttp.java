package com.neko.mcbot.bridge;

import com.google.gson.JsonObject;
import com.neko.mcbot.McbotClient;
import com.neko.mcbot.McbotMod;
import com.neko.mcbot.agent.AgentRunner;
import com.neko.mcbot.agentcore.bridge.BridgeBackend;
import com.neko.mcbot.agentcore.bridge.BridgeService;
import com.neko.mcbot.agentcore.bridge.EventRing;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * neko 入口（设计 §9）：127.0.0.1:57121，Bearer token 鉴权（token 存 mcbot/bridge.token，跨重启稳定）。
 * REST /v1/* 与 MCP /mcp 全部路由进 BridgeService（agent-core 内核）；SSE /v1/events 在这里直连环形缓冲。
 * 只绑回环——绝不裸监听 0.0.0.0。
 */
public final class BridgeHttp implements BridgeEvents.Sink {

    public static final int PORT = 57121;
    private static final int MAX_BODY = 64 * 1024;

    private static volatile BridgeHttp instance;

    private final HttpServer server;
    private final EventRing ring = new EventRing(200);
    private final BridgeService service;
    private final String token;
    private final List<SseClient> clients = new CopyOnWriteArrayList<>();

    private static final class SseClient {
        final HttpExchange ex;
        final LinkedBlockingQueue<String> out = new LinkedBlockingQueue<>();

        SseClient(HttpExchange ex) {
            this.ex = ex;
        }
    }

    /** 幂等启动（客户端初始化后调一次；重复调用安全）。 */
    public static void ensureStarted() {
        if (instance != null) {
            return;
        }
        synchronized (BridgeHttp.class) {
            if (instance != null) {
                return;
            }
            try {
                instance = new BridgeHttp();
            } catch (IOException e) {
                McbotMod.LOG.error("桥接启动失败（端口 {} 被占？）", PORT, e);
            }
        }
    }

    public static void shutdown() {
        BridgeHttp b = instance;
        instance = null;
        if (b != null) {
            b.stop();
        }
    }

    public static String endpoint() {
        return "http://127.0.0.1:" + PORT;
    }

    public static String tokenOrNull() {
        BridgeHttp b = instance;
        return b == null ? null : b.token;
    }

    private BridgeHttp() throws IOException {
        token = loadOrCreateToken();
        // ask 超时 135s：必须 > 反问的 120s——否则 neko 经 /v1/ask 链上触发 ask_owner 时
        // HTTP 先 504 放弃、大脑还在空等反问，答案回来只塞进已作废的 future。
        service = new BridgeService(new RunnerBackend(), ring, 135_000);
        server = HttpServer.create(
                new InetSocketAddress(InetAddress.getByName("127.0.0.1"), PORT), 4);
        // 池 4→8：每个 SSE 长连接永久占一条线程，4 条 = 2 个 SSE + 2 个长 wait 即饥饿。
        // 彻底解法（SSE 不占线程）归 R2-C 一并设计。
        server.setExecutor(Executors.newFixedThreadPool(8, r -> {
            Thread t = new Thread(r, "mcbot-bridge");
            t.setDaemon(true);
            return t;
        }));
        server.createContext("/", this::route);
        server.start();
        BridgeEvents.attach(this);
        McbotMod.LOG.info("桥接已启动 {}（token 见 mcbot/bridge.token）", endpoint());
    }

    private void stop() {
        BridgeEvents.detach(this);
        for (SseClient c : clients) {
            c.out.add("STOP");
        }
        server.stop(0);
        McbotMod.LOG.info("桥接已关闭");
    }

    // ---- Sink：事件入环 + 转发 SSE ----

    @Override
    public void event(String type, JsonObject data) {
        EventRing.Event e = ring.add(type, data.toString());
        String frame = "id: " + e.id() + "\nevent: " + type + "\ndata: " + e.dataJson() + "\n\n";
        for (SseClient c : clients) {
            c.out.add(frame);
        }
    }

    // ---- 路由 ----

    private void route(HttpExchange ex) throws IOException {
        try {
            String path = ex.getRequestURI().getPath();
            ex.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
            if (ex.getRequestMethod().equals("OPTIONS")) {
                ex.sendResponseHeaders(204, -1);
                return;
            }
            if (!authorized(ex)) {
                write(ex, 401, "application/json", "{\"error\":\"missing or bad bearer token\"}");
                return;
            }
            if (path.equals("/v1/events") && ex.getRequestMethod().equals("GET")) {
                sse(ex);
                return;
            }
            String body = readBody(ex);
            BridgeService.Rest r = service.handle(ex.getRequestMethod(), path, body);
            if (r == null) {
                write(ex, 404, "application/json", "{\"error\":\"unknown route\"}");
                return;
            }
            if (r.status() == 202) {
                ex.sendResponseHeaders(202, -1);
                return;
            }
            write(ex, r.status(), r.contentType(), r.body());
        } catch (IOException e) {
            // 客户端中途断开是常态，不刷堆栈
        } catch (RuntimeException e) {
            McbotMod.LOG.error("桥接路由异常", e);
            try {
                write(ex, 500, "application/json", "{\"error\":\"internal\"}");
            } catch (IOException ignored) {
            }
        } finally {
            ex.close();
        }
    }

    private boolean authorized(HttpExchange ex) {
        String auth = ex.getRequestHeaders().getFirst("Authorization");
        if (auth != null && auth.startsWith("Bearer ")) {
            return MessageDigestEquals(auth.substring(7).trim(), token);
        }
        String q = ex.getRequestURI().getQuery(); // EventSource 带不了 header：events 允许 ?token=
        if (q != null && ex.getRequestURI().getPath().equals("/v1/events")) {
            for (String kv : q.split("&")) {
                String[] p = kv.split("=", 2);
                if (p.length == 2 && p[0].equals("token")) {
                    return MessageDigestEquals(java.net.URLDecoder.decode(
                            p[1], StandardCharsets.UTF_8), token);
                }
            }
        }
        return false;
    }

    /** 常量时间比较，别用 String.equals 泄长度时序（本机威胁低，照章办事）。 */
    private static boolean MessageDigestEquals(String a, String b) {
        return java.security.MessageDigest.isEqual(
                a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    private void sse(HttpExchange ex) throws IOException {
        long last = 0;
        String head = ex.getRequestHeaders().getFirst("Last-Event-ID");
        if (head != null) {
            try {
                last = Long.parseLong(head.trim());
            } catch (NumberFormatException ignored) {
            }
        }
        ex.getResponseHeaders().add("Content-Type", "text/event-stream; charset=utf-8");
        ex.getResponseHeaders().add("Cache-Control", "no-cache");
        ex.getResponseHeaders().add("Connection", "keep-alive");
        ex.sendResponseHeaders(200, 0);
        OutputStream os = ex.getResponseBody();
        SseClient c = new SseClient(ex);
        clients.add(c);
        try {
            for (EventRing.Event e : ring.since(last)) {
                os.write(("id: " + e.id() + "\nevent: " + e.type()
                        + "\ndata: " + e.dataJson() + "\n\n").getBytes(StandardCharsets.UTF_8));
            }
            os.flush();
            while (true) {
                String f = c.out.poll(15, TimeUnit.SECONDS);
                if (f == null) {
                    os.write(": ping\n\n".getBytes(StandardCharsets.UTF_8));
                } else if (f.equals("STOP")) {
                    break;
                } else {
                    os.write(f.getBytes(StandardCharsets.UTF_8));
                }
                os.flush();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            clients.remove(c);
        }
    }

    private static String readBody(HttpExchange ex) throws IOException {
        byte[] b = ex.getRequestBody().readNBytes(MAX_BODY + 1);
        if (b.length > MAX_BODY) {
            throw new IOException("body too large");
        }
        return new String(b, StandardCharsets.UTF_8);
    }

    private static void write(HttpExchange ex, int status, String ctype, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", ctype);
        ex.sendResponseHeaders(status, b.length);
        ex.getResponseBody().write(b);
        ex.getResponseBody().flush();
    }

    private static String loadOrCreateToken() throws IOException {
        Path dir = FabricLoader.getInstance().getGameDir().resolve("mcbot");
        Files.createDirectories(dir);
        Path f = dir.resolve("bridge.token");
        if (Files.exists(f)) {
            String t = Files.readString(f, StandardCharsets.UTF_8).trim();
            if (!t.isBlank()) {
                return t;
            }
        }
        byte[] rnd = new byte[16];
        new SecureRandom().nextBytes(rnd);
        StringBuilder sb = new StringBuilder();
        for (byte x : rnd) {
            sb.append(String.format("%02x", x));
        }
        Files.writeString(f, sb.toString(), StandardCharsets.UTF_8);
        return sb.toString();
    }

    // ---- Backend：把桥的语义翻成 AgentRunner 动作 ----

    private static final class RunnerBackend implements BridgeBackend {
        @Override
        public long submitTask(String text) {
            AgentRunner r = McbotClient.runner();
            return r == null ? -1 : r.submitTask(text);
        }

        @Override
        public java.util.concurrent.CompletableFuture<String> ask(String text) {
            AgentRunner r = McbotClient.runner();
            if (r == null) {
                return java.util.concurrent.CompletableFuture.failedFuture(
                        new IllegalStateException("尚未进世界，大脑不在线"));
            }
            return r.askNext(text);
        }

        @Override
        public boolean answer(String questionId, String text) {
            AgentRunner r = McbotClient.runner();
            return r != null && r.answerQuestion(questionId, text);
        }

        @Override
        public String statusJson() {
            AgentRunner r = McbotClient.runner();
            if (r == null) {
                Minecraft mc = Minecraft.getInstance();
                JsonObject o = new JsonObject();
                o.addProperty("in_game", mc.level != null);
                o.addProperty("brain_enabled", false);
                o.addProperty("companion", "");
                return o.toString();
            }
            return r.statusJson();
        }

        @Override
        public boolean cancel(long taskId) {
            AgentRunner r = McbotClient.runner();
            if (r == null) {
                return false;
            }
            Minecraft.getInstance().execute(r::requestCancel);
            return true;
        }
    }
}
