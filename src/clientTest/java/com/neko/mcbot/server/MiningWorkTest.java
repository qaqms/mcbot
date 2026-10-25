package com.neko.mcbot.server;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MiningWorkTest {
    @BeforeAll static void bootstrap() { BlockActionFixtures.bootstrap(); }

    @Test void vanishedWorkTargetsAreReturnedWithoutSearchingOrClaimingTheirRemoval() {
        var target = new BlockPos(-284,137,-892);
        for (var block : List.of(Blocks.AIR,Blocks.CAVE_AIR,Blocks.VOID_AIR)) {
            var result = MiningWork.targetFailure(target,block.defaultBlockState());
            assertFalse(result.ok());
            assertTrue(result.feedback().startsWith("TARGET_BLOCKED:"));
            assertTrue(result.data().get("target_already_clear").getAsBoolean());
            assertFalse(result.data().get("can_work_here").getAsBoolean());
            assertFalse(result.data().get("removed").getAsBoolean());
            assertEquals(-284,result.data().getAsJsonObject("work_target").get("x").getAsInt());
        }
    }

    @Test void livingFlowerBedsAndSoilRemainNormalWorkWhileUnknownTargetsAreNotCalledClear() {
        for (var block : List.of(Blocks.DIRT,Blocks.GRASS_BLOCK,Blocks.WILDFLOWERS,Blocks.PINK_PETALS))
            assertNull(MiningWork.targetFailure(BlockPos.ZERO,block.defaultBlockState()));
        var unknown = MiningWork.targetFailure(BlockPos.ZERO,null);
        assertFalse(unknown.ok());
        assertTrue(unknown.feedback().startsWith("TARGET_LOST:"));
        assertNull(unknown.data());
    }
}
