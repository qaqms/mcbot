package com.neko.mcbot.server.tools;

import com.neko.mcbot.server.BlockActionFixtures;
import com.neko.mcbot.server.BlockMining;
import com.neko.mcbot.server.ServerTool;
import com.neko.mcbot.task.TickTask;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MiningInterruptionTest {
    @BeforeAll static void bootstrap() { BlockActionFixtures.bootstrap(); }

    @Test void cancellationDuringPostRemovalDelayKeepsEffectButRemainsCancellation() {
        var access = new BlockActionFixtures.Mining();
        access.gain = 1;
        var task = new BreakBlockTool.Task(new BlockMining(access));
        assertInstanceOf(TickTask.Progress.Running.class, task.tickSafe(null));
        var stopped = task.interruptedResult(new ServerTool.Result(false, "CANCELLED:owner", null));
        task.onAbort();
        assertFalse(stopped.ok());
        assertTrue(stopped.feedback().startsWith("CANCELLED:"));
        assertTrue(stopped.data().get("removed").getAsBoolean());
        assertEquals(1, access.destroys);
    }

    @Test void safeResumeStillUsesFrozenTargetAndHandChecks() {
        var access = new BlockActionFixtures.Mining();
        var task = new BreakBlockTool.Task(new BlockMining(access));
        task.tickSafe(null);
        task.onSuspend();
        access.inventory.setSelectedSlot(1);
        assertTrue(task.onResume(null).feedback().startsWith("WRONG_TOOL:"));
        assertEquals(0, access.destroys);
        assertEquals(-1, access.cracks.getLast());
    }

    @Test void terminalAndCancelledReceiptsRefreshTheSameOriginWithoutAnotherWorldAction() {
        for (boolean cancelled : new boolean[]{false, true}) {
            var access = new BlockActionFixtures.Mining();
            access.gain = 1;
            var drop = new BlockActionFixtures.Drop(new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.COBBLESTONE, 4));
            access.action = () -> {
                access.state = net.minecraft.world.level.block.Blocks.AIR.defaultBlockState();
                access.drops.add(drop);
            };
            var task = new BreakBlockTool.Task(new BlockMining(access));
            assertInstanceOf(TickTask.Progress.Running.class, task.tickSafe(null));
            var before = new com.google.gson.JsonObject();
            before.addProperty("item", "minecraft:cobblestone");
            before.addProperty("count", 4);
            var after = new com.google.gson.JsonObject();
            after.addProperty("item", "minecraft:cobblestone");
            after.addProperty("count", 2);
            access.ledger.reduced(new com.neko.mcbot.server.DropLedger.Sample(drop.id, drop.stack, before,
                            com.neko.mcbot.server.DropLedger.Availability.LIVE),
                    new com.neko.mcbot.server.DropLedger.Sample(drop.id, drop.stack.copyWithCount(2), after,
                            com.neko.mcbot.server.DropLedger.Availability.LIVE),
                    com.neko.mcbot.server.DropLedger.Outcome.PICKUP,
                    new com.neko.mcbot.server.DropLedger.Picker(access.actor, access.body), "native_player_touch");
            ServerTool.Result result;
            if (cancelled) {
                result = task.interruptedResult(new ServerTool.Result(false, "CANCELLED:owner", null));
                task.onAbort();
                assertFalse(result.ok());
                assertTrue(result.feedback().startsWith("CANCELLED:"));
            } else {
                task.tickSafe(null); task.tickSafe(null);
                result = ((TickTask.Progress.Done) task.tickSafe(null)).result();
            }
            assertTrue(result.data().get("removed").getAsBoolean());
            var origin = result.data().getAsJsonObject("drop_accounting");
            assertEquals(2, origin.get("picked_up_self_count").getAsInt());
            assertEquals(2, origin.get("live_count").getAsInt());
            assertEquals(0, result.data().get("collected_count").getAsInt());
            assertEquals(1, access.destroys);
            assertEquals(0, access.inventory.countItem(net.minecraft.world.item.Items.COBBLESTONE),
                    "provenance facts must never mutate or replace the actual inventory");
        }
    }

    @Test void destructionExceptionKeepsAdmittedDropsUnknownAndDoesNotRetryTheAction() {
        var access = new BlockActionFixtures.Mining();
        access.gain = 1;
        access.action = () -> {
            access.state = net.minecraft.world.level.block.Blocks.AIR.defaultBlockState();
            access.drops.add(new BlockActionFixtures.Drop(new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.DIRT)));
        };
        access.afterCapture = () -> { throw new IllegalStateException("controlled native boundary"); };
        var mining = new BlockMining(access);
        var task = new BreakBlockTool.Task(mining);
        assertThrows(IllegalStateException.class, () -> task.tickSafe(null));
        var repeated = mining.tick();
        assertFalse(repeated.ok());
        assertFalse(repeated.data().get("removal_known").getAsBoolean());
        assertEquals(1, access.destroys);
        var result = task.interruptedResult(new ServerTool.Result(false, "TOOL_EXCEPTION:observed", null));
        task.onAbort();
        assertFalse(result.ok());
        assertEquals(1, result.data().getAsJsonObject("drop_accounting").get("spawned_count").getAsInt());
        assertEquals(1, access.destroys);
        assertEquals(0, result.data().get("collected_count").getAsInt());
    }
}
