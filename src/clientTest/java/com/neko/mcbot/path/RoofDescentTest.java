package com.neko.mcbot.path;

import com.neko.mcbot.server.BlockActionFixtures;
import com.neko.mcbot.server.StandingGeometry;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class RoofDescentTest {
    @BeforeAll static void registries() { BlockActionFixtures.bootstrap(); }

    static final class Site implements DigSampler, StandingGeometry.Access {
        final Map<BlockPos, BlockState> blocks = new HashMap<>();
        final Set<BlockPos> unknown = new HashSet<>();
        boolean underground;
        final StandingGeometry geometry = new StandingGeometry(this, .6, 1.8, .6);

        Site() {
            for (int x = 59; x <= 64; x++) for (int z = -89; z <= -79; z++)
                blocks.put(new BlockPos(x, 71, z), Blocks.GRASS_BLOCK.defaultBlockState());
            for (int y = 72; y <= 74; y++)
                blocks.put(new BlockPos(61, y, -87), Blocks.OAK_LOG.defaultBlockState());
        }
        BlockState state(BlockPos p) { return blocks.getOrDefault(p, Blocks.AIR.defaultBlockState()); }
        @Override public boolean known(BlockPos p) { return inBounds(p.getX(), p.getY(), p.getZ()) && !unknown.contains(p); }
        @Override public boolean known(int x, int y, int z) { return known(new BlockPos(x, y, z)); }
        @Override public List<AABB> boxes(BlockPos p) {
            assertTrue(known(p), "unobserved cells must not be read");
            return StandingGeometry.collision(state(p), EmptyBlockGetter.INSTANCE, p);
        }
        @Override public boolean inBounds(int x, int y, int z) {
            return x >= 59 && x <= 64 && z >= -89 && z <= -79 && y >= 70 && y <= 79;
        }
        @Override public boolean passable(int x, int y, int z) { return state(new BlockPos(x,y,z)).isAir(); }
        @Override public boolean support(int x, int y, int z) { return !passable(x,y,z); }
        @Override public double digSeconds(int x, int y, int z) { return INFEASIBLE; }
        @Override public boolean placeable(int x, int y, int z) { return false; }
        @Override public double placeCost(int x, int y, int z) { return INFEASIBLE; }
        @Override public int maxPlaces() { return 0; }
        @Override public NavigationGeometry geometry() { return geometry; }
        @Override public boolean allowsStep(int fx, int fy, int fz, int x, int y, int z) {
            return !underground || Math.abs(x-fx)+Math.abs(z-fz)==1 && Math.abs(y-fy)<=1;
        }
    }

    @Test void completedCornerColumnHasAnUneditedRouteDownToTheGround() {
        var site = new Site();
        var before = Map.copyOf(site.blocks);
        var route = PathTask.searchFor(new MemoDigSampler(site),
                new BlockPos(61,75,-87), new BlockPos(62,72,-80), true, true);
        while (!route.advance(100)) { }
        assertNull(route.failure(), route.failure());
        assertTrue(route.path().stream().anyMatch(step -> step.action() == DigAStar.ACT_FALL));
        assertTrue(route.path().stream().allMatch(step -> step.dig().isEmpty() && step.place().isEmpty()));
        assertEquals(route.path(), PathTask.liveSteps(route.path(), 75, site));
        assertNull(PathTask.planFailure(route.path(), site, 0));
        assertEquals(before, site.blocks, "do not remove the completed column or fabricate support");
        site.blocks.remove(new BlockPos(route.path().get(1).x(),71,route.path().get(1).z()));
        assertNotNull(PathTask.planFailure(route.path(), site, 0), "recheck the landing before movement");
    }

    @Test void fallingCannotTunnelThroughARoofOrChooseAFloorBelowAnEarlierLanding() {
        var site = new Site();
        var floor = new BlockPos(60,73,-87);
        site.blocks.put(floor, Blocks.OAK_PLANKS.defaultBlockState());
        assertNull(PathTransition.check(site,TerrainEdits.EMPTY,61,75,-87,60,72,-87,99,99).plan());
        assertNotNull(PathTransition.check(site,TerrainEdits.EMPTY,61,75,-87,60,74,-87,0,0).plan());
        site.blocks.remove(floor);
        site.blocks.put(new BlockPos(60,75,-87),Blocks.OAK_PLANKS.defaultBlockState());
        assertNull(PathTransition.check(site,TerrainEdits.EMPTY,61,75,-87,60,72,-87,99,99).plan(),
                "a drop is not permission to silently clear its descent column");
    }

    @Test void unknownHazardousDiagonalAndUndergroundDropsRemainUnavailable() {
        var site = new Site();
        site.unknown.add(new BlockPos(60,73,-87));
        assertNull(PathTransition.check(site,TerrainEdits.EMPTY,61,75,-87,60,72,-87,0,0).plan());
        site.unknown.clear();
        site.blocks.put(new BlockPos(60,71,-87),Blocks.MAGMA_BLOCK.defaultBlockState());
        assertNull(PathTransition.check(site,TerrainEdits.EMPTY,61,75,-87,60,72,-87,0,0).plan());
        site.blocks.put(new BlockPos(60,71,-87),Blocks.POINTED_DRIPSTONE.defaultBlockState());
        assertNull(PathTransition.check(site,TerrainEdits.EMPTY,61,75,-87,60,72,-87,0,0).plan());
        site.blocks.put(new BlockPos(60,71,-87),Blocks.GRASS_BLOCK.defaultBlockState());
        assertNull(PathTransition.check(site,TerrainEdits.EMPTY,61,75,-87,60,72,-88,0,0).plan());
        site.underground = true;
        assertNull(PathTransition.check(site,TerrainEdits.EMPTY,61,75,-87,60,72,-87,0,0).plan());
    }

    @Test void fallPolicyIsReadFreshAndFractionalFootingCannotHideAnExtraHalfBlock() {
        var site = new Site();
        double[] safeFall = {3};
        var geometry = new StandingGeometry(site,.6,1.8,.6,() -> safeFall[0]);
        assertEquals(NavigationGeometry.Motion.WALK, geometry.motion(61,75,-87,60,72,-87,TerrainEdits.EMPTY));
        safeFall[0] = 2;
        assertEquals(NavigationGeometry.Motion.BLOCKED, geometry.motion(61,75,-87,60,72,-87,TerrainEdits.EMPTY));
        safeFall[0] = 100;
        assertEquals(3,geometry.maxDrop(), "higher damaging falls require their own landing contract");
        site.blocks.put(new BlockPos(61,75,-87),Blocks.OAK_SLAB.defaultBlockState());
        assertNull(PathTransition.check(site,TerrainEdits.EMPTY,61,75,-87,60,72,-87,0,0).plan(),
                "the feet are at 75.5 rather than 75, making this drop unsafe");
        site.blocks.remove(new BlockPos(61,75,-87));
        site.blocks.remove(new BlockPos(60,71,-87));
        site.blocks.put(new BlockPos(60,70,-87),Blocks.STONE.defaultBlockState());
        assertNull(PathTransition.check(site,TerrainEdits.EMPTY,61,75,-87,60,71,-87,0,0).plan());
    }
}
