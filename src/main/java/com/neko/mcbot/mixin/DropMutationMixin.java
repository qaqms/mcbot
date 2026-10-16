package com.neko.mcbot.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.neko.mcbot.server.DropLedger;
import com.neko.mcbot.server.DropTracker;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(ItemEntity.class)
public abstract class DropMutationMixin {
    @WrapMethod(method = "merge(Lnet/minecraft/world/entity/item/ItemEntity;Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/entity/item/ItemEntity;Lnet/minecraft/world/item/ItemStack;)V")
    private static void mcbot$merge(ItemEntity destination, ItemStack destinationStack, ItemEntity source,
                                   ItemStack sourceStack, Operation<Void> nativeCall) {
        if (!DropTracker.tracked(source) && !DropTracker.tracked(destination)) {
            nativeCall.call(destination, destinationStack, source, sourceStack);
            return;
        }
        var beforeSource = DropTracker.before(source);
        var beforeDestination = DropTracker.before(destination);
        boolean completed = false;
        DropTracker.mutation(source, true);
        DropTracker.mutation(destination, true);
        try {
            nativeCall.call(destination, destinationStack, source, sourceStack);
            completed = true;
        } finally {
            try { DropTracker.merged(source, destination, beforeSource, beforeDestination, completed); }
            finally { DropTracker.mutation(source, false); DropTracker.mutation(destination, false); }
        }
    }

    @WrapMethod(method = "playerTouch")
    private void mcbot$pickup(Player player, Operation<Void> nativeCall) {
        ItemEntity item = (ItemEntity) (Object) this;
        if (!DropTracker.tracked(item)) { nativeCall.call(player); return; }
        var before = DropTracker.before(item);
        boolean completed = false;
        DropTracker.mutation(item, true);
        try { nativeCall.call(player); completed = true; }
        finally {
            try { DropTracker.reduced(item, before, DropLedger.Outcome.PICKUP,
                    DropTracker.picker(player), "native_player_touch", completed); }
            finally { DropTracker.mutation(item, false); }
        }
    }

    @WrapMethod(method = "hurtServer")
    private boolean mcbot$damage(ServerLevel level, DamageSource damage, float amount, Operation<Boolean> nativeCall) {
        ItemEntity item = (ItemEntity) (Object) this;
        if (!DropTracker.tracked(item)) return nativeCall.call(level, damage, amount);
        var before = DropTracker.before(item);
        boolean completed = false;
        try {
            DropTracker.mutation(item, true);
            boolean result = nativeCall.call(level, damage, amount);
            completed = true;
            return result;
        } finally {
            try { DropTracker.reduced(item, before, DropLedger.Outcome.DESTROYED, null,
                    damage.typeHolder().unwrapKey().map(key -> key.identifier().toString()).orElse("unknown_damage"),
                    completed); }
            finally { DropTracker.mutation(item, false); }
        }
    }
}
