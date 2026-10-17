package com.neko.mcbot.agentcore.script;

import com.google.gson.*;
import com.neko.mcbot.agentcore.loop.ToolExecutor.ToolOutcome;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BiFunction;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(10)
class ConstructionProgramTest {
    static final String REV = "a".repeat(64);
    static final String CODE = """
            local t={anchor={x=4,y=90,z=0},cells={{x=0,y=0,z=0,block="stone"}}}
            return mc.build(t,{access_route={{x=0,y=90,z=0},{x=1,y=90,z=0}},max_rounds=3,max_actions=2})
            """;
    record Sent(String name,JsonObject args) { }
    static final class World {
        int remaining=2, placed, moved, breaks, checkpoints, pages=1;
        boolean clearing, complete=true, unstablePage, material=true, repeat, cancelled;
        String moveFailure;
        final List<Sent> calls=new ArrayList<>();
        BiFunction<String,JsonObject,ToolOutcome> override;
        ToolOutcome call(String name,JsonObject args) {
            calls.add(new Sent(name,args));
            if(override!=null) {
                var value=override.apply(name,args);
                if(value!=null) return value;
            }
            return switch(name) {
                case "status" -> actual("""
                        {"x":0,"y":90,"z":0,"dimension":"fixture:world","inventory":{"slots":[]}}
                        """);
                case "build_site" -> {
                    if(args.get("operation").getAsString().equals("checkpoint") && ++checkpoints>1 && repeat)
                        yield new ToolOutcome(false,"NO_PROGRESS:repeated real gap and stance");
                    yield actual("{\"active\":true,\"target_revision\":\""+REV+"\","
                            +"\"bounds\":{\"x\":0,\"y\":90,\"z\":0,\"radius\":32},\"temporary\":[]}");
                }
                case "build_diff" -> {
                    int offset=args.get("offset").getAsInt();
                    String state=remaining==0?"satisfied":clearing?"needs_clear":moved>0?"ready":"out_of_reach";
                    if(pages>1 && offset==0 && remaining>0) state="needs_support";
                    yield actual("""
                            {"target_revision":"%s","goal_satisfied":%s,"remaining":%d,
                            "observation_complete":%s,"spatial_stable":true,"material_bill_complete":true,
                            "budget_exhausted":false,"observation_key":"%s","offset":%d,"next_offset":%d,
                            "cells":[{"x":4,"y":90,"z":0,"state":"%s","item":"minecraft:stone",
                            "material_available":%s,"actual_block":"minecraft:dirt","clear_allowed":true}]}
                            """.formatted(REV,remaining==0,remaining,complete,remaining+(unstablePage&&offset>0?"changed":""),
                            offset,offset+16<pages*16?offset+16:-1,state,material));
                }
                case "move_to" -> {
                    if(moveFailure!=null) yield new ToolOutcome(false,moveFailure);
                    moved++; yield actual("{\"can_work_here\":true}");
                }
                case "build_nearby" -> {
                    if(!repeat) { placed+=remaining; remaining=0; }
                    yield actual("{\"placed_blocks\":2,\"consumed_count\":2,\"goal_satisfied\":false}");
                }
                case "break_block" -> {
                    breaks++; clearing=false; moved++;
                    yield actual("{\"removed\":true,\"collected_count\":0}");
                }
                default -> throw new AssertionError(name);
            };
        }
    }
    static ToolOutcome actual(String json) { return new ToolOutcome(true,"observed",JsonParser.parseString(json).getAsJsonObject()); }
    static ToolOutcome run(World world,String code,int maxCalls) throws Exception {
        var queue=new ConcurrentLinkedQueue<Runnable>();
        var p=new BoundedProgram(new BoundedProgram.Plan(code,3,maxCalls),()->0L,
                (name,args)->CompletableFuture.completedFuture(world.call(name,JsonParser.parseString(args).getAsJsonObject())),
                queue::add,()->world.cancelled=true);
        var future=p.start();
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(4);
        try {
            while(!future.isDone() && System.nanoTime()<deadline) {
                Runnable next; while((next=queue.poll())!=null) next.run();
                java.util.concurrent.locks.LockSupport.parkNanos(100_000);
            }
            assertTrue(future.isDone(),"construction Lua did not settle");
            return future.join();
        } finally {
            p.stop("CANCELLED:test cleanup"); p.terminated().get(2,TimeUnit.SECONDS);
            Runnable next; while((next=queue.poll())!=null) next.run();
        }
    }
    @Test void helperCombinesActualDiffStanceAndConstructionThenIndependentlyVerifiesGoal() throws Exception {
        var w=new World(); var result=run(w,CODE,128);
        assertTrue(result.ok(),result.feedback()); assertEquals(1,w.moved); assertEquals(2,w.placed);
        var returned=result.data().getAsJsonObject("returned");
        assertTrue(returned.get("goal_satisfied").getAsBoolean()); assertFalse(returned.get("outer_goal_verified").getAsBoolean());
        assertEquals("build_diff",w.calls.getLast().name());
        var move=w.calls.stream().filter(c->c.name().equals("move_to")).findFirst().orElseThrow().args();
        assertTrue(move.get("walk_only").getAsBoolean()); assertEquals("work",move.get("arrival").getAsString());
        assertEquals(32,move.getAsJsonObject("bounds").get("radius").getAsInt());
    }
    @Test void pagesAreReadBeforeChoosingLaterPageWorkAndWorldChangesStopAllMutations() throws Exception {
        var w=new World(); w.pages=2;
        assertTrue(run(w,CODE,128).ok()); assertEquals(2,w.placed);
        assertTrue(w.calls.stream().anyMatch(c->c.name().equals("build_diff")&&c.args().get("offset").getAsInt()==16));
        var unstable=new World(); unstable.pages=2; unstable.unstablePage=true;
        var result=run(unstable,CODE,128);
        assertTrue(result.ok()); assertFalse(result.data().getAsJsonObject("returned").get("goal_satisfied").getAsBoolean());
        assertEquals(0,unstable.moved); assertEquals(0,unstable.placed);
    }
    @Test void clearingUsesFreshSameCellObservationAndOriginalMiningEntryWithNoImplicitObstructionWork() throws Exception {
        var w=new World(); w.clearing=true;
        assertTrue(run(w,CODE,128).ok()); assertEquals(1,w.breaks);
        int index=0;
        while(!w.calls.get(index).name().equals("break_block")) index++;
        assertEquals("build_diff",w.calls.get(index-1).name());
        var args=w.calls.get(index).args();
        assertEquals(REV,args.get("site_revision").getAsString());
        assertEquals("minecraft:dirt",args.get("expected_block").getAsString());
        assertFalse(args.has("clear_obstructions"));
    }
    @Test void localMoveFailureRequiresFreshBodyCheckAndServerRoundMemoryStopsOscillation() throws Exception {
        var w=new World(); w.moveFailure="NO_PATH:local geometry"; w.repeat=true;
        var result=run(w,CODE,128);
        assertFalse(result.ok()); assertEquals(0,w.placed);
        int index=0; while(!w.calls.get(index).name().equals("move_to")) index++;
        assertEquals("status",w.calls.get(index+1).name());
        assertTrue(w.calls.get(index+1).args().get("details").getAsBoolean());
        assertEquals(2,w.checkpoints); assertEquals(1,w.calls.stream().filter(c->c.name().equals("move_to")).count());
    }
    @Test void permissionSafetyUnknownAndActionBudgetFailuresNeverBecomeLocalRetries() throws Exception {
        for(String reason:List.of("DENIED:permission","ENVIRONMENT_UNSAFE:water","TARGET_LOST:unknown","ACTION_LIMIT:nodes")) {
            var w=new World(); w.moveFailure=reason;
            var result=run(w,CODE,128);
            assertFalse(result.ok(),reason); assertEquals(0,w.placed); assertEquals(1,w.checkpoints);
            assertEquals("move_to",w.calls.getLast().name());
        }
    }
    @Test void incompleteObservationMissingMaterialAndUnsupportedWorkCannotBeCompletion() throws Exception {
        var w=new World(); w.complete=false;
        var result=run(w,CODE,128);
        assertTrue(result.ok()); assertFalse(result.data().getAsJsonObject("returned").get("goal_satisfied").getAsBoolean());
        assertEquals(0,w.checkpoints); assertEquals(0,w.moved);
        var noMaterial=new World(); noMaterial.material=false;
        var lacking=run(noMaterial,CODE,128);
        assertFalse(lacking.data().getAsJsonObject("returned").get("goal_satisfied").getAsBoolean());
        assertEquals(0,noMaterial.moved);
    }
    @Test void alreadySatisfiedTargetSkipsAllTemporaryEngineeringAndConsumption() throws Exception {
        var w=new World(); w.remaining=0;
        var code=CODE.replace("max_actions=2","max_actions=2,temporary={{x=8,y=90,z=0,item='stone',purpose='support'}}");
        var result=run(w,code,128);
        assertTrue(result.ok(),result.feedback());
        assertEquals(0,w.placed); assertEquals(0,w.moved);
        assertTrue(w.calls.stream().noneMatch(c->c.name().equals("place_block")));
    }
    @Test void helperDoesNotIncreaseOriginalProgramCallBudget() throws Exception {
        var w=new World();
        var result=run(w,CODE,3);
        assertFalse(result.ok()); assertTrue(result.feedback().contains("ACTION_LIMIT:"));
        assertEquals(3,w.calls.size()); assertEquals(0,w.moved); assertEquals(0,w.placed);
    }
    @Test void cleanupIsExplicitRetainsDependantsAndWaitsForFreshPermissionBeforeMining() throws Exception {
        var w=new World();
        w.override=(name,args)->name.equals("build_site") ? actual("""
                {"active":true,"target_revision":"%s","bounds":{"x":0,"y":90,"z":0,"radius":32},
                "temporary":[{"x":8,"y":90,"z":0,"block":"minecraft:stone","cleanup_allowed":true},
                {"x":9,"y":90,"z":0,"block":"minecraft:stone","cleanup_allowed":false}]}
                """.formatted(REV)):null;
        var result=run(w,"return mc.cleanup_temporary('"+REV+"')",128);
        assertTrue(result.ok(),result.feedback());
        assertEquals(1,w.breaks);
        var mining=w.calls.stream().filter(c->c.name().equals("break_block")).findFirst().orElseThrow();
        assertTrue(mining.args().get("temporary_cleanup").getAsBoolean());
        assertEquals(1,result.data().getAsJsonObject("returned").get("retained").getAsInt());
        assertFalse(result.data().getAsJsonObject("returned").get("goal_satisfied").getAsBoolean());
    }
    @Test void finalBatchReceiptAloneCannotFinishAndCancellationStopsNextObservation() throws Exception {
        var w=new World();
        w.override=(name,args)->name.equals("build_nearby") ? new ToolOutcome(false,"CANCELLED:owner stopped") : null;
        var result=run(w,CODE,128);
        assertFalse(result.ok()); assertEquals("build_nearby",w.calls.getLast().name());
        assertEquals(0,w.placed);
    }
}
