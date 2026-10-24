package com.neko.mcbot.building;

import com.google.gson.*;
import com.neko.mcbot.server.*;
import com.neko.mcbot.server.tools.BuildRecordTool;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class BuildRecordsTest {
    @Test void worldIdentityIsDurableEvenBeforeAnyDesignOrBuildingExists() {
        var first = new BuildRecords(root); first.load(); first.initializeIdentity();
        var next = new BuildRecords(root); next.load();
        assertTrue(next.healthy()); assertEquals(first.worldId(), next.worldId());
        assertEquals(0, next.list(owner, builder, "world", true, 0).getAsJsonArray("entries").size());
    }
    @TempDir Path root;
    @BeforeAll static void boot() { BlockActionFixtures.bootstrap(); }
    final UUID owner=UUID.randomUUID(), builder=UUID.randomUUID();
    final BuildSiteTest.World world=new BuildSiteTest.World();
    static JsonObject json(String value) { return JsonParser.parseString(value).getAsJsonObject(); }
    static final String DEFINITION="{\"cells\":[{\"x\":0,\"y\":0,\"z\":0,\"block\":\"stone\"}]}";
    BuildRecords store() {
        var records=new BuildRecords(root); records.load();
        records.createDesign(owner,"wall",json(DEFINITION)); return records;
    }
    BuildRecords.Instance instance(BuildRecords records) {
        return records.createInstance(owner,builder,"world","wall",1,BuildSiteTest.TARGET,0);
    }
    @Test void maximumCompactDesignPersistsAndLargeInstanceRecordsContinuePastTheOld256Events() throws Exception {
        var records=new BuildRecords(root); records.load();
        var definition=BuildTargetTest.largeInput(); definition.remove("anchor");
        records.createDesign(owner,"large",definition);
        var instance=records.createInstance(owner,builder,"world","large",1,BlockPos.ZERO,90);
        String revision=instance.target().revision();
        assertEquals(16384,instance.target().cells().size());
        assertTrue(Files.size(root.resolve("mcbot/buildings.json"))<2048,"compact source is preserved on disk");
        for (var cell:instance.target().cells().subList(0,300)) {
            assertTrue(records.hasCapacity(instance,cell.pos()));
            world.edit(cell.pos(),cell.desired());
            records.placed(instance,cell.pos(),Blocks.AIR.defaultBlockState(),cell.desired(),1,world);
        }
        assertEquals(300,actions(records,instance));
        var reloaded=new BuildRecords(root); reloaded.load();
        assertTrue(reloaded.healthy());
        var restored=reloaded.instance(owner,builder,"world",instance.id(),1);
        assertEquals(revision,restored.target().revision());
        assertEquals(300,actions(reloaded,restored));
        assertTrue(reloaded.queryInstance(restored,world,288).get("conflicts").getAsInt()>0,
                "reloading data never fabricates live provenance");
        var last=reloaded.queryDesign(owner,"large",1,16368,null);
        assertEquals(16,last.getAsJsonArray("cells").size());
        assertEquals(-1,last.get("next_offset").getAsInt());
        assertTrue(restored.target().input().has("fill"));
    }
    BuildSite site(BuildRecords records, BuildRecords.Instance instance) {
        var site=new BuildSite(); site.bind(new Object(),world);
        assertNull(site.begin(instance.target(),BuildSiteTest.ROUTE,BuildSiteTest.FEET,world));
        site.attach(records,instance); return site;
    }
    void place(BuildSite site, BlockPos pos) {
        var before=world.state(pos); world.edit(pos,Blocks.STONE.defaultBlockState());
        site.recordPlacement(pos,before,world.state(pos),1,world);
    }
    int actions(BuildRecords records,BuildRecords.Instance instance) {
        return records.queryInstance(instance,world,0).get("action_count").getAsInt();
    }
    @Test void targetEncodingPreservesRotatedCoordinatesAndAuthoredRequirements() {
        for (int rotation:List.of(0,90,180,270)) {
            var target=BuildTarget.parse(json("""
                    {"anchor":{"x":20,"y":90,"z":-4},"rotation":%d,
                    "cells":[{"x":-2,"y":1,"z":3,"block":"oak_log","properties":[{"name":"axis","value":"x"}]}]}
                    """.formatted(rotation)));
            var copy=BuildTarget.parse(target.input());
            assertEquals(target.revision(),copy.revision()); assertEquals(target.cells(),copy.cells());
        }
    }
    @Test void oneDesignHasIndependentInstancesAtMultipleAnchorsAndRotations() {
        var records=store(); var a=instance(records);
        var b=records.createInstance(owner,builder,"world","wall",1,BuildSiteTest.TARGET.east(6),90);
        assertNotEquals(a.id(),b.id()); assertNotEquals(a.target().revision(),b.target().revision());
        assertThrows(RuntimeException.class,()->records.createInstance(owner,builder,"world","wall",1,BuildSiteTest.TARGET,180));
        assertEquals(2,records.list(owner,builder,"world",false,0).getAsJsonArray("entries").size());
    }
    @Test void patchesAreRevisionCheckedAndDoNotSilentlyChangeInstancesOrConsumeNoopRevisions() {
        var records=store(); var a=instance(records); String original=a.target().revision();
        var edit=JsonParser.parseString("[{\"x\":0,\"y\":0,\"z\":0,\"block\":\"glass\"}]").getAsJsonArray();
        records.patchDesign(owner,"wall",1,edit);
        assertEquals(original,a.target().revision()); assertEquals(1,a.revision());
        assertThrows(RuntimeException.class,()->records.patchDesign(owner,"wall",1,edit));
        records.patchDesign(owner,"wall",2,edit); assertEquals(2,records.designRevision(owner,"wall"));
        records.reviseInstance(a,2); assertEquals(2,a.revision()); assertNotEquals(original,a.target().revision());
    }
    @Test void partialEffectsAreWrittenBeforeCancelAndNewTasksDoNotReplayOrClaimSatisfiedCells() throws Exception {
        var records=store(); var a=instance(records); var site=site(records,a);
        place(site,BuildSiteTest.TARGET); assertEquals(1,actions(records,a));
        site.bind(new Object(),world); assertFalse(site.active());
        assertEquals(1,actions(records,a)); assertTrue(records.protects("world",BuildSiteTest.TARGET.below()));
        var data=Files.readString(root.resolve("mcbot/buildings.json"));
        assertTrue(data.contains("\"consumed\":1")); assertTrue(data.contains("\"sequence\":1"));
        var second=site(records,a);
        second.recordPlacement(BuildSiteTest.TARGET,world.state(BuildSiteTest.TARGET),world.state(BuildSiteTest.TARGET),0,world);
        assertEquals(1,actions(records,a));
    }
    @Test void worldLocalReloadKeepsIdentityHistoryAndDesignButNeverRecreatesLiveOwnership() {
        var records=store(); var a=instance(records); var site=site(records,a); place(site,BuildSiteTest.TARGET);
        assertTrue(records.mayChange(a,BuildSiteTest.TARGET,world));
        var restored=new BuildRecords(root); restored.load(); assertTrue(restored.healthy());
        assertEquals(records.worldId(),restored.worldId());
        var copy=restored.instance(owner,builder,"world",a.id(),1);
        assertEquals(a.target().revision(),copy.target().revision()); assertEquals(1,actions(restored,copy));
        assertFalse(restored.mayChange(copy,BuildSiteTest.TARGET,world));
        assertEquals(1,restored.queryInstance(copy,world,0).get("conflicts").getAsInt());
        assertFalse(restored.queryInstance(copy,world,0).get("automatic_resume").getAsBoolean());
    }
    @Test void sameBlockReplacementAndChunkReincarnationCannotRestoreOwnership() {
        var records=store(); var a=instance(records); place(site(records,a),BuildSiteTest.TARGET);
        world.edit(BuildSiteTest.TARGET,Blocks.AIR.defaultBlockState());
        world.edit(BuildSiteTest.TARGET,Blocks.STONE.defaultBlockState());
        assertFalse(records.mayChange(a,BuildSiteTest.TARGET,world));
        var records2=new BuildRecords(root.resolve("another")); records2.load();
        records2.createDesign(owner,"wall",json(DEFINITION)); var b=instance(records2);
        world.states.clear(); world.witnesses.clear(); place(site(records2,b),BuildSiteTest.TARGET);
        world.chunk=new Object(); assertFalse(records2.mayChange(b,BuildSiteTest.TARGET,world));
    }
    @Test void unknownAndMissingHooksRefuseRemovalButAnActualAirCellCanBeRepaired() {
        var records=store(); var a=instance(records); world.watch=false;
        place(site(records,a),BuildSiteTest.TARGET);
        assertFalse(records.mayChange(a,BuildSiteTest.TARGET,world));
        world.unknown.add(BuildSiteTest.TARGET); assertFalse(records.mayChange(a,BuildSiteTest.TARGET,world));
        world.unknown.clear(); world.edit(BuildSiteTest.TARGET,Blocks.AIR.defaultBlockState());
        assertTrue(records.mayChange(a,BuildSiteTest.TARGET,world));
        assertEquals("absent",records.queryInstance(a,world,0).getAsJsonArray("records").get(0).getAsJsonObject().get("status").getAsString());
    }
    @Test void preexistingMatchingBlocksAreNeverAdopted() {
        var records=store(); var a=instance(records);
        world.states.put(BuildSiteTest.TARGET,Blocks.STONE.defaultBlockState());
        var site=site(records,a);
        site.recordPlacement(BuildSiteTest.TARGET,world.state(BuildSiteTest.TARGET),world.state(BuildSiteTest.TARGET),0,world);
        assertEquals(0,actions(records,a)); assertFalse(records.mayChange(a,BuildSiteTest.TARGET,world));
        assertFalse(records.protects("world",BuildSiteTest.TARGET));
    }
    @Test void retiredOwnedCellsBecomeExplicitAirAndStillPassOriginalSafetyAndSourceChecks() {
        var records=store(); var a=instance(records); var site=site(records,a); place(site,BuildSiteTest.TARGET);
        records.patchDesign(owner,"wall",1,JsonParser.parseString("""
                [{"x":0,"y":0,"z":0,"remove":true},{"x":1,"y":0,"z":0,"block":"glass"}]
                """).getAsJsonArray());
        records.reviseInstance(a,2); site=site(records,a);
        assertEquals(2,a.target().cells().size());
        assertTrue(a.target().cells().stream().filter(c->c.pos().equals(BuildSiteTest.TARGET)).findFirst().orElseThrow().authored().isAir());
        assertNull(site.miningGuard(BuildSiteTest.TARGET,a.target().revision(),false,world));
        world.occupied.add(BuildSiteTest.TARGET);
        assertNotNull(site.miningGuard(BuildSiteTest.TARGET,a.target().revision(),false,world));
        world.occupied.clear(); world.edit(BuildSiteTest.TARGET,Blocks.STONE.defaultBlockState());
        assertNotNull(site.miningGuard(BuildSiteTest.TARGET,a.target().revision(),false,world));
    }
    @Test void aChangedAuthoredStateCanBeClearedOnlyWithTheLiveInstanceSource() {
        var records=new BuildRecords(root); records.load();
        records.createDesign(owner,"wall",json("""
                {"cells":[{"x":0,"y":0,"z":0,"block":"oak_log","properties":[{"name":"axis","value":"y"}]}]}
                """));
        var a=instance(records); var site=site(records,a);
        var before=world.state(BuildSiteTest.TARGET);
        world.edit(BuildSiteTest.TARGET,Blocks.OAK_LOG.defaultBlockState());
        site.recordPlacement(BuildSiteTest.TARGET,before,world.state(BuildSiteTest.TARGET),1,world);
        records.patchDesign(owner,"wall",1,JsonParser.parseString("""
                [{"x":0,"y":0,"z":0,"block":"oak_log","properties":[{"name":"axis","value":"x"}]}]
                """).getAsJsonArray());
        records.reviseInstance(a,2); var revised=site(records,a);
        var cell=a.target().cells().getFirst();
        BuildSurvey.Access access=new BuildSurvey.Access() {
            @Override public BlockState state(BlockPos pos) { return world.state(pos); }
            @Override public boolean inBounds(BlockPos pos) { return true; }
            @Override public boolean protectedCell(BlockPos pos) { return false; }
            @Override public boolean near(BlockPos pos) { return true; }
            @Override public BlockPlacement.Access placement() { return null; }
            @Override public JsonObject terrain() { return new JsonObject(); }
            @Override public boolean clearable(BuildTarget.Cell value) {
                return revised.miningGuard(value.pos(),a.target().revision(),false,world)==null;
            }
        };
        assertEquals("needs_clear",BuildSurvey.cellState(cell,access,world.state(cell.pos())));
        world.edit(cell.pos(),Blocks.AIR.defaultBlockState()); world.edit(cell.pos(),Blocks.OAK_LOG.defaultBlockState());
        assertEquals("state_conflict",BuildSurvey.cellState(cell,access,world.state(cell.pos())));
    }
    @Test void malformedHistoryCannotEraseProtectionOrBecomeExecutableWork() throws Exception {
        var records=store(); var a=instance(records); place(site(records,a),BuildSiteTest.TARGET);
        Path file=root.resolve("mcbot/buildings.json");
        var rootData=json(Files.readString(file));
        rootData.getAsJsonArray("instances").get(0).getAsJsonObject().getAsJsonArray("actions")
                .get(0).getAsJsonObject().addProperty("operation","execute");
        Files.writeString(file,rootData.toString());
        var restored=new BuildRecords(root); restored.load();
        assertFalse(restored.healthy()); assertTrue(restored.protects("world",BuildSiteTest.TARGET));
    }
    @Test void removalRecordsActualChangedStateAndKeepsHistoryAndOriginalAccount() {
        var records=store(); var a=instance(records); var site=site(records,a); place(site,BuildSiteTest.TARGET);
        var before=world.state(BuildSiteTest.TARGET); world.edit(BuildSiteTest.TARGET,Blocks.AIR.defaultBlockState());
        site.recordRemoval(BuildSiteTest.TARGET,before,world.state(BuildSiteTest.TARGET)); assertEquals(2,actions(records,a));
        var budget=site.navigation(); site.bind(new Object(),world);
        assertEquals(2,actions(records,a)); assertNotNull(budget);
        assertEquals(1,records.queryInstance(a,world,0).get("record_count").getAsInt());
    }
    @Test void ownershipAndDimensionAreCheckedTogetherWithRevisionAndWorldStore() {
        var records=store(); var a=instance(records);
        assertThrows(RuntimeException.class,()->records.instance(UUID.randomUUID(),builder,"world",a.id(),1));
        assertThrows(RuntimeException.class,()->records.instance(owner,UUID.randomUUID(),"world",a.id(),1));
        assertThrows(RuntimeException.class,()->records.instance(owner,builder,"other",a.id(),1));
        assertThrows(RuntimeException.class,()->records.instance(owner,builder,"world",a.id(),2));
        var other=new BuildRecords(root.resolve("other")); other.load();
        assertThrows(RuntimeException.class,()->other.instance(owner,builder,"world",a.id(),1));
        assertNotEquals(records.worldId(),other.worldId());
        assertTrue(records.list(UUID.randomUUID(),builder,"world",true,0).getAsJsonArray("entries").isEmpty());
    }
    @Test void explicitInstanceRevisionIsBlockedInsideAFrozenTask() {
        var records=store(); var a=instance(records); var site=site(records,a);
        var args=json("{\"operation\":\"instance_revise\",\"instance_id\":\""+a.id()+"\",\"instance_revision\":1,\"design_revision\":1}");
        assertFalse(BuildRecordTool.execute(records,owner,builder,"world",site,world,args).ok());
        assertEquals(1,a.revision()); assertEquals(0,site.report(world).get("edit_attempts").getAsInt());
    }
    @Test void layersAndPagesAreBoundedAndCannotBeMixedAcrossRevisions() {
        var records=new BuildRecords(root); records.load();
        records.createDesign(owner,"floor",json("""
                {"fill":{"from":{"x":0,"y":0,"z":0},"to":{"x":4,"y":1,"z":3},"block":"stone"}}
                """));
        var first=records.queryDesign(owner,"floor",1,0,1);
        assertEquals(16,first.getAsJsonArray("cells").size()); assertEquals(16,first.get("next_offset").getAsInt());
        assertEquals(4,records.queryDesign(owner,"floor",1,16,1).getAsJsonArray("cells").size());
        assertThrows(RuntimeException.class,()->records.queryDesign(owner,"floor",2,16,1));
        assertThrows(RuntimeException.class,()->records.queryDesign(owner,"floor",1,1,null));
    }
    @Test void namesPayloadsAndDuplicateEditsFailWithoutChangingDesign() {
        var records=store();
        for (String text:List.of("\"../outside\"","\"Bad Name\"","\"\""))
            assertThrows(RuntimeException.class,()->BuildRecords.name(JsonParser.parseString(text)));
        assertThrows(RuntimeException.class,()->records.createDesign(owner,"unsafe",json("""
                {"cells":[{"x":0,"y":0,"z":0,"block":"stone","nbt":{"Items":[]}}]}
                """)));
        assertThrows(RuntimeException.class,()->records.patchDesign(owner,"wall",1,JsonParser.parseString("""
                [{"x":0,"y":0,"z":0,"remove":true},{"x":0,"y":0,"z":0,"block":"glass"}]
                """).getAsJsonArray()));
        assertEquals(1,records.designRevision(owner,"wall"));
    }
    @Test void corruptionAndOversizedStoresFailClosedAndAreNotOverwritten() throws Exception {
        Files.createDirectories(root.resolve("mcbot"));
        Path file=root.resolve("mcbot/buildings.json"); Files.writeString(file,"[broken");
        var records=new BuildRecords(root); records.load();
        assertFalse(records.healthy()); assertTrue(records.protects("world",BlockPos.ZERO));
        assertThrows(RuntimeException.class,()->records.createDesign(owner,"wall",json(DEFINITION)));
        assertEquals("[broken",Files.readString(file));
        Files.writeString(file," ".repeat(BuildRecords.FILE_LIMIT+1));
        var huge=new BuildRecords(root); huge.load(); assertFalse(huge.healthy());
    }
    @Test void saveFailureAfterAnActualEffectIsVisibleAndStopsFurtherEdits() throws Exception {
        var records=store(); var a=instance(records); var site=site(records,a);
        Files.delete(root.resolve("mcbot/buildings.json")); Files.delete(root.resolve("mcbot"));
        Files.writeString(root.resolve("mcbot"),"blocked_directory");
        place(site,BuildSiteTest.TARGET);
        assertEquals(Blocks.STONE.defaultBlockState(),world.state(BuildSiteTest.TARGET));
        assertFalse(records.healthy()); assertNotNull(site.live(world));
        assertFalse(records.hasCapacity(a,BuildSiteTest.TARGET));
    }
    @Test void dataAndPerInstanceActionCapsDoNotRecycleOnRevisionOrTaskChanges() {
        var records=store(); var a=instance(records); var site=site(records,a);
        for (int n=0;n<BuildRecords.ACTION_LIMIT/2;n++) {
            assertTrue(records.hasCapacity(a,BuildSiteTest.TARGET)); place(site,BuildSiteTest.TARGET);
            var before=world.state(BuildSiteTest.TARGET); world.edit(BuildSiteTest.TARGET,Blocks.AIR.defaultBlockState());
            site.recordRemoval(BuildSiteTest.TARGET,before,world.state(BuildSiteTest.TARGET));
        }
        assertFalse(records.hasCapacity(a,BuildSiteTest.TARGET));
        site.bind(new Object(),world); assertEquals(256,actions(records,a));
        for (int n=1;n<BuildRecords.ENTRY_LIMIT;n++) records.createDesign(owner,"d"+n,json(DEFINITION));
        assertThrows(RuntimeException.class,()->records.createDesign(owner,"overflow",json(DEFINITION)));
    }
    @Test void actualNativePlacementCoreRecordsAnExceptionWithPartialEffectsExactlyOnce() {
        var records=store(); var a=instance(records); var site=site(records,a);
        var inventory=BlockActionFixtures.inventory(); inventory.setItem(0,new ItemStack(Items.STONE,2));
        BlockPlacement.Access access=new BlockPlacement.Access() {
            @Override public ServerTool.Result guard(BlockPos pos) { return site.placementGuard(pos,Blocks.STONE.defaultBlockState(),world); }
            @Override public Container inventory() { return inventory; }
            @Override public BlockState state(BlockPos pos) { return world.state(pos); }
            @Override public ServerTool.Result sight(BlockPos pos,Direction face) { return null; }
            @Override public InteractionResult place(BlockPos pos,ItemStack stack,Direction face) {
                world.edit(pos,Blocks.STONE.defaultBlockState()); stack.shrink(1); throw new IllegalStateException("partial");
            }
            @Override public void recorded(BlockPos pos,BlockState before,BlockState after,int consumed) {
                site.recordPlacement(pos,before,after,consumed,world);
            }
        };
        assertNull(BlockPlacement.assess(access,BuildSiteTest.TARGET,0,Direction.UP)); assertEquals(0,actions(records,a));
        var result=BlockPlacement.place(access,BuildSiteTest.TARGET,0,Direction.UP);
        assertFalse(result.ok()); assertTrue(result.data().get("changed").getAsBoolean());
        assertEquals(1,actions(records,a)); assertEquals(1,inventory.getItem(0).getCount());
        assertFalse(BlockPlacement.place(access,BuildSiteTest.TARGET,0,Direction.UP).ok()); assertEquals(1,actions(records,a));
    }
}
