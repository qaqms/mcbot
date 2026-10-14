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
import java.util.function.Function;
import java.util.function.LongSupplier;

/** Domain-specific continuation using fresh receipts. Each primitive retains the normal server gate. */
final class BoundedGather {
    record Plan(ItemGoal.Target goal, List<String> targets, int radius, int seconds, int maxBlocks,
                ItemGoal.Scope scope, int exploreSteps, int exploreRadius) { }

    static Plan parse(String json) {
        if (json == null || json.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 4096)
            throw new IllegalArgumentException();
        var args = JsonParser.parseString(json).getAsJsonObject();
        if (!Set.of("item", "count", "mode", "targets", "r", "timeout_seconds", "max_blocks", "scope",
                        "explore_steps", "explore_radius")
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
                integer(args, "timeout_seconds", 600, 1, 900), integer(args, "max_blocks", 128, 1, 256),
                ItemGoal.Scope.parse(args), integer(args, "explore_steps", 8, 0, 16),
                integer(args, "explore_radius", 64, 16, 64));
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
    private ItemGoal goal;
    private final LongSupplier clock;
    private final BiFunction<String, String, CompletableFuture<ToolOutcome>> remote;
    private final Consumer<Runnable> dispatch;
    private final Runnable cancelChild;
    private final Consumer<String> progress;
    private final Function<ToolOutcome, CompletableFuture<ToolOutcome>> confirm;
    private String activity = "读取背包进度";
    private final CompletableFuture<ToolOutcome> result = new CompletableFuture<>();
    private final Set<String> skippedBlocks = new HashSet<>();
    private final Set<String> skippedItems = new HashSet<>();
    private final Set<String> approachedItems = new HashSet<>();
    private final Set<String> waitedItems = new HashSet<>();
    private double originX, originY, originZ;
    private final ExplorationRoute exploration;
    private boolean exploring;
    private int searchPages, sampleOffset;
    private String pageCenter;
    private JsonObject scanCenter;
    private String dimension;
    private boolean anchored;
    private long started;
    private int calls;
    private int dug;
    private int cleared;
    private int stalls;
    private int lastProgress;
    private int workPositionAttempts;
    private String currentFeet;
    private String lastProblem = "";
    private JsonObject lastData = new JsonObject();

    BoundedGather(Plan plan, JsonObject baseline, LongSupplier clock,
                  BiFunction<String, String, CompletableFuture<ToolOutcome>> remote,
                  Consumer<Runnable> dispatch, Runnable cancelChild, Consumer<String> progress) {
        this(plan, baseline, clock, remote, dispatch, cancelChild, progress, null);
    }

    BoundedGather(Plan plan, JsonObject baseline, LongSupplier clock,
                  BiFunction<String, String, CompletableFuture<ToolOutcome>> remote,
                  Consumer<Runnable> dispatch, Runnable cancelChild, Consumer<String> progress,
                  Function<ToolOutcome, CompletableFuture<ToolOutcome>> confirm) {
        this.plan = plan;
        exploration = new ExplorationRoute(plan.exploreSteps(), plan.exploreRadius());
        // Material subtasks count from their own fresh start, rather than the owner's directive start.
        if (plan.scope() == ItemGoal.Scope.TASK) goal = new ItemGoal(plan.goal(), baseline);
        this.clock = clock;
        this.remote = remote;
        this.dispatch = dispatch;
        this.cancelChild = cancelChild;
        this.progress = progress;
        this.confirm = confirm;
    }

    CompletableFuture<ToolOutcome> start() {
        started = clock.getAsLong();
        dispatch.accept(this::observe);
        return result;
    }
    boolean done() { return result.isDone(); }
    String activity() { return activity; }
    void tick() { if (!done() && clock.getAsLong() - started >= plan.seconds() * 1000L) stop("TIMEOUT:连续采集时限已到。"); }
    void stop(String reason) {
        if (done()) return;
        finish(false, reason + " 已完成的动作保留，在途效果可能未知。");
        cancelChild.run();
    }

    private void call(String name, JsonObject args, Consumer<ToolOutcome> next) {
        call(name, args, next, true);
    }

    private void call(String name, JsonObject args, Consumer<ToolOutcome> next, boolean mayConfirm) {
        if (done()) return;
        tick();
        if (done()) return;
        if (++calls > 1024) { stop("ACTION_LIMIT:连续采集子调用预算已到。"); return; }
        activity = switch (name) {
            case "status" -> "核对实际背包";
            case "scan_area" -> "查看可拾取掉落";
            case "find_resource" -> "查找可工作资源";
            case "move_to" -> "走向站位 (" + exact(args, "x") + "," + exact(args, "y") + "," + exact(args, "z") + ")";
            case "break_block" -> "挖掘目标 (" + exact(args, "x") + "," + exact(args, "y") + "," + exact(args, "z") + ")";
            case "wait" -> "等待身体触碰拾取";
            default -> name;
        };
        if (!name.equals("status")) progress.accept(activity);
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
            try {
                if (mayConfirm && confirm != null && !receipt.ok()
                        && receipt.feedback().startsWith("NEED_CONFIRM:")
                        && Set.of("move_to", "break_block").contains(name)
                        && receipt.data().has("authorization_id")) {
                    awaitConfirmation(name, args.deepCopy(), receipt, next);
                } else next.accept(receipt);
            }
            catch (RuntimeException invalid) { stop("STATE_UNAVAILABLE:采集回执缺少完整事实，未猜测或续发动作。"); }
        }));
    }

    private void awaitConfirmation(String name, JsonObject args, ToolOutcome proposal, Consumer<ToolOutcome> next) {
        String id = proposal.data().get("authorization_id").getAsString();
        if (id.isBlank() || id.length() > 64) throw new IllegalArgumentException();
        // Never replay a removal whose effects are already known. The model must reconcile that receipt.
        if (proposal.data().has("removed") && flag(proposal.data(), "removed")
                || proposal.data().has("cleared_blocks") && exact(proposal.data(), "cleared_blocks") > 0) {
            next.accept(proposal);
            return;
        }
        lastData = proposal.data();
        activity = "等待具体改动授权";
        progress.accept(activity);
        confirm.apply(proposal).whenComplete((answer, failure) -> dispatch.accept(() -> {
            if (done()) return;
            tick();
            if (done()) return;
            if (failure != null || answer == null || answer.accepted()) {
                finish(false, "INTERNAL:确认没有有效终态，未续发动作。");
            } else if (!answer.ok()) {
                finish(false, answer.feedback());
            } else {
                args.addProperty("authorization_id", id);
                // This reissue consumes the same time/action budgets. A changed proposal yields to the model.
                call(name, args, next, false);
            }
        }));
    }

    private void observe() {
        var args = new JsonObject();
        args.addProperty("details", true);
        call("status", args, receipt -> {
            if (!receipt.ok()) { finish(false, receipt.feedback()); return; }
            JsonObject state = receipt.data();
            if (goal == null) goal = new ItemGoal(plan.goal(), state, plan.scope());
            goal.observe(state);
            double x = finite(state, "x"), y = finite(state, "y"), z = finite(state, "z");
            currentFeet = Math.abs(x - Math.floor(x) - .5) <= .1 && Math.abs(y - Math.rint(y)) <= .05
                    && Math.abs(z - Math.floor(z) - .5) <= .1
                    ? (int) Math.floor(x) + ":" + (int) Math.rint(y) + ":" + (int) Math.floor(z) : null;
            String nowDimension = state.get("dimension").getAsString();
            if (!anchored) {
                originX = x; originY = y; originZ = z; dimension = nowDimension; anchored = true;
            }
            if (!dimension.equals(nowDimension)) {
                finish(false, "SCOPE_CHANGED:身体换维度，已停止。"); return;
            }
            boolean explored;
            try { explored = exploration.observe(state); }
            catch (IllegalArgumentException changed) {
                finish(false, "SCOPE_CHANGED:身体离开有界探索范围或状态无效，已停止。"); return;
            }
            if (exploring) {
                exploring = false;
                if (explored) {
                    originX = x; originY = y; originZ = z;
                    // Only real position observations can establish another collection region.
                    sampleOffset = 0;
                    skippedBlocks.clear();
                    skippedItems.clear();
                    approachedItems.clear();
                    waitedItems.clear();
                } else lastProblem = "探索移动未产生至少4格的实际位移，未计为新区域。";
            }
            if (distance(x, y, z) > (plan.radius() + 2d) * (plan.radius() + 2d)
                    || plan.exploreSteps() > 0 && !exploration.within(state)) {
                finish(false, "SCOPE_CHANGED:身体离开原采集区域或换维度，已停止。"); return;
            }
            String center = (int) Math.floor(x) + ":" + (int) Math.floor(y) + ":" + (int) Math.floor(z);
            if (!center.equals(pageCenter)) { pageCenter = center; sampleOffset = 0; }
            progress.accept(goal.facts() + " 本段已挖=" + dug + "，已探索区域=" + exploration.regions());
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
            if (dug + cleared >= plan.maxBlocks()) { finish(false, "BLOCK_LIMIT:本段挖掘预算已到，实际物品目标未满足。"); return; }
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
        args.addProperty("offset", sampleOffset);
        if (sampleOffset > 0) args.add("scan_center", scanCenter.deepCopy());
        if (plan.exploreSteps() > 0) args.addProperty("frontiers", true);
        call("find_resource", args, receipt -> {
            if (!receipt.ok()) { finish(false, receipt.feedback()); return; }
            lastData = receipt.data();
            if (lastData.has("scan_center")) scanCenter = lastData.getAsJsonObject("scan_center").deepCopy();
            searchPages++;
            if (sampleOffset == 0) exploration.searchedHere();
            var blocks = lastData.getAsJsonArray("targets");
            if (blocks == null) throw new IllegalArgumentException();
            for (var entry : blocks) {
                var block = entry.getAsJsonObject();
                if (!within(block) || skippedBlocks.contains(blockKey(block))) continue;
                var positions = positions(block);
                var tried = new HashSet<String>();
                tried.add(currentFeet);
                if (flag(block, "can_work_here") || block.has("can_clear_here") && flag(block, "can_clear_here")) {
                    mine(block, positions, 0, tried); return;
                }
                if (positions.isEmpty()) {
                    skippedBlocks.add(blockKey(block));
                    if (block.has("work_failure")) lastProblem = block.get("work_failure").getAsString();
                    continue;
                }
                approachBlock(block, positions, 0, tried);
                return;
            }
            if (lastData.has("next_offset")) {
                int nextOffset = exact(lastData, "next_offset");
                if (nextOffset != -1) {
                    if (nextOffset != sampleOffset + 4096 || nextOffset > 16384 || scanCenter == null)
                        throw new IllegalArgumentException();
                    sampleOffset = nextOffset;
                    findBlocks();
                    return;
                }
            }
            explore();
        });
    }

    private void resourceBlocked(String lead) {
        finish(false, lead + " 当前有界区域没有可继续采集的已观测候选；"
                    + "站位已筛选实际视线/距离及有界安全清理；超出预算或保护条件的埋藏目标须另行规划。"
                    + "未加载/撞帽范围不证明无资源；未进行挖掘勘探。" + lastProblem);
    }

    private void explore() {
        if (plan.exploreSteps() == 0) { resourceBlocked("RESOURCE_BLOCKED:本段未启用换区探索。"); return; }
        if (exploration.exhausted()) {
            resourceBlocked("EXPLORATION_LIMIT:本段探索步数预算已到。"); return;
        }
        var points = lastData.getAsJsonArray("exploration_points");
        if (points == null) { resourceBlocked("RESOURCE_BLOCKED:服务端未提供探索落脚样本。"); return; }
        JsonObject chosen = exploration.choose(points, plan.radius());
        if (chosen == null) { resourceBlocked("RESOURCE_BLOCKED:没有预算内尚未检查的已加载落脚样本。"); return; }
        var move = new JsonObject();
        for (String key : List.of("x", "y", "z")) move.addProperty(key, exact(chosen, key));
        exploration.attempt(chosen);
        move.addProperty("exact", true);
        move.addProperty("walk_only", true);
        progress.accept("本区域未找到可工作资源，正进行第" + exploration.attempts() + "/" + plan.exploreSteps()
                + "段只行走探索；到达后重新查询，物品目标保持不变。");
        call("move_to", move, receipt -> {
            if (receipt.ok() || receipt.feedback().startsWith("NO_PATH:")
                    || receipt.feedback().startsWith("NO_PROGRESS:") || receipt.feedback().startsWith("PARTIAL:")
                    || receipt.feedback().startsWith("TARGET_LOST:")) {
                lastProblem = receipt.ok() ? "" : receipt.feedback();
                exploring = true;
                // A failed/partial route may have moved. Always inspect before selecting again.
                observe();
            } else {
                lastData = receipt.data();
                finish(false, receipt.feedback());
            }
        });
    }

    private void approachBlock(JsonObject block, List<JsonObject> positions, int index, Set<String> tried) {
        while (index < positions.size() && tried.contains(blockKey(positions.get(index)))) index++;
        if (index >= positions.size()) { blocked(block); return; }
        JsonObject point = positions.get(index).deepCopy();
        tried.add(blockKey(point));
        point.addProperty("exact", true);
        point.addProperty("clear_obstructions", true);
        var target = new JsonObject();
        for (String key : List.of("x", "y", "z")) target.addProperty(key, exact(block, key));
        point.add("work_target", target);
        int next = index + 1;
        workPositionAttempts++;
        call("move_to", point, receipt -> {
            if (receipt.ok()) {
                currentFeet = blockKey(point);
                mine(block, positions, next, tried);
            } else if (localStanceFailure(receipt.feedback()) || receipt.feedback().startsWith("NO_PATH:")
                    || receipt.feedback().startsWith("NO_PROGRESS:")) {
                lastProblem = receipt.feedback();
                approachBlock(block, positions, next, tried);
            } else if (receipt.feedback().startsWith("TARGET_LOST:")) {
                lastProblem = receipt.feedback();
                blocked(block);
            } else {
                lastData = receipt.data();
                finish(false, receipt.feedback());
            }
        });
    }

    private static boolean localStanceFailure(String feedback) {
        return feedback.startsWith("TARGET_BLOCKED:") || feedback.startsWith("OUT_OF_REACH:");
    }

    private void blocked(JsonObject block) {
        skippedBlocks.add(blockKey(block));
        stalls++;
        observe();
    }

    private void mine(JsonObject block, List<JsonObject> positions, int next, Set<String> tried) {
        var args = new JsonObject();
        for (String key : List.of("x", "y", "z")) args.addProperty(key, exact(block, key));
        args.addProperty("expected_block", block.get("block").getAsString());
        args.addProperty("clear_obstructions", true);
        args.addProperty("clear_limit", Math.min(3, plan.maxBlocks() - dug - cleared - 1));
        call("break_block", args, receipt -> {
            lastData = receipt.data();
            int incidental = receipt.data().has("cleared_blocks")
                    ? receipt.data().get("cleared_blocks").getAsInt() : 0;
            if (incidental < 0 || incidental > args.get("clear_limit").getAsInt()) throw new IllegalArgumentException();
            cleared += incidental;
            if (incidental > 0) skippedBlocks.clear();
            if (receipt.data().has("removed") && receipt.data().get("removed").getAsBoolean()) {
                dug++;
                stalls++;
                // A real removal can expose a previously blocked target. Query it afresh, with the same caps.
                skippedBlocks.clear();
                if (!receipt.ok()) { finish(false, receipt.feedback()); return; }
                observe();
            } else if (localStanceFailure(receipt.feedback())) {
                lastProblem = receipt.feedback();
                approachBlock(block, positions, next, tried);
            } else if (receipt.feedback().startsWith("TARGET_LOST:")) {
                lastProblem = receipt.feedback();
                blocked(block);
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
        var seen = new HashSet<String>();
        for (var entry : array) {
            var pos = entry.getAsJsonObject();
            var point = new JsonObject();
            for (String key : List.of("x", "y", "z")) point.addProperty(key, exact(pos, key));
            if (within(point) && seen.add(blockKey(point))) points.add(point);
        }
        return List.copyOf(points);
    }

    private boolean within(JsonObject target) {
        return distance(finite(target, "x"), finite(target, "y"), finite(target, "z"))
                <= plan.radius() * (double) plan.radius()
                && (plan.exploreSteps() == 0 || exploration.within(target));
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
        data.addProperty("goal_satisfied", ok && goal != null && goal.satisfied());
        data.addProperty("scope", plan.scope() == ItemGoal.Scope.TASK ? "task" : "subtask");
        data.addProperty("mined_blocks", dug);
        data.addProperty("cleared_blocks", cleared);
        data.addProperty("actual_progress", goal == null ? 0 : goal.progress());
        data.addProperty("primitive_calls", calls);
        data.addProperty("work_position_attempts", workPositionAttempts);
        data.addProperty("exploration_attempts", exploration.attempts());
        data.addProperty("explored_regions", exploration.regions());
        data.addProperty("search_pages", searchPages);
        data.addProperty("skipped_blocks", skippedBlocks.size());
        data.addProperty("skipped_items", skippedItems.size());
        data.add("last_observation", lastData);
        for (String key : List.of("authorization_id", "authorization_summary"))
            if (lastData.has(key)) data.add(key, lastData.get(key).deepCopy());
        result.complete(new ToolOutcome(ok, reason + "\n"
                + (goal == null ? "尚未取得本次采集的完整背包基线。" : goal.facts())
                + (plan.scope() == ItemGoal.Scope.SUBTASK ? "\n此回执只结算材料子任务，不代表主人的整项目标完成。" : "")
                + "\n本段实际目标挖掘=" + dug + "，另清遮挡=" + cleared + "，子调用=" + calls + "，受阻候选=" + skippedBlocks.size()
                + "，未拾取候选=" + skippedItems.size() + "；请按实际回执决定后续。", data));
    }
}
