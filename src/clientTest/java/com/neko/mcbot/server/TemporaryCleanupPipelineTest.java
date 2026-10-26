package com.neko.mcbot.server;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.neko.mcbot.agentcore.loop.ToolExecutor.ToolOutcome;
import com.neko.mcbot.agentcore.script.BoundedProgram;
import com.neko.mcbot.building.BuildSite;
import com.neko.mcbot.building.BuildTarget;
import com.neko.mcbot.building.CellWitness;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(20)
class TemporaryCleanupPipelineTest {
    @BeforeAll static void bootstrap() { BlockActionFixtures.bootstrap(); }

    @Test void nativeRaysSiteSourcesAndMiningDrainLocalSupportsBeforeOneDistantTrip() throws Exception {
        var grid=new BlockAimTest.Grid(); grid.blocks.clear();
        grid.eye=new Vec3(.5,65.62,.5);
        for(int x=-1;x<=13;x++) for(int z=-1;z<=3;z++)
            grid.blocks.put(new BlockPos(x,64,z),Blocks.STONE.defaultBlockState());
        var near=new BlockPos(2,65,0);
        var nearby=new BlockPos(2,65,2);
        var distant=new BlockPos(11,65,0);
        var site=new BuildSite();
        var removed=new ArrayList<BlockPos>();
        var routes=new ArrayList<BlockPos>();
        class World implements BuildSite.Access {
            final Object chunk=new Object();
            final HashMap<BlockPos,CellWitness> witnesses=new HashMap<>();
            @Override public BlockState state(BlockPos p) { return grid.getBlockState(p); }
            @Override public boolean corridor(BlockPos p) { return true; }
            @Override public boolean facility(BlockPos p) { return state(p).hasBlockEntity(); }
            @Override public boolean occupied(BlockPos p) { return false; }
            @Override public boolean material(BuildTarget.Cell c) { return true; }
            @Override public Object chunk(BlockPos p) { return chunk; }
            @Override public CellWitness watch(BlockPos p) { return witnesses.computeIfAbsent(p,k->new CellWitness()); }
            @Override public double cleanupDistanceSq(BlockPos p) { return grid.eye.distanceToSqr(Vec3.atCenterOf(p)); }
            @Override public boolean cleanupReady(BlockPos p) { return new BlockMining(mining(p)).preflight(5.5)==null; }
            BlockActionFixtures.Mining mining(BlockPos p) {
                var world=this;
                return new BlockActionFixtures.Mining() {
                    { gain=1; expectedBlock="minecraft:cobblestone"; }
                    @Override public BlockState state() { return world.state(p); }
                    @Override public ServerTool.Result guard(double reach) {
                        return site.miningGuard(p,site.target().revision(),true,world);
                    }
                    @Override public ServerTool.Result sight(double reach) {
                        var aim=BlockAim.mining(grid,p,reach);
                        return aim.ok()?null:new ServerTool.Result(false,aim.failure(),null);
                    }
                    @Override public boolean destroy() {
                        assertNull(site.miningGuard(p,site.target().revision(),true,world));
                        grid.blocks.remove(p); removed.add(p);
                        return true;
                    }
                };
            }
        }
        var world=new World(); site.bind("cleanup",world);
        var target=BuildTarget.parse(JsonParser.parseString("""
                {"anchor":{"x":14,"y":65,"z":0},"cells":[{"x":0,"y":0,"z":0,"block":"stone"}]}
                """).getAsJsonObject());
        assertNull(site.begin(target,List.of(),new BlockPos(0,65,0),world));
        for(var pos:List.of(distant,near,nearby)) {
            assertNull(site.temporaryGuard(pos,"support",world));
            var before=world.state(pos); grid.blocks.put(pos,Blocks.COBBLESTONE.defaultBlockState());
            site.placedTemporary(pos,before,world.state(pos),"support",world);
        }
        var initial=site.report(world);
        assertFalse(initial.getAsJsonArray("temporary").get(0).getAsJsonObject().get("cleanup_ready").getAsBoolean());
        assertTrue(initial.getAsJsonArray("temporary").get(1).getAsJsonObject().get("cleanup_ready").getAsBoolean());
        assertTrue(initial.getAsJsonArray("temporary").get(2).getAsJsonObject().get("cleanup_ready").getAsBoolean());
        assertTrue(removed.isEmpty());
        var queue=new LinkedBlockingQueue<Runnable>();
        var program=new BoundedProgram(new BoundedProgram.Plan("return mc.cleanup_temporary('"+target.revision()+"')",0,0),
                ()->0L,(name,json)->{
            var args=JsonParser.parseString(json).getAsJsonObject();
            ServerTool.Result result=switch(name) {
                case "build_site" -> new ServerTool.Result(true,"live source and native ray observations",site.report(world));
                case "move_to" -> {
                    assertEquals(List.of(near,nearby),removed);
                    assertTrue(args.get("walk_only").getAsBoolean());
                    assertEquals(distant.getX(),args.get("x").getAsInt());
                    routes.add(distant);
                    // Only physical arrival and the vanilla destruction callback are controlled.
                    grid.eye=new Vec3(8.5,65.62,.5);
                    yield new ServerTool.Result(true,"controlled arrival",new JsonObject());
                }
                case "break_block" -> {
                    assertEquals(target.revision(),args.get("site_revision").getAsString());
                    assertTrue(args.get("temporary_cleanup").getAsBoolean());
                    var pos=new BlockPos(args.get("x").getAsInt(),args.get("y").getAsInt(),args.get("z").getAsInt());
                    var mining=new BlockMining(world.mining(pos));
                    assertNull(mining.preflight(5.5));
                    var mined=mining.tick();
                    assertNotNull(mined); assertTrue(mined.ok(),mined.feedback());
                    assertTrue(mined.data().get("removed").getAsBoolean());
                    site.removedTemporary(pos);
                    yield mined;
                }
                default -> throw new AssertionError(name);
            };
            return CompletableFuture.completedFuture(new ToolOutcome(result.ok(),result.feedback(),result.data()));
        },queue::add,()->{});
        var future=program.start();
        try {
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
            while(!future.isDone() && System.nanoTime()<deadline) {
                var next=queue.poll(20,TimeUnit.MILLISECONDS);
                if(next!=null) next.run();
            }
            assertTrue(future.isDone());
            var result=future.join();
            assertTrue(result.ok(),result.feedback());
            assertEquals(3,result.data().getAsJsonObject("returned").get("removed").getAsInt());
            assertEquals(List.of(near,nearby,distant),removed);
            assertEquals(List.of(distant),routes);
            assertTrue(site.report(world).getAsJsonArray("temporary").isEmpty());
        } finally {
            program.stop("CANCELLED:test cleanup");
            program.terminated().get(2,TimeUnit.SECONDS);
        }
    }
}
