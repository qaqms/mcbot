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

        ServerTool.Result r;
        long t0 = System.nanoTime();
        try {
            tool.runAsync(companion, args, McbotMod.scheduler())
                    .whenComplete((res, err) -> {
                        if (err != null) {
                            // 工具层以异常完成：按原语义交回一条 INTERNAL，别让 owner 白等 90s。
                            // （此前这条路径只在 runAsync **同步抛**时才走到，异步异常会静默丢失，
                            //   客户端挂到 TIMEOUT；顺手补上，属于同一处语义缺口。）
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
        } catch (Throwable t) {
            McbotMod.LOG.error("工具 {} 派发异常", toolName, t);
            r = new ServerTool.Result(false, "INTERNAL:工具内部错误: " + t.getClass().getSimpleName(), null);
        }
        logToolTiming(toolName, t0, -1);
        replyTool(sender, seq, r.ok(), r.feedback(), r.data());
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

    private void broadcast(ServerPlayer owner, String kind, Object payload) {
        JsonObject body = new JsonObject();
        if (payload instanceof Integer i) {
            body.addProperty("v", i);
        } else {
            body.addProperty("text", String.valueOf(payload));
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
        small.addProperty("ok", false);
        small.addProperty("feedback", "INTERNAL:回执过大（" + originalBytes
                + "B，上限 " + WireSize.MAX_BODY_BYTES + "B），详细数据已丢弃。"
                + "请把这次请求范围改小（如缩小扫描半径、分批取数）后重试。");
        return new Envelope(kind, small).encode();
    }
}
