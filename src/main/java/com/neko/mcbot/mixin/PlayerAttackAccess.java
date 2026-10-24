package com.neko.mcbot.mixin;

import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Ask the native attack predicate before allowing possible collateral damage. */
@Mixin(Player.class)
public interface PlayerAttackAccess {
    @Invoker("isSweepAttack")
    boolean mcbot$isSweepAttack(boolean charged, boolean critical, boolean sprintAttack);
}
