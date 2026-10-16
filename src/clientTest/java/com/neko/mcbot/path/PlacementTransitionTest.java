package com.neko.mcbot.path;

import com.neko.mcbot.server.BlockActionFixtures;
import com.neko.mcbot.server.StandingGeometry;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PlacementTransitionTest {
    @BeforeAll static void registries() { BlockActionFixtures.bootstrap(); }

    static class Terrain implements DigSampler {
        boolean allow, shaped;
        TerrainEdits seen;
        int checks;
        final long obstruction = DigAStar.pack(1, 1, 0), support = DigAStar.pack(1, -1, 0);
        @Override public boolean passable(int x, int y, int z) {
            return !(x == 0 && y == -1 && z == 0) && DigAStar.pack(x, y, z) != obstruction;
        }
        @Override public boolean support(int x, int y, int z) { return x == 0 && y == -1 && z == 0; }
        @Override public double digSeconds(int x, int y, int z) { return y >= 0 ? 1 : INFEASIBLE; }
        @Override public boolean placeable(int x, int y, int z) { return DigAStar.pack(x, y, z) == support; }
        @Override public double placeCost(int x, int y, int z) { return 1; }
        @Override public boolean inBounds(int x, int y, int z) {
            return x >= 0 && x <= 1 && z == 0 && y >= -1 && y <= 3;
        }
        @Override public int maxPlaces() { return 1; }
        @Override public boolean canPlaceFrom(int x, int y, int z, long target, TerrainEdits edits) {
            checks++;
            assertEquals(0, x); assertEquals(0, y); assertEquals(0, z);
            assertEquals(support, target);
            assertTrue(edits.removed().contains(obstruction), "current edge clearing precedes placement");
            assertFalse(edits.placed().contains(support), "the planned support cannot be its own anchor");
            seen = edits;
            return allow;
        }
        @Override public NavigationGeometry geometry() {
            if (!shaped) return null;
            return new StandingGeometry(new StandingGeometry.Access() {
                @Override public boolean known(BlockPos pos) {
                    return inBounds(pos.getX(), pos.getY(), pos.getZ());
                }
                @Override public List<AABB> boxes(BlockPos pos) {
                    return (passable(pos.getX(), pos.getY(), pos.getZ()) ? Blocks.AIR : Blocks.STONE)
                            .defaultBlockState().getCollisionShape(EmptyBlockGetter.INSTANCE, pos).toAabbs();
                }
            }, .6, 1.8, .6);
        }
    }

    @Test void searchAndLiveChecksRejectAPlaceableCellWithoutAnExecutableContact() {
        for (boolean shaped : List.of(false, true)) {
            var terrain = new Terrain(); terrain.shaped = shaped;
            var edits = TerrainEdits.EMPTY.after(List.of(DigAStar.pack(4, 0, 0)), List.of());
            var memo = new MemoDigSampler(terrain);
            var refused = PathTransition.check(memo, edits, 0, 0, 0, 1, 0, 0, 1, 1);
            assertNull(refused.plan());
            assertEquals("PLACEMENT_PRECONDITION", refused.reason());
            assertEquals(terrain.support, refused.cell());
            assertTrue(terrain.seen.removed().contains(DigAStar.pack(4, 0, 0)));
            terrain.allow = true;
            var plan = PathTransition.check(memo, edits, 0, 0, 0, 1, 0, 0, 1, 1).plan();
            assertNotNull(plan);
            assertEquals(List.of(terrain.support), plan.place());
            assertEquals(List.of(terrain.obstruction), plan.dig());
            assertEquals(2, terrain.checks, "contact is live, not memoized");
            terrain.allow = false;
            var step = new DigAStar.Step(1, 0, 0, plan.dig(), plan.place(), DigAStar.ACT_BRIDGE, plan.feetY(), plan.jump());
            var route = List.of(new DigAStar.Step(0, 0, 0, List.of(), List.of(), DigAStar.ACT_START), step);
            assertFalse(PathTask.editsFeasible(route, terrain));
            assertEquals("PLACE_LIMIT", PathTransition.check(terrain, edits,
                    0, 0, 0, 1, 0, 0, 1, 0).reason());
        }
    }

    @Test void terrainViewForwardsTheSuppliedCumulativePrefixWithoutDroppingIt() {
        var terrain = new Terrain();
        terrain.allow = true;
        var edits = TerrainEdits.EMPTY.after(List.of(terrain.obstruction), List.of());
        assertTrue(edits.view(new MemoDigSampler(terrain)).canPlaceFrom(0, 0, 0, terrain.support, edits));
        assertSame(edits, terrain.seen);
    }
}
