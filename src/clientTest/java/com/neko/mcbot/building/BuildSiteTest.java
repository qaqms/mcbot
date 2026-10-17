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
    @Test void restoredSiteStillDetectsRepeatedRoundsAndRejectsChangedCorridorsAndSpentEdits() {
        var world = new World(); var original = site(world);
        assertNull(original.checkpoint(target().revision(), FEET, world));
        var restored = new BuildSite(); restored.bind("new-session", world);
        restored.restore(original.snapshot(), FEET, world);
        assertTrue(restored.checkpoint(target().revision(), FEET, world).feedback().startsWith("NO_PROGRESS:"));
        var saved = original.snapshot(); saved.addProperty("edits", BuildSite.EDIT_LIMIT);
        var spent = new BuildSite(); spent.bind("spent", world);
        assertThrows(IllegalArgumentException.class, () -> spent.restore(saved, FEET, world));
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
            return BuildSite.plainCube(state(p.below())) && state(p)!=null && state(p).isAir()
                    && state(p.above())!=null && state(p.above()).isAir();
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
        assertSame(budget,site.navigation()); assertEquals(1,site.report(w).get("edit_attempts").getAsInt());
        assertNotNull(site.begin(target(),List.of(FEET,FEET.north()),FEET,w));
        var other=new BuildTarget(target().anchor(),target().rotation(),target().cells(),"different");
        assertNotNull(site.begin(other,ROUTE,FEET,w));
        assertNotNull(site.checkpoint(target().revision(),FEET,w));
        assertTrue(site.report(w).get("stopped").getAsBoolean());
    }
    @Test void movementAndTargetFactsDetectRoundTripsEvenWhenCountersChange() {
        var w=new World(); var site=site(w);
        assertNull(site.checkpoint(target().revision(),FEET,w));
        assertNull(site.checkpoint(target().revision(),FEET.east(),w));
        assertNull(site.charge());
        assertTrue(site.checkpoint(target().revision(),FEET,w).feedback().startsWith("NO_PROGRESS:"));
    }
    @Test void newActualTargetStateAndTemporaryWorkAreEvidenceButQueriesAreNot() {
        var w=new World(); var site=site(w);
        assertNull(site.checkpoint(target().revision(),FEET,w));
        temporary(site,w);
        assertNull(site.checkpoint(target().revision(),FEET,w));
        w.states.put(TARGET,Blocks.STONE.defaultBlockState());
        assertNull(site.checkpoint(target().revision(),FEET,w));
        site.report(w);
        assertNotNull(site.checkpoint(target().revision(),FEET,w));
    }
    @Test void declaredRouteIsRecheckedBeforeEveryEditAndItsGuaranteeIsLimited() {
        var w=new World(); var site=site(w);
        assertFalse(site.report(w).get("return_guaranteed").getAsBoolean());
        w.states.put(FEET.east().above(),Blocks.STONE.defaultBlockState());
        assertNotNull(site.live(w));
        assertNotNull(site.placementGuard(TARGET,Blocks.STONE.defaultBlockState(),w));
        assertNotNull(site.miningGuard(TARGET,target().revision(),false,w));
    }
    @Test void clearingRequiresOriginalRevisionRealMaterialAndNoFinishedCellOrDependants() {
        var w=new World(); var site=site(w);
        w.states.put(TARGET,Blocks.DIRT.defaultBlockState());
        assertNotNull(site.miningGuard(TARGET,null,false,w));
        assertNull(site.miningGuard(TARGET,target().revision(),false,w));
        w.material=false; assertNotNull(site.miningGuard(TARGET,target().revision(),false,w));
        w.material=true; w.states.put(TARGET,Blocks.STONE.defaultBlockState());
        assertNotNull(site.miningGuard(TARGET,target().revision(),false,w));
        w.states.put(TARGET,Blocks.DIRT.defaultBlockState()); w.states.put(TARGET.above(),Blocks.STONE.defaultBlockState());
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
    @Test void nativeEditAndRoundCapsRemainFiniteAcrossRegistrationAndPauses() {
        var w=new World(); var site=site(w);
        for(int i=0;i<128;i++) assertNull(site.charge());
        assertNotNull(site.charge());
        assertNotNull(site.begin(target(),ROUTE,FEET,w));
        var second=site(new World()); var secondWorld=new World();
        for(int i=0;i<16;i++) assertNull(second.checkpoint(target().revision(),FEET.east(i),secondWorld));
        assertNotNull(second.checkpoint(target().revision(),FEET.east(16),secondWorld));
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
        var w=new World(); var site=site(w);
        assertEquals(FEET,site.scope().anchor());
        assertFalse(site.inScope(FEET.east(65)));
        assertNotNull(site.placementGuard(FEET.east(65),Blocks.STONE.defaultBlockState(),w));
        assertNotNull(site.miningGuard(FEET.east(65),null,false,w));
        assertSame(site.navigation(),site.navigation());
    }
    @Test void actualPassedCellsCannotBeSealedOrReclaimedAndRecordingGapsStopFurtherWork() {
        var w=new World(); var site=site(w);
        w.passage.add(TEMP);
        assertNotNull(site.placementGuard(TEMP,Blocks.STONE.defaultBlockState(),w));
        assertNotNull(site.temporaryGuard(TEMP,"support",w));
        assertNotNull(site.miningGuard(TEMP,null,false,w));
        w.passageComplete=false;
        assertNotNull(site.live(w));
        assertNotNull(site.checkpoint(target().revision(),FEET,w));
        assertFalse(site.allowsStanding(TARGET));
        assertFalse(site.allowsStanding(TARGET.below()));
        assertTrue(site.allowsStanding(TARGET.east()));
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
        assertNotNull(site.checkpoint(target().revision(),FEET,w),"returning to an earlier actual condition remains a cycle");
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
