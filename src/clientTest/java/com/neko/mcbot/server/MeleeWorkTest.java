package com.neko.mcbot.server;

import net.minecraft.core.BlockPos;
import net.minecraft.world.item.component.AttackRange;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MeleeWorkTest {
    @BeforeAll static void registries() { BlockActionFixtures.bootstrap(); }

    @Test void nativeBoundsUseEyeToActualBoxWithMinRangeAndMargin() {
        var range = new AttackRange(2, 3, 4, 5, .25f, 1);
        var box = new AABB(0, 0, 0, 4, 4, 4);
        assertTrue(MeleeWork.inRange(range, false, new Vec3(7.25, 2, 2), box));
        assertFalse(MeleeWork.inRange(range, false, new Vec3(7.251, 2, 2), box));
        assertTrue(MeleeWork.inRange(range, false, new Vec3(5.75, 2, 2), box));
        assertFalse(MeleeWork.inRange(range, false, new Vec3(5.749, 2, 2), box));
        assertFalse(MeleeWork.inRange(range, false, new Vec3(2, 2, 2), box),
                "no hardcoded minimum reach enlargement");
        assertTrue(MeleeWork.inRange(range, true, new Vec3(9.25, 2, 2), box));
        assertFalse(MeleeWork.inRange(range, false, new Vec3(9.25, 2, 2), box));
        assertFalse(MeleeWork.inRange(range, false, new Vec3(Double.NaN, 2, 2), box));
    }

    @Test void goalUsesFractionalFeetAndRejectsOverlapUnknownOrOccludedStances() {
        var world = new BlockAimTest.Grid();
        world.blocks.clear();
        var grid = new StandingGeometryTest.Grid();
        var target = new AABB(3.4, 2.25, .3, 3.8, 2.65, .7);
        var goal = MeleeWork.goal(world, grid.geometry(), target, target.getCenter(),
                new AttackRange(0, 2.94f, 0, 2.94f, 0, 1), 1.62);
        assertFalse(goal.reached(0, 0, 0));
        grid.blocks.put(BlockPos.ZERO, Blocks.OAK_SLAB.defaultBlockState());
        assertTrue(goal.reached(0, 0, 0), "real half-slab eye height makes this stance usable");
        world.blocks.put(new BlockPos(1, 2, 0), Blocks.STONE.defaultBlockState());
        assertFalse(goal.reached(0, 0, 0));
        world.blocks.clear();
        world.unknown.add(new BlockPos(1, 2, 0));
        assertFalse(goal.reached(0, 0, 0));
        world.unknown.clear();
        grid.unknown.add(BlockPos.ZERO);
        assertFalse(goal.reached(0, 0, 0), "unknown support cannot become an arrival");
        grid.unknown.clear();
        var overlapping = MeleeWork.goal(world, grid.geometry(), new AABB(.4, 0, .4, 1, 2, 1),
                new Vec3(.7, 1.7, .7), new AttackRange(0, 3, 0, 3, 0, 1), 1.62);
        assertFalse(overlapping.reached(0, 0, 0));
    }

    @Test void actualWalkSearchFindsAVisibleStanceAroundWallWithoutAnyEdits() {
        var world = new BlockAimTest.Grid();
        world.blocks.clear();
        world.wall();
        var grid = new StandingGeometryTest.Grid();
        grid.blocks.putAll(world.blocks);
        var original = java.util.Map.copyOf(grid.blocks);
        var geometry = grid.geometry();
        var target = new AABB(3.2, 0, .2, 3.8, 1.8, .8);
        var goal = MeleeWork.goal(world, geometry, target, new Vec3(3.5, 1.62, .5),
                new AttackRange(0, 3, 0, 3, 0, 1), 1.62);
        assertFalse(goal.reached(0, 0, 0));
        com.neko.mcbot.path.DigSampler terrain = new com.neko.mcbot.path.DigSampler() {
            @Override public boolean passable(int x, int y, int z) {
                return y >= 0 && !grid.blocks.containsKey(new BlockPos(x, y, z));
            }
            @Override public double digSeconds(int x, int y, int z) { return INFEASIBLE; }
            @Override public boolean support(int x, int y, int z) { return y == -1; }
            @Override public boolean placeable(int x, int y, int z) { return false; }
            @Override public double placeCost(int x, int y, int z) { return INFEASIBLE; }
            @Override public int maxPlaces() { return 0; }
            @Override public com.neko.mcbot.path.NavigationGeometry geometry() { return geometry; }
            @Override public boolean inBounds(int x, int y, int z) {
                return x >= -2 && x <= 4 && z >= -2 && z <= 2 && y >= -1 && y <= 4;
            }
        };
        var budget = new com.neko.mcbot.path.NavigationBudget();
        var search = budget.search(terrain, 0, 0, 0, 3, 0, 0, goal);
        while (!search.advance(100)) { }
        budget.observe(search);
        assertNull(search.failure());
        var end = search.path().getLast();
        assertTrue(goal.reached(end.x(), end.y(), end.z()));
        assertTrue(search.path().size() > 1);
        assertTrue(search.path().stream().allMatch(step -> step.dig().isEmpty() && step.place().isEmpty()));
        assertTrue(budget.nodes() > 0);
        assertEquals(original, grid.blocks, "search keeps the fixture world intact");
    }
}
