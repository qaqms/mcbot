package com.neko.mcbot.agentcore.loop;

import com.google.gson.JsonObject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Set;

/** A selected achievement, verified from fresh facts without changing the final goal. */
public final class MilestoneReport {
    private MilestoneReport() { }

    public static JsonObject condition(JsonObject input) {
        String type = WorldGoal.text(input, "type");
        if (type.equals("building")) return WorldGoal.request(input);
        if (type.equals("item")) {
            var args = input.deepCopy();
            args.remove("type");
            var target = ItemGoal.Target.parse(args.toString());
            var result = new JsonObject();
            result.addProperty("type", "item");
            result.addProperty("item", target.item());
            result.addProperty("count", target.count());
            result.addProperty("mode", target.additional() ? "additional" : "total");
            return result;
        }
        if (!type.equals("position") || !Set.of("type", "dimension", "x", "y", "z", "radius")
                .containsAll(input.keySet())) throw new IllegalArgumentException("Invalid milestone condition");
        String dimension = WorldGoal.text(input, "dimension");
        if (!dimension.matches("[a-z0-9_.-]+:[a-z0-9_./-]+") || dimension.length() > 128)
            throw new IllegalArgumentException("Invalid dimension");
        var result = new JsonObject();
        result.addProperty("type", type);
        result.addProperty("dimension", dimension);
        for (String axis : java.util.List.of("x", "y", "z")) {
            int value = WorldGoal.integer(input, axis);
            int bound = axis.equals("y") ? 2048 : 29999984;
            if (value < -bound || value > bound) throw new IllegalArgumentException("Invalid position");
            result.addProperty(axis, value);
        }
        int radius = input.has("radius") ? WorldGoal.integer(input, "radius") : 2;
        if (radius < 0 || radius > 4) throw new IllegalArgumentException("Invalid radius");
        result.addProperty("radius", radius);
        return result;
    }

    public static String id(JsonObject condition) {
        var normalized = condition(condition);
        // A building revision already identifies every cell, anchor and rotation.
        String identity = normalized.get("type").getAsString().equals("building")
                ? "building:" + normalized.get("expected_revision").getAsString() : normalized.toString();
        return fingerprint(identity);
    }

    static String fingerprint(String identity) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(identity.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    public static JsonObject verify(JsonObject condition, JsonObject initial, JsonObject fresh, String bodyId) {
        var spec = condition(condition);
        if (bodyId == null || bodyId.isBlank() || !bodyId.equals(WorldGoal.text(fresh, "body_id"))
                || !WorldGoal.flag(fresh, "alive")
                || !WorldGoal.text(initial, "dimension").equals(WorldGoal.text(fresh, "dimension")))
            throw new IllegalArgumentException("Milestone body unavailable");
        String type = WorldGoal.text(spec, "type");
        String facts;
        var evidence = new JsonObject();
        if (type.equals("item")) {
            var args = spec.deepCopy();
            args.remove("type");
            var target = ItemGoal.Target.parse(args.toString());
            var goal = new ItemGoal(target, initial);
            goal.observe(fresh);
            if (!goal.satisfied()) throw new IllegalArgumentException("Inventory condition unsatisfied");
            int before = ItemGoal.count(initial, target.item()), current = ItemGoal.count(fresh, target.item());
            evidence.addProperty("item", target.item());
            evidence.addProperty("baseline", before);
            evidence.addProperty("current", current);
            evidence.addProperty("progress", goal.progress());
            evidence.addProperty("count", target.count());
            evidence.addProperty("mode", target.additional() ? "additional" : "total");
            facts = "已核实背包成果：" + target.item() + "，当前总量=" + current + "，"
                    + (target.additional() ? "相对任务开始净新增=" + goal.progress() : "所需总量=" + target.count())
                    + "。只证明物品已在背包，不单独证明由谁制作或采得。";
        } else if (type.equals("building")) {
            var goal = new WorldGoal(spec, fresh);
            if (!goal.satisfied()) throw new IllegalArgumentException("Building condition unsatisfied");
            evidence.addProperty("target_revision", spec.get("expected_revision").getAsString());
            evidence.addProperty("satisfied", goal.progress());
            facts = "已核实建筑阶段：修订=" + spec.get("expected_revision").getAsString()
                    + "，该格集全部" + goal.progress() + "格满足。仅核实此格集，不替代整项建筑验收。";
        } else {
            if (!WorldGoal.text(spec, "dimension").equals(WorldGoal.text(fresh, "dimension"))
                    || !WorldGoal.flag(fresh, "on_ground")) throw new IllegalArgumentException("Position unavailable");
            long distance = 0;
            for (String axis : java.util.List.of("x", "y", "z")) {
                int value = WorldGoal.integer(fresh, axis);
                long delta = (long) value - WorldGoal.integer(spec, axis);
                if (Math.abs(delta) > WorldGoal.integer(spec, "radius"))
                    throw new IllegalArgumentException("Position condition unsatisfied");
                distance += delta * delta;
                evidence.addProperty(axis, value);
            }
            int radius = WorldGoal.integer(spec, "radius");
            if (distance > (long) radius * radius) throw new IllegalArgumentException("Position condition unsatisfied");
            evidence.addProperty("dimension", WorldGoal.text(fresh, "dimension"));
            facts = "已核实到达位置：" + evidence + "，身体已接地，位于目标" + spec
                    + "的允许范围内。坐标到达不单独证明已进入矿洞或区域安全。";
        }
        var report = new JsonObject();
        report.addProperty("id", id(spec));
        report.addProperty("kind", type);
        report.addProperty("verified", true);
        report.addProperty("facts", facts);
        report.add("evidence", evidence);
        return report;
    }
}
