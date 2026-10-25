package com.neko.mcbot.building;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.properties.Half;

/** Drawing observations only; decorative stairs are allowed and the frozen design is never rewritten. */
public final class BuildDesignReview {
    private BuildDesignReview() { }

    public static JsonObject describe(BuildTarget target) {
        int missing = 0, reversed = 0, logsMissingAxis = 0;
        var samples = new JsonArray();
        for (var cell : target.cells()) {
            var state = cell.desired();
            if (state.getBlock() instanceof net.minecraft.world.level.block.RotatedPillarBlock
                    && !cell.properties().contains("axis")) logsMissingAxis++;
            if (!(state.getBlock() instanceof StairBlock)) continue;
            if (!cell.properties().contains("facing") || !cell.properties().contains("half")) missing++;
            if (state.getValue(StairBlock.HALF) != Half.BOTTOM) continue;
            var facing = state.getValue(StairBlock.FACING);
            var higher = target.index().get(cell.pos().relative(facing.getOpposite()).above());
            if (higher == null || higher.desired().getBlock() != state.getBlock()
                    || higher.desired().getValue(StairBlock.HALF) != Half.BOTTOM
                    || higher.desired().getValue(StairBlock.FACING) != facing) continue;
            reversed++;
            if (samples.size() < 4) {
                var sample = BuildTarget.coordinates(cell.pos());
                sample.addProperty("facing", facing.getName());
                sample.addProperty("higher_course", facing.getOpposite().getName());
                samples.add(sample);
            }
        }
        var data = new JsonObject();
        data.addProperty("stairs_missing_orientation", missing);
        data.addProperty("logs_missing_axis", logsMissingAxis);
        data.addProperty("stairs_rising_behind_back", reversed);
        data.add("samples", samples);
        if (missing > 0 || reversed > 0 || logsMissingAxis > 0) data.addProperty("advice",
                "检查原木axis和楼梯facing/half。柱axis=y，东西梁axis=x，南北梁axis=z；"
                + "未声明axis会接受任意轴向。楼梯facing指向高背，两坡应分别朝向屋脊。"
                + "这是设计提示，特殊装饰可保留；登记前核对，冻结后不自动改图。");
        return data;
    }
}
