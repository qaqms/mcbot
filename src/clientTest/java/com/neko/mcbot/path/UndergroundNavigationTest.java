package com.neko.mcbot.path;

import com.neko.mcbot.server.BlockActionFixtures;
import com.neko.mcbot.server.BlockAim;
import com.neko.mcbot.server.NavigationWork;
import com.neko.mcbot.server.StandingGeometry;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashMap;

import static org.junit.jupiter.api.Assertions.*;

class UndergroundNavigationTest {
    @BeforeAll static void registries() { BlockActionFixtures.bootstrap(); }

    static final class Grid implements BlockGetter, BlockAim.Access {
        final Map<BlockPos, BlockState> blocks = new HashMap<>();
        @Override public BlockState getBlockState(BlockPos pos) {
            return blocks.getOrDefault(pos, Blocks.AIR.defaultBlockState());
        }
        @Override public BlockEntity getBlockEntity(BlockPos pos) { return null; }
        @Override public FluidState getFluidState(BlockPos pos) { return getBlockState(pos).getFluidState(); }
        @Override public int getMinY() { return -64; }
        @Override public int getHeight() { return 384; }
        @Override public Vec3 eye() { return Vec3.ZERO; }
        @Override public boolean loaded(BlockPos pos) { return true; }
        @Override public List<AABB> outline(BlockPos pos) { return getBlockState(pos).getShape(this, pos).toAabbs(); }
        @Override public BlockHitResult trace(Vec3 from, Vec3 to) {
            return clip(new ClipContext(from, to, ClipContext.Block.OUTLINE,
                    ClipContext.Fluid.NONE, CollisionContext.empty()));
        }
        @Override public BlockHitResult traceEdited(Vec3 from, Vec3 to, Set<BlockPos> removed, Set<BlockPos> placed) {
            return BlockAim.clipEdited(this, from, to, CollisionContext.empty(), removed, placed);
        }
    }

    static final class Rock implements DigSampler {
        final Grid world = new Grid();
        final StandingGeometry geometry;
        Rock() {
            world.blocks.clear();
            for (int x = -6; x <= 8; x++) for (int z = -6; z <= 6; z++)
                for (int y = -7; y <= 4; y++)
                    world.blocks.put(new BlockPos(x, y, z), Blocks.STONE.defaultBlockState());
            world.blocks.remove(new BlockPos(0, 0, 0));
            world.blocks.remove(new BlockPos(0, 1, 0));
            geometry = new StandingGeometry(new StandingGeometry.Access() {
                @Override public boolean known(BlockPos pos) { return world.loaded(pos); }
                @Override public List<AABB> boxes(BlockPos pos) {
                    return StandingGeometry.collision(world.getBlockState(pos), world, pos);
                }
            }, .6, 1.8, .6);
        }
        @Override public boolean inBounds(int x, int y, int z) {
            return x >= -5 && x <= 7 && z >= -5 && z <= 5 && y >= -6 && y <= 3;
        }
        @Override public boolean known(int x, int y, int z) { return world.loaded(new BlockPos(x, y, z)); }
        @Override public boolean passable(int x, int y, int z) {
            var pos = new BlockPos(x, y, z);
            return world.getBlockState(pos).getCollisionShape(world, pos).isEmpty();
        }
        @Override public boolean support(int x, int y, int z) {
            var pos = new BlockPos(x, y, z);
            return world.getBlockState(pos).isFaceSturdy(world, pos, Direction.UP);
        }
        @Override public double digSeconds(int x, int y, int z) {
            return inBounds(x, y, z) && !(x == 0 && y == -1 && z == 0) ? .3 : INFEASIBLE;
        }
        @Override public boolean placeable(int x, int y, int z) { return false; }
        @Override public double placeCost(int x, int y, int z) { return INFEASIBLE; }
        @Override public int maxPlaces() { return 0; }
        @Override public NavigationGeometry geometry() { return geometry; }
        @Override public boolean allowsStep(int fx, int fy, int fz, int x, int y, int z) {
            return Math.abs(x - fx) + Math.abs(z - fz) == 1 && Math.abs(y - fy) <= 1;
        }
        @Override public boolean canClear(int x, int y, int z, List<Long> cells, TerrainEdits edits) {
            return NavigationWork.canClear(world, new BlockPos(x, y, z), 1.62, 4.5, cells, edits, geometry);
        }
    }

    @Test void nativeSolidRockCanDescendByFourStairsWithoutSearchingToStorageExhaustion() {
        var terrain = new Rock();
        var check = PathTransition.check(terrain, TerrainEdits.EMPTY, 0, 0, 0, 1, -1, 0, 32, 0);
        assertNotNull(check.plan(), check.reason());
        var goal = NavigationGoal.position(4, -4, 0, 0);
        var route = new DigAStar(terrain, 0, 0, 0, 4, -4, 0, 4000, 32, goal).segments();
        while (!route.advance(300)) { }
        assertNull(route.failure(), () -> route.failure() + " " + route.rejections());
        assertNull(PathTask.planFailure(route.path(), terrain, 32));
        assertEquals(-4, route.path().getLast().y());
        assertTrue(route.expanded() < 4000);
    }

    @Test void descentUnderAnExistingLowTunnelCanFindAStairDetour() {
        var terrain = new Rock();
        for (int x = -4; x <= 4; x++) for (int y = 0; y <= 1; y++)
            terrain.world.blocks.remove(new BlockPos(x, y, 0));
        var goal = NavigationGoal.position(0, -4, 0, 0);
        var route = new DigAStar(terrain, 0, 0, 0, 0, -4, 0, 4000, 64, goal).segments();
        while (!route.advance(300)) { }
        assertNull(route.failure(), () -> route.failure() + " " + route.rejections());
        assertNull(PathTask.planFailure(route.path(), terrain, 64));
    }

    @Test void climbingOutOfALowRockTunnelCanPlanTheTakeoffCeilingAsRealWork() {
        var terrain = new Rock();
        var route = new DigAStar(terrain, 0, 0, 0, 2, 1, 0, 4000, 24,
                NavigationGoal.position(2, 1, 0, 0)).segments();
        while (!route.advance(100)) { }
        assertNull(route.failure(), () -> route.failure() + " " + route.rejections());
        assertTrue(route.path().stream().anyMatch(step -> step.dig().contains(DigAStar.pack(0, 2, 0))));
        assertNull(PathTask.planFailure(route.path(), terrain, 24));
        assertTrue(terrain.world.getBlockState(new BlockPos(0, 2, 0)).is(Blocks.STONE),
                "planning must leave the real ceiling unchanged");
    }

    @Test void speculativeStartUsesThePrecedingRoutesSupportAndClearedBodyButDoesNotCreateWorldEffects() {
        var terrain = new Rock();
        terrain.world.blocks.remove(new BlockPos(1, -1, 0));
        var seed = TerrainEdits.EMPTY.after(List.of(DigAStar.pack(1, 1, 0), DigAStar.pack(1, 0, 0)),
                List.of(DigAStar.pack(1, -1, 0)));
        var route = new DigAStar(terrain, 1, 0, 0, 2, 0, 0, 4000, 2,
                NavigationGoal.position(2, 0, 0, 0)).segments().after(seed);
        while (!route.advance(100)) { }
        assertNull(route.failure(), () -> route.failure() + " " + route.rejections());
        assertEquals(0, route.placedCount(), "the previous route already reserved and pays for this support");
        assertEquals(2, route.dugCount(), "only new digging uses the remaining allowance");
        assertTrue(terrain.world.getBlockState(new BlockPos(1, 0, 0)).is(Blocks.STONE));
        assertTrue(terrain.world.getBlockState(new BlockPos(1, -1, 0)).isAir());
        assertNotNull(PathTask.planFailure(route.path(), terrain, 2),
                "a predicted start cannot be executed before the preceding route makes it real");
        for (long cell : seed.removed()) terrain.world.blocks.remove(new BlockPos(
                DigAStar.unpackX(cell), DigAStar.unpackY(cell), DigAStar.unpackZ(cell)));
        for (long cell : seed.placed()) terrain.world.blocks.put(new BlockPos(
                DigAStar.unpackX(cell), DigAStar.unpackY(cell), DigAStar.unpackZ(cell)), Blocks.COBBLESTONE.defaultBlockState());
        assertNull(PathTask.planFailure(route.path(), terrain, 2));
        terrain.world.blocks.put(new BlockPos(1, 0, 0), Blocks.BEDROCK.defaultBlockState());
        assertNotNull(PathTask.planFailure(route.path(), terrain, 2),
                "live adoption still rejects changed clearance");
    }
}
