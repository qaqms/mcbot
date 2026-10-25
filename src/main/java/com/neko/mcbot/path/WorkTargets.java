package com.neko.mcbot.path;

import net.minecraft.core.BlockPos;
import java.util.HashSet;
import java.util.Set;

/** Selected work cells belong to one task; expiry never silently grants edits to old work. */
public final class WorkTargets {
    public static final int LIMIT = 64;
    private Object task, world;
    private final Set<BlockPos> cells = new HashSet<>();

    public boolean boundTo(Object expected, Object expectedWorld) { return task == expected && world == expectedWorld; }
    public void continueTask(Object expected, Object next, Object nextWorld) {
        if (!boundTo(expected, nextWorld)) throw new IllegalArgumentException("work_session_changed");
        task = next;
    }

    public void bind(Object next) {
        bind(next, null);
    }

    public void bind(Object next, Object nextWorld) {
        if (task != next || world != nextWorld) {
            cells.clear(); task = next; world = nextWorld;
        }
    }

    public boolean add(BlockPos target) {
        if (task == null) return true;
        if (!canAdd(target)) return false;
        cells.add(target.immutable());
        return true;
    }

    public boolean canAdd(BlockPos target) {
        return task == null || cells.size() < LIMIT || cells.contains(target);
    }
    public boolean canAdd(java.util.Collection<BlockPos> targets) {
        if (task == null) return true;
        var combined = new HashSet<>(cells);
        combined.addAll(targets);
        return combined.size() <= LIMIT;
    }

    public boolean protects(BlockPos cell) {
        return cells.contains(cell) || cells.contains(cell.above());
    }

    public com.google.gson.JsonArray snapshot() {
        var data = new com.google.gson.JsonArray();
        cells.stream().sorted(java.util.Comparator.comparingLong(BlockPos::asLong)).forEach(p ->
                data.add(com.neko.mcbot.building.BuildTarget.coordinates(p)));
        return data;
    }

    public void restore(com.google.gson.JsonArray data) {
        if (task == null || !cells.isEmpty() || data == null || data.size() > LIMIT) throw new IllegalArgumentException();
        for (var value : data) {
            var p = value.getAsJsonObject();
            if (!cells.add(new BlockPos(p.get("x").getAsBigDecimal().intValueExact(),
                    p.get("y").getAsBigDecimal().intValueExact(), p.get("z").getAsBigDecimal().intValueExact())))
                throw new IllegalArgumentException();
        }
    }
}
