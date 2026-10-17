package com.neko.mcbot.server;

import com.neko.mcbot.body.CompanionPlayer;
import com.neko.mcbot.path.NavigationGeometry;
import com.neko.mcbot.path.NavigationGoal;
import com.neko.mcbot.path.TerrainEdits;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** A placement intent supplies a work predicate to the existing route, never an extra action. */
public record PlacementWork(BlockItem item, Direction face, String temporaryPurpose) {
    public PlacementWork(BlockItem item, Direction face) { this(item, face, null); }
    @FunctionalInterface public interface Assessor {
        ServerTool.Result assess(Vec3 eye, AABB bounds, TerrainEdits edits);
    }

    public ServerTool.Result ready(CompanionPlayer body) {
        int slot = BlockPlacement.findSlot(body.getInventory(), item);
        if (slot < 0) return new ServerTool.Result(false, "NO_ITEM:缺少请求的放置材料，先核对inventory。", null);
        if (!BlockPlacement.projectable(item, body.getInventory().getItem(slot)))
            return new ServerTool.Result(false, "TARGET_BLOCKED:该物品的特殊、多格或组件状态尚不支持放置工作导航。"
                    + "可先选安全站位，再直接place_block query核对。", null);
        return null;
    }

    public NavigationGoal goal(CompanionPlayer body, BlockPos target, NavigationGeometry geometry) {
        return goal(target, geometry, body.getEyeHeight(), body.getBbWidth(), body.getBbHeight(),
                Math.min(6.5, body.blockInteractionRange()),
                (eye, bounds, edits) -> BlockPlacement.assessAt(body, target,
                        BlockPlacement.findSlot(body.getInventory(), item), face, eye, bounds, edits, temporaryPurpose));
    }

    public static NavigationGoal goal(BlockPos target, NavigationGeometry geometry, double eyeHeight,
                                      double width, double height, double reach, Assessor assess) {
        return new NavigationGoal() {
            @Override public Bounds estimateBounds() {
                int r = (int) Math.ceil(reach + 2);
                return new Bounds(target.getX() - r, (int) Math.floor(target.getY() - eyeHeight - r - 1),
                        target.getZ() - r, target.getX() + r,
                        (int) Math.ceil(target.getY() - eyeHeight + r + 1), target.getZ() + r);
            }
            @Override public double remaining(int x, int y, int z) {
                double dx = x - target.getX(), dz = z - target.getZ();
                double dy = Math.max(0, Math.abs(y + eyeHeight - target.getY()) - 1);
                return Math.max(0, Math.sqrt(dx * dx + dy * dy + dz * dz) - reach - 1.5);
            }
            @Override public boolean reached(int x, int y, int z) { return reached(x, y, z, TerrainEdits.EMPTY); }
            @Override public boolean reached(int x, int y, int z, TerrainEdits edits) {
                if (geometry == null || remaining(x, y, z) > 0) return false;
                var foot = geometry.foot(x, y, z, edits);
                if (foot == null) return false;
                var clear = geometry.clearance(x, foot.height(), z, x, foot.height(), z, edits);
                if (!clear.known() || !clear.blockers().isEmpty()) return false;
                double half = width / 2;
                var bounds = new AABB(x + .5 - half, foot.height(), z + .5 - half,
                        x + .5 + half, foot.height() + height, z + .5 + half);
                if (BlockPlacement.occupiesWorkTarget(bounds, target)) return false;
                return assess.assess(new Vec3(x + .5, foot.height() + eyeHeight, z + .5), bounds, edits) == null;
            }
        };
    }

    public ServerTool.Result assess(CompanionPlayer body, BlockPos target) {
        if (BlockPlacement.occupiesWorkTarget(body.getBoundingBox(), target))
            return new ServerTool.Result(false, "TARGET_BLOCKED:身体占用了放置目标格，未认定工作到达。", null);
        var access = temporaryPurpose == null ? BlockPlacement.forPlayer(body) : BlockPlacement.forTemporary(body, temporaryPurpose);
        var failure = BlockPlacement.assess(access, target, BlockPlacement.findSlot(body.getInventory(), item), face);
        if (failure != null) return failure;
        var data = new com.google.gson.JsonObject();
        data.addProperty("preflight_passed", true);
        data.addProperty("native_validation_pending", true);
        if (access.contact() != null) data.add("placement_contact", access.contact().deepCopy());
        return new ServerTool.Result(true, "当前放置预检通过，尚未放置或消费。", data);
    }

    public static ServerTool.Result finish(ServerTool.Result moved, java.util.function.Supplier<ServerTool.Result> assess) {
        if (!moved.ok()) return moved;
        var current = assess.get();
        var data = moved.data() == null ? new com.google.gson.JsonObject() : moved.data().deepCopy();
        data.addProperty("work_kind", "place");
        data.addProperty("can_work_here", current.ok());
        if (current.data() != null) data.add("placement_preflight", current.data().deepCopy());
        if (!current.ok()) data.addProperty("work_failure", current.feedback());
        return new ServerTool.Result(current.ok(), current.ok()
                ? moved.feedback() + " 放置站位预检通过；尚未执行place_block，执行前仍须重验。"
                : current.feedback() + " 已有移动效果保留，放置前提已变化，未放置或消费。", data);
    }
}
