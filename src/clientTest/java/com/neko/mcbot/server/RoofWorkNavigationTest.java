package com.neko.mcbot.server;

import com.neko.mcbot.path.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.StairBlock;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class RoofWorkNavigationTest {
    @BeforeAll static void bootstrap() { BlockActionFixtures.bootstrap(); }

    @Test void aBodyOnTheLoggedSolidRoofCanOpenAndDescendToARealGroundFloorPlacementContact() {
        var world=new BlockAimTest.Grid(); world.blocks.clear();
        var target=new BlockPos(214,69,62);
        for (int x=209;x<=223;x++) for (int z=55;z<=67;z++)
            world.blocks.put(new BlockPos(x,68,z),Blocks.DIRT.defaultBlockState());
        for (int x=212;x<=220;x++) for (int z=58;z<=64;z++) {
            if (!new BlockPos(x,69,z).equals(target))
                world.blocks.put(new BlockPos(x,69,z),Blocks.OAK_PLANKS.defaultBlockState());
            if (x==212 || x==220 || z==58 || z==64) for (int y=70;y<=72;y++)
                world.blocks.put(new BlockPos(x,y,z),Blocks.COBBLESTONE.defaultBlockState());
        }
        for (int k=0;k<=2;k++) for (int x=212;x<=220;x++) for (int z=58+k;z<=64-k;z++)
            world.blocks.put(new BlockPos(x,73+k,z),
                    z==58+k || z==64-k ? Blocks.OAK_STAIRS.defaultBlockState()
                            .setValue(StairBlock.FACING,z==58+k ? Direction.NORTH : Direction.SOUTH)
                            : Blocks.OAK_PLANKS.defaultBlockState());
        var geometry=new StandingGeometry(new StandingGeometry.Access() {
            @Override public boolean known(BlockPos p) { return true; }
            @Override public List<net.minecraft.world.phys.AABB> boxes(BlockPos p) {
                return StandingGeometry.collision(world.getBlockState(p),world,p);
            }
        },.6,1.8,.6);
        DigSampler terrain=new DigSampler() {
            @Override public NavigationGeometry geometry() { return geometry; }
            @Override public boolean passable(int x,int y,int z) {
                return world.getBlockState(new BlockPos(x,y,z)).getCollisionShape(world,new BlockPos(x,y,z)).isEmpty();
            }
            @Override public boolean support(int x,int y,int z) { return !passable(x,y,z); }
            @Override public double digSeconds(int x,int y,int z) {
                return y>=70 && y<=75 && com.neko.mcbot.building.BuildSite.restorable(
                        world.getBlockState(new BlockPos(x,y,z))) ? .2 : INFEASIBLE;
            }
            @Override public boolean placeable(int x,int y,int z) { return false; }
            @Override public double placeCost(int x,int y,int z) { return INFEASIBLE; }
            @Override public int maxPlaces() { return 0; }
            @Override public boolean inBounds(int x,int y,int z) {
                return x>=209 && x<=223 && z>=55 && z<=67 && y>=68 && y<=78;
            }
            @Override public boolean canClear(int x,int y,int z,List<Long> cells,TerrainEdits edits) {
                return NavigationWork.canClear(world,new BlockPos(x,y,z),1.62,4.5,cells,edits,geometry);
            }
        };
        var goal=PlacementWork.goal(target,geometry,1.62,.6,1.8,4.5,(eye,body,edits)->
                SupportPlacement.canPlanBlock(world,world,target,eye,body,new ItemStack(Items.OAK_PLANKS),
                        4.5,edits,p->true,f->true,b->true) ? null
                        : new ServerTool.Result(false,"TARGET_BLOCKED:no visible native contact",null));
        assertFalse(goal.reached(214,76,62));
        var original=java.util.Map.copyOf(world.blocks);
        var route=new DigAStar(new MemoDigSampler(terrain),214,76,62,214,69,62,4000,16,goal).segments();
        while (!route.advance(100)) { }
        assertNull(route.failure(),()->route.failure()+" "+route.rejections());
        assertTrue(route.path().stream().anyMatch(s->s.action()==DigAStar.ACT_LOWER));
        assertTrue(route.dugCount()>0);
        System.out.println("[roof-work] expanded="+route.expanded()+" nodes="+route.path().size()
                +" digs="+route.dugCount()+" places="+route.placedCount());
        assertEquals(original,world.blocks,"route planning cannot edit the saved scene");
        var edits=TerrainEdits.EMPTY;
        for (var step:route.path()) edits=edits.after(step.dig(),step.place());
        var end=route.path().getLast();
        assertTrue(goal.reached(end.x(),end.y(),end.z(),edits));
        assertTrue(edits.removed().stream().noneMatch(p->DigAStar.unpackY(p)<70),
                "the requested floor and its actual supporting ground remain untouched");
    }
}
