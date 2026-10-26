package com.neko.mcbot.building;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.neko.mcbot.server.BlockPlacement;
import com.neko.mcbot.server.ServerTool;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class BuildSurveyTest {
    @Test void pairedDrawingDiagnosticsCountAllInvalidCellsButKeepOnlyFourExamples() {
        var target=BuildTarget.parse(JsonParser.parseString("""
                {"anchor":{"x":0,"y":90,"z":0},"fill":{"from":{"x":0,"y":0,"z":0},
                "to":{"x":31,"y":0,"z":0},"block":"red_bed"}}
                """).getAsJsonObject());
        var world=new World();
        var result=BuildSurvey.query(target,world,0,0,()->0);
        var validation=result.data().getAsJsonObject("object_validation");
        assertEquals(32,validation.get("invalid_cells").getAsInt());
        assertEquals(4,validation.getAsJsonArray("issues").size());
        assertEquals(28,validation.get("issues_omitted").getAsInt());
        assertTrue(result.feedback().contains("INVALID_BUILD_TARGET:"));
        assertTrue(result.data().toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length<20000);
        assertEquals(0,world.prepares);
    }

    @Test void invalidBedsOutsideTheDetailPageAreReportedForTheWholeDrawingWithoutChangingIt() {
        for (int rotation:List.of(0,90,180,270)) {
            var target=BuildTarget.parse(JsonParser.parseString("""
                    {"anchor":{"x":-236,"y":63,"z":-440},"rotation":%d,"regions":[
                    {"from":{"x":0,"y":0,"z":0},"to":{"x":31,"y":0,"z":0},"block":"stone"},
                    {"from":{"x":1,"y":1,"z":8},"to":{"x":1,"y":1,"z":8},"block":"red_bed",
                    "properties":[{"name":"part","value":"head"},{"name":"facing","value":"south"}]},
                    {"from":{"x":1,"y":1,"z":9},"to":{"x":1,"y":1,"z":9},"block":"red_bed",
                    "properties":[{"name":"part","value":"foot"},{"name":"facing","value":"south"}]}]}
                    """.formatted(rotation)).getAsJsonObject());
            var original=target.input().deepCopy();
            var world=new World();
            var data=BuildSurvey.query(target,world,0,0,()->0).data();
            var validation=data.getAsJsonObject("object_validation");
            assertNotNull(validation,"the first detail page must expose invalid paired cells elsewhere in the drawing");
            assertFalse(validation.get("valid").getAsBoolean());
            assertEquals(2,validation.get("invalid_cells").getAsInt());
            assertEquals(2,validation.getAsJsonArray("issues").size());
            var issue=validation.getAsJsonArray("issues").get(0).getAsJsonObject();
            assertEquals("bed_pair_missing_or_inconsistent",issue.get("reason").getAsString());
            assertTrue(issue.has("expected_partner"));
            assertEquals(validation,BuildSurvey.verify(target,world,0,()->0).data().get("object_validation"));
            assertEquals(original,target.input(),"diagnosis cannot silently turn a bed or replace the drawing");
            assertTrue(world.blocks.isEmpty());
            assertEquals(0,world.prepares);
        }
    }

    @Test void foundationObservationsDistinguishReplacingTopsoilFromAddingAnExtraFloorAboveIt() {
        var world=new World();
        world.inventory.setItem(0,new ItemStack(Items.STONE,64));
        var target=cells("""
                [{"x":0,"y":0,"z":0,"block":"stone"},{"x":1,"y":0,"z":0,"block":"stone"}]
                """);
        for (var cell:target.cells()) {
            world.blocks.put(cell.pos(),Blocks.GRASS_BLOCK.defaultBlockState());
            world.blocks.put(cell.pos().below(),Blocks.DIRT.defaultBlockState());
        }
        var ground=world.query(target).getAsJsonObject("design_review").getAsJsonObject("foundation_surface");
        assertEquals(90,ground.get("lowest_design_y").getAsInt());
        assertEquals(2,ground.get("topsoil_in_layer").getAsInt());
        assertTrue(ground.get("assessment_complete").getAsBoolean());
        assertFalse(ground.has("advice"),"soil under the original topsoil is not a raised foundation");
        assertEquals(2,count(world.query(target),"needs_clear"));
        for (var cell:target.cells()) world.blocks.put(cell.pos(),Blocks.SHORT_GRASS.defaultBlockState());
        assertEquals(2,count(world.query(target),"needs_clear"),
                "replaceable grass cover must be cleared before the foundation is placed");
        for (var cell:target.cells()) world.blocks.put(cell.pos(),Blocks.GRASS_BLOCK.defaultBlockState());
        var raised=target.input().deepCopy();
        raised.getAsJsonObject("anchor").addProperty("y",91);
        var onGrass=BuildTarget.parse(raised);
        var before=onGrass.input().deepCopy();
        var review=world.query(onGrass).getAsJsonObject("design_review").getAsJsonObject("foundation_surface");
        assertEquals(0,review.get("topsoil_in_layer").getAsInt());
        assertEquals(2,review.get("topsoil_below_layer").getAsInt());
        assertEquals(91,review.get("lowest_design_y").getAsInt());
        assertTrue(review.has("advice"));
        assertEquals(before,onGrass.input(),"observations do not lower or rewrite an authored foundation");
        for (var cell:onGrass.cells()) world.blocks.put(cell.pos(),cell.desired());
        assertFalse(world.query(onGrass).getAsJsonObject("design_review").getAsJsonObject("foundation_surface")
                .has("advice"),"an already built floor cannot reveal its original surface height");
        world.unknown.add(new BlockPos(0,90,0));
        assertFalse(world.query(onGrass).getAsJsonObject("design_review").getAsJsonObject("foundation_surface")
                .get("assessment_complete").getAsBoolean());
    }

    @Test void clearingToolAdviceCountsTheWholeSiteAndRefreshesAfterAUsefulToolIsMade() {
        int[] observations = {0};
        boolean[] efficient = {false};
        var world = new World() {
            @Override public JsonObject miningTool(BlockPos pos, BlockState state) {
                observations[0]++;
                var tool = new JsonObject();
                tool.addProperty("preferred_kind", "shovel");
                tool.addProperty("preparation_recommended", !efficient[0]);
                tool.addProperty("estimated_seconds", efficient[0] ? .125 : .75);
                return tool;
            }
        };
        var target = BuildTarget.parse(JsonParser.parseString("""
                {"anchor":{"x":0,"y":90,"z":0},"fill":{"from":{"x":0,"y":0,"z":0},
                "to":{"x":6,"y":0,"z":6},"block":"stone"}}
                """).getAsJsonObject());
        target.cells().forEach(c -> world.blocks.put(c.pos(), Blocks.DIRT.defaultBlockState()));
        var before = world.query(target);
        var work = before.getAsJsonObject("clearing_work");
        assertEquals(49, work.get("observed_cells").getAsInt());
        assertTrue(work.get("assessment_complete").getAsBoolean());
        assertTrue(work.get("preparation_recommended").getAsBoolean());
        var shovel = work.getAsJsonArray("tools").get(0).getAsJsonObject();
        assertEquals(49, shovel.get("cells").getAsInt(), "the current 16-cell detail page is not the workload");
        assertEquals(36.75, shovel.get("estimated_seconds").getAsDouble());
        assertEquals(1, observations[0], "price one state once, not every target cell");
        efficient[0] = true;
        var after = world.query(target);
        assertFalse(after.getAsJsonObject("clearing_work").get("preparation_recommended").getAsBoolean());
        assertEquals(6.125, after.getAsJsonObject("clearing_work").getAsJsonArray("tools")
                .get(0).getAsJsonObject().get("estimated_seconds").getAsDouble());
        assertEquals(before.get("goal_observation_key"), after.get("goal_observation_key"),
                "tool readiness does not create construction progress");
        assertEquals(49, world.blocks.size());
        world.unknown.add(target.cells().getFirst().pos());
        assertFalse(world.query(target).getAsJsonObject("clearing_work").get("assessment_complete").getAsBoolean());
    }

    @Test void wrongNativeOrientationAtAnEmptyCellIsAWorkStanceProblemRatherThanAProtectedTarget() {
        var target=BuildTarget.parse(JsonParser.parseString("""
                {"anchor":{"x":4,"y":90,"z":0},"cells":[{"x":0,"y":0,"z":0,"block":"oak_stairs",
                "properties":[{"name":"facing","value":"east"}]}]}
                """).getAsJsonObject());
        var evidence=new BuildSiteTest.World();
        var site=new BuildSite(); site.bind(new Object(),evidence);
        assertNull(site.begin(target,BuildSiteTest.ROUTE,BuildSiteTest.FEET,evidence));
        var world=new World(); world.inventory.setItem(0,new ItemStack(Items.OAK_STAIRS,2));
        world.prepare=(pos,face)->site.placementGuard(pos,world.proposed,evidence);
        var wrong=world.query(target).getAsJsonArray("cells").get(0).getAsJsonObject();
        assertEquals("state_conflict",wrong.get("state").getAsString());
        assertEquals("desired_state_mismatch",wrong.get("placement_failure").getAsString());
        assertEquals("minecraft:air",wrong.get("actual_block").getAsString());
        assertTrue(wrong.get("material_available").getAsBoolean());
        world.overrideProposed=target.cells().getFirst().desired();
        assertEquals(1,count(world.query(target),"ready"),"a new native context can fulfill the unchanged authored orientation");
        assertTrue(world.blocks.isEmpty());
        assertEquals(2,world.inventory.getItem(0).getCount());
    }
    @Test void aRejectedClearReportsTheActualConditionInsteadOfInventingAnAuthorizationRequest() {
        var world=new World() {
            @Override public String clearReason(BuildTarget.Cell cell) { return "body_support"; }
        };
        world.blocks.put(new BlockPos(0,90,0),Blocks.DIRT.defaultBlockState());
        world.inventory.setItem(0,new ItemStack(Items.STONE,1));
        var row=world.query(cells("[{\"x\":0,\"y\":0,\"z\":0,\"block\":\"stone\"}]"))
                .getAsJsonArray("cells").get(0).getAsJsonObject();
        assertEquals("needs_clear",row.get("state").getAsString());
        assertFalse(row.get("clear_allowed").getAsBoolean());
        assertEquals("body_support",row.get("clear_reason").getAsString());
        assertFalse(row.has("authorization_id"));
    }
    @BeforeAll static void bootstrap() { com.neko.mcbot.server.BlockActionFixtures.bootstrap(); }

    @Test void constructionIdentityIgnoresUnrelatedChunkVersionsButChangesWithMaterialsTargetOrWorkContext() {
        String[] stamp={"a"}, context={"standing"};
        var world=new World() {
            @Override public JsonObject terrain() {
                return JsonParser.parseString("{\"dimension\":\"fixture:world\",\"chunks\":{\"0,0\":\""+stamp[0]+"\"}}").getAsJsonObject();
            }
            @Override public String workContext() { return context[0]; }
        };
        var target=cells("[{\"x\":0,\"y\":0,\"z\":0,\"block\":\"stone\"}]");
        var before=world.query(target);
        stamp[0]="b";
        var chunkChanged=world.query(target);
        assertNotEquals(before.get("observation_key"),chunkChanged.get("observation_key"));
        assertEquals(before.get("work_observation_key"),chunkChanged.get("work_observation_key"));
        assertEquals(before.get("progress_observation_key"),chunkChanged.get("progress_observation_key"));
        world.inventory.setItem(0,new ItemStack(Items.STONE,1));
        var stocked=world.query(target);
        assertNotEquals(before.get("work_observation_key"),stocked.get("work_observation_key"));
        context[0]="moved or equipped";
        var moved=world.query(target);
        assertNotEquals(stocked.get("work_observation_key"),moved.get("work_observation_key"));
        assertEquals(stocked.get("progress_observation_key"),moved.get("progress_observation_key"),
                "moving or changing the selected hand is not progress on the drawing");
        world.blocks.put(new BlockPos(0,90,0),Blocks.STONE.defaultBlockState());
        assertNotEquals(moved.get("work_observation_key"),world.query(target).get("work_observation_key"));
        assertNotEquals(moved.get("progress_observation_key"),world.query(target).get("progress_observation_key"));
    }

    @Test void designComponentsReportOmittedDoorsAndCountPairedFurnitureOnlyOnce() {
        var world=new World();
        var shell=world.query(cells("[{\"x\":0,\"y\":0,\"z\":0,\"block\":\"stone\"}]"));
        assertEquals(0,shell.getAsJsonObject("design_components").get("doors").getAsInt());
        var target=cells("""
                [{"x":0,"y":0,"z":0,"block":"oak_door","properties":[
                {"name":"half","value":"lower"},{"name":"facing","value":"north"},{"name":"hinge","value":"left"}]},
                {"x":0,"y":1,"z":0,"block":"oak_door","properties":[
                {"name":"half","value":"upper"},{"name":"facing","value":"north"},{"name":"hinge","value":"left"}]},
                {"x":2,"y":0,"z":0,"block":"crafting_table"},
                {"x":3,"y":0,"z":0,"block":"chest"},
                {"x":4,"y":0,"z":0,"block":"torch"},
                {"x":5,"y":0,"z":0,"block":"oak_stairs"},
                {"x":6,"y":0,"z":0,"block":"oak_slab"}]
                """);
        var parts=world.query(target).getAsJsonObject("design_components");
        assertEquals(1,parts.get("doors").getAsInt());
        assertEquals(0,parts.get("beds").getAsInt());
        for(String key:List.of("crafting_tables","containers","lights","stairs","slabs"))
            assertEquals(1,parts.get(key).getAsInt(),key);
        assertTrue(world.blocks.isEmpty());
    }

    static class World implements BuildSurvey.Access, BlockPlacement.Access {
        final net.minecraft.world.entity.player.Inventory inventory = com.neko.mcbot.server.BlockActionFixtures.inventory();
        final HashMap<BlockPos, BlockState> blocks = new HashMap<>();
        final Set<BlockPos> unknown = new HashSet<>(), protectedCells = new HashSet<>(), far = new HashSet<>(), outside = new HashSet<>();
        java.util.function.BiFunction<BlockPos, Direction, ServerTool.Result> prepare = (p, d) -> null;
        BlockState proposed;
        BlockState overrideProposed;
        int queries, prepares, snapshots;
        boolean terrainChanged, terrainUnknown;
        @Override public BlockState state(BlockPos pos) {
            queries++;
            return unknown.contains(pos) ? null : blocks.getOrDefault(pos, Blocks.AIR.defaultBlockState());
        }
        @Override public boolean inBounds(BlockPos pos) { return !outside.contains(pos); }
        @Override public boolean protectedCell(BlockPos pos) { return protectedCells.contains(pos); }
        @Override public boolean near(BlockPos pos) { return !far.contains(pos); }
        @Override public BlockPlacement.Access placement() { return this; }
        @Override public JsonObject terrain() {
            return JsonParser.parseString("""
                    {"dimension":"fixture:world","chunks":{"0,0":%s}}
                    """.formatted(terrainUnknown ? "null" : "\"" + (terrainChanged && snapshots++ > 0 ? "b" : "a") + "\""))
                    .getAsJsonObject();
        }
        @Override public ServerTool.Result guard(BlockPos pos) { return null; }
        @Override public Container inventory() { return inventory; }
        @Override public ServerTool.Result sight(BlockPos pos, Direction face) { return null; }
        @Override public ServerTool.Result prepare(BlockPos pos, ItemStack stack, Direction face) {
            prepares++;
            proposed = overrideProposed == null ? ((BlockItem) stack.getItem()).getBlock().defaultBlockState() : overrideProposed;
            return prepare.apply(pos, face);
        }
        @Override public BlockState proposedState() { return proposed; }
        @Override public InteractionResult place(BlockPos pos, ItemStack stack, Direction face) {
            fail("query must never execute placement");
            return InteractionResult.FAIL;
        }
        JsonObject query(BuildTarget target) { return BuildSurvey.query(target, this, 0, 0, () -> 0).data(); }
    }

    private static BuildTarget cells(String cells) {
        return BuildTarget.parse(JsonParser.parseString("""
                {"anchor":{"x":0,"y":90,"z":0},"cells":%s}
                """.formatted(cells)).getAsJsonObject());
    }
    private static int count(JsonObject data, String state) { return data.getAsJsonObject("counts").get(state).getAsInt(); }
    private static JsonObject material(JsonObject data) { return data.getAsJsonArray("materials").get(0).getAsJsonObject(); }

    @Test void currentPageSurfaceObservationsDoNotChangeTargetsReachClassificationOrFinalVerification() {
        var surfaces=new java.util.ArrayList<BlockPos>();
        var world=new World() {
            @Override public JsonObject surface(BlockPos pos) {
                surfaces.add(pos);
                var data=new JsonObject(); data.addProperty("feet_y",89.5); return data;
            }
        };
        var target=BuildTarget.parse(JsonParser.parseString("""
                {"anchor":{"x":0,"y":90,"z":0},"fill":{"from":{"x":0,"y":0,"z":0},
                "to":{"x":7,"y":0,"z":3},"block":"stone"}}
                """).getAsJsonObject());
        target.cells().forEach(c->world.far.add(c.pos()));
        var input=target.input().deepCopy();
        var query=world.query(target);
        assertEquals(32,count(query,"out_of_reach"));
        assertEquals(16,surfaces.size());
        assertTrue(query.getAsJsonArray("cells").get(0).getAsJsonObject().has("surface"));
        assertEquals(input,target.input());
        surfaces.clear();
        BuildSurvey.queryForWork(target,world,0,()->0);
        assertTrue(surfaces.isEmpty(),"native work assessment does not repeat advisory surface searches");
        BuildSurvey.verify(target,world,0,()->0);
        assertTrue(surfaces.isEmpty(),"final whole-target verification does not run advisory surface searches");
        var next=BuildSurvey.query(target,world,16,0,()->0).data();
        assertEquals(16,surfaces.size());
        assertEquals(target.cells().get(16).pos(),surfaces.getFirst());
        assertFalse(next.get("goal_satisfied").getAsBoolean());
        assertEquals(0,world.prepares);
    }
    @Test void actualSatisfiedCellsNeedNoMaterialPreflightOrActionAndExternalChangesReappear() {
        var target = cells("[{\"x\":0,\"y\":0,\"z\":0,\"block\":\"stone\"}]");
        var world = new World();
        world.blocks.put(new BlockPos(0, 90, 0), Blocks.STONE.defaultBlockState());
        var matched = world.query(target);
        assertTrue(matched.get("goal_satisfied").getAsBoolean());
        assertEquals(0, material(matched).get("required").getAsInt());
        assertEquals(0, world.prepares);
        world.blocks.clear();
        var changed = world.query(target);
        assertFalse(changed.get("goal_satisfied").getAsBoolean());
        assertEquals(1, count(changed, "missing_material"));
        assertEquals(1, material(changed).get("required").getAsInt());
        assertEquals(0, material(changed).get("consumed").getAsInt());
    }

    @Test void goalFingerprintTracksAllActualCellsButIgnoresInventoryAndUnrelatedChunkVersions() {
        var world=new World();
        var target=cells("[{\"x\":0,\"y\":0,\"z\":0,\"block\":\"stone\"},"
                + "{\"x\":1,\"y\":0,\"z\":0,\"block\":\"stone\"}]");
        var before=BuildSurvey.verify(target,world,0,()->0).data();
        world.inventory.setItem(0,new ItemStack(Items.STONE,12));
        var inventory=BuildSurvey.verify(target,world,0,()->0).data();
        assertEquals(before.get("goal_observation_key"),inventory.get("goal_observation_key"));
        assertNotEquals(before.get("observation_key"),inventory.get("observation_key"));
        world.blocks.put(new BlockPos(0,90,0),Blocks.STONE.defaultBlockState());
        var first=BuildSurvey.verify(target,world,0,()->0).data();
        world.blocks.remove(new BlockPos(0,90,0));
        world.blocks.put(new BlockPos(1,90,0),Blocks.STONE.defaultBlockState());
        var second=BuildSurvey.verify(target,world,0,()->0).data();
        assertNotEquals(first.get("goal_observation_key"),second.get("goal_observation_key"));
        assertEquals(0,world.prepares,"verification must not run placement preflights");
        var furnace=cells("[{\"x\":0,\"y\":0,\"z\":0,\"block\":\"furnace\"}]");
        world.blocks.put(new BlockPos(0,90,0),Blocks.FURNACE.defaultBlockState());
        var unlit=BuildSurvey.verify(furnace,world,0,()->0).data();
        world.blocks.put(new BlockPos(0,90,0),Blocks.FURNACE.defaultBlockState()
                .setValue(BlockStateProperties.LIT,true));
        assertEquals(unlit.get("goal_observation_key"),
                BuildSurvey.verify(furnace,world,0,()->0).data().get("goal_observation_key"),
                "state properties absent from the authored condition cannot buy another stage");
    }

    @Test void finiteAllocationDoesNotPromiseOneRemainingStackToEveryCellOrConsumeIt() {
        var target = cells("""
                [{"x":0,"y":0,"z":0,"block":"stone"},{"x":1,"y":0,"z":0,"block":"stone"},
                {"x":2,"y":0,"z":0,"block":"stone"}]
                """);
        var world = new World();
        var stack = new ItemStack(Items.STONE, 2);
        world.inventory.setItem(5, stack);
        var data = world.query(target);
        assertEquals(2, count(data, "ready"));
        assertEquals(1, count(data, "missing_material"));
        assertEquals(3, material(data).get("required").getAsInt());
        assertEquals(2, material(data).get("available").getAsInt());
        assertEquals(2, material(data).get("allocated").getAsInt());
        assertEquals(1, material(data).get("shortfall").getAsInt());
        assertSame(stack, world.inventory.getItem(5));
        assertEquals(2, stack.getCount());
        assertEquals(2, world.prepares);
    }

    @Test void inventoryUsesOnlyStorageAndPreservesComponentsInsteadOfSilentlyStrippingThem() {
        var world = new World();
        world.inventory.setItem(36, new ItemStack(Items.STONE, 32));
        world.inventory.setItem(40, new ItemStack(Items.STONE, 32));
        var named = new ItemStack(Items.STONE, 7);
        named.set(DataComponents.CUSTOM_NAME, Component.literal("keep this stack"));
        world.inventory.setItem(1, named);
        world.inventory.setItem(20, new ItemStack(Items.STONE, 3));
        assertEquals(3, BuildInventory.available(world.inventory, Items.STONE));
        assertEquals(20, BuildInventory.slot(world.inventory, Items.STONE));
        var data = world.query(cells("[{\"x\":0,\"y\":0,\"z\":0,\"block\":\"stone\"}]"));
        assertEquals(3, material(data).get("available").getAsInt());
        assertEquals(7, named.getCount());
        assertTrue(named.has(DataComponents.CUSTOM_NAME));
    }

    @Test void classificationsKeepClearProtectUnknownFarAndStateConflictDistinct() {
        var target = cells("""
                [{"x":0,"y":0,"z":0,"block":"air"},{"x":1,"y":0,"z":0,"block":"stone"},
                {"x":2,"y":0,"z":0,"block":"stone"},{"x":3,"y":0,"z":0,"block":"stone"},
                {"x":4,"y":0,"z":0,"block":"oak_log","properties":[{"name":"axis","value":"x"}]},
                {"x":5,"y":0,"z":0,"block":"stone"}]
                """);
        var world = new World();
        world.blocks.put(new BlockPos(0, 90, 0), Blocks.DIRT.defaultBlockState());
        world.protectedCells.add(new BlockPos(1, 90, 0));
        world.unknown.add(new BlockPos(2, 90, 0));
        world.far.add(new BlockPos(3, 90, 0));
        world.blocks.put(new BlockPos(4, 90, 0), Blocks.OAK_LOG.defaultBlockState());
        world.outside.add(new BlockPos(5, 90, 0));
        var data = world.query(target);
        for (String state : java.util.List.of("needs_clear", "protected", "unknown", "out_of_reach", "state_conflict", "out_of_scope"))
            assertEquals(1, count(data, state), state);
        assertFalse(data.get("observation_complete").getAsBoolean());
        assertFalse(data.get("material_bill_complete").getAsBoolean());
        assertFalse(data.get("goal_satisfied").getAsBoolean());
        assertEquals(0, world.prepares);
    }

    @Test void actualNativePredictionMustMeetExplicitPropertiesAndMayUseAnotherLegalFace() {
        var target = cells("""
                [{"x":0,"y":0,"z":0,"block":"oak_log","properties":[{"name":"axis","value":"x"}]}]
                """);
        var world = new World();
        world.inventory.setItem(0, new ItemStack(Items.OAK_LOG));
        assertEquals(1, count(world.query(target), "state_conflict"));
        assertEquals(6, world.prepares);
        world.prepare = (p, face) -> {
            if (face == Direction.EAST) world.proposed = Blocks.OAK_LOG.defaultBlockState()
                    .setValue(BlockStateProperties.AXIS, Direction.Axis.X);
            return null;
        };
        var data = world.query(target);
        assertEquals(1, count(data, "ready"));
        assertEquals("east", data.getAsJsonArray("cells").get(0).getAsJsonObject().get("face").getAsString());
        assertTrue(world.blocks.isEmpty());
        assertEquals(1, world.inventory.getItem(0).getCount());
    }

    @Test void nativeSupportAndCollisionFailuresAreNotReportedAsMissingMaterialOrSatisfied() {
        var target = cells("[{\"x\":0,\"y\":0,\"z\":0,\"block\":\"stone\"}]");
        var world = new World();
        world.inventory.setItem(0, new ItemStack(Items.STONE, 2));
        world.prepare = (p, face) -> new ServerTool.Result(false, "TARGET_BLOCKED:support",
                JsonParser.parseString("{\"placement_failure\":\"unsupported_state\"}").getAsJsonObject());
        assertEquals(1, count(world.query(target), "needs_support"));
        world.prepare = (p, face) -> new ServerTool.Result(false, "TARGET_BLOCKED:entity",
                JsonParser.parseString("{\"placement_failure\":\"entity_collision\"}").getAsJsonObject());
        var blocked = world.query(target);
        assertEquals(1, count(blocked, "blocked"));
        assertEquals(0, material(blocked).get("allocated").getAsInt());
        assertFalse(blocked.get("goal_satisfied").getAsBoolean());
    }

    @Test void unsupportedAndUnknownTargetsCannotBecomeDoneEvenWhenAllOtherWorkIsEmpty() {
        var target = cells("""
                [{"x":0,"y":0,"z":0,"block":"red_bed"},{"x":1,"y":0,"z":0,"block":"oak_slab",
                "properties":[{"name":"type","value":"double"}]}]
                """);
        var world = new World();
        world.blocks.put(new BlockPos(0, 90, 0), Blocks.RED_BED.defaultBlockState());
        var data = world.query(target);
        assertEquals(2, count(data, "unsupported"));
        assertFalse(data.get("goal_satisfied").getAsBoolean());
        assertFalse(data.get("material_bill_complete").getAsBoolean());
        assertEquals(2, material(data).get("required").getAsInt());
        assertEquals(0, world.prepares);
    }

    @Test void wholeDemandAndGoalCountsAreSeparateFromBoundedPageWorkAndAdvisoryAllocations() {
        var target = BuildTarget.parse(JsonParser.parseString("""
                {"anchor":{"x":0,"y":90,"z":0},"fill":{"from":{"x":0,"y":0,"z":0},
                "to":{"x":7,"y":1,"z":7},"block":"stone"}}
                """).getAsJsonObject());
        var world = new World();
        world.inventory.setItem(0, new ItemStack(Items.STONE, 64));
        var first = world.query(target);
        assertEquals(128, first.get("total").getAsInt());
        assertEquals(128, material(first).get("required").getAsInt());
        assertEquals(16, first.getAsJsonArray("cells").size());
        assertEquals(16, count(first, "ready"));
        assertEquals(112, count(first, "not_assessed"));
        assertEquals(16, first.get("next_offset").getAsInt());
        assertFalse(first.get("work_assessment_complete").getAsBoolean());
        var last = BuildSurvey.query(target, world, 112, 0, () -> 0).data();
        assertEquals(-1, last.get("next_offset").getAsInt());
        assertEquals(first.get("target_revision"), last.get("target_revision"));
        assertEquals(128, material(last).get("required").getAsInt());
        assertEquals(64, world.inventory.getItem(0).getCount());
    }

    @Test void timeBudgetAndNativeCheckCapDeclareUnassessedWorkWithoutGrantingExtraAttempts() {
        var target = BuildTarget.parse(JsonParser.parseString("""
                {"anchor":{"x":0,"y":90,"z":0},"fill":{"from":{"x":0,"y":0,"z":0},
                "to":{"x":3,"y":0,"z":3},"block":"stone"}}
                """).getAsJsonObject());
        var world = new World();
        world.inventory.setItem(0, new ItemStack(Items.STONE, 32));
        world.overrideProposed = Blocks.DIRT.defaultBlockState();
        var cap = world.query(target);
        assertEquals(96, world.prepares);
        assertEquals(96, cap.get("work_preflights").getAsInt());
        assertEquals(16, count(cap, "state_conflict"));
        var time = new AtomicLong();
        world.prepare = (p, f) -> { time.set(BuildSurvey.MAX_NANOS); return null; };
        var timed = BuildSurvey.query(target, world, 0, 0, time::get).data();
        assertTrue(timed.get("budget_exhausted").getAsBoolean());
        assertEquals(16, count(timed, "not_assessed"));
        assertFalse(timed.get("work_assessment_complete").getAsBoolean());
    }

    @Test void terrainMutationOrUnknownHookBlocksStableFinalAcceptance() {
        var target = cells("[{\"x\":0,\"y\":0,\"z\":0,\"block\":\"stone\"}]");
        for (boolean missing : new boolean[]{false, true}) {
            var world = new World();
            world.blocks.put(new BlockPos(0, 90, 0), Blocks.STONE.defaultBlockState());
            world.terrainChanged = !missing;
            world.terrainUnknown = missing;
            var data = world.query(target);
            assertEquals(1, count(data, "satisfied"), "observed matches do not establish a stable final snapshot");
            assertFalse(data.get("spatial_stable").getAsBoolean());
            assertFalse(data.get("goal_satisfied").getAsBoolean());
            assertFalse(data.get("observation_complete").getAsBoolean());
        }
    }

    @Test void materialPagesExposeEveryTypeWithoutTruncatingWholeTargetDemand() {
        var items = java.util.List.of(Items.WHITE_WOOL, Items.ORANGE_WOOL, Items.MAGENTA_WOOL,
                Items.LIGHT_BLUE_WOOL, Items.YELLOW_WOOL, Items.LIME_WOOL, Items.PINK_WOOL, Items.GRAY_WOOL,
                Items.LIGHT_GRAY_WOOL, Items.CYAN_WOOL, Items.PURPLE_WOOL, Items.BLUE_WOOL, Items.BROWN_WOOL,
                Items.GREEN_WOOL, Items.RED_WOOL, Items.BLACK_WOOL, Items.STONE, Items.DIRT, Items.ANDESITE,
                Items.COBBLESTONE);
        var input = new JsonObject();
        input.add("anchor", JsonParser.parseString("{\"x\":0,\"y\":90,\"z\":0}"));
        var selected = new com.google.gson.JsonArray();
        var world = new World();
        for (int i = 0; i < items.size(); i++) {
            var cell = new JsonObject();
            cell.addProperty("x", i); cell.addProperty("y", 0); cell.addProperty("z", 0);
            cell.addProperty("block", net.minecraft.core.registries.BuiltInRegistries.BLOCK
                    .getKey(((BlockItem) items.get(i)).getBlock()).toString());
            selected.add(cell);
            world.inventory.setItem(i, new ItemStack(items.get(i), 2));
        }
        input.add("cells", selected);
        var target = BuildTarget.parse(input);
        var first = world.query(target);
        var tail = BuildSurvey.query(target, world, 16, 16, () -> 0).data();
        assertEquals(20, first.get("material_types").getAsInt());
        assertEquals(16, first.get("material_next_offset").getAsInt());
        assertEquals(-1, tail.get("material_next_offset").getAsInt());
        var seen = new HashSet<String>();
        for (var page : java.util.List.of(first, tail))
            for (var entry : page.getAsJsonArray("materials")) {
                assertTrue(seen.add(entry.getAsJsonObject().get("item").getAsString()));
                assertEquals(1, entry.getAsJsonObject().get("required").getAsInt());
            }
        assertEquals(20, seen.size());
        assertTrue(first.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length < 24000);
        assertTrue(tail.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length < 24000);
    }
}
