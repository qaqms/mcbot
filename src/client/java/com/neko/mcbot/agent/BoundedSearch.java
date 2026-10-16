package com.neko.mcbot.agent;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.neko.mcbot.agentcore.loop.SearchPages;
import com.neko.mcbot.agentcore.loop.ToolExecutor.ToolOutcome;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/** One complete, read-only query over the existing bounded server pages. */
final class BoundedSearch {
    private final JsonObject query;
    private final BiFunction<String, String, CompletableFuture<ToolOutcome>> remote;
    private final Consumer<Runnable> dispatch;
    private final LongSupplier clock;
    private final Runnable cancel;
    private final SearchPages chain = new SearchPages();
    private final CompletableFuture<ToolOutcome> result = new CompletableFuture<>();
    private final ArrayList<JsonObject> targets = new ArrayList<>();
    private JsonObject last = new JsonObject();
    private int pages, examined, read, unknown, matches;
    private boolean truncated;
    private long started;

    BoundedSearch(JsonObject query, BiFunction<String, String, CompletableFuture<ToolOutcome>> remote,
                  Consumer<Runnable> dispatch, LongSupplier clock, Runnable cancel) {
        this.query = query.deepCopy(); this.remote = remote; this.dispatch = dispatch;
        this.clock = clock; this.cancel = cancel;
        if (!this.query.has("r")) this.query.addProperty("r", 16);
        this.query.addProperty("offset", 0);
    }
    CompletableFuture<ToolOutcome> start() {
        started = clock.getAsLong(); dispatch.accept(this::page); return result;
    }
    boolean done() { return result.isDone(); }
    void tick() { if (!done() && clock.getAsLong() - started >= 30000) stop("TIMEOUT:完整搜索超过30秒。"); }
    void stop(String reason) {
        if (done()) return;
        finish(false, reason);
        cancel.run();
    }
    private void page() {
        tick();
        if (done()) return;
        if (++pages > 5) { stop("ACTION_LIMIT:完整搜索最多5页。"); return; }
        String args = query.toString();
        try {
            remote.apply("find_resource", args).whenComplete((receipt, failure) -> dispatch.accept(() -> {
                tick();
                if (done()) return;
                if (failure != null || receipt == null || receipt.accepted() || receipt.feedback() == null
                        || receipt.feedback().getBytes(StandardCharsets.UTF_8).length > 24000) {
                    stop("STATE_UNAVAILABLE:搜索没有有效终态。"); return;
                }
                if (!receipt.ok()) { finish(false, receipt.feedback()); return; }
                try {
                    boolean continued = chain.continues("find_resource", args, receipt);
                    if (!chain.valid() || pages > 1 && !continued) throw new IllegalArgumentException();
                    last = receipt.data();
                    int cells = ExplorationRoute.integer(last, "cells_read");
                    int missing = ExplorationRoute.integer(last, "unknown");
                    int count = ExplorationRoute.integer(last, "samples_examined");
                    if (cells < 0 || missing < 0 || cells + missing != count) throw new IllegalArgumentException();
                    examined += count; read += cells; unknown += missing;
                    int observed = ExplorationRoute.integer(last, "matches_observed");
                    if (observed < 0 || observed > cells) throw new IllegalArgumentException();
                    matches += observed;
                    var found = last.getAsJsonArray("targets");
                    if (found == null || found.size() > 16) throw new IllegalArgumentException();
                    for (var value : found) targets.add(value.getAsJsonObject().deepCopy());
                    truncated |= last.has("results_truncated") && last.get("results_truncated").getAsBoolean();
                    int next = ExplorationRoute.integer(last, "next_offset");
                    if (next < 0) { finish(true, "已完成本次范围的全部查询页。"); return; }
                    query.addProperty("offset", next);
                    query.add("scan_center", last.get("scan_center").deepCopy());
                    dispatch.accept(this::page);
                } catch (RuntimeException invalid) { stop("STATE_UNAVAILABLE:分页覆盖事实不一致，未猜测续查。"); }
            }));
        } catch (RuntimeException failure) { stop("INTERNAL:搜索未发出有效调用。"); }
    }
    private static int rank(JsonObject target) {
        if (target.has("dry_work_safe") && !target.get("dry_work_safe").getAsBoolean()) return 5;
        if (target.has("harvestable_with_hand") && !target.get("harvestable_with_hand").getAsBoolean()) return 4;
        if (target.has("can_work_here") && target.get("can_work_here").getAsBoolean()) return 0;
        if (target.has("can_clear_here") && target.get("can_clear_here").getAsBoolean()) return 1;
        if (target.has("stand_positions") && !target.getAsJsonArray("stand_positions").isEmpty()) return 2;
        return 3;
    }
    private void finish(boolean ok, String reason) {
        if (done()) return;
        var data = last.deepCopy();
        var kept = new JsonArray();
        targets.sort(Comparator.comparingInt(BoundedSearch::rank));
        for (var target : targets) if (kept.size() < 16) kept.add(target);
        data.add("targets", kept);
        data.addProperty("search_pages", Math.min(pages, 5));
        data.addProperty("primitive_calls", Math.min(pages, 5));
        data.addProperty("samples_examined", examined);
        data.addProperty("cells_read", read);
        data.addProperty("unknown", unknown);
        data.addProperty("matches_observed", matches);
        data.addProperty("scan_complete", ok);
        data.addProperty("coverage_complete", ok && unknown == 0);
        data.addProperty("no_targets", kept.isEmpty());
        data.addProperty("results_truncated", truncated || targets.size() > kept.size());
        StringBuilder text = new StringBuilder(reason).append(" 目标=").append(query.get("targets"))
                .append("，半径=").append(query.get("r")).append("，查询页=").append(Math.min(pages, 5))
                .append("，已读=").append(read).append("，未知=").append(unknown)
                .append("，完整查询=").append(ok).append("。未加载不代表没有资源；候选不证明可达/可采收。");
        for (var target : kept) text.append("\n").append(target);
        if (data.toString().getBytes(StandardCharsets.UTF_8).length > 24000
                || text.toString().getBytes(StandardCharsets.UTF_8).length > 24000) {
            data.remove("targets");
            data.remove("no_targets");
            result.complete(new ToolOutcome(false, "OUTPUT_LIMIT:查询结果过大；缩小范围或使用find_resource分页。", data));
        } else result.complete(new ToolOutcome(ok, text.toString(), data));
    }
}
