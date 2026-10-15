package com.neko.mcbot.agentcore.script;

import com.google.gson.*;
import com.neko.mcbot.agentcore.loop.ToolExecutor.ToolOutcome;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiFunction;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(10)
class BoundedProgramTest {
    private static final class Fixture implements AutoCloseable {
        final ConcurrentLinkedQueue<Runnable> queue = new ConcurrentLinkedQueue<>();
        final AtomicLong time = new AtomicLong();
        final List<String> calls = new ArrayList<>();
        int aborted;
        BiFunction<String, String, CompletableFuture<ToolOutcome>> host = (name, args) ->
                CompletableFuture.completedFuture(new ToolOutcome(true, "actual " + name, new JsonObject()));
        final BoundedProgram program;
        final CompletableFuture<ToolOutcome> result;
        Fixture(String code) { this(code, 128); }
        Fixture(String code, int maxCalls) {
            program = new BoundedProgram(new BoundedProgram.Plan(code, 2, maxCalls), time::get,
                    (name, args) -> { calls.add(name + ":" + args); return host.apply(name, args); },
                    queue::add, () -> aborted++);
            result = program.start();
        }
        void until(BooleanSupplier condition) {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
                Runnable action;
                while ((action = queue.poll()) != null) action.run();
                java.util.concurrent.locks.LockSupport.parkNanos(100000);
            }
            assertTrue(condition.getAsBoolean(), "program/client queue did not settle");
        }
        ToolOutcome outcome() { until(result::isDone); return result.join(); }
        @Override public void close() throws Exception {
            program.stop("CANCELLED:test cleanup");
            program.terminated().get(2, TimeUnit.SECONDS);
            Runnable action;
            while ((action = queue.poll()) != null) action.run();
        }
    }
    private static ToolOutcome actual(String data) {
        return new ToolOutcome(true, "actual", JsonParser.parseString(data).getAsJsonObject());
    }

    @Test void variablesBranchesFunctionsAndLoopsUseActualReceiptData() throws Exception {
        try (var f = new Fixture("""
                local function query(n) return mc.call("inventory", {page=n}).data.amount end
                local total=0
                for i=1,3 do total=total+query(i) end
                if total==12 then return mc.call("equip",{slot=total-5}).data end
                error("unexpected world facts")
                """)) {
            f.host = (name, args) -> CompletableFuture.completedFuture(name.equals("inventory")
                    ? actual("{\"amount\":4}") : actual("{\"selected_slot\":7}"));
            var r = f.outcome();
            assertTrue(r.ok(), r.feedback());
            assertEquals(4, f.calls.size());
            assertTrue(f.calls.getLast().contains("\"slot\":7.0"));
            assertEquals(7, r.data().getAsJsonObject("returned").get("selected_slot").getAsInt());
        }
    }

    @Test void doesNotDispatchADependentActionUntilTheRealFutureCompletes() throws Exception {
        try (var f = new Fixture("mc.call('move_to',{}) return mc.call('break_block',{})")) {
            var movement = new CompletableFuture<ToolOutcome>();
            f.host = (name, args) -> name.equals("move_to") ? movement
                    : CompletableFuture.completedFuture(actual("{\"removed\":true}"));
            f.until(() -> f.calls.size() == 1);
            assertFalse(f.result.isDone());
            assertEquals(1, f.calls.size());
            movement.complete(actual("{\"arrived\":true}"));
            assertTrue(f.outcome().ok());
            assertEquals(2, f.calls.size());
        }
    }

    @Test void rawAcceptanceCannotBeConsumedAsATerminalResult() throws Exception {
        try (var f = new Fixture("mc.call('wait',{}) mc.call('craft',{})")) {
            f.host = (name, args) -> CompletableFuture.completedFuture(ToolOutcome.accepted("job", "accepted"));
            assertFalse(f.outcome().ok());
            assertEquals(1, f.calls.size());
            assertTrue(f.result.join().feedback().startsWith("INTERNAL:"));
        }
    }

    @Test void failureStopsAndPreservesActualPriorCountsAndAuthorization() throws Exception {
        try (var f = new Fixture("mc.call('craft',{}) mc.call('move_to',{}) mc.call('attack',{})")) {
            f.host = (name, args) -> CompletableFuture.completedFuture(name.equals("craft")
                    ? new ToolOutcome(true, "crafted", JsonParser.parseString("{\"crafted_count\":4}").getAsJsonObject())
                    : new ToolOutcome(false, "NEED_CONFIRM:actual route", JsonParser.parseString("{\"authorization_id\":\"fixture\"}").getAsJsonObject()));
            var r = f.outcome();
            assertFalse(r.ok());
            assertEquals(2, f.calls.size());
            assertEquals(4, r.data().get("crafted_count").getAsInt());
            assertEquals("fixture", r.data().getAsJsonObject("last_receipt").get("authorization_id").getAsString());
            assertTrue(r.feedback().contains("NEED_CONFIRM:actual route"));
        }
    }

    @Test void cancellationReleasesWorkerAndLateResultCannotContinue() throws Exception {
        try (var f = new Fixture("mc.call('wait',{}) mc.call('craft',{})")) {
            var pending = new CompletableFuture<ToolOutcome>();
            f.host = (name, args) -> pending;
            f.until(() -> !f.calls.isEmpty());
            f.program.stop("CANCELLED:owner");
            f.program.terminated().get(2, TimeUnit.SECONDS);
            pending.complete(actual("{}"));
            assertFalse(f.outcome().ok());
            assertEquals(1, f.calls.size());
            assertEquals(1, f.aborted);
        }
    }

    @Test void exactDeadlineStopsAnInflightCallWithoutRealWaiting() throws Exception {
        try (var f = new Fixture("mc.call('wait',{}) mc.call('craft',{})")) {
            f.host = (name, args) -> new CompletableFuture<>();
            f.until(() -> !f.calls.isEmpty());
            f.time.set(2000);
            f.program.tick();
            assertTrue(f.outcome().feedback().startsWith("TIMEOUT:"));
            assertEquals(1, f.calls.size());
        }
    }

    @Test void callBudgetStopsBeforeTheExcessCallIsDispatched() throws Exception {
        try (var f = new Fixture("for i=1,4 do mc.call('inventory',{page=i}) end", 2)) {
            assertTrue(f.outcome().feedback().startsWith("ACTION_LIMIT:"));
            assertEquals(2, f.calls.size());
        }
    }

    @Test void pureInfiniteLoopCannotBlockTheClient() throws Exception {
        try (var f = new Fixture("while true do end")) {
            assertTrue(f.outcome().feedback().startsWith("INSTRUCTION_LIMIT:"));
            assertTrue(f.calls.isEmpty());
        }
    }

    @Test void totalInstructionBudgetDoesNotResetAtApiCalls() throws Exception {
        try (var f = new Fixture("""
                local n=0
                for i=1,200 do
                    for j=1,1000 do n=n+j end
                    mc.call("inventory",{page=i})
                end
                return n
                """, 256)) {
            assertTrue(f.outcome().feedback().startsWith("INSTRUCTION_LIMIT:"));
            assertTrue(f.calls.size() > 1 && f.calls.size() < 200);
        }
    }

    @Test void expandingStringsAreStoppedBeforeLargeAllocation() throws Exception {
        try (var f = new Fixture("local s='x' for i=1,90 do s=s..s end return s")) {
            assertTrue(f.outcome().feedback().startsWith("VALUE_LIMIT:"));
            assertTrue(f.calls.isEmpty());
        }
    }

    @Test void tableAndRecursionBudgetsAreIndependentOfWorldCalls() throws Exception {
        for (String code : List.of("local t={} for i=1,10000 do t[i]={{},{},{},{}} end return 1",
                "local function f(n) return 1+f(n+1) end return f(0)")) {
            try (var f = new Fixture(code)) {
                assertTrue(f.outcome().feedback().startsWith("VALUE_LIMIT:"), f.result.join().feedback());
            }
        }
    }

    @Test void environmentDoesNotExposeExternalOrUnboundedLibraries() throws Exception {
        try (var f = new Fixture("""
                assert(io==nil and os==nil and luajava==nil and package==nil and require==nil)
                assert(load==nil and loadfile==nil and dofile==nil and debug==nil)
                assert(coroutine==nil and pcall==nil and xpcall==nil and setmetatable==nil)
                assert(string==nil and table==nil and math==nil and _G==nil)
                return true
                """)) {
            assertTrue(f.outcome().ok());
            assertTrue(f.result.join().data().get("returned").getAsBoolean());
        }
    }

    @Test void stringMethodsCannotReachAnotherModsGlobalMetatable() throws Exception {
        var saved = org.luaj.vm2.LuaString.s_metatable;
        var metatable = new org.luaj.vm2.LuaTable();
        var methods = new org.luaj.vm2.LuaTable();
        methods.set("foreign", new org.luaj.vm2.lib.ZeroArgFunction() {
            @Override public org.luaj.vm2.LuaValue call() { fail("foreign library was reached"); return NIL; }
        });
        metatable.set("__index", methods);
        org.luaj.vm2.LuaString.s_metatable = metatable;
        try (var f = new Fixture("return ('x'):foreign()")) {
            assertTrue(f.outcome().feedback().startsWith("DENIED:"));
        } finally { org.luaj.vm2.LuaString.s_metatable = saved; }
    }

    @Test void invalidDataCannotBePassedToWorldTools() throws Exception {
        for (String code : List.of("local t={} t.self=t mc.call('inventory',t)",
                "mc.call('inventory',{slot=0/0})", "mc.call('inventory',{x=function() end})",
                "mc.call('inventory',{[2]=true})")) {
            try (var f = new Fixture(code)) {
                assertFalse(f.outcome().ok());
                assertTrue(f.calls.isEmpty());
            }
        }
    }

    @Test void outputHasAHardBudgetWithoutWritingProcessStdout() throws Exception {
        try (var f = new Fixture("for i=1,1000 do print('bounded local output repeated enough to stop') end")) {
            var r = f.outcome();
            assertTrue(r.feedback().startsWith("RESULT_LIMIT:"));
            assertTrue(r.feedback().getBytes(java.nio.charset.StandardCharsets.UTF_8).length < 24000);
        }
    }

    @Test void repeatedFeedbackUsesReferencesWhileLuaAndReturnedValuesKeepTheCompleteText() throws Exception {
        String text = "actual world receipt ".repeat(260);
        try (var f = new Fixture("""
                local r=mc.call("inventory",{})
                for i=1,4 do print("inventory",r.ok,r.feedback) end
                assert(r.feedback==r.data.original)
                mc.call("craft",{})
                return {echo=r.feedback,amount=r.data.amount}
                """)) {
            f.host = (name, args) -> {
                var data = new JsonObject();
                data.addProperty("original", text);
                data.addProperty("amount", 7);
                return CompletableFuture.completedFuture(new ToolOutcome(true,
                        name.equals("inventory") ? text : "crafted", data));
            };
            var result = f.outcome();
            assertTrue(result.ok(), result.feedback());
            assertEquals(2, f.calls.size(), "duplicating display must not stop a dependent action");
            assertEquals(1, result.feedback().split(java.util.regex.Pattern.quote(text), -1).length - 1);
            assertTrue(result.feedback().contains("见回执#1"));
            assertEquals(text, result.data().getAsJsonObject("returned").get("echo").getAsString());
            assertEquals(7, result.data().getAsJsonObject("returned").get("amount").getAsInt());
        }
    }

    @Test void referencesNeverElideDistinctCallsOrTextThatIsNotAnExactFeedbackCopy() throws Exception {
        try (var f = new Fixture("""
                print("actual")
                local first=mc.call("inventory",{page=1})
                local second=mc.call("inventory",{page=2})
                print("prefix: "..first.feedback)
                return {first=first.feedback,second=second.feedback,other="actual!"}
                """)) {
            f.host = (name, args) -> CompletableFuture.completedFuture(actual("{}"));
            var result = f.outcome();
            assertTrue(result.ok(), result.feedback());
            assertTrue(result.feedback().contains("回执#1 inventory"));
            assertTrue(result.feedback().contains("回执#2 inventory"));
            assertTrue(result.feedback().contains("输出 \"actual\""));
            assertTrue(result.feedback().contains("prefix: actual"));
            assertTrue(result.feedback().contains("actual!"));
            assertTrue(result.feedback().contains("见回执#2"));
        }
    }

    @Test void failedChildKeepsItsFullReasonAndPriorReferencesWithoutDispatchingMoreWork() throws Exception {
        try (var f = new Fixture("""
                local r=mc.call("inventory",{})
                print(r.feedback)
                mc.call("move_to",{})
                mc.call("craft",{})
                """)) {
            f.host = (name, args) -> CompletableFuture.completedFuture(name.equals("inventory")
                    ? actual("{}") : new ToolOutcome(false, "NEED_CONFIRM:full concrete plan",
                    JsonParser.parseString("{\"authorization_id\":\"fixture\"}").getAsJsonObject()));
            var result = f.outcome();
            assertFalse(result.ok());
            assertEquals(2, f.calls.size());
            assertTrue(result.feedback().contains("见回执#1"));
            assertTrue(result.feedback().contains("回执#2 move_to ok=false：NEED_CONFIRM:full concrete plan"));
            assertEquals("fixture", result.data().getAsJsonObject("last_receipt")
                    .get("authorization_id").getAsString());
        }
    }

    @Test void syntaxErrorsAreDetectedBeforeAnyEarlierStatementRuns() throws Exception {
        try (var f = new Fixture("mc.call('inventory',{}) if")) {
            assertTrue(f.outcome().feedback().startsWith("PROGRAM_ERROR:"));
            assertTrue(f.calls.isEmpty());
        }
    }

    @Test void successfulMissingMaterialQueryCanBranchWithoutPerformingAnAction() throws Exception {
        try (var f = new Fixture("""
                local q=mc.call("craft",{item="fixture",query=true})
                if not q.data.can_craft then return {blocked=q.feedback} end
                return mc.call("craft",{item="fixture",count=1})
                """)) {
            f.host = (name, args) -> CompletableFuture.completedFuture(actual("{\"can_craft\":false}"));
            assertTrue(f.outcome().ok());
            assertEquals(1, f.calls.size());
            assertTrue(f.result.join().data().getAsJsonObject("returned").has("blocked"));
        }
    }

    @Test void repeatedNoTargetsCannotBeHiddenByStatusQueries() throws Exception {
        try (var f = new Fixture("for i=1,4 do mc.call('find_resource',{r=i}) mc.call('status',{}) end")) {
            f.host = (name, args) -> CompletableFuture.completedFuture(actual(
                    name.equals("find_resource") ? "{\"no_targets\":true}" : "{}"));
            assertTrue(f.outcome().feedback().startsWith("NO_TARGETS:"));
            assertEquals(5, f.calls.size());
        }
    }

    @Test void actualPageProgressAllowsAllFivePagesWithoutForgivingARepeatedFirstPage() throws Exception {
        String code = """
                local offset=0
                for i=1,5 do
                    local r=mc.call('find_resource',{targets={'oak_log'},r=16,offset=offset,
                        scan_center={x=0,y=80,z=0}})
                    offset=r.data.next_offset
                end
                return offset
                """;
        try (var f = new Fixture(code)) {
            f.host = (name, text) -> {
                var args = com.google.gson.JsonParser.parseString(text).getAsJsonObject();
                int offset = args.get("offset").getAsInt();
                var data = new com.google.gson.JsonObject();
                data.addProperty("offset", offset);
                data.addProperty("radius", 16);
                data.add("scan_center", args.get("scan_center"));
                data.addProperty("samples_planned", 17077);
                data.addProperty("samples_examined", Math.min(4096, 17077 - offset));
                data.addProperty("next_offset", offset == 16384 ? -1 : offset + 4096);
                data.addProperty("no_targets", true);
                return CompletableFuture.completedFuture(new ToolOutcome(true, "page", data));
            };
            assertTrue(f.outcome().ok(), f.result.join().feedback());
            assertEquals(5, f.calls.size());
            assertEquals(-1, f.result.join().data().get("returned").getAsInt());
        }
    }

    @Test void repeatedCraftsWithRealProgressAreNotMistakenForAPollingLoop() throws Exception {
        try (var f = new Fixture("for i=1,8 do mc.call('craft',{item='stick',count=4}) end")) {
            f.host = (name, args) -> CompletableFuture.completedFuture(actual("{\"crafted_count\":4}"));
            assertTrue(f.outcome().ok());
            assertEquals(8, f.calls.size());
        }
    }

    @Test void identicalPollingResultsHaveANoProgressBound() throws Exception {
        try (var f = new Fixture("for i=1,8 do mc.call('status',{}) end")) {
            assertTrue(f.outcome().feedback().startsWith("NO_PROGRESS:"));
            assertEquals(5, f.calls.size());
        }
    }

    @Test void sourceAndLimitsAreStrictlyValidated() {
        for (String json : List.of("{}", "{\"code\":1}", "{\"code\":\"return 1\",\"max_calls\":0}",
                "{\"code\":\"return 1\",\"timeout_seconds\":901}", "{\"code\":\"return 1\",\"unknown\":true}",
                "{\"code\":\"" + " ".repeat(17000) + "\"}"))
            assertThrows(RuntimeException.class, () -> BoundedProgram.Plan.parse(json));
        assertEquals(128, BoundedProgram.Plan.parse("{\"code\":\"return 1\"}").maxCalls());
    }
}
