package com.neko.mcbot.server.tools;

import com.google.gson.JsonObject;
import com.neko.mcbot.body.CompanionPlayer;
import com.neko.mcbot.server.BlockMining;
import com.neko.mcbot.server.ServerTool;
import com.neko.mcbot.task.CompanionScheduler;
import com.neko.mcbot.task.TickTask;
import net.minecraft.core.BlockPos;

import java.util.concurrent.CompletableFuture;

/** Timed mining; final removal, harvest and durability use the vanilla player entry point. */
public final class BreakBlockTool implements ServerTool {
    public static final int CAP_TICKS = 1200;

    @Override public String name() { return "break_block"; }
    @Override public int capTicks(JsonObject args) { return CAP_TICKS; }
    @Override public Acceptance acceptanceMode() { return Acceptance.ACCEPT; }
    @Override public String acceptSubject(JsonObject args) {
        BlockPos pos = readPos(args);
        return pos == null ? "挖方块" : "挖 " + pos.toShortString() + " 的方块";
    }

    @Override
    public CompletableFuture<Result> runAsync(CompanionPlayer c, JsonObject args, CompanionScheduler sched) {
        BlockPos pos = readPos(args);
        if (pos == null) return CompletableFuture.completedFuture(
                new Result(false, "DENIED:参数需要数值整数坐标 x/y/z。", null));
        if (sched.busy(c.getUUID())) return CompletableFuture.completedFuture(
                new Result(false, "BUSY:我正忙着上一件事，等它结束或取消。", null));
        BlockMining mining = BlockMining.forPlayer(c, pos);
        Result failure = mining.preflight(5.5);
        if (failure != null) return CompletableFuture.completedFuture(failure);
        CompletableFuture<Result> future = new CompletableFuture<>();
        if (!sched.submit(c, new Task(mining), future, capTicks(args))) {
            return CompletableFuture.completedFuture(new Result(false, "BUSY:身体任务槽已占用，先等待或取消。", null));
        }
        return future;
    }

    static final class Task extends TickTask {
        private final BlockMining mining;
        private Result result;
        private int finishTicks;

        Task(BlockMining mining) { this.mining = mining; }

        @Override public Progress tick(CompanionPlayer c) {
            if (result != null) {
                // Preserve the two running ticks between removal and the final receipt.
                return ++finishTicks < 3 ? running() : new Progress.Done(result);
            }
            result = mining.tick();
            if (result != null && !result.data().get("removed").getAsBoolean()) return new Progress.Done(result);
            return running();
        }

        @Override public void onAbort() { mining.abort(); }
        @Override public JsonObject observation() { return mining.observation(); }
    }

    static BlockPos readPos(JsonObject args) {
        try {
            return new BlockPos(integer(args, "x"), integer(args, "y"), integer(args, "z"));
        } catch (RuntimeException invalid) {
            return null;
        }
    }

    private static int integer(JsonObject args, String key) {
        var value = args.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            throw new IllegalArgumentException("integer required");
        }
        return value.getAsBigDecimal().intValueExact();
    }
}
