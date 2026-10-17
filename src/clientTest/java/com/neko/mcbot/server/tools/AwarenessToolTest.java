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
    @Test void statusExposesSharedDefenseFactsAndExplicitIncompleteThreatScan() {
        var defense = json("{\"active\":true,\"state\":\"retreat\",\"strikes\":2,"
                + "\"threats\":{\"scan_complete\":false}}");
        var result = StatusTool.defenseReport(new ServerTool.Result(true, "body", new JsonObject()), defense);
        assertSame(defense, result.data().get("body_defense"));
        assertTrue(result.data().get("defense_active").getAsBoolean());
        assertFalse(result.data().get("threat_scan_complete").getAsBoolean());
        assertTrue(result.feedback().contains("retreat"));
        assertTrue(result.feedback().contains("不代表甩掉追兵"));
    }
    @BeforeAll static void boot() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }
    private static JsonObject json(String value) { return JsonParser.parseString(value).getAsJsonObject(); }
    private static class Scan implements FindResourceTool.Access {
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

    @Test void knownWrongToolIsReportedBeforeSpendingGeometryOrSuggestingWorkStances() {
        var scan = new Scan() {
            @Override public Boolean harvestable(BlockState state) { return false; }
            @Override public void work(JsonObject target, BlockPos pos, com.neko.mcbot.server.BlockAim.TraceBudget budget) {
                fail("no geometric evaluation is needed for a known unusable hand");
            }
            @Override public void stands(JsonObject target, BlockPos pos, com.neko.mcbot.server.BlockAim.TraceBudget budget) {
                fail("must not suggest a route that cannot harvest its target");
            }
        };
        scan.cells.put(scan.center().offset(2, -1, 0), Blocks.IRON_ORE.defaultBlockState());
        var result = FindResourceTool.execute(json("{\"targets\":[\"iron_ore\"],\"r\":4,\"work\":true}"), scan);
        assertTrue(result.ok());
        var target = result.data().getAsJsonArray("targets").get(0).getAsJsonObject();
        assertFalse(target.get("harvestable_with_hand").getAsBoolean());
        assertTrue(target.get("work_failure").getAsString().startsWith("WRONG_TOOL:"));
        assertTrue(target.getAsJsonArray("stand_positions").isEmpty());
        assertEquals(0, result.data().get("work_traces").getAsInt());
        assertFalse(result.data().get("work_truncated").getAsBoolean(),
                "a known tool refusal is not unknown geometry");
        assertTrue(result.feedback().contains("当前主手没有采收资格"));
    }

    @Test void manyToolRefusalsCannotHideAnEligibleCandidateWithUnknownGeometry() {
        var scan = new Scan() {
            @Override public Boolean harvestable(BlockState state) { return !state.is(Blocks.IRON_ORE); }
        };
        for (int x = -3; x <= 3; x++) for (int z = -3; z <= 3; z++)
            scan.cells.put(scan.center().offset(x, 0, z), Blocks.IRON_ORE.defaultBlockState());
        scan.cells.put(scan.center().offset(4, 0, 0), Blocks.OAK_LOG.defaultBlockState());
        var result = FindResourceTool.execute(json(
                "{\"targets\":[\"iron_ore\",\"oak_log\"],\"r\":5,\"work\":true}"), scan);
        assertEquals("minecraft:oak_log", result.data().getAsJsonArray("targets").get(0)
                .getAsJsonObject().get("block").getAsString());
        assertEquals(1, result.data().get("work_checks").getAsInt());
        assertFalse(result.data().get("work_truncated").getAsBoolean());
        assertTrue(result.data().get("results_truncated").getAsBoolean());
    }

    @Test void hazardPreflightCannotSpendWorkGeometryOrHideAnotherSafeResource() {
        var scan = new Scan() {
            @Override public Boolean dryWorkSafe(BlockPos pos) { return pos.getX() >= center().getX() + 4; }
            @Override public void work(JsonObject target, BlockPos pos, com.neko.mcbot.server.BlockAim.TraceBudget budget) {
                assertTrue(dryWorkSafe(pos), "hazards cannot consume geometry checks");
            }
            @Override public void stands(JsonObject target, BlockPos pos, com.neko.mcbot.server.BlockAim.TraceBudget budget) {
                assertTrue(dryWorkSafe(pos), "hazards cannot advertise working stances");
            }
        };
        for (int x = -3; x <= 3; x++) for (int z = -3; z <= 3; z++)
            scan.cells.put(scan.center().offset(x, 0, z), Blocks.STONE.defaultBlockState());
        scan.cells.put(scan.center().offset(4, 0, 0), Blocks.STONE.defaultBlockState());
        var result = FindResourceTool.execute(json("{\"targets\":[\"stone\"],\"r\":5,\"work\":true}"), scan);
        assertTrue(result.ok());
        var targets = result.data().getAsJsonArray("targets");
        assertTrue(targets.get(0).getAsJsonObject().get("dry_work_safe").getAsBoolean());
        assertEquals(1, result.data().get("work_checks").getAsInt());
        assertTrue(targets.get(1).getAsJsonObject().get("work_failure").getAsString().startsWith("ENVIRONMENT_UNSAFE:"));
    }

    @Test void statusExposesNativeWaterGroundingAndRecoveryAlongsideFreshInventory() {
        var body = new StatusTool.Body(.5, 60, .5, "minecraft:overworld", 20, 20, false, 25,
                false, true, true, false, 300, 300, "trapped_or_unknown");
        var report = StatusTool.report(json("{\"details\":true}"), body,
                new Inventory(null, new EntityEquipment()), json("{\"busy\":false}"));
        assertTrue(report.data().get("eyes_in_water").getAsBoolean());
        assertFalse(report.data().get("on_ground").getAsBoolean());
        assertEquals("trapped_or_unknown", report.data().get("water_recovery").getAsString());
        assertEquals(300, report.data().get("air_supply").getAsInt());
        assertTrue(report.feedback().contains("trapped_or_unknown"));
        assertTrue(report.data().has("inventory"));
    }

    @Test void productionObservationFieldsAreReadableThroughTheActualLuaBridge() throws Exception {
        var inventory = new Inventory(null, new EntityEquipment());
        inventory.setItem(4, new ItemStack(Items.BIRCH_LOG, 3));
        var scan = new Scan();
        scan.cells.put(scan.center().offset(1, 0, 0), Blocks.BIRCH_LOG.defaultBlockState());
        var calls = new java.util.ArrayList<String>();
        var program = new com.neko.mcbot.agentcore.script.BoundedProgram(
                new com.neko.mcbot.agentcore.script.BoundedProgram.Plan("""
                        local s=mc.call("status",{details=true})
                        local inv=mc.call("inventory",{})
                        local found=mc.call("find_resource",{targets={"birch_log"},r=3})
                        local t=found.data.targets[1]
                        local b=mc.call("inspect_block",{x=t.x,y=t.y,z=t.z})
                        return {x=s.data.x,z=s.data.z,slot=s.data.inventory.slots[5].slot,
                            item=inv.data.slots[5].item,count=inv.data.slots[5].count,
                            block=b.data.block,next_offset=b.data.next_offset,container=b.data.container}
                        """, 30, 8), System::currentTimeMillis, (name, text) -> {
                    calls.add(name);
                    var args = json(text);
                    ServerTool.Result receipt = switch (name) {
                        case "status" -> StatusTool.report(args,
                                new StatusTool.Body(12.8, 80, -5.2, "minecraft:overworld", 20, 20, false, 50),
                                inventory, json("{\"busy\":false}"));
                        case "inventory" -> InventoryTool.report(inventory);
                        case "find_resource" -> FindResourceTool.execute(args, scan);
                        case "inspect_block" -> InspectBlockTool.execute(args, new InspectBlockTool.Access() {
                            @Override public ServerTool.Result guard(BlockPos pos) {
                                assertEquals(new BlockPos(13, 80, -6), pos);
                                return null;
                            }
                            @Override public InspectBlockTool.Target target(BlockPos pos) {
                                return new InspectBlockTool.Target("minecraft:birch_log", null, false, true, false);
                            }
                        });
                        default -> throw new AssertionError(name);
                    };
                    return java.util.concurrent.CompletableFuture.completedFuture(
                            new com.neko.mcbot.agentcore.loop.ToolExecutor.ToolOutcome(
                                    receipt.ok(), receipt.feedback(), receipt.data()));
                }, Runnable::run, () -> { });
        try {
            var result = program.start().get(3, java.util.concurrent.TimeUnit.SECONDS);
            assertTrue(result.ok(), result.feedback());
            var returned = result.data().getAsJsonObject("returned");
            assertEquals(12, returned.get("x").getAsInt());
            assertEquals(-6, returned.get("z").getAsInt());
            assertEquals(4, returned.get("slot").getAsInt());
            assertEquals("minecraft:birch_log", returned.get("item").getAsString());
            assertEquals(3, returned.get("count").getAsInt());
            assertEquals("minecraft:birch_log", returned.get("block").getAsString());
            assertEquals(-1, returned.get("next_offset").getAsInt());
            assertFalse(returned.get("container").getAsBoolean());
            assertEquals(java.util.List.of("status", "inventory", "find_resource", "inspect_block"), calls);
            assertEquals(3, inventory.getItem(4).getCount());
            assertEquals(4, result.data().get("query_calls").getAsInt());
        } finally {
            program.stop("CANCELLED:test cleanup");
            program.terminated().get(3, java.util.concurrent.TimeUnit.SECONDS);
        }
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

    @Test void pagesCoverOuterResourcesWithoutRaisingThePerQueryReadBudget() {
        var scan = new Scan();
        scan.cells.put(scan.center().offset(15, 0, 0), Blocks.BIRCH_LOG.defaultBlockState());
        var args = json("{\"targets\":[\"birch_log\"],\"r\":16,\"frontiers\":true}");
        int total = 0, pages = 0, hits = 0;
        do {
            int before = scan.reads;
            var result = FindResourceTool.execute(args, scan);
            assertTrue(result.ok(), result.feedback());
            assertTrue(scan.reads - before <= 4096);
            total += scan.reads - before;
            pages++;
            hits += result.data().getAsJsonArray("targets").size();
            int next = result.data().get("next_offset").getAsInt();
            if (next < 0) break;
            args.addProperty("offset", next);
            args.add("scan_center", result.data().get("scan_center"));
        } while (pages < 6);
        assertEquals(5, pages);
        assertEquals(17077, total);
        assertEquals(1, hits);
    }

    @Test void pagingRejectsMovedCentersAndMalformedOffsetsBeforeWorldReads() {
        var scan = new Scan();
        for (String text : new String[]{
                "{\"targets\":[\"oak_log\"],\"r\":16,\"offset\":4096}",
                "{\"targets\":[\"oak_log\"],\"r\":16,\"offset\":1}",
                "{\"targets\":[\"oak_log\"],\"r\":16,\"frontiers\":\"true\"}",
                "{\"targets\":[\"oak_log\"],\"r\":16,\"offset\":4096,\"scan_center\":{\"x\":0,\"y\":80,\"z\":-6}}",
                "{\"targets\":[\"oak_log\"],\"r\":8,\"offset\":4096,\"scan_center\":{\"x\":12,\"y\":80,\"z\":-6}}"})
            assertFalse(FindResourceTool.execute(json(text), scan).ok(), text);
        assertEquals(0, scan.reads);
    }

    @Test void exposedWorkableResourceBeyondSixteenBuriedHitsGetsAnOutputSlot() {
        var scan = new Scan() {
            int checked;
            @Override public void work(JsonObject target, BlockPos pos) {
                checked++;
                assertTrue(checked <= FindResourceTool.MAX_WORK_CHECKS);
                boolean viable = pos.equals(center().offset(5, 0, 0));
                target.addProperty("can_work_here", viable);
                target.addProperty("can_clear_here", viable);
                target.add("stand_positions", new com.google.gson.JsonArray());
            }
        };
        for (int x = -2; x <= 2; x++) for (int y = -2; y <= 2; y++) for (int z = -2; z <= 2; z++)
            scan.cells.put(scan.center().offset(x, y, z),
                    (Math.max(Math.abs(x), Math.max(Math.abs(y), Math.abs(z))) < 2
                            ? Blocks.STONE : Blocks.DIRT).defaultBlockState());
        scan.cells.put(scan.center().offset(5, 0, 0), Blocks.STONE.defaultBlockState());
        var result = FindResourceTool.execute(json("{\"targets\":[\"stone\"],\"r\":8,\"work\":true}"), scan);
        var targets = result.data().getAsJsonArray("targets");
        assertEquals(16, targets.size());
        assertEquals(scan.center().getX() + 5, targets.get(0).getAsJsonObject().get("x").getAsInt());
        assertTrue(targets.get(0).getAsJsonObject().get("can_work_here").getAsBoolean());
        assertEquals(24, result.data().get("work_checks").getAsInt());
        assertTrue(result.data().get("work_truncated").getAsBoolean());
        assertEquals(result.data().get("cells_read").getAsInt(), scan.reads, "no extra neighbor world reads");
    }

    @Test void workFactsAndRealStancesAreVisibleInTheOrdinaryModelReceipt() {
        var scan = new Scan() {
            @Override public void work(JsonObject target, BlockPos pos) {
                target.addProperty("can_work_here", false);
                target.addProperty("can_clear_here", true);
                target.addProperty("clear_blocks", 2);
            }
            @Override public void stands(JsonObject target, BlockPos pos, com.neko.mcbot.server.BlockAim.TraceBudget budget) {
                target.add("stand_positions", JsonParser.parseString("[{\"x\":13,\"y\":80,\"z\":-6}]"));
            }
        };
        scan.cells.put(scan.center(), Blocks.STONE.defaultBlockState());
        var result = FindResourceTool.execute(json("{\"targets\":[\"stone\"],\"work\":true,\"r\":1}"), scan);
        assertTrue(result.feedback().contains("遮挡=2"));
        assertTrue(result.feedback().contains("工作站位=[{\"x\":13,\"y\":80,\"z\":-6}]"));
        assertTrue(result.feedback().contains("work_target={\"x\":12,\"y\":80,\"z\":-6}"));
        assertTrue(result.feedback().contains("exact=true"));
        assertEquals(7, scan.reads, "formatting cannot add world reads");
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

    @Test void statusReportsTheServerEffectiveModeWithoutMutatingBodyFacts() {
        var original = new ServerTool.Result(true, "body facts", json("{\"hp\":20}"));
        var owner = java.util.UUID.randomUUID();
        var companion = java.util.UUID.randomUUID();
        for (var context : java.util.List.of(
                new com.neko.mcbot.server.ActionPermissions.Context(owner, companion, "world", 1, false),
                new com.neko.mcbot.server.ActionPermissions.Context(owner, companion, "world", 2, false, true),
                new com.neko.mcbot.server.ActionPermissions.Context(owner, companion, "world", 3, true, true))) {
            var result = StatusTool.permissionReport(original, context);
            assertEquals(context.mode(), result.data().get("permission_mode").getAsString());
            assertTrue(result.feedback().startsWith("body facts"));
            assertEquals(20, result.data().get("hp").getAsInt());
        }
        assertFalse(original.data().has("permission_mode"));
        assertEquals("none", StatusTool.permissionReport(original, null).data().get("permission_mode").getAsString());
        var failure = new ServerTool.Result(false, "DENIED:invalid args", null);
        assertSame(failure, StatusTool.permissionReport(failure, null));
    }
}
