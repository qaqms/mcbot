package com.neko.mcbot.path;

import java.util.List;

/** Collision facts for a standing body. Coordinates identify the cell containing its actual feet. */
public interface NavigationGeometry {
    record Foot(double height, long support) { }
    record Clearance(List<Long> blockers, String failure, Long cell) {
        public boolean known() { return failure == null; }
    }
    enum Motion { WALK, JUMP, BLOCKED }

    Foot foot(int x, int y, int z, TerrainEdits edits);
    Clearance clearance(int fx, double fromHeight, int fz, int x, double toHeight, int z, TerrainEdits edits);
    Motion motion(int fx, double fromHeight, int fz, int x, double toHeight, int z, TerrainEdits edits);

    static int cell(double height) { return (int) Math.floor(height + 1e-5); }
}
