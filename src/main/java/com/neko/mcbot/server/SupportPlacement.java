package com.neko.mcbot.server;

import com.neko.mcbot.path.DigAStar;
import com.neko.mcbot.path.TerrainEdits;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.HashSet;
import java.util.Set;
import java.util.function.Predicate;
import java.util.function.Function;

/** Read-only contact and collision planning for predictable full blocks through earlier route edits. */
public final class SupportPlacement {
    private SupportPlacement() { }

    public static boolean canPlan(BlockGetter world, BlockAim.Access sight, BlockPos target,
                                  Vec3 eye, AABB body, ItemStack material, double reach, TerrainEdits edits,
                                  Predicate<BlockPos> permitted, Predicate<Direction> mayUse,
                                  Predicate<AABB> entityClear) {
        return PathMaterials.allowed(material) && canPlanBlock(world, sight, target, eye, body, material,
                reach, edits, permitted, mayUse, entityClear);
    }

    /** A state-free construction block uses the same edited collision and real contact checks as a route support. */
    public static boolean canPlanBlock(BlockGetter world, BlockAim.Access sight, BlockPos target,
                                      Vec3 eye, AABB body, ItemStack material, double reach, TerrainEdits edits,
                                      Predicate<BlockPos> permitted, Predicate<Direction> mayUse,
                                      Predicate<AABB> entityClear) {
        if (!(material.getItem() instanceof BlockItem item) || !item.getBlock().defaultBlockState().getValues().isEmpty())
            return false;
        return canPlanBlock(world, sight, target, eye, body, material, reach, edits, permitted, mayUse,
                entityClear, hit -> ((BlockItem) material.getItem()).getBlock().defaultBlockState(), state -> true);
    }

    /** The native predictor may select a pillar axis; the caller still checks the frozen drawing. */
    public static boolean canPlanBlock(BlockGetter world, BlockAim.Access sight, BlockPos target,
                                      Vec3 eye, AABB body, ItemStack material, double reach, TerrainEdits edits,
                                      Predicate<BlockPos> permitted, Predicate<Direction> mayUse,
                                      Predicate<AABB> entityClear, Function<BlockHitResult, BlockState> prediction,
                                      Predicate<BlockState> admissible) {
        if (material.isEmpty() || !ItemStack.isSameItemSameComponents(material, new ItemStack(material.getItem()))
                || !(material.getItem() instanceof BlockItem item)
                || item.getClass() != BlockItem.class || body.intersects(new AABB(target))) return false;
        for (var near : BlockPos.betweenClosed(target.offset(-1, -1, -1), target.offset(1, 1, 1)))
            if (!sight.loaded(near) || !permitted.test(near)) return false;
        var removed = positions(edits.removed());
        var placed = positions(edits.placed());
        var view = BlockAim.editedWorld(world, removed, placed);
        var current = view.getBlockState(target);
        if (!current.isAir() || !current.getFluidState().isEmpty()) return false;
        if (!predictable(item)) return false;
        for (var near : BlockPos.betweenClosed(BlockPos.containing(body.minX, body.minY, body.minZ).offset(-1, -1, -1),
                BlockPos.containing(body.maxX, body.maxY, body.maxZ).offset(1, 1, 1)))
            if (!sight.loaded(near)) return false;
        for (var cell : BlockPos.betweenClosed(BlockPos.containing(body.minX, body.minY, body.minZ),
                BlockPos.containing(body.maxX, body.maxY, body.maxZ))) {
            var boxes = StandingGeometry.collision(view.getBlockState(cell), view, cell);
            if (boxes == null || boxes.stream().anyMatch(box -> box.move(cell).intersects(body))) return false;
        }
        if (!entityClear.test(body) || !entityClear.test(new AABB(target))) return false;
        var aimed = NavigationWork.view(sight, eye, removed, placed);
        for (var face : Direction.values()) {
            var clicked = target.relative(face.getOpposite());
            if (!view.getBlockState(clicked).getFluidState().isEmpty()) continue;
            if (!mayUse.test(face)) continue;
            var contact = BlockAim.placementContact(aimed, clicked, face, reach);
            if (!contact.ok()) continue;
            var state = prediction.apply(contact.hit());
            if (state != null && state.getBlock() == item.getBlock()
                    && state.isCollisionShapeFullBlock(view, target) && admissible.test(state)) return true;
        }
        return false;
    }

    public static boolean predictable(BlockItem item) {
        var block = item.getBlock();
        return block.getClass() == net.minecraft.world.level.block.Block.class
                && block.defaultBlockState().getValues().isEmpty()
                || block.getClass() == net.minecraft.world.level.block.RotatedPillarBlock.class
                || block.getClass() == net.minecraft.world.level.block.TransparentBlock.class
                && block.defaultBlockState().getValues().isEmpty();
    }

    private static Set<BlockPos> positions(Set<Long> cells) {
        var result = new HashSet<BlockPos>();
        for (long cell : cells)
            result.add(new BlockPos(DigAStar.unpackX(cell), DigAStar.unpackY(cell), DigAStar.unpackZ(cell)));
        return result;
    }
}
