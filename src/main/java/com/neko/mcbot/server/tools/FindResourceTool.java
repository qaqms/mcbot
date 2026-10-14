package com.neko.mcbot.server.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.neko.mcbot.body.CompanionPlayer;
import com.neko.mcbot.common.ResourceLocationHelper;
import com.neko.mcbot.server.ServerTool;
import com.neko.mcbot.server.WorkPositions;
import com.neko.mcbot.server.MiningWork;
import com.neko.mcbot.server.BlockAim;
import com.neko.mcbot.server.ExplorationPoints;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;

/** Bounded nearest-first sampling of explicit block ids/tags. Unknown cells stay unknown. */
public final class FindResourceTool implements ServerTool {
    public static final int MAX_SAMPLES = 4096;
    public static final int MAX_RESULTS = 16;
    public static final int MAX_WORK_CHECKS = 24;
    public static final int MAX_WORK_TRACES = 2048;
    private static final List<BlockPos> OFFSETS = offsets();
    record Input(int radius, int offset, BlockPos expectedCenter, List<String> targets, Predicate<BlockState> matches) {
    }
    interface Access {
        BlockPos center();
        boolean readable(BlockPos pos);
        BlockState state(BlockPos pos);
        default void work(JsonObject target, BlockPos pos) { }
        default void work(JsonObject target, BlockPos pos, BlockAim.TraceBudget budget) { work(target, pos); }
        default void stands(JsonObject target, BlockPos pos, BlockAim.TraceBudget budget) { }
        default JsonArray frontiers() { return new JsonArray(); }
    }
    @Override public String name() { return "find_resource"; }
    @Override public Result run(CompanionPlayer companion, JsonObject args) {
        return execute(args, new Access() {
            @Override public BlockPos center() { return companion.blockPosition(); }
            @Override public boolean readable(BlockPos pos) {
                return companion.level().isInWorldBounds(pos)
                        && companion.level().getWorldBorder().isWithinBounds(pos)
                        && companion.level().getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4) != null;
            }
            @Override public BlockState state(BlockPos pos) {
                var chunk = companion.level().getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);
                if (chunk == null) throw new IllegalStateException("chunk left during server-thread scan");
                return chunk.getBlockState(pos);
            }
            @Override public void work(JsonObject target, BlockPos pos, BlockAim.TraceBudget budget) {
                var plan = MiningWork.plan(companion, pos, companion.getEyePosition(), companion.blockPosition(), budget);
                target.addProperty("can_work_here", plan.ok() && plan.blockers().isEmpty());
                target.addProperty("can_clear_here", plan.ok());
                target.addProperty("clear_blocks", plan.blockers().size());
                if (!plan.ok()) target.addProperty("work_failure", plan.aim().failure());
                target.add("stand_positions", new JsonArray());
            }
            @Override public void stands(JsonObject target, BlockPos pos, BlockAim.TraceBudget budget) {
                target.add("stand_positions", WorkPositions.describeMining(companion, pos, budget));
            }
            @Override public JsonArray frontiers() { return ExplorationPoints.describe(companion); }
        });
    }

    static Input arguments(JsonObject args) {
        try {
            for (String key : args.keySet())
                if (!List.of("targets", "r", "work", "offset", "frontiers", "scan_center").contains(key)) return null;
            if (args.has("work") && (!args.get("work").isJsonPrimitive()
                    || !args.get("work").getAsJsonPrimitive().isBoolean())) return null;
            if (args.has("frontiers") && (!args.get("frontiers").isJsonPrimitive()
                    || !args.get("frontiers").getAsJsonPrimitive().isBoolean())) return null;
            int radius = args.has("r") ? integer(args.get("r")) : 8;
            if (radius < 1 || radius > 16 || !args.has("targets") || !args.get("targets").isJsonArray()) return null;
            int offset = args.has("offset") ? integer(args.get("offset")) : 0;
            if (offset < 0 || offset > 16384 || offset % MAX_SAMPLES != 0) return null;
            if (offset > 0 && OFFSETS.stream().filter(p -> p.distSqr(BlockPos.ZERO) <= radius * radius).count() <= offset)
                return null;
            BlockPos expectedCenter = null;
            if (args.has("scan_center")) {
                var center = args.getAsJsonObject("scan_center");
                if (!center.keySet().equals(java.util.Set.of("x", "y", "z"))) return null;
                expectedCenter = BreakBlockTool.readPos(center);
                if (expectedCenter == null) return null;
            }
            if (offset > 0 && expectedCenter == null) return null;
            var values = args.getAsJsonArray("targets");
            if (values.isEmpty() || values.size() > 8) return null;
            List<String> names = new ArrayList<>();
            List<Predicate<BlockState>> predicates = new ArrayList<>();
            for (var value : values) {
                if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) return null;
                String name = value.getAsString();
                if (name.isBlank() || name.length() > 128) return null;
                if (name.startsWith("#")) {
                    var tag = TagKey.create(Registries.BLOCK, ResourceLocationHelper.of(name.substring(1)));
                    if (!BuiltInRegistries.BLOCK.getTagOrEmpty(tag).iterator().hasNext()) return null;
                    predicates.add(state -> state.is(tag));
                } else {
                    Block block = BuiltInRegistries.BLOCK.getOptional(ResourceLocationHelper.of(name)).orElse(null);
                    if (block == null || block.defaultBlockState().isAir()) return null;
                    predicates.add(state -> state.is(block));
                }
                names.add(name);
            }
            return new Input(radius, offset, expectedCenter, List.copyOf(names),
                    state -> predicates.stream().anyMatch(p -> p.test(state)));
        } catch (RuntimeException invalid) {
            return null;
        }
    }

    private static int integer(com.google.gson.JsonElement value) {
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) throw new IllegalArgumentException();
        return value.getAsBigDecimal().intValueExact();
    }

    static Result execute(JsonObject args, Access access) {
        Input input = arguments(args);
        if (input == null) return new Result(false,
                "DENIED:targets 为1-8个有效方块ID或非空方块#标签，r为1-16整数；"
                        + "offset须使用同中心/半径回执的next_offset，frontiers须为布尔。", null);
        BlockPos center = access.center();
        if (input.expectedCenter() != null && !center.equals(input.expectedCenter()))
            return new Result(false, "TARGET_LOST:身体已离开分页查询中心；用offset=0从最新位置重查。", null);
        boolean work = args.has("work") && args.get("work").getAsBoolean();
        int planned = 0, sampled = 0, read = 0, unknown = 0, matches = 0;
        var results = new JsonArray();
        var observed = new java.util.HashMap<BlockPos, BlockState>();
        var candidates = new ArrayList<JsonObject>();
        StringBuilder lines = new StringBuilder();
        for (BlockPos offset : OFFSETS) {
            int distance = offset.getX() * offset.getX() + offset.getY() * offset.getY() + offset.getZ() * offset.getZ();
            if (distance > input.radius() * input.radius()) break;
            planned++;
            if (planned <= input.offset()) continue;
            if (sampled >= MAX_SAMPLES) continue;
            sampled++;
            BlockPos pos = center.offset(offset);
            if (!access.readable(pos)) { unknown++; continue; }
            BlockState state = access.state(pos);
            if (work) observed.put(pos, state);
            read++;
            if (!input.matches().test(state)) continue;
            matches++;
            if (!work && candidates.size() >= MAX_RESULTS) continue;
            String id = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
            if (id.length() > 128) continue;
            var target = new JsonObject();
            target.addProperty("block", id);
            target.addProperty("x", pos.getX());
            target.addProperty("y", pos.getY());
            target.addProperty("z", pos.getZ());
            target.addProperty("distance", Math.sqrt(distance));
            candidates.add(target);
        }
        int workChecks = 0;
        var traceBudget = new BlockAim.TraceBudget(MAX_WORK_TRACES);
        if (work) {
            // Rank all observed hits before spending scarce geometric checks or output slots.
            for (var target : candidates) {
                BlockPos pos = point(target);
                int exposure = 2;
                for (Direction direction : Direction.values()) {
                    var neighbor = observed.get(pos.relative(direction));
                    if (neighbor == null) exposure = Math.min(exposure, 1);
                    else if (!neighbor.blocksMotion()) exposure = 0;
                }
                target.addProperty("exposure_rank", exposure);
            }
            candidates.sort(Comparator.comparingInt(t -> t.get("exposure_rank").getAsInt()));
            for (var target : candidates) {
                if (workChecks >= MAX_WORK_CHECKS || !traceBudget.available()) break;
                access.work(target, point(target), traceBudget);
                target.addProperty("work_checked", true);
                workChecks++;
            }
            // Assess current-eye candidates first so one buried target cannot spend every stance ray.
            candidates.sort(Comparator.comparingInt(FindResourceTool::workRank));
            for (var target : candidates) {
                if (!traceBudget.available()) break;
                if (!target.has("work_checked")) continue;
                access.stands(target, point(target), traceBudget);
            }
            // Unchecked candidates stay diagnostic; never advertise them as actionable.
            candidates.sort(Comparator.comparingInt(FindResourceTool::workRank));
        }
        for (var target : candidates) {
            if (results.size() >= MAX_RESULTS) break;
            if (work && !target.has("work_checked")) {
                target.addProperty("work_checked", false);
                target.addProperty("can_work_here", false);
                target.addProperty("can_clear_here", false);
                target.add("stand_positions", new JsonArray());
                target.addProperty("work_failure", "WORK_BUDGET:本次几何评估预算已到，该候选可工作性未知。");
            }
            BlockPos pos = point(target);
            String id = target.get("block").getAsString();
            results.add(target);
            lines.append("\n").append(id).append(" @(").append(pos.getX()).append(",")
                    .append(pos.getY()).append(",").append(pos.getZ()).append(")");
            if (work) {
                lines.append("；");
                if (target.has("can_work_here") && target.get("can_work_here").getAsBoolean())
                    lines.append("当前可直接挖");
                else if (target.has("can_clear_here") && target.get("can_clear_here").getAsBoolean())
                    lines.append("当前可经安全清理挖掘，遮挡=").append(target.get("clear_blocks"));
                else lines.append(target.has("work_failure") ? target.get("work_failure").getAsString() : "工作可行性未知");
                var stands = target.getAsJsonArray("stand_positions");
                if (stands != null && !stands.isEmpty()) {
                    lines.append("；工作站位=").append(stands);
                    lines.append("；work_target=")
                            .append("{\"x\":").append(pos.getX()).append(",\"y\":").append(pos.getY())
                            .append(",\"z\":").append(pos.getZ()).append("}");
                } else lines.append("；没有已核实工作站位");
            }
        }
        var data = new JsonObject();
        data.add("targets", results);
        data.addProperty("matches_observed", matches);
        data.addProperty("samples_planned", planned);
        data.addProperty("samples_examined", sampled);
        data.addProperty("cells_read", read);
        data.addProperty("unknown", unknown);
        int end = input.offset() + sampled;
        int nextOffset = end < planned ? end : -1;
        data.addProperty("truncated", input.offset() > 0 || nextOffset >= 0);
        data.addProperty("offset", input.offset());
        data.addProperty("next_offset", nextOffset);
        var scanCenter = new JsonObject();
        scanCenter.addProperty("x", center.getX());
        scanCenter.addProperty("y", center.getY());
        scanCenter.addProperty("z", center.getZ());
        data.add("scan_center", scanCenter);
        data.addProperty("radius", input.radius());
        if (args.has("frontiers") && args.get("frontiers").getAsBoolean())
            data.add("exploration_points", nextOffset == -1 ? access.frontiers() : new JsonArray());
        data.addProperty("results_truncated", matches > results.size());
        data.addProperty("no_targets", results.isEmpty());
        data.addProperty("work_checks", workChecks);
        data.addProperty("work_traces", traceBudget.used());
        data.addProperty("work_truncated", work && (workChecks < candidates.size() || !traceBudget.available()));
        String feedback = "定向查找 " + input.targets() + "，半径 " + input.radius() + "，实读 "
                + read + "/" + planned + " 格，未知 " + unknown + " 格，采样上限 " + MAX_SAMPLES
                + "；列出 " + results.size() + " 个候选（最多16）。" + lines
                + (work ? "\n优先露出候选，再按已核对的直接工作/局部清理/站位排序；几何评估最多24个，未检查为未知。"
                + "有工作站位时选择该脚格move_to exact=true，并携带对应work_target和clear_obstructions=true；"
                + "不要把资源方块坐标当作脚格。清理仍需原任务权限，站位不证明路线可达。" : "")
                + "\n这是已读格的方块观测，不证明路径可达或掉落数量；未找到不等于周边没有资源。"
                + " 本页offset=" + input.offset() + "，next_offset=" + nextOffset + "。"
                + (nextOffset >= 0 ? "同中心/半径可续查下一页，当前未看全。" : "")
                + "不要靠挖掘探查或原地反复改变半径；采集可用gather有界换区寻找，空结果不代表整项任务受阻。"
                + (args.has("frontiers") && args.get("frontiers").getAsBoolean()
                ? " exploration_points只是已加载落脚样本，不证明路径可达；探索须walk_only=true实际移动后重查。" : "");
        return new Result(true, feedback, data);
    }

    private static BlockPos point(JsonObject target) {
        return new BlockPos(target.get("x").getAsInt(), target.get("y").getAsInt(), target.get("z").getAsInt());
    }

    private static int workRank(JsonObject target) {
        if (target.has("can_work_here") && target.get("can_work_here").getAsBoolean()) return 0;
        if (target.has("can_clear_here") && target.get("can_clear_here").getAsBoolean()) return 1;
        if (target.has("stand_positions") && !target.getAsJsonArray("stand_positions").isEmpty()) return 2;
        return target.has("work_checked") ? 3 : 4;
    }

    private static List<BlockPos> offsets() {
        var offsets = new ArrayList<BlockPos>();
        for (int x = -16; x <= 16; x++) for (int y = -16; y <= 16; y++) for (int z = -16; z <= 16; z++) {
            if (x * x + y * y + z * z <= 256) offsets.add(new BlockPos(x, y, z));
        }
        offsets.sort(Comparator.comparingInt((BlockPos p) -> p.getX() * p.getX() + p.getY() * p.getY() + p.getZ() * p.getZ())
                .thenComparingInt(BlockPos::getY).thenComparingInt(BlockPos::getX).thenComparingInt(BlockPos::getZ));
        return List.copyOf(offsets);
    }
}
