package com.neko.mcbot.server;

import com.neko.mcbot.body.CompanionPlayer;
import com.neko.mcbot.path.DigAStar;
import com.neko.mcbot.path.NavigationGeometry;
import com.neko.mcbot.path.TerrainEdits;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Shared dry-land geometry. Native collision resolves each bounded sweep; no body is moved here. */
public final class StandingGeometry implements NavigationGeometry {
    public interface Access {
        boolean known(BlockPos pos);
        /** Null means unsafe or unsupported, not empty space. Boxes are local to the cell. */
        List<AABB> boxes(BlockPos pos);
    }
    private record Piece(long cell, AABB box) { }
    private static final double EPS = 1e-7;
    private static final int MAX_CELLS = 64, MAX_PIECES = 256, WALK_SLICES = 16;
    private final Access world;
    private final double width, height, step;
    private final java.util.function.DoubleSupplier safeFall;

    public StandingGeometry(Access world, double width, double height, double step) {
        this(world, width, height, step, () -> net.minecraft.world.entity.Entity.BASE_SAFE_FALL_DISTANCE);
    }

    public StandingGeometry(Access world, double width, double height, double step,
                            java.util.function.DoubleSupplier safeFall) {
        this.world = world;
        this.width = width;
        this.height = height;
        this.step = Math.max(0, Math.min(.6, step));
        this.safeFall = java.util.Objects.requireNonNull(safeFall);
        if (!(width > 0 && width < 1 && height > 0 && height <= 2))
            throw new IllegalArgumentException("unsupported body dimensions");
    }

    public static StandingGeometry forPlayer(CompanionPlayer body) {
        return forPlayer(body, pos -> true);
    }

    public static StandingGeometry forPlayer(CompanionPlayer body, java.util.function.Predicate<BlockPos> allowed) {
        var level = body.level();
        return new StandingGeometry(new Access() {
            @Override public boolean known(BlockPos pos) {
                return allowed.test(pos) && level.isInWorldBounds(pos) && level.getWorldBorder().isWithinBounds(pos)
                        && level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4) != null;
            }
            @Override public List<AABB> boxes(BlockPos pos) {
                return collision(level.getBlockState(pos), level, pos);
            }
        }, body.getBbWidth(), body.getBbHeight(), body.maxUpStep(),
                () -> body.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.SAFE_FALL_DISTANCE));
    }

    @Override public double maxDrop() {
        // Higher falls need damage and special landing semantics, beyond ordinary dry walking.
        return Math.max(0, Math.min(net.minecraft.world.entity.Entity.BASE_SAFE_FALL_DISTANCE, safeFall.getAsDouble()));
    }

    public static List<AABB> collision(BlockState state, BlockGetter level, BlockPos pos) {
        // These require a different locomotion mode or a hypothetical entity context.
        if (!state.getFluidState().isEmpty() || state.getBlock().hasDynamicShape()
                || state.is(Blocks.POWDER_SNOW) || state.is(Blocks.SCAFFOLDING)
                || state.is(Blocks.FIRE) || state.is(Blocks.SOUL_FIRE) || state.is(Blocks.COBWEB)
                || state.is(Blocks.SWEET_BERRY_BUSH) || state.is(Blocks.CACTUS)
                || state.is(Blocks.POINTED_DRIPSTONE)
                || state.is(Blocks.MAGMA_BLOCK) || state.is(Blocks.WITHER_ROSE)) return null;
        return state.getCollisionShape(level, pos).toAabbs();
    }

    public static BlockPos feetCell(CompanionPlayer body) {
        return BlockPos.containing(body.getX(), NavigationGeometry.cell(body.getY()), body.getZ());
    }

    public AABB body(int x, double feet, int z) {
        return bodyAt(x + .5, feet, z + .5);
    }

    private AABB bodyAt(double x, double feet, double z) {
        double half = width / 2;
        return new AABB(x - half, feet, z - half, x + half, feet + height, z + half);
    }

    /** Defer the cell that closes the last dry exit; the same check applies to projected work stances. */
    public boolean preservesExit(int x, double feet, int z,
                                 java.util.Map<BlockPos, BlockState> proposed, TerrainEdits edits) {
        if (proposed.keySet().stream().noneMatch(pos -> Math.abs(pos.getX()-x)<=1
                && Math.abs(pos.getZ()-z)<=1 && pos.getY()>=feet-maxDrop()-1 && pos.getY()<=feet+3)) return true;
        var after = new StandingGeometry(new Access() {
            @Override public boolean known(BlockPos pos) { return world.known(pos); }
            @Override public List<AABB> boxes(BlockPos pos) {
                var state = proposed.get(pos);
                return state == null ? world.boxes(pos)
                        : collision(state, net.minecraft.world.level.EmptyBlockGetter.INSTANCE, pos);
            }
        }, width, height, step, safeFall);
        var removed = new java.util.ArrayList<>(edits.removed());
        var placed = new java.util.ArrayList<>(edits.placed());
        for (var pos : proposed.keySet()) {
            long key = DigAStar.pack(pos.getX(),pos.getY(),pos.getZ());
            removed.remove(key);
            placed.remove(key);
        }
        var overlay = new TerrainEdits(java.util.Set.copyOf(removed),java.util.Set.copyOf(placed));
        return after.exit(x,feet,z,overlay) || !exit(x,feet,z,edits);
    }

    private boolean exit(int x, double feet, int z, TerrainEdits edits) {
        int y = NavigationGeometry.cell(feet);
        var at = standing(x,y,z,edits);
        if (at == null || !same(at.height(),feet)) return false;
        for (int dx=-1;dx<=1;dx++) for (int dz=-1;dz<=1;dz++) {
            if (dx==0 && dz==0) continue;
            int depth = dx==0 || dz==0 ? (int) Math.ceil(maxDrop()) : 1;
            for (int dy=1;dy>=-depth;dy--) {
                var dest = standing(x+dx,y+dy,z+dz,edits);
                if (dest != null && dx!=0 && dz!=0 && Math.abs(feet-dest.height())>1+1e-5) continue;
                if (dest != null && motion(x,feet,z,x+dx,dest.height(),z+dz,edits)!=Motion.BLOCKED) return true;
            }
        }
        return false;
    }

    /** Recover only onto a column already under the real sole, through a supported, clear walk. */
    public BlockPos departure(double x, double feet, double z, java.util.function.Predicate<BlockPos> allowed) {
        if (!Double.isFinite(x) || !Double.isFinite(feet) || !Double.isFinite(z)) return null;
        double half = width / 2;
        int y = NavigationGeometry.cell(feet);
        BlockPos nearest = null;
        double distance = Double.POSITIVE_INFINITY;
        for (int cx = (int) Math.floor(x - half + EPS); cx <= (int) Math.floor(x + half - EPS); cx++)
            for (int cz = (int) Math.floor(z - half + EPS); cz <= (int) Math.floor(z + half - EPS); cz++) {
                var pos = new BlockPos(cx, y, cz);
                double d = Math.hypot(cx + .5 - x, cz + .5 - z);
                if (d < distance && allowed.test(pos) && canAlign(x, feet, z, pos)) {
                    nearest = pos;
                    distance = d;
                }
            }
        return nearest;
    }

    /** Actual pose to a centered stance; no edits, jump, height change or unobserved support. */
    public boolean canAlign(double x, double feet, double z, BlockPos destination) {
        int y = NavigationGeometry.cell(feet);
        if (!Double.isFinite(x) || !Double.isFinite(feet) || !Double.isFinite(z)
                || destination.getY() != y || Math.abs(destination.getX() + .5 - x) > 1
                || Math.abs(destination.getZ() + .5 - z) > 1) return false;
        var foot = standing(destination.getX(), y, destination.getZ(), TerrainEdits.EMPTY);
        if (foot == null || !same(foot.height(), feet)) return false;
        var start = bodyAt(x, feet, z);
        var end = body(destination.getX(), foot.height(), destination.getZ());
        var pieces = gather(start.minmax(end), TerrainEdits.EMPTY);
        if (pieces == null) return false;
        var across = start.minmax(end).deflate(EPS);
        if (pieces.stream().anyMatch(piece -> piece.box().intersects(across))) return false;
        double dx = (end.getCenter().x - x) / WALK_SLICES;
        double dz = (end.getCenter().z - z) / WALK_SLICES;
        for (int i = 0; i <= WALK_SLICES; i++) {
            var sole = start.move(dx * i, 0, dz * i);
            if (pieces.stream().noneMatch(piece -> same(piece.box().maxY, feet)
                    && overlaps(sole, piece.box()))) return false;
        }
        return true;
    }

    /** A flat running corridor needs lateral clearance and support under the entire sole. */
    public boolean runningLane(int fx, double feet, int fz, int tx, int tz) {
        if (!Double.isFinite(feet) || Math.abs(tx - fx) > 1 || Math.abs(tz - fz) > 1) return false;
        var start = body(fx, feet, fz).inflate(.1, 0, .1);
        var end = body(tx, feet, tz).inflate(.1, 0, .1);
        var pieces = gather(start.minmax(end), TerrainEdits.EMPTY);
        if (pieces == null) return false;
        var sweep = start.minmax(end).deflate(EPS);
        if (pieces.stream().anyMatch(piece -> piece.box().intersects(sweep))) return false;
        double half = width / 2 + .1 - EPS;
        for (int i = 0; i <= WALK_SLICES; i++) {
            double x = fx + .5 + (tx - fx) * (double) i / WALK_SLICES;
            double z = fz + .5 + (tz - fz) * (double) i / WALK_SLICES;
            for (int corner = 0; corner < 5; corner++) {
                double sx = x + (corner == 4 ? 0 : (corner & 1) == 0 ? -half : half);
                double sz = z + (corner == 4 ? 0 : (corner & 2) == 0 ? -half : half);
                boolean supported = false;
                for (var piece : pieces) {
                    var box = piece.box();
                    if (same(box.maxY, feet) && sx >= box.minX && sx <= box.maxX
                            && sz >= box.minZ && sz <= box.maxZ) {
                        supported = true;
                        break;
                    }
                }
                if (!supported) return false;
            }
        }
        return true;
    }

    @Override public Foot foot(int x, int y, int z, TerrainEdits edits) {
        double top = Double.NEGATIVE_INFINITY;
        long support = 0;
        var sole = body(x, y, z);
        for (int cy = y - 1; cy <= y; cy++) {
            var pieces = read(new BlockPos(x, cy, z), edits);
            if (pieces == null) return null;
            for (var piece : pieces) {
                var box = piece.box();
                if (overlaps(sole, box) && NavigationGeometry.cell(box.maxY) == y && box.maxY > top) {
                    top = box.maxY;
                    support = piece.cell();
                }
            }
        }
        return Double.isFinite(top) ? new Foot(top, support) : null;
    }

    public Foot standing(int x, int y, int z, TerrainEdits edits) {
        Foot foot = foot(x, y, z, edits);
        if (foot == null) return null;
        var clear = clearance(x, foot.height(), z, x, foot.height(), z, edits);
        return clear.known() && clear.blockers().isEmpty() ? foot : null;
    }

    @Override public Clearance clearance(int fx, double from, int fz, int x, double to, int z, TerrainEdits edits) {
        double transit = Math.max(from, to);
        AABB across = body(fx, transit, fz).minmax(body(x, transit, z)).deflate(EPS);
        AABB landing = body(x, to, z).minmax(body(x, transit, z)).deflate(EPS);
        // Going up must leave room to rise before entering the next column.
        AABB rising = body(fx, from, fz).minmax(body(fx, transit, fz)).deflate(EPS);
        AABB region = across.minmax(landing).minmax(rising);
        var pieces = gather(region, edits);
        if (pieces == null) return new Clearance(List.of(), "UNKNOWN_OR_UNSUPPORTED_GEOMETRY", null);
        var blockers = new ArrayList<Long>();
        for (var piece : pieces) if (piece.box().intersects(across) || piece.box().intersects(landing)
                || piece.box().intersects(rising)) {
            if (!blockers.contains(piece.cell())) blockers.add(piece.cell());
        }
        blockers.sort(Comparator.comparingInt((Long c) -> DigAStar.unpackY(c)).reversed()
                .thenComparingLong(Long::longValue));
        return new Clearance(List.copyOf(blockers), null, null);
    }

    @Override public Motion motion(int fx, double from, int fz, int x, double to, int z, TerrainEdits edits) {
        if (to - from > 1 + 1e-5 || from - to > Math.max(1, maxDrop()) + 1e-5) return Motion.BLOCKED;
        var clear = clearance(fx, from, fz, x, to, z, edits);
        if (!clear.known() || !clear.blockers().isEmpty()) return Motion.BLOCKED;
        if (x == fx && z == fz) return to > from ? Motion.JUMP : Motion.WALK;
        double high = Math.max(from, to);
        AABB region = body(fx, Math.min(from, to), fz)
                .minmax(body(x, high + 1.25, z));
        var pieces = gather(region, edits);
        if (pieces == null) return Motion.BLOCKED;
        var shapes = pieces.stream().map(p -> Shapes.create(p.box())).toList();
        AABB box = body(fx, from, fz);
        double dx = (x - fx) / (double) WALK_SLICES, dz = (z - fz) / (double) WALK_SLICES;
        boolean walked = true;
        for (int i = 0; i < WALK_SLICES; i++) {
            var moved = horizontal(box, shapes, dx, dz);
            if (!same(moved.minX - box.minX, dx) || !same(moved.minZ - box.minZ, dz)) {
                double up = Shapes.collide(Direction.Axis.Y, box.deflate(EPS), shapes, step);
                var raised = box.move(0, up, 0);
                moved = horizontal(raised, shapes, dx, dz);
                if (!same(moved.minX - box.minX, dx) || !same(moved.minZ - box.minZ, dz)) {
                    walked = false;
                    break;
                }
            }
            double down = Shapes.collide(Direction.Axis.Y, moved.deflate(EPS), shapes,
                    Math.min(from, to) - moved.minY - 1e-4);
            box = moved.move(0, down, 0);
        }
        if (walked && same(box.minY, to)) return Motion.WALK;
        // A conservative standing jump envelope. Low ceilings are rejected rather than dug implicitly.
        double apex = from + 1.25;
        if (to <= from) return Motion.BLOCKED;
        var jump = clearance(fx, from, fz, x, apex, z, edits);
        return jump.known() && jump.blockers().isEmpty() ? Motion.JUMP : Motion.BLOCKED;
    }

    private static AABB horizontal(AABB box, List<VoxelShape> shapes, double dx, double dz) {
        double x = Shapes.collide(Direction.Axis.X, box.deflate(EPS), shapes, dx);
        AABB moved = box.move(x, 0, 0);
        double z = Shapes.collide(Direction.Axis.Z, moved.deflate(EPS), shapes, dz);
        return moved.move(0, 0, z);
    }

    private List<Piece> gather(AABB region, TerrainEdits edits) {
        var result = new ArrayList<Piece>();
        int cells = 0;
        for (int x = (int) Math.floor(region.minX); x <= (int) Math.floor(region.maxX); x++)
            for (int z = (int) Math.floor(region.minZ); z <= (int) Math.floor(region.maxZ); z++)
                for (int y = (int) Math.floor(region.minY) - 1; y <= (int) Math.floor(region.maxY); y++) {
                    if (++cells > MAX_CELLS) return null;
                    var pieces = read(new BlockPos(x, y, z), edits);
                    if (pieces == null || result.size() + pieces.size() > MAX_PIECES) return null;
                    result.addAll(pieces);
                }
        return result;
    }

    private List<Piece> read(BlockPos pos, TerrainEdits edits) {
        if (!world.known(pos)) return null;
        long cell = DigAStar.pack(pos.getX(), pos.getY(), pos.getZ());
        if (edits.removed().contains(cell)) return List.of();
        List<AABB> boxes = edits.placed().contains(cell) ? List.of(new AABB(0, 0, 0, 1, 1, 1)) : world.boxes(pos);
        if (boxes == null || boxes.size() > 16) return null;
        var result = new ArrayList<Piece>(boxes.size());
        for (var box : boxes) {
            if (box.minX < 0 || box.minY < 0 || box.minZ < 0 || box.maxX > 1 || box.maxY > 1.5 || box.maxZ > 1)
                return null;
            result.add(new Piece(cell, box.move(pos)));
        }
        return result;
    }

    private static boolean overlaps(AABB a, AABB b) {
        return a.minX + EPS < b.maxX && a.maxX - EPS > b.minX
                && a.minZ + EPS < b.maxZ && a.maxZ - EPS > b.minZ;
    }
    private static boolean same(double a, double b) { return Math.abs(a - b) <= 1e-5; }
}
