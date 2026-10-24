package com.neko.mcbot.path;

/** Trip diagnostics accumulate across segments without imposing an implicit completion quota. */
final class NavigationAllowance {
    private long used;
    private final java.util.Map<DigAStar, Integer> observed = new java.util.WeakHashMap<>();

    NavigationAllowance(double distance) {
        if (!Double.isFinite(distance) || distance < 0) throw new IllegalArgumentException("distance");
    }

    void observe(DigAStar search) {
        if (search == null) return;
        int count = Math.max(1, search.chargedNodes());
        long delta = Math.max(0, count - observed.getOrDefault(search, 0));
        used = Math.min(Long.MAX_VALUE - delta, used) + delta;
        observed.put(search, count);
    }

    int next() { return NavigationBudget.NODES; }
    long used() { return used; }
    long limit() { return Long.MAX_VALUE; }
}
