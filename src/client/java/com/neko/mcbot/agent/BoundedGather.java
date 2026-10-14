package com.neko.mcbot.agent;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.neko.mcbot.agentcore.loop.ItemGoal;
import com.neko.mcbot.agentcore.loop.ToolExecutor.ToolOutcome;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/** Domain-specific continuation using fresh receipts. Each primitive retains the normal server gate. */
final class BoundedGather {
    record Plan(ItemGoal.Target goal, List<String> targets, int radius, int seconds, int maxBlocks) { }

    static Plan parse(String json) {
        if (json == null || json.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 4096)
            throw new IllegalArgumentException();
        var args = JsonParser.parseString(json).getAsJsonObject();
        if (!Set.of("item", "count", "mode", "targets", "r", "timeout_seconds", "max_blocks")
                .containsAll(args.keySet())) throw new IllegalArgumentException();
        var goal = new JsonObject();
        for (String key : List.of("item", "count", "mode")) if (args.has(key)) goal.add(key, args.get(key));
        ItemGoal.Target target = ItemGoal.Target.parse(goal.toString());
        JsonArray targets = args.getAsJsonArray("targets");
        if (targets == null || targets.isEmpty() || targets.size() > 8) throw new IllegalArgumentException();
        var names = new java.util.ArrayList<String>();
        for (var value : targets) {
            if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()
                    || value.getAsString().length() > 128
                    || !value.getAsString().matches("#?(?:[a-z0-9_.-]+:)?[a-z0-9_./-]+"))
                throw new IllegalArgumentException();
            names.add(value.getAsString());
        }
        return new Plan(target, List.copyOf(names), integer(args, "r", 8, 1, 16),
                integer(args, "timeout_seconds", 600, 1, 900), integer(args, "max_blocks", 128, 1, 256));
    }

    private static int integer(JsonObject args, String key, int fallback, int min, int max) {
        if (!args.has(key)) return fallback;
        if (!args.get(key).isJsonPrimitive() || !args.get(key).getAsJsonPrimitive().isNumber())
            throw new IllegalArgumentException();
        int value = args.get(key).getAsBigDecimal().intValueExact();
        if (value < min || value > max) throw new IllegalArgumentException();
        return value;
    }

    private final Plan plan;
    private final ItemGoal goal;
    private final LongSupplier clock;
    private final BiFunction<String, String, CompletableFuture<ToolOutcome>> remote;
    private final Consumer<Runnable> dispatch;
    private final Runnable cancelChild;
    private final Consumer<String> progress;
    private final CompletableFuture<ToolOutcome> result = new CompletableFuture<>();
    private final Set<String> skippedBlocks = new HashSet<>();
    private final Set<String> skippedItems = new HashSet<>();
    private final Set<String> approachedItems = new HashSet<>();
    private final Set<String> waitedItems = new HashSet<>();
    private double originX, originY, originZ;
    private String dimension;
    private boolean anchored;
    private long started;
    private int calls;
    private int dug;
    private int stalls;
    private int lastProgress;
    private String lastProblem = "";
    private JsonObject lastData = new JsonObject();

    BoundedGather(Plan plan, JsonObject baseline, LongSupplier clock,
                  BiFunction<String, String, CompletableFuture<ToolOutcome>> remote,
                  Consumer<Runnable> dispatch, Runnable cancelChild, Consumer<String> progress) {
        this.plan = plan;
        goal = new ItemGoal(plan.goal(), baseline);
        this.clock = clock;
        this.remote = remote;
        this.dispatch = dispatch;
        this.cancelChild = cancelChild;
        this.progress = progress;
    }

    CompletableFuture<ToolOutcome> start() {
        started = clock.getAsLong();
        dispatch.accept(this::observe);
        return result;
    }
    boolean done() { return result.isDone(); }
    void tick() { if (!done() && clock.getAsLong() - started >= plan.seconds() * 1000L) stop("TIMEOUT:连续采集时限已到。"); }
    void stop(String reason) {
        if (done()) return;
        finish(false, reason + " 已完成的动作保留，在途效果可能未知。");
        cancelChild.run();
    }

    private void call(String name, JsonObject args, Consumer<ToolOutcome> next) {
        if (done()) return;
        tick();
        if (done()) return;
        if (++calls > 1024) { stop("ACTION_LIMIT:连续采集子调用预算已到。"); return; }
        CompletableFuture<ToolOutcome> response;
        try { response = java.util.Objects.requireNonNull(remote.apply(name, args.toString())); }
        catch (RuntimeException failure) { stop("INTERNAL:采集子调用未发出有效结果。"); return; }
        response.whenComplete((receipt, failure) -> dispatch.accept(() -> {
            if (done()) return;
            tick();
            if (done()) return;
            if (failure != null || receipt == null || receipt.accepted() || receipt.feedback() == null
                    || receipt.feedback().getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 24000) {
                stop("INTERNAL:连续采集没有有效的子步骤终态，未自动重发。");
                return;
            }
            try { next.accept(receipt); }
            catch (RuntimeException invalid) { stop("STATE_UNAVAILABLE:采集回执缺少完整事实，未猜测或续发动作。"); }
        }));
    }

    private void observe() {
        var args = new JsonObject();
        args.addProperty("details", true);
        call("status", args, receipt -> {
            if (!receipt.ok()) { finish(false, receipt.feedback()); return; }
            JsonObject state = receipt.data();
            goal.observe(state);
            double x = finite(state, "x"), y = finite(state, "y"), z = finite(state, "z");
            String nowDimension = state.get("dimension").getAsString();
            if (!anchored) {
                originX = x; originY = y; originZ = z; dimension = nowDimension; anchored = true;
            }
            if (!dimension.equals(nowDimension) || distance(x, y, z) > (plan.radius() + 2d) * (plan.radius() + 2d)) {
                finish(false, "SCOPE_CHANGED:身体离开原采集区域或换维度，已停止。"); return;
            }
            progress.accept(goal.facts() + " 本段已挖=" + dug);
            if (goal.satisfied()) { finish(true, "采集目标经实际背包核对满足。"); return; }
            if (goal.progress() > lastProgress) { stalls = 0; lastProgress = goal.progress(); }
            scanDrops();
        });
    }

    private void scanDrops() {
        var args = new JsonObject();
        args.addProperty("r", plan.radius());
        args.addProperty("items_only", true);
        args.addProperty("item", plan.goal().item());
        call("scan_area", args, receipt -> {
            if (!receipt.ok()) { finish(false, receipt.feedback()); return; }
            var items = receipt.data().getAsJsonArray("item_targets");
            if (items == null) throw new IllegalArgumentException();
            for (var entry : items) {
                JsonObject item = entry.getAsJsonObject();
                if (!plan.goal().item().equals(item.get("item").getAsString()) || !within(item)
                        || !flag(item, "pickup_allowed") || skippedItems.contains(itemKey(item))) continue;
                pickup(item);
                return;
            }
            if (stalls >= 3) { finish(false, "NO_PROGRESS:三次采集/拾取未增加实际背包数量。" + lastProblem); return; }
            if (dug >= plan.maxBlocks()) { finish(false, "BLOCK_LIMIT:本段挖掘预算已到，实际物品目标未满足。"); return; }
            findBlocks();
        });
    }

    private void pickup(JsonObject item) {
        String key = itemKey(item);
        if (flag(item, "can_touch_here")) {
            if (!waitedItems.add(key)) {
                finish(false, "PICKUP_BLOCKED:走近并等待后物品仍在触碰范围内，实际背包未满足目标；"
                        + "检查背包空间、拾取延迟或现场限制，未把物品消失当作入包。");
                return;
            }
            // Give the server body tick time to touch a newly created or delayed item.
            var wait = new JsonObject();
            wait.addProperty("seconds", 1);
            call("wait", wait, receipt -> {
                if (!receipt.ok()) finish(false, receipt.feedback());
                else observe();
            });
            return;
        }
        if (!approachedItems.add(key)) {
            skippedItems.add(key);
            stalls++;
            lastProblem = "已到候选站位但最新物品仍未进入身体触碰范围。";
            observe();
            return;
        }
        var positions = positions(item);
        if (positions.isEmpty()) {
            skippedItems.add(itemKey(item));
            lastProblem = "掉落附近没有已观测安全站位。";
            observe();
            return;
        }
        // Movement ticks may already have picked it up. Only a fresh inventory proves progress.
        walk(positions, 0, this::observe,
                () -> { skippedItems.add(itemKey(item)); observe(); });
    }

    private void findBlocks() {
        var args = new JsonObject();
        var targets = new JsonArray();
        plan.targets().forEach(targets::add);
        args.add("targets", targets);
        args.addProperty("r", plan.radius());
        args.addProperty("work", true);
        call("find_resource", args, receipt -> {
            if (!receipt.ok()) { finish(false, receipt.feedback()); return; }
            lastData = receipt.data();
            var blocks = lastData.getAsJsonArray("targets");
            if (blocks == null) throw new IllegalArgumentException();
            for (var entry : blocks) {
                var block = entry.getAsJsonObject();
                if (!within(block) || skippedBlocks.contains(blockKey(block))) continue;
                if (flag(block, "can_work_here")) { mine(block); return; }
                var positions = positions(block);
                if (positions.isEmpty()) { skippedBlocks.add(blockKey(block)); continue; }
                walk(positions, 0, () -> mine(block), () -> {
                    skippedBlocks.add(blockKey(block)); observe();
                });
                return;
            }
            finish(false, "RESOURCE_BLOCKED:当前有界区域没有可继续采集的已观测候选；"
                    + "未加载/撞帽范围不证明无资源，不扩大范围或挖掘探查。" + lastProblem);
        });
    }

    private void mine(JsonObject block) {
        var args = new JsonObject();
        for (String key : List.of("x", "y", "z")) args.addProperty(key, exact(block, key));
        call("break_block", args, receipt -> {
            if (receipt.data().has("removed") && receipt.data().get("removed").getAsBoolean()) {
                dug++;
                stalls++;
                if (!receipt.ok()) { finish(false, receipt.feedback()); return; }
                observe();
            } else if (receipt.feedback().startsWith("TARGET_BLOCKED:")
                    || receipt.feedback().startsWith("OUT_OF_REACH:") || receipt.feedback().startsWith("TARGET_LOST:")) {
                skippedBlocks.add(blockKey(block));
                lastProblem = receipt.feedback();
                stalls++;
                observe();
            } else finish(false, receipt.feedback());
        });
    }

    private void walk(List<JsonObject> positions, int index, Runnable arrived, Runnable unavailable) {
        if (index >= positions.size()) { unavailable.run(); return; }
        JsonObject point = positions.get(index);
        point.addProperty("exact", true);
        call("move_to", point, receipt -> {
            if (receipt.ok()) { arrived.run(); return; }
            if (receipt.feedback().startsWith("NO_PATH:") || receipt.feedback().startsWith("NO_PROGRESS:")) {
                lastProblem = receipt.feedback();
                walk(positions, index + 1, arrived, unavailable);
            } else {
                // Includes NEED_CONFIRM/PARTIAL/cancellation and uncertain effects: yield to the model.
                lastData = receipt.data();
                finish(false, receipt.feedback());
            }
        });
    }

    private List<JsonObject> positions(JsonObject target) {
        var array = target.getAsJsonArray("stand_positions");
        if (array == null || array.size() > 6) throw new IllegalArgumentException();
        var points = new java.util.ArrayList<JsonObject>();
        for (var entry : array) {
            var pos = entry.getAsJsonObject();
            var point = new JsonObject();
            for (String key : List.of("x", "y", "z")) point.addProperty(key, exact(pos, key));
            if (within(point)) points.add(point);
        }
        return List.copyOf(points);
    }

    private boolean within(JsonObject target) {
        return distance(finite(target, "x"), finite(target, "y"), finite(target, "z"))
                <= plan.radius() * (double) plan.radius();
    }
    private double distance(double x, double y, double z) {
        return (x - originX) * (x - originX) + (y - originY) * (y - originY) + (z - originZ) * (z - originZ);
    }
    private static double finite(JsonObject data, String key) {
        if (!data.has(key) || !data.get(key).isJsonPrimitive() || !data.get(key).getAsJsonPrimitive().isNumber())
            throw new IllegalArgumentException();
        double n = data.get(key).getAsDouble();
        if (!Double.isFinite(n)) throw new IllegalArgumentException();
        return n;
    }
    private static int exact(JsonObject data, String key) {
        finite(data, key);
        return data.get(key).getAsBigDecimal().intValueExact();
    }
    private static boolean flag(JsonObject data, String key) {
        if (!data.has(key) || !data.get(key).isJsonPrimitive() || !data.get(key).getAsJsonPrimitive().isBoolean())
            throw new IllegalArgumentException();
        return data.get(key).getAsBoolean();
    }
    private static String blockKey(JsonObject block) { return exact(block, "x") + ":" + exact(block, "y") + ":" + exact(block, "z"); }
    private static String itemKey(JsonObject item) { return item.get("target_uuid").getAsString(); }

    private void finish(boolean ok, String reason) {
        if (done()) return;
        var data = new JsonObject();
        data.addProperty("goal_satisfied", ok && goal.satisfied());
        data.addProperty("mined_blocks", dug);
        data.addProperty("actual_progress", goal.progress());
        data.addProperty("primitive_calls", calls);
        data.addProperty("skipped_blocks", skippedBlocks.size());
        data.addProperty("skipped_items", skippedItems.size());
        data.add("last_observation", lastData);
        result.complete(new ToolOutcome(ok, reason + "\n" + goal.facts()
                + "\n本段实际挖掘=" + dug + "，子调用=" + calls + "，受阻候选=" + skippedBlocks.size()
                + "，未拾取候选=" + skippedItems.size() + "；请按实际回执决定后续。", data));
    }
}
