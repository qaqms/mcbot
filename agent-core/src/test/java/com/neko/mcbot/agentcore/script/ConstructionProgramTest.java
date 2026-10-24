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
    @Test void emptyCellOrientationConflictChangesWorkStanceButAnExistingWrongStateIsNotReplaced() throws Exception {
        for (boolean empty:new boolean[]{true,false}) {
            var world=new World();
            world.override=(name,args)->!name.equals("build_diff") || world.moved>0 ? null : actual("""
                    {"target_revision":"%s","goal_satisfied":false,"remaining":1,"observation_complete":true,
                    "spatial_stable":true,"budget_exhausted":false,"observation_key":"unchanged",
                    "offset":0,"next_offset":-1,"material_bill_complete":true,"cells":[
                    {"x":4,"y":90,"z":0,"block":"minecraft:stone","state":"state_conflict",
                    "actual_block":"%s","item":"minecraft:stone","material_available":true}]}
                    """.formatted(REV,empty?"minecraft:air":"minecraft:stone"));
            var result=run(world,CODE,128);
            assertTrue(result.ok(),result.feedback());
            assertEquals(empty?1:0,world.moved);
            assertEquals(empty?2:0,world.placed);
            assertEquals(empty,result.data().getAsJsonObject("returned").get("goal_satisfied").getAsBoolean());
        }
    }
    @Test void largePublicDiffReturnsGlobalCountsWithOneExplicitlyIncompletePageAndCanReadTheLastPage() throws Exception {
        var world=new World();
        world.override=(name,args)-> {
            if (!name.equals("build_diff")) return null;
            int offset=args.get("offset").getAsInt();
            return actual("""
                    {"target_revision":"%s","total":16384,"remaining":1,"goal_satisfied":false,
                    "observation_complete":true,"spatial_stable":true,"budget_exhausted":false,
                    "observation_key":"same","counts":{"satisfied":16383,"out_of_reach":1},
                    "offset":%d,"next_offset":%d,"details_complete":false,
                    "cells":[{"x":31,"y":121,"z":7,"state":"out_of_reach"}],
                    "materials":[],"material_next_offset":-1}
                    """.formatted(REV,offset,offset==0?16:-1));
        };
        var first=run(world,"return mc.build_diff({})",128);
        assertTrue(first.ok(),first.feedback());
        var result=first.data().getAsJsonObject("returned");
        assertEquals(16384,result.get("total").getAsInt());
        assertEquals(16383,result.getAsJsonObject("counts").get("satisfied").getAsInt());
        assertFalse(result.get("details_complete").getAsBoolean());
        assertEquals(16,result.get("next_offset").getAsInt());
        assertEquals(1,world.calls.size(),"global accounting must not transport 1024 detail pages");
        var last=run(world,"return mc.build_diff({},'"+REV+"',16368)",128);
        assertTrue(last.ok(),last.feedback());
        assertEquals(16368,world.calls.getLast().args().get("offset").getAsInt());
        assertEquals(REV,world.calls.getLast().args().get("expected_revision").getAsString());
    }
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
                    if(args.get("operation").getAsString().equals("checkpoint")) checkpoints++;
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
        return run(world,code,maxCalls,null);
    }
    static ToolOutcome run(World world,String code,int maxCalls,JsonObject goal) throws Exception {
        var queue=new ConcurrentLinkedQueue<Runnable>();
        var p=new BoundedProgram(new BoundedProgram.Plan(code,3,maxCalls),()->0L,
                (name,args)->CompletableFuture.completedFuture(world.call(name,JsonParser.parseString(args).getAsJsonObject())),
                queue::add,()->world.cancelled=true,goal);
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
        assertFalse(move.has("walk_only")); assertEquals("work",move.get("arrival").getAsString());
        assertEquals(32,move.getAsJsonObject("bounds").get("radius").getAsInt());
    }
    @Test void defaultHelperRequiresNoHandwrittenExitAndDoesNotInventBoundsOrDisableTerrainWork() throws Exception {
        var w=new World();
        w.override=(name,args)->name.equals("build_site") ? actual(
                "{\"active\":true,\"target_revision\":\""+REV+"\",\"temporary\":[]}") : null;
        var result=run(w,CODE.replace("{access_route={{x=0,y=90,z=0},{x=1,y=90,z=0}},max_rounds=3,max_actions=2}","nil"),128);
        assertTrue(result.ok(),result.feedback());
        assertTrue(result.data().getAsJsonObject("returned").get("goal_satisfied").getAsBoolean());
        var begin=w.calls.stream().filter(c->c.name().equals("build_site")).findFirst().orElseThrow().args();
        assertFalse(begin.has("access_route")); assertFalse(begin.has("bounds"));
        var move=w.calls.stream().filter(c->c.name().equals("move_to")).findFirst().orElseThrow().args();
        assertFalse(move.has("walk_only")); assertFalse(move.has("max_digs")); assertFalse(move.has("bounds"));
        assertTrue(move.get("exact").getAsBoolean()); assertEquals("work",move.get("arrival").getAsString());
        assertTrue(move.has("work_target")); assertEquals(2,w.placed);
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
    @Test void incompleteMaterialBillDoesNotHideSupportedWorkOrItsFinalUnresolvedCells() throws Exception {
        var w=new World(); w.pages=2;
        w.override=(name,args)->name.equals("build_diff") ? actual("""
                {"target_revision":"%s","goal_satisfied":false,"total":3,"remaining":%d,
                "observation_complete":true,"spatial_stable":true,"material_bill_complete":false,
                "budget_exhausted":false,"observation_key":"%s","offset":%d,"next_offset":%d,
                "cells":[{"x":4,"y":90,"z":0,"state":"%s","item":"minecraft:stone",
                "material_available":true,"unsupported_reason":"fixture_special_block"}]}
                """.formatted(REV,w.remaining+1,w.remaining,args.get("offset").getAsInt(),
                args.get("offset").getAsInt()==0?16:-1,args.get("offset").getAsInt()==0
                        ? "unsupported" : w.remaining==0 ? "satisfied" : w.moved>0?"ready":"out_of_reach")) : null;
        var result=run(w,CODE,128);
        assertTrue(result.ok(),result.feedback());
        assertEquals(2,w.placed,"supported cells must still be constructed");
        var returned=result.data().getAsJsonObject("returned");
        assertFalse(returned.get("goal_satisfied").getAsBoolean());
        assertEquals(1,returned.get("remaining").getAsInt());
        assertEquals(2,returned.get("placed_blocks").getAsInt());
        assertTrue(result.feedback().contains("unsupported"),"blocked facts must reach the model");
    }
    @Test void wholeDiffHelperPinsRevisionAndReturnsEveryPageWithoutManualOffsets() throws Exception {
        var w=new World(); w.pages=3;
        var result=run(w,"return mc.build_diff({anchor={x=4,y=90,z=0},cells={{x=0,y=0,z=0,block='stone'}}})",128);
        assertTrue(result.ok(),result.feedback());
        var calls=w.calls.stream().filter(c->c.name().equals("build_diff")).toList();
        assertEquals(List.of(0,16,32),calls.stream().map(c->c.args().get("offset").getAsInt()).toList());
        assertEquals(REV,calls.get(1).args().get("expected_revision").getAsString());
        assertEquals(3,result.data().getAsJsonObject("returned").getAsJsonArray("cells").size());
        assertTrue(w.calls.stream().noneMatch(c->c.name().equals("move_to")));
        var complete=new World(); complete.pages=3; complete.remaining=0;
        var observed=run(complete,"return mc.build_diff({})",128);
        assertTrue(observed.ok(),observed.feedback());
        assertEquals(3,observed.data().getAsJsonObject("returned").getAsJsonArray("cells").size(),
                "whole-target success must not truncate the returned details to the first page");
    }
    @Test void wholeDiffReadsMaterialContinuationSeparatelyWithoutMixingDifferentObservations() throws Exception {
        var w=new World();
        w.override=(name,args)->{
            if(!name.equals("build_diff")) return null;
            int offset=args.has("material_offset")?args.get("material_offset").getAsInt():0;
            return actual("""
                    {"target_revision":"%s","goal_satisfied":false,"remaining":1,"offset":0,"next_offset":-1,
                    "observation_complete":true,"spatial_stable":true,"budget_exhausted":false,
                    "material_bill_complete":false,"observation_key":"same","cells":[{"state":"unsupported"}],
                    "materials":[{"item":"fixture:%d"}],"material_next_offset":%d}
                    """.formatted(REV,offset,offset==0?16:-1));
        };
        var result=run(w,"return mc.build_diff({})",128);
        assertTrue(result.ok(),result.feedback());
        assertEquals(2,w.calls.size());
        assertEquals(16,w.calls.getLast().args().get("material_offset").getAsInt());
        assertEquals(REV,w.calls.getLast().args().get("expected_revision").getAsString());
        assertEquals(2,result.data().getAsJsonObject("returned").getAsJsonArray("materials").size());
    }
    @Test void helperReportsBlockedFactsEvenWhenCallerDiscardsItsReturnValue() throws Exception {
        var w=new World(); w.material=false;
        var result=run(w,CODE.replace("return mc.build","mc.build"),128);
        assertTrue(result.ok(),result.feedback());
        assertTrue(result.feedback().contains("\"status\":\"blocked\""),result.feedback());
        assertTrue(result.feedback().contains("\"placed_blocks\":0"),result.feedback());
        assertEquals(0,w.placed);
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
    @Test void nativeNavigationDropAccountingLabelDoesNotStopConstructionAfterArrival() throws Exception {
        for (String accounting : List.of("native_origin_events", "bounded_identity_observation")) {
            var w=new World(); w.clearing=true;
            w.override=(name,args)-> {
                if (!name.equals("move_to")) return null;
                w.moved++;
                return actual("""
                        {"x":-29,"y":68,"z":-387,"arrival":"work","feet_y":68,
                        "removed_blocks":0,"placed_blocks":0,"drop_accounting":"%s",
                        "spawned_count":0,"live_count":0}
                        """.formatted(accounting));
            };
            var result=run(w,CODE,128);
            assertTrue(result.ok(),result.feedback());
            assertTrue(result.data().getAsJsonObject("returned").get("goal_satisfied").getAsBoolean());
            assertEquals(1,w.breaks,"successful native arrival must proceed to actual clearing");
            assertEquals(2,w.placed);
            assertTrue(result.feedback().contains(accounting),"the accounting method remains visible");
        }
    }
    @Test void registeredTargetCanBuildAndReadWholeDiffWithoutRecreatingItsCellsOrInstanceIdentity() throws Exception {
        var goal=JsonParser.parseString("""
                {"type":"building","target":{"anchor":{"x":-31,"y":67,"z":-389},"rotation":90,
                "cells":[{"x":0,"y":0,"z":0,"block":"stone"}]},"expected_revision":"%s"}
                """.formatted(REV)).getAsJsonObject();
        var saved=goal.deepCopy();
        var w=new World(); w.clearing=true;
        w.override=(name,args)-> {
            if (args.has("target")) assertEquals(saved.get("target"),args.get("target"));
            if (name.equals("build_site") && args.get("operation").getAsString().equals("begin"))
                assertEquals(REV,args.get("expected_revision").getAsString());
            return null;
        };
        var result=run(w,"local g=mc.goal(); g.target.anchor.x=999; return mc.build()",128,goal);
        assertTrue(result.ok(),result.feedback());
        assertTrue(result.data().getAsJsonObject("returned").get("goal_satisfied").getAsBoolean());
        assertEquals(1,w.breaks);
        assertEquals(saved,goal,"the script cannot mutate the independent condition");
        var diff=run(new World(),"return mc.build_diff()",128,saved);
        assertTrue(diff.ok(),diff.feedback());
        assertEquals(REV,diff.data().getAsJsonObject("returned").get("target_revision").getAsString());
    }
    @Test void constructionSamplingPreservesLaterFailuresAndRawQueriesStillExposeTheirCompleteData() throws Exception {
        var w=new World(); w.moved=1; w.remaining=20;
        w.override=(name,args)-> {
            if (!name.equals("build_nearby")) return null;
            if (w.placed==8) return new ToolOutcome(false,"ENVIRONMENT_UNSAFE:fixture water arrived");
            w.remaining--; w.placed++;
            return actual("{\"placed_blocks\":1,\"consumed_count\":1}");
        };
        var result=run(w,CODE.replace("max_rounds=3,",""),0);
        assertFalse(result.ok());
        assertTrue(result.feedback().contains("ENVIRONMENT_UNSAFE:fixture water arrived"));
        assertEquals(8,w.placed);
        assertTrue(result.data().get("construction_effects_omitted").getAsInt()>0);
        assertEquals(8,result.data().getAsJsonObject("construction_action_calls").get("build_nearby").getAsInt());
        var raw=new World();
        raw.override=(name,args)->actual("{\"surface\":{\"feet_y\":67.5},\"arbitrary_full_fact\":\"retained\"}");
        var query=run(raw,"return mc.call('build_diff',{}).data",0);
        assertTrue(query.ok(),query.feedback());
        assertEquals(67.5,query.data().getAsJsonObject("returned").getAsJsonObject("surface").get("feet_y").getAsDouble());
        assertEquals("retained",query.data().getAsJsonObject("returned").get("arbitrary_full_fact").getAsString());
    }
    @Test void omittedTargetRequiresAnActualRegisteredBuildingBeforeAnyRemoteCall() throws Exception {
        for (JsonObject goal : Arrays.asList(null,JsonParser.parseString(
                "{\"type\":\"item\",\"item\":\"minecraft:stone\",\"count\":3}").getAsJsonObject())) {
            var w=new World();
            var result=run(w,"return mc.build()",128,goal);
            assertFalse(result.ok());
            assertTrue(w.calls.isEmpty(),"no goal or an item goal is not a construction instruction");
        }
        var w=new World();
        var result=run(w,"assert(mc.goal()==nil); return 1",128);
        assertTrue(result.ok(),result.feedback());
        assertTrue(w.calls.isEmpty());
    }
    @Test void localMoveFailureChecksFreshBodyAndReturnsItsObstacleWithoutRepeatingAnUnchangedRoute() throws Exception {
        var w=new World(); w.moveFailure="NO_PATH:local geometry"; w.repeat=true;
        var result=run(w,CODE,128);
        assertTrue(result.ok()); assertEquals(0,w.placed);
        assertEquals("placement_stance_unavailable",result.data().getAsJsonObject("returned").get("blocked").getAsString());
        int index=0; while(!w.calls.get(index).name().equals("move_to")) index++;
        assertEquals("status",w.calls.get(index+1).name());
        assertTrue(w.calls.get(index+1).args().get("details").getAsBoolean());
        assertEquals(1,w.checkpoints); assertEquals(1,w.calls.stream().filter(c->c.name().equals("move_to")).count());
        w.moveFailure=null; w.repeat=false;
        assertTrue(run(w,CODE,128).data().getAsJsonObject("returned").get("goal_satisfied").getAsBoolean(),
                "a local failure is not a persistent ban on the owner's task or later dispatch");
    }
    @Test void aFailedCellDoesNotPreventReachingAnotherCellInTheSameUnchangedDesign() throws Exception {
        var w=new World(); w.moveFailure="NO_PATH:sealed first cell";
        w.override=(name,args)-> {
            if(name.equals("build_diff") && w.remaining>0) return actual("""
                    {"target_revision":"%s","goal_satisfied":false,"remaining":2,
                    "observation_complete":true,"spatial_stable":true,"material_bill_complete":true,
                    "budget_exhausted":false,"observation_key":"unchanged","offset":0,"next_offset":-1,
                    "cells":[{"x":4,"y":90,"z":0,"state":"out_of_reach","item":"stone","material_available":true},
                    {"x":5,"y":90,"z":0,"state":"%s","item":"stone","material_available":true}]}
                    """.formatted(REV,w.moved>0?"ready":"out_of_reach"));
            if(name.equals("move_to") && args.get("x").getAsInt()==5) {
                w.moved++; return actual("{\"can_work_here\":true}");
            }
            return null;
        };
        var result=run(w,CODE,128);
        assertTrue(result.ok(),result.feedback());
        assertTrue(result.data().getAsJsonObject("returned").get("goal_satisfied").getAsBoolean());
        assertEquals(2,w.calls.stream().filter(c->c.name().equals("move_to")).count());
        assertTrue(w.calls.stream().filter(c->c.name().equals("move_to")).noneMatch(c->c.args().has("face")),
                "work navigation chooses a real clickable face rather than forcing UP");
    }

    @Test void lowerFoundationClearingPrecedesAReadyWallFromAnotherPage() throws Exception {
        var w=new World(); w.clearing=true;
        w.override=(name,args)-> name.equals("build_diff") && w.clearing ? actual("""
                {"target_revision":"%s","goal_satisfied":false,"remaining":2,
                "observation_complete":true,"spatial_stable":true,"material_bill_complete":true,
                "budget_exhausted":false,"observation_key":"before","offset":0,"next_offset":-1,
                "cells":[{"x":4,"y":91,"z":0,"state":"ready","item":"stone","material_available":true},
                {"x":4,"y":90,"z":0,"state":"needs_clear","actual_block":"minecraft:dirt",
                "item":"stone","material_available":true,"clear_allowed":true}]}
                """.formatted(REV)) : null;
        assertTrue(run(w,CODE,128).ok());
        var actions=w.calls.stream().map(Sent::name)
                .filter(n->n.equals("break_block")||n.equals("build_nearby")).toList();
        assertEquals(List.of("break_block","build_nearby"),actions);
    }
    @Test void explicitRoundLimitStillBoundsProductiveWorkWithoutChangingTheOuterTask() throws Exception {
        var w=new World(); w.moved=1; w.remaining=5;
        w.override=(name,args)->{
            if(!name.equals("build_nearby")) return null;
            w.remaining--; w.placed++;
            return actual("{\"placed_blocks\":1,\"goal_satisfied\":false}");
        };
        var result=run(w,CODE,128);
        assertTrue(result.ok(),result.feedback());
        assertEquals("round_limit",result.data().getAsJsonObject("returned").get("blocked").getAsString());
        assertEquals(3,w.checkpoints);
        assertEquals(3,w.calls.stream().filter(c->c.name().equals("build_nearby")).count());
        assertEquals(2,w.remaining);
    }
    @Test void unchangedFreshWorldAfterReportedSuccessDoesNotReplayTheSamePlacementBatch() throws Exception {
        var w=new World(); w.moved=1; w.repeat=true;
        var result=run(w,CODE.replace("max_rounds=3,",""),0);
        assertTrue(result.ok(),result.feedback());
        assertFalse(result.data().getAsJsonObject("returned").get("goal_satisfied").getAsBoolean());
        assertEquals(1,w.calls.stream().filter(c->c.name().equals("build_nearby")).count());
        assertEquals(2,w.remaining);
        assertEquals(0,w.placed);
    }
    @Test void defaultConstructionContinuesBeyondSixteenRoundsAndOldCallLimit() throws Exception {
        var w=new World(); w.remaining=40; w.moved=1;
        w.override=(name,args)->{
            if(!name.equals("build_nearby")) return null;
            w.remaining--; w.placed++;
            return actual("{\"placed_blocks\":1,\"goal_satisfied\":false}");
        };
        var result=run(w,CODE.replace("max_rounds=3,",""),0);
        assertTrue(result.ok(),result.feedback());
        assertTrue(result.data().getAsJsonObject("returned").get("goal_satisfied").getAsBoolean());
        assertEquals(40,w.checkpoints); assertEquals(40,w.placed);
        assertTrue(w.calls.size()>128);
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
