package com.neko.mcbot.server.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.neko.mcbot.body.CompanionPlayer;
import com.neko.mcbot.server.ServerTool;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.Container;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * scan_area（朴素 v1）：半径内实体清单 + 特殊方块抽样（容器 BlockEntity/作物/矿类粗筛）。
 * 字符网格与密度控制在 M5 精修，这里先保证"看得见"。
 */
public final class ScanAreaTool implements ServerTool {

    /** 扫描主线程耗时（ms）：模型反馈不展示，但排查"世界信息慢"类投诉必用。 */
    public static volatile long lastScanMillis = -1;

    private static final int MAX_RADIUS = 32;
    private static final int MAX_BLOCK_SAMPLES = 24;

    @Override
    public String name() {
        return "scan_area";
    }

    @Override
    public Result run(CompanionPlayer companion, JsonObject args) {
        long t0 = System.nanoTime();
        int r = args.has("r") ? Math.min(MAX_RADIUS, Math.max(1, args.get("r").getAsInt())) : 16;
        var level = companion.level();

        List<String> entities = new ArrayList<>();
        for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class,
                companion.getBoundingBox().inflate(r))) {
            if (e == companion) {
                continue;
            }
            entities.add(String.format("%s %s hp=%.0f 距离%.0f%s",
                    e.getName().getString(),
                    e instanceof Monster ? "[敌对]" : "",
                    e.getHealth(),
                    e.distanceTo(companion),
                    e.isOnFire() ? " [着火]" : ""));
            if (entities.size() >= 20) {
                entities.add("……(还有更多，此处截断)");
                break;
            }
        }

        Map<String, Integer> specials = new LinkedHashMap<>();
        List<String> near = new ArrayList<>();
        BlockPos center = companion.blockPosition();
        int sampled = 0;
        for (int x = -r; x <= r && sampled < MAX_BLOCK_SAMPLES; x += 2) {
            for (int z = -r; z <= r && sampled < MAX_BLOCK_SAMPLES; z += 2) {
                for (int y = -2; y <= 2 && sampled < MAX_BLOCK_SAMPLES; y += 2) {
                    BlockPos p = center.offset(x, y, z);
                    BlockState st = level.getBlockState(p);
                    if (st.isAir()) {
                        continue;
                    }
                    String tag = describe(level, p, st, x, y, z);
                    if (tag != null) {
                        specials.merge(tag.split(" @")[0], 1, Integer::sum);
                        near.add(tag);
                        sampled++;
                    }
                }
            }
        }

        JsonObject data = new JsonObject();
        JsonArray ents = new JsonArray();
        entities.forEach(ents::add);
        data.add("entities", ents);
        JsonArray blocks = new JsonArray();
        near.forEach(blocks::add);
        data.add("special_blocks", blocks);
        data.addProperty("radius", r);

        StringBuilder fb = new StringBuilder();
        fb.append("半径 ").append(r).append(" 内：");
        fb.append(entities.isEmpty() ? "没有活物" : entities.size() + " 个活物(最多列 20)");
        fb.append("；特殊方块 ").append(specials.isEmpty() ? "无" : specials.toString());
        if (!near.isEmpty()) {
            fb.append("。近处: ").append(String.join("; ", near.subList(0, Math.min(6, near.size()))));
            fb.append("。@相对(dx,dy,dz) 是世界轴位移，加上我的位置即为目标坐标。");
        }
        lastScanMillis = (System.nanoTime() - t0) / 1_000_000;
        if (lastScanMillis > 50) {
            // 只在真慢时喊：扫描同步跑在服务器主线程，这条是"谁偷了 tick"的直接证据
            com.neko.mcbot.McbotMod.LOG.warn("[m3] scan_area r={} 耗时 {}ms（主线程同步）", r, lastScanMillis);
        }
        return new Result(true, fb.toString(), data);
    }

    /** 粗筛"值得告诉模型"的方块类型，其余返回 null（带相对坐标后缀，与 near 列表一致）。 */
    private static String describe(net.minecraft.server.level.ServerLevel level,
                                   BlockPos pos, BlockState st, int rx, int ry, int rz) {
        String reg = BuiltInRegistries.BLOCK.getKey(st.getBlock()).getPath();
        String at = " @相对(" + rx + "," + ry + "," + rz + ")";
        if (level.getBlockEntity(pos) instanceof Container) {
            return "容器:" + reg + at;
        }
        if (reg.contains("ore")) {
            return "矿石:" + reg + at;
        }
        if (reg.equals("crafting_table") || reg.equals("furnace")) {
            return "工作台/熔炉:" + reg + at;
        }
        if (st.getBlock() instanceof CropBlock crop) {
            return "作物:" + reg + "(成熟度 " + crop.getAge(st) + ")" + at;
        }
        return null;
    }
}
