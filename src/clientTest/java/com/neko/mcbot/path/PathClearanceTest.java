package com.neko.mcbot.path;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class PathClearanceTest {
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
}
