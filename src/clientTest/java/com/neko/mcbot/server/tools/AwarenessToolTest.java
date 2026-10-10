package com.neko.mcbot.server.tools;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.neko.mcbot.server.ServerTool;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.EntityEquipment;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import java.util.HashMap;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class AwarenessToolTest {
    @BeforeAll static void boot() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }
    private static JsonObject json(String value) { return JsonParser.parseString(value).getAsJsonObject(); }
    private static final class Scan implements FindResourceTool.Access {
        final Map<BlockPos, BlockState> cells = new HashMap<>();
        boolean loaded = true;
        int reads;
        @Override public BlockPos center() { return new BlockPos(12, 80, -6); }
        @Override public boolean readable(BlockPos pos) { return loaded; }
        @Override public BlockState state(BlockPos pos) {
            assertTrue(loaded, "must never read unknown cells");
            reads++;
            return cells.getOrDefault(pos, Blocks.AIR.defaultBlockState());
        }
    }
    @Test void findsWoodAndVariantsNearestFirstWithAbsoluteTargets() {
        var scan = new Scan();
        scan.cells.put(scan.center().offset(2, 0, 0), Blocks.OAK_LOG.defaultBlockState());
        scan.cells.put(scan.center().offset(1, 0, 0), Blocks.BIRCH_LOG.defaultBlockState());
        var result = FindResourceTool.execute(json("{\"targets\":[\"oak_log\",\"birch_log\"],\"r\":3}"), scan);
        assertTrue(result.ok());
        var targets = result.data().getAsJsonArray("targets");
        assertEquals(2, targets.size());
        assertEquals("minecraft:birch_log", targets.get(0).getAsJsonObject().get("block").getAsString());
        assertEquals(13, targets.get(0).getAsJsonObject().get("x").getAsInt());
        assertEquals(-6, targets.get(0).getAsJsonObject().get("z").getAsInt());
        assertFalse(result.data().get("no_targets").getAsBoolean());
    }
    @Test void unknownCellsNeverReadAndEmptyDoesNotClaimAbsence() {
        var scan = new Scan();
        scan.loaded = false;
        var result = FindResourceTool.execute(json("{\"targets\":[\"oak_log\"]}"), scan);
        assertEquals(0, scan.reads);
        assertTrue(result.data().get("unknown").getAsInt() > 0);
        assertTrue(result.data().get("no_targets").getAsBoolean());
        assertTrue(result.feedback().contains("未找到不等于"));
    }
    @Test void sampleAndResultBudgetsAreReportedHonestly() {
        var scan = new Scan();
        for (int x = -3; x <= 3; x++) scan.cells.put(scan.center().offset(x, 0, 0), Blocks.OAK_LOG.defaultBlockState());
        for (int y = 1; y <= 3; y++) for (int x = -3; x <= 3; x++)
            scan.cells.put(scan.center().offset(x, y, 0), Blocks.OAK_LOG.defaultBlockState());
        var result = FindResourceTool.execute(json("{\"targets\":[\"oak_log\"],\"r\":16}"), scan);
        assertEquals(4096, scan.reads);
        assertTrue(result.data().get("truncated").getAsBoolean());
        assertEquals(16, result.data().getAsJsonArray("targets").size());
        assertTrue(result.data().get("results_truncated").getAsBoolean());
    }
    @Test void invalidNamesTagsTypesAndExtraKeysFailBeforeAnyRead() {
        var scan = new Scan();
        for (String args : new String[]{"{}", "{\"targets\":[]}", "{\"targets\":[4]}", "{\"targets\":[\"unknown:block\"]}",
                "{\"targets\":[\"#unknown:tag\"]}", "{\"targets\":[\"air\"]}", "{\"targets\":[\"oak_log\"],\"r\":\"4\"}",
                "{\"targets\":[\"oak_log\"],\"r\":1.5}", "{\"targets\":[\"oak_log\"],\"r\":17}",
                "{\"targets\":[\"oak_log\"],\"x\":1}"}) assertFalse(FindResourceTool.execute(json(args), scan).ok(), args);
        assertEquals(0, scan.reads);
    }
    @Test void scansAreFreshAndDoNotKeepOldTargets() {
        var scan = new Scan();
        scan.cells.put(scan.center(), Blocks.OAK_LOG.defaultBlockState());
        var args = json("{\"targets\":[\"oak_log\"],\"r\":1}");
        assertFalse(FindResourceTool.execute(args, scan).data().get("no_targets").getAsBoolean());
        scan.cells.clear();
        assertTrue(FindResourceTool.execute(args, scan).data().get("no_targets").getAsBoolean());
    }

    private static final class Storage implements InspectBlockTool.Access {
        final SimpleContainer slots = new SimpleContainer(27);
        boolean locked, valid = true, pendingLoot;
        ServerTool.Result failure;
        int reads;
        @Override public ServerTool.Result guard(BlockPos pos) { return failure; }
        @Override public InspectBlockTool.Target target(BlockPos pos) {
            reads++;
            return new InspectBlockTool.Target("minecraft:chest", slots, locked, valid, pendingLoot);
        }
    }
    @Test void slotPagesReportActualContentsAndPreserveComponents() {
        var storage = new Storage();
        var tool = new ItemStack(Items.IRON_PICKAXE);
        tool.setDamageValue(15);
        tool.set(DataComponents.CUSTOM_NAME, Component.literal("private-name".repeat(1000)));
        storage.slots.setItem(26, tool);
        var first = InspectBlockTool.execute(json("{\"x\":1,\"y\":80,\"z\":2}"), storage);
        assertEquals(24, first.data().getAsJsonArray("slots").size());
        assertEquals(24, first.data().get("next_offset").getAsInt());
        var second = InspectBlockTool.execute(json("{\"x\":1,\"y\":80,\"z\":2,\"offset\":24}"), storage);
        assertEquals(3, second.data().getAsJsonArray("slots").size());
        assertEquals(-1, second.data().get("next_offset").getAsInt());
        assertTrue(second.feedback().contains("235/250"));
        assertFalse(second.feedback().contains("private-name"));
        assertTrue(ItemStack.matches(tool, storage.slots.getItem(26)));
    }
    @Test void lockedInvalidAndPendingLootRefuseWithoutReadingSlots() {
        for (int mode = 0; mode < 3; mode++) {
            var storage = new Storage();
            storage.locked = mode == 0; storage.valid = mode != 1; storage.pendingLoot = mode == 2;
            var result = InspectBlockTool.execute(json("{\"x\":1,\"y\":80,\"z\":2}"), storage);
            assertFalse(result.ok());
            assertNull(result.data());
        }
    }
    @Test void guardFailureNeverResolvesTargetAndBadCoordinatesNeverPassGuard() {
        var storage = new Storage();
        storage.failure = new ServerTool.Result(false, "TARGET_LOST:unloaded", null);
        assertFalse(InspectBlockTool.execute(json("{\"x\":1,\"y\":80,\"z\":2}"), storage).ok());
        assertEquals(0, storage.reads);
        for (String args : new String[]{"{\"x\":\"1\",\"y\":80,\"z\":2}", "{\"x\":1,\"y\":80,\"z\":2,\"offset\":1.5}",
                "{\"x\":1,\"y\":80,\"z\":2,\"offset\":-1}", "{\"x\":1,\"y\":80,\"z\":2,\"offset\":1024}"})
            assertFalse(InspectBlockTool.execute(json(args), storage).ok());
    }
    @Test void readsNonContainerAndRejectsBeyondLastSlot() {
        var result = InspectBlockTool.execute(json("{\"x\":1,\"y\":80,\"z\":2}"), new InspectBlockTool.Access() {
            @Override public ServerTool.Result guard(BlockPos pos) { return null; }
            @Override public InspectBlockTool.Target target(BlockPos pos) {
                return new InspectBlockTool.Target("minecraft:stone", null, false, true, false);
            }
        });
        assertTrue(result.ok());
        assertFalse(result.data().get("container").getAsBoolean());
        assertFalse(InspectBlockTool.execute(json("{\"x\":1,\"y\":80,\"z\":2,\"offset\":27}"), new Storage()).ok());
    }
    @Test void detailedStatusShowsFreshInventoryAndServerTaskCounters() {
        var inventory = new Inventory(null, new EntityEquipment());
        inventory.setItem(4, new ItemStack(Items.OAK_LOG, 7));
        inventory.setSelectedSlot(4);
        var task = json("{\"busy\":true,\"elapsed_ticks\":8,\"cap_ticks\":400,\"progress\":{\"strikes\":2}}");
        var body = new StatusTool.Body(1.25, 80, -2.75, "minecraft:overworld", 20, 20, false, 42);
        var result = StatusTool.report(json("{\"details\":true}"), body, inventory, task);
        assertTrue(result.feedback().contains("oak_log"));
        assertTrue(result.feedback().contains("\"strikes\":2"));
        assertEquals(-3, result.data().get("z").getAsInt());
        assertEquals(4, result.data().getAsJsonObject("inventory").get("selected_slot").getAsInt());
        inventory.setItem(4, ItemStack.EMPTY);
        var next = StatusTool.report(json("{\"details\":true}"), body, inventory, task);
        assertFalse(next.feedback().contains("oak_log"));
        assertEquals(0, next.data().get("slots_used").getAsInt());
    }
    @Test void statusPreservesLegacyFieldsAndRejectsInvalidDetails() {
        var inventory = new Inventory(null, new EntityEquipment());
        var body = new StatusTool.Body(0, 0, 0, "minecraft:overworld", 20, 20, false, 1);
        var task = json("{\"busy\":false}");
        var result = StatusTool.report(json("{}"), body, inventory, task);
        for (String key : new String[]{"x","y","z","dimension","hp","food","slots_used","held","on_fire"})
            assertTrue(result.data().has(key));
        assertFalse(result.data().has("inventory"));
        assertFalse(StatusTool.report(json("{\"details\":\"true\"}"), body, inventory, task).ok());
    }
}
