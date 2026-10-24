package com.neko.mcbot.server;

import com.neko.mcbot.path.DigAStar;
import com.neko.mcbot.path.TerrainEdits;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.HashSet;
import java.util.Set;
import java.util.function.Predicate;

/** Shared read-only contact and collision planning for state-free cubes through earlier route edits. */
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
        // All admitted materials are ordinary, state-free full cubes; special/native contexts stay on assessAt.
        var state = item.getBlock().defaultBlockState();
        if (state.getBlock().getClass() != net.minecraft.world.level.block.Block.class
                || !state.getValues().isEmpty() || !state.isCollisionShapeFullBlock(view, target)) return false;
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
            if (mayUse.test(face) && BlockAim.placementContact(aimed, clicked, face, reach).ok()) return true;
        }
        return false;
    }

    private static Set<BlockPos> positions(Set<Long> cells) {
        var result = new HashSet<BlockPos>();
        for (long cell : cells)
            result.add(new BlockPos(DigAStar.unpackX(cell), DigAStar.unpackY(cell), DigAStar.unpackZ(cell)));
        return result;
    }
}
