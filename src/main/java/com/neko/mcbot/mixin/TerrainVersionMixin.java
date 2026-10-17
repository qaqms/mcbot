package com.neko.mcbot.mixin;

import com.neko.mcbot.server.TerrainVersion;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LevelChunk.class)
public abstract class TerrainVersionMixin implements TerrainVersion {
    @Unique private String mcbot$incarnation;
    @Unique private long mcbot$terrainRevision;

    @Override public String mcbot$terrainStamp() {
        if (mcbot$incarnation == null) mcbot$incarnation = java.util.UUID.randomUUID().toString();
        return mcbot$incarnation + ":" + mcbot$terrainRevision;
    }

    @Override public void mcbot$invalidateTerrain() {
        if (mcbot$terrainRevision == Long.MAX_VALUE) {
            mcbot$incarnation = null;
            mcbot$terrainRevision = 0;
        } else mcbot$terrainRevision++;
    }

    @Inject(method = "setBlockState", at = @At("RETURN"))
    private void mcbot$changed(BlockPos pos, BlockState state, int flags,
                              CallbackInfoReturnable<BlockState> result) {
        if (result.getReturnValue() != null && result.getReturnValue() != state) {
            mcbot$invalidateTerrain();
        }
    }
}
