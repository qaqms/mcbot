package com.neko.mcbot.server.tools;

import com.google.gson.JsonObject;
import com.neko.mcbot.body.CompanionPlayer;
import com.neko.mcbot.server.ServerTool;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/** place_block：把背包里的方块放到目标格（只允许替换空气/可.replaceable 面）。 */
public final class PlaceBlockTool implements ServerTool {

    @Override
    public String name() {
        return "place_block";
    }

    @Override
    public Result run(CompanionPlayer c, JsonObject args) {
        BlockPos pos = BreakBlockTool.readPos(args);
        if (pos == null || !args.has("item")) {
            return new Result(false, "DENIED:需要整数坐标 x/y/z 和 item（如 \"cobblestone\"）。", null);
        }
        var holder = BuiltInRegistries.BLOCK.<Block>get(
                com.neko.mcbot.common.ResourceLocationHelper.of(args.get("item").getAsString()));
        Block block = holder.isEmpty() ? Blocks.AIR : holder.get().value();
        if (block == Blocks.AIR) {
            return new Result(false, "DENIED:\"item\" 不是有效方块名（用注册表路径，如 cobblestone）。", null);
        }
        if (!(block.asItem() instanceof BlockItem bi)) {
            return new Result(false, "DENIED:" + args.get("item").getAsString() + " 不是可放置物。", null);
        }
        ItemStack stack = find(c, bi);
        if (stack == null) {
            return new Result(false, "NO_ITEM:背包里没有 " + block.getName().getString()
                    + "。先挖一点或去箱子里拿。", null);
        }
        var level = c.level();
        if (!level.isLoaded(pos)) {
            return new Result(false, "TARGET_LOST:目标不在已加载区域。", null);
        }
        BlockState target = level.getBlockState(pos);
        if (!target.isAir() && !target.getBlock().getClass().getSimpleName().contains("Liquid")) {
            return new Result(false, "TARGET_LOST:(" + pos.toShortString() + ") 已被占据，换一格。", null);
        }
        if (c.blockPosition().equals(pos) || c.blockPosition().above().equals(pos)) {
            return new Result(false, "DENIED:那会把我自己封进方块里，换个位置。", null);
        }
        if (c.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) > 6.5 * 6.5) {
            return new Result(false, "OUT_OF_REACH:太远了，先 move_to 过去再放。", null);
        }
        BlockState placed = block.defaultBlockState();
        if (!placed.canSurvive(level, pos)) {
            return new Result(false, "DENIED:那个位置不能安放 " + block.getName().getString()
                    + "（悬空或贴不牢）。", null);
        }
        level.setBlockAndUpdate(pos, placed);
        stack.shrink(1);
        JsonObject data = new JsonObject();
        data.addProperty("placed", args.get("item").getAsString());
        return new Result(true, "在 (" + pos.toShortString() + ") 放下了 1 个 "
                + block.getName().getString() + "。", data);
    }

    private static ItemStack find(CompanionPlayer c, BlockItem bi) {
        var inv = c.getInventory();
        for (int i = 0; i < 36; i++) {
            ItemStack s = inv.getItem(i);
            if (!s.isEmpty() && s.is(bi)) {
                return s;
            }
        }
        return null;
    }
}
