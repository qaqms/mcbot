package com.neko.mcbot.server;

import com.google.gson.JsonObject;
import com.neko.mcbot.path.DigAStar;
import com.neko.mcbot.path.DigSampler;
import com.neko.mcbot.path.TerrainEdits;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class PlacementWorkSetTest {
    @BeforeAll static void bootstrap() { BlockActionFixtures.bootstrap(); }
    static PlacementWorkSet.Member member(BlockPos p, Direction face) {
        return new PlacementWorkSet.Member(p, new PlacementWork(
                (net.minecraft.world.item.BlockItem) Items.COBBLESTONE, face));
    }
    static PlacementWorkSet set() {
        return new PlacementWorkSet(List.of(member(new BlockPos(2, 0, 0), Direction.UP),
                member(new BlockPos(4, 0, 0), Direction.EAST)), "a".repeat(64));
    }

    @Test void strictCompositeFindsANativeClickableSecondMemberAndReportsThatMember() {
        var set = set();
        var world = new BlockAimTest.Grid(); world.blocks.clear();
        var grid = new StandingGeometryTest.Grid();
        for (int x = -2; x <= 9; x++) for (int z = -2; z <= 2; z++)
            grid.blocks.put(new BlockPos(x, -1, z), Blocks.STONE.defaultBlockState());
        // The first intent requests an absent UP anchor; only the second has a real EAST face.
        world.blocks.put(new BlockPos(3, 0, 0), Blocks.STONE.defaultBlockState());
        grid.blocks.putAll(world.blocks);
        var geometry = grid.geometry();
        var goal = set.goal(member -> PlacementWork.goal(member.target(), geometry, 1.62, .6, 1.8, 4.5,
                (eye, bounds, edits) -> {
                    var hit = BlockAim.placementContact(BlockAim.fromEye(world, eye),
                            member.target().relative(member.work().face().getOpposite()), member.work().face(), 4.5);
                    return hit.ok() ? null : new ServerTool.Result(false, hit.failure(), null);
                }));
        DigSampler terrain = new DigSampler() {
            @Override public com.neko.mcbot.path.NavigationGeometry geometry() { return geometry; }
            @Override public boolean passable(int x, int y, int z) { return !grid.blocks.containsKey(new BlockPos(x,y,z)); }
            @Override public boolean support(int x, int y, int z) { return grid.blocks.containsKey(new BlockPos(x,y,z)); }
            @Override public double digSeconds(int x, int y, int z) { return INFEASIBLE; }
            @Override public boolean placeable(int x, int y, int z) { return false; }
            @Override public double placeCost(int x, int y, int z) { return INFEASIBLE; }
            @Override public int maxPlaces() { return 0; }
            @Override public boolean inBounds(int x, int y, int z) {
                return x >= -2 && x <= 9 && z >= -2 && z <= 2 && y >= -1 && y <= 4;
            }
        };
        var search = new DigAStar(terrain, 0, 0, 0, 2, 0, 0, 4000, 0, goal);
        while (!search.advance(100)) { }
        assertNull(search.failure());
        var end = search.path().getLast();
        var eye = new Vec3(end.x()+.5, end.feetY()+1.62, end.z()+.5);
        var data = new JsonObject(); data.addProperty("placed_blocks", 3);
        var result = set.finish(new ServerTool.Result(true, "arrived", data), member -> {
            var aim = BlockAim.placementContact(BlockAim.fromEye(world, eye),
                    member.target().relative(member.work().face().getOpposite()), member.work().face(), 4.5);
            return new ServerTool.Result(aim.ok(), aim.ok() ? "native preflight" : aim.failure(), null);
        });
        assertTrue(result.ok(), result.feedback());
        assertEquals(4, result.data().getAsJsonObject("selected_work_target").get("x").getAsInt());
        assertEquals(3, result.data().get("placed_blocks").getAsInt());
        assertFalse(data.has("selected_work_target"), "the source route receipt remains immutable");
        assertEquals(0, search.placedCount(), "finding work must not perform the work");
    }

    @Test void losingOneMemberPreservesOthersAndNeverResurrectsAStaleMemberInsideTheRoute() {
        var set = set();
        var blocked = new ServerTool.Result(false, "TARGET_BLOCKED:target satisfied", null);
        assertNull(set.refresh(member -> member.target().getX() == 2 ? blocked : null));
        assertEquals(1, set.generation());
        var goal = set.goal(member -> com.neko.mcbot.path.NavigationGoal.position(member.target().getX(), 0, 0, 0));
        assertFalse(goal.reached(2, 0, 0));
        assertTrue(goal.reached(4, 0, 0));
        assertNull(set.refresh(member -> { assertEquals(4, member.target().getX()); return null; }));
        assertEquals(1, set.generation());
        var lost = set.refresh(member -> new ServerTool.Result(false, "TARGET_LOST:anchor unknown", null));
        assertTrue(lost.feedback().startsWith("TARGET_LOST:"));
        assertEquals(2, lost.data().getAsJsonArray("work_member_failures").size());
        assertFalse(lost.data().has("changed"), "premise checks cannot erase earlier route edits");
    }

    @Test void failedRoutesNeverAssessOrSelectMembersAndLiveArrivalFailureKeepsEffects() {
        var set = set();
        var data = new JsonObject(); data.addProperty("removed_blocks", 2);
        var denied = set.finish(new ServerTool.Result(false, "NEED_CONFIRM:original list", data),
                member -> { fail("authorization is not work arrival"); return null; });
        assertTrue(denied.feedback().startsWith("NEED_CONFIRM:"));
        assertFalse(denied.data().has("selected_work_target"));
        var changed = set.finish(new ServerTool.Result(true, "moved", data),
                member -> new ServerTool.Result(false, "NO_ITEM:changed", null));
        assertFalse(changed.ok());
        assertEquals(2, changed.data().get("removed_blocks").getAsInt());
        assertFalse(changed.data().get("can_work_here").getAsBoolean());
        assertTrue(changed.feedback().startsWith("NO_ITEM:"));
        assertEquals(denied.data().get("work_set_id"), changed.data().get("work_set_id"));
    }

    @Test void globalPermissionAndAllMissingMaterialFailuresKeepTheirOriginalMeaning() {
        var set = set();
        var denied = new ServerTool.Result(false,"DENIED:original permission",null);
        assertSame(denied,set.refresh(member -> denied));
        assertEquals(0,set.generation(),"global denial must not turn into a discarded member");
        var missing = set.refresh(member -> new ServerTool.Result(false,"NO_ITEM:no material",null));
        assertTrue(missing.feedback().startsWith("NO_ITEM:"));
    }
}
