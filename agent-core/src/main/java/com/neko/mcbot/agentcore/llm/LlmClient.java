package com.neko.mcbot.agentcore.llm;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
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
 * 不是 SSE，必须原样收下交给 {@link #summarizeError}；200 时那套完全用不上。
 * {@code BodyHandler.apply(ResponseInfo)} 拿得到 {@code statusCode()}，所以一次请求内就能
 * 选好收法；非 200 分支因此退化回"把行拼起来"的旧行为，对外语义零变化。
 */
public final class LlmClient implements ChatEngine {

    private final ChatProvider provider;
    private final HttpClient http;
    private final Duration timeout;
    private final java.util.function.LongSupplier clock;

    public LlmClient(ChatProvider provider, Duration timeout) {
        this(provider, timeout, Duration.ofSeconds(15), System::nanoTime);
    }

    public LlmClient(ChatProvider provider, Duration timeout, Duration connectTimeout,
                     java.util.function.LongSupplier nanoClock) {
        this.provider = provider;
        this.timeout = timeout;
        this.clock = nanoClock;
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
                new TurnTimings(clock));
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

        HttpRequest request = HttpRequest.newBuilder(URI.create(cp.endpoint()))
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .headers(flatten(cp.authHeaders()))
                .POST(HttpRequest.BodyPublishers.ofString(requestBody.toString(), StandardCharsets.UTF_8))
                .build();

        return http.sendAsync(request, info -> {
                    body.statusCode(info.statusCode());
                    return body;
                })
                .handle((resp, err) -> {
                    Throwable failure = unwrap(err);
                    if (failure != null) {
                        throw new CompletionException(failure);
                    }
                    timings.markResponse();
                    int code = body.statusCode();
                    if (code != 200) {
                        String summary = summarizeError(body.lines());
                        if (mayRetryV1 && summary.startsWith("web page:")
                                && !cp.endpoint().contains("/v1/")) {
                            String alt = cp.endpoint().replaceFirst("/chat/completions$",
                                    "/v1/chat/completions");
                            if (!alt.equals(cp.endpoint())) {
                                return chatOn(cp.withBaseUrl(alt), false, systemPrompt, convo, tools,
                                        sink, accumulate, new TurnTimings(clock));
                            }
                        }
                        throw new CompletionException(new IOException(
                                cp.name() + " HTTP " + code + ": " + summary));
                    }
                    if (body.failure() != null) {
                        throw new CompletionException(body.failure());
                    }
                    AssistantTurn turn = accumulate ? reader.builder().build() : null;
                    if (sink != null) {
                        sink.onCounters(timings.chunks(), timings.deltas(), timings.toolsReady());
                        sink.onComplete(turn, null);
                    }
                    return CompletableFuture.completedFuture(turn);
                })
                .thenCompose(f -> f)
                .whenComplete((turn, err) -> {
                    Throwable failure = unwrap(err);
                    if (failure != null && sink != null) {
                        sink.onComplete(null, failure);
                    }
                });
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
                                   TurnSink sink, TurnTimings timings) {
        String s = line.trim();
        if (!s.startsWith("data:")) {
            return; // 注释行/事件名/心跳：不是数据，也不算 chunk
        }
        String payload = s.substring(5).trim();
        if (cp.isTerminalData(payload)) {
            return;
        }
        timings.markChunk();
        try {
            com.google.gson.JsonElement el = JsonParser.parseString(payload);
            if (!el.isJsonObject()) {
                return;
            }
            int before = reader.builder().textLength();
            reader.acceptChunk(cp, el.getAsJsonObject());
            if (sink == null) {
                return;
            }
            String textDelta = reader.builder().textTail(before);
            if (!textDelta.isEmpty()) {
                sink.onTextDelta(textDelta);
            }
            for (StreamingTurnReader.ReadyCall ready : reader.drainReady()) {
                sink.onToolCallReady(ready.index(), ready.call());
            }
        } catch (RuntimeException ignored) {
            // 个别端点会混入非 JSON 行，跳过比中断安全
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
     * 错误体归一：OpenAI 族正常回 JSON（取 error 对象）；站点首页/CF 墙是 HTML——
     * 整坨塞进聊天和模型上下文毫无意义，折成一句人话。
     */
    private static String summarizeError(List<String> lines) {
        if (lines == null) {
            return "(empty body)";
        }
        String raw = lines.stream().limit(50).reduce("", (a, b) -> (a + " " + b).trim());
        if (raw.isEmpty()) {
            return "(empty body)";
        }
        if (raw.startsWith("<")) {
            return "web page: endpoint returned HTML (wrong path or bot-wall), not a JSON API";
        }
        try {
            var j = JsonParser.parseString(raw);
            if (j.isJsonObject() && j.getAsJsonObject().has("error")) {
                raw = j.getAsJsonObject().get("error").toString();
            }
        } catch (RuntimeException notJson) {
            // 保持原文截断
        }
        return raw.length() > 300 ? raw.substring(0, 300) + "…" : raw;
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
        private volatile Throwable failure;

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
            if (statusCode != 200) {
                errLines.add(line);
                return;
            }
            onDataLine(line, provider, reader, sink, timings);
        }
    }
}

