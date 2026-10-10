package com.neko.mcbot.server;

import com.google.gson.JsonObject;
import com.neko.mcbot.agentcore.loop.TaskPolicy;
import com.neko.mcbot.task.CompanionScheduler;
import com.neko.mcbot.task.ResourceLocks.Region;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/** The dispatcher uses this same boundary for every registered server tool. */
public final class ServerActionGate {
    private ServerActionGate() {
    }

    public static CompletableFuture<ServerTool.Result> execute(ActionPermissions.Context context,
            UUID companion, String dimension, String tool, JsonObject args, CompanionScheduler scheduler,
            Supplier<CompletableFuture<ServerTool.Result>> action) {
        if (!ActionPermissions.allowed(context, tool, args)) return denied();
        if (TaskPolicy.observation(tool, args)) return action.get();
        if (context == null || !context.companion().equals(companion)
                || !context.dimension().equals(dimension)) return denied();
        return scheduler.execute(companion, regions(dimension, tool, args), action);
    }

    public static List<Region> regions(String dimension, String tool, JsonObject args) {
        if (!List.of("break_block", "place_block", "transfer", "smelt", "collect").contains(tool)) return List.of();
        try {
            int x = integer(args, "x"), y = integer(args, "y"), z = integer(args, "z");
            // Placement hooks and neighboring mining checks may touch adjacent blocks.
            int radius = "collect".equals(tool) ? args.has("r") ? integer(args, "r") : 3 : 1;
            if (radius < 1 || radius > 12) return List.of();
            return List.of(new Region(dimension, Math.subtractExact(x, radius), Math.subtractExact(y, radius),
                    Math.subtractExact(z, radius), Math.addExact(x, radius), Math.addExact(y, radius), Math.addExact(z, radius)));
        } catch (RuntimeException invalid) {
            return List.of();
        }
    }

    private static int integer(JsonObject args, String name) {
        var value = args.get(name);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            throw new IllegalArgumentException("integer required");
        }
        return value.getAsBigDecimal().intValueExact();
    }

    private static CompletableFuture<ServerTool.Result> denied() {
        return CompletableFuture.completedFuture(new ServerTool.Result(false,
                "DENIED:当前任务权限不允许此动作；只读任务只能观察，不能移动、换装或改动世界/物品。", null));
    }
}
