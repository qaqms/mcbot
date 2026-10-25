package com.neko.mcbot.agentcore.loop;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.Set;

/** A task-local inventory condition. World receipts, never assistant prose, supply its facts. */
public final class ItemGoal {
    public enum Scope {
        TASK, SUBTASK;

        public static Scope parse(JsonObject args) {
            if (!args.has("scope")) return TASK;
            var value = args.get("scope");
            if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString())
                throw new IllegalArgumentException("Invalid goal scope");
            return switch (value.getAsString()) {
                case "task" -> TASK;
                case "subtask" -> SUBTASK;
                default -> throw new IllegalArgumentException("Invalid goal scope");
            };
        }
    }

    public record Target(String item, int count, boolean additional) {
        public static Target parse(String json) {
            JsonObject args = JsonParser.parseString(json).getAsJsonObject();
            if (!Set.of("item", "count", "mode").containsAll(args.keySet()))
                throw new IllegalArgumentException("Unexpected goal field");
            if (!args.has("item") || !args.get("item").isJsonPrimitive()
                    || !args.get("item").getAsJsonPrimitive().isString()) throw new IllegalArgumentException();
            String item = args.get("item").getAsString();
            if (!item.matches("(?:[a-z0-9_.-]+:)?[a-z0-9_./-]+") || item.length() > 128)
                throw new IllegalArgumentException();
            if (!item.contains(":")) item = "minecraft:" + item;
            if (!args.has("count") || !args.get("count").isJsonPrimitive()
                    || !args.get("count").getAsJsonPrimitive().isNumber()) throw new IllegalArgumentException();
            int count = args.get("count").getAsBigDecimal().intValueExact();
            if (count < 1 || count > 4096) throw new IllegalArgumentException();
            String mode = args.has("mode") && args.get("mode").isJsonPrimitive()
                    && args.get("mode").getAsJsonPrimitive().isString() ? args.get("mode").getAsString() : "additional";
            if (args.has("mode") && (!args.get("mode").isJsonPrimitive()
                    || !args.get("mode").getAsJsonPrimitive().isString())) throw new IllegalArgumentException();
            if (!Set.of("additional", "total").contains(mode)) throw new IllegalArgumentException();
            return new Target(item, count, mode.equals("additional"));
        }
    }

    private final Target target;
    private final int baseline;
    private final Scope scope;
    private int current;
    private final Set<String> accepted;

    public ItemGoal(Target target, JsonObject initial) {
        this(target, initial, Scope.TASK);
    }

    public ItemGoal(Target target, JsonObject initial, Scope scope) {
        this(target, initial, scope, Set.of(target.item()));
    }

    public ItemGoal(Target target, JsonObject initial, Scope scope, Set<String> accepted) {
        if (accepted == null || accepted.isEmpty() || accepted.size() > 64
                || scope != Scope.SUBTASK && !accepted.equals(Set.of(target.item())))
            throw new IllegalArgumentException("Alternatives require a material subtask");
        this.target = target;
        this.scope = scope;
        this.accepted = Set.copyOf(accepted);
        baseline = count(initial, this.accepted);
        current = baseline;
    }

    public Target target() { return target; }
    public record Progress(String item, int current, int target, boolean additional, Scope scope,
                           boolean alternatives) {}
    public Progress snapshot() {
        return new Progress(target.item(), progress(), target.count(), target.additional(), scope,
                !accepted.equals(Set.of(target.item())));
    }
    public void observe(JsonObject state) { current = count(state, accepted); }
    public int progress() { return target.additional() ? Math.max(0, current - baseline) : current; }
    public boolean satisfied() { return progress() >= target.count(); }
    public JsonObject evidence() {
        var data = new JsonObject();
        data.addProperty("item", target.item());
        data.addProperty("count", target.count());
        data.addProperty("mode", target.additional() ? "additional" : "total");
        data.addProperty("baseline", baseline);
        data.addProperty("current", current);
        var items = new com.google.gson.JsonArray();
        accepted.stream().sorted().forEach(items::add);
        data.add("accepted_items", items);
        return data;
    }
    public String facts() {
        return (scope == Scope.SUBTASK ? "采集子目标=" : "物品目标=")
                + (accepted.equals(Set.of(target.item())) ? target.item() : "配方候选来源" + accepted)
                + "，口径=" + (target.additional() ? "背包净新增" : "背包总量")
                + "，目标=" + target.count() + (scope == Scope.SUBTASK ? "，本次采集开始基线=" : "，任务开始基线=")
                + baseline + "，当前总量=" + current
                + "，实际进度=" + progress() + "，尚缺=" + Math.max(0, target.count() - progress())
                + (accepted.equals(Set.of(target.item())) ? "" : "，实际按配方候选来源件数合计=" + accepted
                + "；不代表转换后的材料单位或整批配方已满足")
                + "。挖掘格数和地上掉落不计入背包成果；交付目标不由此校验。";
    }

    public static int count(JsonObject state, String item) {
        return count(state, Set.of(item));
    }
    private static int count(JsonObject state, Set<String> items) {
        if (state == null) throw new IllegalArgumentException("Missing inventory");
        JsonObject inventory = state.has("inventory") && state.get("inventory").isJsonObject()
                ? state.getAsJsonObject("inventory") : state;
        if (!inventory.has("slots") || !inventory.get("slots").isJsonArray())
            throw new IllegalArgumentException("Missing inventory slots");
        var slots = inventory.getAsJsonArray("slots");
        if (slots.size() != 36) throw new IllegalArgumentException("Incomplete inventory");
        var seen = new java.util.HashSet<Integer>();
        int total = 0;
        for (var value : slots) {
            var slot = value.getAsJsonObject();
            int index = number(slot, "slot");
            int quantity = number(slot, "count");
            if (index < 0 || index >= 36 || !seen.add(index) || quantity < 0)
                throw new IllegalArgumentException("Invalid inventory");
            if (!slot.has("item") || !slot.get("item").isJsonPrimitive()
                    || !slot.get("item").getAsJsonPrimitive().isString()) throw new IllegalArgumentException();
            if (slot.has("item_id_truncated") && slot.get("item_id_truncated").getAsBoolean())
                throw new IllegalArgumentException("Incomplete item id");
            if (items.contains(slot.get("item").getAsString())) total = Math.addExact(total, quantity);
        }
        return total;
    }

    private static int number(JsonObject object, String key) {
        if (!object.has(key) || !object.get(key).isJsonPrimitive()
                || !object.get(key).getAsJsonPrimitive().isNumber()) throw new IllegalArgumentException();
        return object.get(key).getAsBigDecimal().intValueExact();
    }
}
