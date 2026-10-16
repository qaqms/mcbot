package com.neko.mcbot.path;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class PathClearanceTest {
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
        var stair = new DigAStar.Step(1, 63, 0, List.of(DigAStar.pack(1, 63, 0)), List.of(), DigAStar.ACT_DIG);
        assertTrue(PathTask.editsFeasible(List.of(start, stair), cells), "legal adjacent stairs remain valid");
    }
}
