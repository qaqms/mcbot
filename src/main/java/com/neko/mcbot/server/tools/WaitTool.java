package com.neko.mcbot.server.tools;

import com.google.gson.JsonObject;
import com.neko.mcbot.body.CompanionPlayer;
import com.neko.mcbot.server.ServerTool;
import com.neko.mcbot.task.CompanionScheduler;
import com.neko.mcbot.task.TickTask;

import java.util.concurrent.CompletableFuture;

/**
 * Wait for a bounded number of server ticks without polling the model.
 */
public final class WaitTool implements ServerTool {

    @Override
    public String name() {
        return "wait";
    }

    @Override
    public CompletableFuture<Result> runAsync(CompanionPlayer c, JsonObject args, CompanionScheduler sched) {
        int ticks;
        try {
            ticks = durationTicks(args);
        } catch (RuntimeException e) {
            return CompletableFuture.completedFuture(
                    new Result(false, "DENIED:仅选整数 seconds（1-60）或 ticks（1-1200），不能同时填写。", null));
        }
        CompletableFuture<Result> f = new CompletableFuture<>();
        if (!sched.submit(c, new Task(ticks), f, ticks + 100)) {
            return CompletableFuture.completedFuture(new Result(false,
                    "BUSY:我正忙着上一件事，等它结束或让我取消。", null));
        }
        return f;
    }

    static int durationTicks(JsonObject args) {
        if (args.size() != 1 || !args.has("seconds") && !args.has("ticks"))
            throw new IllegalArgumentException();
        String key = args.has("ticks") ? "ticks" : "seconds";
        var value = args.get(key);
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber())
            throw new IllegalArgumentException();
        int n = value.getAsBigDecimal().intValueExact();
        if (n < 1 || n > (key.equals("ticks") ? 1200 : 60)) throw new IllegalArgumentException();
        return key.equals("ticks") ? n : n * 20;
    }

    static final class Task extends TickTask {
        private final int targetTicks;

        Task(int ticks) {
            this.targetTicks = ticks;
        }

        @Override
        public JsonObject observation() {
            var data = new JsonObject();
            data.addProperty("action", "wait");
            data.addProperty("waited_ticks", Math.min(age, targetTicks));
            data.addProperty("target_ticks", targetTicks);
            return data;
        }

        @Override
        public Progress tick(CompanionPlayer c) {
            if (age >= targetTicks) {
                return new Progress.Done(new Result(true,
                        "已等待 " + targetTicks + " 游戏刻；继续查看实际结果。", observation()));
            }
            return running();
        }
    }
}
