package com.neko.mcbot.path;

/** Arrival and remaining distance use the same goal, including partial-route selection. */
public interface NavigationGoal {
    boolean reached(int x, int y, int z);
    double remaining(int x, int y, int z);

    default boolean reached(int x, int y, int z, TerrainEdits edits) {
        return reached(x, y, z);
    }

    static NavigationGoal position(int tx, int ty, int tz, int radius) {
        return new NavigationGoal() {
            @Override public boolean reached(int x, int y, int z) { return remaining(x, y, z) == 0; }
            @Override public double remaining(int x, int y, int z) {
                return Math.max(0, Math.abs(x - tx) - radius)
                        + Math.max(0, Math.abs(y - ty) - radius) + Math.max(0, Math.abs(z - tz) - radius);
            }
        };
    }
}
