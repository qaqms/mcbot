package com.neko.mcbot.path;

import java.util.HashMap;
import java.util.List;

/**
 * 搜索期 memo 装饰器（R1-S2，设计卡 §B）：把 {@link DigSampler} 的**单格世界查询**各算一次。
 *
 * <p><b>为什么存在</b>：{@code DigAStar.relax} 每节点 26 邻居 × 脚/头/支撑/斜穿，约 100–300 次
 * 单格查询；同一格被周围最多 6 个邻居重复问，每次底层都是 getBlockState + 方块实体 +
 * 注册表 + 岩浆 6 邻——8000 节点 = 百万级世界读。这是真机山体撞帽的另一半主因
 * （加权 h 只减展开数；这里减每次展开的成本）。
 *
 * <p><b>快照只当启发式，不当承诺</b>（设计卡 §B 的一致性口径）：
 * ① 本层只包搜索期的四个世界查询；执行期落位清单由 PathTask 用**裸 sampler live 重算**，
 *    memo 永不进入"已经动手"的判断；
 * ② 周期性"验尸"：每 {@value #VERIFY_EVERY_MISSES} 次实查抽 {@value #VERIFY_CELLS} 格绕开缓存
 *    重问，不符即弃该格、{@code stale++}；累计超 {@value #STALE_MAX} → {@link #worldChanged()}，
 *    调用方据此丢弃本图（绝不用旧图执行）。搜索跨 ~27 tick 中途变天，靠这两层而不是靠运气。
 * ③ 超 {@link DigAStar#MEMO_MAX_CELLS} 停止 memoize 退回直读——内存有界优先于命中率，不失败。
 *
 * <p>零 MC 依赖（只包接口），单测可用 GridSampler 驱动；单线程使用（服务器主线程），
 * 与 DigAStar 同约定。
 */
public final class MemoDigSampler implements DigSampler {

    private static final byte NO = 0;
    private static final byte YES = 1;

    static final int VERIFY_EVERY_MISSES = 256; // 每 256 次实查抽一轮验尸
    static final int VERIFY_CELLS = 8;          // 每轮抽 8 格
    static final int STALE_MAX = 24;            // 验尸不符过此数 → 图不可信

    private final DigSampler base;
    private final HashMap<Long, Byte> pass = new HashMap<>();
    private final HashMap<Long, Byte> sup = new HashMap<>();
    private final HashMap<Long, Byte> plc = new HashMap<>();
    /** 量化挖秒（tick 的 1/4 秒粒度）；负值=不可挖。 */
    private final HashMap<Long, Integer> dig = new HashMap<>();

    private long hits;
    private long misses;
    private int stale;
    private boolean worldChanged;

    public MemoDigSampler(DigSampler base) {
        this.base = base;
    }

    public long memoHits() {
        return hits;
    }

    public long memoMisses() {
        return misses;
    }

    public int staleCount() {
        return stale;
    }

    /** 验尸判定：图不可信。调用方（PathTask/S3）应据此丢弃本次搜索并重开。 */
    public boolean worldChanged() {
        return worldChanged;
    }

    private boolean memoCap() {
        return pass.size() + sup.size() + plc.size() + dig.size() < DigAStar.MEMO_MAX_CELLS;
    }

    @Override
    public boolean passable(int x, int y, int z) {
        long k = DigAStar.pack(x, y, z);
        Byte cached = pass.get(k);
        if (cached != null) {
            hits++;
            return cached == YES;
        }
        boolean r = base.passable(x, y, z);
        misses++;
        if (memoCap()) {
            pass.put(k, (byte) (r ? YES : NO));
        }
        afterProbe();
        return r;
    }

    @Override
    public boolean support(int x, int y, int z) {
        long k = DigAStar.pack(x, y, z);
        Byte cached = sup.get(k);
        if (cached != null) {
            hits++;
            return cached == YES;
        }
        boolean r = base.support(x, y, z);
        misses++;
        if (memoCap()) {
            sup.put(k, (byte) (r ? YES : NO));
        }
        afterProbe();
        return r;
    }

    @Override
    public boolean placeable(int x, int y, int z) {
        long k = DigAStar.pack(x, y, z);
        Byte cached = plc.get(k);
        if (cached != null) {
            hits++;
            return cached == YES;
        }
        boolean r = base.placeable(x, y, z);
        misses++;
        if (memoCap()) {
            plc.put(k, (byte) (r ? YES : NO));
        }
        afterProbe();
        return r;
    }

    /**
     * 挖秒按 250ms 量化存储（代价差 <3%，换缓存紧凑）；INFEASIBLE 存 -1。
     * 注意"起点脚下不挖"等**调用方相关**规则在 LevelDigSampler 里按格判——本层缓存的
     * 是一次搜索生命周期内的答案，起点不随搜索变，成立；跨搜索必须换新的 MemoDigSampler
     * （PathTask 每次搜索新建 sampler+wrapper，见其构造处）。
     */
    @Override
    public double digSeconds(int x, int y, int z) {
        long k = DigAStar.pack(x, y, z);
        Integer cached = dig.get(k);
        if (cached != null) {
            hits++;
            return cached < 0 ? INFEASIBLE : cached / (double) 4;
        }
        double r = base.digSeconds(x, y, z);
        misses++;
        if (memoCap()) {
            dig.put(k, r == INFEASIBLE ? -1 : (int) Math.ceil(r * 4));
        }
        afterProbe();
        return r;
    }

    @Override
    public double placeCost(int x, int y, int z) {
        return base.placeCost(x, y, z); // 纯算术（读库存常数），无世界读，不 memo
    }

    @Override
    public int maxPlaces() {
        return base.maxPlaces();
    }

    @Override
    public boolean inBounds(int x, int y, int z) {
        return base.inBounds(x, y, z); // 纯算术，不 memo
    }

    // ---- 验尸（周期性 live 复验）----

    /** 验尸节拍：每 VERIFY_EVERY_MISSES 次实查抽一轮。 */
    private void afterProbe() {
        if (worldChanged || misses % VERIFY_EVERY_MISSES != 0) {
            return;
        }
        verifyOnce();
    }

    /**
     * 抽验一轮（包内可见仅作测试入口：验尸需要确定性触发，不能靠碰运气撞 256 节拍）。
     * 用 HashMap 迭代序前 VERIFY_CELLS 格——对同插入序列确定性，不依赖时钟/随机源。
     * 只验 pass 面（最敏感：变了直接改连通性），不符弃该格全部缓存位并 stale++。
     */
    void verifyOnce() {
        if (worldChanged || pass.isEmpty()) { // 图已判不可信：验尸停机，等调用方丢图
            return;
        }
        // 先取键快照再验：边遍历 entrySet 边 remove 会直接 CME（首版自审抓到）
        java.util.ArrayList<Long> keys = new java.util.ArrayList<>(VERIFY_CELLS);
        for (Long k : pass.keySet()) {
            keys.add(k);
            if (keys.size() >= VERIFY_CELLS) {
                break;
            }
        }
        for (long k : keys) {
            Byte v = pass.get(k); // 前面可能已验掉同格（理论上不会，防御一手）
            if (v == null) {
                continue;
            }
            int x = DigAStar.unpackX(k);
            int y = DigAStar.unpackY(k);
            int z = DigAStar.unpackZ(k);
            if (base.passable(x, y, z) != (v == YES)) {
                pass.remove(k);
                dig.remove(k);
                sup.remove(k);
                plc.remove(k);
                if (++stale > STALE_MAX) {
                    worldChanged = true;
                    return;
                }
            }
        }
    }
}
