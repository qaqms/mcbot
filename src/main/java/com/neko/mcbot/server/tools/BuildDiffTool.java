package com.neko.mcbot.server.tools;

import com.google.gson.JsonObject;
import com.neko.mcbot.body.CompanionPlayer;
import com.neko.mcbot.building.BuildSurvey;
import com.neko.mcbot.building.BuildTarget;
import com.neko.mcbot.server.BlockPlacement;
import com.neko.mcbot.server.ServerTool;
import com.neko.mcbot.server.TerrainSnapshots;
import com.neko.mcbot.server.WorksiteProtection;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Set;
import java.util.function.Function;
import java.util.function.LongSupplier;

/** Shared target survey exposed as a read-only game tool. Construction is a separate bounded action. */
public final class BuildDiffTool implements ServerTool {
    @Override public String name() { return "build_diff"; }

    @Override public Result run(CompanionPlayer body, JsonObject args) {
        return report(args, target -> {
            var placement = BlockPlacement.forPlayer(body);
            return new BuildSurvey.Access() {
                @Override public BlockState state(BlockPos pos) {
                    return inBounds(pos) && body.level().isLoaded(pos) ? body.level().getBlockState(pos) : null;
                }
                @Override public boolean inBounds(BlockPos pos) {
                    return body.level().isInWorldBounds(pos) && body.level().getWorldBorder().isWithinBounds(pos);
                }
                @Override public boolean protectedCell(BlockPos pos) {
                    return WorksiteProtection.protectedCell(pos, p -> state(p) != null,
                            this::state, p -> {
                                var value = state(p);
                                return value != null && value.hasBlockEntity();
                            }, body.workTargets()::protects);
                }
                @Override public boolean near(BlockPos pos) {
                    double range = Math.min(6.5, body.blockInteractionRange()) + 1;
                    return body.getEyePosition().distanceToSqr(net.minecraft.world.phys.Vec3.atCenterOf(pos)) <= range * range;
                }
                @Override public BlockPlacement.Access placement() { return placement; }
                @Override public JsonObject terrain() {
                    var data = new JsonObject();
                    data.addProperty("dimension", body.level().dimension().identifier().toString());
                    var chunks = new JsonObject();
                    var visited = new java.util.HashSet<String>();
                    for (var cell : target.cells()) {
                        var pos = cell.pos();
                        String key = (pos.getX() >> 4) + "," + (pos.getZ() >> 4);
                        if (!visited.add(key)) continue;
                        var observed = TerrainSnapshots.around(body.level(), pos, 0).getAsJsonObject("chunks");
                        for (var entry : observed.entrySet()) chunks.add(entry.getKey(), entry.getValue());
                    }
                    data.add("chunks", chunks);
                    return data;
                }
            };
        }, System::nanoTime);
    }

    static Result report(JsonObject args, Function<BuildTarget, BuildSurvey.Access> access, LongSupplier clock) {
        BuildTarget target;
        int offset, materialOffset;
        try {
            BuildTarget.keys(args, Set.of("target", "offset", "material_offset", "expected_revision"));
            target = BuildTarget.parse(args.getAsJsonObject("target"));
            offset = args.has("offset") ? BuildTarget.integer(args.get("offset"), 0, 112) : 0;
            materialOffset = args.has("material_offset") ? BuildTarget.integer(args.get("material_offset"), 0, 112) : 0;
            if (offset >= target.cells().size() || offset % BuildSurvey.PAGE != 0 || materialOffset % BuildSurvey.PAGE != 0)
                throw new IllegalArgumentException("page");
            if ((offset != 0 || materialOffset != 0) && !args.has("expected_revision"))
                throw new IllegalArgumentException("page_revision_required");
            long types = target.cells().stream().map(BuildTarget.Cell::material).map(BuildTarget.Material::item)
                    .filter(java.util.Objects::nonNull).distinct().count();
            if (materialOffset >= types && materialOffset != 0) throw new IllegalArgumentException("material_page");
            if (args.has("expected_revision")) {
                String expected = BuildTarget.string(args.get("expected_revision"), 64);
                if (!expected.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("revision");
                if (!expected.equals(target.revision()))
                    return new Result(false, "TARGET_REVISION_CHANGED:输入目标已变化，未沿用另一修订的差异或分配。", null);
            }
        } catch (RuntimeException invalid) {
            return new Result(false, "DENIED:建筑目标须为anchor/rotation及cells或fill之一，最多128格；"
                    + "相对坐标±32，无重复格，状态名/值须在真实注册表中；两种分页偏移须为16的倍数。", null);
        }
        return BuildSurvey.query(target, access.apply(target), offset, materialOffset, clock);
    }
}
