package com.neko.mcbot.agentcore.loop;

import com.google.gson.JsonObject;

import java.util.LinkedHashSet;

/** Receipt-derived stages use bounded existing facts, never another model or world query. */
public final class AutomaticMilestone {
    private AutomaticMilestone() { }

    public static JsonObject collection(ToolExecutor.ToolOutcome receipt, String bodyId, String dimension) {
        if (!receipt.ok() || receipt.accepted()) return null;
        var data = receipt.data();
        if (!WorldGoal.flag(data, "goal_satisfied")) return null;
        var fresh = data.getAsJsonObject("last_observation");
        if (!bodyId.equals(WorldGoal.text(fresh, "body_id")) || !WorldGoal.flag(fresh, "alive")
                || !dimension.equals(WorldGoal.text(fresh, "dimension"))) return null;
        var goal = data.getAsJsonObject("collection_goal");
        int requested = WorldGoal.integer(goal, "count");
        int baseline = WorldGoal.integer(goal, "baseline");
        int current = WorldGoal.integer(goal, "current");
        String mode = WorldGoal.text(goal, "mode");
        if (requested < 1 || requested > 4096 || baseline < 0 || current < 0
                || !java.util.Set.of("additional", "total").contains(mode)) return null;
        var accepted = goal.getAsJsonArray("accepted_items");
        if (accepted == null || accepted.isEmpty() || accepted.size() > 64) return null;
        var items = new LinkedHashSet<String>();
        int observed = 0;
        for (var value : accepted) {
            if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) return null;
            String item = canonicalItem(value.getAsString());
            if (!items.add(item)) return null;
            observed = Math.addExact(observed, ItemGoal.count(fresh, item));
        }
        int progress = mode.equals("additional") ? Math.max(0, observed - baseline) : observed;
        if (observed != current || progress < requested) return null;
        var evidence = goal.deepCopy();
        evidence.addProperty("source", "gather");
        evidence.addProperty("body_id", bodyId);
        evidence.addProperty("progress", progress);
        // One automatic acquisition notice per resource set, even across tiny repeated gathers.
        var identity = new JsonObject();
        var sorted = new com.google.gson.JsonArray();
        items.stream().sorted().forEach(sorted::add);
        identity.add("resources", sorted);
        String listed = items.stream().sorted().limit(8).collect(java.util.stream.Collectors.joining(","));
        String facts = "采集材料子目标已核实满足：物品=[" + listed + "]"
                + (items.size() > 8 ? "（另" + (items.size() - 8) + "种见证据）" : "")
                + "，当前背包总量=" + observed
                + "，本次要求=" + requested + "，口径=" + mode
                + (mode.equals("additional") ? "，相对本次采集基线净新增=" + progress : "")
                + "。只结算此材料阶段，原任务继续；不代表整项目标完成或独占采集归因。";
        return report("collection", identity, facts, evidence);
    }

    public static JsonObject crafting(ToolExecutor.ToolOutcome receipt, String bodyId, String dimension) {
        if (!receipt.ok() || receipt.accepted()) return null;
        var data = receipt.data();
        if (WorldGoal.flag(data, "query") || !WorldGoal.flag(data, "can_craft")) return null;
        int crafted = WorldGoal.integer(data, "crafted_count");
        int stored = WorldGoal.integer(data, "output_inventory_count");
        if (crafted <= 0 || stored <= 0) return null;
        var output = data.getAsJsonObject("output_per_batch");
        if (output.has("item_id_truncated") && WorldGoal.flag(output, "item_id_truncated")) return null;
        boolean tool = output.has("mining_tool") && WorldGoal.flag(output, "mining_tool");
        boolean durable = output.has("max_damage") && WorldGoal.integer(output, "max_damage") > 0;
        if (!tool && !durable) return null;
        var delta = data.getAsJsonObject("body_delta");
        if (!WorldGoal.flag(delta, "observation_known") || !WorldGoal.flag(delta, "same_world")
                || !bodyId.equals(WorldGoal.text(delta, "body_id"))
                || !dimension.equals(WorldGoal.text(delta, "dimension_after"))) return null;
        String item = canonicalItem(WorldGoal.text(output, "item"));
        var evidence = new JsonObject();
        evidence.addProperty("source", "craft");
        evidence.addProperty("item", item);
        evidence.addProperty("crafted_count", crafted);
        evidence.addProperty("current", stored);
        evidence.addProperty("body_id", bodyId);
        var identity = new JsonObject();
        identity.addProperty("item", item);
        return report("crafting", identity, "已完成工具或装备制作：" + item + "，本次制作=" + crafted
                + "，已核实当前背包总量=" + stored + "。原任务继续，尚不代表已装备或整项目标完成。", evidence);
    }

    private static String canonicalItem(String value) {
        var args = new JsonObject();
        args.addProperty("item", value);
        args.addProperty("count", 1);
        return ItemGoal.Target.parse(args.toString()).item();
    }

    private static JsonObject report(String stage, JsonObject identity, String facts, JsonObject evidence) {
        // The same task's existing persisted milestone ledger owns these IDs as well.
        var condition = new JsonObject();
        condition.addProperty("stage", stage);
        condition.add("identity", identity);
        var report = new JsonObject();
        report.addProperty("id", MilestoneReport.fingerprint(condition.toString()));
        report.addProperty("kind", "item");
        report.addProperty("verified", true);
        report.addProperty("facts", facts);
        report.add("evidence", evidence);
        return report;
    }
}
