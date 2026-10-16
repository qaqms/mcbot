package com.neko.mcbot.mixin;

import com.neko.mcbot.server.DropTracker;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ServerLevel.class)
public abstract class DropAdmissionMixin {
    @Inject(method = "addFreshEntity", at = @At("RETURN"))
    private void mcbot$admitted(Entity entity, CallbackInfoReturnable<Boolean> result) {
        if (result.getReturnValueZ()) DropTracker.admitted(entity);
    }
}
