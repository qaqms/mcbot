package com.neko.mcbot.server;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.neko.mcbot.body.CompanionPlayer;
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
        default JsonObject contact() { return null; }
        InteractionResult place(BlockPos pos, ItemStack stack, Direction face);
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
        return new PlayerAccess(player);
    }

    public static boolean supported(BlockItem item) {
        Class<?> type = item.getClass();
        return type == BlockItem.class || type == BedItem.class || type == DoubleHighBlockItem.class
                || type == StandingAndWallBlockItem.class;
    }

    public static ServerTool.Result place(Access access, BlockPos pos, int slot, Direction face) {
        ServerTool.Result failure = preflight(access, pos, slot, face);
        if (failure != null) return failure;
        Container inventory = access.inventory();
        ItemStack stack = inventory.getItem(slot);
        BlockState before = access.state(pos);
        int count = stack.getCount();
        JsonObject contact = access.contact();
        InteractionResult result = access.place(pos, stack, face);
        BlockState after = access.state(pos);
        int consumed = Math.max(0, count - stack.getCount());
        boolean changed = before != after;
        inventory.setChanged();
        if (!result.consumesAction() || !changed || after.isAir() || consumed != 1) {
            return failed("PLACE_FAILED:未确认目标格成功放置；已消耗 " + consumed
                    + " 个，目标状态" + (changed ? "已变化" : "未变化")
                    + "。先核对现场与背包，勿自动重试。", changed, consumed);
        }
        JsonObject data = new JsonObject();
        data.addProperty("placed", BuiltInRegistries.BLOCK.getKey(after.getBlock()).toString());
        data.addProperty("consumed_count", consumed);
        data.addProperty("changed", true);
        data.addProperty("x", pos.getX());
        data.addProperty("y", pos.getY());
        data.addProperty("z", pos.getZ());
        if (contact != null) data.add("placement_contact", contact.deepCopy());
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
        if (failure != null) {
            var data = failure.data() == null ? new JsonObject() : failure.data().deepCopy();
            data.addProperty("changed", false);
            data.addProperty("consumed_count", 0);
            return new ServerTool.Result(false, failure.feedback(), data);
        }
        return null;
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
        return new ServerTool.Result(true, "仅查询放置预检，未转头、移动、挖放或消耗。目标 "
                + pos.toShortString() + (failure == null ? " 通过当前预检。" : " 受阻：" + failure.feedback())
                + " 其他通过预检的位置（同face，最多6个）：" + alternatives
                + "。精确目标不能自行换格；若任务允许选址，再选择其中一格执行。"
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
        private final List<BlockHitResult> contacts = new ArrayList<>(2);
        private BlockHitResult hit;
        private JsonObject contact;
        private BlockPlaceContext context;

        private PlayerAccess(CompanionPlayer player) { this.player = player; }
        @Override public ServerTool.Result guard(BlockPos pos) {
            var level = player.level();
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
            return null;
        }
        @Override public Container inventory() { return player.getInventory(); }
        @Override public BlockState state(BlockPos pos) { return player.level().getBlockState(pos); }
        @Override public ServerTool.Result sight(BlockPos pos, Direction face) {
            contacts.clear();
            context = null;
            hit = null;
            contact = null;
            BlockAim.Contact failure = null;
            var world = BlockAim.forPlayer(player);
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
            BlockItem item = (BlockItem) stack.getItem();
            // The item's native selector can choose a wall variant instead of getBlock().
            if (item instanceof StandingAndWallBlockItem) return null;
            BlockState desired = item.getBlock().getStateForPlacement(context);
            return checkSite(pos, new Site() {
                @Override public BlockState current() { return state(pos); }
                @Override public boolean replaceable() { return context.canPlace(); }
                @Override public BlockState placed() { return desired; }
                @Override public boolean survives() { return desired.canSurvive(player.level(), pos); }
                @Override public List<AABB> collision() {
                    return desired.getCollisionShape(player.level(), pos, CollisionContext.of(player))
                            .move(pos.getX(), pos.getY(), pos.getZ()).toAabbs();
                }
                @Override public AABB body() { return player.getBoundingBox(); }
                @Override public boolean entityClear() {
                    return player.level().isUnobstructed(player, desired
                            .getCollisionShape(player.level(), pos, CollisionContext.of(player))
                            .move(pos.getX(), pos.getY(), pos.getZ()));
                }
            });
        }
        private BlockPlaceContext placementContext(ItemStack stack, BlockHitResult clickHit) {
            Vec3 click = clickHit.getLocation();
            Direction face = clickHit.getDirection();
            Vec3 toward = click.subtract(player.getEyePosition());
            var look = Look.toward(toward.lengthSqr() < 1e-10 ? player.getLookAngle() : toward);
            return new BlockPlaceContext(player, InteractionHand.MAIN_HAND, stack, clickHit) {
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
        @Override public InteractionResult place(BlockPos pos, ItemStack stack, Direction face) {
            player.facePoint(hit.getLocation());
            // Invoke placement directly: using a block must not open its menu or eat an item.
            InteractionResult result = ((BlockItem) stack.getItem()).place(context);
            if (result.consumesAction()) player.swing(InteractionHand.MAIN_HAND);
            return result;
        }
    }

}
