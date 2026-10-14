package com.neko.mcbot.agentcore.script;

import com.google.gson.*;
import com.neko.mcbot.agentcore.loop.ToolExecutor.ToolOutcome;
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
            return new Plan(code.getAsString(), integer(args, "timeout_seconds", 300, 1, 900),
                    integer(args, "max_calls", 128, 1, 256));
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

    private static final int INSTRUCTIONS = 250000;
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
    private final Map<String, Long> counts = new LinkedHashMap<>();
    private volatile boolean halted;
    private volatile Thread worker;
    private volatile CompletableFuture<ToolOutcome> waiting;
    private volatile int calls;
    private volatile int completed;
    private volatile ToolOutcome last;
    private volatile String lastTool = "";
    private volatile long started;
    private int instructions, slice, cells, strings, outputBytes, emptySearches, repeats;
    private String signature = "";

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
    private boolean expired() { return clock.getAsLong() - started >= plan.seconds() * 1000L; }
    private void check() {
        if (halted || Thread.currentThread().isInterrupted()) throw new Halt("CANCELLED:组合执行已停止。");
        if (expired()) throw new Halt("TIMEOUT:组合执行时限已到。");
    }

    private void run() {
        try {
            var globals = new Globals();
            globals.load(new BaseLib());
            // Keep only bounded pure helpers; no loaders, metatables, protected calls or external libraries.
            Set<String> keep = Set.of("assert", "error", "ipairs", "pairs", "next", "tonumber", "tostring", "type");
            for (LuaValue key : globals.keys()) if (!keep.contains(key.tojstring())) globals.set(key, LuaValue.NIL);
            LuaC.install(globals);
            globals.debuglib = new Budget();
            var api = new LuaTable();
            api.set("call", new VarArgFunction() {
                @Override public Varargs invoke(Varargs args) {
                    check();
                    if (args.narg() != 2 || args.arg(1).type() != LuaValue.TSTRING || !args.arg(2).istable())
                        throw new Halt("DENIED:mc.call须为工具名字符串与参数表。");
                    String name = args.arg(1).tojstring();
                    if (name.length() > 32) throw new Halt("DENIED:工具名无效。");
                    JsonElement json = toJson(args.arg(2), 0, new IdentityHashMap<>(), new int[]{0});
                    // Empty Lua tables are objects. A nonempty positional table is an array.
                    if (!json.isJsonObject() || bytes(json.toString()) > 16384)
                        throw new Halt("DENIED:工具参数须为有界对象。");
                    ToolOutcome receipt = BoundedProgram.this.call(name, json.toString());
                    var value = new JsonObject();
                    value.addProperty("ok", receipt.ok());
                    value.addProperty("feedback", receipt.feedback());
                    value.add("data", receipt.data());
                    slice = 0;
                    return fromJson(value, 0);
                }
            });
            globals.set("mc", api);
            globals.set("print", new VarArgFunction() {
                @Override public Varargs invoke(Varargs args) {
                    if (args.narg() > 32) throw new Halt("RESULT_LIMIT:打印参数过多。");
                    var parts = new ArrayList<String>();
                    for (int i = 1; i <= args.narg(); i++)
                        parts.add(toJson(args.arg(i), 0, new IdentityHashMap<>(), new int[]{0}).toString());
                    append("输出 " + String.join(" ", parts));
                    return LuaValue.NONE;
                }
            });
            check();
            var value = globals.load(plan.code(), "task-program").call();
            check();
            JsonElement returned = toJson(value, 0, new IdentityHashMap<>(), new int[]{0});
            if (bytes(returned.toString()) > 12000 || bytes(returned.toString()) + outputBytes > OUTPUT_BYTES)
                throw new Halt("RESULT_LIMIT:返回值与阶段回执合计过大。");
            dispatch.accept(() -> {
                if (!done()) {
                    if (expired()) stop("TIMEOUT:组合执行时限已到。");
                    else finish(true, "组合执行结束；子步骤结果已核对，不代表主人整项目标完成。", returned);
                }
            });
        } catch (Halt stopped) {
            dispatch.accept(() -> stop(stopped.reason));
        } catch (LuaError | IllegalArgumentException | StackOverflowError invalid) {
            dispatch.accept(() -> stop("PROGRAM_ERROR:脚本语法、类型、返回值或调用深度无效；未重试动作。"));
        } catch (RuntimeException unexpected) {
            dispatch.accept(() -> stop("INTERNAL:组合执行未取得有效结果，未猜测或重发。"));
        } finally {
            terminated.complete(null);
        }
    }

    private ToolOutcome call(String name, String args) {
        check();
        if (++calls > plan.maxCalls()) throw new Halt("ACTION_LIMIT:组合执行子调用预算已到。");
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
            last = receipt;
            lastTool = name;
            completed++;
            for (String key : COUNTS) {
                var amount = receipt.data().get(key);
                if (amount != null && amount.isJsonPrimitive() && amount.getAsJsonPrimitive().isNumber())
                    counts.merge(key, Math.max(0, amount.getAsLong()), Long::sum);
            }
        }
        append(name + " ok=" + receipt.ok() + "：" + receipt.feedback());
        if (!receipt.ok()) throw new Halt("CHILD_STOPPED:子调用失败或需要确认，后续动作未执行。");
        var data = receipt.data();
        boolean progress = false;
        for (String key : COUNTS) {
            var amount = data.get(key);
            if (amount != null && amount.isJsonPrimitive() && amount.getAsJsonPrimitive().isNumber()
                    && amount.getAsDouble() > 0) progress = true;
        }
        var gathered = data.get("actual_progress");
        if (name.equals("gather") && gathered != null && gathered.isJsonPrimitive()
                && gathered.getAsJsonPrimitive().isNumber() && gathered.getAsDouble() > 0) progress = true;
        if (progress) emptySearches = 0;
        if (Set.of("scan_area", "find_resource").contains(name) && data.has("no_targets")) {
            emptySearches = data.get("no_targets").getAsBoolean() ? emptySearches + 1 : 0;
            if (emptySearches >= 3) throw new Halt("NO_TARGETS:三次搜索未取得目标，停止组合执行。");
        }
        String now = name + "|" + args + "|" + receipt.feedback();
        repeats = progress ? 0 : now.equals(signature) ? repeats + 1 : 1;
        signature = now;
        if (repeats >= 5) throw new Halt("NO_PROGRESS:连续五次相同调用及回执，停止组合执行。");
        return receipt;
    }

    private synchronized void append(String line) {
        int size = bytes(line) + 1;
        if (outputBytes + size > OUTPUT_BYTES) throw new Halt("RESULT_LIMIT:组合执行回执预算已到。");
        outputBytes += size;
        receipts.append('\n').append(line);
    }
    private synchronized void finish(boolean ok, String reason, JsonElement returned) {
        if (done()) return;
        var data = new JsonObject();
        data.addProperty("api_calls", calls);
        data.addProperty("terminal_calls", completed);
        data.addProperty("last_tool", lastTool);
        if (last != null) {
            data.add("last_receipt", last.data());
            if (last.data().has("no_targets")) data.add("no_targets", last.data().get("no_targets"));
        }
        counts.forEach(data::addProperty);
        data.add("returned", returned);
        result.complete(new ToolOutcome(ok, reason + "\n实际终态=" + completed + "，子调用=" + calls
                + receipts + (returned.isJsonNull() ? "" : "\n返回值=" + returned), data));
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
            if (++instructions > INSTRUCTIONS || ++slice > SLICE)
                throw new Halt("INSTRUCTION_LIMIT:脚本计算预算已到。");
            Frame frame = frames.peek();
            int instruction = frame.closure().p.code[pc];
            int op = Lua.GET_OPCODE(instruction);
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
