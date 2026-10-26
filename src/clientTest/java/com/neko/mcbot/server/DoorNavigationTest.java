package com.neko.mcbot.server;

import com.neko.mcbot.path.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class DoorNavigationTest {
    @BeforeAll static void bootstrap() { BlockActionFixtures.bootstrap(); }
    static class Scene implements DigSampler {
        final BlockAimTest.Grid world=new BlockAimTest.Grid();
        final StandingGeometry geometry;
        boolean interaction=true;
        Scene() {
            world.blocks.clear();
            for (int x=-3;x<=3;x++) for (int z=-2;z<=2;z++)
                world.blocks.put(new BlockPos(x,-1,z),Blocks.STONE.defaultBlockState());
            for (int z=-2;z<=2;z++) for (int y=0;y<=2;y++)
                world.blocks.put(new BlockPos(0,y,z),Blocks.OAK_PLANKS.defaultBlockState());
            door(false);
            geometry=new StandingGeometry(new StandingGeometry.Access() {
                @Override public boolean known(BlockPos pos) { return world.loaded(pos); }
                @Override public List<net.minecraft.world.phys.AABB> boxes(BlockPos pos) {
                    return StandingGeometry.collision(world.getBlockState(pos),world,pos);
                }
                @Override public List<net.minecraft.world.phys.AABB> toggledBoxes(BlockPos pos) {
                    return StandingGeometry.collision(DoorTraversal.flip(world.getBlockState(pos)),world,pos);
                }
            },.6,1.8,.6);
        }
        void door(boolean iron) {
            var state=(iron ? Blocks.IRON_DOOR : Blocks.OAK_DOOR).defaultBlockState()
                    .setValue(DoorBlock.FACING,Direction.EAST);
            world.blocks.put(BlockPos.ZERO,state);
            world.blocks.put(BlockPos.ZERO.above(),state.setValue(DoorBlock.HALF,DoubleBlockHalf.UPPER));
        }
        @Override public boolean inBounds(int x,int y,int z) { return x>=-3 && x<=3 && y>=-1 && y<=4 && z>=-2 && z<=2; }
        @Override public boolean known(int x,int y,int z) { return world.loaded(new BlockPos(x,y,z)); }
        @Override public NavigationGeometry geometry() { return geometry; }
        @Override public boolean passable(int x,int y,int z) {
            return world.getBlockState(new BlockPos(x,y,z)).getCollisionShape(world,new BlockPos(x,y,z)).isEmpty();
        }
        @Override public boolean support(int x,int y,int z) {
            return world.getBlockState(new BlockPos(x,y,z)).is(Blocks.STONE);
        }
        @Override public double digSeconds(int x,int y,int z) {
            return world.getBlockState(new BlockPos(x,y,z)).is(Blocks.OAK_PLANKS) ? .1 : INFEASIBLE;
        }
        @Override public boolean placeable(int x,int y,int z) { return false; }
        @Override public double placeCost(int x,int y,int z) { return INFEASIBLE; }
        @Override public int maxPlaces() { return 0; }
        @Override public List<Long> toggleCells(int x,int y,int z) {
            return DoorTraversal.cells(world,new BlockPos(x,y,z),p->interaction && world.loaded(p)
                    && inBounds(p.getX(),p.getY(),p.getZ()));
        }
        @Override public boolean canToggleFrom(int x,int y,int z,long target,TerrainEdits edits) {
            return DoorTraversal.reachable(world,world,geometry,x,y,z,target,edits,1.62,4.5);
        }
    }
    @Test void anExistingDoorPassageWinsOverBreakingTheCheapWallAndWorksWithoutDigs() {
        var scene=new Scene();
        var original=java.util.Map.copyOf(scene.world.blocks);
        for (int digs:List.of(0,32)) {
            var search=new DigAStar(new MemoDigSampler(scene),-2,0,0,2,0,0,4000,digs,
                    NavigationGoal.position(2,0,0,0)).segments().preferExistingPaths();
            while(!search.advance(300)) { }
            assertNull(search.failure(),()->search.failure()+" "+search.rejections());
            assertEquals(0,search.dugCount(),"the existing door does not require wall demolition");
            assertEquals(0,search.placedCount());
            assertTrue(search.path().stream().anyMatch(step->step.toggle().size()==2));
            var edits=TerrainEdits.EMPTY;
            for (var step:search.path()) edits=edits.after(step.dig(),step.place()).toggle(step.toggle());
            assertTrue(edits.toggled().contains(DigAStar.pack(0,0,0)));
            assertTrue(edits.toggled().contains(DigAStar.pack(0,1,0)));
        }
        assertEquals(original,scene.world.blocks,"planning does not operate a native door");
    }
    @Test void ironDoorsUnknownHalvesAndDeniedInteractionAreNotHandOpened() {
        var scene=new Scene();
        scene.door(true);
        assertTrue(scene.toggleCells(0,0,0).isEmpty());
        scene.door(false); scene.world.unknown.add(BlockPos.ZERO.above());
        assertTrue(scene.toggleCells(0,0,0).isEmpty());
        scene.world.unknown.clear(); scene.interaction=false;
        assertTrue(scene.toggleCells(0,0,0).isEmpty());
        var search=new DigAStar(scene,-2,0,0,2,0,0,4000,0,NavigationGoal.position(2,0,0,0));
        while(!search.advance(300)) { }
        assertNotNull(search.failure());
    }
    @Test void planningCanUseTheVisibleUpperHalfWhenTheLowerHalfCannotBeClicked() {
        var scene=new Scene();
        scene.world.blocks.put(new BlockPos(-1,0,0),Blocks.STONE.defaultBlockState());
        var eye=new net.minecraft.world.phys.Vec3(-.5,2.62,.5);
        var sight=BlockAim.fromEye(scene.world,eye);
        assertFalse(BlockAim.mining(sight,BlockPos.ZERO,1.2).ok());
        assertTrue(BlockAim.mining(sight,BlockPos.ZERO.above(),1.2).ok());
        assertTrue(DoorTraversal.reachable(scene.world,scene.world,scene.geometry,
                -1,1,0,DigAStar.pack(0,0,0),TerrainEdits.EMPTY,1.62,1.2));
    }
    @Test void aTurnedDoorStillNeedsRealCollisionClearanceAndBothHalvesHaveOneEdit() {
        var scene=new Scene();
        var parts=scene.toggleCells(0,1,0);
        assertEquals(scene.toggleCells(0,0,0),parts);
        var edits=TerrainEdits.EMPTY.toggle(parts);
        var straight=scene.geometry.clearance(-1,0,0,0,0,0,edits);
        assertTrue(straight.known()); assertTrue(straight.blockers().isEmpty());
        assertEquals(TerrainEdits.EMPTY,edits.toggle(parts));
        assertEquals(2,parts.size());
        var closed=scene.geometry.clearance(-1,0,0,0,0,0,TerrainEdits.EMPTY);
        assertFalse(closed.blockers().isEmpty());
        assertFalse(DoorTraversal.openable(Blocks.IRON_TRAPDOOR.defaultBlockState()));
        assertTrue(DoorTraversal.openable(Blocks.OAK_FENCE_GATE.defaultBlockState()));
        assertTrue(DoorTraversal.openable(Blocks.OAK_TRAPDOOR.defaultBlockState()));
    }
}
