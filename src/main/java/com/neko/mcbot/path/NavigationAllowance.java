package com.neko.mcbot.path;

/** A trip pays for every search; successful segments do not create a fresh total account. */
final class NavigationAllowance {
    private final long limit;
    private long used;
    private DigAStar charged;
    private int observed;

    NavigationAllowance(double distance) {
        limit = (long) Math.min(Integer.MAX_VALUE, Math.max(10d,
                3 * Math.ceil(distance / DigAStar.SEGMENT_DISTANCE)) * NavigationBudget.NODES);
    }

    void observe(DigAStar search) {
        if (search == null) return;
        if (charged != search) {
            charged = search;
            observed = 0;
        }
        int count = Math.max(1, search.expanded() + search.estimateExpanded());
        used += Math.max(0, count - observed);
        observed = count;
    }

    int next() { return (int) Math.max(0, Math.min(NavigationBudget.NODES, limit - used)); }
    long used() { return used; }
    long limit() { return limit; }
}
