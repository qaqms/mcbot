package com.neko.mcbot.server;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.neko.mcbot.building.BuildTarget;
import com.neko.mcbot.path.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Relative block-only save excerpt; native shapes/rays run offline, while permissions and state prediction are fixtures. */
class HouseWorkSearchTest {
    @BeforeAll static void bootstrap() { BlockActionFixtures.bootstrap(); }

    @Test void upperWallWorkFromABedFindsAnExecutableApproachBeforeFloodingEditHistories() throws Exception {
        var scene = new Scene();
        var target = new BlockPos(0,3,0);
        scene.world.blocks.remove(target);
        var goal = scene.goal(target);
        assertEquals(.5625,scene.geometry.foot(1,0,1,TerrainEdits.EMPTY).height());
        assertFalse(goal.reached(1,0,1));
        scene.check(goal, target);
    }

    @Test void aSameLayerRoofSetFromABedFindsARealWorkMember() throws Exception {
        var scene = new Scene();
        var goals = new ArrayList<NavigationGoal>();
        for (int z=0;z<=10;z++) goals.add(scene.stairGoal(new BlockPos(0,4,z)));
        for (int x=1;x<=3;x++) {
            goals.add(scene.goal(new BlockPos(x,4,0)));
            if (x<3) goals.add(scene.goal(new BlockPos(x,4,10)));
        }
        assertEquals(16,goals.size());
        var goal=NavigationGoal.anyOf(goals);
        assertFalse(goal.reached(1,0,1));
        scene.check(goal,new BlockPos(0,4,0));
    }

    @Test void protectedWallsDoNotHideTheScaffoldApproachToTheSameRoofSet() throws Exception {
        var scene = new Scene();
        scene.preserveWalls = true;
        var goals = new ArrayList<NavigationGoal>();
        for (int z=0;z<=10;z++) goals.add(scene.stairGoal(new BlockPos(0,4,z)));
        for (int x=1;x<=3;x++) {
            goals.add(scene.goal(new BlockPos(x,4,0)));
            if (x<3) goals.add(scene.goal(new BlockPos(x,4,10)));
        }
        var route=scene.check(NavigationGoal.anyOf(goals),new BlockPos(0,4,0));
        assertEquals(0,route.dugCount(),"the protected house stays intact");
        assertTrue(route.placedCount()>0,"the route must actually plan the needed scaffold");
    }

    @Test void anExistingStairAroundTheUpperFloorBeatsBreakingThroughOrPillaring() throws Exception {
        var scene = new Scene();
        scene.world.blocks.clear();
        for (int x=5;x<=15;x++) for(int z=-1;z<=13;z++)
            scene.world.blocks.put(new BlockPos(x,-1,z),Blocks.STONE.defaultBlockState());
        for (int x=8;x<=12;x++) for(int z=0;z<=8;z++)
            scene.world.blocks.put(new BlockPos(x,2,z),Blocks.OAK_PLANKS.defaultBlockState());
        scene.world.blocks.put(new BlockPos(8,0,10),Blocks.COBBLESTONE.defaultBlockState());
        scene.world.blocks.put(new BlockPos(8,1,9),Blocks.COBBLESTONE.defaultBlockState());
        scene.world.blocks.put(new BlockPos(8,2,8),Blocks.COBBLESTONE.defaultBlockState());
        var target=new BlockPos(10,7,4);
        scene.world.blocks.put(target.east(),Blocks.OAK_PLANKS.defaultBlockState());
        var goal=scene.goal(target);
        assertFalse(goal.reached(11,0,4));
        assertTrue(goal.reached(10,3,4),"the upper floor has a real, visible placement contact");
        var route=scene.check(goal,target,new BlockPos(11,0,4));
        assertEquals(0,route.dugCount(),"preserve the upper floor while using the existing stair");
        assertEquals(0,route.placedCount(),"do not make another pillar beside an existing way up");
        assertTrue(route.path().stream().anyMatch(step->step.z()>=9),
                "the actual stair initially leads away from the work target");
    }

    @Test void theLatestUpperRoofDoesNotSpendItsSearchUnderTheWorkingHeight() throws Exception {
        var scene = new Scene("/navigation/upper-house-20261026.json");
        scene.preserveWalls = true;
        var goals = new ArrayList<NavigationGoal>();
        for (int z=0;z<=10;z++) goals.add(scene.goal(new BlockPos(1,6,z)));
        for (int z=0;z<=4;z++) goals.add(scene.goal(new BlockPos(2,6,z)));
        scene.check(NavigationGoal.anyOf(goals),new BlockPos(1,6,0),new BlockPos(6,0,-1));
    }

    static class Scene {
        final BlockAimTest.Grid world = new BlockAimTest.Grid();
        final StandingGeometry geometry;
        final DigSampler terrain;
        boolean preserveWalls;
        Scene() throws Exception { this("/navigation/house-work-scene.json"); }
        Scene(String resource) throws Exception {
            world.blocks.clear();
            try (var input = getClass().getResourceAsStream(resource)) {
                assertNotNull(input);
                var json=JsonParser.parseReader(new InputStreamReader(input,StandardCharsets.UTF_8)).getAsJsonObject();
                var palette=new ArrayList<BlockState>();
                for (var entry:json.getAsJsonArray("states")) {
                    var state=entry.getAsJsonObject();
                    var cell=new JsonObject();
                    cell.addProperty("x",0); cell.addProperty("y",0); cell.addProperty("z",0);
                    cell.addProperty("block",state.get("Name").getAsString());
                    var properties=new JsonArray();
                    if (state.has("Properties")) state.getAsJsonObject("Properties").entrySet().forEach(p->{
                        var property=new JsonObject();
                        property.addProperty("name",p.getKey()); property.add("value",p.getValue());
                        properties.add(property);
                    });
                    cell.add("properties",properties);
                    var drawing=JsonParser.parseString("{\"anchor\":{\"x\":0,\"y\":0,\"z\":0},\"cells\":[]}").getAsJsonObject();
                    drawing.getAsJsonArray("cells").add(cell);
                    palette.add(BuildTarget.parse(drawing).cells().getFirst().desired());
                }
                for (var entry:json.getAsJsonArray("runs")) {
                    var row=entry.getAsJsonArray();
                    for (int z=row.get(2).getAsInt();z<=row.get(3).getAsInt();z++)
                        world.blocks.put(new BlockPos(row.get(0).getAsInt(),row.get(1).getAsInt(),z),
                                palette.get(row.get(4).getAsInt()));
                }
            }
            geometry=new StandingGeometry(new StandingGeometry.Access() {
                @Override public boolean known(BlockPos p) { return true; }
                @Override public List<AABB> boxes(BlockPos p) {
                    return StandingGeometry.collision(world.getBlockState(p),world,p);
                }
            },.6,1.8,.6);
            terrain=new DigSampler() {
                @Override public NavigationGeometry geometry() { return geometry; }
                @Override public boolean inBounds(int x,int y,int z) {
                    return x>=-9 && x<=17 && z>=-9 && z<=21 && y>=-4 && y<=10;
                }
                @Override public boolean passable(int x,int y,int z) {
                    var p=new BlockPos(x,y,z);
                    return world.getBlockState(p).getCollisionShape(world,p).isEmpty();
                }
                @Override public boolean support(int x,int y,int z) { return !passable(x,y,z); }
                @Override public double digSeconds(int x,int y,int z) {
                    var p=new BlockPos(x,y,z);
                    if (preserveWalls && y>=-1 || WorksiteProtection.protectedCell(p,b->true,
                            world::getBlockState,b->world.getBlockState(b).hasBlockEntity(),b->false))
                        return INFEASIBLE;
                    var state=world.getBlockState(p);
                    return com.neko.mcbot.building.BuildSite.restorable(state) ? .5 : INFEASIBLE;
                }
                @Override public boolean placeable(int x,int y,int z) {
                    return world.getBlockState(new BlockPos(x,y,z)).isAir()
                            && !(x>=0 && x<=8 && z>=0 && z<=10 && y>=-1
                            && (y==-1 || x==0 || x==8 || z==0 || z==10));
                }
                @Override public double placeCost(int x,int y,int z) { return 1; }
                @Override public int maxPlaces() { return 16; }
                @Override public boolean canClear(int x,int y,int z,List<Long> cells,TerrainEdits edits) {
                    return NavigationWork.canClear(world,new BlockPos(x,y,z),1.62,4.5,cells,edits,geometry);
                }
                @Override public boolean canPlaceFrom(int x,int y,int z,long target,TerrainEdits edits) {
                    var foot=geometry.foot(x,y,z,edits);
                    if (foot==null) return false;
                    double height=target==DigAStar.pack(x,y,z) ? y+1.02 : foot.height();
                    return SupportPlacement.canPlan(world,world,new BlockPos(DigAStar.unpackX(target),
                                    DigAStar.unpackY(target),DigAStar.unpackZ(target)),
                            new Vec3(x+.5,height+1.62,z+.5),new AABB(x+.2,height,z+.2,x+.8,height+1.8,z+.8),
                            new ItemStack(Items.COBBLESTONE),4.5,edits,p->true,f->true,b->true);
                }
            };
        }
        NavigationGoal goal(BlockPos target) {
            var item=target.getY()==4 ? Items.OAK_PLANKS : Items.COBBLESTONE;
            var state=target.getY()==4 ? Blocks.OAK_PLANKS.defaultBlockState() : Blocks.COBBLESTONE.defaultBlockState();
            return PlacementWork.goal(target,geometry,1.62,.6,1.8,4.5,(eye,body,edits)->
                    SupportPlacement.canPlanBlock(world,world,target,eye,body,new ItemStack(item),
                            4.5,edits,p->true,f->true,b->true)
                            && BlockPlacement.constructionExit(geometry,target,state,body,edits)==null
                            ? null : new ServerTool.Result(false,"TARGET_BLOCKED:no contact or exit",null));
        }
        NavigationGoal stairGoal(BlockPos target) {
            var state=Blocks.OAK_STAIRS.defaultBlockState().setValue(StairBlock.FACING,Direction.EAST);
            return PlacementWork.goal(target,geometry,1.62,.6,1.8,4.5,(eye,body,edits)->
                    SupportPlacement.canPlanWorkBlock(world,world,target,eye,body,new ItemStack(Items.OAK_STAIRS),
                            4.5,edits,p->true,f->true,b->true,
                            hit->BlockPlacement.Look.toward(hit.getLocation().subtract(eye)).horizontal()==Direction.EAST ? state : null,
                            proposed->BlockPlacement.constructionExit(geometry,target,proposed,body,edits)==null)
                            ? null : new ServerTool.Result(false,"TARGET_BLOCKED:no native stair contact",null));
        }
        DigAStar check(NavigationGoal goal,BlockPos target) {
            return check(goal,target,new BlockPos(1,0,1));
        }
        DigAStar check(NavigationGoal goal,BlockPos target,BlockPos from) {
            var original=Map.copyOf(world.blocks);
            var route=new DigAStar(new MemoDigSampler(terrain),from.getX(),from.getY(),from.getZ(),
                    target.getX(),target.getY(),target.getZ(),
                    4000,64,goal).segments();
            for (int i=0;i<10000 && !route.done();i++) route.advance(100,2_000_000);
            System.out.println("[house-work] expanded="+route.expanded()+" seeds="+route.estimatedWorkStances()
                    +" probes="+route.estimateSeedProbes()+" failure="+route.failure()+" rejects="+route.rejections()
                    +" route="+route.path());
            assertNull(route.failure());
            assertNotNull(route.path());
            assertTrue(route.expanded()<1000,"short construction approach: "+route.expanded());
            var edits=TerrainEdits.EMPTY;
            for (var step:route.path()) edits=edits.after(step.dig(),step.place());
            var end=route.path().getLast();
            assertTrue(goal.reached(end.x(),end.y(),end.z(),edits));
            assertEquals(original,world.blocks);
            return route;
        }
    }
}
