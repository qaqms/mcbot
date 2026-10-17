package com.neko.mcbot.server;

import com.neko.mcbot.mixin.TerrainVersionMixin;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.junit.jupiter.api.Test;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import static org.junit.jupiter.api.Assertions.*;

class TerrainVersionTest {
    @Test void individualWitnessDetectsSameBlockReplacementWithoutInvalidatingNeighbors() throws Exception {
        BlockActionFixtures.bootstrap();
        var hook=new TerrainVersionMixin() { };
        var callback=TerrainVersionMixin.class.getDeclaredMethod("mcbot$changed",
                BlockPos.class,BlockState.class,int.class,CallbackInfoReturnable.class);
        callback.setAccessible(true);
        var watched=hook.mcbot$watchCell(BlockPos.ZERO);
        var neighbor=hook.mcbot$watchCell(BlockPos.ZERO.east());
        assertSame(watched,hook.mcbot$watchCell(BlockPos.ZERO));
        callback.invoke(hook,BlockPos.ZERO,Blocks.AIR.defaultBlockState(),3,
                new CallbackInfoReturnable<>("test",false,Blocks.STONE.defaultBlockState()));
        callback.invoke(hook,BlockPos.ZERO,Blocks.STONE.defaultBlockState(),3,
                new CallbackInfoReturnable<>("test",false,Blocks.AIR.defaultBlockState()));
        assertFalse(watched.intact()); assertTrue(neighbor.intact());
        var fresh=hook.mcbot$watchCell(BlockPos.ZERO);
        assertNotSame(watched,fresh); assertTrue(fresh.intact());
        hook.mcbot$invalidateTerrain();
        assertFalse(neighbor.intact()); assertFalse(fresh.intact());
    }
    @Test void liveWitnessCapacityNeverEvictsOldProvenanceAndUninstrumentedChunksFailClosed() {
        var hook=new TerrainVersionMixin() { };
        var kept=new java.util.ArrayList<com.neko.mcbot.building.CellWitness>();
        for(int i=0;i<64;i++) kept.add(hook.mcbot$watchCell(new BlockPos(i,90,0)));
        assertTrue(kept.stream().allMatch(java.util.Objects::nonNull));
        assertNull(hook.mcbot$watchCell(new BlockPos(65,90,0)));
        assertTrue(kept.getFirst().intact());
        var unknown=new TerrainVersion() {
            public String mcbot$terrainStamp() { return "unknown"; }
            public void mcbot$invalidateTerrain() { }
        };
        assertNull(unknown.mcbot$watchCell(BlockPos.ZERO));
    }
    @Test void actualMappedMutationContractAndHookOnlyCountAnActualStateChange() throws Exception {
        BlockActionFixtures.bootstrap();
        assertEquals(BlockState.class, LevelChunk.class.getDeclaredMethod("setBlockState",
                BlockPos.class, BlockState.class, int.class).getReturnType());
        var hook = new TerrainVersionMixin() { };
        var callback = TerrainVersionMixin.class.getDeclaredMethod("mcbot$changed",
                BlockPos.class, BlockState.class, int.class, CallbackInfoReturnable.class);
        callback.setAccessible(true);
        var first = hook.mcbot$terrainStamp();
        callback.invoke(hook, BlockPos.ZERO, Blocks.STONE.defaultBlockState(), 3,
                new CallbackInfoReturnable<BlockState>("test", false, null));
        assertEquals(first, hook.mcbot$terrainStamp());
        callback.invoke(hook, BlockPos.ZERO, Blocks.STONE.defaultBlockState(), 3,
                new CallbackInfoReturnable<>("test", false, Blocks.STONE.defaultBlockState()));
        assertEquals(first, hook.mcbot$terrainStamp());
        callback.invoke(hook, BlockPos.ZERO, Blocks.STONE.defaultBlockState(), 3,
                new CallbackInfoReturnable<>("test", false, Blocks.DIRT.defaultBlockState()));
        assertNotEquals(first, hook.mcbot$terrainStamp());
        assertNotEquals(hook.mcbot$terrainStamp(), new TerrainVersionMixin() { }.mcbot$terrainStamp());
        var beforeUnload = hook.mcbot$terrainStamp();
        hook.mcbot$invalidateTerrain();
        assertNotEquals(beforeUnload, hook.mcbot$terrainStamp(), "same chunk object reused after unload");
    }
}
