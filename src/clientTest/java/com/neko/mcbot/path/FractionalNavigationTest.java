package com.neko.mcbot.path;

import com.neko.mcbot.server.BlockActionFixtures;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class FractionalNavigationTest {
    @BeforeAll static void registries() { BlockActionFixtures.bootstrap(); }

    static final class Terrain implements DigSampler {
        final Map<BlockPos, BlockState> blocks = new HashMap<>();
        boolean digging;
        Terrain() {
            for (int x = 0; x <= 3; x++) blocks.put(new BlockPos(x, -1, 0), Blocks.STONE.defaultBlockState());
        }
        BlockState state(int x, int y, int z) {
            return blocks.getOrDefault(new BlockPos(x, y, z), Blocks.AIR.defaultBlockState());
        }
        @Override public boolean passable(int x, int y, int z) {
            return state(x, y, z).getCollisionShape(EmptyBlockGetter.INSTANCE, new BlockPos(x, y, z)).isEmpty();
        }
        @Override public boolean support(int x, int y, int z) {
            return state(x, y, z).isFaceSturdy(EmptyBlockGetter.INSTANCE, new BlockPos(x, y, z), Direction.UP);
        }
        @Override public double digSeconds(int x, int y, int z) {
            return digging && y >= 0 ? 1 : INFEASIBLE;
        }
        @Override public boolean placeable(int x, int y, int z) { return false; }
        @Override public double placeCost(int x, int y, int z) { return INFEASIBLE; }
        @Override public boolean inBounds(int x, int y, int z) {
            return x >= 0 && x <= 3 && y >= -1 && y <= 4 && z == 0;
        }
        @Override public int maxPlaces() { return 0; }
        @Override public NavigationGeometry geometry() {
            return new com.neko.mcbot.server.StandingGeometry(new com.neko.mcbot.server.StandingGeometry.Access() {
                @Override public boolean known(BlockPos pos) {
                    return inBounds(pos.getX(), pos.getY(), pos.getZ());
                }
                @Override public java.util.List<net.minecraft.world.phys.AABB> boxes(BlockPos pos) {
                    return state(pos.getX(), pos.getY(), pos.getZ())
                            .getCollisionShape(EmptyBlockGetter.INSTANCE, pos).toAabbs();
                }
            }, .6, 1.8, .6);
        }
    }

    static DigAStar route(Terrain terrain, int targetY) {
        var route = new DigAStar(terrain, 0, 0, 0, 3, targetY, 0, 1000, 0, DigAStar.H_WEIGHT, 0);
        while (!route.advance(100)) { }
        return route;
    }

    @Test void alreadySatisfiedFractionalStartIsAStandingNodeRatherThanASelfTransition() {
        var terrain = new Terrain();
        terrain.blocks.put(new BlockPos(0, 0, 0), Blocks.OAK_SLAB.defaultBlockState());
        var route = new DigAStar(terrain, 0, 0, 0, 0, 0, 0, 1000, 0,
                NavigationGoal.position(0, 0, 0, 0));
        while (!route.advance(100)) { }
        assertNull(route.failure());
        assertEquals(1, route.path().size());
        var start = route.path().getFirst();
        assertEquals(.5, start.feetY());
        assertTrue(PathTask.standingStart(start, terrain));
        assertFalse(new PathSteering(0, .5, 0, false)
                .tick(new PathSteering.Body(.2, .5, .5, 0, 0, true)).arrived(),
                "an off-center body must still recenter before arrival");
        terrain.blocks.put(new BlockPos(0, 1, 0), Blocks.STONE.defaultBlockState());
        assertFalse(PathTask.standingStart(start, terrain));
    }

    @Test void levelTurnsCanCarryMotionButReversalsEditsAndFinalArrivalCannot() {
        var a = new DigAStar.Step(0, 0, 0, java.util.List.of(), java.util.List.of(), DigAStar.ACT_START);
        var b = new DigAStar.Step(1, 0, 0, java.util.List.of(), java.util.List.of(), DigAStar.ACT_WALK);
        var c = new DigAStar.Step(1, 0, 1, java.util.List.of(), java.util.List.of(), DigAStar.ACT_WALK);
        assertTrue(PathTask.continuous(a, b, c));
        assertFalse(PathTask.continuous(a, b, a));
        var edited = new DigAStar.Step(1, 0, 1, java.util.List.of(1L), java.util.List.of(), DigAStar.ACT_WALK);
        assertFalse(PathTask.continuous(a, b, edited));
        var steering = new PathSteering(1, 0, 0, false);
        var moving = new PathSteering.Body(1.5, 0, .5, .2, 0, true);
        assertTrue(steering.tick(moving, true).arrived());
        assertFalse(steering.tick(moving).arrived());
    }

    @Test void bottomSlabIsReachedWithoutDestroyingTheSlabOrAddingSupport() {
        var terrain = new Terrain();
        terrain.blocks.put(new BlockPos(1, 0, 0), Blocks.OAK_SLAB.defaultBlockState());
        terrain.blocks.put(new BlockPos(2, 0, 0), Blocks.OAK_SLAB.defaultBlockState());
        terrain.blocks.put(new BlockPos(3, 0, 0), Blocks.OAK_SLAB.defaultBlockState());
        var route = route(terrain, 0);
        assertNull(route.failure(), "collision-height surfaces must not require digging or a full top face");
        assertTrue(route.path().stream().allMatch(s -> s.dig().isEmpty() && s.place().isEmpty()));
        assertEquals(.5, route.path().getLast().feetY());
        assertTrue(route.path().stream().noneMatch(DigAStar.Step::jump));
        var live = PathTask.liveSteps(route.path(), 0, terrain);
        assertEquals(route.path(), live);
        assertTrue(PathTask.editsFeasible(live, terrain));
    }

    @Test void snowCollisionHeightDoesNotMakeAnOtherwiseWalkableColumnImpassable() {
        var terrain = new Terrain();
        terrain.blocks.put(new BlockPos(1, 0, 0), Blocks.SNOW.defaultBlockState().setValue(SnowLayerBlock.LAYERS, 3));
        terrain.blocks.put(new BlockPos(2, 0, 0), Blocks.SNOW.defaultBlockState().setValue(SnowLayerBlock.LAYERS, 5));
        terrain.blocks.put(new BlockPos(3, 0, 0), Blocks.SNOW.defaultBlockState().setValue(SnowLayerBlock.LAYERS, 7));
        assertNull(route(terrain, 0).failure(), "snow must use its collision surface throughout the route");
    }

    @Test void sameCellSupportHeightChangesInvalidateTheFrozenRouteAndFreshRebuildRestoresAgreement() {
        var terrain = new Terrain();
        terrain.blocks.put(new BlockPos(3, 0, 0), Blocks.OAK_SLAB.defaultBlockState());
        var route = route(terrain, 0);
        assertNull(route.failure());
        terrain.blocks.put(new BlockPos(3, 0, 0), Blocks.SNOW.defaultBlockState().setValue(SnowLayerBlock.LAYERS, 3));
        var failure = PathTask.planFailure(route.path(), terrain, 0);
        assertNotNull(failure);
        assertEquals("FOOTING_CHANGED", failure.reason());
        var fresh = PathTask.liveSteps(route.path(), 0, terrain);
        assertEquals(.25, fresh.getLast().feetY());
        assertTrue(PathTask.editsFeasible(fresh, terrain));
    }

    @Test void theShapeAdapterAlsoPreservesOrderedTunnelEditsAndTheClearedReturnRoute() {
        var terrain = new Terrain();
        terrain.digging = true;
        for (int x = 1; x <= 3; x++) for (int y = 0; y <= 2; y++)
            terrain.blocks.put(new BlockPos(x, y, 0), Blocks.STONE.defaultBlockState());
        var route = new DigAStar(new MemoDigSampler(terrain), 0, 0, 0, 3, 0, 0,
                1000, 12, DigAStar.H_WEIGHT, 0);
        while (!route.advance(100)) { }
        assertNull(route.failure());
        var live = PathTask.liveSteps(route.path(), 0, terrain);
        assertTrue(PathTask.editsFeasible(live, terrain));
        int removed = 0;
        for (var step : live) for (long cell : step.dig()) {
            assertNotNull(terrain.blocks.remove(new BlockPos(DigAStar.unpackX(cell),
                    DigAStar.unpackY(cell), DigAStar.unpackZ(cell))));
            removed++;
        }
        assertTrue(removed >= 6);
        var back = new DigAStar(terrain, 3, 0, 0, 0, 0, 0, 1000, 0, DigAStar.H_WEIGHT, 0);
        while (!back.advance(100)) { }
        assertNull(back.failure());
        assertTrue(back.path().stream().allMatch(s -> s.dig().isEmpty() && s.place().isEmpty()));
    }

    @Test void aJumpCannotAcquirePermissionToDigItsOwnLowCeilingAsASideEffect() {
        var terrain = new Terrain();
        terrain.digging = true;
        terrain.blocks.put(new BlockPos(1, 0, 0), Blocks.STONE.defaultBlockState());
        terrain.blocks.put(new BlockPos(0, 2, 0), Blocks.STONE.defaultBlockState());
        var check = PathTransition.check(terrain, TerrainEdits.EMPTY, 0, 0, 0, 1, 1, 0, 12, 0);
        assertNull(check.plan());
        assertEquals("JUMP_CLEARANCE", check.reason());
    }
}
