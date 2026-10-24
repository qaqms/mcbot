package com.neko.mcbot.path;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class PathTimingTest {
    @Test void sprintDiagnosticsCountRenewedInputOncePerTickAndFreezeAtCompletion() {
        var timing = new PathTiming();
        timing.beginTick();
        timing.input(1, 0, false, true);
        timing.input(1, 0, false, true);
        timing.endTick();
        timing.beginTick();
        timing.input(1, 0, false);
        timing.endTick();
        timing.beginTick();
        timing.input(0, 0, false, true);
        timing.finish();
        timing.input(1, 0, false, true);
        assertEquals(1, timing.snapshot().get("sprint_input_ticks").getAsInt());
        assertEquals(2, timing.snapshot().get("input_ticks").getAsInt());
        assertEquals(1, timing.snapshot().get("no_input_ticks").getAsInt());
    }
    @Test void exclusivePhasesExcludeBetweenTickWaitAndDoNotDoubleCountReentry() {
        var clock = new AtomicLong();
        var timing = new PathTiming(clock::get);
        timing.beginTick();
        clock.addAndGet(2);
        timing.phase(PathTiming.Phase.SEARCH);
        clock.addAndGet(7);
        timing.phase(PathTiming.Phase.VALIDATE);
        clock.addAndGet(3);
        timing.phase(PathTiming.Phase.SEARCH);
        clock.addAndGet(5);
        timing.endTick();
        clock.addAndGet(1000);
        timing.beginTick();
        clock.addAndGet(4);
        timing.input(1, 0, false);
        timing.endTick();
        timing.finish();
        var data = timing.snapshot();
        assertEquals(1021, data.get("wall_nanos").getAsLong());
        assertEquals(21, data.get("active_nanos").getAsLong());
        assertEquals(2, data.get("ticks").getAsInt());
        assertEquals(1, data.get("input_ticks").getAsInt());
        assertEquals(1, data.get("no_input_ticks").getAsInt());
        var search = data.getAsJsonObject("phases").getAsJsonObject("SEARCH");
        assertEquals(12, search.get("nanos").getAsLong());
        assertEquals(1, search.get("ticks").getAsInt());
    }

    @Test void finishFreezesCancellationAndRejectsLateDiagnosticUpdates() {
        var clock = new AtomicLong(100);
        var timing = new PathTiming(clock::get);
        timing.beginTick();
        timing.input(0, 0, true);
        timing.input(0, 0, false);
        timing.settling();
        timing.node(true);
        timing.node(false);
        timing.suspend();
        clock.addAndGet(50);
        timing.finish();
        var frozen = timing.snapshot();
        clock.addAndGet(500);
        timing.beginTick();
        timing.phase(PathTiming.Phase.DIG);
        timing.input(1, 1, true);
        timing.node(true);
        timing.settling();
        timing.suspend();
        timing.endTick();
        timing.finish();
        assertEquals(frozen, timing.snapshot());
        assertEquals(1, frozen.get("input_ticks").getAsInt());
        assertEquals(0, frozen.get("no_input_ticks").getAsInt());
        assertEquals(1, frozen.get("settling_ticks").getAsInt());
        assertEquals(1, frozen.get("through_nodes").getAsInt());
        assertEquals(1, frozen.get("stopped_nodes").getAsInt());
        assertEquals(1, frozen.get("suspensions").getAsInt());
        frozen.addProperty("ticks", 1000);
        assertEquals(1, timing.snapshot().get("ticks").getAsInt());
    }

    @Test void unusedRoutesHaveNoInventedElapsedTimeAndDistinctLocalIds() {
        var clock = new AtomicLong(1000);
        var first = new PathTiming(clock::get);
        var second = new PathTiming(clock::get);
        clock.addAndGet(1000);
        first.finish();
        assertNotEquals(first.id(), second.id());
        var data = first.snapshot();
        assertEquals(0, data.get("wall_nanos").getAsLong());
        assertEquals(0, data.get("active_nanos").getAsLong());
        assertEquals(0, data.get("ticks").getAsInt());
    }
}
