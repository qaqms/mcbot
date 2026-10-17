package com.neko.mcbot.building;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.neko.mcbot.server.ServerTool;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.EmptyBlockGetter;

import java.util.*;

/** One task-selected worksite. Registration protects work; it grants no world-edit permission. */
public final class BuildSite {
    public static final int ROUTE_LIMIT = 64, TEMP_LIMIT = 16, ROUND_LIMIT = 16, EDIT_LIMIT = 128;
    public interface Access {
        BlockState state(BlockPos pos);
        boolean corridor(BlockPos feet);
        boolean facility(BlockPos pos);
        boolean occupied(BlockPos pos);
        boolean material(BuildTarget.Cell cell);
        Object chunk(BlockPos pos);
        CellWitness watch(BlockPos pos);
        default boolean passage(BlockPos pos) { return false; }
        default boolean passageComplete() { return true; }
        default boolean canMine(BlockState actual) { return true; }
    }
    private record Temporary(BlockPos pos, BlockState state, String purpose, Object chunk, CellWitness witness) { }
    private Object task, world;
    private BuildTarget target;
    private List<BlockPos> route = List.of();
    private final Map<BlockPos, Temporary> temporary = new LinkedHashMap<>();
    private final Set<String> rounds = new HashSet<>();
    private final Set<BlockPos> protectedCells = new HashSet<>();
    private final Set<BlockPos> accessCells = new HashSet<>();
    private com.neko.mcbot.path.PathSpec scope = com.neko.mcbot.path.PathSpec.DEFAULT;
    private com.neko.mcbot.path.NavigationBudget navigation = new com.neko.mcbot.path.NavigationBudget();
    private int checkpoints, edits;
    private boolean stopped;
    private BuildRecords records;
    private BuildRecords.Instance instance;

    public void bind(Object nextTask, Object nextWorld) {
        if (task != nextTask || world != nextWorld) {
            target = null; route = List.of(); temporary.clear(); rounds.clear();
            protectedCells.clear(); accessCells.clear(); scope = com.neko.mcbot.path.PathSpec.DEFAULT;
            navigation = new com.neko.mcbot.path.NavigationBudget();
            checkpoints = edits = 0; stopped = false; task = nextTask; world = nextWorld;
            records=null; instance=null;
        }
    }
    public boolean active() { return task != null && target != null; }
    public boolean same(String revision) { return active() && target.revision().equals(revision); }
    public BuildTarget target() { return target; }
    public com.neko.mcbot.path.PathSpec scope() { return scope; }
    public com.neko.mcbot.path.NavigationBudget navigation() { return navigation; }
    public String instanceId() { return instance==null ? null : instance.id(); }
    public BuildRecords.Instance instance() { return instance; }
    public void attach(BuildRecords store, BuildRecords.Instance selected) {
        if (!same(selected.target().revision()) || instance!=null && instance!=selected)
            throw new IllegalArgumentException("instance_binding");
        records=store; instance=selected;
    }
    public void recordPlacement(BlockPos pos, BlockState before, BlockState after, int consumed, Access access) {
        if (instance!=null && cell(pos)!=null) records.placed(instance,pos,before,after,consumed,access);
    }
    public void recordRemoval(BlockPos pos, BlockState before, BlockState after) {
        if (instance!=null && cell(pos)!=null) records.removed(instance,pos,before,after);
    }
    public void recordObjectPlacement(BlockPos primary, Map<BlockPos,BlockState> before,
                                       Map<BlockPos,BlockState> after, int consumed, Access access) {
        if (instance!=null && cell(primary)!=null) records.placedObject(instance,primary,before,after,consumed,access);
    }
    public void recordObjectRemoval(Map<BlockPos,BlockState> before, Map<BlockPos,BlockState> after) {
        if (instance!=null) {
            var selected=new LinkedHashMap<BlockPos,BlockState>();
            before.forEach((pos,state) -> { if (cell(pos)!=null) selected.put(pos,state); });
            if (!selected.isEmpty()) records.removedObject(instance,selected,after);
        }
    }

    public ServerTool.Result begin(BuildTarget next, List<BlockPos> accessRoute, BlockPos feet, Access access) {
        return begin(next, accessRoute, feet, active() ? scope
                : new com.neko.mcbot.path.PathSpec(false, false, false, 128, feet, 64), access);
    }
    public ServerTool.Result begin(BuildTarget next, List<BlockPos> accessRoute, BlockPos feet,
                                   com.neko.mcbot.path.PathSpec selectedScope, Access access) {
        if (task == null || stopped) return failed("DENIED:没有有效施工任务或工地已停止。");
        if (next.cells().stream().anyMatch(c -> !BuildObjects.problem(next,c).isEmpty()))
            return failed("DENIED:双格门须明确完整且一致的两半目标，不登记缺半或冲突工地。");
        if (target != null) {
            if (!same(next.revision()) || !route.equals(accessRoute) || !scope.equals(selectedScope))
                return failed("TARGET_REVISION_CHANGED:任务内工地与通道已冻结，不换图、换通道或重开账。");
            return live(access);
        }
        if (accessRoute.size() < 2 || accessRoute.size() > ROUTE_LIMIT
                || new HashSet<>(accessRoute).size() != accessRoute.size()
                || !accessRoute.contains(feet)) return failed("DENIED:须明确2-64格无重复且包含当前脚格的水平进出通道。");
        BlockPos previous = null;
        int minX=next.cells().stream().mapToInt(c -> c.pos().getX()).min().orElseThrow();
        int maxX=next.cells().stream().mapToInt(c -> c.pos().getX()).max().orElseThrow();
        int minZ=next.cells().stream().mapToInt(c -> c.pos().getZ()).min().orElseThrow();
        int maxZ=next.cells().stream().mapToInt(c -> c.pos().getZ()).max().orElseThrow();
        if (java.util.stream.Stream.of(accessRoute.getFirst(), accessRoute.getLast()).noneMatch(
                p -> p.getX()<minX || p.getX()>maxX || p.getZ()<minZ || p.getZ()>maxZ))
            return failed("DENIED:通道至少一个端点须伸出完整目标的水平包围范围，不能只保留房间内两格。");
        if (selectedScope.anchor() == null || !contains(selectedScope, feet)
                || next.cells().stream().anyMatch(c -> !contains(selectedScope, c.pos()))
                || accessRoute.stream().anyMatch(p -> !contains(selectedScope, p)))
            return failed("SCOPE_CHANGED:工地、通道或身体超出固定施工范围。");
        for (var p : accessRoute) {
            if (previous != null && (p.getY() != previous.getY() || p.distManhattan(previous) != 1))
                return failed("DENIED:当前只支持相邻水平、整格地板的明确通道。");
            if (!access.corridor(p)) return failed("ENVIRONMENT_UNSAFE:通道未确认净空、完整地板或已加载，未登记工地。");
            for (var cell : next.cells())
                if ((cell.pos().equals(p) || cell.pos().equals(p.above())) && !cell.authored().isAir())
                    return failed("DENIED:非空气目标会封住声明的进出通道，先修改目标。");
            previous = p;
        }
        target = next;
        scope = selectedScope;
        route = accessRoute.stream().map(BlockPos::immutable).toList();
        for (var p : route) { accessCells.add(p); accessCells.add(p.above()); accessCells.add(p.below()); }
        for (var c : target.cells()) {
            protectedCells.add(c.pos());
            if (!c.authored().isAir()) protectedCells.add(c.pos().below());
            if (!plainCube(c.desired()) && !c.authored().isAir())
                for (var side : Direction.values()) protectedCells.add(c.pos().relative(side));
        }
        return null;
    }

    public ServerTool.Result live(Access access) {
        if (!active()) return failed("DENIED:须先登记当前任务工地。");
        if (stopped) return failed("ACTION_LIMIT:工地有限工作已停止，不通过重开Lua续账。");
        if (instance!=null && (!records.healthy() || !same(instance.target().revision())))
            return failed("RECORD_UNAVAILABLE:实例记录不可用或修订已变，未继续施工。");
        if (!access.passageComplete()) return failed("TARGET_LOST:实际路线记录有缺口或已到256条帽，未继续修改工地。");
        for (var p : route) if (!access.corridor(p))
            return failed("ENVIRONMENT_UNSAFE:声明的进出通道已改变或未知，停止施工并核对现场。");
        return null;
    }

    /** Full-target protection has its own B1-sized set; the older 64-cell work set is unchanged. */
    public boolean protects(BlockPos pos) {
        if (!active()) return false;
        return protectedCells.contains(pos)
                || accessCells.contains(pos)
                || temporary.containsKey(pos);
    }
    private static boolean contains(com.neko.mcbot.path.PathSpec scope, BlockPos pos) {
        return scope.contains(pos.getX(), pos.getY(), pos.getZ());
    }
    public boolean inScope(BlockPos pos) { return !active() || contains(scope, pos); }
    private boolean accessCell(BlockPos pos) {
        return accessCells.contains(pos);
    }
    public boolean allowsStanding(BlockPos feet) {
        var lower=cell(feet); var upper=cell(feet.above());
        return !active() || (lower==null || lower.authored().isAir()) && (upper==null || upper.authored().isAir());
    }
    private BuildTarget.Cell cell(BlockPos pos) {
        return target == null ? null : target.cells().stream().filter(c -> c.pos().equals(pos)).findFirst().orElse(null);
    }
    private boolean dependent(BlockPos pos, Access access) {
        return dependent(pos, access, Set.of());
    }
    private boolean dependent(BlockPos pos, Access access, Set<BlockPos> removing) {
        for (Direction side : Direction.values()) {
            var near = pos.relative(side);
            if (removing.contains(near)) continue;
            var state = access.state(near);
            if (state == null || access.facility(near)) return true;
            if (state.isAir()) continue;
            // Unknown attachment behavior is retained. Dry full cubes beside/below are the narrow safe case.
            if (side == Direction.UP || !plainCube(state)) return true;
        }
        return false;
    }
    public static boolean plainCube(BlockState state) {
        return state != null && !state.hasBlockEntity() && state.getFluidState().isEmpty()
                && !(state.getBlock() instanceof FallingBlock)
                && state.isCollisionShapeFullBlock(EmptyBlockGetter.INSTANCE, BlockPos.ZERO)
                && state.isSolidRender();
    }
    public ServerTool.Result placementGuard(BlockPos pos, BlockState proposed, Access access) {
        return placementGuard(pos, proposed, access, false);
    }
    public ServerTool.Result placementGuard(BlockPos pos, BlockState proposed, Access access, boolean temporaryPlacement) {
        if (!active()) return null;
        var failure = live(access);
        if (failure != null) return failure;
        if (!inScope(pos)) return failed("SCOPE_CHANGED:改动超出冻结工地范围。");
        if (accessCell(pos) || access.passage(pos) || temporary.containsKey(pos))
            return failed("DENIED:不覆盖工地通道、地板或已记录临时工程。");
        var selected = cell(pos);
        if (selected != null) {
            if (instance!=null && (!records.mayChange(instance,pos,access) || !records.hasCapacity(instance,pos)))
                return failed("DENIED:实例格来源冲突、未知或累计记录额度耗尽，不覆盖外部改动。");
            if (instance!=null && BuildObjects.door(proposed)
                    && !records.hasCapacity(instance,BuildObjects.footprint(pos,proposed)))
                return failed("ACTION_LIMIT:双格实际变化所需记录额度不足，未开始原版动作。");
            if (selected.matches(access.state(pos)) || !selected.matches(proposed))
                return failed("DENIED:不覆盖已满足格或放入偏离冻结目标的状态。");
        } else if (protects(pos) && !temporaryPlacement) return failed("DENIED:不覆盖完整目标的直接支撑。");
        return null;
    }
    public ServerTool.Result miningGuard(BlockPos pos, String revision, boolean cleanup, Access access) {
        if (!active()) return revision != null || cleanup ? failed("DENIED:原工地不存在。") : null;
        var failure = live(access);
        if (failure != null) return failure;
        if (!inScope(pos)) return failed("SCOPE_CHANGED:改动超出冻结工地范围。");
        if (access.passage(pos)) return failed("DENIED:不拆实际经过的通道或其地板。");
        if (revision == null) return protects(pos) || access.facility(pos) || access.facility(pos.above())
                ? failed("DENIED:工地目标、支撑、通道、工位或临时工程不可作为普通清障。") : null;
        if (!same(revision) || accessCell(pos) || access.occupied(pos))
            return failed("DENIED:施工修订不符，或目标承担通道/身体支撑。");
        if (BuildObjects.door(access.state(pos))) return doorMiningGuard(pos, cleanup, access);
        if (cleanup) {
            var record = temporary.get(pos);
            if (record == null || record.witness == null || !record.witness.intact()
                    || record.chunk != access.chunk(pos) || record.state != access.state(pos))
                return failed("DENIED:临时工程来源未知、已被修改或区块已重载，不认领相同方块替换物。");
            if (cell(pos) != null || target.cells().stream().anyMatch(c -> !c.authored().isAir() && c.pos().below().equals(pos))
                    || dependent(pos, access)) return failed("DENIED:临时工程仍有目标、承重、贴附或工位依赖，未回收。");
        } else {
            var selected = cell(pos);
            var actual = access.state(pos);
            if (selected == null || actual == null || selected.matches(actual)
                    || instance==null && actual.getBlock() == selected.authored().getBlock() || !selected.material().supported()
                    || !access.material(selected) || access.facility(pos) || dependent(pos, access)
                    || target.cells().stream().anyMatch(c -> !c.authored().isAir() && c.pos().below().equals(pos)))
                return failed("DENIED:只清有材料可替换、非成品且无依赖的冻结目标障碍；状态冲突交回模型。");
            if (instance!=null && (!records.mayChange(instance,pos,access) || !records.hasCapacity(instance,pos)))
                return failed("DENIED:旧实例格来源不可证、已被外部修改或累计记录额度耗尽，未拆除。");
        }
        return null;
    }
    private ServerTool.Result doorMiningGuard(BlockPos pos, boolean cleanup, Access access) {
        var actual = access.state(pos);
        var footprint = Set.copyOf(BuildObjects.footprint(pos,actual));
        if (cleanup || instance==null || !records.hasCapacity(instance,footprint))
            return failed("DENIED:双格拆改须当前实例两格的活来源与足量累计记录，不回收为临时工程。");
        boolean complete = true;
        for (var p : footprint) {
            var selected = cell(p); var state = access.state(p);
            if (selected==null || state==null || !inScope(p) || accessCell(p) || access.passage(p)
                    || access.occupied(p) || access.facility(p) || !selected.material().supported()
                    || !access.material(selected) || !records.mayChange(instance,p,access)
                    || !access.canMine(state) || dependent(p,access,footprint)
                    || target.cells().stream().anyMatch(c -> !c.authored().isAir()
                    && !footprint.contains(c.pos()) && c.pos().below().equals(p)))
                return failed("DENIED:双格拆改的占位、来源、材料、支撑或依赖不满足；未触碰任一半。");
            if (!state.isAir() && (!BuildObjects.door(state) || !p.equals(pos)
                    && state != actual.setValue(net.minecraft.world.level.block.DoorBlock.HALF,
                    BuildObjects.upper(actual) ? net.minecraft.world.level.block.state.properties.DoubleBlockHalf.LOWER
                            : net.minecraft.world.level.block.state.properties.DoubleBlockHalf.UPPER)))
                return failed("DENIED:门的另一半已被替换或状态不一致，未连带拆除。");
            complete &= selected.matches(state);
        }
        return complete ? failed("DENIED:完整双格目标已满足，不拆成品。") : null;
    }
    public ServerTool.Result temporaryGuard(BlockPos pos, String purpose, Access access) {
        var failure = live(access);
        if (failure != null) return failure;
        if (!inScope(pos) || !Set.of("support", "workstation").contains(purpose)
                || cell(pos) != null || accessCell(pos) || access.passage(pos) || temporary.containsKey(pos)
                || temporary.size() >= TEMP_LIMIT || access.state(pos) != Blocks.AIR.defaultBlockState())
            return failed("DENIED:临时用途须support/workstation、原格须空气且不占目标/通道，最多16条不淘汰。");
        return null;
    }
    public void placedTemporary(BlockPos pos, BlockState before, BlockState after, String purpose, Access access) {
        if (!active() || purpose == null || before == null || !before.isAir() || after == null || after.isAir()
                || temporary.size() >= TEMP_LIMIT) return;
        temporary.put(pos.immutable(), new Temporary(pos.immutable(), after, purpose, access.chunk(pos), access.watch(pos)));
    }
    public void removedTemporary(BlockPos pos) {
        // Keep the slot consumed and the invalidated witness: repeated placement cannot recycle the provenance cap.
        var record = temporary.get(pos);
        if (record != null && record.witness != null) record.witness.invalidate();
    }
    public ServerTool.Result charge() {
        if (!active()) return null;
        if (stopped || edits >= EDIT_LIMIT) { stopped = true; return failed("ACTION_LIMIT:工地累计128次原生改动尝试已到。"); }
        edits++;
        return null;
    }
    public ServerTool.Result checkpoint(String revision, BlockPos feet, Access access) {
        if (!same(revision)) return failed("TARGET_REVISION_CHANGED:工地修订不符。");
        if (!inScope(feet)) return failed("SCOPE_CHANGED:身体已离开冻结工地范围。");
        var failure = live(access);
        if (failure != null) return failure;
        if (checkpoints >= ROUND_LIMIT) { stopped = true; return failed("ACTION_LIMIT:工地累计16轮已到。"); }
        var signature = new StringBuilder(feet.toShortString());
        for (var c : target.cells()) {
            var actual = access.state(c.pos());
            if (actual == null) return failed("TARGET_LOST:整目标观察不完整，未开始下一施工轮。");
            signature.append('|').append(actual).append(':').append(access.material(c)).append(':').append(access.canMine(actual));
            for (Direction side : Direction.values()) {
                var neighbor = access.state(c.pos().relative(side));
                if (neighbor == null) return failed("TARGET_LOST:目标邻接条件未知，未开始下一施工轮。");
                signature.append('/').append(neighbor);
            }
        }
        for (var record : temporary.values()) signature.append('|').append(record.pos).append(':')
                .append(access.state(record.pos)).append(':').append(record.witness != null && record.witness.intact());
        checkpoints++;
        if (!rounds.add(signature.toString())) {
            stopped = true;
            return failed("NO_PROGRESS:相同真实目标缺口与站位已出现，停止往返施工；计时、查询和扣料不算进展。");
        }
        return null;
    }
    public JsonObject report(Access access) {
        var data = new JsonObject();
        data.addProperty("active", active());
        data.addProperty("task_scoped", true);
        if (instance!=null) {
            data.addProperty("instance_id",instance.id()); data.addProperty("instance_revision",instance.revision());
            data.addProperty("recording_known",records.healthy());
        }
        data.addProperty("return_guaranteed", false);
        if (!active()) return data;
        data.addProperty("target_revision", target.revision());
        data.addProperty("protected_target_cells", target.cells().size());
        data.addProperty("access_route_cells", route.size());
        data.addProperty("checkpoints", checkpoints); data.addProperty("edit_attempts", edits);
        data.addProperty("round_limit", ROUND_LIMIT); data.addProperty("edit_limit", EDIT_LIMIT);
        data.addProperty("stopped", stopped);
        var bounds = new JsonObject();
        bounds.addProperty("x", scope.anchor().getX()); bounds.addProperty("y", scope.anchor().getY());
        bounds.addProperty("z", scope.anchor().getZ()); bounds.addProperty("radius", scope.radius());
        data.add("bounds", bounds);
        data.addProperty("navigation_nodes", navigation.nodes()); data.addProperty("navigation_records", navigation.records());
        data.addProperty("navigation_nanos", navigation.nanos()); data.addProperty("navigation_replans", navigation.replans());
        var rows = new JsonArray();
        for (var record : temporary.values()) {
            var row = new JsonObject();
            row.addProperty("x", record.pos.getX()); row.addProperty("y", record.pos.getY()); row.addProperty("z", record.pos.getZ());
            row.addProperty("purpose", record.purpose);
            row.addProperty("block", net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(record.state.getBlock()).toString());
            var actual = access.state(record.pos);
            row.addProperty("present_known", actual != null);
            row.addProperty("present", actual != null && !actual.isAir());
            row.addProperty("provenance_intact", record.witness != null && record.witness.intact()
                    && record.chunk == access.chunk(record.pos) && record.state == access.state(record.pos));
            row.addProperty("cleanup_allowed", miningGuard(record.pos, target.revision(), true, access) == null);
            rows.add(row);
        }
        data.add("temporary", rows);
        return data;
    }
    private static ServerTool.Result failed(String reason) { return new ServerTool.Result(false, reason, null); }
}
