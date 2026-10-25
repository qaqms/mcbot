package com.neko.mcbot.building;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.BedPart;

import java.util.List;
import java.util.Set;
import java.util.function.Function;

/** Both native object cells remain requirements; only the door lower half or bed foot issues work. */
public final class BuildObjects {
    private BuildObjects() { }

    public static boolean door(BlockState state) {
        return state != null && state.getBlock().getClass() == DoorBlock.class
                && BuiltInRegistries.BLOCK.getKey(state.getBlock()).getNamespace().equals("minecraft");
    }
    public static boolean upper(BlockState state) {
        return door(state) && state.getValue(DoorBlock.HALF) == DoubleBlockHalf.UPPER;
    }
    public static boolean bed(BlockState state) {
        return state!=null && state.getBlock().getClass()==BedBlock.class
                && BuiltInRegistries.BLOCK.getKey(state.getBlock()).getNamespace().equals("minecraft");
    }
    public static boolean paired(BlockState state) { return door(state) || bed(state); }
    public static boolean secondary(BlockState state) {
        return upper(state) || bed(state) && state.getValue(BedBlock.PART)==BedPart.HEAD;
    }
    public static BlockPos other(BlockPos pos, BlockState state) {
        if (bed(state)) return pos.relative(secondary(state)
                ? state.getValue(BedBlock.FACING).getOpposite() : state.getValue(BedBlock.FACING));
        return upper(state) ? pos.below() : pos.above();
    }
    public static List<BlockPos> footprint(BlockPos pos, BlockState state) {
        return paired(state) ? List.of(pos, other(pos, state)) : List.of(pos);
    }
    public static BlockState upperState(BlockState lower) {
        return lower.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER);
    }
    public static BlockState partnerState(BlockState state) {
        return bed(state) ? state.setValue(BedBlock.PART,secondary(state) ? BedPart.FOOT : BedPart.HEAD)
                : state.setValue(DoorBlock.HALF,secondary(state) ? DoubleBlockHalf.LOWER : DoubleBlockHalf.UPPER);
    }
    public static Set<String> requiredProperties(BlockState state) {
        return bed(state) ? Set.of("part","facing") : Set.of("half","facing");
    }
    public static BuildTarget.Cell at(BuildTarget target, BlockPos pos) {
        return target.index().get(pos);
    }
    public static String problem(BuildTarget target, BuildTarget.Cell cell) {
        if (!paired(cell.authored())) return "";
        String kind=bed(cell.authored()) ? "bed" : "door";
        if (!cell.properties().containsAll(requiredProperties(cell.authored())))
            return kind+"_requires_explicit_"+(bed(cell.authored()) ? "part_facing" : "half_facing");
        var partner = at(target, other(cell.pos(), cell.desired()));
        if (partner == null || partner.authored().getBlock() != cell.authored().getBlock()
                || !partner.properties().containsAll(requiredProperties(partner.authored()))
                || secondary(partner.authored()) == secondary(cell.authored())
                || !other(partner.pos(),partner.desired()).equals(cell.pos()))
            return kind+"_pair_missing_or_inconsistent";
        var first = partnerState(cell.desired());
        var second = partner.desired();
        for (String name : cell.properties()) {
            // An omitted hinge is chosen by the native context, not by the state's default value.
            if (!partner.properties().contains(name)) continue;
            var property = first.getBlock().getStateDefinition().getProperty(name);
            if (!first.getValue(property).equals(second.getValue(property)))
                return kind+"_pair_state_mismatch";
        }
        for (String name : partner.properties()) {
            if (!cell.properties().contains(name)) continue;
            var property = first.getBlock().getStateDefinition().getProperty(name);
            if (!first.getValue(property).equals(second.getValue(property)))
                return kind+"_pair_state_mismatch";
        }
        return "";
    }
    public static boolean matches(BuildTarget target, BuildTarget.Cell cell, Function<BlockPos, BlockState> state) {
        if (!cell.matches(state.apply(cell.pos()))) return false;
        if (!paired(cell.authored())) return true;
        var partner = at(target, other(cell.pos(), cell.desired()));
        return problem(target, cell).isEmpty() && partner.matches(state.apply(partner.pos()));
    }
}
