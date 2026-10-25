package com.neko.mcbot.path;

import com.neko.mcbot.server.BlockActionFixtures;
import com.neko.mcbot.server.StandingGeometry;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class WorkRelocationTest {
    @BeforeAll static void bootstrap() { BlockActionFixtures.bootstrap(); }

    static class Terrain implements DigSampler, StandingGeometry.Access {
        final Map<BlockPos, BlockState> blocks = new HashMap<>();
        boolean sight = true, attachment = true;
        int stock;
        Terrain() {
            for (int x = -3; x <= 3; x++) for (int z = -3; z <= 3; z++)
                blocks.put(new BlockPos(x, -1, z), Blocks.STONE.defaultBlockState());
        }
        @Override public boolean known(BlockPos pos) { return true; }
        @Override public List<net.minecraft.world.phys.AABB> boxes(BlockPos pos) {
            return StandingGeometry.collision(blocks.getOrDefault(pos, Blocks.AIR.defaultBlockState()),
                    EmptyBlockGetter.INSTANCE, pos);
        }
        @Override public boolean passable(int x, int y, int z) { return !blocks.containsKey(new BlockPos(x,y,z)); }
        @Override public boolean support(int x, int y, int z) { return !passable(x,y,z); }
        @Override public double digSeconds(int x, int y, int z) {
            return y >= 0 && support(x,y,z) ? 1 : INFEASIBLE;
        }
        @Override public boolean placeable(int x, int y, int z) { return stock > 0 && passable(x,y,z); }
        @Override public double placeCost(int x, int y, int z) { return 1; }
        @Override public int maxPlaces() { return stock; }
        @Override public boolean inBounds(int x, int y, int z) {
            return Math.abs(x) <= 3 && Math.abs(z) <= 3 && y >= -1 && y <= 5;
        }
        @Override public StandingGeometry geometry() { return new StandingGeometry(this,.6,1.8,.6); }
        @Override public boolean canClear(int x, int y, int z, List<Long> cells) { return sight; }
        @Override public boolean canPlaceFrom(int x,int y,int z,long pos,TerrainEdits edits) { return attachment; }
    }

    @Test void aBodyInTheDrawingChoosesAnAdjacentClearStanceBeforeAnyTerrainEdits() {
        var terrain = new Terrain();
        terrain.stock = 4;
        var step = WorkRelocation.choose(terrain,0,0,0,12, candidate -> candidate.x() != 0);
        assertNotNull(step);
        assertNotEquals(0,step.x());
        assertTrue(step.dig().isEmpty());
        assertTrue(step.place().isEmpty());
        assertEquals(49,terrain.blocks.size(),"planning never edits the world");
    }

    @Test void aClosedWorkColumnCanOpenASideOnlyWithTheActualClearanceAndDigAllowance() {
        var terrain = new Terrain();
        terrain.blocks.put(new BlockPos(1,0,0),Blocks.STONE.defaultBlockState());
        terrain.blocks.put(new BlockPos(1,1,0),Blocks.STONE.defaultBlockState());
        java.util.function.Predicate<DigAStar.Step> east = s -> s.x()==1 && s.y()==0 && s.z()==0;
        assertNull(WorkRelocation.choose(terrain,0,0,0,0,east));
        var step = WorkRelocation.choose(terrain,0,0,0,2,east);
        assertNotNull(step);
        assertEquals(List.of(DigAStar.pack(1,1,0),DigAStar.pack(1,0,0)),step.dig());
        var route = List.of(new DigAStar.Step(0,0,0,List.of(),List.of(),DigAStar.ACT_START),step);
        assertNull(PathTask.planFailure(route,terrain,2));
        terrain.sight = false;
        assertNull(WorkRelocation.choose(terrain,0,0,0,2,east));
    }

    @Test void aScaffoldExitNeedsBothRealMaterialAndAPassedAttachmentPreflight() {
        var terrain = new Terrain();
        terrain.blocks.remove(new BlockPos(1,-1,0));
        java.util.function.Predicate<DigAStar.Step> east = s -> s.x()==1 && s.y()==0 && s.z()==0;
        assertNull(WorkRelocation.choose(terrain,0,0,0,0,east));
        terrain.stock = 1;
        var step = WorkRelocation.choose(terrain,0,0,0,0,east);
        assertNotNull(step);
        assertEquals(List.of(DigAStar.pack(1,-1,0)),step.place());
        assertNull(PathTask.planFailure(List.of(new DigAStar.Step(0,0,0,List.of(),List.of(),
                DigAStar.ACT_START),step),terrain,0));
        terrain.attachment = false;
        assertNull(WorkRelocation.choose(terrain,0,0,0,0,east));
    }
}
