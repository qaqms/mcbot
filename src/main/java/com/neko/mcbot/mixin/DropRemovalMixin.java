package com.neko.mcbot.mixin;

import com.neko.mcbot.server.DropTracker;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Entity.class)
public abstract class DropRemovalMixin {
    @Inject(method = "setRemoved", at = @At("HEAD"))
    private void mcbot$unavailable(Entity.RemovalReason reason, CallbackInfo callback) {
        DropTracker.removed((Entity) (Object) this, reason);
    }
}
