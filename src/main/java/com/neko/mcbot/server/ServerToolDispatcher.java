package com.neko.mcbot.server;

import com.google.gson.JsonObject;
import com.neko.mcbot.McbotMod;
import com.neko.mcbot.body.CompanionPlayer;
import com.neko.mcbot.body.CompanionRoster;
import com.neko.mcbot.body.SummonService;
import com.neko.mcbot.common.Envelope;
import com.neko.mcbot.common.McbotPayloads;
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

    /** 闸①尺寸校验在 StreamCodec readUtf 已兜底；这里闸②③+执行。 */
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
        try {
            tool.runAsync(companion, args, McbotMod.scheduler())
                    .thenAccept(res -> replyTool(sender, seq, res.ok(), res.feedback(), res.data()));
            return;
        } catch (Throwable t) {
            McbotMod.LOG.error("工具 {} 派发异常", toolName, t);
            r = new ServerTool.Result(false, "INTERNAL:工具内部错误: " + t.getClass().getSimpleName(), null);
        }
        replyTool(sender, seq, r.ok(), r.feedback(), r.data());
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
        if (json.length() > Envelope.MAX_BYTES) {
            McbotMod.LOG.warn("S2C 信封超限({}B)，截断 kind={}", json.length(), kind);
            json = json.substring(0, Envelope.MAX_BYTES);
        }
        McbotMod.LOG.info("[m3] S2C -> {} {} ({}B)", owner.getGameProfile().name(), kind, json.length());
        net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.send(owner,
                new McbotPayloads.S2c(json));
    }
}
