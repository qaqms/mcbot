package com.neko.mcbot.agentcore.loop;

import com.neko.mcbot.agentcore.convo.Conversation;
import com.neko.mcbot.agentcore.llm.AssistantTurn;
import com.neko.mcbot.agentcore.llm.ChatEngine;
import com.neko.mcbot.agentcore.llm.LlmFailure;
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
import java.util.function.Supplier;

/**
 * 大脑状态机：指令 → 问模型 → （有工具调用则）执行并喂回执 → 再问 → 直到纯文本回复。
 * 全程异步；同一实例串行处理指令队列。调用方负责把执行链路上的回调
 * 送回到正确的线程（mod 侧是主线程入队，见 M3）。
 */
public final class AgentLoop {

    public enum TaskStatus { COMPLETED, FAILED, CANCELLED, SUPERSEDED }

    public interface Listener {
        default void onTaskStarted(long taskId) {
        }

        default void onTaskFinished(long taskId, TaskStatus status, String text) {
        }

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

        /**
         * 服务端**受理**了一次跨 tick 长活（R2-S4 受理即回执）。
         *
         * <p>与 {@link #onToolInvoked} 的区别：那条是"结果到了"，这条只是"我开始了、还没有结果"。
         * 宿主用它播报进度（桥的 progress 帧 / 面板一行），**不要**把它当成工具结果去记账。
         */
        default void onToolAccepted(String name, String argsJson, String feedback, String jobId) {
        }

        /**
         * PARK 状态变化（R2-S4）：true = 这条指令挂起在等长活的结果，链上暂时没有下一步。
         * 观测用；宿主可据此在面板上显示"在等 j7"。
         */
        default void onParked(boolean parked, int outstandingJobs) {
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
     * @param firstTool    发起请求 → 第一个工具就绪，不包含客户端队列延迟
     * @param afterChunk   首个数据行 → 首段文本（中转站攒批的直接证据）
     * @param afterTool    首个数据行 → 首个工具就绪
     * @param chunks       收到的非终止 SSE 数据行数（不含 [DONE]）
     * @param deltas       文本增量段数
     * @param toolsReady   传输层工具就绪数，不等于实际派发数
     * @param accumulated  是否仍拼了整轮（false = 走了"只要过程"的路径）
     * @param earlyDispatched 整轮处理前经就绪回调实际调用执行器的次数，不代表服务端受理
     * @param firstDispatch 发起请求 → 首次实际调用执行器，包含客户端队列延迟
     * @param streamDuration 发起请求 → 传输结束；首次派发可能晚于它
     */
    public record StreamStats(long ttfb, long ttft, long firstTool, long afterChunk, long afterTool,
                              int chunks, int deltas, int toolsReady, boolean accumulated,
                              long cacheWaste, int earlyDispatched, long firstDispatch,
                              long streamDuration) {

        /** 一行可 grep 的观测格式；宿主直接 LOG.info。 */
        public String format() {
            return "ttfb=" + ttfb + "ms ttft=" + ttft + "ms first_tool=" + firstTool
                    + "ms after_chunk=" + afterChunk + "ms after_tool=" + afterTool
                    + "ms chunks=" + chunks + " deltas=" + deltas
                    + " ready=" + toolsReady + " early=" + earlyDispatched
                    + " first_dispatch=" + firstDispatch + "ms stream=" + streamDuration
                    + "ms accumulated=" + accumulated
                    + " cache_waste=" + cacheWaste;
        }
    }

    /**
     * 一条指令里最多允许几个工具调用"提前派发"。
     *
     * <p><b>为什么是 1 而不是全部</b>：早派发在流式过程中就把 payload 发出去，
     * 这些调用**不走 {@link #runTools} 的串行链**。而 mod 侧的执行器背后只有
     * 一具身体 + 单槽调度器（忙即回 BUSY），所以同轮若多个"占身体"的调用一起早派发，
     * 只有第一个能进槽，其余立刻被拒——**一条 BUSY 教学换一次白跑的往返**。
     * 只早派发 index 0 就同时拿到两件事：单调用场景零损失（仍然提前起跑），
     * 多调用场景退回 R2-A 之前的串行语义（第二个起，等轮落地后按 index 依次发）。
     *
     * <p>纯只读工具（status/scan_area）其实可以并行，但 agent-core 不认识"哪些工具占身体"，
     * 那是宿主的知识；在核里设 1 是不依赖宿主、且不会错的安全默认。
     */
    private static final int MAX_EARLY_DISPATCH = 1;

    // ---- 缓存浪费诊断（评估 §5.2）：让"前缀缓存静默退化"变成可观测 ----

    /** 上一轮 API 回报的 prompt token（0 = 没报过）。 */
    private long prevPromptTokens;
    /** 最近一次算出的缓存浪费（token），随 StreamStats 出到日志。 */
    private long lastCacheWaste = -1;
    /** 分包粒度噪声底：缓存按段命中，段边界处必然差一点；扣掉它免得长期误报。 */
    private static final long CACHE_WASTE_NOISE_FLOOR = 1024;

    /**
     * 本轮"本该命中却重新处理"的 token 数。
     *
     * <pre>期望缓存读取 ≈ min(上一轮 prompt, 本轮 prompt)</pre>
     *
     * 扣掉真实命中数再减噪声底，负数归零。**它是诊断不是统计**：正常长期贴近 0，
     * **一旦持续抬头**就说明前缀被谁动了——系统提示里混进会变的字段、工具表增删、
     * 历史被从中间剪了、或两轮间隔太久缓存过期。比看 {@code cached/prompt} 比值灵敏，
     * 因为它盯的是**增量**（比值本来就会随历史增长而变化）。
     *
     * <p>首轮不计（那时缓存本就该是空的）；后端没报 cached（<0）时返回 -1。纯读数，不影响逻辑。
     */
    static long cacheWasteOf(long prevPrompt, long prompt, long cached) {
        if (prevPrompt <= 0 || prompt <= 0 || cached < 0) {
            return -1;
        }
        long expected = Math.min(prevPrompt, prompt);
        return Math.max(0, expected - cached - CACHE_WASTE_NOISE_FLOOR);
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

    private record Directive(long id, String text) {
    }

    private final ArrayDeque<Directive> pending = new ArrayDeque<>();
    private Directive activeDirective;
    private long generation;
    private boolean closed;
    private StepReactor activeReactor;
    private boolean running;
    private int steps;
    private String lastCallKey;
    private int repeatCount;
    private boolean stuckAbort;
    /** 本步工具调用的落账账本；非 null = "这一步的工具还没全部写进对话"。 */
    private Ledger ledger;
    /**
     * PARK 标记（R2-S4）：这条指令的工具里有"已受理、还没结果"的长活，链挂起等事件。
     *
     * <p>与非 PARK 的区别只有一个但很关键：PARK 期间**没有下一个步边界**来消费
     * 取消或推进指令队列，所以叫停/换发必须在本地就地解锁并补齐回执
     * （见 {@link #cancelDirective()} / {@link #submit(String)}）。
     */
    private volatile boolean parked;

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
        submit(0, directive);
    }

    public synchronized void submit(long taskId, String directive) {
        if (closed) {
            throw new IllegalStateException("agent loop is closed");
        }
        if (directive == null || directive.isBlank()) {
            throw new IllegalArgumentException("directive must not be blank");
        }
        if (parked) {
            discardQueued(TaskStatus.SUPERSEDED, "SUPERSEDED:新指令替换了尚未执行的任务。");
            stopActive(TaskStatus.SUPERSEDED,
                    "SUPERSEDED:我收到了新指令，这件事被顶掉了。要做就重新发一次。", false);
        }
        pending.add(new Directive(taskId, directive));
        if (!running) {
            running = true;
            pump();
        }
    }

    /** 本地立即终止并补齐工具配对；宿主负责同时叫停服务端身体。 */
    public synchronized void cancelDirective() {
        discardQueued(TaskStatus.CANCELLED, "CANCELLED:主人取消了尚未执行的任务。");
        if (activeDirective != null) {
            stopActive(TaskStatus.CANCELLED,
                    "CANCELLED:主人主动叫停了这件事。别自作主张续上，等主人的下一步指示。", true);
            pump();
        }
    }

    public synchronized boolean cancelTask(long taskId) {
        if (taskId == 0) {
            boolean hadTask = activeDirective != null || !pending.isEmpty();
            cancelDirective();
            return hadTask;
        }
        if (activeDirective != null && activeDirective.id() == taskId) {
            stopActive(TaskStatus.CANCELLED, "CANCELLED:主人叫停了这件事。", true);
            pump();
            return true;
        }
        var it = pending.iterator();
        while (it.hasNext()) {
            Directive d = it.next();
            if (d.id() == taskId) {
                it.remove();
                listener.onTaskFinished(d.id(), TaskStatus.CANCELLED,
                        "CANCELLED:主人取消了尚未执行的任务。");
                return true;
            }
        }
        return false;
    }

    public synchronized long currentTaskId() {
        return activeDirective == null ? 0 : activeDirective.id();
    }

    public synchronized int queuedTasks() {
        return pending.size();
    }

    public synchronized boolean isParked() {
        return parked;
    }

    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        discardQueued(TaskStatus.CANCELLED, "CANCELLED:大脑会话已关闭。");
        stopActive(TaskStatus.CANCELLED, "CANCELLED:大脑会话已关闭。", false);
        running = false;
    }

    /**
     * 服务端的 job 事件（受理回执的后续）——PARK 的解锁键。
     *
     * <p>线程：宿主保证同一实例上串行调用（mod 侧走客户端主线程 tick / payload 处理）。
     * 幂等：未知 jobId 直接忽略——因为叫停/换发时本地已经补过合成回执，服务端那条迟到的
     * 真结果到了就该被丢掉，而不是把已经写好的对话再改一遍。
     */
    public synchronized void onJobEvent(String jobId, ToolOutcome outcome) {
        if (jobId == null || closed) {
            return;
        }
        int idx = ledger == null ? -1 : ledger.indexOfJob(jobId);
        if (idx < 0) {
            if (activeReactor != null) {
                activeReactor.rememberJobEvent(jobId, outcome);
            }
            return;
        }
        Ledger led = ledger;
        if (parked) {
            parked = false;
            listener.onParked(false, 0);
        }
        led.resolve(idx, outcome);
        // Resolving the terminal gate may synchronously dispatch the remaining calls or next turn.
        if (ledger == led) {
            led.flush();
        }
    }

    /**
     * PARK 解锁的铁律：给账本里每个 in-flight job 写一条**合成回执**，然后立刻按序落账。
     *
     * <p>为什么必须合成而不是"留着以后再说"：协议要求每条 tool_call 都有配对的 tool 消息，
     * 而这条指令的历史**马上**就会被下一条指令的请求带出去。没有合成回执 = 下次请求 400。
     */
    private void stopActive(TaskStatus status, String text, boolean reply) {
        generation++;
        if (activeReactor != null) {
            activeReactor.seal();
            activeReactor = null;
        }
        if (ledger != null) {
            for (int i = 0; i < ledger.calls.size(); i++) {
                ledger.resolve(i, ToolOutcome.synthetic(text));
            }
            ledger.flush();
            ledger = null;
        }
        if (parked) {
            listener.onParked(false, 0);
        }
        parked = false;
        Directive stopped = activeDirective;
        if (stopped != null) {
            if (reply) {
                listener.onNotice("cancelled by owner");
                listener.onReply("（收到，我先停手，等你的下一步指示。）");
            }
            listener.onTaskFinished(stopped.id(), status, text);
        }
        activeDirective = null;
        running = false;
    }

    private void discardQueued(TaskStatus status, String text) {
        while (!pending.isEmpty()) {
            Directive d = pending.poll();
            listener.onTaskFinished(d.id(), status, text);
        }
    }

    private boolean isCurrent(long expectedGeneration) {
        return !closed && activeDirective != null && generation == expectedGeneration;
    }

    private synchronized void pump() {
        if (closed) {
            return;
        }
        Directive d = pending.poll();
        if (d == null) {
            running = false;
            return;
        }
        activeDirective = d;
        generation++;
        running = true;
        convo.onDirectiveBoundary(); // 熔断计数随新指令重置
        convo.onNewDirective();     // R2-B：折叠检查点归零（合法 prefix reset 之二）
        if (promptCache != null) {
            promptCache.onDirectiveBoundary(); // system 换发也只在指令边界
        }
        steps = 0;
        lastCallKey = null;
        repeatCount = 0;
        stuckAbort = false;
        // 新指令不继承上一条的账本/PARK（换发路径已在 submit 里补齐合成回执）
        ledger = null;
        parked = false;
        listener.onTaskStarted(d.id());
        convo.add(new Msg.User(d.text()));
        step();
    }

    private synchronized void step() {
        if (closed || activeDirective == null) {
            return;
        }
        if (stuckAbort) {
            finishCurrent(TaskStatus.FAILED, "[内部] 我在同一个操作上反复无进展，先停下了。");
            return;
        }
        if (++steps > cfg.maxStepsPerDirective()) {
            finishCurrent(TaskStatus.FAILED, "[内部] 这个任务步数超限，我停下了。");
            return;
        }

        // R2-B：步边界推进折叠检查点（必须在 outboundHistory 之前），
        // 本次请求的字节前缀自此对本步之前的历史固定。
        convo.onStepBoundary();
        // 每步独立持有反应器，旧流的回调不能改写下一步的派发登记。
        long stepGeneration = generation;
        StepReactor reactor = new StepReactor(stepGeneration);
        activeReactor = reactor;
        CompletableFuture<ToolOutcome> observation;
        try {
            observation = java.util.Objects.requireNonNull(executor.observe(activeDirective.id()));
        } catch (RuntimeException failure) {
            observation = CompletableFuture.failedFuture(failure);
        }
        observation.whenComplete((state, failure) -> {
            synchronized (AgentLoop.this) {
                if (!isCurrent(stepGeneration) || activeReactor != reactor) return;
                if (failure != null || state == null || !state.ok() || state.accepted()
                        || state.feedback() == null || state.feedback().getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 16384) {
                    finishCurrent(TaskStatus.FAILED, "STATE_UNAVAILABLE:没有取得有效的最新身体状态，任务已停止；不能猜测完成。");
                    return;
                }
                var history = new ArrayList<>(convo.outboundHistory());
                if (!state.feedback().isBlank()) history.add(new Msg.Nudge(
                        "[本轮服务端身体观测 task_id=" + activeDirective.id() + " step=" + steps
                                + "，仅对本次请求有效]\n" + state.feedback()));
                requestTurn(stepGeneration, reactor, List.copyOf(history));
            }
        });
    }

    private void requestTurn(long stepGeneration, StepReactor reactor, List<Msg> history) {
        CompletableFuture<AssistantTurn> response;
        try {
            response = java.util.Objects.requireNonNull(
                    engine.chat(systemPrompt.get(), history, tools, reactor, true));
        } catch (RuntimeException t) {
            response = CompletableFuture.failedFuture(t);
        }
        response.thenAccept(turn -> handleTurn(stepGeneration, reactor, turn))
                .exceptionally(t -> {
                    synchronized (AgentLoop.this) {
                        if (!isCurrent(stepGeneration) || activeReactor != reactor) {
                            return null;
                        }
                        reactor.reportStats(-1);
                        reactor.seal();
                        finishCurrent(TaskStatus.FAILED, LlmFailure.userMessage(t));
                    }
                    return null;
                });
    }

    private synchronized void handleTurn(long stepGeneration, StepReactor reactor, AssistantTurn turn) {
        if (!isCurrent(stepGeneration) || activeReactor != reactor) {
            return;
        }
        convo.noteUsage(turn);
        listener.onUsage(turn.promptTokens(), turn.completionTokens(), turn.cachedTokens());
        lastCacheWaste = cacheWasteOf(prevPromptTokens, turn.promptTokens(), turn.cachedTokens());
        prevPromptTokens = turn.promptTokens() > 0 ? turn.promptTokens() : prevPromptTokens;
        reactor.reportStats(lastCacheWaste);
        reactor.seal();
        convo.add(new Msg.Assistant(turn.text(), turn.toolCalls()));
        if (!turn.hasToolCalls()) {
            finishCurrent(TaskStatus.COMPLETED, turn.text());
            return;
        }
        Ledger led = new Ledger(turn.toolCalls(), reactor);
        ledger = led;
        runTools(reactor.startedSnapshot(), turn, led, stepGeneration)
                .thenRun(() -> afterTools(led, stepGeneration));
    }

    private void finishCurrent(TaskStatus status, String text) {
        Directive finished = activeDirective;
        listener.onReply(text);
        listener.onTaskFinished(finished.id(), status, text);
        activeDirective = null;
        activeReactor = null;
        finishChain();
    }

    /**
     * 本步的早派发反应器：流式中只提前启动 index 0，其余调用按最终轮次原序串行。
     * 宿主可用 CallbackChatEngine 把回调送到主线程，纯库不依赖特定线程调度器。
     *
     * <p><b>顺序纪律</b>：{@code onToolCallReady} 的到达顺序被刻意忽略——最终写
     * {@code Msg.Tool} 时一律按 index 升序（{@link #runTools}），因为 OpenAI 协议要求
     * tool 消息与 assistant.tool_calls 严格配对且同序；打转判定也据此串行推进。
     */
    private final class StepReactor implements TurnSink {
        private final long stepGeneration;
        private TurnTimings.Snapshot timings;
        private Long firstDispatchNanos;
        private final Set<Integer> earlyDispatched = ConcurrentHashMap.newKeySet();
        private final Map<Integer, CompletableFuture<ToolOutcome>> started = new ConcurrentHashMap<>();
        private final java.util.concurrent.atomic.AtomicBoolean sealed =
                new java.util.concurrent.atomic.AtomicBoolean();
        /** 本步已经占掉早派发额度的数量（上限 {@link #MAX_EARLY_DISPATCH}）。 */
        private final java.util.concurrent.atomic.AtomicInteger earlySlots =
                new java.util.concurrent.atomic.AtomicInteger();
        private final Map<String, ToolOutcome> jobEvents = new java.util.HashMap<>();

        StepReactor(long stepGeneration) {
            this.stepGeneration = stepGeneration;
        }

        void seal() {
            sealed.set(true);
        }

        Map<Integer, CompletableFuture<ToolOutcome>> startedSnapshot() {
            return new java.util.TreeMap<>(started);
        }

        void rememberJobEvent(String jobId, ToolOutcome outcome) {
            for (var future : started.values()) {
                if (!future.isDone() || future.isCompletedExceptionally() || future.isCancelled()) continue;
                ToolOutcome r = future.getNow(null);
                if (r != null && r.accepted() && jobId.equals(r.jobId())) {
                    jobEvents.putIfAbsent(jobId, outcome);
                    return;
                }
            }
        }

        void reportStats(long cacheWaste) {
            var stats = timings == null
                    ? new StreamStats(-1, -1, -1, -1, -1, 0, 0, 0, true,
                            cacheWaste, started.size(), -1, -1)
                    : new StreamStats(timings.ttfb(), timings.ttft(), timings.firstTool(),
                            timings.afterChunk(), timings.afterTool(), timings.chunks(),
                            timings.deltas(), timings.toolsReady(), true, cacheWaste, started.size(),
                            firstDispatchNanos == null ? -1 : timings.sinceRequestMs(firstDispatchNanos),
                            timings.elapsedMs());
            try {
                listener.onStreamStats(stats);
            } catch (RuntimeException ignored) {
                // A diagnostic listener cannot fail or replay a task.
            }
        }

        @Override
        public void onTimings(TurnTimings.Snapshot snapshot) {
            synchronized (AgentLoop.this) {
                if (!sealed.get() && isCurrent(stepGeneration) && activeReactor == this) {
                    timings = snapshot;
                }
            }
        }

        @Override
        public void onToolCallReady(int index, ToolCall call) {
            synchronized (AgentLoop.this) {
                if (index != 0 || sealed.get() || !isCurrent(stepGeneration)
                        || activeReactor != this || !earlyDispatched.add(index)) {
                    return;
                }
                if (earlySlots.incrementAndGet() > MAX_EARLY_DISPATCH) {
                    return;
                }
                firstDispatchNanos = System.nanoTime();
                started.put(index, executeTool(call));
            }
        }
    }

    private CompletableFuture<ToolOutcome> executeTool(ToolCall call) {
        try {
            return java.util.Objects.requireNonNull(executor.execute(call.name(), call.argsJson()))
                    .handle((outcome, failure) -> failure == null && outcome != null
                            ? outcome : toolFailure());
        } catch (RuntimeException failure) {
            return CompletableFuture.completedFuture(toolFailure());
        }
    }

    private static ToolOutcome toolFailure() {
        // 不回显执行器异常原文：异常可能夹带端点凭据或请求内容。
        return new ToolOutcome(false, "INTERNAL:工具执行失败，没有收到有效结果。向主人说明情况，别猜测已完成。");
    }

    /**
     * 一步里每条 tool_call 的落账状态。
     *
     * <p><b>为什么需要账本而不是"来了就写"</b>：受理即回执引入了**乱序到达**——第 0 条被受理
     * （结果要等几十秒），第 1 条（同步工具/BUSY）当场就有结果。而 OpenAI 协议要求
     * {@code tool} 消息与 {@code assistant.tool_calls} **严格同序配对**，所以第 1 条不能先写。
     * 账本只做一件事：**按 index 升序写出"已到达"的最长前缀**，其余留在槽里等。
     *
     * <p>"已受理"**不算已到达**：受理是"还没有结果"，写进对话就等于告诉模型事情做完了——
     * 那正是这一卡要消灭的谎。所以 {@code accepted} 只登记 jobId，槽位仍然空着。
     */
    private final class Ledger {
        private final List<ToolCall> calls;
        private final ToolOutcome[] outcomes;
        private final String[] jobIds;
        private final List<CompletableFuture<Void>> terminalGates;
        private final StepReactor reactor;
        /** 已按序写入 convo 的前缀长度（不变式：它之前的槽位全部非空）。 */
        private int flushed;

        Ledger(List<ToolCall> calls, StepReactor reactor) {
            this.calls = calls;
            this.reactor = reactor;
            this.outcomes = new ToolOutcome[calls.size()];
            this.jobIds = new String[calls.size()];
            this.terminalGates = new ArrayList<>();
            for (int i = 0; i < calls.size(); i++) terminalGates.add(new CompletableFuture<>());
        }

        /** 一次真结果（同步回执、job 事件、或本地合成回执）。 */
        void resolve(int idx, ToolOutcome r) {
            if (outcomes[idx] == null) {
                outcomes[idx] = r;
                terminalGates.get(idx).complete(null);
            }
        }

        /** 受理：只登记 jobId，槽位保持"未到达"。 */
        void accepted(int idx, ToolOutcome r) {
            jobIds[idx] = r.jobId();
            ToolOutcome earlyEvent = reactor.jobEvents.remove(r.jobId());
            if (earlyEvent != null) {
                resolve(idx, earlyEvent);
            }
            ToolCall tc = calls.get(idx);
            listener.onToolAccepted(tc.name(), tc.argsJson(), r.feedback(), r.jobId());
        }

        /** 还在跑（已受理、无结果）的槽位。 */
        List<Integer> outstanding() {
            List<Integer> out = new ArrayList<>();
            for (int i = 0; i < outcomes.length; i++) {
                if (outcomes[i] == null && jobIds[i] != null) {
                    out.add(i);
                }
            }
            return out;
        }

        int indexOfJob(String jobId) {
            for (int i = 0; i < jobIds.length; i++) {
                if (outcomes[i] == null && jobId.equals(jobIds[i])) {
                    return i;
                }
            }
            return -1;
        }

        /** 按 index 升序写出已到达的最长前缀；返回 true = 这一步全部落账完毕。 */
        boolean flush() {
            while (flushed < outcomes.length && outcomes[flushed] != null) {
                ToolCall tc = calls.get(flushed);
                ToolOutcome r = outcomes[flushed];
                convo.add(new Msg.Tool(tc.id(), tc.name(), r.feedback(), r.ok()));
                flushed++;
                listener.onToolInvoked(tc.name(), tc.argsJson(), r.ok(), r.feedback());
                noteResult(tc.name(), tc.argsJson(), r);
            }
            return flushed == outcomes.length;
        }
    }

    /**
     * 工具链跑完之后：要么进下一步，要么 PARK。
     *
     * <p>PARK 的判据是"账本里还有已受理未到达的槽位"。注意**不进 {@code step()}**——
     * 这正是"受理即回执"的收益所在：模型不用干等那几十秒，这条指令就此挂起，
     * 等到 {@code job_event} 再来续。PARK 期间不计步（{@code steps} 只在 {@code step()} 里涨），
     * 所以长活不会把 40 步帽吃掉。
     */
    private synchronized void afterTools(Ledger led, long stepGeneration) {
        if (!isCurrent(stepGeneration) || ledger != led) {
            return;
        }
        if (ledger.flush()) {
            ledger = null;
            if (parked) listener.onParked(false, 0);
            parked = false;
            step();
            return;
        }
        parked = true;
        int n = ledger.outstanding().size();
        listener.onParked(true, n);
    }

    /**
     * 等齐本步的工具结果并把它们**按 index 原序**交给账本。
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
     *
     * <p>R2-S4 起这条链**只负责"把结果填进账本"**，写对话与 park 判定都归
     * {@link Ledger#flush()} / {@link #afterTools()}。
     */
    private CompletableFuture<Void> runTools(
            Map<Integer, CompletableFuture<ToolOutcome>> early, AssistantTurn turn,
            Ledger led, long stepGeneration) {
        CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
        for (int i = 0; i < turn.toolCalls().size(); i++) {
            final int idx = i;
            ToolCall tc = turn.toolCalls().get(i);
            CompletableFuture<ToolOutcome> started = early.get(i);
            chain = chain.thenCompose(ignored -> {
                synchronized (AgentLoop.this) {
                    if (!isCurrent(stepGeneration) || ledger != led) {
                        return CompletableFuture.completedFuture(ToolOutcome.synthetic(
                                "CANCELLED:这个操作所属的任务已终止，没有继续执行。"));
                    }
                    if (started != null) {
                        return started;
                    }
                    return executeTool(tc);
                }
            }).thenCompose(r -> {
                synchronized (AgentLoop.this) {
                    if (!isCurrent(stepGeneration) || ledger != led) {
                        return CompletableFuture.completedFuture(null);
                    }
                    if (r.accepted()) {
                        led.accepted(idx, r);
                        if (led.outcomes[idx] == null) {
                            parked = true;
                            listener.onParked(true, led.outstanding().size());
                        }
                    } else {
                        led.resolve(idx, r);
                    }
                    return led.terminalGates.get(idx);
                }
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
    private synchronized void finishChain() {
        if (convo.needsCompaction()) {
            long expectedGeneration = generation;
            try {
                convo.compact(engine).whenComplete((v, t) -> {
                    synchronized (AgentLoop.this) {
                        if (!closed && generation == expectedGeneration) {
                            pump();
                        }
                    }
                });
            } catch (RuntimeException failure) {
                listener.onNotice("历史压缩失败，保留原文继续。");
                pump();
            }
        } else {
            pump();
        }
    }

    /**
     * 压缩等处置见 {@link #finishChain()}。
     *
     * <p>（原 {@code runToolCalls} 是 M3 期的第二条工具执行路径，早在 R2-A 就被
     * {@code runTools} 取代、没人调用了。R2-S4 起**删掉**而不是留着：它不认识账本与 PARK，
     * 留着就是一条"能用但会绕过受理语义"的暗路，将来谁接上去就是一个难查的错。）
     */
}
