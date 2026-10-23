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
    private static final int MAX_BATCH_BLOCKS = 8;
    record Plan(ItemGoal.Target goal, List<String> targets, int radius, int seconds, int maxBlocks,
                ItemGoal.Scope scope, int exploreSteps, int exploreRadius, JsonObject materialFor) { }

    static Plan parse(String json) {
        if (json == null || json.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 4096)
            throw new IllegalArgumentException();
        var args = JsonParser.parseString(json).getAsJsonObject();
        if (!Set.of("item", "count", "mode", "targets", "r", "timeout_seconds", "max_blocks", "scope",
                        "explore_steps", "explore_radius", "material_for")
                .containsAll(args.keySet())) throw new IllegalArgumentException();
        var goal = new JsonObject();
        for (String key : List.of("item", "count", "mode")) if (args.has(key)) goal.add(key, args.get(key));
        var scope = ItemGoal.Scope.parse(args);
        if (scope == ItemGoal.Scope.SUBTASK && !goal.has("mode")) goal.addProperty("mode", "total");
        JsonObject materialFor = null;
        if (args.has("material_for")) {
            materialFor = args.getAsJsonObject("material_for");
            if (scope != ItemGoal.Scope.SUBTASK || args.has("item") || args.has("targets")
                    || !Set.of("item","ingredient","sources","recipe").containsAll(materialFor.keySet())
                    || !materialFor.has("item")) throw new IllegalArgumentException();
            integer(materialFor,"ingredient",0,0,8);
            if (materialFor.has("sources") && (!materialFor.get("sources").isJsonPrimitive()
                    || !materialFor.get("sources").getAsJsonPrimitive().isBoolean())) throw new IllegalArgumentException();
            if (materialFor.has("recipe") && (!materialFor.get("recipe").isJsonPrimitive()
                    || !materialFor.get("recipe").getAsJsonPrimitive().isString()
                    || !materialFor.get("recipe").getAsString().matches("(?:[a-z0-9_.-]+:)?[a-z0-9_./-]+")
                    || materialFor.get("recipe").getAsString().length()>128)) throw new IllegalArgumentException();
            goal.add("item", materialFor.get("item"));
        }
        ItemGoal.Target target = ItemGoal.Target.parse(goal.toString());
        JsonArray targets = materialFor == null ? args.getAsJsonArray("targets") : new JsonArray();
        if (targets == null || materialFor == null && targets.isEmpty() || targets.size() > 8) throw new IllegalArgumentException();
        var names = new java.util.ArrayList<String>();
        for (var value : targets) {
            if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()
                    || value.getAsString().length() > 128
                    || !value.getAsString().matches("#?(?:[a-z0-9_.-]+:)?[a-z0-9_./-]+"))
                throw new IllegalArgumentException();
            names.add(value.getAsString());
        }
        return new Plan(target, List.copyOf(names), integer(args, "r", 16, 1, 16),
                integer(args, "timeout_seconds", 0, 0, 900), integer(args, "max_blocks", Integer.MAX_VALUE, 1, Integer.MAX_VALUE),
                scope, integer(args, "explore_steps", Integer.MAX_VALUE, 0, Integer.MAX_VALUE),
                integer(args, "explore_radius", 0, 0, Integer.MAX_VALUE), materialFor == null ? null : materialFor.deepCopy());
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
    private Set<String> acceptedItems;
    private List<String> sourceTargets;
    private int targetGroup;
    private boolean materialResolved;
    private JsonObject materialEvidence;
    private final CompletableFuture<ToolOutcome> result = new CompletableFuture<>();
    private final Set<String> skippedBlocks = new HashSet<>();
    private final Set<String> skippedItems = new HashSet<>();
    private final Set<String> unharvestableBlocks = new HashSet<>();
    private final java.util.Map<String, Set<String>> itemStances = new java.util.HashMap<>();
    private final Set<String> waitedItems = new HashSet<>();
    private final java.util.Map<String, JsonObject> excludedBlocks = new java.util.LinkedHashMap<>();
    private final java.util.Map<String, JsonObject> excludedItems = new java.util.LinkedHashMap<>();
    private final JsonArray sourceObservations = new JsonArray();
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
    private int lastProgress;
    private int workPositionAttempts;
    private String currentFeet;
    private String observedFeet, batchFeet;
    private final java.util.ArrayDeque<JsonObject> workBatch = new java.util.ArrayDeque<>();
    private int batchDigs, batchLimit, batchContinuations;
    private boolean dropSettlePending;
    private int dropSettleWaits;
    private String lastProblem = "";
    private JsonObject lastData = new JsonObject();
    private JsonObject mismatchedSource;

    BoundedGather(Plan plan, JsonObject baseline, LongSupplier clock,
                  BiFunction<String, String, CompletableFuture<ToolOutcome>> remote,
                  Consumer<Runnable> dispatch, Runnable cancelChild, Consumer<String> progress) {
        this(plan, baseline, clock, remote, dispatch, cancelChild, progress, null);
    }

    BoundedGather(Plan plan, JsonObject baseline, LongSupplier clock,
                  BiFunction<String, String, CompletableFuture<ToolOutcome>> remote,
                  Consumer<Runnable> dispatch, Runnable cancelChild, Consumer<String> progress,
                  Function<ToolOutcome, CompletableFuture<ToolOutcome>> confirm) {
        this(plan, baseline, clock, remote, dispatch, cancelChild, progress, confirm, null);
    }

    BoundedGather(Plan plan, JsonObject baseline, LongSupplier clock,
                  BiFunction<String, String, CompletableFuture<ToolOutcome>> remote,
                  Consumer<Runnable> dispatch, Runnable cancelChild, Consumer<String> progress,
                  Function<ToolOutcome, CompletableFuture<ToolOutcome>> confirm, ExplorationRoute route) {
        this.plan = plan;
        acceptedItems = Set.of(plan.goal().item());
        sourceTargets = plan.targets();
        exploration = route == null ? new ExplorationRoute(plan.exploreSteps(), plan.exploreRadius()) : route;
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
        dispatch.accept(plan.materialFor() == null ? this::observe : this::resolveMaterial);
        return result;
    }
    private void resolveMaterial() {
        var query = new JsonObject();
        query.addProperty("item", plan.goal().item());
        query.addProperty("query", true);
        query.addProperty("material_index", integer(plan.materialFor(), "ingredient", 0, 0, 8));
        if (plan.materialFor().has("recipe")) query.add("recipe", plan.materialFor().get("recipe"));
        call("craft", query, receipt -> {
            if (!receipt.ok()) { finish(false, receipt.feedback()); return; }
            var material = receipt.data().getAsJsonObject("material");
            if (material == null || flag(material,"truncated")) {
                finish(false,"MATERIAL_UNAVAILABLE:配方替代材料列表不完整，未猜测来源。"); return;
            }
            String kind = plan.materialFor().has("sources") && plan.materialFor().get("sources").getAsBoolean()
                    ? "sources" : "items";
            var options = material.getAsJsonArray(kind);
            if (options == null || options.size() > 64) throw new IllegalArgumentException();
            var items = new java.util.LinkedHashSet<String>();
            var blocks = new java.util.LinkedHashSet<String>();
            for (var option : options) {
                var entry = option.getAsJsonObject();
                if (!entry.has("block")) continue;
                String item = entry.get("item").getAsString(), block = entry.get("block").getAsString();
                if (!item.matches("[a-z0-9_.-]+:[a-z0-9_./-]+") || item.length()>128
                        || !block.matches("[a-z0-9_.-]+:[a-z0-9_./-]+") || block.length()>128)
                    throw new IllegalArgumentException();
                items.add(item); blocks.add(block);
            }
            if (items.isEmpty()) { finish(false,"MATERIAL_UNAVAILABLE:该配方材料没有已验证的方块来源。"); return; }
            acceptedItems = Set.copyOf(items);
            sourceTargets = List.copyOf(blocks);
            materialEvidence = material.deepCopy();
            materialResolved = true;
            observe();
        });
    }
    boolean done() { return result.isDone(); }
    String activity() { return activity; }
    ItemGoal.Progress goalProgress() { return goal == null ? null : goal.snapshot(); }
    void tick() { if (!done() && plan.seconds() > 0 && clock.getAsLong() - started >= plan.seconds() * 1000L) stop("TIMEOUT:连续采集时限已到。"); }
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
        if (calls < Integer.MAX_VALUE) calls++;
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
        observe(null);
    }

    private void observe(Runnable next) {
        var args = new JsonObject();
        args.addProperty("details", true);
        call("status", args, receipt -> {
            if (!receipt.ok()) { finish(false, receipt.feedback()); return; }
            JsonObject state = receipt.data();
            if (goal == null) goal = new ItemGoal(plan.goal(), state, plan.scope(), acceptedItems);
            goal.observe(state);
            double x = finite(state, "x"), y = finite(state, "y"), z = finite(state, "z");
            observedFeet = (int) Math.floor(x) + ":" + (int) Math.floor(y) + ":" + (int) Math.floor(z);
            if (!observedFeet.equals(batchFeet)) workBatch.clear();
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
            boolean explored = false;
            try {
                if (plan.exploreSteps() > 0) explored = exploration.observe(state);
            }
            catch (IllegalArgumentException changed) {
                lastData = state.deepCopy();
                finish(false, "SCOPE_CHANGED:身体离开有界探索范围或状态无效，已停止。"
                        + "若已主动到达另一工区且只需近处采集，可显式explore_steps=0，"
                        + "仅以本次起点为中心采集r内目标，不换区，也不重置原任务探索账。"); return;
            }
            if (exploring) {
                exploring = false;
                if (explored) {
                    originX = x; originY = y; originZ = z;
                    // Only real position observations can establish another collection region.
                    sampleOffset = 0;
                    targetGroup = 0;
                    skippedBlocks.clear();
                    unharvestableBlocks.clear();
                    skippedItems.clear();
                    itemStances.clear();
                    waitedItems.clear();
                } else lastProblem = "探索移动未产生至少4格的实际位移，未计为新区域。";
            }
            if (distance(x, y, z) > (plan.radius() + 2d) * (plan.radius() + 2d)
                    || plan.exploreSteps() > 0 && !exploration.within(state)) {
                finish(false, "SCOPE_CHANGED:身体离开原采集区域或换维度，已停止。"); return;
            }
            String center = (int) Math.floor(x) + ":" + (int) Math.floor(y) + ":" + (int) Math.floor(z);
            if (!center.equals(pageCenter)) { pageCenter = center; sampleOffset = 0; targetGroup = 0; }
            progress.accept(goal.facts() + " 本段已挖=" + dug + "，已探索区域=" + exploration.regions());
            if (goal.satisfied()) { finish(true, "采集目标经实际背包核对满足。"); return; }
            if (mismatchedSource != null) {
                lastData = state.deepCopy();
                var counts = new JsonObject();
                for (var item : mismatchedSource.getAsJsonArray("observed_items"))
                    counts.addProperty(item.getAsString(), ItemGoal.count(state, item.getAsString()));
                mismatchedSource.add("inventory_counts", counts);
                finish(false, "SOURCE_MISMATCH:本次目标方块的完整实际掉落中未见所需物品，已核对新鲜背包，"
                        + "停止沿错误来源继续采集，交回模型调整item/targets。"
                        + "实际来源物品当前背包数量=" + counts + "。本次掉落不是未来掉落的保证；未禁止后续新调用。"
                        + "已有材料用mode=total；通用配方材料用scope=subtask与material_for查询真实替代来源，"
                        + "不要把一种木材的精确ID用于要求任意木材的阶段。");
                return;
            }
            if (com.neko.mcbot.agentcore.loop.BodyFacts.environmentUnsafe(state)) {
                lastData = state.deepCopy();
                finish(false, "ENVIRONMENT_UNSAFE:当前身体环境或避险恢复尚不安全，停止普通采集；先核对身体避险状态。");
                return;
            }
            if (goal.progress() > lastProgress) {
                lastProgress = goal.progress();
                dropSettlePending = false;
            }
            if (next != null) next.run();
            else if (!continueBatch()) scanDrops();
        });
    }

    private void scanDrops() {
        workBatch.clear();
        excludedItems.clear();
        var args = new JsonObject();
        args.addProperty("r", plan.radius());
        args.addProperty("items_only", true);
        if (acceptedItems.size() == 1) args.addProperty("item", acceptedItems.iterator().next());
        call("scan_area", args, receipt -> {
            if (!receipt.ok()) { finish(false, receipt.feedback()); return; }
            var items = receipt.data().getAsJsonArray("item_targets");
            if (items == null) throw new IllegalArgumentException();
            for (var entry : items) {
                JsonObject item = entry.getAsJsonObject();
                if (!acceptedItems.contains(item.get("item").getAsString())) continue;
                // Passive contact needs no movement beyond the allowed work area.
                if (!within(item) && !flag(item, "can_touch_here")) {
                    recordExclusion(excludedItems, item, "item"); continue;
                }
                if (!flag(item, "pickup_allowed")) continue;
                String key = itemKey(item);
                if (skippedItems.contains(key)) {
                    var tried = itemStances.getOrDefault(key, Set.of());
                    boolean freshStance = tried.size() < 6
                            && positions(item).stream().anyMatch(p -> !tried.contains(blockKey(p)));
                    if (!flag(item, "can_touch_here") && !freshStance) continue;
                    skippedItems.remove(key);
                }
                if (pickup(item)) return;
            }
            if (dropSettlePending) {
                dropSettlePending = false;
                dropSettleWaits++;
                var wait = new JsonObject();
                wait.addProperty("ticks", 10);
                call("wait", wait, settled -> {
                    if (!settled.ok()) finish(false, settled.feedback());
                    else observe();
                });
                return;
            }
            if (dug + cleared >= plan.maxBlocks()) { finish(false, "BLOCK_LIMIT:本段挖掘预算已到，实际物品目标未满足。"); return; }
            findBlocks();
        });
    }

    private boolean pickup(JsonObject item) {
        String key = itemKey(item);
        if (flag(item, "can_touch_here")) {
            if (!waitedItems.add(key)) {
                finish(false, "PICKUP_BLOCKED:走近并等待后物品仍在触碰范围内，实际背包未满足目标；"
                        + "检查背包空间、拾取延迟或现场限制，未把物品消失当作入包。");
                return true;
            }
            // Give the server body tick time to touch a newly created or delayed item.
            var wait = new JsonObject();
            // One bounded wait per observed entity; never shorten vanilla's actual delay.
            wait.addProperty("ticks", item.has("pickup_delay_ticks")
                    ? Math.min(20, Math.max(1, exact(item, "pickup_delay_ticks")) + 1) : 20);
            call("wait", wait, receipt -> {
                if (!receipt.ok()) finish(false, receipt.feedback());
                else observe();
            });
            return true;
        }
        var tried = itemStances.computeIfAbsent(key, ignored -> new HashSet<>());
        var positions = positions(item);
        if (positions.isEmpty() || tried.size() >= 6
                || positions.stream().allMatch(p -> tried.contains(blockKey(p)))) {
            skippedItems.add(itemKey(item));
            lastProblem = "掉落没有剩余的安全触碰站位；已按最新实体位置核对，本段不重复尝试旧站位。";
            return false;
        }
        // Movement ticks may already have picked it up. Only a fresh inventory proves progress.
        walk(positions, 0, tried, () -> { skippedItems.add(itemKey(item)); scanDrops(); });
        return true;
    }

    private void findBlocks() {
        if (sampleOffset == 0 && targetGroup == 0) {
            excludedBlocks.clear();
            unharvestableBlocks.clear();
        }
        var args = new JsonObject();
        var targets = new JsonArray();
        sourceTargets.subList(targetGroup, Math.min(targetGroup + 8, sourceTargets.size())).forEach(targets::add);
        args.add("targets", targets);
        args.addProperty("r", plan.radius());
        args.addProperty("work", true);
        args.addProperty("offset", sampleOffset);
        if (sampleOffset > 0) args.add("scan_center", scanCenter.deepCopy());
        if (plan.exploreSteps() > 0) args.addProperty("frontiers", true);
        if (plan.exploreSteps() > 0) args.addProperty("frontier_distance", Math.max(4, plan.radius()));
        call("find_resource", args, receipt -> {
            if (!receipt.ok()) { finish(false, receipt.feedback()); return; }
            if (plan.exploreSteps() > 0) exploration.searchPage(args, receipt, clock.getAsLong());
            lastData = receipt.data();
            if (lastData.has("scan_center")) scanCenter = lastData.getAsJsonObject("scan_center").deepCopy();
            searchPages++;
            if (sampleOffset == 0 && plan.exploreSteps() > 0) exploration.searchedHere();
            var blocks = lastData.getAsJsonArray("targets");
            if (blocks == null) throw new IllegalArgumentException();
            for (var entry : blocks) {
                var block = entry.getAsJsonObject();
                if (!within(block)) { recordExclusion(excludedBlocks, block, "block"); continue; }
                if (skippedBlocks.contains(blockKey(block))) continue;
                if (block.has("dry_work_safe") && !flag(block, "dry_work_safe")) {
                    skippedBlocks.add(blockKey(block));
                    lastProblem = "候选不满足干燥采掘安全条件；未为它移动或开路。";
                    continue;
                }
                if (block.has("harvestable_with_hand") && !flag(block, "harvestable_with_hand")) {
                    unharvestableBlocks.add(blockKey(block));
                    continue;
                }
                var positions = positions(block);
                var tried = new HashSet<String>();
                tried.add(currentFeet);
                if (flag(block, "can_work_here") || block.has("can_clear_here") && flag(block, "can_clear_here")) {
                    beginBatch(blocks, block);
                    mine(block, positions, 0, tried); return;
                }
                if (positions.isEmpty()) {
                    // Nearby standing samples do not prove a buried object has no working route.
                    var semantic = new JsonObject();
                    for (String axis : List.of("x", "y", "z")) semantic.add(axis, block.get(axis));
                    semantic.addProperty("arrival", "work");
                    positions = List.of(semantic);
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
            if (targetGroup + 8 < sourceTargets.size()) {
                targetGroup += 8; sampleOffset = 0; findBlocks(); return;
            }
            if (!unharvestableBlocks.isEmpty()) {
                finish(false, "WRONG_TOOL:已发现范围内匹配方块，但当前主手没有采收资格；"
                        + "未为这些目标开路，先inventory/equip核对工具再决定后续。"); return;
            }
            explore();
        });
    }

    private void beginBatch(JsonArray targets, JsonObject first) {
        workBatch.clear();
        batchFeet = observedFeet;
        batchDigs = 0;
        batchLimit = Math.min(MAX_BATCH_BLOCKS, Math.max(1, goal.target().count() - goal.progress()));
        var seen = new HashSet<String>();
        seen.add(blockKey(first));
        for (var entry : targets) {
            var target = entry.getAsJsonObject();
            if (workBatch.size() >= batchLimit - 1) break;
            if (!within(target) || skippedBlocks.contains(blockKey(target))
                    || target.has("harvestable_with_hand") && !flag(target, "harvestable_with_hand")
                    || !flag(target, "can_work_here") || !seen.add(blockKey(target))) continue;
            workBatch.addLast(target.deepCopy());
        }
    }

    private boolean continueBatch() {
        if (workBatch.isEmpty() || batchDigs == 0 || batchDigs >= batchLimit
                || dug + cleared >= plan.maxBlocks() || !observedFeet.equals(batchFeet)) return false;
        var next = workBatch.removeFirst();
        var tried = new HashSet<String>();
        tried.add(currentFeet);
        batchContinuations++;
        // The observation only nominates work. Each primitive rechecks the live target, hand and permission.
        mine(next, positions(next), 0, tried);
        return true;
    }

    private void resourceBlocked(String lead) {
        finish(false, lead + " 当前有界区域没有可继续采集的已观测候选；"
                    + "站位已筛选实际视线/距离及有界安全清理；超出预算或保护条件的埋藏目标须另行规划。"
                    + "未加载/撞帽范围不证明无资源；未进行挖掘勘探。" + lastProblem);
    }

    private void explore() {
        if ((!excludedBlocks.isEmpty() || !excludedItems.isEmpty()) && skippedBlocks.isEmpty()) {
            resourceBlocked("SCOPE_LIMIT:已发现匹配资源或掉落，但剩余已知候选超出本段采集或任务共享范围；未继续换区消耗探索预算。");
            return;
        }
        if (plan.exploreSteps() == 0) { resourceBlocked("RESOURCE_BLOCKED:本段未启用换区探索。"); return; }
        if (exploration.exhausted()) {
            resourceBlocked("EXPLORATION_LIMIT:本任务共享探索移动预算已到，重复gather/explore不会补充。"); return;
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
        progress.accept("本区域未找到可工作资源，正进行第" + exploration.attempts() + "/" + exploration.stepLimitText()
                + "段只行走探索；搜索半径=" + plan.radius() + "，完整已知查询区域=" + exploration.coverageRegions()
                + "，预计新增覆盖比例=" + exploration.selectedCoverage()
                + "；到达后重新查询，物品目标保持不变。");
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
        point.addProperty("max_digs", Math.max(0, plan.maxBlocks() - dug - cleared - 1));
        var target = new JsonObject();
        for (String key : List.of("x", "y", "z")) target.addProperty(key, exact(block, key));
        point.add("work_target", target);
        int next = index + 1;
        workPositionAttempts++;
        call("move_to", point, receipt -> {
            if (receipt.ok()) {
                afterMovement(receipt, () -> mine(block, positions, next, tried));
            } else if (localRouteFailure(receipt.feedback())) {
                lastProblem = receipt.feedback();
                afterMovement(receipt, () -> approachBlock(block, positions, next, tried));
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

    private static boolean localRouteFailure(String feedback) {
        return localStanceFailure(feedback) || feedback.startsWith("NO_PATH:")
                || feedback.startsWith("NO_PROGRESS:") || feedback.startsWith("PARTIAL:");
    }

    private void afterMovement(ToolOutcome receipt, Runnable next) {
        workBatch.clear();
        for (String key : List.of("removed_blocks", "placed_blocks")) {
            if (!receipt.data().has(key)) continue;
            int changes = exact(receipt.data(), key);
            if (changes < 0) throw new IllegalArgumentException();
            if (key.equals("removed_blocks")) cleared = Math.addExact(cleared, changes);
            if (changes > 0) exploration.invalidateCoverage();
        }
        // Failed routes can move or collect items too. Recheck scope and quantity before another action.
        observe(next);
    }

    private void blocked(JsonObject block) {
        workBatch.clear();
        skippedBlocks.add(blockKey(block));
        observe();
    }

    private void mine(JsonObject block, List<JsonObject> positions, int next, Set<String> tried) {
        var args = new JsonObject();
        for (String key : List.of("x", "y", "z")) args.addProperty(key, exact(block, key));
        args.addProperty("expected_block", block.get("block").getAsString());
        args.addProperty("clear_obstructions", true);
        args.addProperty("clear_limit", Math.max(0, plan.maxBlocks() - dug - cleared - 1));
        call("break_block", args, receipt -> {
            lastData = receipt.data();
            int incidental = receipt.data().has("cleared_blocks")
                    ? receipt.data().get("cleared_blocks").getAsInt() : 0;
            if (incidental < 0 || incidental > args.get("clear_limit").getAsInt()) throw new IllegalArgumentException();
            cleared += incidental;
            if (incidental > 0 || receipt.data().has("removed") && receipt.data().get("removed").getAsBoolean())
                exploration.invalidateCoverage();
            if (incidental > 0) skippedBlocks.clear();
            if (receipt.data().has("removed") && receipt.data().get("removed").getAsBoolean()) {
                dug++;
                batchDigs++;
                sampleOffset = 0; targetGroup = 0;
                // A real removal can expose a previously blocked target. Query it afresh, with the same caps.
                skippedBlocks.clear();
                var drops = receipt.data().getAsJsonArray("drops");
                if (!receipt.data().has("drop_spawns_complete")
                        || receipt.data().get("drop_spawns_complete").getAsBoolean())
                    recordYield(block, receipt.data().has("drop_spawns")
                            ? receipt.data().getAsJsonArray("drop_spawns") : drops,
                            receipt.data().has("drop_spawns_complete")
                                    && flag(receipt.data(), "drop_spawns_complete"));
                if (drops != null) for (var drop : drops) {
                    var item = drop.getAsJsonObject();
                    if (acceptedItems.contains(item.get("item").getAsString())) dropSettlePending = true;
                }
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

    private void recordYield(JsonObject block, JsonArray drops, boolean complete) {
        // Only the requested block's receipt is evidence here, never incidental clearing_results.
        if (drops == null || drops.isEmpty()) return;
        if (drops.size() > 32) throw new IllegalArgumentException();
        var items = new java.util.TreeSet<String>();
        for (var drop : drops) {
            String id = drop.getAsJsonObject().get("item").getAsString();
            if (id.length() > 128 || !id.matches("[a-z0-9_.-]+:[a-z0-9_./-]+"))
                throw new IllegalArgumentException();
            items.add(id);
        }
        var evidence = new JsonObject();
        for (String axis : List.of("x", "y", "z")) evidence.addProperty(axis, exact(block, axis));
        evidence.add("block", block.get("block").deepCopy());
        var observed = new JsonArray();
        items.stream().limit(8).forEach(observed::add);
        evidence.add("observed_items", observed);
        evidence.addProperty("observed_item_count", items.size());
        evidence.addProperty("items_truncated", items.size() > 8);
        evidence.addProperty("observed_goal_item", items.stream().anyMatch(acceptedItems::contains));
        if (complete && items.stream().noneMatch(acceptedItems::contains)) mismatchedSource = evidence.deepCopy();
        if (sourceObservations.size() < 8) sourceObservations.add(evidence);
    }

    private void walk(List<JsonObject> positions, int index, Set<String> tried, Runnable unavailable) {
        while (index < positions.size() && tried.contains(blockKey(positions.get(index)))) index++;
        if (index >= positions.size() || tried.size() >= 6) { unavailable.run(); return; }
        JsonObject point = positions.get(index).deepCopy();
        tried.add(blockKey(point));
        point.addProperty("exact", true);
        point.addProperty("max_digs", Math.max(0, plan.maxBlocks() - dug - cleared));
        call("move_to", point, receipt -> {
            if (receipt.ok()) { afterMovement(receipt, null); return; }
            if (localRouteFailure(receipt.feedback())) {
                lastProblem = receipt.feedback();
                // A failed route can displace the item too; select again from a fresh entity observation.
                afterMovement(receipt, null);
            } else {
                // Permission, cancellation and unknown failures retain their terminal meaning.
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

    private void recordExclusion(java.util.Map<String, JsonObject> evidence, JsonObject target, String kind) {
        // Retain a bounded sample of observed exclusions, not a claim that the world has no resources.
        if (evidence.size() >= 16) return;
        var point = new JsonObject();
        for (String axis : List.of("x", "y", "z")) point.add(axis, target.get(axis).deepCopy());
        point.addProperty(kind, target.get(kind).getAsString());
        point.addProperty("reason", plan.exploreSteps() > 0 && !exploration.within(target)
                ? "task_scope" : "collection_scope");
        evidence.put(kind.equals("item") ? itemKey(target) : blockKey(target), point);
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
        if (plan.exploreSteps() > 0) exploration.abandon();
        var data = new JsonObject();
        data.addProperty("goal_satisfied", ok && goal != null && goal.satisfied());
        data.addProperty("scope", plan.scope() == ItemGoal.Scope.TASK ? "task" : "subtask");
        if (materialEvidence != null) data.add("material", materialEvidence);
        data.addProperty("mined_blocks", dug);
        data.addProperty("cleared_blocks", cleared);
        data.addProperty("actual_progress", goal == null ? 0 : goal.progress());
        data.addProperty("primitive_calls", calls);
        data.addProperty("work_position_attempts", workPositionAttempts);
        data.addProperty("batch_continuations", batchContinuations);
        data.addProperty("drop_settle_waits", dropSettleWaits);
        data.addProperty("exploration_enabled", plan.exploreSteps() > 0);
        data.addProperty("exploration_attempts", exploration.attempts());
        data.addProperty("exploration_max_steps", exploration.maxSteps);
        data.addProperty("exploration_radius", exploration.radius);
        data.addProperty("explored_regions", exploration.regions());
        data.addProperty("search_pages", searchPages);
        data.addProperty("search_radius", plan.radius());
        data.addProperty("coverage_regions", exploration.coverageRegions());
        data.addProperty("estimated_new_coverage", exploration.selectedCoverage());
        data.addProperty("skipped_blocks", skippedBlocks.size());
        data.addProperty("skipped_items", skippedItems.size());
        var excluded = new JsonArray();
        excludedBlocks.values().forEach(excluded::add);
        excludedItems.values().forEach(excluded::add);
        data.add("scope_exclusions", excluded);
        data.add("source_observations", sourceObservations.deepCopy());
        if (mismatchedSource != null) data.add("mismatched_source", mismatchedSource.deepCopy());
        data.add("last_observation", lastData);
        for (String key : List.of("authorization_id", "authorization_summary"))
            if (lastData.has(key)) data.add(key, lastData.get(key).deepCopy());
        result.complete(new ToolOutcome(ok, reason + "\n"
                + (goal == null ? "尚未取得本次采集的完整背包基线。" : goal.facts())
                + (materialResolved ? "\n材料按服务器配方候选来源的实际件数结算；未自动转换，后续合成仍须重新查询及执行。" : "")
                + (plan.scope() == ItemGoal.Scope.SUBTASK ? "\n此回执只结算材料子任务，不代表主人的整项目标完成。" : "")
                + (plan.exploreSteps() == 0 ? "\n本段仅在开始工区附近采集，不换区；原任务探索账继续保留。" : "")
                + "\n本段实际目标挖掘=" + dug + "，另清遮挡=" + cleared + "，子调用=" + calls + "，受阻候选=" + skippedBlocks.size()
                + "，未拾取候选=" + skippedItems.size() + "；任务探索移动=" + exploration.attempts() + "/" + exploration.stepLimitText()
                + "，共享范围半径=" + exploration.radiusText() + "，新增观察区域=" + exploration.regions()
                + "，搜索半径=" + plan.radius() + "，本段查询页=" + searchPages
                + "，完整已知查询区域=" + exploration.coverageRegions()
                + (excluded.isEmpty() ? "" : "\n观测到但范围排除的候选样本（方块/掉落各最多16）=" + excluded
                + "；task_scope为本任务首次固定的共享范围，collection_scope为本段搜索范围；不是不可挖或不可拾取。")
                + (!ok && !sourceObservations.isEmpty()
                ? "\n本段目标方块的实际掉落样本（最多8次）=" + sourceObservations
                + "；observed_goal_item=false表示本次记录未见期望物品，先核对item与targets是否匹配。"
                + "样本不是完整战利品表，也不证明该方块永不产出目标；未自动放宽物品目标，实际成果仍以背包为准。" : "")
                + "；请按实际回执决定后续。", data));
    }
}
