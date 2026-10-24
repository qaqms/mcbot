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
