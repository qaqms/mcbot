package com.neko.mcbot.server.tools;

import com.google.gson.JsonObject;
import com.neko.mcbot.body.CompanionPlayer;
import com.neko.mcbot.common.ResourceLocationHelper;
import com.neko.mcbot.server.ServerTool;
import net.minecraft.core.BlockPos;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;

/**
 * transfer：背包 <-> 容器（原版 Container 接口，兼容绝大多数 mod 箱子/机器）。
 * dir=out 从容器取出到背包；dir=in 存入；item 过滤（注册表路径）；缺省搬运全部。
 */
public final class TransferTool implements ServerTool {

    @Override
    public String name() {
        return "transfer";
    }

    @Override
    public Result run(CompanionPlayer c, JsonObject args) {
        BlockPos pos = BreakBlockTool.readPos(args);
        boolean out = "out".equals(args.has("dir") ? args.get("dir").getAsString() : "out");
        if (pos == null) {
            return new Result(false, "DENIED:需要容器坐标 x/y/z。", null);
        }
        if (c.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) > 6.5 * 6.5) {
            return new Result(false, "OUT_OF_REACH:离容器太远，先 move_to 过去。", null);
        }
        if (!c.level().isLoaded(pos)) {
            return new Result(false, "TARGET_LOST:容器所在区块未加载，先靠近后重试。", null);
        }
        var entity = c.level().getBlockEntity(pos);
        if (entity instanceof net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity) {
            return new Result(false, "DENIED:熔炉类机器须用 smelt query/load/take 按原料/燃料/产物槽操作。", null);
        }
        if (!(entity instanceof Container container)) {
            return new Result(false, "TARGET_LOST:(" + pos.toShortString() + ") 不是可存取容器。", null);
        }
        String filter = args.has("item") ? args.get("item").getAsString() : null;
        net.minecraft.world.item.Item filterItem = null;
        if (filter != null) {
            var h = net.minecraft.core.registries.BuiltInRegistries.ITEM.<net.minecraft.world.item.Item>get(
                    ResourceLocationHelper.of(filter));
            if (h.isEmpty()) {
                return new Result(false, "DENIED:\"item\" 不是有效物品名（如 cobblestone）。", null);
            }
            filterItem = h.get().value();
        }

        var inv = c.getInventory();
        int movedStacks = 0;
        int movedItems = 0;
        if (out) {
            for (int i = 0; i < container.getContainerSize(); i++) {
                ItemStack s = container.getItem(i);
                if (s.isEmpty() || !matches(s, filterItem)) {
                    continue;
                }
                ItemStack copy = s.copy();
                if (inv.add(copy)) {
                    ItemStack rest = copy.isEmpty() ? ItemStack.EMPTY : copy;
                    container.setItem(i, rest);
                    movedStacks++;
                    movedItems += s.getCount() - rest.getCount();
                } else {
                    movedItems += s.getCount();
                    break;
                }
            }
        } else {
            for (int i = 0; i < 36; i++) {
                ItemStack s = inv.getItem(i);
                if (s.isEmpty() || !matches(s, filterItem)) {
                    continue;
                }
                if (insert(container, s)) {
                    movedStacks++;
                    movedItems += s.getCount();
                    inv.setItem(i, ItemStack.EMPTY);
                } else {
                    break; // 容器满了
                }
            }
        }
        container.setChanged();
        String what = filter == null ? "物品" : filter;
        return new Result(true,
                movedStacks == 0
                        ? (out ? "容器里没有可取的" + what + "（或背包已装满）。" : "背包里没有 " + what + "，或容器满了。")
                        : (out ? "从容器取出 " : "存入容器 ") + movedItems + " 个" + what
                                + "（" + movedStacks + " 组）。",
                null);
    }

    private static boolean matches(ItemStack s, net.minecraft.world.item.Item filterItem) {
        if (filterItem == null) {
            return true;
        }
        return s.getItem() == filterItem;
    }

    /** 优先堆叠合并，再找空格；返回是否全部放入。 */
    private static boolean insert(Container container, ItemStack stack) {
        ItemStack need = stack.copy();
        for (int i = 0; i < container.getContainerSize() && !need.isEmpty(); i++) {
            ItemStack at = container.getItem(i);
            if (at.isEmpty()) {
                container.setItem(i, need.split(need.getMaxStackSize()));
                continue;
            }
            if (ItemStack.isSameItemSameComponents(at, need)) {
                int room = at.getMaxStackSize() - at.getCount();
                int give = Math.min(room, need.getCount());
                at.grow(give);
                need.shrink(give);
                container.setItem(i, at);
            }
        }
        return need.isEmpty();
    }
}
