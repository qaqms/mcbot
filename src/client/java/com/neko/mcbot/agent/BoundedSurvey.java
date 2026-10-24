package com.neko.mcbot.agent;

import com.google.gson.JsonObject;
import com.neko.mcbot.agentcore.loop.ItemGoal;
import com.neko.mcbot.agentcore.loop.ToolExecutor.ToolOutcome;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/** Finite underground observations over shared navigation, search, permission and task budgets. */
final class BoundedSurvey {
    private final ExplorationRoute route;
    private final JsonObject options;
    private final LongSupplier clock;
    private final BiFunction<String, String, CompletableFuture<ToolOutcome>> remote;
    private final Consumer<Runnable> dispatch;
    private final Runnable cancel;
    private final java.util.function.Function<ToolOutcome, CompletableFuture<ToolOutcome>> confirm;
    private final CompletableFuture<ToolOutcome> result = new CompletableFuture<>();
    private final boolean returning;
    private final int seconds;
    private long started;
    private int calls, hops;
    private JsonObject state = new JsonObject(), movement = new JsonObject(), discovery = new JsonObject();
    private BoundedSearch search;
    private boolean initialized;
    private boolean advanced;

    BoundedSurvey(ExplorationRoute route, JsonObject options, LongSupplier clock,
                  BiFunction<String, String, CompletableFuture<ToolOutcome>> remote,
                  Consumer<Runnable> dispatch, Runnable cancel) {
        this(route, options, clock, remote, dispatch, cancel, null);
    }
    BoundedSurvey(ExplorationRoute route, JsonObject options, LongSupplier clock,
                  BiFunction<String, String, CompletableFuture<ToolOutcome>> remote,
                  Consumer<Runnable> dispatch, Runnable cancel,
                  java.util.function.Function<ToolOutcome, CompletableFuture<ToolOutcome>> confirm) {
        this.route = route; this.options = options.deepCopy(); this.clock = clock;
        this.remote = remote; this.dispatch = dispatch; this.cancel = cancel;
        this.confirm = confirm;
        returning = options.get("mode").getAsString().equals("return");
        seconds = options.has("timeout_seconds") ? ExplorationRoute.integer(options, "timeout_seconds") : 0;
        if (!returning && (!options.has("target_y") || !options.has("direction")
                || !options.has("targets"))) throw new IllegalArgumentException();
    }

    CompletableFuture<ToolOutcome> start() {
        started = clock.getAsLong(); dispatch.accept(this::observe); return result;
    }
    boolean done() { return result.isDone(); }
    boolean returning() { return returning; }
    void tick() {
        if (search != null) search.tick();
        if (!done() && seconds > 0 && clock.getAsLong() - started >= seconds * 1000L) stop("TIMEOUT:地下本段时限已到。");
    }
    void stop(String reason) {
        if (done()) return;
        finish(false, reason);
        if (search != null && !search.done()) search.stop(reason);
        cancel.run();
    }
    private CompletableFuture<ToolOutcome> primitive(String name, String json) {
        if (done()) return CompletableFuture.completedFuture(new ToolOutcome(false, "CANCELLED:地下本段已结束。"));
        if (calls < Integer.MAX_VALUE) calls++;
        return remote.apply(name, json);
    }
    private void call(String name, JsonObject args, Consumer<ToolOutcome> next) {
        call(name, args, next, true);
    }
    private void call(String name, JsonObject args, Consumer<ToolOutcome> next, boolean mayConfirm) {
        tick();
        if (done()) return;
        try {
            primitive(name, args.toString()).whenComplete((receipt, failure) -> dispatch.accept(() -> {
                tick();
                if (done()) return;
                if (failure != null || receipt == null || receipt.accepted() || receipt.feedback() == null
                        || receipt.feedback().getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 24000) {
                    stop("STATE_UNAVAILABLE:地下动作没有有效终态，未重发。"); return;
                }
                try {
                    if (mayConfirm && confirm != null && name.equals("move_to") && !receipt.ok()
                            && receipt.feedback().startsWith("NEED_CONFIRM:")
                            && receipt.data().has("authorization_id")
                            && (!receipt.data().has("removed_blocks")
                                || ExplorationRoute.integer(receipt.data(), "removed_blocks") == 0)
                            && (!receipt.data().has("placed_blocks")
                                || ExplorationRoute.integer(receipt.data(), "placed_blocks") == 0)) {
                        movement = receipt.data();
                        confirm.apply(receipt).whenComplete((answer, error) -> dispatch.accept(() -> {
                            tick();
                            if (done()) return;
                            if (error != null || answer == null || answer.accepted())
                                stop("STATE_UNAVAILABLE:授权没有有效终态，未续发。");
                            else if (!answer.ok()) finish(false, answer.feedback());
                            else {
                                var approved = args.deepCopy();
                                approved.add("authorization_id", receipt.data().get("authorization_id"));
                                call(name, approved, next, false);
                            }
                        }));
                    } else next.accept(receipt);
                }
                catch (RuntimeException invalid) { stop("STATE_UNAVAILABLE:地下回执缺少完整事实，未继续。"); }
            }));
        } catch (RuntimeException failure) { stop("INTERNAL:地下动作调用失败，未重发。"); }
    }
    private void observe() {
        var args = new JsonObject();
        args.addProperty("details", true);
        call("status", args, receipt -> {
            if (!receipt.ok()) { finish(false, receipt.feedback()); return; }
            state = receipt.data();
            if (!initialized) {
                if (returning && !route.hasUndergroundTrail()) {
                    finish(false, "NO_ROUTE:本任务没有地下勘探的实际路标，不能猜测返程。"); return;
                }
                try { route.beginUnderground(state); }
                catch (IllegalArgumentException changed) { scopeChanged(); return; }
                initialized = true;
            } else {
                try { route.observe(state); }
                catch (IllegalArgumentException changed) { scopeChanged(); return; }
            }
            // Inventory completeness is required even on a return trip.
            ItemGoal.count(state, "minecraft:air");
            if (stopUnsafeEnvironment()) return;
            if (returning) returnNext();
            else {
                String shortage = shortage(state);
                if (shortage != null) { finish(false, shortage); return; }
                scan();
            }
        });
    }
    private void scan() {
        var query = new JsonObject();
        query.add("targets", options.get("targets").deepCopy());
        query.addProperty("r", options.has("r") ? ExplorationRoute.integer(options, "r") : 8);
        query.addProperty("work", true);
        search = new BoundedSearch(query, this::primitive, dispatch, clock, cancel);
        search.start().whenComplete((receipt, failure) -> dispatch.accept(() -> {
            tick();
            if (done()) return;
            if (failure != null || receipt == null) { stop("STATE_UNAVAILABLE:地下搜索无结果。"); return; }
            discovery = receipt.data();
            if (!receipt.ok()) { finish(false, receipt.feedback()); return; }
            if (!discovery.getAsJsonArray("targets").isEmpty()) {
                finish(true, "已发现真实查询候选，停在当前观察位置；采收仍须核对工具和工作路径。"); return;
            }
            nextHop();
        }));
    }
    private void nextHop() {
        // An outward move consumes one attempt and may add one return leg.
        if (route.remainingSteps() <= route.returnSteps() + 1) {
            finish(false, "EXPLORATION_LIMIT:已预留返程移动次数，停止向外推进；可用explore mode=return返回。"); return;
        }
        int budget = route.remainingDigs();
        var slots = state.getAsJsonObject("inventory");
        var selected = slots.getAsJsonArray("slots").get(ExplorationRoute.integer(slots, "selected_slot")).getAsJsonObject();
        int reserve = option("reserve_durability", 16);
        budget = Math.min(budget, ExplorationRoute.integer(selected, "max_damage")
                - ExplorationRoute.integer(selected, "damage") - reserve);
        if (budget <= 0) { finish(false, "SUPPLY_LOW:主手耐久已到保留值，停止开新通道。"); return; }
        int x = (int) Math.floor(ExplorationRoute.number(state, "x"));
        int y = (int) Math.floor(ExplorationRoute.number(state, "y"));
        int z = (int) Math.floor(ExplorationRoute.number(state, "z"));
        String direction = options.get("direction").getAsString();
        int dx = direction.equals("east") ? 1 : direction.equals("west") ? -1 : 0;
        int dz = direction.equals("south") ? 1 : direction.equals("north") ? -1 : 0;
        int ty = ExplorationRoute.integer(options, "target_y");
        var point = new JsonObject();
        point.addProperty("x", x + 4 * dx);
        point.addProperty("y", y + Math.max(-4, Math.min(4, ty - y)));
        point.addProperty("z", z + 4 * dz);
        if (!route.within(point)) { finish(false, "SCOPE_CHANGED:下一地下路段超出任务范围，未发出。"); return; }
        route.attempt(point);
        move(point, budget, false);
    }
    private void returnNext() {
        var point = route.returnPoint();
        if (point == null) { finish(true, "已按新鲜位置核对回到地下勘探起点。"); return; }
        if (route.exhausted()) { finish(false, "EXPLORATION_LIMIT:共享预算不足以继续返回，未重开预算。"); return; }
        route.attemptReturn(point);
        move(point, 0, true);
    }
    private void move(JsonObject point, int digs, boolean back) {
        var args = point.deepCopy();
        args.addProperty("exact", true);
        args.addProperty("underground", true);
        args.addProperty("max_digs", digs);
        args.addProperty("walk_only", back);
        if (route.radius > 0) args.add("bounds", route.bounds());
        call("move_to", args, receipt -> {
            movement = receipt.data();
            // Reconcile any known effects even when the terminal failure forbids continuation.
            if (movement.has("removed_blocks")) route.recordDigs(movement);
            else if (receipt.ok()) {
                stop("STATE_UNAVAILABLE:移动缺少实际改动账，未继续。"); return;
            }
            var statusArgs = new JsonObject();
            statusArgs.addProperty("details", true);
            call("status", statusArgs, observed -> {
                if (!observed.ok()) { finish(false, observed.feedback()); return; }
                var before = state;
                state = observed.data();
                ItemGoal.count(state, "minecraft:air");
                try {
                    advanced |= route.observe(state);
                    route.reachedUnderground(state, back);
                } catch (IllegalArgumentException changed) { scopeChanged(); return; }
                hops++;
                if (!receipt.ok()) {
                    finish(false, receipt.feedback() + " 已重新核对实际位置，保留原路标。"); return;
                }
                if (stopUnsafeEnvironment()) return;
                boolean at = true;
                for (String axis : List.of("x", "y", "z"))
                    at &= (int) Math.floor(ExplorationRoute.number(state, axis)) == ExplorationRoute.integer(point, axis);
                if (!at || before.equals(state)) {
                    finish(false, "NO_PROGRESS:移动成功文案未被新鲜落点证实。"); return;
                }
                if (back) returnNext();
                else {
                    String shortage = shortage(state);
                    if (shortage != null) finish(false, shortage);
                    else scan();
                }
            });
        });
    }
    private int option(String key, int fallback) {
        return options.has(key) ? ExplorationRoute.integer(options, key) : fallback;
    }
    private void scopeChanged() {
        finish(false, "SCOPE_CHANGED:地下位置、维度、范围或有限路标状态变化，停止推进。");
    }
    private String shortage(JsonObject state) {
        if (ExplorationRoute.number(state, "hp") < option("min_health", 10)
                || ExplorationRoute.integer(state, "food") < option("min_food", 6)
                || state.get("on_fire").getAsBoolean()) return "SUPPLY_LOW:生命、饥饿或着火条件不适合继续勘探；保留返程路标。";
        var inventory = state.getAsJsonObject("inventory");
        if (ExplorationRoute.integer(inventory, "slots_used") >= 36)
            return "INVENTORY_FULL:背包已满，停止向外勘探。";
        int selected = ExplorationRoute.integer(inventory, "selected_slot");
        var slot = inventory.getAsJsonArray("slots").get(selected).getAsJsonObject();
        if (!slot.has("max_damage") || !slot.has("damage"))
            return "TOOL_REQUIRED:地下开路需要已装备、具备实际耐久的工具，采收资格仍由身体检查。";
        if (ExplorationRoute.integer(slot, "max_damage") - ExplorationRoute.integer(slot, "damage")
                <= option("reserve_durability", 16)) return "SUPPLY_LOW:主手耐久已到保留值。";
        return null;
    }
    private boolean stopUnsafeEnvironment() {
        if (!com.neko.mcbot.agentcore.loop.BodyFacts.environmentUnsafe(state)) return false;
        finish(false, "ENVIRONMENT_UNSAFE:身体环境或避险恢复尚不安全，停止地下工作；保留原返程记录和预算。");
        return true;
    }
    private void finish(boolean ok, String reason) {
        if (done()) return;
        route.abandon();
        var data = new JsonObject();
        data.addProperty("exploration_advanced", advanced);
        data.addProperty("exploration_attempts", route.attempts());
        data.addProperty("exploration_max_steps", route.maxSteps);
        data.addProperty("exploration_radius", route.radius);
        data.addProperty("underground_remaining_digs", route.remainingDigs());
        data.addProperty("underground_digs", route.undergroundDigs());
        data.addProperty("return_steps", route.returnSteps());
        data.addProperty("primitive_calls", calls);
        data.addProperty("survey_hops", hops);
        data.add("return_trail", route.trail());
        data.add("state", state.deepCopy());
        var supply = supplyFacts(state, option("reserve_durability", 16));
        data.add("supply", supply);
        data.add("discovery", discovery.deepCopy());
        data.add("movement", movement.deepCopy());
        for (String key : List.of("authorization_id", "authorization_summary"))
            if (movement.has(key)) data.add(key, movement.get(key).deepCopy());
        String text = reason + " 累计移动=" + route.attempts() + "/" + route.stepLimitText()
                + "，尚需返程路段=" + route.returnSteps() + "；实际路标=" + route.trail()
                + "。发现不等于采收到背包，原路仍须活世界复核。";
        var targets = discovery.getAsJsonArray("targets");
        if (targets != null && !targets.isEmpty()) text += "\n查询候选：" + targets;
        if (movement.has("authorization_summary")) text += "\n" + movement.get("authorization_summary").getAsString();
        if (!ok && supply.has("selected_remaining")) {
            text += "\n实际补给条件：" + supply
                    + "。本次地下执行结束不等于原目标无法完成；先按新鲜背包、真实配方和原许可选择"
                    + "备用工具、补做工具/工位/材料或原路返程，再继续原目标。不要把工具剩余耐久称为模型步数限制。";
        }
        result.complete(new ToolOutcome(ok, text, data));
    }

    static JsonObject supplyFacts(JsonObject state, int reserve) {
        var facts = new JsonObject();
        var tools = new com.google.gson.JsonArray();
        facts.add("durable_spares", tools);
        facts.addProperty("reserve_durability", reserve);
        var inventory = state.getAsJsonObject("inventory");
        if (inventory == null || !inventory.has("selected_slot") || !inventory.has("slots")) return facts;
        int selected = ExplorationRoute.integer(inventory, "selected_slot");
        for (var entry : inventory.getAsJsonArray("slots")) {
            var slot = entry.getAsJsonObject();
            if (!slot.has("damage") || !slot.has("max_damage") || ExplorationRoute.integer(slot, "count") <= 0) continue;
            int remaining = Math.max(0, ExplorationRoute.integer(slot, "max_damage") - ExplorationRoute.integer(slot, "damage"));
            int index = ExplorationRoute.integer(slot, "slot");
            if (index == selected) {
                facts.addProperty("selected_tool", slot.get("item").getAsString());
                facts.addProperty("selected_remaining", remaining);
                facts.addProperty("selected_dig_capacity", Math.max(0, remaining - reserve));
            } else if (remaining > reserve) {
                var tool = new JsonObject();
                tool.add("slot", slot.get("slot").deepCopy());
                tool.add("item", slot.get("item").deepCopy());
                tool.addProperty("remaining", remaining);
                tools.add(tool);
            }
        }
        return facts;
    }
}
