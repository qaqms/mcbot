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
        try { return report(com.neko.mcbot.server.BuildRecordAccess.expanded(body,args),
                target -> access(body, target), System::nanoTime); }
        catch (RuntimeException invalid) { return new Result(false,"DENIED:实例身份或修订不符，未查询另一实例。",null); }
    }

    public static BuildSurvey.Access access(CompanionPlayer body, BuildTarget target) {
            var placement = BlockPlacement.forPlayer(body);
            var geometry = com.neko.mcbot.server.StandingGeometry.forPlayer(body);
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
                @Override public boolean clearable(BuildTarget.Cell cell) {
                    return body.buildSite().same(target.revision())
                            && body.buildSite().miningGuard(cell.pos(), target.revision(), false,
                            new com.neko.mcbot.server.BuildSiteAccess(body)) == null;
                }
                @Override public boolean near(BlockPos pos) {
                    double range = Math.min(6.5, body.blockInteractionRange()) + 1;
                    return body.getEyePosition().distanceToSqr(net.minecraft.world.phys.Vec3.atCenterOf(pos)) <= range * range;
                }
                @Override public BlockPlacement.Access placement() { return placement; }
                @Override public JsonObject surface(BlockPos pos) {
                    return observedSurface(geometry, pos, this::state);
                }
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
    }

    static JsonObject observedSurface(com.neko.mcbot.server.StandingGeometry geometry, BlockPos pos,
                                      Function<BlockPos, BlockState> state) {
        for (int dy : new int[]{1, 0, 2, -1, 3, -2, -3}) {
            var feet = pos.above(dy);
            var foot = geometry.standing(feet.getX(), feet.getY(), feet.getZ(),
                    com.neko.mcbot.path.TerrainEdits.EMPTY);
            if (foot == null) continue;
            var support = new BlockPos(com.neko.mcbot.path.DigAStar.unpackX(foot.support()),
                    com.neko.mcbot.path.DigAStar.unpackY(foot.support()),
                    com.neko.mcbot.path.DigAStar.unpackZ(foot.support()));
            var actual = state.apply(support);
            if (actual == null) continue;
            var data = new JsonObject();
            data.add("feet", BuildTarget.coordinates(feet));
            data.addProperty("feet_y", foot.height());
            data.add("support", BuildTarget.coordinates(support));
            data.addProperty("support_block", net.minecraft.core.registries.BuiltInRegistries.BLOCK
                    .getKey(actual.getBlock()).toString());
            return data;
        }
        return null;
    }

    static Result report(JsonObject args, Function<BuildTarget, BuildSurvey.Access> access, LongSupplier clock) {
        BuildTarget target;
        int offset, materialOffset;
        boolean verify;
        try {
            BuildTarget.keys(args, Set.of("target", "offset", "material_offset", "expected_revision", "verify_only"));
            if (args.has("verify_only") && (!args.get("verify_only").isJsonPrimitive()
                    || !args.get("verify_only").getAsJsonPrimitive().isBoolean())) throw new IllegalArgumentException();
            verify = args.has("verify_only") && args.get("verify_only").getAsBoolean();
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
        return verify ? BuildSurvey.verify(target, access.apply(target), offset, clock)
                : BuildSurvey.query(target, access.apply(target), offset, materialOffset, clock);
    }
}
