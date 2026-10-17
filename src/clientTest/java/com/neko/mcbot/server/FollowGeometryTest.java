package com.neko.mcbot.server;

import com.neko.mcbot.path.DigSampler;
import com.neko.mcbot.path.NavigationBudget;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class FollowGeometryTest {
    @BeforeAll static void registries() { BlockActionFixtures.bootstrap(); }
    private static FollowWork.Target target(double x, double y, double z) {
        return new FollowWork.Target(UUID.randomUUID(), new Vec3(x, y, z),
                new AABB(x - .3, y, z - .3, x + .3, y + 1.8, z + .3));
    }

    @Test void arrivalUsesFractionalSupportAndRefusesOverlapOrUnknownGround() {
        var grid = new StandingGeometryTest.Grid();
        var goal = FollowWork.goal(grid.geometry(), target(2.5, .5, .5), 2);
        assertFalse(goal.reached(0, 0, 0));
        grid.blocks.put(BlockPos.ZERO, Blocks.OAK_SLAB.defaultBlockState());
        assertTrue(goal.reached(0, 0, 0));
        grid.unknown.add(BlockPos.ZERO);
        assertFalse(goal.reached(0, 0, 0));
        grid.unknown.clear();
        assertFalse(FollowWork.goal(grid.geometry(), target(.5, .5, .5), 2).reached(0, 0, 0));
        grid.blocks.put(new BlockPos(0, 2, 0), Blocks.STONE.defaultBlockState());
        assertFalse(goal.reached(0, 0, 0));
    }

    private static DigSampler terrain(StandingGeometryTest.Grid grid) {
        var geometry = grid.geometry();
        return new DigSampler() {
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
                return x >= -2 && x <= 4 && y >= -1 && y <= 4 && z >= -2 && z <= 2;
            }
        };
    }

    @Test void realSharedSearchFindsDryStanceWithoutEditingAndChargesOriginalAccount() {
        var grid = new StandingGeometryTest.Grid();
        var original = java.util.Map.copyOf(grid.blocks);
        var goal = FollowWork.goal(grid.geometry(), target(4.5, 0, .5), 2);
        var budget = new NavigationBudget();
        var search = budget.search(terrain(grid), 0, 0, 0, 4, 0, 0, goal);
        while (!search.advance(100)) { }
        budget.observe(search);
        assertNull(search.failure());
        var end = search.path().getLast();
        assertTrue(goal.reached(end.x(), end.y(), end.z()));
        assertTrue(search.path().size() > 1);
        assertTrue(search.path().stream().allMatch(s -> s.dig().isEmpty() && s.place().isEmpty()));
        assertTrue(budget.nodes() > 0);
        assertEquals(original, grid.blocks);
    }

    @Test void flyingTargetIsNotSilentlyProjectedDownToAnInventedGroundGoal() {
        var grid = new StandingGeometryTest.Grid();
        var goal = FollowWork.goal(grid.geometry(), target(4.5, 12, .5), 2);
        assertFalse(goal.reached(4, 0, 0));
        var budget = new NavigationBudget();
        var search = budget.search(terrain(grid), 0, 0, 0, 4, 12, 0, goal);
        while (!search.advance(100)) { }
        budget.observe(search);
        assertTrue(search.failure() != null || search.path().isEmpty()
                || !goal.reached(search.path().getLast().x(), search.path().getLast().y(), search.path().getLast().z()));
        assertTrue(budget.nodes() > 0);
    }
}
