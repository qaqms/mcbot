package com.neko.mcbot.path;

import java.util.List;
import java.util.function.Supplier;

/** One speculative route, advanced on the world thread alongside the current route's real actions. */
final class NavigationContinuation {
    private DigAStar search;
    private boolean attempted;
    private int started, adopted, discarded;

    void advance(Supplier<DigAStar> create, NavigationAllowance allowance, int nodes, long nanos) {
        if (nanos <= 0) return;
        if (!attempted) {
            attempted = true;
            search = create.get();
            if (search != null) started++;
        }
        if (search == null || search.done()) return;
        search.advance(nodes, nanos);
        allowance.observe(search);
    }

    DigAStar take(int x, int y, int z) {
        if (search == null || !search.done()) return null;
        if (!search.startsAt(x, y, z)) return null;
        var value = search;
        search = null;
        attempted = false;
        adopted++;
        return value;
    }

    DigAStar clear() {
        var value = search;
        if (search != null) discarded++;
        search = null;
        attempted = false;
        return value;
    }

    static TerrainEdits remainingEdits(List<DigAStar.Step> path, int cursor) {
        var edits = TerrainEdits.EMPTY;
        for (int i = cursor; i < path.size(); i++) {
            var step = path.get(i);
            edits = edits.after(step.dig(), step.place());
        }
        return edits;
    }

    boolean attempted() { return attempted; }
    boolean pending() { return search != null && !search.done(); }
    DigAStar search() { return search; }
    void rejected() { adopted--; discarded++; }
    int started() { return started; }
    int adopted() { return adopted; }
    int discarded() { return discarded; }
}
