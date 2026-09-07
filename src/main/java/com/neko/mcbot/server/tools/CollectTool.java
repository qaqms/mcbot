package com.neko.mcbot.server.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.neko.mcbot.body.CompanionPlayer;
import com.neko.mcbot.server.ServerTool;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.List;

/** collect：把指定点（默认为自己脚下）半径内掉落地上的物品吸进背包。 */
public final class CollectTool implements ServerTool {

    @Override
    public String name() {
        return "collect";
    }

    @Override
    public Result run(CompanionPlayer c, JsonObject args) {
        BlockPos at = args.has("x") ? BreakBlockTool.readPos(args) : c.blockPosition();
        int r = args.has("r") ? Math.min(12, Math.max(1, args.get("r").getAsInt())) : 3;

        List<String> got = new ArrayList<>();
        List<String> left = new ArrayList<>();
        for (ItemEntity it : c.level().getEntitiesOfClass(ItemEntity.class,
                new AABB(at).inflate(r))) {
            ItemStack stack = it.getItem();
            if (stack.isEmpty()) {
                continue;
            }
            if (c.getInventory().add(stack.copy())) {
                got.add(stack.getCount() + "×" + stack.getItemName().getString());
                it.discard();
            } else {
                left.add(stack.getCount() + "×" + stack.getItemName().getString());
            }
        }
        JsonObject data = new JsonObject();
        JsonArray arr = new JsonArray();
        got.forEach(arr::add);
        data.add("collected", arr);
        if (got.isEmpty() && left.isEmpty()) {
            return new Result(true, "半径 " + r + " 内地上没有可捡的东西。", data);
        }
        return new Result(left.isEmpty(),
                "捡起 " + String.join("、", got) + (left.isEmpty() ? "。"
                        : "；背包满了捡不下: " + String.join("、", left)), data);
    }
}
