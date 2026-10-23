package com.neko.mcbot.agent;

import com.google.gson.JsonObject;
import com.neko.mcbot.agentcore.loop.ToolExecutor.ToolOutcome;

import java.util.concurrent.CompletableFuture;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/** One verified observation hop; the caller owns its discovery/completion condition. */
final class BoundedExplore {
    private final ExplorationRoute route;
    private final int seconds;
    private final LongSupplier clock;
    private final BiFunction<String, String, CompletableFuture<ToolOutcome>> remote;
    private final Consumer<Runnable> dispatch;
    private final Runnable cancel;
    private final CompletableFuture<ToolOutcome> result = new CompletableFuture<>();
    private long started;
    private int calls;
    private boolean walked;
    private JsonObject latest = new JsonObject();
    private JsonObject movement = new JsonObject();

    BoundedExplore(ExplorationRoute route, int seconds, LongSupplier clock,
                   BiFunction<String, String, CompletableFuture<ToolOutcome>> remote,
                   Consumer<Runnable> dispatch, Runnable cancel) {
        this.route = route; this.seconds = seconds; this.clock = clock;
        this.remote = remote; this.dispatch = dispatch; this.cancel = cancel;
    }
    CompletableFuture<ToolOutcome> start() {
        started = clock.getAsLong(); dispatch.accept(this::observe); return result;
    }
    boolean done() { return result.isDone(); }
    void tick() { if (!done() && seconds > 0 && clock.getAsLong() - started >= seconds * 1000L) stop("TIMEOUT:探索本段时限已到。"); }
    void stop(String reason) { if (done()) return; finish(false, reason); cancel.run(); }
    private void call(String name, JsonObject args, Consumer<ToolOutcome> next) {
        tick();
        if (done()) return;
        if (calls < Integer.MAX_VALUE) calls++;
        try {
            remote.apply(name, args.toString()).whenComplete((receipt, failure) -> dispatch.accept(() -> {
                tick();
                if (done()) return;
                if (failure != null || receipt == null || receipt.accepted() || receipt.feedback() == null
                        || receipt.feedback().getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 24000) {
                    stop("INTERNAL:探索没有有效终态，未重发。"); return;
                }
                try { next.accept(receipt); }
                catch (RuntimeException invalid) { stop("STATE_UNAVAILABLE:探索缺少完整事实，未继续。"); }
            }));
        } catch (RuntimeException failure) { stop("INTERNAL:探索子调用未发出有效结果。"); }
    }
    private void observe() {
        var args = new JsonObject();
        args.addProperty("details", true);
        call("status", args, receipt -> {
            if (!receipt.ok()) { finish(false, receipt.feedback()); return; }
            latest = receipt.data();
            boolean moved;
            try { moved = route.observe(latest); }
            catch (IllegalArgumentException changed) { finish(false, "SCOPE_CHANGED:探索状态/维度或范围变化。"); return; }
            if (walked && moved) { finish(true, "已从实际位置核对新的观察区域；尚未验证调用方目标。"); return; }
            if (route.exhausted()) { finish(false, "EXPLORATION_LIMIT:本任务探索移动预算已到。"); return; }
            route.searchedHere();
            var query = new JsonObject();
            query.addProperty("items_only", true);
            query.addProperty("frontiers", true);
            query.addProperty("r", 8);
            call("scan_area", query, scan -> {
                if (!scan.ok()) { finish(false, scan.feedback()); return; }
                var points = scan.data().getAsJsonArray("exploration_points");
                if (points == null) { finish(false, "RESOURCE_BLOCKED:没有服务端探索样本。"); return; }
                var point = route.choose(points, 0);
                if (point == null) { finish(false, "NO_PATH:没有范围内尚未检查的已加载落脚样本。"); return; }
                route.attempt(point);
                point.addProperty("exact", true);
                point.addProperty("walk_only", true);
                call("move_to", point, move -> {
                    movement = move.data();
                    if (move.ok() || move.feedback().startsWith("NO_PATH:")
                            || move.feedback().startsWith("NO_PROGRESS:") || move.feedback().startsWith("PARTIAL:")
                            || move.feedback().startsWith("TARGET_LOST:")) {
                        walked = true;
                        observe();
                    } else { latest = move.data(); finish(false, move.feedback()); }
                });
            });
        });
    }
    private void finish(boolean ok, String reason) {
        if (done()) return;
        route.abandon();
        var data = new JsonObject();
        data.addProperty("exploration_advanced", ok);
        data.addProperty("exploration_attempts", route.attempts());
        data.addProperty("exploration_max_steps", route.maxSteps);
        data.addProperty("exploration_radius", route.radius);
        data.addProperty("explored_regions", route.regions());
        data.addProperty("primitive_calls", calls);
        data.add("state", latest);
        data.add("movement", movement);
        for (String key : java.util.List.of("authorization_id", "authorization_summary"))
            if (latest.has(key)) data.add(key, latest.get(key).deepCopy());
        result.complete(new ToolOutcome(ok, reason + " 探索移动=" + route.attempts() + "/" + route.stepLimitText()
                + "，共享范围半径=" + route.radiusText() + "，新增观察区域=" + route.regions()
                + "。已完成移动保留；取消或未知结果不证明目标达成。", data));
    }
}
