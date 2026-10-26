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
    @Test void foundationSupplyGapReturnsToTheModelBeforeAnyUpperWorkEvenWithRawMaterialsInInventory() throws Exception {
        var w=new World();
        w.override=(name,args)-> {
            if(name.equals("build_diff")) return actual("""
                    {"target_revision":"%s","remaining":18,"goal_satisfied":false,"observation_complete":true,
                    "spatial_stable":true,"budget_exhausted":false,"observation_key":"stock",
                    "offset":0,"next_offset":-1,"work_scan":true,"work_next_cursor":-1,
                    "foundation_work":{"active":true,"y":90,"unfinished":17,"supply_required":true,
                    "materials":[{"item":"minecraft:oak_planks","required":17,"available":0,"shortfall":17}]},
                    "cells":[{"x":4,"y":91,"z":0,"state":"ready","item":"stone","material_available":true}]}
                    """.formatted(REV));
            return null;
        };
        var result=run(w,CODE,128);
        assertTrue(result.ok(),result.feedback());
        var report=result.data().getAsJsonObject("returned");
        assertEquals("foundation_materials_required",report.get("blocked").getAsString());
        assertTrue(report.get("supply_required").getAsBoolean());
        assertTrue(report.get("model_reassessment_required").getAsBoolean());
        assertEquals(17,report.getAsJsonObject("foundation_work").get("unfinished").getAsInt());
        assertTrue(report.get("blocked_detail").getAsString().contains("配方"));
        assertEquals(0,w.placed); assertEquals(0,w.moved); assertEquals(0,w.breaks);
        assertFalse(w.calls.stream().anyMatch(c->c.name().equals("craft")),
                "prerequisite processing belongs to the model and real recipe tools");
    }

    @Test void aRouteRemovalTriggersRepairEvenWhenTheNextWorkPageHasNoActionableCells() throws Exception {
        var w=new World();
        w.override=(name,args)-> {
            if (name.equals("build_site")) return actual("""
                    {"active":true,"target_revision":"%s","temporary":[],"repairs_pending":%d}
                    """.formatted(REV,w.moved>0?1:0));
            if (name.equals("build_diff")) return actual("""
                    {"target_revision":"%s","goal_satisfied":false,"remaining":2,
                    "observation_complete":true,"spatial_stable":true,"budget_exhausted":false,
                    "observation_key":"state-%d","offset":0,"next_offset":-1,"cells":[
                    {"x":4,"y":90,"z":0,"state":"%s","item":"stone","material_available":true}]}
                    """.formatted(REV,w.moved,w.moved==0?"out_of_reach":"needs_support"));
            if (name.equals("move_to")) {
                w.moved++;
                return actual("{\"removed_blocks\":1,\"can_work_here\":true}");
            }
            if (name.equals("build_nearby")) {
                assertEquals(1,w.moved);
                assertTrue(args.get("repairs_only").getAsBoolean());
                return new ToolOutcome(false,"DENIED:stop after verifying repair dispatch");
            }
            return null;
        };
        var result=run(w,CODE.replace("max_rounds=3,",""),128);
        assertFalse(result.ok());
        assertEquals("build_nearby",w.calls.getLast().name());
        assertTrue(result.feedback().contains("DENIED:stop after verifying repair dispatch"));
    }

    @Test void anUnchangedUnreachableRepairIsCheckedOnceAndCannotBecomeCompletion() throws Exception {
        var w=new World();
        w.override=(name,args)-> {
            if (name.equals("build_site")) return actual("""
                    {"active":true,"target_revision":"%s","temporary":[],"repairs_pending":1}
                    """.formatted(REV));
            if (name.equals("build_diff")) return actual("""
                    {"target_revision":"%s","goal_satisfied":false,"remaining":1,
                    "observation_complete":true,"spatial_stable":true,"budget_exhausted":false,
                    "observation_key":"same","work_observation_key":"same-work",
                    "offset":0,"next_offset":-1,"cells":[
                    {"x":4,"y":90,"z":0,"state":"needs_support","repair_pending":true}]}
                    """.formatted(REV));
            if (name.equals("build_nearby")) {
                assertTrue(args.get("repairs_only").getAsBoolean());
                return actual("{\"placed_blocks\":0,\"no_work_here\":true,\"goal_satisfied\":false}");
            }
            return null;
        };
        var result=run(w,CODE.replace("max_rounds=3,",""),128);
        assertTrue(result.ok(),result.feedback());
        var report=result.data().getAsJsonObject("returned");
        assertFalse(report.get("goal_satisfied").getAsBoolean());
        assertEquals(0,report.get("nearby_repaired_blocks").getAsInt());
        assertEquals(1,w.calls.stream().filter(c->c.name().equals("build_nearby")).count());
        assertEquals(0,w.moved);
    }

    @Test void earlyRepairPermissionCancellationAndBudgetFailureStopAllLaterWork() throws Exception {
        for (String reason:List.of("DENIED:permission changed","CANCELLED:owner stopped","ACTION_LIMIT:work")) {
            var w=new World();
            w.override=(name,args)-> {
                if (name.equals("build_site")) return actual("""
                        {"active":true,"target_revision":"%s","temporary":[],"repairs_pending":1}
                        """.formatted(REV));
                if (name.equals("build_nearby")) {
                    assertTrue(args.get("repairs_only").getAsBoolean());
                    return new ToolOutcome(false,reason);
                }
                return null;
            };
            var result=run(w,CODE,128);
            assertFalse(result.ok(),reason);
            assertTrue(result.feedback().contains(reason),result.feedback());
            assertEquals("build_nearby",w.calls.getLast().name());
            assertEquals(0,w.moved);
            assertEquals(0,w.placed);
        }
    }

    @Test void reachableRepairsAreRestoredBeforeAnUnrelatedRoofSearchCanStall() throws Exception {
        var w=new World();
        boolean[] repaired={false};
        w.override=(name,args)-> {
            if (name.equals("build_site")) return actual("""
                    {"active":true,"target_revision":"%s","temporary":[],"repairs_pending":%d}
                    """.formatted(REV,repaired[0]?0:1));
            if (name.equals("build_diff")) return actual("""
                    {"target_revision":"%s","goal_satisfied":false,"remaining":%d,
                    "observation_complete":true,"spatial_stable":true,"budget_exhausted":false,
                    "observation_key":"state-%s","offset":0,"next_offset":-1,"cells":[
                    {"x":4,"y":90,"z":0,"state":"%s","repair_pending":true},
                    {"x":5,"y":94,"z":0,"state":"out_of_reach","item":"stone","material_available":true}]}
                    """.formatted(REV,repaired[0]?1:2,repaired[0],repaired[0]?"satisfied":"ready"));
            if (name.equals("build_nearby")) {
                assertTrue(args.get("repairs_only").getAsBoolean());
                assertEquals(REV,args.get("expected_revision").getAsString());
                assertFalse(args.has("offset"),"nearby repairs must cover later drawing pages too");
                assertEquals(0,w.moved,"repair without another route");
                repaired[0]=true; w.placed++;
                return actual("{\"placed_blocks\":1,\"consumed_count\":1}");
            }
            if (name.equals("move_to")) return new ToolOutcome(false,"NAVIGATION_STALLED:roof");
            return null;
        };
        var result=run(w,CODE,128);
        assertFalse(result.ok());
        assertTrue(result.feedback().contains("NAVIGATION_STALLED:roof"));
        assertTrue(repaired[0],"the reachable hole must not wait for an impossible roof search");
        assertEquals(1,w.placed);
        assertEquals("move_to",w.calls.getLast().name(),"a stall still stops all later actions");
    }

    @Test void workArrivalFinishesItsWorkThenRepairsBeforeLeavingForAnotherLayer() throws Exception {
        var w=new World();
        boolean[] first={false},repair={false},roof={false};
        w.override=(name,args)-> {
            if (name.equals("build_site")) return actual("""
                    {"active":true,"target_revision":"%s","temporary":[],"repairs_pending":%d}
                    """.formatted(REV,w.moved>0&&!repair[0]?1:0));
            if (name.equals("build_diff")) return actual("""
                    {"target_revision":"%s","goal_satisfied":%s,"remaining":%d,
                    "observation_complete":true,"spatial_stable":true,"budget_exhausted":false,
                    "observation_key":"state-%s-%s-%s","offset":0,"next_offset":-1,"cells":[
                    {"x":4,"y":90,"z":0,"state":"%s","item":"stone","material_available":true},
                    {"x":5,"y":90,"z":0,"state":"%s","repair_pending":true},
                    {"x":6,"y":94,"z":0,"state":"%s","item":"stone","material_available":true}]}
                    """.formatted(REV,roof[0],(first[0]?0:1)+(repair[0]?0:1)+(roof[0]?0:1),
                    first[0],repair[0],roof[0],first[0]?"satisfied":w.moved>0?"ready":"out_of_reach",
                    repair[0]?"satisfied":"ready",roof[0]?"satisfied":w.moved>1?"ready":"out_of_reach"));
            if (name.equals("move_to")) {
                if (w.moved>0) assertTrue(repair[0],"do not leave a reachable repair behind");
                w.moved++;
                return actual("{\"can_work_here\":true}");
            }
            if (name.equals("build_nearby")) {
                if (args.has("repairs_only") && args.get("repairs_only").getAsBoolean()) {
                    assertTrue(first[0],"keep the work opening until the arrival's work is done");
                    assertEquals(1,w.moved);
                    repair[0]=true;
                } else if (!first[0]) {
                    assertFalse(repair[0]); first[0]=true;
                } else {
                    assertTrue(repair[0]); roof[0]=true;
                }
                w.placed++;
                return actual("{\"placed_blocks\":1,\"consumed_count\":1}");
            }
            return null;
        };
        var result=run(w,CODE.replace("max_rounds=3,",""),128);
        assertTrue(result.ok(),result.feedback());
        assertTrue(result.data().getAsJsonObject("returned").get("goal_satisfied").getAsBoolean());
        assertEquals(3,w.placed);
        assertEquals(2,w.moved);
    }

    @Test void fullTemporaryLedgerTriggersCleanupBeforeMoreConstructionAndReportsBoundedRetainedReasons() throws Exception {
        var w=new World(); w.moved=1;
        w.override=(name,args)-> {
            if (name.equals("build_site")) {
                var site=JsonParser.parseString("""
                        {"active":true,"target_revision":"%s","temporary":[]}
                        """.formatted(REV)).getAsJsonObject();
                for (int i=w.breaks>0 ? 1 : 0;i<16;i++) {
                    site.getAsJsonArray("temporary").add(JsonParser.parseString("""
                            {"x":%d,"y":90,"z":0,"block":"minecraft:stone","present_known":true,
                            "present":true,"cleanup_allowed":%s,"cleanup_reason":"dependent_block"}
                            """.formatted(8+i,i==0)));
                }
                return new ToolOutcome(true,"fresh supports",site);
            }
            if (name.equals("build_nearby")) assertEquals(1,w.breaks,
                    "free an actual source slot before continuing the construction batch");
            return null;
        };
        var result=run(w,CODE,128);
        assertTrue(result.ok(),result.feedback());
        var report=result.data().getAsJsonObject("returned");
        assertTrue(report.get("goal_satisfied").getAsBoolean());
        assertEquals(1,report.get("temporary_removed").getAsInt());
        assertEquals(15,report.get("temporary_retained").getAsInt());
        assertEquals(8,report.getAsJsonArray("temporary_retained_reasons").size());
        assertEquals(7,report.get("temporary_retained_reasons_omitted").getAsInt());
    }

    @Test void cleanupDoesNotContinueAfterCancellationPermissionOrSearchStall() throws Exception {
        for (String reason:List.of("CANCELLED:owner stopped","DENIED:permission changed",
                "NAVIGATION_STALLED:yield for reassessment")) {
            var w=new World();
            w.override=(name,args)-> {
                if (name.equals("build_site")) return actual("""
                        {"active":true,"target_revision":"%s","temporary":[
                        {"x":8,"y":92,"z":0,"block":"minecraft:stone","cleanup_allowed":true,"present_known":true,"present":true},
                        {"x":9,"y":90,"z":0,"block":"minecraft:stone","cleanup_allowed":true,"present_known":true,"present":true}]}
                        """.formatted(REV));
                return name.equals("move_to") ? new ToolOutcome(false,reason) : null;
            };
            var result=run(w,"return mc.cleanup_temporary('"+REV+"')",128);
            assertFalse(result.ok(),reason);
            assertTrue(result.feedback().contains(reason),result.feedback());
            assertEquals("move_to",w.calls.getLast().name());
            assertEquals(1,w.calls.stream().filter(c->c.name().equals("move_to")).count());
            assertEquals(0,w.breaks);
        }
    }

    @Test void oneUnreachableTemporarySupportDoesNotPreventCleaningOtherSupports() throws Exception {
        var w=new World();
        w.override=(name,args)-> {
            if (name.equals("build_site")) return actual("""
                    {"active":true,"target_revision":"%s","temporary":[
                    {"x":8,"y":92,"z":0,"block":"minecraft:stone","cleanup_allowed":true,"present_known":true,"present":true},
                    {"x":9,"y":90,"z":0,"block":"minecraft:stone","cleanup_allowed":true,"present_known":true,"present":%s}]}
                    """.formatted(REV,w.breaks==0));
            if (name.equals("move_to") && args.get("x").getAsInt()==8)
                return new ToolOutcome(false,"NO_PATH:upper support currently unreachable");
            return null;
        };
        var result=run(w,"return mc.cleanup_temporary('"+REV+"')",128);
        assertTrue(result.ok(),result.feedback());
        var returned=result.data().getAsJsonObject("returned");
        assertEquals(1,returned.get("removed").getAsInt());
        assertEquals(1,returned.get("retained").getAsInt());
        assertEquals(1,w.breaks);
        assertTrue(returned.getAsJsonArray("failures").size()>=1);
    }
    @Test void reopeningAndRepairingTheSameDrawingStateDoesNotKeepConstructionWalkingForever() throws Exception {
        var w=new World();
        w.override=(name,args)-> {
            if (name.equals("build_diff")) {
                boolean ready=w.moved>w.placed;
                return actual("""
                        {"target_revision":"%s","total":3,"remaining":1,"goal_satisfied":false,
                        "observation_complete":true,"spatial_stable":true,"budget_exhausted":false,
                        "observation_key":"observation-%d-%d","progress_observation_key":"inventory-%d-%d",
                        "goal_observation_key":"drawing-%d","offset":0,"next_offset":-1,
                        "cells":[{"x":4,"y":90,"z":0,"state":"%s","item":"stone","material_available":true}]}
                        """.formatted(REV,w.moved,w.placed,w.moved,w.placed,w.placed%2,
                        ready?"ready":"out_of_reach"));
            }
            if (name.equals("build_nearby")) {
                w.placed++;
                return actual("{\"placed_blocks\":1,\"consumed_count\":1}");
            }
            return null;
        };
        var result=run(w,CODE.replace("max_rounds=3,",""),128);
        assertTrue(result.ok(),result.feedback());
        var returned=result.data().getAsJsonObject("returned");
        assertEquals("construction_state_repeated",returned.get("blocked").getAsString());
        assertFalse(returned.get("goal_satisfied").getAsBoolean());
        assertTrue(returned.get("model_reassessment_required").getAsBoolean());
        assertTrue(returned.get("remaining_known").getAsBoolean());
        assertEquals(2,w.moved,"target states A -> B -> A must yield before another route");
        assertEquals(2,w.placed,"real placements in a cycle are retained, not called completion");
    }

    @Test void aSuccessfulWorkMoveWithoutConstructionCannotBeRepeatedByInventoryChanges() throws Exception {
        var w=new World();
        w.override=(name,args)->name.equals("build_diff") ? actual("""
                {"target_revision":"%s","remaining":2,"goal_satisfied":false,"observation_complete":true,
                "spatial_stable":true,"budget_exhausted":false,"observation_key":"body-%d",
                "progress_observation_key":"inventory-%d","goal_observation_key":"same-drawing",
                "offset":0,"next_offset":-1,"cells":[{"x":%d,"y":90,"z":0,
                "state":"out_of_reach","item":"stone","material_available":true}]}
                """.formatted(REV,w.moved,w.moved,4+w.moved)) : null;
        var result=run(w,CODE.replace("max_rounds=3,",""),128);
        assertTrue(result.ok(),result.feedback());
        assertEquals("construction_state_repeated",
                result.data().getAsJsonObject("returned").get("blocked").getAsString());
        assertEquals(1,w.moved);
    }

    @Test void differentDrawingStatesWithTheSameRemainingCountCanContinueWork() throws Exception {
        var w=new World();
        w.override=(name,args)-> {
            if (name.equals("build_diff") && w.placed<4) {
                boolean ready=w.moved>w.placed;
                return actual("""
                        {"target_revision":"%s","remaining":2,"goal_satisfied":false,
                        "observation_complete":true,"spatial_stable":true,"budget_exhausted":false,
                        "observation_key":"observation-%d","goal_observation_key":"drawing-%d",
                        "offset":0,"next_offset":-1,"cells":[{"x":%d,"y":90,"z":0,
                        "state":"%s","item":"stone","material_available":true}]}
                        """.formatted(REV,w.placed,w.placed,4+w.placed,ready?"ready":"out_of_reach"));
            }
            if (name.equals("build_nearby")) {
                w.placed++;
                if (w.placed==4) w.remaining=0;
                return actual("{\"placed_blocks\":1,\"consumed_count\":1}");
            }
            return null;
        };
        var result=run(w,CODE.replace("max_rounds=3,",""),128);
        assertTrue(result.ok(),result.feedback());
        assertTrue(result.data().getAsJsonObject("returned").get("goal_satisfied").getAsBoolean());
        assertEquals(4,w.moved);
        assertEquals(4,w.placed);
    }

    @Test void aStalledRouteReturnsToTheModelInsteadOfTryingMoreConstructionCandidates() throws Exception {
        var w=new World();
        var stalled=new JsonObject();
        stalled.addProperty("navigation_stalled",true);
        stalled.addProperty("model_reassessment_required",true);
        stalled.addProperty("search_idle_ms",30000);
        w.override=(name,args)->name.equals("move_to") ? new ToolOutcome(false,
                "NAVIGATION_STALLED:30秒没有实际进展，请核对现场再选择路线。",stalled) : null;
        var result=run(w,CODE.replace("max_rounds=3,",""),128);
        assertFalse(result.ok(),result.feedback());
        assertTrue(result.feedback().contains("NAVIGATION_STALLED:"),result.feedback());
        assertTrue(result.data().getAsJsonObject("last_receipt").get("model_reassessment_required").getAsBoolean());
        assertEquals(1,w.calls.stream().filter(c->c.name().equals("move_to")).count());
        assertEquals("move_to",w.calls.getLast().name(),"the helper must yield immediately");
        assertEquals(0,w.placed);
    }

    @Test void cleanupWithAnotherRevisionReportsTheConflictAndNeverDispatchesAnEdit() throws Exception {
        var w=new World();
        var result=run(w,"return mc.cleanup_temporary('"+ "b".repeat(64) +"')",128);
        assertTrue(result.ok(),result.feedback());
        var report=result.data().getAsJsonObject("returned");
        assertEquals("worksite_revision_mismatch",report.get("blocked").getAsString());
        assertFalse(report.get("goal_satisfied").getAsBoolean());
        assertEquals(1,w.calls.size()); assertEquals(0,w.breaks); assertEquals(0,w.moved);
    }

    @Test void unsupportedEavesAreDeferredUntilARealRoofCourseChangesTheWorkFacts() throws Exception {
        var world=new World(); world.moved=1;
        world.override=(name,args)->{
            if (name.equals("build_diff") && world.remaining>0) return actual("""
                    {"target_revision":"%s","remaining":%d,"goal_satisfied":false,"observation_complete":true,
                    "spatial_stable":true,"budget_exhausted":false,"progress_observation_key":"roof-%d",
                    "observation_key":"roof-%d","offset":0,"next_offset":-1,"cells":[
                    {"x":4,"y":94,"z":-1,"state":"%s","item":"stone","material_available":true},
                    {"x":4,"y":94,"z":0,"state":"%s","item":"stone","material_available":true}]}
                    """.formatted(REV,world.remaining,world.placed,world.placed,
                    world.placed==0?"needs_support":world.moved>1?"ready":"out_of_reach",
                    world.placed==0?"ready":"satisfied"));
            if (name.equals("move_to")) {
                assertEquals(1,world.placed,"do the inner roof first instead of navigating to an unsupported eave");
                assertEquals(-1,args.get("z").getAsInt());
            }
            if (name.equals("build_nearby")) {
                world.placed++; world.remaining--;
                return actual("{\"placed_blocks\":1,\"consumed_count\":1}");
            }
            return null;
        };
        var result=run(world,CODE.replace("max_rounds=3,",""),128);
        assertTrue(result.ok(),result.feedback());
        assertTrue(result.data().getAsJsonObject("returned").get("goal_satisfied").getAsBoolean());
        assertEquals(2,world.placed);
        assertEquals(1,world.calls.stream().filter(c->c.name().equals("move_to")).count());
    }

    @Test void aLowerUnfinishedFloorIsApproachedBeforeAReachableRoof() throws Exception {
        var w=new World();
        boolean[] first={true};
        w.override=(name,args)-> {
            if (name.equals("build_diff") && w.moved==0) return actual("""
                    {"target_revision":"%s","remaining":2,"goal_satisfied":false,"observation_complete":true,
                    "spatial_stable":true,"budget_exhausted":false,"observation_key":"initial",
                    "offset":0,"next_offset":-1,"cells":[
                    {"x":4,"y":90,"z":0,"state":"out_of_reach","item":"stone","material_available":true},
                    {"x":4,"y":94,"z":0,"state":"ready","item":"stone","material_available":true}]}
                    """.formatted(REV));
            if (name.equals("move_to") || name.equals("build_nearby")) {
                if (first[0]) {
                    first[0]=false;
                    assertEquals("move_to",name,"finish the floor before closing its roof");
                    assertEquals(90,args.get("y").getAsInt());
                }
            }
            return null;
        };
        var result=run(w,CODE,128);
        assertTrue(result.ok(),result.feedback());
        assertTrue(result.data().getAsJsonObject("returned").get("goal_satisfied").getAsBoolean());
    }

    @Test void freshReadyWorkIsNotAbandonedBecauseAnotherDrawingCellIntersectsTheBody() throws Exception {
        var w = new World(); w.moved = 1;
        w.override = (name,args) -> {
            if (!name.equals("build_site")) return null;
            return actual("""
                    {"active":true,"target_revision":"%s","temporary":[],"body_obstructs_target":true,
                    "relocation":{"operation":"move","x":-1,"y":90,"z":0,"walk_only":true}}
                    """.formatted(REV));
        };
        var result = run(w,CODE.replace("max_rounds=3,",""),128);
        assertTrue(result.ok(),result.feedback());
        assertTrue(result.data().getAsJsonObject("returned").get("goal_satisfied").getAsBoolean());
        assertEquals(2,w.placed);
        assertEquals(0,w.calls.stream().filter(c->c.name().equals("move_to")).count());
    }

    @Test void bodyOnlyChangesDoNotReplayAnUnproductiveWorkApproach() throws Exception {
        var w = new World();
        w.override = (name,args) -> name.equals("build_diff") ? actual("""
                {"target_revision":"%s","remaining":2,"goal_satisfied":false,"observation_complete":true,
                "spatial_stable":true,"budget_exhausted":false,"observation_key":"body-%d",
                "work_observation_key":"body-%d","progress_observation_key":"same-world-and-material",
                "offset":0,"next_offset":-1,"cells":[{"x":4,"y":90,"z":0,
                "state":"out_of_reach","item":"stone","material_available":true}]}
                """.formatted(REV,w.moved,w.moved)) : null;
        var result = run(w,CODE.replace("max_rounds=3,",""),128);
        assertTrue(result.ok(),result.feedback());
        assertFalse(result.data().getAsJsonObject("returned").get("goal_satisfied").getAsBoolean());
        assertEquals(1,w.calls.stream().filter(c->c.name().equals("move_to")).count());
        assertEquals(0,w.placed);
    }

    @Test void aPlantLostBeforeArrivalIsReobservedAndReplacedWithoutMiningAirOrStoppingOtherWork() throws Exception {
        var world = new World(); world.clearing = true;
        boolean[] vanished = {false};
        world.override = (name,args) -> {
            if (!name.equals("move_to") || vanished[0]) return null;
            vanished[0] = true;
            world.clearing = false;
            return new ToolOutcome(false,"TARGET_BLOCKED:target became air",
                    JsonParser.parseString("{\"target_already_clear\":true,\"removed\":false}").getAsJsonObject());
        };
        var result = run(world,CODE,128);
        assertTrue(result.ok(),result.feedback());
        assertTrue(result.data().getAsJsonObject("returned").get("goal_satisfied").getAsBoolean());
        assertEquals(0,world.breaks);
        assertEquals(0,result.data().getAsJsonObject("returned").get("cleared_blocks").getAsInt());
        assertEquals(2,world.placed);
        assertEquals(2,world.calls.stream().filter(c->c.name().equals("move_to")).count());
    }

    @Test void constructionClearsTheWorkRayUnderTheSameRevisionAndCountsTheActualCoverRemoval() throws Exception {
        var world = new World(); world.clearing = true;
        world.override = (name,args) -> {
            if (!name.equals("break_block")) return null;
            assertEquals(REV,args.get("site_revision").getAsString());
            assertEquals("minecraft:dirt",args.get("expected_block").getAsString());
            assertTrue(args.get("clear_obstructions").getAsBoolean());
            world.clearing = false; world.breaks++;
            return actual("{\"removed\":true,\"cleared_blocks\":2}");
        };
        var result = run(world,CODE,128);
        assertTrue(result.ok(),result.feedback());
        assertEquals(3,result.data().getAsJsonObject("returned").get("cleared_blocks").getAsInt());
        var workMove = world.calls.stream().filter(c -> c.name().equals("move_to")).findFirst().orElseThrow().args();
        assertTrue(workMove.get("clear_obstructions").getAsBoolean(),
                "a clearing goal must admit the same safe sight clearing as the subsequent action");
        assertFalse(workMove.has("place_item"));
    }

    @Test void failedLandingRetainsTheRealRemovalAndDoesNotContinueDismantling() throws Exception {
        var w = new World();
        boolean[] removed = {false};
        w.override = (name,args) -> {
            if (name.equals("build_site")) return actual("""
                    {"active":true,"target_revision":"%s","body_on_temporary":true,
                    "relocation":{"operation":"lower","x":8,"y":91,"z":0,
                    "expected_block":"minecraft:stone","temporary_cleanup":true},"temporary":[
                    {"x":8,"y":91,"z":0,"block":"minecraft:stone","present_known":true,
                    "present":%s,"cleanup_allowed":true},
                    {"x":8,"y":90,"z":0,"block":"minecraft:stone","present_known":true,
                    "present":true,"cleanup_allowed":false}]}
                    """.formatted(REV,!removed[0]));
            if (name.equals("break_block")) {
                assertFalse(removed[0]);
                removed[0]=true;
                return actual("{\"removed\":true}");
            }
            if (name.equals("move_to")) return new ToolOutcome(false,"NO_PATH:landing changed");
            return null;
        };
        var result=run(w,"return mc.cleanup_temporary('"+REV+"')",128);
        assertTrue(result.ok(),result.feedback());
        var report=result.data().getAsJsonObject("returned");
        assertTrue(report.get("blocked").getAsBoolean());
        assertEquals(1,report.get("removed").getAsInt());
        assertEquals(1,report.get("retained").getAsInt());
        assertEquals(1,w.calls.stream().filter(c->c.name().equals("break_block")).count());
        assertEquals(1,w.calls.stream().filter(c->c.name().equals("move_to")).count());
    }

    @Test void cleanupDescendsAnOccupiedScaffoldOneBlockAtATimeAndWaitsForEachLanding() throws Exception {
        var w = new World();
        int[] feet = {92};
        boolean[] falling = {false};
        var removed = new HashSet<Integer>();
        w.override = (name,args) -> {
            if (name.equals("build_site")) {
                assertFalse(falling[0], "confirm the landing before querying another descent");
                var data = JsonParser.parseString("""
                        {"active":true,"target_revision":"%s","body_on_temporary":%s,"temporary":[]}
                        """.formatted(REV,feet[0]>90)).getAsJsonObject();
                if (feet[0]>90) data.add("relocation",JsonParser.parseString("""
                        {"operation":"lower","x":8,"y":%d,"z":0,"expected_block":"minecraft:stone",
                        "temporary_cleanup":true}
                        """.formatted(feet[0]-1)));
                for (int y=90; y<=91; y++) data.getAsJsonArray("temporary").add(JsonParser.parseString("""
                        {"x":8,"y":%d,"z":0,"block":"minecraft:stone","present_known":true,
                        "present":%s,"cleanup_allowed":false}
                        """.formatted(y,!removed.contains(y))));
                return new ToolOutcome(true,"fresh body and supports",data);
            }
            if (name.equals("break_block")) {
                assertEquals(feet[0]-1,args.get("y").getAsInt());
                assertTrue(args.get("temporary_cleanup").getAsBoolean());
                assertTrue(removed.add(feet[0]-1));
                falling[0]=true;
                return actual("{\"removed\":true}");
            }
            if (name.equals("move_to")) {
                assertTrue(falling[0]);
                assertTrue(args.get("walk_only").getAsBoolean());
                assertTrue(args.get("exact").getAsBoolean());
                assertFalse(args.has("work_target"));
                feet[0]=args.get("y").getAsInt();
                falling[0]=false;
                return actual("{\"placed_blocks\":0}");
            }
            return null;
        };
        var result=run(w,"return mc.cleanup_temporary('"+REV+"')",128);
        assertTrue(result.ok(),result.feedback());
        assertEquals(90,feet[0]);
        assertEquals(2,result.data().getAsJsonObject("returned").get("removed").getAsInt());
        assertEquals(0,result.data().getAsJsonObject("returned").get("retained").getAsInt());
    }

    @Test void cleanupRechecksDependenciesAndRemovesAColumnFromTheTopDown() throws Exception {
        var w = new World();
        var removed = new HashSet<Integer>();
        var order = new ArrayList<Integer>();
        w.override = (name,args) -> {
            if (name.equals("build_site")) {
                var data = JsonParser.parseString("""
                        {"active":true,"target_revision":"%s","temporary":[]}
                        """.formatted(REV)).getAsJsonObject();
                for (int y = 90; y <= 92; y++) {
                    var row = JsonParser.parseString("""
                            {"x":8,"y":%d,"z":0,"block":"minecraft:stone",
                            "present_known":true,"present":%s,"cleanup_allowed":%s}
                            """.formatted(y,!removed.contains(y),y==92 || removed.contains(y+1))).getAsJsonObject();
                    data.getAsJsonArray("temporary").add(row);
                }
                return new ToolOutcome(true,"fresh scaffold",data);
            }
            if (name.equals("break_block")) {
                int y = args.get("y").getAsInt();
                assertTrue(y==92 || removed.contains(y+1), "never remove a lower block before its dependent");
                assertTrue(removed.add(y));
                order.add(y);
                return actual("{\"removed\":true}");
            }
            return null;
        };
        var result = run(w,"return mc.cleanup_temporary('"+REV+"')",128);
        assertTrue(result.ok(),result.feedback());
        assertEquals(List.of(92,91,90),order);
        assertEquals(3,result.data().getAsJsonObject("returned").get("removed").getAsInt());
        assertEquals(0,result.data().getAsJsonObject("returned").get("retained").getAsInt());
    }

    @Test void freshWholeTargetBodyEvidenceTriggersRelocationEvenIfTheInitialSiteWasClear() throws Exception {
        var w=new World();
        w.override=(name,args)-> {
            if (name.equals("build_site")) return actual("""
                    {"active":true,"target_revision":"%s","temporary":[],"body_obstructs_target":%s,
                    "relocation":{"operation":"move","x":-1,"y":90,"z":0,"walk_only":true}}
                    """.formatted(REV,args.get("operation").getAsString().equals("query") && w.moved==0));
            if (name.equals("build_diff") && w.moved==0) return actual("""
                    {"target_revision":"%s","goal_satisfied":false,"remaining":2,"observation_complete":true,
                    "spatial_stable":true,"budget_exhausted":false,"body_obstructs_target":true,
                    "observation_key":"body-in-drawing","offset":0,"next_offset":-1,"cells":[
                    {"x":4,"y":90,"z":0,"state":"blocked","item":"stone","material_available":true}]}
                    """.formatted(REV));
            return null;
        };
        var result=run(w,CODE,128);
        assertTrue(result.ok(),result.feedback());
        assertTrue(result.data().getAsJsonObject("returned").get("goal_satisfied").getAsBoolean());
        var move=w.calls.stream().filter(c->c.name().equals("move_to")).findFirst().orElseThrow().args();
        assertFalse(move.has("work_target"));
        assertEquals(-1,move.get("x").getAsInt());
    }

    @Test void navigationSupportEffectsSurviveThePrivateViewAndTriggerAutomaticCleanup() throws Exception {
        var w=new World();
        boolean[] removed={false};
        w.override=(name,args)-> {
            if (name.equals("build_site")) return actual(w.moved==0
                    ? "{\"active\":true,\"target_revision\":\""+REV+"\",\"temporary\":[]}"
                    : """
                    {"active":true,"target_revision":"%s","temporary":[
                    {"x":8,"y":90,"z":0,"block":"minecraft:stone","present_known":true,
                    "present":%s,"cleanup_allowed":true}]}
                    """.formatted(REV,!removed[0]));
            if (name.equals("move_to")) {
                w.moved++;
                return actual("{\"placed_blocks\":"+(w.moved==1?1:0)+",\"can_work_here\":true}");
            }
            if (name.equals("break_block")) {
                assertTrue(args.get("temporary_cleanup").getAsBoolean());
                removed[0]=true; w.breaks++;
                return actual("{\"removed\":true}");
            }
            return null;
        };
        var result=run(w,CODE,128);
        assertTrue(result.ok(),result.feedback());
        assertTrue(result.data().getAsJsonObject("returned").get("goal_satisfied").getAsBoolean());
        assertEquals(1,w.breaks);
        assertEquals(1,result.data().getAsJsonObject("returned").get("temporary_removed").getAsInt());
    }

    @Test void repairsAreDeferredUntilOtherWorkAndThenRestoredUnderTheSameRevision() throws Exception {
        var w = new World();
        w.override = (name,args) -> {
            if (name.equals("build_diff")) return actual("""
                    {"target_revision":"%s","goal_satisfied":%s,"remaining":%d,
                    "observation_complete":true,"spatial_stable":true,"budget_exhausted":false,
                    "observation_key":"work-%d","offset":0,"next_offset":-1,"cells":[
                    {"x":4,"y":90,"z":0,"state":"%s","item":"stone","material_available":true,"repair_pending":true},
                    {"x":5,"y":90,"z":0,"state":"%s","item":"stone","material_available":true}]}
                    """.formatted(REV,w.remaining==0,w.remaining,w.remaining,
                    w.remaining==0?"satisfied":"ready",w.remaining<2?"satisfied":"ready"));
            if (name.equals("build_nearby")) {
                assertEquals(REV,args.get("expected_revision").getAsString());
                assertEquals(w.remaining==1,args.get("include_repairs").getAsBoolean());
                w.remaining--; w.placed++;
                return actual("{\"placed_blocks\":1,\"consumed_count\":1}");
            }
            return null;
        };
        var result=run(w,CODE.replace("max_rounds=3,",""),128);
        assertTrue(result.ok(),result.feedback());
        assertTrue(result.data().getAsJsonObject("returned").get("goal_satisfied").getAsBoolean());
        assertEquals(2,w.placed);
    }

    @Test void anUnchangedRecoveryIsNotRetriedAndPermissionOrCancellationFailuresStopIt() throws Exception {
        for (String failure : List.of("NO_PATH:unchanged","DENIED:permission","CANCELLED:owner")) {
            var w=new World(); w.moveFailure=failure;
            w.override=(name,args)->name.equals("build_site") ? actual("""
                    {"active":true,"target_revision":"%s","temporary":[],"body_obstructs_target":true,
                    "relocation":{"operation":"move","x":-1,"y":90,"z":0,"walk_only":true}}
                    """.formatted(REV)) : null;
            var result=run(w,CODE.replace("max_rounds=3,",""),128);
            assertEquals(failure.startsWith("NO_PATH"),result.ok(),result.feedback());
            assertEquals(1,w.calls.stream().filter(c->c.name().equals("move_to")
                    && !c.args().has("work_target")).count(),"the same failed local plan is attempted once");
            assertEquals(0,w.placed);
            assertEquals(0,w.breaks);
        }
    }

    @Test void wholeTargetCompletionAutomaticallyReclaimsKnownTemporaryWorkAndRechecksTheDrawing() throws Exception {
        var w=new World(); w.remaining=0;
        boolean[] removed={false};
        w.override=(name,args)-> {
            if (name.equals("build_site")) return actual("""
                    {"active":true,"target_revision":"%s","temporary":[
                    {"x":8,"y":90,"z":0,"block":"minecraft:stone","present_known":true,
                    "present":%s,"cleanup_allowed":true}]}
                    """.formatted(REV,!removed[0]));
            if (name.equals("break_block")) {
                assertTrue(args.get("temporary_cleanup").getAsBoolean());
                assertEquals(REV,args.get("site_revision").getAsString());
                removed[0]=true; w.breaks++;
                return actual("{\"removed\":true}");
            }
            return null;
        };
        var result=run(w,CODE,128);
        assertTrue(result.ok(),result.feedback());
        assertEquals(1,w.breaks);
        var report=result.data().getAsJsonObject("returned");
        assertTrue(report.get("goal_satisfied").getAsBoolean());
        assertEquals(1,report.get("temporary_removed").getAsInt());
        assertEquals(0,report.get("temporary_retained").getAsInt());
        assertEquals("build_diff",w.calls.getLast().name());
    }
    @Test void aBodyInTheDrawingMovesToTheObservedAdjacentStanceBeforeStartingWork() throws Exception {
        var w = new World();
        w.override = (name,args) -> name.equals("build_site") ? actual("""
                {"active":true,"target_revision":"%s","temporary":[],"body_obstructs_target":%s,
                "relocation":{"operation":"move","x":-1,"y":90,"z":0,"walk_only":true}}
                """.formatted(REV,w.moved==0)) : null;
        var result = run(w,CODE,128);
        assertTrue(result.ok(),result.feedback());
        var moved = w.calls.stream().filter(c -> c.name().equals("move_to")).toList();
        assertEquals(1,moved.size());
        assertEquals(-1,moved.getFirst().args().get("x").getAsInt());
        assertTrue(moved.getFirst().args().get("walk_only").getAsBoolean());
        assertFalse(moved.getFirst().args().has("work_target"));
        assertTrue(result.data().getAsJsonObject("returned").get("goal_satisfied").getAsBoolean());
        assertEquals(1,result.data().getAsJsonObject("returned").get("relocations").getAsInt());
    }

    @Test void aProvenBlockedDepartureExecutesARealLocalRecoveryAndThenResumesTheOriginalDrawing() throws Exception {
        var w = new World();
        w.override = (name,args) -> {
            if (name.equals("build_site")) return actual("""
                    {"active":true,"target_revision":"%s","temporary":[],"body_obstructs_target":false,
                    "relocation":{"operation":"move","x":-1,"y":90,"z":0,"walk_only":false}}
                    """.formatted(REV));
            if (name.equals("move_to") && args.has("work_target") && w.moved==0)
                return new ToolOutcome(false,"NO_PATH:closed departure",
                        JsonParser.parseString("{\"departure_blocked\":true}").getAsJsonObject());
            return null;
        };
        var result = run(w,CODE,128);
        assertTrue(result.ok(),result.feedback());
        assertTrue(result.data().getAsJsonObject("returned").get("goal_satisfied").getAsBoolean());
        assertEquals(2,w.calls.stream().filter(c -> c.name().equals("move_to")).count());
        assertEquals(1,w.moved);
        assertEquals(2,w.placed);
    }

    @Test void aSafeColumnDescentUsesTheOriginalBreakToolWithTheFrozenRevisionAndExpectedBlock() throws Exception {
        var w = new World();
        w.override = (name,args) -> name.equals("build_site") ? actual("""
                {"active":true,"target_revision":"%s","temporary":[],"body_obstructs_target":%s,
                "relocation":{"operation":"lower","x":0,"y":89,"z":0,
                "expected_block":"minecraft:stone","temporary_cleanup":false}}
                """.formatted(REV,w.breaks==0)) : null;
        var result = run(w,CODE,128);
        assertTrue(result.ok(),result.feedback());
        assertEquals(1,w.breaks);
        var mined = w.calls.stream().filter(c -> c.name().equals("break_block")).findFirst().orElseThrow().args();
        assertEquals(REV,mined.get("site_revision").getAsString());
        assertEquals("minecraft:stone",mined.get("expected_block").getAsString());
        assertTrue(result.data().getAsJsonObject("returned").get("goal_satisfied").getAsBoolean());
    }
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
                    moved++;
                    var data=new JsonObject(); data.addProperty("can_work_here",true);
                    if (args.has("work_targets")) {
                        var member=args.getAsJsonArray("work_targets").get(0).getAsJsonObject();
                        var selected=new JsonObject();
                        for (String key:List.of("x","y","z")) selected.add(key,member.get(key));
                        data.add("selected_work_target",selected);
                    }
                    yield new ToolOutcome(true,"observed",data);
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
    @Test void clearingUsesFreshSameCellObservationAndTheOriginalMiningEntryWithExplicitSightClearing() throws Exception {
        var w=new World(); w.clearing=true;
        assertTrue(run(w,CODE,128).ok()); assertEquals(1,w.breaks);
        int index=0;
        while(!w.calls.get(index).name().equals("break_block")) index++;
        assertEquals("build_diff",w.calls.get(index-1).name());
        var args=w.calls.get(index).args();
        assertEquals(REV,args.get("site_revision").getAsString());
        assertEquals("minecraft:dirt",args.get("expected_block").getAsString());
        assertTrue(args.get("clear_obstructions").getAsBoolean());
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
    @Test void unrelatedChunkChangesDoNotReplayFailedCandidatesAcrossWorkPages() throws Exception {
        var w=new World(); w.moveFailure="NO_PATH:unchanged work";
        int[] observed={0};
        w.override=(name,args)-> {
            if (!name.equals("build_diff")) return null;
            int cursor=args.has("work_cursor")?args.get("work_cursor").getAsInt():0;
            if (cursor==0) observed[0]++;
            return actual("""
                    {"target_revision":"%s","total":40,"remaining":40,"goal_satisfied":false,
                    "observation_complete":true,"spatial_stable":true,"budget_exhausted":false,
                    "observation_key":"chunk-%d","work_observation_key":"same-work",
                    "work_scan":true,"work_next_cursor":%d,"offset":0,"next_offset":16,
                    "counts":{"out_of_reach":40},"cells":[{"x":%d,"y":90,"z":0,
                    "state":"out_of_reach","item":"stone","material_available":true}]}
                    """.formatted(REV,observed[0],cursor==0?16:-1,cursor==0?4:5));
        };
        var result=run(w,CODE.replace("max_rounds=3,",""),128);
        assertTrue(result.ok(),result.feedback());
        var moves=w.calls.stream().filter(c->c.name().equals("move_to")).toList();
        assertEquals(1,moves.size(),"the failed composite is not replayed by unrelated chunk changes");
        assertEquals(List.of(4,5),java.util.stream.StreamSupport.stream(
                moves.getFirst().args().getAsJsonArray("work_targets").spliterator(),false)
                .map(c->c.getAsJsonObject().get("x").getAsInt()).toList());
        assertEquals("placement_stance_unavailable",result.data().getAsJsonObject("returned").get("blocked").getAsString());
    }

    @Test void aProvenBlockedDepartureStopsRemoteCandidateEnumerationButKeepsFreshBodyAndFailure() throws Exception {
        var w=new World();
        w.override=(name,args)-> {
            if (name.equals("build_diff")) return actual("""
                    {"target_revision":"%s","goal_satisfied":false,"remaining":2,
                    "observation_complete":true,"spatial_stable":true,"budget_exhausted":false,
                    "observation_key":"same","offset":0,"next_offset":-1,"cells":[
                    {"x":4,"y":90,"z":0,"state":"out_of_reach","item":"stone","material_available":true},
                    {"x":5,"y":90,"z":0,"state":"out_of_reach","item":"stone","material_available":true}]}
                    """.formatted(REV));
            if (name.equals("move_to")) return new ToolOutcome(false,"NO_PATH:departure has no edge",
                    JsonParser.parseString("{\"departure_blocked\":true}").getAsJsonObject());
            return null;
        };
        var result=run(w,CODE.replace("max_rounds=3,",""),128);
        assertTrue(result.ok(),result.feedback());
        var report=result.data().getAsJsonObject("returned");
        assertFalse(report.get("goal_satisfied").getAsBoolean());
        assertEquals("departure_blocked",report.get("blocked").getAsString());
        assertEquals("NO_PATH:departure has no edge",report.get("last_failure").getAsString());
        assertEquals(1,w.calls.stream().filter(c->c.name().equals("move_to")).count());
        int index=0; while(!w.calls.get(index).name().equals("move_to")) index++;
        assertEquals("status",w.calls.get(index+1).name());
        assertTrue(result.feedback().contains("NO_PATH:departure has no edge"),result.feedback());
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
            if(name.equals("move_to") && args.has("work_targets")) {
                assertEquals(2,args.getAsJsonArray("work_targets").size());
                w.moved++; return actual("{\"can_work_here\":true,\"selected_work_target\":{\"x\":5,\"y\":90,\"z\":0}}");
            }
            return null;
        };
        var result=run(w,CODE,128);
        assertTrue(result.ok(),result.feedback());
        assertTrue(result.data().getAsJsonObject("returned").get("goal_satisfied").getAsBoolean());
        assertEquals(1,w.calls.stream().filter(c->c.name().equals("move_to")).count(),
                "one composite search reaches the alternative without first exhausting the sealed member");
        assertTrue(w.calls.stream().filter(c->c.name().equals("move_to")).noneMatch(c->c.args().has("face")),
                "work navigation chooses a real clickable face rather than forcing UP");
    }

    @Test void sameLayerMembersAcrossWorkPagesKeepMaterialsAndRevisionAndStopBeforeTheHigherLayer() throws Exception {
        var w=new World();
        w.override=(name,args)-> {
            if(name.equals("build_diff") && w.moved==0) {
                int cursor=args.get("work_cursor").getAsInt();
                return actual("""
                        {"target_revision":"%s","goal_satisfied":false,"remaining":3,
                        "observation_complete":true,"spatial_stable":true,"budget_exhausted":false,
                        "observation_key":"same","work_scan":true,"work_next_cursor":%d,
                        "offset":0,"next_offset":16,"cells":[
                        {"x":%d,"y":%d,"z":0,"state":"out_of_reach","item":"%s","material_available":true}]}
                        """.formatted(REV,cursor==0?16:cursor==16?32:-1,
                        cursor==0?4:5,cursor==32?91:90,cursor==0?"stone":"oak_log"));
            }
            if(name.equals("move_to")) {
                assertEquals(REV,args.get("expected_revision").getAsString());
                var members=args.getAsJsonArray("work_targets");
                assertEquals(2,members.size());
                assertEquals("oak_log",members.get(1).getAsJsonObject().get("item").getAsString());
                for(var member:members) assertEquals(90,member.getAsJsonObject().get("y").getAsInt());
                assertFalse(args.has("work_target"));
                assertFalse(args.has("place_item"));
                w.moved++;
                return actual("{\"can_work_here\":true,\"selected_work_target\":{\"x\":5,\"y\":90,\"z\":0}}");
            }
            return null;
        };
        var result=run(w,CODE,128);
        assertTrue(result.ok(),result.feedback());
        assertTrue(result.data().getAsJsonObject("returned").get("goal_satisfied").getAsBoolean());
        assertEquals(1,w.moved);
    }

    @Test void changedFactsDuringCandidateLookaheadStopBeforeStartingTheCombinedRoute() throws Exception {
        var w=new World();
        w.override=(name,args)-> {
            if(!name.equals("build_diff")) return null;
            int cursor=args.get("work_cursor").getAsInt();
            return actual("""
                    {"target_revision":"%s","goal_satisfied":false,"remaining":2,
                    "observation_complete":true,"spatial_stable":true,"budget_exhausted":false,
                    "observation_key":"%s","work_scan":true,"work_next_cursor":%d,
                    "offset":0,"next_offset":16,"cells":[
                    {"x":%d,"y":90,"z":0,"state":"out_of_reach","item":"stone","material_available":true}]}
                    """.formatted(REV,cursor==0?"before":"after",cursor==0?16:-1,cursor==0?4:5));
        };
        var result=run(w,CODE,128);
        assertTrue(result.ok(),result.feedback());
        assertEquals("world_or_material_changed_between_pages",
                result.data().getAsJsonObject("returned").get("blocked").getAsString());
        assertEquals(0,w.moved);
        assertEquals(0,w.checkpoints);
    }

    @Test void selectingTheSecondMemberDoesNotMarkTheUnchosenFirstMemberAsAlreadyTried() throws Exception {
        var w=new World(); w.repeat=true;
        w.override=(name,args)-> {
            if(name.equals("build_diff")) return actual("""
                    {"target_revision":"%s","goal_satisfied":false,"remaining":2,
                    "observation_complete":true,"spatial_stable":true,"budget_exhausted":false,
                    "observation_key":"same","offset":0,"next_offset":-1,"cells":[
                    {"x":4,"y":90,"z":0,"state":"out_of_reach","item":"stone","material_available":true},
                    {"x":5,"y":90,"z":0,"state":"out_of_reach","item":"stone","material_available":true}]}
                    """.formatted(REV));
            if(name.equals("move_to")) {
                w.moved++;
                if(w.moved==1) return actual("""
                        {"selected_work_target":{"x":5,"y":90,"z":0},"selected_place_item":"stone","placed_blocks":1}
                        """);
                assertFalse(args.has("work_targets"),"the selected second member is no longer eligible in this pass");
                assertEquals(4,args.get("x").getAsInt());
                return new ToolOutcome(false,"NO_PATH:remaining first");
            }
            return null;
        };
        var result=run(w,CODE.replace("max_rounds=3,",""),128);
        assertTrue(result.ok(),result.feedback());
        assertEquals(2,w.moved);
        assertFalse(result.data().getAsJsonObject("returned").get("goal_satisfied").getAsBoolean());
    }

    @Test void combinedArrivalWithoutAMatchingSelectedMemberCannotBeUsedAsWorkConfirmation() throws Exception {
        for (String selection:List.of("{}", "{\"selected_work_target\":{\"x\":99,\"y\":90,\"z\":0}}")) {
            var w=new World();
            w.override=(name,args)-> {
                if(name.equals("build_diff")) return actual("""
                        {"target_revision":"%s","goal_satisfied":false,"remaining":2,
                        "observation_complete":true,"spatial_stable":true,"budget_exhausted":false,
                        "observation_key":"same","offset":0,"next_offset":-1,"cells":[
                        {"x":4,"y":90,"z":0,"state":"out_of_reach","item":"stone","material_available":true},
                        {"x":5,"y":90,"z":0,"state":"out_of_reach","item":"stone","material_available":true}]}
                        """.formatted(REV));
                return name.equals("move_to")?actual(selection):null;
            };
            var result=run(w,CODE,128);
            assertTrue(result.ok(),result.feedback());
            var report=result.data().getAsJsonObject("returned");
            assertEquals("work_member_unconfirmed",report.get("blocked").getAsString());
            assertFalse(report.get("remaining_known").getAsBoolean());
            assertEquals(0,w.placed);
        }
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
                "temporary":[{"x":8,"y":90,"z":0,"block":"minecraft:stone","cleanup_allowed":true,
                "present_known":true,"present":%s},
                {"x":9,"y":90,"z":0,"block":"minecraft:stone","cleanup_allowed":false,
                "present_known":true,"present":true}]}
                """.formatted(REV,w.breaks==0)):null;
        var result=run(w,"return mc.cleanup_temporary('"+REV+"')",128);
        assertTrue(result.ok(),result.feedback());
        assertEquals(1,w.breaks);
        var mining=w.calls.stream().filter(c->c.name().equals("break_block")).findFirst().orElseThrow();
        assertTrue(mining.args().get("temporary_cleanup").getAsBoolean());
        assertTrue(w.calls.stream().filter(c->c.name().equals("move_to"))
                .allMatch(c->c.args().get("walk_only").getAsBoolean()));
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
