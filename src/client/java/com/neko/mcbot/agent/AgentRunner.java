package com.neko.mcbot.agent;

import com.google.gson.JsonObject;
import com.neko.mcbot.McbotClient;
import com.neko.mcbot.agentcore.llm.LlmClient;
import com.neko.mcbot.agentcore.llm.CallbackChatEngine;
import com.neko.mcbot.agentcore.llm.ChatEngine;
import com.neko.mcbot.agentcore.loop.AgentLoop;
import com.neko.mcbot.agentcore.loop.PendingJobs;
import com.neko.mcbot.agentcore.loop.TaskReplies;
import com.neko.mcbot.agentcore.loop.ToolExecutor;
import com.neko.mcbot.agentcore.loop.TaskPolicy;
import com.neko.mcbot.agentcore.prompt.PromptBuilder;
import com.neko.mcbot.agentcore.prompt.SkillLoader;
import com.neko.mcbot.bridge.BridgeEvents;
import com.neko.mcbot.cfg.ClientConfig;
import com.neko.mcbot.common.Envelope;
import com.neko.mcbot.common.McbotPayloads;
import com.neko.mcbot.common.WireSize;
import com.neko.mcbot.common.ScanFormat;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 大脑宿主：AgentLoop + "工具调用→C2S payload→等 S2C tool_result"执行器。
 * 只跑客户端侧（LLM 异步、配置带 key）；世界操作永远发生在服务器。
 * transcript 是面板的只读回看缓冲；同时是桥接（M6）的事件生产者。
 */
public final class AgentRunner implements ToolExecutor {

    private static final org.slf4j.Logger LOG = LoggerFactory.getLogger("mcbot/agent");
    /** 默认工具回执超时。 */
    private static final long TOOL_TIMEOUT_MS = 90_000;
    // 反问等待从 5min 降到 2min：配合游戏内 `@bot 答 <文本>` 入口，没人理就快醒。
    private static final long QUESTION_TIMEOUT_MS = 120_000;
    private static final int TRANSCRIPT_CAP = 80;

    /**
     * 按工具名给回执超时（效率评估发现的真缺陷：客户端 90s &lt; 服务端 move 帽 180s）。
     *
     * <p><b>原来的后果</b>：`move_to` 服务端允许跑 3600 tick = **180s**，而客户端一律 90s 就
     * 判 TIMEOUT。于是任何 90–180s 的移动，模型都会收到"这次操作 90 秒没有结果，先别重复这个操作"
     * ——**一句错误的教它别重试**，而同伴其实还在走；随后那条第 90s 之后才到的真回执，
     * 因为 seq 已从 `pending` 移除而被**静默丢弃**（`handleS2c` 里 `p == null` 直接返回）。
     * 模型拿着错的世界模型继续动作，再撞一次 BUSY。
     *
     * <p><b>口径</b>：客户端超时必须**严格大于**服务端该工具的 cap（帽 + 余量），
     * 否则 TIMEOUT 就不是"真超时"而是"客户端先跑了"。帽值来源：
     * {@code MoveToTool} 3600 tick、{@code BreakBlockTool} 1200 tick、
     * {@code WaitTool} seconds*20+100 tick（按最长的 60s 算）。
     */
    private static long toolTimeoutMs(String tool, String argsJson) {
        return switch (tool) {
            // 服务端 3600 tick = 180s，留 30s 余量
            case "move_to" -> 210_000;
            // 服务端 seconds*20+100 tick；seconds 上限 60 ⇒ 1300 tick = 65s，留余量
            case "wait" -> 80_000;
            // 服务端 1200 tick = 60s，90s 已够
            default -> TOOL_TIMEOUT_MS;
        };
    }

    private volatile ClientConfig cfg;
    private static final AtomicLong seqGen = new AtomicLong();
    private static final AtomicLong taskSeq = new AtomicLong();
    private volatile boolean closed;

    private record ToolTicket(long taskId, AgentLoop owner, CompletableFuture<ToolOutcome> future) {
        void complete(ToolOutcome outcome) { future.complete(outcome); }
    }
    /**
     * 两段式等待记账（R2-S4）：seq → 终局回执；受理后转成 jobId → job 事件。
     * 凭据就是那个 future，转段时跟着一起搬（见 {@link PendingJobs}）。
     */
    private final PendingJobs<ToolTicket> pending = new PendingJobs<>();
    private final TaskReplies askWaiters = new TaskReplies();
    private final ArrayDeque<String> transcript = new ArrayDeque<>();
    private volatile long currentTask;
    private volatile String companionName = "";
    private volatile String lifecycleResult = "";
    /** 已超时或取消的调用仍收到回执；只计数，不重新消费。 */
    private final AtomicLong lateResults = new AtomicLong();
    /** PARK 观测：受理之后链挂起、等 job 事件的数量（面板/桥可见）。 */
    private volatile boolean parked;
    private volatile int parkedJobs;
    private volatile AgentLoop loop;
    private long brainGeneration;
    private final ClientServices client;
    private final Map<Long, Boolean> taskPolicies = new ConcurrentHashMap<>();
    private record AuthorizationPlan(long taskId, String summary) {
    }
    private final Map<String, AuthorizationPlan> authorizationPlans = new ConcurrentHashMap<>();

    /** Client side effects stay at this boundary so the real runner can be exercised offline. */
    interface ClientServices {
        ChatEngine engine(ClientConfig cfg);
        PromptBuilder prompt(ClientConfig cfg);
        void showChat(String text);
        void sendCancel(AgentRunner owner);
        void saveConfig(ClientConfig cfg);
        long nowMs();
        boolean inGame();
        void executeOnClient(Runnable action);
        boolean canSend();
        void send(Envelope envelope);
        boolean isCurrentRunner(AgentRunner owner);
        CompletableFuture<ToolOutcome> observe(AgentRunner owner, long taskId);
    }

    private static final class MinecraftServices implements ClientServices {
        @Override
        public ChatEngine engine(ClientConfig cfg) {
            var provider = new com.neko.mcbot.agentcore.provider.OpenAiCompatProvider(
                    "mcbot", cfg.baseUrl, cfg.apiKey, cfg.model);
            return new CallbackChatEngine(new LlmClient(provider,
                    java.time.Duration.ofSeconds(180),
                    diagnostic -> LOG.info("[brain] llm request {}", diagnostic.summary()),
                    diagnostic -> LOG.info("[brain] llm response {}", diagnostic.summary())),
                    Minecraft.getInstance()::execute);
        }

        @Override
        public PromptBuilder prompt(ClientConfig cfg) {
            return new PromptBuilder(() -> PromptBuilder.build(cfg.persona, SkillLoader.load(
                    FabricLoader.getInstance().getGameDir().resolve("mcbot").resolve("skills"))));
        }

        @Override
        public void showChat(String text) {
            Minecraft mc = Minecraft.getInstance();
            mc.execute(() -> {
                if (mc.gui != null) mc.gui.getChat().addMessage(Component.literal(text));
            });
        }

        @Override
        public void sendCancel(AgentRunner owner) {
            Minecraft.getInstance().execute(() -> {
                if (McbotClient.runner() != owner) return;
                if (ClientPlayNetworking.canSend(McbotPayloads.C2s.TYPE)) {
                    ClientPlayNetworking.send(new McbotPayloads.C2s(
                            new Envelope("cancel", new JsonObject()).encode()));
                }
            });
        }

        @Override public void saveConfig(ClientConfig cfg) { ClientConfig.save(cfg); }
        @Override public long nowMs() { return System.currentTimeMillis(); }
        @Override public boolean inGame() { return Minecraft.getInstance().level != null; }
        @Override public void executeOnClient(Runnable action) { Minecraft.getInstance().execute(action); }
        @Override public boolean canSend() { return ClientPlayNetworking.canSend(McbotPayloads.C2s.TYPE); }
        @Override public void send(Envelope envelope) {
            ClientPlayNetworking.send(new McbotPayloads.C2s(envelope.encode()));
        }
        @Override public boolean isCurrentRunner(AgentRunner owner) { return McbotClient.runner() == owner; }
        @Override public CompletableFuture<ToolOutcome> observe(AgentRunner owner, long taskId) {
            return owner.requestObservation(taskId);
        }
    }

    public AgentRunner(ClientConfig cfg) {
        this(cfg, new MinecraftServices());
    }

    AgentRunner(ClientConfig cfg, ClientServices client) {
        this.cfg = cfg;
        this.client = client;
    }

    /** 进世界后调用一次；重复调用安全。 */
    public void start() {
        if (closed || !cfg.brainEnabled || loop != null) {
            return;
        }
        ChatEngine engine;
        try {
            engine = client.engine(cfg);
        } catch (IllegalArgumentException invalid) {
            say("§c[mcbot] API 地址无效，请打开模型配置检查地址。§r");
            LOG.warn("[brain] invalid API address; brain not started");
            return;
        }
        // R2-B：prefix 缓存也接上了——启动读一次盘，指令边界才允许换发（链中不换=不裂前缀）。
        PromptBuilder sessionPrompt = client.prompt(cfg);
        loop = new AgentLoop(engine, ClientToolDefs.SPECS, this, AgentLoop.Config.defaults(),
                new AgentLoop.Listener() {
                    @Override
                    public void onTaskStarted(long taskId) {
                        currentTask = taskId;
                        JsonObject scope = new JsonObject();
                        scope.addProperty("task_id", taskId);
                        scope.addProperty("read_only", taskPolicies.getOrDefault(taskId, true));
                        sendTaskControl("task_begin", scope);
                        JsonObject d = new JsonObject();
                        d.addProperty("status", "running");
                        emit("state", taskId, d);
                    }

                    @Override
                    public void onTaskFinished(long taskId, AgentLoop.TaskStatus status, String text) {
                        boolean wasActive = currentTask == taskId;
                        if (wasActive && status != AgentLoop.TaskStatus.COMPLETED) sendCancel();
                        if (wasActive) {
                            JsonObject scope = new JsonObject();
                            scope.addProperty("task_id", taskId);
                            sendTaskControl("task_end", scope);
                        }
                        taskPolicies.remove(taskId);
                        authorizationPlans.entrySet().removeIf(entry -> entry.getValue().taskId() == taskId);
                        clearTaskWaits(taskId, status.name() + ":任务已终止。");
                        if (wasActive) currentTask = 0;
                        emitDone(taskId, status, text);
                        askWaiters.finish(taskId, status, text);
                    }

                    @Override
                    public void onReply(String text) {
                        say("§b[同伴] §r" + text);
                    }

                    @Override
                    public void onToolInvoked(String name, String argsJson, boolean ok, String feedback) {
                        LOG.info("tool {} -> {} {}", name, ok ? "✔" : "✘", feedback);
                        record("§7" + name + (ok ? " ✔ " : " ✘ ") + feedback + "§r");
                        JsonObject d = new JsonObject();
                        d.addProperty("text", name + (ok ? " ✔ " : " ✘ ") + feedback);
                        d.addProperty("tool", name);
                        d.addProperty("ok", ok);
                        emit("progress", d);
                    }

                    @Override
                    public void onNotice(String text) {
                        LOG.info("loop notice: {}", text);
                        record("§7（护栏）" + text + "§r");
                        JsonObject d = new JsonObject();
                        d.addProperty("text", "（护栏）" + text);
                        emit("progress", d);
                    }

                    @Override
                    public void onUsage(long prompt, long completion, long cached) {
                        // M4.5 可观测：每步真实上下文体量。cached<0=后端没报；
                        // prompt 持续涨而 cached 常年 0/缺失 → 前缀缓存没吃到，该查压缩/剪枝。
                        LOG.info("[brain] step tokens prompt={} completion={} cached={}",
                                prompt, completion, cached);
                    }

                    @Override
                    public void onStreamStats(AgentLoop.StreamStats stats) {
                        // R2-A 打点：回答"这一轮卡在哪"。
                        // first_tool 是传输就绪，first_dispatch 才是执行器派发；二者不能混为一谈。
                        LOG.info("[brain] llm stream {}", stats.format());
                    }

                    @Override
                    public void onPrefixReset(String reason) {
                        // R2-B：合法前缀重置（compaction / directive-boundary）唯一两处，打点以便对账
                        // 缓存命中率下降到底是这两件事还是别处动了前缀。
                        LOG.info("[brain] prefix reset reason={}", reason);
                    }

                    @Override
                    public void onToolAccepted(String name, String argsJson, String feedback, String jobId) {
                        // 受理 ≠ 结果：这里只播报，不进 transcript 的"已做完"叙事。
                        LOG.info("[brain] tool {} 受理 job={}", name, jobId);
                        JsonObject d = new JsonObject();
                        d.addProperty("text", feedback);
                        d.addProperty("tool", name);
                        d.addProperty("job_id", jobId);
                        emit("progress", d);
                    }

                    @Override
                    public void onParked(boolean nowParked, int outstanding) {
                        // PARK 是本卡的核心状态：它必须在面板/桥/日志三处都看得见，
                        // 否则"卡住了"和"在等一条长活"从外面完全分不出来。
                        parked = nowParked;
                        parkedJobs = outstanding;
                        LOG.info("[brain] park={} 在等 {} 条长活", nowParked, outstanding);
                        JsonObject d = new JsonObject();
                        d.addProperty("parked", nowParked);
                        d.addProperty("outstanding", outstanding);
                        emit("state", d);
                    }
                },
                sessionPrompt::get,
                // 压缩闸门：6000 真 token（API 数优先，本地 CJK 感知估算兜底）。
                // 参考实测：固定开销≈350，每步只追加；这个数给中转站通道留了延迟余地。
                sessionPrompt,
                6_000);
        LOG.info("大脑已上线：{} / {}", cfg.baseUrl, cfg.model);
    }

    /** 面板"保存并应用"：落盘配置并重建大脑（对话历史清零属预期；悬着的问题就地终止）。 */
    public void reconfigure(ClientConfig newCfg) {
        if (closed) return;
        client.saveConfig(newCfg);
        shutdownBrain();
        cfg = newCfg;
        start();
        if (newCfg.brainEnabled) {
            say("§a[mcbot] 配置已保存，大脑已重启：" + newCfg.model + "§r");
        } else {
            say("§c[mcbot] 配置不完整（需要 base_url/model/api_key），大脑未启动。§r");
        }
    }

    public ClientConfig config() {
        return cfg;
    }

    public void close() {
        if (closed) return;
        closed = true;
        shutdownBrain();
    }

    private void shutdownBrain() {
        brainGeneration++;
        AgentLoop old = loop;
        if (old == null || old.currentTaskId() == 0) sendCancel();
        if (old != null) old.close();
        loop = null;
        currentTask = 0;
        parked = false;
        parkedJobs = 0;
        latestQuestion = null;
        clearTaskWaits(0, "CANCELLED:会话已重载或关闭。");
    }

    private void clearTaskWaits(long taskId, String text) {
        ToolOutcome stopped = ToolOutcome.synthetic(text);
        var tickets = pending.drain(t -> t.taskId() == taskId);
        var questions = new ArrayList<QuestionRecord>();
        questionRecords.entrySet().removeIf(e -> {
            if (e.getValue().taskId != taskId) return false;
            if (latestQuestion != null && latestQuestion.equals(e.getKey())) latestQuestion = null;
            questions.add(e.getValue());
            return true;
        });
        tickets.forEach(t -> t.complete(stopped));
        questions.forEach(q -> q.future.complete(stopped));
    }

    public List<String> transcriptSnapshot() {
        synchronized (transcript) {
            return new ArrayList<>(transcript);
        }
    }

    public void handleS2c(Envelope env) {
        if (closed) return;
        switch (env.kind()) {
            case "tool_result" -> {
                long seq = env.num("seq", -1);
                var p = pending.takeTool(seq);
                if (p != null) {
                    rememberAuthorization(env, p.ticket());
                    p.ticket().complete(new ToolOutcome(env.bool("ok"), env.str("feedback")));
                } else {
                    // 超时、取消、会话清理或重复包都可能迟到；计数不能单独证明超时帽错误。
                    lateResults.incrementAndGet();
                    LOG.warn("[brain] 迟到回执 seq={} ok={}（已无等待项，丢弃）feedback={}",
                            seq, env.bool("ok"), env.str("feedback"));
                }
            }
            case "job_ack" -> onJobAck(env);
            case "job_event" -> onJobEvent(env);
            case "companion_state" -> {
                companionName = env.str("companion");
                lifecycleResult = env.str("text");
                emitState(lifecycleResult);
            }
            case "event" -> {
                say("§7[同伴] " + env.str("text") + "§r");
                emitState(env.str("text"));
            }
            default -> {
                String text = env.body().has("text") ? env.str("text") : env.kind();
                say("§7[mcbot] " + text + "§r");
                if (env.body().has("companion")) {
                    companionName = env.str("companion");
                    lifecycleResult = text;
                }
                emitState(text);
            }
        }
    }

    /**
     * 受理回执：那个 seq 这一跳就定性了（**不会再等 tool_result**），改等 job 事件。
     *
     * <p>兑现 future 用的是"受理"这个 outcome（{@code accepted=true}）——大脑据此
     * **不把这条写进对话**，转而 PARK。这里绝不能拿它当结果：把它写进对话就等于告诉模型
     * "事情做完了"，那正是这一卡要消灭的谎。
     */
    private void onJobAck(Envelope env) {
        long seq = env.num("seq", -1);
        String jobId = env.str("job_id");
        int capTicks = env.num("cap_ticks", PendingJobs.FALLBACK_CAP_TICKS);
        var j = pending.acceptAsJob(seq, jobId, capTicks, client.nowMs());
        if (j == null) {
            lateResults.incrementAndGet();
            LOG.warn("[brain] 迟到受理 seq={} job={}（该 seq 已被收走，丢弃）", seq, jobId);
            return;
        }
        LOG.info("[brain] job {} 受理 {}（帽 {}tick ⇒ 本地等 {}s）",
                jobId, j.tool(), capTicks, j.timeoutMs() / 1000);
        j.ticket().complete(ToolOutcome.accepted(jobId, env.str("text")));
    }

    /** 长活的后续：progress 只播报；done/failed/cancelled/superseded 才解锁 PARK。 */
    private void onJobEvent(Envelope env) {
        String jobId = env.str("job_id");
        String phase = env.str("phase");
        String text = env.str("text");
        var known = pending.peekJob(jobId);
        if (known == null || known.seq() != env.num("seq", -1)) return;
        if ("progress".equals(phase)) {
            // 进度帧不进对话（模型不需要、也看不懂"第 3/7 格"），只喂给桥与面板
            JsonObject d = new JsonObject();
            d.addProperty("text", text);
            d.addProperty("job_id", jobId);
            d.addProperty("tool", env.str("tool"));
            emit("progress", known.ticket().taskId(), d);
            return;
        }
        if (!List.of("done", "failed", "cancelled", "superseded").contains(phase)) return;
        var j = pending.takeJob(jobId);
        if (j == null) {
            // 正常：叫停/换发时大脑已经本地补过合成回执，这条真结果就该被丢掉。
            LOG.info("[brain] job {} 事件 phase={} 无人认领（已被叫停/顶替收走）", jobId, phase);
            return;
        }
        LOG.info("[brain] job {} {} phase={} {}ms", jobId, j.tool(), phase,
                client.nowMs() - j.acceptedAtMs());
        rememberAuthorization(env, j.ticket());
        boolean ok = "done".equals(phase);
        if (j.ticket().owner() == loop && loop != null) {
            j.ticket().owner().onJobEvent(jobId, new ToolOutcome(ok, text));
        }
    }

    /** 面板召唤/遣散按钮：与 /mcbot 命令同权限（服务器按发送者校验 owner）。 */
    public void sendLifecycle(String kind, String name) {
        if (closed || !client.canSend()) {
            lifecycleResult = "未连接装有 mcbot 的服务器，操作未发送。";
            say("§c[mcbot] " + lifecycleResult + "§r");
            return;
        }
        if (("summon".equals(kind) || "dismiss".equals(kind))
                && !name.matches("[A-Za-z0-9_]{2,16}")) {
            lifecycleResult = "名字需要 2-16 位英文字母、数字或下划线。";
            say("§c[mcbot] " + lifecycleResult + "§r");
            return;
        }
        JsonObject body = new JsonObject();
        body.addProperty("name", name);
        lifecycleResult = "请求已发送，等待当前世界回执。";
        client.send(new Envelope(kind, body));
    }

    public String companionName() {
        return companionName;
    }

    public String lifecycleResult() {
        return lifecycleResult;
    }

    /** Read-only panel inspections are independent of the model and any active agent task. */
    public void inspect(String name) {
        if (!List.of("status", "scan_area").contains(name)) {
            throw new IllegalArgumentException("Only read-only panel inspections are allowed");
        }
        record("§9我> §r" + ("status".equals(name) ? "查看状态" : "扫描附近"));
        long generation = brainGeneration;
        executeRemote(name, "{}", 0, null).whenComplete((result, failure) ->
                client.executeOnClient(() -> {
                    // Reload keeps this runner, but queued inspection displays belong to the old brain.
                    if (closed || generation != brainGeneration || !client.isCurrentRunner(this)) return;
                    say("§7[mcbot] " + (failure == null ? result.feedback() : "检查失败，请重试。") + "§r");
                }));
    }
    /** 主线程周期调用：工具/长活/问题超时兜底。 */
    public void tick() {
        if (closed) return;
        long now = client.nowMs();
        for (var t : pending.sweepTools(now)) {
            if (t.ticket().owner() != null) sendCancel();
            t.ticket().complete(new ToolOutcome(false,
                    "TIMEOUT:这次操作 " + (t.timeoutMs() / 1000)
                            + " 秒没有结果，先别重复这个操作，向主人说明情况。"));
        }
        for (var j : pending.sweepJobs(now)) {
            sendCancel();
            // 长活超时必须**同样交给大脑**：不交的话 PARK 就永远解不开，整条链静默卡死
            // （比收到一条 TIMEOUT 教学坏得多——模型连"我卡住了"都看不到）。
            LOG.warn("[brain] job {} 本地等满 {}s 仍未收到事件，按超时解锁 PARK",
                    j.jobId(), j.timeoutMs() / 1000);
            if (j.ticket().owner() == loop && loop != null) {
                j.ticket().owner().onJobEvent(j.jobId(), new ToolOutcome(false,
                        "TIMEOUT:这件事等了 " + (j.timeoutMs() / 1000)
                                + " 秒还没有结果（服务端可能已掉线）。别重复这个操作，先向主人说明情况。"));
            }
        }
        sweepQuestionTimeouts();
    }

    /** 同伴正在等主人回答的最新反问（游戏内 `@bot 答 …` 入口用）。 */
    private volatile Long latestQuestion;

    private final class QuestionRecord {
        final CompletableFuture<ToolOutcome> future;
        final long at;
        final long taskId;
        final String authorizationId;

        QuestionRecord(long taskId, CompletableFuture<ToolOutcome> f, String authorizationId) {
            this.taskId = taskId;
            this.future = f;
            this.at = client.nowMs();
            this.authorizationId = authorizationId;
        }
    }

    private static final java.util.regex.Pattern CANCEL_WORDS = java.util.regex.Pattern
            .compile("(停|停下|停手|取消|别干了|别做了|cancel|stop)", java.util.regex.Pattern.CASE_INSENSITIVE);

    // 游戏内回答同伴反问："答 <文本>" 或 "answer <文本>"
    private static final java.util.regex.Pattern ANSWER_DIRECTIVE = java.util.regex.Pattern
            .compile("^(答|answer)[：: ]+(.+)$", java.util.regex.Pattern.CASE_INSENSITIVE | java.util.regex.Pattern.DOTALL);

    /** 主人指令统一入口（聊天/桥共用）。返回分配的 task_id。 */
    public long submitTask(String text) {
        if (text == null || text.isBlank()) throw new IllegalArgumentException("text must not be blank");
        long id = taskSeq.incrementAndGet();
        submitTask(id, text);
        return id;
    }

    private void submitTask(long id, String text) {
        record("§9我> §r" + text);   // 面板回看只记主人原话：注入是给模型的上下文，不是主人说过的话
        AgentLoop brain = loop;
        if (closed || brain == null) {
            say("§c[mcbot] 大脑未配置：打开面板（默认 G）填 base_url/model/api_key 后保存。§r");
            String reason = "大脑不在线，指令未执行";
            emitDone(id, AgentLoop.TaskStatus.FAILED, reason);
            askWaiters.finish(id, AgentLoop.TaskStatus.FAILED, reason);
            return;
        }
        // R2-D 准星注入：把“主人刚说这句话时正盯着什么”拼到 user 文本尾。
        // 读不到（未进世界/准星空/跨线程异常）就吐个空串，“没得看”不能变成“看不看得到都要”的噪声。
        String hint = crosshairHint();
        JsonObject queued = new JsonObject();
        queued.addProperty("status", "queued");
        emit("state", id, queued);
        try {
            taskPolicies.put(id, TaskPolicy.readOnlyDirective(text));
            brain.submit(id, hint.isEmpty() ? text : text + "\n" + hint);
        } catch (IllegalStateException stopped) {
            String reason = "大脑会话已关闭，指令未执行";
            emitDone(id, AgentLoop.TaskStatus.FAILED, reason);
            askWaiters.finish(id, AgentLoop.TaskStatus.FAILED, reason);
        }
    }

    /**
     * 本地拼一条准星提示（不过网络、不进服务器，只补上“发话瞬间”这个模型本来拿不到的信息）。
     *
     * <p><b>“发话瞬间”语义：</b>取的是 {@code Minecraft.hitResult}（客户端每拍刷一次的上一次
     * 射线拾取结果），不是“主人开口那一帧”的精确重放：一拍 50ms 内准星不会瞬移，
     * 拿它当“刚说话时盯的格”对模型足够；反过来，等到工具跑完再看就错了一整个往返。
     *
     * <p>口径：MISS / ENTITY / 未加载区块 / 空方块一律不注入；方块名用注册表路径
     * （与 scan_area 同一套词），坐标用绝对。准星够不着时 {@code hitResult} 本身就是 MISS：
     * 拿不到确定位置时“猜一个”比“不说”坑得多。
     *
     * <p>线程：桥接投令先进入客户端主线程，再读取准星与世界；退世界时读不到就不注入。
     */
    private static String crosshairHint() {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc.level == null || mc.player == null) {
                return "";
            }
            HitResult hit = mc.hitResult;
            if (hit == null || hit.getType() != HitResult.Type.BLOCK) {
                return "";
            }
            BlockPos p = ((BlockHitResult) hit).getBlockPos();
            if (!mc.level.hasChunkAt(p)) {
                return "";
            }
            BlockState st = mc.level.getBlockState(p);
            if (st.isAir()) {
                return "";
            }
            String path = BuiltInRegistries.BLOCK.getKey(st.getBlock()).getPath();
            double d = mc.player.position().distanceTo(Vec3.atCenterOf(p));
            return ScanFormat.crosshair(path, p.getX(), p.getY(), p.getZ(), d);
        } catch (Throwable t) {
            return "";
        }
    }

    public void onOwnerDirective(String text) {
        if (CANCEL_WORDS.matcher(text.trim()).matches()) {
            record("§9我> §r" + text);
            requestCancel();
            return;
        }
        var ans = ANSWER_DIRECTIVE.matcher(text.trim());
        if (ans.matches()) {
            Long qid = latestQuestion;
            if (qid != null && answerQuestion("q" + qid, ans.group(2).trim())) {
                record("§9我> §r" + text);
                return;
            }
            say("§7[mcbot] 同伴现在没有可回答的问题，回答未提交。§r");
            return;
        }
        submitTask(text);
    }

    /** 全局叫停保留给游戏内按钮；桥接可按任务编号取消。 */
    public void requestCancel() {
        cancelTask(0);
    }

    public boolean cancelTask(long taskId) {
        AgentLoop brain = loop;
        if (closed) return false;
        boolean cancelled = brain != null && brain.cancelTask(taskId);
        if (taskId == 0 && !cancelled) sendCancel();
        return cancelled;
    }

    private void sendCancel() {
        client.sendCancel(this);
    }

    /** 先登记编号再投令，同步完成也不会抢答其他任务。 */
    public CompletableFuture<String> askNext(String text) {
        if (text == null || text.isBlank()) throw new IllegalArgumentException("text must not be blank");
        long id = taskSeq.incrementAndGet();
        CompletableFuture<String> f = askWaiters.register(id);
        submitTask(id, text);
        return f;
    }

    /** 回答同伴的 ask_owner 反问。qid 形如 "q12"。 */
    public boolean answerQuestion(String qid, String text) {
        if (closed || qid == null || !qid.startsWith("q") || text == null || text.isBlank()) {
            return false;
        }
        try {
            long seq = Long.parseLong(qid.substring(1));
            if (!qid.equals("q" + seq)) return false;
            QuestionRecord rec = questionRecords.remove(seq);
            if (rec == null) {
                return false;
            }
            if (latestQuestion != null && latestQuestion == seq) {
                latestQuestion = null;
            }
            if (questionExpired(rec, client.nowMs())) {
                timeoutQuestion(rec);
                return false;
            }
            JsonObject d = new JsonObject();
            d.addProperty("text", "主人已回答 " + qid);
            emit("state", rec.taskId, d);
            if (rec.authorizationId == null) {
                rec.future.complete(new ToolOutcome(true, "主人说：" + text));
            } else if (TaskPolicy.affirmative(text) && !taskPolicies.getOrDefault(rec.taskId, true)) {
                authorize(rec).whenComplete((outcome, failure) -> rec.future.complete(failure == null
                        ? outcome : new ToolOutcome(false, "INTERNAL:授权没有有效回执，未批准。")));
            } else {
                rec.future.complete(new ToolOutcome(false, "DENIED:未获得明确许可，路线改动未授权。"));
            }
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private final Map<Long, QuestionRecord> questionRecords = new ConcurrentHashMap<>();

    /** 桥状态探针（GET /v1/status）。字段全部 volatile/并发安全，可跨线程读。 */
    public String statusJson() {
        JsonObject o = new JsonObject();
        o.addProperty("in_game", client.inGame());
        o.addProperty("brain_enabled", !closed && loop != null);
        o.addProperty("model", cfg == null ? "" : cfg.model);
        o.addProperty("companion", companionName);
        o.addProperty("current_task", currentTask);
        AgentLoop brain = loop;
        o.addProperty("queued_tasks", brain == null ? 0 : brain.queuedTasks());
        o.addProperty("pending_asks", askWaiters.size());
        o.addProperty("pending_tools", pending.pendingTools());
        // R2-S4：长活是"第二段等待"，它和普通工具待办必须分开看——
        // 合在一起数的话，"卡在工具上"和"正常在等一条 3 分钟的移动"分不出来。
        o.addProperty("pending_jobs", pending.pendingJobs());
        o.addProperty("parked", parked);
        o.addProperty("parked_jobs", parkedJobs);
        o.addProperty("accept_mode", cfg == null || cfg.acceptMode);
        o.addProperty("pending_questions", questionRecords.size());
        // 取消/超时后的迟到回执可见，但不再参与新任务。
        o.addProperty("late_results", lateResults.get());
        return o.toString();
    }

    @Override
    public CompletableFuture<ToolOutcome> observe(long taskId) {
        if (closed || taskId != currentTask) return CompletableFuture.completedFuture(
                ToolOutcome.synthetic("CANCELLED:观测所属任务已结束。"));
        return client.observe(this, taskId);
    }

    CompletableFuture<ToolOutcome> requestObservation(long taskId) {
        return executeRemote("status", "{\"details\":true}", taskId, loop);
    }

    @Override
    public CompletableFuture<ToolOutcome> execute(String name, String argsJson) {
        if (closed) return CompletableFuture.completedFuture(ToolOutcome.synthetic("CANCELLED:会话已关闭。"));
        if ("ask_owner".equals(name)) {
            return askOwner(argsJson);
        }
        return executeRemote(name, argsJson, currentTask, loop);
    }

    private CompletableFuture<ToolOutcome> executeRemote(String name, String argsJson,
                                                        long taskId, AgentLoop owner) {
        if (closed) return CompletableFuture.completedFuture(ToolOutcome.synthetic("CANCELLED:会话已关闭。"));
        if (!client.canSend()) {
            return CompletableFuture.completedFuture(
                    new ToolOutcome(false, "DENIED:尚未连上装有 mcbot 的服务器。"));
        }
        long seq = seqGen.incrementAndGet();
        JsonObject body = new JsonObject();
        body.addProperty("seq", seq);
        body.addProperty("tool", name);
        // R2-S4：**显式点名**要不要受理即回执。服务端对没有这个字段的请求一律按老语义
        // （同步回执）处理——老客户端不认识 job_ack，擅自换形态会让它白等到超时。
        // 这个字段同时也是发布后的止血开关（client.json 的 accept_mode）。
        boolean accept = cfg == null || cfg.acceptMode;
        body.addProperty("accept", accept);
        try {
            body.add("args", com.google.gson.JsonParser.parseString(
                    argsJson == null || argsJson.isBlank() ? "{}" : argsJson));
        } catch (RuntimeException e) {
            return CompletableFuture.completedFuture(
                    new ToolOutcome(false, "INTERNAL:模型给出的参数不是合法 JSON。"));
        }
        if (!body.get("args").isJsonObject()) return CompletableFuture.completedFuture(
                new ToolOutcome(false, "DENIED:工具参数须为 JSON 对象。"));
        JsonObject args = body.getAsJsonObject("args");
        if (taskPolicies.getOrDefault(taskId, false) && !TaskPolicy.observation(name, args)) {
            return CompletableFuture.completedFuture(new ToolOutcome(false,
                    "DENIED:主人要求只读，此任务不能移动或改动世界/物品。"));
        }
        body.addProperty("task_id", taskId);
        CompletableFuture<ToolOutcome> f = new CompletableFuture<>();
        String json = new Envelope("tool_call", body).encode();
        // 发送前自检：服务端闸①对超尺寸包是“丢弃 + 日志”，不会回话。若不在这里拦下，
        // 这个 seq 会挂在 pending 里直到 90 秒后变一条与真因无关的 TIMEOUT 教学，
        // 模型也就学不会“是我参数太肥”。就地回 DENIED，既不占任务槽也不制造掉线错觉。
        if (!WireSize.fits(json)) {
            return CompletableFuture.completedFuture(new ToolOutcome(false,
                    "DENIED:这次工具调用的参数过大（" + WireSize.utf8Bytes(json)
                            + "B，上限 " + WireSize.MAX_BODY_BYTES + "B），服务器不会收。"
                            + "请缩小范围或分批（如扫描半径调小、一次只处理少量方块）。"));
        }
        ToolTicket ticket = new ToolTicket(taskId, owner, f);
        pending.registerTool(seq, name, client.nowMs(), toolTimeoutMs(name, argsJson), ticket);
        client.executeOnClient(() -> {
            if (closed || f.isDone()) return;
            if (ticket.owner() != null && (ticket.owner() != loop || ticket.taskId() != currentTask)) return;
            if (client.canSend()) {
                client.send(new Envelope("tool_call", body));
            } else {
                pending.takeTool(seq);
                f.complete(new ToolOutcome(false, "DENIED:服务器连接已关闭。"));
            }
        });
        return f;
    }

    /** 本地工具：问题按任务归属，120 秒无回复超时。 */
    private CompletableFuture<ToolOutcome> askOwner(String argsJson) {
        long seq = seqGen.incrementAndGet();
        CompletableFuture<ToolOutcome> f = new CompletableFuture<>();
        long taskId = currentTask;
        String text;
        String authorizationId = null;
        try {
            var el = com.google.gson.JsonParser.parseString(
                    argsJson == null || argsJson.isBlank() ? "{}" : argsJson);
            text = el.isJsonObject() && el.getAsJsonObject().has("text")
                    ? el.getAsJsonObject().get("text").getAsString() : String.valueOf(argsJson);
            if (el.isJsonObject() && el.getAsJsonObject().has("authorization_id")) {
                var value = el.getAsJsonObject().get("authorization_id");
                if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
                    return CompletableFuture.completedFuture(new ToolOutcome(false, "DENIED:授权编号无效。"));
                }
                authorizationId = value.getAsString();
                AuthorizationPlan plan = authorizationPlans.get(authorizationId);
                if (plan == null || plan.taskId() != taskId || taskPolicies.getOrDefault(taskId, true)) {
                    return CompletableFuture.completedFuture(new ToolOutcome(false,
                            "DENIED:没有属于当前任务的可批准路线，先查看具体清单。"));
                }
                // Approval is for the server's concrete scope, never a model-written substitute.
                text = plan.summary() + "\n批准请回答「确认」，其他回答不授予路线改动权限。";
            }
        } catch (RuntimeException e) {
            text = String.valueOf(argsJson);
        }
        questionRecords.put(seq, new QuestionRecord(taskId, f, authorizationId));
        say("§d[同伴想问] §r" + text + " §7（回答：@bot 答 <文本>）§r");
        latestQuestion = seq;
        JsonObject d = new JsonObject();
        d.addProperty("question_id", "q" + seq);
        d.addProperty("text", text);
        emit("question", taskId, d);
        return f;
    }

    private void sendTaskControl(String kind, JsonObject body) {
        client.executeOnClient(() -> {
            if (client.isCurrentRunner(this) && client.canSend()) client.send(new Envelope(kind, body));
        });
    }

    private void rememberAuthorization(Envelope envelope, ToolTicket ticket) {
        if (ticket.owner() != loop || ticket.taskId() != currentTask) return;
        var data = envelope.body().get("data");
        if (data == null || !data.isJsonObject()) return;
        var id = data.getAsJsonObject().get("authorization_id");
        if (id != null && id.isJsonPrimitive() && id.getAsJsonPrimitive().isString()
                && id.getAsString().length() <= 64) {
            authorizationPlans.clear();
            var summary = data.getAsJsonObject().get("authorization_summary");
            if (summary != null && summary.isJsonPrimitive() && summary.getAsJsonPrimitive().isString()) {
                authorizationPlans.put(id.getAsString(), new AuthorizationPlan(ticket.taskId(), summary.getAsString()));
            }
        }
    }

    private CompletableFuture<ToolOutcome> authorize(QuestionRecord question) {
        long seq = seqGen.incrementAndGet();
        JsonObject body = new JsonObject();
        body.addProperty("seq", seq);
        body.addProperty("task_id", question.taskId);
        body.addProperty("authorization_id", question.authorizationId);
        var future = new CompletableFuture<ToolOutcome>();
        var ticket = new ToolTicket(question.taskId, loop, future);
        pending.registerTool(seq, "authorize", client.nowMs(), TOOL_TIMEOUT_MS, ticket);
        client.executeOnClient(() -> {
            if (closed || future.isDone()) return;
            if (question.taskId != currentTask || ticket.owner() != loop || !client.canSend()) {
                pending.takeTool(seq);
                future.complete(new ToolOutcome(false, "CANCELLED:任务已结束，授权没有发送。"));
                return;
            }
            client.send(new Envelope("authorize", body));
        });
        return future;
    }

    /** 问题超时巡检（客户端主线程 tick 里调）。 */
    private void sweepQuestionTimeouts() {
        long now = client.nowMs();
        var expired = new ArrayList<QuestionRecord>();
        questionRecords.entrySet().removeIf(e -> {
            if (questionExpired(e.getValue(), now)) {
                if (latestQuestion != null && latestQuestion.equals(e.getKey())) {
                    latestQuestion = null; // 超时作废，别留悬指针
                }
                expired.add(e.getValue());
                return true;
            }
            return false;
        });
        expired.forEach(this::timeoutQuestion);
    }

    private boolean questionExpired(QuestionRecord question, long now) {
        return now - question.at > QUESTION_TIMEOUT_MS;
    }

    private void timeoutQuestion(QuestionRecord question) {
        question.future.complete(new ToolOutcome(false,
                question.authorizationId == null
                        ? "TIMEOUT:主人 2 分钟没回你的问题。按最稳妥的理解自行定夺，或向主人说明你在等什么。"
                        : "TIMEOUT:主人未在2分钟内确认具体清单，路线改动未获许可；先停止并汇报。"));
    }

    private void emitState(String text) {
        JsonObject d = new JsonObject();
        d.addProperty("text", text);
        emit("state", 0, d);
    }

    private void emitDone(long taskId, AgentLoop.TaskStatus status, String text) {
        JsonObject d = new JsonObject();
        d.addProperty("text", text);
        d.addProperty("status", status.name().toLowerCase(java.util.Locale.ROOT));
        emit("done", taskId, d);
    }

    private void emit(String kind, JsonObject data) {
        emit(kind, currentTask, data);
    }

    private void emit(String kind, long taskId, JsonObject data) {
        data.addProperty("ev", kind);
        data.addProperty("task_id", taskId);
        BridgeEvents.publish(kind, data);
    }

    private void say(String text) {
        record(text);
        // 模型偶尔把换行写成字面量 \n，聊天显示前归一化
        String clean = stripCodes(text).replace("\\r", "").replace("\\n", "\n");
        client.showChat(clean);
    }

    private void record(String text) {
        synchronized (transcript) {
            transcript.addLast(text);
            while (transcript.size() > TRANSCRIPT_CAP) {
                transcript.removeFirst();
            }
        }
    }

    private static String stripCodes(String s) {
        return s.replaceAll("§.", "");
    }
}
