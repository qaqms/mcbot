package com.neko.mcbot.server.tools;

import com.google.gson.JsonObject;
import com.neko.mcbot.body.CompanionPlayer;
import com.neko.mcbot.server.ActionPermissions;
import com.neko.mcbot.server.ConsumptionWork;
import com.neko.mcbot.server.NativeSupplyAccess;
import com.neko.mcbot.server.ServerTool;
import com.neko.mcbot.task.CompanionScheduler;

import java.util.concurrent.CompletableFuture;

/** A single selected food, not arbitrary right-click interaction or a nutrition planner. */
public final class ConsumeTool implements ServerTool {
    public static Integer sourceSlot(JsonObject args) {
        return args != null && args.size() == 1 && args.has("slot") ? EquipTool.sourceSlot(args) : null;
    }
    @Override public String name() { return "consume"; }
    @Override public Acceptance acceptanceMode() { return Acceptance.ACCEPT; }
    @Override public int capTicks(JsonObject args) { return ConsumptionWork.CAP_TICKS; }
    @Override public String acceptSubject(JsonObject args) { return "单次原生食用，打断不重开"; }
    @Override public CompletableFuture<Result> runAuthorized(CompanionPlayer body, JsonObject args,
            CompanionScheduler scheduler, ActionPermissions permissions, ActionPermissions.Context context) {
        Integer source = sourceSlot(args);
        if (source == null) return completed("DENIED:consume仅接受slot=0-35整数，一次最多消费一件。");
        var level = body.level();
        var access = new NativeSupplyAccess(body, () -> {
            if (permissions == null || context == null || permissions.current(context.owner(), body.getUUID(),
                    context.dimension(), context.taskId(), body) != context
                    || !ActionPermissions.allowed(context, "consume", args) || body.level() != level)
                return new Result(false, "DENIED:原食用任务、身体、世界或许可已失效。", null);
            if (!scheduler.reserve(body.getUUID(), java.util.List.of()))
                return new Result(false, "BUSY:原食用资源租约已失效。", null);
            return null;
        });
        var failure = access.guard();
        if (failure != null) return CompletableFuture.completedFuture(failure);
        var work = new ConsumptionWork(access, source, body.getInventory().getItem(source));
        var future = new CompletableFuture<Result>();
        if (!scheduler.submit(body, work, future, ConsumptionWork.CAP_TICKS))
            return completed("BUSY:原身体槽仍被占用，未开始用物。");
        return future;
    }
    private static CompletableFuture<Result> completed(String message) {
        return CompletableFuture.completedFuture(new Result(false, message, null));
    }
}
