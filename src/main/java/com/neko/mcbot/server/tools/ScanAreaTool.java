package com.neko.mcbot.server.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.neko.mcbot.McbotMod;
import com.neko.mcbot.body.CompanionPlayer;
import com.neko.mcbot.common.ScanCategory;
import com.neko.mcbot.common.ScanClassify;
import com.neko.mcbot.common.ScanFormat;
import com.neko.mcbot.common.ScanPlan;
import com.neko.mcbot.common.ScanSummary;
import com.neko.mcbot.server.ServerTool;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.Container;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;

/**
 * scan_area（R2-D：感知可行动化）。三条硬口径：
 * ① 方块按 {@link ScanCategory} 词表分类（container/ore/rock/workbench/farm），
 *    不再输出"容器:chest"这种要模型再推一遍的描述；
 * ② 坐标一律**绝对**，模型不给自己的位置做加法；
 * ③ 按距离分层（近环细列给坐标、中环计数+最近一格、远环只计数），
 *    所以回执稳定在 ~2KB 而不会因为半径变大而爆量。
 *
 * <p>分类/格式化/层带装配全部挪进 {@code common} 的零依赖类；这里只剩
 * "按计划读一格、把 MC 事实翻译成 (路径, 是否容器, 是否作物)" 这一层薄胶水。
 */
public final class ScanAreaTool implements ServerTool {

    /** 扫描主线程耗时（ms）：模型反馈不展示，但排查"世界信息慢"类投诉必用。 */
    public static volatile long lastScanMillis = -1;

    /** 近环细列阶段的耗时告警线（ms）：步长 1 是最贵的一层，超线即"谁偷了 tick"的证据。 */
    private static final long NEAR_WARN_MS = 50;

    private static final int MAX_RADIUS = 32;
    private static final int DEFAULT_RADIUS = 16;
    private static final int MAX_ENTITY_LINES = 20;

    @Override
    public String name() {
        return "scan_area";
    }

    @Override
    public Result run(CompanionPlayer companion, JsonObject args) {
        long t0 = System.nanoTime();
        int r = args.has("r")
                ? Math.min(MAX_RADIUS, Math.max(1, args.get("r").getAsInt()))
                : DEFAULT_RADIUS;
        var level = companion.level();
        BlockPos center = companion.blockPosition();
        var look = companion.getLookAngle();
        ScanSummary summary = new ScanSummary(center.getX(), center.getY(), center.getZ(), r);

        List<String> entities = scanEntities(companion, center, r, summary);

        ScanPlan.Plan plan = ScanPlan.build(r);
        int cells = 0;
        int unloaded = 0;
        long nearDone = 0;
        long loopT0 = System.nanoTime();   // 近环计时从这开始（实体那一段不算进"步长1 的代价"）
        for (ScanPlan.Cell cell : plan.cells()) {
            if (nearDone == 0 && cell.band() != ScanPlan.BAND_NEAR) {
                nearDone = System.nanoTime();   // 近环（步长 1）到此为止，单独计时
            }
            BlockPos p = center.offset(cell.dx(), cell.dy(), cell.dz());
            // 未加载格直接跳过：假玩家周围靠产品层的 5×5 垫子取票（见 CompanionChunkPads），
            // 在这里"顺手读一格"会把未加载区块同步生成出来，那是 R1-B 已经定性过的坑。
            if (!level.hasChunkAt(p)) {
                unloaded++;
                continue;
            }
            BlockState st = level.getBlockState(p);
            if (st.isAir()) {
                continue;
            }
            cells++;
            String path = BuiltInRegistries.BLOCK.getKey(st.getBlock()).getPath();
            ScanCategory cat = classify(level, p, st, path);
            if (cat == null) {
                continue;
            }
            summary.add(cat, path, p.getX(), p.getY(), p.getZ(), note(st, path, cat));
        }
        long blockDone = System.nanoTime();

        JsonObject data = new JsonObject();
        JsonArray ents = new JsonArray();
        entities.forEach(ents::add);
        data.add("entities", ents);
        List<String> ring = summary.lines();   // 只装配一次：下面 data 与 feedback 用同一份
        JsonArray lines = new JsonArray();
        ring.forEach(lines::add);
        data.add("summary_lines", lines);
        JsonArray groups = new JsonArray();
        summary.groupTokens().forEach(groups::add);
        data.add("groups", groups);
        JsonArray blocks = new JsonArray();
        summary.nearCells().forEach(blocks::add);
        data.add("special_blocks", blocks);   // 旧字段名保留（面板/回放按它取）：现在是近环逐格绝对坐标
        data.addProperty("radius", r);
        data.addProperty("cells_read", cells);
        data.addProperty("samples_planned", plan.cells().size());
        data.addProperty("truncated", plan.truncated());
        data.addProperty("unloaded", unloaded);

        StringBuilder fb = new StringBuilder();
        // 首行：模型每轮都要知道"我在哪、朝哪"，否则绝对坐标和它的意图对不上（R2-D）
        fb.append(ScanFormat.hereLine(center.getX(), center.getY(), center.getZ(),
                look.x, look.z)).append('\n');
        fb.append("半径 ").append(r).append(" 内：")
                .append(entities.isEmpty() ? "没有活物" : entities.size() + " 个活物(最多列 "
                        + MAX_ENTITY_LINES + ")");
        fb.append("；可行动方块 ").append(summary.total())
                .append(" 格（实读 ").append(cells).append(" 格/计划 ").append(plan.cells().size())
                .append(" 格，帽 ").append(ScanPlan.MAX_SAMPLES).append("）");
        if (plan.truncated()) {
            fb.append("[采样撞帽:远环未看全，想要更远的视野就走近了再扫]");
        }
        if (unloaded > 0) {
            fb.append("[周围 ").append(unloaded).append(" 格未加载，看不见]");
        }
        fb.append('\n');
        if (ring.isEmpty()) {
            fb.append("这一圈里没有可行动的东西（土/沙/木头一类不算目标）。");
        } else {
            fb.append(ScanSummary.join(ring));
        }
        if (!entities.isEmpty()) {
            fb.append("\n活物: ").append(String.join("; ", entities));
        }

        lastScanMillis = (blockDone - t0) / 1_000_000;
        long nearMs = ((nearDone == 0 ? blockDone : nearDone) - loopT0) / 1_000_000;
        if (lastScanMillis > NEAR_WARN_MS || nearMs > NEAR_WARN_MS) {
            // 只在真慢时喊：扫描同步跑在服务器主线程，这条是"谁偷了 tick"的直接证据；
            // near=近环（步长 1）单独耗时，它就是"该不该退到步长 2"的调帽依据。
            McbotMod.LOG.warn("[m3] scan_area r={} 耗时 {}ms（近环步长1 {}ms，主线程同步；计划{}格/读{}格）",
                    r, lastScanMillis, nearMs, plan.cells().size(), cells);
        }
        return new Result(true, fb.toString(), data);
    }

    /** 容器判定只在"确有 BlockEntity"时走：省掉逐格 BlockEntity 查表（那是本工具最贵的一跳）。 */
    private static ScanCategory classify(net.minecraft.server.level.ServerLevel level,
                                         BlockPos p, BlockState st, String path) {
        boolean container = st.hasBlockEntity() && level.getBlockEntity(p) instanceof Container;
        return ScanClassify.classify(path, container, st.getBlock() instanceof CropBlock);
    }

    /** 附带一句可直接行动的细节：作物给成熟度（决定"现在能不能收"）。 */
    private static String note(BlockState st, String path, ScanCategory cat) {
        if (cat == ScanCategory.FARM && st.getBlock() instanceof CropBlock crop) {
            return "成熟度" + crop.getAge(st) + "/" + crop.getMaxAge();
        }
        return null;
    }

    private static List<String> scanEntities(CompanionPlayer companion, BlockPos center, int r,
                                            ScanSummary summary) {
        var level = companion.level();
        List<String> out = new ArrayList<>();
        for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class,
                companion.getBoundingBox().inflate(r))) {
            if (e == companion) {
                continue;
            }
            BlockPos ep = e.blockPosition();
            boolean hostile = e instanceof Monster;
            if (hostile) {
                // 敌对生物也走同一套分类词表：让"远处有几个僵尸"和"远处有几格矿"一样可统计
                summary.add(ScanCategory.HOSTILE,
                        BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).getPath(),
                        ep.getX(), ep.getY(), ep.getZ(), null);
            }
            if (out.size() >= MAX_ENTITY_LINES) {
                out.add("……(还有更多，此处截断)");
                break;
            }
            out.add(String.format("%s%s @(%d,%d,%d) %s hp=%.0f%s",
                    e.getName().getString(),
                    hostile ? "[敌对]" : "",
                    ep.getX(), ep.getY(), ep.getZ(),
                    ScanFormat.dist(distance(center, ep)),
                    e.getHealth(),
                    e.isOnFire() ? " [着火]" : ""));
        }
        return out;
    }

    private static double distance(BlockPos a, BlockPos b) {
        double dx = a.getX() - b.getX();
        double dy = a.getY() - b.getY();
        double dz = a.getZ() - b.getZ();
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }
}
