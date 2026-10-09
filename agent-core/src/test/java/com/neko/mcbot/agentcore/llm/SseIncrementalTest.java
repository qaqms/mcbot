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
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
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
        final AtomicInteger deltaCount = new AtomicInteger();
        final AtomicInteger readyCount = new AtomicInteger();
        final List<TurnTimings.Snapshot> timings = new CopyOnWriteArrayList<>();
        final AtomicInteger completions = new AtomicInteger();
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
            assertEquals(1, timings.size(), "最终统计必须在完成回调之前交付，换道不能多报");
            completions.incrementAndGet();
            turn.set(t);
            error.set(e);
            completeAtMs = now();
        }

        @Override
        public void onCounters(int chunks, int deltas, int toolCallsReady) {
            chunkCount.set(chunks);
            deltaCount.set(deltas);
            readyCount.set(toolCallsReady);
        }

        @Override
        public void onTimings(TurnTimings.Snapshot snapshot) {
            timings.add(snapshot);
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
                if (!r.path.equals(ex.getRequestURI().getPath())) {
                    ex.sendResponseHeaders(404, -1);
                    ex.close();
                    return;
                }
                r.hits.incrementAndGet();
                r.requestBody.set(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                ex.getResponseHeaders().add("Content-Type", r.contentType);
                r.beforeHeaders.run();
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
        final AtomicReference<String> requestBody = new AtomicReference<>();
        Runnable beforeHeaders = () -> {};

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
    void transportTimingsMeasureHeadersAndCountActualDeltasAndReadyCalls() throws Exception {
        String base = serve(List.of(Route.ok(List.of(": heartbeat\n\n",
                toolChunk(0, "call_1", "move_to", ARGS_HEAD),
                toolChunk(0, null, null, ARGS_TAIL),
                textChunk("a"), textChunk("b"),
                frame("{\"choices\":[{\"delta\":{},\"finish_reason\":\"tool_calls\"}]}"),
                "data: [DONE]\n\n"), 80)));
        Probe probe = new Probe();
        var turn = new LlmClient(provider(base), Duration.ofSeconds(15))
                .chat("sys", List.of(), List.of(MOVE_WITH_REQUIRED), probe, true)
                .get(20, TimeUnit.SECONDS);
        var timings = probe.timings.getFirst();
        assertEquals(5, timings.chunks(), "心跳和 DONE 不计为业务 chunk，finish 帧计入");
        assertEquals(2, timings.deltas());
        assertEquals(1, timings.toolsReady());
        assertEquals(timings.chunks(), probe.chunkCount.get());
        assertEquals(timings.deltas(), probe.deltaCount.get());
        assertEquals(timings.toolsReady(), probe.readyCount.get());
        assertTrue(timings.ttfb() >= 0);
        assertTrue(timings.ttft() - timings.ttfb() >= 120,
                "响应头计时不能等到整轮完成，首字前有心跳和两个工具帧");
        assertTrue(timings.firstTool() >= timings.ttfb());
        assertTrue(timings.ttft() > timings.firstTool());
        assertTrue(timings.afterTool() >= 40);
        assertTrue(timings.afterChunk() > timings.afterTool());
        assertTrue(timings.elapsedMs() > timings.ttft());
        assertEquals("{\"x\":1,\"y\":2,\"z\":3}", turn.toolCalls().getFirst().argsJson());
    }

    @Test
    void brokenTimingAndCounterObserversCannotFailOrRetryRequest() throws Exception {
        Route route = Route.ok(List.of(textChunk("ok"), "data: [DONE]\n\n"), 0);
        String base = serve(List.of(route));
        var completed = new AtomicInteger();
        var snapshots = new AtomicInteger();
        var turn = new LlmClient(provider(base), Duration.ofSeconds(15))
                .chat("sys", List.of(), List.of(), new TurnSink() {
                    @Override public void onCounters(int c, int d, int r) {
                        throw new IllegalStateException("observer");
                    }
                    @Override public void onTimings(TurnTimings.Snapshot timings) {
                        snapshots.incrementAndGet();
                        throw new IllegalStateException("observer");
                    }
                    @Override public void onComplete(AssistantTurn t, Throwable error) {
                        assertNull(error);
                        completed.incrementAndGet();
                    }
                }, true).get(20, TimeUnit.SECONDS);
        assertEquals("ok", turn.text());
        assertEquals(1, snapshots.get());
        assertEquals(1, completed.get());
        assertEquals(1, route.hits.get());
    }

    @Test
    void fallbackTimingsStartAtTheFinalAttemptInsteadOfMixingRequestOrigins() throws Exception {
        var clock = new AtomicLong();
        Route root = new Route("/chat/completions", 404, "text/html",
                List.of("<html>wrong path</html>"), 0, false);
        Route v1 = new Route("/v1/chat/completions", 200, "text/event-stream",
                List.of(textChunk("ok"), "data: [DONE]\n\n"), 0, false);
        root.beforeHeaders = () -> clock.set(1_000_000_000L);
        v1.beforeHeaders = () -> clock.set(3_000_000_000L);
        String base = serve(List.of(root, v1));
        Probe probe = new Probe();
        var turn = new LlmClient(provider(base), Duration.ofSeconds(15),
                Duration.ofSeconds(5), clock::get)
                .chat("sys", List.of(), List.of(), probe, true).get(20, TimeUnit.SECONDS);
        assertEquals("ok", turn.text());
        assertEquals(1, root.hits.get());
        assertEquals(1, v1.hits.get());
        assertEquals(1, probe.timings.size());
        var timings = probe.timings.getFirst();
        assertEquals(1_000_000_000L, timings.requestStartedNanos());
        assertEquals(2000, timings.ttfb());
        assertEquals(2000, timings.ttft());
        assertEquals(2000, timings.elapsedMs());
        assertEquals(1, timings.chunks());
        assertEquals(1, timings.deltas());
    }

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
        assertEquals(2, probe.timings.getFirst().deltas());
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
        assertTrue(e.getCause() instanceof LlmFailure);
        assertTrue(!msg.contains("bad key"), "服务端原始错误体不得进入异常或用户消息");
        assertNotNull(probe.error.get(), "sink 也要收到失败通知");
        assertTrue(probe.readyIndexes.isEmpty(), "错误响应不得产生任何工具就绪信号");
    }

    @Test
    void serverEchoedCredentialNeverEntersTheFailure() throws Exception {
        String base = serve(List.of(new Route("/chat/completions", 401, "application/json",
                List.of("{\"error\":{\"message\":\"Authorization: Bearer private-value\"}}"), 0, false)));
        ExecutionException failure = assertThrows(ExecutionException.class,
                () -> new LlmClient(provider(base), Duration.ofSeconds(15))
                        .chat("sys", List.of(new Msg.User("hi")), List.of()).get(20, TimeUnit.SECONDS));
        assertTrue(!failure.getCause().toString().contains("private-value"));
        assertTrue(!LlmFailure.userMessage(failure).contains("private-value"));
    }

    @Test
    void htmlWithHttp200IsNotReportedAsAnEmptySuccessfulReply() throws Exception {
        String base = serve(List.of(new Route("/chat/completions", 200, "text/html",
                List.of("<html>not an API</html>"), 0, false)));
        ExecutionException failure = assertThrows(ExecutionException.class,
                () -> new LlmClient(provider(base), Duration.ofSeconds(15))
                        .chat("sys", List.of(new Msg.User("hi")), List.of()).get(20, TimeUnit.SECONDS));
        assertTrue(failure.getCause() instanceof LlmFailure);
        assertTrue(LlmFailure.userMessage(failure).contains("网页"));
    }

    @Test
    void diagnosticSinkReceivesSuccessfulResponseShapeExactlyOnce() throws Exception {
        String base = serve(List.of(Route.ok(List.of(textChunk("好"),
                frame("{\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}"),
                "data: [DONE]\n\n"), 0)));
        List<ResponseDiagnostics> diagnostics = new ArrayList<>();
        var turn = new LlmClient(provider(base), Duration.ofSeconds(15), diagnostics::add)
                .chat("sys", List.of(new Msg.User("hi")), List.of()).get(20, TimeUnit.SECONDS);
        assertEquals("好", turn.text());
        assertEquals(1, diagnostics.size());
        var shape = diagnostics.getFirst();
        assertEquals(200, shape.http());
        assertEquals(ResponseDiagnostics.Format.SSE, shape.format());
        assertEquals(3, shape.dataLines());
        assertEquals(2, shape.jsonFrames());
        assertEquals(2, shape.deltaFrames());
        assertEquals(0, shape.parseErrors());
        assertEquals(ResponseDiagnostics.Finish.STOP, shape.finish());
        assertTrue(shape.bytes() > 0);
        assertTrue(shape.done());
        assertTrue(!shape.empty());
    }

    @Test
    void requestSummaryDescribesTheExactSentBodyAndMatchesTheResponse() throws Exception {
        Route route = Route.ok(List.of(textChunk("好"), "data: [DONE]\n\n"), 0);
        String base = serve(List.of(route));
        List<RequestDiagnostics> requests = new ArrayList<>();
        List<ResponseDiagnostics> responses = new ArrayList<>();
        var client = new LlmClient(provider(base), Duration.ofSeconds(15), requests::add, responses::add);
        List<Msg> history = List.of(new Msg.User("private-user"),
                new Msg.Assistant("", List.of(new ToolCall("private-call", "move_to", "{\"x\":1}"))),
                new Msg.Tool("private-call", "move_to", "private-result", true));
        client.chat("private-system", history, List.of(MOVE_WITH_REQUIRED)).get(20, TimeUnit.SECONDS);
        assertEquals(1, requests.size());
        assertEquals(1, responses.size());
        var request = requests.getFirst();
        var actualBody = com.google.gson.JsonParser.parseString(route.requestBody.get()).getAsJsonObject();
        assertEquals(RequestDiagnostics.from(request.requestId(), request.attempt(), actualBody,
                route.requestBody.get().getBytes(StandardCharsets.UTF_8).length), request);
        assertEquals(request.requestId(), responses.getFirst().requestId());
        assertEquals(request.attempt(), responses.getFirst().attempt());
        assertEquals(0, request.missingResults());
        assertEquals(0, request.orphanResults());
        assertTrue(!request.summary().contains("private"));
        assertTrue(!responses.getFirst().toString().contains("private"));
    }

    @Test
    void streamErrorCategoryAndParameterAreSafeAndDoNotTriggerRetry() throws Exception {
        Route route = Route.ok(List.of(frame("{\"error\":{\"code\":\"context_length_exceeded\","
                + "\"type\":\"invalid_request_error\",\"param\":\"messages[3]\","
                + "\"message\":\"private-value\"}}")), 0);
        String base = serve(List.of(route));
        List<ResponseDiagnostics> responses = new ArrayList<>();
        ExecutionException failure = assertThrows(ExecutionException.class,
                () -> new LlmClient(provider(base), Duration.ofSeconds(15), responses::add)
                        .chat("sys", List.of(new Msg.User("hi")), List.of()).get(20, TimeUnit.SECONDS));
        var error = responses.getFirst().error();
        assertEquals(ServiceErrorDiagnostics.Category.CONTEXT_LIMIT, error.category());
        assertEquals(ServiceErrorDiagnostics.Source.CODE, error.source());
        assertEquals(ServiceErrorDiagnostics.Param.MESSAGES, error.param());
        assertEquals(1, route.hits.get());
        assertTrue(LlmFailure.userMessage(failure).contains("上下文超限"));
        assertTrue(!failure.getCause().toString().contains("private"));
        assertTrue(!responses.getFirst().toString().contains("private"));
    }

    @Test
    void prettyNon200JsonDistinguishesQuotaFromRateLimit() throws Exception {
        Route route = new Route("/chat/completions", 429, "application/json",
                List.of("{\n \"error\": {\n \"code\": \"insufficient_quota\",\n"
                        + "\"message\":\"private-value\"\n }\n}"), 0, false);
        String base = serve(List.of(route));
        List<ResponseDiagnostics> responses = new ArrayList<>();
        ExecutionException failure = assertThrows(ExecutionException.class,
                () -> new LlmClient(provider(base), Duration.ofSeconds(15), responses::add)
                        .chat("sys", List.of(new Msg.User("hi")), List.of()).get(20, TimeUnit.SECONDS));
        assertEquals(429, responses.getFirst().http());
        assertEquals(ServiceErrorDiagnostics.Category.QUOTA, responses.getFirst().error().category());
        assertEquals(1, responses.getFirst().errorFrames());
        assertTrue(LlmFailure.userMessage(failure).contains("额度"));
        assertTrue(!LlmFailure.userMessage(failure).contains("private"));
    }

    @Test
    void prettyHttp200JsonErrorIsClassifiedButNotAcceptedAsChat() throws Exception {
        String base = serve(List.of(new Route("/chat/completions", 200, "application/json",
                List.of("{\n\"error\": {\n\"code\":\"unsupported_parameter\","
                        + "\"param\":\"stream_options.include_usage\","
                        + "\"message\":\"private-value\"\n}\n}"), 0, false)));
        List<ResponseDiagnostics> responses = new ArrayList<>();
        assertThrows(ExecutionException.class,
                () -> new LlmClient(provider(base), Duration.ofSeconds(15), responses::add)
                        .chat("sys", List.of(new Msg.User("hi")), List.of()).get(20, TimeUnit.SECONDS));
        var detail = responses.getFirst();
        assertEquals(0, detail.dataLines());
        assertEquals(0, detail.malformedFrames());
        assertEquals(ServiceErrorDiagnostics.Category.UNSUPPORTED_PARAMETER, detail.error().category());
        assertEquals(ServiceErrorDiagnostics.Param.STREAM_OPTIONS, detail.error().param());
    }

    @Test
    void oversizedJsonErrorIsNotLoggedOrPartiallyGuessed() throws Exception {
        String base = serve(List.of(new Route("/chat/completions", 400, "application/json",
                List.of("{\"error\":{\"code\":\"private-code\",\"message\":\""
                        + "private-value".repeat(2000) + "\"}}"), 0, false)));
        List<ResponseDiagnostics> responses = new ArrayList<>();
        assertThrows(ExecutionException.class,
                () -> new LlmClient(provider(base), Duration.ofSeconds(15), responses::add)
                        .chat("sys", List.of(new Msg.User("hi")), List.of()).get(20, TimeUnit.SECONDS));
        assertTrue(responses.getFirst().errorBodyTruncated());
        assertEquals(0, responses.getFirst().errorFrames());
        assertTrue(!responses.getFirst().summary().contains("private"));
    }

    @Test
    void misleadingJsonHeaderDoesNotBreakExistingSseOrHtmlFallback() throws Exception {
        Route root = new Route("/chat/completions", 404, "application/json",
                List.of("<html>private-value</html>"), 0, false);
        Route v1 = new Route("/v1/chat/completions", 200, "application/json",
                List.of(textChunk("好"), "data: [DONE]\n\n"), 0, false);
        String base = serve(List.of(root, v1));
        List<RequestDiagnostics> requests = new ArrayList<>();
        List<ResponseDiagnostics> responses = new ArrayList<>();
        var turn = new LlmClient(provider(base), Duration.ofSeconds(15), requests::add, responses::add)
                .chat("sys", List.of(new Msg.User("hi")), List.of()).get(20, TimeUnit.SECONDS);
        assertEquals("好", turn.text());
        assertEquals(2, requests.size());
        assertEquals(2, responses.size());
        assertEquals(1, requests.getFirst().attempt());
        assertEquals(2, requests.getLast().attempt());
        assertTrue(requests.getFirst().requestId() != requests.getLast().requestId());
        assertEquals(requests.getFirst().requestId(), responses.getFirst().requestId());
        assertEquals(requests.getLast().requestId(), responses.getLast().requestId());
    }

    @Test
    void requestLoggingFailureCannotAlterDispatchOrCauseRetry() throws Exception {
        Route route = Route.ok(List.of(textChunk("好"), "data: [DONE]\n\n"), 0);
        String base = serve(List.of(route));
        var turn = new LlmClient(provider(base), Duration.ofSeconds(15),
                request -> { throw new IllegalStateException("private-value"); }, response -> {})
                .chat("sys", List.of(new Msg.User("hi")), List.of()).get(20, TimeUnit.SECONDS);
        assertEquals("好", turn.text());
        assertEquals(1, route.hits.get());
    }

    @Test
    void aKnownStreamErrorSuppressesToolsAndTextInSubsequentFrames() throws Exception {
        String base = serve(List.of(Route.ok(List.of(frame("{\"error\":{\"code\":\"upstream_error\"}}"),
                toolChunk(0, "call_1", "move_to", ARGS_HEAD + ARGS_TAIL),
                textChunk("private-value"), "data: [DONE]\n\n"), 0)));
        Probe probe = new Probe();
        assertThrows(ExecutionException.class,
                () -> new LlmClient(provider(base), Duration.ofSeconds(15))
                        .chat("sys", List.of(new Msg.User("hi")), List.of(MOVE_WITH_REQUIRED), probe, true)
                        .get(20, TimeUnit.SECONDS));
        assertTrue(probe.readyIndexes.isEmpty());
        assertTrue(probe.deltas.isEmpty());
        assertEquals(1, probe.completions.get());
    }

    @Test
    void http200StreamErrorIsNotAnEmptyReplyOrPartialSuccess() throws Exception {
        String base = serve(List.of(Route.ok(List.of(textChunk("partial"),
                frame("{\"error\":{\"message\":\"Authorization: Bearer private-value\"}}"),
                "data: [DONE]\n\n"), 0)));
        List<ResponseDiagnostics> diagnostics = new ArrayList<>();
        Probe probe = new Probe();
        ExecutionException failure = assertThrows(ExecutionException.class,
                () -> new LlmClient(provider(base), Duration.ofSeconds(15), diagnostics::add)
                        .chat("sys", List.of(new Msg.User("hi")), List.of(), probe, true)
                        .get(20, TimeUnit.SECONDS));
        assertTrue(LlmFailure.userMessage(failure).contains("响应中报告错误"));
        assertTrue(!LlmFailure.userMessage(failure).contains("private-value"));
        assertTrue(!failure.getCause().toString().contains("private-value"));
        assertEquals(1, probe.completions.get());
        assertNotNull(probe.error.get());
        assertEquals(1, diagnostics.size());
        assertEquals(1, diagnostics.getFirst().errorFrames());
        assertEquals(2, probe.timings.getFirst().chunks());
        assertEquals(1, probe.timings.getFirst().deltas());
        assertEquals(0, probe.timings.getFirst().toolsReady());
        assertTrue(!diagnostics.getFirst().summary().contains("partial"));
        assertTrue(!diagnostics.getFirst().summary().contains("private-value"));
    }

    @Test
    void nonStreamingJsonIsDiagnosedWithoutPretendingItWasAnSseSuccess() throws Exception {
        String json = "{\"choices\":[{\"message\":{\"content\":\"private-value\"},"
                + "\"finish_reason\":\"stop\"}]}";
        String base = serve(List.of(new Route("/chat/completions", 200, "application/json",
                List.of(json), 0, false)));
        List<ResponseDiagnostics> diagnostics = new ArrayList<>();
        assertThrows(ExecutionException.class,
                () -> new LlmClient(provider(base), Duration.ofSeconds(15), diagnostics::add)
                        .chat("sys", List.of(new Msg.User("hi")), List.of())
                        .get(20, TimeUnit.SECONDS));
        var shape = diagnostics.getFirst();
        assertEquals(ResponseDiagnostics.Format.JSON, shape.format());
        assertEquals(0, shape.dataLines());
        assertEquals(1, shape.messageFrames());
        assertTrue(shape.empty());
        assertTrue(!shape.summary().contains("private-value"));
    }

    @Test
    void emptyReasoningAndRefusalFramesHaveSafeLocalCategories() throws Exception {
        String base = serve(List.of(Route.ok(List.of(
                frame("{\"choices\":[{\"delta\":{\"reasoning_content\":\"private-value\","
                        + "\"refusal\":\"private-refusal\"},\"finish_reason\":\"private-reason\"}]}"),
                "data: [DONE]\n\n"), 0)));
        List<ResponseDiagnostics> diagnostics = new ArrayList<>();
        assertThrows(ExecutionException.class,
                () -> new LlmClient(provider(base), Duration.ofSeconds(15), diagnostics::add)
                        .chat("sys", List.of(new Msg.User("hi")), List.of())
                        .get(20, TimeUnit.SECONDS));
        var shape = diagnostics.getFirst();
        assertEquals(1, shape.reasoningFrames());
        assertEquals(1, shape.refusalFrames());
        assertEquals(ResponseDiagnostics.Finish.OTHER, shape.finish());
        assertTrue(shape.empty());
        assertTrue(!shape.summary().contains("private"));
    }

    @Test
    void malformedJsonAndProviderParseErrorsAreSeparateCounters() throws Exception {
        String base = serve(List.of(Route.ok(List.of("data: not-json\n\n",
                frame("{\"usage\":{\"prompt_tokens\":\"private-value\"},"
                        + "\"choices\":[{\"delta\":{\"content\":\"ignored\"}}]}"),
                textChunk("好"), "data: [DONE]\n\n"), 0)));
        List<ResponseDiagnostics> diagnostics = new ArrayList<>();
        var turn = new LlmClient(provider(base), Duration.ofSeconds(15), diagnostics::add)
                .chat("sys", List.of(new Msg.User("hi")), List.of()).get(20, TimeUnit.SECONDS);
        assertEquals("好", turn.text());
        assertEquals(1, diagnostics.getFirst().malformedFrames());
        assertEquals(1, diagnostics.getFirst().parseErrors());
        assertTrue(!diagnostics.getFirst().summary().contains("private-value"));
    }

    @Test
    void brokenDiagnosticSinkDoesNotFailOrRetrySuccessfulRequest() throws Exception {
        Route route = Route.ok(List.of(textChunk("好"), "data: [DONE]\n\n"), 0);
        String base = serve(List.of(route));
        var turn = new LlmClient(provider(base), Duration.ofSeconds(15), shape -> {
            throw new IllegalStateException("private-value");
        }).chat("sys", List.of(new Msg.User("hi")), List.of()).get(20, TimeUnit.SECONDS);
        assertEquals("好", turn.text());
        assertEquals(1, route.hits.get());
    }

    @Test
    void emptyResponseDoesNotAutomaticallyRetryTheTask() throws Exception {
        Route route = Route.ok(List.of("data: [DONE]\n\n"), 0);
        String base = serve(List.of(route));
        List<ResponseDiagnostics> diagnostics = new ArrayList<>();
        assertThrows(ExecutionException.class,
                () -> new LlmClient(provider(base), Duration.ofSeconds(15), diagnostics::add)
                        .chat("sys", List.of(new Msg.User("hi")), List.of())
                        .get(20, TimeUnit.SECONDS));
        assertEquals(1, route.hits.get());
        assertEquals(1, diagnostics.size());
        assertEquals(0, diagnostics.getFirst().jsonFrames());
        assertTrue(diagnostics.getFirst().done());
        assertTrue(diagnostics.getFirst().empty());
    }

    @Test
    void toolFieldsInAnErrorFrameCannotProduceAnEarlyDispatch() throws Exception {
        String error = "{\"error\":{\"message\":\"private-value\"},\"choices\":[{\"delta\":"
                + "{\"tool_calls\":[{\"index\":0,\"id\":\"call\",\"function\":"
                + "{\"name\":\"move_to\",\"arguments\":\"{\\\"x\\\":1,\\\"y\\\":2,\\\"z\\\":3}\"}}]}}]}";
        String base = serve(List.of(Route.ok(List.of(frame(error), "data: [DONE]\n\n"), 0)));
        Probe probe = new Probe();
        assertThrows(ExecutionException.class,
                () -> new LlmClient(provider(base), Duration.ofSeconds(15))
                        .chat("sys", List.of(new Msg.User("hi")), List.of(MOVE_WITH_REQUIRED),
                                probe, true).get(20, TimeUnit.SECONDS));
        assertTrue(probe.readyIndexes.isEmpty());
        assertTrue(probe.deltas.isEmpty());
        assertEquals(1, probe.completions.get());
        assertEquals(1, probe.timings.size());
        assertEquals(1, probe.timings.getFirst().chunks());
        assertEquals(0, probe.timings.getFirst().deltas());
    }

    @Test
    void pathFallbackReportsEachAttemptWithoutMixingResponseCounters() throws Exception {
        Route root = new Route("/chat/completions", 404, "text/html",
                List.of("<html>private-value</html>"), 0, false);
        Route v1 = new Route("/v1/chat/completions", 200, "text/event-stream",
                List.of(textChunk("好"), "data: [DONE]\n\n"), 0, false);
        String base = serve(List.of(root, v1));
        List<ResponseDiagnostics> diagnostics = new ArrayList<>();
        Probe probe = new Probe();
        var turn = new LlmClient(provider(base), Duration.ofSeconds(15), diagnostics::add)
                .chat("sys", List.of(new Msg.User("hi")), List.of(), probe, true)
                .get(20, TimeUnit.SECONDS);
        assertEquals("好", turn.text());
        assertEquals(2, diagnostics.size());
        assertEquals(404, diagnostics.getFirst().http());
        assertEquals(ResponseDiagnostics.Format.HTML, diagnostics.getFirst().format());
        assertEquals(0, diagnostics.getFirst().jsonFrames());
        assertEquals(200, diagnostics.getLast().http());
        assertEquals(1, diagnostics.getLast().jsonFrames());
        assertEquals(1, probe.completions.get());
        assertEquals(1, probe.timings.size());
        assertEquals(1, probe.timings.getFirst().chunks());
        assertEquals(1, probe.timings.getFirst().deltas());
        assertTrue(diagnostics.stream().noneMatch(shape -> shape.summary().contains("private-value")));
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

    @Test
    void failedV1RetryReportsOnlyOneCompletion() throws Exception {
        Route root = new Route("/chat/completions", 404, "text/html",
                List.of("<html>wrong path</html>"), 0, false);
        Route v1 = new Route("/v1/chat/completions", 401, "application/json",
                List.of("{\"error\":{\"message\":\"bad key\"}}"), 0, false);
        String base = serve(List.of(root, v1));
        Probe probe = new Probe();
        assertThrows(ExecutionException.class, () -> new LlmClient(provider(base), Duration.ofSeconds(15))
                .chat("sys", List.of(new Msg.User("hi")), List.of(), probe, true)
                .get(20, TimeUnit.SECONDS));
        assertEquals(1, probe.completions.get());
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
