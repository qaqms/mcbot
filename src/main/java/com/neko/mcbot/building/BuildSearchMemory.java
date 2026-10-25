package com.neko.mcbot.building;

import com.neko.mcbot.server.ServerTool;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;

/** Live worksite evidence survives a new Lua program, but never proves that no route exists. */
public final class BuildSearchMemory {
    private static final int LIMIT = 32;
    public record Facts(Vec3 position, String inventory, int edits, Map<BlockPos, BlockState> blocks) {
        public Facts { blocks = Map.copyOf(blocks); }
    }
    private final Map<String, Facts> stalled = new LinkedHashMap<>();

    public void clear() { stalled.clear(); }

    public ServerTool.Result check(String intent, Supplier<Facts> observe) {
        var previous = stalled.get(intent);
        if (previous == null) return null;
        var current = observe.get();
        if (current == null || previous.position().distanceToSqr(current.position()) >= .25 * .25
                || previous.edits() != current.edits() || !previous.inventory().equals(current.inventory())
                || !previous.blocks().equals(current.blocks())) {
            stalled.remove(intent);
            return null;
        }
        var data = new com.google.gson.JsonObject();
        data.addProperty("navigation_stalled", true);
        data.addProperty("unchanged_stalled_search", true);
        data.addProperty("model_reassessment_required", true);
        data.addProperty("goal_satisfied", false);
        data.addProperty("search_started", false);
        return new ServerTool.Result(false,
                "NAVIGATION_STALLED:同一工地工作路线此前已超时，身体、库存、施工改动和附近方块没有变化，"
                + "未重新启动相同搜索。只查询status或重新调用mc.build不会改变条件；"
                + "请选择其他工作目标或实际改变站位、可用工具、材料或通路后再续建。原图及已完成成果保留，尚未证明无路。",
                data);
    }

    public void terminal(String intent, ServerTool.Result result, Supplier<Facts> observe) {
        if (!result.ok() && result.feedback().startsWith("NAVIGATION_STALLED:")) {
            var facts = observe.get();
            if (facts == null) return;
            if (!stalled.containsKey(intent) && stalled.size() >= LIMIT)
                stalled.remove(stalled.keySet().iterator().next());
            stalled.put(intent, facts);
        } else if (result.ok()) stalled.remove(intent);
    }

    /** Bounded local geometry, not chunk clocks: unrelated updates cannot renew an empty search. */
    public static Facts observe(Vec3 position, String inventory, int edits, List<BlockPos> targets,
                                Function<BlockPos, BlockState> state) {
        if (targets.isEmpty() || targets.size() > 16) throw new IllegalArgumentException("work targets");
        var points = new java.util.ArrayList<>(targets);
        points.add(BlockPos.containing(position));
        var blocks = new LinkedHashMap<BlockPos, BlockState>();
        for (var center : points)
            for (var point : BlockPos.betweenClosed(center.offset(-2, -2, -2), center.offset(2, 2, 2))) {
                var cell = point.immutable();
                if (blocks.containsKey(cell)) continue;
                var actual = state.apply(cell);
                if (actual == null) return null;
                blocks.put(cell, actual);
            }
        return new Facts(position, inventory, edits, blocks);
    }
}
