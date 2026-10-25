package com.neko.mcbot.server;

import com.google.gson.JsonObject;
import com.neko.mcbot.path.DigAStar;
import com.neko.mcbot.path.DigSampler;
import com.neko.mcbot.path.NavigationGeometry;
import com.neko.mcbot.path.TerrainEdits;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class PlacementWorkTest {
    @Test void roofRepairSearchUsesActualVisibleWorkStancesAcrossAnEnclosedHouse() {
        var grid = new StandingGeometryTest.Grid(); grid.blocks.clear();
        var sight = new BlockAimTest.Grid(); sight.blocks.clear();
        for (int x=-8;x<=18;x++) for (int z=-8;z<=18;z++)
            grid.blocks.put(new BlockPos(x,-1,z),Blocks.STONE.defaultBlockState());
        for (int x=0;x<=10;x++) for (int z=0;z<=10;z++) {
            grid.blocks.put(new BlockPos(x,0,z),Blocks.OAK_PLANKS.defaultBlockState());
            grid.blocks.put(new BlockPos(x,4,z),Blocks.OAK_PLANKS.defaultBlockState());
            if (x==0 || x==10 || z==0 || z==10)
                for (int y=1;y<4;y++) grid.blocks.put(new BlockPos(x,y,z),Blocks.OAK_PLANKS.defaultBlockState());
        }
        grid.blocks.remove(new BlockPos(5,1,0)); grid.blocks.remove(new BlockPos(5,2,0));
        var target = new BlockPos(2,4,9); grid.blocks.remove(target);
        sight.blocks.putAll(grid.blocks);
        var geometry = grid.geometry();
        var material = new ItemStack(Items.OAK_PLANKS,3);
        var goal = PlacementWork.goal(target,geometry,1.62,.6,1.8,4.5,(eye,bounds,edits) ->
                SupportPlacement.canPlanWorkBlock(sight,sight,target,eye,bounds,material,4.5,edits,
                        p->true,f->true,b->true,hit->Blocks.OAK_PLANKS.defaultBlockState(),s->true)
                        ? null : new ServerTool.Result(false,"TARGET_BLOCKED:no actual contact",null));
        DigSampler terrain = new DigSampler() {
            @Override public NavigationGeometry geometry() { return geometry; }
            @Override public boolean inBounds(int x,int y,int z) {
                return x>=-8 && x<=18 && z>=-8 && z<=18 && y>=-2 && y<=8;
            }
            @Override public boolean passable(int x,int y,int z) {
                return sight.getBlockState(new BlockPos(x,y,z)).isAir();
            }
            @Override public boolean support(int x,int y,int z) { return !passable(x,y,z); }
            @Override public double digSeconds(int x,int y,int z) {
                return y>=1 && !new BlockPos(x,y,z).equals(target) ? .5 : INFEASIBLE;
            }
            @Override public boolean placeable(int x,int y,int z) {
                return y>=0 && !new BlockPos(x,y,z).equals(target);
            }
            @Override public double placeCost(int x,int y,int z) { return 1; }
            @Override public int maxPlaces() { return 16; }
            @Override public boolean canPlaceFrom(int x,int y,int z,long cell,TerrainEdits edits) {
                var foot=geometry.foot(x,y,z,edits);
                if (foot==null) return false;
                double height=cell==DigAStar.pack(x,y,z) ? y+1.02 : foot.height();
                return SupportPlacement.canPlan(sight,sight,
                        new BlockPos(DigAStar.unpackX(cell),DigAStar.unpackY(cell),DigAStar.unpackZ(cell)),
                        new Vec3(x+.5,height+1.62,z+.5),new net.minecraft.world.phys.AABB(
                                x+.2,height,z+.2,x+.8,height+1.8,z+.8),new ItemStack(Items.COBBLESTONE),4.5,
                        edits,p->true,f->true,b->true);
            }
        };
        var route = new DigAStar(terrain,-3,0,9,2,4,9,4000,64,goal).segments();
        while (!route.advance(100)) { }
        assertNull(route.failure(),()->route.failure()+" expanded="+route.expanded());
        assertTrue(route.expanded()<1000,"visible interior stances guide the short repair: "+route.expanded());
        var edits=TerrainEdits.EMPTY;
        for (var step:route.path()) edits=edits.after(step.dig(),step.place());
        var end=route.path().getLast();
        assertTrue(goal.reached(end.x(),end.y(),end.z(),edits));
        assertTrue(sight.getBlockState(target).isAir()); assertEquals(3,material.getCount());
        var reachSeeds = new com.neko.mcbot.path.NavigationGoal() {
            @Override public boolean reached(int x,int y,int z) { return goal.reached(x,y,z); }
            @Override public boolean reached(int x,int y,int z,TerrainEdits changed) { return goal.reached(x,y,z,changed); }
            @Override public double remaining(int x,int y,int z) { return goal.remaining(x,y,z); }
            @Override public double approach(int x,int y,int z) { return goal.approach(x,y,z); }
            @Override public Bounds estimateBounds() { return goal.estimateBounds(); }
        };
        var baseline = new DigAStar(terrain,-3,0,9,2,4,9,4000,64,reachSeeds).segments();
        while (!baseline.advance(100)) { }
        System.out.println("roof repair expanded: work stances="+route.expanded()+", geometric seeds="+baseline.expanded());
        assertTrue(baseline.expanded()>route.expanded(),"compare identical admission rules and different guidance");
    }

    @Test void aChangedPlacementPremiseEndsTheOldSearchWithoutClaimingAnActionOrChangingTheDrawing() {
        var drawing = com.neko.mcbot.building.BuildTarget.parse(com.google.gson.JsonParser.parseString("""
                {"anchor":{"x":2,"y":2,"z":0},"cells":[
                {"x":0,"y":0,"z":0,"block":"oak_log","properties":[{"name":"axis","value":"y"}]}]}
                """).getAsJsonObject());
        var cell = drawing.cells().getFirst();
        var before = drawing.input().deepCopy();
        assertNull(PlacementWork.targetFailure(cell.pos(),Blocks.AIR.defaultBlockState(),cell,
                () -> BlockAim.Anchor.PRESENT));
        var missing = PlacementWork.targetFailure(cell.pos(),Blocks.AIR.defaultBlockState(),cell,
                () -> BlockAim.Anchor.ABSENT);
        assertFalse(missing.ok());
        assertEquals("no_compatible_placement_anchor",missing.data().get("placement_failure").getAsString());
        var done = PlacementWork.targetFailure(cell.pos(),cell.desired(),cell,
                () -> { fail("a satisfied target needs no anchor search"); return null; });
        assertFalse(done.ok());
        assertTrue(done.data().get("target_already_satisfied").getAsBoolean());
        assertFalse(done.data().has("changed"),"target observation must not override earlier route effects");
        assertFalse(done.data().has("consumed_count"),"the route owns its actual scaffold accounting");
        var occupied = PlacementWork.targetFailure(cell.pos(),Blocks.DIRT.defaultBlockState(),cell,
                () -> { fail("clearing must precede placement"); return null; });
        assertEquals("target_needs_clear",occupied.data().get("placement_failure").getAsString());
        assertTrue(PlacementWork.targetFailure(cell.pos(),Blocks.AIR.defaultBlockState(),cell,
                () -> BlockAim.Anchor.UNKNOWN).feedback().startsWith("TARGET_LOST:"));
        assertTrue(PlacementWork.targetFailure(cell.pos(),null,cell,
                () -> { fail("unloaded"); return null; }).feedback().startsWith("TARGET_LOST:"));
        assertNull(PlacementWork.targetFailure(cell.pos(),Blocks.AIR.defaultBlockState(),null,
                () -> { fail("ordinary placement can approach using planned scaffold contacts"); return null; }));
        assertEquals(before,drawing.input());
    }

    @Test void nearButUnworkableReachDoesNotFloodEditableGroundBeforeTryingTheBuilding() {
        var grid = new StandingGeometryTest.Grid();
        grid.blocks.clear();
        for (int x = -8; x <= 12; x++) for (int z = -8; z <= 8; z++)
            grid.blocks.put(new BlockPos(x, -1, z), Blocks.STONE.defaultBlockState());
        var target = new BlockPos(5, 0, 0);
        var goal = PlacementWork.goal(target, grid.geometry(), 1.62, .6, 1.8, 4.5,
                (eye, bounds, edits) -> eye.x >= 7.5 && eye.y == 1.62 && eye.z == .5
                        ? null : new ServerTool.Result(false, "TARGET_BLOCKED:no clickable face", null));
        DigSampler terrain = new DigSampler() {
            @Override public NavigationGeometry geometry() { return grid.geometry(); }
            @Override public boolean passable(int x, int y, int z) { return y >= 0; }
            @Override public boolean support(int x, int y, int z) { return y == -1; }
            @Override public double digSeconds(int x, int y, int z) { return INFEASIBLE; }
            @Override public boolean placeable(int x, int y, int z) { return y >= 0; }
            @Override public double placeCost(int x, int y, int z) { return 1; }
            @Override public int maxPlaces() { return 16; }
            @Override public boolean inBounds(int x, int y, int z) {
                return x >= -8 && x <= 12 && z >= -8 && z <= 8 && y >= -1 && y <= 6;
            }
        };
        assertEquals(0, goal.remaining(0, 0, 0));
        assertFalse(goal.reached(0, 0, 0), "geometric reach is not actual work arrival");
        var route = new DigAStar(terrain, 0, 0, 0, 5, 0, 0, 4000, 0, goal).segments();
        while (!route.advance(300)) { }
        assertNull(route.failure());
        assertTrue(route.expanded() < 1000, "short feasible work approach must precede flooding edit histories: "
                + route.expanded());
        var end = route.path().getLast();
        assertTrue(goal.reached(end.x(), end.y(), end.z(), TerrainEdits.EMPTY));
        assertEquals(0, route.placedCount());
    }

    @Test void automaticPlacementFacesCanUseAnExposedSideWhileAnExplicitFaceStaysExact() {
        var tried = new java.util.ArrayList<Direction>();
        java.util.function.Function<Direction, ServerTool.Result> assess = face -> {
            tried.add(face);
            return face == Direction.EAST ? null : new ServerTool.Result(false,
                    "TARGET_BLOCKED:no clickable face", null);
        };
        assertNull(PlacementWork.assessFaces(null, assess));
        assertTrue(tried.contains(Direction.EAST));
        tried.clear();
        assertNotNull(PlacementWork.assessFaces(Direction.UP, assess));
        assertEquals(List.of(Direction.UP), tried);
    }

    @Test void aRoofExtensionWithOnlyASideAnchorUsesRealShapeRaysWithoutDemandingABlockUnderIt() {
        var grid=new StandingGeometryTest.Grid();
        var sight=new BlockAimTest.Grid();
        sight.blocks.clear();
        var target=new BlockPos(2,2,0);
        sight.blocks.put(target.west(),Blocks.STONE.defaultBlockState());
        PlacementWork.Assessor automatic=(eye,bounds,edits)->PlacementWork.assessFaces(null,face->{
            var hit=BlockAim.placementContact(BlockAim.fromEye(sight,eye),
                    target.relative(face.getOpposite()),face,4.5);
            return hit.ok()?null:new ServerTool.Result(false,hit.failure(),null);
        });
        var goal=PlacementWork.goal(target,grid.geometry(),1.62,.6,1.8,4.5,automatic);
        assertTrue(goal.reached(3,0,0));
        var up=BlockAim.placementContact(BlockAim.fromEye(sight,new Vec3(3.5,1.62,.5)),
                target.below(),Direction.UP,4.5);
        assertFalse(up.ok());
        assertEquals(1,sight.blocks.size(),"read-only standing assessment never invents an anchor");
        sight.blocks.clear();
        assertFalse(goal.reached(3,0,0),"a real lost anchor invalidates the stance");
    }
    @BeforeAll static void registries() { BlockActionFixtures.bootstrap(); }

    @Test void searchLeavesTheRequestedCellWithoutChangingTheTargetOrConsumingMaterial() {
        var grid = new StandingGeometryTest.Grid();
        var access = new BlockActionFixtures.Placement();
        access.inventory.setItem(17, new ItemStack(Items.COBBLESTONE, 4));
        var calls = new AtomicInteger();
        var goal = PlacementWork.goal(BlockPos.ZERO, grid.geometry(), 1.62, .6, 1.8, 4.5,
                (eye, bounds, edits) -> {
                    calls.incrementAndGet();
                    return BlockPlacement.assess(access, BlockPos.ZERO, 17, Direction.UP);
                });
        assertFalse(goal.reached(0, 0, 0));
        assertEquals(0, calls.get(), "target occupancy must be rejected before placement assessment");
        DigSampler terrain = new DigSampler() {
            @Override public boolean passable(int x, int y, int z) { return y >= 0; }
            @Override public boolean support(int x, int y, int z) { return y == -1; }
            @Override public double digSeconds(int x, int y, int z) { return INFEASIBLE; }
            @Override public boolean placeable(int x, int y, int z) { return false; }
            @Override public double placeCost(int x, int y, int z) { return INFEASIBLE; }
            @Override public boolean inBounds(int x, int y, int z) {
                return Math.abs(x) <= 2 && Math.abs(z) <= 2 && y >= -1 && y <= 3;
            }
            @Override public int maxPlaces() { return 0; }
            @Override public NavigationGeometry geometry() { return grid.geometry(); }
        };
        var route = new DigAStar(terrain, 0, 0, 0, 0, 0, 0, 1000, 0, goal);
        while (!route.advance(100)) { }
        assertNull(route.failure());
        assertTrue(route.path().size() > 1);
        var end = route.path().getLast();
        assertTrue(goal.reached(end.x(), end.y(), end.z()));
        assertTrue(route.path().stream().allMatch(s -> s.dig().isEmpty() && s.place().isEmpty()));
        assertEquals(0, access.calls);
        assertEquals(4, access.inventory.getItem(17).getCount());
        assertTrue(access.prepared.stream().allMatch(BlockPos.ZERO::equals));
    }

    @Test void fractionalFeetUseTheSameVisibleFaceAndReachAsTheSinglePlacementPreflight() {
        var grid = new StandingGeometryTest.Grid();
        grid.blocks.put(BlockPos.ZERO, Blocks.OAK_SLAB.defaultBlockState());
        var world = new BlockAimTest.Grid();
        world.blocks.clear();
        var target = new BlockPos(2, 0, 0);
        world.blocks.put(target.below(), Blocks.STONE.defaultBlockState());
        var access = new BlockActionFixtures.Placement();
        access.inventory.setItem(11, new ItemStack(Items.COBBLESTONE, 2));
        for (double reach : new double[]{1.5, 2, 4.5}) {
            PlacementWork.Assessor assess = (eye, bounds, edits) -> {
                assertEquals(.5, bounds.minY);
                assertEquals(2.12, eye.y, 1e-9);
                access.sightAt = (pos, face) -> {
                    var contact = BlockAim.placementContact(BlockAim.fromEye(world, eye), pos.below(), face, reach);
                    return contact.ok() ? null : new ServerTool.Result(false, contact.failure(), null);
                };
                return BlockPlacement.assess(access, target, 11, Direction.UP);
            };
            var goal = PlacementWork.goal(target, grid.geometry(), 1.62, .6, 1.8, reach, assess);
            assertEquals(BlockAim.placementContact(BlockAim.fromEye(world, new Vec3(.5, 2.12, .5)),
                    target.below(), Direction.UP, reach).ok(), goal.reached(0, 0, 0));
        }
        assertEquals(0, access.calls);
        assertEquals(2, access.inventory.getItem(11).getCount());
    }

    @Test void changedAnchorOccupancyInventoryAndLandingAreReassessedBeforeAnAction() {
        var grid = new StandingGeometryTest.Grid();
        var world = new BlockAimTest.Grid();
        world.blocks.clear();
        var target = new BlockPos(2, 0, 0);
        world.blocks.put(target.below(), Blocks.STONE.defaultBlockState());
        var access = new BlockActionFixtures.Placement();
        access.inventory.setItem(13, new ItemStack(Items.COBBLESTONE, 3));
        var goal = PlacementWork.goal(target, grid.geometry(), 1.62, .6, 1.8, 4.5,
                (eye, bounds, edits) -> {
                    access.sightAt = (pos, face) -> {
                        var contact = BlockAim.placementContact(BlockAim.fromEye(world, eye), pos.below(), face, 4.5);
                        return contact.ok() ? null : new ServerTool.Result(false, contact.failure(), null);
                    };
                    return BlockPlacement.assess(access, target, 13, Direction.UP);
                });
        assertTrue(goal.reached(0, 0, 0));
        world.blocks.clear();
        assertFalse(goal.reached(0, 0, 0), "lost anchor cannot remain workable");
        world.blocks.put(target.below(), Blocks.STONE.defaultBlockState());
        for (String reason : List.of("entity_collision", "wrong_landing", "occupied_block")) {
            access.prepareFailure = new ServerTool.Result(false, "TARGET_BLOCKED:" + reason, null);
            assertFalse(goal.reached(0, 0, 0));
            assertFalse(BlockPlacement.place(access, target, 13, Direction.UP).ok());
        }
        access.prepareFailure = null;
        access.inventory.setItem(13, ItemStack.EMPTY);
        assertFalse(goal.reached(0, 0, 0));
        assertEquals(0, access.calls);
        access.inventory.setItem(13, new ItemStack(Items.COBBLESTONE, 3));
        assertTrue(goal.reached(0, 0, 0));
        assertTrue(BlockPlacement.place(access, target, 13, Direction.UP).ok());
        assertEquals(1, access.calls);
        assertEquals(2, access.inventory.getItem(13).getCount());
    }

    @Test void unknownOrUnsupportedStandingAndLiveSupportChangesNeverPassWorkArrival() {
        var grid = new StandingGeometryTest.Grid();
        var goal = PlacementWork.goal(new BlockPos(2, 0, 0), grid.geometry(), 1.62, .6, 1.8, 4.5,
                (eye, bounds, edits) -> null);
        assertTrue(goal.reached(0, 0, 0));
        grid.unknown.add(BlockPos.ZERO.below());
        assertFalse(goal.reached(0, 0, 0));
        grid.unknown.clear();
        grid.unsupported.add(BlockPos.ZERO);
        assertFalse(goal.reached(0, 0, 0));
        grid.unsupported.clear();
        grid.blocks.remove(BlockPos.ZERO.below());
        assertFalse(goal.reached(0, 0, 0));
    }

    @Test void pendingNativeNeighborhoodDefersUntilActualWorldChangesWithoutReadingABody() {
        long near = DigAStar.pack(1, 0, 0), far = DigAStar.pack(4, 0, 0);
        for (var edits : List.of(TerrainEdits.EMPTY.after(List.of(near), List.of()),
                TerrainEdits.EMPTY.after(List.of(), List.of(near)))) {
            var result = BlockPlacement.assessAt(null, BlockPos.ZERO, 0, Direction.UP,
                    Vec3.ZERO, new net.minecraft.world.phys.AABB(BlockPos.ZERO), edits);
            assertFalse(result.ok());
            assertTrue(result.feedback().contains("尚未落地"));
            assertFalse(result.data().get("changed").getAsBoolean());
        }
        assertFalse(BlockPlacement.pendingNativeTerrain(BlockPos.ZERO, TerrainEdits.EMPTY));
        assertFalse(BlockPlacement.pendingNativeTerrain(BlockPos.ZERO,
                TerrainEdits.EMPTY.after(List.of(far), List.of())));
    }

    @Test void terminalFreshAssessmentPreservesMovementAndNeverAssessesFailedOrCancelledRoutes() {
        var data = new JsonObject();
        data.addProperty("x", 1);
        data.addProperty("feet_y", .5);
        data.addProperty("removed_blocks", 2);
        var moved = new ServerTool.Result(true, "arrived", data);
        var allowed = PlacementWork.finish(moved, () -> new ServerTool.Result(true, "ready", data));
        assertTrue(allowed.ok());
        assertTrue(allowed.data().get("can_work_here").getAsBoolean());
        assertEquals("place", allowed.data().get("work_kind").getAsString());
        var blocked = PlacementWork.finish(moved,
                () -> new ServerTool.Result(false, "TARGET_BLOCKED:new entity", null));
        assertFalse(blocked.ok());
        assertEquals(.5, blocked.data().get("feet_y").getAsDouble());
        assertEquals(2, blocked.data().get("removed_blocks").getAsInt());
        assertFalse(data.has("can_work_here"));
        for (String reason : List.of("CANCELLED:", "PARTIAL:", "NEED_CONFIRM:")) {
            var failure = new ServerTool.Result(false, reason, data);
            assertSame(failure, PlacementWork.finish(failure, () -> { fail("failed route"); return null; }));
        }
    }
}
