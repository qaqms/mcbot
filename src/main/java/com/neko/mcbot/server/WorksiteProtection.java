package com.neko.mcbot.server;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import java.util.function.Function;
import java.util.function.Predicate;

/** One dry-land edit guard for facilities, selected work cells and their direct floor support. */
public final class WorksiteProtection {
    private WorksiteProtection() { }

    public static boolean protectedCell(BlockPos cell, Predicate<BlockPos> known,
            Function<BlockPos, BlockState> state, Predicate<BlockPos> blockEntity,
            Predicate<BlockPos> selected) {
        if (selected.test(cell) || !known.test(cell) || !known.test(cell.above())) return true;
        return facility(state.apply(cell), blockEntity.test(cell))
                || facility(state.apply(cell.above()), blockEntity.test(cell.above()));
    }

    public static boolean facility(BlockState state, boolean blockEntity) {
        return blockEntity || com.neko.mcbot.path.LevelDigSampler.protectedFacility(state);
    }
}
