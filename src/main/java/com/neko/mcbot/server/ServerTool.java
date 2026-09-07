package com.neko.mcbot.server;

import com.google.gson.JsonObject;
import com.neko.mcbot.body.CompanionPlayer;
import com.neko.mcbot.task.CompanionScheduler;

import java.util.concurrent.CompletableFuture;

/**
 * 服务端工具：由主人客户端经 payload 请求、主线程执行。
 * feedback 是给模型看的人话（教学式回执，设计文档 §7）；data 给 UI/neko 播报。
 * 同步工具实现 run()；跨 tick 工具覆写 runAsync()，把 TickTask 交给 scheduler。
 */
public interface ServerTool {

    record Result(boolean ok, String feedback, JsonObject data) {
    }

    String name();

    default Result run(CompanionPlayer companion, JsonObject args) {
        throw new UnsupportedOperationException("async tool must override runAsync");
    }

    default CompletableFuture<Result> runAsync(CompanionPlayer companion, JsonObject args,
                                               CompanionScheduler scheduler) {
        return CompletableFuture.completedFuture(run(companion, args));
    }
}
