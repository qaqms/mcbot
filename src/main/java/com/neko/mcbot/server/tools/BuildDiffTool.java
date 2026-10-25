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
        try {
            var selected = new BuildTarget[1];
            var result = report(args,input -> selected[0] = com.neko.mcbot.server.BuildRecordAccess.resolve(body,input),
                    target -> access(body, target), System::nanoTime);
            if (result.ok() && selected[0] != null && !args.getAsJsonObject("target").has("instance_id")) {
                boolean retained = body.buildSite().drafts().remember(selected[0]);
                result.data().addProperty("draft_retained", retained);
                if (retained) result.data().add("target", com.neko.mcbot.building.BuildDrafts.reference(selected[0]));
                if (retained && args.getAsJsonObject("target").has("draft_revision")
                        && args.has("verify_only") && args.get("verify_only").getAsBoolean())
                    body.buildSite().retainVerifiedDraft(selected[0]);
            }
            if (result.ok() && body.buildSite().active())
                result.data().addProperty("worksite_revision", body.buildSite().target().revision());
            return result;
        }
        catch (RuntimeException invalid) { return new Result(false,"DENIED:目标格式、实例身份或修订不符，未查询另一实例。"
                + "expected_revision是内容哈希，不是instance_id；已登记建筑用run_program执行return mc.build()，"
                + "或set_goal单独operation=query取回原条件，不从摘要猜写实例或重建图纸。",null); }
    }

    public static BuildSurvey.Access access(CompanionPlayer body, BuildTarget target) {
            var placement = BlockPlacement.forPlayer(body);
            var sight = com.neko.mcbot.server.BlockAim.forPlayer(body);
            var geometry = com.neko.mcbot.server.StandingGeometry.forPlayer(body);
            return new BuildSurvey.Access() {
                private com.neko.mcbot.server.MiningTools miningTools;
                @Override public JsonObject miningTool(BlockPos pos, BlockState state) {
                    if (miningTools == null) miningTools = com.neko.mcbot.server.MiningTools.forPlayer(body);
                    return com.neko.mcbot.server.MiningTools.describe(miningTools.best(state), state,
                            state.getDestroySpeed(body.level(), pos));
                }
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
                @Override public boolean repairPending(BlockPos pos) { return body.buildSite().repairPending(pos); }
                @Override public boolean bodyObstructsTarget() {
                    return body.buildSite().same(target.revision()) && body.buildSite().obstructs(body.getBoundingBox());
                }
                @Override public boolean clearable(BuildTarget.Cell cell) {
                    return body.buildSite().same(target.revision())
                            && body.buildSite().miningGuard(cell.pos(), target.revision(), false,
                            new com.neko.mcbot.server.BuildSiteAccess(body)) == null;
                }
                @Override public String clearReason(BuildTarget.Cell cell) {
                    if (!body.buildSite().same(target.revision())) return "build_site_not_registered";
                    var failure = body.buildSite().miningGuard(cell.pos(), target.revision(), false,
                            new com.neko.mcbot.server.BuildSiteAccess(body));
                    return failure == null ? "" : failure.feedback();
                }
                @Override public boolean near(BlockPos pos) {
                    double range = Math.min(6.5, body.blockInteractionRange()) + 1;
                    return body.getEyePosition().distanceToSqr(net.minecraft.world.phys.Vec3.atCenterOf(pos)) <= range * range;
                }
                @Override public com.neko.mcbot.server.BlockAim.Anchor placementAnchor(BuildTarget.Cell cell) {
                    return com.neko.mcbot.server.PlacementWork.anchor(sight, cell.pos(), null, cell);
                }
                @Override public BlockPlacement.Access placement() { return placement; }
                @Override public String workContext() {
                    var facts=new StringBuilder().append(body.getX()).append('|').append(body.getY())
                            .append('|').append(body.getZ());
                    for (int slot=0;slot<body.getInventory().getContainerSize();slot++) {
                        var stack=body.getInventory().getItem(slot);
                        facts.append('|').append(stack.getCount()).append(':')
                                .append(net.minecraft.world.item.ItemStack.hashItemAndComponents(stack));
                    }
                    return facts.toString();
                }
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
        return report(args,BuildTarget::parse,access,clock);
    }
    private static Result report(JsonObject args, Function<JsonObject,BuildTarget> resolve,
                                 Function<BuildTarget, BuildSurvey.Access> access, LongSupplier clock) {
        BuildTarget target;
        int offset, materialOffset, cursor;
        boolean verify, work;
        try {
            BuildTarget.keys(args, Set.of("target", "offset", "material_offset", "expected_revision", "verify_only",
                    "work_scan", "work_cursor"));
            if (args.has("verify_only") && (!args.get("verify_only").isJsonPrimitive()
                    || !args.get("verify_only").getAsJsonPrimitive().isBoolean())) throw new IllegalArgumentException();
            verify = args.has("verify_only") && args.get("verify_only").getAsBoolean();
            if (args.has("work_scan") && (!args.get("work_scan").isJsonPrimitive()
                    || !args.get("work_scan").getAsJsonPrimitive().isBoolean())) throw new IllegalArgumentException();
            work=args.has("work_scan") && args.get("work_scan").getAsBoolean();
            cursor=args.has("work_cursor") ? BuildTarget.integer(args.get("work_cursor"),0,BuildTarget.LIMIT-1) : 0;
            if (work && (verify || args.has("offset") && args.get("offset").getAsInt()!=0
                    || args.has("material_offset")) || !work && args.has("work_cursor")) throw new IllegalArgumentException();
            target = resolve.apply(args.getAsJsonObject("target"));
            offset = args.has("offset") ? BuildTarget.integer(args.get("offset"), 0, BuildTarget.LIMIT-BuildSurvey.PAGE) : 0;
            materialOffset = args.has("material_offset") ? BuildTarget.integer(args.get("material_offset"), 0, BuildTarget.LIMIT-BuildSurvey.PAGE) : 0;
            if (offset >= target.cells().size() || offset % BuildSurvey.PAGE != 0 || materialOffset % BuildSurvey.PAGE != 0)
                throw new IllegalArgumentException("page");
            if (cursor>=target.cells().size()) throw new IllegalArgumentException("cursor");
            if ((offset != 0 || materialOffset != 0 || cursor!=0) && !args.has("expected_revision"))
                return new Result(false, "DENIED:非零offset/material_offset/work_cursor续页须携带首张回执的target_revision，"
                        + "填入expected_revision，并保持同一target；Lua明细续页用mc.build_diff(target,revision,offset)。", null);
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
            return new Result(false, "DENIED:建筑目标须为anchor/rotation及cells、fill或regions之一，最多16384格；"
                    + "也可用本任务实际返回的draft_revision引用或有效实例引用；草稿失效先build_record draft_list，跨任务用持久实例。"
                    + "相对坐标±32，cells无重复格、regions按次序覆盖；状态名/值须在真实注册表中，"
                    + "offset/material_offset须为16的倍数，work_cursor须配work_scan且沿原修订。", null);
        }
        return work ? BuildSurvey.workScan(target,access.apply(target),cursor,clock)
                : verify ? BuildSurvey.verify(target, access.apply(target), offset, clock)
                : BuildSurvey.query(target, access.apply(target), offset, materialOffset, clock);
    }
}
