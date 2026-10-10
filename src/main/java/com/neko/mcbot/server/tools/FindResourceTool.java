package com.neko.mcbot.server.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.neko.mcbot.body.CompanionPlayer;
import com.neko.mcbot.common.ResourceLocationHelper;
import com.neko.mcbot.server.ServerTool;
import net.minecraft.core.BlockPos;
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
    private static final List<BlockPos> OFFSETS = offsets();
    record Input(int radius, List<String> targets, Predicate<BlockState> matches) {
    }
    interface Access {
        BlockPos center();
        boolean readable(BlockPos pos);
        BlockState state(BlockPos pos);
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
        });
    }

    static Input arguments(JsonObject args) {
        try {
            for (String key : args.keySet()) if (!List.of("targets", "r").contains(key)) return null;
            int radius = args.has("r") ? integer(args.get("r")) : 8;
            if (radius < 1 || radius > 16 || !args.has("targets") || !args.get("targets").isJsonArray()) return null;
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
            return new Input(radius, List.copyOf(names), state -> predicates.stream().anyMatch(p -> p.test(state)));
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
                "DENIED:targets 为1-8个有效方块ID或非空方块#标签，r为1-16整数；如 oak_log 或 #minecraft:logs。", null);
        BlockPos center = access.center();
        int planned = 0, sampled = 0, read = 0, unknown = 0, matches = 0;
        var results = new JsonArray();
        StringBuilder lines = new StringBuilder();
        for (BlockPos offset : OFFSETS) {
            int distance = offset.getX() * offset.getX() + offset.getY() * offset.getY() + offset.getZ() * offset.getZ();
            if (distance > input.radius() * input.radius()) break;
            planned++;
            if (sampled >= MAX_SAMPLES) continue;
            sampled++;
            BlockPos pos = center.offset(offset);
            if (!access.readable(pos)) { unknown++; continue; }
            BlockState state = access.state(pos);
            read++;
            if (!input.matches().test(state)) continue;
            matches++;
            if (results.size() >= MAX_RESULTS) continue;
            String id = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
            if (id.length() > 128) continue;
            var target = new JsonObject();
            target.addProperty("block", id);
            target.addProperty("x", pos.getX());
            target.addProperty("y", pos.getY());
            target.addProperty("z", pos.getZ());
            target.addProperty("distance", Math.sqrt(distance));
            results.add(target);
            lines.append("\n").append(id).append(" @(").append(pos.getX()).append(",")
                    .append(pos.getY()).append(",").append(pos.getZ()).append(")");
        }
        var data = new JsonObject();
        data.add("targets", results);
        data.addProperty("matches_observed", matches);
        data.addProperty("samples_planned", planned);
        data.addProperty("samples_examined", sampled);
        data.addProperty("cells_read", read);
        data.addProperty("unknown", unknown);
        data.addProperty("truncated", sampled < planned);
        data.addProperty("results_truncated", matches > results.size());
        data.addProperty("no_targets", results.isEmpty());
        String feedback = "定向查找 " + input.targets() + "，半径 " + input.radius() + "，实读 "
                + read + "/" + planned + " 格，未知 " + unknown + " 格，采样上限 " + MAX_SAMPLES
                + "；列出 " + results.size() + " 个候选（最多16）。" + lines
                + "\n这是已读格的方块观测，不证明可达、露出、当前能挖或掉落数量；未找到不等于周边没有资源。"
                + (sampled < planned ? "采样撞帽，远处未看全。" : "")
                + "不要靠挖掘探查或连续改变半径空转；受阻时说明已查范围。";
        return new Result(true, feedback, data);
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
