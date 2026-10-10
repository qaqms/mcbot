package com.neko.mcbot.task;

import com.neko.mcbot.McbotMod;
import com.neko.mcbot.body.CompanionPlayer;
import com.neko.mcbot.server.ServerTool;
import net.minecraft.server.level.ServerPlayer;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.List;
import java.util.function.Supplier;

/**
 * 每同伴一个活跃任务槽（v1 不做队列；模型串行思考，并发任务留给链式组合）。
 * 服务器 END_SERVER_TICK 驱动；完成/超时都通过 future 交还给工具层回 tool_result。
 */
public final class CompanionScheduler {

    /** 工具没覆写 {@link ServerTool#capTicks} 时的兜底帽：1200 tick = 60s。 */
    public static final int DEFAULT_CAP_TICKS = 1200;

    private final Map<UUID, Slot> active = new HashMap<>();
    private final ResourceLocks locks = new ResourceLocks();
    private final Map<UUID, ResourceLocks.Lease> leases = new HashMap<>();
    private final Map<UUID, CompletableFuture<ServerTool.Result>> calls = new HashMap<>();

    private record Slot(UUID id, CompanionPlayer companion, TickTask task,
                        CompletableFuture<ServerTool.Result> future, int capTicks,
                        net.minecraft.server.level.ServerLevel level) {
    }

    public boolean submit(CompanionPlayer companion, TickTask task,
                          CompletableFuture<ServerTool.Result> future, int capTicks) {
        return submit(companion.getUUID(), companion, task, future, capTicks);
    }

    boolean submit(UUID id, CompanionPlayer companion, TickTask task,
                   CompletableFuture<ServerTool.Result> future, int capTicks) {
        if (active.containsKey(id)) {
            return false; // 同伴正忙——工具层应回执 BUSY 教学
        }
        active.put(id,
                new Slot(id, companion, task, future, capTicks <= 0 ? DEFAULT_CAP_TICKS : capTicks,
                        companion == null ? null : companion.level()));
        return true;
    }

    public CompletableFuture<ServerTool.Result> execute(UUID companion, List<ResourceLocks.Region> regions,
                                                       Supplier<CompletableFuture<ServerTool.Result>> action) {
        if (busy(companion)) return completed("BUSY:身体正忙，等待终态或取消后再执行。");
        ResourceLocks.Lease lease = locks.acquire(companion, regions);
        if (lease == null) return completed("BUSY:身体或目标区域被其他动作占用，等待或取消后再执行。");
        leases.put(companion, lease);
        try {
            CompletableFuture<ServerTool.Result> future = java.util.Objects.requireNonNull(action.get());
            calls.put(companion, future);
            return future.whenComplete((result, failure) -> {
                Slot slot = active.get(companion);
                if (slot != null && slot.future() == future) {
                    finish(slot, result == null ? new ServerTool.Result(false,
                            "INTERNAL:工具异常结束，已停手。", null) : result, true);
                }
                if (calls.get(companion) == future) calls.remove(companion);
                if (leases.get(companion) == lease) leases.remove(companion);
                lease.close();
            });
        } catch (Throwable failure) {
            cancel(companion, "动作派发异常，已中止。");
            if (leases.get(companion) == lease) leases.remove(companion);
            lease.close();
            throw failure;
        }
    }

    public boolean reserve(UUID companion, List<ResourceLocks.Region> regions) {
        ResourceLocks.Lease lease = leases.get(companion);
        return lease != null && lease.extend(regions);
    }

    public boolean reserveTarget(UUID companion, ResourceLocks.Target target) {
        ResourceLocks.Lease lease = leases.get(companion);
        return lease != null && lease.claim(target);
    }

    private static CompletableFuture<ServerTool.Result> completed(String feedback) {
        return CompletableFuture.completedFuture(new ServerTool.Result(false, feedback, null));
    }

    public boolean busy(UUID companionId) {
        return active.containsKey(companionId);
    }

    public com.google.gson.JsonObject observation(UUID companionId) {
        Slot slot = active.get(companionId);
        var data = new com.google.gson.JsonObject();
        data.addProperty("busy", slot != null);
        if (slot != null) {
            data.addProperty("elapsed_ticks", slot.task().age);
            data.addProperty("cap_ticks", slot.capTicks());
            data.add("progress", slot.task().observation());
        }
        return data;
    }

    public void cancelAll(String reason) {
        var ids = new java.util.HashSet<>(active.keySet());
        ids.addAll(calls.keySet());
        for (UUID uuid : ids) cancel(uuid, reason);
    }

    /**
     * 主人叫停：中止该同伴的活跃任务（先走 onAbort 收尾），
     * future 以 CANCELLED 教学回执完成——顺带把 tool_result 送回客户端。
     * 返回 false 表示它本来就没在干活。仅服务器线程调用。
     */
    public boolean cancel(UUID companionId, String reason) {
        Slot slot = active.get(companionId);
        String why = reason == null || reason.isBlank()
                ? "主人主动叫停了这件事。" : reason;
        ServerTool.Result result = new ServerTool.Result(false,
                "CANCELLED:" + why + "别自作主张续上，等主人的下一步指示。", null);
        if (slot != null) finish(slot, result, true);
        else {
            var future = calls.remove(companionId);
            if (future == null) return false;
            ResourceLocks.Lease lease = leases.remove(companionId);
            if (lease != null) lease.close();
            future.complete(result);
        }
        return true;
    }

    /** 服务器主线程 tick。 */
    public void tick(net.minecraft.server.MinecraftServer server) {
        tick(new Bodies() {
            @Override public CompanionPlayer get(UUID id) {
                ServerPlayer player = server.getPlayerList().getPlayer(id);
                return player instanceof CompanionPlayer cp ? cp : null;
            }
            @Override public boolean same(CompanionPlayer expected, CompanionPlayer fresh) {
                return fresh != null && fresh == expected;
            }
        });
    }

    interface Bodies {
        CompanionPlayer get(UUID id);
        boolean same(CompanionPlayer expected, CompanionPlayer fresh);
        default boolean sameDimension(CompanionPlayer fresh, net.minecraft.server.level.ServerLevel expected) {
            return fresh == null ? expected == null : fresh.level() == expected;
        }
    }

    void tick(Bodies bodies) {
        if (active.isEmpty()) {
            return;
        }
        for (Slot slot : new HashMap<>(active).values()) {
            if (active.get(slot.id()) != slot) continue;
            TickTask.Progress p;
            try {
                CompanionPlayer cp = bodies.get(slot.id());
                if (!bodies.same(slot.companion(), cp) || !bodies.sameDimension(cp, slot.level())) {
                    finish(slot, new ServerTool.Result(false,
                            "TARGET_LOST:原身体已不在场、已被替换或已换维度，这个任务作废。", null), true);
                    continue;
                }
                p = slot.task().tickSafe(cp);
            } catch (Throwable t) {
                McbotMod.LOG.error("任务 tick 异常", t);
                finish(slot, new ServerTool.Result(false,
                        "INTERNAL:任务执行异常 " + t.getClass().getSimpleName(), null), true);
                continue;
            }
            if (p instanceof TickTask.Progress.Done d) {
                finish(slot, d.result() == null
                        ? new ServerTool.Result(false, "INTERNAL:任务没有返回结果。", null) : d.result(), false);
            } else if (slot.task().age >= slot.capTicks()) {
                // 文案必须**按实际帽算**：这里原来硬写"60 秒"，而 move_to 的帽是 3600tick=180s，
                // 于是模型看到的是"60 秒没做完"、实际等了 3 分钟——一句自相矛盾的读数会把
                // "到底该不该重试"的判断带偏。（效率评估 §5.6 点名的那处。）
                finish(slot, new ServerTool.Result(false,
                        "TIMEOUT:这件事 " + Math.max(1, slot.capTicks() / 20) + " 秒没做完，我停手了。"
                                + "要不要换个做法？", null), true);
            }
        }
    }

    private void finish(Slot slot, ServerTool.Result result, boolean aborted) {
        if (active.get(slot.id()) != slot) return;
        active.remove(slot.id());
        try {
            if (aborted) {
                try {
                    ServerTool.Result partial = slot.task().interruptedResult(result);
                    if (partial != null) result = partial;
                } finally {
                    slot.task().onAbort();
                }
            } else slot.task().onFinish();
        } catch (Throwable failure) {
            McbotMod.LOG.error("任务收尾异常", failure);
        } finally {
            ResourceLocks.Lease lease = leases.remove(slot.id());
            if (lease != null) lease.close();
            slot.future().complete(result);
        }
    }
}
