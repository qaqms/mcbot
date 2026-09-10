package com.neko.mcbot.agentcore.llm;

import com.neko.mcbot.agentcore.provider.OpenAiCompatProvider;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R2-A 真流式的端到端单测：自起一个 HTTP 服务，按真实节奏（帧间隔 &gt; 0）吐 SSE，
 * 断言"宿主在整轮结束之前就拿到了东西"。
 *
 * <p><b>为什么必须起真服务而不是打桩 HTTP</b>：要证明的正是"行会随流交出来"这个传输层行为。
 * 用 mock 换掉 HttpClient 等于把被测对象换成替身，回归时什么都测不到。
 *
 * <p>时间判据一律用远大于调度抖动的间隔（帧距 200ms 对门槛 120ms），不追求精确时长——
 * 抖动下精确断言只会变成随机失败源。
 */
class SseIncrementalTest {

    private HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
    }

    // ---- SSE 帧构造 ----

    private static String frame(String json) {
        return "data: " + json + "\n\n";
    }

    private static String textChunk(String text) {
        return frame("{\"choices\":[{\"delta\":{\"content\":\"" + text + "\"}}]}");
    }

    /**
     * 造一条 tool_call 增量帧。括号层级：{ choices:[ { delta:{ tool_calls:[ { index, function:{
     * name, arguments } } ] } } ] } —— 收尾必须是 4 个 '}'（function/tool_calls 元素/delta/choice）
     * 加 1 个 ']'（choices）再 1 个 '}'（根）。多写一个就是非法 JSON，会被整行丢弃。
     *
     * @param argsFragment 已按 JSON 字符串内容转义过的 fragment
     *                     （{@code {"x":1} 的 Java 字面量是 {@code "{\\\"x\\\":1"}）
     */
    private static String toolChunk(int index, String id, String name, String argsFragment) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":").append(index);
        if (id != null) {
            sb.append(",\"id\":\"").append(id).append('"');
        }
        sb.append(",\"function\":{");
        if (name != null) {
            sb.append("\"name\":\"").append(name).append('"');
            sb.append(',');
        }
        sb.append("\"arguments\":\"").append(argsFragment).append("\"}}]}}]}");
        return checked(sb.toString());
    }

    private static OpenAiCompatProvider provider(String baseUrl) {
        return new OpenAiCompatProvider("test", baseUrl, "sk-test", "test-model");
    }

    /**
     * 帧构造器本身也要被验：测试替身发出去的 JSON 若是坏的，失败会伪装成"被测代码有 bug"。
     * 每个 data 载荷都当场让 Gson 解一遍，不合法就直接炸在出题人脸上。
     */
    private static String checked(String json) {
        try {
            com.google.gson.JsonParser.parseString(json);
        } catch (RuntimeException e) {
            throw new AssertionError("测试造的 SSE 载荷不是合法 JSON: " + json, e);
        }
        return frame(json);
    }

    private static final String ARGS_HEAD = "{\\\"x\\\":1";      // 载荷里是 {"x":1
    private static final String ARGS_TAIL = ",\\\"y\\\":2,\\\"z\\\":3}";
    private static final ToolSpec MOVE_WITH_REQUIRED =
            ToolSpec.of("move_to", "走", "{\"required\":[\"x\",\"y\",\"z\"]}");

    /** 记录回调时序的探针，线程安全。 */
    private static final class Probe implements TurnSink {
        final List<String> deltas = Collections.synchronizedList(new ArrayList<>());
        final List<Integer> readyIndexes = Collections.synchronizedList(new ArrayList<>());
        final AtomicReference<AssistantTurn> turn = new AtomicReference<>();
        final AtomicReference<Throwable> error = new AtomicReference<>();
        final AtomicInteger chunkCount = new AtomicInteger();
        volatile long deltaAtMs = -1;
        volatile long readyAtMs = -1;
        volatile long completeAtMs = -1;
        private final long t0 = System.nanoTime();

        private long now() {
            return (System.nanoTime() - t0) / 1_000_000L;
        }

        @Override
        public void onTextDelta(String delta) {
            deltas.add(delta);
            if (deltaAtMs < 0) {
                deltaAtMs = now();
            }
        }

        @Override
        public void onToolCallReady(int index, ToolCall call) {
            readyIndexes.add(index);
            if (readyAtMs < 0) {
                readyAtMs = now();
            }
        }

        @Override
        public void onComplete(AssistantTurn t, Throwable e) {
            turn.set(t);
            error.set(e);
            completeAtMs = now();
        }

        @Override
        public void onCounters(int chunks, int deltas, int toolCallsReady) {
            chunkCount.set(chunks);
        }
    }

    private static void await(CompletableFuture<?> f) throws Exception {
        f.get(20, TimeUnit.SECONDS);
    }

    // ---- 服务端 ----

    /** @param pieces 原样写出的片段（UTF-8 编码） */
    private String serve(List<Route> routes) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        for (Route r : routes) {
            server.createContext(r.path, ex -> {
                r.hits.incrementAndGet();
                drain(ex);
                ex.getResponseHeaders().add("Content-Type", r.contentType);
                ex.sendResponseHeaders(r.status, 0);
                try (OutputStream out = ex.getResponseBody()) {
                    for (String piece : r.pieces) {
                        out.write(piece.getBytes(r.rawBytes
                                ? StandardCharsets.ISO_8859_1 : StandardCharsets.UTF_8));
                        out.flush();
                        if (r.gapMs > 0) {
                            try {
                                Thread.sleep(r.gapMs);
                            } catch (InterruptedException ie) {
                                Thread.currentThread().interrupt();
                                return;
                            }
                        }
                    }
                }
            });
        }
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private static void drain(HttpExchange ex) throws IOException {
        byte[] sink = new byte[4096];
        while (ex.getRequestBody().read(sink) > 0) {
            // 请求体照单全收：本测只关心响应方向的流式行为
        }
    }

    private static final class Route {
        final String path;
        final int status;
        final String contentType;
        final List<String> pieces;
        final long gapMs;
        final boolean rawBytes;
        final AtomicInteger hits = new AtomicInteger();

        Route(String path, int status, String contentType, List<String> pieces, long gapMs,
              boolean rawBytes) {
            this.path = path;
            this.status = status;
            this.contentType = contentType;
            this.pieces = pieces;
            this.gapMs = gapMs;
            this.rawBytes = rawBytes;
        }

        static Route ok(List<String> frames, long gapMs) {
            return new Route("/chat/completions", 200, "text/event-stream", frames, gapMs, false);
        }
    }

    // ---- 用例 ----

    @Test
    void deltasArriveWhileStreamIsStillOpen() throws Exception {
        List<String> frames = List.of(
                textChunk("正"), textChunk("在"), textChunk("挖"), "data: [DONE]\n\n");
        String base = serve(List.of(Route.ok(frames, 200)));
        Probe probe = new Probe();

        AssistantTurn turn = new LlmClient(provider(base), Duration.ofSeconds(15))
                .chat("sys", List.of(new Msg.User("hi")), List.of(), probe, true)
                .get(20, TimeUnit.SECONDS);

        assertEquals(List.of("正", "在", "挖"), probe.deltas, "三片文本必须逐段到达，而不是合并成一段");
        assertEquals("正在挖", turn.text());
        assertTrue(probe.deltaAtMs >= 0 && probe.deltaAtMs < 600,
                "首段文本应在流还开着的时候就到（实际 " + probe.deltaAtMs + "ms）");
        assertTrue(probe.completeAtMs > probe.deltaAtMs,
                "整轮完成必须晚于首段文本：早到才有早派发的余地");
    }

    /** 关键断言：工具就绪必须早于整轮完成——这正是早派发能省下时间的原因。 */
    @Test
    void toolCallIsReadyBeforeTurnCompletes() throws Exception {
        List<String> frames = List.of(
                toolChunk(0, "call_1", "move_to", ARGS_HEAD),
                toolChunk(0, null, null, ARGS_TAIL),
                // 后面还跟着文本：模拟"工具早写完了，模型还在说别的"
                textChunk("我"), textChunk("出发了"), "data: [DONE]\n\n");
        String base = serve(List.of(Route.ok(frames, 200)));
        Probe probe = new Probe();
        // 必须把工具表传进来：required 名单来自 schema，传空表就永远无法判定"写完了"
        var tools = List.of(MOVE_WITH_REQUIRED);

        AssistantTurn turn = new LlmClient(provider(base), Duration.ofSeconds(15))
                .chat("sys", List.of(new Msg.User("走")), tools, probe, true)
                .get(20, TimeUnit.SECONDS);

        assertEquals(List.of(0), probe.readyIndexes, "工具应恰好就绪一次");
        assertTrue(probe.readyAtMs > 0 && probe.readyAtMs < probe.completeAtMs,
                "工具就绪(" + probe.readyAtMs + "ms) 必须早于整轮完成(" + probe.completeAtMs + "ms)");
        assertEquals(1, turn.toolCalls().size());
        assertEquals("move_to", turn.toolCalls().get(0).name());
        assertEquals("{\"x\":1,\"y\":2,\"z\":3}", turn.toolCalls().get(0).argsJson());
        assertEquals("我出发了", turn.text());
    }

    @Test
    void usageIsParsedIncludingCacheHitTokens() throws Exception {
        List<String> frames = List.of(textChunk("好"),
                frame("{\"choices\":[],\"usage\":{\"prompt_tokens\":120,\"completion_tokens\":8,"
                        + "\"prompt_cache_hit_tokens\":64}}"),
                "data: [DONE]\n\n");
        String base = serve(List.of(Route.ok(frames, 60)));

        AssistantTurn turn = new LlmClient(provider(base), Duration.ofSeconds(15))
                .chat("sys", List.of(new Msg.User("hi")), List.of(), null, true)
                .get(20, TimeUnit.SECONDS);

        assertEquals(120, turn.promptTokens());
        assertEquals(8, turn.completionTokens());
        assertEquals(64, turn.cachedTokens(), "deepseek 方言的缓存命中数要能透传");
    }

    /** 不完整（缺 required）的参数不许报就绪。 */
    @Test
    void partialToolArgsDoNotReportReady() throws Exception {
        List<String> frames = List.of(
                toolChunk(0, "call_1", "move_to", "{\\\"x\\\":1}"),
                textChunk("嗯"), "data: [DONE]\n\n");
        String base = serve(List.of(Route.ok(frames, 120)));
        Probe probe = new Probe();
        var tools = List.of(MOVE_WITH_REQUIRED);

        await(new LlmClient(provider(base), Duration.ofSeconds(15))
                .chat("sys", List.of(new Msg.User("走")), tools, probe, true));

        assertTrue(probe.readyIndexes.isEmpty(),
                "只有 x 的残缺参数不得报就绪（否则会拿着半截坐标去挖）");
    }

    /** 补齐之后即使又来了更多碎片，也不许重复报就绪。 */
    @Test
    void readyReportedOnlyOnceAcrossManyFragments() throws Exception {
        List<String> frames = new ArrayList<>();
        frames.add(toolChunk(0, "c", "move_to", ARGS_HEAD));
        for (int i = 0; i < 4; i++) {
            frames.add(textChunk("…"));
        }
        frames.add(toolChunk(0, null, null, ARGS_TAIL));
        frames.add("data: [DONE]\n\n");
        String base = serve(List.of(Route.ok(frames, 90)));
        Probe probe = new Probe();
        var tools = List.of(MOVE_WITH_REQUIRED);

        await(new LlmClient(provider(base), Duration.ofSeconds(15))
                .chat("sys", List.of(new Msg.User("走")), tools, probe, true));

        assertEquals(List.of(0), probe.readyIndexes, "就绪信号必须幂等");
    }

    @Test
    void accumulateFalseStillStreamsButReturnsNoTurn() throws Exception {
        List<String> frames = List.of(textChunk("甲"), textChunk("乙"), "data: [DONE]\n\n");
        String base = serve(List.of(Route.ok(frames, 80)));
        Probe probe = new Probe();

        AssistantTurn turn = new LlmClient(provider(base), Duration.ofSeconds(15))
                .chat("sys", List.of(new Msg.User("hi")), List.of(), probe, false)
                .get(20, TimeUnit.SECONDS);

        assertNull(turn, "accumulate=false 时不该再拼整轮");
        assertEquals(List.of("甲", "乙"), probe.deltas, "即使不累积，增量仍要照发");
    }

    /** 中文跨 TCP 片段被切断：手写解码器必须把残缺的多字节序列攒住。 */
    @Test
    void multibyteCharSplitAcrossChunksIsDecodedIntact() throws Exception {
        String whole = textChunk("你好世界");
        byte[] bytes = whole.getBytes(StandardCharsets.UTF_8);
        // 切点落在第一个汉字的三字节序列内部，才算真的"切断一个字符"
        int charStart = whole.indexOf('你');
        byte[] prefix = whole.substring(0, charStart).getBytes(StandardCharsets.UTF_8);
        int inside = prefix.length + 1;
        assertTrue(inside > prefix.length && inside < bytes.length, "切点必须落在字符内部");
        List<String> pieces = List.of(
                new String(bytes, 0, inside, StandardCharsets.ISO_8859_1),
                new String(bytes, inside, bytes.length - inside, StandardCharsets.ISO_8859_1),
                "data: [DONE]\n\n");
        String base = serve(List.of(new Route("/chat/completions", 200, "text/event-stream",
                pieces, 100, true)));
        Probe probe = new Probe();

        await(new LlmClient(provider(base), Duration.ofSeconds(15))
                .chat("sys", List.of(new Msg.User("hi")), List.of(), probe, true));

        assertEquals("你好世界", probe.turn.get().text(),
                "被切断的 UTF-8 汉字必须原样复原，不得出现替换字符");
    }

    @Test
    void non200JsonErrorSurfacesInException() throws Exception {
        String base = serve(List.of(new Route("/chat/completions", 401, "application/json",
                List.of("{\"error\":{\"message\":\"bad key\"}}"), 0, false)));
        Probe probe = new Probe();

        CompletableFuture<AssistantTurn> f = new LlmClient(provider(base), Duration.ofSeconds(15))
                .chat("sys", List.of(new Msg.User("hi")), List.of(), probe, true);

        ExecutionException e = assertThrows(ExecutionException.class,
                () -> f.get(20, TimeUnit.SECONDS));
        String msg = String.valueOf(e.getCause().getMessage());
        assertTrue(msg.contains("401"), "异常里要有状态码，实际: " + msg);
        assertTrue(msg.contains("bad key"), "异常里要有服务端错误体，实际: " + msg);
        assertNotNull(probe.error.get(), "sink 也要收到失败通知");
        assertTrue(probe.readyIndexes.isEmpty(), "错误响应不得产生任何工具就绪信号");
    }

    /** 根路径被网页接管时自动换道 /v1 重试一次（旧行为，不得因流式改造而丢）。 */
    @Test
    void htmlOnRootPathRetriesOnV1() throws Exception {
        List<String> v1Frames = List.of(textChunk("换道成功"), "data: [DONE]\n\n");
        Route root = new Route("/chat/completions", 404, "text/html",
                List.of("<!DOCTYPE html><html><body>cf wall</body></html>"), 0, false);
        Route v1 = new Route("/v1/chat/completions", 200, "text/event-stream", v1Frames, 0, false);
        String base = serve(List.of(root, v1));
        Probe probe = new Probe();

        AssistantTurn turn = new LlmClient(provider(base), Duration.ofSeconds(15))
                .chat("sys", List.of(new Msg.User("hi")), List.of(), probe, true)
                .get(20, TimeUnit.SECONDS);

        assertEquals(1, root.hits.get(), "根路径只该被打一次");
        assertEquals(1, v1.hits.get(), "换道只该发生一次");
        assertEquals("换道成功", turn.text());
    }

    /** 换道不得把 baseUrl 写回客户端状态（否则后续请求全被带偏）。 */
    @Test
    void retryDoesNotPoisonLaterRequests() throws Exception {
        List<String> v1Frames = List.of(textChunk("第一次"), "data: [DONE]\n\n");
        Route root = new Route("/chat/completions", 404, "text/html",
                List.of("<html>wall</html>"), 0, false);
        Route v1 = new Route("/v1/chat/completions", 200, "text/event-stream", v1Frames, 0, false);
        String base = serve(List.of(root, v1));
        LlmClient client = new LlmClient(provider(base), Duration.ofSeconds(15));
        List<Msg> convo = List.of(new Msg.User("hi"));

        AssistantTurn first = client.chat("sys", convo, List.of(), null, true)
                .get(20, TimeUnit.SECONDS);
        AssistantTurn second = client.chat("sys", convo, List.of(), null, true)
                .get(20, TimeUnit.SECONDS);

        assertEquals("第一次", first.text());
        assertEquals("第一次", second.text(), "第二次请求仍应从原 baseUrl 出发（各自换道）");
        assertEquals(2, root.hits.get(), "每次请求各自打一次根路径，而不是记住换道结果");
        assertEquals(2, v1.hits.get(), "两次都要走 /v1");
    }

    /** 流中途断开：失败要冒出来，不能静默当成"空回答"。 */
    @Test
    void truncatedStreamFailsInsteadOfSilentlySucceeding() throws Exception {
        // 声明的长度远大于实际写出的字节 → 客户端读流时必然异常
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", ex -> {
            drain(ex);
            ex.getResponseHeaders().add("Content-Type", "text/event-stream");
            ex.sendResponseHeaders(200, 4096);
            try (OutputStream out = ex.getResponseBody()) {
                out.write(textChunk("半").getBytes(StandardCharsets.UTF_8));
                out.flush();
            }
        });
        server.start();
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        Probe probe = new Probe();

        CompletableFuture<AssistantTurn> f = new LlmClient(provider(base), Duration.ofSeconds(15))
                .chat("sys", List.of(new Msg.User("hi")), List.of(), probe, true);

        assertThrows(Exception.class, () -> f.get(20, TimeUnit.SECONDS),
                "截断的流必须失败，不能当成功");
        assertNotNull(probe.error.get(), "sink 必须收到失败通知");
    }
}


