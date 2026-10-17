package com.neko.mcbot.building;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;

import java.util.List;
import java.util.Set;
import java.util.function.Function;

/** Native two-cell footprints; both cells remain explicit requirements, only the lower cell issues work. */
public final class BuildObjects {
    private BuildObjects() { }

    public static boolean door(BlockState state) {
        return state != null && state.getBlock().getClass() == DoorBlock.class
                && BuiltInRegistries.BLOCK.getKey(state.getBlock()).getNamespace().equals("minecraft");
    }
    public static boolean upper(BlockState state) {
        return door(state) && state.getValue(DoorBlock.HALF) == DoubleBlockHalf.UPPER;
    }
    public static BlockPos other(BlockPos pos, BlockState state) {
        return upper(state) ? pos.below() : pos.above();
    }
    public static List<BlockPos> footprint(BlockPos pos, BlockState state) {
        return door(state) ? List.of(pos, other(pos, state)) : List.of(pos);
    }
    public static BlockState upperState(BlockState lower) {
        return lower.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER);
    }
    public static BuildTarget.Cell at(BuildTarget target, BlockPos pos) {
        return target.cells().stream().filter(c -> c.pos().equals(pos)).findFirst().orElse(null);
    }
    public static String problem(BuildTarget target, BuildTarget.Cell cell) {
        if (!door(cell.authored())) return "";
        if (!cell.properties().containsAll(Set.of("half", "facing", "hinge")))
            return "door_requires_explicit_half_facing_hinge";
        var partner = at(target, other(cell.pos(), cell.desired()));
        if (partner == null || partner.authored().getBlock() != cell.authored().getBlock()
                || !partner.properties().equals(cell.properties()) || upper(partner.authored()) == upper(cell.authored()))
            return "door_pair_missing_or_inconsistent";
        var first = cell.desired().setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER);
        var second = partner.desired().setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER);
        for (String name : cell.properties()) {
            var property = first.getBlock().getStateDefinition().getProperty(name);
            if (!first.getValue(property).equals(second.getValue(property)))
                return "door_pair_state_mismatch";
        }
        return "";
    }
    public static boolean matches(BuildTarget target, BuildTarget.Cell cell, Function<BlockPos, BlockState> state) {
        if (!cell.matches(state.apply(cell.pos()))) return false;
        if (!door(cell.authored())) return true;
        var partner = at(target, other(cell.pos(), cell.desired()));
        return problem(target, cell).isEmpty() && partner.matches(state.apply(partner.pos()));
    }
}
