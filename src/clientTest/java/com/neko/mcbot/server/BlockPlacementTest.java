package com.neko.mcbot.server;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.SlabType;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static com.neko.mcbot.server.BlockActionFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

class BlockPlacementTest {
    private static final BlockPos POS = new BlockPos(2, 90, -4);
    @BeforeAll static void registriesOnly() { bootstrap(); }

    private Placement access() {
        Placement access = new Placement();
        access.inventory.setItem(13, new ItemStack(Items.COBBLESTONE, 3));
        return access;
    }

    @Test void sightRefusalNeverPlacesOrConsumesTheSourceStack() {
        Placement access = access();
        access.sightFailure = new ServerTool.Result(false, "TARGET_BLOCKED:wall", null);
        ItemStack before = access.inventory.getItem(13).copy();
        var result = BlockPlacement.place(access, POS, 13, Direction.UP);
        assertTrue(result.feedback().startsWith("TARGET_BLOCKED:"));
        assertFalse(result.data().get("changed").getAsBoolean());
        assertEquals(0, result.data().get("consumed_count").getAsInt());
        assertEquals(0, access.calls);
        assertTrue(ItemStack.matches(before, access.inventory.getItem(13)));
    }

    @Test void pathPlacementSelectsAVisibleSideAtTheSameTargetAndPlacesOnlyOnce() {
        var access = access();
        var seen = new java.util.ArrayList<Direction>();
        var grid = new BlockAimTest.Grid();
        grid.blocks.clear();
        grid.eye = new net.minecraft.world.phys.Vec3(4.5, 91.62, -3.5);
        grid.blocks.put(POS.west(), Blocks.STONE.defaultBlockState());
        access.sightAt = (pos, face) -> {
            seen.add(face);
            var aim = BlockAim.placementContact(grid, pos.relative(face.getOpposite()), face, 4.5);
            return aim.ok() ? null : new ServerTool.Result(false, aim.failure(), null);
        };
        var result = BlockPlacement.placeAttached(access, POS, 13);
        assertTrue(result.ok(), result.feedback());
        assertEquals(POS, access.target);
        assertEquals(Direction.EAST, access.face);
        assertEquals(1, access.calls);
        assertEquals(2, access.inventory.getItem(13).getCount());
        assertEquals(6, seen.size(), "five face probes and a fresh execution check");
    }

    @Test void failedPathFaceSearchIsBoundedAndNeverRetriesAnActionWithEffects() {
        var access = access();
        var probes = new java.util.ArrayList<Direction>();
        access.sightAt = (pos, face) -> {
            probes.add(face);
            return new ServerTool.Result(false, "TARGET_BLOCKED:no face", null);
        };
        assertFalse(BlockPlacement.placeAttached(access, POS, 13).ok());
        assertEquals(6, probes.size());
        assertEquals(0, access.calls);
        access.sightAt = (pos, face) -> null;
        access.result = InteractionResult.FAIL;
        assertFalse(BlockPlacement.placeAttached(access, POS, 13).ok());
        assertEquals(1, access.calls, "native failure with consumption must not try another face");
    }

    @Test void contactDiagnosticsSurviveQueryAndExecutionAndSightRefusalsKeepTheirReason() {
        var access = access();
        access.contactData = new com.google.gson.JsonObject();
        access.contactData.addProperty("face", "west");
        access.contactData.addProperty("block", "minecraft:stone");
        var query = BlockPlacement.query(access, POS, 13, Direction.WEST);
        assertEquals(access.contactData, query.data().getAsJsonObject("placement_contact"));
        var placed = BlockPlacement.place(access, POS, 13, Direction.WEST);
        assertEquals(access.contactData, placed.data().getAsJsonObject("placement_contact"));
        var reason = new com.google.gson.JsonObject();
        reason.addProperty("placement_failure", "no_clickable_face");
        access.sightFailure = new ServerTool.Result(false, "TARGET_BLOCKED:no contact", reason);
        var refused = BlockPlacement.query(access, POS, 13, Direction.UP);
        assertFalse(refused.data().get("preflight_passed").getAsBoolean());
        assertEquals("no_clickable_face", refused.data().get("placement_failure").getAsString());
        assertFalse(refused.data().has("placement_contact"));
    }

    private static final class Site implements BlockPlacement.Site {
        BlockState current = Blocks.SNOW.defaultBlockState();
        BlockState placed = Blocks.CRAFTING_TABLE.defaultBlockState();
        boolean replaceable = true, survives = true, clear = true;
        net.minecraft.world.phys.AABB body = new net.minecraft.world.phys.AABB(0, 90, 0, .6, 91.8, .6);
        @Override public BlockState current() { return current; }
        @Override public boolean replaceable() { return replaceable; }
        @Override public BlockState placed() { return placed; }
        @Override public boolean survives() { return survives; }
        @Override public java.util.List<net.minecraft.world.phys.AABB> collision() {
            return placed.getCollisionShape(net.minecraft.world.level.EmptyBlockGetter.INSTANCE, POS)
                    .move(POS.getX(), POS.getY(), POS.getZ()).toAabbs();
        }
        @Override public net.minecraft.world.phys.AABB body() { return body; }
        @Override public boolean entityClear() { return clear; }
    }

    @Test void snowReplacementAndExactShapeCollisionHaveSeparateActionableReasons() {
        var site = new Site();
        assertNull(BlockPlacement.checkSite(POS, site), "native replaceability admits snow without harvesting it");
        site.body = new net.minecraft.world.phys.AABB(2.2, 90, -3.8, 2.8, 91.8, -3.2);
        var self = BlockPlacement.checkSite(POS, site);
        assertEquals("self_collision", self.data().get("placement_failure").getAsString());
        assertTrue(self.feedback().contains("exact=true"));
        site.placed = Blocks.OAK_SLAB.defaultBlockState();
        site.body = site.body.move(0, .5, 0);
        assertNull(BlockPlacement.checkSite(POS, site), "standing above a lower slab does not intersect its shape");
        site.clear = false;
        assertEquals("entity_collision", BlockPlacement.checkSite(POS, site)
                .data().get("placement_failure").getAsString());
        site.replaceable = false;
        assertEquals("occupied_block", BlockPlacement.checkSite(POS, site)
                .data().get("placement_failure").getAsString());
        site.replaceable = true;
        site.survives = false;
        assertEquals("unsupported_state", BlockPlacement.checkSite(POS, site)
                .data().get("placement_failure").getAsString());
    }

    @Test void nativePreflightRefusalPreservesEvidenceAndNeverPlacesOrConsumes() {
        Placement access = access();
        var data = new com.google.gson.JsonObject();
        data.addProperty("placement_failure", "self_collision");
        access.prepareFailure = new ServerTool.Result(false, "TARGET_BLOCKED:move first", data);
        var result = BlockPlacement.place(access, POS, 13, Direction.UP);
        assertFalse(result.ok());
        assertEquals("self_collision", result.data().get("placement_failure").getAsString());
        assertFalse(result.data().get("changed").getAsBoolean());
        assertEquals(0, result.data().get("consumed_count").getAsInt());
        assertEquals(0, access.calls);
        assertEquals(3, access.inventory.getItem(13).getCount());
    }

    @Test void queryUsesTheExecutionPreflightAndFindsBoundedAlternativesWithoutPlacing() {
        Placement access = access();
        var reason = new com.google.gson.JsonObject();
        reason.addProperty("placement_failure", "self_collision");
        var blocked = new ServerTool.Result(false, "TARGET_BLOCKED:self", reason);
        access.prepareAt = pos -> pos.equals(POS) ? blocked : null;
        var query = BlockPlacement.query(access, POS, 13, Direction.WEST);
        assertTrue(query.ok(), query.feedback());
        assertFalse(query.data().get("preflight_passed").getAsBoolean());
        assertEquals("self_collision", query.data().get("placement_failure").getAsString());
        var alternatives = query.data().getAsJsonArray("alternatives");
        assertEquals(6, alternatives.size());
        assertEquals(7, query.data().get("checked_positions").getAsInt());
        assertEquals(0, access.calls);
        assertEquals(3, access.inventory.getItem(13).getCount());
        assertFalse(query.data().get("changed").getAsBoolean());
        assertEquals(0, query.data().get("consumed_count").getAsInt());
        assertFalse(BlockPlacement.place(access, POS, 13, Direction.WEST).ok(),
                "query never silently substitutes an alternative for the exact target");
        var next = alternatives.get(0).getAsJsonObject();
        assertEquals("west", next.get("face").getAsString());
        var nextPos = new BlockPos(next.get("x").getAsInt(), next.get("y").getAsInt(), next.get("z").getAsInt());
        access.prepareAt = pos -> blocked;
        assertFalse(BlockPlacement.place(access, nextPos, 13, Direction.WEST).ok(),
                "a stale suggestion is checked again before any consumption");
        assertEquals(0, access.calls);
        access.prepareAt = pos -> pos.equals(nextPos) ? null : blocked;
        assertTrue(BlockPlacement.place(access, nextPos, 13, Direction.WEST).ok());
        assertEquals(1, access.calls);
        assertEquals(2, access.inventory.getItem(13).getCount());
    }

    @Test void fullyBlockedQueryHasASeventyFivePositionCapAndNoNativeEffects() {
        Placement access = access();
        access.prepareFailure = new ServerTool.Result(false, "TARGET_BLOCKED:wall", null);
        var query = BlockPlacement.query(access, POS, 13, Direction.UP);
        assertTrue(query.ok());
        assertEquals(75, query.data().get("checked_positions").getAsInt());
        assertTrue(query.data().getAsJsonArray("alternatives").isEmpty());
        assertEquals(0, access.calls);
        assertEquals(3, access.inventory.getItem(13).getCount());
    }

    @Test void successfulQueryIsStillAnObservationAndChecksNativeConditionsAgainOnExecution() {
        Placement access = access();
        var query = BlockPlacement.query(access, POS, 13, Direction.UP);
        assertTrue(query.data().get("query").getAsBoolean());
        assertTrue(query.data().get("preflight_passed").getAsBoolean());
        assertTrue(query.data().get("native_validation_pending").getAsBoolean());
        assertEquals(1, query.data().get("checked_positions").getAsInt());
        assertEquals(0, access.calls);
        access.sightFailure = new ServerTool.Result(false, "TARGET_LOST:world changed", null);
        assertFalse(BlockPlacement.place(access, POS, 13, Direction.UP).ok());
        assertEquals(0, access.calls);
    }

    @Test void projectedLookHasStableCardinalAndVerticalDirectionsWithoutMovingAnEntity() {
        var eastUp = BlockPlacement.Look.toward(new net.minecraft.world.phys.Vec3(3, 5, 1));
        assertEquals(Direction.EAST, eastUp.horizontal());
        assertEquals(Direction.UP, eastUp.vertical());
        assertEquals(Direction.UP, eastUp.order()[0]);
        var northDown = BlockPlacement.Look.toward(new net.minecraft.world.phys.Vec3(0, -1, -4));
        assertEquals(Direction.NORTH, northDown.horizontal());
        assertEquals(Direction.DOWN, northDown.vertical());
        assertEquals(Direction.NORTH, northDown.order()[0]);
        assertEquals(6, java.util.Set.of(northDown.order()).size());
    }

    @Test void projectedLookMatchesVanillaLookAtAndDirectionOrderIncludingAxisTies() {
        var marker = new net.minecraft.world.entity.Marker(net.minecraft.world.entity.EntityType.MARKER, null);
        for (int x : new int[]{-4, -1, 0, 1, 4}) for (int y : new int[]{-4, -1, 0, 1, 4})
            for (int z : new int[]{-4, -1, 0, 1, 4}) {
                if (x == 0 && y == 0 && z == 0) continue;
                var direction = new net.minecraft.world.phys.Vec3(x, y, z);
                var look = BlockPlacement.Look.toward(direction);
                marker.lookAt(net.minecraft.commands.arguments.EntityAnchorArgument.Anchor.EYES,
                        marker.getEyePosition().add(direction));
                assertEquals(marker.getYRot(), look.yaw(), direction.toString());
                assertEquals(marker.getDirection(), look.horizontal(), direction.toString());
                assertEquals(Direction.getFacingAxis(marker, Direction.Axis.Y), look.vertical(), direction.toString());
                assertArrayEquals(Direction.orderedByNearest(marker), look.order(), direction.toString());
            }
    }

    @Test void originalFullStackFaceAndExactTargetCrossThePlacementBoundary() {
        Placement access = access();
        ItemStack original = access.inventory.getItem(13);
        original.set(DataComponents.CUSTOM_NAME, Component.literal("keep"));
        access.inventory.setSelectedSlot(4);
        access.inventory.setItem(4, new ItemStack(Items.IRON_PICKAXE));
        access.inventory.setItem(40, new ItemStack(Items.TORCH, 7));
        var result = BlockPlacement.place(access, POS, 13, Direction.WEST);
        assertTrue(result.ok());
        assertSame(original, access.supplied);
        assertEquals(POS, access.target);
        assertEquals(Direction.WEST, access.face);
        assertEquals(2, original.getCount());
        assertEquals("keep", original.get(DataComponents.CUSTOM_NAME).getString());
        assertEquals(4, access.inventory.getSelectedSlot());
        assertEquals(7, access.inventory.getItem(40).getCount());
        assertTrue(access.inventory.getSelectedItem().is(Items.IRON_PICKAXE));
        assertEquals("minecraft:cobblestone", result.data().get("placed").getAsString());
        assertEquals(1, result.data().get("consumed_count").getAsInt());
        assertFalse(result.feedback().contains("keep"));
    }

    @Test void guardRejectsBeforeReadingWorldOrMutatingInventory() {
        Placement access = access();
        access.denied = new ServerTool.Result(false, "TARGET_LOST:unloaded", null);
        assertSame(access.denied, BlockPlacement.place(access, POS, 13, Direction.UP));
        assertEquals(0, access.calls);
        assertEquals(0, access.reads);
        assertEquals(3, access.inventory.getItem(13).getCount());
    }

    @Test void missingNonBlockEquipmentAndRedirectingItemsNeverInvokePlacement() {
        Placement access = access();
        access.inventory.setItem(40, new ItemStack(Items.COBBLESTONE));
        access.inventory.setItem(10, new ItemStack(Items.STICK));
        access.inventory.setItem(11, new ItemStack(Items.SCAFFOLDING));
        for (int slot : new int[]{-1, 36, 40, 43, 0, 10, 11}) {
            assertFalse(BlockPlacement.place(access, POS, slot, Direction.UP).ok());
        }
        assertEquals(0, access.calls);
        assertEquals(3, access.inventory.getItem(13).getCount());
    }

    @Test void supportedVanillaClassesAreExplicitAndSpecialSignContextsDenied() {
        for (var item : new net.minecraft.world.item.Item[]{Items.COBBLESTONE, Items.RED_BED,
                Items.OAK_DOOR, Items.TORCH}) {
            assertTrue(BlockPlacement.supported((BlockItem) item));
        }
        assertFalse(BlockPlacement.supported((BlockItem) Items.SCAFFOLDING));
        assertFalse(BlockPlacement.supported((BlockItem) Items.OAK_SIGN));
        assertFalse(BlockPlacement.supported((BlockItem) Items.OAK_HANGING_SIGN));
    }

    @Test void vanillaFailureWithNoMutationKeepsOriginalStack() {
        Placement access = access();
        access.result = InteractionResult.FAIL;
        access.after = access.before;
        access.consume = 0;
        ItemStack before = access.inventory.getItem(13).copy();
        var result = BlockPlacement.place(access, POS, 13, Direction.UP);
        assertFalse(result.ok());
        assertFalse(result.data().get("changed").getAsBoolean());
        assertEquals(0, result.data().get("consumed_count").getAsInt());
        assertTrue(ItemStack.matches(before, access.inventory.getItem(13)));
    }

    @Test void misleadingSuccessMustNotHideUnchangedAirOrWrongConsumption() {
        for (int scenario = 0; scenario < 4; scenario++) {
            Placement access = access();
            switch (scenario) {
                case 0 -> access.after = access.before;
                case 1 -> { access.before = Blocks.STONE.defaultBlockState(); access.after = Blocks.AIR.defaultBlockState(); }
                case 2 -> access.consume = 0;
                case 3 -> access.consume = 2;
            }
            var result = BlockPlacement.place(access, POS, 13, Direction.UP);
            assertFalse(result.ok());
            assertTrue(result.feedback().startsWith("PLACE_FAILED:"));
            assertEquals(access.consume, result.data().get("consumed_count").getAsInt());
        }
    }

    @Test void failedActionReportsObservedEffectsWithoutPretendingRollback() {
        Placement access = access();
        access.result = InteractionResult.FAIL;
        var result = BlockPlacement.place(access, POS, 13, Direction.NORTH);
        assertFalse(result.ok());
        assertTrue(result.data().get("changed").getAsBoolean());
        assertEquals(1, result.data().get("consumed_count").getAsInt());
        assertEquals(2, access.inventory.getItem(13).getCount());
        assertTrue(result.feedback().contains("已消耗 1"));
    }

    @Test void observedReplacingWaterAndSlabMergeCanSucceedWithoutAirPrecheck() {
        for (var before : new net.minecraft.world.level.block.state.BlockState[]{
                Blocks.WATER.defaultBlockState(), Blocks.SHORT_GRASS.defaultBlockState(),
                Blocks.OAK_SLAB.defaultBlockState()}) {
            Placement access = access();
            access.before = before;
            access.after = Blocks.OAK_SLAB.defaultBlockState().setValue(
                    net.minecraft.world.level.block.SlabBlock.TYPE, SlabType.DOUBLE);
            access.inventory.setItem(13, new ItemStack(Items.OAK_SLAB, 3));
            assertTrue(BlockPlacement.place(access, POS, 13, Direction.UP).ok());
            assertEquals(2, access.inventory.getItem(13).getCount());
        }
    }
}
