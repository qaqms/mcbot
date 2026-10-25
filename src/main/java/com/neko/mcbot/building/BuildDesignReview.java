package com.neko.mcbot.building;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.properties.Half;

/** Drawing observations only; decorative stairs are allowed and the frozen design is never rewritten. */
public final class BuildDesignReview {
    private BuildDesignReview() { }

    public static JsonObject describe(BuildTarget target) {
        int missing = 0, reversed = 0, logsMissingAxis = 0;
        var samples = new JsonArray();
        int minX = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE, minY = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE;
        int roofCells = 0;
        boolean residential = false;
        for (var cell : target.cells()) {
            var state = cell.desired();
            if (!state.isAir()) {
                minX = Math.min(minX, cell.pos().getX());
                minY = Math.min(minY, cell.pos().getY());
                minZ = Math.min(minZ, cell.pos().getZ());
                maxX = Math.max(maxX, cell.pos().getX());
                maxY = Math.max(maxY, cell.pos().getY());
                maxZ = Math.max(maxZ, cell.pos().getZ());
            }
            if (state.getBlock() instanceof StairBlock || state.getBlock() instanceof SlabBlock) roofCells++;
            if (state.getBlock() instanceof DoorBlock || state.getBlock() instanceof ChestBlock
                    || state.getBlock() instanceof BarrelBlock || state.is(net.minecraft.world.level.block.Blocks.CRAFTING_TABLE)
                    || BuildObjects.bed(state)) residential = true;
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
        var warnings = new JsonArray();
        if (minX != Integer.MAX_VALUE) {
            int width = maxX - minX + 1, depth = maxZ - minZ + 1, height = maxY - minY + 1;
            data.addProperty("footprint_width", width);
            data.addProperty("footprint_depth", depth);
            data.addProperty("volume_height", height);
            if (residential && (width < 9 || depth < 9)) warnings.add("compact_residential_footprint");
            if (residential && height < 6) warnings.add("low_residential_volume");
            if (residential && roofCells == 0) warnings.add("roof_profile_missing");
            if (residential && roofCells > 0) {
                int baseMinX = Integer.MAX_VALUE, baseMaxX = Integer.MIN_VALUE;
                int baseMinZ = Integer.MAX_VALUE, baseMaxZ = Integer.MIN_VALUE;
                for (var cell : target.cells()) if (!cell.desired().isAir() && cell.pos().getY() == minY) {
                    baseMinX = Math.min(baseMinX, cell.pos().getX());
                    baseMaxX = Math.max(baseMaxX, cell.pos().getX());
                    baseMinZ = Math.min(baseMinZ, cell.pos().getZ());
                    baseMaxZ = Math.max(baseMaxZ, cell.pos().getZ());
                }
                final int bodyMinX = baseMinX == Integer.MAX_VALUE ? minX : baseMinX;
                final int bodyMaxX = baseMaxX == Integer.MIN_VALUE ? maxX : baseMaxX;
                final int bodyMinZ = baseMinZ == Integer.MAX_VALUE ? minZ : baseMinZ;
                final int bodyMaxZ = baseMaxZ == Integer.MIN_VALUE ? maxZ : baseMaxZ;
                boolean overhang = target.cells().stream()
                        .filter(c -> !c.desired().isAir()
                                && (c.desired().getBlock() instanceof StairBlock
                                || c.desired().getBlock() instanceof SlabBlock))
                        .anyMatch(c -> c.pos().getX() < bodyMinX || c.pos().getX() > bodyMaxX
                                || c.pos().getZ() < bodyMinZ || c.pos().getZ() > bodyMaxZ);
                if (!overhang) warnings.add("roof_no_overhang");
            }
        }
        data.add("design_warnings", warnings);
        if (missing > 0 || reversed > 0 || logsMissingAxis > 0 || warnings.size() > 0) {
            StringBuilder advice = new StringBuilder();
            if (missing > 0 || reversed > 0 || logsMissingAxis > 0) {
                advice.append("检查原木axis和楼梯facing/half。柱axis=y，东西梁axis=x，南北梁axis=z；")
                        .append("未声明axis会接受任意轴向。楼梯facing指向高背，两坡应分别朝向屋脊。");
            }
            if (warnings.size() > 0) {
                if (advice.length() > 0) advice.append(' ');
                advice.append("住宅设计建议：主要活动区净高默认4至5格，不含地板和屋顶实体，")
                        .append("按实际顶、梁及吊灯核对；volume_height只是整图包围盒高度，不证明室内净高。")
                        .append("先保证足够净使用空间和至少一处入口变化，")
                        .append("使用连续坡屋顶、山墙/脊线与至少一格檐口，不要登记成小平顶方盒。")
                        .append("design_warnings只提示设计缺项，不改写已登记图纸。");
            }
            advice.append("这是设计提示，特殊装饰可保留；登记前核对，冻结后不自动改图。");
            data.addProperty("advice", advice.toString());
        }
        return data;
    }
}
