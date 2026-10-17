package com.neko.mcbot.mixin;

import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Read the current mapped native attack geometry, including subclass overrides. */
@Mixin(Mob.class)
public interface MobThreatAccess {
    @Accessor("DEFAULT_ATTACK_REACH")
    static double mcbot$defaultReach() { throw new AssertionError(); }

    @Invoker("getAttackBoundingBox")
    AABB mcbot$attackBox(double reach);
}
