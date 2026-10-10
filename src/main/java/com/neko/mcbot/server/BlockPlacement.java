package com.neko.mcbot.server;

import com.google.gson.JsonObject;
import com.neko.mcbot.body.CompanionPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.BedItem;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.DoubleHighBlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.StandingAndWallBlockItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/** Exact target placement; vanilla owns state selection, multi-block hooks and consumption. */
public final class BlockPlacement {
    public interface Access {
        ServerTool.Result guard(BlockPos pos);
        Container inventory();
        BlockState state(BlockPos pos);
        InteractionResult place(BlockPos pos, ItemStack stack, Direction face);
    }

    private BlockPlacement() {
    }

    public static Access forPlayer(CompanionPlayer player) {
        return new PlayerAccess(player);
    }

    public static boolean supported(BlockItem item) {
        Class<?> type = item.getClass();
        return type == BlockItem.class || type == BedItem.class || type == DoubleHighBlockItem.class
                || type == StandingAndWallBlockItem.class;
    }

    public static ServerTool.Result place(Access access, BlockPos pos, int slot, Direction face) {
        ServerTool.Result failure = access.guard(pos);
        if (failure != null) return failure;
        Container inventory = access.inventory();
        if (slot < 0 || slot >= Math.min(Inventory.INVENTORY_SIZE, inventory.getContainerSize())) {
            return failed("NO_ITEM:背包里没有可用方块，先 inventory 核对材料。", false, 0);
        }
        ItemStack stack = inventory.getItem(slot);
        if (stack.isEmpty() || !(stack.getItem() instanceof BlockItem item)) {
            return failed("NO_ITEM:该槽不是可放置方块，先 inventory 核对材料。", false, 0);
        }
        if (!supported(item)) {
            return failed("DENIED:暂不支持该特殊方块物品的放置上下文，换普通方块。", false, 0);
        }
        BlockState before = access.state(pos);
        int count = stack.getCount();
        InteractionResult result = access.place(pos, stack, face);
        BlockState after = access.state(pos);
        int consumed = Math.max(0, count - stack.getCount());
        boolean changed = before != after;
        inventory.setChanged();
        if (!result.consumesAction() || !changed || after.isAir() || consumed != 1) {
            return failed("PLACE_FAILED:未确认目标格成功放置；已消耗 " + consumed
                    + " 个，目标状态" + (changed ? "已变化" : "未变化")
                    + "。先核对现场与背包，勿自动重试。", changed, consumed);
        }
        JsonObject data = new JsonObject();
        data.addProperty("placed", BuiltInRegistries.BLOCK.getKey(after.getBlock()).toString());
        data.addProperty("consumed_count", consumed);
        data.addProperty("changed", true);
        data.addProperty("x", pos.getX());
        data.addProperty("y", pos.getY());
        data.addProperty("z", pos.getZ());
        return new ServerTool.Result(true, "在 (" + pos.toShortString() + ") 放置 "
                + data.get("placed").getAsString() + "，消耗 1 个；朝向/组件由原版放置流程处理。", data);
    }

    private static ServerTool.Result failed(String feedback, boolean changed, int consumed) {
        JsonObject data = new JsonObject();
        data.addProperty("changed", changed);
        data.addProperty("consumed_count", consumed);
        return new ServerTool.Result(false, feedback, data);
    }

    private record PlayerAccess(CompanionPlayer player) implements Access {
        @Override public ServerTool.Result guard(BlockPos pos) {
            var level = player.level();
            if (player.distanceToSqr(pos.getX() + .5, pos.getY() + .5, pos.getZ() + .5) > 6.5 * 6.5) {
                return failed("OUT_OF_REACH:目标超过臂长，先 move_to 靠近。", false, 0);
            }
            // Vanilla doors/beds and support/collision queries also read adjacent cells.
            for (BlockPos near : BlockPos.betweenClosed(pos.offset(-1, -1, -1), pos.offset(1, 1, 1))) {
                if (!level.isInWorldBounds(near) || !level.getWorldBorder().isWithinBounds(near)) {
                    return failed("DENIED:放置及相邻格超出世界范围，换位置。", false, 0);
                }
                if (!level.isLoaded(near)) return failed("TARGET_LOST:放置邻域未加载，先靠近。", false, 0);
                if (!level.mayInteract(player, near)) return failed("DENIED:世界规则不允许在该邻域放置。", false, 0);
            }
            if (!player.gameMode.isSurvival() || !player.getAbilities().mayBuild) {
                return failed("DENIED:当前玩家模式不允许生存放置。", false, 0);
            }
            return null;
        }
        @Override public Container inventory() { return player.getInventory(); }
        @Override public BlockState state(BlockPos pos) { return player.level().getBlockState(pos); }
        @Override public InteractionResult place(BlockPos pos, ItemStack stack, Direction face) {
            Vec3 click = Vec3.atCenterOf(pos).add(face.getStepX() * .5, face.getStepY() * .5, face.getStepZ() * .5);
            BlockPlaceContext context = new BlockPlaceContext(player, InteractionHand.MAIN_HAND, stack,
                    new BlockHitResult(click, face, pos, false)) {
                @Override public BlockPos getClickedPos() { return pos; }
                @Override public boolean canPlace() { return state(pos).canBeReplaced(this); }
            };
            if (!player.mayUseItemAt(pos, face, stack)) return InteractionResult.FAIL;
            // Invoke placement directly: using a block must not open its menu or eat an item.
            return ((BlockItem) stack.getItem()).place(context);
        }
    }
}
