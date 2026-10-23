package com.neko.mcbot.path;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class PathClearanceTest {
    @Test void productionSearchAndLiveValidationAgreeOnDescendingWorkAndWalkingBack() {
        var blocks = new HashSet<Long>();
        for (int x = 0; x < 4; x++) for (int y = 0; y < 4; y++) blocks.add(DigAStar.pack(x, y, 0));
        DigSampler hill = new DigSampler() {
            @Override public boolean passable(int x, int y, int z) {
                return inBounds(x, y, z) && !blocks.contains(DigAStar.pack(x, y, z));
            }
            @Override public double digSeconds(int x, int y, int z) {
                return inBounds(x, y, z) && y > 0 ? 1 : INFEASIBLE;
            }
            @Override public boolean support(int x, int y, int z) { return blocks.contains(DigAStar.pack(x, y, z)); }
            @Override public boolean placeable(int x, int y, int z) { return false; }
            @Override public double placeCost(int x, int y, int z) { return INFEASIBLE; }
            @Override public boolean inBounds(int x, int y, int z) {
                return x >= 0 && x < 4 && y >= 0 && y <= 5 && z == 0;
            }
            @Override public int maxPlaces() { return 0; }
        };
        var from = new net.minecraft.core.BlockPos(0, 4, 0);
        var target = new net.minecraft.core.BlockPos(3, 1, 0);
        var walk = PathTask.searchFor(hill, from, target, true, true);
        while (!walk.advance(300)) { }
        assertNotNull(walk.failure());
        var down = PathTask.searchFor(hill, from, target, true, false);
        while (!down.advance(300)) { }
        assertNull(down.failure());
        var fresh = PathTask.liveSteps(down.path(), from.getY(), hill);
        assertEquals(down.path().stream().map(DigAStar.Step::dig).toList(),
                fresh.stream().map(DigAStar.Step::dig).toList());
        assertTrue(PathTask.editsFeasible(fresh, hill), "a selected route must pass the real pre-execution gate");
        for (var step : fresh) for (long cell : step.dig()) assertTrue(blocks.remove(cell));
        var up = PathTask.searchFor(hill, target, from, true, true);
        while (!up.advance(300)) { }
        assertNull(up.failure());
        assertTrue(up.path().stream().allMatch(step -> step.dig().isEmpty() && step.place().isEmpty()));
        assertTrue(PathTask.editsFeasible(PathTask.liveSteps(up.path(), target.getY(), hill), hill));
    }

    @Test void theProductionWalkOnlySearchCannotDigThroughACorridor() {
        DigSampler corridor = new DigSampler() {
            @Override public boolean passable(int x, int y, int z) { return inBounds(x, y, z) && x != 1; }
            @Override public double digSeconds(int x, int y, int z) { return 1; }
            @Override public boolean support(int x, int y, int z) { return x >= 0 && x <= 2 && y == 63 && z == 0; }
            @Override public boolean placeable(int x, int y, int z) { return false; }
            @Override public double placeCost(int x, int y, int z) { return 1; }
            @Override public boolean inBounds(int x, int y, int z) { return x >= 0 && x <= 2 && y >= 63 && y <= 65 && z == 0; }
            @Override public int maxPlaces() { return 0; }
        };
        var start = new net.minecraft.core.BlockPos(0, 64, 0);
        var target = new net.minecraft.core.BlockPos(2, 64, 0);
        var walking = PathTask.searchFor(corridor, start, target, true, true);
        assertTrue(walking.advance(8000, Long.MAX_VALUE));
        assertNotNull(walking.failure());
        var working = PathTask.searchFor(corridor, start, target, true, false);
        assertTrue(working.advance(8000, Long.MAX_VALUE));
        assertNull(working.failure());
        assertTrue(working.path().stream().anyMatch(step -> !step.dig().isEmpty()));
    }
    private static final class Cells implements DigSampler {
        final Set<Long> blocked = new HashSet<>();
        @Override public boolean passable(int x, int y, int z) {
            return !blocked.contains(DigAStar.pack(x, y, z));
        }
        @Override public double digSeconds(int x, int y, int z) { return 1; }
        @Override public boolean support(int x, int y, int z) { return true; }
        @Override public boolean placeable(int x, int y, int z) { return false; }
        @Override public double placeCost(int x, int y, int z) { return 1; }
        @Override public boolean inBounds(int x, int y, int z) { return true; }
        @Override public int maxPlaces() { return 0; }
    }

    @Test void livePlanValidationHasNoImplicit128DigQuota() {
        var cells = new Cells();
        var plan = new java.util.ArrayList<DigAStar.Step>();
        plan.add(new DigAStar.Step(0, 64, 0, List.of(), List.of(), DigAStar.ACT_START));
        for (int x = 1; x <= 40; x++)
            plan.add(new DigAStar.Step(x, 64, 0, List.of(), List.of(), DigAStar.ACT_DIG));
        for (int x = 40; x >= 0; x--)
            plan.add(new DigAStar.Step(x, 64, 1, List.of(), List.of(), DigAStar.ACT_DIG));
        for (var step : plan.subList(1, plan.size())) {
            cells.blocked.add(DigAStar.pack(step.x(), 64, step.z()));
            cells.blocked.add(DigAStar.pack(step.x(), 65, step.z()));
        }
        var live = PathTask.liveSteps(plan, 64, cells);
        assertTrue(live.stream().mapToInt(step -> step.dig().size()).sum() > 128);
        assertTrue(PathTask.editsFeasible(live, cells));
        assertNotNull(PathTask.planFailure(live, cells, 128), "an explicit finite cap still applies");
    }

    @Test void cachedAndFreshPlansRebuildTheSameCompleteClearance() {
        var cells = new Cells();
        var expected = DigAStar.clearanceCells(66, 88, 65, -12);
        cells.blocked.addAll(expected);
        var start = new DigAStar.Step(87, 66, -12, List.of(), List.of(), DigAStar.ACT_START);
        var end = new DigAStar.Step(88, 65, -12, List.of(), List.of(), DigAStar.ACT_FALL);
        var live = PathTask.liveSteps(List.of(start, end), start.y(), cells);
        assertEquals(expected, live.getLast().dig());
        assertEquals(start, live.getFirst());
        assertTrue(live.getLast().place().isEmpty());
    }

    @Test void remainingPermissionListRetainsTheDepartureHeightWithoutAStartNode() {
        var cells = new Cells();
        long top = DigAStar.pack(88, 67, -12);
        long bottom = DigAStar.pack(88, 65, -12);
        cells.blocked.addAll(List.of(top, bottom));
        var end = new DigAStar.Step(88, 65, -12, List.of(bottom), List.of(), DigAStar.ACT_DIG);
        var live = PathTask.liveSteps(List.of(end), 66, cells);
        assertEquals(List.of(top, bottom), live.getFirst().dig());
        assertEquals(List.of(bottom), end.dig(), "cached nodes must not be mutated");
    }

    @Test void rebuildingAfterRemovalDoesNotReintroduceCompletedWork() {
        var cells = new Cells();
        long bottom = DigAStar.pack(88, 65, -12);
        cells.blocked.add(bottom);
        var end = new DigAStar.Step(88, 65, -12,
                DigAStar.clearanceCells(66, 88, 65, -12), List.of(), DigAStar.ACT_DIG);
        assertEquals(List.of(bottom), PathTask.liveSteps(List.of(end), 66, cells).getFirst().dig());
        assertTrue(PathTask.liveSteps(List.of(), 66, cells).isEmpty());
    }

    @Test void orderedPlanCannotStandOnASupportRemovedByAnEarlierStep() {
        var cells = new Cells();
        long removedFloor = DigAStar.pack(1, 65, 0);
        cells.blocked.add(removedFloor);
        var start = new DigAStar.Step(0, 64, 0, List.of(), List.of(), DigAStar.ACT_START);
        var clear = new DigAStar.Step(1, 64, 0, List.of(removedFloor), List.of(), DigAStar.ACT_DIG);
        var returnAbove = new DigAStar.Step(1, 66, 0, List.of(), List.of(), DigAStar.ACT_JUMP);
        assertFalse(PathTask.editsFeasible(List.of(start, clear, returnAbove), cells),
                "static support alone incorrectly accepts this route");
        assertEquals(java.util.Set.of(removedFloor), cells.blocked, "validation must not change the world");
    }

    @Test void repeatedColumnsDoNotRepeatDigEntriesOrExcavateTheDepartureFloor() {
        var cells = new Cells();
        long shared = DigAStar.pack(1, 65, 0);
        cells.blocked.add(shared);
        var a = new DigAStar.Step(1, 64, 0, List.of(), List.of(), DigAStar.ACT_DIG);
        var b = new DigAStar.Step(2, 64, 0, List.of(), List.of(), DigAStar.ACT_WALK);
        var live = PathTask.liveSteps(List.of(a, b, a), 64, cells);
        assertEquals(List.of(shared), live.getFirst().dig());
        assertTrue(live.getLast().dig().isEmpty());
        var start = new DigAStar.Step(0, 64, 0, List.of(), List.of(), DigAStar.ACT_START);
        var down = new DigAStar.Step(0, 63, 0, List.of(DigAStar.pack(0, 63, 0)), List.of(), DigAStar.ACT_DIG);
        assertFalse(PathTask.editsFeasible(List.of(start, down), cells));
        cells.blocked.add(DigAStar.pack(1, 63, 0));
        var missingHead = new DigAStar.Step(1, 63, 0, List.of(DigAStar.pack(1, 63, 0)), List.of(), DigAStar.ACT_DIG);
        assertFalse(PathTask.editsFeasible(List.of(start, missingHead), cells), "swept clearance cannot be omitted");
        var stair = new DigAStar.Step(1, 63, 0, List.of(shared, DigAStar.pack(1, 63, 0)), List.of(), DigAStar.ACT_DIG);
        assertTrue(PathTask.editsFeasible(List.of(start, stair), cells), "legal adjacent stairs remain valid");
    }

    @Test void routeDiagnosticsIdentifyTheStepAndExactSupportWithoutChangingTheWorld() {
        var cells = new Cells();
        long floor = DigAStar.pack(0, 63, 0);
        cells.blocked.add(floor);
        var start = new DigAStar.Step(0, 64, 0, List.of(), List.of(), DigAStar.ACT_START);
        var down = new DigAStar.Step(0, 63, 0, List.of(floor), List.of(), DigAStar.ACT_DIG);
        var failure = PathTask.planFailure(List.of(start, down), cells, 128);
        assertEquals("DEPARTURE_SUPPORT", failure.reason());
        assertEquals(1, failure.step());
        assertEquals(floor, failure.cell());
        assertEquals(DigAStar.pack(0, 64, 0), failure.from());
        assertEquals(DigAStar.pack(0, 63, 0), failure.to());
        assertTrue(failure.summary().contains("cell=0,63,0"));
        assertEquals(Set.of(floor), cells.blocked);
    }

    @Test void missingClearanceAndTheActualRemainingDigBudgetHaveDifferentEvidence() {
        var cells = new Cells();
        long top = DigAStar.pack(1, 65, 0);
        cells.blocked.add(top);
        var start = new DigAStar.Step(0, 64, 0, List.of(), List.of(), DigAStar.ACT_START);
        var incomplete = new DigAStar.Step(1, 64, 0, List.of(), List.of(), DigAStar.ACT_WALK);
        assertEquals("EDIT_LIST_CHANGED", PathTask.planFailure(List.of(start, incomplete), cells, 128).reason());
        var complete = new DigAStar.Step(1, 64, 0, List.of(top), List.of(), DigAStar.ACT_DIG);
        assertNull(PathTask.planFailure(List.of(start, complete), cells, 1));
        var over = PathTask.planFailure(List.of(start, complete), cells, 0);
        assertEquals("DIG_LIMIT", over.reason());
        assertEquals(top, over.cell());
    }

    @Test void interruptedReceiptsKeepActualEffectsAndStructuredDiagnosticsWithoutNewSearches() throws Exception {
        var cells = new Cells();
        var route = PathTask.searchFor(cells, net.minecraft.core.BlockPos.ZERO,
                new net.minecraft.core.BlockPos(40, 0, 0), true, true);
        route.totalBudget(0);
        route.advance(300, 0);
        var task = new PathTask(null, net.minecraft.core.BlockPos.ZERO, null, null, null, null);
        for (var entry : java.util.Map.of("search", route, "actualDigs", 2,
                "planFailure", new PathFailure(1, "NO_SUPPORT", DigAStar.pack(0, 0, 0),
                        DigAStar.pack(1, 0, 0), DigAStar.pack(1, -1, 0))).entrySet()) {
            var field = PathTask.class.getDeclaredField(entry.getKey());
            field.setAccessible(true);
            field.set(task, entry.getValue());
        }
        int expanded = route.expanded();
        var result = task.interruptedResult(new com.neko.mcbot.server.ServerTool.Result(false, "CANCELLED:test", null));
        assertEquals(2, result.data().get("removed_blocks").getAsInt());
        assertEquals("NO_SUPPORT", result.data().getAsJsonObject("path_failure").get("reason").getAsString());
        var diagnostics = result.data().getAsJsonObject("search_diagnostics");
        assertEquals("TIME", diagnostics.get("budget_kind").getAsString());
        assertTrue(diagnostics.getAsJsonArray("samples").size() <= 8);
        assertEquals(expanded, route.expanded());
        assertTrue(result.feedback().startsWith("CANCELLED:"));
        var timing = result.data().getAsJsonObject("path_timing");
        assertTrue(timing.get("closed").getAsBoolean());
        assertEquals(0, timing.get("ticks").getAsInt(), "interrupting an unstarted route invents no motion");
        var again = task.interruptedResult(new com.neko.mcbot.server.ServerTool.Result(false, "CANCELLED:test", null));
        assertEquals(timing, again.data().get("path_timing"));
    }
}
