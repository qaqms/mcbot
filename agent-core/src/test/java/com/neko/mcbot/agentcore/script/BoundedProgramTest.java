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

    @Test void largeSuccessfulQueriesStayLocalWithoutStoppingDependentWorkOrChangingTheirData() throws Exception {
        String full = "large query fact ".repeat(700);
        try (var f = new Fixture("""
                local total=0
                for i=1,4 do
                    local q=mc.call("craft",{query=true,item="fixture",material_index=i})
                    assert(q.feedback==q.data.exact)
                    total=total+q.data.amount
                end
                if total==28 then mc.call("craft",{item="fixture",count=1}) end
                return {amount=total}
                """)) {
            f.host = (name, args) -> {
                boolean query = JsonParser.parseString(args).getAsJsonObject().has("query");
                var data = new JsonObject();
                data.addProperty("exact", query ? full : "crafted with real materials");
                data.addProperty("amount", 7);
                if (!query) data.addProperty("crafted_count", 1);
                return CompletableFuture.completedFuture(new ToolOutcome(true,
                        query ? full : "crafted with real materials", data));
            };
            var result = f.outcome();
            assertTrue(result.ok(), result.feedback());
            assertEquals(5, f.calls.size());
            assertFalse(result.feedback().contains(full));
            assertTrue(result.feedback().contains("crafted with real materials"));
            assertTrue(result.feedback().length() < 1000);
            assertEquals(4, result.data().get("query_calls").getAsInt());
            assertEquals(0, result.data().get("expanded_queries").getAsInt());
            assertEquals(1, result.data().get("crafted_count").getAsInt());
            assertEquals(28, result.data().getAsJsonObject("returned").get("amount").getAsInt());
        }
    }

    @Test void runtimeFailureReportsTheScriptLineAndLastQueryWithoutEchoingTheException() throws Exception {
        try (var f = new Fixture("""
                local q=mc.call("inspect_block",{x=1,y=64,z=2})
                local missing=q.data.position
                return missing.x
                """)) {
            f.host = (name, args) -> CompletableFuture.completedFuture(new ToolOutcome(true,
                    "actual block is a string; no inventory was changed", JsonParser.parseString(
                    "{\"block\":\"minecraft:stone\",\"slots\":[]}").getAsJsonObject()));
            var result = f.outcome();
            assertFalse(result.ok());
            assertTrue(result.feedback().startsWith("PROGRAM_ERROR:FIELD_ACCESS"), result.feedback());
            assertEquals(3, result.data().getAsJsonObject("program_error").get("line").getAsInt());
            assertTrue(result.feedback().contains("actual block is a string"));
            assertEquals(1, result.data().get("terminal_calls").getAsInt());
            assertEquals(1, f.calls.size());
        }
        try (var f = new Fixture("error('private-script-value-do-not-echo')")) {
            var result = f.outcome();
            assertTrue(result.feedback().contains("FUNCTION_CALL"));
            assertFalse(result.feedback().contains("private-script"));
            assertFalse(result.data().toString().contains("private-script"));
        }
    }

    @Test void compileFailureReportsOnlyANumericLocationAndDoesNotExecuteTheValidPrefix() throws Exception {
        try (var f = new Fixture("mc.call('craft',{item='fixture'})\nlocal private_source = @")) {
            var result = f.outcome();
            assertTrue(result.feedback().startsWith("PROGRAM_ERROR:SYNTAX"), result.feedback());
            assertEquals(2, result.data().getAsJsonObject("program_error").get("line").getAsInt());
            assertFalse(result.feedback().contains("private_source"));
            assertTrue(f.calls.isEmpty());
        }
    }

    @Test void unavailableLibraryErrorsGiveTheActualLanguageBoundaryAndNoWorldAction() throws Exception {
        try (var f = new Fixture("local n=3\nlocal x=math.floor(n)\nmc.call('craft',{})")) {
            var result = f.outcome();
            assertFalse(result.ok());
            assertEquals(2, result.data().getAsJsonObject("program_error").get("line").getAsInt());
            assertTrue(result.feedback().contains("不提供string/table/math库"));
            assertTrue(result.feedback().contains("t[#t+1]=v"));
            assertTrue(f.calls.isEmpty());
        }
    }

    @Test void negativeQueryReasonsRemainVisibleWithoutReprintingLargeCandidateTables() throws Exception {
        try (var f = new Fixture("""
                for i=1,3 do mc.call("craft",{item="fixture",query=true}) end
                return {ready=false}
                """)) {
            String text = "candidate ".repeat(600);
            f.host = (name, args) -> CompletableFuture.completedFuture(new ToolOutcome(true, text,
                    JsonParser.parseString("{\"can_craft\":false,\"failure\":\"NEED_WORKBENCH:real missing worksite\"}")
                            .getAsJsonObject()));
            var result = f.outcome();
            assertTrue(result.ok(), result.feedback());
            assertTrue(result.feedback().contains("NEED_WORKBENCH:real missing worksite"));
            assertEquals(result.feedback().indexOf("real missing worksite"),
                    result.feedback().lastIndexOf("real missing worksite"));
            assertFalse(result.feedback().contains(text));
            assertEquals(3, f.calls.size());
        }
    }

    @Test void failedScriptsGetABoundedQueryHintAndPreserveAlreadyCompletedEffects() throws Exception {
        try (var f = new Fixture("""
                mc.call("craft",{item="fixture"})
                mc.call("inventory",{})
                error("do not replay")
                mc.call("craft",{item="fixture"})
                """)) {
            f.host = (name, args) -> CompletableFuture.completedFuture(name.equals("craft")
                    ? actual("{\"crafted_count\":2}") : new ToolOutcome(true,
                    "large observation ".repeat(1000), new JsonObject()));
            var result = f.outcome();
            assertFalse(result.ok());
            assertTrue(result.feedback().contains("说明截短"));
            assertTrue(result.feedback().getBytes(java.nio.charset.StandardCharsets.UTF_8).length < 4000);
            assertEquals(2, result.data().get("crafted_count").getAsInt());
            assertEquals(List.of("craft", "inventory"), f.calls.stream().map(s -> s.split(":")[0]).toList());
        }
    }

    @Test void helpInsideAProgramIsVisibleEvenWhenTheScriptOnlyPrintsItsType() throws Exception {
        try (var f = new Fixture("""
                local h=mc.call("tool_help",{names={"craft"}})
                print(type(h.data))
                """)) {
            f.host = (name, args) -> CompletableFuture.completedFuture(new ToolOutcome(true,
                    "trusted help with actual result fields", JsonParser.parseString("{\"definitions\":[]}")
                            .getAsJsonObject()));
            var result = f.outcome();
            assertTrue(result.ok(), result.feedback());
            assertTrue(result.feedback().contains("trusted help with actual result fields"));
            assertEquals(1, f.calls.size());
        }
    }

    @Test void errorHintRemainsBoundedWithUnicodeAndAnAlmostFullOutput() throws Exception {
        try (var f = new Fixture("print('" + "x".repeat(18700)
                + "')\nmc.call('inventory',{})\nlocal function broken()\nreturn nil_value.x\nend\nbroken()")) {
            f.host = (name, args) -> CompletableFuture.completedFuture(
                    new ToolOutcome(true, "木头🪵".repeat(2000), new JsonObject()));
            var result = f.outcome();
            assertFalse(result.ok());
            assertTrue(result.feedback().startsWith("PROGRAM_ERROR:FIELD_ACCESS"), result.feedback().substring(0, 100));
            assertEquals(4, result.data().getAsJsonObject("program_error").get("line").getAsInt());
            assertTrue(result.feedback().contains("说明截短"));
            assertFalse(result.feedback().contains("\uFFFD"));
            assertTrue(result.feedback().getBytes(java.nio.charset.StandardCharsets.UTF_8).length < 21000);
            assertEquals(1, f.calls.size());
        }
    }

    @Test void changingTimingTextArgumentOrderOrNoOpSuccessCannotReplenishLocalRecovery() throws Exception {
        try (var f = new Fixture("""
                mc.call("status",{details=true})
                for i=1,20 do
                    local args
                    if i%2==0 then args={z=0,y=64,x=2} else args={x=2,y=64,z=0} end
                    mc.try_call("move_to",args)
                    mc.call("status",{details=true})
                    mc.call("move_to",{x=0,y=64,z=0})
                end
                """)) {
            var failures = new java.util.concurrent.atomic.AtomicInteger();
            f.host = (name, args) -> {
                if (name.equals("status")) return CompletableFuture.completedFuture(actual(
                        "{\"x\":0,\"y\":64,\"z\":0,\"dimension\":\"fixture:world\",\"inventory\":{\"slots\":[]}}"));
                if (JsonParser.parseString(args).getAsJsonObject().get("x").getAsInt() == 0)
                    return CompletableFuture.completedFuture(actual("{\"x\":0,\"y\":64,\"z\":0}"));
                return CompletableFuture.completedFuture(new ToolOutcome(false,
                        "NO_PROGRESS:8001 nodes in " + failures.incrementAndGet() + "ms"));
            };
            var result = f.outcome();
            assertTrue(result.feedback().startsWith("NO_PROGRESS:"), result.feedback());
            assertEquals(5, failures.get());
        }
    }

    @Test void actualBodyProgressAllowsNewAttemptsButClockSlotAndWearDoNot() throws Exception {
        try (var f = new Fixture("""
                mc.call("status",{details=true})
                for i=1,12 do
                    mc.try_call("move_to",{x=2})
                    mc.call("status",{details=true})
                end
                """)) {
            var observations = new java.util.concurrent.atomic.AtomicInteger();
            f.host = (name, args) -> {
                if (!name.equals("status")) return CompletableFuture.completedFuture(new ToolOutcome(false, "NO_PATH:wall"));
                int i = observations.getAndIncrement();
                var data = JsonParser.parseString("{\"x\":" + (i >= 4 ? 1 : 0)
                        + ",\"y\":64,\"z\":0,\"dimension\":\"fixture:world\",\"game_tick\":" + i
                        + ",\"inventory\":{\"slots\":[]}}").getAsJsonObject();
                for (int index = 0; index < 36; index++) {
                    var slot = new JsonObject();
                    slot.addProperty("slot", index);
                    slot.addProperty("item", index == i % 36 ? "fixture:tool" : "");
                    slot.addProperty("count", index == i % 36 ? 1 : 0);
                    slot.addProperty("damage", i);
                    data.getAsJsonObject("inventory").getAsJsonArray("slots").add(slot);
                }
                return CompletableFuture.completedFuture(new ToolOutcome(true, "actual state", data));
            };
            var result = f.outcome();
            assertTrue(result.feedback().startsWith("NO_PROGRESS:"), result.feedback());
            assertEquals(9, result.data().get("local_failures").getAsInt());
        }
    }

    @Test void localFailureCanChooseAnAlternativeOnlyAfterFreshCompleteBodyObservation() throws Exception {
        try (var f = new Fixture("""
                local first=mc.try_call("move_to",{x=1,y=64,z=0})
                assert(not first.ok and first.data.partial==true)
                local state=mc.call("status",{details=true}).data
                if state.x==2 and state.inventory.slots[1].count==7 then
                    return mc.call("move_to",{x=3,y=64,z=0}).data
                end
                return {blocked="fresh state did not permit an alternative"}
                """)) {
            f.host = (name, args) -> CompletableFuture.completedFuture(name.equals("status")
                    ? actual("{\"x\":2,\"y\":64,\"z\":0,\"dimension\":\"fixture:world\","
                            + "\"inventory\":{\"slots\":[{\"count\":7}]}}")
                    : args.contains("1.0") ? new ToolOutcome(false, "PARTIAL:changed route, arrived elsewhere",
                    JsonParser.parseString("{\"partial\":true,\"removed_blocks\":2}").getAsJsonObject())
                    : actual("{\"arrived\":true}"));
            var result = f.outcome();
            assertTrue(result.ok(), result.feedback());
            assertEquals(3, f.calls.size());
            assertTrue(result.feedback().contains("PARTIAL:changed route, arrived elsewhere"));
            assertEquals(1, result.data().get("local_failures").getAsInt());
            assertFalse(result.data().get("body_recheck_required").getAsBoolean());
            assertTrue(result.data().getAsJsonObject("returned").get("arrived").getAsBoolean());
        }
    }

    @Test void aCaughtFailureDoesNotPermitAnotherActionWithoutCompleteStatus() throws Exception {
        for (String intermediate : List.of("", "mc.call('inventory',{}) ",
                "mc.call('status',{}) ", "mc.call('status',{details=true}) ")) {
            try (var f = new Fixture("mc.try_call('move_to',{x=1}) " + intermediate
                    + "mc.call('break_block',{x=2})")) {
                f.host = (name, args) -> CompletableFuture.completedFuture(name.equals("move_to")
                        ? new ToolOutcome(false, "NO_PATH:actual local failure") : actual("{\"x\":2,\"y\":64,\"z\":0}"));
                var result = f.outcome();
                assertTrue(result.feedback().startsWith("STATE_RECHECK_REQUIRED:"), result.feedback());
                assertTrue(f.calls.stream().noneMatch(s -> s.startsWith("break_block:")));
                assertTrue(result.feedback().contains("NO_PATH:actual local failure"));
            }
        }
    }

    @Test void tryCallCannotCatchPrerequisitesPermissionsUnknownErrorsOrNonlocalToolFailures() throws Exception {
        for (String reason : List.of("NEED_CONFIRM:concrete authorization", "DENIED:readonly",
                "WRONG_TOOL:missing prerequisite", "MISSING_MATERIALS:no input", "ENVIRONMENT_UNSAFE:water",
                "TIMEOUT:unknown effects", "CANCELLED:owner", "INTERNAL:unknown failure",
                "SCOPE_CHANGED:outside original range", "ACTION_LIMIT:shared budget exhausted")) {
            try (var f = new Fixture("mc.try_call('move_to',{}) mc.call('status',{details=true})")) {
                f.host = (name, args) -> CompletableFuture.completedFuture(new ToolOutcome(false, reason));
                var result = f.outcome();
                assertTrue(result.feedback().startsWith("CHILD_STOPPED:"), result.feedback());
                assertTrue(result.feedback().contains(reason));
                assertEquals(1, f.calls.size());
            }
        }
        try (var f = new Fixture("mc.try_call('craft',{}) mc.call('inventory',{})")) {
            f.host = (name, args) -> CompletableFuture.completedFuture(new ToolOutcome(false, "NO_PATH:wrong tool category"));
            assertFalse(f.outcome().ok());
            assertEquals(1, f.calls.size());
        }
    }

    @Test void tryCallRecoveryStillUsesTheSameCallBudgetAndDoesNotAutoRetry() throws Exception {
        try (var f = new Fixture("""
                mc.try_call("move_to",{})
                mc.call("status",{details=true})
                mc.call("move_to",{x=2})
                """, 2)) {
            f.host = (name, args) -> CompletableFuture.completedFuture(name.equals("move_to")
                    ? new ToolOutcome(false, "NO_PATH:local obstacle")
                    : actual("{\"x\":0,\"y\":64,\"z\":0,\"dimension\":\"fixture:world\",\"inventory\":{\"slots\":[]}}"));
            var result = f.outcome();
            assertTrue(result.feedback().startsWith("ACTION_LIMIT:"));
            assertEquals(2, f.calls.size());
        }
    }

    @Test void fullDataFromAStatusWithoutDetailsCannotClearTheRecoveryGate() throws Exception {
        try (var f = new Fixture("""
                mc.try_call("move_to",{})
                mc.call("status",{})
                mc.call("move_to",{x=2})
                """)) {
            f.host = (name, args) -> CompletableFuture.completedFuture(name.equals("move_to")
                    ? new ToolOutcome(false, "NO_PATH:local obstacle")
                    : actual("{\"x\":0,\"y\":64,\"z\":0,\"dimension\":\"fixture:world\",\"inventory\":{\"slots\":[]}}"));
            var result = f.outcome();
            assertTrue(result.feedback().startsWith("STATE_RECHECK_REQUIRED:"));
            assertEquals(2, f.calls.size());
        }
    }

    @Test void unchangedFailureCannotBeRetriedIndefinitelyByInterleavingFreshStatus() throws Exception {
        try (var f = new Fixture("""
                for i=1,20 do
                    mc.try_call("move_to",{x=2})
                    mc.call("status",{details=true})
                end
                """)) {
            f.host = (name, args) -> CompletableFuture.completedFuture(name.equals("move_to")
                    ? new ToolOutcome(false, "NO_PATH:same local obstacle")
                    : actual("{\"x\":0,\"y\":64,\"z\":0,\"dimension\":\"fixture:world\",\"inventory\":{\"slots\":[]}}"));
            var result = f.outcome();
            assertTrue(result.feedback().startsWith("NO_PROGRESS:"), result.feedback());
            assertEquals(9, f.calls.size());
            assertEquals(5, result.data().get("local_failures").getAsInt());
            assertTrue(result.data().get("body_recheck_required").getAsBoolean());
        }
    }

    @Test void cancelledRecoveryCannotConsumeALatePartialOrSendARecheck() throws Exception {
        try (var f = new Fixture("mc.try_call('move_to',{}) mc.call('status',{details=true})")) {
            var pending = new CompletableFuture<ToolOutcome>();
            f.host = (name, args) -> pending;
            f.until(() -> f.calls.size() == 1);
            f.program.stop("CANCELLED:owner");
            f.program.terminated().get(2, TimeUnit.SECONDS);
            pending.complete(new ToolOutcome(false, "PARTIAL:late movement"));
            var result = f.outcome();
            assertTrue(result.feedback().startsWith("CANCELLED:"));
            assertEquals(1, f.calls.size());
            assertEquals(0, result.data().get("local_failures").getAsInt());
            assertEquals(1, f.aborted);
        }
    }

    @Test void queryAuditKeepsNegativePreflightUnknownCoverageAndEnvironmentSignals() throws Exception {
        try (var f = new Fixture("""
                mc.call("place_block",{query=true})
                mc.call("search_resource",{})
                mc.call("status",{})
                return {blocked=true}
                """)) {
            f.host = (name, args) -> CompletableFuture.completedFuture(new ToolOutcome(true, "unabridged observation",
                    JsonParser.parseString(switch (name) {
                        case "place_block" -> "{\"preflight_passed\":false}";
                        case "search_resource" -> "{\"no_targets\":true,\"truncated\":true,\"unknown\":17,\"coverage_complete\":false}";
                        default -> "{\"eyes_in_water\":true,\"water_recovery\":\"seeking_air\"}";
                    }).getAsJsonObject()));
            var result = f.outcome();
            assertTrue(result.ok());
            assertTrue(result.feedback().contains("\"preflight_passed\":false"));
            assertTrue(result.feedback().contains("\"unknown\":17"));
            assertTrue(result.feedback().contains("\"coverage_complete\":false"));
            assertTrue(result.feedback().contains("\"truncated\":true"));
            assertTrue(result.feedback().contains("\"eyes_in_water\":true"));
            assertTrue(result.feedback().contains("seeking_air"));
            assertTrue(result.feedback().contains("unabridged observation"), "negative preflight must retain its reason");
        }
    }

    @Test void explicitQueryExpansionStillHonorsTheOutputCapAndKeepsPriorActualEffects() throws Exception {
        try (var f = new Fixture("""
                mc.call("craft",{})
                local q=mc.call("inventory",{})
                print(q.feedback)
                mc.call("equip",{})
                """)) {
            f.host = (name, args) -> CompletableFuture.completedFuture(name.equals("craft")
                    ? actual("{\"crafted_count\":4}")
                    : new ToolOutcome(true, "x".repeat(20500)));
            var result = f.outcome();
            assertTrue(result.feedback().startsWith("RESULT_LIMIT:"), result.feedback());
            assertEquals(2, f.calls.size());
            assertEquals(4, result.data().get("crafted_count").getAsInt());
            assertEquals(0, result.data().get("expanded_queries").getAsInt());
            assertTrue(result.feedback().length() < 1000);
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
