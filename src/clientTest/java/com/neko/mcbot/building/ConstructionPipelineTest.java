package com.neko.mcbot.building;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.neko.mcbot.agentcore.loop.ToolExecutor.ToolOutcome;
import com.neko.mcbot.agentcore.script.BoundedProgram;
import com.neko.mcbot.server.BlockPlacement;
import com.neko.mcbot.server.ServerTool;
import com.neko.mcbot.task.TickTask;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(15)
class ConstructionPipelineTest {
    @BeforeAll static void bootstrap() { com.neko.mcbot.server.BlockActionFixtures.bootstrap(); }

    @Test void aFullFloorClearsAndBuildsAcrossPagesInOneProgramWithoutExhaustingReceiptSpace() throws Exception {
        var target=BuildTarget.parse(JsonParser.parseString("""
                {"anchor":{"x":0,"y":90,"z":0},"fill":{"from":{"x":0,"y":0,"z":0},
                "to":{"x":6,"y":0,"z":6},"block":"stone"}}
                """).getAsJsonObject());
        var world=new BuildWorkTest.World();
        world.inventory.setItem(0,new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.STONE,64));
        target.cells().forEach(c->world.blocks.put(c.pos(),Blocks.DIRT.defaultBlockState()));
        BuildSurvey.Access survey=new BuildSurvey.Access() {
            @Override public net.minecraft.world.level.block.state.BlockState state(BlockPos p) { return world.state(p); }
            @Override public boolean inBounds(BlockPos p) { return world.inBounds(p); }
            @Override public boolean protectedCell(BlockPos p) { return world.protectedCell(p); }
            @Override public boolean near(BlockPos p) { return world.near(p); }
            @Override public BlockPlacement.Access placement() { return world; }
            @Override public JsonObject terrain() { return world.terrain(); }
            @Override public boolean clearable(BuildTarget.Cell c) {
                return !c.matches(world.state(c.pos())) && !world.protectedCell(c.pos());
            }
        };
        var names=new ArrayList<String>();
        var offsets=new ArrayList<Integer>();
        var queue=new ConcurrentLinkedQueue<Runnable>();
        int[] cleared={0};
        var program=new BoundedProgram(new BoundedProgram.Plan("""
                local t={anchor={x=0,y=90,z=0},fill={from={x=0,y=0,z=0},to={x=6,y=0,z=6},block="stone"}}
                return mc.build(t)
                """,0,0),()->0L,(name,json)->{
            var args=JsonParser.parseString(json).getAsJsonObject();
            names.add(name);
            ServerTool.Result result;
            switch(name) {
                case "status" -> result=new ServerTool.Result(true,"fresh body",JsonParser.parseString("""
                        {"x":0,"y":91,"z":0,"dimension":"fixture:world","inventory":{"slots":[]}}
                        """).getAsJsonObject());
                case "build_site" -> {
                    var data=new JsonObject();
                    data.addProperty("target_revision",target.revision());
                    data.add("temporary",new com.google.gson.JsonArray());
                    result=new ServerTool.Result(true,"same registered site",data);
                }
                case "build_diff" -> {
                    assertEquals(target.revision(),BuildTarget.parse(args.getAsJsonObject("target")).revision());
                    assertEquals(target.revision(),args.get("expected_revision").getAsString());
                    int offset=args.get("offset").getAsInt();
                    offsets.add(offset);
                    result=BuildSurvey.query(target,survey,offset,0,()->0);
                }
                case "move_to" -> result=new ServerTool.Result(true,"actual work arrival and full body observations ".repeat(40),
                        JsonParser.parseString("""
                                {"x":0,"y":91,"z":0,"arrival":"work","feet_y":91,"removed_blocks":0,"placed_blocks":0}
                                """).getAsJsonObject());
                case "break_block" -> {
                    var pos=new BlockPos(args.get("x").getAsInt(),args.get("y").getAsInt(),args.get("z").getAsInt());
                    assertEquals("minecraft:dirt",args.get("expected_block").getAsString());
                    assertEquals(target.revision(),args.get("site_revision").getAsString());
                    assertEquals(Blocks.DIRT.defaultBlockState(),world.blocks.get(pos));
                    world.blocks.remove(pos); cleared[0]++;
                    result=new ServerTool.Result(true,"full source and body observations ".repeat(40),
                            JsonParser.parseString("{\"removed\":true,\"changed\":true,\"collected_count\":0}").getAsJsonObject());
                }
                case "build_nearby" -> {
                    var work=new BuildWork(target,new BuildWork.Access() {
                        @Override public BuildSurvey.Access survey() { return survey; }
                        @Override public ServerTool.Result valid() { return null; }
                        @Override public boolean reserve(BuildTarget.Cell c) { return true; }
                    },args.get("offset").getAsInt(),args.get("max_actions").getAsInt(),()->0);
                    result=null;
                    for(int tick=0;tick<40 && result==null;tick++)
                        if(work.tickSafe(null) instanceof TickTask.Progress.Done done) result=done.result();
                    assertNotNull(result);
                }
                default -> throw new AssertionError(name);
            }
            return CompletableFuture.completedFuture(new ToolOutcome(result.ok(),result.feedback(),result.data()));
        },queue::add,()->{});
        var future=program.start();
        try {
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(8);
            while(!future.isDone() && System.nanoTime()<deadline) {
                Runnable next; while((next=queue.poll())!=null) next.run();
                java.util.concurrent.locks.LockSupport.parkNanos(100_000);
            }
            assertTrue(future.isDone(),"local construction must settle");
            var result=future.join();
            assertTrue(result.ok(),result.feedback());
            var report=result.data().getAsJsonObject("returned");
            assertTrue(report.get("goal_satisfied").getAsBoolean(),result.feedback());
            assertEquals(49,cleared[0]);
            assertEquals(49,world.actions);
            assertEquals(49,report.get("cleared_blocks").getAsInt());
            assertEquals(49,report.get("placed_blocks").getAsInt());
            assertEquals(0,report.get("remaining").getAsInt());
            assertEquals(15,world.inventory.getItem(0).getCount());
            assertTrue(offsets.contains(48));
            assertTrue(offsets.size()<120,"full-page scans must be shared by a batch of clearing actions");
            int first=names.indexOf("break_block");
            assertEquals(java.util.List.of("move_to","build_diff","break_block"),names.subList(first+1,first+4));
            assertTrue(result.data().get("construction_query_calls").getAsInt()>0);
            assertTrue(result.feedback().getBytes(java.nio.charset.StandardCharsets.UTF_8).length<20000);
        } finally {
            program.stop("CANCELLED:test cleanup");
            program.terminated().get(2,TimeUnit.SECONDS);
        }
    }
}
