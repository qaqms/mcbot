package com.neko.mcbot.mixin;

import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Reuse native modifier removal/application instead of copying equipment attribute rules. */
@Mixin(LivingEntity.class)
public interface LivingEquipmentAccess {
    @Invoker("detectEquipmentUpdates")
    void mcbot$detectEquipmentUpdates();
}
