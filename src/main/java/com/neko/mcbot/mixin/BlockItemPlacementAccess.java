package com.neko.mcbot.mixin;

import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Wall/floor variants must use the item's native selector rather than its default block. */
@Mixin(BlockItem.class)
public interface BlockItemPlacementAccess {
    @Invoker("getPlacementState")
    BlockState mcbot$placementState(BlockPlaceContext context);
}
