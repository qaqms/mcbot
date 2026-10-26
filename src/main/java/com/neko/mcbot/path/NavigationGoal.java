package com.neko.mcbot.path;

/** Arrival and remaining distance use the same goal, including partial-route selection. */
public interface NavigationGoal {
    boolean reached(int x, int y, int z);
    double remaining(int x, int y, int z);

    /** Direction within a geometric reach plateau, used only to order equal search priorities. */
    default double approach(int x, int y, int z) { return 0; }

    /** Optional guidance starts at verified work stances, never a proof that other edited stances are impossible. */
    default boolean estimateWorkStances() { return false; }

    /** Optional, strictly rechecked guidance for a local work ray; never an executable edit plan. */
    default java.util.List<Long> workOpening(DigSampler terrain, int x, int y, int z, TerrainEdits edits) {
        return java.util.List.of();
    }

    /** Null keeps goals with live premises on the world thread. */
    default NavigationGoal snapshot(DigSampler terrain) { return null; }
    default boolean reached(int x, int y, int z, TerrainEdits edits) {
        return reached(x, y, z);
    }

    /** A geometric superset of possible feet cells; null disables the optional terrain estimate. */
    default Bounds estimateBounds() { return null; }

    /** Local work still needed at this stance, in the same units as route edits. */
    default double arrivalCost(DigSampler terrain, int x, int y, int z, TerrainEdits edits) {
        return reached(x, y, z, edits) ? 0 : Double.POSITIVE_INFINITY;
    }

    record Bounds(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) { }

    static NavigationGoal anyOf(java.util.List<NavigationGoal> goals) {
        var members = java.util.List.copyOf(goals);
        if (members.isEmpty()) throw new IllegalArgumentException("empty goals");
        return new NavigationGoal() {
            @Override public boolean reached(int x, int y, int z) {
                return reached(x, y, z, TerrainEdits.EMPTY);
            }
            @Override public boolean reached(int x, int y, int z, TerrainEdits edits) {
                return members.stream().anyMatch(g -> g.reached(x, y, z, edits));
            }
            @Override public double remaining(int x, int y, int z) {
                double distance = Double.POSITIVE_INFINITY;
                for (var member : members) distance = Math.min(distance, member.remaining(x, y, z));
                return distance;
            }
            @Override public double approach(int x, int y, int z) {
                double distance = Double.POSITIVE_INFINITY;
                for (var member : members) distance = Math.min(distance, member.approach(x, y, z));
                return distance;
            }
            @Override public boolean estimateWorkStances() {
                return members.stream().allMatch(NavigationGoal::estimateWorkStances);
            }
            @Override public java.util.List<Long> workOpening(DigSampler terrain, int x, int y, int z, TerrainEdits edits) {
                for (var member : members) {
                    var opening=member.workOpening(terrain,x,y,z,edits);
                    if (!opening.isEmpty()) return opening;
                }
                return java.util.List.of();
            }
            @Override public double arrivalCost(DigSampler terrain, int x, int y, int z, TerrainEdits edits) {
                double cost = Double.POSITIVE_INFINITY;
                for (var member : members) {
                    if (member.reached(x, y, z, edits))
                        cost = Math.min(cost, member.arrivalCost(terrain, x, y, z, edits));
                }
                return cost;
            }
            @Override public Bounds estimateBounds() {
                int lx = Integer.MAX_VALUE, ly = lx, lz = lx;
                int hx = Integer.MIN_VALUE, hy = hx, hz = hx;
                for (var member : members) {
                    var bounds = member.estimateBounds();
                    if (bounds == null) return null;
                    lx = Math.min(lx, bounds.minX()); ly = Math.min(ly, bounds.minY()); lz = Math.min(lz, bounds.minZ());
                    hx = Math.max(hx, bounds.maxX()); hy = Math.max(hy, bounds.maxY()); hz = Math.max(hz, bounds.maxZ());
                }
                return new Bounds(lx, ly, lz, hx, hy, hz);
            }
            @Override public NavigationGoal snapshot(DigSampler terrain) {
                var frozen = new java.util.ArrayList<NavigationGoal>();
                for (var member : members) {
                    var goal = member.snapshot(terrain);
                    if (goal == null) return null;
                    frozen.add(goal);
                }
                return anyOf(frozen);
            }
        };
    }

    static NavigationGoal position(int tx, int ty, int tz, int radius) {
        return new NavigationGoal() {
            @Override public NavigationGoal snapshot(DigSampler terrain) { return this; }
            @Override public Bounds estimateBounds() {
                return new Bounds(tx - radius, ty - radius, tz - radius,
                        tx + radius, ty + radius, tz + radius);
            }
            @Override public boolean reached(int x, int y, int z) { return remaining(x, y, z) == 0; }
            @Override public double remaining(int x, int y, int z) {
                return Math.max(0, Math.abs((double) x - tx) - radius)
                        + Math.max(0, Math.abs((double) y - ty) - radius) + Math.max(0, Math.abs((double) z - tz) - radius);
            }
        };
    }
}
