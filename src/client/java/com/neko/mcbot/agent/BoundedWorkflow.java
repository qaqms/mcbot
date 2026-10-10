package com.neko.mcbot.agent;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.neko.mcbot.agentcore.loop.TaskPolicy;
import com.neko.mcbot.agentcore.loop.ToolExecutor.ToolOutcome;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiFunction;
import java.util.function.LongSupplier;

/** Fixed near-body steps; every action still crosses the server's normal permission gate. */
final class BoundedWorkflow {
    private static final Set<String> ALLOWED = Set.of("status", "inventory", "equip", "scan_area",
            "find_resource", "inspect_block", "break_block", "place_block", "craft", "smelt", "wait");
    record Step(String tool, JsonObject args) { }
    record Plan(List<Step> steps, int seconds, int itemRequests, int blockActions) {
        boolean readOnly() {
            return steps.stream().allMatch(step -> TaskPolicy.observation(step.tool(), step.args()));
        }
    }

    static Plan parse(String json) {
        if (json == null || json.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 16384)
            throw new IllegalArgumentException("参数须在16384字节内");
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        keys(root, Set.of("steps", "timeout_seconds"));
        int seconds = root.has("timeout_seconds") ? integer(root.get("timeout_seconds"), 1, 300) : 180;
        var array = root.getAsJsonArray("steps");
        if (array == null || array.isEmpty() || array.size() > 12)
            throw new IllegalArgumentException("步骤数须为1-12");
        List<Step> steps = new ArrayList<>();
        int items = 0, blocks = 0;
        for (JsonElement value : array) {
            JsonObject step = value.getAsJsonObject();
            keys(step, Set.of("tool", "args"));
            String tool = string(step.get("tool"), 32);
            if (!ALLOWED.contains(tool)) throw new IllegalArgumentException("不支持该子工具");
            JsonObject args = step.getAsJsonObject("args");
            if (args == null) throw new IllegalArgumentException("每步须有args对象");
            var spec = ClientToolDefs.SPECS.stream().filter(s -> s.name().equals(tool)).findFirst().orElseThrow();
            validate(args, JsonParser.parseString(spec.paramsJsonSchema()).getAsJsonObject());
            if (tool.equals("break_block") || tool.equals("place_block")) blocks++;
            if (tool.equals("place_block")) items++;
            if (tool.equals("wait")) integer(args.get("seconds"), 1, 60);
            if (tool.equals("scan_area") && args.has("r")) integer(args.get("r"), 1, 32);
            if (tool.equals("craft") && !args.has("query")) args.addProperty("query", false);
            if (tool.equals("craft") && !args.get("query").getAsBoolean())
                items += requiredCount(args, "count");
            if (tool.equals("smelt")) {
                String action = args.has("action") ? args.get("action").getAsString() : "query";
                Set<String> common = Set.of("x", "y", "z", "action");
                var permitted = new java.util.HashSet<>(common);
                if (action.equals("load")) {
                    permitted.addAll(Set.of("input_slot", "input_count", "fuel_slot", "fuel_count"));
                    if (!args.has("input_slot") && !args.has("fuel_slot"))
                        throw new IllegalArgumentException("装料须指定来源槽");
                    for (String role : List.of("input", "fuel")) {
                        if (args.has(role + "_slot")) items += requiredCount(args, role + "_count");
                        else if (args.has(role + "_count")) throw new IllegalArgumentException("数量缺少来源槽");
                    }
                } else if (action.equals("take")) {
                    permitted.addAll(Set.of("slot", "count"));
                    items += requiredCount(args, "count");
                }
                keys(args, permitted);
            }
            if (items > 128) throw new IllegalArgumentException("请求数量合计须不超过128件");
            steps.add(new Step(tool, args.deepCopy()));
        }
        return new Plan(List.copyOf(steps), seconds, items, blocks);
    }

    private static int requiredCount(JsonObject args, String key) {
        if (!args.has(key)) throw new IllegalArgumentException("动作须明确填写" + key);
        return integer(args.get(key), 1, 64);
    }

    private static void keys(JsonObject object, Set<String> allowed) {
        if (!allowed.containsAll(object.keySet())) throw new IllegalArgumentException("存在多余字段");
    }

    private static int integer(JsonElement value, int min, int max) {
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber())
            throw new IllegalArgumentException("数量/坐标须为整数");
        try {
            int n = value.getAsBigDecimal().intValueExact();
            if (n < min || n > max) throw new IllegalArgumentException("整数越界");
            return n;
        } catch (ArithmeticException ex) {
            throw new IllegalArgumentException("整数越界或包含小数");
        }
    }

    private static String string(JsonElement value, int max) {
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()
                || value.getAsString().isBlank() || value.getAsString().length() > max)
            throw new IllegalArgumentException("字符串无效");
        return value.getAsString();
    }

    private static void validate(JsonElement value, JsonObject schema) {
        switch (schema.get("type").getAsString()) {
            case "object" -> {
                JsonObject object = value.getAsJsonObject();
                JsonObject properties = schema.getAsJsonObject("properties");
                keys(object, properties.keySet());
                if (schema.has("required")) for (JsonElement key : schema.getAsJsonArray("required"))
                    if (!object.has(key.getAsString())) throw new IllegalArgumentException("缺少必要参数");
                object.entrySet().forEach(e -> validate(e.getValue(), properties.getAsJsonObject(e.getKey())));
            }
            case "integer" -> integer(value, schema.has("minimum") ? schema.get("minimum").getAsInt() : Integer.MIN_VALUE,
                    schema.has("maximum") ? schema.get("maximum").getAsInt() : Integer.MAX_VALUE);
            case "boolean" -> {
                if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean())
                    throw new IllegalArgumentException("布尔参数无效");
            }
            case "string" -> {
                string(value, schema.has("maxLength") ? schema.get("maxLength").getAsInt() : 128);
                if (schema.has("enum") && !schema.getAsJsonArray("enum").contains(value))
                    throw new IllegalArgumentException("枚举参数无效");
            }
            case "array" -> {
                var array = value.getAsJsonArray();
                if (array.size() < schema.get("minItems").getAsInt()
                        || array.size() > schema.get("maxItems").getAsInt())
                    throw new IllegalArgumentException("数组数量越界");
                array.forEach(e -> validate(e, schema.getAsJsonObject("items")));
            }
            default -> throw new IllegalArgumentException("不支持的参数结构");
        }
    }

    private final Plan plan;
    private final LongSupplier clock;
    private final BiFunction<String, String, CompletableFuture<ToolOutcome>> execute;
    private final Runnable abort;
    private final long started;
    private final List<ToolOutcome> receipts = new ArrayList<>();
    private final CompletableFuture<ToolOutcome> result = new CompletableFuture<>();
    private int index;
    private boolean stopped;
    private int receiptBytes;

    BoundedWorkflow(Plan plan, LongSupplier clock,
                    BiFunction<String, String, CompletableFuture<ToolOutcome>> execute, Runnable abort) {
        this.plan = plan;
        this.clock = clock;
        this.execute = execute;
        this.abort = abort;
        started = clock.getAsLong();
    }

    synchronized CompletableFuture<ToolOutcome> start() {
        next();
        return result;
    }

    synchronized boolean done() { return stopped; }
    synchronized void tick() {
        if (!stopped && expired()) stop("TIMEOUT:流程时限已到；在途步骤效果未知，先核对最新状态。");
    }

    synchronized void stop(String reason) {
        if (stopped) return;
        stopped = true;
        abort.run();
        finish(false, reason);
    }

    private boolean expired() { return clock.getAsLong() - started >= plan.seconds() * 1000L; }

    private void next() {
        if (stopped) return;
        if (expired()) { tick(); return; }
        if (index == plan.steps().size()) {
            stopped = true;
            finish(true, "全部步骤已取得成功终态；装料/等待不等于烧制完成，取出量以回执为准。");
            return;
        }
        Step step = plan.steps().get(index);
        CompletableFuture<ToolOutcome> future;
        try { future = java.util.Objects.requireNonNull(execute.apply(step.tool(), step.args().toString())); }
        catch (RuntimeException failure) {
            stop("INTERNAL:子步骤未取得有效回执；不要猜测效果或重发。");
            return;
        }
        future.whenComplete((receipt, failure) -> {
            synchronized (BoundedWorkflow.this) {
                if (stopped) return;
                if (expired()) { tick(); return; }
                if (failure != null || receipt == null || receipt.accepted()) {
                    stop("INTERNAL:子步骤未取得有效终态；不要猜测效果或重发。");
                    return;
                }
                receipts.add(receipt);
                index++;
                receiptBytes += receipt.feedback().getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
                JsonObject data = receipt.data();
                boolean noTargets = data.has("no_targets") && data.get("no_targets").isJsonPrimitive()
                        && data.get("no_targets").getAsJsonPrimitive().isBoolean() && data.get("no_targets").getAsBoolean();
                boolean missing = step.tool().equals("craft") && data.has("can_craft")
                        && data.get("can_craft").isJsonPrimitive() && data.get("can_craft").getAsJsonPrimitive().isBoolean()
                        && !data.get("can_craft").getAsBoolean();
                var state = data.get("state");
                boolean machineCheck = step.tool().equals("smelt")
                        && !("take".equals(step.args().has("action") ? step.args().get("action").getAsString() : "query"));
                boolean machineBlocked = machineCheck && state != null
                        && state.isJsonPrimitive() && state.getAsJsonPrimitive().isString()
                        && Set.of("MISSING_FUEL", "NO_RECIPE", "OUTPUT_BLOCKED", "NOT_TICKING").contains(state.getAsString());
                var enough = data.get("fuel_sufficient_for_input");
                boolean batchFuelMissing = machineCheck && state != null
                        && state.isJsonPrimitive() && state.getAsJsonPrimitive().isString()
                        && "COOKING".equals(state.getAsString()) && enough != null
                        && enough.isJsonPrimitive() && enough.getAsJsonPrimitive().isBoolean() && !enough.getAsBoolean();
                if (!receipt.ok() || noTargets || missing || machineBlocked || batchFuelMissing || receiptBytes > 12000) {
                    stopped = true;
                    finish(false, noTargets ? "NO_TARGETS:搜索未取得目标，后续步骤未执行。"
                            : missing ? "MISSING_MATERIALS:整批制作条件不足，后续步骤未执行。"
                            : machineBlocked || batchFuelMissing ? "MACHINE_BLOCKED:机器无法完成本批烧制，后续步骤未执行。"
                            : receiptBytes > 12000 ? "RESULT_LIMIT:回执预算已到，后续步骤未执行。"
                            : "STOPPED:子步骤失败或需要确认，后续步骤未执行。");
                } else next();
            }
        });
    }

    private void finish(boolean ok, String reason) {
        StringBuilder text = new StringBuilder(reason).append("\n终态回执 ")
                .append(receipts.size()).append('/').append(plan.steps().size())
                .append("；请求预算=").append(plan.itemRequests()).append("，明确挖放=").append(plan.blockActions());
        int bytes = 0;
        for (int i = 0; i < receipts.size(); i++) {
            ToolOutcome receipt = receipts.get(i);
            String line = "\n步骤" + (i + 1) + " " + plan.steps().get(i).tool() + " ok=" + receipt.ok()
                    + "：" + receipt.feedback();
            int size = line.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
            if (bytes + size > 24000) {
                text.append("\n回执过长，未展示此项全文；停止后续步骤，请按最新状态核对。");
                break;
            }
            bytes += size;
            text.append(line);
        }
        JsonObject data = new JsonObject();
        data.addProperty("terminal_steps", receipts.size());
        data.addProperty("total_steps", plan.steps().size());
        for (int i = 0; i < receipts.size(); i++) {
            if (Set.of("scan_area", "find_resource").contains(plan.steps().get(i).tool())
                    && receipts.get(i).data().has("no_targets"))
                data.add("no_targets", receipts.get(i).data().get("no_targets"));
        }
        for (String key : List.of("crafted_count", "consumed_count", "collected_count",
                "loaded_input_count", "loaded_fuel_count", "taken_count")) {
            long sum = 0;
            for (ToolOutcome receipt : receipts) {
                var value = receipt.data().get(key);
                if (value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber())
                    sum += Math.max(0, value.getAsLong());
            }
            if (sum > 0) data.addProperty(key, sum);
        }
        result.complete(new ToolOutcome(ok, text.toString(), data));
    }
}
