package com.neko.mcbot.server;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.HashSet;

import static org.junit.jupiter.api.Assertions.*;

class MiningEnvironmentTest {
    @BeforeAll static void registriesOnly() { BlockActionFixtures.bootstrap(); }
    private static class World implements MiningEnvironment.Access {
        final HashMap<BlockPos, BlockState> blocks = new HashMap<>();
        final HashSet<BlockPos> unknown = new HashSet<>();
        @Override public boolean loaded(BlockPos pos) { return !unknown.contains(pos); }
        @Override public BlockState state(BlockPos pos) {
            assertFalse(unknown.contains(pos), "unknown cells cannot be read");
            return blocks.getOrDefault(pos, Blocks.STONE.defaultBlockState());
        }
    }

    @Test void allDryMiningEntrypointsCanShareFluidGravityAndUnknownRejection() {
        var world = new World();
        var target = new BlockPos(0, 63, 0);
        assertTrue(MiningEnvironment.safe(world, target));
        for (Direction side : Direction.values()) {
            BlockPos near = target.relative(side);
            for (var fluid : new net.minecraft.world.level.block.Block[]{Blocks.WATER, Blocks.LAVA}) {
                world.blocks.put(near, fluid.defaultBlockState());
                assertFalse(MiningEnvironment.safe(world, target));
            }
            world.blocks.remove(near);
            world.unknown.add(near);
            assertFalse(MiningEnvironment.safe(world, target));
            world.unknown.clear();
        }
        world.blocks.put(target.above(), Blocks.SAND.defaultBlockState());
        assertFalse(MiningEnvironment.safe(world, target));
        world.blocks.clear();
        world.blocks.put(target, Blocks.GRAVEL.defaultBlockState());
        assertFalse(MiningEnvironment.safe(world, target));
    }

    @Test void liveHazardStopsAnExistingDigAndClearsItsCrackWithoutRemovingTheTarget() {
        var access = new BlockActionFixtures.Mining();
        var mining = new BlockMining(access);
        assertNull(mining.tick());
        access.environmentSafe = false;
        var stopped = mining.tick();
        assertTrue(stopped.feedback().startsWith("ENVIRONMENT_UNSAFE:"));
        assertEquals(0, access.destroys);
        assertEquals(-1, access.cracks.getLast());
        assertSame(stopped, mining.tick());
        assertNull(MiningEnvironment.bodyFailure(false, false), "feet-only wading is not head submersion");
        assertNotNull(MiningEnvironment.bodyFailure(true, false));
        assertNotNull(MiningEnvironment.bodyFailure(false, true));
    }
}
