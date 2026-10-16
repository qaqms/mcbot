package com.neko.mcbot.path;

/** Arrival and remaining distance use the same goal, including partial-route selection. */
public interface NavigationGoal {
    boolean reached(int x, int y, int z);
    double remaining(int x, int y, int z);

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

    static NavigationGoal position(int tx, int ty, int tz, int radius) {
        return new NavigationGoal() {
            @Override public Bounds estimateBounds() {
                return new Bounds(tx - radius, ty - radius, tz - radius,
                        tx + radius, ty + radius, tz + radius);
            }
            @Override public boolean reached(int x, int y, int z) { return remaining(x, y, z) == 0; }
            @Override public double remaining(int x, int y, int z) {
                return Math.max(0, Math.abs(x - tx) - radius)
                        + Math.max(0, Math.abs(y - ty) - radius) + Math.max(0, Math.abs(z - tz) - radius);
            }
        };
    }
}
