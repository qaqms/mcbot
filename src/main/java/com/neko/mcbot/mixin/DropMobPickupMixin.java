package com.neko.mcbot.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.neko.mcbot.server.DropLedger;
import com.neko.mcbot.server.DropTracker;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.item.ItemEntity;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(Mob.class)
public abstract class DropMobPickupMixin {
    @WrapMethod(method = "pickUpItem")
    private void mcbot$mobPickup(ServerLevel level, ItemEntity item, Operation<Void> nativeCall) {
        if (!DropTracker.tracked(item)) { nativeCall.call(level, item); return; }
        var before = DropTracker.before(item);
        boolean completed = false;
        DropTracker.mutation(item, true);
        try { nativeCall.call(level, item); completed = true; }
        finally {
            try { DropTracker.reduced(item, before, DropLedger.Outcome.PICKUP,
                    DropTracker.picker((Mob) (Object) this), "native_mob_pickup", completed); }
            finally { DropTracker.mutation(item, false); }
        }
    }
}
