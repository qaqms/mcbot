package com.neko.mcbot.task;

import com.neko.mcbot.McbotMod;
import com.neko.mcbot.body.CompanionPlayer;
import com.neko.mcbot.server.ServerTool;
import net.minecraft.server.level.ServerPlayer;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * 每同伴一个活跃任务槽（v1 不做队列；模型串行思考，并发任务留给链式组合）。
 * 服务器 END_SERVER_TICK 驱动；完成/超时都通过 future 交还给工具层回 tool_result。
 */
public final class CompanionScheduler {

    /** 工具没覆写 {@link ServerTool#capTicks} 时的兜底帽：1200 tick = 60s。 */
    public static final int DEFAULT_CAP_TICKS = 1200;

    private final Map<UUID, Slot> active = new HashMap<>();

    private record Slot(CompanionPlayer companion, TickTask task,
                        CompletableFuture<ServerTool.Result> future, int capTicks) {
    }

    public boolean submit(CompanionPlayer companion, TickTask task,
                          CompletableFuture<ServerTool.Result> future, int capTicks) {
        if (active.containsKey(companion.getUUID())) {
            return false; // 同伴正忙——工具层应回执 BUSY 教学
        }
        active.put(companion.getUUID(),
                new Slot(companion, task, future, capTicks <= 0 ? DEFAULT_CAP_TICKS : capTicks));
        return true;
    }

    public boolean busy(UUID companionId) {
        return active.containsKey(companionId);
    }

    public void cancelAll(String reason) {
        for (UUID uuid : new HashMap<>(active).keySet()) cancel(uuid, reason);
    }

    /**
     * 主人叫停：中止该同伴的活跃任务（先走 onAbort 收尾），
     * future 以 CANCELLED 教学回执完成——顺带把 tool_result 送回客户端。
     * 返回 false 表示它本来就没在干活。仅服务器线程调用。
     */
    public boolean cancel(UUID companionId, String reason) {
        Slot slot = active.remove(companionId);
        if (slot == null) {
            return false;
        }
        try {
            slot.task().onAbort();
        } catch (RuntimeException ignored) {
        }
        String why = reason == null || reason.isBlank()
                ? "主人主动叫停了这件事。" : reason;
        slot.future().complete(new ServerTool.Result(false,
                "CANCELLED:" + why + "别自作主张续上，等主人的下一步指示。", null));
        return true;
    }

    /** 服务器主线程 tick。 */
    public void tick(net.minecraft.server.MinecraftServer server) {
        if (active.isEmpty()) {
            return;
        }
        for (Slot slot : new HashMap<>(active).values()) {
            ServerPlayer fresh = server.getPlayerList().getPlayer(slot.companion().getUUID());
            if (!(fresh instanceof CompanionPlayer cp)) {
                active.remove(slot.companion().getUUID());
                slot.future().complete(new ServerTool.Result(false,
                        "TARGET_LOST:同伴已不在场（可能被遣散），这个任务作废。", null));
                continue;
            }
            TickTask.Progress p;
            try {
                p = slot.task().tickSafe(cp);
            } catch (Throwable t) {
                McbotMod.LOG.error("任务 tick 异常", t);
                active.remove(cp.getUUID());
                slot.future().complete(new ServerTool.Result(false,
                        "INTERNAL:任务执行异常 " + t.getClass().getSimpleName(), null));
                continue;
            }
            if (p instanceof TickTask.Progress.Done d) {
                active.remove(cp.getUUID());
                slot.future().complete(d.result());
            } else if (slot.task().age > slot.capTicks()) {
                active.remove(cp.getUUID());
                try {
                    slot.task().onAbort();
                } catch (RuntimeException ignored) {
                }
                // 文案必须**按实际帽算**：这里原来硬写"60 秒"，而 move_to 的帽是 3600tick=180s，
                // 于是模型看到的是"60 秒没做完"、实际等了 3 分钟——一句自相矛盾的读数会把
                // "到底该不该重试"的判断带偏。（效率评估 §5.6 点名的那处。）
                slot.future().complete(new ServerTool.Result(false,
                        "TIMEOUT:这件事 " + Math.max(1, slot.capTicks() / 20) + " 秒没做完，我停手了。"
                                + "要不要换个做法？", null));
            }
        }
    }
}
