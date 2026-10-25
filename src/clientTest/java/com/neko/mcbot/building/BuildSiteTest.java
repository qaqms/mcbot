package com.neko.mcbot.building;

import com.google.gson.JsonParser;
import com.neko.mcbot.server.ServerTool;
import com.neko.mcbot.server.WorksiteProtection;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class BuildSiteTest {
    @Test void aScaffoldUnderFinishedPlainRoofBlocksCanBeRemovedWithoutChangingTheDrawing() {
        for (String block : List.of("stone","oak_planks","sand","crafting_table")) {
            var w = new World();
            var design = BuildTarget.parse(JsonParser.parseString("""
                    {"anchor":{"x":8,"y":91,"z":0},"cells":[{"x":0,"y":0,"z":0,"block":"%s"}]}
                    """.formatted(block)).getAsJsonObject());
            var site = new BuildSite(); site.bind("task",w);
            assertNull(site.begin(design,List.of(),FEET,w));
            temporary(site,w);
            w.states.put(TEMP.above(),design.cells().getFirst().desired());
            boolean allowed = block.equals("stone") || block.equals("oak_planks");
            assertEquals(allowed,site.miningGuard(TEMP,design.revision(),true,w)==null,block);
            assertEquals(design.revision(),site.target().revision());
        }
    }

    @Test void finishedPlainWorkCanBeReopenedAndTheSameFrozenCellRemainsDueUntilRestored() {
        var w = new World(); var site = site(w);
        w.states.put(TARGET,Blocks.STONE.defaultBlockState());
        assertNull(site.miningGuard(TARGET,target().revision(),false,w));
        assertEquals(target().revision(),site.navigationRevision(TARGET,w));
        assertNotNull(site.miningGuard(TARGET,null,false,w));
        site.recordRemoval(TARGET,w.state(TARGET),Blocks.AIR.defaultBlockState());
        w.states.remove(TARGET);
        assertTrue(site.repairPending(TARGET));
        assertEquals(1,site.report(w).get("repairs_pending").getAsInt());
        var saved = site.snapshot();
        var restored = new BuildSite(); restored.bind("next-session",w);
        restored.restore(saved,FEET,w);
        assertTrue(restored.repairPending(TARGET));
        assertNull(restored.placementGuard(TARGET,Blocks.STONE.defaultBlockState(),w));
        restored.recordPlacement(TARGET,Blocks.AIR.defaultBlockState(),Blocks.STONE.defaultBlockState(),1,w);
        w.states.put(TARGET,Blocks.STONE.defaultBlockState());
        assertFalse(restored.repairPending(TARGET));
        assertEquals(0,restored.report(w).get("repairs_pending").getAsInt());
        assertEquals(target().revision(),restored.target().revision());
    }

    @Test void reopeningStillRequiresRepairMaterialsAndDoesNotRemoveFacilitiesOrAnOccupiedSupport() {
        var w = new World(); var site = site(w);
        w.states.put(TARGET,Blocks.STONE.defaultBlockState());
        w.material = false;
        assertNotNull(site.miningGuard(TARGET,target().revision(),false,w));
        w.material = true; w.occupied.add(TARGET);
        assertNotNull(site.miningGuard(TARGET,target().revision(),false,w));
        w.occupied.clear(); w.states.put(TARGET.above(),Blocks.CRAFTING_TABLE.defaultBlockState());
        assertNotNull(site.miningGuard(TARGET,target().revision(),false,w));
        w.states.remove(TARGET.above()); w.states.put(TARGET,Blocks.CRAFTING_TABLE.defaultBlockState());
        assertNotNull(site.miningGuard(TARGET,target().revision(),false,w));
    }

    @Test void reclaimingAnOldRouteSupportNeedsLiveProvenanceAndNoCurrentBodyOrDependency() {
        var w = new World(); var site = site(w); temporary(site,w);
        w.passage.add(TEMP);
        assertNull(site.miningGuard(TEMP,target().revision(),true,w));
        assertEquals(target().revision(),site.navigationRevision(TEMP,w));
        w.occupied.add(TEMP);
        assertNotNull(site.miningGuard(TEMP,target().revision(),true,w));
        assertNull(site.navigationRevision(TEMP,w));
        w.occupied.clear(); w.states.put(TEMP.above(),Blocks.TORCH.defaultBlockState());
        assertNotNull(site.miningGuard(TEMP,target().revision(),true,w));
    }
    @Test void recoveredSiteKeepsProtectionNavigationAndEditAccountsWithoutGrantingOldCleanupRights() {
        var world = new World(); var original = site(world); temporary(original, world);
        assertNull(original.charge()); assertTrue(original.navigation().takeReplan());
        assertNull(original.checkpoint(target().revision(), FEET, world));
        var restored = new BuildSite(); restored.bind("new-session", world);
        restored.restore(original.snapshot(), FEET.east(2), world);
        assertEquals(original.scope(), restored.scope());
        assertEquals(1, restored.navigation().replans());
        assertEquals(1, restored.report(world).get("edit_attempts").getAsInt());
        assertTrue(restored.protects(TARGET)); assertTrue(restored.protects(FEET.below()));
        assertTrue(restored.protects(TEMP));
        assertNotNull(restored.miningGuard(TEMP, target().revision(), true, world));
        // This particular round is not retried; changing provenance cannot reopen its budget.
        assertEquals(1, restored.report(world).get("checkpoints").getAsInt());
    }
    @Test void restoredSiteAllowsRepeatedRoundsAndSpentEditsButRejectsChangedCorridors() {
        var world = new World(); var original = site(world);
        assertNull(original.checkpoint(target().revision(), FEET, world));
        var restored = new BuildSite(); restored.bind("new-session", world);
        restored.restore(original.snapshot(), FEET, world);
        assertNull(restored.checkpoint(target().revision(), FEET, world));
        var saved = original.snapshot(); saved.addProperty("edits", 1024);
        var spent = new BuildSite(); spent.bind("spent", world);
        spent.restore(saved, FEET, world);
        assertNull(spent.charge());
        assertEquals(1025, spent.report(world).get("edit_attempts").getAsInt());
        world.unknown.add(FEET.below());
        var unknown = new BuildSite(); unknown.bind("unknown", world);
        assertThrows(IllegalArgumentException.class, () -> unknown.restore(original.snapshot(), FEET, world));
    }
    @BeforeAll static void bootstrap() { com.neko.mcbot.server.BlockActionFixtures.bootstrap(); }
    static final BlockPos FEET = new BlockPos(0,90,0), TARGET = new BlockPos(4,90,0), TEMP = new BlockPos(8,90,0);
    static final List<BlockPos> ROUTE = List.of(FEET, FEET.east());
    static BuildTarget target() {
        return BuildTarget.parse(JsonParser.parseString("""
                {"anchor":{"x":4,"y":90,"z":0},"cells":[{"x":0,"y":0,"z":0,"block":"stone"}]}
                """).getAsJsonObject());
    }
    static class World implements BuildSite.Access {
        final Map<BlockPos,BlockState> states = new HashMap<>();
        final Map<BlockPos,CellWitness> witnesses = new HashMap<>();
        final Set<BlockPos> unknown = new HashSet<>(), occupied = new HashSet<>();
        Object chunk = new Object();
        boolean material = true, watch = true;
        boolean canMine = true;
        @Override public boolean canMine(BlockState actual) { return canMine; }
        boolean passageComplete = true;
        final Set<BlockPos> passage = new HashSet<>();
        @Override public boolean passage(BlockPos pos) { return passage.contains(pos); }
        @Override public boolean passageComplete() { return passageComplete; }
        @Override public BlockState state(BlockPos p) {
            return unknown.contains(p) ? null : states.getOrDefault(p,
                    p.getY()==89 ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState());
        }
        @Override public boolean corridor(BlockPos p) {
            return geometry().standing(p.getX(), p.getY(), p.getZ(),
                    com.neko.mcbot.path.TerrainEdits.EMPTY) != null;
        }
        com.neko.mcbot.server.StandingGeometry geometry() {
            return new com.neko.mcbot.server.StandingGeometry(new com.neko.mcbot.server.StandingGeometry.Access() {
                @Override public boolean known(BlockPos pos) { return state(pos) != null; }
                @Override public List<net.minecraft.world.phys.AABB> boxes(BlockPos pos) {
                    return com.neko.mcbot.server.StandingGeometry.collision(state(pos),
                            net.minecraft.world.level.EmptyBlockGetter.INSTANCE, pos);
                }
            }, .6, 1.8, .6);
        }
        @Override public boolean corridorStep(BlockPos from, BlockPos to) {
            return BuildSite.Access.super.corridorStep(from, to)
                    && com.neko.mcbot.server.BuildSiteAccess.walkable(geometry(), from, to);
        }
        @Override public List<BlockPos> corridorBody(BlockPos feet) {
            return com.neko.mcbot.server.BuildSiteAccess.corridorBody(geometry(),feet,1.8);
        }
        @Override public boolean facility(BlockPos p) {
            var state=state(p);
            return state==null || WorksiteProtection.facility(state,state.hasBlockEntity());
        }
        @Override public boolean occupied(BlockPos p) { return occupied.contains(p); }
        @Override public boolean material(BuildTarget.Cell c) { return material || c.authored().isAir(); }
        @Override public Object chunk(BlockPos p) { return chunk; }
        @Override public CellWitness watch(BlockPos p) {
            return watch ? witnesses.computeIfAbsent(p,key -> new CellWitness()) : null;
        }
        void edit(BlockPos p,BlockState next) {
            states.put(p,next);
            if(witnesses.containsKey(p)) witnesses.get(p).invalidate();
        }
    }
    static BuildSite site(World w) {
        var site = new BuildSite(); site.bind("task",w);
        assertNull(site.begin(target(),ROUTE,FEET,w));
        return site;
    }
    static void temporary(BuildSite site,World w) {
        assertNull(site.temporaryGuard(TEMP,"support",w));
        var before=w.state(TEMP); w.states.put(TEMP,Blocks.COBBLESTONE.defaultBlockState());
        site.placedTemporary(TEMP,before,w.state(TEMP),"support",w);
    }
    @Test void fullTargetProtectionDoesNotSpendOrIncreaseTheOlder64CellSet() {
        var w=new World(); var site=new BuildSite(); site.bind("task",w);
        var target=BuildTarget.parse(JsonParser.parseString("""
                {"anchor":{"x":4,"y":90,"z":0},"fill":{"from":{"x":0,"y":0,"z":0},
                "to":{"x":7,"y":3,"z":3},"block":"stone"}}
                """).getAsJsonObject());
        assertNull(site.begin(target,ROUTE,FEET,w));
        assertEquals(128,site.report(w).get("protected_target_cells").getAsInt());
        for(var cell:target.cells()) { assertTrue(site.protects(cell.pos())); assertTrue(site.protects(cell.pos().below())); }
        assertEquals(64,com.neko.mcbot.path.WorkTargets.LIMIT);
        assertTrue(site.protects(FEET.above())); assertTrue(site.protects(FEET.below()));
        assertFalse(site.protects(TEMP.east(20)));
    }
    @Test void accessRouteRejectsGapsDiagonalsUnknownFloorsAndSealingTargets() {
        var w=new World();
        for(var route:List.of(List.of(FEET),List.of(FEET,FEET),List.of(FEET,FEET.east(2)),
                List.of(FEET,FEET.east().south()),List.of(FEET,FEET.above()))) {
            var site=new BuildSite(); site.bind("task",w);
            assertNotNull(site.begin(target(),route,FEET,w)); assertFalse(site.active());
        }
        w.unknown.add(FEET.east().below());
        var site=new BuildSite(); site.bind("task",w);
        assertNotNull(site.begin(target(),ROUTE,FEET,w));
        w.unknown.clear(); w.states.put(FEET.east().below(),Blocks.OAK_SLAB.defaultBlockState());
        assertNotNull(site.begin(target(),ROUTE,FEET,w));
        w.states.clear();
        assertNotNull(site.begin(target(),List.of(TARGET,TARGET.east()),TARGET,w));
    }
    @Test void beginIsIdempotentAndCannotChangeRevisionRouteScopeOrResetAccounts() {
        var w=new World(); var site=site(w); var budget=site.navigation();
        assertNull(site.charge()); assertNull(site.checkpoint(target().revision(),FEET,w));
        assertNull(site.begin(target(),ROUTE,FEET.east(),w));
        assertNull(site.begin(target(),List.of(),FEET.east(),w));
        assertSame(budget,site.navigation()); assertEquals(1,site.report(w).get("edit_attempts").getAsInt());
        assertNotNull(site.begin(target(),List.of(FEET,FEET.north()),FEET,w));
        var other=new BuildTarget(target().anchor(),target().rotation(),target().cells(),"different");
        assertNotNull(site.begin(other,ROUTE,FEET,w));
        assertNull(site.checkpoint(target().revision(),FEET,w));
        assertFalse(site.report(w).get("stopped").getAsBoolean());
    }
    @Test void movementAndTargetFactsDetectRoundTripsEvenWhenCountersChange() {
        var w=new World(); var site=site(w);
        assertNull(site.checkpoint(target().revision(),FEET,w));
        assertNull(site.checkpoint(target().revision(),FEET.east(),w));
        assertNull(site.charge());
        assertNull(site.checkpoint(target().revision(),FEET,w));
    }
    @Test void newActualTargetStateAndTemporaryWorkAreEvidenceButQueriesAreNot() {
        var w=new World(); var site=site(w);
        assertNull(site.checkpoint(target().revision(),FEET,w));
        temporary(site,w);
        assertNull(site.checkpoint(target().revision(),FEET,w));
        w.states.put(TARGET,Blocks.STONE.defaultBlockState());
        assertNull(site.checkpoint(target().revision(),FEET,w));
        site.report(w);
        assertNull(site.checkpoint(target().revision(),FEET,w));
    }
    @Test void declaredRouteIsRecheckedBeforeEveryEditAndItsGuaranteeIsLimited() {
        var w=new World(); var site=site(w);
        assertFalse(site.report(w).get("return_guaranteed").getAsBoolean());
        w.states.put(FEET.east().above(),Blocks.STONE.defaultBlockState());
        assertNotNull(site.live(w));
        assertNotNull(site.placementGuard(TARGET,Blocks.STONE.defaultBlockState(),w));
        assertNotNull(site.miningGuard(TARGET,target().revision(),false,w));
    }
    @Test void clearingAndReopeningRequireTheOriginalRevisionRealMaterialAndNoAttachedDependants() {
        var w=new World(); var site=site(w);
        w.states.put(TARGET,Blocks.DIRT.defaultBlockState());
        assertNotNull(site.miningGuard(TARGET,null,false,w));
        assertNull(site.miningGuard(TARGET,target().revision(),false,w));
        w.material=false; assertNotNull(site.miningGuard(TARGET,target().revision(),false,w));
        w.material=true; w.states.put(TARGET,Blocks.STONE.defaultBlockState());
        assertNull(site.miningGuard(TARGET,target().revision(),false,w),
                "an ordinary finished cube may be temporarily reopened under the same drawing");
        assertNotNull(site.miningGuard(TARGET,null,false,w));
        w.states.put(TARGET,Blocks.DIRT.defaultBlockState()); w.states.put(TARGET.above(),Blocks.STONE.defaultBlockState());
        assertNull(site.miningGuard(TARGET,target().revision(),false,w),
                "a static full cube above is not a falling or attached dependency");
        w.states.put(TARGET.above(),Blocks.SAND.defaultBlockState());
        assertNotNull(site.miningGuard(TARGET,target().revision(),false,w));
        w.states.remove(TARGET.above()); w.states.put(TARGET.east(),Blocks.TORCH.defaultBlockState());
        assertNotNull(site.miningGuard(TARGET,target().revision(),false,w));
    }
    @Test void existingFacilitiesUnknownNeighborsBodySupportAndStateConflictsAreNeverCleared() {
        var w=new World(); var site=site(w);
        w.states.put(TARGET,Blocks.CRAFTING_TABLE.defaultBlockState());
        assertNotNull(site.miningGuard(TARGET,target().revision(),false,w));
        w.states.put(TARGET,Blocks.DIRT.defaultBlockState()); w.unknown.add(TARGET.east());
        assertNotNull(site.miningGuard(TARGET,target().revision(),false,w));
        w.unknown.clear(); w.occupied.add(TARGET);
        assertNotNull(site.miningGuard(TARGET,target().revision(),false,w));
        assertNotNull(site.miningGuard(TARGET,"a".repeat(64),false,w));
        w.occupied.clear(); w.states.put(TARGET,Blocks.STONE.defaultBlockState());
        assertNotNull(site.placementGuard(TARGET,Blocks.STONE.defaultBlockState(),w));
        w.states.remove(TARGET);
        assertNotNull(site.placementGuard(TARGET,Blocks.DIRT.defaultBlockState(),w));
        assertNull(site.placementGuard(TARGET,Blocks.STONE.defaultBlockState(),w));
    }
    @Test void temporaryRecordsActualStatePurposeAndDoesNotAuthorizeOrdinaryMining() {
        var w=new World(); var site=site(w); temporary(site,w);
        assertTrue(site.protects(TEMP));
        assertNotNull(site.miningGuard(TEMP,null,false,w));
        assertNull(site.miningGuard(TEMP,target().revision(),true,w));
        var row=site.report(w).getAsJsonArray("temporary").get(0).getAsJsonObject();
        assertEquals("support",row.get("purpose").getAsString()); assertTrue(row.get("provenance_intact").getAsBoolean());
        assertTrue(row.get("cleanup_allowed").getAsBoolean());
    }
    @Test void sameBlockReplacementChunkReloadMissingHookAndBodyOccupancyRefuseCleanup() {
        for(int mode=0;mode<4;mode++) {
            var w=new World(); var site=site(w);
            if(mode==2) w.watch=false;
            temporary(site,w);
            switch(mode) {
                case 0 -> { w.edit(TEMP,Blocks.AIR.defaultBlockState()); w.edit(TEMP,Blocks.COBBLESTONE.defaultBlockState()); }
                case 1 -> w.chunk=new Object();
                case 3 -> w.occupied.add(TEMP);
            }
            assertNotNull(site.miningGuard(TEMP,target().revision(),true,w),"mode "+mode);
        }
    }
    @Test void supportOrNearbyFacilityUnknownAttachmentKeepsTemporaryWork() {
        var w=new World(); var site=site(w); temporary(site,w);
        for(var state:List.of(Blocks.STONE.defaultBlockState(),Blocks.SAND.defaultBlockState(),Blocks.TORCH.defaultBlockState())) {
            w.states.put(TEMP.above(),state);
            assertNotNull(site.miningGuard(TEMP,target().revision(),true,w));
        }
        w.states.remove(TEMP.above()); w.states.put(TEMP.east(),Blocks.CRAFTING_TABLE.defaultBlockState());
        assertNotNull(site.miningGuard(TEMP,target().revision(),true,w));
        w.states.remove(TEMP.east()); w.unknown.add(TEMP.east());
        assertNotNull(site.miningGuard(TEMP,target().revision(),true,w));
    }
    @Test void temporaryCapDoesNotEvictOrRecycleRemovedRecords() {
        var w=new World(); var site=site(w);
        for(int i=0;i<16;i++) {
            var pos=TEMP.south(i);
            assertNull(site.temporaryGuard(pos,"support",w));
            w.states.put(pos,Blocks.STONE.defaultBlockState());
            site.placedTemporary(pos,Blocks.AIR.defaultBlockState(),w.state(pos),"support",w);
            site.removedTemporary(pos);
        }
        assertNotNull(site.temporaryGuard(TEMP.north(),"support",w));
        assertEquals(16,site.report(w).getAsJsonArray("temporary").size());
    }
    @Test void nativeEditsAndRepeatedRoundsContinuePastOldCapsAcrossRegistrationAndRestore() {
        var w=new World(); var site=site(w);
        for(int i=0;i<160;i++) {
            assertNull(site.charge());
            assertNull(site.checkpoint(target().revision(),FEET,w));
        }
        assertNull(site.begin(target(),ROUTE,FEET,w));
        var restored=new BuildSite(); restored.bind("restore",w);
        restored.restore(site.snapshot(),FEET,w);
        assertNull(restored.charge());
        assertNull(restored.checkpoint(target().revision(),FEET,w));
        assertEquals(161,restored.report(w).get("edit_attempts").getAsInt());
        w.unknown.add(FEET.below());
        assertNotNull(restored.live(w));
    }
    @Test void taskWorldAndBodyBindingDiscardAuthorityWithoutCleanupOrAutomaticResume() {
        var w=new World(); var site=site(w); temporary(site,w);
        var budget=site.navigation();
        site.bind("task",w); assertTrue(site.active()); assertSame(budget,site.navigation());
        site.bind(null,w); assertFalse(site.active()); assertNotNull(site.miningGuard(TEMP,target().revision(),true,w));
        assertEquals(Blocks.COBBLESTONE.defaultBlockState(),w.state(TEMP));
        site.bind("next",new Object()); assertFalse(site.active());
    }
    @Test void fixedScopeDoesNotMoveWithNewStancesOrAllowOutOfBoundsEdits() {
        var w=new World(); var site=new BuildSite(); site.bind("task",w);
        assertNull(site.begin(target(),ROUTE,FEET,
                new com.neko.mcbot.path.PathSpec(false,false,false,Integer.MAX_VALUE,FEET,64),w));
        assertEquals(FEET,site.scope().anchor());
        assertFalse(site.inScope(FEET.east(65)));
        assertNotNull(site.placementGuard(FEET.east(65),Blocks.STONE.defaultBlockState(),w));
        assertNotNull(site.miningGuard(FEET.east(65),null,false,w));
        assertSame(site.navigation(),site.navigation());
    }
    @Test void defaultSiteNeedsNoInventedCorridorOrDistanceCapAndRestoresItsOriginalAccounts() {
        var w=new World(); var site=new BuildSite(); site.bind("task",w);
        assertNull(site.begin(target(),List.of(),FEET,w));
        assertNull(site.scope().anchor());
        assertEquals(Integer.MAX_VALUE,site.scope().maxDigs());
        assertTrue(site.inScope(FEET.east(256)));
        assertFalse(site.report(w).has("bounds"));
        assertFalse(site.report(w).get("scope_limited").getAsBoolean());
        assertNull(site.checkpoint(target().revision(),FEET.east(256),w));
        assertNull(site.charge());
        var restored=new BuildSite(); restored.bind("resumed",w);
        restored.restore(site.snapshot(),FEET.east(256),w);
        assertEquals(site.scope(),restored.scope());
        assertEquals(1,restored.report(w).get("edit_attempts").getAsInt());
        assertEquals(0,restored.report(w).get("access_route_cells").getAsInt());
        assertFalse(restored.report(w).get("return_guaranteed").getAsBoolean());
        assertTrue(restored.protects(TARGET));
    }
    @Test void explicitCorridorsUseCollisionForGrassFlowersSlabsAndSlopesButRejectRealBlockers() {
        var w=new World();
        w.states.put(FEET,Blocks.SHORT_GRASS.defaultBlockState());
        w.states.put(FEET.east(),Blocks.DANDELION.defaultBlockState());
        var site=new BuildSite(); site.bind("task",w);
        assertNull(site.begin(target(),ROUTE,FEET,w));
        w.states.put(FEET.east(),Blocks.COBWEB.defaultBlockState());
        assertNotNull(site.live(w));
        w.states.clear();
        w.states.put(FEET.east().below(),Blocks.OAK_SLAB.defaultBlockState());
        var slope=new BuildSite(); slope.bind("slope",w);
        assertNull(slope.begin(target(),List.of(FEET,FEET.east().below()),FEET,w));
        assertTrue(slope.protects(FEET.east().above()),
                "fractional feet can occupy a third cell; its headroom must also be protected");
        w.states.put(FEET.east(),Blocks.STONE.defaultBlockState());
        assertNotNull(slope.live(w));
        w.states.clear(); w.states.put(FEET.east(),Blocks.STONE.defaultBlockState());
        var step=new BuildSite(); step.bind("step",w);
        assertNull(step.begin(target(),List.of(FEET,FEET.east().above()),FEET,w));
        w.states.put(FEET.above(2),Blocks.STONE.defaultBlockState());
        assertNotNull(step.begin(target(),List.of(FEET,FEET.east().above()),FEET,w));
    }
    @Test void plannedUnbuiltWallsAndSoftCoverDoNotPreventPreparingTheFoundation() {
        var w=new World(); var site=new BuildSite(); site.bind("task",w);
        var design=BuildTarget.parse(JsonParser.parseString("""
                {"anchor":{"x":4,"y":90,"z":0},"cells":[
                {"x":0,"y":0,"z":0,"block":"stone"},{"x":0,"y":1,"z":0,"block":"stone"}]}
                """).getAsJsonObject());
        assertNull(site.begin(design,List.of(),FEET,w));
        w.states.put(TARGET,Blocks.DIRT.defaultBlockState());
        w.states.put(TARGET.above(),Blocks.DANDELION.defaultBlockState());
        assertNull(site.miningGuard(TARGET,design.revision(),false,w));
        w.passage.add(TARGET);
        assertNull(site.miningGuard(TARGET,design.revision(),false,w),
                "a deliberate target edit can replace an old visited floor after the body moves away");
        assertNotNull(site.miningGuard(TARGET,null,false,w));
        w.occupied.add(TARGET);
        assertNotNull(site.miningGuard(TARGET,design.revision(),false,w));
        w.occupied.clear();
        w.states.put(TARGET.above(),Blocks.STONE.defaultBlockState());
        assertNull(site.miningGuard(TARGET,design.revision(),false,w),
                "native static walls do not prevent replacing the authored floor from an unoccupied stance");
        w.states.remove(TARGET.above()); w.states.put(TARGET,Blocks.AIR.defaultBlockState());
        assertNull(site.placementGuard(TARGET,Blocks.STONE.defaultBlockState(),w));
    }
    @Test void softCoverDoesNotIncludeHazardsFluidsFacilitiesOrNonreplaceableObjects() {
        for(var block:List.of(Blocks.SHORT_GRASS,Blocks.DANDELION,Blocks.POPPY))
            assertTrue(BuildSite.softClearance(block.defaultBlockState()),block.toString());
        for(var block:List.of(Blocks.AIR,Blocks.STONE,Blocks.WATER,Blocks.COBWEB,Blocks.WITHER_ROSE,
                Blocks.SWEET_BERRY_BUSH,Blocks.CRAFTING_TABLE,Blocks.CHEST,Blocks.TORCH))
            assertFalse(BuildSite.softClearance(block.defaultBlockState()),block.toString());
        assertFalse(BuildSite.softClearance(null));
    }
    @Test void clearingAnAirTargetCanRemoveGrassInsideTheBodyOrAnExplicitPassageWithoutMovingItsFloor() {
        var w=new World(); var site=new BuildSite(); site.bind("task",w);
        var design=BuildTarget.parse(JsonParser.parseString("""
                {"anchor":{"x":0,"y":90,"z":0},"cells":[
                {"x":0,"y":0,"z":0,"block":"air"}]}
                """).getAsJsonObject());
        w.states.put(FEET,Blocks.DANDELION.defaultBlockState());
        assertNull(site.begin(design,ROUTE,FEET,w));
        w.occupied.add(FEET); w.passage.add(FEET);
        assertNull(site.miningGuard(FEET,design.revision(),false,w));
        assertNotNull(site.miningGuard(FEET.below(),design.revision(),false,w));
        w.states.put(FEET,Blocks.STONE.defaultBlockState());
        assertNotNull(site.miningGuard(FEET,design.revision(),false,w));
    }
    @Test void observedPassagesRemainProtectedButOldRecordingGapsDoNotBanOrdinaryConstruction() {
        var w=new World(); var site=site(w);
        w.passage.add(TEMP);
        assertNotNull(site.placementGuard(TEMP,Blocks.STONE.defaultBlockState(),w));
        assertNull(site.temporaryGuard(TEMP,"support",w));
        assertNull(site.placementGuard(TEMP,Blocks.STONE.defaultBlockState(),w,true));
        assertNotNull(site.temporaryGuard(TEMP,"workstation",w));
        assertNotNull(site.temporaryGuard(FEET,"support",w), "the explicit corridor remains frozen");
        assertNotNull(site.miningGuard(TEMP,null,false,w));
        w.passageComplete=false;
        assertNull(site.live(w));
        assertNull(site.checkpoint(target().revision(),FEET,w));
        assertFalse(site.report(w).get("passage_observation_complete").getAsBoolean());
        assertNull(site.placementGuard(TARGET,Blocks.STONE.defaultBlockState(),w));
        assertTrue(site.allowsStanding(TARGET), "future design cells are not current collision");
        assertTrue(site.allowsStanding(TARGET.below()));
        assertTrue(site.allowsStanding(TARGET.east()));
    }

    @Test void leafyObstaclesCanBeClearedWithoutTreatingTheCanopyAsAnAttachmentOrChangingFinishedWork() {
        var w=new World(); var site=site(w);
        w.states.put(TARGET,Blocks.SPRUCE_LEAVES.defaultBlockState());
        w.states.put(TARGET.above(),Blocks.SPRUCE_LEAVES.defaultBlockState());
        w.states.put(TARGET.east(),Blocks.SPRUCE_LEAVES.defaultBlockState());
        assertNull(site.miningGuard(TARGET,target().revision(),false,w));
        assertEquals(target().revision(),site.navigationRevision(TARGET,w));
        assertFalse(site.protectsNavigation(TARGET,w));
        assertTrue(site.protects(TARGET),"the complete design stays protected for unrelated actions");
        assertNotNull(site.miningGuard(TARGET,null,false,w));
        w.states.put(TARGET,Blocks.STONE.defaultBlockState());
        assertEquals(target().revision(),site.navigationRevision(TARGET,w));
        assertFalse(site.protectsNavigation(TARGET,w));
        assertNotNull(site.miningGuard(TARGET,null,false,w));
        w.states.put(TARGET,Blocks.SPRUCE_LEAVES.defaultBlockState());
        w.states.put(TARGET.above(),Blocks.TORCH.defaultBlockState());
        assertNotNull(site.miningGuard(TARGET,target().revision(),false,w));
        assertNull(site.navigationRevision(TARGET,w));
    }
    @Test void missingHistoricalPassagesKeepTemporaryCleanupConservativeWithoutStoppingNormalEdits() {
        var w=new World(); var site=site(w); temporary(site,w);
        w.passageComplete=false;
        assertNull(site.live(w));
        assertNull(site.placementGuard(TARGET,Blocks.STONE.defaultBlockState(),w));
        assertNotNull(site.miningGuard(TEMP,target().revision(),true,w));
        var restored=new BuildSite(); restored.bind("restored",w);
        restored.restore(site.snapshot(),FEET,w);
        assertNull(restored.live(w));
        assertNotNull(restored.miningGuard(TEMP,target().revision(),true,w));
    }
    @Test void aDeclaredCorridorInsideTheWholeFootprintDoesNotProveAnExit() {
        var w=new World(); var site=new BuildSite(); site.bind("task",w);
        var enclosing=BuildTarget.parse(JsonParser.parseString("""
                {"anchor":{"x":0,"y":90,"z":0},"cells":[
                {"x":-2,"y":0,"z":-2,"block":"stone"},{"x":2,"y":0,"z":2,"block":"stone"}]}
                """).getAsJsonObject());
        assertNotNull(site.begin(enclosing,ROUTE,FEET,w));
        assertFalse(site.active());
    }
    @Test void realRestockingToolQualificationAndSupportChangesAllowReassessmentWithoutNewBudgets() {
        var w=new World(); w.material=false; w.canMine=false; var site=site(w);
        var account=site.navigation();
        assertNull(site.checkpoint(target().revision(),FEET,w));
        w.material=true;
        assertNull(site.checkpoint(target().revision(),FEET,w));
        w.canMine=true;
        assertNull(site.checkpoint(target().revision(),FEET,w));
        w.states.put(TARGET.east(),Blocks.STONE.defaultBlockState());
        assertNull(site.checkpoint(target().revision(),FEET,w));
        assertSame(account,site.navigation());
        assertEquals(4,site.report(w).get("checkpoints").getAsInt());
        w.states.remove(TARGET.east());
        assertNull(site.checkpoint(target().revision(),FEET,w),"repeat facts are diagnostic, not a dispatch ban");
    }
    @Test void equalTaskNumbersInANewServerTaskEpochCannotInheritTheOldWorksiteOrProvenance() {
        var w=new World(); var site=new BuildSite();
        var first=new String("same id");
        var next=new String("same id");
        assertEquals(first,next); assertNotSame(first,next);
        site.bind(first,w); assertNull(site.begin(target(),ROUTE,FEET,w)); temporary(site,w);
        site.bind(first,w); assertTrue(site.active());
        site.bind(next,w); assertFalse(site.active());
        assertNotNull(site.miningGuard(TEMP,target().revision(),true,w));
    }
}
