package com.neko.mcbot.body;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Native registry values with controlled world/body state, shared by wire-boundary tests. */
public final class BodyChangeFixtures {
    public float hp = 20;
    public ItemStack held = ItemStack.EMPTY;
    public boolean unavailable;
    public final BodyChangeLog log = new BodyChangeLog(UUID.randomUUID(), () -> {
        if (unavailable) throw new IllegalStateException("offline observation unavailable");
        return new BodyChangeLog.Snapshot(this, "minecraft:overworld", 1, Vec3.ZERO, hp, 0,
                20, 300, 0, 0, null, 0, List.of(held), Map.of());
    });
}
