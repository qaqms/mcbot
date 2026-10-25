package com.neko.mcbot.path;

import com.neko.mcbot.server.BlockActionFixtures;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PillarConstructionTest {
    @BeforeAll static void bootstrap() { BlockActionFixtures.bootstrap(); }

    @Test void aNarrowConstructionColumnCanReachHighWorkUsingSuccessiveRealSupports() {
        var terrain = new WorkRelocationTest.Terrain() {
            @Override public boolean allowsStep(int fx, int fy, int fz, int x, int y, int z) {
                return x == 0 && z == 0;
            }
        };
        terrain.stock = 4;
        var search = new DigAStar(new MemoDigSampler(terrain), 0, 0, 0, 0, 4, 0, 4000, 0,
                NavigationGoal.position(0, 4, 0, 0));
        while (!search.advance(100)) { }
        assertNull(search.failure(), () -> search.failure() + " " + search.rejections());
        assertEquals(4, search.placedCount());
        assertEquals(49, terrain.blocks.size(), "planning must not place or consume material");
        assertEquals(search.path(), PathTask.liveSteps(search.path(), 0, terrain));
        assertNull(PathTask.planFailure(search.path(), terrain, 0));
        for (var step : search.path().subList(1, search.path().size())) {
            assertEquals(DigAStar.ACT_PILLAR, step.action());
            assertEquals(List.of(DigAStar.pack(0, step.y() - 1, 0)), step.place());
            assertTrue(step.jump());
        }
        terrain.attachment = false;
        assertNotNull(PathTask.planFailure(search.path(), terrain, 0));
    }

    @Test void pillarRequiresMaterialClearJumpEnvelopeAndAnExecutableClick() {
        var terrain = new WorkRelocationTest.Terrain();
        assertNull(PathTransition.check(terrain, TerrainEdits.EMPTY, 0, 0, 0, 0, 1, 0, 0, 0).plan());
        terrain.stock = 2;
        assertNotNull(PathTransition.check(terrain, TerrainEdits.EMPTY, 0, 0, 0, 0, 1, 0, 0, 2).plan());
        terrain.attachment = false;
        assertNull(PathTransition.check(terrain, TerrainEdits.EMPTY, 0, 0, 0, 0, 1, 0, 0, 2).plan());
        terrain.attachment = true;
        terrain.blocks.put(new BlockPos(0, 3, 0), Blocks.STONE.defaultBlockState());
        assertNull(PathTransition.check(terrain, TerrainEdits.EMPTY, 0, 0, 0, 0, 1, 0, 0, 2).plan(),
                "the jump apex needs more space than the final standing pose");
        var cleared = PathTransition.check(terrain, TerrainEdits.EMPTY, 0, 0, 0, 0, 1, 0, 1, 2).plan();
        assertNotNull(cleared);
        assertEquals(List.of(DigAStar.pack(0, 3, 0)), cleared.dig());
        terrain.sight = false;
        assertNull(PathTransition.check(terrain, TerrainEdits.EMPTY, 0, 0, 0, 0, 1, 0, 1, 2).plan());
    }

    @Test void fractionalAndUnsafeDepartureCannotBeTurnedIntoAFullBlockPillar() {
        var terrain = new WorkRelocationTest.Terrain();
        terrain.stock = 4;
        terrain.blocks.put(BlockPos.ZERO, Blocks.OAK_SLAB.defaultBlockState());
        assertNull(PathTransition.check(terrain, TerrainEdits.EMPTY, 0, 0, 0, 0, 1, 0, 4, 4).plan());
        terrain.blocks.put(BlockPos.ZERO, Blocks.WATER.defaultBlockState());
        assertNull(PathTransition.check(terrain, TerrainEdits.EMPTY, 0, 0, 0, 0, 1, 0, 4, 4).plan());
    }

    @Test void placedSupportIsValidatedFromTheAirborneBodyInsteadOfTheNowOccupiedDeparture() {
        var terrain = new WorkRelocationTest.Terrain();
        terrain.stock = 2;
        var plan = PathTransition.check(terrain, TerrainEdits.EMPTY, 0, 0, 0, 0, 1, 0, 0, 2).plan();
        assertNotNull(plan);
        var step = new DigAStar.Step(0, 1, 0, List.of(), List.of(), DigAStar.ACT_PILLAR, 1, true);
        assertFalse(PathTask.pillarLanding(terrain.geometry(), step, 1.1), "no actual support yet");
        terrain.blocks.put(BlockPos.ZERO, Blocks.COBBLESTONE.defaultBlockState());
        assertTrue(PathTask.pillarLanding(terrain.geometry(), step, 1.1));
        assertFalse(PathTask.pillarLanding(terrain.geometry(), step, .9), "the block must not intersect the body");
        terrain.blocks.put(new BlockPos(0, 2, 0), Blocks.STONE.defaultBlockState());
        assertFalse(PathTask.pillarLanding(terrain.geometry(), step, 1.1));
    }

    @Test void pillarClickHeightOnlyChangesForTheExactDepartureCell() {
        assertEquals(1.02, LevelDigSampler.placementHeight(0, 0, 0, DigAStar.pack(0, 0, 0), 0));
        assertEquals(.5, LevelDigSampler.placementHeight(0, 0, 0, DigAStar.pack(1, 0, 0), .5));
    }
}
