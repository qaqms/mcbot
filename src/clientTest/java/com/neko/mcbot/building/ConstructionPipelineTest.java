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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(30)
class ConstructionPipelineTest {
    @BeforeAll static void bootstrap() { com.neko.mcbot.server.BlockActionFixtures.bootstrap(); }

    @Test void exhaustedFoundationStockReturnsForRecipeProcessingAndResumesTheSameDrawingBeforeUpperWork() throws Exception {
        var target=FoundationWorkTest.drawing(25);
        var world=new BuildWorkTest.World();
        world.inventory.setItem(0,new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.OAK_PLANKS,23));
        world.inventory.setItem(1,new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.STONE,64));
        world.inventory.setItem(2,new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.OAK_LOG,64));
        world.afterAction=()->{
            if(world.blocks.containsKey(new BlockPos(0,91,0)))
                assertEquals(25,world.blocks.keySet().stream().filter(p->p.getY()==90).count());
        };
        var goal=new JsonObject();
        goal.addProperty("type","building"); goal.add("target",target.input());
        goal.addProperty("expected_revision",target.revision());
        var frozen=goal.deepCopy();
        var queue=new LinkedBlockingQueue<Runnable>();
        var names=new ArrayList<String>();
        boolean[] queried={false};
        var reports=new ArrayList<ToolOutcome>();
        for(String code:java.util.List.of("return mc.build()",
                "return mc.call('craft',{item='minecraft:oak_planks',count=2,query=true})",
                "return mc.call('craft',{item='minecraft:oak_planks',count=2,query=false})",
                "return mc.build()")) {
            var program=new BoundedProgram(new BoundedProgram.Plan(code,0,0),()->0L,(name,json)->{
                names.add(name);
                var args=JsonParser.parseString(json).getAsJsonObject();
                ServerTool.Result result=switch(name) {
                    case "status" -> new ServerTool.Result(true,"controlled body",new JsonObject());
                    case "build_site" -> {
                        var data=new JsonObject(); data.addProperty("target_revision",target.revision());
                        data.add("temporary",new com.google.gson.JsonArray());
                        yield new ServerTool.Result(true,"same site",data);
                    }
                    case "build_diff" -> args.has("work_scan") && args.get("work_scan").getAsBoolean()
                            ? BuildSurvey.workScan(target,world,args.get("work_cursor").getAsInt(),()->0)
                            : BuildSurvey.query(target,world,args.get("offset").getAsInt(),0,()->0);
                    case "build_nearby" -> {
                        var work=new BuildWork(target,world,-1,args.get("max_actions").getAsInt(),()->0,
                                !args.has("include_repairs")||args.get("include_repairs").getAsBoolean(),
                                args.has("repairs_only")&&args.get("repairs_only").getAsBoolean());
                        yield FoundationWorkTest.finish(work);
                    }
                    case "craft" -> {
                        assertEquals(23,world.actions,"supply must return before placing the upper block");
                        assertEquals("minecraft:oak_planks",args.get("item").getAsString());
                        assertEquals(2,args.get("count").getAsInt(),"the model requests the reported foundation gap");
                        var data=new JsonObject(); data.addProperty("can_craft",true);
                        if(args.get("query").getAsBoolean()) {
                            queried[0]=true;
                            assertEquals(64,world.inventory.getItem(2).getCount());
                            data.addProperty("crafted_count",0);
                        } else {
                            assertTrue(queried[0]);
                            // Crafting is a controlled boundary here; this test exercises its handoff, not recipes.
                            world.inventory.getItem(2).shrink(1);
                            world.inventory.setItem(0,new net.minecraft.world.item.ItemStack(
                                    net.minecraft.world.item.Items.OAK_PLANKS,4));
                            data.addProperty("crafted_count",4);
                        }
                        yield new ServerTool.Result(true,"controlled recipe result",data);
                    }
                    default -> throw new AssertionError("unexpected action during foundation supply: "+name);
                };
                return CompletableFuture.completedFuture(new ToolOutcome(result.ok(),result.feedback(),result.data()));
            },queue::add,()->{},goal);
            var future=program.start();
            try {
                long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
                while(!future.isDone() && System.nanoTime()<deadline) {
                    var next=queue.poll(20,TimeUnit.MILLISECONDS);
                    if(next!=null) next.run();
                }
                assertTrue(future.isDone());
                reports.add(future.join());
                assertTrue(future.join().ok(),future.join().feedback());
                assertEquals(frozen,goal);
            } finally {
                program.stop("CANCELLED:test cleanup");
                program.terminated().get(2,TimeUnit.SECONDS);
            }
        }
        var gap=reports.getFirst().data().getAsJsonObject("returned");
        assertEquals("foundation_materials_required",gap.get("blocked").getAsString());
        assertEquals(2,gap.getAsJsonObject("foundation_work").get("unfinished").getAsInt());
        assertEquals(23,gap.get("placed_blocks").getAsInt());
        assertEquals(target.revision(),gap.get("target_revision").getAsString());
        var finalReport=reports.getLast().data().getAsJsonObject("returned");
        assertTrue(finalReport.get("goal_satisfied").getAsBoolean());
        assertEquals(target.revision(),finalReport.get("target_revision").getAsString());
        assertEquals(3,finalReport.get("placed_blocks").getAsInt());
        assertEquals(26,world.actions);
        assertEquals(63,world.inventory.getItem(2).getCount());
        assertEquals(2,world.inventory.getItem(0).getCount());
        assertEquals(2,names.stream().filter(n->n.equals("craft")).count());
    }

    @Test void arrivalWorkThenEarlyRepairClearsTheRealSiteLedgerBeforeTheNextRoofRoute() throws Exception {
        var target=BuildTarget.parse(JsonParser.parseString("""
                {"anchor":{"x":4,"y":90,"z":0},"regions":[
                {"from":{"x":0,"y":0,"z":0},"to":{"x":31,"y":0,"z":0},"block":"stone"},
                {"from":{"x":0,"y":4,"z":0},"to":{"x":0,"y":4,"z":0},"block":"stone"}]}
                """).getAsJsonObject());
        var first=new BlockPos(4,90,0);
        var repaired=new BlockPos(35,90,0);
        var roof=new BlockPos(4,94,0);
        var siteWorld=new BuildSiteTest.World();
        var site=new BuildSite(); site.bind("pipeline",siteWorld);
        target.cells().forEach(c->siteWorld.states.put(c.pos(),c.desired()));
        siteWorld.states.remove(first); siteWorld.states.remove(roof);
        assertNull(site.begin(target,java.util.List.of(),BuildSiteTest.FEET,siteWorld));
        var placements=new ArrayList<BlockPos>();
        var world=new BuildSurveyTest.World() {
            @Override public net.minecraft.world.level.block.state.BlockState state(BlockPos p) { return siteWorld.state(p); }
            @Override public boolean repairPending(BlockPos p) { return site.repairPending(p); }
            @Override public ServerTool.Result beforePlace() { return site.charge(); }
            @Override public net.minecraft.world.InteractionResult place(BlockPos p,
                    net.minecraft.world.item.ItemStack stack,net.minecraft.core.Direction face) {
                placements.add(p); stack.shrink(1); siteWorld.states.put(p,proposed);
                return net.minecraft.world.InteractionResult.SUCCESS;
            }
            @Override public void recorded(BlockPos p,net.minecraft.world.level.block.state.BlockState before,
                    net.minecraft.world.level.block.state.BlockState after,int consumed) {
                site.recordPlacement(p,before,after,consumed,siteWorld);
            }
        };
        world.prepare=(p,face)->site.placementGuard(p,world.proposed,siteWorld);
        world.inventory.setItem(0,new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.STONE,3));
        world.far.add(first); world.far.add(roof);
        int[] routes={0};
        var queue=new LinkedBlockingQueue<Runnable>();
        var goal=new JsonObject();
        goal.addProperty("type","building"); goal.add("target",target.input());
        goal.addProperty("expected_revision",target.revision());
        var program=new BoundedProgram(new BoundedProgram.Plan("return mc.build()",0,0),()->0L,(name,json)->{
            var args=JsonParser.parseString(json).getAsJsonObject();
            ServerTool.Result result;
            switch(name) {
                case "status" -> result=new ServerTool.Result(true,"controlled body",JsonParser.parseString("""
                        {"x":0,"y":90,"z":0,"dimension":"fixture:world","inventory":{"slots":[]}}
                        """).getAsJsonObject());
                case "build_site" -> {
                    if (args.get("operation").getAsString().equals("checkpoint"))
                        assertNull(site.checkpoint(target.revision(),BuildSiteTest.FEET,siteWorld));
                    result=new ServerTool.Result(true,"actual worksite report",site.report(siteWorld));
                }
                case "build_diff" -> result=args.has("work_scan") && args.get("work_scan").getAsBoolean()
                        ? BuildSurvey.workScan(target,world,args.get("work_cursor").getAsInt(),()->0)
                        : BuildSurvey.query(target,world,args.get("offset").getAsInt(),0,()->0);
                case "move_to" -> {
                    routes[0]++;
                    if (routes[0]==1) {
                        assertEquals(first.getY(),args.get("y").getAsInt());
                        assertNull(site.miningGuard(repaired,target.revision(),false,siteWorld));
                        site.recordRemoval(repaired,siteWorld.state(repaired),Blocks.AIR.defaultBlockState());
                        siteWorld.states.remove(repaired); world.far.remove(first);
                        assertEquals(1,site.report(siteWorld).get("repairs_pending").getAsInt());
                    } else {
                        assertEquals(2,routes[0]);
                        assertEquals(java.util.List.of(first,repaired),placements);
                        assertFalse(site.repairPending(repaired),"the native placement callback clears the source ledger");
                        assertEquals(0,site.report(siteWorld).get("repairs_pending").getAsInt());
                        assertEquals(roof.getY(),args.get("y").getAsInt());
                        world.far.remove(roof);
                    }
                    var data=new JsonObject(); data.addProperty("can_work_here",true);
                    data.addProperty("removed_blocks",routes[0]==1?1:0);
                    result=new ServerTool.Result(true,"controlled arrival, actual removal ledger",data);
                }
                case "build_nearby" -> {
                    boolean repairsOnly=args.has("repairs_only") && args.get("repairs_only").getAsBoolean();
                    if (repairsOnly) {
                        assertEquals(java.util.List.of(first),placements,"finish the arrival's work before closing its opening");
                        assertEquals(1,routes[0],"repair without another route");
                    }
                    var work=new BuildWork(target,new BuildWork.Access() {
                        @Override public BuildSurvey.Access survey() { return world; }
                        @Override public ServerTool.Result valid() { return site.live(siteWorld); }
                        @Override public boolean reserve(BuildTarget.Cell c) { return true; }
                    },-1,args.get("max_actions").getAsInt(),()->0,
                            !args.has("include_repairs") || args.get("include_repairs").getAsBoolean(),repairsOnly);
                    result=null;
                    for (int tick=0;tick<40 && result==null;tick++)
                        if (work.tickSafe(null) instanceof TickTask.Progress.Done done) result=done.result();
                    assertNotNull(result);
                }
                default -> throw new AssertionError(name);
            }
            return CompletableFuture.completedFuture(new ToolOutcome(result.ok(),result.feedback(),result.data()));
        },queue::add,()->{},goal);
        var future=program.start();
        try {
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(20);
            while(!future.isDone() && System.nanoTime()<deadline) {
                var next=queue.poll(50,TimeUnit.MILLISECONDS);
                if (next!=null) next.run();
            }
            assertTrue(future.isDone());
            var result=future.join();
            assertTrue(result.ok(),result.feedback());
            var report=result.data().getAsJsonObject("returned");
            assertTrue(report.get("goal_satisfied").getAsBoolean(),result.feedback());
            assertEquals(1,report.get("nearby_repaired_blocks").getAsInt());
            assertEquals(3,report.get("placed_blocks").getAsInt());
            assertEquals(3,report.get("consumed_count").getAsInt());
            assertEquals(java.util.List.of(first,repaired,roof),placements);
            assertEquals(0,world.inventory.getItem(0).getCount());
            assertEquals(0,site.report(siteWorld).get("repairs_pending").getAsInt());
            assertEquals(target.revision(),site.target().revision());
        } finally {
            program.stop("CANCELLED:test cleanup");
            program.terminated().get(2,TimeUnit.SECONDS);
        }
    }

    @ParameterizedTest @ValueSource(ints = {49,105,128,400})
    void aFullFloorClearsAndBuildsAcrossPagesInOneProgramWithoutExhaustingReceiptSpace(int count) throws Exception {
        int depth=count==400?20:count==128?8:7, width=count/depth;
        var target=BuildTarget.parse(JsonParser.parseString("""
                {"anchor":{"x":0,"y":90,"z":0},"fill":{"from":{"x":0,"y":0,"z":0},
                "to":{"x":%d,"y":0,"z":%d},"block":"stone"}}
                """.formatted(width-1,depth-1)).getAsJsonObject());
        var world=new BuildWorkTest.World();
        int stock=Math.max(128,(count+63)/64*64);
        for (int slot=0;slot<stock/64;slot++)
            world.inventory.setItem(slot,new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.STONE,64));
        var topsoil=(count==49 ? Blocks.GRASS_BLOCK : Blocks.DIRT).defaultBlockState();
        target.cells().forEach(c->{
            world.blocks.put(c.pos(),topsoil);
            world.blocks.put(c.pos().below(),Blocks.DIRT.defaultBlockState());
        });
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
        var queue=new LinkedBlockingQueue<Runnable>();
        int[] cleared={0};
        var program=new BoundedProgram(new BoundedProgram.Plan("""
                local t={anchor={x=0,y=90,z=0},fill={from={x=0,y=0,z=0},to={x=%d,y=0,z=%d},block="stone"}}
                return mc.build(t)
                """.formatted(width-1,depth-1),0,0),()->0L,(name,json)->{
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
                    result=args.has("work_scan") && args.get("work_scan").getAsBoolean()
                            ? BuildSurvey.workScan(target,survey,args.get("work_cursor").getAsInt(),()->0)
                            : BuildSurvey.query(target,survey,offset,0,()->0);
                }
                case "move_to" -> {
                    var data=JsonParser.parseString("""
                                {"x":0,"y":91,"z":0,"arrival":"work","feet_y":91,"removed_blocks":0,"placed_blocks":0}
                                """).getAsJsonObject();
                    new com.neko.mcbot.server.DropObservations().snapshotSources(ignored->null)
                            .entrySet().forEach(e->data.add(e.getKey(),e.getValue()));
                    result=new ServerTool.Result(true,"actual work arrival and full body observations ".repeat(40),data);
                }
                case "break_block" -> {
                    var pos=new BlockPos(args.get("x").getAsInt(),args.get("y").getAsInt(),args.get("z").getAsInt());
                    assertEquals(count==49 ? "minecraft:grass_block" : "minecraft:dirt",args.get("expected_block").getAsString());
                    assertEquals(target.revision(),args.get("site_revision").getAsString());
                    assertEquals(topsoil,world.blocks.get(pos));
                    world.blocks.remove(pos); cleared[0]++;
                    result=new ServerTool.Result(true,"full source and body observations ".repeat(40),
                            JsonParser.parseString("{\"removed\":true,\"changed\":true,\"collected_count\":0}").getAsJsonObject());
                }
                case "build_nearby" -> {
                    var work=new BuildWork(target,new BuildWork.Access() {
                        @Override public BuildSurvey.Access survey() { return survey; }
                        @Override public ServerTool.Result valid() { return null; }
                        @Override public boolean reserve(BuildTarget.Cell c) { return true; }
                    },args.has("offset")?args.get("offset").getAsInt():-1,args.get("max_actions").getAsInt(),()->0);
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
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(20);
            while(!future.isDone() && System.nanoTime()<deadline) {
                var next=queue.poll(50,TimeUnit.MILLISECONDS);
                if (next!=null) next.run();
            }
            assertTrue(future.isDone(),"local construction must settle; cleared="+cleared[0]
                    +", placed="+world.actions+", calls="+names.size());
            var result=future.join();
            assertTrue(result.ok(),result.feedback());
            var report=result.data().getAsJsonObject("returned");
            assertTrue(report.get("goal_satisfied").getAsBoolean(),result.feedback());
            assertEquals(count,cleared[0]);
            assertEquals(count,world.actions);
            assertEquals(count,report.get("cleared_blocks").getAsInt());
            assertEquals(count,report.get("placed_blocks").getAsInt());
            assertEquals(0,report.get("remaining").getAsInt());
            for (var cell:target.cells()) {
                assertEquals(90,cell.pos().getY(),"replace topsoil at its own height");
                assertEquals(Blocks.STONE.defaultBlockState(),world.state(cell.pos()));
                assertEquals(Blocks.DIRT.defaultBlockState(),world.state(cell.pos().below()),"preserve soil under the foundation");
            }
            assertEquals(stock-count,BuildInventory.available(world.inventory,net.minecraft.world.item.Items.STONE));
            assertTrue(offsets.contains(48));
            assertTrue(offsets.size()<count*3,"full-page scans must be shared by a batch of clearing actions");
            int first=names.indexOf("break_block");
            assertEquals(java.util.List.of("move_to","build_diff","break_block"),names.subList(first+1,first+4));
            assertTrue(result.data().get("construction_query_calls").getAsInt()>0);
            assertTrue(result.data().get("construction_effects_omitted").getAsInt()>0);
            assertEquals(count,result.data().getAsJsonObject("construction_action_calls").get("break_block").getAsInt());
            assertEquals((count+11)/12,result.data().getAsJsonObject("construction_action_calls").get("build_nearby").getAsInt(),
                    "a page boundary must not prematurely end a nearby placement batch");
            assertTrue(result.feedback().getBytes(java.nio.charset.StandardCharsets.UTF_8).length<20000);
        } finally {
            program.stop("CANCELLED:test cleanup");
            program.terminated().get(2,TimeUnit.SECONDS);
        }
    }
}
