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
    public static final int ROUTE_LIMIT = 64, TEMP_LIMIT = 16;
    private static final int HISTORY_LIMIT = 128;
    public interface Access {
        BlockState state(BlockPos pos);
        boolean corridor(BlockPos feet);
        default List<BlockPos> corridorBody(BlockPos feet) { return List.of(feet, feet.above()); }
        default boolean corridorStep(BlockPos from, BlockPos to) {
            return Math.abs(from.getX() - to.getX()) + Math.abs(from.getZ() - to.getZ()) == 1
                    && Math.abs(from.getY() - to.getY()) <= 1;
        }
        boolean facility(BlockPos pos);
        boolean occupied(BlockPos pos);
        boolean material(BuildTarget.Cell cell);
        Object chunk(BlockPos pos);
        CellWitness watch(BlockPos pos);
        default boolean passage(BlockPos pos) { return false; }
        default boolean passageComplete() { return true; }
        default boolean canMine(BlockState actual) { return true; }
        default boolean canLowerThrough(BlockPos pos) { return false; }
        default boolean repairMaterials(List<BuildTarget.Cell> cells) { return cells.stream().allMatch(this::material); }
        default boolean cleanupReady(BlockPos pos) { return false; }
        default double cleanupDistanceSq(BlockPos pos) { return Double.NaN; }
    }
    private record Temporary(BlockPos pos, BlockState state, String purpose, Object chunk, CellWitness witness) { }
    private Object task, world;
    private BuildTarget target;
    private final Map<BlockPos, BuildTarget.Cell> targetCells = new HashMap<>();
    private List<BlockPos> route = List.of();
    private final Map<BlockPos, Temporary> temporary = new LinkedHashMap<>();
    private final Set<BlockPos> repairs = new HashSet<>();
    private final Set<String> rounds = new LinkedHashSet<>();
    private final Set<BlockPos> protectedCells = new HashSet<>();
    private final Set<BlockPos> accessCells = new HashSet<>();
    private com.neko.mcbot.path.PathSpec scope = com.neko.mcbot.path.PathSpec.DEFAULT;
    private com.neko.mcbot.path.NavigationBudget navigation = new com.neko.mcbot.path.NavigationBudget();
    private int checkpoints, edits;
    private boolean stopped;
    private int targetEdits;
    private final BuildDrafts drafts = new BuildDrafts();
    private final BuildSearchMemory searches = new BuildSearchMemory();
    private BuildTarget verifiedDraft;
    private BuildRecords records;
    private BuildRecords.Instance instance;

    public void bind(Object nextTask, Object nextWorld) {
        if (task != nextTask || world != nextWorld) {
            target = null; targetCells.clear(); route = List.of(); temporary.clear(); repairs.clear(); rounds.clear();
            protectedCells.clear(); accessCells.clear(); scope = com.neko.mcbot.path.PathSpec.DEFAULT;
            navigation = new com.neko.mcbot.path.NavigationBudget();
            checkpoints = edits = targetEdits = 0; stopped = false; task = nextTask; world = nextWorld;
            drafts.clear();
            searches.clear();
            verifiedDraft = null;
            records=null; instance=null;
        }
    }
    public boolean active() { return task != null && target != null; }
    public Object boundTask() { return task; }
    public boolean boundTo(Object expected, Object expectedWorld) { return task == expected && world == expectedWorld; }
    public void continueTask(Object expected, Object next, Object nextWorld) {
        if (!boundTo(expected, nextWorld)) throw new IllegalArgumentException("site_session_changed");
        task = next;
    }
    public boolean same(String revision) { return active() && target.revision().equals(revision); }
    public BuildTarget target() { return target; }
    public BuildDrafts drafts() { return drafts; }
    public BuildSearchMemory searches() { return searches; }
    public int targetEdits() { return targetEdits; }
    public void retainVerifiedDraft(BuildTarget selected) {
        if (!drafts.remember(selected)) throw new IllegalArgumentException("draft_capacity");
        verifiedDraft = selected;
    }
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
        recordTargetEdit(pos, before, after);
        if (cell(pos) != null && cell(pos).matches(after)) repairs.remove(pos);
        if (instance!=null && cell(pos)!=null) records.placed(instance,pos,before,after,consumed,access);
    }
    public void recordRemoval(BlockPos pos, BlockState before, BlockState after) {
        recordTargetEdit(pos, before, after);
        if (cell(pos) != null && cell(pos).matches(before) && !cell(pos).matches(after)) repairs.add(pos.immutable());
        if (instance!=null && cell(pos)!=null) records.removed(instance,pos,before,after);
    }
    public boolean repairPending(BlockPos pos) { return repairs.contains(pos); }
    public Set<BlockPos> interactionSources(java.util.Collection<BlockPos> positions,Access access) {
        if (instance==null || !records.hasCapacity(instance,positions)) return Set.of();
        var owned=new HashSet<BlockPos>();
        for (var pos:positions) if (cell(pos)!=null && access.state(pos)!=null && !access.state(pos).isAir()
                && records.mayChange(instance,pos,access)) owned.add(pos.immutable());
        return Set.copyOf(owned);
    }
    public void recordInteraction(Map<BlockPos,BlockState> before,Map<BlockPos,BlockState> after,
                                  Set<BlockPos> owned,Access access) {
        before.forEach((pos,state)->recordTargetEdit(pos,state,after.get(pos)));
        if (instance!=null && !owned.isEmpty()) {
            var selected=new LinkedHashMap<BlockPos,BlockState>();
            before.forEach((pos,state)-> { if (cell(pos)!=null) selected.put(pos,state); });
            records.interacted(instance,selected,after,owned,access);
        }
    }
    public boolean temporary(BlockPos pos) { return temporary.containsKey(pos); }

    /** A work stance should not occupy the collision volume that the frozen drawing still needs. */
    public boolean obstructs(net.minecraft.world.phys.AABB body) {
        for (var pos : BlockPos.betweenClosed(BlockPos.containing(body.minX, body.minY, body.minZ),
                BlockPos.containing(body.maxX, body.maxY, body.maxZ))) {
            var selected = cell(pos);
            if (selected == null || selected.authored().isAir()) continue;
            var boxes = com.neko.mcbot.server.StandingGeometry.collision(selected.desired(), EmptyBlockGetter.INSTANCE, pos);
            if (boxes == null || boxes.stream().anyMatch(box -> box.move(pos).intersects(body))) return true;
        }
        return false;
    }
    public void recordObjectPlacement(BlockPos primary, Map<BlockPos,BlockState> before,
                                       Map<BlockPos,BlockState> after, int consumed, Access access) {
        before.forEach((pos,state) -> recordTargetEdit(pos,state,after.get(pos)));
        if (instance!=null && cell(primary)!=null) records.placedObject(instance,primary,before,after,consumed,access);
    }
    public void recordObjectRemoval(Map<BlockPos,BlockState> before, Map<BlockPos,BlockState> after) {
        before.forEach((pos,state) -> recordTargetEdit(pos,state,after.get(pos)));
        if (instance!=null) {
            var selected=new LinkedHashMap<BlockPos,BlockState>();
            before.forEach((pos,state) -> { if (cell(pos)!=null) selected.put(pos,state); });
            if (!selected.isEmpty()) records.removedObject(instance,selected,after);
        }
    }

    private void recordTargetEdit(BlockPos pos, BlockState before, BlockState after) {
        if (cell(pos) != null && before != null && after != null && before != after && targetEdits < Integer.MAX_VALUE)
            targetEdits++;
    }

    /** Correct an unbuilt drawing without erasing temporary provenance or navigation accounts. */
    public ServerTool.Result revise(BuildTarget next, String previousRevision, BlockPos feet, Access access) {
        if (!same(previousRevision)) return failed("TARGET_REVISION_CHANGED:须核对当前工地旧修订后显式纠正。");
        if (instance != null || stopped || targetEdits != 0 || !repairs.isEmpty())
            return failed("DENIED:实例工地、已停止或已改动目标格的工地不能按草稿纠正；原来源及累计账保持。");
        if (!target.anchor().equals(next.anchor()) || target.rotation() != next.rotation()
                || !target.index().keySet().equals(next.index().keySet())
                || next.cells().stream().anyMatch(c -> temporary.containsKey(c.pos())))
            return failed("DENIED:草稿纠正限原落点、旋转和同一格集的材料/属性修正，不迁移工地或吞并临时工程。");
        // Validate against the original corridor and scope before touching the live registration.
        var probe = new BuildSite();
        probe.bind(task, world);
        var failure = probe.begin(next, route, feet, scope, access);
        if (failure != null) return failure;
        failure = live(access);
        if (failure != null) return failure;
        target = next;
        targetCells.clear(); targetCells.putAll(probe.targetCells);
        protectedCells.clear(); protectedCells.addAll(probe.protectedCells);
        accessCells.clear(); accessCells.addAll(probe.accessCells);
        drafts.remember(next);
        return null;
    }

    public ServerTool.Result begin(BuildTarget next, List<BlockPos> accessRoute, BlockPos feet, Access access) {
        return begin(next, accessRoute, feet, active() ? scope
                : com.neko.mcbot.path.PathSpec.DEFAULT, access);
    }
    public ServerTool.Result begin(BuildTarget next, List<BlockPos> accessRoute, BlockPos feet,
                                   com.neko.mcbot.path.PathSpec selectedScope, Access access) {
        if (task == null || stopped) return failed("DENIED:没有有效施工任务或工地已停止。");
        var validation=BuildObjects.validation(next);
        if (!validation.get("valid").getAsBoolean()) {
            var data=validation.getAsJsonArray("issues").get(0).getAsJsonObject().deepCopy();
            data.addProperty("object_problem",data.get("reason").getAsString());
            data.addProperty("target_revision",next.revision());
            data.add("object_validation",validation);
            return new ServerTool.Result(false,BuildObjects.invalidFeedback(validation),data);
        }
        if (target != null) {
            if (accessRoute.isEmpty()) accessRoute = route;
            if (!same(next.revision()) || !route.equals(accessRoute) || !scope.equals(selectedScope))
                return failed("TARGET_REVISION_CHANGED:任务内工地与通道已冻结，不换图、换通道或重开账。");
            return live(access);
        }
        if (accessRoute.size() == 1 || accessRoute.size() > ROUTE_LIMIT
                || new HashSet<>(accessRoute).size() != accessRoute.size()
                || !accessRoute.isEmpty() && !accessRoute.contains(feet))
            return failed("DENIED:可省略通道由导航选择工作站位；显式通道须2-64格无重复且包含当前脚格。");
        BlockPos previous = null;
        int minX=next.cells().stream().mapToInt(c -> c.pos().getX()).min().orElseThrow();
        int maxX=next.cells().stream().mapToInt(c -> c.pos().getX()).max().orElseThrow();
        int minZ=next.cells().stream().mapToInt(c -> c.pos().getZ()).min().orElseThrow();
        int maxZ=next.cells().stream().mapToInt(c -> c.pos().getZ()).max().orElseThrow();
        if (!accessRoute.isEmpty() && java.util.stream.Stream.of(accessRoute.getFirst(), accessRoute.getLast()).noneMatch(
                p -> p.getX()<minX || p.getX()>maxX || p.getZ()<minZ || p.getZ()>maxZ))
            return failed("DENIED:通道至少一个端点须伸出完整目标的水平包围范围，不能只保留房间内两格。");
        if (!contains(selectedScope, feet)
                || next.cells().stream().anyMatch(c -> !contains(selectedScope, c.pos()))
                || accessRoute.stream().anyMatch(p -> !contains(selectedScope, p)))
            return failed("SCOPE_CHANGED:工地、通道或身体超出固定施工范围。");
        for (var p : accessRoute) {
            if (!access.corridor(p))
                return failed("TARGET_BLOCKED:显式通道在 " + p.toShortString()
                        + " 未确认真实支撑、身体净空或已加载；可省略access_route由正常导航选择工作站位。");
            if (previous != null && !access.corridorStep(previous, p))
                return failed("TARGET_BLOCKED:显式通道相邻段未通过实际碰撞移动检查；可省略access_route使用正常导航。");
            var bodyCells = access.corridorBody(p);
            for (var cell : next.cells())
                if (bodyCells.contains(cell.pos()) && !cell.authored().isAir()
                        && !cell.matches(access.state(cell.pos())))
                    return failed("DENIED:非空气目标会封住声明的进出通道，先修改目标。");
            previous = p;
        }
        target = next;
        drafts.remember(next);
        target.cells().forEach(cell -> targetCells.put(cell.pos(), cell));
        scope = selectedScope;
        route = accessRoute.stream().map(BlockPos::immutable).toList();
        for (var p : route) {
            accessCells.addAll(access.corridorBody(p));
            accessCells.add(p.below());
        }
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
        BlockPos previous = null;
        for (var p : route) {
            if (!access.corridor(p) || previous != null && !access.corridorStep(previous, p))
                return failed("TARGET_BLOCKED:显式进出通道的实际支撑、净空或相邻段已改变，须核对现场。");
            previous = p;
        }
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
        return inScope(feet);
    }
    public String navigationRevision(BlockPos pos, Access access) {
        return active() && (cell(pos) != null || temporary.containsKey(pos))
                && miningGuard(pos, target.revision(), temporary.containsKey(pos), access) == null
                ? target.revision() : null;
    }
    public boolean protectsNavigation(BlockPos pos, Access access) {
        return protects(pos) && navigationRevision(pos, access) == null;
    }
    private BuildTarget.Cell cell(BlockPos pos) {
        return targetCells.get(pos);
    }
    private boolean dependent(BlockPos pos, Access access) {
        return dependent(pos, access, Set.of());
    }
    private boolean dependent(BlockPos pos, Access access, Set<BlockPos> removing) {
        return dependent(pos, access, removing, false);
    }
    private boolean dependent(BlockPos pos, Access access, Set<BlockPos> removing, boolean preparation) {
        for (Direction side : Direction.values()) {
            var near = pos.relative(side);
            if (removing.contains(near)) continue;
            var state = access.state(near);
            if (state == null || access.facility(near)) return true;
            if (state.isAir()) continue;
            if (softClearance(state)) continue;
            if (state.getBlock() instanceof net.minecraft.world.level.block.LeavesBlock) continue;
            if (preparation && restorable(state)) continue;
            // Unknown attachment behavior is retained. Dry full cubes beside/below are the narrow safe case.
            if (side == Direction.UP || !plainCube(state)) return true;
        }
        return false;
    }
    /** Ordinary flowers and segmented flower beds need not carry the replaceable flag. */
    public static boolean softClearance(BlockState state) {
        if (state == null || state.isAir() || state.hasBlockEntity()
                || !(state.canBeReplaced() || state.getBlock() instanceof net.minecraft.world.level.block.FlowerBlock
                || state.getBlock() instanceof net.minecraft.world.level.block.TallFlowerBlock
                || state.getBlock() instanceof net.minecraft.world.level.block.FlowerBedBlock)
                || state.getBlock().hasDynamicShape()) return false;
        var boxes = com.neko.mcbot.server.StandingGeometry.collision(state, EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
        return boxes != null && boxes.isEmpty();
    }
    private boolean supportsFinished(BlockPos pos, Access access) {
        var above=cell(pos.above());
        return above!=null && !above.authored().isAir() && above.matches(access.state(above.pos()))
                && !restorable(above.desired());
    }
    private boolean cleanupDependent(BlockPos pos, Access access) {
        var independent = new HashSet<BlockPos>();
        for (Direction side : Direction.values()) {
            var near = pos.relative(side);
            var selected = cell(near);
            if (selected != null && restorable(selected.desired()) && selected.matches(access.state(near))
                    && !access.facility(near)) independent.add(near);
        }
        return dependent(pos, access, independent);
    }
    public static boolean plainCube(BlockState state) {
        return state != null && !state.hasBlockEntity() && state.getFluidState().isEmpty()
                && !(state.getBlock() instanceof FallingBlock)
                && state.isCollisionShapeFullBlock(EmptyBlockGetter.INSTANCE, BlockPos.ZERO)
                && (state.isSolidRender()
                || state.getBlock().getClass() == net.minecraft.world.level.block.TransparentBlock.class);
    }
    /** Vanilla stairs and slabs stay in place without a block beneath them. */
    public static boolean restorable(BlockState state) {
        return plainCube(state) || state != null && !state.hasBlockEntity() && state.getFluidState().isEmpty()
                && (state.getBlock().getClass() == net.minecraft.world.level.block.StairBlock.class
                || state.getBlock().getClass() == net.minecraft.world.level.block.SlabBlock.class);
    }
    public ServerTool.Result placementGuard(BlockPos pos, BlockState proposed, Access access) {
        return placementGuard(pos, proposed, access, false);
    }
    public ServerTool.Result placementGuard(BlockPos pos, BlockState proposed, Access access, boolean temporaryPlacement) {
        if (!active()) return null;
        var failure = live(access);
        if (failure != null) return failure;
        if (!inScope(pos)) return failed("SCOPE_CHANGED:改动超出冻结工地范围。");
        var selected = cell(pos);
        if (accessCell(pos) || selected == null && access.passage(pos) && !temporaryPlacement || temporary.containsKey(pos))
            return failed("DENIED:不覆盖工地通道、地板或已记录临时工程。");
        if (selected != null) {
            if (instance!=null && (!records.mayChange(instance,pos,access) || !records.hasCapacity(instance,pos)))
                return failed("DENIED:实例格来源冲突、未知或累计记录额度耗尽，不覆盖外部改动。");
            if (instance!=null && BuildObjects.paired(proposed)
                    && !records.hasCapacity(instance,BuildObjects.footprint(pos,proposed)))
                return failed("ACTION_LIMIT:双格实际变化所需记录额度不足，未开始原版动作。");
            if (selected.matches(access.state(pos))) return failed("DENIED:不覆盖已满足的冻结目标。");
            if (!selected.matches(proposed)) {
                var data=new JsonObject(); data.addProperty("placement_failure","desired_state_mismatch");
                return new ServerTool.Result(false,"TARGET_BLOCKED:当前原版落位状态不满足冻结目标，换工作站位后重验；未改写状态。",data);
            }
        } else if (protects(pos) && !temporaryPlacement) return failed("DENIED:不覆盖完整目标的直接支撑。");
        return null;
    }
    public ServerTool.Result miningGuard(BlockPos pos, String revision, boolean cleanup, Access access) {
        if (!active()) return revision != null || cleanup ? failed("DENIED:原工地不存在。") : null;
        var failure = live(access);
        if (failure != null) return failure;
        if (!inScope(pos)) return failed("SCOPE_CHANGED:改动超出冻结工地范围。");
        var selected = cell(pos);
        boolean targetEdit = same(revision) && !cleanup && selected != null;
        if (access.passage(pos) && !targetEdit && !cleanup && !softClearance(access.state(pos)))
            return failed("DENIED:不为无关清障拆实际经过的通道或其地板。");
        if (revision == null) return protects(pos) || access.facility(pos) || access.facility(pos.above())
                ? failed("DENIED:工地目标、支撑、通道、工位或临时工程不可作为普通清障。") : null;
        boolean soft = softClearance(access.state(pos));
        if (!same(revision) || accessCell(pos) && !soft
                || access.occupied(pos) && !soft && !access.canLowerThrough(pos))
            return failed("DENIED:施工修订不符，或目标承担通道/身体支撑。");
        if (BuildObjects.paired(access.state(pos))) return objectMiningGuard(pos, cleanup, access);
        if (cleanup) {
            var record = temporary.get(pos);
            if (record == null || record.witness == null || !record.witness.intact()
                    || record.chunk != access.chunk(pos) || record.state != access.state(pos))
                return failed("DENIED:临时工程来源未知、已被修改或区块已重载，不认领相同方块替换物。");
            // Historical walking is not a live dependency. Explicit corridors, bodies and
            // attachments are checked separately; only workstations still need the full trail.
            if (!access.passageComplete()
                    && !"support".equals(record.purpose))
                return failed("TARGET_LOST:实际路线记录有缺口，不能确认临时工程已无通行依赖，未回收。");
            if (cell(pos) != null || target.cells().stream().anyMatch(c -> !c.authored().isAir()
                    && !restorable(c.desired()) && c.pos().below().equals(pos))
                    || cleanupDependent(pos, access))
                return failed("DENIED:临时工程仍有目标、承重、贴附或工位依赖，未回收。");
        } else {
            var actual = access.state(pos);
            boolean reopening = selected != null && selected.matches(actual) && restorable(actual)
                    && restorable(selected.desired());
            var owed = new ArrayList<BuildTarget.Cell>();
            for (var p : repairs) if (!p.equals(pos) && !cell(p).matches(access.state(p))) owed.add(cell(p));
            if (reopening) owed.add(selected);
            if (selected == null || actual == null || selected.matches(actual) && !reopening
                    || instance==null && actual.getBlock() == selected.authored().getBlock() && !reopening
                    || reopening && !access.repairMaterials(owed) || !selected.material().supported()
                    || !access.material(selected) || access.facility(pos) || dependent(pos, access, Set.of(), true)
                    || supportsFinished(pos, access))
                return failed("DENIED:原图清障或临时开路的补料、占位、设施或依赖条件不满足，未拆除。");
            if (instance!=null && (!records.mayChange(instance,pos,access) || !records.hasCapacity(instance,pos)))
                return failed("DENIED:旧实例格来源不可证、已被外部修改或累计记录额度耗尽，未拆除。");
        }
        return null;
    }
    private ServerTool.Result objectMiningGuard(BlockPos pos, boolean cleanup, Access access) {
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
            if (!state.isAir() && (!BuildObjects.paired(state) || !p.equals(pos)
                    && state != BuildObjects.partnerState(actual)))
                return failed("DENIED:门的另一半已被替换或状态不一致，未连带拆除。");
            complete &= selected.matches(state);
        }
        return complete ? failed("DENIED:完整双格目标已满足，不拆成品。") : null;
    }
    public ServerTool.Result temporaryGuard(BlockPos pos, String purpose, Access access) {
        var failure = live(access);
        if (failure != null) return failure;
        if (!inScope(pos) || !Set.of("support", "workstation").contains(purpose)
                || cell(pos) != null || accessCell(pos) || access.passage(pos) && !"support".equals(purpose)
                || temporary.containsKey(pos)
                || temporary.size() >= TEMP_LIMIT || access.state(pos) != Blocks.AIR.defaultBlockState())
            return failed("DENIED:临时用途须support/workstation、原格须空气且不占目标/通道，最多16条待回收记录。");
        return null;
    }
    public void placedTemporary(BlockPos pos, BlockState before, BlockState after, String purpose, Access access) {
        if (!active() || purpose == null || before == null || !before.isAir() || after == null || after.isAir()
                || temporary.size() >= TEMP_LIMIT) return;
        temporary.put(pos.immutable(), new Temporary(pos.immutable(), after, purpose, access.chunk(pos), access.watch(pos)));
    }
    public void removedTemporary(BlockPos pos) {
        // Called only after actual guarded removal. A later placement needs a fresh consumed item and witness.
        var record = temporary.remove(pos);
        if (record != null && record.witness != null) record.witness.invalidate();
    }
    public ServerTool.Result charge() {
        if (!active()) return null;
        if (stopped) return failed("DENIED:原工地已停止。");
        if (edits < Integer.MAX_VALUE) edits++;
        return null;
    }
    public ServerTool.Result checkpoint(String revision, BlockPos feet, Access access) {
        if (!same(revision)) return failed("TARGET_REVISION_CHANGED:工地修订不符。");
        if (!inScope(feet)) return failed("SCOPE_CHANGED:身体已离开冻结工地范围。");
        var failure = live(access);
        if (failure != null) return failure;
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
        if (checkpoints < Integer.MAX_VALUE) checkpoints++;
        rounds.add(fingerprint(signature.toString()));
        if (rounds.size() > HISTORY_LIMIT) rounds.remove(rounds.iterator().next());
        return null;
    }

    private static String fingerprint(String value) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    public JsonObject snapshot() {
        var data = new JsonObject();
        data.addProperty("active", active());
        if (verifiedDraft != null && !same(verifiedDraft.revision()))
            data.add("verified_draft",verifiedDraft.input());
        if (!active()) return data;
        data.add("target", target.input()); data.add("scope", scope.snapshot());
        var accessRoute = new JsonArray(); route.forEach(p -> accessRoute.add(BuildTarget.coordinates(p)));
        data.add("route", accessRoute);
        data.addProperty("checkpoints", checkpoints); data.addProperty("edits", edits); data.addProperty("stopped", stopped);
        data.addProperty("target_edits", targetEdits);
        data.add("navigation", navigation.snapshot());
        var seen = new JsonArray(); rounds.stream().sorted().forEach(seen::add); data.add("rounds", seen);
        var items = new JsonArray();
        for (var entry : temporary.values()) {
            var item = BuildTarget.coordinates(entry.pos);
            item.add("state", BuildTarget.exactState(entry.state)); item.addProperty("purpose", entry.purpose);
            items.add(item);
        }
        data.add("temporary", items);
        var pending = new JsonArray();
        repairs.stream().sorted(java.util.Comparator.comparingLong(BlockPos::asLong))
                .forEach(pos -> pending.add(BuildTarget.coordinates(pos)));
        data.add("repairs", pending);
        if (instance != null) {
            data.addProperty("instance_id", instance.id()); data.addProperty("instance_revision", instance.revision());
        }
        return data;
    }

    public void restore(JsonObject data, BlockPos feet, Access access) {
        if (!data.getAsJsonPrimitive("active").isBoolean() || active()) throw new IllegalArgumentException();
        if (data.has("verified_draft")) retainVerifiedDraft(BuildTarget.parse(data.getAsJsonObject("verified_draft")));
        if (!data.get("active").getAsBoolean()) return;
        var target = BuildTarget.parse(data.getAsJsonObject("target"));
        var scope = com.neko.mcbot.path.PathSpec.restore(data.getAsJsonObject("scope"));
        var values = data.getAsJsonArray("route");
        if (values.size() == 1 || values.size() > ROUTE_LIMIT || !scope.contains(feet.getX(), feet.getY(), feet.getZ()))
            throw new IllegalArgumentException();
        var restoredRoute = new ArrayList<BlockPos>();
        for (var value : values) {
            var p = value.getAsJsonObject();
            restoredRoute.add(new BlockPos(BuildTarget.integer(p.get("x"), -29999984, 29999984),
                    BuildTarget.integer(p.get("y"), -2048, 2047), BuildTarget.integer(p.get("z"), -29999984, 29999984)));
        }
        var failure = begin(target, restoredRoute, restoredRoute.isEmpty() ? feet : restoredRoute.getFirst(), scope, access);
        if (failure != null) throw new IllegalArgumentException("Worksite changed");
        checkpoints = BuildTarget.integer(data.get("checkpoints"), 0, Integer.MAX_VALUE);
        edits = BuildTarget.integer(data.get("edits"), 0, Integer.MAX_VALUE);
        // Old checkpoints cannot prove that their edits were all outside the drawing.
        targetEdits = data.has("target_edits") ? BuildTarget.integer(data.get("target_edits"),0,Integer.MAX_VALUE) : edits;
        if (!data.getAsJsonPrimitive("stopped").isBoolean()) throw new IllegalArgumentException();
        stopped = data.get("stopped").getAsBoolean();
        navigation = com.neko.mcbot.path.NavigationBudget.restore(data.getAsJsonObject("navigation"));
        var seen = data.getAsJsonArray("rounds");
        var items = data.getAsJsonArray("temporary");
        if (seen.size() > HISTORY_LIMIT || items.size() > TEMP_LIMIT) throw new IllegalArgumentException();
        for (var value : seen) {
            String hash = value.getAsString();
            if (!hash.matches("[0-9a-f]{64}") || !rounds.add(hash)) throw new IllegalArgumentException();
        }
        for (var value : items) {
            var item = value.getAsJsonObject();
            var pos = new BlockPos(BuildTarget.integer(item.get("x"), -29999984, 29999984),
                    BuildTarget.integer(item.get("y"), -2048, 2047), BuildTarget.integer(item.get("z"), -29999984, 29999984));
            var state = item.getAsJsonObject("state").deepCopy();
            state.addProperty("x", 0); state.addProperty("y", 0); state.addProperty("z", 0);
            var cells = new JsonArray(); cells.add(state);
            var input = new JsonObject(); input.add("anchor", BuildTarget.coordinates(BlockPos.ZERO)); input.add("cells", cells);
            String purpose = item.get("purpose").getAsString();
            if (!Set.of("support", "workstation").contains(purpose) || !inScope(pos)
                    || temporary.put(pos, new Temporary(pos, BuildTarget.parse(input).cells().getFirst().authored(),
                    purpose, null, null)) != null) throw new IllegalArgumentException();
        }
        if (data.has("repairs")) {
            var pending = data.getAsJsonArray("repairs");
            if (pending.size() > target.cells().size()) throw new IllegalArgumentException();
            for (var value : pending) {
                var p = value.getAsJsonObject();
                var pos = new BlockPos(BuildTarget.integer(p.get("x"), -29999984, 29999984),
                        BuildTarget.integer(p.get("y"), -2048, 2047), BuildTarget.integer(p.get("z"), -29999984, 29999984));
                if (cell(pos) == null || !restorable(cell(pos).desired()) || !repairs.add(pos))
                    throw new IllegalArgumentException("repair");
            }
        }
        // Old witnesses are never reconstructed from a saved block state.
        if (stopped || navigation.exhausted()
                || live(access) != null) throw new IllegalArgumentException("Worksite exhausted or unavailable");
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
        data.addProperty("passage_observation_complete", access.passageComplete());
        if (!active()) return data;
        data.addProperty("target_revision", target.revision());
        if (instance != null) {
            var reference = new JsonObject();
            reference.addProperty("instance_id",instance.id());
            reference.addProperty("instance_revision",instance.revision());
            data.add("target",reference);
        } else if (drafts.remember(target)) data.add("target", BuildDrafts.reference(target));
        data.addProperty("target_edits", targetEdits);
        data.addProperty("draft_revision_allowed", instance == null && !stopped && targetEdits == 0 && repairs.isEmpty());
        data.addProperty("repairs_pending", repairs.stream().filter(pos -> !cell(pos).matches(access.state(pos))).count());
        data.addProperty("protected_target_cells", target.cells().size());
        data.addProperty("access_route_cells", route.size());
        data.addProperty("checkpoints", checkpoints); data.addProperty("edit_attempts", edits);
        data.addProperty("round_limit", 0); data.addProperty("edit_limit", 0);
        data.addProperty("stopped", stopped);
        data.addProperty("scope_limited", scope.anchor() != null);
        data.addProperty("access_route_explicit", !route.isEmpty());
        if (scope.anchor() != null) {
            var bounds = new JsonObject();
            bounds.addProperty("x", scope.anchor().getX()); bounds.addProperty("y", scope.anchor().getY());
            bounds.addProperty("z", scope.anchor().getZ()); bounds.addProperty("radius", scope.radius());
            data.add("bounds", bounds);
        }
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
            var failure = miningGuard(record.pos, target.revision(), true, access);
            row.addProperty("cleanup_allowed", failure == null);
            row.addProperty("cleanup_ready", failure == null && access.cleanupReady(record.pos));
            double distance = access.cleanupDistanceSq(record.pos);
            if (Double.isFinite(distance) && distance >= 0) row.addProperty("cleanup_distance_sq", distance);
            if (failure != null) row.addProperty("cleanup_reason", failure.feedback());
            rows.add(row);
        }
        data.add("temporary", rows);
        return data;
    }
    private static ServerTool.Result failed(String reason) { return new ServerTool.Result(false, reason, null); }
}
