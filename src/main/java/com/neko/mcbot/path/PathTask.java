package com.neko.mcbot.path;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.neko.mcbot.McbotMod;
import com.neko.mcbot.body.CompanionPlayer;
import com.neko.mcbot.server.ServerTool.Result;
import com.neko.mcbot.task.TickTask;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ClientboundBlockDestructionPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;

/**
 * 路径任务（M8）：SEARCH（分帧 A*）→ 需要点头的世界 → NEED_CONFIRM 出结果
 * → EXECUTE（逐节点：挖（真计时）→ 放（消耗背包）→ 落位）→ 到达。
 *
 * 执行期复核（DESIGN §8）：每提交 20 个节点，重验接下来 5 个节点的状态
 * （该清的还清着、支撑还在、放过的没被拆）；变了就地从当前位置重新规划，
 * 重规划上限 2 次，再变就 NO_PATH 交还给模型决策。
 *
 * 取消/超时：onAbort 清掉进行中的裂纹动画；future 由调度器统一完成。
 */
public final class PathTask extends TickTask {

    private static final int EXPAND_PER_TICK = 300; // 主线程每拍最多展开节点数
    private static final int MAX_NODES = 8000;
    private static final int MAX_DIGS = 128;
    private static final int RECHECK_EVERY = 20;
    private static final int RECHECK_AHEAD = 5;
    private static final int MAX_REPLAN = 2;

    private enum Phase { SEARCH, EXECUTE }

    private final BlockPos target;
    private final boolean mayAlterTerrain;
    private final ServerLevel level;

    private Phase phase = Phase.SEARCH;
    private DigAStar search;
    private LevelDigSampler sampler;
    /** 搜索期 memo 装饰（R1-S2）；执行/复核/确认清单永远走裸 sampler live 读——快照只当启发式。 */
    private MemoDigSampler memo;
    /** 验尸判图不可信的重开次数；超 2 次本轮直接裸读不再 memo（防"边改边搜"死重启）。 */
    private int memoRestarts;
    /** liveify 后的真实挖/放计数（NEED_CONFIRM 清单与到达文案的唯一事实源）。 */
    private int planDigs;
    private int planPlaces;
    private List<DigAStar.Step> path;
    private int cursor;            // 下一个要进入的节点（path[0] 是起点）
    private int executed;          // 已提交节点计数（复核节拍用）
    private int replans;
    private int tickWarmup;        // 进场让世界加载/防抖的缓冲拍

    // 挖掘子状态
    private BlockState digTarget;
    private BlockPos digPos;
    private float digProgress;
    private int digLastStage = -1;
    private int digStallTicks;
    private ServerLevel digLevel;

    // 行走插值子状态（同层 WALK 沿用滑步节奏，视觉与 M4 版一致）
    private double wx, wy, wz;

    public PathTask(ServerLevel level, BlockPos target, boolean mayAlterTerrain) {
        this.level = level;
        this.target = target;
        this.mayAlterTerrain = mayAlterTerrain;
    }

    @Override
    public Progress tick(CompanionPlayer c) {
        if (++tickWarmup < 2) {
            return running(); // 等一拍出出生/传送的下坠，起点判据才稳
        }
        return phase == Phase.SEARCH ? searchTick(c) : executeTick(c);
    }

    // ---- SEARCH ----

    private Progress searchTick(CompanionPlayer c) {
        if (search == null) {
            BlockPos from = c.blockPosition();
            sampler = new LevelDigSampler(level, c, from,
                    LevelDigSampler.countPlaceables(c.getInventory()));
            search = newSearch(from);
        }
        if (!search.advance(EXPAND_PER_TICK)) {
            // 分帧搜索跨 ~27 tick，中途世界会被别人改：验尸超限则丢旧图，从当前位置重开
            if (memo != null && memo.worldChanged()) {
                McbotMod.LOG.info("[path] 验尸超限 stale={}，丢弃旧图重开搜索（第 {} 次）",
                        memo.staleCount(), memoRestarts + 1);
                memoRestarts++;
                search = newSearch(c.blockPosition());
            }
            return running(); // 还在算（分帧）
        }
        if (search.failure() != null) {
            McbotMod.LOG.info("[path] 搜索失败 from={} to={}: {} {}", c.blockPosition().toShortString(),
                    target.toShortString(), search.failure(), memoStats());
            return new Progress.Done(new Result(false, search.failure(), null));
        }
        // 搜索成功≠清单真值：用裸 sampler 按当前世界重建挖/放清单，再出确认/执行/计数
        path = liveify(search.path());
        cursor = 0;
        int digs = planDigs;
        int places = planPlaces;
        McbotMod.LOG.info("[path] {}→{} 路径 {} 节点（挖 {} 放 {}）replan={} {}",
                c.blockPosition().toShortString(), target.toShortString(), path.size(),
                digs, places, replans, memoStats());
        if (digs > 0 || places > 0) {
            StringBuilder sb = new StringBuilder();
            for (DigAStar.Step st : path) {
                for (long cell : st.dig()) {
                    sb.append("D[").append(unpackShort(cell)).append("] ");
                }
                for (long cell : st.place()) {
                    sb.append("P[").append(unpackShort(cell)).append("] ");
                }
            }
            McbotMod.LOG.info("[path] 计划清单：{}", sb);
        }
        if ((digs > 0 || places > 0) && !mayAlterTerrain) {
            return new Progress.Done(new Result(false,
                    "NEED_CONFIRM:到 " + target.toShortString() + " 需要改动世界——挖 "
                            + digs + " 格、放 " + places + " 格（明细见 data.blocks）。"
                            + "确认就重发 move_to 并带 may_alter_terrain=true；不想改就换目的地。",
                    confirmData(digs, places)));
        }
        phase = Phase.EXECUTE;
        return executeTick(c);
    }

    /** 搜索构造统一入口：裸 sampler 必在之前建好；memo 套在搜索侧，执行侧永不见缓存。 */
    private DigAStar newSearch(BlockPos from) {
        if (memoRestarts < 2) {
            memo = new MemoDigSampler(sampler);
        } else {
            memo = null; // 验尸两度超限：这趟世界改得太快，直接 live 读（宁慢不抽）
        }
        return new DigAStar(memo != null ? memo : sampler,
                from.getX(), from.getY(), from.getZ(),
                target.getX(), target.getY(), target.getZ(), MAX_NODES, MAX_DIGS);
    }

    /**
     * 用裸 sampler 按**当前世界**重建每节点的挖/放清单并统计 planDigs/planPlaces。
     * 为什么必须：NEED_CONFIRM 拿给主人点头、模型拿来决定 may_alter_terrain 的清单，
     * 如果是 memo 期（搜索跨 27 tick）的旧答案，就是在让主人批准一份"幽灵清单"。
     * 动作字节不重分类（仅播报语义）；执行期 mineOne/placeOne 本就逐格 live 判定，双层自晦。
     */
    private List<DigAStar.Step> liveify(List<DigAStar.Step> plan) {
        ArrayList<DigAStar.Step> out = new ArrayList<>(plan.size());
        planDigs = 0;
        planPlaces = 0;
        for (DigAStar.Step st : plan) {
            if (st.action() == DigAStar.ACT_START) {
                out.add(st);
                continue;
            }
            ArrayList<Long> dig = new ArrayList<>(2);
            for (int cy = st.y(); cy <= st.y() + 1; cy++) {
                if (!sampler.passable(st.x(), cy, st.z())) {
                    dig.add(DigAStar.pack(st.x(), cy, st.z()));
                }
            }
            ArrayList<Long> place = new ArrayList<>(1);
            if (!sampler.support(st.x(), st.y() - 1, st.z())
                    && sampler.placeable(st.x(), st.y() - 1, st.z())) {
                place.add(DigAStar.pack(st.x(), st.y() - 1, st.z()));
            }
            planDigs += dig.size();
            planPlaces += place.size();
            out.add(new DigAStar.Step(st.x(), st.y(), st.z(), dig, place, st.action()));
        }
        return out;
    }

    private String memoStats() {
        return memo == null ? "memo=off(验尸超限)" : "[memo] expanded=" + search.expanded()
                + " 命中=" + memo.memoHits() + " 实查=" + memo.memoMisses()
                + " 验尸不符=" + memo.staleCount();
    }

    private static String unpackShort(long cell) {
        return DigAStar.unpackX(cell) + "," + DigAStar.unpackY(cell) + "," + DigAStar.unpackZ(cell);
    }

    private JsonObject confirmData(int digs, int places) {
        JsonArray arr = new JsonArray();
        int listed = 0;
        for (DigAStar.Step st : path) {
            for (long cell : st.dig()) {
                if (listed++ < 32) {
                    arr.add(cellDesc("dig", cell));
                }
            }
            for (long cell : st.place()) {
                if (listed++ < 32) {
                    arr.add(cellDesc("place", cell));
                }
            }
        }
        JsonObject data = new JsonObject();
        data.add("blocks", arr);
        data.addProperty("dig", digs);
        data.addProperty("place", places);
        if (listed > 32) {
            data.addProperty("truncated_of", listed);
        }
        return data;
    }

    private JsonObject cellDesc(String op, long cell) {
        int x = DigAStar.unpackX(cell);
        int y = DigAStar.unpackY(cell);
        int z = DigAStar.unpackZ(cell);
        JsonObject o = new JsonObject();
        o.addProperty("op", op);
        o.addProperty("x", x);
        o.addProperty("y", y);
        o.addProperty("z", z);
        o.addProperty("block", sampler.blockName(x, y, z));
        return o;
    }

    // ---- EXECUTE ----

    private Progress executeTick(CompanionPlayer c) {
        // 到达判定：路径走完且与末节点贴近
        if (cursor >= path.size()) {
            return arrived(c);
        }
        // 执行期复核节拍
        if (executed > 0 && executed % RECHECK_EVERY == 0 && !validateAhead(c)) {
            if (replans >= MAX_REPLAN) {
                return new Progress.Done(new Result(false,
                        "NO_PATH:路被改变得太多（重规划 " + replans + " 次仍失败）。"
                                + "到了 (" + c.blockPosition().toShortString()
                                + ")，重新扫一下再定目的地吧。", null));
            }
            Progress early = replan(c);
            if (early != null) {
                return early; // 重规划失败，或新路线需确认
            }
            executed = 0;
        }

        DigAStar.Step next = path.get(cursor);

        // 1) 本节点要挖的格：逐拍真计时（mineOne 完成/已清返回 null，推进清单）
        if (!next.dig().isEmpty()) {
            Progress p = mineOne(c, next.dig().get(0));
            if (p != null) {
                return p; // 还在挖或终局失败
            }
            path.set(cursor, new DigAStar.Step(next.x(), next.y(), next.z(),
                    next.dig().subList(1, next.dig().size()), next.place(), next.action()));
            return running();
        }

        // 2) 本节点要放的支撑
        if (!next.place().isEmpty()) {
            long cell = next.place().get(0);
            Progress p = placeOne(c, cell);
            if (p != null) {
                return p;
            }
            path.set(cursor, new DigAStar.Step(next.x(), next.y(), next.z(),
                    next.dig(), next.place().subList(1, next.place().size()), next.action()));
            return running();
        }

        // 3) 落位：同层走路用 0.45/tick 插值（视觉与滑步版一致），其余直接传送
        if (next.y() == c.blockPosition().getY() && next.action() == DigAStar.ACT_WALK) {
            double dx = next.x() + 0.5 - c.getX();
            double dz = next.z() + 0.5 - c.getZ();
            double dist = Math.hypot(dx, dz);
            if (dist > 0.45) {
                c.teleportTo(c.getX() + dx / dist * 0.45, c.getY(),
                        c.getZ() + dz / dist * 0.45);
                return running();
            }
        }
        c.teleportTo(next.x() + 0.5, next.y(), next.z() + 0.5);
        cursor++;
        executed++;
        return running();
    }

    private Progress arrived(CompanionPlayer c) {
        BlockPos at = c.blockPosition();
        int need = planDigs + planPlaces;
        String fb = "到了 (" + target.toShortString() + ") 附近，站定在 " + at.toShortString()
                + (need > 0 ? "（这一路动了 " + need + " 个方块）。" : "。");
        return new Progress.Done(new Result(true, fb, null));
    }

    /**
     * 从当前位置重新算路。返回 null=成功可继续；Done=失败或新路线需确认（防绕过确认流）。
     * 注意：本方法在**单拍内同步算完**（replan 发生在当前 tick，世界不会中途变，
     * memo 在这层是纯去重不会引入陈旧）——但这个同步循环本身就是单帧冻结点，
     * 真分帧重规划属 S3（REPLAN_SEARCH 相位），本卡不动它的预算语义。
     */
    private Progress replan(CompanionPlayer c) {
        BlockPos from = c.blockPosition();
        sampler = new LevelDigSampler(level, c, from,
                LevelDigSampler.countPlaceables(c.getInventory()));
        search = newSearch(from);
        replans++;
        cursor = 0;
        // 重规划同步算完（预算有界：8000/300≈27 次循环，必然终止）
        while (!search.advance(EXPAND_PER_TICK)) {
            // 分帧接口在同步循环下等价于一次算完
        }
        if (search.failure() != null) {
            return new Progress.Done(new Result(false,
                    "NO_PATH:路被改变后重规划失败：" + search.failure(), null));
        }
        path = liveify(search.path());
        if (!mayAlterTerrain && (planDigs > 0 || planPlaces > 0)) {
            // 新路线要改世界但未授权：就地停，交回确认流
            return new Progress.Done(new Result(false,
                    "NEED_CONFIRM:路被人改动，新路线需要挖/放才能继续（明细见 data.blocks）。"
                            + "确认就带 may_alter_terrain=true 重发。",
                    confirmData(planDigs, planPlaces)));
        }
        return null;
    }

    /** 复核未来节点的格子状态：该清的还清着？支撑还在？ */
    private boolean validateAhead(CompanionPlayer c) {
        int end = Math.min(path.size(), cursor + RECHECK_AHEAD);
        for (int i = cursor; i < end; i++) {
            DigAStar.Step st = path.get(i);
            for (long cell : st.dig()) {
                int x = DigAStar.unpackX(cell), y = DigAStar.unpackY(cell), z = DigAStar.unpackZ(cell);
                if (!level.hasChunkAt(new BlockPos(x, y, z))) {
                    return false; // 区块卸载，假设失效
                }
                // 挖过的应该还是空的（被人补上=变了）；没挖的仍待挖，不算坏
            }
            if (!sampler.support(st.x(), st.y() - 1, st.z()) && st.place().isEmpty()
                    && i > cursor) {
                return false; // 原支撑被拆了
            }
        }
        return true;
    }

    // ---- 挖掘子机（BreakBlockTool 同款公式与动画，独立小实现避免动已验收代码；
    //      重复约 40 行，债务记录在 STATUS） ----

    /** 返回 null=还在挖/刚清完交回主流程；Done=终局（含失败教学）。 */
    private Progress mineOne(CompanionPlayer c, long cell) {
        int x = DigAStar.unpackX(cell), y = DigAStar.unpackY(cell), z = DigAStar.unpackZ(cell);
        BlockPos pos = new BlockPos(x, y, z);
        BlockState st = level.getBlockState(pos);
        if (st.isAir()) {
            return null; // 已经清了（可能上拍刚挖完），交回主流程
        }
        if (digPos == null || !digPos.equals(pos)) {
            digPos = pos;
            digTarget = st;
            digProgress = 0;
            digLastStage = -1;
            digStallTicks = 0;
            digLevel = level;
        }
        // 目标被人动过：假设作废
        if (st != digTarget) {
            clearCrack();
            return new Progress.Done(new Result(false,
                    "TARGET_LOST:路上那格 (" + pos.toShortString() + ") 被人动过了，"
                            + "原地重发 move_to（会重新算路）。", null));
        }
        float speed = c.getDestroySpeed(st);
        float hardness = st.getBlock().defaultDestroyTime();
        if (speed <= 0 || hardness < 0) {
            clearCrack();
            return new Progress.Done(new Result(false,
                    "WRONG_TOOL:开路要挖的 " + st.getBlock().getName().getString()
                            + " 现在手持挖不动。换个工具或换条路。", null));
        }
        digProgress += speed / hardness / 30f;
        if (digProgress * 20f < 1f && ++digStallTicks > 1200) {
            clearCrack();
            return new Progress.Done(new Result(false,
                    "TIMEOUT:一格要挖得太久（" + st.getBlock().getName().getString()
                            + "），先绕路或去做别的。", null));
        }
        int stage = Math.min(9, (int) (digProgress * 10f));
        if (stage != digLastStage) {
            digLastStage = stage;
            level.getServer().getPlayerList()
                    .broadcastAll(new ClientboundBlockDestructionPacket(0, pos, stage),
                            level.dimension());
        }
        if (digProgress >= 1f) {
            var be = level.getBlockEntity(pos);
            List<net.minecraft.world.item.ItemStack> drops =
                    Block.getDrops(st, level, pos, be, c, c.getInventory().getSelectedItem());
            clearCrack();
            level.destroyBlock(pos, false, c);
            int got = 0;
            for (var s : drops) {
                if (s.isEmpty()) {
                    continue;
                }
                if (!c.getInventory().add(s.copy())) {
                    Block.popResource(level, pos, s);
                } else {
                    got += s.getCount();
                }
            }
            McbotMod.LOG.info("[path] 挖清 {} {} 掉落={} 收进={}", pos.toShortString(),
                    st.getBlock().getName().getString(), drops.size(), got);
            digPos = null;
            return null; // 挖完了，交回主流程推进下一步
        }
        return running();
    }

    /** 放一格垫脚/搭桥：从背包挑一个能挡腿的方块消耗掉。返回 null=继续。 */
    private Progress placeOne(CompanionPlayer c, long cell) {
        int x = DigAStar.unpackX(cell), y = DigAStar.unpackY(cell), z = DigAStar.unpackZ(cell);
        BlockPos pos = new BlockPos(x, y, z);
        McbotMod.LOG.info("[path] 尝试放格 {} 当前方块={} 背包方块数={}", pos.toShortString(),
                level.getBlockState(pos).getBlock().getName().getString(),
                LevelDigSampler.countPlaceables(c.getInventory()));
        if (!level.getBlockState(pos).isAir()) {
            return null; // 规划后被填上：支撑现成，直接过
        }
        var inv = c.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            var stack = inv.getItem(i);
            if (stack.isEmpty()) {
                continue;
            }
            Block b = Block.byItem(stack.getItem());
            if (b == null || b == Blocks.AIR || !b.defaultBlockState().blocksMotion()) {
                continue;
            }
            level.setBlockAndUpdate(pos, b.defaultBlockState());
            stack.shrink(1);
            return null;
        }
        return new Progress.Done(new Result(false,
                "NO_PATH:搭路要放方块但背包里没有可用的固体方块了。回去拿点材料再来。", null));
    }

    private void clearCrack() {
        if (digLastStage >= 0 && digLevel != null && digPos != null) {
            digLevel.getServer().getPlayerList()
                    .broadcastAll(new ClientboundBlockDestructionPacket(0, digPos, -1),
                            digLevel.dimension());
            digLastStage = -1;
        }
    }

    @Override
    public void onAbort() {
        clearCrack();
    }

    /** 供 MoveToTool 在提交前做的距离帽判断。 */
    public static boolean withinCap(BlockPos from, BlockPos to) {
        return Math.abs(to.getX() - from.getX()) <= LevelDigSampler.RADIUS_XZ
                && Math.abs(to.getZ() - from.getZ()) <= LevelDigSampler.RADIUS_XZ
                && Math.abs(to.getY() - from.getY()) <= LevelDigSampler.RADIUS_Y;
    }
}
