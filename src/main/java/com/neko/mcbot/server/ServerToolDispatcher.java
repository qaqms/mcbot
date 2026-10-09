package com.neko.mcbot.server;

import com.google.gson.JsonObject;
import com.neko.mcbot.McbotMod;
import com.neko.mcbot.body.CompanionPlayer;
import com.neko.mcbot.body.CompanionRoster;
import com.neko.mcbot.body.SummonService;
import com.neko.mcbot.common.Envelope;
import com.neko.mcbot.common.McbotPayloads;
import com.neko.mcbot.common.WireSize;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;

import java.util.concurrent.CompletableFuture;

/**
 * C2S 信封处理：三道闸 → 工具执行 → S2C 回执。
 * 调用方（payload receiver）保证已在服务器线程。
 */
public final class ServerToolDispatcher {

    private final MinecraftServer server;
    private final SummonService summon;
    private final ToolRegistry registry;
    private final RateGuard rate = new RateGuard(20, 60);

    public ServerToolDispatcher(MinecraftServer server, SummonService summon, ToolRegistry registry) {
        this.server = server;
        this.summon = summon;
        this.registry = registry;
    }

    public boolean belongsTo(MinecraftServer candidate) {
        return server == candidate;
    }

    /**
     * 三道闸的②③＋执行在这里；闸①（尺寸）在 {@code McbotPayloads.C2s.CODEC} 里已完成，
     * 超限包根本进不到这个方法。
     */
    public void handle(ServerPlayer sender, Envelope env) {
        var profile = sender.getGameProfile();

        if (!rate.tryAcquire(profile.id())) {
            replyTool(sender, env.num("seq", -1), false, "DENIED:消息过于频繁，稍后再试。", null);
            return;
        }

        switch (env.kind()) {
            case "summon" -> {
                String result = summon.summon(sender, env.str("name"));
                broadcast(sender, "summon_result", result);
            }
            case "dismiss" -> {
                String result = summon.dismiss(sender, env.str("name"));
                broadcast(sender, "dismiss_result", result);
            }
            case "companion_status" -> {
                ServerPlayer companion = summon.companionOf(sender.getUUID());
                broadcast(sender, "companion_state", companion == null
                        ? "当前世界尚未召唤伙伴。" : "当前世界伙伴：" + companion.getGameProfile().name());
            }
            case "tool_call" -> handleToolCall(sender, env);
            case "cancel" -> {
                CompanionPlayer cp = companionOfSender(sender);
                if (cp == null) {
                    broadcast(sender, "cancel_ack", "你名下没有同伴，无从叫停。");
                } else {
                    boolean stopped = McbotMod.scheduler().cancel(cp.getUUID(), null);
                    broadcast(sender, "cancel_ack", stopped
                            ? "已叫停 " + cp.getGameProfile().name() + " 手头的活。"
                            : "它现在手头没有进行中的任务。");
                }
            }
            case "answer" -> McbotMod.LOG.info("(M3 暂存)主人对 {} 的回答: {}",
                    env.str("question_id"), env.str("text"));
            default -> McbotMod.LOG.warn("未知信封 kind={} (from {})", env.kind(), profile.name());
        }
    }

    private void handleToolCall(ServerPlayer sender, Envelope env) {
        int seq = env.num("seq", -1);
        String toolName = env.str("tool");

        // 闸③a：白名单
        ServerTool tool = registry.get(toolName);
        if (tool == null) {
            replyTool(sender, seq, false, "DENIED:未知工具 " + toolName, null);
            return;
        }
        // 闸③b：args 必须是 JSON 对象
        if (env.body() == null || !env.body().has("args") || !env.body().get("args").isJsonObject()) {
            replyTool(sender, seq, false, "DENIED:args 必须是 JSON 对象", null);
            return;
        }
        JsonObject args = env.body().getAsJsonObject("args");

        // 闸③c：owner 强校验——只能驱动自己的同伴
        CompanionPlayer companion = companionOfSender(sender);
        if (companion == null) {
            replyTool(sender, seq, false,
                    "DENIED:你没有在册的同伴，先召唤一个（/mcbot summon <名字>）。", null);
            return;
        }

        // 受理即回执（R2-S4）：**客户端显式要求**才走 ACCEPT。
        // 缺省 false 是刻意的向后兼容——老客户端不认识 job_ack，若服务端擅自改形态，
        // 它那条 tool_call 会一直等到 90 秒超时。所以新增形态永远由发送方点名。
        boolean wantAccept = env.body().has("accept") && env.body().get("accept").getAsBoolean()
                && tool.acceptanceMode() == ServerTool.Acceptance.ACCEPT;

        long t0 = System.nanoTime();
        CompletableFuture<ServerTool.Result> fut;
        try {
            fut = tool.runAsync(companion, args, McbotMod.scheduler());
        } catch (Throwable t) {
            McbotMod.LOG.error("工具 {} 派发异常", toolName, t);
            logToolTiming(toolName, t0, -1);
            replyTool(sender, seq, false,
                    "INTERNAL:工具内部错误: " + t.getClass().getSimpleName(), null);
            return;
        }
        if (fut == null) {
            fut = CompletableFuture.completedFuture(
                    new ServerTool.Result(false, "INTERNAL:工具没有返回 future", null));
        }

        // 已经出结果了（DENIED/BUSY/TARGET_LOST 这些快路径，或 wait(0) 这类瞬时活）：
        // 直接当同步回执发回去。**"受理"只该给真的还要跑一会儿的活**——先 ack 再立刻报
        // 失败等于白多一跳，而且模型会以为"被打回了"和"跑完了"是两件事。
        if (!wantAccept || fut.isDone()) {
            fut.whenComplete((res, err) -> {
                if (err != null) {
                    // 工具层以异常完成：按原语义交回一条 INTERNAL，别让 owner 白等 90s。
                    McbotMod.LOG.error("工具 {} 异步失败", toolName, err);
                    replyTool(sender, seq, false, "INTERNAL:工具执行失败: "
                            + err.getClass().getSimpleName(), null);
                    logToolTiming(toolName, t0, -1);
                    return;
                }
                logToolTiming(toolName, t0, res == null ? -1 : res.feedback().length());
                replyTool(sender, seq, res.ok(), res.feedback(), res.data());
            });
            return;
        }

        // ACCEPT：先回"我受理了"，再等 future 落地补一条 job_event。
        // **单终局契约**：这个 seq 从此只走 job 通道，绝不会再有 tool_result——
        // 客户端收到 job_ack 就把 seq 那条待办转成 job 待办，两条都发会让它记两次账。
        String jobId = "j" + jobSeq.incrementAndGet();
        int cap = tool.capTicks(args);
        replyAck(sender, seq, jobId, toolName, acceptText(tool, args, jobId, cap), cap);
        McbotMod.LOG.info("[brain] job {} 受理 {} seq={} 帽={}tick", jobId, toolName, seq, cap);
        fut.whenComplete((res, err) -> {
            String phase;
            String text;
            JsonObject data = null;
            if (err != null) {
                McbotMod.LOG.error("工具 {} 受理后异步失败 job={}", toolName, jobId, err);
                phase = "failed";
                text = "INTERNAL:工具执行失败: " + err.getClass().getSimpleName();
            } else if (res == null) {
                phase = "failed";
                text = "INTERNAL:工具没有返回结果";
            } else {
                phase = phaseOf(res.ok(), res.feedback());
                text = res.feedback();
                data = res.data();
            }
            logToolTiming(toolName, t0, text == null ? -1 : text.length());
            McbotMod.LOG.info("[brain] job {} 结束 {} phase={}", jobId, toolName, phase);
            replyJobEvent(sender, seq, jobId, toolName, phase, text, data);
        });
    }

    private final java.util.concurrent.atomic.AtomicLong jobSeq = new java.util.concurrent.atomic.AtomicLong();

    /**
     * 受理回执的教学文案（**模板只此一处**）。
     *
     * <p>要教模型三件事：①这条还没有结果，别把它当结果；②别干等——它既不能改主意也不能
     * 催，白等一轮只是烧一次 prefill+decode；③做完会**主动**报出编号，所以"我该不该再问一次"
     * 这个问题根本不用问。这三句每个工具一字不差，模型才学一遍就够。
     *
     * <p>文本前缀是契约（{@code ACCEPTED:}），机器可读信号在回执的 outcome 字段里——
     * 用文本做控制流，任何一个工具的回执恰好这么开头就会误判。
     */
    public static String acceptText(ServerTool tool, JsonObject args, String jobId, int capTicks) {
        return com.neko.mcbot.agentcore.loop.ToolExecutor.ToolOutcome.ACCEPTED_PREFIX
                + "我已开始「" + tool.acceptSubject(args) + "」编号 " + jobId
                + "，最多约 " + Math.max(1, capTicks / 20) + " 秒。"
                + "这条还没有结果——别猜、别等着，可以先回我一句话或做别的，"
                + "做完我会主动报 " + jobId + "。";
    }

    /**
     * 终局相位。删掉前缀那一层包装，让模型看到的是**干净的结果或教学**，
     * 而相位是给客户端/面板做状态用的（{@code cancelled}/{@code superseded} 要能区分，
     * 因为前者是主人叫停、后者是被新指令顶掉，处理方式不同）。
     */
    public static String phaseOf(boolean ok, String feedback) {
        if (feedback != null) {
            if (feedback.startsWith("CANCELLED:")) {
                return "cancelled";
            }
            if (feedback.startsWith("SUPERSEDED:")) {
                return "superseded";
            }
        }
        return ok ? "done" : "failed";
    }

    /**
     * 每工具耗时归因（效率评估 §5.5）。
     *
     * <p>在这条日志之前，"模型慢"和"工具慢"是混在同一个总时长里的——`[m4]` 验收虽然打印过
     * "break_block 完成"，但那是自由文本、事后无法统计。有了这一行，
     * 真机曲线就能把每一秒归到具体工具上（例：`move_to` 慢到底慢在搜索还是走路，
     * 再看 `[path]` 的 expanded/elapsed 细分）。
     *
     * @param chars 回执字符数（-1 = 未拿到回执）；回执越长，模型那侧 prefill 越贵，
     *              所以它和时间一起记，才能解释"工具很快但这轮还是很慢"
     */
    private static void logToolTiming(String toolName, long t0, int chars) {
        long ms = (System.nanoTime() - t0) / 1_000_000L;
        McbotMod.LOG.info("[brain] tool {} {}ms chars={}", toolName, ms, chars);
    }

    private CompanionPlayer companionOfSender(ServerPlayer sender) {
        ServerPlayer p = summon.companionOf(sender.getUUID());
        if (p instanceof CompanionPlayer cp) {
            return cp;
        }
        return null;
    }

    private void replyTool(ServerPlayer owner, int seq, boolean ok, String feedback, JsonObject data) {
        JsonObject body = new JsonObject();
        body.addProperty("seq", seq);
        body.addProperty("ok", ok);
        body.addProperty("feedback", feedback);
        if (data != null) {
            body.add("data", data);
        }
        send(owner, "tool_result", body);
    }

    /** 受理回执：seq 这条待办就此定性（转成 job 通道），所以它自己就是一个"终局"。 */
    private void replyAck(ServerPlayer owner, int seq, String jobId, String tool, String text, int capTicks) {
        JsonObject body = new JsonObject();
        body.addProperty("seq", seq);
        body.addProperty("job_id", jobId);
        body.addProperty("tool", tool);
        body.addProperty("text", text);
        // cap 由服务端报出、客户端据此算等待上限：客户端的超时必须**严格大于**它，
        // 否则"客户端先跑了"会被误判成"服务端超时"（效率评估 §5.5 那条真缺陷的根因）。
        body.addProperty("cap_ticks", capTicks);
        send(owner, "job_ack", body);
    }

    /** 长活的后续：progress / done / failed / cancelled / superseded。 */
    private void replyJobEvent(ServerPlayer owner, int seq, String jobId, String tool,
                               String phase, String text, JsonObject data) {
        JsonObject body = new JsonObject();
        body.addProperty("seq", seq);
        body.addProperty("job_id", jobId);
        body.addProperty("tool", tool);
        body.addProperty("phase", phase);
        body.addProperty("text", text);
        if (data != null) {
            body.add("data", data);
        }
        send(owner, "job_event", body);
    }

    private void broadcast(ServerPlayer owner, String kind, Object payload) {
        JsonObject body = new JsonObject();
        if (payload instanceof Integer i) {
            body.addProperty("v", i);
        } else {
            body.addProperty("text", String.valueOf(payload));
        }
        if ("summon_result".equals(kind) || "dismiss_result".equals(kind)
                || "companion_state".equals(kind)) {
            ServerPlayer companion = summon.companionOf(owner.getUUID());
            body.addProperty("companion", companion == null ? "" : companion.getGameProfile().name());
        }
        send(owner, kind, body);
    }

    private void send(ServerPlayer owner, String kind, JsonObject body) {
        String json = new Envelope(kind, body).encode();
        // 闸①（出站）：按 UTF-8 字节量，不是字符数。旧写法用 json.length() 比较 32*1024，
        // 而本项目的回执几乎全是中文（一字三字节）：那等于把上限抬到了 ~96KB，
        // 正好越过原版入站 32767 字符/98301 字节的红线。
        int bytes = WireSize.utf8Bytes(json);
        if (bytes > WireSize.MAX_BODY_BYTES) {
            McbotMod.LOG.warn("S2C 信封超限({}B > {}B)，改发瘦身回执 kind={}",
                    bytes, WireSize.MAX_BODY_BYTES, kind);
            json = shrink(kind, body, bytes);
        }
        McbotMod.LOG.info("[m3] S2C -> {} {} ({}B)", owner.getGameProfile().name(), kind,
                WireSize.utf8Bytes(json));
        net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.send(owner,
                new McbotPayloads.S2c(json));
    }

    /**
     * 超限回执的堆：不是“截断到 32KB”，而是换一条合法的小信封。
     *
     * <p>为什么不截断：JSON 中途铲断只会让对端 {@code Envelope.decode} 解不动而静默丢弃，
     * 于是那个 {@code seq} 永远等不到回执，大脑要白等 90 秒才吐一条 TIMEOUT 教学——
     * 尺寸闸反而造成了一次难查的“卡住”。换成合法信封，并且**保留 seq**，
     * 才能立刻把“回执太大、换个小范请求”这件事教给模型。
     */
    private static String shrink(String kind, JsonObject body, int originalBytes) {
        JsonObject small = new JsonObject();
        if (body != null && body.has("seq")) {
            small.addProperty("seq", body.get("seq").getAsInt());
        }
        if (body != null && body.has("task_id")) {
            small.addProperty("task_id", body.get("task_id").getAsInt());
        }
        // R2-S4：job 通道的两条信封都要带上关联键，否则瘦身之后客户端认不出是哪条活——
        // 那会变成"PARK 永远等不到解锁"的假死（比超限本身更难查）。
        if (body != null && body.has("job_id")) {
            small.addProperty("job_id", body.get("job_id").getAsString());
        }
        if (body != null && body.has("phase")) {
            small.addProperty("phase", body.get("phase").getAsString());
        }
        if (body != null && body.has("cap_ticks")) {
            small.addProperty("cap_ticks", body.get("cap_ticks").getAsInt());
        }
        small.addProperty("ok", false);
        if ("job_ack".equals(kind)) {
            // 瘦身版受理回执：仍然必须以 ACCEPTED: 开头，否则模型会把它当失败去重试。
            small.addProperty("text", com.neko.mcbot.agentcore.loop.ToolExecutor.ToolOutcome
                    .ACCEPTED_PREFIX + "我受理了这件事（详细文案过大已省略），做完我会主动报 "
                    + (body != null && body.has("job_id") ? body.get("job_id").getAsString() : "") + "。");
            return new Envelope(kind, small).encode();
        }
        small.addProperty("feedback", "INTERNAL:回执过大（" + originalBytes
                + "B，上限 " + WireSize.MAX_BODY_BYTES + "B），详细数据已丢弃。"
                + "请把这次请求范围改小（如缩小扫描半径、分批取数）后重试。");
        return new Envelope(kind, small).encode();
    }
}
