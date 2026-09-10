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
     * @param firstTool    发起请求 → 第一个工具就绪（早派发真正发生的时刻）
     * @param afterChunk   首个数据行 → 首段文本（中转站攒批的直接证据）
     * @param afterTool    首个数据行 → 首个工具就绪
     * @param chunks       收到的 SSE 数据行数
     * @param deltas       文本增量段数
     * @param toolsReady   提前就绪（= 被早派发）的工具数
     * @param accumulated  是否仍拼了整轮（false = 走了"只要过程"的路径）
     */
    public record StreamStats(long ttfb, long ttft, long firstTool, long afterChunk, long afterTool,
                              int chunks, int deltas, int toolsReady, boolean accumulated,
                              long cacheWaste) {

        /** 一行可 grep 的观测格式；宿主直接 LOG.info。 */
        public String format() {
            return "ttfb=" + ttfb + "ms ttft=" + ttft + "ms first_tool=" + firstTool
                    + "ms after_chunk=" + afterChunk + "ms after_tool=" + afterTool
                    + "ms chunks=" + chunks + " deltas=" + deltas
                    + " early=" + toolsReady + " accumulated=" + accumulated
                    + " cache_waste=" + cacheWaste;
        }
    }

    /**
     * 一条指令里最多允许几个工具调用"提前派发"。
     *
     * <p><b>为什么是 1 而不是全部</b>：早派发在流式过程中就把 payload 发出去，
     * 这些调用**不走 {@link #awaitAndRecord} 的串行链**。而 mod 侧的执行器背后只有
     * 一具身体 + 单槽调度器（忙即回 BUSY），所以同轮若多个"占身体"的调用一起早派发，
     * 只有第一个能进槽，其余立刻被拒——**一条 BUSY 教学换一次白跑的往返**。
     * 只早派发首个就同时拿到两件事：单调用场景零损失（仍然提前起跑），
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
    /** 本步工具调用的落账账本；非 null = "这一步的工具还没全部写进对话"。 */
    private Ledger ledger;
    /**
     * PARK 标记（R2-S4）：这条指令的工具里有"已受理、还没结果"的长活，链挂起等事件。
     *
     * <p>与非 PARK 的区别只有一个但很关键：PARK 期间**没有下一个步边界**来消费
     * {@link #cancelRequested} 或推进指令队列，所以叫停/换发必须在本地就地解锁并补齐回执
     * （见 {@link #cancelDirective()} / {@link #submit(String)}）。
     */
    private volatile boolean parked;

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
        if (parked) {
            // 新指令 = PARK 解锁。**铁律**：解锁前必须给每个 in-flight job 补一条合成回执，
            // 否则那条 tool_call 永远没有配对的 tool 消息——OpenAI 对"assistant.tool_calls
            // 有 id 却找不到对应 tool 消息"是直接 400，整段历史就废了。
            // 顺带把挂起的那条链作废（它的续跑已经没有意义了）。
            supersedeAll("SUPERSEDED:我收到了新指令，这件事被顶掉了，不会再给你它的结果。"
                    + "要做就重新发一次。");
            ledger = null;
            running = false;
        }
        pending.add(directive);
        if (!running) {
            running = true;
            pump();
        }
    }

    /**
     * 主人叫停：排队中的指令直接丢弃；正在跑的这条在下一个边界停下
     * （若它正等某个工具回执，服务端会把该任务的 future 以 CANCELLED 完成，链即续跑到步首）。
     *
     * <p>PARK 期间没有"下一个边界"可等（链是挂起的），所以这里就地解锁：先补齐 CANCELLED 合成回执，
     * 再推一步让 {@link #consumeCancel()} 收尾并回一句"先停手"。
     */
    public synchronized void cancelDirective() {
        pending.clear();
        cancelRequested = true;
        if (parked) {
            supersedeAll("CANCELLED:主人主动叫停了这件事。别自作主张续上，等主人的下一步指示。");
            step();
        }
    }

    /**
     * 服务端的 job 事件（受理回执的后续）——PARK 的解锁键。
     *
     * <p>线程：宿主保证同一实例上串行调用（mod 侧走客户端主线程 tick / payload 处理）。
     * 幂等：未知 jobId 直接忽略——因为叫停/换发时本地已经补过合成回执，服务端那条迟到的
     * 真结果到了就该被丢掉，而不是把已经写好的对话再改一遍。
     */
    public synchronized void onJobEvent(String jobId, ToolOutcome outcome) {
        if (jobId == null || ledger == null) {
            return;
        }
        int idx = ledger.indexOfJob(jobId);
        if (idx < 0) {
            return;
        }
        ledger.resolve(idx, outcome);
        if (ledger.flush()) {
            ledger = null;
            parked = false;
            listener.onParked(false, 0);
            step();
        }
    }

    /**
     * PARK 解锁的铁律：给账本里每个 in-flight job 写一条**合成回执**，然后立刻按序落账。
     *
     * <p>为什么必须合成而不是"留着以后再说"：协议要求每条 tool_call 都有配对的 tool 消息，
     * 而这条指令的历史**马上**就会被下一条指令的请求带出去。没有合成回执 = 下次请求 400。
     */
    private void supersedeAll(String syntheticText) {
        if (ledger == null) {
            parked = false;
            return;
        }
        for (int idx : ledger.outstanding()) {
            ledger.resolve(idx, ToolOutcome.synthetic(syntheticText));
        }
        ledger.flush();
        parked = false;
        listener.onParked(false, 0);
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
        // 新指令不继承上一条的账本/PARK（换发路径已在 submit 里补齐合成回执）
        ledger = null;
        parked = false;
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
                    // 缓存浪费诊断（评估 §5.2）：读上一轮的 prompt 与本轮的 cached 做增量对比。
                    // 只在这里记账，不参与任何判断——它存在的意义是"前缀退化时有人立刻看得见"。
                    lastCacheWaste = cacheWasteOf(prevPromptTokens, turn.promptTokens(),
                            turn.cachedTokens());
                    prevPromptTokens = turn.promptTokens() > 0 ? turn.promptTokens() : prevPromptTokens;
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
                    runTools(started, turn).thenRun(this::afterTools);
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
        /** 本步已经占掉早派发额度的数量（上限 {@link #MAX_EARLY_DISPATCH}）。 */
        private final java.util.concurrent.atomic.AtomicInteger earlySlots =
                new java.util.concurrent.atomic.AtomicInteger();

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
                    timings.deltas(), timings.toolsReady(), true, lastCacheWaste);
        }

        @Override
        public void onToolCallReady(int index, ToolCall call) {
            if (sealed.get() || !earlyDispatched.add(index)) {
                return;
            }
            // 额度纪律：只让最先就绪的那个（们）提前起跑。理由见 MAX_EARLY_DISPATCH——
            // 同轮多个"占身体"的调用一起发出去，服务端单槽只会收下第一个，其余白拿 BUSY。
            // 超额的调用不在这里发，改由 awaitAndRecord 按 index 依次串行执行。
            if (earlySlots.incrementAndGet() > MAX_EARLY_DISPATCH) {
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
        /** 已按序写入 convo 的前缀长度（不变式：它之前的槽位全部非空）。 */
        private int flushed;

        Ledger(List<ToolCall> calls) {
            this.calls = calls;
            this.outcomes = new ToolOutcome[calls.size()];
            this.jobIds = new String[calls.size()];
        }

        /** 一次真结果（同步回执、job 事件、或本地合成回执）。 */
        void resolve(int idx, ToolOutcome r) {
            if (outcomes[idx] == null) {
                outcomes[idx] = r;
            }
        }

        /** 受理：只登记 jobId，槽位保持"未到达"。 */
        void accepted(int idx, ToolOutcome r) {
            jobIds[idx] = r.jobId();
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
                listener.onToolInvoked(tc.name(), tc.argsJson(), r.ok(), r.feedback());
                noteResult(tc.name(), tc.argsJson(), r);
                flushed++;
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
    private void afterTools() {
        if (ledger == null) {
            step();
            return;
        }
        if (ledger.flush()) {
            ledger = null;
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
            Map<Integer, CompletableFuture<ToolOutcome>> early, AssistantTurn turn) {
        Ledger led = new Ledger(turn.toolCalls());
        ledger = led;
        CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
        for (int i = 0; i < turn.toolCalls().size(); i++) {
            final int idx = i;
            ToolCall tc = turn.toolCalls().get(i);
            CompletableFuture<ToolOutcome> started = early.get(i);
            CompletableFuture<ToolOutcome> result =
                    started != null ? started : executor.execute(tc.name(), tc.argsJson());
            chain = chain.thenCompose(ignored -> result).thenAccept(r -> {
                if (r.accepted()) {
                    led.accepted(idx, r);
                } else {
                    led.resolve(idx, r);
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
    private void finishChain() {
        if (convo.needsCompaction()) {
            convo.compact(engine).whenComplete((v, t) -> pump());
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