package com.neko.mcbot.path;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DigCostSafetyTest {
    private DigSampler corridor(double cost) {
        return new DigSampler() {
            @Override public boolean passable(int x, int y, int z) { return x != 1 && z == 0; }
            @Override public double digSeconds(int x, int y, int z) { return cost; }
            @Override public boolean support(int x, int y, int z) { return y == 0; }
            @Override public boolean placeable(int x, int y, int z) { return false; }
            @Override public double placeCost(int x, int y, int z) { return 1; }
            @Override public boolean inBounds(int x, int y, int z) {
                return x >= 0 && x <= 4 && y == 1 && z == 0;
            }
            @Override public int maxPlaces() { return 0; }
        };
    }

    private DigAStar search(DigSampler sampler) {
        DigAStar search = new DigAStar(sampler, 0, 1, 0, 4, 1, 0, 100, 8);
        for (int i = 0; i < 10 && !search.advance(100); i++) { }
        return search;
    }

    @Test void infeasibleOrInvalidCostsCannotPlanThroughTheOnlyBlockingColumn() {
        for (double cost : new double[]{DigSampler.INFEASIBLE, Double.POSITIVE_INFINITY,
                Double.NEGATIVE_INFINITY, Double.NaN, -1}) {
            for (boolean memo : new boolean[]{false, true}) {
                DigSampler sampler = memo ? new MemoDigSampler(corridor(cost)) : corridor(cost);
                var result = search(sampler);
                assertNotNull(result.failure(), "cost=" + cost + ", memo=" + memo);
                assertNull(result.path());
            }
        }
    }

    @Test void zeroAndPositiveCostsRemainUsableForPlainAndMemoizedPlanning() {
        for (double cost : new double[]{0, .05, 2}) {
            for (boolean memo : new boolean[]{false, true}) {
                DigSampler sampler = memo ? new MemoDigSampler(corridor(cost)) : corridor(cost);
                var result = search(sampler);
                assertNull(result.failure());
                assertTrue(result.path().stream().anyMatch(step -> !step.dig().isEmpty()));
            }
        }
    }
}
