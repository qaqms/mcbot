package com.neko.mcbot.path;

import com.neko.mcbot.server.BlockAim;
import com.neko.mcbot.server.StandingGeometry;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Owned section copies and body dimensions, with no live world or entity retained. */
public final class NavigationSnapshot implements DigSampler, BlockGetter {
    private final Map<Long, PalettedContainer<BlockState>> sections;
    private final java.util.Set<Long> loaded;
    private final int minY, height, sx, sy, sz;
    private final double minX, maxX, minZ, maxZ;
    private final PathSpec scope, outer;
    private final StandingGeometry geometry;

    record Bounds(int minY, int height, double minX, double maxX, double minZ, double maxZ) { }

    NavigationSnapshot(Bounds bounds,
                       java.util.function.BiFunction<Integer, Integer, PalettedContainer<BlockState>[]> source,
                       BlockPos start, double width, double bodyHeight, double step, PathSpec scope, PathSpec outer) {
        sx = start.getX(); sy = start.getY(); sz = start.getZ();
        minY = bounds.minY(); height = bounds.height();
        minX = bounds.minX(); maxX = bounds.maxX(); minZ = bounds.minZ(); maxZ = bounds.maxZ();
        this.scope = scope; this.outer = outer;
        var copied = new HashMap<Long, PalettedContainer<BlockState>>();
        var chunks = new java.util.HashSet<Long>();
        for (int cx = (sx - 65) >> 4; cx <= (sx + 65) >> 4; cx++)
            for (int cz = (sz - 65) >> 4; cz <= (sz + 65) >> 4; cz++) {
                var chunk = source.apply(cx, cz);
                if (chunk == null) continue;
                chunks.add(chunkKey(cx, cz));
                for (int cy = (Math.max(minY, sy - 34)) >> 4;
                     cy <= (Math.min(minY + height - 1, sy + 35)) >> 4; cy++) {
                    var section = chunk[cy - (minY >> 4)];
                    if (section != null) copied.put(DigAStar.pack(cx, cy, cz), section.copy());
                }
            }
        sections = Map.copyOf(copied);
        loaded = java.util.Set.copyOf(chunks);
        geometry = new StandingGeometry(new StandingGeometry.Access() {
            @Override public boolean known(BlockPos pos) {
                return inBounds(pos.getX(), pos.getY(), pos.getZ()) && NavigationSnapshot.this.known(pos);
            }
            @Override public List<AABB> boxes(BlockPos pos) {
                return StandingGeometry.collision(getBlockState(pos), NavigationSnapshot.this, pos);
            }
        }, width, bodyHeight, step);
    }

    /** Called on the world thread before a worker receives this value. */
    public static NavigationSnapshot capture(ServerLevel level, BlockPos start, double width, double height,
                                             double step, PathSpec scope, PathSpec outer) {
        var border = level.getWorldBorder();
        var bounds = new Bounds(level.getMinY(), level.getHeight(),
                border.getMinX(), border.getMaxX(), border.getMinZ(), border.getMaxZ());
        return new NavigationSnapshot(bounds, (cx, cz) -> {
            var chunk = level.getChunkSource().getChunkNow(cx, cz);
            if (chunk == null) return null;
            var source = chunk.getSections();
            @SuppressWarnings("unchecked")
            PalettedContainer<BlockState>[] states = new PalettedContainer[source.length];
            for (int i = 0; i < source.length; i++)
                if (!source[i].hasOnlyAir()) states[i] = source[i].getStates();
            return states;
        }, start, width, height, step, scope, outer);
    }

    private static long chunkKey(int x, int z) { return ((long) x << 32) ^ (z & 0xffffffffL); }

    private boolean known(BlockPos pos) {
        return pos.getY() >= minY && pos.getY() < minY + height
                && pos.getY() >= sy - 34 && pos.getY() <= sy + 35
                && pos.getX() >= minX && pos.getX() + 1 <= maxX
                && pos.getZ() >= minZ && pos.getZ() + 1 <= maxZ
                && loaded.contains(chunkKey(pos.getX() >> 4, pos.getZ() >> 4));
    }

    @Override public BlockState getBlockState(BlockPos pos) {
        if (!known(pos)) return Blocks.BARRIER.defaultBlockState();
        var section = sections.get(DigAStar.pack(pos.getX() >> 4, pos.getY() >> 4, pos.getZ() >> 4));
        return section == null ? Blocks.AIR.defaultBlockState()
                : section.get(pos.getX() & 15, pos.getY() & 15, pos.getZ() & 15);
    }
    @Override public BlockEntity getBlockEntity(BlockPos pos) { return null; }
    @Override public FluidState getFluidState(BlockPos pos) { return getBlockState(pos).getFluidState(); }
    @Override public int getHeight() { return height; }
    @Override public int getMinY() { return minY; }

    public StandingGeometry standing() { return geometry; }
    public BlockAim.Access sight(Vec3 eye) {
        return new BlockAim.Access() {
            @Override public Vec3 eye() { return eye; }
            @Override public boolean loaded(BlockPos pos) { return known(pos); }
            @Override public List<AABB> outline(BlockPos pos) {
                return getBlockState(pos).getShape(NavigationSnapshot.this, pos).toAabbs();
            }
            @Override public BlockHitResult trace(Vec3 from, Vec3 to) {
                return clip(new ClipContext(from, to, ClipContext.Block.OUTLINE,
                        ClipContext.Fluid.NONE, CollisionContext.empty()));
            }
            @Override public BlockHitResult traceIgnoring(Vec3 from, Vec3 to, java.util.Set<BlockPos> ignored) {
                return traceEdited(from, to, ignored, java.util.Set.of());
            }
            @Override public BlockHitResult traceEdited(Vec3 from, Vec3 to, java.util.Set<BlockPos> removed,
                                                       java.util.Set<BlockPos> placed) {
                return BlockAim.clipEdited(NavigationSnapshot.this, from, to,
                        CollisionContext.empty(), removed, placed);
            }
        };
    }

    public NavigationGoal workGoal(BlockPos target, double eyeHeight, double reach) {
        return com.neko.mcbot.server.NavigationWork.goal(sight(Vec3.ZERO), target.immutable(),
                eyeHeight, reach, false, pos -> false, geometry);
    }

    @Override public boolean known(int x, int y, int z) { return known(new BlockPos(x, y, z)); }
    @Override public boolean inBounds(int x, int y, int z) {
        return y >= minY && y < minY + height && Math.abs((long) x - sx) <= 64
                && Math.abs((long) z - sz) <= 64 && Math.abs((long) y - sy) <= 32
                && scope.contains(x, y, z) && outer.contains(x, y, z);
    }
    @Override public boolean passable(int x, int y, int z) {
        var pos = new BlockPos(x, y, z);
        var state = getBlockState(pos);
        return inBounds(x, y, z) && known(pos) && state.getFluidState().isEmpty()
                && state.getCollisionShape(this, pos).isEmpty();
    }
    @Override public boolean support(int x, int y, int z) {
        var pos = new BlockPos(x, y, z);
        var state = getBlockState(pos);
        return inBounds(x, y, z) && known(pos) && state.getFluidState().isEmpty()
                && state.isFaceSturdy(this, pos, net.minecraft.core.Direction.UP);
    }
    @Override public NavigationGeometry geometry() { return geometry; }
    @Override public boolean allowsStep(int fx, int fy, int fz, int x, int y, int z) {
        return scope.allowsStep(fx, fy, fz, x, y, z) && outer.allowsStep(fx, fy, fz, x, y, z);
    }
    @Override public double digSeconds(int x, int y, int z) { return INFEASIBLE; }
    @Override public boolean placeable(int x, int y, int z) { return false; }
    @Override public double placeCost(int x, int y, int z) { return INFEASIBLE; }
    @Override public int maxPlaces() { return 0; }
}
