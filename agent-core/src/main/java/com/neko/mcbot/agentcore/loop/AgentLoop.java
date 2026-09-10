package com.neko.mcbot.agentcore.loop;

import com.neko.mcbot.agentcore.convo.Conversation;
import com.neko.mcbot.agentcore.llm.AssistantTurn;
import com.neko.mcbot.agentcore.llm.ChatEngine;
import com.neko.mcbot.agentcore.llm.Msg;
import com.neko.mcbot.agentcore.llm.ToolCall;
import com.neko.mcbot.agentcore.llm.ToolSpec;
import com.neko.mcbot.agentcore.llm.TurnSink;
import com.neko.mcbot.agentcore.llm.TurnTimings;
import com.neko.mcbot.agentcore.loop.ToolExecutor.ToolOutcome;
import com.neko.mcbot.agentcore.prompt.PromptBuilder;
import com.neko.mcbot.agentcore.provider.ToolArgsScanner;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * 大脑状态机：指令 → 问模型 → （有工具调用则）执行并喂回执 → 再问 → 直到纯文本回复。
 * 全程异步；同一实例串行处理指令队列。调用方负责把执行链路上的回调
 * 送回到正确的线程（mod 侧是主线程入队，见 M3）。
 */
public final class AgentLoop {

    public interface Listener {
        default void onReply(String text) {
        }

        default void onToolInvoked(String name, String argsJson, boolean ok, String feedback) {
        }

        default void onNotice(String text) {
        }

        /** 每步模型的 token 真数（cached<0 = 后端没报）。观测用，不影响逻辑。 */
        default void onUsage(long prompt, long completion, long cached) {
        }

        /** 合法 prefix reset 事件（reason: compaction / directive-boundary）。
         *  宿主接成 "[brain] prefix reset reason=..." 日志；观测用，不影响逻辑。 */
        default void onPrefixReset(String reason) {
        }

        /** 每步的流式打点（R2-A）。观测用，不影响逻辑。 */
        default void onStreamStats(AgentLoop.StreamStats stats) {
        }
    }

    public record Config(int maxStepsPerDirective, int repeatNudgeAt, int repeatAbortAt) {

        public static Config defaults() {
            return new Config(40, 3, 5);
        }
    }

    /**
     * 一轮流式的打点结果（R2-A）。全部毫秒；未发生 = -1。
     *
     * @param ttfb         发起请求 → 响应头
     * @param ttft         发起请求 → 首段文本增量
     * @param firstTool    发起请求 → 第一个工具就绪（早派发真正发生的时刻）
     * @param afterChunk   首个数据行 → 首段文本（中转站攒批的直接证据）
     * @param afterTool    首个数据行 → 首个工具就绪
     * @param chunks       收到的 SSE 数据行数
     * @param deltas       文本增量段数
     * @param toolsReady   提前就绪（= 被早派发）的工具数
     * @param accumulated  是否仍拼了整轮（false = 走了"只要过程"的路径）
     */
    public record StreamStats(long ttfb, long ttft, long firstTool, long afterChunk, long afterTool,
                              int chunks, int deltas, int toolsReady, boolean accumulated) {

        /** 一行可 grep 的观测格式；宿主直接 LOG.info。 */
        public String format() {
            return "ttfb=" + ttfb + "ms ttft=" + ttft + "ms first_tool=" + firstTool
                    + "ms after_chunk=" + afterChunk + "ms after_tool=" + afterTool
                    + "ms chunks=" + chunks + " deltas=" + deltas
                    + " early=" + toolsReady + " accumulated=" + accumulated;
        }
    }

    private final ChatEngine engine;
    private final List<ToolSpec> tools;
    private final ToolExecutor executor;
    private final Config cfg;
    private final Listener listener;
    private final Supplier<String> systemPrompt;
    private final Conversation convo;
    /** 可空：接入后每条新指令边界喂它的 onDirectiveBoundary（脏标记只在边界兑现，
     *  链中不换 system 前缀）；宿主若自己管缓存则不传。 */
    private final PromptBuilder promptCache;

    private final ArrayDeque<String> pending = new ArrayDeque<>();
    private boolean running;
    /** 主人叫停标记：在当前链的下一个步边界生效；换发新指令时清零。 */
    private volatile boolean cancelRequested;
    private int steps;
    private String lastCallKey;
    private int repeatCount;
    private boolean stuckAbort;
    /** 本步的早派发反应器（R2-A）；一步一个，步首新建、收尾置空。 */
    private StepReactor reactor;
    /** 已被早派发的 index：跨 future 回调串行化，保证一个 index 只派发一次。 */
    private final Set<Integer> earlyDispatched = ConcurrentHashMap.newKeySet();

    public AgentLoop(ChatEngine engine, List<ToolSpec> tools, ToolExecutor executor,
                     Config cfg, Listener listener, Supplier<String> systemPrompt,
                     int conversationSoftTokens) {
        this(engine, tools, executor, cfg, listener, systemPrompt, null, conversationSoftTokens);
    }

    public AgentLoop(ChatEngine engine, List<ToolSpec> tools, ToolExecutor executor,
                     Config cfg, Listener listener, Supplier<String> systemPrompt,
                     PromptBuilder promptCache, int conversationSoftTokens) {
        this.engine = engine;
        this.tools = tools;
        this.executor = executor;
        this.cfg = cfg;
        this.listener = listener;
        this.systemPrompt = systemPrompt;
        this.promptCache = promptCache;
        this.convo = new Conversation(conversationSoftTokens);
        // 纯库不引日志框架：reset 事件经 listener 暴露（测试可断言计数/原因）
        this.convo.setPrefixResetHook(listener::onPrefixReset);
    }

    public Conversation conversation() {
        return convo;
    }

    public synchronized void submit(String directive) {
        pending.add(directive);
        if (!running) {
            running = true;
            pump();
        }
    }

    /**
     * 主人叫停：排队中的指令直接丢弃；正在跑的这条在下一个边界停下
     * （若它正等某个工具回执，服务端会把该任务的 future 以 CANCELLED 完成，链即续跑到步首）。
     */
    public synchronized void cancelDirective() {
        pending.clear();
        cancelRequested = true;
    }

    private void pump() {
        String d = pending.poll();
        if (d == null) {
            running = false;
            cancelRequested = false;
            return;
        }
        cancelRequested = false; // 叫停只对当时那条链有效，不追溯新指令
        convo.onDirectiveBoundary(); // 熔断计数随新指令重置
        convo.onNewDirective();     // R2-B：折叠检查点归零（合法 prefix reset 之二）
        if (promptCache != null) {
            promptCache.onDirectiveBoundary(); // system 换发也只在指令边界
        }
        steps = 0;
        lastCallKey = null;
        repeatCount = 0;
        stuckAbort = false;
        convo.add(new Msg.User(d));
        step();
    }

    /** 消费叫停标记：命中则回一句停手并推进到下一条（队列已被清空即下线）。 */
    private boolean consumeCancel() {
        if (!cancelRequested) {
            return false;
        }
        cancelRequested = false;
        listener.onNotice("cancelled by owner");
        listener.onReply("（收到，我先停手，等你的下一步指示。）");
        pump();
        return true;
    }

    private void step() {
        if (consumeCancel()) {
            return;
        }
        if (stuckAbort) {
            listener.onReply("[内部] 我在同一个操作上反复无进展，先停下了。");
            finishChain();
            return;
        }
        if (++steps > cfg.maxStepsPerDirective()) {
            listener.onReply("[内部] 这个任务步数超限，我停下了。");
            finishChain();
            return;
        }

        // R2-B：步边界推进折叠检查点（必须在 outboundHistory 之前），
        // 本次请求的字节前缀自此对本步之前的历史固定。
        convo.onStepBoundary();
        // R2-A：本步的早派发状态清空（上一步的已在 awaitAndRecord 里消化干净）。
        clearEarly();
        reactor = new StepReactor();
        Runnable finish = () -> {
            reactor = null;
            finishChain(); // R2-B：压缩挪链尾，不在 step 关键路径上等摘要
        };
        engine.chat(systemPrompt.get(), convo.outboundHistory(), tools, reactor, true)
                .thenAccept(turn -> {
                    convo.noteUsage(turn);
                    listener.onUsage(turn.promptTokens(), turn.completionTokens(), turn.cachedTokens());
                    listener.onStreamStats(reactor.stats());
                    reactor.seal(); // 此后到达的工具就绪信号不再派发（它们已过时）
                    Map<Integer, CompletableFuture<ToolOutcome>> started = reactor.takeStarted();
                    if (consumeCancel()) {
                        if (!started.isEmpty()) {
                            // 早派发的副作用已经发生且撤不回：如实告诉主人，别装作什么都没做。
                            listener.onNotice("已派发作废 " + started.size() + " 项（叫停优先）");
                        }
                        clearEarly();
                        finish.run();
                        return;
                    }
                    convo.add(new Msg.Assistant(turn.text(), turn.toolCalls()));
                    if (!turn.hasToolCalls() && started.isEmpty()) {
                        listener.onReply(turn.text());
                        finish.run();
                        return;
                    }
                    // 同一指令里必须继续问模型（回执已进对话）；finishChain 是"这条指令完了"的出口，
                    // 在这里调会把工具回执直接丢掉、链断在工具调用上（R2-A 分支重写时踩过）。
                    awaitAndRecord(started, turn).thenRun(this::step);
                })
                .exceptionally(t -> {
                    listener.onReply("[内部] 模型调用失败：" + msg(t));
                    clearEarly();
                    finish.run();
                    return null;
                });
    }

    private static String msg(Throwable t) {
        return t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
    }

    /**
     * 本步的早派发反应器：既然是"到达即回调"，就必须决定在哪个线程把工具启动起来。
     *
     * <p><b>为什么就地启动而不是排队回主线程</b>：排到主线程相当于把省下来的挂起时间
     * 又还回去了（主线程每拍才跑一次 tick）。{@code executor.execute} 契约上只做
     * "投递并立刻返回 future"（mod 侧就是发一个 payload），不阻塞，所以直接在回调线程
     * 调用它是安全的；真正可能久等的 {@code thenAccept} 仍在原线程异步等。
     *
     * <p><b>顺序纪律</b>：{@code onToolCallReady} 的到达顺序被刻意忽略——最终写
     * {@code Msg.Tool} 时一律按 index 升序（{@link #awaitAndRecord}），因为 OpenAI 协议要求
     * tool 消息与 assistant.tool_calls 严格配对且同序；打转判定也据此串行推进。
     */
    private final class StepReactor implements TurnSink {
        private final TurnTimings timings = new TurnTimings();
        private final Map<Integer, CompletableFuture<ToolOutcome>> started = new ConcurrentHashMap<>();
        private final java.util.concurrent.atomic.AtomicBoolean sealed =
                new java.util.concurrent.atomic.AtomicBoolean();

        void seal() {
            sealed.set(true);
        }

        Map<Integer, CompletableFuture<ToolOutcome>> takeStarted() {
            Map<Integer, CompletableFuture<ToolOutcome>> copy = new java.util.TreeMap<>(started);
            started.clear();
            return copy;
        }

        StreamStats stats() {
            return new StreamStats(timings.ttfbMs(), timings.ttftMs(), timings.firstToolMs(),
                    timings.afterChunkMs(), timings.afterToolMs(), timings.chunks(),
                    timings.deltas(), timings.toolsReady(), true);
        }

        @Override
        public void onToolCallReady(int index, ToolCall call) {
            if (sealed.get() || !earlyDispatched.add(index)) {
                return;
            }
            // 与整轮落地后的路径同构：executor.execute 只投递、不阻塞（mod 侧就是发一个 payload），
            // 所以就地启动是安全的；真正可能久等的等待仍在 awaitAndRecord 里异步进行。
            started.put(index, executor.execute(call.name(), call.argsJson()));
        }
    }

    /** 早派发用：清空本步的早派发登记（下一步开始前调用）。 */
    private void clearEarly() {
        earlyDispatched.clear();
    }

    /**
     * 等齐本步的工具结果并按 index 原序写回对话。
     *
     * <p><b>为什么不是 allOf</b>：allOf 只保证"都完成"，不保证"按序记账"——
     * {@code thenApply} 是挂在各自的 future 上的，谁先完成谁先写。真实 mod 路径上
     * 执行是串行的（一次一个 payload、等回执才发下一个），所以看不出差别；但接口契约上
     * {@link ToolExecutor} 完全可以并行，那时 index 0/1 的回执就会被写反，而
     * OpenAI 协议要求 tool 消息与 assistant.tool_calls 严格同序配对。
     * 所以这里显式串成一条链：第 i 条的记账必须排在第 i-1 条之后。
     *
     * <p>早派发的 future 是**同一个**对象：提前起跑省的是时间，不改变记账顺序，
     * 也不会重复执行（map 命中即不再调 executor）。
     */
    private CompletableFuture<Void> awaitAndRecord(
            Map<Integer, CompletableFuture<ToolOutcome>> early, AssistantTurn turn) {
        CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
        for (int i = 0; i < turn.toolCalls().size(); i++) {
            ToolCall tc = turn.toolCalls().get(i);
            CompletableFuture<ToolOutcome> started = early.get(i);
            CompletableFuture<ToolOutcome> result =
                    started != null ? started : executor.execute(tc.name(), tc.argsJson());
            chain = chain.thenCompose(ignored -> result).thenAccept(r -> {
                convo.add(new Msg.Tool(tc.id(), tc.name(), r.feedback(), r.ok()));
                listener.onToolInvoked(tc.name(), tc.argsJson(), r.ok(), r.feedback());
                noteResult(tc.name(), tc.argsJson(), r);
            });
        }
        return chain;
    }

    /**
     * 打转判定：同调用**且同结果**才累计——TIMEOUT 后原参重试是合法恢复
     * （参考项目用真事故换的教训），结果一变说明世界在动，不是空转。
     */
    private void noteResult(String name, String argsJson, ToolOutcome r) {
        String key = signatureOf(name, argsJson) + '|' + r.ok() + '|' + r.feedback().hashCode();
        repeatCount = key.equals(lastCallKey) ? repeatCount + 1 : 1;
        lastCallKey = key;
        if (repeatCount == cfg.repeatNudgeAt()) {
            convo.add(new Msg.Nudge("[系统] 你已连续 " + repeatCount
                    + " 次执行完全相同的操作且结果相同。换一个做法，或直接向主人说明障碍。"));
            listener.onNotice("nudge@" + repeatCount);
        } else if (repeatCount >= cfg.repeatAbortAt()) {
            stuckAbort = true;
            listener.onNotice("abort@" + repeatCount);
        }
    }

    /** 打转判定用的调用签名：空白归一化，免得 {@code {"a":1}} 与 {@code {"a": 1}} 被当成两次。 */
    private static String signatureOf(String name, String argsJson) {
        return name + '|' + ToolArgsScanner.normalize(argsJson == null ? "" : argsJson);
    }

    /**
     * 链尾（当前指令走完、推进下一条之前）：超水位才压缩。
     * 关键路径收益：任何一步都不再同步等摘要往返（省 2–5s/次）；摘要请求在
     * 本链完成后、下一条指令启动前发出——对空转等待队列的宿主而言就是后台。
     * 压缩成功后 Conversation 内部已调 noteCompacted（真数归零 + prefix reset），
     * 时机仍在"重写历史"的同一同步块里，R0 回归钉不破。
     */
    private void finishChain() {
        if (convo.needsCompaction()) {
            convo.compact(engine).whenComplete((v, t) -> pump());
        } else {
            pump();
        }
    }

    private CompletableFuture<Void> runToolCalls(List<ToolCall> calls) {
        CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
        for (ToolCall tc : calls) {
            chain = chain.thenCompose(v -> executor.execute(tc.name(), tc.argsJson())
                    .thenAccept(r -> {
                        convo.add(new Msg.Tool(tc.id(), tc.name(), r.feedback(), r.ok()));
                        listener.onToolInvoked(tc.name(), tc.argsJson(), r.ok(), r.feedback());
                        noteResult(tc.name(), tc.argsJson(), r);
                    }));
        }
        return chain;
    }
}
