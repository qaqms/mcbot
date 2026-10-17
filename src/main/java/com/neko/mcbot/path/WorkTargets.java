package com.neko.mcbot.path;

import net.minecraft.core.BlockPos;
import java.util.HashSet;
import java.util.Set;

/** Selected work cells belong to one task; expiry never silently grants edits to old work. */
public final class WorkTargets {
    public static final int LIMIT = 64;
    private Object task;
    private final Set<BlockPos> cells = new HashSet<>();

    public void bind(Object next) {
        if (!java.util.Objects.equals(task, next)) { cells.clear(); task = next; }
    }

    public boolean add(BlockPos target) {
        if (task == null) return true;
        if (cells.size() >= LIMIT && !cells.contains(target)) return false;
        cells.add(target.immutable());
        return true;
    }

    public boolean protects(BlockPos cell) {
        return cells.contains(cell) || cells.contains(cell.above());
    }
}
