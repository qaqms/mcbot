package com.neko.mcbot.server;

import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.UUID;

/** Real inventory/components, controlled entity observations and native strike callback. */
public final class AttackFixtures {
    private AttackFixtures() {}

    public static final class Access implements EntityAttack.Access {
        public final Inventory inventory = BlockActionFixtures.inventory();
        public UUID uuid = UUID.randomUUID();
        public int id = 47;
        public String type = "minecraft:zombie";
        public boolean hostile = true, named, protectedTarget, alive = true, ready = true, reserved = true;
        public float health = 20, absorption;
        public int strikes, reservations;
        public ServerTool.Result guard, permission;
        public Runnable effect = () -> health -= 3;

        public Access() { inventory.setItem(0, new ItemStack(Items.IRON_SWORD)); }
        @Override public EntityAttack.Target target() {
            return new EntityAttack.Target(id, uuid, type, hostile, named, protectedTarget, alive, health, absorption);
        }
        @Override public ServerTool.Result guard() { return guard; }
        @Override public ServerTool.Result permission(EntityAttack.Target target, int maxHits) { return permission; }
        @Override public int selectedSlot() { return inventory.getSelectedSlot(); }
        @Override public ItemStack held() { return inventory.getItem(selectedSlot()); }
        @Override public boolean ready() { return ready; }
        @Override public boolean reserve() { reservations++; return reserved; }
        @Override public void strike() { strikes++; effect.run(); }
    }
}
