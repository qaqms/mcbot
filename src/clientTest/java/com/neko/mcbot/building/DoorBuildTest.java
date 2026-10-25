package com.neko.mcbot.building;

import com.google.gson.*;
import com.neko.mcbot.server.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import com.neko.mcbot.task.TickTask;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class DoorBuildTest {
    @BeforeAll static void boot() { BlockActionFixtures.bootstrap(); }
    @TempDir Path root;
    static final BlockPos LOWER = new BlockPos(4,90,0), UPPER = LOWER.above();

    static BuildTarget target() {
        return BuildTarget.parse(JsonParser.parseString("""
                {"anchor":{"x":4,"y":90,"z":0},"cells":[
                {"x":0,"y":0,"z":0,"block":"oak_door","properties":[
                  {"name":"half","value":"lower"},{"name":"facing","value":"north"},{"name":"hinge","value":"left"}]},
                {"x":0,"y":1,"z":0,"block":"oak_door","properties":[
                  {"name":"half","value":"upper"},{"name":"facing","value":"north"},{"name":"hinge","value":"left"}]}]}
                """).getAsJsonObject());
    }

    static final class World extends BuildSurveyTest.World implements BuildWork.Access {
        BlockPos primary = LOWER;
        int floorY = 89;
        int actions, consumed = 1;
        boolean valid = true, throwAfter, lowerOnly, wrongUpper;
        Runnable afterAction = () -> { };
        Runnable beforeReserve = () -> { };
        BuildSite site;
        BuildRecords records;
        Path recordRoot;
        final BuildSiteTest.World evidence = new BuildSiteTest.World() {
            @Override public BlockState state(BlockPos p) {
                return p.getY()==floorY ? Blocks.STONE.defaultBlockState() : World.this.state(p);
            }
        };
        final Map<BlockPos,ServerTool.Result> denials = new HashMap<>();
        World() { inventory.setItem(2,new ItemStack(Items.OAK_DOOR,4)); }
        @Override public BuildSurvey.Access survey() { return this; }
        @Override public ServerTool.Result valid() {
            return !valid ? new ServerTool.Result(false,"DENIED:changed body",null)
                    : site==null ? null : site.live(evidence);
        }
        @Override public boolean recordingKnown() { return records==null || records.healthy(); }
        @Override public boolean reserve(BuildTarget.Cell cell) { beforeReserve.run(); return true; }
        @Override public ServerTool.Result guard(BlockPos pos) {
            if (denials.containsKey(pos)) return denials.get(pos);
            if (unknown.contains(pos)) return new ServerTool.Result(false,"TARGET_LOST:unloaded",null);
            return null;
        }
        @Override public boolean clearable(BuildTarget.Cell cell) {
            return site!=null && site.miningGuard(cell.pos(),site.target().revision(),false,evidence)==null;
        }
        @Override public ServerTool.Result beforePlace() {
            if (site==null) return null;
            var failure=site.placementGuard(primary,proposed,evidence);
            if (failure==null) failure=site.placementGuard(primary.above(),BuildObjects.upperState(proposed),evidence);
            return failure!=null ? failure : site.charge();
        }
        @Override public InteractionResult place(BlockPos pos,ItemStack stack,Direction face) {
            actions++; stack.shrink(consumed); edit(pos,proposed);
            if (!lowerOnly) edit(pos.above(),wrongUpper ? Blocks.DIRT.defaultBlockState() : BuildObjects.upperState(proposed));
            afterAction.run();
            if (throwAfter) throw new IllegalStateException("native partial");
            return InteractionResult.SUCCESS;
        }
        void edit(BlockPos pos,BlockState state) {
            blocks.put(pos,state);
            var witness=evidence.witnesses.get(pos); if (witness!=null) witness.invalidate();
        }
        @Override public void recordedObject(BlockPos primary,Map<BlockPos,BlockState> before,
                                              Map<BlockPos,BlockState> after,int consumed) {
            if (site!=null) site.recordObjectPlacement(primary,before,after,consumed,evidence);
        }
    }
    static ServerTool.Result finish(BuildWork work) {
        for (int i=0;i<30;i++) {
            if (work.tickSafe(null) instanceof TickTask.Progress.Done done) return done.result();
        }
        throw new AssertionError("bounded work did not stop");
    }
    BuildRecords records(World w) {
        w.recordRoot=root.resolve(UUID.randomUUID().toString());
        var records=new BuildRecords(w.recordRoot); records.load();
        w.records=records;
        var input=target().input(); input.remove("anchor"); input.remove("rotation");
        records.createDesign(OWNER,"door",input);
        var instance=records.createInstance(OWNER,BUILDER,"world","door",1,LOWER,0);
        w.site=new BuildSite(); w.site.bind(new Object(),w);
        assertNull(w.site.begin(instance.target(),BuildSiteTest.ROUTE,BuildSiteTest.FEET,w.evidence));
        w.site.attach(records,instance); return records;
    }
    static final UUID OWNER=UUID.randomUUID(), BUILDER=UUID.randomUUID();

    @Test void bothDoorCellsStayInTheTargetButOnlyOneRealItemIsAllocated() {
        var target = target();
        var world = new BuildSurveyTest.World();
        world.inventory.setItem(2, new ItemStack(Items.OAK_DOOR, 1));
        var result = world.query(target);
        assertEquals(2, result.get("total").getAsInt());
        assertEquals(0, result.getAsJsonObject("counts").get("unsupported").getAsInt());
        var bill = result.getAsJsonArray("materials").get(0).getAsJsonObject();
        assertEquals(1, bill.get("design_required").getAsInt());
        assertEquals(1, bill.get("required").getAsInt());
        assertEquals(1, bill.get("allocated").getAsInt());
        assertEquals(1, result.getAsJsonObject("counts").get("ready").getAsInt());
        assertFalse(result.get("goal_satisfied").getAsBoolean());
    }

    @Test void wholeTargetVerificationRequiresBothHalvesEvenWhenOneMatches() {
        var w=new World(); var target=target();
        w.blocks.put(LOWER,target.cells().getFirst().desired());
        var report=w.query(target);
        assertFalse(report.get("goal_satisfied").getAsBoolean());
        assertEquals(1,report.getAsJsonArray("materials").get(0).getAsJsonObject().get("required").getAsInt());
        assertEquals(0,report.getAsJsonObject("counts").get("ready").getAsInt());
        w.blocks.put(UPPER,target.cells().getLast().desired());
        assertTrue(w.query(target).get("goal_satisfied").getAsBoolean());
        assertEquals(0,w.query(target).getAsJsonArray("materials").get(0).getAsJsonObject().get("required").getAsInt());
    }
    @Test void missingWrongAndConflictingPartnerTargetsRemainUnsupportedRatherThanDroppingCells() {
        for (String kind:List.of("missing","air","block","half","facing","hinge")) {
            var input=target().input(); var cells=input.getAsJsonArray("cells");
            var upper=cells.get(1).getAsJsonObject();
            switch (kind) {
                case "missing" -> cells.remove(1);
                case "air" -> { upper.addProperty("block","air"); upper.remove("properties"); }
                case "block" -> upper.addProperty("block","iron_door");
                case "half","facing","hinge" -> {
                    for (var property:upper.getAsJsonArray("properties")) {
                        var pair=property.getAsJsonObject();
                        if (pair.get("name").getAsString().equals(kind))
                            pair.addProperty("value",kind.equals("half") ? "lower" : kind.equals("facing") ? "east" : "right");
                    }
                }
            }
            var bad=BuildTarget.parse(input); var w=new World(); var report=w.query(bad);
            assertFalse(report.get("goal_satisfied").getAsBoolean(),kind);
            assertTrue(report.getAsJsonObject("counts").get("unsupported").getAsInt()>0,kind);
            assertEquals(0,report.getAsJsonObject("counts").get("ready").getAsInt(),kind);
            var site=new BuildSite(); site.bind(new Object(),w);
            assertNotNull(site.begin(bad,BuildSiteTest.ROUTE,BuildSiteTest.FEET,w.evidence),kind);
        }
    }
    @Test void bothCellsAreRotatedAndSerializedWithoutChangingAuthoredStateRequirements() {
        for (int rotation:List.of(0,90,180,270)) {
            var input=target().input(); input.addProperty("rotation",rotation);
            var rotated=BuildTarget.parse(input);
            assertEquals(rotated.revision(),BuildTarget.parse(rotated.input()).revision());
            var lower=rotated.cells().getFirst(); var upper=rotated.cells().getLast();
            assertEquals(lower.pos().above(),upper.pos());
            assertEquals("",BuildObjects.problem(rotated,lower));
            assertEquals(lower.desired().getValue(DoorBlock.FACING),upper.desired().getValue(DoorBlock.FACING));
        }
    }
    @Test void onlyThePrimaryCellIssuesOneNativeActionAndBothAreIndependentlyVerified() {
        var w=new World(); var result=finish(new BuildWork(target(),w,0,12,()->0));
        assertTrue(result.ok(),result.feedback());
        assertEquals(1,w.actions); assertEquals(3,w.inventory.getItem(2).getCount());
        assertEquals(1,result.data().get("action_attempts").getAsInt());
        assertTrue(result.data().get("goal_satisfied").getAsBoolean());
        var effect=result.data().getAsJsonArray("placement_results").get(0).getAsJsonObject().getAsJsonObject("effect");
        assertEquals(2,effect.get("affected_count").getAsInt());
        assertEquals(2,effect.get("object_cells").getAsInt());
        assertTrue(effect.get("object_satisfied").getAsBoolean());
        assertTrue(finish(new BuildWork(target(),w,0,12,()->0)).ok());
        assertEquals(1,w.actions);
    }
    @Test void upperCellDamageAtSettlementCannotBeMistakenForFinishedObject() {
        var w=new World(); var work=new BuildWork(target(),w,0,1,()->0);
        work.tickSafe(null); w.edit(UPPER,Blocks.AIR.defaultBlockState());
        var result=finish(work);
        assertTrue(result.ok()); assertFalse(result.data().get("goal_satisfied").getAsBoolean());
        assertEquals(1,w.actions);
    }
    @Test void incompleteWrongAndThrowingNativeCallbacksRetainEffectsAndNeverTryTheUpperOrAnotherFace() {
        for (String kind:List.of("missing","wrong","throw","free")) {
            var w=new World(); var records=records(w); var instance=w.site.instance();
            switch (kind) {
                case "missing" -> w.lowerOnly=true;
                case "wrong" -> w.wrongUpper=true;
                case "throw" -> w.throwAfter=true;
                default -> w.consumed=0;
            }
            var result=finish(new BuildWork(target(),w,0,12,()->0));
            assertFalse(result.ok(),kind);
            assertEquals(kind.equals("throw") || kind.equals("free"),
                    result.data().get("goal_satisfied").getAsBoolean(),kind);
            assertEquals(1,w.actions,kind);
            var report=records.queryInstance(instance,w.evidence,0);
            assertEquals(kind.equals("missing") ? 1 : 2,report.get("action_count").getAsInt(),kind);
            assertEquals(kind.equals("free") ? 0 : kind.equals("wrong") || kind.equals("missing") ? 1 : 2,
                    report.get("record_count").getAsInt(),kind);
        }
    }
    @Test void eitherPartsScopeProtectionUnknownOrOccupancyIsRecheckedAfterSelection() {
        for (String kind:List.of("scope","protected","unknown","occupied","permission")) {
            var w=new World();
            w.beforeReserve=()-> {
                switch (kind) {
                    case "scope" -> w.outside.add(UPPER);
                    case "protected" -> w.protectedCells.add(UPPER);
                    case "unknown" -> w.unknown.add(UPPER);
                    case "occupied" -> w.denials.put(UPPER,new ServerTool.Result(false,"TARGET_BLOCKED:entity",null));
                    default -> w.valid=false;
                }
            };
            assertFalse(finish(new BuildWork(target(),w,0,12,()->0)).ok(),kind);
            assertEquals(0,w.actions,kind);
        }
    }
    @Test void singlePlacementAlsoGuardsTheUpperCellAndNeverOverwritesReplaceablePlants() {
        for (String kind:List.of("protected","unknown","plant","stone")) {
            var w=new World();
            switch (kind) {
                case "protected" -> w.denials.put(UPPER,new ServerTool.Result(false,"DENIED:protected upper",null));
                case "unknown" -> w.unknown.add(UPPER);
                default -> w.blocks.put(UPPER,kind.equals("plant") ? Blocks.SHORT_GRASS.defaultBlockState() : Blocks.STONE.defaultBlockState());
            }
            assertFalse(BlockPlacement.place(w,LOWER,2,Direction.UP).ok(),kind);
            assertEquals(0,w.actions,kind); assertEquals(4,w.inventory.getItem(2).getCount());
        }
    }
    @Test void secondaryHalfAndExplicitNativeStateMismatchNeverIssueAnAction() {
        var w=new World(); var t=target();
        assertFalse(BlockPlacement.placeDesired(w,UPPER,2,Direction.UP,t,t.cells().getLast()).ok());
        w.overrideProposed=Blocks.OAK_DOOR.defaultBlockState().setValue(DoorBlock.HINGE,
                net.minecraft.world.level.block.state.properties.DoorHingeSide.RIGHT);
        assertFalse(BlockPlacement.placeDesired(w,LOWER,2,Direction.UP,t,t.cells().getFirst()).ok());
        assertEquals(0,w.actions);
    }
    @Test void cancellationAfterNativeActionPersistsBothCellsButDoesNotResumeOrClaimFinalVerification() {
        var w=new World(); var records=records(w);
        var work=new BuildWork(target(),w,0,12,()->0);
        work.tickSafe(null); work.onAbort();
        var result=work.interruptedResult(new ServerTool.Result(false,"CANCELLED:owner",null));
        assertEquals(1,w.actions); assertEquals(2,records.queryInstance(w.site.instance(),w.evidence,0).get("record_count").getAsInt());
        assertFalse(result.data().get("final_verified").getAsBoolean());
        assertFalse(finish(work).data().get("goal_satisfied").getAsBoolean()); assertEquals(1,w.actions);
    }
    @Test void persistentBothCellSourcesCountConsumptionOnlyOnceAndReloadCannotRestoreRemovalRights() throws Exception {
        var w=new World(); var records=records(w); var instance=w.site.instance();
        assertTrue(finish(new BuildWork(target(),w,0,12,()->0)).ok());
        assertTrue(records.mayChange(instance,LOWER,w.evidence)); assertTrue(records.mayChange(instance,UPPER,w.evidence));
        assertTrue(records.protects("world",LOWER.below()));
        var stored=JsonParser.parseString(Files.readString(w.recordRoot.resolve("mcbot/buildings.json"))).getAsJsonObject();
        var events=stored.getAsJsonArray("instances").get(0).getAsJsonObject().getAsJsonArray("actions");
        assertEquals(1,events.get(0).getAsJsonObject().get("consumed").getAsInt()
                + events.get(1).getAsJsonObject().get("consumed").getAsInt());
        var restored=new BuildRecords(w.recordRoot); restored.load(); assertTrue(restored.healthy());
        var copy=restored.instance(OWNER,BUILDER,"world",instance.id(),1);
        assertEquals(2,restored.queryInstance(copy,w.evidence,0).get("conflicts").getAsInt());
        assertFalse(restored.mayChange(copy,UPPER,w.evidence));
    }
    void retire(BuildRecords records,World w) {
        var edits=JsonParser.parseString("""
                [{"x":0,"y":0,"z":0,"remove":true},{"x":0,"y":1,"z":0,"remove":true},
                 {"x":4,"y":0,"z":0,"block":"glass"}]
                """).getAsJsonArray();
        records.patchDesign(OWNER,"door",1,edits); records.reviseInstance(w.site.instance(),2);
        var instance=w.site.instance(); w.site.bind(new Object(),w);
        assertNull(w.site.begin(instance.target(),BuildSiteTest.ROUTE,BuildSiteTest.FEET,w.evidence));
        w.site.attach(records,instance);
    }
    @Test void retiredDoorRemovesAsOneBoundedActionWithTwoAirRequirementsAndBothActualEvents() {
        var w=new World(); var records=records(w); finish(new BuildWork(target(),w,0,12,()->0));
        retire(records,w); var revision=w.site.target().revision();
        assertNull(w.site.miningGuard(LOWER,revision,false,w.evidence));
        assertNull(w.site.miningGuard(UPPER,revision,false,w.evidence));
        var before=Map.of(LOWER,w.state(LOWER),UPPER,w.state(UPPER));
        w.edit(LOWER,Blocks.AIR.defaultBlockState()); w.edit(UPPER,Blocks.AIR.defaultBlockState());
        w.site.recordObjectRemoval(before,Map.of(LOWER,w.state(LOWER),UPPER,w.state(UPPER)));
        assertEquals(4,records.queryInstance(w.site.instance(),w.evidence,0).get("action_count").getAsInt());
        assertEquals(2,w.site.target().cells().stream().filter(c -> c.authored().isAir()).count());
    }
    @Test void eitherHalfReplacementUnknownChunkReloadPassageAndDependenciesBlockWholeDoorRemoval() {
        for (String kind:List.of("replace","unknown","reload","passage","occupied","support","tool")) {
            var w=new World(); var records=records(w); finish(new BuildWork(target(),w,0,12,()->0));
            retire(records,w);
            switch (kind) {
                case "replace" -> { w.edit(UPPER,Blocks.AIR.defaultBlockState()); w.edit(UPPER,BuildObjects.upperState(Blocks.OAK_DOOR.defaultBlockState())); }
                case "unknown" -> w.unknown.add(UPPER);
                case "reload" -> w.evidence.chunk=new Object();
                case "passage" -> w.evidence.passage.add(UPPER);
                case "occupied" -> w.evidence.occupied.add(UPPER);
                case "support" -> w.edit(UPPER.above(),Blocks.STONE.defaultBlockState());
                default -> w.evidence.canMine=false;
            }
            assertNotNull(w.site.miningGuard(LOWER,w.site.target().revision(),false,w.evidence),kind);
            assertEquals(2,records.queryInstance(w.site.instance(),w.evidence,0).get("action_count").getAsInt(),kind);
        }
    }
    @Test void absentSecondaryDoesNotPreventExplicitRemovalOfTheRemainingOwnedHalf() {
        var w=new World(); var records=records(w); finish(new BuildWork(target(),w,0,12,()->0));
        w.edit(UPPER,Blocks.AIR.defaultBlockState());
        assertEquals("needs_clear",w.query(target()).getAsJsonArray("cells").get(0).getAsJsonObject().get("state").getAsString());
        assertNull(w.site.miningGuard(LOWER,w.site.target().revision(),false,w.evidence));
        assertEquals(2,records.queryInstance(w.site.instance(),w.evidence,0).get("record_count").getAsInt());
    }
    @Test void alreadyPresentForeignDoorIsNeverAdoptedAndSatisfiedPairIsNeverDismantled() {
        var w=new World(); var records=records(w);
        w.edit(LOWER,target().cells().getFirst().desired()); w.edit(UPPER,target().cells().getLast().desired());
        assertTrue(finish(new BuildWork(target(),w,0,12,()->0)).ok()); assertEquals(0,w.actions);
        assertEquals(0,records.queryInstance(w.site.instance(),w.evidence,0).get("record_count").getAsInt());
        assertNotNull(w.site.miningGuard(LOWER,w.site.target().revision(),false,w.evidence));
    }
    @Test void doorPairCrossingDetailsPagesStillConsumesOnceAndNeverPlacesSecondaryPage() {
        var input=target().input(); var cells=input.getAsJsonArray("cells");
        for (int x=1;x<=15;x++) {
            var cell=BuildTarget.coordinates(new BlockPos(x,0,0)); cell.addProperty("block","stone"); cells.add(cell);
        }
        var t=BuildTarget.parse(input); var w=new World();
        var result=finish(new BuildWork(t,w,0,1,()->0)); assertTrue(result.ok()); assertEquals(1,w.actions);
        assertFalse(result.data().get("goal_satisfied").getAsBoolean());
        var upperPage=finish(new BuildWork(t,w,16,1,()->0));
        assertFalse(upperPage.data().get("goal_satisfied").getAsBoolean()); assertEquals(1,w.actions);
    }
    @Test void sourceHistoryNeedsTwoFreeEventsEvenThoughTheObjectUsesOneItemAndOneEditAttempt() {
        var w=new World(); var records=records(w); var instance=w.site.instance();
        for (int i=0;i<127;i++) {
            var before=w.state(LOWER); w.edit(LOWER,target().cells().getFirst().desired());
            records.placed(instance,LOWER,before,w.state(LOWER),1,w.evidence);
            before=w.state(LOWER); w.edit(LOWER,Blocks.AIR.defaultBlockState());
            records.removed(instance,LOWER,before,w.state(LOWER));
        }
        // A real partial change without a consumed item uses one more history entry, without adopting a cell.
        w.edit(UPPER,Blocks.DIRT.defaultBlockState());
        records.placed(instance,UPPER,Blocks.AIR.defaultBlockState(),w.state(UPPER),0,w.evidence);
        w.edit(UPPER,Blocks.AIR.defaultBlockState());
        assertTrue(records.hasCapacity(instance,LOWER));
        assertFalse(records.hasCapacity(instance,List.of(LOWER,UPPER)));
        var result=BlockPlacement.placeDesired(w,LOWER,2,Direction.UP,target(),target().cells().getFirst());
        assertFalse(result.ok()); assertEquals(0,w.actions);
        assertEquals(0,w.site.report(w.evidence).get("edit_attempts").getAsInt());
    }
    @Test void recordWriteFailureKeepsBothActualCellsAndReportsUnavailableBeforeAnotherAction() throws Exception {
        var w=new World(); var records=records(w);
        Path folder=w.recordRoot.resolve("mcbot");
        Files.delete(folder.resolve("buildings.json")); Files.delete(folder); Files.writeString(folder,"blocked");
        var result=finish(new BuildWork(target(),w,0,1,()->0));
        assertEquals(1,w.actions); assertFalse(records.healthy());
        assertFalse(w.state(LOWER).isAir()); assertFalse(w.state(UPPER).isAir());
        assertNotNull(w.site.live(w.evidence));
        assertFalse(result.ok());
        assertFalse(result.data().getAsJsonArray("placement_results").get(0).getAsJsonObject()
                .getAsJsonObject("effect").get("recording_known").getAsBoolean());
    }
    @Test void defaultComponentsAreRequiredAndSecondaryPagesCannotObtainMaterialForFree() {
        var w=new World();
        w.inventory.getItem(2).set(net.minecraft.core.component.DataComponents.CUSTOM_NAME,
                net.minecraft.network.chat.Component.literal("keep named door"));
        assertFalse(finish(new BuildWork(target(),w,0,12,()->0)).ok());
        assertFalse(BlockPlacement.place(w,LOWER,2,Direction.UP).ok());
        assertEquals(0,w.actions); assertEquals(4,w.inventory.getItem(2).getCount());
    }
}
