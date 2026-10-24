package com.neko.mcbot.server;

import com.neko.mcbot.path.*;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class PickupGoalTest {
    private static final class Terrain implements DigSampler, StandingGeometry.Access {
        final Set<BlockPos> blocks = new HashSet<>();
        boolean edits;
        int stock;
        Terrain() {
            for (int x = 0; x <= 7; x++) blocks.add(new BlockPos(x, -1, 0));
        }
        @Override public boolean inBounds(int x, int y, int z) {
            return x >= 0 && x <= 7 && y >= -2 && y <= 8 && z == 0;
        }
        @Override public boolean known(BlockPos pos) { return inBounds(pos.getX(), pos.getY(), pos.getZ()); }
        @Override public List<AABB> boxes(BlockPos pos) {
            return blocks.contains(pos) ? List.of(new AABB(0, 0, 0, 1, 1, 1)) : List.of();
        }
        @Override public boolean passable(int x, int y, int z) { return !blocks.contains(new BlockPos(x, y, z)); }
        @Override public boolean support(int x, int y, int z) { return !passable(x, y, z); }
        @Override public double digSeconds(int x, int y, int z) { return edits && y >= 0 ? .1 : INFEASIBLE; }
        @Override public boolean placeable(int x, int y, int z) { return edits && passable(x, y, z); }
        @Override public double placeCost(int x, int y, int z) { return 1; }
        @Override public int maxPlaces() { return stock; }
        @Override public StandingGeometry geometry() { return new StandingGeometry(this, .6, 1.8, .6); }
    }
    private static DigAStar search(Terrain world, NavigationGoal goal) {
        var route = new DigAStar(world, 0, 0, 0, 5, 5, 0, 4000,
                world.edits ? 20 : 0, goal).segments();
        for (int i = 0; i < 2000 && !route.done(); i++) route.advance(300);
        assertTrue(route.done());
        return route;
    }

    @Test void aDropAboveTheHeadUsesGroundContactInsteadOfAnImpossibleExactFootCell() {
        var terrain = new Terrain();
        var item = new AABB(5.35, 2.1, .35, 5.6, 2.35, .6);
        var exact = search(terrain, NavigationGoal.position(5, 2, 0, 0));
        assertNotNull(exact.failure());
        var goal = new PickupGoal(item, .6, 1.8, terrain.geometry());
        var contact = search(terrain, goal);
        assertNull(contact.failure(), contact.failure());
        var end = contact.path().getLast();
        assertEquals(0, end.y());
        assertTrue(GroundItems.touches(terrain.geometry().body(end.x(), end.feetY(), end.z()), item));
        assertTrue(contact.expanded() < 30);
    }

    @Test void hangingDropsCanUseDiggingAndSupportButNeverInventAvailableStock() {
        for (int stock : new int[]{0, 5}) {
            var terrain = new Terrain();
            terrain.edits = true;
            terrain.stock = stock;
            terrain.blocks.add(new BlockPos(1, 0, 0));
            terrain.blocks.add(new BlockPos(1, 1, 0));
            var item = new AABB(5.35, 5, .35, 5.6, 5.25, .6);
            var goal = new PickupGoal(item, .6, 1.8, terrain.geometry());
            var route = search(terrain, goal);
            if (stock == 0) {
                assertNotNull(route.failure());
                continue;
            }
            assertNull(route.failure(), route.failure());
            assertTrue(route.path().stream().anyMatch(step -> !step.place().isEmpty()));
            assertTrue(route.placedCount() <= stock);
            assertTrue(terrain.blocks.contains(new BlockPos(1, 1, 0)), "planning cannot edit the world");
            var edits = TerrainEdits.EMPTY;
            for (var step : route.path()) edits = edits.after(step.dig(), step.place());
            var end = route.path().getLast();
            assertTrue(goal.reached(end.x(), end.y(), end.z(), edits));
        }
    }

    @Test void freshGoalsFollowFallingDropsWhileSnapshotsKeepTheirOwnGeometry() {
        var terrain = new Terrain();
        var high = new PickupGoal(new AABB(5.3, 6, .3, 5.55, 6.25, .55), .6, 1.8, terrain.geometry());
        var low = new PickupGoal(new AABB(5.3, .1, .3, 5.55, .35, .55), .6, 1.8, terrain.geometry());
        assertFalse(high.reached(5, 0, 0));
        assertTrue(low.reached(5, 0, 0));
        var frozen = low.snapshot(terrain);
        assertNotSame(low, frozen);
        assertTrue(frozen.reached(5, 0, 0));
        terrain.blocks.remove(new BlockPos(5, -1, 0));
        assertFalse(low.reached(5, 0, 0), "a touch envelope alone is not a supported landing");
    }

    @Test void obstructedGroundContactCanDigThroughInsteadOfRequiringAnExistingSample() {
        var terrain = new Terrain();
        terrain.edits = true;
        for (int y = 0; y <= 3; y++) terrain.blocks.add(new BlockPos(2, y, 0));
        var item = new AABB(5.35, .1, .35, 5.6, .35, .6);
        var goal = new PickupGoal(item, .6, 1.8, terrain.geometry());
        var route = search(terrain, goal);
        assertNull(route.failure(), route.failure());
        assertTrue(route.dugCount() > 0);
        assertEquals(0, route.placedCount());
        assertEquals(12, terrain.blocks.size());
    }

    @Test void contactBoundsIncludeEveryPossibleFractionalLandingAndRejectNearbyButUntouchableHeights() {
        var item = new AABB(5.99, 4.1, .4, 6.24, 4.35, .65);
        var goal = new PickupGoal(item, .6, 1.8, null);
        var bounds = goal.estimateBounds();
        for (int x = 2; x < 9; x++) for (int y = 0; y < 7; y++) {
            for (double fraction : new double[]{0, .25, .5, .875}) {
                var body = new AABB(x + .2, y + fraction, .2, x + .8, y + fraction + 1.8, .8);
                if (!GroundItems.touches(body, item)) continue;
                assertTrue(x >= bounds.minX() && x <= bounds.maxX() && y >= bounds.minY() && y <= bounds.maxY());
                assertEquals(0, goal.remaining(x, y, 0), 1e-8);
            }
        }
        assertFalse(goal.reached(5, 0, 0));
        assertTrue(goal.reached(5, 2, 0));
    }
}
