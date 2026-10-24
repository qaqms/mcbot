package com.neko.mcbot.path;

/** A trip pays for every search; successful segments do not create a fresh total account. */
final class NavigationAllowance {
    private final long limit;
    private long used;
    private final java.util.Map<DigAStar, Integer> observed = new java.util.WeakHashMap<>();

    NavigationAllowance(double distance) {
        limit = (long) Math.min(Integer.MAX_VALUE, Math.max(10d,
                3 * Math.ceil(distance / DigAStar.SEGMENT_DISTANCE)) * NavigationBudget.NODES);
    }

    void observe(DigAStar search) {
        if (search == null) return;
        int count = Math.max(1, search.chargedNodes());
        used += Math.max(0, count - observed.getOrDefault(search, 0));
        observed.put(search, count);
    }

    int next() { return (int) Math.max(0, Math.min(NavigationBudget.NODES, limit - used)); }
    long used() { return used; }
    long limit() { return limit; }
}
