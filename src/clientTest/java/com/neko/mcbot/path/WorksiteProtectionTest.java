package com.neko.mcbot.path;

import com.neko.mcbot.server.BlockActionFixtures;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class WorksiteProtectionTest {
    @BeforeAll static void registriesOnly() { BlockActionFixtures.bootstrap(); }

    @Test void workstationsWithoutBlockEntitiesAreProtectedAlongsideExistingFacilities() {
        for (Block block : List.of(Blocks.CRAFTING_TABLE, Blocks.CARTOGRAPHY_TABLE,
                Blocks.FLETCHING_TABLE, Blocks.SMITHING_TABLE, Blocks.LOOM,
                Blocks.ANVIL, Blocks.GRINDSTONE, Blocks.STONECUTTER, Blocks.RED_BED)) {
            assertTrue(LevelDigSampler.isSacred(block.defaultBlockState()), block.toString());
        }
        assertFalse(LevelDigSampler.isSacred(Blocks.STONE.defaultBlockState()));
        assertFalse(LevelDigSampler.isSacred(Blocks.DIRT.defaultBlockState()));
    }

    @Test void navigationCannotClearItsWorkTargetButCanStillChooseAnAdjacentStance() {
        var target = new BlockPos(1, 64, 0);
        DigSampler cells = new DigSampler() {
            @Override public boolean passable(int x, int y, int z) { return x != 1 || y != 64; }
            @Override public double digSeconds(int x, int y, int z) {
                return LevelDigSampler.preserved(target, new BlockPos(x, y, z)) ? INFEASIBLE : 1;
            }
            @Override public boolean support(int x, int y, int z) { return y == 63; }
            @Override public boolean placeable(int x, int y, int z) { return false; }
            @Override public double placeCost(int x, int y, int z) { return 1; }
            @Override public boolean inBounds(int x, int y, int z) {
                return x >= 0 && x <= 1 && y >= 63 && y <= 65 && z == 0;
            }
            @Override public int maxPlaces() { return 0; }
        };
        var start = new BlockPos(0, 64, 0);
        var throughTarget = PathTask.searchFor(cells, start, target, true, false);
        assertTrue(throughTarget.advance(8000, Long.MAX_VALUE));
        assertNotNull(throughTarget.failure());
        var adjacent = PathTask.searchFor(cells, start, start, true, false);
        assertTrue(adjacent.advance(8000, Long.MAX_VALUE));
        assertNull(adjacent.failure());

        var stale = List.of(new DigAStar.Step(1, 64, 0,
                List.of(DigAStar.pack(1, 64, 0)), List.of(), DigAStar.ACT_DIG));
        assertFalse(PathTask.editsFeasible(stale, cells),
                "a previously cached clearance must not bypass the current work target");
    }

    @Test void preservationIncludesTheSupportButNotUnrelatedNeighboringTerrain() {
        var target = new BlockPos(9, 80, -3);
        assertTrue(LevelDigSampler.preserved(target, target));
        assertTrue(LevelDigSampler.preserved(target, target.below()));
        assertFalse(LevelDigSampler.preserved(target, target.above()));
        assertFalse(LevelDigSampler.preserved(target, target.east()));
        assertFalse(LevelDigSampler.preserved(null, target));
    }

    @Test void sharedLiveGuardProtectsFacilitiesTargetsFloorsAndUnknownWithoutProtectingOrdinaryTerrain() {
        var workstation = new BlockPos(3, 70, 0);
        var target = new BlockPos(6, 70, 0);
        var targets = new WorkTargets();
        targets.bind("task"); targets.add(target);
        java.util.function.Predicate<BlockPos> guard = cell -> com.neko.mcbot.server.WorksiteProtection.protectedCell(
                cell, p -> p.getX() != 9, p -> p.equals(workstation)
                        ? Blocks.CRAFTING_TABLE.defaultBlockState() : Blocks.STONE.defaultBlockState(),
                p -> false, targets::protects);
        assertTrue(guard.test(workstation));
        assertTrue(guard.test(workstation.below()));
        assertTrue(guard.test(target));
        assertTrue(guard.test(target.below()));
        assertTrue(guard.test(new BlockPos(9, 70, 0)));
        assertFalse(guard.test(target.east()));
    }
}
