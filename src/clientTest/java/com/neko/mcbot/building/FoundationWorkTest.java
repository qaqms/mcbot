package com.neko.mcbot.building;

import com.google.gson.JsonParser;
import com.neko.mcbot.server.ServerTool;
import com.neko.mcbot.task.TickTask;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class FoundationWorkTest {
    @BeforeAll static void bootstrap() { com.neko.mcbot.server.BlockActionFixtures.bootstrap(); }

    static BuildTarget drawing(int width) {
        return BuildTarget.parse(JsonParser.parseString("""
                {"anchor":{"x":0,"y":90,"z":0},"regions":[
                {"from":{"x":0,"y":0,"z":0},"to":{"x":%d,"y":0,"z":0},"block":"oak_planks"},
                {"from":{"x":0,"y":1,"z":0},"to":{"x":0,"y":1,"z":0},"block":"stone"}]}
                """.formatted(width - 1)).getAsJsonObject());
    }

    static ServerTool.Result finish(BuildWork work) {
        for (int tick = 0; tick < 100; tick++)
            if (work.tickSafe(null) instanceof TickTask.Progress.Done done) return done.result();
        throw new AssertionError("construction did not settle");
    }

    @Test void absentPlanksWithLogsRetainsFoundationCandidatesAndReportsOnlyTheirImmediateShortfall() {
        var target = drawing(17);
        var world = new BuildWorkTest.World();
        target.cells().stream().filter(c -> c.pos().getY() == 90)
                .forEach(c -> world.blocks.put(c.pos(), Blocks.DIRT.defaultBlockState()));
        world.inventory.setItem(0, new ItemStack(Items.OAK_LOG, 64));
        world.inventory.setItem(1, new ItemStack(Items.STONE, 64));
        var data = BuildSurvey.workScan(target, world, 0, () -> 0).data();
        assertEquals(17, data.getAsJsonObject("counts").get("needs_clear").getAsInt());
        for (var row : data.getAsJsonArray("cells"))
            assertEquals(90, row.getAsJsonObject().get("y").getAsInt());
        var foundation = data.getAsJsonObject("foundation_work");
        assertNotNull(foundation);
        assertTrue(foundation.get("active").getAsBoolean());
        assertTrue(foundation.get("supply_required").getAsBoolean());
        var item = foundation.getAsJsonArray("materials").get(0).getAsJsonObject();
        assertEquals("minecraft:oak_planks", item.get("item").getAsString());
        assertEquals(17, item.get("shortfall").getAsInt());
        assertEquals(64, world.inventory.getItem(0).getCount(), "a survey cannot turn logs into planks");
        assertEquals(0, world.actions);
    }

    @Test void automaticAndExplicitPageBatchesDoNotBuildAboveDistantOrBlockedFoundation() {
        for (boolean far : new boolean[]{true, false}) {
            for (int offset : new int[]{-1, 16}) {
                var target = drawing(17);
                var world = new BuildWorkTest.World();
                world.inventory.setItem(0, new ItemStack(Items.OAK_PLANKS, 32));
                world.inventory.setItem(1, new ItemStack(Items.STONE, 32));
                if (far) target.cells().stream().filter(c -> c.pos().getY() == 90)
                        .forEach(c -> world.far.add(c.pos()));
                else world.prepare = (p, face) -> p.getY() == 90
                        ? new ServerTool.Result(false, "TARGET_BLOCKED:controlled obstruction", null) : null;
                var result = finish(new BuildWork(target, world, offset, 12, () -> 0));
                assertFalse(result.data().get("goal_satisfied").getAsBoolean());
                assertEquals(0, world.actions, "low-layer failure or pagination is not permission to build upstairs");
                assertFalse(world.blocks.containsKey(new BlockPos(0, 91, 0)));
            }
        }
    }

    @Test void upperPlacementRechecksFoundationAfterItsRegionIsReserved() {
        var target = drawing(17);
        var world = new BuildWorkTest.World();
        target.cells().stream().filter(c -> c.pos().getY() == 90)
                .forEach(c -> world.blocks.put(c.pos(), c.desired()));
        world.inventory.setItem(0, new ItemStack(Items.STONE, 32));
        world.beforeReserve = () -> world.blocks.remove(new BlockPos(16, 90, 0));
        var result = finish(new BuildWork(target, world, -1, 12, () -> 0));
        assertEquals(0, world.actions);
        assertFalse(result.ok());
        assertTrue(result.feedback().startsWith("TARGET_BLOCKED:"), result.feedback());
    }

    @Test void partialFoundationStockIsUsedWithoutRequiringTheWholeBuildingOrSkippingUpstairs() {
        var target = drawing(25);
        var world = new BuildWorkTest.World();
        world.inventory.setItem(0, new ItemStack(Items.OAK_PLANKS, 23));
        world.inventory.setItem(1, new ItemStack(Items.STONE, 64));
        world.inventory.setItem(2, new ItemStack(Items.OAK_LOG, 64));
        for (int batch = 0; batch < 3; batch++)
            finish(new BuildWork(target, world, -1, 12, () -> 0));
        assertEquals(23, world.actions);
        assertTrue(world.blocks.keySet().stream().allMatch(p -> p.getY() == 90));
        var summary = BuildSurvey.workScan(target, world, 0, () -> 0).data().getAsJsonObject("foundation_work");
        assertTrue(summary.get("supply_required").getAsBoolean());
        assertEquals(2, summary.get("unfinished").getAsInt());
        assertEquals(64, world.inventory.getItem(2).getCount());
    }

    @Test void recordedAccessRepairsRemainSeparateFromNeverFinishedFoundation() {
        var target = drawing(2);
        var hole = new BlockPos(0, 90, 0);
        var world = new BuildSurveyTest.World() {
            @Override public boolean repairPending(BlockPos p) { return p.equals(hole); }
        };
        world.blocks.put(new BlockPos(1, 90, 0), Blocks.OAK_PLANKS.defaultBlockState());
        world.inventory.setItem(0, new ItemStack(Items.STONE, 1));
        var summary = BuildSurvey.workScan(target, world, 0, () -> 0).data().getAsJsonObject("foundation_work");
        assertNotNull(summary);
        assertEquals(1, summary.get("repairs_pending").getAsInt());
        assertFalse(summary.get("active").getAsBoolean(), "an active passage uses the existing repair lifecycle");
    }

    @Test void attachmentsBelowTheStructureCannotBecomeAnImpossibleFoundationGate() {
        var target = BuildTarget.parse(JsonParser.parseString("""
                {"anchor":{"x":0,"y":90,"z":0},"cells":[
                {"x":0,"y":0,"z":0,"block":"red_carpet"},
                {"x":1,"y":1,"z":0,"block":"oak_planks"},
                {"x":1,"y":2,"z":0,"block":"stone"}]}
                """).getAsJsonObject());
        var world = new BuildWorkTest.World();
        world.inventory.setItem(0, new ItemStack(Items.STONE, 1));
        var summary = BuildSurvey.workScan(target, world, 0, () -> 0).data().getAsJsonObject("foundation_work");
        assertEquals(91, summary.get("y").getAsInt());
        assertEquals(1, summary.get("unfinished").getAsInt());
        world.blocks.put(new BlockPos(1, 91, 0), Blocks.OAK_PLANKS.defaultBlockState());
        var result = finish(new BuildWork(target, world, -1, 12, () -> 0));
        assertEquals(1, world.actions, "the unfinished carpet does not prevent building the independent structure");
        assertFalse(result.data().get("goal_satisfied").getAsBoolean(), "the carpet still counts in full verification");
    }

    @Test void changedUnknownOrProtectedFoundationNeverBecomesPermissionForUpperWork() {
        for (String reason : java.util.List.of("unknown", "protected")) {
            var target = drawing(17);
            var world = new BuildWorkTest.World();
            world.inventory.setItem(0, new ItemStack(Items.OAK_PLANKS, 32));
            world.inventory.setItem(1, new ItemStack(Items.STONE, 32));
            for (var c : target.cells()) if (c.pos().getY() == 90) {
                if (reason.equals("unknown")) world.unknown.add(c.pos());
                else world.protectedCells.add(c.pos());
            }
            var result = finish(new BuildWork(target, world, -1, 12, () -> 0));
            assertEquals(0, world.actions);
            assertFalse(result.data().get("goal_satisfied").getAsBoolean());
        }
    }
}
