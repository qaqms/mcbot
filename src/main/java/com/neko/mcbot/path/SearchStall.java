package com.neko.mcbot.path;

import java.util.function.LongSupplier;

/** Observes idle search time independently of planner batches, tick rate and goal changes. */
final class SearchStall {
    static final long LIMIT_MILLIS = 30_000;
    private static final long LIMIT_NANOS = LIMIT_MILLIS * 1_000_000;
    private static final long MAX_SAMPLE_NANOS = 1_000_000_000L;
    private static final double MOVE_SQUARED = .25 * .25;
    private final LongSupplier clock;
    private boolean observed, sampling, wasSearching;
    private double anchorX, anchorY, anchorZ;
    private int digs, places;
    private long lastSample, idleNanos;

    SearchStall() { this(System::nanoTime); }
    SearchStall(LongSupplier clock) { this.clock=clock; }

    boolean observe(boolean searching, double x, double y, double z, int removed, int placed) {
        long now=clock.getAsLong();
        // A long unscheduled gap (for example pausing the game) is not continuous planner work.
        if (sampling && wasSearching && searching)
            idleNanos=Math.min(LIMIT_NANOS,idleNanos+Math.min(MAX_SAMPLE_NANOS,Math.max(0,now-lastSample)));
        lastSample=now;
        sampling=true;
        wasSearching=searching;
        double dx=x-anchorX, dy=y-anchorY, dz=z-anchorZ;
        if (!observed || dx*dx+dy*dy+dz*dz >= MOVE_SQUARED || removed>digs || placed>places) {
            observed=true;
            anchorX=x; anchorY=y; anchorZ=z;
            idleNanos=0;
        }
        digs=removed; places=placed;
        return searching && idleNanos>=LIMIT_NANOS;
    }

    void suspend() { sampling=false; }
    long idleMillis() { return idleNanos/1_000_000; }
}
