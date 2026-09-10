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

    /**
     * 回执形态（R2-S4 受理即回执）。
     *
     * <p>{@link #SYNC}（默认）：一次调用 = 一个终局回执，模型问一次就等到结果。
     * {@link #ACCEPT}：先回一条"我受理了、还没有结果"（{@code job_ack}），真正的结果
     * 晚点以 {@code job_event} 送来。**只给跨 tick 的长活用**：短活先回受理反而白多一跳，
     * 而且模型还要多花一次往返去问"好了没"。
     */
    enum Acceptance {
        SYNC, ACCEPT
    }

    String name();

    /**
     * 这条活要占多久的能力帽（tick）。
     *
     * <p><b>为什么放在接口上而不是各写各的</b>：这个数有两个消费者——{@code scheduler.submit}
     * 用它做服务端超时，受理回执用它算客户端的等待上限。两边各写一份，就是"服务端允许跑 180s、
     * 客户端 90s 就判 TIMEOUT"那条真缺陷的成因（模型会收到一句错误的"先别重试"）。
     * 工具覆写它、并且在 {@code submit} 时也传它，两个消费者就永远同源。
     */
    default int capTicks(JsonObject args) {
        return CompanionScheduler.DEFAULT_CAP_TICKS;
    }

    /** 默认同步回执；长活覆写 {@link Acceptance#ACCEPT}。 */
    default Acceptance acceptanceMode() {
        return Acceptance.SYNC;
    }

    /**
     * ACCEPT 回执里"我在干什么"那一小段（例：{@code 走到 (12,63,-4)}）。
     *
     * <p>只让工具提供**主语**、模板统一由派发层拼（见 {@code ServerToolDispatcher#acceptText}）：
     * "别猜、别等着、做完我主动报 j7"这套教学必须每个工具一字不差，否则模型要学好几遍。
     */
    default String acceptSubject(JsonObject args) {
        return name();
    }

    default Result run(CompanionPlayer companion, JsonObject args) {
        throw new UnsupportedOperationException("async tool must override runAsync");
    }

    default CompletableFuture<Result> runAsync(CompanionPlayer companion, JsonObject args,
                                               CompanionScheduler scheduler) {
        return CompletableFuture.completedFuture(run(companion, args));
    }
}
