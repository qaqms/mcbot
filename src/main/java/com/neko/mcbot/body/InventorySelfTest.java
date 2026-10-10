package com.neko.mcbot.body;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.neko.mcbot.McbotMod;
import com.neko.mcbot.common.Envelope;
import com.neko.mcbot.common.WireSize;
import com.neko.mcbot.server.ServerTool;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.storage.LevelResource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Actual server objects in a disposable world; never run this fixture in a player's world. */
final class InventorySelfTest {
    private InventorySelfTest() {
    }

    static boolean runIfRequested(MinecraftServer server) {
        Path flags = FabricLoader.getInstance().getGameDir().resolve("mcbot");
        Path flag = flags.resolve("autotest-inventory.flag");
        if (!Files.exists(flag)) return false;
        try {
            Files.delete(flag);
            Path world = server.getWorldPath(LevelResource.ROOT).normalize();
            if (!world.getFileName().toString().startsWith("mcbot-inventory-")) {
                throw new IllegalStateException("Requires an isolated mcbot-inventory-* development world");
            }
            for (String other : List.of("autotest.flag", "autotest-craft.flag", "autotest-persistence-seed.flag",
                    "autotest-persistence-verify.flag")) {
                if (Files.exists(flags.resolve(other))) throw new IllegalStateException("Conflicting test flags");
            }
            if (McbotMod.summonService().roster().byName("inv_fixture") != null) {
                throw new IllegalStateException("Requires a fresh fixture world");
            }
            McbotMod.summonService().summon(null, "inv_fixture");
            var player = server.getPlayerList().getPlayer(SummonService.offlineUuid("inv_fixture"));
            if (!(player instanceof CompanionPlayer companion)) throw new IllegalStateException("Summon failed");
            verify(companion);
            McbotMod.LOG.info("[inventory-test] PASS; normal shutdown requested");
        } catch (Exception failure) {
            McbotMod.LOG.error("[inventory-test] FAILED", failure);
        } finally {
            server.halt(false);
        }
        return true;
    }

    private static void verify(CompanionPlayer companion) {
        var registry = McbotMod.toolRegistry();
        ServerTool inventoryTool = registry.get("inventory");
        ServerTool equip = registry.get("equip");
        check("registration", inventoryTool != null && equip != null
                && inventoryTool.acceptanceMode() == ServerTool.Acceptance.SYNC
                && equip.acceptanceMode() == ServerTool.Acceptance.SYNC);
        Inventory inventory = companion.getInventory();
        inventory.clearContent();
        inventory.setSelectedSlot(4);
        ServerTool.Result empty = inventoryTool.run(companion, new JsonObject());
        check("empty-inventory", empty.ok() && empty.data().getAsJsonArray("slots").size() == 36
                && empty.data().get("slots_used").getAsInt() == 0
                && empty.data().get("selected_slot").getAsInt() == 4);
        check("equipment-mapping", inventory.getContainerSize() == 43
                && inventory.getNonEquipmentItems().size() == 36
                && Inventory.EQUIPMENT_SLOT_MAPPING.get(36) == EquipmentSlot.FEET
                && Inventory.EQUIPMENT_SLOT_MAPPING.get(37) == EquipmentSlot.LEGS
                && Inventory.EQUIPMENT_SLOT_MAPPING.get(38) == EquipmentSlot.CHEST
                && Inventory.EQUIPMENT_SLOT_MAPPING.get(39) == EquipmentSlot.HEAD
                && Inventory.EQUIPMENT_SLOT_MAPPING.get(40) == EquipmentSlot.OFFHAND
                && Inventory.EQUIPMENT_SLOT_MAPPING.get(41) == EquipmentSlot.BODY
                && Inventory.EQUIPMENT_SLOT_MAPPING.get(42) == EquipmentSlot.SADDLE
                && empty.data().getAsJsonArray("equipment").size() == 7);

        ItemStack tool = new ItemStack(Items.IRON_PICKAXE);
        tool.setDamageValue(17);
        tool.set(DataComponents.CUSTOM_NAME, Component.literal("fixture-name".repeat(18000)));
        inventory.setItem(0, new ItemStack(Items.TORCH, 7));
        inventory.setItem(4, new ItemStack(Items.COBBLESTONE, 23));
        inventory.setItem(13, tool);
        inventory.setItem(35, new ItemStack(Items.OAK_LOG, 11));
        inventory.setItem(38, new ItemStack(Items.GOLDEN_CHESTPLATE));
        inventory.setItem(40, new ItemStack(Items.TORCH, 9));
        List<ItemStack> initial = snapshot(inventory);
        ServerTool.Result details = inventoryTool.run(companion, new JsonObject());
        JsonObject pick = details.data().getAsJsonArray("slots").get(13).getAsJsonObject();
        check("details-and-readonly", details.ok() && matches(inventory, initial, 4)
                && details.data().get("slots_used").getAsInt() == 4
                && pick.get("item").getAsString().equals("minecraft:iron_pickaxe")
                && pick.get("count").getAsInt() == 1
                && pick.get("damage").getAsInt() == 17
                && pick.get("max_damage").getAsInt() == tool.getMaxDamage()
                && details.feedback().contains("槽 13: minecraft:iron_pickaxe")
                && details.feedback().contains("槽 35: minecraft:oak_log")
                && details.feedback().contains("offhand: minecraft:torch"));
        check("bounded-receipt", fits(details) && !details.feedback().contains("fixture-name")
                && !details.data().toString().contains("fixture-name"));

        check("hotbar-selection", equip(companion, 0).ok() && matches(inventory, initial, 0)
                && ItemStack.matches(companion.getMainHandItem(), initial.get(0)));
        check("selected-slot-idempotent", equip(companion, 0).ok() && matches(inventory, initial, 0));
        var swapped = new ArrayList<>(initial);
        swapped.set(0, initial.get(13));
        swapped.set(13, initial.get(0));
        ServerTool.Result switched = equip(companion, 13);
        check("backpack-whole-stack-swap", switched.ok() && fits(switched)
                && matches(inventory, swapped, 0)
                && ItemStack.matches(companion.getMainHandItem(), tool)
                && switched.data().get("selected_slot").getAsInt() == 0
                && switched.data().get("source_slot").getAsInt() == 13
                && switched.data().get("swapped").getAsBoolean()
                && switched.feedback().contains("原主手移到槽 13"));
        check("swap-back", equip(companion, 13).ok() && matches(inventory, initial, 0));
        ServerTool.Result missing = equip(companion, 12);
        check("empty-slot-denied", !missing.ok() && missing.feedback().startsWith("DENIED:")
                && matches(inventory, initial, 0));

        for (String args : List.of("{}", "{\"slot\":null}", "{\"slot\":\"13\"}", "{\"slot\":true}",
                "{\"slot\":[]}", "{\"slot\":{}}", "{\"slot\":13.5}", "{\"slot\":-1}",
                "{\"slot\":36}", "{\"slot\":40}", "{\"slot\":42}", "{\"slot\":1e100}")) {
            ServerTool.Result denied = equip.run(companion, JsonParser.parseString(args).getAsJsonObject());
            if (denied.ok() || !denied.feedback().startsWith("DENIED:")
                    || !matches(inventory, initial, 0)) throw new IllegalStateException("Malformed slot changed inventory");
        }
        check("invalid-parameters-no-mutation", true);

        for (int slot = 0; slot < 36; slot++) inventory.setItem(slot, new ItemStack(Items.COBBLESTONE, slot + 1));
        inventory.setItem(35, tool.copy());
        inventory.setSelectedSlot(8);
        List<ItemStack> full = snapshot(inventory);
        var fullSwap = new ArrayList<>(full);
        fullSwap.set(8, full.get(35));
        fullSwap.set(35, full.get(8));
        check("full-inventory-swap", equip(companion, 35).ok() && matches(inventory, fullSwap, 8));
        ServerTool.Result fullDetails = inventoryTool.run(companion, new JsonObject());
        check("full-inventory-receipt", fullDetails.data().get("slots_used").getAsInt() == 36
                && fullDetails.feedback().contains("槽 35: minecraft:cobblestone")
                && fits(fullDetails));

        inventory.setItem(8, ItemStack.EMPTY);
        List<ItemStack> emptyHand = snapshot(inventory);
        var emptyHandSwap = new ArrayList<>(emptyHand);
        emptyHandSwap.set(8, emptyHand.get(27));
        emptyHandSwap.set(27, ItemStack.EMPTY);
        check("empty-mainhand-swap", equip(companion, 27).ok() && matches(inventory, emptyHandSwap, 8));

        JsonObject waitArgs = new JsonObject();
        waitArgs.addProperty("seconds", 60);
        var wait = registry.get("wait").runAsync(companion, waitArgs, McbotMod.scheduler());
        try {
            ServerTool.Result busy = equip(companion, 35);
            var busyAsync = equip.runAsync(companion, slot(0), McbotMod.scheduler());
            check("busy-switch-denied", !wait.isDone() && !busy.ok() && busy.feedback().startsWith("BUSY:")
                    && busyAsync.isDone() && !busyAsync.join().ok()
                    && busyAsync.join().feedback().startsWith("BUSY:")
                    && matches(inventory, emptyHandSwap, 8));
            check("busy-inventory-readable", inventoryTool.run(companion, new JsonObject()).ok()
                    && !wait.isDone() && matches(inventory, emptyHandSwap, 8));
        } finally {
            McbotMod.scheduler().cancel(companion.getUUID(), "Inventory fixture finished.");
        }
        check("cancel-then-equip", wait.isDone() && wait.join().feedback().startsWith("CANCELLED:")
                && equip.runAsync(companion, slot(0), McbotMod.scheduler()).join().ok()
                && matches(inventory, emptyHandSwap, 0));
    }

    private static ServerTool.Result equip(CompanionPlayer companion, int source) {
        return McbotMod.toolRegistry().get("equip").run(companion, slot(source));
    }

    private static JsonObject slot(int source) {
        JsonObject args = new JsonObject();
        args.addProperty("slot", source);
        return args;
    }

    private static List<ItemStack> snapshot(Inventory inventory) {
        var stacks = new ArrayList<ItemStack>();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) stacks.add(inventory.getItem(slot).copy());
        return stacks;
    }

    private static boolean matches(Inventory inventory, List<ItemStack> expected, int selected) {
        if (inventory.getSelectedSlot() != selected || inventory.getContainerSize() != expected.size()) return false;
        for (int slot = 0; slot < expected.size(); slot++) {
            if (!ItemStack.matches(inventory.getItem(slot), expected.get(slot))) return false;
        }
        return true;
    }

    private static boolean fits(ServerTool.Result result) {
        JsonObject body = new JsonObject();
        body.addProperty("seq", Integer.MAX_VALUE);
        body.addProperty("ok", result.ok());
        body.addProperty("feedback", result.feedback());
        body.add("data", result.data());
        return WireSize.fits(new Envelope("tool_result", body).encode());
    }

    private static void check(String scenario, boolean passed) {
        McbotMod.LOG.info("[inventory-test] {}={}", scenario, passed);
        if (!passed) throw new IllegalStateException(scenario);
    }
}
