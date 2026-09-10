package com.neko.mcbot.agent;

import com.google.gson.JsonObject;
import com.neko.mcbot.agentcore.llm.LlmClient;
import com.neko.mcbot.agentcore.loop.AgentLoop;
import com.neko.mcbot.agentcore.loop.PendingJobs;
import com.neko.mcbot.agentcore.loop.ToolExecutor;
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

    private ClientConfig cfg;
    private final AtomicLong seqGen = new AtomicLong();
    private final AtomicLong taskSeq = new AtomicLong();
    /**
     * 两段式等待记账（R2-S4）：seq → 终局回执；受理后转成 jobId → job 事件。
     * 凭据就是那个 future，转段时跟着一起搬（见 {@link PendingJobs}）。
     */
    private final PendingJobs<CompletableFuture<ToolOutcome>> pending = new PendingJobs<>();
    /** mcbot_ask 排队等作答的桥（onReply 按序喂给最早的问题）。 */
    private final ArrayDeque<CompletableFuture<String>> askWaiters = new ArrayDeque<>();
    private final ArrayDeque<String> transcript = new ArrayDeque<>();
    private volatile long currentTask;
    private volatile String companionName = "";
    /** 迟到回执计数（效率评估 §6 的直接证据）：>0 说明超时帽配错、真结果被扔。 */
    private final AtomicLong lateResults = new AtomicLong();
    /** PARK 观测：受理之后链挂起、等 job 事件的数量（面板/桥可见）。 */
    private volatile boolean parked;
    private volatile int parkedJobs;
    private AgentLoop loop;
    /** R2-B 前缀缓存：启动读盘一次，链中不再逐步读 skills；换发只在指令边界。 */
    private PromptBuilder promptCache;

    public AgentRunner(ClientConfig cfg) {
        this.cfg = cfg;
    }

    /** 进世界后调用一次；重复调用安全。 */
    public void start() {
        if (!cfg.brainEnabled || loop != null) {
            return;
        }
        var engine = new LlmClient(
                new com.neko.mcbot.agentcore.provider.OpenAiCompatProvider(
                        "mcbot", cfg.baseUrl, cfg.apiKey, cfg.model),
                java.time.Duration.ofSeconds(180));
        // R2-B：prefix 缓存也接上了——启动读一次盘，指令边界才允许换发（链中不换=不裂前缀）。
        promptCache = new PromptBuilder(() -> PromptBuilder.build(cfg.persona, SkillLoader.load(
                FabricLoader.getInstance().getGameDir().resolve("mcbot").resolve("skills"))));
        loop = new AgentLoop(engine, ClientToolDefs.SPECS, this, AgentLoop.Config.defaults(),
                new AgentLoop.Listener() {
                    @Override
                    public void onReply(String text) {
                        say("§b[同伴] §r" + text);
                        synchronized (askWaiters) {
                            if (!askWaiters.isEmpty()) {
                                askWaiters.pollFirst().complete(text);
                            }
                        }
                        JsonObject d = new JsonObject();
                        d.addProperty("text", text);
                        emit("done", d);
                    }

                    @Override
                    public void onToolInvoked(String name, String argsJson, boolean ok, String feedback) {
                        LOG.info("tool {} -> {} {}", name, ok ? "✔" : "✘", feedback);
                        JsonObject d = new JsonObject();
                        d.addProperty("text", name + (ok ? " ✔ " : " ✘ ") + feedback);
                        d.addProperty("tool", name);
                        d.addProperty("ok", ok);
                        emit("progress", d);
                    }

                    @Override
                    public void onNotice(String text) {
                        LOG.info("loop notice: {}", text);
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
                        // ttft 大而 ttfb 小 → 中转站在攒批；first_tool 远早于整轮结束 → 早派发真省了时间。
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
                () -> promptCache.get(),
                // 压缩闸门：6000 真 token（API 数优先，本地 CJK 感知估算兜底）。
                // 参考实测：固定开销≈350，每步只追加；这个数给中转站通道留了延迟余地。
                promptCache,
                6_000);
        LOG.info("大脑已上线：{} / {}", cfg.baseUrl, cfg.model);
    }

    /** 面板"保存并应用"：落盘配置并重建大脑（对话历史清零属预期；悬着的问题就地终止）。 */
    public void reconfigure(ClientConfig newCfg) {
        ClientConfig.save(newCfg);
        cfg = newCfg;
        loop = null;
        for (var q : questionRecords.values()) {
            q.future.complete(new ToolOutcome(false, "INTERNAL:大脑热重启，这个问题作废。"));
        }
        questionRecords.clear();
        synchronized (askWaiters) {
            for (var f : askWaiters) {
                f.completeExceptionally(new IllegalStateException("大脑热重启"));
            }
            askWaiters.clear();
        }
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

    public List<String> transcriptSnapshot() {
        synchronized (transcript) {
            return new ArrayList<>(transcript);
        }
    }

    public void handleS2c(Envelope env) {
        switch (env.kind()) {
            case "tool_result" -> {
                long seq = env.num("seq", -1);
                var p = pending.takeTool(seq);
                if (p != null) {
                    p.ticket().complete(new ToolOutcome(env.bool("ok"), env.str("feedback")));
                } else {
                    // 迟到的回执（本 seq 已被超时/叫停收走）：语义上只能丢弃，但**必须留痕**——
                    // 这正是"客户端超时 < 服务端帽"那类缺陷唯一的直接证据。
                    // 该计数持续 >0 就说明超时帽配错了，工具的活其实干完了、回执却被扔了。
                    lateResults.incrementAndGet();
                    LOG.warn("[brain] 迟到回执 seq={} ok={}（已被超时收走，丢弃）feedback={}",
                            seq, env.bool("ok"), env.str("feedback"));
                }
            }
            case "job_ack" -> onJobAck(env);
            case "job_event" -> onJobEvent(env);
            case "event" -> {
                say("§7[同伴] " + env.str("text") + "§r");
                emitState(env.str("text"));
            }
            default -> {
                String text = env.body().has("text") ? env.str("text") : env.kind();
                say("§7[mcbot] " + text + "§r");
                var m = java.util.regex.Pattern.compile("同伴 (\\w+) 出现了").matcher(text);
                if (m.find()) {
                    companionName = m.group(1);
                } else if (text.contains("离开了")) {
                    companionName = "";
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
        var j = pending.acceptAsJob(seq, jobId, capTicks, System.currentTimeMillis());
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
        if ("progress".equals(phase)) {
            // 进度帧不进对话（模型不需要、也看不懂"第 3/7 格"），只喂给桥与面板
            JsonObject d = new JsonObject();
            d.addProperty("text", text);
            d.addProperty("job_id", jobId);
            d.addProperty("tool", env.str("tool"));
            emit("progress", d);
            return;
        }
        var j = pending.takeJob(jobId);
        if (j == null) {
            // 正常：叫停/换发时大脑已经本地补过合成回执，这条真结果就该被丢掉。
            LOG.info("[brain] job {} 事件 phase={} 无人认领（已被叫停/顶替收走）", jobId, phase);
            return;
        }
        LOG.info("[brain] job {} {} phase={} {}ms", jobId, j.tool(), phase,
                System.currentTimeMillis() - j.acceptedAtMs());
        boolean ok = "done".equals(phase);
        if (loop != null) {
            loop.onJobEvent(jobId, new ToolOutcome(ok, text));
        }
    }

    /** 面板召唤/遣散按钮：与 /mcbot 命令同权限（服务器按发送者校验 owner）。 */
    public void sendLifecycle(String kind, String name) {
        JsonObject body = new JsonObject();
        body.addProperty("name", name);
        ClientPlayNetworking.send(new McbotPayloads.C2s(new Envelope(kind, body).encode()));
    }
    /** 主线程周期调用：工具/长活/问题超时兜底。 */
    public void tick() {
        long now = System.currentTimeMillis();
        for (var t : pending.sweepTools(now)) {
            t.ticket().complete(new ToolOutcome(false,
                    "TIMEOUT:这次操作 " + (t.timeoutMs() / 1000)
                            + " 秒没有结果，先别重复这个操作，向主人说明情况。"));
        }
        for (var j : pending.sweepJobs(now)) {
            // 长活超时必须**同样交给大脑**：不交的话 PARK 就永远解不开，整条链静默卡死
            // （比收到一条 TIMEOUT 教学坏得多——模型连"我卡住了"都看不到）。
            LOG.warn("[brain] job {} 本地等满 {}s 仍未收到事件，按超时解锁 PARK",
                    j.jobId(), j.timeoutMs() / 1000);
            if (loop != null) {
                loop.onJobEvent(j.jobId(), new ToolOutcome(false,
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

        QuestionRecord(CompletableFuture<ToolOutcome> f) {
            this.future = f;
            this.at = System.currentTimeMillis();
        }
    }

    private static final java.util.regex.Pattern CANCEL_WORDS = java.util.regex.Pattern
            .compile("(停|停下|停手|取消|别干了|别做了|cancel|stop)", java.util.regex.Pattern.CASE_INSENSITIVE);

    // 游戏内回答同伴反问："答 <文本>" 或 "answer <文本>"
    private static final java.util.regex.Pattern ANSWER_DIRECTIVE = java.util.regex.Pattern
            .compile("^(答|answer)[：: ]+(.+)$", java.util.regex.Pattern.CASE_INSENSITIVE | java.util.regex.Pattern.DOTALL);

    /** 主人指令统一入口（聊天/桥共用）。返回分配的 task_id。 */
    public long submitTask(String text) {
        long id = taskSeq.incrementAndGet();
        currentTask = id;
        record("§9我> §r" + text);   // 面板回看只记主人原话：注入是给模型的上下文，不是主人说过的话
        if (loop == null) {
            say("§c[mcbot] 大脑未配置：打开面板（默认 G）填 base_url/model/api_key 后保存。§r");
            emitState("大脑未配置，指令未执行");
            return id;
        }
        // R2-D 准星注入：把“主人刚说这句话时正盯着什么”拼到 user 文本尾。
        // 读不到（未进世界/准星空/跨线程异常）就吐个空串，“没得看”不能变成“看不看得到都要”的噪声。
        String hint = crosshairHint();
        loop.submit(hint.isEmpty() ? text : text + "\n" + hint);
        return id;
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
     * <p>线程：{@code /v1/task} 是在 HTTP 线程上直接调 {@code submitTask} 的，而 hitResult/客户端
     * level 由渲染线程维护。这里只读不写，且 {@code HitResult}/{@code BlockPos}/{@code BlockState}
     * 字段都是不可变的；一旦真碰上正在拆除的 level（退世界瞬间等），统一吃掉异常当成“没得看”。
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
            // 没有在等的问题：当普通指令走，但提醒一句免得主人以为回答了空气
            say("§7[mcbot] 同伴现在没有在等回答的问题，这句当新指令处理了。§r");
        }
        submitTask(text);
    }

    /** 叫停：服务端中止进行中的任务（回执会作为 CANCELLED 流回大脑），本地链在下一个边界停。 */
    public void requestCancel() {
        if (ClientPlayNetworking.canSend(McbotPayloads.C2s.TYPE)) {
            ClientPlayNetworking.send(
                    new McbotPayloads.C2s(new Envelope("cancel", new JsonObject()).encode()));
        }
        if (loop != null) {
            loop.cancelDirective();
        }
        emitState("主人叫停了当前任务");
    }

    /** 桥接 mcbot_ask：排队等大脑的下一次作答。 */
    public CompletableFuture<String> askNext(String text) {
        CompletableFuture<String> f = new CompletableFuture<>();
        synchronized (askWaiters) {
            askWaiters.addLast(f);
        }
        submitTask(text);
        return f;
    }

    /** 回答同伴的 ask_owner 反问。qid 形如 "q12"。 */
    public boolean answerQuestion(String qid, String text) {
        if (qid == null || !qid.startsWith("q")) {
            return false;
        }
        try {
            long seq = Long.parseLong(qid.substring(1));
            QuestionRecord rec = questionRecords.remove(seq);
            if (rec == null) {
                return false;
            }
            if (latestQuestion != null && latestQuestion == seq) {
                latestQuestion = null;
            }
            rec.future.complete(new ToolOutcome(true, "主人说：" + text));
            JsonObject d = new JsonObject();
            d.addProperty("text", "主人已回答 " + qid);
            emit("state", d);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private final Map<Long, QuestionRecord> questionRecords = new ConcurrentHashMap<>();

    /** 桥状态探针（GET /v1/status）。字段全部 volatile/并发安全，可跨线程读。 */
    public String statusJson() {
        JsonObject o = new JsonObject();
        o.addProperty("in_game", Minecraft.getInstance().level != null);
        o.addProperty("brain_enabled", cfg != null && cfg.brainEnabled);
        o.addProperty("model", cfg == null ? "" : cfg.model);
        o.addProperty("companion", companionName);
        o.addProperty("current_task", currentTask);
        o.addProperty("pending_tools", pending.pendingTools());
        // R2-S4：长活是"第二段等待"，它和普通工具待办必须分开看——
        // 合在一起数的话，"卡在工具上"和"正常在等一条 3 分钟的移动"分不出来。
        o.addProperty("pending_jobs", pending.pendingJobs());
        o.addProperty("parked", parked);
        o.addProperty("parked_jobs", parkedJobs);
        o.addProperty("accept_mode", cfg == null || cfg.acceptMode);
        o.addProperty("pending_questions", questionRecords.size());
        // 效率诊断：迟到回执数（>0 即超时帽配错，真结果被丢弃）；桥的 /v1/status 直接可见
        o.addProperty("late_results", lateResults.get());
        return o.toString();
    }

    @Override
    public CompletableFuture<ToolOutcome> execute(String name, String argsJson) {
        if ("ask_owner".equals(name)) {
            return askOwner(argsJson);
        }
        if (!ClientPlayNetworking.canSend(McbotPayloads.C2s.TYPE)) {
            return CompletableFuture.completedFuture(
                    new ToolOutcome(false, "DENIED:尚未连上装有 mcbot 的服务器。"));
        }
        long seq = seqGen.incrementAndGet();
        JsonObject body = new JsonObject();
        body.addProperty("seq", (int) seq);
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
        pending.registerTool(seq, name, System.currentTimeMillis(), toolTimeoutMs(name, argsJson), f);
        ClientPlayNetworking.send(new McbotPayloads.C2s(json));
        return f;
    }

    /** 本地工具：问题进事件流（question 帧），答案从 answerQuestion 回来；5 分钟无回复超时。 */
    private CompletableFuture<ToolOutcome> askOwner(String argsJson) {
        long seq = seqGen.incrementAndGet();
        CompletableFuture<ToolOutcome> f = new CompletableFuture<>();
        questionRecords.put(seq, new QuestionRecord(f));
        String text;
        try {
            var el = com.google.gson.JsonParser.parseString(
                    argsJson == null || argsJson.isBlank() ? "{}" : argsJson);
            text = el.isJsonObject() && el.getAsJsonObject().has("text")
                    ? el.getAsJsonObject().get("text").getAsString() : String.valueOf(argsJson);
        } catch (RuntimeException e) {
            text = String.valueOf(argsJson);
        }
        say("§d[同伴想问] §r" + text + " §7（回答：@bot 答 <文本>）§r");
        latestQuestion = seq;
        JsonObject d = new JsonObject();
        d.addProperty("question_id", "q" + seq);
        d.addProperty("text", text);
        emit("question", d);
        return f;
    }

    /** 问题超时巡检（客户端主线程 tick 里调）。 */
    private void sweepQuestionTimeouts() {
        long now = System.currentTimeMillis();
        questionRecords.entrySet().removeIf(e -> {
            if (now - e.getValue().at > QUESTION_TIMEOUT_MS) {
                if (latestQuestion != null && latestQuestion == e.getKey()) {
                    latestQuestion = null; // 超时作废，别留悬指针
                }
                e.getValue().future.complete(new ToolOutcome(false,
                        "TIMEOUT:主人 2 分钟没回你的问题。按最稳妥的理解自行定夺，或向主人说明你在等什么。"));
                return true;
            }
            return false;
        });
    }

    private void emitState(String text) {
        JsonObject d = new JsonObject();
        d.addProperty("text", text);
        emit("state", d);
    }

    private void emit(String kind, JsonObject data) {
        data.addProperty("ev", kind);
        data.addProperty("task_id", currentTask);
        BridgeEvents.publish(kind, data);
    }

    private void say(String text) {
        record(text);
        // 模型偶尔把换行写成字面量 \n，聊天显示前归一化
        String clean = stripCodes(text).replace("\\r", "").replace("\\n", "\n");
        // loop 回调运行在 HttpClient 线程上；GUI 一律弹回渲染线程（否则 Rendersystem 炸）
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> {
            if (mc.gui != null) {
                mc.gui.getChat().addMessage(Component.literal(clean));
            }
        });
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
