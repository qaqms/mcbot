package com.neko.mcbot.agentcore.llm;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class TurnTimingsTest {
    @Test
    void snapshotFreezesTransportEventsWithoutClaimingAnExecution() {
        var clock = new AtomicLong(1_000_000_000L);
        var timings = new TurnTimings(clock::get);
        clock.addAndGet(10_000_000);
        timings.markResponse();
        clock.addAndGet(20_000_000);
        timings.markChunk();
        clock.addAndGet(15_000_000);
        timings.onToolCallReady(0, new ToolCall("c", "status", "{}"));
        clock.addAndGet(25_000_000);
        timings.markChunk();
        timings.onTextDelta("a");
        var snapshot = timings.snapshot();
        assertEquals(10, snapshot.ttfb());
        assertEquals(70, snapshot.ttft());
        assertEquals(45, snapshot.firstTool());
        assertEquals(40, snapshot.afterChunk());
        assertEquals(15, snapshot.afterTool());
        assertEquals(2, snapshot.chunks());
        assertEquals(1, snapshot.deltas());
        assertEquals(1, snapshot.toolsReady());
        assertEquals(70, snapshot.elapsedMs());
        clock.addAndGet(50_000_000);
        timings.onTextDelta("b");
        timings.onToolCallReady(1, new ToolCall("d", "status", "{}"));
        assertEquals(1, snapshot.deltas());
        assertEquals(1, snapshot.toolsReady());
        assertEquals(120, snapshot.sinceRequestMs(clock.get()));
    }

    @Test
    void missingTransportEventsRemainUnknownIncludingNegativeNanoTimeOrigin() {
        var clock = new AtomicLong(-1_000_000_000L);
        var timings = new TurnTimings(clock::get);
        clock.addAndGet(5_000_000);
        var snapshot = timings.snapshot();
        assertEquals(-1, snapshot.ttfb());
        assertEquals(-1, snapshot.ttft());
        assertEquals(-1, snapshot.firstTool());
        assertEquals(-1, snapshot.afterChunk());
        assertEquals(-1, snapshot.afterTool());
        assertEquals(0, snapshot.chunks());
        assertEquals(0, snapshot.deltas());
        assertEquals(0, snapshot.toolsReady());
        assertEquals(5, snapshot.sinceRequestMs(clock.get()));
    }
}
