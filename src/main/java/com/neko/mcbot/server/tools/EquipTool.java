package com.neko.mcbot.server.tools;

import com.google.gson.JsonObject;
import com.neko.mcbot.McbotMod;
import com.neko.mcbot.body.CompanionPlayer;
import com.neko.mcbot.server.ServerTool;
import com.neko.mcbot.server.EquipmentActions;
import com.neko.mcbot.server.NativeSupplyAccess;
import com.neko.mcbot.task.CompanionScheduler;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

import java.util.concurrent.CompletableFuture;

/** Main-hand selection only; exchange whole stacks without merging or requiring a free slot. */
public final class EquipTool implements ServerTool {
    @Override
    public String name() {
        return "equip";
    }

    @Override
    public Result run(CompanionPlayer companion, JsonObject args) {
        return equip(companion, args, McbotMod.scheduler());
    }

    @Override
    public CompletableFuture<Result> runAsync(CompanionPlayer companion, JsonObject args,
                                               CompanionScheduler scheduler) {
        return CompletableFuture.completedFuture(equip(companion, args, scheduler));
    }

    static Integer sourceSlot(JsonObject args) {
        var value = args == null ? null : args.get("slot");
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) return null;
        try {
            int slot = value.getAsBigDecimal().intValueExact();
            return slot >= 0 && slot < Inventory.INVENTORY_SIZE ? slot : null;
        } catch (RuntimeException invalid) {
            return null;
        }
    }

    private static Result equip(CompanionPlayer companion, JsonObject args, CompanionScheduler scheduler) {
        Integer source = sourceSlot(args);
        String target = target(args);
        if (source == null || target == null) {
            return new Result(false, "DENIED:slot须为0-35整数，target可省略/mainhand或head/chest/legs/feet；先inventory查看。", null);
        }
        if (scheduler.busy(companion.getUUID())) {
            return new Result(false, "BUSY:我正忙着上一件事，等它结束或取消后再切换主手。", null);
        }
        var access = new NativeSupplyAccess(companion, () -> null);
        if (!target.equals("mainhand")) {
            var failure = access.guard();
            if (failure != null) return failure;
        }
        return EquipmentActions.equip(access, source, target);
    }

    public static String target(JsonObject args) {
        if (args == null || !java.util.Set.of("slot", "target").containsAll(args.keySet())) return null;
        if (!args.has("target")) return "mainhand";
        var value = args.get("target");
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) return null;
        String target = value.getAsString();
        return target.equals("mainhand") || EquipmentActions.ARMOR.contains(target) ? target : null;
    }
}
