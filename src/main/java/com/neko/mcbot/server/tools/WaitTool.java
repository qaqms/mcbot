package com.neko.mcbot.server.tools;

import com.google.gson.JsonObject;
import com.neko.mcbot.body.CompanionPlayer;
import com.neko.mcbot.server.ServerTool;
import com.neko.mcbot.task.CompanionScheduler;
import com.neko.mcbot.task.TickTask;

import java.util.concurrent.CompletableFuture;

/**
 * wait：站定等 N 秒（1-60）。等熔炉出货、等作物长熟、等怪走远这类节奏都靠它，
 * 免得模型用连发 scan 空转烧 token。
 */
public final class WaitTool implements ServerTool {

    @Override
    public String name() {
        return "wait";
    }

    @Override
    public CompletableFuture<Result> runAsync(CompanionPlayer c, JsonObject args, CompanionScheduler sched) {
        int seconds;
        try {
            seconds = args.get("seconds").getAsInt();
        } catch (RuntimeException e) {
            return CompletableFuture.completedFuture(
                    new Result(false, "DENIED:需要整数 seconds（1-60 秒）。", null));
        }
        if (seconds < 1 || seconds > 60) {
            return CompletableFuture.completedFuture(new Result(false,
                    "DENIED:seconds 要在 1-60 之间。更长的等待，先去干别的再回来查。", null));
        }
        CompletableFuture<Result> f = new CompletableFuture<>();
        if (!sched.submit(c, new Task(seconds), f, seconds * 20 + 100)) {
            return CompletableFuture.completedFuture(new Result(false,
                    "BUSY:我正忙着上一件事，等它结束或让我取消。", null));
        }
        return f;
    }

    private static final class Task extends TickTask {
        private final int seconds;
        private final int targetTicks;

        Task(int seconds) {
            this.seconds = seconds;
            this.targetTicks = seconds * 20;
        }

        @Override
        public Progress tick(CompanionPlayer c) {
            if (age >= targetTicks) {
                return new Progress.Done(new Result(true,
                        "这 " + seconds + " 秒等完了。要查结果就继续。", null));
            }
            return running();
        }
    }
}
