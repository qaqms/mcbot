package com.neko.mcbot.server;

import com.neko.mcbot.body.CompanionPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.ItemStack;

import java.util.function.Supplier;

/** Native menu qualification and held use, shared by tools and continuous maintenance. */
public final class NativeSupplyAccess implements EquipmentActions.Access, ConsumptionWork.Access {
    private final CompanionPlayer body;
    private final Supplier<ServerTool.Result> authority;
    public NativeSupplyAccess(CompanionPlayer body, Supplier<ServerTool.Result> authority) {
        this.body = body; this.authority = authority;
    }
    @Override public ServerTool.Result guard() {
        var failure = authority.get();
        if (failure != null) return failure;
        if (!body.isAlive() || body.isRemoved()) return failed("TARGET_LOST:原身体已离场。");
        if (!body.gameMode.isSurvival() || body.isSleeping() || body.isPassenger())
            return failed("DENIED:当前身体状态不支持补给或装备维护。");
        return body.environmentFailure();
    }
    @Override public Inventory inventory() { return body.getInventory(); }
    @Override public EquipmentActions.Armor armor(String target) {
        int index = EquipmentActions.ARMOR.indexOf(target);
        if (index < 0) return null;
        var slot = body.inventoryMenu.getSlot(InventoryMenu.ARMOR_SLOT_START + index);
        return new EquipmentActions.Armor() {
            @Override public ItemStack worn() { return slot.getItem(); }
            @Override public boolean accepts(ItemStack stack) { return slot.mayPlace(stack); }
            @Override public void put(ItemStack stack) { slot.setByPlayer(stack); }
        };
    }
    @Override public int food() { return body.getFoodData().getFoodLevel(); }
    @Override public float health() { return body.getHealth(); }
    @Override public boolean using() { return body.isUsingItem(); }
    @Override public boolean usingMainHand() { return body.getUsedItemHand() == InteractionHand.MAIN_HAND; }
    @Override public boolean start() {
        body.releaseMovement();
        return body.gameMode.useItem(body, body.level(), body.getMainHandItem(), InteractionHand.MAIN_HAND)
                .consumesAction();
    }
    @Override public void advanceNativeUse() {
        ((com.neko.mcbot.mixin.LivingEquipmentAccess) (net.minecraft.world.entity.LivingEntity) body)
                .mcbot$updatingUsingItem();
    }
    @Override public void stop() { body.stopUsingItem(); }
    private static ServerTool.Result failed(String message) { return new ServerTool.Result(false, message, null); }
}
