package com.neko.mcbot.mixin;

import net.minecraft.world.entity.monster.Creeper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(Creeper.class)
public interface CreeperThreatAccess {
    @Accessor("explosionRadius")
    int mcbot$explosionRadius();
}
