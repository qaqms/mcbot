package com.neko.mcbot.server;

import com.neko.mcbot.task.TickTask;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ConsumptionWorkTest {
    @BeforeAll static void bootstrap() { BlockActionFixtures.bootstrap(); }

    static class Access implements ConsumptionWork.Access {
        final Inventory inventory = BlockActionFixtures.inventory();
        int food = 5, starts, ticks, stops, finishAt = 3;
        boolean using, mainHand = true, accepted = true, throwAfter, pickup, remainder;
        ServerTool.Result denied;
        Access() {
            inventory.setItem(0, new ItemStack(Items.IRON_PICKAXE));
            inventory.setItem(13, new ItemStack(Items.BREAD, 4));
        }
        @Override public ServerTool.Result guard() { return denied; }
        @Override public Inventory inventory() { return inventory; }
        @Override public int food() { return food; }
        @Override public float health() { return 11; }
        @Override public boolean using() { return using; }
        @Override public boolean usingMainHand() { return mainHand; }
        @Override public boolean start() { starts++; using = accepted; return accepted; }
        @Override public void advanceNativeUse() {
            if (++ticks != finishAt) return;
            inventory.getSelectedItem().shrink(1);
            if (remainder) inventory.setItem(inventory.getSelectedSlot(), new ItemStack(Items.BOWL));
            if (pickup) inventory.setItem(9, new ItemStack(Items.BREAD));
            food = Math.min(20, food + 5);
            using = false;
            if (throwAfter) throw new IllegalStateException("native boundary");
        }
        @Override public void stop() { stops++; using = false; }
    }
    private static ServerTool.Result finish(ConsumptionWork work) {
        for (int i = 0; i < 130; i++) {
            var progress = work.tickSafe(null);
            if (progress instanceof TickTask.Progress.Done done) return done.result();
        }
        throw new AssertionError("unbounded consumption");
    }

    @Test void oneNativeUseReportsActualFoodAndRestoresOriginalWholeToolWithoutReplay() {
        var a = new Access();
        var old = a.inventory.getSelectedItem().copy();
        var work = new ConsumptionWork(a, 13);
        var result = finish(work);
        assertTrue(result.ok(), result.feedback());
        assertEquals(1, result.data().get("consumed_count").getAsInt());
        assertEquals(10, result.data().get("food").getAsInt());
        assertEquals(11, result.data().get("hp").getAsInt());
        assertFalse(result.data().get("goal_satisfied").getAsBoolean());
        assertTrue(result.data().get("hand_restored").getAsBoolean());
        assertTrue(ItemStack.matches(old, a.inventory.getSelectedItem()));
        assertEquals(3, a.inventory.getItem(13).getCount());
        work.tickSafe(null);
        work.onFinish();
        assertEquals(1, a.starts);
        assertEquals(3, a.ticks);
    }

    @Test void hotbarSelectionRestoresTheOldSelectionAndKeepsTheRemainderInItsRealSlot() {
        var a = new Access();
        a.inventory.setItem(2, new ItemStack(Items.BREAD, 2));
        var result = finish(new ConsumptionWork(a, 2));
        assertTrue(result.ok());
        assertEquals(0, a.inventory.getSelectedSlot());
        assertEquals(1, a.inventory.getItem(2).getCount());
        assertTrue(a.inventory.getSelectedItem().is(Items.IRON_PICKAXE));
    }

    @Test void nativeBowlIsMovedBackToTheFoodSourceWithoutAnEmptySlotOrItemLoss() {
        var a = new Access();
        for (int i = 1; i < 36; i++) a.inventory.setItem(i, new ItemStack(Items.STONE, 64));
        a.inventory.setItem(13, new ItemStack(Items.MUSHROOM_STEW));
        a.remainder = true;
        var result = finish(new ConsumptionWork(a, 13));
        assertTrue(result.ok(), result.feedback());
        assertTrue(a.inventory.getItem(13).is(Items.BOWL));
        assertTrue(a.inventory.getSelectedItem().is(Items.IRON_PICKAXE));
        assertEquals(64, a.inventory.getItem(12).getCount());
    }

    @Test void nativeFullnessRefusalDoesNotPretendToConsumeOrTryAgain() {
        var a = new Access();
        a.food = 20; a.accepted = false;
        var work = new ConsumptionWork(a, 13);
        var result = finish(work);
        assertFalse(result.ok());
        assertEquals(0, result.data().get("consumed_count").getAsInt());
        assertEquals(4, a.inventory.getItem(13).getCount());
        work.tickSafe(null);
        assertEquals(1, a.starts);
        assertEquals(0, a.ticks);
    }

    @Test void authorityLossBeforeNextNativeTickStopsAndNeverResumesTheSameConsumption() {
        var a = new Access();
        var work = new ConsumptionWork(a, 13);
        work.tickSafe(null);
        a.denied = new ServerTool.Result(false, "DENIED:lost body or lease", null);
        var result = finish(work);
        assertFalse(result.ok());
        assertTrue(result.feedback().startsWith("DENIED:"));
        assertFalse(a.using);
        assertEquals(0, a.ticks);
        a.denied = null;
        work.tickSafe(null);
        assertEquals(1, a.starts);
    }

    @Test void preemptionAndCancellationRestoreWithoutRestartingOrCallingNativeCompletion() {
        for (boolean preempt : new boolean[]{false, true}) {
            var a = new Access();
            var work = new ConsumptionWork(a, 13);
            work.tickSafe(null);
            if (preempt) work.onSuspend(); else work.onAbort();
            var result = work.interruptedResult(new ServerTool.Result(false, "CANCELLED:test", null));
            assertEquals(0, result.data().get("consumed_count").getAsInt());
            assertFalse(a.using);
            assertTrue(a.inventory.getSelectedItem().is(Items.IRON_PICKAXE));
            assertEquals(0, a.ticks);
            assertNotNull(work.onResume(null));
            assertFalse(work.onResume(null).ok());
        }
    }

    @Test void changedHandComponentsOrSelectedSlotCannotBeConsumedOrOverwrittenByCleanup() {
        for (boolean select : new boolean[]{false, true}) {
            var a = new Access();
            var work = new ConsumptionWork(a, 13);
            work.tickSafe(null);
            if (select) a.inventory.setSelectedSlot(3);
            else a.inventory.getSelectedItem().set(DataComponents.CUSTOM_NAME,
                    net.minecraft.network.chat.Component.literal("changed"));
            var before = a.inventory.getSelectedItem().copy();
            var result = finish(work);
            assertFalse(result.ok());
            assertFalse(result.data().get("hand_restored").getAsBoolean());
            assertTrue(ItemStack.matches(before, a.inventory.getSelectedItem()));
            assertEquals(0, a.ticks);
        }
    }

    @Test void aChangedAcceptedSourceNeverSelectsAnotherFood() {
        var a = new Access();
        var work = new ConsumptionWork(a, 13, a.inventory.getItem(13));
        a.inventory.setItem(13, new ItemStack(Items.COOKED_BEEF));
        assertFalse(finish(work).ok());
        assertEquals(0, a.starts);
        assertTrue(a.inventory.getSelectedItem().is(Items.IRON_PICKAXE));
    }

    @Test void exceptionAfterActualConsumptionKeepsTheEffectAndNativeRemainder() {
        var a = new Access();
        a.throwAfter = true;
        var work = new ConsumptionWork(a, 13);
        work.tickSafe(null);
        work.tickSafe(null);
        work.tickSafe(null);
        assertThrows(IllegalStateException.class, () -> work.tickSafe(null));
        work.onAbort();
        var result = work.interruptedResult(new ServerTool.Result(false, "INTERNAL:native exception", null));
        assertEquals(1, result.data().get("consumed_count").getAsInt());
        assertEquals(10, result.data().get("food").getAsInt());
        assertEquals(3, a.inventory.getItem(13).getCount());
    }

    @Test void concurrentPickupCannotTurnAnUnprovenInventoryDifferenceIntoSuccess() {
        var a = new Access();
        a.pickup = true;
        var result = finish(new ConsumptionWork(a, 13));
        assertFalse(result.ok());
        assertEquals(0, result.data().get("consumed_count").getAsInt());
        assertEquals(10, result.data().get("food").getAsInt());
    }

    @Test void aNativeUseThatNeverFinishesCannotExtendItsTickAccount() {
        var a = new Access();
        a.finishAt = 1000;
        var work = new ConsumptionWork(a, 13);
        var result = finish(work);
        assertFalse(result.ok());
        assertTrue(result.feedback().startsWith("TIMEOUT:"));
        assertEquals(100, a.ticks);
        assertFalse(a.using);
        work.tickSafe(null);
        assertEquals(100, a.ticks);
    }

    @Test void explicitConsumptionAndAutomaticFoodShareNativeComponentsButDifferentPolicy() {
        assertTrue(ConsumptionWork.supported(new ItemStack(Items.BREAD)));
        assertTrue(ConsumptionWork.supported(new ItemStack(Items.MUSHROOM_STEW)));
        assertFalse(ConsumptionWork.supported(new ItemStack(Items.MILK_BUCKET)));
        assertFalse(ConsumptionWork.supported(new ItemStack(Items.POTION)));
        assertTrue(MaintenanceDriver.automaticFood(new ItemStack(Items.BREAD)));
        assertFalse(MaintenanceDriver.automaticFood(new ItemStack(Items.ROTTEN_FLESH)));
        assertFalse(MaintenanceDriver.automaticFood(new ItemStack(Items.GOLDEN_APPLE)));
        assertFalse(MaintenanceDriver.automaticFood(new ItemStack(Items.CHORUS_FRUIT)));
        assertFalse(MaintenanceDriver.automaticFood(new ItemStack(Items.SUSPICIOUS_STEW)));
        var changed = new ItemStack(Items.BREAD);
        changed.set(DataComponents.CUSTOM_NAME, net.minecraft.network.chat.Component.literal("reserved"));
        assertFalse(MaintenanceDriver.automaticFood(changed));
    }
}
