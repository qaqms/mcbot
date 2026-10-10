package com.neko.mcbot.server.tools;

import com.google.gson.JsonObject;
import com.neko.mcbot.McbotMod;
import com.neko.mcbot.body.CompanionPlayer;
import com.neko.mcbot.common.ResourceLocationHelper;
import com.neko.mcbot.server.BlockPlacement;
import com.neko.mcbot.server.ServerTool;
import com.neko.mcbot.task.CompanionScheduler;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.entity.player.Inventory;

import java.util.concurrent.CompletableFuture;

/** Place the requested item at the exact target, preserving vanilla state and component hooks. */
public final class PlaceBlockTool implements ServerTool {
    record Arguments(BlockPos pos, BlockItem item, Direction face) {
    }

    @Override public String name() { return "place_block"; }
    @Override public Result run(CompanionPlayer c, JsonObject args) {
        return place(BlockPlacement.forPlayer(c), args, McbotMod.scheduler().busy(c.getUUID()));
    }
    @Override public CompletableFuture<Result> runAsync(CompanionPlayer c, JsonObject args,
                                                        CompanionScheduler scheduler) {
        return CompletableFuture.completedFuture(place(BlockPlacement.forPlayer(c), args, scheduler.busy(c.getUUID())));
    }

    static Arguments arguments(JsonObject args) {
        BlockPos pos = BreakBlockTool.readPos(args);
        if (pos == null) return null;
        try {
            var value = args.get("item");
            if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) return null;
            String name = value.getAsString();
            if (name.isBlank() || name.length() > 128) return null;
            var holder = BuiltInRegistries.ITEM.<Item>get(ResourceLocationHelper.of(name));
            if (holder.isEmpty() || !(holder.get().value() instanceof BlockItem item)) return null;
            Direction face = Direction.UP;
            if (args.has("face")) {
                var faceValue = args.get("face");
                if (!faceValue.isJsonPrimitive() || !faceValue.getAsJsonPrimitive().isString()) return null;
                face = switch (faceValue.getAsString()) {
                    case "up" -> Direction.UP;
                    case "down" -> Direction.DOWN;
                    case "north" -> Direction.NORTH;
                    case "south" -> Direction.SOUTH;
                    case "east" -> Direction.EAST;
                    case "west" -> Direction.WEST;
                    default -> null;
                };
                if (face == null) return null;
            }
            return new Arguments(pos, item, face);
        } catch (RuntimeException invalid) {
            return null;
        }
    }

    static Result place(BlockPlacement.Access access, JsonObject args, boolean busy) {
        Arguments input = arguments(args);
        if (input == null) return new Result(false,
                "DENIED:需要整数 x/y/z 和方块物品 ID；face 可选 up/down/north/south/east/west。", null);
        if (busy) return new Result(false, "BUSY:身体正忙，任务结束或取消后再放置。", null);
        int slot = -1;
        var inventory = access.inventory();
        for (int i = 0; i < Math.min(Inventory.INVENTORY_SIZE, inventory.getContainerSize()); i++) {
            var stack = inventory.getItem(i);
            if (!stack.isEmpty() && stack.is(input.item())) {
                slot = i;
                break;
            }
        }
        return BlockPlacement.place(access, input.pos(), slot, input.face());
    }
}
