package com.neko.mcbot.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.neko.mcbot.server.DropTracker;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(Block.class)
public abstract class DropSourceMixin {
    @WrapMethod(method = "popResource(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/item/ItemStack;)V")
    private static void mcbot$source(Level level, BlockPos pos, ItemStack stack, Operation<Void> nativeCall) {
        DropTracker.blockSource(level, pos, () -> nativeCall.call(level, pos, stack));
    }

    @WrapMethod(method = "popResourceFromFace")
    private static void mcbot$faceSource(Level level, BlockPos pos, Direction face, ItemStack stack,
                                       Operation<Void> nativeCall) {
        DropTracker.blockSource(level, pos, () -> nativeCall.call(level, pos, face, stack));
    }
}
