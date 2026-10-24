package com.neko.mcbot.mixin;

import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Reuse native modifier removal/application instead of copying equipment attribute rules. */
@Mixin(LivingEntity.class)
public interface LivingEquipmentAccess {
    @Accessor("lastEquipmentItems")
    java.util.Map<net.minecraft.world.entity.EquipmentSlot, net.minecraft.world.item.ItemStack> mcbot$lastEquipmentItems();

    @Invoker("detectEquipmentUpdates")
    void mcbot$detectEquipmentUpdates();

    @Invoker("updatingUsingItem")
    void mcbot$updatingUsingItem();
}
