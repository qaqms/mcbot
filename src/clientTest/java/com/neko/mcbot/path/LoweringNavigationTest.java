package com.neko.mcbot.path;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class LoweringNavigationTest {
    @BeforeAll static void bootstrap() { com.neko.mcbot.server.BlockActionFixtures.bootstrap(); }

    static WorkRelocationTest.Terrain column() {
        var terrain = new WorkRelocationTest.Terrain() {
            @Override public boolean inBounds(int x,int y,int z) { return x==0 && z==0 && y>=-1 && y<=5; }
        };
        terrain.blocks.put(BlockPos.ZERO,Blocks.OAK_PLANKS.defaultBlockState());
        terrain.blocks.put(BlockPos.ZERO.above(),Blocks.OAK_PLANKS.defaultBlockState());
        return terrain;
    }

    @Test void searchCanDescendTwoRoofLayersWithTheSamePlansUsedForLiveValidation() {
        var terrain = column();
        var before = java.util.Map.copyOf(terrain.blocks);
        var route = new DigAStar(terrain,0,2,0,0,0,0,4000,2,NavigationGoal.position(0,0,0,0)).segments();
        while (!route.advance(100)) { }
        assertNull(route.failure(),route.failure());
        assertEquals(2,route.dugCount());
        assertTrue(route.path().stream().skip(1).allMatch(s->s.action()==DigAStar.ACT_LOWER));
        assertNull(PathTask.planFailure(route.path(),terrain,2));
        assertEquals(route.path(),PathTask.liveSteps(route.path(),2,terrain));
        assertEquals(before,terrain.blocks);
        assertNotNull(PathTask.planFailure(route.path(),terrain,1));
    }

    @Test void loweringNeedsActualSightDigAllowanceAndAKnownSafeLanding() {
        var terrain = column();
        assertNotNull(PathTransition.check(terrain,TerrainEdits.EMPTY,0,2,0,0,1,0,1,0).plan());
        assertNull(PathTransition.check(terrain,TerrainEdits.EMPTY,0,2,0,0,1,0,0,0).plan());
        terrain.sight=false;
        assertNull(PathTransition.check(terrain,TerrainEdits.EMPTY,0,2,0,0,1,0,1,0).plan());
        terrain.sight=true;
        terrain.blocks.remove(BlockPos.ZERO);
        assertNull(PathTransition.check(terrain,TerrainEdits.EMPTY,0,2,0,0,1,0,1,0).plan(),
                "do not dig a roof into an unsupported room below");
        terrain.blocks.put(BlockPos.ZERO,Blocks.MAGMA_BLOCK.defaultBlockState());
        assertNull(PathTransition.check(terrain,TerrainEdits.EMPTY,0,2,0,0,1,0,1,0).plan());
    }

    @Test void loweringCostsAndValidationUseTheHypotheticalStanceInsteadOfTheOriginalBodyFloor() {
        var terrain=new WorkRelocationTest.Terrain() {
            @Override public boolean inBounds(int x,int y,int z) { return x==0 && z==0 && y>=-1 && y<=5; }
            @Override public double digSeconds(int x,int y,int z) { return INFEASIBLE; }
            @Override public double loweringSeconds(int x,int y,int z,TerrainEdits edits) {
                return y==2 || y==1 && edits.removed().contains(DigAStar.pack(0,1,0)) ? 1 : INFEASIBLE;
            }
        };
        terrain.blocks.put(BlockPos.ZERO,Blocks.OAK_PLANKS.defaultBlockState());
        terrain.blocks.put(BlockPos.ZERO.above(),Blocks.OAK_PLANKS.defaultBlockState());
        var route=new DigAStar(new MemoDigSampler(terrain),0,2,0,0,0,0,4000,2);
        while (!route.advance(100)) { }
        assertNull(route.failure(),route.failure());
        assertTrue(route.pathCost()<100,"lowering must not be charged as an infeasible ordinary excavation");
        assertNull(PathTask.planFailure(route.path(),terrain,2));
    }

    @Test void anAlreadyRemovedSupportUsesTheMeasuredAirborneColumnUntilTheLandingIsSettled() {
        var terrain = column();
        terrain.blocks.remove(BlockPos.ZERO.above());
        var next = new DigAStar.Step(0,1,0,List.of(),List.of(),DigAStar.ACT_LOWER,1,false);
        assertTrue(PathTask.pillarLanding(terrain.geometry(),next,1.9));
        terrain.blocks.put(BlockPos.ZERO.above(2),Blocks.OAK_PLANKS.defaultBlockState());
        assertFalse(PathTask.pillarLanding(terrain.geometry(),next,1.9));
    }
}
