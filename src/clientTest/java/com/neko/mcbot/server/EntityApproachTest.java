package com.neko.mcbot.server;

import com.neko.mcbot.body.CompanionPlayer;
import com.neko.mcbot.path.NavigationBudget;
import com.neko.mcbot.task.TickTask;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class EntityApproachTest {
    private static final class Access implements EntityApproach.Access {
        Vec3 position = new Vec3(4, 0, 0);
        boolean strike, enterRange;
        int plans, advances, aborts, releases;
        NavigationBudget original;
        TickTask.Progress progress = new TickTask.Progress.Running();
        @Override public Vec3 targetPosition() { return position; }
        @Override public boolean canStrike() { return strike; }
        @Override public TickTask route(NavigationBudget budget) {
            if (original == null) original = budget;
            else assertSame(original, budget);
            plans++;
            return new TickTask() {
                @Override public Progress tick(CompanionPlayer body) { return progress; }
                @Override public void onAbort() { aborts++; }
            };
        }
        @Override public TickTask.Progress advance(TickTask route) {
            advances++;
            if (enterRange) strike = true;
            return route.tick(null);
        }
        @Override public void stopInput() { releases++; }
    }

    @Test void boundaryJitterWaitsAndLegalRangeStopsNavigationImmediately() {
        var access = new Access();
        var move = new EntityApproach(access);
        assertNull(move.tick());
        assertNull(move.tick());
        assertEquals(0, access.plans);
        access.strike = true;
        assertNull(move.tick());
        access.strike = false;
        assertNull(move.tick());
        assertNull(move.tick());
        access.enterRange = true;
        assertNull(move.tick());
        assertEquals(1, access.plans);
        assertEquals(1, access.advances);
        assertEquals(0, access.aborts);
        assertFalse(move.observation().get("moving").getAsBoolean());
        for (int i = 0; i < 20; i++) assertNull(move.tick());
        assertEquals(1, access.plans);
    }

    @Test void smallMotionAndFrequentLargeUpdatesDoNotReplanEveryTick() {
        var access = new Access();
        var move = new EntityApproach(access);
        for (int i = 0; i < 3; i++) assertNull(move.tick());
        for (int i = 0; i < 7; i++) {
            access.position = new Vec3(4 + i * .1, 0, 0);
            assertNull(move.tick());
        }
        assertEquals(1, access.plans);
        access.position = new Vec3(7, 0, 0);
        assertNull(move.tick());
        assertEquals(1, access.plans, "minimum ten execution ticks between plans");
        assertNull(move.tick());
        assertNull(move.tick());
        assertEquals(2, access.plans);
        assertEquals(1, access.original.replans());
    }

    @Test void repeatedTargetChangesDoNotHitACumulativeReplanQuota() {
        var access = new Access();
        var move = new EntityApproach(access);
        for (int i = 0; i < 3; i++) assertNull(move.tick());
        for (int replacement = 0; replacement < 8; replacement++) {
            access.position = access.position.add(3, 0, 0);
            for (int i = 0; i < 10; i++) assertNull(move.tick());
        }
        assertEquals(9, access.plans);
        access.position = access.position.add(3, 0, 0);
        for (int i = 0; i < 9; i++) assertNull(move.tick());
        assertNull(move.tick());
        assertEquals(10, access.plans);
        assertEquals(9, access.aborts);
    }

    @Test void suspensionDiscardsOldRouteButRetainsOriginalReplacementAccount() {
        var access = new Access();
        var move = new EntityApproach(access);
        for (int i = 0; i < 3; i++) assertNull(move.tick());
        for (int i = 0; i < 2; i++) {
            move.stop();
            assertNull(move.tick());
        }
        move.stop();
        assertNull(move.tick());
        assertEquals(3, access.original.replans());
        assertEquals(4, access.plans);
    }

    @Test void rangeReentryIsDistinguishedFromRetargetingWithoutBuyingMoreReplans() {
        var access = new Access();
        var move = new EntityApproach(access);
        for (int run = 0; run < 3; run++) {
            access.strike = false;
            access.enterRange = true;
            for (int tick = 0; tick < 3; tick++) assertNull(move.tick());
        }
        var observed = move.observation();
        assertEquals(1, observed.get("plans").getAsInt());
        assertEquals(3, observed.get("in_range_stops").getAsInt());
        assertEquals(0, observed.get("retargets").getAsInt());
        assertEquals(0, observed.get("replans").getAsInt());
        assertEquals(0, observed.get("budget_stops").getAsInt());
        access.strike = false;
        access.enterRange = false;
        assertNull(move.tick());
        assertNull(move.tick());
        assertNull(move.tick());
        assertEquals(0, move.observation().get("budget_stops").getAsInt());
        assertEquals(1, access.plans);
    }

    @Test void trueRouteFailureAndObsoleteArrivalHaveDifferentResults() {
        var access = new Access();
        var failed = new ServerTool.Result(false, "NO_PATH:closed", null);
        access.progress = new TickTask.Progress.Done(failed);
        var move = new EntityApproach(access);
        assertNull(move.tick());
        assertNull(move.tick());
        assertSame(failed, move.tick());
        assertSame(failed, move.tick());
        assertEquals(1, access.plans);
        access = new Access();
        access.progress = new TickTask.Progress.Done(new ServerTool.Result(true, "old stance", null));
        move = new EntityApproach(access);
        for (int i = 0; i < 5; i++) assertNull(move.tick());
        assertNull(move.tick());
        assertEquals(4, access.plans, "old snapshot arrival never counts as a strike");
    }
}
