package com.neko.mcbot.path;

import com.google.gson.JsonObject;

import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/** Per-route diagnostic time; never a search allowance or a motion decision. Server thread only. */
final class PathTiming {
    enum Phase { GUARD, GROUND_WAIT, WARMUP, SEARCH, VALIDATE, DIG, PLACE, STEER, HANDOFF, FINALIZE, PLAN_AHEAD }
    private static final AtomicLong IDS = new AtomicLong();
    private final long id = IDS.incrementAndGet();
    private final LongSupplier clock;
    private final long[] nanos = new long[Phase.values().length];
    private final int[] visits = new int[nanos.length];
    private final boolean[] visited = new boolean[nanos.length];
    private long started, phaseStart, finished;
    private boolean begun, open, closed, input;
    private Phase phase;
    private int ticks, inputTicks, noInputTicks, settlingTicks, throughNodes, stoppedNodes, suspensions;
    private int sprintInputTicks;
    private boolean sprintInput;

    PathTiming() { this(System::nanoTime); }
    PathTiming(LongSupplier clock) { this.clock = clock; }

    long id() { return id; }
    Phase phase() { return phase; }

    void beginTick() {
        if (closed) return;
        long now = clock.getAsLong();
        if (!begun) { begun = true; started = now; }
        java.util.Arrays.fill(visited, false);
        input = false;
        sprintInput = false;
        open = true;
        phase = Phase.GUARD;
        phaseStart = now;
        ticks++;
        visit(phase);
    }

    void phase(Phase next) {
        if (!open || closed || phase == next) return;
        long now = clock.getAsLong();
        nanos[phase.ordinal()] += Math.max(0, now - phaseStart);
        phaseStart = now;
        phase = next;
        visit(next);
    }

    private void visit(Phase next) {
        if (!visited[next.ordinal()]) {
            visits[next.ordinal()]++;
            visited[next.ordinal()] = true;
        }
    }

    void input(double x, double z, boolean jump) {
        input(x, z, jump, false);
    }
    void input(double x, double z, boolean jump, boolean sprint) {
        if (open && !closed) input |= x != 0 || z != 0 || jump;
        if (open && !closed) sprintInput |= sprint && (x != 0 || z != 0);
    }
    void settling() { if (open && !closed) settlingTicks++; }
    void node(boolean through) {
        if (!open || closed) return;
        if (through) throughNodes++; else stoppedNodes++;
    }
    void suspend() { if (!closed) suspensions++; }

    void endTick() {
        if (!open || closed) return;
        nanos[phase.ordinal()] += Math.max(0, clock.getAsLong() - phaseStart);
        if (input) inputTicks++; else noInputTicks++;
        if (sprintInput) sprintInputTicks++;
        open = false;
    }

    void finish() {
        if (closed) return;
        endTick();
        finished = clock.getAsLong();
        closed = true;
    }

    JsonObject snapshot() {
        long now = closed ? finished : clock.getAsLong();
        var data = new JsonObject();
        data.addProperty("route", id);
        data.addProperty("closed", closed);
        data.addProperty("started_nanos", begun ? started : 0);
        data.addProperty("finished_nanos", begun && closed ? finished : 0);
        data.addProperty("wall_nanos", begun ? Math.max(0, now - started) : 0);
        data.addProperty("ticks", ticks);
        data.addProperty("input_ticks", inputTicks);
        data.addProperty("sprint_input_ticks", sprintInputTicks);
        data.addProperty("no_input_ticks", noInputTicks);
        data.addProperty("settling_ticks", settlingTicks);
        data.addProperty("through_nodes", throughNodes);
        data.addProperty("stopped_nodes", stoppedNodes);
        data.addProperty("suspensions", suspensions);
        long active = 0;
        var phases = new JsonObject();
        for (Phase value : Phase.values()) {
            int index = value.ordinal();
            long elapsed = nanos[index] + (open && phase == value ? Math.max(0, now - phaseStart) : 0);
            active += elapsed;
            var entry = new JsonObject();
            entry.addProperty("nanos", elapsed);
            entry.addProperty("ticks", visits[index]);
            phases.add(value.name(), entry);
        }
        data.addProperty("active_nanos", active);
        data.add("phases", phases);
        return data;
    }
}
