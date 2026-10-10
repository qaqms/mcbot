package com.neko.mcbot.body;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.neko.mcbot.McbotMod;
import com.neko.mcbot.common.Envelope;
import com.neko.mcbot.common.WireSize;
import com.neko.mcbot.server.ServerTool;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.LevelResource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Opt-in real recipe/inventory checks in a disposable development world, with no model or client. */
final class CraftSelfTest {
    private CraftSelfTest() {
    }

    static boolean runIfRequested(MinecraftServer server) {
        Path flags = FabricLoader.getInstance().getGameDir().resolve("mcbot");
        Path flag = flags.resolve("autotest-craft.flag");
        if (!Files.exists(flag)) return false;
        try {
            Files.delete(flag);
            Path world = server.getWorldPath(LevelResource.ROOT).normalize();
            if (!world.getFileName().toString().startsWith("mcbot-craft-")) {
                throw new IllegalStateException("Requires an isolated mcbot-craft-* development world");
            }
            for (String other : List.of("autotest.flag", "autotest-inventory.flag",
                    "autotest-persistence-seed.flag", "autotest-persistence-verify.flag")) {
                if (Files.exists(flags.resolve(other))) throw new IllegalStateException("Conflicting test flags");
            }
            if (McbotMod.summonService().roster().byName("craft_fixture") != null) {
                throw new IllegalStateException("Requires a fresh fixture world");
            }
            McbotMod.summonService().summon(null, "craft_fixture");
            var player = server.getPlayerList().getPlayer(SummonService.offlineUuid("craft_fixture"));
            if (!(player instanceof CompanionPlayer companion)) throw new IllegalStateException("Summon failed");
            verify(companion);
            McbotMod.LOG.info("[craft-test] PASS; normal shutdown requested");
        } catch (Exception failure) {
            McbotMod.LOG.error("[craft-test] FAILED", failure);
        } finally {
            server.halt(false);
        }
        return true;
    }

    private static void verify(CompanionPlayer companion) {
        var tool = McbotMod.toolRegistry().get("craft");
        check("registered-sync", tool != null && tool.acceptanceMode() == ServerTool.Acceptance.SYNC);
        Inventory inv = companion.getInventory();
        companion.snapTo(0.5, 90, 0.5);
        BlockPos table = new BlockPos(2, 90, 0);
        companion.level().setBlockAndUpdate(table.below(), Blocks.STONE.defaultBlockState());
        companion.level().setBlockAndUpdate(table, Blocks.AIR.defaultBlockState());
        inv.clearContent();
        inv.setSelectedSlot(4);
        ItemStack namedTool = new ItemStack(Items.IRON_PICKAXE);
        namedTool.setDamageValue(17);
        namedTool.set(DataComponents.CUSTOM_NAME, Component.literal("fixture".repeat(18000)));
        inv.setItem(4, namedTool);
        inv.setItem(0, new ItemStack(Items.OAK_LOG, 3));
        inv.setItem(38, new ItemStack(Items.GOLDEN_CHESTPLATE));
        inv.setItem(40, new ItemStack(Items.TORCH, 9));
        List<ItemStack> initial = snapshot(inv);
        var query = craft(companion, "{\"item\":\"oak_planks\",\"count\":5,\"query\":true}");
        check("query-readonly-and-rounding", query.ok() && matches(inv, initial, 4) && fits(query)
                && query.data().get("can_craft").getAsBoolean()
                && query.data().get("batches").getAsInt() == 2
                && query.data().get("produced_count").getAsInt() == 8
                && query.data().get("crafted_count").getAsInt() == 0
                && query.feedback().contains("minecraft:oak_log")
                && !query.feedback().contains("fixture"));
        var planks = craft(companion, "{\"item\":\"oak_planks\",\"count\":5}");
        check("shapeless-real-output", planks.ok() && count(inv, Items.OAK_PLANKS) == 8
                && count(inv, Items.OAK_LOG) == 1 && fits(planks));
        check("equipment-selected-and-components-preserved", inv.getSelectedSlot() == 4
                && ItemStack.matches(namedTool, initial.get(4))
                && ItemStack.matches(inv.getItem(4), initial.get(4))
                && ItemStack.matches(inv.getItem(38), initial.get(38))
                && ItemStack.matches(inv.getItem(40), initial.get(40)));
        var sticks = craft(companion, "{\"item\":\"stick\",\"count\":5,\"recipe\":\"minecraft:stick\"}");
        check("shaped-two-by-two", sticks.ok() && count(inv, Items.STICK) == 8
                && count(inv, Items.OAK_PLANKS) == 4
                && sticks.data().get("crafted_count").getAsInt() == 8);

        inv.clearContent();
        inv.setItem(12, new ItemStack(Items.COBBLESTONE, 3));
        inv.setItem(29, new ItemStack(Items.STICK, 2));
        List<ItemStack> before = snapshot(inv);
        var missingTable = craft(companion, "{\"item\":\"stone_pickaxe\"}");
        check("workbench-required-no-mutation", !missingTable.ok()
                && missingTable.feedback().startsWith("NEED_WORKBENCH:") && matches(inv, before, 4));
        companion.level().setBlockAndUpdate(table, Blocks.CRAFTING_TABLE.defaultBlockState());
        var pickQuery = craft(companion, "{\"item\":\"stone_pickaxe\",\"query\":true}");
        check("workbench-query-visible-to-model", pickQuery.ok() && matches(inv, before, 4)
                && pickQuery.data().get("can_craft").getAsBoolean()
                && pickQuery.feedback().contains("工作台 @(")
                && pickQuery.data().getAsJsonObject("workbench").get("x").getAsInt() == 2);
        var insufficientBatch = craft(companion, "{\"item\":\"stone_pickaxe\",\"count\":2}");
        check("later-batch-shortage-rolls-back-all", !insufficientBatch.ok()
                && insufficientBatch.feedback().startsWith("MISSING_MATERIALS:")
                && matches(inv, before, 4) && insufficientBatch.data().get("crafted_count").getAsInt() == 0);
        var pick = craft(companion, "{\"item\":\"stone_pickaxe\"}");
        check("stone-pickaxe-full-chain", pick.ok() && count(inv, Items.STONE_PICKAXE) == 1
                && count(inv, Items.COBBLESTONE) == 0 && count(inv, Items.STICK) == 0);

        inv.clearContent();
        inv.setItem(0, new ItemStack(Items.OAK_LOG, 2));
        inv.setItem(13, new ItemStack(Items.COBBLESTONE, 3));
        companion.level().setBlockAndUpdate(table, Blocks.AIR.defaultBlockState());
        boolean chain = craft(companion, "{\"item\":\"oak_planks\",\"count\":8}").ok()
                && craft(companion, "{\"item\":\"crafting_table\"}").ok()
                && craft(companion, "{\"item\":\"stick\",\"count\":2,\"recipe\":\"minecraft:stick\"}").ok();
        JsonObject place = new JsonObject();
        place.addProperty("x", table.getX());
        place.addProperty("y", table.getY());
        place.addProperty("z", table.getZ());
        place.addProperty("item", "crafting_table");
        chain &= McbotMod.toolRegistry().get("place_block").run(companion, place).ok();
        chain &= craft(companion, "{\"item\":\"stone_pickaxe\"}").ok();
        check("logs-workbench-placement-pickaxe-chain", chain
                && count(inv, Items.OAK_LOG) == 0 && count(inv, Items.CRAFTING_TABLE) == 0
                && count(inv, Items.OAK_PLANKS) == 2 && count(inv, Items.STICK) == 2
                && count(inv, Items.STONE_PICKAXE) == 1 && count(inv, Items.COBBLESTONE) == 0
                && companion.level().getBlockState(table).is(Blocks.CRAFTING_TABLE));
        inv.clearContent();
        inv.setItem(0, new ItemStack(Items.COBBLESTONE, 3));
        inv.setItem(1, new ItemStack(Items.STICK, 2));
        before = snapshot(inv);
        companion.level().setBlockAndUpdate(table, Blocks.AIR.defaultBlockState());
        BlockPos farTable = new BlockPos(6, 90, 0);
        companion.level().setBlockAndUpdate(farTable, Blocks.CRAFTING_TABLE.defaultBlockState());
        var tooFar = craft(companion, "{\"item\":\"stone_pickaxe\"}");
        check("workbench-outside-reach-denied", !tooFar.ok()
                && tooFar.feedback().startsWith("NEED_WORKBENCH:") && matches(inv, before, 4));
        companion.level().setBlockAndUpdate(farTable, Blocks.AIR.defaultBlockState());
        companion.level().setBlockAndUpdate(table, Blocks.CRAFTING_TABLE.defaultBlockState());

        inv.clearContent();
        inv.setItem(5, new ItemStack(Items.OAK_PLANKS, 4));
        inv.setItem(27, new ItemStack(Items.BIRCH_PLANKS, 4));
        check("tag-materials-across-stacks", craft(companion, "{\"item\":\"chest\"}").ok()
                && count(inv, Items.CHEST) == 1 && count(inv, Items.OAK_PLANKS) == 0
                && count(inv, Items.BIRCH_PLANKS) == 0);

        inv.clearContent();
        inv.setItem(0, new ItemStack(Items.OAK_PLANKS));
        inv.setItem(1, new ItemStack(Items.BIRCH_PLANKS));
        var custom = craft(companion, "{\"item\":\"gold_nugget\",\"count\":5,"
                + "\"recipe\":\"mcbot_test:overlapping_planks\"}");
        check("loaded-datapack-overlapping-ingredients", custom.ok()
                && custom.data().get("crafted_count").getAsInt() == 7
                && count(inv, Items.GOLD_NUGGET) == 7
                && count(inv, Items.OAK_PLANKS) == 0 && count(inv, Items.BIRCH_PLANKS) == 0);
        inv.clearContent();
        inv.setItem(0, new ItemStack(Items.OAK_PLANKS));
        before = snapshot(inv);
        var shared = craft(companion, "{\"item\":\"gold_nugget\","
                + "\"recipe\":\"mcbot_test:overlapping_planks\"}");
        check("overlapping-ingredients-cannot-double-spend", !shared.ok()
                && shared.feedback().startsWith("MISSING_MATERIALS:") && matches(inv, before, 4));

        inv.clearContent();
        inv.setItem(0, new ItemStack(Items.BAMBOO, 2));
        var alternate = craft(companion, "{\"item\":\"stick\"}");
        check("automatic-craftable-alternative", alternate.ok() && count(inv, Items.STICK) == 1
                && alternate.data().get("recipe").getAsString().equals("minecraft:stick_from_bamboo_item"));

        cakeIngredients(inv);
        var cake = craft(companion, "{\"item\":\"cake\"}");
        check("recipe-remainders-conserved", cake.ok() && count(inv, Items.CAKE) == 1
                && count(inv, Items.BUCKET) == 3 && count(inv, Items.MILK_BUCKET) == 0
                && count(inv, Items.SUGAR) == 0 && count(inv, Items.WHEAT) == 0 && count(inv, Items.EGG) == 0);

        fill(inv);
        inv.setItem(0, new ItemStack(Items.COBBLESTONE, 64));
        inv.setItem(1, new ItemStack(Items.STICK, 64));
        before = snapshot(inv);
        var full = craft(companion, "{\"item\":\"stone_pickaxe\"}");
        check("full-output-denied-atomic", !full.ok() && full.feedback().startsWith("INVENTORY_FULL:")
                && matches(inv, before, 4));
        inv.setItem(0, new ItemStack(Items.OAK_LOG));
        check("full-but-consumption-frees-output-slot", craft(companion, "{\"item\":\"oak_planks\"}").ok()
                && count(inv, Items.OAK_PLANKS) == 4 && count(inv, Items.OAK_LOG) == 0);

        fill(inv);
        inv.setItem(0, new ItemStack(Items.OAK_PLANKS, 64));
        inv.setItem(1, new ItemStack(Items.STICK, 60));
        before = snapshot(inv);
        var laterOverflow = craft(companion, "{\"item\":\"stick\",\"count\":5,\"recipe\":\"minecraft:stick\"}");
        check("later-output-overflow-rolls-back-all", !laterOverflow.ok()
                && laterOverflow.feedback().startsWith("INVENTORY_FULL:") && matches(inv, before, 4));
        var merge = craft(companion, "{\"item\":\"stick\",\"recipe\":\"minecraft:stick\"}");
        check("component-aware-output-merge", merge.ok() && count(inv, Items.STICK) == 64
                && count(inv, Items.OAK_PLANKS) == 62);
        inv.getItem(1).set(DataComponents.CUSTOM_NAME, Component.literal("different-components"));
        inv.getItem(1).setCount(60);
        before = snapshot(inv);
        var noMerge = craft(companion, "{\"item\":\"stick\",\"recipe\":\"minecraft:stick\"}");
        check("different-components-not-merged", !noMerge.ok()
                && noMerge.feedback().startsWith("INVENTORY_FULL:") && matches(inv, before, 4));

        fill(inv);
        inv.setItem(0, new ItemStack(Items.HONEY_BOTTLE, 16));
        before = snapshot(inv);
        var noRemainderRoom = craft(companion, "{\"item\":\"honey_block\"}");
        check("remainder-overflow-rolls-back", !noRemainderRoom.ok()
                && noRemainderRoom.feedback().startsWith("INVENTORY_FULL:") && matches(inv, before, 4));
        inv.clearContent();
        inv.setItem(0, new ItemStack(Items.OAK_LOG, 3));
        inv.setItem(1, new ItemStack(Items.OAK_PLANKS, 64));
        inv.setItem(2, new ItemStack(Items.OAK_PLANKS, 62));
        check("merge-before-empty-slot", craft(companion, "{\"item\":\"oak_planks\"}").ok()
                && inv.getItem(2).getCount() == 64 && inv.getItem(3).is(Items.OAK_PLANKS)
                && inv.getItem(3).getCount() == 2);

        before = snapshot(inv);
        for (String args : List.of("{\"item\":\"not_a_registered_item\"}", "{\"item\":\"air\"}",
                "{\"item\":\"iron_ingot\",\"recipe\":\"minecraft:iron_ingot_from_smelting_raw_iron\"}",
                "{\"item\":\"oak_planks\",\"recipe\":\"minecraft:stick\"}",
                "{\"item\":\"oak_planks\",\"recipe\":\"mcbot_test:absent\"}",
                "{\"item\":\"stick\",\"count\":1.5}", "{\"item\":\"stick\",\"count\":65}",
                "{\"item\":\"stick\",\"query\":\"true\"}", "{\"item\":null}")) {
            var denied = craft(companion, args);
            if (denied.ok() || !matches(inv, before, 4)) throw new IllegalStateException("Rejected call mutated inventory");
        }
        check("invalid-and-noncraft-recipes-denied", true);

        inv.clearContent();
        inv.setItem(40, new ItemStack(Items.OAK_PLANKS, 2));
        before = snapshot(inv);
        var offhand = craft(companion, "{\"item\":\"stick\",\"recipe\":\"minecraft:stick\",\"query\":true}");
        check("equipment-excluded-and-shortage-query-readonly", offhand.ok()
                && !offhand.data().get("can_craft").getAsBoolean()
                && offhand.feedback().contains("MISSING_MATERIALS:") && matches(inv, before, 4));
        inv.setItem(0, new ItemStack(Items.OAK_LOG, 3));
        before = snapshot(inv);
        JsonObject waitArgs = new JsonObject();
        waitArgs.addProperty("seconds", 60);
        var wait = McbotMod.toolRegistry().get("wait").runAsync(companion, waitArgs, McbotMod.scheduler());
        try {
            var busy = craft(companion, "{\"item\":\"oak_planks\"}");
            var busyAsync = tool.runAsync(companion, JsonParser.parseString("{\"item\":\"oak_planks\"}")
                    .getAsJsonObject(), McbotMod.scheduler());
            check("busy-sync-and-async-denied", !wait.isDone() && !busy.ok()
                    && busy.feedback().startsWith("BUSY:") && busyAsync.isDone() && !busyAsync.join().ok()
                    && busyAsync.join().feedback().startsWith("BUSY:") && matches(inv, before, 4));
            var busyQuery = craft(companion, "{\"item\":\"oak_planks\",\"query\":true}");
            check("busy-query-stays-readonly", busyQuery.ok() && !wait.isDone() && matches(inv, before, 4));
        } finally {
            McbotMod.scheduler().cancel(companion.getUUID(), "Craft fixture finished.");
        }
        var afterCancel = tool.runAsync(companion, JsonParser.parseString("{\"item\":\"oak_planks\"}")
                .getAsJsonObject(), McbotMod.scheduler());
        check("cancel-then-craft", wait.isDone() && wait.join().feedback().startsWith("CANCELLED:")
                && afterCancel.isDone() && afterCancel.join().ok());
    }

    private static ServerTool.Result craft(CompanionPlayer companion, String args) {
        return McbotMod.toolRegistry().get("craft").run(companion, JsonParser.parseString(args).getAsJsonObject());
    }

    private static void cakeIngredients(Inventory inv) {
        inv.clearContent();
        inv.setItem(0, new ItemStack(Items.MILK_BUCKET));
        inv.setItem(9, new ItemStack(Items.MILK_BUCKET));
        inv.setItem(35, new ItemStack(Items.MILK_BUCKET));
        inv.setItem(3, new ItemStack(Items.SUGAR, 2));
        inv.setItem(12, new ItemStack(Items.WHEAT, 3));
        inv.setItem(28, new ItemStack(Items.EGG));
    }

    private static void fill(Inventory inv) {
        inv.clearContent();
        for (int slot = 0; slot < 36; slot++) inv.setItem(slot, new ItemStack(Items.STONE, 64));
    }

    private static int count(Inventory inv, Item item) {
        int count = 0;
        for (int slot = 0; slot < inv.getContainerSize(); slot++) {
            ItemStack stack = inv.getItem(slot);
            if (stack.is(item)) count += stack.getCount();
        }
        return count;
    }

    private static List<ItemStack> snapshot(Inventory inv) {
        List<ItemStack> stacks = new ArrayList<>();
        for (int slot = 0; slot < inv.getContainerSize(); slot++) stacks.add(inv.getItem(slot).copy());
        return stacks;
    }

    private static boolean matches(Inventory inv, List<ItemStack> expected, int selected) {
        if (inv.getSelectedSlot() != selected || inv.getContainerSize() != expected.size()) return false;
        for (int slot = 0; slot < expected.size(); slot++) {
            if (!ItemStack.matches(inv.getItem(slot), expected.get(slot))) return false;
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
        McbotMod.LOG.info("[craft-test] {}={}", scenario, passed);
        if (!passed) throw new IllegalStateException(scenario);
    }
}
