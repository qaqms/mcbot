package com.neko.mcbot.server;

import com.neko.mcbot.path.DigAStar;
import com.neko.mcbot.path.DigSampler;
import com.neko.mcbot.path.PathSpec;
import com.neko.mcbot.path.NavigationGoal;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class NavigationWorkTest {
    @BeforeAll static void registries() { BlockActionFixtures.bootstrap(); }

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
}
