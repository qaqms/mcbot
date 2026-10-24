package com.neko.mcbot.server;

import com.neko.mcbot.path.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SupportPlacementTest {
    @BeforeAll static void registries() { BlockActionFixtures.bootstrap(); }

    private static final class Terrain implements DigSampler {
        final BlockAimTest.Grid world = new BlockAimTest.Grid();
        final net.minecraft.world.entity.player.Inventory inventory = BlockActionFixtures.inventory();
        final StandingGeometry geometry;
        final BlockPos target;
        boolean digging;
        int maxX = 4, maxY = 9;
        Terrain(BlockPos target, int materials) {
            this.target = target;
            world.blocks.clear();
            world.blocks.put(target, Blocks.OAK_LOG.defaultBlockState());
            if (materials > 0) inventory.setItem(0, new ItemStack(Items.COBBLESTONE, materials));
            geometry = new StandingGeometry(new StandingGeometry.Access() {
                @Override public boolean known(BlockPos pos) { return world.loaded(pos); }
                @Override public List<AABB> boxes(BlockPos pos) {
                    return StandingGeometry.collision(world.getBlockState(pos), world, pos);
                }
            }, .6, 1.8, .6);
        }
        @Override public boolean inBounds(int x, int y, int z) {
            return x >= 0 && x <= maxX && y >= -1 && y <= maxY && z == 0;
        }
        @Override public boolean known(int x, int y, int z) { return world.loaded(new BlockPos(x, y, z)); }
        @Override public NavigationGeometry geometry() { return geometry; }
        @Override public boolean passable(int x, int y, int z) {
            var pos = new BlockPos(x, y, z);
            return world.getBlockState(pos).getCollisionShape(world, pos).isEmpty();
        }
        @Override public boolean support(int x, int y, int z) {
            var pos = new BlockPos(x, y, z);
            return world.getBlockState(pos).isFaceSturdy(world, pos, Direction.UP);
        }
        @Override public double digSeconds(int x, int y, int z) {
            var pos = new BlockPos(x, y, z);
            return digging && inBounds(x, y, z) && y >= 0 && !pos.equals(target)
                    && !pos.equals(target.below()) && world.getBlockState(pos).is(Blocks.STONE) ? .3 : INFEASIBLE;
        }
        @Override public boolean placeable(int x, int y, int z) {
            var pos = new BlockPos(x, y, z);
            return inBounds(x, y, z) && !pos.equals(target) && !pos.equals(target.below()) && passable(x, y, z);
        }
        @Override public double placeCost(int x, int y, int z) { return 1; }
        @Override public int maxPlaces() { return PathMaterials.count(inventory); }
        @Override public boolean canClear(int x, int y, int z, List<Long> cells, TerrainEdits edits) {
            return NavigationWork.canClear(world, new BlockPos(x, y, z), 1.62, 4.5, cells, edits, geometry);
        }
        @Override public boolean canPlaceFrom(int x, int y, int z, long cell, TerrainEdits edits) {
            var foot = geometry.foot(x, y, z, edits);
            if (foot == null) return false;
            var pos = new BlockPos(DigAStar.unpackX(cell), DigAStar.unpackY(cell), DigAStar.unpackZ(cell));
            int slot = PathMaterials.find(inventory);
            return slot >= 0 && SupportPlacement.canPlan(world, world, pos,
                    new Vec3(x + .5, foot.height() + 1.62, z + .5), geometry.body(x, foot.height(), z),
                    inventory.getItem(slot), 4.5, edits, p -> true, face -> true, box -> true);
        }
        DigAStar route(int digs) {
            var goal = NavigationWork.goal(world, target, 1.62, 4.5, false, p -> false, geometry);
            var route = new DigAStar(this, 0, 0, 0, target.getX(), target.getY(), target.getZ(), 4000, digs, goal).segments();
            while (!route.advance(300)) { }
            return route;
        }
    }

    private static Terrain ramp(int stock) {
        var terrain = new Terrain(new BlockPos(4, 8, 0), stock);
        terrain.world.blocks.put(new BlockPos(0, -1, 0), Blocks.STONE.defaultBlockState());
        // A tree/wall supplies real visible side faces; no invisible bridge anchors are invented.
        for (int x = 1; x <= 4; x++) for (int y = -1; y <= 9; y++)
            terrain.world.blocks.put(new BlockPos(x, y, 1), Blocks.STONE.defaultBlockState());
        return terrain;
    }

    @Test void workGoalBuildsSeveralSupportsAndReplaysEveryNativeShapeAndContactInOrder() {
        var terrain = ramp(8);
        int original = terrain.world.blocks.size();
        var route = terrain.route(0);
        assertNull(route.failure(), () -> route.failure() + " " + route.rejections());
        assertTrue(route.placedCount() >= 3);
        assertEquals(original, terrain.world.blocks.size(), "planning never places actual blocks");
        int stock = PathMaterials.count(terrain.inventory);
        var before = route.path().getFirst();
        TerrainEdits history = TerrainEdits.EMPTY;
        boolean crossedPendingNeighbor = false;
        for (var step : route.path().subList(1, route.path().size())) {
            for (long cell : step.place()) {
                var pos = new BlockPos(DigAStar.unpackX(cell), DigAStar.unpackY(cell), DigAStar.unpackZ(cell));
                crossedPendingNeighbor |= BlockPlacement.pendingNativeTerrain(pos, history);
                assertTrue(terrain.canPlaceFrom(before.x(), before.y(), before.z(), cell, TerrainEdits.EMPTY),
                        "every support must also be clickable after earlier real placements");
                terrain.world.blocks.put(pos, Blocks.COBBLESTONE.defaultBlockState());
                terrain.inventory.getItem(PathMaterials.find(terrain.inventory)).shrink(1);
                history = history.after(List.of(), List.of(cell));
            }
            assertNotNull(terrain.geometry.standing(step.x(), step.y(), step.z(), TerrainEdits.EMPTY));
            assertNotEquals(NavigationGeometry.Motion.BLOCKED, terrain.geometry.motion(before.x(), before.feetY(),
                    before.z(), step.x(), step.feetY(), step.z(), TerrainEdits.EMPTY));
            before = step;
        }
        assertTrue(crossedPendingNeighbor, "this route used to be rejected by the blanket native-neighborhood gate");
        assertEquals(stock - route.placedCount(), PathMaterials.count(terrain.inventory));
        assertTrue(NavigationWork.goal(terrain.world, terrain.target, 1.62, 4.5, false, p -> false,
                terrain.geometry).reached(before.x(), before.y(), before.z()));
        assertTrue(terrain.world.blocks.get(terrain.target).is(Blocks.OAK_LOG));
    }

    @Test void noMaterialsOrTooFewMaterialsCannotProduceTheElevatedWorkRoute() {
        for (int stock : new int[]{0, 1}) {
            var terrain = ramp(stock);
            var route = terrain.route(0);
            assertNotNull(route.failure());
            assertEquals(stock, PathMaterials.count(terrain.inventory));
        }
    }

    @Test void miningThroughASealedPassageUsesTheSameWorkGoalWithoutMiningItsTargetOrFloor() {
        var terrain = new Terrain(new BlockPos(7, 1, 0), 0);
        terrain.maxX = 7; terrain.maxY = 3; terrain.digging = true;
        for (int x = 0; x <= 7; x++) {
            terrain.world.blocks.put(new BlockPos(x, -1, 0), Blocks.BEDROCK.defaultBlockState());
            terrain.world.blocks.put(new BlockPos(x, 3, 0), Blocks.BEDROCK.defaultBlockState());
        }
        for (int x = 1; x <= 2; x++) for (int y = 0; y <= 2; y++)
            terrain.world.blocks.put(new BlockPos(x, y, 0), Blocks.STONE.defaultBlockState());
        assertNotNull(terrain.route(0).failure(), "walking alone cannot pass this sealed obstacle");
        var route = terrain.route(8);
        assertNull(route.failure(), () -> route.failure() + " " + route.rejections());
        assertTrue(route.dugCount() >= 4);
        var previous = route.path().getFirst();
        for (var step : route.path().subList(1, route.path().size())) {
            assertTrue(terrain.canClear(previous.x(), previous.y(), previous.z(), step.dig(), TerrainEdits.EMPTY));
            for (long cell : step.dig()) {
                var pos = new BlockPos(DigAStar.unpackX(cell), DigAStar.unpackY(cell), DigAStar.unpackZ(cell));
                assertNotEquals(terrain.target, pos);
                assertNotEquals(terrain.target.below(), pos);
                assertNotNull(terrain.world.blocks.remove(pos));
            }
            assertNotNull(terrain.geometry.standing(step.x(), step.y(), step.z(), TerrainEdits.EMPTY));
            previous = step;
        }
    }

    @Test void hypotheticalSupportsStillRequireRealFacesPermissionKnownSpaceAndUnoccupiedBody() {
        var world = new BlockAimTest.Grid();
        world.blocks.clear();
        var target = new BlockPos(1, 0, 0);
        world.blocks.put(new BlockPos(1, 0, 1), Blocks.STONE.defaultBlockState());
        var eye = new Vec3(.5, 1.62, .5);
        var body = new AABB(.2, 0, .2, .8, 1.8, .8);
        var material = new ItemStack(Items.COBBLESTONE, 3);
        assertTrue(SupportPlacement.canPlan(world, world, target, eye, body, material, 4.5,
                TerrainEdits.EMPTY, p -> true, face -> true, box -> true));
        assertFalse(SupportPlacement.canPlan(world, world, target, eye, body, material, 4.5,
                TerrainEdits.EMPTY, p -> false, face -> true, box -> true));
        assertFalse(SupportPlacement.canPlan(world, world, target, eye, body, material, 4.5,
                TerrainEdits.EMPTY, p -> true, face -> false, box -> true));
        assertFalse(SupportPlacement.canPlan(world, world, target, eye, body, material, 4.5,
                TerrainEdits.EMPTY, p -> true, face -> true, box -> false));
        world.unknown.add(target.above());
        assertFalse(SupportPlacement.canPlan(world, world, target, eye, body, material, 4.5,
                TerrainEdits.EMPTY, p -> true, face -> true, box -> true));
        world.unknown.clear();
        assertFalse(SupportPlacement.canPlan(world, world, target, eye, new AABB(target), material, 4.5,
                TerrainEdits.EMPTY, p -> true, face -> true, box -> true));
        var removedFace = TerrainEdits.EMPTY.after(List.of(DigAStar.pack(1, 0, 1)), List.of());
        assertFalse(SupportPlacement.canPlan(world, world, target, eye, body, material, 4.5,
                removedFace, p -> true, face -> true, box -> true));
        world.blocks.clear();
        var plannedFace = TerrainEdits.EMPTY.after(List.of(), List.of(DigAStar.pack(1, 0, 1)));
        assertTrue(SupportPlacement.canPlan(world, world, target, eye, body, material, 4.5,
                plannedFace, p -> true, face -> true, box -> true));
        assertTrue(world.blocks.isEmpty());
        assertFalse(SupportPlacement.canPlan(world, world, target, eye, body, new ItemStack(Items.OAK_SLAB), 4.5,
                plannedFace, p -> true, face -> true, box -> true));
    }
}
