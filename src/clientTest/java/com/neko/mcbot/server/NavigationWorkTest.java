package com.neko.mcbot.server;

import com.neko.mcbot.path.DigAStar;
import com.neko.mcbot.path.DigSampler;
import com.neko.mcbot.path.PathSpec;
import com.neko.mcbot.path.NavigationGoal;
import com.neko.mcbot.path.TerrainEdits;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class NavigationWorkTest {
    @BeforeAll static void registries() { BlockActionFixtures.bootstrap(); }

    @Test void laterWorkRaysSeeAllEarlierRemovalsAndPlannedSupports() {
        var world = new BlockAimTest.Grid();
        world.wall();
        long opening = DigAStar.pack(1, 1, 0);
        var removed = TerrainEdits.EMPTY.after(List.of(opening), List.of());
        var goal = NavigationWork.goal(world, BlockAimTest.TARGET, 1.62, 4.5, false, p -> false);
        assertFalse(goal.reached(0, 0, 0));
        assertTrue(goal.reached(0, 0, 0, removed), "a real opening from an earlier step exposes the target");
        assertTrue(NavigationWork.canClear(world, BlockPos.ZERO, 1.62, 4.5,
                List.of(DigAStar.pack(2, 1, 0)), removed));
        var filled = removed.after(List.of(), List.of(opening));
        assertFalse(goal.reached(0, 0, 0, filled), "planned full-cube supports must occlude work rays");
        assertFalse(NavigationWork.canClear(world, BlockPos.ZERO, 1.62, 4.5,
                List.of(DigAStar.pack(2, 1, 0)), filled));
        assertTrue(world.blocks.containsKey(new BlockPos(1, 1, 0)), "native clip runs without changing the world");
    }

    @Test void editedGeometryNeverTurnsUnknownCellsIntoObservedAir() {
        var world = new BlockAimTest.Grid();
        world.wall();
        BlockPos unknown = new BlockPos(1, 1, 0);
        world.unknown.add(unknown);
        var edits = TerrainEdits.EMPTY.after(List.of(DigAStar.pack(1, 1, 0)), List.of());
        var goal = NavigationWork.goal(world, BlockAimTest.TARGET, 1.62, 4.5, false, p -> false);
        assertFalse(goal.reached(0, 0, 0, edits));
        assertFalse(NavigationWork.canClear(world, BlockPos.ZERO, 1.62, 4.5,
                List.of(DigAStar.pack(2, 1, 0)), edits));
    }

    @Test void clippingAnEditedViewUsesTheNativePlacedBlockOutline() {
        var world = new BlockAimTest.Grid();
        var eye = world.eye();
        var view = NavigationWork.view(world, eye, Set.of(), Set.of(new BlockPos(1, 1, 0)));
        assertFalse(BlockAim.mining(view, BlockAimTest.TARGET, 4.5).ok());
        assertTrue(BlockAim.mining(view, new BlockPos(1, 1, 0), 4.5).ok());
        assertEquals(1, world.blocks.size());
    }

    @Test void realClipChecksEachClearingCellInTheRequestedOrder() {
        var world = new BlockAimTest.Grid();
        world.blocks.clear();
        world.blocks.put(new BlockPos(1, 0, 0), Blocks.STONE.defaultBlockState());
        world.blocks.put(new BlockPos(1, 1, 0), Blocks.STONE.defaultBlockState());
        world.blocks.put(new BlockPos(1, 2, 0), Blocks.STONE.defaultBlockState());
        var low = DigAStar.pack(1, 1, 0);
        var upper = DigAStar.pack(1, 2, 0);
        assertTrue(NavigationWork.canClear(world, BlockPos.ZERO, 1.62, 4.5, List.of(upper, low)));
        var behind = DigAStar.pack(2, 1, 0);
        world.blocks.put(new BlockPos(2, 1, 0), Blocks.STONE.defaultBlockState());
        assertFalse(NavigationWork.canClear(world, BlockPos.ZERO, 1.62, 4.5, List.of(behind, low)));
        assertTrue(NavigationWork.canClear(world, BlockPos.ZERO, 1.62, 4.5, List.of(low, behind)));
        assertEquals(4, world.blocks.size(), "planning must not change the world");
    }

    @Test void workSearchGoesAroundTheWallInsteadOfStoppingAtGeometricReach() {
        var world = new BlockAimTest.Grid();
        world.wall();
        var goal = NavigationWork.goal(world, BlockAimTest.TARGET, 1.62, 4.5, false, p -> false);
        assertFalse(goal.reached(0, 0, 0));
        assertTrue(goal.reached(2, 0, 2));
        DigSampler terrain = new DigSampler() {
            @Override public boolean passable(int x, int y, int z) {
                return y >= 0 && !world.blocks.containsKey(new BlockPos(x, y, z));
            }
            @Override public double digSeconds(int x, int y, int z) { return INFEASIBLE; }
            @Override public boolean support(int x, int y, int z) { return y == -1; }
            @Override public boolean placeable(int x, int y, int z) { return false; }
            @Override public double placeCost(int x, int y, int z) { return 1; }
            @Override public boolean inBounds(int x, int y, int z) {
                return x >= -1 && x <= 4 && z >= -3 && z <= 3 && y >= -1 && y <= 4;
            }
            @Override public int maxPlaces() { return 0; }
        };
        var search = new DigAStar(terrain, 0, 0, 0, 2, 1, 0, 1000, 0, goal);
        while (!search.advance(100)) { }
        assertNull(search.failure());
        assertTrue(search.path().size() > 1);
        var end = search.path().getLast();
        assertTrue(goal.reached(end.x(), end.y(), end.z()));
    }

    @Test void hypotheticalArrivalCannotOccupyItsTargetOrTreatUnknownAsAir() {
        var world = new BlockAimTest.Grid();
        var goal = NavigationWork.goal(world, BlockAimTest.TARGET, 1.62, 4.5, true, p -> true);
        assertFalse(goal.reached(2, 1, 0));
        assertFalse(goal.reached(2, 0, 0));
        world.unknown.add(BlockAimTest.TARGET);
        assertFalse(goal.reached(0, 0, 0));
    }

    @Test void workArrivalPaysForObstructionsAndCanLeaveAnExpensiveInitialStance() {
        var world = new BlockAimTest.Grid();
        world.blocks.put(new BlockPos(1, 1, 0), Blocks.STONE.defaultBlockState());
        var goal = NavigationWork.goal(world, BlockAimTest.TARGET, 1.62, 4.5, true, p -> true);
        assertTrue(goal.reached(0, 0, 0), "the starting stance can work after clearing");
        DigSampler terrain = new DigSampler() {
            @Override public boolean passable(int x, int y, int z) {
                return y >= 0 && !world.blocks.containsKey(new BlockPos(x, y, z));
            }
            @Override public double digSeconds(int x, int y, int z) { return 3; }
            @Override public boolean support(int x, int y, int z) { return y == -1; }
            @Override public boolean placeable(int x, int y, int z) { return false; }
            @Override public double placeCost(int x, int y, int z) { return 1; }
            @Override public int maxPlaces() { return 0; }
            @Override public boolean inBounds(int x, int y, int z) {
                return x >= -1 && x <= 4 && y >= -1 && y <= 3 && Math.abs(z) <= 3;
            }
        };
        var route = new DigAStar(terrain, 0, 0, 0, 2, 1, 0, 1000, 0, goal);
        while (!route.advance(100)) { }
        assertNull(route.failure());
        assertTrue(route.path().size() > 1, "a short walk is cheaper than clearing from the initial stance");
        var end = route.path().getLast();
        var direct = NavigationWork.goal(world, BlockAimTest.TARGET, 1.62, 4.5, false, p -> false);
        assertTrue(direct.reached(end.x(), end.y(), end.z()), "the chosen stance needs no clearing");
    }

    @Test void undergroundStairCanBeClearedInOrderAndWalkedBackWithoutMoreEdits() {
        var world = new BlockAimTest.Grid();
        world.blocks.clear();
        for (int x = 0; x <= 4; x++) for (int y = 0; y <= 6; y++)
            world.blocks.put(new BlockPos(x, y, 0), Blocks.STONE.defaultBlockState());
        world.blocks.remove(new BlockPos(0, 5, 0));
        world.blocks.remove(new BlockPos(0, 6, 0));
        var spec = new PathSpec(false, false, true, 24, new BlockPos(0, 5, 0), 16);
        DigSampler terrain = new DigSampler() {
            @Override public boolean passable(int x, int y, int z) {
                return inBounds(x, y, z) && !world.blocks.containsKey(new BlockPos(x, y, z));
            }
            @Override public double digSeconds(int x, int y, int z) { return inBounds(x, y, z) && y > 0 ? 1 : INFEASIBLE; }
            @Override public boolean support(int x, int y, int z) {
                return inBounds(x, y, z) && world.blocks.containsKey(new BlockPos(x, y, z));
            }
            @Override public boolean placeable(int x, int y, int z) { return false; }
            @Override public double placeCost(int x, int y, int z) { return INFEASIBLE; }
            @Override public int maxPlaces() { return 0; }
            @Override public boolean inBounds(int x, int y, int z) {
                return x >= 0 && x <= 4 && y >= 0 && y <= 6 && z == 0;
            }
            @Override public boolean allowsStep(int fx, int fy, int fz, int x, int y, int z) {
                return spec.allowsStep(fx, fy, fz, x, y, z);
            }
            @Override public boolean canClear(int x, int y, int z, List<Long> cells) {
                return NavigationWork.canClear(world, new BlockPos(x, y, z), 1.62, 4.5, cells);
            }
            @Override public boolean canClear(int x, int y, int z, List<Long> cells, TerrainEdits edits) {
                return NavigationWork.canClear(world, new BlockPos(x, y, z), 1.62, 4.5, cells, edits);
            }
        };
        assertFalse(spec.allowsStep(0, 5, 0, 1, 2, 0), "a drop must not become a returnable stair");
        var down = new DigAStar(terrain, 0, 5, 0, 4, 1, 0, 2000, 24,
                NavigationGoal.position(4, 1, 0, 0));
        while (!down.advance(100)) { }
        assertNull(down.failure());
        int removed = 0;
        var previous = down.path().getFirst();
        for (var step : down.path().subList(1, down.path().size())) {
            assertTrue(terrain.canClear(previous.x(), previous.y(), previous.z(), step.dig()));
            for (long cell : step.dig()) {
                assertNotNull(world.blocks.remove(new BlockPos(DigAStar.unpackX(cell),
                        DigAStar.unpackY(cell), DigAStar.unpackZ(cell))));
                removed++;
            }
            assertTrue(terrain.support(step.x(), step.y() - 1, step.z()));
            previous = step;
        }
        assertTrue(removed >= 8);
        var up = new DigAStar(terrain, 4, 1, 0, 0, 5, 0, 2000, 0,
                NavigationGoal.position(0, 5, 0, 0));
        while (!up.advance(100)) { }
        assertNull(up.failure());
        assertTrue(up.path().stream().allMatch(s -> s.dig().isEmpty() && s.place().isEmpty()));
    }

    @Test void segmentedShortUndergroundRoutesUseNativeCollisionAndEveryOrderedMiningRay() {
        for (int targetY : new int[]{1, 5}) {
            var world = new BlockAimTest.Grid();
            world.blocks.clear();
            for (int x = -4; x <= 8; x++) for (int y = -4; y <= 10; y++)
                for (int z = -4; z <= 4; z++)
                    world.blocks.put(new BlockPos(x, y, z), Blocks.STONE.defaultBlockState());
            world.blocks.remove(new BlockPos(0, 5, 0));
            world.blocks.remove(new BlockPos(0, 6, 0));
            var geometry = new StandingGeometry(new StandingGeometry.Access() {
                @Override public boolean known(BlockPos pos) { return world.loaded(pos); }
                @Override public List<net.minecraft.world.phys.AABB> boxes(BlockPos pos) {
                    return StandingGeometry.collision(world.getBlockState(pos), world, pos);
                }
            }, .6, 1.8, .6);
            var spec = new PathSpec(false, false, true, Integer.MAX_VALUE, null, 0);
            DigSampler terrain = new DigSampler() {
                @Override public com.neko.mcbot.path.NavigationGeometry geometry() { return geometry; }
                @Override public boolean passable(int x, int y, int z) {
                    return !world.blocks.containsKey(new BlockPos(x, y, z));
                }
                @Override public boolean support(int x, int y, int z) { return !passable(x, y, z); }
                @Override public double digSeconds(int x, int y, int z) { return 1; }
                @Override public boolean placeable(int x, int y, int z) { return false; }
                @Override public double placeCost(int x, int y, int z) { return INFEASIBLE; }
                @Override public int maxPlaces() { return 0; }
                @Override public boolean inBounds(int x, int y, int z) {
                    return Math.abs(x) <= 6 && y >= -3 && y <= 9 && Math.abs(z) <= 3;
                }
                @Override public boolean allowsStep(int fx, int fy, int fz, int x, int y, int z) {
                    return spec.allowsStep(fx, fy, fz, x, y, z);
                }
                @Override public boolean canClear(int x, int y, int z, List<Long> cells, TerrainEdits edits) {
                    return NavigationWork.canClear(world, new BlockPos(x, y, z), 1.62, 4.5, cells, edits, geometry);
                }
            };
            int original = world.blocks.size();
            var route = new DigAStar(terrain, 0, 5, 0, 4, targetY, 0, 4000, Integer.MAX_VALUE,
                    NavigationGoal.position(4, targetY, 0, 0)).segments();
            while (!route.advance(300)) { }
            assertNull(route.failure(), route.failure());
            assertFalse(route.segmentEnd());
            assertEquals(original, world.blocks.size(), "planning must not edit the native block map");
            var from = route.path().getFirst();
            for (var step : route.path().subList(1, route.path().size())) {
                assertTrue(NavigationWork.canClear(world, new BlockPos(from.x(), from.y(), from.z()),
                        1.62, 4.5, step.dig(), TerrainEdits.EMPTY, geometry));
                for (long cell : step.dig())
                    assertNotNull(world.blocks.remove(new BlockPos(DigAStar.unpackX(cell),
                            DigAStar.unpackY(cell), DigAStar.unpackZ(cell))));
                assertNotNull(geometry.standing(step.x(), step.y(), step.z(), TerrainEdits.EMPTY));
                assertNotEquals(com.neko.mcbot.path.NavigationGeometry.Motion.BLOCKED,
                        geometry.motion(from.x(), from.feetY(), from.z(), step.x(), step.feetY(), step.z(), TerrainEdits.EMPTY));
                from = step;
            }
            assertEquals(4, from.x()); assertEquals(targetY, from.y());
        }
    }
}
