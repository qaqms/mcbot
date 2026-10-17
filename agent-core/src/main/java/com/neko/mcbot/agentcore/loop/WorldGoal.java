package com.neko.mcbot.agentcore.loop;

import com.google.gson.JsonObject;

import java.util.List;
import java.util.Set;

/** Fixed world conditions; only fresh, complete server evidence may satisfy them. */
public final class WorldGoal {
    private final JsonObject request;
    private final String type, dimension, bodyId, containerId;
    private final ItemGoal.Target delivery;
    private final int baseline;
    private final long balanceBaseline;
    private int current, progress, total;
    private boolean satisfied;
    private boolean available = true;
    private String fingerprint;

    public static JsonObject request(JsonObject args) {
        String type = text(args, "type");
        Set<String> allowed = type.equals("building") ? Set.of("type", "target", "expected_revision")
                : type.equals("delivery") ? Set.of("type", "container", "item", "count", "mode") : Set.of();
        if (!allowed.containsAll(args.keySet()) || allowed.isEmpty()) throw new IllegalArgumentException();
        var result = args.deepCopy();
        if (type.equals("building")) {
            if (!text(args, "expected_revision").matches("[0-9a-f]{64}")
                    || !args.has("target") || !args.get("target").isJsonObject()) throw new IllegalArgumentException();
        } else {
            var itemArgs = new JsonObject();
            for (String key : List.of("item", "count", "mode")) if (args.has(key)) itemArgs.add(key, args.get(key));
            var item = ItemGoal.Target.parse(itemArgs.toString());
            result.addProperty("item", item.item());
            result.addProperty("mode", item.additional() ? "additional" : "total");
            var container = args.getAsJsonObject("container");
            if (!container.keySet().equals(Set.of("x", "y", "z"))) throw new IllegalArgumentException();
            for (String axis : List.of("x", "y", "z")) integer(container, axis);
        }
        if (result.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 20000)
            throw new IllegalArgumentException();
        return result;
    }

    public WorldGoal(JsonObject spec, JsonObject state) {
        request = request(spec);
        type = text(request, "type");
        dimension = text(state, "dimension");
        bodyId = text(state, "body_id");
        var evidence = state.getAsJsonObject("goal_evidence");
        if (evidence == null) throw new IllegalArgumentException("Missing initial goal evidence");
        if (type.equals("delivery")) {
            var itemArgs = new JsonObject();
            for (String key : List.of("item", "count", "mode")) itemArgs.add(key, request.get(key));
            delivery = ItemGoal.Target.parse(itemArgs.toString());
            containerId = text(evidence, "container_id");
            baseline = integer(evidence, "item_total");
            balanceBaseline = longNumber(evidence, "delivery_balance");
        } else {
            delivery = null; containerId = null; baseline = 0; balanceBaseline = 0;
        }
        observe(state);
    }

    public JsonObject request() { return request.deepCopy(); }
    public boolean matches(JsonObject spec) { return request.equals(request(spec)); }
    public int progress() { return progress; }
    public boolean satisfied() { return satisfied; }
    public String fingerprint() { return fingerprint; }

    public void observeBody(JsonObject state) {
        if (!dimension.equals(text(state, "dimension")) || !bodyId.equals(text(state, "body_id"))
                || !flag(state, "alive")) throw new IllegalArgumentException("Body identity unavailable");
    }

    public void observe(JsonObject state) {
        observeBody(state);
        if (state.has("goal_evidence_unavailable") && flag(state, "goal_evidence_unavailable")
                && "OUT_OF_REACH".equals(text(state, "goal_evidence_reason"))) {
            available = false; satisfied = false; progress = 0; fingerprint = "out_of_reach";
            return;
        }
        available = true;
        var data = state.getAsJsonObject("goal_evidence");
        if (type.equals("building")) {
            if (!text(request, "expected_revision").equals(text(data, "target_revision"))
                    || !flag(data, "observation_complete") || !flag(data, "spatial_stable")
                    || flag(data, "budget_exhausted")) throw new IllegalArgumentException("Incomplete building facts");
            total = integer(data, "total");
            var counts = data.getAsJsonObject("counts");
            int sum = 0;
            for (var value : counts.entrySet()) {
                int count = integer(counts, value.getKey());
                if (count < 0) throw new IllegalArgumentException();
                sum = Math.addExact(sum, count);
            }
            progress = integer(counts, "satisfied");
            if (total < 1 || total > 128 || sum != total || progress < 0 || progress > total
                    || integer(data, "remaining") != total - progress || integer(counts, "unknown") != 0)
                throw new IllegalArgumentException();
            satisfied = flag(data, "goal_satisfied") && progress == total && integer(counts, "unsupported") == 0;
            fingerprint = text(data, "goal_observation_key");
            if (!fingerprint.matches("[0-9a-f]{64}")) throw new IllegalArgumentException();
        } else {
            if (!dimension.equals(text(data, "dimension")) || !containerId.equals(text(data, "container_id"))
                    || !flag(data, "container") || !flag(data, "container_identity_known")
                    || !flag(data, "contents_complete") || !delivery.item().equals(text(data, "item")))
                throw new IllegalArgumentException("Container identity unavailable");
            for (String axis : List.of("x", "y", "z"))
                if (integer(request.getAsJsonObject("container"), axis) != integer(data, axis))
                    throw new IllegalArgumentException();
            current = integer(data, "item_total");
            if (current < 0 || integer(data, "slot_count") < 1 || integer(data, "slot_count") > 1024)
                throw new IllegalArgumentException();
            long balance = longNumber(data, "delivery_balance");
            if (delivery.additional() && !flag(data, "delivery_ledger_complete"))
                throw new IllegalArgumentException("Unobserved transfer");
            progress = delivery.additional() ? (int) Math.min(Integer.MAX_VALUE, Math.max(0,
                    Math.min((long) current - baseline, Math.subtractExact(balance, balanceBaseline)))) : current;
            total = delivery.count();
            satisfied = progress >= total;
            fingerprint = current + "|" + (delivery.additional() ? balance : 0);
        }
    }

    public String facts() {
        return (available ? "上次独立核验：" : "本次目标现场超距，尚不可验收；登记条件保持：")
                + (type.equals("building") ? "建筑目标修订=" + text(request, "expected_revision")
                + "，完整现场已满足=" + progress + "/" + total
                + "；局部批次、放置次数和跳过格不替代全目标核验。"
                : "容器交付目标=" + delivery.item() + "@" + request.get("container")
                + "，口径=" + (delivery.additional() ? "登记后本任务已核实净存入且容器净新增" : "当前容器总量")
                + "，登记基线=" + baseline + "，当前总量=" + current + "，实际进度=" + progress + "/" + total
                + "；只验同一已知本体容器，不证明双箱整体、接收者或物品独占来源。");
    }

    static String text(JsonObject data, String name) {
        var value = data.get(name);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()
                || value.getAsString().isBlank() || value.getAsString().length() > 128) throw new IllegalArgumentException();
        return value.getAsString();
    }
    static boolean flag(JsonObject data, String name) {
        var value = data.get(name);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean())
            throw new IllegalArgumentException();
        return value.getAsBoolean();
    }
    static int integer(JsonObject data, String name) { return Math.toIntExact(longNumber(data, name)); }
    static long longNumber(JsonObject data, String name) {
        var value = data.get(name);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber())
            throw new IllegalArgumentException();
        return value.getAsBigDecimal().longValueExact();
    }
}
