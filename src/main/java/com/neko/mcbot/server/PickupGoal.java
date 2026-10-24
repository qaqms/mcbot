package com.neko.mcbot.server;

import com.neko.mcbot.path.DigSampler;
import com.neko.mcbot.path.NavigationGeometry;
import com.neko.mcbot.path.NavigationGoal;
import com.neko.mcbot.path.TerrainEdits;
import net.minecraft.world.phys.AABB;

/** An immutable contact target; terrain edits may create any suitable landing. */
public final class PickupGoal implements NavigationGoal {
    private final AABB item;
    private final double width, height;
    private final NavigationGeometry geometry;
    private final Bounds bounds;

    public PickupGoal(AABB item, double width, double height, NavigationGeometry geometry) {
        this.item = item;
        this.width = width;
        this.height = height;
        this.geometry = geometry;
        double reach = width / 2 + 1;
        bounds = new Bounds((int) Math.floor(item.minX - reach - .5),
                (int) Math.floor(item.minY - height - .5) - 1,
                (int) Math.floor(item.minZ - reach - .5),
                (int) Math.ceil(item.maxX + reach - .5),
                (int) Math.ceil(item.maxY + .5),
                (int) Math.ceil(item.maxZ + reach - .5));
    }

    @Override public NavigationGoal snapshot(DigSampler terrain) {
        return new PickupGoal(item, width, height, terrain.geometry());
    }
    @Override public Bounds estimateBounds() { return bounds; }
    @Override public boolean reached(int x, int y, int z) {
        return reached(x, y, z, TerrainEdits.EMPTY);
    }
    @Override public boolean reached(int x, int y, int z, TerrainEdits edits) {
        var foot = geometry == null ? null : geometry.foot(x, y, z, edits);
        return (geometry == null || foot != null) && touches(x, foot == null ? y : foot.height(), z);
    }
    @Override public double arrivalCost(DigSampler terrain, int x, int y, int z, TerrainEdits edits) {
        var shape = terrain.geometry();
        var foot = shape == null ? null : shape.foot(x, y, z, edits);
        return (shape == null || foot != null) && touches(x, foot == null ? y : foot.height(), z)
                ? 0 : Double.POSITIVE_INFINITY;
    }
    private boolean touches(int x, double feet, int z) {
        return GroundItems.touches(new AABB(x + .5 - width / 2, feet, z + .5 - width / 2,
                x + .5 + width / 2, feet + height, z + .5 + width / 2), item);
    }
    @Override public double remaining(int x, int y, int z) {
        double reach = width / 2 + 1;
        // Feet may be fractional within their cell. This envelope never excludes real contact.
        return Math.max(0, item.minX - reach - (x + .5))
                + Math.max(0, x + .5 - reach - item.maxX)
                + Math.max(0, item.minZ - reach - (z + .5))
                + Math.max(0, z + .5 - reach - item.maxZ)
                + Math.max(0, item.minY - height - .5 - (y + 1))
                + Math.max(0, y - .5 - item.maxY);
    }
}
