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
            + "mc.cleanup_temporary(revision)只回收新鲜判定可拆的本任务临时工程。";
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
    private boolean needsBodyCheck;
    private volatile int sourceLine, opcode = -1;
    private volatile boolean compiled;
    private JsonObject programError;

    public BoundedProgram(Plan plan, LongSupplier clock,
                          BiFunction<String, String, CompletableFuture<ToolOutcome>> execute,
                          Consumer<Runnable> dispatch, Runnable abort) {
        this.plan = plan;
        this.clock = clock;
        this.execute = execute;
        this.dispatch = dispatch;
        this.abort = abort;
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
            globals.load(new BaseLib());
            // Keep only bounded pure helpers; no loaders, metatables, protected calls or external libraries.
            for (LuaValue key : globals.keys()) if (!GLOBALS.contains(key.tojstring())) globals.set(key, LuaValue.NIL);
            LuaC.install(globals);
            globals.debuglib = new Budget();
            var api = new LuaTable();
            api.set("call", bridge(false));
            api.set("try_call", bridge(true));
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
                byte[] text = source.readNBytes(16385);
                if (text.length > 16384) throw new IllegalStateException("oversized construction helper");
                globals.load(new String(text, StandardCharsets.UTF_8), "construction-helper").call();
            } catch (java.io.IOException unavailable) { throw new IllegalStateException(unavailable); }
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
        if (last != null && queryRefs.containsKey(last.feedback()) && !feedbackRefs.containsKey(last.feedback()))
            queryNote(last.feedback(), Math.min(2048, OUTPUT_BYTES - outputBytes - 128));
        String hint = "按tool_help的返回字段读取data，缺字段先检查nil；" + LANGUAGE_GUIDE;
        stop("PROGRAM_ERROR:" + kind + "，脚本行=" + line + "。" + hint + "未重试动作。");
    }

    private VarArgFunction bridge(boolean allowLocalFailure) {
        return new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) {
                check();
                if (args.narg() != 2 || args.arg(1).type() != LuaValue.TSTRING || !args.arg(2).istable())
                    throw new Halt("DENIED:mc.call/try_call须为工具名字符串与参数表。");
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
                value.add("data", receipt.data());
                slice = 0;
                return fromJson(value, 0);
            }
        };
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
        if (receipt.ok() && TaskPolicy.observation(name, args)) {
            queryCalls++;
            if (name.equals("tool_help")) {
                // Instructions are for the next model turn even when help was called inside a program.
                append("回执#" + completed + " " + name + " ok=true：" + display(new JsonPrimitive(receipt.feedback())));
                feedbackRefs.put(receipt.feedback(), completed);
                return;
            }
            var signals = new JsonObject();
            for (String key : List.of("no_targets", "can_craft", "preflight_passed", "truncated",
                    "unknown", "unloaded", "scan_complete", "coverage_complete", "results_truncated",
                    "work_truncated", "next_offset", "in_water", "eyes_in_water", "in_lava", "water_recovery",
                    "goal_satisfied", "observation_complete", "spatial_stable", "material_bill_complete",
                    "work_assessment_complete", "budget_exhausted", "material_next_offset",
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
            append("回执#" + completed + " " + name + " ok=" + receipt.ok() + "：" + receipt.feedback());
            feedbackRefs.put(receipt.feedback(), completed);
        }
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
    private final class Budget extends DebugLib {
        private record Frame(LuaClosure closure, LuaValue[] registers) { }
        private final ArrayDeque<Frame> frames = new ArrayDeque<>();
        @Override public void onCall(LuaClosure closure, Varargs args, LuaValue[] stack) {
            check();
            if (frames.size() >= 48) throw new Halt("VALUE_LIMIT:脚本调用深度超限。");
            frames.push(new Frame(closure, stack));
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
