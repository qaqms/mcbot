package com.neko.mcbot.building;

import com.google.gson.*;
import com.neko.mcbot.server.*;
import com.neko.mcbot.server.tools.BuildBlueprintTool;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.*;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.GZIPOutputStream;
import static org.junit.jupiter.api.Assertions.*;

class StructureBlueprintTest {
    @TempDir Path worldRoot;
    @BeforeAll static void boot() { BlockActionFixtures.bootstrap(); }
    Path directory() throws Exception {
        var dir=worldRoot.resolve("mcbot/blueprints"); Files.createDirectories(dir); return dir;
    }
    static ListTag vector(int x,int y,int z) {
        var result=new ListTag(); result.add(IntTag.valueOf(x)); result.add(IntTag.valueOf(y)); result.add(IntTag.valueOf(z)); return result;
    }
    static CompoundTag palette(String id) { var result=new CompoundTag(); result.putString("Name",id); return result; }
    static CompoundTag block(int x,int y,int z,int state) {
        var result=new CompoundTag(); result.put("pos",vector(x,y,z)); result.putInt("state",state); return result;
    }
    static CompoundTag fixture() {
        var root=new CompoundTag(); root.put("size",vector(2,1,1)); root.putInt("DataVersion",0);
        var palette=new ListTag(); palette.add(palette("minecraft:stone")); palette.add(palette("minecraft:air"));
        root.put("palette",palette);
        var blocks=new ListTag(); blocks.add(block(0,0,0,0)); blocks.add(block(1,0,0,1));
        root.put("blocks",blocks); root.put("entities",new ListTag()); return root;
    }
    void write(String name,CompoundTag root) throws Exception { NbtIo.writeCompressed(root,directory().resolve(name+".nbt")); }
    StructureBlueprint source() { return new StructureBlueprint(worldRoot,()->0L); }
    BuildRecords records() { var records=new BuildRecords(worldRoot); records.load(); return records; }
    static JsonObject json(String value) { return JsonParser.parseString(value).getAsJsonObject(); }
    @Test void inspectionIsReadOnlyAndPreservesExplicitAirAndB1Materials() throws Exception {
        write("sample",fixture());
        var source=source(); var inspection=source.inspect("sample");
        assertTrue(inspection.importable()); assertEquals(2,inspection.target().cells().size());
        assertTrue(inspection.target().cells().get(1).authored().isAir());
        assertEquals(1,inspection.report().getAsJsonArray("materials").get(0).getAsJsonObject().get("design_required").getAsInt());
        assertFalse(inspection.report().get("world_changed").getAsBoolean());
        assertFalse(inspection.report().get("goal_satisfied").getAsBoolean());
        assertFalse(Files.exists(worldRoot.resolve("mcbot/buildings.json")));
    }
    @Test void emptyListingDoesNotCreateADirectoryAndUnrelatedFormatsAreNotParsed() throws Exception {
        var result=source().list(0); assertTrue(result.getAsJsonArray("names").isEmpty());
        assertFalse(Files.exists(worldRoot.resolve("mcbot")));
        Files.writeString(directory().resolve("other.schem"),"not parsed");
        write("sample",fixture());
        result=source().list(0);
        assertEquals(1,result.getAsJsonArray("names").size()); assertEquals(1,result.get("ignored_files").getAsInt());
        assertThrows(Exception.class,()->source().inspect("../outside"));
        assertThrows(Exception.class,()->source().inspect("sample.nbt"));
    }
    @Test void fullImportUsesB4DesignThenOriginalTargetRotationWithoutWorldActions() throws Exception {
        var root=fixture(); var state=new CompoundTag(); state.putString("axis","x");
        var log=palette("minecraft:oak_log"); log.put("Properties",state);
        root.getListOrEmpty("palette").set(0,log);
        write("sample",root);
        var source=source(); var records=records(); var owner=UUID.randomUUID();
        String revision=source.inspect("sample").sourceRevision();
        var result=BuildBlueprintTool.execute(source,records,owner,json("""
                {"operation":"import","name":"sample","source_revision":"%s","design_id":"logs"}
                """.formatted(revision)));
        assertTrue(result.ok(),result.feedback()); assertTrue(result.data().get("imported").getAsBoolean());
        var instance=records.createInstance(owner,UUID.randomUUID(),"world","logs",1,new BlockPos(4,90,0),90);
        var cell=instance.target().cells().getFirst();
        assertTrue(cell.matches(Blocks.OAK_LOG.defaultBlockState().setValue(
                net.minecraft.world.level.block.state.properties.BlockStateProperties.AXIS,Direction.Axis.Z)));
        assertEquals(0,records.queryInstance(instance,new BuildSiteTest.World(),0).get("action_count").getAsInt());
    }
    @Test void changedFileIsRejectedInsteadOfSilentlyUpdatingAnImportedDesign() throws Exception {
        write("sample",fixture()); var source=source(); var first=source.inspect("sample");
        var root=fixture(); root.getListOrEmpty("palette").set(0,palette("minecraft:glass")); write("sample",root);
        var records=records(); var owner=UUID.randomUUID();
        var result=BuildBlueprintTool.execute(source,records,owner,json("""
                {"operation":"import","name":"sample","source_revision":"%s","design_id":"changed"}
                """.formatted(first.sourceRevision())));
        assertFalse(result.ok()); assertTrue(result.feedback().startsWith("TARGET_REVISION_CHANGED:"));
        assertTrue(records.list(owner,UUID.randomUUID(),"world",true,0).getAsJsonArray("entries").isEmpty());
    }
    @Test void invalidIndicesAreCountedAndCannotProduceAPartialDesign() throws Exception {
        var root=fixture(); root.getListOrEmpty("blocks").add(block(0,0,1,99)); root.put("size",vector(2,1,2));
        write("broken",root); var source=source(); var inspection=source.inspect("broken");
        assertEquals(1,inspection.report().get("invalid_cells").getAsInt()); assertFalse(inspection.importable());
        var records=records(); var owner=UUID.randomUUID();
        assertFalse(BuildBlueprintTool.execute(source,records,owner,json("""
                {"operation":"import","name":"broken","source_revision":"%s","design_id":"partial"}
                """.formatted(inspection.sourceRevision()))).ok());
        assertFalse(Files.exists(worldRoot.resolve("mcbot/buildings.json")));
    }
    @Test void unknownNamesAndPropertiesIncludingUnusedPaletteEntriesBlockImport() throws Exception {
        for (String kind:List.of("unknown","property","unused")) {
            var root=fixture(); var palette=root.getListOrEmpty("palette");
            if (kind.equals("unknown")) palette.set(0,palette("minecraft:missing_block"));
            else if (kind.equals("unused")) palette.add(palette("minecraft:missing_block"));
            else {
                var state=new CompoundTag(); state.putString("axis","diagonal");
                var log=palette("minecraft:oak_log"); log.put("Properties",state); palette.set(0,log);
            }
            write(kind,root); var result=source().inspect(kind);
            assertFalse(result.importable()); assertEquals(1,result.report().get("invalid_palette_entries").getAsInt());
        }
    }
    @Test void unsupportedMulticellFluidAndComponentBlocksNeverDisappearFromAccounting() throws Exception {
        for (String name:List.of("red_bed","water","chest","oak_door")) {
            var root=fixture(); root.getListOrEmpty("palette").set(0,palette("minecraft:"+name));
            write("sample",root); var result=source().inspect("sample");
            assertFalse(result.importable()); assertEquals(1,result.report().get("unsupported_cells").getAsInt(),name);
            assertEquals(2,result.report().get("cells").getAsInt()); assertEquals(2,result.target().cells().size());
        }
    }
    @Test void payloadsCommandsLootContainersAndEvenInertTextAreNotImported() throws Exception {
        for (String payload:List.of("Items","Command","LootTable","front_text","components")) {
            var root=fixture(); var data=new CompoundTag(); data.putString(payload,"not executable");
            root.getListOrEmpty("blocks").getCompoundOrEmpty(0).put("nbt",data);
            write("sample",root); var result=source().inspect("sample");
            assertEquals(1,result.report().get("payload_cells").getAsInt()); assertFalse(result.importable());
            assertFalse(result.report().toString().contains("not executable"));
        }
    }
    @Test void entitiesAreCountedAndBlockImportWithoutCreatingOrPricingTheirContents() throws Exception {
        var root=fixture(); var entity=new CompoundTag(); entity.putString("id","minecraft:armor_stand");
        var data=new CompoundTag(); data.putString("Item","not disclosed"); entity.put("nbt",data);
        root.getListOrEmpty("entities").add(entity); write("sample",root);
        var result=source().inspect("sample");
        assertEquals(1,result.report().get("entity_count").getAsInt()); assertFalse(result.importable());
        assertFalse(result.report().toString().contains("armor_stand"));
    }
    @Test void duplicateCoordinatesOutOfSizeInvalidTypesAndMultiplePalettesFailCleanly() throws Exception {
        for (String kind:List.of("duplicate","outside","type","palettes","unknown_field","negative_index_type")) {
            var root=fixture();
            switch (kind) {
                case "duplicate" -> root.getListOrEmpty("blocks").add(block(0,0,0,0));
                case "outside" -> root.getListOrEmpty("blocks").add(block(2,0,0,0));
                case "type" -> root.put("size",vector(0,1,1));
                case "palettes" -> root.put("palettes",new ListTag());
                case "unknown_field" -> root.putString("execute","never");
                default -> root.getListOrEmpty("blocks").getCompoundOrEmpty(0).putLong("state",-1);
            }
            write(kind,root);
            assertThrows(Exception.class,()->source().inspect(kind),kind);
        }
    }
    @Test void collectionDimensionsPaletteAndDirectoryBudgetsAreIndependent() throws Exception {
        for (String kind:List.of("size","volume","cells","palette")) {
            var root=fixture();
            switch (kind) {
                case "size" -> root.put("size",vector(34,1,1));
                case "volume" -> root.put("size",vector(33,33,33));
                case "cells" -> { for (int n=0;n<129;n++) root.getListOrEmpty("blocks").add(block(0,0,0,0)); }
                default -> { for (int n=0;n<65;n++) root.getListOrEmpty("palette").add(palette("minecraft:stone")); }
            }
            write(kind,root); assertThrows(Exception.class,()->source().inspect(kind),kind);
        }
        for (int n=0;n<129;n++) Files.writeString(directory().resolve("ignored"+n+".txt"),"");
        assertThrows(Exception.class,()->source().list(0));
    }
    @Test void compressedExpandedHeapDepthAndDeadlineBudgetsFailBeforeImport() throws Exception {
        Files.write(directory().resolve("huge.nbt"),new byte[StructureBlueprint.COMPRESSED_LIMIT+1]);
        assertThrows(Exception.class,()->source().inspect("huge"));
        var expanded=fixture(); expanded.putByteArray("padding",new byte[StructureBlueprint.EXPANDED_LIMIT+1]);
        write("expanded",expanded); assertThrows(Exception.class,()->source().inspect("expanded"));
        var nested=fixture(); var cursor=new CompoundTag(); nested.put("nested",cursor);
        for (int n=0;n<30;n++) { var next=new CompoundTag(); cursor.put("next",next); cursor=next; }
        write("nested",nested); assertThrows(Exception.class,()->source().inspect("nested"));
        try (var output=new GZIPOutputStream(Files.newOutputStream(directory().resolve("heap.nbt")));
             var data=new DataOutputStream(output)) {
            data.writeByte(10); data.writeUTF(""); data.writeByte(7); data.writeUTF("padding"); data.writeInt(Integer.MAX_VALUE);
        }
        assertThrows(Exception.class,()->source().inspect("heap"));
        write("sample",fixture()); var time=new AtomicLong();
        var bounded=new StructureBlueprint(worldRoot,()->time.getAndAdd(StructureBlueprint.MAX_NANOS));
        assertThrows(Exception.class,()->bounded.inspect("sample"));
        assertFalse(Files.exists(worldRoot.resolve("mcbot/buildings.json")));
    }
    @Test void truncatedGzipAndTrailingDataAreNotAcceptedAsAValidPrefix() throws Exception {
        write("sample",fixture()); Path file=directory().resolve("sample.nbt");
        byte[] bytes=Files.readAllBytes(file); Files.write(file,Arrays.copyOf(bytes,bytes.length/2));
        assertThrows(Exception.class,()->source().inspect("sample"));
        try (var output=new GZIPOutputStream(Files.newOutputStream(file)); var data=new DataOutputStream(output)) {
            NbtIo.write(fixture(),data); data.writeInt(17);
        }
        assertThrows(Exception.class,()->source().inspect("sample"));
    }
    @Test void invalidAndPayloadDiagnosticsAreBoundedWithoutChangingTheDenominator() throws Exception {
        var root=fixture(); root.put("size",vector(12,1,1)); var blocks=new ListTag();
        for (int x=0;x<12;x++) blocks.add(block(x,0,0,99)); root.put("blocks",blocks); write("sample",root);
        var result=source().inspect("sample");
        assertEquals(12,result.report().get("invalid_cells").getAsInt());
        assertEquals(8,result.report().getAsJsonArray("diagnostics").size());
        assertTrue(result.report().get("diagnostics_truncated").getAsBoolean());
        assertFalse(result.importable()); assertNull(result.target());
    }
    @Test void validImportStillUsesTheOriginalPlacementCoreAndFreshWholeTargetVerification() throws Exception {
        var root=fixture(); root.put("size",vector(1,1,1)); var blocks=new ListTag(); blocks.add(block(0,0,0,0));
        root.put("blocks",blocks); write("sample",root);
        var source=source(); var inspected=source.inspect("sample"); var records=records();
        var owner=UUID.randomUUID(); var builder=UUID.randomUUID();
        assertTrue(BuildBlueprintTool.execute(source,records,owner,json("""
                {"operation":"import","name":"sample","source_revision":"%s","design_id":"wall"}
                """.formatted(inspected.sourceRevision()))).ok());
        var instance=records.createInstance(owner,builder,"world","wall",1,BuildSiteTest.TARGET,0);
        var world=new BuildSiteTest.World(); var site=new BuildSite(); site.bind(new Object(),world);
        assertNull(site.begin(instance.target(),BuildSiteTest.ROUTE,BuildSiteTest.FEET,world)); site.attach(records,instance);
        var inventory=BlockActionFixtures.inventory(); inventory.setItem(0,new ItemStack(Items.STONE,2));
        BlockPlacement.Access placement=new BlockPlacement.Access() {
            @Override public ServerTool.Result guard(BlockPos pos) { return site.placementGuard(pos,Blocks.STONE.defaultBlockState(),world); }
            @Override public Container inventory() { return inventory; }
            @Override public BlockState state(BlockPos pos) { return world.state(pos); }
            @Override public ServerTool.Result sight(BlockPos pos,Direction face) { return null; }
            @Override public BlockState proposedState() { return Blocks.STONE.defaultBlockState(); }
            @Override public InteractionResult place(BlockPos pos,ItemStack stack,Direction face) {
                world.edit(pos,Blocks.STONE.defaultBlockState()); stack.shrink(1); return InteractionResult.SUCCESS;
            }
            @Override public void recorded(BlockPos pos,BlockState before,BlockState after,int consumed) { site.recordPlacement(pos,before,after,consumed,world); }
        };
        BuildSurvey.Access access=new BuildSurvey.Access() {
            @Override public BlockState state(BlockPos pos) { return world.state(pos); }
            @Override public boolean inBounds(BlockPos pos) { return true; }
            @Override public boolean protectedCell(BlockPos pos) { return false; }
            @Override public boolean near(BlockPos pos) { return true; }
            @Override public BlockPlacement.Access placement() { return placement; }
            @Override public JsonObject terrain() { return json("{\"dimension\":\"world\",\"chunks\":{\"0,0\":\"stable\"}}"); }
        };
        assertFalse(BuildSurvey.verify(instance.target(),access,0,()->0L).data().get("goal_satisfied").getAsBoolean());
        assertTrue(BlockPlacement.placeDesired(placement,BuildSiteTest.TARGET,0,Direction.UP,
                instance.target().cells().getFirst()::matches).ok());
        assertEquals(1,inventory.getItem(0).getCount());
        assertTrue(BuildSurvey.verify(instance.target(),access,0,()->0L).data().get("goal_satisfied").getAsBoolean());
        assertEquals(1,records.queryInstance(instance,world,0).get("action_count").getAsInt());
        assertFalse(BlockPlacement.placeDesired(placement,BuildSiteTest.TARGET,0,Direction.UP,
                instance.target().cells().getFirst()::matches).ok());
        assertEquals(1,inventory.getItem(0).getCount());
    }
    static CompoundTag doorFixture() {
        var root=fixture(); root.put("size",vector(1,2,1));
        var palette=new ListTag();
        for (String half:List.of("lower","upper")) {
            var entry=palette("minecraft:oak_door"); var properties=new CompoundTag();
            properties.putString("half",half); properties.putString("facing","north"); properties.putString("hinge","left");
            entry.put("Properties",properties); palette.add(entry);
        }
        root.put("palette",palette); var blocks=new ListTag();
        blocks.add(block(0,0,0,0)); blocks.add(block(0,1,0,1)); root.put("blocks",blocks); return root;
    }
    @Test void doorBlueprintKeepsBothRequirementsAndChargesOneMaterialThroughTheSameB4Target() throws Exception {
        write("door",doorFixture()); var inspection=source().inspect("door");
        assertTrue(inspection.importable(),inspection.report().toString()); assertEquals(2,inspection.target().cells().size());
        assertEquals(1,inspection.report().getAsJsonArray("materials").get(0).getAsJsonObject().get("design_required").getAsInt());
        var records=records(); var owner=UUID.randomUUID();
        assertTrue(BuildBlueprintTool.execute(source(),records,owner,json("""
                {"operation":"import","name":"door","source_revision":"%s","design_id":"door"}
                """.formatted(inspection.sourceRevision()))).ok());
        var instance=records.createInstance(owner,UUID.randomUUID(),"world","door",1,BuildSiteTest.TARGET,90);
        assertEquals(2,instance.target().cells().size());
        assertEquals("",BuildObjects.problem(instance.target(),instance.target().cells().getFirst()));
        var restored=new BuildRecords(worldRoot); restored.load(); assertTrue(restored.healthy());
    }
    @Test void incompleteConflictingOrPayloadDoorBlueprintCannotImportASanitizedOrExpandedDesign() throws Exception {
        for (String kind:List.of("missing","conflict","payload")) {
            var root=doorFixture();
            switch (kind) {
                case "missing" -> root.getListOrEmpty("blocks").remove(1);
                case "conflict" -> root.getListOrEmpty("palette").getCompoundOrEmpty(1)
                        .getCompoundOrEmpty("Properties").putString("facing","east");
                default -> root.getListOrEmpty("blocks").getCompoundOrEmpty(1).put("nbt",new CompoundTag());
            }
            write(kind,root); var inspected=source().inspect(kind); assertFalse(inspected.importable(),kind);
            assertEquals(kind.equals("missing") ? 1 : 2,inspected.report().get("cells").getAsInt());
            assertEquals(kind.equals("payload") ? 1 : 0,inspected.report().get("payload_cells").getAsInt());
            assertFalse(BuildBlueprintTool.execute(source(),records(),UUID.randomUUID(),json("""
                    {"operation":"import","name":"%s","source_revision":"%s","design_id":"bad"}
                    """.formatted(kind,inspected.sourceRevision()))).ok());
        }
        assertFalse(Files.exists(worldRoot.resolve("mcbot/buildings.json")));
    }
    @Test void listingIsPagedAndArgumentModesCannotFallBackToImport() throws Exception {
        for (int index=0;index<20;index++) write("n"+index,fixture());
        assertEquals(16,source().list(0).getAsJsonArray("names").size());
        assertEquals(4,source().list(16).getAsJsonArray("names").size());
        assertThrows(Exception.class,()->source().list(1));
        var records=records(); var owner=UUID.randomUUID();
        for (String args:List.of("{\"operation\":\"list\",\"design_id\":\"bad\"}",
                "{\"operation\":\"inspect\",\"name\":\"n0\",\"source_revision\":\"wrong\"}",
                "{\"operation\":\"import\",\"name\":\"n0\"}","{\"operation\":true}"))
            assertFalse(BuildBlueprintTool.execute(source(),records,owner,json(args)).ok());
        assertFalse(Files.exists(worldRoot.resolve("mcbot/buildings.json")));
    }
}
