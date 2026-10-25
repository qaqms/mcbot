package com.neko.mcbot.server;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.neko.mcbot.body.CompanionPlayer;
import com.neko.mcbot.building.BuildObjects;
import com.neko.mcbot.building.BuildTarget;
import com.neko.mcbot.path.DigAStar;
import com.neko.mcbot.path.TerrainEdits;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.util.Mth;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.BedItem;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.DoubleHighBlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.StandingAndWallBlockItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.CollisionContext;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Exact target placement; vanilla owns state selection, multi-block hooks and consumption. */
public final class BlockPlacement {
    public interface Access {
        ServerTool.Result guard(BlockPos pos);
        Container inventory();
        BlockState state(BlockPos pos);
        ServerTool.Result sight(BlockPos pos, Direction face);
        /** Read-only preflight; the same context is consumed only by place. */
        default ServerTool.Result prepare(BlockPos pos, ItemStack stack, Direction face) { return null; }
        default BlockState proposedState() { return null; }
        default JsonObject contact() { return null; }
        default JsonArray standingPositions(BlockPos pos, int slot, Direction face) { return new JsonArray(); }
        InteractionResult place(BlockPos pos, ItemStack stack, Direction face);
        default ServerTool.Result beforePlace() { return null; }
        default void recorded(BlockPos pos, BlockState before, BlockState after, int consumed) { }
        default void recordedObject(BlockPos primary, java.util.Map<BlockPos, BlockState> before,
                                    java.util.Map<BlockPos, BlockState> after, int consumed) {
            recorded(primary, before.get(primary), after.get(primary), consumed);
        }
        default ServerTool.Result secondaryCell(BlockPos pos, BlockState proposed) { return null; }
        default boolean recordingKnown() { return true; }
    }

    interface Site {
        BlockState current();
        boolean replaceable();
        BlockState placed();
        boolean survives();
        List<AABB> collision();
        AABB body();
        boolean entityClear();
    }

    private BlockPlacement() {
    }

    public static Access forPlayer(CompanionPlayer player) {
        return new PlayerAccess(player, null, null, TerrainEdits.EMPTY);
    }
    public static Access forTemporary(CompanionPlayer player, String purpose) {
        var access = new PlayerAccess(player, null, null, TerrainEdits.EMPTY);
        access.temporaryPurpose = purpose;
        return access;
    }

    public static int findSlot(Container inventory, net.minecraft.world.item.Item item) {
        for (int i = 0; i < Math.min(Inventory.INVENTORY_SIZE, inventory.getContainerSize()); i++)
            if (!inventory.getItem(i).isEmpty() && inventory.getItem(i).is(item)) return i;
        return -1;
    }

    public static boolean occupiesWorkTarget(AABB body, BlockPos target) { return body.intersects(new AABB(target)); }

    /** Null is a passed read-only preflight, never an executed placement. */
    public static ServerTool.Result assess(Access access, BlockPos pos, int slot, Direction face) {
        return preflight(access, pos, slot, face);
    }

    /** Author requirements constrain the native context; they never replace its selected state. */
    public static ServerTool.Result assessDesired(Access access, BlockPos pos, int slot, Direction face,
                                                  java.util.function.Predicate<BlockState> matches) {
        var failure = preflight(access, pos, slot, face);
        if (failure != null) return failure;
        BlockState proposed = access.proposedState();
        if (proposed != null && matches.test(proposed)) return null;
        var data = new JsonObject();
        data.addProperty("placement_failure", proposed == null ? "state_prediction_unknown" : "desired_state_mismatch");
        return new ServerTool.Result(false, "TARGET_BLOCKED:原版预检选出的状态不能确认满足目标要求，未放置或改写状态。", data);
    }

    public static ServerTool.Result assessDesired(Access access, BlockPos pos, int slot, Direction face,
                                                  BuildTarget target, BuildTarget.Cell cell) {
        var failure = assessDesired(access, pos, slot, face, cell::matches);
        return failure != null ? failure : desiredObject(access, target, cell);
    }

    private static ServerTool.Result desiredObject(Access access, BuildTarget target, BuildTarget.Cell cell) {
        if (!BuildObjects.paired(cell.authored())) return null;
        var other = BuildObjects.at(target, BuildObjects.other(cell.pos(),cell.desired()));
        if (!BuildObjects.problem(target, cell).isEmpty() || BuildObjects.secondary(cell.authored())
                || other == null || !other.matches(BuildObjects.partnerState(access.proposedState())))
            return failed("DENIED:双格对象须明确两半的对应状态，且只从主格执行原版放置。", false, 0);
        return null;
    }

    public static ServerTool.Result assessAt(CompanionPlayer player, BlockPos pos, int slot, Direction face,
                                             Vec3 eye, AABB body, TerrainEdits edits) {
        return assessAt(player, pos, slot, face, eye, body, edits, null);
    }
    public static ServerTool.Result assessAt(CompanionPlayer player, BlockPos pos, int slot, Direction face,
                                             Vec3 eye, AABB body, TerrainEdits edits, String temporaryPurpose) {
        if (pendingNativeTerrain(pos, edits))
            return failed("TARGET_BLOCKED:放置邻域依赖尚未落地的路线改动，先完成该段再按现场查询。", false, 0);
        var access = new PlayerAccess(player, eye, body, edits);
        access.temporaryPurpose = temporaryPurpose;
        return preflight(access, pos, slot, face);
    }

    /** Known native work states use edited contacts and collision; actual arrival always rechecks the live world. */
    public static ServerTool.Result assessPlannedAt(CompanionPlayer player, BlockPos pos, int slot, Direction face,
                                                    Vec3 eye, AABB body, TerrainEdits edits, String temporaryPurpose) {
        if (edits.equals(TerrainEdits.EMPTY)) return assessAt(player, pos, slot, face, eye, body, edits, temporaryPurpose);
        if (slot < 0 || slot >= Inventory.INVENTORY_SIZE)
            return failed("NO_ITEM:没有可用放置材料。", false, 0);
        var material = player.getInventory().getItem(slot);
        if (!(material.getItem() instanceof BlockItem item)
                || item.getClass() != BlockItem.class
                || !SupportPlacement.workPredictable(item)
                || !SupportPlacement.predictable(item)
                && (!player.level().isLoaded(pos) || !player.level().getBlockState(pos).isAir()
                || !player.level().getFluidState(pos).isEmpty()
                || !SupportPlacement.nativeWorkNeighborhood(item,pos,edits)))
            return assessAt(player, pos, slot, face, eye, body, edits, temporaryPurpose);
        if (!player.gameMode.isSurvival() || !player.getAbilities().mayBuild)
            return failed("DENIED:当前模式不允许生存放置。", false, 0);
        var failure = BuildRecordAccess.guard(player, pos, false, null);
        var site = new BuildSiteAccess(player);
        if (failure == null && temporaryPurpose != null)
            failure = player.buildSite().temporaryGuard(pos, temporaryPurpose, site);
        if (failure != null) return failure;
        if (temporaryPurpose != null && !temporaryPurpose.equals("support"))
            return failed("TARGET_BLOCKED:特殊临时工位须在实际现场核对。", false, 0);
        var level = player.level();
        var projected = new PlayerAccess(player, eye, body, edits);
        boolean planned = SupportPlacement.canPlanWorkBlock(level, BlockAim.forPlayer(player), pos, eye, body,
                material, Math.min(6.5, player.blockInteractionRange()), edits,
                near -> level.isInWorldBounds(near) && level.getWorldBorder().isWithinBounds(near)
                        && level.isLoaded(near) && level.mayInteract(player, near),
                candidate -> candidate == face && player.mayUseItemAt(pos, candidate, material),
                bounds -> level.isUnobstructed(player, net.minecraft.world.phys.shapes.Shapes.create(bounds)),
                hit -> projected.plannedState(pos, material, hit),
                state -> state.canSurvive(level,pos)
                        && player.buildSite().placementGuard(pos, state, site, temporaryPurpose != null) == null
                        && constructionExit(player, pos, state, body, edits) == null);
        return planned ? null : failed(
                "TARGET_BLOCKED:计划改动后的碰撞、点击面、材料、目标状态或放置条件尚未满足。", false, 0);
    }

    private static ServerTool.Result constructionExit(CompanionPlayer player, BlockPos pos, BlockState state,
                                                       AABB bounds, TerrainEdits edits) {
        if (!player.buildSite().active() || state == null) return null;
        return constructionExit(StandingGeometry.forPlayer(player,player.buildSite()::inScope),pos,state,bounds,edits);
    }

    static ServerTool.Result constructionExit(StandingGeometry geometry, BlockPos pos, BlockState state,
                                              AABB bounds, TerrainEdits edits) {
        var proposed = new java.util.HashMap<BlockPos,BlockState>();
        proposed.put(pos,state);
        if (BuildObjects.paired(state)) proposed.put(BuildObjects.other(pos,state),BuildObjects.partnerState(state));
        var center = bounds.getCenter();
        boolean open = geometry.preservesExit(
                (int)Math.floor(center.x),bounds.minY,(int)Math.floor(center.z),proposed,edits);
        if (open) return null;
        var data = new JsonObject();
        data.addProperty("placement_failure","would_seal_exit");
        return new ServerTool.Result(false,"TARGET_BLOCKED:放置会封死当前工作站位的最后出口；先换到外侧站位再补此格。",data);
    }

    static boolean pendingNativeTerrain(BlockPos pos, TerrainEdits edits) {
        for (BlockPos near : BlockPos.betweenClosed(pos.offset(-1, -1, -1), pos.offset(1, 1, 1))) {
            long cell = DigAStar.pack(near.getX(), near.getY(), near.getZ());
            if (edits.removed().contains(cell) || edits.placed().contains(cell)) return true;
        }
        return false;
    }

    public static boolean attachedAt(CompanionPlayer player, BlockPos pos, int slot,
                                     Vec3 eye, AABB body, TerrainEdits edits) {
        return attachedAt(player, pos, slot, eye, body, edits, null);
    }
    public static boolean attachedAt(CompanionPlayer player, BlockPos pos, int slot,
                                     Vec3 eye, AABB body, TerrainEdits edits, String temporaryPurpose) {
        for (Direction face : Direction.values())
            if (assessAt(player, pos, slot, face, eye, body, edits, temporaryPurpose) == null) return true;
        return false;
    }

    /** Route supports can depend on earlier edits; execution still uses the ordinary native placement path. */
    public static boolean attachedSupportAt(CompanionPlayer player, BlockPos pos, int slot,
                                           Vec3 eye, AABB body, TerrainEdits edits, String temporaryPurpose) {
        if (edits.equals(TerrainEdits.EMPTY))
            return attachedAt(player, pos, slot, eye, body, edits, temporaryPurpose);
        if (slot < 0 || slot >= Inventory.INVENTORY_SIZE
                || !PathMaterials.allowed(player.getInventory().getItem(slot))) return false;
        for (Direction face : Direction.values())
            if (assessPlannedAt(player, pos, slot, face, eye, body, edits, temporaryPurpose) == null) return true;
        return false;
    }

    public static boolean supported(BlockItem item) {
        Class<?> type = item.getClass();
        return type == BlockItem.class || type == BedItem.class || type == DoubleHighBlockItem.class
                || type == StandingAndWallBlockItem.class;
    }

    public static ServerTool.Result place(Access access, BlockPos pos, int slot, Direction face) {
        return placeDesired(access, pos, slot, face, null);
    }

    public static ServerTool.Result placeDesired(Access access, BlockPos pos, int slot, Direction face,
                                                  java.util.function.Predicate<BlockState> matches) {
        return placeDesired(access, pos, slot, face, matches, null, null);
    }
    public static ServerTool.Result placeDesired(Access access, BlockPos pos, int slot, Direction face,
                                                  BuildTarget target, BuildTarget.Cell cell) {
        return placeDesired(access, pos, slot, face, cell::matches, target, cell);
    }
    private static ServerTool.Result placeDesired(Access access, BlockPos pos, int slot, Direction face,
                                                  java.util.function.Predicate<BlockState> matches,
                                                  BuildTarget target, BuildTarget.Cell selected) {
        ServerTool.Result failure = matches == null ? preflight(access, pos, slot, face)
                : assessDesired(access, pos, slot, face, matches);
        if (failure != null) return failure;
        if (target != null && (failure = desiredObject(access, target, selected)) != null) return failure;
        Container inventory = access.inventory();
        ItemStack stack = inventory.getItem(slot);
        if (matches != null && !ItemStack.isSameItemSameComponents(stack, new ItemStack(stack.getItem())))
            return failed("NO_ITEM:建筑材料组件已变化，未使用非默认组件物品。", false, 0);
        BlockState before = access.state(pos);
        var footprint = BuildObjects.footprint(pos, access.proposedState());
        var objectBefore = new java.util.LinkedHashMap<BlockPos, BlockState>();
        footprint.forEach(p -> objectBefore.put(p, access.state(p)));
        var neighborhood = new java.util.LinkedHashMap<BlockPos, BlockState>();
        for (BlockPos near : BlockPos.betweenClosed(pos.offset(-1, -1, -1), pos.offset(1, 1, 1)))
            neighborhood.put(near.immutable(), access.state(near));
        int count = stack.getCount();
        JsonObject contact = access.contact();
        failure = access.beforePlace();
        if (failure != null) return failure;
        InteractionResult result;
        boolean threw = false;
        try {
            result = access.place(pos, stack, face);
        } catch (RuntimeException error) {
            result = InteractionResult.FAIL;
            threw = true;
        }
        BlockState after = access.state(pos);
        int consumed = Math.max(0, count - stack.getCount());
        var objectAfter = new java.util.LinkedHashMap<BlockPos, BlockState>();
        footprint.forEach(p -> objectAfter.put(p, access.state(p)));
        access.recordedObject(pos, objectBefore, objectAfter, consumed);
        boolean changed = before != after;
        inventory.setChanged();
        JsonObject data = new JsonObject();
        var affected = new JsonArray();
        int affectedCount = 0;
        boolean known = true;
        for (var entry : neighborhood.entrySet()) {
            var actual = access.state(entry.getKey());
            if (actual == null || entry.getValue() == null) { known = false; continue; }
            if (entry.getValue() == actual) continue;
            affectedCount++;
            if (affected.size() == 8) continue;
            var cell = new JsonObject();
            cell.addProperty("x", entry.getKey().getX()); cell.addProperty("y", entry.getKey().getY());
            cell.addProperty("z", entry.getKey().getZ());
            String previous = entry.getValue().toString(), current = actual.toString();
            cell.addProperty("before", com.neko.mcbot.common.WireSize.truncateToBytes(previous, 256));
            cell.addProperty("after", com.neko.mcbot.common.WireSize.truncateToBytes(current, 256));
            cell.addProperty("states_truncated", com.neko.mcbot.common.WireSize.utf8Bytes(previous) > 256
                    || com.neko.mcbot.common.WireSize.utf8Bytes(current) > 256);
            affected.add(cell);
        }
        if (after != null) data.addProperty("placed", BuiltInRegistries.BLOCK.getKey(after.getBlock()).toString());
        data.addProperty("consumed_count", consumed);
        data.addProperty("recording_known",access.recordingKnown());
        data.addProperty("changed", changed || affectedCount > 0);
        data.add("affected_cells", affected);
        data.addProperty("affected_count", affectedCount);
        data.addProperty("affected_cells_complete", known && affectedCount <= 8);
        data.addProperty("effect_scope", "target_neighborhood_radius_1_not_global");
        data.addProperty("effect_unknown", threw || !known);
        data.addProperty("x", pos.getX());
        data.addProperty("y", pos.getY());
        data.addProperty("z", pos.getZ());
        if (contact != null) data.add("placement_contact", contact.deepCopy());
        boolean matched = after != null && (matches == null || matches.test(after));
        if (footprint.size() == 2) {
            matched &= BuildObjects.paired(after) && !BuildObjects.secondary(after)
                    && objectAfter.get(BuildObjects.other(pos,after)) == BuildObjects.partnerState(after);
            if (target != null) matched &= BuildObjects.matches(target, selected, objectAfter::get);
            data.addProperty("object_cells", 2);
            data.addProperty("object_satisfied", matched);
        }
        data.addProperty("desired_satisfied", matched);
        if (threw || !result.consumesAction() || !changed || after == null || after.isAir() || consumed != 1 || !matched)
            return new ServerTool.Result(false, (threw ? "INTERNAL:" : "PLACE_FAILED:")
                    + "未确认目标格按要求成功放置；已消耗 " + consumed + " 个，目标状态"
                    + (changed ? "已变化" : "未变化") + "。保留实际效果，先核对现场与背包，勿自动重试。", data);
        return new ServerTool.Result(true, "在 (" + pos.toShortString() + ") 放置 "
                + data.get("placed").getAsString() + "，消耗 1 个；朝向/组件由原版放置流程处理。"
                + (contact == null ? "" : "实际点击=" + contact), data);
    }

    /** A path specifies the exact support cell but leaves the usable click face to the body. */
    public static ServerTool.Result placeAttached(Access access, BlockPos pos, int slot) {
        ServerTool.Result first = null;
        for (Direction face : List.of(Direction.UP, Direction.NORTH, Direction.SOUTH,
                Direction.WEST, Direction.EAST, Direction.DOWN)) {
            var failure = preflight(access, pos, slot, face);
            if (failure == null) return place(access, pos, slot, face);
            if (failure.feedback().startsWith("NO_ITEM:") || failure.feedback().startsWith("DENIED:"))
                return failure;
            if (first == null) first = failure;
        }
        return first;
    }

    private static ServerTool.Result preflight(Access access, BlockPos pos, int slot, Direction face) {
        ServerTool.Result failure = access.guard(pos);
        if (failure != null) return failure;
        Container inventory = access.inventory();
        if (slot < 0 || slot >= Math.min(Inventory.INVENTORY_SIZE, inventory.getContainerSize())) {
            return failed("NO_ITEM:背包里没有可用方块，先 inventory 核对材料。", false, 0);
        }
        ItemStack stack = inventory.getItem(slot);
        if (stack.isEmpty() || !(stack.getItem() instanceof BlockItem item)) {
            return failed("NO_ITEM:该槽不是可放置方块，先 inventory 核对材料。", false, 0);
        }
        if (!supported(item)) {
            return failed("DENIED:暂不支持该特殊方块物品的放置上下文，换普通方块。", false, 0);
        }
        failure = access.sight(pos, face);
        if (failure == null) failure = access.prepare(pos, stack, face);
        if (failure==null && access.proposedState()!=null
                && (BuildObjects.paired(access.proposedState())
                || access.proposedState().getBlock() instanceof net.minecraft.world.level.block.ChestBlock
                || access.proposedState().getBlock() instanceof net.minecraft.world.level.block.BarrelBlock)
                && !ItemStack.isSameItemSameComponents(stack,new ItemStack(stack.getItem())))
            return failed("DENIED:家具只支持默认组件材料，不搬入容器内容或其他载荷。",false,0);
        if (failure==null && access.proposedState()!=null
                && access.proposedState().getBlock() instanceof net.minecraft.world.level.block.ChestBlock
                && access.proposedState().getValue(net.minecraft.world.level.block.ChestBlock.TYPE)
                !=net.minecraft.world.level.block.state.properties.ChestType.SINGLE)
            return failed("DENIED:原版预检将合并邻箱；当前只支持独立空箱，未改动邻箱。",false,0);
        if (failure == null && BuildObjects.paired(access.proposedState())) {
            if (!ItemStack.isSameItemSameComponents(stack, new ItemStack(stack.getItem())))
                return failed("DENIED:双格对象只支持默认组件的真实材料。", false, 0);
            failure = objectGuard(access, pos, access.proposedState());
        }
        if (failure != null) {
            var data = failure.data() == null ? new JsonObject() : failure.data().deepCopy();
            data.addProperty("changed", false);
            data.addProperty("consumed_count", 0);
            return new ServerTool.Result(false, failure.feedback(), data);
        }
        return null;
    }

    /** The secondary native write is checked before the primary action can start. */
    private static ServerTool.Result objectGuard(Access access, BlockPos pos, BlockState proposed) {
        if (BuildObjects.secondary(proposed)) return failed("DENIED:不直接放置双格对象的次格。", false, 0);
        var other = BuildObjects.other(pos,proposed);
        var failure = access.guard(other);
        if (failure != null) return failure;
        var actual = access.state(other);
        if (actual == null) return failed("TARGET_LOST:双格对象的次格未知，未放置。", false, 0);
        if (!actual.isAir()) return failed("TARGET_BLOCKED:双格对象的次格须为真实空气，不覆盖已有或可替换方块。", false, 0);
        return access.secondaryCell(other, BuildObjects.partnerState(proposed));
    }

    /** Bounded alternatives are observations, never an instruction to replace the requested cell. */
    public static ServerTool.Result query(Access access, BlockPos pos, int slot, Direction face) {
        var failure = preflight(access, pos, slot, face);
        if (failure != null && (failure.feedback().startsWith("NO_ITEM:")
                || failure.feedback().startsWith("DENIED:"))) return failure;
        var data = failure == null || failure.data() == null ? new JsonObject() : failure.data().deepCopy();
        data.addProperty("query", true);
        data.addProperty("preflight_passed", failure == null);
        data.addProperty("native_validation_pending", true);
        data.addProperty("changed", false);
        data.addProperty("consumed_count", 0);
        data.addProperty("x", pos.getX()); data.addProperty("y", pos.getY()); data.addProperty("z", pos.getZ());
        if (failure == null && access.contact() != null)
            data.add("placement_contact", access.contact().deepCopy());
        var alternatives = new JsonArray();
        int checked = 1;
        if (failure != null) {
            data.addProperty("failure", failure.feedback());
            var nearby = new ArrayList<BlockPos>();
            for (int y = -1; y <= 1; y++) for (int x = -2; x <= 2; x++) for (int z = -2; z <= 2; z++)
                if (x != 0 || y != 0 || z != 0) nearby.add(pos.offset(x, y, z));
            nearby.sort(Comparator.comparingDouble((BlockPos p) -> p.distSqr(pos))
                    .thenComparingInt(BlockPos::getY).thenComparingInt(BlockPos::getX).thenComparingInt(BlockPos::getZ));
            for (BlockPos next : nearby) {
                checked++;
                if (preflight(access, next, slot, face) != null) continue;
                var point = new JsonObject();
                point.addProperty("x", next.getX()); point.addProperty("y", next.getY()); point.addProperty("z", next.getZ());
                point.addProperty("face", face.getName());
                alternatives.add(point);
                if (alternatives.size() == 6) break;
            }
        }
        data.addProperty("checked_positions", checked);
        data.add("alternatives", alternatives);
        var stands = failure == null ? new JsonArray() : access.standingPositions(pos, slot, face);
        data.add("stand_positions", stands);
        return new ServerTool.Result(true, "仅查询放置预检，未转头、移动、挖放或消耗。目标 "
                + pos.toShortString() + (failure == null ? " 通过当前预检。" : " 受阻：" + failure.feedback())
                + " 其他通过预检的位置（同face，最多6个）：" + alternatives
                + "。精确目标不能自行换格；若任务允许选址，再选择其中一格执行。"
                + "原目标的站位建议（整数脚格，最多6个）：" + stands + "；到位后须重查放置条件。"
                + "query.ok仅表示查询完成；执行前必须检查data.preflight_passed=true。"
                + "预检不保证原版物品最终接受，执行仍重新核对世界并以实际变化/消耗为准。", data);
    }

    record Look(float yaw, Direction[] order) {
        static Look toward(Vec3 direction) {
            float yaw = Mth.wrapDegrees((float) (Mth.atan2(direction.z, direction.x) * Mth.RAD_TO_DEG) - 90);
            float pitch = Mth.wrapDegrees((float) -(Mth.atan2(direction.y,
                    Math.sqrt(direction.x * direction.x + direction.z * direction.z)) * Mth.RAD_TO_DEG));
            float yawRadians = -yaw * Mth.DEG_TO_RAD, pitchRadians = pitch * Mth.DEG_TO_RAD;
            float x = Mth.sin(yawRadians), z = Mth.cos(yawRadians);
            float vertical = Mth.sin(pitchRadians), horizontal = Mth.cos(pitchRadians);
            Direction dx = x > 0 ? Direction.EAST : Direction.WEST;
            Direction dz = z > 0 ? Direction.SOUTH : Direction.NORTH;
            Direction dy = vertical < 0 ? Direction.UP : Direction.DOWN;
            var nearest = new ArrayList<>(Math.abs(x) > Math.abs(z) ? List.of(dx, dz) : List.of(dz, dx));
            float first = Math.max(Math.abs(x), Math.abs(z)) * horizontal;
            float second = Math.min(Math.abs(x), Math.abs(z)) * horizontal;
            // Match vanilla's horizontal-first and vertical-before-second tie rules.
            nearest.add(Math.abs(vertical) > first ? 0 : second > Math.abs(vertical) ? 2 : 1, dy);
            Direction[] order = {nearest.get(0), nearest.get(1), nearest.get(2),
                    nearest.get(2).getOpposite(), nearest.get(1).getOpposite(), nearest.get(0).getOpposite()};
            return new Look(yaw, order);
        }
        Direction horizontal() { return Direction.fromYRot(yaw); }
        Direction vertical() {
            for (Direction direction : order) if (direction.getAxis() == Direction.Axis.Y) return direction;
            throw new IllegalStateException("missing vertical direction");
        }
    }

    private static ServerTool.Result failed(String feedback, boolean changed, int consumed) {
        JsonObject data = new JsonObject();
        data.addProperty("changed", changed);
        data.addProperty("consumed_count", consumed);
        return new ServerTool.Result(false, feedback, data);
    }

    static ServerTool.Result checkSite(BlockPos pos, Site site) {
        String reason = null, detail = null;
        if (!site.replaceable()) {
            reason = "occupied_block";
            detail = "目标格已有 " + BuiltInRegistries.BLOCK.getKey(site.current().getBlock())
                    + "，当前物品不能原地替换；换空格，或先单独确认是否要挖除。";
        } else if (site.placed() == null || !site.survives()) {
            reason = "unsupported_state";
            detail = "原版无法在该格形成可存活的放置状态；查看支撑、接触面及多格空间后换位置。";
        } else if (site.collision().stream().anyMatch(box -> box.intersects(site.body()))) {
            reason = "self_collision";
            detail = "放置后的碰撞形状会占用自己的身体；先move_to exact=true走到另一空闲脚格，再核对目标。";
        } else if (!site.entityClear()) {
            reason = "entity_collision";
            detail = "放置后的碰撞形状被其他实体占用；等待让开或换位置。";
        }
        if (reason == null) return null;
        var data = new JsonObject();
        data.addProperty("placement_failure", reason);
        data.addProperty("x", pos.getX());
        data.addProperty("y", pos.getY());
        data.addProperty("z", pos.getZ());
        return new ServerTool.Result(false, "TARGET_BLOCKED:" + detail, data);
    }

    private static final class PlayerAccess implements Access {
        private final CompanionPlayer player;
        private final Vec3 eye;
        private final AABB bounds;
        private final boolean projected;
        private final TerrainEdits edits;
        private final List<BlockHitResult> contacts = new ArrayList<>(2);
        private BlockHitResult hit;
        private JsonObject contact;
        private BlockPlaceContext context;
        private BlockState proposedState;
        private String temporaryPurpose;

        private PlayerAccess(CompanionPlayer player, Vec3 eye, AABB bounds, TerrainEdits edits) {
            this.player = player;
            this.projected = eye != null;
            this.eye = projected ? eye : player.getEyePosition();
            this.bounds = projected ? bounds : player.getBoundingBox();
            this.edits = edits;
        }
        @Override public ServerTool.Result guard(BlockPos pos) {
            var level = player.level();
            var recordFailure=BuildRecordAccess.guard(player,pos,false,null);
            if (recordFailure!=null) return recordFailure;
            // Vanilla doors/beds and support/collision queries also read adjacent cells.
            for (BlockPos near : BlockPos.betweenClosed(pos.offset(-1, -1, -1), pos.offset(1, 1, 1))) {
                if (!level.isInWorldBounds(near) || !level.getWorldBorder().isWithinBounds(near)) {
                    return failed("DENIED:放置及相邻格超出世界范围，换位置。", false, 0);
                }
                if (!level.isLoaded(near)) return failed("TARGET_LOST:放置邻域未加载，先靠近。", false, 0);
                if (!level.mayInteract(player, near)) return failed("DENIED:世界规则不允许在该邻域放置。", false, 0);
            }
            if (!player.gameMode.isSurvival() || !player.getAbilities().mayBuild) {
                return failed("DENIED:当前玩家模式不允许生存放置。", false, 0);
            }
            if (projected && !level.noCollision(player, bounds))
                return failed("TARGET_BLOCKED:假想站位被实际方块或实体占用，须重新观察。", false, 0);
            if (temporaryPurpose != null) {
                var failure = player.buildSite().temporaryGuard(pos, temporaryPurpose, new BuildSiteAccess(player));
                if (failure != null) return failure;
            }
            return null;
        }
        @Override public Container inventory() { return player.getInventory(); }
        @Override public BlockState state(BlockPos pos) { return player.level().getBlockState(pos); }
        @Override public ServerTool.Result sight(BlockPos pos, Direction face) {
            contacts.clear();
            context = null;
            proposedState = null;
            hit = null;
            contact = null;
            BlockAim.Contact failure = null;
            var world = projected ? NavigationWork.view(BlockAim.forPlayer(player), eye,
                    positions(edits.removed()), positions(edits.placed())) : BlockAim.forPlayer(player);
            // The native context decides whether the clicked cell is replaced or placement is adjacent.
            for (BlockPos clicked : List.of(pos, pos.relative(face.getOpposite()))) {
                var contact = BlockAim.placementContact(world, clicked, face,
                        Math.min(6.5, player.blockInteractionRange()));
                if (contact.ok()) contacts.add(contact.hit());
                else if (failure == null || contact.failure().startsWith("TARGET_LOST:")
                        || contact.failure().startsWith("OUT_OF_REACH:")) failure = contact;
            }
            if (!contacts.isEmpty()) return null;
            var data = new JsonObject();
            data.addProperty("placement_failure", "no_clickable_face");
            return new ServerTool.Result(false, failure.failure(), data);
        }
        @Override public ServerTool.Result prepare(BlockPos pos, ItemStack stack, Direction face) {
            if (projected && occupiesWorkTarget(bounds, pos)) {
                var data = new JsonObject();
                data.addProperty("placement_failure", "standing_in_target");
                return new ServerTool.Result(false, "TARGET_BLOCKED:工作站位占用请求的放置格，须站到目标格外。", data);
            }
            BlockItem item = (BlockItem) stack.getItem();
            if (projected && !projectable(item, stack))
                return failed("TARGET_BLOCKED:该物品的多格、组件或特殊状态尚不支持假想站位预检，"
                        + "到合适站位后直接query核对原版条件。", false, 0);
            if (!player.mayUseItemAt(pos, face, stack))
                return failed("DENIED:当前物品或玩家规则不允许在此放置。", false, 0);
            for (BlockHitResult candidate : contacts) {
                var proposed = placementContext(stack, candidate);
                if (pos.equals(proposed.getClickedPos()) && proposed.canPlace()) {
                    context = proposed;
                    hit = candidate;
                    break;
                }
            }
            if (context == null) {
                var data = new JsonObject();
                data.addProperty("placement_failure", "wrong_landing");
                return new ServerTool.Result(false, "TARGET_BLOCKED:真实点击后的原版落位不是指定目标，"
                        + "或目标不能替换；不会强制改写落位。核对目标占位、face及邻接接触面。", data);
            }
            contact = new JsonObject();
            BlockPos clicked = hit.getBlockPos();
            contact.addProperty("x", clicked.getX());
            contact.addProperty("y", clicked.getY());
            contact.addProperty("z", clicked.getZ());
            contact.addProperty("face", hit.getDirection().getName());
            contact.addProperty("block", BuiltInRegistries.BLOCK.getKey(state(clicked).getBlock()).toString());
            BlockState desired = item instanceof StandingAndWallBlockItem
                    ? ((com.neko.mcbot.mixin.BlockItemPlacementAccess) item).mcbot$placementState(context)
                    : item.getBlock().getStateForPlacement(context);
            proposedState = desired;
            var siteFailure = player.buildSite().placementGuard(pos, desired, new BuildSiteAccess(player), temporaryPurpose != null);
            if (siteFailure != null) return siteFailure;
            if (temporaryPurpose != null && (!projectable(item, stack)
                    || !com.neko.mcbot.building.BuildSite.plainCube(desired)
                    || WorksiteProtection.facility(desired, desired.hasBlockEntity()) != temporaryPurpose.equals("workstation")))
                return failed("DENIED:临时工程只支持无组件的稳定单格整方块；workstation须无方块实体工位，support不可冒充工位。", false, 0);
            var geometryFailure = checkSite(pos, new Site() {
                @Override public BlockState current() { return state(pos); }
                @Override public boolean replaceable() { return context.canPlace(); }
                @Override public BlockState placed() { return desired; }
                @Override public boolean survives() { return desired.canSurvive(player.level(), pos); }
                @Override public List<AABB> collision() {
                    return desired.getCollisionShape(player.level(), pos, CollisionContext.of(player))
                            .move(pos.getX(), pos.getY(), pos.getZ()).toAabbs();
                }
                @Override public AABB body() { return bounds; }
                @Override public boolean entityClear() {
                    return player.level().isUnobstructed(player, desired
                            .getCollisionShape(player.level(), pos, CollisionContext.of(player))
                            .move(pos.getX(), pos.getY(), pos.getZ()));
                }
            });
            return geometryFailure != null ? geometryFailure : constructionExit(player,pos,desired,bounds,edits);
        }
        private BlockPlaceContext placementContext(ItemStack stack, BlockHitResult clickHit) {
            return placementContext(stack,clickHit,null);
        }
        private BlockState plannedState(BlockPos target, ItemStack stack, BlockHitResult clickHit) {
            BlockPos clicked=clickHit.getBlockPos();
            boolean futureSupport=edits.placed().contains(DigAStar.pack(clicked.getX(),clicked.getY(),clicked.getZ()));
            var prediction=placementContext(stack,clickHit,futureSupport ? target : null);
            if (!target.equals(prediction.getClickedPos())) return null;
            return ((BlockItem)stack.getItem()).getBlock().getStateForPlacement(prediction);
        }
        private BlockPlaceContext placementContext(ItemStack stack, BlockHitResult clickHit, BlockPos plannedLanding) {
            Vec3 click = clickHit.getLocation();
            Direction face = clickHit.getDirection();
            Vec3 toward = click.subtract(eye);
            var look = Look.toward(toward.lengthSqr() < 1e-10 ? player.getLookAngle() : toward);
            return new BlockPlaceContext(player, InteractionHand.MAIN_HAND, stack, clickHit) {
                @Override public BlockPos getClickedPos() {
                    return plannedLanding==null ? super.getClickedPos() : plannedLanding;
                }
                @Override public boolean replacingClickedOnBlock() {
                    return plannedLanding==null ? super.replacingClickedOnBlock()
                            : plannedLanding.equals(clickHit.getBlockPos());
                }
                @Override public Direction getHorizontalDirection() { return look.horizontal(); }
                @Override public float getRotation() { return look.yaw(); }
                @Override public Direction getNearestLookingDirection() { return look.order()[0]; }
                @Override public Direction getNearestLookingVerticalDirection() { return look.vertical(); }
                @Override public Direction[] getNearestLookingDirections() {
                    var ordered = new ArrayList<>(List.of(look.order()));
                    if (!replacingClickedOnBlock()) {
                        ordered.remove(face.getOpposite());
                        ordered.addFirst(face.getOpposite());
                    }
                    return ordered.toArray(Direction[]::new);
                }
            };
        }
        @Override public JsonObject contact() { return contact; }
        @Override public ServerTool.Result secondaryCell(BlockPos pos, BlockState proposed) {
            if (!player.mayUseItemAt(pos,context.getClickedFace(),context.getItemInHand()))
                return failed("DENIED:物品或玩家规则不允许双格对象的次格改动。",false,0);
            var failure = player.buildSite().placementGuard(pos, proposed, new BuildSiteAccess(player));
            if (failure != null) return failure;
            var shape = proposed.getCollisionShape(player.level(), pos, CollisionContext.of(player))
                    .move(pos.getX(), pos.getY(), pos.getZ());
            if (shape.toAabbs().stream().anyMatch(box -> box.intersects(bounds))
                    || !player.level().isUnobstructed(player, shape))
                return failed("TARGET_BLOCKED:双格对象的次格有身体或实体占位，未执行放置。", false, 0);
            return null;
        }
        @Override public ServerTool.Result beforePlace() {
            var failure=BuildRecordAccess.guard(player,context.getClickedPos(),false,null);
            if (failure==null) failure=player.buildSite().placementGuard(context.getClickedPos(),proposedState,
                    new BuildSiteAccess(player),temporaryPurpose!=null);
            if (failure==null && BuildObjects.paired(proposedState))
                failure=objectGuard(this,context.getClickedPos(),proposedState);
            if (failure==null) failure=constructionExit(player,context.getClickedPos(),proposedState,bounds,edits);
            return failure!=null ? failure : player.buildSite().charge();
        }
        @Override public void recordedObject(BlockPos primary, java.util.Map<BlockPos, BlockState> before,
                                              java.util.Map<BlockPos, BlockState> after, int consumed) {
            if (before.size() == 1) { recorded(primary,before.get(primary),after.get(primary),consumed); return; }
            player.buildSite().recordObjectPlacement(primary,before,after,consumed,new BuildSiteAccess(player));
        }
        @Override public void recorded(BlockPos pos, BlockState before, BlockState after, int consumed) {
            player.buildSite().recordPlacement(pos,before,after,consumed,new BuildSiteAccess(player));
            if (temporaryPurpose != null && consumed == 1)
                player.buildSite().placedTemporary(pos, before, after, temporaryPurpose, new BuildSiteAccess(player));
        }
        @Override public boolean recordingKnown() {
            var records=BuildRecordAccess.store(player);
            return records==null || records.healthy();
        }
        @Override public BlockState proposedState() { return proposedState; }
        @Override public JsonArray standingPositions(BlockPos pos, int slot, Direction face) {
            var result = new JsonArray();
            var points = WorkPositions.miningCandidates(pos, new WorkPositions.Access() {
                @Override public boolean standable(BlockPos feet) { return WorkPositions.standable(player, feet); }
                @Override public double distanceSquared(BlockPos feet) {
                    return player.distanceToSqr(feet.getX() + .5, feet.getY(), feet.getZ() + .5);
                }
                @Override public boolean workable(BlockPos feet) {
                    var geometry = StandingGeometry.forPlayer(player);
                    var foot = geometry.standing(feet.getX(), feet.getY(), feet.getZ(), TerrainEdits.EMPTY);
                    return foot != null && assessAt(player, pos, slot, face,
                            new Vec3(feet.getX() + .5, foot.height() + player.getEyeHeight(), feet.getZ() + .5),
                            geometry.body(feet.getX(), foot.height(), feet.getZ()), TerrainEdits.EMPTY) == null;
                }
            });
            for (var feet : points) {
                var foot = WorkPositions.foot(player, feet);
                if (foot == null) continue;
                var data = new JsonObject();
                data.addProperty("x", feet.getX()); data.addProperty("y", feet.getY()); data.addProperty("z", feet.getZ());
                data.addProperty("feet_y", foot.height());
                result.add(data);
            }
            return result;
        }
        @Override public InteractionResult place(BlockPos pos, ItemStack stack, Direction face) {
            if (projected) throw new IllegalStateException("a projected placement is read-only");
            player.facePoint(hit.getLocation());
            // Invoke placement directly: using a block must not open its menu or eat an item.
            InteractionResult result = ((BlockItem) stack.getItem()).place(context);
            if (result.consumesAction()) player.swing(InteractionHand.MAIN_HAND);
            return result;
        }
    }

    private static java.util.Set<BlockPos> positions(java.util.Set<Long> cells) {
        var out = new java.util.HashSet<BlockPos>();
        for (long cell : cells)
            out.add(new BlockPos(DigAStar.unpackX(cell), DigAStar.unpackY(cell), DigAStar.unpackZ(cell)));
        return out;
    }

    public static boolean projectable(BlockItem item, ItemStack stack) {
        if (!stack.getOrDefault(net.minecraft.core.component.DataComponents.BLOCK_STATE,
                net.minecraft.world.item.component.BlockItemStateProperties.EMPTY).isEmpty()) return false;
        var block = item.getBlock();
        if (!BuiltInRegistries.BLOCK.getKey(block).getNamespace().equals("minecraft")) return false;
        return block.getClass() == net.minecraft.world.level.block.Block.class
                || block instanceof net.minecraft.world.level.block.TransparentBlock
                || block instanceof net.minecraft.world.level.block.IronBarsBlock
                || block instanceof net.minecraft.world.level.block.RotatedPillarBlock
                || block instanceof net.minecraft.world.level.block.SlabBlock
                || block instanceof net.minecraft.world.level.block.StairBlock
                || block instanceof net.minecraft.world.level.block.CraftingTableBlock
                || block instanceof net.minecraft.world.level.block.AbstractFurnaceBlock
                || block instanceof net.minecraft.world.level.block.ChestBlock
                || block instanceof net.minecraft.world.level.block.BarrelBlock
                || block instanceof net.minecraft.world.level.block.LanternBlock
                || block instanceof net.minecraft.world.level.block.BaseTorchBlock
                || block instanceof net.minecraft.world.level.block.TrapDoorBlock
                || block instanceof net.minecraft.world.level.block.FenceBlock
                || block instanceof net.minecraft.world.level.block.WallBlock
                || block instanceof net.minecraft.world.level.block.CarpetBlock
                || BuildObjects.paired(block.defaultBlockState());
    }

}
