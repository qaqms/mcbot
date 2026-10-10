package com.neko.mcbot.server.tools;

import com.google.gson.JsonObject;
import com.neko.mcbot.McbotMod;
import com.neko.mcbot.body.CompanionPlayer;
import com.neko.mcbot.common.ResourceLocationHelper;
import com.neko.mcbot.server.ItemTransfers;
import com.neko.mcbot.server.ServerTool;
import com.neko.mcbot.task.CompanionScheduler;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.entity.BaseContainerBlockEntity;

import java.util.concurrent.CompletableFuture;

/** Partial moves keep the exact remainder at the source, including all components. */
public final class TransferTool implements ServerTool {
    record Arguments(BlockPos pos, boolean out, Item filter) {
    }

    record Target(Container slots, boolean locked, boolean machine, boolean valid, Direction face) {
    }

    interface Access {
        boolean busy();
        boolean inBounds(BlockPos pos);
        double distanceSquared(BlockPos pos);
        boolean loaded(BlockPos pos);
        Target target(BlockPos pos);
        Container inventory();
    }

    private record PlayerAccess(CompanionPlayer companion, CompanionScheduler scheduler) implements Access {
        @Override public boolean busy() { return scheduler.busy(companion.getUUID()); }
        @Override public boolean inBounds(BlockPos pos) { return companion.level().isInWorldBounds(pos); }
        @Override public double distanceSquared(BlockPos pos) {
            return companion.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
        }
        @Override public boolean loaded(BlockPos pos) { return companion.level().isLoaded(pos); }
        @Override public Container inventory() { return companion.getInventory(); }
        @Override public Target target(BlockPos pos) {
            var entity = companion.level().getBlockEntity(pos);
            if (!(entity instanceof Container container)) return null;
            Direction face = facing(companion.getX() - pos.getX() - 0.5,
                    companion.getEyeY() - pos.getY() - 0.5, companion.getZ() - pos.getZ() - 0.5);
            return new Target(container, locked(container), entity instanceof AbstractFurnaceBlockEntity,
                    container.stillValid(companion), face);
        }
    }

    @Override public String name() { return "transfer"; }

    @Override public Result run(CompanionPlayer companion, JsonObject args) {
        return execute(args, new PlayerAccess(companion, McbotMod.scheduler()));
    }

    @Override public CompletableFuture<Result> runAsync(CompanionPlayer companion, JsonObject args,
                                                       CompanionScheduler scheduler) {
        return CompletableFuture.completedFuture(execute(args, new PlayerAccess(companion, scheduler)));
    }

    static Arguments arguments(JsonObject args) {
        if (args == null) return null;
        Integer x = integer(args, "x"), y = integer(args, "y"), z = integer(args, "z");
        if (x == null || y == null || z == null) return null;
        String dir = args.has("dir") ? text(args, "dir") : "out";
        if (!"out".equals(dir) && !"in".equals(dir)) return null;
        Item filter = null;
        if (args.has("item")) {
            String id = text(args, "item");
            if (id == null || id.length() > 128) return null;
            try {
                var entry = BuiltInRegistries.ITEM.get(ResourceLocationHelper.of(id));
                if (entry.isEmpty()) return null;
                filter = entry.get().value();
            } catch (RuntimeException invalid) {
                return null;
            }
        }
        return new Arguments(new BlockPos(x, y, z), dir.equals("out"), filter);
    }

    private static Integer integer(JsonObject args, String key) {
        var value = args.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) return null;
        try {
            return value.getAsBigDecimal().intValueExact();
        } catch (RuntimeException invalid) {
            return null;
        }
    }

    private static String text(JsonObject args, String key) {
        var value = args.get(key);
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()
                ? value.getAsString() : null;
    }

    static boolean locked(Container container) {
        return container instanceof BaseContainerBlockEntity base && base.isLocked();
    }

    static Direction facing(double x, double y, double z) {
        if (Math.abs(y) > Math.abs(x) && Math.abs(y) > Math.abs(z)) return y >= 0 ? Direction.UP : Direction.DOWN;
        if (Math.abs(x) >= Math.abs(z)) return x >= 0 ? Direction.EAST : Direction.WEST;
        return z >= 0 ? Direction.SOUTH : Direction.NORTH;
    }

    static Result execute(JsonObject json, Access access) {
        Arguments args = arguments(json);
        if (args == null) return failure("DENIED:需要整数容器坐标 x/y/z，dir=in/out 和可选有效物品 item。");
        if (access.busy()) return failure("BUSY:我正忙着上一件事，等它结束或取消后再存取物品。");
        if (!access.inBounds(args.pos())) return failure("DENIED:容器坐标超出当前世界边界。");
        if (access.distanceSquared(args.pos()) > 6.5 * 6.5) {
            return failure("OUT_OF_REACH:离容器太远，先 move_to 过去。");
        }
        if (!access.loaded(args.pos())) {
            return failure("TARGET_LOST:容器所在区块未加载，先靠近后重试。");
        }
        Target target = access.target(args.pos());
        if (target == null) return failure("TARGET_LOST:目标不是可存取容器，先 scan_area 重新查找。");
        if (target.machine()) {
            return failure("DENIED:熔炉类机器须用 smelt query/load/take 按原料/燃料/产物槽操作。");
        }
        if (target.locked()) return failure("DENIED:容器已锁定，不能通过工具绕过容器锁。");
        if (!target.valid()) return failure("DENIED:容器当前不可用，先靠近并检查目标。");
        return transfer(access.inventory(), target, args);
    }

    private static Result transfer(Container inventory, Target target, Arguments args) {
        Container source = args.out() ? target.slots() : inventory;
        Container destination = args.out() ? inventory : target.slots();
        int sourceSlots = args.out() ? source.getContainerSize() : Math.min(Inventory.INVENTORY_SIZE, source.getContainerSize());
        int destinationSlots = args.out() ? Inventory.INVENTORY_SIZE : destination.getContainerSize();
        int movedStacks = 0, movedItems = 0, remainingItems = 0;
        for (int slot = 0; slot < sourceSlots; slot++) {
            var stack = source.getItem(slot);
            if (stack.isEmpty() || args.filter() != null && !stack.is(args.filter())) continue;
            var movement = ItemTransfers.move(source, slot, destination, destinationSlots, target.face());
            if (movement.moved() > 0) movedStacks++;
            movedItems += movement.moved();
            remainingItems += movement.remainder().getCount();
        }
        JsonObject data = new JsonObject();
        data.addProperty("moved_items", movedItems);
        data.addProperty("moved_stacks", movedStacks);
        data.addProperty("remaining_items", remainingItems);
        data.addProperty("partial", movedItems > 0 && remainingItems > 0);
        data.addProperty("dir", args.out() ? "out" : "in");
        String what = args.filter() == null ? "物品" : BuiltInRegistries.ITEM.getKey(args.filter()).toString();
        String feedback = (args.out() ? "从容器取出 " : "存入容器 ") + movedItems + " 个" + what
                + "（" + movedStacks + " 组）。";
        if (remainingItems > 0) {
            feedback += "仍有 " + remainingItems + " 个留在来源，未丢弃；"
                    + (args.out() ? "背包空间不足或来源槽不允许取出。" : "目标空间不足或槽/接触面不允许存入。")
                    + "用 inventory 核对已搬数量，腾出空间或换容器后再处理剩余物品。";
        } else if (movedItems == 0) {
            feedback += "来源没有匹配物品，未移动；请核对目标与 item。";
        }
        return new Result(remainingItems == 0, feedback, data);
    }

    private static Result failure(String text) {
        return new Result(false, text, null);
    }
}
