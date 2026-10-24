package com.neko.mcbot.server;

import com.google.gson.JsonObject;
import com.neko.mcbot.mixin.LivingEquipmentAccess;
import com.neko.mcbot.server.tools.InventoryTool;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.effect.MobEffectUtil;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.ToDoubleFunction;

/** Read-only inventory choices. Only take() changes the hand, after the original action gates. */
public final class MiningTools {
    public record Pick(int slot, ItemStack stack, float speed) {
        public double seconds(float hardness) {
            if (hardness < 0 || !(speed > 0) || Float.isNaN(speed)) return Double.POSITIVE_INFINITY;
            return hardness == 0 ? 0 : hardness * 30.0 / (speed * 20.0);
        }
    }
    private record Candidate(int slot, ItemStack stack, double efficiency, double multiplier) { }
    private record Key(BlockState state, int reserve) { }
    private final List<Candidate> candidates;
    private final Map<Key, Pick> cache = new HashMap<>();

    public MiningTools(Inventory inventory, ToDoubleFunction<ItemStack> efficiency,
                       ToDoubleFunction<ItemStack> multiplier) {
        var choices = new ArrayList<Candidate>();
        for (int slot = 0; slot < Inventory.INVENTORY_SIZE; slot++) {
            var stack = inventory.getItem(slot).copy();
            if (stack.isDamageableItem() && stack.getDamageValue() >= stack.getMaxDamage()) continue;
            choices.add(new Candidate(slot, stack, efficiency.applyAsDouble(stack), multiplier.applyAsDouble(stack)));
        }
        candidates = List.copyOf(choices);
    }

    public static MiningTools forPlayer(ServerPlayer player) {
        // Attributes may still belong to the preceding hand until native equipment detection runs.
        var equipped = ((LivingEquipmentAccess) player).mcbot$lastEquipmentItems()
                .getOrDefault(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
        var mining = baseline(player, Attributes.MINING_EFFICIENCY, equipped);
        var breaking = baseline(player, Attributes.BLOCK_BREAK_SPEED, equipped);
        var submerged = baseline(player, Attributes.SUBMERGED_MINING_SPEED, equipped);
        float effect = MobEffectUtil.hasDigSpeed(player)
                ? 1 + (MobEffectUtil.getDigSpeedAmplification(player) + 1) * .2f : 1;
        var fatigue = player.getEffect(MobEffects.MINING_FATIGUE);
        if (fatigue != null) effect *= switch (fatigue.getAmplifier()) {
            case 0 -> .3f;
            case 1 -> .09f;
            case 2 -> .0027f;
            default -> .00081f;
        };
        double common = effect / (player.onGround() ? 1 : 5.0);
        boolean wet = player.isEyeInFluid(FluidTags.WATER);
        return new MiningTools(player.getInventory(), stack -> value(mining, stack),
                stack -> common * value(breaking, stack) * (wet ? value(submerged, stack) : 1));
    }

    private static AttributeInstance baseline(ServerPlayer player, Holder<Attribute> attribute, ItemStack equipped) {
        var copy = new AttributeInstance(attribute, ignored -> { });
        var actual = player.getAttribute(attribute);
        if (actual != null) copy.replaceFrom(actual);
        equipped.forEachModifier(EquipmentSlot.MAINHAND, (key, modifier) -> {
            if (key.equals(attribute)) copy.removeModifier(modifier.id());
        });
        return copy;
    }

    static double value(AttributeInstance baseline, ItemStack stack) {
        var copy = new AttributeInstance(baseline.getAttribute(), ignored -> { });
        copy.replaceFrom(baseline);
        stack.forEachModifier(EquipmentSlot.MAINHAND, (key, modifier) -> {
            if (key.equals(copy.getAttribute())) copy.addOrUpdateTransientModifier(modifier);
        });
        return copy.getValue();
    }

    public Pick best(BlockState state) { return best(state, 0); }
    public Pick best(BlockState state, int reserve) {
        return cache.computeIfAbsent(new Key(state, reserve), key -> choose(key.state(), key.reserve()));
    }

    public static boolean usable(ItemStack stack, int reserve) {
        if (!stack.isDamageableItem()) return true;
        var tool = stack.get(DataComponents.TOOL);
        int cost = reserve == 0 || tool == null ? 1 : Math.max(1, tool.damagePerBlock());
        return stack.getMaxDamage() - stack.getDamageValue() >= (long) reserve + cost;
    }

    private Pick choose(BlockState state, int reserve) {
        Pick best = null;
        for (var candidate : candidates) {
            var stack = candidate.stack();
            if (!usable(stack, reserve)) continue;
            if (state.requiresCorrectToolForDrops() && !stack.isCorrectToolForDrops(state)) continue;
            float speed = stack.getDestroySpeed(state);
            if (speed > 1) speed += (float) candidate.efficiency();
            speed *= (float) candidate.multiplier();
            if (!(speed > 0) || Float.isNaN(speed)) continue;
            if (best == null || speed > best.speed() || speed == best.speed() && lessWear(stack, best.stack()))
                best = new Pick(candidate.slot(), stack, speed);
        }
        return best;
    }

    private static boolean lessWear(ItemStack next, ItemStack previous) {
        if (next.isDamageableItem() != previous.isDamageableItem()) return !next.isDamageableItem();
        return next.isEmpty() && !previous.isEmpty();
    }

    /** The observation cannot silently equip a different stack if a slot changed before execution. */
    public static ServerTool.Result take(Inventory inventory, Pick pick) {
        if (pick == null || !ItemStack.matches(inventory.getItem(pick.slot()), pick.stack()))
            return new ServerTool.Result(false, "WRONG_TOOL:已选工具槽发生变化，未切换或挖掘；先重新核对背包。", null);
        var data = new JsonObject();
        int previous = inventory.getSelectedSlot();
        data.addProperty("previous_slot", previous);
        data.add("previous", InventoryTool.stackData(inventory.getSelectedItem()));
        EquipmentActions.hold(inventory, pick.slot());
        data.addProperty("source_slot", pick.slot());
        data.addProperty("selected_slot", inventory.getSelectedSlot());
        data.addProperty("changed", previous != pick.slot());
        data.add("held", InventoryTool.stackData(inventory.getSelectedItem()));
        return new ServerTool.Result(true, "", data);
    }

    public static JsonObject describe(Pick pick, BlockState state, float hardness) {
        var data = new JsonObject();
        data.addProperty("harvestable", pick != null);
        if (pick != null) {
            data.addProperty("slot", pick.slot());
            data.add("stack", InventoryTool.stackData(pick.stack()));
            double seconds = pick.seconds(hardness);
            if (Double.isFinite(seconds)) data.addProperty("estimated_seconds", seconds);
            float base = pick.stack().getDestroySpeed(state);
            if (Float.isFinite(base)) data.addProperty("base_speed", base);
        }
        var tags = List.of(net.minecraft.tags.BlockTags.MINEABLE_WITH_AXE,
                net.minecraft.tags.BlockTags.MINEABLE_WITH_PICKAXE, net.minecraft.tags.BlockTags.MINEABLE_WITH_SHOVEL,
                net.minecraft.tags.BlockTags.MINEABLE_WITH_HOE);
        var kinds = List.of("axe", "pickaxe", "shovel", "hoe");
        for (int i = 0; i < tags.size(); i++) if (state.is(tags.get(i))) {
            data.addProperty("preferred_kind", kinds.get(i));
            data.addProperty("preparation_recommended", pick == null || pick.stack().getDestroySpeed(state) <= 1);
            break;
        }
        return data;
    }
}
