package com.neko.mcbot.path;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class NavigationContinuationTest {
    private static final DigSampler FLOOR = new DigSampler() {
        @Override public boolean inBounds(int x, int y, int z) { return x >= 0 && x <= 80 && z == 0 && y >= -1 && y <= 2; }
        @Override public boolean passable(int x, int y, int z) { return y >= 0; }
        @Override public boolean support(int x, int y, int z) { return y == -1; }
        @Override public double digSeconds(int x, int y, int z) { return INFEASIBLE; }
        @Override public boolean placeable(int x, int y, int z) { return false; }
        @Override public double placeCost(int x, int y, int z) { return INFEASIBLE; }
        @Override public int maxPlaces() { return 0; }
    };

    private static DigAStar next() {
        return new DigAStar(FLOOR, 32, 0, 0, 60, 0, 0, 4000, 0,
                NavigationGoal.position(60, 0, 0, 0)).segments();
    }

    @Test void nextSegmentIsReadyBeforeCurrentWorkEndsAndSharesTheOriginalAccount() {
        var continuation = new NavigationContinuation();
        var allowance = new NavigationAllowance(60);
        var shared = new NavigationBudget();
        var current = shared.search(FLOOR, 0, 0, 0, 32, 0, 0, NavigationGoal.position(32, 0, 0, 0));
        while (!current.advance(100)) { }
        shared.observe(current);
        allowance.observe(current);
        var created = new AtomicInteger();
        int workingTicks = 0;
        for (int tick = 0; tick < 100; tick++) {
            workingTicks++;
            continuation.advance(() -> { created.incrementAndGet(); return next(); }, allowance, 1, Long.MAX_VALUE);
            shared.trackConcurrent(continuation.search());
            shared.observe(current);
        }
        assertEquals(100, workingTicks);
        assertEquals(1, created.get());
        assertFalse(continuation.pending());
        var prefetched = continuation.take(32, 0, 0);
        assertNotNull(prefetched);
        assertNull(prefetched.failure());
        long total = current.chargedNodes() + prefetched.chargedNodes();
        assertEquals(total, allowance.used());
        assertEquals(total, shared.nodes());
        shared.recordCompleted(prefetched);
        allowance.observe(current);
        allowance.observe(prefetched);
        assertEquals(total, allowance.used(), "adopting or observing either route again must not refill or double-charge");
        assertEquals(total, shared.nodes());
        assertEquals(1, continuation.adopted());
    }

    @Test void cancellationKeepsSpentWorkButCannotPublishTheSpeculativeRoute() {
        var continuation = new NavigationContinuation();
        var allowance = new NavigationAllowance(60);
        continuation.advance(NavigationContinuationTest::next, allowance, 1, Long.MAX_VALUE);
        assertTrue(continuation.pending());
        long spent = allowance.used();
        var discarded = continuation.clear();
        assertNotNull(discarded);
        allowance.observe(discarded);
        assertEquals(spent, allowance.used());
        assertNull(continuation.take(32, 0, 0));
        assertFalse(continuation.pending());
        assertEquals(1, continuation.discarded());
    }

    @Test void wrongActualLandingCannotAdoptThePredictedStart() {
        var continuation = new NavigationContinuation();
        var allowance = new NavigationAllowance(60);
        continuation.advance(NavigationContinuationTest::next, allowance, 300, Long.MAX_VALUE);
        assertNull(continuation.take(31, 0, 0));
        assertNotNull(continuation.clear());
        assertEquals(0, continuation.adopted());
    }

    @Test void seedContainsOnlyTheCurrentRoutesUnfinishedEdits() {
        long earlier = DigAStar.pack(0, 1, 0), pendingDig = DigAStar.pack(1, 1, 0);
        long pendingSupport = DigAStar.pack(2, -1, 0);
        var plan = List.of(
                new DigAStar.Step(0, 0, 0, List.of(earlier), List.of(), DigAStar.ACT_DIG),
                new DigAStar.Step(1, 0, 0, List.of(pendingDig), List.of(), DigAStar.ACT_DIG),
                new DigAStar.Step(2, 0, 0, List.of(), List.of(pendingSupport), DigAStar.ACT_BRIDGE));
        var seed = NavigationContinuation.remainingEdits(plan, 1);
        assertEquals(java.util.Set.of(pendingDig), seed.removed());
        assertEquals(java.util.Set.of(pendingSupport), seed.placed());
        assertFalse(seed.removed().contains(earlier));
    }

    @Test void emptyBudgetOrZeroSliceDoesNotCreateAnotherSearchOrChangeTheAccount() {
        var continuation = new NavigationContinuation();
        var allowance = new NavigationAllowance(60);
        continuation.advance(() -> { fail("no time slice"); return null; }, allowance, 300, 0);
        assertFalse(continuation.attempted());
        continuation.advance(() -> null, allowance, 300, Long.MAX_VALUE);
        continuation.advance(() -> { fail("one attempt per route"); return null; }, allowance, 300, Long.MAX_VALUE);
        assertEquals(0, allowance.used());
    }

    @Test void aFinishedFailureIsHandedOverOnceInsteadOfSearchingTheSameObservationAgain() {
        var continuation = new NavigationContinuation();
        var allowance = new NavigationAllowance(60);
        NavigationGoal impossible = new NavigationGoal() {
            @Override public boolean reached(int x, int y, int z) { return false; }
            @Override public double remaining(int x, int y, int z) { return 1; }
        };
        var failed = new DigAStar(FLOOR, 32, 0, 0, 60, 0, 0, 4, 0, impossible).segments();
        continuation.advance(() -> failed, allowance, 100, Long.MAX_VALUE);
        for (int tick = 0; tick < 10 && continuation.pending(); tick++)
            continuation.advance(() -> { fail("do not recreate a pending search"); return null; },
                    allowance, 100, Long.MAX_VALUE);
        assertTrue(failed.done());
        assertEquals("NODES", failed.budgetKind());
        assertSame(failed, continuation.take(32, 0, 0));
        assertEquals(4, allowance.used());
        assertNull(continuation.take(32, 0, 0));
    }
}
