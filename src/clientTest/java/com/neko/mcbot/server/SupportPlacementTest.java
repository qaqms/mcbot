package com.neko.mcbot.server;

import com.neko.mcbot.path.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SupportPlacementTest {
    @Test void aHighStairWorkGoalFindsTheRouteAcrossSeveralPendingSupports() {
        var target=new BlockPos(1,9,0);
        var terrain=new Terrain(target,8) {
            @Override public boolean inBounds(int x,int y,int z) { return x==0 && z==0 && y>=-1 && y<=12; }
            @Override public boolean canPlaceFrom(int x,int y,int z,long cell,TerrainEdits edits) {
                var foot=geometry.foot(x,y,z,edits);
                if (foot==null) return false;
                double feet=cell==DigAStar.pack(x,y,z) ? y+1.02 : foot.height();
                return SupportPlacement.canPlan(world,world,
                        new BlockPos(DigAStar.unpackX(cell),DigAStar.unpackY(cell),DigAStar.unpackZ(cell)),
                        new Vec3(x+.5,feet+1.62,z+.5),geometry.body(x,feet,z),inventory.getItem(0),4.5,
                        edits,p->true,f->true,b->true);
            }
        };
        terrain.world.blocks.remove(target);
        terrain.world.blocks.put(target.below(),Blocks.STONE.defaultBlockState());
        terrain.world.blocks.put(new BlockPos(0,-1,0),Blocks.STONE.defaultBlockState());
        var material=new ItemStack(Items.OAK_STAIRS);
        var desired=Blocks.OAK_STAIRS.defaultBlockState();
        var goal=PlacementWork.goal(target,terrain.geometry,1.62,.6,1.8,4.5,(eye,body,edits)->
                SupportPlacement.canPlanWorkBlock(terrain.world,terrain.world,target,eye,body,material,4.5,
                        edits,p->true,f->true,b->true,hit->desired,desired::equals)
                        ? null : new ServerTool.Result(false,"TARGET_BLOCKED:stair contact or collision",null));
        var route=new DigAStar(new MemoDigSampler(terrain),0,0,0,1,9,0,4000,0,goal).segments();
        while (!route.advance(100)) { }
        assertNull(route.failure(),()->route.failure()+" "+route.rejections());
        assertTrue(route.placedCount()>=7);
        var edits=TerrainEdits.EMPTY;
        for (var step:route.path()) edits=edits.after(step.dig(),step.place());
        var end=route.path().getLast();
        assertTrue(goal.reached(end.x(),end.y(),end.z(),edits));
        assertTrue(terrain.world.getBlockState(target).isAir());
        assertEquals(8,terrain.inventory.getItem(0).getCount());
        assertEquals(1,material.getCount());
    }

    @Test void roofStairsAndSlabsCanUseScaffoldingWithoutBecomingRouteSupportMaterials() {
        for (var material:List.of(new ItemStack(Items.OAK_STAIRS),new ItemStack(Items.OAK_SLAB))) {
            var world=new BlockAimTest.Grid(); world.blocks.clear();
            var target=new BlockPos(2,1,0);
            var support=DigAStar.pack(2,0,0);
            var edits=TerrainEdits.EMPTY.after(List.of(),List.of(support));
            var item=(net.minecraft.world.item.BlockItem)material.getItem();
            var desired=item.getBlock().defaultBlockState();
            var body=new AABB(.2,1,.2,.8,2.8,.8);
            var eye=new Vec3(.5,2.62,.5);
            assertFalse(SupportPlacement.canPlanWorkBlock(world,world,target,eye,body,material,4.5,
                    TerrainEdits.EMPTY,p->true,f->true,b->true,hit->desired,desired::equals));
            assertTrue(SupportPlacement.canPlanWorkBlock(world,world,target,eye,body,material,4.5,
                    edits,p->true,f->true,b->true,hit->desired,desired::equals));
            assertFalse(SupportPlacement.canPlanBlock(world,world,target,eye,body,material,4.5,
                    edits,p->true,f->true,b->true,hit->desired,desired::equals));
            assertFalse(SupportPlacement.canPlanWorkBlock(world,world,target,eye,body,material,4.5,
                    edits,p->true,f->true,b->true,hit->desired,s->false));
            assertFalse(SupportPlacement.canPlanWorkBlock(world,world,target,eye,body,material,4.5,
                    edits,p->false,f->true,b->true,hit->desired,desired::equals));
            assertFalse(SupportPlacement.canPlanWorkBlock(world,world,target,eye,body,material,4.5,
                    edits,p->true,f->true,b->false,hit->desired,desired::equals));
            assertFalse(SupportPlacement.canPlanWorkBlock(world,world,target,eye,new AABB(target),material,4.5,
                    edits,p->true,f->true,b->true,hit->desired,desired::equals));
            world.blocks.put(target.below(),Blocks.COBBLESTONE.defaultBlockState());
            assertTrue(SupportPlacement.canPlanWorkBlock(world,world,target,eye,body,material,4.5,
                    TerrainEdits.EMPTY,p->true,f->true,b->true,hit->desired,desired::equals));
            assertTrue(world.getBlockState(target).isAir());
            assertEquals(1,material.getCount());
        }
    }

    @Test void pendingStairCornersAndSlabMergesStillRequireTheirNativeNeighborhoodToLand() {
        var world=new BlockAimTest.Grid(); world.blocks.clear();
        var target=new BlockPos(2,1,0);
        var below=TerrainEdits.EMPTY.after(List.of(),List.of(DigAStar.pack(2,0,0)));
        var corner=below.after(List.of(),List.of(DigAStar.pack(2,1,1)));
        var stair=(net.minecraft.world.item.BlockItem)Items.OAK_STAIRS;
        var slab=(net.minecraft.world.item.BlockItem)Items.OAK_SLAB;
        assertTrue(SupportPlacement.nativeWorkNeighborhood(stair,target,below));
        assertFalse(SupportPlacement.nativeWorkNeighborhood(stair,target,corner));
        assertTrue(SupportPlacement.nativeWorkNeighborhood(slab,target,corner));
        var removedTarget=below.after(List.of(DigAStar.pack(2,1,0)),List.of());
        assertFalse(SupportPlacement.nativeWorkNeighborhood(stair,target,removedTarget));
        assertFalse(SupportPlacement.nativeWorkNeighborhood(slab,target,removedTarget));
        world.blocks.put(target,Blocks.OAK_SLAB.defaultBlockState());
        assertFalse(SupportPlacement.canPlanWorkBlock(world,world,target,new Vec3(.5,2.62,.5),
                new AABB(.2,1,.2,.8,2.8,.8),new ItemStack(slab),4.5,removedTarget,p->true,f->true,b->true,
                hit->Blocks.OAK_SLAB.defaultBlockState(),s->true),"do not predict a double slab from a live half slab");
        world.unknown.add(target);
        assertFalse(SupportPlacement.canPlanWorkBlock(world,world,target,new Vec3(.5,2.62,.5),
                new AABB(.2,1,.2,.8,2.8,.8),new ItemStack(slab),4.5,below,p->true,f->true,b->true,
                hit->Blocks.OAK_SLAB.defaultBlockState(),s->true));
        assertFalse(SupportPlacement.workPredictable((net.minecraft.world.item.BlockItem)Items.CHEST));
    }

    @Test void anAuthoredPillarAxisCanUseNearbyPlannedScaffoldingAndStillRequiresTheRightFace() {
        var target = new BlockPos(1, 9, 0);
        var terrain = new Terrain(target, 8) {
            @Override public boolean inBounds(int x, int y, int z) {
                return x == 0 && z == 0 && y >= -1 && y <= 12;
            }
            @Override public boolean canPlaceFrom(int x, int y, int z, long cell, TerrainEdits edits) {
                var foot = geometry.foot(x,y,z,edits);
                if (foot == null) return false;
                double height = cell == DigAStar.pack(x,y,z) ? y+1.02 : foot.height();
                return SupportPlacement.canPlan(world, world,
                        new BlockPos(DigAStar.unpackX(cell),DigAStar.unpackY(cell),DigAStar.unpackZ(cell)),
                        new Vec3(x+.5,height+1.62,z+.5), geometry.body(x,height,z), inventory.getItem(0),
                        4.5, edits, p->true, f->true, b->true);
            }
        };
        terrain.world.blocks.remove(target);
        terrain.world.blocks.put(target.below(), Blocks.OAK_LOG.defaultBlockState());
        terrain.world.blocks.put(new BlockPos(0,-1,0), Blocks.STONE.defaultBlockState());
        var material = new ItemStack(Items.OAK_LOG);
        var desired = Blocks.OAK_LOG.defaultBlockState();
        java.util.function.Function<net.minecraft.world.phys.BlockHitResult,
                net.minecraft.world.level.block.state.BlockState> prediction = hit -> desired.setValue(
                net.minecraft.world.level.block.RotatedPillarBlock.AXIS, hit.getDirection().getAxis());
        var goal = PlacementWork.goal(target, terrain.geometry, 1.62,.6,1.8,4.5, (eye,body,edits) ->
                SupportPlacement.canPlanBlock(terrain.world,terrain.world,target,eye,body,material,4.5,
                        edits,p->true,f->true,b->true,prediction,desired::equals)
                        ? null : new ServerTool.Result(false,"TARGET_BLOCKED:state or contact",null));
        var route = new DigAStar(new MemoDigSampler(terrain),0,0,0,1,9,0,4000,0,goal).segments();
        while (!route.advance(100)) { }
        assertNull(route.failure(), () -> route.failure()+" "+route.rejections());
        assertTrue(route.placedCount() >= 7);
        assertTrue(route.expanded() < 1000, "a feasible pillar must not flood the reach plateau");
        var end = route.path().getLast();
        var edits = TerrainEdits.EMPTY;
        for (var step : route.path()) edits = edits.after(step.dig(),step.place());
        var foot = terrain.geometry.foot(end.x(),end.y(),end.z(),edits);
        var eye = new Vec3(end.x()+.5,foot.height()+1.62,end.z()+.5);
        var body = terrain.geometry.body(end.x(),foot.height(),end.z());
        assertFalse(SupportPlacement.canPlanBlock(terrain.world,terrain.world,target,eye,body,material,4.5,
                edits,p->true,f->f==Direction.EAST,b->true,prediction,desired::equals));
        assertFalse(SupportPlacement.canPlanBlock(terrain.world,terrain.world,target,eye,body,material,4.5,
                edits,p->true,f->true,b->true,prediction,state->false), "the frozen drawing still decides");
        assertTrue(terrain.world.getBlockState(target).isAir());
        assertEquals(8,terrain.inventory.getItem(0).getCount());
        assertEquals(1,material.getCount());
        assertFalse(SupportPlacement.predictable((net.minecraft.world.item.BlockItem)Items.OAK_STAIRS));
        assertFalse(SupportPlacement.predictable((net.minecraft.world.item.BlockItem)Items.CHEST));
    }

    @Test void aNativeAxisPredictionUsesTheNewSupportInsteadOfRejectingItsWholeNeighborhood() {
        var world = new BlockAimTest.Grid(); world.blocks.clear();
        var target = new BlockPos(2,1,0);
        var material = new ItemStack(Items.OAK_LOG);
        var edits = TerrainEdits.EMPTY.after(List.of(),List.of(DigAStar.pack(2,0,0)));
        var desired = Blocks.OAK_LOG.defaultBlockState();
        var eye = new Vec3(.5,2.62,.5);
        var body = new AABB(.2,1,.2,.8,2.8,.8);
        assertTrue(BlockPlacement.pendingNativeTerrain(target,edits));
        assertTrue(SupportPlacement.canPlanBlock(world,world,target,eye,body,material,4.5,
                edits,p->true,f->true,b->true,hit -> desired.setValue(
                        net.minecraft.world.level.block.RotatedPillarBlock.AXIS,hit.getDirection().getAxis()),
                desired::equals));
        assertTrue(world.getBlockState(target.below()).isAir());
        assertEquals(1,material.getCount());
    }

    @Test void aHighPlacementGoalCanBuildItsOwnColumnUsingCumulativeNativeContacts() {
        var target = new BlockPos(1,9,0);
        var terrain = new Terrain(target,8) {
            @Override public boolean inBounds(int x, int y, int z) {
                return x==0 && z==0 && y>=-1 && y<=12;
            }
            @Override public boolean canPlaceFrom(int x,int y,int z,long cell,TerrainEdits edits) {
                var foot=geometry.foot(x,y,z,edits);
                if (foot==null) return false;
                double feet=cell==DigAStar.pack(x,y,z) ? y+1.02 : foot.height();
                return SupportPlacement.canPlan(world,world,
                        new BlockPos(DigAStar.unpackX(cell),DigAStar.unpackY(cell),DigAStar.unpackZ(cell)),
                        new Vec3(x+.5,feet+1.62,z+.5),geometry.body(x,feet,z),inventory.getItem(0),4.5,
                        edits,p->true,f->true,b->true);
            }
        };
        terrain.world.blocks.remove(target);
        terrain.world.blocks.put(target.below(),Blocks.STONE.defaultBlockState());
        terrain.world.blocks.put(new BlockPos(0,-1,0),Blocks.STONE.defaultBlockState());
        var material=new ItemStack(Items.OAK_PLANKS,1);
        var goal=PlacementWork.goal(target,terrain.geometry,1.62,.6,1.8,4.5,(eye,body,edits)->
                SupportPlacement.canPlanBlock(terrain.world,terrain.world,target,eye,body,material,4.5,
                        edits,p->true,f->true,b->true) ? null
                        : new ServerTool.Result(false,"TARGET_BLOCKED:no contact",null));
        var search=new DigAStar(new MemoDigSampler(terrain),0,0,0,1,9,0,4000,0,goal);
        while(!search.advance(100)) { }
        assertNull(search.failure(),()->search.failure()+" "+search.rejections());
        assertTrue(search.placedCount()>=7, "the top face must actually be visible from the elevated stance");
        assertTrue(search.path().subList(1,search.path().size()).stream()
                .allMatch(step->step.action()==DigAStar.ACT_PILLAR));
        assertTrue(terrain.world.getBlockState(target).isAir());
        assertEquals(8,terrain.inventory.getItem(0).getCount());
        assertEquals(1,material.getCount());
    }

    @Test void successivePillarSupportsUseTheRealTopFaceOnlyAfterTheBodyHasJumpedClear() {
        var world = new BlockAimTest.Grid();
        world.blocks.clear();
        world.blocks.put(new BlockPos(0,-1,0),Blocks.STONE.defaultBlockState());
        var material = new ItemStack(Items.COBBLESTONE,4);
        var geometry = new StandingGeometry(new StandingGeometry.Access() {
            @Override public boolean known(BlockPos pos) { return world.loaded(pos); }
            @Override public List<AABB> boxes(BlockPos pos) {
                return StandingGeometry.collision(world.getBlockState(pos),world,pos);
            }
        },.6,1.8,.6);
        var edits = TerrainEdits.EMPTY;
        for (int y=0; y<4; y++) {
            var target = new BlockPos(0,y,0);
            var body = geometry.body(0,y,0);
            assertFalse(SupportPlacement.canPlan(world,world,target,new Vec3(.5,y+1.62,.5),
                    body,material,4.5,edits,p->true,f->true,b->true));
            var raised = geometry.body(0,y+1.02,0);
            var eye = new Vec3(.5,y+2.64,.5);
            assertTrue(SupportPlacement.canPlan(world,world,target,eye,raised,material,4.5,
                    edits,p->true,f->true,b->true));
            assertFalse(SupportPlacement.canPlan(world,world,target,eye,raised,material,4.5,
                    edits,p->true,f->true,b->false));
            edits=edits.after(List.of(),List.of(DigAStar.pack(0,y,0)));
        }
        assertEquals(1,world.blocks.size());
        assertEquals(4,material.getCount());
    }

    @Test void aPlankWorkStanceCanUseActualRouteClearingAndSupportsWithoutWritingTheWorld() {
        var world=new BlockAimTest.Grid();
        world.blocks.clear();
        var target=new BlockPos(2,1,0);
        var obstruction=new BlockPos(0,1,0);
        var anchor=target.below();
        world.blocks.put(obstruction,Blocks.SPRUCE_LEAVES.defaultBlockState());
        var edits=TerrainEdits.EMPTY.after(List.of(DigAStar.pack(0,1,0)),
                List.of(DigAStar.pack(2,0,0)));
        var material=new ItemStack(Items.SPRUCE_PLANKS,4);
        var eye=new Vec3(.5,1.62,.5);
        var body=new AABB(.2,0,.2,.8,1.8,.8);
        assertFalse(SupportPlacement.canPlanBlock(world,world,target,eye,body,material,4.5,
                TerrainEdits.EMPTY,p->true,face->true,box->true));
        assertTrue(SupportPlacement.canPlanBlock(world,world,target,eye,body,material,4.5,
                edits,p->true,face->true,box->true));
        assertFalse(SupportPlacement.canPlanBlock(world,world,target,eye,body,material,4.5,
                edits,p->false,face->true,box->true));
        assertFalse(SupportPlacement.canPlanBlock(world,world,target,eye,body,material,4.5,
                edits,p->true,face->false,box->true));
        assertFalse(SupportPlacement.canPlanBlock(world,world,target,eye,body,material,4.5,
                edits,p->true,face->true,box->false));
        assertEquals(4,material.getCount());
        assertTrue(world.getBlockState(obstruction).is(Blocks.SPRUCE_LEAVES));
        assertTrue(world.getBlockState(anchor).isAir());
        world.blocks.remove(obstruction);
        world.blocks.put(anchor,Blocks.COBBLESTONE.defaultBlockState());
        assertTrue(SupportPlacement.canPlanBlock(world,world,target,eye,body,material,4.5,
                TerrainEdits.EMPTY,p->true,face->true,box->true),
                "the same stance is valid after the route edits actually happen");
    }
    @BeforeAll static void registries() { BlockActionFixtures.bootstrap(); }

    private static class Terrain implements DigSampler {
        final BlockAimTest.Grid world = new BlockAimTest.Grid();
        final net.minecraft.world.entity.player.Inventory inventory = BlockActionFixtures.inventory();
        final StandingGeometry geometry;
        final BlockPos target;
        boolean digging;
        int maxX = 4, maxY = 9;
        Terrain(BlockPos target, int materials) {
            this.target = target;
            world.blocks.clear();
            world.blocks.put(target, Blocks.OAK_LOG.defaultBlockState());
            if (materials > 0) inventory.setItem(0, new ItemStack(Items.COBBLESTONE, materials));
            geometry = new StandingGeometry(new StandingGeometry.Access() {
                @Override public boolean known(BlockPos pos) { return world.loaded(pos); }
                @Override public List<AABB> boxes(BlockPos pos) {
                    return StandingGeometry.collision(world.getBlockState(pos), world, pos);
                }
            }, .6, 1.8, .6);
        }
        @Override public boolean inBounds(int x, int y, int z) {
            return x >= 0 && x <= maxX && y >= -1 && y <= maxY && z == 0;
        }
        @Override public boolean known(int x, int y, int z) { return world.loaded(new BlockPos(x, y, z)); }
        @Override public NavigationGeometry geometry() { return geometry; }
        @Override public boolean passable(int x, int y, int z) {
            var pos = new BlockPos(x, y, z);
            return world.getBlockState(pos).getCollisionShape(world, pos).isEmpty();
        }
        @Override public boolean support(int x, int y, int z) {
            var pos = new BlockPos(x, y, z);
            return world.getBlockState(pos).isFaceSturdy(world, pos, Direction.UP);
        }
        @Override public double digSeconds(int x, int y, int z) {
            var pos = new BlockPos(x, y, z);
            return digging && inBounds(x, y, z) && y >= 0 && !pos.equals(target)
                    && !pos.equals(target.below()) && world.getBlockState(pos).is(Blocks.STONE) ? .3 : INFEASIBLE;
        }
        @Override public boolean placeable(int x, int y, int z) {
            var pos = new BlockPos(x, y, z);
            return inBounds(x, y, z) && !pos.equals(target) && !pos.equals(target.below()) && passable(x, y, z);
        }
        @Override public double placeCost(int x, int y, int z) { return 1; }
        @Override public int maxPlaces() { return PathMaterials.count(inventory); }
        @Override public boolean canClear(int x, int y, int z, List<Long> cells, TerrainEdits edits) {
            return NavigationWork.canClear(world, new BlockPos(x, y, z), 1.62, 4.5, cells, edits, geometry);
        }
        @Override public boolean canPlaceFrom(int x, int y, int z, long cell, TerrainEdits edits) {
            var foot = geometry.foot(x, y, z, edits);
            if (foot == null) return false;
            var pos = new BlockPos(DigAStar.unpackX(cell), DigAStar.unpackY(cell), DigAStar.unpackZ(cell));
            int slot = PathMaterials.find(inventory);
            return slot >= 0 && SupportPlacement.canPlan(world, world, pos,
                    new Vec3(x + .5, foot.height() + 1.62, z + .5), geometry.body(x, foot.height(), z),
                    inventory.getItem(slot), 4.5, edits, p -> true, face -> true, box -> true);
        }
        DigAStar route(int digs) {
            var goal = NavigationWork.goal(world, target, 1.62, 4.5, false, p -> false, geometry);
            var route = new DigAStar(this, 0, 0, 0, target.getX(), target.getY(), target.getZ(), 4000, digs, goal).segments();
            while (!route.advance(300)) { }
            return route;
        }
    }

    private static Terrain ramp(int stock) {
        var terrain = new Terrain(new BlockPos(4, 8, 0), stock);
        terrain.world.blocks.put(new BlockPos(0, -1, 0), Blocks.STONE.defaultBlockState());
        // A tree/wall supplies real visible side faces; no invisible bridge anchors are invented.
        for (int x = 1; x <= 4; x++) for (int y = -1; y <= 9; y++)
            terrain.world.blocks.put(new BlockPos(x, y, 1), Blocks.STONE.defaultBlockState());
        return terrain;
    }

    @Test void workGoalBuildsSeveralSupportsAndReplaysEveryNativeShapeAndContactInOrder() {
        var terrain = ramp(8);
        int original = terrain.world.blocks.size();
        var route = terrain.route(0);
        assertNull(route.failure(), () -> route.failure() + " " + route.rejections());
        assertTrue(route.placedCount() >= 3);
        assertEquals(original, terrain.world.blocks.size(), "planning never places actual blocks");
        int stock = PathMaterials.count(terrain.inventory);
        var before = route.path().getFirst();
        TerrainEdits history = TerrainEdits.EMPTY;
        boolean crossedPendingNeighbor = false;
        for (var step : route.path().subList(1, route.path().size())) {
            for (long cell : step.place()) {
                var pos = new BlockPos(DigAStar.unpackX(cell), DigAStar.unpackY(cell), DigAStar.unpackZ(cell));
                crossedPendingNeighbor |= BlockPlacement.pendingNativeTerrain(pos, history);
                assertTrue(terrain.canPlaceFrom(before.x(), before.y(), before.z(), cell, TerrainEdits.EMPTY),
                        "every support must also be clickable after earlier real placements");
                terrain.world.blocks.put(pos, Blocks.COBBLESTONE.defaultBlockState());
                terrain.inventory.getItem(PathMaterials.find(terrain.inventory)).shrink(1);
                history = history.after(List.of(), List.of(cell));
            }
            assertNotNull(terrain.geometry.standing(step.x(), step.y(), step.z(), TerrainEdits.EMPTY));
            assertNotEquals(NavigationGeometry.Motion.BLOCKED, terrain.geometry.motion(before.x(), before.feetY(),
                    before.z(), step.x(), step.feetY(), step.z(), TerrainEdits.EMPTY));
            before = step;
        }
        assertTrue(crossedPendingNeighbor, "this route used to be rejected by the blanket native-neighborhood gate");
        assertEquals(stock - route.placedCount(), PathMaterials.count(terrain.inventory));
        assertTrue(NavigationWork.goal(terrain.world, terrain.target, 1.62, 4.5, false, p -> false,
                terrain.geometry).reached(before.x(), before.y(), before.z()));
        assertTrue(terrain.world.blocks.get(terrain.target).is(Blocks.OAK_LOG));
    }

    @Test void noMaterialsOrTooFewMaterialsCannotProduceTheElevatedWorkRoute() {
        for (int stock : new int[]{0, 1}) {
            var terrain = ramp(stock);
            var route = terrain.route(0);
            assertNotNull(route.failure());
            assertEquals(stock, PathMaterials.count(terrain.inventory));
        }
    }

    @Test void miningThroughASealedPassageUsesTheSameWorkGoalWithoutMiningItsTargetOrFloor() {
        var terrain = new Terrain(new BlockPos(7, 1, 0), 0);
        terrain.maxX = 7; terrain.maxY = 3; terrain.digging = true;
        for (int x = 0; x <= 7; x++) {
            terrain.world.blocks.put(new BlockPos(x, -1, 0), Blocks.BEDROCK.defaultBlockState());
            terrain.world.blocks.put(new BlockPos(x, 3, 0), Blocks.BEDROCK.defaultBlockState());
        }
        for (int x = 1; x <= 2; x++) for (int y = 0; y <= 2; y++)
            terrain.world.blocks.put(new BlockPos(x, y, 0), Blocks.STONE.defaultBlockState());
        assertNotNull(terrain.route(0).failure(), "walking alone cannot pass this sealed obstacle");
        var route = terrain.route(8);
        assertNull(route.failure(), () -> route.failure() + " " + route.rejections());
        assertTrue(route.dugCount() >= 4);
        var previous = route.path().getFirst();
        for (var step : route.path().subList(1, route.path().size())) {
            assertTrue(terrain.canClear(previous.x(), previous.y(), previous.z(), step.dig(), TerrainEdits.EMPTY));
            for (long cell : step.dig()) {
                var pos = new BlockPos(DigAStar.unpackX(cell), DigAStar.unpackY(cell), DigAStar.unpackZ(cell));
                assertNotEquals(terrain.target, pos);
                assertNotEquals(terrain.target.below(), pos);
                assertNotNull(terrain.world.blocks.remove(pos));
            }
            assertNotNull(terrain.geometry.standing(step.x(), step.y(), step.z(), TerrainEdits.EMPTY));
            previous = step;
        }
    }

    @Test void hypotheticalSupportsStillRequireRealFacesPermissionKnownSpaceAndUnoccupiedBody() {
        var world = new BlockAimTest.Grid();
        world.blocks.clear();
        var target = new BlockPos(1, 0, 0);
        world.blocks.put(new BlockPos(1, 0, 1), Blocks.STONE.defaultBlockState());
        var eye = new Vec3(.5, 1.62, .5);
        var body = new AABB(.2, 0, .2, .8, 1.8, .8);
        var material = new ItemStack(Items.COBBLESTONE, 3);
        assertTrue(SupportPlacement.canPlan(world, world, target, eye, body, material, 4.5,
                TerrainEdits.EMPTY, p -> true, face -> true, box -> true));
        assertFalse(SupportPlacement.canPlan(world, world, target, eye, body, material, 4.5,
                TerrainEdits.EMPTY, p -> false, face -> true, box -> true));
        assertFalse(SupportPlacement.canPlan(world, world, target, eye, body, material, 4.5,
                TerrainEdits.EMPTY, p -> true, face -> false, box -> true));
        assertFalse(SupportPlacement.canPlan(world, world, target, eye, body, material, 4.5,
                TerrainEdits.EMPTY, p -> true, face -> true, box -> false));
        world.unknown.add(target.above());
        assertFalse(SupportPlacement.canPlan(world, world, target, eye, body, material, 4.5,
                TerrainEdits.EMPTY, p -> true, face -> true, box -> true));
        world.unknown.clear();
        assertFalse(SupportPlacement.canPlan(world, world, target, eye, new AABB(target), material, 4.5,
                TerrainEdits.EMPTY, p -> true, face -> true, box -> true));
        var removedFace = TerrainEdits.EMPTY.after(List.of(DigAStar.pack(1, 0, 1)), List.of());
        assertFalse(SupportPlacement.canPlan(world, world, target, eye, body, material, 4.5,
                removedFace, p -> true, face -> true, box -> true));
        world.blocks.clear();
        var plannedFace = TerrainEdits.EMPTY.after(List.of(), List.of(DigAStar.pack(1, 0, 1)));
        assertTrue(SupportPlacement.canPlan(world, world, target, eye, body, material, 4.5,
                plannedFace, p -> true, face -> true, box -> true));
        assertTrue(world.blocks.isEmpty());
        assertFalse(SupportPlacement.canPlan(world, world, target, eye, body, new ItemStack(Items.OAK_SLAB), 4.5,
                plannedFace, p -> true, face -> true, box -> true));
    }
}
