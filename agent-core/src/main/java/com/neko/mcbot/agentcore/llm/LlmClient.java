package com.neko.mcbot.agentcore.llm;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.neko.mcbot.agentcore.provider.ChatProvider;
import com.neko.mcbot.agentcore.provider.StreamingTurnReader;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CoderResult;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;

/**
 * 零第三方依赖的 SSE 流式客户端：JDK HttpClient + Gson。
 * 异步执行，不阻塞任何游戏线程；失败以异常完成 future（调用方决定重试策略）。
 *
 * <p><b>R2-A 真流式</b>：默认不再用 {@code BodyHandlers.ofLines()}——它把"响应完成"与
 * "能看见行"绑死（行是收完后才一次性交出来的），早派发在物理上不可能。这里自己实现
 * {@code BodySubscriber}，按字节增量解码、按行切分（见 {@link SseBodySubscriber}），
 * 于是宿主能在整轮落地前拿到文本增量与"某个工具调用参数已闭合"的信号，先把工具跑起来。
 *
 * <p><b>为什么不用 {@code BodySubscribers.fromLineSubscriber}</b>：它确实能逐行回调，
 * 但行类型是 {@code String}、且被套在 {@code Flow.Subscriber} 与 {@code BodySubscriber}
 * 两层泛型里，本类既要做订阅者又要做结果容器，两层泛型参数直接冲突（编译期报
 * "无法使用不同的参数继承 Subscriber"）。自己实现只有约 40 行，换来的是明确的
 * 增量解码语义与可单测的行切分，反而更省心。
 *
 * <p><b>错误分流为什么在 handler 里做</b>：非 200 时响应体是错误文章（CF 墙/网关页/JSON 错误体），
 * 不是 SSE，只检查是否返回网页，错误体不会写进异常或用户消息；200 时那套完全用不上。
 * {@code BodyHandler.apply(ResponseInfo)} 拿得到 {@code statusCode()}，所以一次请求内就能
 * 选好收法；非 200 分支只保留有界内容供网页识别。
 */
public final class LlmClient implements ChatEngine {

    private static final System.Logger LOG = System.getLogger(LlmClient.class.getName());
    private static final java.util.concurrent.atomic.AtomicLong REQUEST_IDS =
            new java.util.concurrent.atomic.AtomicLong();
    private final ChatProvider provider;
    private final HttpClient http;
    private final Duration timeout;
    private final java.util.function.LongSupplier clock;
    private final java.util.function.Consumer<ResponseDiagnostics> diagnostics;
    private final java.util.function.Consumer<RequestDiagnostics> requestDiagnostics;

    public LlmClient(ChatProvider provider, Duration timeout) {
        this(provider, timeout, Duration.ofSeconds(15), System::nanoTime);
    }

    public LlmClient(ChatProvider provider, Duration timeout,
                     java.util.function.Consumer<ResponseDiagnostics> diagnostics) {
        this(provider, timeout, null, diagnostics);
    }

    public LlmClient(ChatProvider provider, Duration timeout,
                     java.util.function.Consumer<RequestDiagnostics> requestDiagnostics,
                     java.util.function.Consumer<ResponseDiagnostics> diagnostics) {
        this(provider, timeout, Duration.ofSeconds(15), System::nanoTime, requestDiagnostics, diagnostics);
    }

    public LlmClient(ChatProvider provider, Duration timeout, Duration connectTimeout,
                     java.util.function.LongSupplier nanoClock) {
        this(provider, timeout, connectTimeout, nanoClock, null, null);
    }

    private LlmClient(ChatProvider provider, Duration timeout, Duration connectTimeout,
                      java.util.function.LongSupplier nanoClock,
                      java.util.function.Consumer<RequestDiagnostics> requestDiagnostics,
                      java.util.function.Consumer<ResponseDiagnostics> diagnostics) {
        this.provider = provider;
        this.timeout = timeout;
        this.clock = nanoClock;
        this.diagnostics = diagnostics;
        this.requestDiagnostics = requestDiagnostics;
        this.http = HttpClient.newBuilder().connectTimeout(connectTimeout).build();
    }

    /** 旧路径：要整轮、不需要过程。等价于 accumulate=true + 无 sink。 */
    @Override
    public CompletableFuture<AssistantTurn> chat(String systemPrompt, List<Msg> convo, List<ToolSpec> tools) {
        return chat(systemPrompt, convo, tools, null, true);
    }

    @Override
    public CompletableFuture<AssistantTurn> chat(String systemPrompt, List<Msg> convo, List<ToolSpec> tools,
                                                TurnSink sink, boolean accumulate) {
        return chatOn(provider, true, systemPrompt, convo, tools, sink, accumulate,
                new TurnTimings(clock)).whenComplete((turn, err) -> {
            Throwable failure = unwrap(err);
            if (failure != null && sink != null) sink.onComplete(null, failure);
        });
    }

    /**
     * 不少中转站只挂在 /v1 下，根路径被首页/CF 人机验证页接管；baseUrl 忘写 /v1 时
     * 对"返回网页"的错误自动换道重试一次（deepseek 式根路径站点零成本）。
     *
     * <p>重试安全的两个前提（都已核实）：①非 200 时一行 SSE 都没处理过，因此没有任何
     * 增量/早派发信号已经发出去，不会重复触发；②每次尝试各自新建聚合器与打点器，状态不串味。
     * {@code sink.onComplete} 由最终那次尝试负责发（失败也算一次）。
     *
     * <p>baseUrl 走参数而不是字段：{@code LlmClient} 是共享的，一次换道重试若写回自身状态，
     * 就会污染后续所有请求。换道时另造一个只改 baseUrl 的同款客户端。
     */
    private CompletableFuture<AssistantTurn> chatOn(ChatProvider cp, boolean mayRetryV1,
                                                    String systemPrompt, List<Msg> convo,
                                                    List<ToolSpec> tools, TurnSink sink,
                                                    boolean accumulate, TurnTimings timings) {
        StreamingTurnReader reader = new StreamingTurnReader(tools, accumulate);
        SseBodySubscriber body = new SseBodySubscriber(cp, reader, sink, timings);
        JsonObject requestBody = cp.buildBody(systemPrompt, convo, tools);
        body.requestId = REQUEST_IDS.incrementAndGet();
        body.attempt = mayRetryV1 ? 1 : 2;
        String serialized = requestBody.toString();
        if (requestDiagnostics != null) {
            try {
                requestDiagnostics.accept(RequestDiagnostics.from(body.requestId, body.attempt,
                        requestBody, serialized.getBytes(StandardCharsets.UTF_8).length));
            } catch (RuntimeException ignored) {
                // Diagnostics neither modify the wire body nor affect request dispatch.
            }
        }

        HttpRequest request = HttpRequest.newBuilder(URI.create(cp.endpoint()))
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .headers(flatten(cp.authHeaders()))
                .POST(HttpRequest.BodyPublishers.ofString(serialized, StandardCharsets.UTF_8))
                .build();

        return http.sendAsync(request, info -> {
                    timings.markResponse();
                    body.statusCode(info.statusCode());
                    body.contentType(info.headers().firstValue("Content-Type").orElse(""));
                    return body;
                })
                .handle((resp, err) -> {
                    var snapshot = timings.snapshot();
                    reportDiagnostics(body);
                    Throwable failure = unwrap(err);
                    int code = body.statusCode();
                    boolean html = code != 200 && isHtml(body.lines());
                    if (failure == null && code != 200) {
                        if (mayRetryV1 && html
                                && !cp.endpoint().contains("/v1/")) {
                            String alt = cp.endpoint().replaceFirst("/chat/completions$",
                                    "/v1/chat/completions");
                            if (!alt.equals(cp.endpoint())) {
                                return chatOn(cp.withBaseUrl(alt), false, systemPrompt, convo, tools,
                                        sink, accumulate, new TurnTimings(clock));
                            }
                        }
                    }
                    reportTimings(sink, snapshot);
                    if (failure != null) {
                        throw new CompletionException(failure);
                    }
                    if (code != 200) {
                        throw new CompletionException(new LlmFailure(
                                html ? LlmFailure.Kind.HTML : LlmFailure.Kind.HTTP, code, body.serviceError));
                    }
                    if (body.failure() != null) {
                        throw new CompletionException(body.failure());
                    }
                    if (body.errorFrames > 0) {
                        throw new CompletionException(new LlmFailure(
                                LlmFailure.Kind.SERVICE_ERROR, code, body.serviceError));
                    }
                    AssistantTurn parsed = reader.builder().build();
                    if (parsed.text().isBlank() && parsed.toolCalls().isEmpty()) {
                        LlmFailure.Kind kind = body.format == ResponseDiagnostics.Format.HTML
                                ? LlmFailure.Kind.HTML : LlmFailure.Kind.EMPTY_STREAM;
                        throw new CompletionException(new LlmFailure(kind, code));
                    }
                    AssistantTurn turn = accumulate ? parsed : null;
                    if (sink != null) {
                        sink.onComplete(turn, null);
                    }
                    return CompletableFuture.completedFuture(turn);
                })
                .thenCompose(f -> f);
    }

    private static void reportTimings(TurnSink sink, TurnTimings.Snapshot snapshot) {
        if (sink == null) return;
        try {
            sink.onCounters(snapshot.chunks(), snapshot.deltas(), snapshot.toolsReady());
        } catch (RuntimeException ignored) {
            // Observation must not turn a completed action into a failed request.
        }
        try {
            sink.onTimings(snapshot);
        } catch (RuntimeException ignored) {
            // Deliver independently of the optional legacy counter hook.
        }
    }

    private void reportDiagnostics(SseBodySubscriber body) {
        ResponseDiagnostics snapshot = body.diagnostics();
        if (diagnostics == null) {
            if (snapshot.empty()) LOG.log(System.Logger.Level.WARNING, snapshot.summary());
            return;
        }
        try {
            diagnostics.accept(snapshot);
        } catch (RuntimeException ignored) {
            // Host logging must not change task results or trigger a retry.
        }
    }

    /**
     * 数据行消费：交 provider 解析，顺带喂打点与流式回调。只在 200 流上跑。
     *
     * <p><b>判定与聚合为什么都在 provider 那一次调用里</b>：arguments 只能被累积一次。
     * 外层若自己再解析一遍 tool_calls 喂进去，raw 会变成
     * {@code {"x":1{"x":1,"y":2,"z":3}}（真实踩到），永远解析失败，就绪永远不发生。
     * 所以 provider 走 {@code toolCallDeltaChecked}：写进去、同时把"写完了"带出来。
     */
    private static void onDataLine(String line, ChatProvider cp, StreamingTurnReader reader,
                                   TurnSink sink, TurnTimings timings, SseBodySubscriber body) {
        String s = line.trim();
        if (!s.startsWith("data:")) {
            return; // 注释行/事件名/心跳：不是数据，也不算 chunk
        }
        body.dataLines++;
        String payload = s.substring(5).trim();
        if (cp.isTerminalData(payload)) {
            body.done = true;
            return;
        }
        timings.markChunk();
        com.google.gson.JsonElement el;
        try {
            el = JsonParser.parseString(payload);
        } catch (RuntimeException ignored) {
            body.malformedFrames++;
            return;
        }
        if (!el.isJsonObject()) {
            body.malformedFrames++;
            return;
        }
        body.observe(el.getAsJsonObject());
        if (body.errorFrames > 0) {
            return;
        }
        try {
            int before = reader.builder().textLength();
            reader.acceptChunk(cp, el.getAsJsonObject());
            String textDelta = reader.builder().textTail(before);
            if (!textDelta.isEmpty()) {
                timings.onTextDelta(textDelta);
                if (sink != null) sink.onTextDelta(textDelta);
            }
            for (StreamingTurnReader.ReadyCall ready : reader.drainReady()) {
                timings.onToolCallReady(ready.index(), ready.call());
                if (sink != null) sink.onToolCallReady(ready.index(), ready.call());
            }
        } catch (RuntimeException ignored) {
            body.parseErrors++;
        }
    }

    private static Throwable unwrap(Throwable t) {
        Throwable c = t;
        while ((c instanceof CompletionException || c instanceof java.util.concurrent.ExecutionException)
                && c.getCause() != null) {
            c = c.getCause();
        }
        return c;
    }

    /**
     * 仅识别网页以决定一次 /v1 换道；远端错误原文可能包含凭据，不进入异常。
     */
    private static boolean isHtml(List<String> lines) {
        if (lines == null) {
            return false;
        }
        String raw = lines.stream().limit(50).reduce("", (a, b) -> (a + " " + b).trim());
        return raw.startsWith("<");
    }

    private static String[] flatten(java.util.Map<String, String> headers) {
        String[] out = new String[headers.size() * 2];
        int i = 0;
        for (var e : headers.entrySet()) {
            out[i++] = e.getKey();
            out[i++] = e.getValue();
        }
        return out;
    }

    /**
     * 字节流 → SSE 行 的转换器（同时充当 {@code BodySubscriber} 与结果容器）。
     *
     * <p>三条必须自己管的语义（用 {@code ofLines()} 时是 JDK 替我们管的）：
     * <ol>
     *   <li><b>行会跨 chunk 断</b>：一个 TCP 片段可能只到半个 {@code data:} 行，所以
     *       半行必须留在缓冲里等下一片；</li>
     *   <li><b>UTF-8 字符也会跨 chunk 断</b>：一个汉字三字节，切在中间直接
     *       {@code new String(bytes)} 会造出替换字符。所以走 {@link CharsetDecoder}
     *       的增量模式（{@code decode(endOfInput=false)}），残缺序列由解码器自己攒着；</li>
     *   <li><b>CRLF</b>：SSE 规范允许 {@code \r\n}，行尾要把 {@code \r} 去掉。</li>
     * </ol>
     * 非 200 时一行都不处理，只把行攒进 {@link #lines()} 交给错误归一。
     */
    static final class SseBodySubscriber implements HttpResponse.BodySubscriber<SseBodySubscriber> {

        private final ChatProvider provider;
        private final StreamingTurnReader reader;
        private final TurnSink sink;
        private final TurnTimings timings;

        private final CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder();
        private final StringBuilder lineBuf = new StringBuilder();
        private final ByteBuffer carry = ByteBuffer.allocate(8);
        private final List<String> errLines = new ArrayList<>();
        private final CompletableFuture<SseBodySubscriber> bodyDone = new CompletableFuture<>();

        private volatile int statusCode;
        private long requestId;
        private int attempt;
        private ResponseDiagnostics.Format format = ResponseDiagnostics.Format.UNKNOWN;
        private long bytes;
        private int dataLines, jsonFrames, malformedFrames, parseErrors, deltaFrames, messageFrames;
        private int toolFrames, reasoningFrames, refusalFrames, errorFrames;
        private boolean done;
        private ResponseDiagnostics.Finish finish = ResponseDiagnostics.Finish.ABSENT;
        private volatile Throwable failure;
        private ServiceErrorDiagnostics serviceError = ServiceErrorDiagnostics.NONE;
        private static final int ERROR_BODY_LIMIT = 16_384;
        private final StringBuilder diagnosticBody = new StringBuilder();
        private boolean diagnosticBodyTruncated;

        SseBodySubscriber(ChatProvider provider, StreamingTurnReader reader, TurnSink sink,
                          TurnTimings timings) {
            this.provider = provider;
            this.reader = reader;
            this.sink = sink;
            this.timings = timings;
        }

        void statusCode(int code) {
            this.statusCode = code;
        }

        int statusCode() {
            return statusCode;
        }

        void contentType(String contentType) {
            String type = contentType.toLowerCase(java.util.Locale.ROOT);
            format = type.contains("text/event-stream") ? ResponseDiagnostics.Format.SSE
                    : type.contains("json") ? ResponseDiagnostics.Format.JSON
                    : type.contains("html") ? ResponseDiagnostics.Format.HTML
                    : ResponseDiagnostics.Format.OTHER;
        }

        ResponseDiagnostics diagnostics() {
            AssistantTurn turn = reader.builder().build();
            return new ResponseDiagnostics(requestId, attempt, statusCode, format, bytes, dataLines, jsonFrames,
                    malformedFrames, parseErrors, deltaFrames, messageFrames, toolFrames,
                    reasoningFrames, refusalFrames, errorFrames, done, finish,
                    turn.text().isBlank() && turn.toolCalls().isEmpty(), diagnosticBodyTruncated, serviceError);
        }

        private void observe(JsonObject chunk) {
            jsonFrames++;
            if (chunk.has("error") && !chunk.get("error").isJsonNull()) {
                errorFrames++;
                serviceError = serviceError.merge(ServiceErrorDiagnostics.from(chunk.get("error")));
            }
            var choices = chunk.get("choices");
            if (choices == null || !choices.isJsonArray() || choices.getAsJsonArray().isEmpty()) return;
            var first = choices.getAsJsonArray().get(0);
            if (!first.isJsonObject()) return;
            JsonObject choice = first.getAsJsonObject();
            var reason = choice.get("finish_reason");
            if (reason != null && reason.isJsonPrimitive() && reason.getAsJsonPrimitive().isString()) {
                finish = switch (reason.getAsString()) {
                    case "stop" -> ResponseDiagnostics.Finish.STOP;
                    case "tool_calls" -> ResponseDiagnostics.Finish.TOOL_CALLS;
                    case "length" -> ResponseDiagnostics.Finish.LENGTH;
                    case "content_filter" -> ResponseDiagnostics.Finish.CONTENT_FILTER;
                    case "function_call" -> ResponseDiagnostics.Finish.FUNCTION_CALL;
                    default -> ResponseDiagnostics.Finish.OTHER;
                };
            }
            if (choice.has("message") && choice.get("message").isJsonObject()) messageFrames++;
            var delta = choice.get("delta");
            if (delta == null || !delta.isJsonObject()) return;
            deltaFrames++;
            JsonObject object = delta.getAsJsonObject();
            var tools = object.get("tool_calls");
            if (tools != null && tools.isJsonArray() && !tools.getAsJsonArray().isEmpty()) toolFrames++;
            var reasoning = object.get("reasoning_content");
            if (reasoning != null && !reasoning.isJsonNull()) reasoningFrames++;
            var refusal = object.get("refusal");
            if (refusal != null && !refusal.isJsonNull()) refusalFrames++;
        }

        Throwable failure() {
            return failure;
        }

        List<String> lines() {
            return errLines;
        }

        @Override
        public CompletionStage<SseBodySubscriber> getBody() {
            return bodyDone;
        }

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            subscription.request(Long.MAX_VALUE);
        }

        @Override
        public void onNext(List<ByteBuffer> buffers) {
            try {
                for (ByteBuffer in : buffers) {
                    bytes += in.remaining();
                    drain(in);
                }
            } catch (Throwable t) {
                failure = t;
            }
        }

        @Override
        public void onError(Throwable t) {
            failure = t;
            bodyDone.complete(this);
        }

        @Override
        public void onComplete() {
            try {
                drain(ByteBuffer.allocate(0));
                if (lineBuf.length() > 0) {
                    emitLine();
                }
                if (format == ResponseDiagnostics.Format.JSON && dataLines == 0 && !diagnosticBodyTruncated
                        && !diagnosticBody.isEmpty()) {
                    try {
                        var el = JsonParser.parseString(diagnosticBody.toString());
                        if (el.isJsonObject()) observe(el.getAsJsonObject());
                        else malformedFrames++;
                    } catch (RuntimeException ignored) {
                        malformedFrames++;
                    }
                }
                diagnosticBody.setLength(0);
            } catch (Throwable t) {
                if (failure == null) {
                    failure = t;
                }
            }
            bodyDone.complete(this);
        }

        /** 解码目的地：够大就行，超出部分解码器会自己分次吐（不会丢）。 */
        private java.nio.CharBuffer chars = java.nio.CharBuffer.allocate(256);

        /** 解一片字节：残缺字符留 carry，完整字符进 lineBuf，遇 \n 就 emitLine。 */
        private void drain(ByteBuffer in) throws CharacterCodingException {
            if (in.hasRemaining() && in.remaining() + 1 > chars.capacity()) {
                chars = java.nio.CharBuffer.allocate(in.remaining() + 1);
            }
            ByteBuffer src = in;
            if (carry.position() > 0) {
                ByteBuffer joined = ByteBuffer.allocate(carry.position() + in.remaining());
                carry.flip();
                joined.put(carry);
                joined.put(in);
                joined.flip();
                carry.clear();
                src = joined;
            }
            chars.clear();
            CoderResult cr = decoder.decode(src, chars, false);
            if (cr.isError()) {
                cr.throwException();
            }
            chars.flip();
            while (chars.hasRemaining()) {
                char c = chars.get();
                if (c == '\n') {
                    emitLine();
                } else {
                    lineBuf.append(c);
                }
            }
            if (src.hasRemaining()) {
                carry.put(src); // 残缺的多字节序列（UTF-8 最多 3 字节）
            }
        }

        private void emitLine() {
            int end = lineBuf.length();
            if (end > 0 && lineBuf.charAt(end - 1) == '\r') {
                lineBuf.setLength(end - 1);
            }
            String line = lineBuf.toString();
            lineBuf.setLength(0);
            if (statusCode != 200 && errLines.size() < 50) {
                errLines.add(line.substring(0, Math.min(line.length(), 512)));
            }
            if (format == ResponseDiagnostics.Format.JSON
                    && (statusCode != 200 || (!line.trim().startsWith("data:") && dataLines == 0))) {
                if (!diagnosticBodyTruncated
                        && line.length() + diagnosticBody.length() + 1 <= ERROR_BODY_LIMIT) {
                    diagnosticBody.append(line).append('\n');
                } else {
                    diagnosticBodyTruncated = true;
                    diagnosticBody.setLength(0);
                }
                return;
            }
            if (statusCode != 200) {
                return;
            }
            onDataLine(line, provider, reader, sink, timings, this);
        }
    }
}
