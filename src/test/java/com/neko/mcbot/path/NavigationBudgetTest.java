package com.neko.mcbot.path;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class NavigationBudgetTest {
    @Test void oldDefaultSnapshotMigratesToPerSearchPolicyWithoutLosingDiagnostics() {
        var old = new NavigationBudget(8000, 32768, NavigationBudget.NANOS, 2).snapshot();
        old.remove("per_search");
        old.addProperty("replans", 2);
        var restored = NavigationBudget.restore(old);
        assertTrue(restored.perSearch());
        assertTrue(restored.takeReplan());
        assertEquals(3, restored.replans());
        assertNotNull(search(restored));
    }
    @Test void snapshotPreservesCustomCapsAndAlreadySpentSearchAndReplanBudget() {
        var original = new NavigationBudget(5, 1000, NavigationBudget.NANOS, 1);
        var search = search(original); search.advance(2); original.observe(search);
        assertTrue(original.takeReplan());
        var restored = NavigationBudget.restore(original.snapshot());
        assertEquals(original.nodes(), restored.nodes()); assertEquals(original.records(), restored.records());
        assertEquals(original.nanos(), restored.nanos()); assertFalse(restored.takeReplan());
        var next = search(restored); while (!next.advance(20)) { }
        restored.observe(next); assertTrue(restored.exhausted()); assertEquals(5, restored.nodes());
    }
    private static final DigSampler FLOOR = new DigSampler() {
        @Override public boolean passable(int x, int y, int z) { return y >= 0; }
        @Override public boolean support(int x, int y, int z) { return y == -1; }
        @Override public double digSeconds(int x, int y, int z) { return INFEASIBLE; }
        @Override public boolean placeable(int x, int y, int z) { return false; }
        @Override public double placeCost(int x, int y, int z) { return INFEASIBLE; }
        @Override public int maxPlaces() { return 0; }
        @Override public boolean inBounds(int x, int y, int z) {
            return y >= -1 && y <= 2 && Math.abs(x) <= 32 && Math.abs(z) <= 32;
        }
    };
    private static DigAStar search(NavigationBudget budget) {
        return budget.search(FLOOR, 0, 0, 0, 30, 0, 0, NavigationGoal.position(30, 0, 0, 0));
    }

    @Test void replacementChargesOldSearchAndNeverReceivesFreshNodeAllowance() {
        var budget = new NavigationBudget(5, 1000, NavigationBudget.NANOS, 2);
        var first = search(budget);
        assertFalse(first.advance(2));
        var second = search(budget);
        assertEquals(2, budget.nodes(), "replacement must charge even an unobserved prior slice");
        while (!second.advance(30)) { }
        budget.observe(second);
        assertEquals("NODES", second.budgetKind());
        assertEquals(5, budget.nodes(), "the rejected sixth node is not an expanded node");
        assertNull(search(budget));
        assertFalse(budget.takeReplan());
    }

    @Test void observationsAreIdempotentAndRetiredOrForeignSearchCannotBeReactivated() {
        var budget = new NavigationBudget();
        var first = search(budget);
        first.advance(1);
        budget.observe(first);
        int records = budget.records(), nodes = budget.nodes();
        long nanos = budget.nanos();
        budget.observe(first);
        assertEquals(records, budget.records());
        assertEquals(nodes, budget.nodes());
        assertEquals(nanos, budget.nanos());
        var next = search(budget);
        assertEquals(records + 1, budget.records(), "every replacement owns another initial record");
        assertThrows(IllegalArgumentException.class, () -> budget.observe(first));
        assertThrows(IllegalArgumentException.class, () -> new NavigationBudget().observe(next));
    }

    @Test void storageIncludesDiscardedRoutesAndCannotBeReopened() {
        var budget = new NavigationBudget(1000, 3, NavigationBudget.NANOS, 2);
        assertNotNull(search(budget));
        assertNotNull(search(budget));
        var last = search(budget);
        assertNotNull(last);
        while (!last.advance(20)) { }
        budget.observe(last);
        assertEquals(3, budget.records());
        assertEquals("STORAGE", last.budgetKind());
        assertNull(search(budget));
    }

    @Test void timeAndReplacementQuotaSurviveDiscardedSearches() {
        var timed = new NavigationBudget(1000, 1000, 1, 2);
        var route = search(timed);
        assertTrue(route.advance(100));
        timed.observe(route);
        assertEquals("TIME", route.budgetKind());
        assertTrue(timed.nanos() > 0);
        assertNull(search(timed));
        var budget = new NavigationBudget();
        assertTrue(budget.takeReplan());
        assertNotNull(search(budget));
        assertTrue(budget.takeReplan());
        assertNotNull(search(budget));
        assertTrue(budget.takeReplan());
        assertEquals(3, budget.replans());
        var restored = NavigationBudget.restore(budget.snapshot());
        for (int i = 0; i < 30; i++) assertTrue(restored.takeReplan());
        assertFalse(restored.exhausted());
    }
}
