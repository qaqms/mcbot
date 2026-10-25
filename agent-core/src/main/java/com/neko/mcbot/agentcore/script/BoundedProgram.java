package com.neko.mcbot.agentcore.script;

import com.google.gson.*;
import com.neko.mcbot.agentcore.loop.ToolExecutor.ToolOutcome;
import com.neko.mcbot.agentcore.loop.TaskPolicy;
import com.neko.mcbot.agentcore.loop.WorkFailureMemory;
import org.luaj.vm2.*;
import org.luaj.vm2.compiler.LuaC;
import org.luaj.vm2.lib.BaseLib;
import org.luaj.vm2.lib.DebugLib;
import org.luaj.vm2.lib.VarArgFunction;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/** Lua controls data and sequencing; only the host may dispatch a game action. */
public final class BoundedProgram {
    private static final Set<String> GLOBALS =
            Set.of("assert", "error", "ipairs", "pairs", "next", "tonumber", "tostring", "type");
    public static final String LANGUAGE_GUIDE =
            "支持Lua变量/表/函数/条件/循环和assert/error/ipairs/pairs/next/tonumber/tostring/type/print；"
            + "不提供string/table/math库、pcall、require、元表或外部访问。"
            + "追加列表用t[#t+1]=v；查看表直接print(t)或return t，不用tostring(t)。"
            + "mc.build(target,{access_route?,bounds?,max_rounds?,max_actions?,temporary?})或mc.build(target)"
            + "组合清障和多站位施工，导航按原权限挖放，无需手写进出通道；"
            + "mc.build直接返回{status,goal_satisfied,placed_blocks,cleared_blocks,remaining,blocked}并自动输出结果，"
            + "不包在data里；mc.build_diff(target)只读全目标统计，大于32格返回有限明细，"
            + "mc.build_diff(target,revision,offset)按next_offset续页，不在Lua攒万格大表。"
            + "已登记建筑可直接mc.build()或mc.build_diff()，mc.goal()返回当前登记条件副本；"
            + "无需重新生成原图或把内容修订号当instance_id。"
            + "身体挡格或出口受阻时mc.build先按工地事实换位、临时开路或安全下落，原图待补格延后恢复；"
            + "高处工作可由导航按真实材料与点击面连续起跳垫柱，不必逐格手写支撑。"
            + "完成后自动尝试mc.cleanup_temporary(revision)，从高到低重查依赖，临时柱逐格拆降并等待落稳。";
    public record Plan(String code, int seconds, int maxCalls) {
        public static Plan parse(String json) {
            if (json == null || bytes(json) > 20000) throw new IllegalArgumentException();
            var args = JsonParser.parseString(json).getAsJsonObject();
            if (!Set.of("code", "timeout_seconds", "max_calls").containsAll(args.keySet()))
                throw new IllegalArgumentException();
            var code = args.get("code");
            if (code == null || !code.isJsonPrimitive() || !code.getAsJsonPrimitive().isString()
                    || code.getAsString().isBlank() || bytes(code.getAsString()) > 16384)
                throw new IllegalArgumentException();
            return new Plan(code.getAsString(), integer(args, "timeout_seconds", 0, 0, 900),
                    integer(args, "max_calls", 0, 0, Integer.MAX_VALUE));
        }
        private static int integer(JsonObject args, String key, int fallback, int min, int max) {
            if (!args.has(key)) return fallback;
            var value = args.get(key);
            if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber())
                throw new IllegalArgumentException();
            int n = value.getAsBigDecimal().intValueExact();
            if (n < min || n > max) throw new IllegalArgumentException();
            return n;
        }
    }

    private static final int SLICE = 25000;
    private static final int CELLS = 65536;
    private static final int STRING_BYTES = 8 * 1024 * 1024;
    private static final int VALUE_BYTES = 65536;
    private static final int OUTPUT_BYTES = 20000;
    private static final Set<String> COUNTS = Set.of("crafted_count", "consumed_count", "collected_count",
            "loaded_input_count", "loaded_fuel_count", "taken_count");
    private final Plan plan;
    private final LongSupplier clock;
    private final BiFunction<String, String, CompletableFuture<ToolOutcome>> execute;
    private final Consumer<Runnable> dispatch;
    private final Runnable abort;
    private final JsonObject registeredGoal;
    private final CompletableFuture<ToolOutcome> result = new CompletableFuture<>();
    private final CompletableFuture<Void> terminated = new CompletableFuture<>();
    private final StringBuilder receipts = new StringBuilder();
    private final Map<String, Integer> feedbackRefs = new LinkedHashMap<>();
    private record QueryReceipt(int number, String name) { }
    private final Map<String, QueryReceipt> queryRefs = new LinkedHashMap<>();
    private final Map<String, Integer> queryNoteRefs = new LinkedHashMap<>();
    private final Map<String, Long> counts = new LinkedHashMap<>();
    private volatile boolean halted;
    private volatile Thread worker;
    private volatile CompletableFuture<ToolOutcome> waiting;
    private volatile int calls;
    private volatile int completed;
    private volatile ToolOutcome last;
    private volatile String lastTool = "";
    private volatile long started;
    private int slice, cells, strings, outputBytes;
    private int queryCalls, expandedQueries, recoveredFailures;
    private int constructionQueries;
    private final Map<String, Integer> constructionActions = new LinkedHashMap<>();
    private int constructionReceiptBytes, constructionEffectsOmitted;
    private boolean constructing, lastQuerySuppressed;
    private boolean needsBodyCheck;
    private volatile int sourceLine, opcode = -1;
    private volatile boolean compiled;
    private JsonObject programError;
    private Globals runtimeGlobals;
    private final Budget budget = new Budget();

    public BoundedProgram(Plan plan, LongSupplier clock,
                          BiFunction<String, String, CompletableFuture<ToolOutcome>> execute,
                          Consumer<Runnable> dispatch, Runnable abort) {
        this(plan, clock, execute, dispatch, abort, null);
    }

    public BoundedProgram(Plan plan, LongSupplier clock,
                          BiFunction<String, String, CompletableFuture<ToolOutcome>> execute,
                          Consumer<Runnable> dispatch, Runnable abort, JsonObject registeredGoal) {
        this.plan = plan;
        this.clock = clock;
        this.execute = execute;
        this.dispatch = dispatch;
        this.abort = abort;
        this.registeredGoal = registeredGoal == null ? null : registeredGoal.deepCopy();
    }

    public synchronized CompletableFuture<ToolOutcome> start() {
        if (worker != null || halted) return result;
        started = clock.getAsLong();
        worker = Thread.ofVirtual().name("mcbot-program").unstarted(this::run);
        worker.start();
        return result;
    }

    public boolean done() { return result.isDone(); }
    public CompletableFuture<Void> terminated() { return terminated; }
    public void tick() {
        if (!done() && worker != null && expired()) stop("TIMEOUT:组合执行时限已到。");
    }
    public synchronized void stop(String reason) {
        if (done()) return;
        halted = true;
        if (waiting != null) waiting.completeExceptionally(new Halt(reason));
        if (worker != null) worker.interrupt();
        else terminated.complete(null);
        abort.run();
        finish(false, reason + " 已完成动作保留，在途效果可能未知。", JsonNull.INSTANCE);
    }
    private boolean expired() { return plan.seconds() > 0 && clock.getAsLong() - started >= plan.seconds() * 1000L; }
    private void check() {
        if (halted || Thread.currentThread().isInterrupted()) throw new Halt("CANCELLED:组合执行已停止。");
        if (expired()) throw new Halt("TIMEOUT:组合执行时限已到。");
    }

    private void run() {
        try {
            var globals = new Globals();
            runtimeGlobals = globals;
            globals.load(new BaseLib());
            // Keep only bounded pure helpers; no loaders, metatables, protected calls or external libraries.
            for (LuaValue key : globals.keys()) if (!GLOBALS.contains(key.tojstring())) globals.set(key, LuaValue.NIL);
            LuaC.install(globals);
            globals.debuglib = budget;
            var api = new LuaTable();
            api.set("call", bridge(false));
            api.set("try_call", bridge(true));
            api.set("goal", new VarArgFunction() {
                @Override public Varargs invoke(Varargs args) {
                    check();
                    if (args.narg() != 0) throw new Halt("DENIED:mc.goal()不接受参数。");
                    reclaimTransientValues();
                    return registeredGoal == null ? LuaValue.NIL : fromJson(registeredGoal, 0);
                }
            });
            globals.set("mc", api);
            globals.set("print", new VarArgFunction() {
                @Override public Varargs invoke(Varargs args) {
                    if (args.narg() > 32) throw new Halt("RESULT_LIMIT:打印参数过多。");
                    var parts = new ArrayList<String>();
                    for (int i = 1; i <= args.narg(); i++)
                        parts.add(display(toJson(args.arg(i), 0, new IdentityHashMap<>(), new int[]{0})));
                    append("输出 " + String.join(" ", parts));
                    return LuaValue.NONE;
                }
            });
            try (var source = BoundedProgram.class.getResourceAsStream("construction.lua")) {
                if (source == null) throw new IllegalStateException("missing construction helper");
                byte[] text = source.readNBytes(32769);
                if (text.length > 32768) throw new IllegalStateException("oversized construction helper");
                globals.load(new String(text, StandardCharsets.UTF_8), "construction-helper").call();
            } catch (java.io.IOException unavailable) { throw new IllegalStateException(unavailable); }
            var build = api.get("build");
            api.set("build", new VarArgFunction() {
                @Override public Varargs invoke(Varargs args) {
                    boolean previous = constructing;
                    constructing = true;
                    try { return build.invoke(args); }
                    finally { constructing = previous; }
                }
            });
            check();
            var chunk = globals.load(plan.code(), "task-program");
            compiled = true;
            var value = chunk.call();
            check();
            JsonElement returned = toJson(value, 0, new IdentityHashMap<>(), new int[]{0});
            if (bytes(returned.toString()) > 12000 || bytes(display(returned)) + outputBytes > OUTPUT_BYTES)
                throw new Halt("RESULT_LIMIT:返回值与阶段回执合计过大。");
            dispatch.accept(() -> {
                if (!done()) {
                    if (expired()) stop("TIMEOUT:组合执行时限已到。");
                    else finish(true, "组合执行结束；子步骤结果已核对，不代表主人整项目标完成。", returned);
                }
            });
        } catch (Halt stopped) {
            dispatch.accept(() -> stop(stopped.reason));
        } catch (LuaError invalid) {
            String kind = !compiled ? "SYNTAX" : switch (opcode) {
                case Lua.OP_GETTABLE, Lua.OP_GETTABUP, Lua.OP_SELF -> "FIELD_ACCESS";
                case Lua.OP_CALL, Lua.OP_TAILCALL -> "FUNCTION_CALL";
                case Lua.OP_ADD, Lua.OP_SUB, Lua.OP_MUL, Lua.OP_DIV, Lua.OP_MOD, Lua.OP_POW,
                        Lua.OP_UNM, Lua.OP_LEN, Lua.OP_CONCAT -> "VALUE_TYPE";
                default -> "LUA_RUNTIME";
            };
            int line = sourceLine;
            if (!compiled) {
                // Only the compiler's numeric location is exposed, never its source excerpt or error value.
                var location = java.util.regex.Pattern.compile("^(?:task-program|\\[string \"task-program\"\\]):(\\d{1,5}):")
                        .matcher(Objects.toString(invalid.getMessage(), ""));
                if (location.find()) line = Integer.parseInt(location.group(1));
            }
            int failedLine = line;
            dispatch.accept(() -> programFailed(kind, failedLine));
        } catch (IllegalArgumentException | StackOverflowError invalid) {
            int failedLine = sourceLine;
            dispatch.accept(() -> programFailed("VALUE_OR_DEPTH", failedLine));
        } catch (RuntimeException unexpected) {
            dispatch.accept(() -> stop("INTERNAL:组合执行未取得有效结果，未猜测或重发。"));
        } finally {
            terminated.complete(null);
        }
    }

    private synchronized void programFailed(String kind, int line) {
        if (done()) return;
        programError = new JsonObject();
        programError.addProperty("kind", kind);
        programError.addProperty("line", line);
        // The query was useful to Lua, but a broken script may never have printed its blocking facts.
        if (last != null && (lastQuerySuppressed || queryRefs.containsKey(last.feedback()))
                && !feedbackRefs.containsKey(last.feedback()))
            queryNote(last.feedback(), Math.min(2048, OUTPUT_BYTES - outputBytes - 128));
        String hint = "按tool_help返回字段读取，缺字段先检查nil；mc.call返回ok/feedback/data，"
                + "mc.build直接返回施工结果。不提供string/table/math库，列表追加用t[#t+1]=v。"
                + "表用print或return，不用tostring；完整语法与分页说明见tool_help run_program。";
        stop("PROGRAM_ERROR:" + kind + "，脚本行=" + line + "。" + hint + "未重试动作。");
    }

    private VarArgFunction bridge(boolean allowLocalFailure) {
        return new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) {
                check();
                if (args.narg() != 2 || args.arg(1).type() != LuaValue.TSTRING || !args.arg(2).istable())
                    throw new Halt("DENIED:mc.call/try_call须为工具名字符串与参数表。");
                reclaimTransientValues();
                String name = args.arg(1).tojstring();
                if (name.length() > 32) throw new Halt("DENIED:工具名无效。");
                JsonElement json = toJson(args.arg(2), 0, new IdentityHashMap<>(), new int[]{0});
                if (!json.isJsonObject() || bytes(json.toString()) > 16384)
                    throw new Halt("DENIED:工具参数须为有界对象。");
                ToolOutcome receipt = BoundedProgram.this.call(name, json.toString(), allowLocalFailure);
                var value = new JsonObject();
                value.addProperty("ok", receipt.ok());
                value.addProperty("feedback", receipt.feedback());
                String kind = receipt.feedback().split(":", 2)[0];
                value.addProperty("failure_kind", receipt.ok() || !kind.matches("[A-Z_]{1,32}") ? "" : kind);
                value.add("data", helperData(name, receipt));
                slice = 0;
                return fromJson(value, 0);
            }
        };
    }

    private JsonObject helperData(String name, ToolOutcome receipt) {
        var data = receipt.data();
        if (!constructing || !receipt.ok()) return data;
        if (name.equals("status")) return new JsonObject();
        if (name.equals("move_to")) {
            var view = new JsonObject();
            if (data.has("placed_blocks")) view.add("placed_blocks", data.get("placed_blocks"));
            return view;
        }
        if (name.equals("break_block") || name.equals("build_nearby")) {
            var view = new JsonObject();
            for (String key : List.of("removed", "placed_blocks", "consumed_count", "placement_results"))
                if (data.has(key)) view.add(key, data.get(key));
            return view;
        }
        if (!name.equals("build_diff")) return data;
        // Only mc.build's private calls use this view; raw mc.call and mc.build_diff retain the complete response.
        var view = new JsonObject();
        for (String key : List.of("target_revision", "observation_complete", "spatial_stable", "budget_exhausted",
                "observation_key", "work_observation_key", "design_components", "goal_satisfied", "offset", "next_offset", "total", "remaining",
                "material_bill_complete", "work_scan", "work_next_cursor", "counts", "body_obstructs_target"))
            if (data.has(key)) view.add(key, data.get(key));
        var cells = new JsonArray();
        for (var value : data.getAsJsonArray("cells")) {
            var cell = value.getAsJsonObject();
            var selected = new JsonObject();
            selected.add("state", cell.get("state"));
            if ("satisfied".equals(cell.get("state").getAsString())) {
                cells.add(selected);
                continue;
            }
            for (String key : List.of("x", "y", "z", "block", "actual_block", "source_offset",
                    "clear_allowed", "clear_reason", "unsupported_reason", "placement_failure", "repair_pending"))
                if (cell.has(key)) selected.add(key, cell.get(key));
            if ("out_of_reach".equals(cell.get("state").getAsString())
                    || "blocked".equals(cell.get("state").getAsString())
                    || "state_conflict".equals(cell.get("state").getAsString())) {
                for (String key : List.of("item", "material_available"))
                    if (cell.has(key)) selected.add(key, cell.get(key));
            }
            cells.add(selected);
        }
        view.add("cells", cells);
        return view;
    }

    private ToolOutcome call(String name, String args, boolean allowLocalFailure) {
        check();
        var arguments = JsonParser.parseString(args).getAsJsonObject();
        if (needsBodyCheck && !TaskPolicy.observation(name, arguments))
            throw new Halt("STATE_RECHECK_REQUIRED:局部失败后须先status details=true核对位置、维度和完整背包，未派发后续动作。");
        if (plan.maxCalls() > 0 && calls >= plan.maxCalls())
            throw new Halt("ACTION_LIMIT:已达到本次显式指定的调用上限。");
        if (calls < Integer.MAX_VALUE) calls++;
        var response = new CompletableFuture<ToolOutcome>();
        waiting = response;
        dispatch.accept(() -> {
            if (halted || done()) { response.completeExceptionally(new Halt("CANCELLED:组合执行已停止。")); return; }
            if (expired()) { stop("TIMEOUT:组合执行时限已到。"); return; }
            try {
                Objects.requireNonNull(execute.apply(name, args)).whenComplete((receipt, failure) -> {
                    if (failure != null || receipt == null || receipt.accepted())
                        response.completeExceptionally(new Halt("INTERNAL:子调用未取得真正终态，未重发。"));
                    else response.complete(receipt);
                });
            } catch (RuntimeException invalid) {
                response.completeExceptionally(new Halt("INTERNAL:子调用未派发有效结果。"));
            }
        });
        ToolOutcome receipt;
        try { receipt = response.get(); }
        catch (InterruptedException interrupted) { throw new Halt("CANCELLED:组合执行已停止。"); }
        catch (ExecutionException failed) {
            if (failed.getCause() instanceof Halt stopped) throw stopped;
            throw new Halt("INTERNAL:子调用结果不可用。");
        } finally { waiting = null; }
        check();
        if (receipt.feedback() == null || bytes(receipt.feedback()) > 24000
                || bytes(receipt.data().toString()) > VALUE_BYTES)
            throw new Halt("RESULT_LIMIT:子调用回执过大或无效。");
        synchronized (this) {
            check();
            last = receipt;
            lastTool = name;
            completed++;
            for (String key : COUNTS) {
                var amount = receipt.data().get(key);
                if (amount != null && amount.isJsonPrimitive() && amount.getAsJsonPrimitive().isNumber())
                    counts.merge(key, Math.max(0, amount.getAsLong()), Long::sum);
            }
            recordReceipt(name, arguments, receipt);
        }
        var data = receipt.data();
        if (!receipt.ok()) {
            if (!allowLocalFailure || !WorkFailureMemory.localFailure(name, receipt.feedback()))
                throw new Halt("CHILD_STOPPED:子调用失败或需要确认，后续动作未执行。");
            recoveredFailures++;
            needsBodyCheck = true;
        } else if (name.equals("status") && arguments.has("details")
                && arguments.get("details").isJsonPrimitive()
                && arguments.get("details").getAsJsonPrimitive().isBoolean()
                && arguments.get("details").getAsBoolean() && completeBody(data)) {
            needsBodyCheck = false;
        }
        return receipt;
    }

    private static boolean completeBody(JsonObject data) {
        for (String key : List.of("x", "y", "z")) {
            var value = data.get(key);
            if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()
                    || !Double.isFinite(value.getAsDouble())) return false;
        }
        var dimension = data.get("dimension");
        var inventory = data.get("inventory");
        return dimension != null && dimension.isJsonPrimitive() && dimension.getAsJsonPrimitive().isString()
                && !dimension.getAsString().isBlank() && inventory != null && inventory.isJsonObject()
                && inventory.getAsJsonObject().has("slots")
                && inventory.getAsJsonObject().get("slots").isJsonArray();
    }

    private synchronized void append(String line) {
        int size = bytes(line) + 1;
        if (outputBytes + size > OUTPUT_BYTES) throw new Halt("RESULT_LIMIT:组合执行回执预算已到。");
        outputBytes += size;
        receipts.append('\n').append(line);
    }
    private synchronized void recordReceipt(String name, JsonObject args, ToolOutcome receipt) {
        lastQuerySuppressed = false;
        if (constructing && receipt.ok() && name.equals("build_site")
                && "checkpoint".equals(args.get("operation").getAsString())) {
            constructionReceipt(name, new JsonObject());
            return;
        }
        if (receipt.ok() && TaskPolicy.observation(name, args)) {
            queryCalls++;
            if (name.equals("tool_help")) {
                // Instructions are for the next model turn even when help was called inside a program.
                append("回执#" + completed + " " + name + " ok=true：" + display(new JsonPrimitive(receipt.feedback())));
                feedbackRefs.put(receipt.feedback(), completed);
                return;
            }
            if (constructing && (name.equals("build_diff") || name.equals("status") || name.equals("build_site"))) {
                // The helper consumes full facts and emits a final result. Repeating every page can stop useful work.
                constructionQueries++;
                lastQuerySuppressed = true;
                return;
            }
            var signals = new JsonObject();
            for (String key : List.of("no_targets", "can_craft", "preflight_passed", "truncated",
                    "unknown", "unloaded", "scan_complete", "coverage_complete", "results_truncated",
                    "work_truncated", "next_offset", "in_water", "eyes_in_water", "in_lava", "water_recovery",
                    "goal_satisfied", "observation_complete", "spatial_stable", "material_bill_complete",
                    "work_assessment_complete", "budget_exhausted", "material_next_offset", "target_revision",
                    "remaining", "page_remaining", "page_satisfied",
                    "defense_active", "defense_state", "threat_scan_complete",
                    "environment_active", "environment_state", "environment_work_safe", "air_supply", "max_air_supply",
                    "maintenance_active", "maintenance_state", "using_item", "saturation",
                    "body_id", "body_state", "alive")) {
                var value = receipt.data().get(key);
                if (value != null && value.isJsonPrimitive() && bytes(value.toString()) <= 128)
                    signals.add(key, value);
            }
            append("回执#" + completed + " " + name + " ok=true（查询数据供程序使用）"
                    + (signals.isEmpty() ? "" : " " + signals));
            queryRefs.put(receipt.feedback(), new QueryReceipt(completed, name));
            if (falseFlag(receipt.data(), "can_craft") || falseFlag(receipt.data(), "preflight_passed")) {
                var failure = receipt.data().get("failure");
                queryNote(failure != null && failure.isJsonPrimitive() && failure.getAsJsonPrimitive().isString()
                        ? failure.getAsString() : receipt.feedback(), 1024);
            }
        } else {
            boolean plainArrival = name.equals("move_to") && receipt.data().has("x") && receipt.data().has("y")
                    && receipt.data().has("z") && receipt.data().has("removed_blocks")
                    && receipt.data().get("removed_blocks").getAsInt() == 0 && receipt.data().has("placed_blocks")
                    && receipt.data().get("placed_blocks").getAsInt() == 0;
            if (constructing && receipt.ok() && (name.equals("break_block") && receipt.data().has("removed")
                    || name.equals("build_nearby") && receipt.data().has("placed_blocks") || plainArrival)) {
                var effect = new JsonObject();
                for (String key : List.of("x", "y", "z")) if (args.has(key)) effect.add(key, args.get(key));
                if (plainArrival) for (String key : List.of("x", "y", "z", "feet_y", "arrival", "removed_blocks"))
                    if (receipt.data().has(key)) effect.add(key, receipt.data().get(key));
                for (String key : List.of("removed", "changed", "placed_blocks", "consumed_count",
                        "goal_satisfied", "final_verified", "placement_results_complete", "drop_accounting_known"))
                    if (receipt.data().has(key)) effect.add(key, receipt.data().get(key));
                var accounting = receipt.data().get("drop_accounting");
                if (accounting != null && accounting.isJsonObject()) {
                    var drops = new JsonObject();
                    for (String key : List.of("spawned_count", "live_count", "picked_up_self_count",
                            "picked_up_other_count", "destroyed_count", "unresolved_count", "accounting_complete"))
                        if (accounting.getAsJsonObject().has(key)) drops.add(key, accounting.getAsJsonObject().get(key));
                    effect.add("drop_accounting", drops);
                } else if (accounting != null && accounting.isJsonPrimitive()
                        && accounting.getAsJsonPrimitive().isString()) {
                    // Navigation reports its accounting method; single-block mining reports a source object.
                    effect.add("drop_accounting", accounting);
                    for (String key : List.of("observed_drop_items", "remaining_items", "picked_up_self_items",
                            "picked_up_other_items", "destroyed_drop_items", "unresolved_drop_items",
                            "remaining_items_complete", "remaining_drops_truncated", "drop_origins_truncated"))
                        if (receipt.data().has(key)) effect.add(key, receipt.data().get(key));
                }
                var placements = receipt.data().getAsJsonArray("placement_results");
                if (placements != null) {
                    var cells = new JsonArray();
                    for (var placement : placements) {
                        var cell = new JsonObject();
                        for (String key : List.of("x", "y", "z", "block", "ok"))
                            if (placement.getAsJsonObject().has(key))
                                cell.add(key, placement.getAsJsonObject().get(key));
                        cells.add(cell);
                    }
                    effect.add("placement_results", cells);
                }
                constructionReceipt(name, effect);
                return;
            }
            append("回执#" + completed + " " + name + " ok=" + receipt.ok() + "：" + receipt.feedback());
            feedbackRefs.put(receipt.feedback(), completed);
        }
    }

    private void constructionReceipt(String name, JsonObject effect) {
        int seen = constructionActions.merge(name, 1, Integer::sum);
        String line = "回执#" + completed + " " + name + " ok=true " + effect;
        int size = bytes(line) + 1;
        // Full receipts remain in the host's panel/events. Leave room for failures and the final world diff.
        if (seen <= 4 && constructionReceiptBytes + size <= 6000) {
            append(line);
            constructionReceiptBytes += size;
        } else constructionEffectsOmitted++;
    }

    private static boolean falseFlag(JsonObject data, String name) {
        var value = data.get(name);
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean()
                && !value.getAsBoolean();
    }

    private void queryNote(String text, int budget) {
        if (budget < 128 || queryNoteRefs.containsKey(text)) return;
        String shown = text;
        if (bytes(shown) > budget) {
            int end = 0, used = 0;
            while (end < text.length()) {
                int next = end + Character.charCount(text.codePointAt(end));
                int size = bytes(text.substring(end, next));
                if (used + size > budget - 64) break;
                used += size;
                end = next;
            }
            shown = text.substring(0, end) + " [说明截短，完整值仍在feedback/data中]";
        }
        append("查询提示#" + completed + "：" + shown);
        queryNoteRefs.put(text, completed);
    }

    private synchronized String display(JsonElement value) {
        return referenced(value).toString();
    }

    // Only the display refers to text already present in this result; Lua and returned data stay intact.
    private JsonElement referenced(JsonElement value) {
        if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) {
            String text = value.getAsString();
            Integer ref = feedbackRefs.get(text);
            QueryReceipt query = queryRefs.get(text);
            if (ref == null && query != null) {
                append("查询全文#" + query.number() + " " + query.name() + "：" + text);
                expandedQueries++;
                feedbackRefs.put(text, query.number());
                ref = query.number();
            }
            if (ref != null) return new JsonPrimitive("见回执#" + ref);
        }
        if (value.isJsonArray()) {
            var shown = new JsonArray();
            value.getAsJsonArray().forEach(entry -> shown.add(referenced(entry)));
            return shown;
        }
        if (value.isJsonObject()) {
            var shown = new JsonObject();
            value.getAsJsonObject().entrySet().forEach(entry -> shown.add(entry.getKey(), referenced(entry.getValue())));
            return shown;
        }
        return value;
    }
    private synchronized void finish(boolean ok, String reason, JsonElement returned) {
        if (done()) return;
        var data = new JsonObject();
        data.addProperty("api_calls", calls);
        data.addProperty("terminal_calls", completed);
        data.addProperty("query_calls", queryCalls);
        data.addProperty("expanded_queries", expandedQueries);
        data.addProperty("construction_query_calls", constructionQueries);
        var construction = new JsonObject();
        constructionActions.forEach(construction::addProperty);
        data.add("construction_action_calls", construction);
        data.addProperty("construction_effects_omitted", constructionEffectsOmitted);
        data.addProperty("local_failures", recoveredFailures);
        data.addProperty("body_recheck_required", needsBodyCheck);
        data.addProperty("last_tool", lastTool);
        if (programError != null) data.add("program_error", programError.deepCopy());
        if (last != null) {
            data.add("last_receipt", last.data());
            if (last.data().has("no_targets")) data.add("no_targets", last.data().get("no_targets"));
        }
        counts.forEach(data::addProperty);
        data.add("returned", returned);
        result.complete(new ToolOutcome(ok, reason + "\n实际终态=" + completed + "，子调用=" + calls
                + (constructionQueries == 0 ? "" : "，施工内部查询已合并=" + constructionQueries)
                + (construction.isEmpty() ? "" : "\n施工成功子调用=" + construction
                + "，动作回执样本省略=" + constructionEffectsOmitted + "；完整子回执保留在面板/事件，实际施工量见mc.build结果。")
                + receipts + (returned.isJsonNull() ? "" : "\n返回值=" + display(returned)), data));
    }

    private LuaValue fromJson(JsonElement value, int depth) {
        if (depth > 16 || ++cells > CELLS) throw new Halt("VALUE_LIMIT:状态表预算已到。");
        if (value.isJsonNull()) return LuaValue.NIL;
        if (value.isJsonPrimitive()) {
            var p = value.getAsJsonPrimitive();
            if (p.isBoolean()) return LuaValue.valueOf(p.getAsBoolean());
            if (p.isNumber()) return LuaValue.valueOf(p.getAsDouble());
            String text = p.getAsString();
            allocateString(bytes(text));
            return LuaValue.valueOf(text);
        }
        var table = new LuaTable();
        if (value.isJsonArray()) {
            int i = 1;
            for (var entry : value.getAsJsonArray()) table.set(i++, fromJson(entry, depth + 1));
        } else for (var entry : value.getAsJsonObject().entrySet()) {
            allocateString(bytes(entry.getKey()));
            table.set(entry.getKey(), fromJson(entry.getValue(), depth + 1));
        }
        return table;
    }

    private static JsonElement toJson(LuaValue value, int depth, IdentityHashMap<LuaValue, Boolean> seen, int[] nodes) {
        if (depth > 16 || ++nodes[0] > 8192) throw new Halt("VALUE_LIMIT:数据深度或大小超限。");
        return switch (value.type()) {
            case LuaValue.TNIL -> JsonNull.INSTANCE;
            case LuaValue.TBOOLEAN -> new JsonPrimitive(value.toboolean());
            case LuaValue.TNUMBER -> {
                double n = value.todouble();
                if (!Double.isFinite(n)) throw new Halt("VALUE_LIMIT:数字须有限。");
                yield new JsonPrimitive(n);
            }
            case LuaValue.TSTRING -> {
                if (value.checkstring().m_length > VALUE_BYTES) throw new Halt("VALUE_LIMIT:字符串过大。");
                yield new JsonPrimitive(value.tojstring());
            }
            case LuaValue.TTABLE -> {
                if (seen.put(value, Boolean.TRUE) != null) throw new Halt("VALUE_LIMIT:不接受循环数据表。");
                var keys = value.checktable().keys();
                if (keys.length + nodes[0] > 8192) throw new Halt("VALUE_LIMIT:数据表过大。");
                boolean array = keys.length > 0 && Arrays.stream(keys).allMatch(k ->
                        k.type() == LuaValue.TNUMBER && k.isint() && k.toint() >= 1 && k.toint() <= keys.length);
                JsonElement json;
                if (array) {
                    var list = new JsonArray();
                    for (int i = 1; i <= keys.length; i++) list.add(toJson(value.get(i), depth + 1, seen, nodes));
                    json = list;
                } else {
                    var object = new JsonObject();
                    for (LuaValue key : keys) {
                        if (key.type() != LuaValue.TSTRING) throw new Halt("VALUE_LIMIT:对象键须为字符串。");
                        object.add(key.tojstring(), toJson(value.get(key), depth + 1, seen, nodes));
                    }
                    json = object;
                }
                seen.remove(value);
                yield json;
            }
            default -> throw new Halt("VALUE_LIMIT:仅支持JSON兼容数据，不能传函数或外部对象。");
        };
    }

    private void allocateString(int size) {
        if (size > VALUE_BYTES || (strings += size) > STRING_BYTES)
            throw new Halt("VALUE_LIMIT:字符串分配预算已到。");
    }
    private void reclaimTransientValues() {
        if (cells < CELLS / 2 && strings < STRING_BYTES / 2) return;
        var queue = new ArrayDeque<LuaValue>();
        queue.add(runtimeGlobals);
        for (var frame : budget.frames) {
            queue.add(frame.closure());
            for (var value : frame.registers()) if (value != null) queue.add(value);
            for (int i = 1; i <= frame.arguments().narg(); i++) queue.add(frame.arguments().arg(i));
        }
        var seen = new IdentityHashMap<LuaValue, Boolean>();
        int retainedCells = 0, retainedStrings = 0;
        while (!queue.isEmpty()) {
            check();
            var value = queue.removeFirst();
            if (seen.put(value, Boolean.TRUE) != null) continue;
            if (++retainedCells > CELLS) throw new Halt("VALUE_LIMIT:保留的状态表预算已到。");
            if (value.type() == LuaValue.TSTRING) {
                retainedStrings += value.checkstring().m_length;
                if (retainedStrings > STRING_BYTES) throw new Halt("VALUE_LIMIT:保留的字符串预算已到。");
            } else if (value.istable()) {
                var table = value.checktable();
                for (var key : table.keys()) {
                    // Charge occupied slots even when several tables share the same value.
                    retainedCells += 2;
                    if (retainedCells > CELLS) throw new Halt("VALUE_LIMIT:保留的状态表预算已到。");
                    queue.add(key);
                    queue.add(table.rawget(key));
                }
            } else if (value instanceof LuaClosure closure) {
                for (var captured : closure.upValues) if (captured != null) queue.add(captured.getValue());
            }
        }
        // Old observations no longer reachable from the VM may be reclaimed; retained pages and closures still count.
        cells = retainedCells;
        strings = retainedStrings;
    }

    private final class Budget extends DebugLib {
        private record Frame(LuaClosure closure, LuaValue[] registers, Varargs arguments) { }
        private final ArrayDeque<Frame> frames = new ArrayDeque<>();
        @Override public void onCall(LuaClosure closure, Varargs args, LuaValue[] stack) {
            check();
            if (frames.size() >= 48) throw new Halt("VALUE_LIMIT:脚本调用深度超限。");
            frames.push(new Frame(closure, stack, args));
        }
        @Override public void onReturn() { if (!frames.isEmpty()) frames.pop(); }
        @Override public String traceback(int level) { return ""; }
        @Override public void onInstruction(int pc, Varargs args, int top) {
            check();
            if (++slice > SLICE)
                throw new Halt("INSTRUCTION_LIMIT:脚本计算预算已到。");
            Frame frame = frames.peek();
            if (frame == null) return;
            if (frame.closure().p.lineinfo != null && pc < frame.closure().p.lineinfo.length)
                sourceLine = frame.closure().p.lineinfo[pc];
            int instruction = frame.closure().p.code[pc];
            int op = Lua.GET_OPCODE(instruction);
            BoundedProgram.this.opcode = op;
            if (op == Lua.OP_CONCAT) {
                // Inspect operands before Lua allocates the concatenation, including intermediate buffers.
                int length = 0, allocated = 0;
                for (int i = Lua.GETARG_C(instruction); i >= Lua.GETARG_B(instruction); i--) {
                    LuaValue value = frame.registers()[i];
                    length += value.type() == LuaValue.TSTRING ? value.checkstring().m_length : 32;
                    allocated += length;
                }
                allocateString(allocated);
            } else if (op == Lua.OP_NEWTABLE || op == Lua.OP_SETTABLE || op == Lua.OP_SETLIST) {
                int cost = op == Lua.OP_NEWTABLE ? 8 + Lua.GETARG_B(instruction) + Lua.GETARG_C(instruction)
                        : op == Lua.OP_SETLIST ? Math.max(1, top) : 1;
                if ((cells += cost) > CELLS) throw new Halt("VALUE_LIMIT:数据表分配预算已到。");
            } else if (op == Lua.OP_GETTABLE || op == Lua.OP_SELF) {
                // LuaJ string metatables are JVM globals and could have been installed by another mod.
                LuaValue receiver = frame.registers()[Lua.GETARG_B(instruction)];
                if (receiver.type() == LuaValue.TSTRING)
                    throw new Halt("DENIED:不开放字符串元表或外部库方法。");
            }
        }
    }
    private static int bytes(String text) { return text.getBytes(StandardCharsets.UTF_8).length; }
    private static final class Halt extends Error {
        private final String reason;
        Halt(String reason) { super(null, null, false, false); this.reason = reason; }
    }
}
