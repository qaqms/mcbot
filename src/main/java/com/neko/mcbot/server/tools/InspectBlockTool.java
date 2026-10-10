package com.neko.mcbot.server.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.neko.mcbot.body.CompanionPlayer;
import com.neko.mcbot.server.ServerTool;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.Container;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;

/** Local read-only slot pages, without opening a menu or unpacking a loot table. */
public final class InspectBlockTool implements ServerTool {
    public static final int PAGE_SIZE = 24;
    record Target(String block, Container container, boolean locked, boolean valid, boolean pendingLoot) {
    }
    interface Access {
        Result guard(BlockPos pos);
        Target target(BlockPos pos);
    }
    @Override public String name() { return "inspect_block"; }
    @Override public Result run(CompanionPlayer companion, JsonObject args) {
        return execute(args, new Access() {
            @Override public Result guard(BlockPos pos) {
                if (!companion.level().isInWorldBounds(pos)
                        || !companion.level().getWorldBorder().isWithinBounds(pos)) return failed("DENIED:目标超出世界边界。");
                if (companion.distanceToSqr(pos.getX() + .5, pos.getY() + .5, pos.getZ() + .5) > 6.5 * 6.5)
                    return failed("OUT_OF_REACH:只读取身体6.5格内目标，先显式靠近。");
                if (companion.level().getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4) == null)
                    return failed("TARGET_LOST:目标区块未完整加载，不强制加载。");
                return null;
            }
            @Override public Target target(BlockPos pos) {
                var entity = companion.level().getBlockEntity(pos);
                Container container = entity instanceof Container c ? c : null;
                return new Target(BuiltInRegistries.BLOCK.getKey(companion.level().getBlockState(pos).getBlock()).toString(),
                        container, container != null && TransferTool.locked(container),
                        container == null || container.stillValid(companion),
                        entity instanceof RandomizableContainerBlockEntity loot && loot.getLootTable() != null);
            }
        });
    }

    static Result execute(JsonObject args, Access access) {
        for (String key : args.keySet()) if (!java.util.List.of("x", "y", "z", "offset").contains(key))
            return failed("DENIED:只接受整数坐标和可选offset。");
        BlockPos pos = BreakBlockTool.readPos(args);
        int offset = 0;
        try {
            if (args.has("offset")) {
                var value = args.get("offset");
                if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) return failed("DENIED:offset须整数。");
                offset = value.getAsBigDecimal().intValueExact();
            }
        } catch (RuntimeException invalid) { return failed("DENIED:offset须整数。"); }
        if (pos == null || offset < 0 || offset > 1023) return failed("DENIED:需要整数坐标与0-1023的offset。");
        Result failure = access.guard(pos);
        if (failure != null) return failure;
        Target target = access.target(pos);
        if (target.locked() || !target.valid()) return failed("DENIED:容器锁定或不可用，不能绕过。");
        if (target.pendingLoot()) return failed("DENIED:未展开战利品容器不能作为纯读取操作展开。");
        Container container = target.container();
        int size = container == null ? 0 : container.getContainerSize();
        if (size > 1024 || offset >= size && offset != 0) return failed("DENIED:槽位范围无效或容器过大。");
        var data = new JsonObject();
        data.addProperty("block", com.neko.mcbot.common.WireSize.truncateToBytes(target.block(), 128));
        data.addProperty("container", container != null);
        data.addProperty("slot_count", size);
        data.addProperty("offset", offset);
        var slots = new JsonArray();
        StringBuilder feedback = new StringBuilder("方块 @(" + pos.getX() + "," + pos.getY() + "," + pos.getZ()
                + ") " + data.get("block").getAsString() + "，本体容器 " + size + " 槽；");
        int end = Math.min(size, offset + PAGE_SIZE);
        for (int slot = offset; slot < end; slot++) {
            var stack = container.getItem(slot);
            var entry = InventoryTool.stackData(stack);
            entry.addProperty("slot", slot);
            slots.add(entry);
            feedback.append("\n槽 ").append(slot).append(": ").append(InventoryTool.describe(stack));
        }
        data.add("slots", slots);
        data.addProperty("next_offset", end < size ? end : -1);
        feedback.append("\n下一页offset=").append(end < size ? end : -1)
                .append("；只读，不开启GUI、不搬物品、不合并双箱、不代表全存储网络；熔炉进度用smelt query。");
        return new Result(true, feedback.toString(), data);
    }

    private static Result failed(String text) { return new Result(false, text, null); }
}
