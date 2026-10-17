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
    @Unique private final java.util.Map<BlockPos, java.lang.ref.WeakReference<com.neko.mcbot.building.CellWitness>>
            mcbot$watched = new java.util.HashMap<>();

    @Override public com.neko.mcbot.building.CellWitness mcbot$watchCell(BlockPos pos) {
        mcbot$watched.values().removeIf(reference -> reference.get() == null);
        var previous = mcbot$watched.get(pos);
        var witness = previous == null ? null : previous.get();
        if (witness != null && witness.intact()) return witness;
        if (previous == null && mcbot$watched.size() >= 64) return null;
        witness = new com.neko.mcbot.building.CellWitness();
        mcbot$watched.put(pos.immutable(), new java.lang.ref.WeakReference<>(witness));
        return witness;
    }

    @Override public String mcbot$terrainStamp() {
        if (mcbot$incarnation == null) mcbot$incarnation = java.util.UUID.randomUUID().toString();
        return mcbot$incarnation + ":" + mcbot$terrainRevision;
    }

    @Override public void mcbot$invalidateTerrain() {
        mcbot$watched.values().forEach(reference -> {
            var witness = reference.get();
            if (witness != null) witness.invalidate();
        });
        if (mcbot$terrainRevision == Long.MAX_VALUE) {
            mcbot$incarnation = null;
            mcbot$terrainRevision = 0;
        } else mcbot$terrainRevision++;
    }

    @Inject(method = "setBlockState", at = @At("RETURN"))
    private void mcbot$changed(BlockPos pos, BlockState state, int flags,
                              CallbackInfoReturnable<BlockState> result) {
        if (result.getReturnValue() != null && result.getReturnValue() != state) {
            // A general invalidation also invalidates witnesses; an ordinary edit affects only its cell.
            var reference = mcbot$watched.get(pos);
            var witness = reference == null ? null : reference.get();
            if (witness != null) witness.invalidate();
            if (mcbot$terrainRevision == Long.MAX_VALUE) mcbot$invalidateTerrain();
            else mcbot$terrainRevision++;
        }
    }
}
