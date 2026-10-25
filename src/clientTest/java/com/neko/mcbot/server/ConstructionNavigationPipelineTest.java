package com.neko.mcbot.server;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.neko.mcbot.agentcore.loop.ToolExecutor.ToolOutcome;
import com.neko.mcbot.agentcore.script.BoundedProgram;
import com.neko.mcbot.building.BuildSurvey;
import com.neko.mcbot.building.BuildTarget;
import com.neko.mcbot.building.BuildWork;
import com.neko.mcbot.path.DigAStar;
import com.neko.mcbot.path.DigSampler;
import com.neko.mcbot.path.TerrainEdits;
import com.neko.mcbot.task.TickTask;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(30)
class ConstructionNavigationPipelineTest {
    @BeforeAll static void bootstrap() { BlockActionFixtures.bootstrap(); }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void realSurveyLuaSearchRaysAndWorkRestoreLowerLogsBeforeApproachingTheUpperLog(boolean multiple) throws Exception {
        var input = JsonParser.parseString("""
                {"anchor":{"x":248,"y":64,"z":-144},"regions":[{
                "from":{"x":0,"y":0,"z":0},"to":{"x":0,"y":2,"z":0},
                "block":"oak_log","properties":[{"name":"axis","value":"y"}]}]}
                """).getAsJsonObject();
        if (multiple) input.getAsJsonArray("regions").add(JsonParser.parseString("""
                {"from":{"x":1,"y":1,"z":-2},"to":{"x":1,"y":1,"z":-2},
                "block":"oak_log","properties":[{"name":"axis","value":"y"}]}
                """));
        var target = BuildTarget.parse(input);
        var lower = new BlockPos(248,65,-144);
        var upper = lower.above();
        var extra = new BlockPos(249,65,-146);
        var world = new BlockAimTest.Grid(); world.blocks.clear();
        world.eye = new Vec3(242.5,65.62,-143.5);
        for (int x=241;x<=252;x++) for (int z=-148;z<=-140;z++)
            world.blocks.put(new BlockPos(x,63,z),Blocks.STONE.defaultBlockState());
        world.blocks.put(lower.below(),Blocks.OAK_LOG.defaultBlockState());
        world.blocks.put(upper.north(),Blocks.OAK_PLANKS.defaultBlockState());
        world.blocks.put(upper.east(),Blocks.OAK_PLANKS.defaultBlockState());
        world.blocks.put(lower.east(),Blocks.OAK_PLANKS.defaultBlockState());
        world.blocks.put(lower.west().below(),Blocks.COBBLESTONE.defaultBlockState());
        if (multiple) world.blocks.put(extra.below(),Blocks.OAK_LOG.defaultBlockState());
        var placed = new ArrayList<BlockPos>();
        var approached = new ArrayList<BlockPos>();
        var inventory = BlockActionFixtures.inventory();
        inventory.setItem(0,new ItemStack(Items.OAK_LOG,multiple ? 3 : 2));
        var collision = new StandingGeometryTest.Grid();
        collision.blocks.clear();
        var geometry = collision.geometry();
        class Work implements BuildSurvey.Access, BlockPlacement.Access, BuildWork.Access {
            BlockState proposed;
            @Override public BlockState state(BlockPos p) { return world.getBlockState(p); }
            @Override public boolean inBounds(BlockPos p) { return true; }
            @Override public boolean protectedCell(BlockPos p) { return false; }
            @Override public boolean near(BlockPos p) { return world.eye.distanceToSqr(Vec3.atCenterOf(p))<=5.5*5.5; }
            @Override public boolean repairPending(BlockPos p) {
                return (p.equals(lower) || multiple && p.equals(extra)) && !placed.contains(p);
            }
            @Override public BlockAim.Anchor placementAnchor(BuildTarget.Cell cell) {
                return PlacementWork.anchor(world,cell.pos(),null,cell);
            }
            @Override public BlockPlacement.Access placement() { return this; }
            @Override public JsonObject terrain() {
                return JsonParser.parseString("{\"dimension\":\"fixture:world\",\"chunks\":{\"15,-9\":\"a\"}}").getAsJsonObject();
            }
            @Override public net.minecraft.world.Container inventory() { return inventory; }
            @Override public ServerTool.Result guard(BlockPos p) { return null; }
            @Override public ServerTool.Result sight(BlockPos p, Direction face) {
                var contact=BlockAim.placementContact(world,p.relative(face.getOpposite()),face,4.5);
                return contact.ok()?null:new ServerTool.Result(false,contact.failure(),null);
            }
            @Override public ServerTool.Result prepare(BlockPos p, ItemStack stack, Direction face) {
                // The world adapter controls the native state callback; shape/ray/search/work code is real.
                proposed=Blocks.OAK_LOG.defaultBlockState().setValue(RotatedPillarBlock.AXIS,face.getAxis());
                return null;
            }
            @Override public BlockState proposedState() { return proposed; }
            @Override public InteractionResult place(BlockPos p, ItemStack stack, Direction face) {
                assertTrue(target.index().get(p).matches(proposed));
                if (p.equals(upper)) {
                    assertTrue(placed.contains(lower));
                    if (multiple) assertTrue(placed.contains(extra));
                }
                world.blocks.put(p,proposed); stack.shrink(1); placed.add(p);
                return InteractionResult.SUCCESS;
            }
            @Override public BuildSurvey.Access survey() { return this; }
            @Override public ServerTool.Result valid() { return null; }
            @Override public boolean reserve(BuildTarget.Cell cell) { return true; }
        }
        var work = new Work();
        assertEquals("needs_support",BuildSurvey.cellState(target,target.index().get(upper),work,work.state(upper)));
        DigSampler terrain = new DigSampler() {
            @Override public com.neko.mcbot.path.NavigationGeometry geometry() { return geometry; }
            @Override public boolean passable(int x,int y,int z) { return world.getBlockState(new BlockPos(x,y,z)).isAir(); }
            @Override public boolean support(int x,int y,int z) { return !passable(x,y,z); }
            @Override public double digSeconds(int x,int y,int z) { return INFEASIBLE; }
            @Override public boolean placeable(int x,int y,int z) { return false; }
            @Override public double placeCost(int x,int y,int z) { return INFEASIBLE; }
            @Override public int maxPlaces() { return 0; }
            @Override public boolean inBounds(int x,int y,int z) {
                return x>=241 && x<=252 && z>=-148 && z<=-140 && y>=63 && y<=70;
            }
        };
        var queue = new LinkedBlockingQueue<Runnable>();
        var registered = new JsonObject();
        registered.addProperty("type","building");
        registered.add("target",target.input());
        registered.addProperty("expected_revision",target.revision());
        boolean[] combined = {false};
        var program = new BoundedProgram(new BoundedProgram.Plan("return mc.build()",0,0),()->0L,(name,json)->{
            var args=JsonParser.parseString(json).getAsJsonObject();
            ServerTool.Result result;
            switch (name) {
                case "status" -> {
                    var data=new JsonObject();
                    data.addProperty("x",world.eye.x); data.addProperty("y",world.eye.y-1.62);
                    data.addProperty("z",world.eye.z); data.addProperty("dimension","fixture:world");
                    result=new ServerTool.Result(true,"fresh",data);
                }
                case "build_site" -> {
                    if (args.has("expected_revision")) assertEquals(target.revision(),args.get("expected_revision").getAsString());
                    var data=new JsonObject(); data.addProperty("target_revision",target.revision());
                    data.add("temporary",new com.google.gson.JsonArray());
                    result=new ServerTool.Result(true,"registered",data);
                }
                case "build_diff" -> result=args.has("work_scan") && args.get("work_scan").getAsBoolean()
                        ? BuildSurvey.workScan(target,work,args.get("work_cursor").getAsInt(),()->0)
                        : BuildSurvey.query(target,work,args.get("offset").getAsInt(),0,()->0);
                case "move_to" -> {
                    var p=new BlockPos(args.get("x").getAsInt(),args.get("y").getAsInt(),args.get("z").getAsInt());
                    var members = new ArrayList<PlacementWorkSet.Member>();
                    if (args.has("work_targets")) {
                        combined[0]=true;
                        assertEquals(target.revision(),args.get("expected_revision").getAsString());
                        for (var value:args.getAsJsonArray("work_targets")) {
                            var c=value.getAsJsonObject();
                            var point=new BlockPos(c.get("x").getAsInt(),c.get("y").getAsInt(),c.get("z").getAsInt());
                            assertEquals("minecraft:oak_log",c.get("item").getAsString());
                            members.add(new PlacementWorkSet.Member(point,new PlacementWork(
                                    (net.minecraft.world.item.BlockItem)Items.OAK_LOG,null)));
                        }
                        assertEquals(2,members.size());
                        assertTrue(members.stream().allMatch(m->m.target().getY()==65));
                    } else {
                        assertTrue(args.has("place_item"));
                        members.add(new PlacementWorkSet.Member(p,new PlacementWork(
                                (net.minecraft.world.item.BlockItem)Items.OAK_LOG,null)));
                    }
                    var set=new PlacementWorkSet(members,target.revision());
                    assertNull(set.refresh(member->PlacementWork.targetFailure(member.target(),work.state(member.target()),
                            target.index().get(member.target()),()->work.placementAnchor(target.index().get(member.target())))));
                    collision.blocks.clear(); collision.blocks.putAll(world.blocks);
                    var goal=set.goal(member->PlacementWork.goal(member.target(),geometry,1.62,.6,1.8,4.5,(eye,bounds,edits)->{
                        assertEquals(TerrainEdits.EMPTY,edits);
                        var original=world.eye; world.eye=eye;
                        try {
                            return PlacementWork.assessFaces(null,face->
                                    BlockPlacement.assessDesired(work,member.target(),0,face,target,target.index().get(member.target())));
                        } finally { world.eye=original; }
                    }));
                    var from=BlockPos.containing(world.eye.x,world.eye.y-1.62,world.eye.z);
                    var search=new DigAStar(terrain,from.getX(),from.getY(),from.getZ(),
                            p.getX(),p.getY(),p.getZ(),4000,0,goal);
                    while (!search.advance(100)) { }
                    assertNull(search.failure(),"native work search for "+p+": "+search.failure());
                    var end=search.path().getLast();
                    assertTrue(goal.reached(end.x(),end.y(),end.z()));
                    var foot=geometry.foot(end.x(),end.y(),end.z(),TerrainEdits.EMPTY);
                    world.eye=new Vec3(end.x()+.5,foot.height()+1.62,end.z()+.5);
                    var data=new JsonObject(); data.addProperty("removed_blocks",0); data.addProperty("placed_blocks",0);
                    result=set.finish(new ServerTool.Result(true,"work arrival",data),member->{
                        var failure=PlacementWork.assessFaces(null,face->BlockPlacement.assessDesired(
                                work,member.target(),0,face,target,target.index().get(member.target())));
                        return failure==null?new ServerTool.Result(true,"fresh native preflight",null):failure;
                    });
                    assertTrue(result.ok(),result.feedback());
                    var selected=result.data().getAsJsonObject("selected_work_target");
                    approached.add(new BlockPos(selected.get("x").getAsInt(),selected.get("y").getAsInt(),selected.get("z").getAsInt()));
                }
                case "build_nearby" -> {
                    assertTrue(args.get("include_repairs").getAsBoolean(),"missing lower log must enter the repair pass");
                    var batch=new BuildWork(target,work,-1,args.get("max_actions").getAsInt(),()->0,true);
                    result=null;
                    for (int tick=0;tick<50 && result==null;tick++)
                        if (batch.tickSafe(null) instanceof TickTask.Progress.Done done) result=done.result();
                    assertNotNull(result);
                }
                default -> throw new AssertionError(name);
            }
            return CompletableFuture.completedFuture(new ToolOutcome(result.ok(),result.feedback(),result.data()));
        },queue::add,()->{},registered);
        var future=program.start();
        try {
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(20);
            while (!future.isDone() && System.nanoTime()<deadline) {
                var next=queue.poll(50,TimeUnit.MILLISECONDS);
                if (next!=null) next.run();
            }
            assertTrue(future.isDone(),"construction must settle");
            var result=future.join();
            assertTrue(result.ok(),result.feedback());
            assertTrue(result.data().getAsJsonObject("returned").get("goal_satisfied").getAsBoolean());
            if (multiple) {
                assertTrue(combined[0],"the real Lua/survey chain must dispatch a composite work request");
                assertEquals(java.util.Set.of(lower,extra,upper),new java.util.HashSet<>(placed));
                assertEquals(upper,placed.getLast(),"finish the lower layer before the upper log");
                assertEquals(3,placed.size());
            } else {
                assertEquals(java.util.List.of(lower,upper),placed);
                assertEquals(java.util.List.of(lower,upper),approached);
            }
            assertEquals(0,inventory.getItem(0).getCount());
            assertTrue(BuildSurvey.verify(target,work,0,()->0).data().get("goal_satisfied").getAsBoolean());
        } finally {
            program.stop("CANCELLED:test cleanup");
            program.terminated().get(2,TimeUnit.SECONDS);
        }
    }
}
