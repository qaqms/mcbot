package com.neko.mcbot.path;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

/**
 * 可挖通道 A*（DESIGN §8，M8）：节点=落脚点（脚格+头格可通行、下格有支撑），
 * 动作边统一建模为"清除目标两格（挖）+ 补支撑（放）+ 移动"，
 * 由此派生 走/跳/落/下挖/向前挖/垫脚/搭桥 全套动作。
 *
 * 三条工程保险丝（搜索跑在服务器主线程，必须预算化）：
 * ① 展开节点 ≤ maxNodes；② 计划挖掘 ≤ maxDigs（按节点累计并剪枝）；
 * ③ 分帧：每次 advance(budget) 限量展开，绝不一口气算崩主线程。
 *
 * 纯算法，零 Minecraft 依赖：地形判断全部经 {@link DigSampler} 接口（可单测）。
 * 线程约定：单线程使用（服务器主线程），无内部锁。
 */
public final class DigAStar {

    /** 一格=打包 long（见 pack()）。一步落点：坐标 + 本步要挖/要放的格子。 */
    public record Step(int x, int y, int z, List<Long> dig, List<Long> place, byte action) {
    }

    /** 动作仅作播报语义；执行逻辑统一为 挖→放→落位。 */
    public static final byte ACT_START = 0, ACT_WALK = 1, ACT_JUMP = 2, ACT_FALL = 3,
            ACT_DIG = 4, ACT_PILLAR = 5, ACT_BRIDGE = 6;

    private static final double MOVE_BASE = 1.0;  // 每步基础代价（≈1 格/秒的节奏单位）
    private static final double MOVE_DIAG = 0.4;  // 斜走略贵
    private static final double DIG_OVERHEAD = 0.3; // 每挖一格的固定开销（换工具/瞄准）
    private static final double FALL_PENALTY = 0.15; // 下落罚（每格²），让楼梯优于直落

    /**
     * 启发权重（docs/plan/R1-pathfinding.md §A，主人拍板 1.8 实测后校）：
     * **故意不可采纳**——代价上界 ≤ W×最优，拿次优度换展开数。
     * 旧 h=0.95×L1(到中心) 两头都错：斜步 1.4 消 2 单位（高估），挖价不进入 h（必挖区低估），
     * f 退化成按 g 的球形扩散→16 格山地撞 8000 帽。真可采纳下界只能假设挖价=0（总能声称绕行），
     * 对山体零梯度→治不了病根，故走加权而非修下界（设计卡 §A 的否决理由）。
     */
    public static final double H_WEIGHT = 1.8;
    /** 每消 1 个曼哈顿单位的最低真实代价：(1,1,1) 斜跨步覆盖 3 单位只花 1.4 → 1.4/3≈0.467。 */
    public static final double H_UNIT = 0.467;
    /** 撞帽时降级为 PARTIAL 的最小推进量（曼哈顿单位）：差不到这个数就坦白 NO_PROGRESS 级。 */
    public static final int PARTIAL_MIN_GAIN = 4;
    /** memo 总格数硬帽（设计卡 §B）：超帽**停止 memoize 退回直读**（不失败）——内存有界优先。 */
    public static final int MEMO_MAX_CELLS = 262_144;
    /** 重规划抑抖（R1-S3 §D）：旧路线格（脚/挖/放）代价×此系数——旧路比任何新候选严格便宜，
     * 同地形重搜必原路复现；不复用 open/best（旧 g/f 建在旧地形上，复用会带进失效代价）。 */
    public static final double BIAS_REUSE = 0.7;

    private record Node(long key, int x, int y, int z, int digs, int places,
                        double g, double f, long prev) {
    }

    private final DigSampler s;
    private final int maxNodes;
    private final int maxDigs;
    private final int sx, sy, sz, tx, ty, tz;
    private final double hWeight;
    /** 入目标柱的最小真实代价（脚+头挖价+放价，搜索开始算一次）；全不可行=0。 */
    private final double goalEntryCost;
    /** 起始点到目标体积的 L1（部分提交的推进量基准）。 */
    private final int startL1;

    private final PriorityQueue<Node> open =
            new PriorityQueue<>((a, b) -> Double.compare(a.f, b.f));
    private final Map<Long, Node> best = new HashMap<>();

    private int expanded;
    /** 累计搜索 CPU 时长（验尸/双帽口径用）；totalBudgetNanos 到点即撞帽，与节点帽同位。 */
    private long usedNanos;
    private long totalBudgetNanos = Long.MAX_VALUE / 8;
    /** 旧路线格集（R1-S3 抑抖）；null=首搜。 */
    private java.util.Set<Long> reuse;
    private boolean finished;
    private boolean budgetReached;
    /** 已扩展（settled）节点中距目标体积最近者（同 L1 取 g 小）——部分提交候选。 */
    private Node bestPartial;
    private List<Step> path;
    /** 命中目标时的 settled 节点（pathCost 供抑抖断言/观测）。 */
    private Node goalNode;
    private String failure;

    public DigAStar(DigSampler sampler, int sx, int sy, int sz, int tx, int ty, int tz,
                    int maxNodes, int maxDigs) {
        this(sampler, sx, sy, sz, tx, ty, tz, maxNodes, maxDigs, H_WEIGHT);
    }

    public DigAStar(DigSampler sampler, int sx, int sy, int sz, int tx, int ty, int tz,
                    int maxNodes, int maxDigs, double hWeight) {
        this.s = sampler;
        this.sx = sx;
        this.sy = sy;
        this.sz = sz;
        this.tx = tx;
        this.ty = ty;
        this.tz = tz;
        this.maxNodes = maxNodes;
        this.maxDigs = maxDigs;
        this.hWeight = hWeight;
        this.startL1 = l1(sx, sy, sz);
        this.goalEntryCost = computeGoalEntryCost(sampler);
        Node start = new Node(pack(sx, sy, sz), sx, sy, sz, 0, 0, 0, h(sx, sy, sz), 0);
        best.put(start.key, start);
        open.add(start);
    }

    /** 分帧推进（不限时，单测/兼容用）。 */
    public boolean advance(int budget) {
        return advance(budget, Long.MAX_VALUE / 8);
    }

    /**
     * 分帧推进：节点数 ≤budget **且** 本拍耗时 ≤sliceNanos，先到先停（R1-S3 双帽之一）；
     * 另受构造后累计 CPU 帽 totalBudget() 约束（另一帽）。返回 true=已出结果
     * （path()/partialPath()/failure() ZL 三态由 budgetReached()+partialAvailable() 定性）。
     */
    public boolean advance(int budget, long sliceNanos) {
        if (finished) {
            return true;
        }
        long t0 = System.nanoTime();
        long sliceEnd = t0 + sliceNanos;
        for (int i = 0; i < budget; i++) {
            Node cur = open.poll();
            if (cur == null) {
                return fail("NO_PATH:搜索空间走完了也没有路——那边是封闭的。");
            }
            Node b = best.get(cur.key);
            if (b != cur) {
                continue; // 陈旧堆项
            }
            if (isGoal(cur.x, cur.y, cur.z)) {
                goalNode = cur;
                path = reconstruct(cur);
                finished = true;
                return true;
            }
            // 部分提交候选：只在 settled 节点里选（陈旧堆项已过滤），距体积最近、同距取 g 小
            if (bestPartial == null || l1(cur.x, cur.y, cur.z) < l1(bestPartial.x, bestPartial.y, bestPartial.z)
                    || (l1(cur.x, cur.y, cur.z) == l1(bestPartial.x, bestPartial.y, bestPartial.z)
                        && cur.g < bestPartial.g)) {
                bestPartial = cur;
            }
            if (++expanded > maxNodes) {
                budgetReached = true;   // 对外失败字符串不动（不破坏 [m8]/TOOLS 契约）；
                                        // PARTIAL/NO_PROGRESS 的定性由 partialAvailable() 给调用方
                return fail("BUDGET_EXCEEDED:搜索展开超过 " + maxNodes
                        + " 节点。目标太远或地形太纠缠，请分短段移动。");
            }
            expand(cur);
            // 每 16 拓才探一次钟：nanoTime 本身不免费，粗粒度换开销（帽语义不差这 16 节点）
            if ((expanded & 15) == 0) {
                long now = System.nanoTime();
                if (now >= sliceEnd) {
                    usedNanos += now - t0;
                    return false; // 本拍切片用尽，下拍续（未出结果）
                }
                if (usedNanos + (now - t0) >= totalBudgetNanos) {
                    usedNanos = totalBudgetNanos;
                    budgetReached = true;
                    return fail("BUDGET_EXCEEDED:搜索耗尽了 " + (totalBudgetNanos / 1_000_000)
                            + "ms 时间预算（展开 " + expanded + " 节点）。目标太远或地形太纠缠，请分短段移动。");
                }
            }
        }
        usedNanos += System.nanoTime() - t0;
        return false;
    }

    /** 累计搜索 CPU 预算（纳秒）；PathTask 传 SEARCH_TOTAL_NS。越界后与节点帽同位撞帽。 */
    public void totalBudget(long nanos) {
        this.totalBudgetNanos = nanos;
    }

    /** 旧路线格降权集（R1-S3 §D）；在建好 start 之后、首次 advance 之前调一次。 */
    public void reuseBias(java.util.Set<Long> cells) {
        this.reuse = cells;
    }

    /** 搜索已花的累计 CPU 毫秒（日志观测）。 */
    public long elapsedMillis() {
        return usedNanos / 1_000_000;
    }

    public boolean done() {
        return finished;
    }

    /** 成功时的路径（不含起点）；失败时为 null。 */
    public List<Step> path() {
        return path;
    }

    /** 失败时的结构化原因（NO_PATH… / BUDGET_EXCEEDED…）；成功时为 null。 */
    public String failure() {
        return failure;
    }

    public int expanded() {
        return expanded;
    }

    /** 是否因节点预算帽而结束（区别于 open 空的真 NO_PATH）。 */
    public boolean budgetReached() {
        return budgetReached;
    }

    /**
     * 撞帽且推进量足够（≥PARTIAL_MIN_GAIN）时，存在可用的"半程路"：调用方可用
     * {@link #partialPath()} 先走完这段、到点重发（设计卡 §C：撞帽从"失败"变"缩短射程"）。
     * 非撞帽结束（成功/真封闭/推进不足）返回 false。
     */
    public boolean partialAvailable() {
        if (!budgetReached || bestPartial == null) {
            return false;
        }
        return startL1 - l1(bestPartial.x, bestPartial.y, bestPartial.z) >= PARTIAL_MIN_GAIN;
    }

    /** 半程路径（不含起点）；仅 {@link #partialAvailable()} 为真时非 null。 */
    public List<Step> partialPath() {
        return partialAvailable() ? reconstruct(bestPartial) : null;
    }

    /** 降级终点距目标体积的 L1（PARTIAL 文案里的"还差 d 格"）；无候选时 -1。 */
    public int remainingL1() {
        return bestPartial == null ? -1 : l1(bestPartial.x, bestPartial.y, bestPartial.z);
    }

    /** 成功路径的总代价 g（抑抖断言/观测用）；未成功为 -1。 */
    public double pathCost() {
        return goalNode == null ? -1 : goalNode.g;
    }

    public int dugCount() {
        if (path == null) {
            return 0;
        }
        int n = 0;
        for (Step st : path) {
            n += st.dig().size();
        }
        return n;
    }

    public int placedCount() {
        if (path == null) {
            return 0;
        }
        int n = 0;
        for (Step st : path) {
            n += st.place().size();
        }
        return n;
    }

    // ---- 展开 ----

    private boolean fail(String why) {
        failure = why;
        finished = true;
        return true;
    }

    /** 终点判据：目标柱 3×3×3 内任一站得住的格（"到附近"语义，与滑步版一致）。 */
    private boolean isGoal(int x, int y, int z) {
        return Math.abs(x - tx) <= 1 && Math.abs(z - tz) <= 1 && Math.abs(y - ty) <= 1;
    }

    private void expand(Node cur) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                for (int dy = -1; dy <= 1; dy++) {
                    if (dx != 0 || dy != 0 || dz != 0) {
                        relax(cur, dx, dy, dz);
                    }
                }
            }
        }
    }

    private void relax(Node cur, int dx, int dy, int dz) {
        int bx = cur.x + dx, by = cur.y + dy, bz = cur.z + dz;
        if (!s.inBounds(bx, by, bz)) {
            return;
        }
        // 斜向：两个共享邻格必须已可通行（不挖角落，斜路只走现成缝）
        if (dx != 0 && dz != 0) {
            if (!s.passable(cur.x + dx, cur.y, cur.z) || !s.passable(cur.x, cur.y, cur.z + dz)
                    || !s.passable(cur.x + dx, cur.y + 1, cur.z)
                    || !s.passable(cur.x, cur.y + 1, cur.z + dz)) {
                return;
            }
        }

        // 1) 脚格+头格：不通就要挖（本步 ≤2 挖），挖不动/不许挖则边无效
        long destKey = pack(bx, by, bz);
        List<Long> dig = new ArrayList<>(2);
        // 抑抖（R1-S3）：旧路线上的落脚格本步基础价先打折，后面逐项再打
        double cost = (MOVE_BASE + (dx != 0 && dz != 0 ? MOVE_DIAG : 0))
                * (reuse != null && reuse.contains(destKey) ? BIAS_REUSE : 1.0);
        int newDigs = 0;
        int places;
        for (int cy = by; cy <= by + 1; cy++) {
            if (s.passable(bx, cy, bz)) {
                continue;
            }
            double sec = s.digSeconds(bx, cy, bz);
            if (!DigSampler.feasibleDig(sec)) {
                return;
            }
            newDigs++;
            if (cur.digs + newDigs > maxDigs) {
                return;
            }
            long dc = pack(bx, cy, bz);
            cost += (sec + DIG_OVERHEAD) * (reuse != null && reuse.contains(dc) ? BIAS_REUSE : 1.0);
            dig.add(dc);
        }
        // 2) 支撑：没有现成实心就放一块（PILLAR=直上、BRIDGE=横移补脚 都由此派生），
        //    放格同挖格一样累计预算（=背包存量），不够就无效——防"规划靠 0 库存的路"
        List<Long> place = new ArrayList<>(1);
        if (!s.support(bx, by - 1, bz)) {
            if (!s.placeable(bx, by - 1, bz) || cur.places + 1 > s.maxPlaces()) {
                return;
            }
            long pc = pack(bx, by - 1, bz);
            cost += s.placeCost(bx, by - 1, bz)
                    * (reuse != null && reuse.contains(pc) ? BIAS_REUSE : 1.0);
            places = cur.places + 1;
            place.add(pc);
        } else {
            places = cur.places;
        }
        // 3) 下落风险
        if (dy < 0) {
            cost += FALL_PENALTY * dy * dy;
        }

        long key = destKey;
        double g = cur.g + cost;
        Node old = best.get(key);
        if (old != null && old.g <= g) {
            return;
        }
        Node n = new Node(key, bx, by, bz, cur.digs + newDigs, places, g, g + h(bx, by, bz),
                cur.key);
        best.put(key, n);
        open.add(n);
    }

    private double h(int x, int y, int z) {
        // 距目标**体积**（3×3×3，与 isGoal 同口径）而非距中心：旧 h 到中心在体积边缘最多多算 3，
        // 与体积终点判据不自洽也是噪声源。详见 H_WEIGHT 注释：加权是**故意**的，不可采纳。
        double l = l1(x, y, z);
        return l == 0 ? 0 : hWeight * H_UNIT * l + goalEntryCost;
    }

    /** 到目标体积的 L1（体积内=0）。部分提交用它做"推进量"口径，与 h 同一把尺。 */
    private int l1(int x, int y, int z) {
        return Math.max(0, Math.abs(x - tx) - 1)
                + Math.max(0, Math.abs(z - tz) - 1)
                + Math.max(0, Math.abs(y - ty) - 1);
    }

    /**
     * 入柱价：目标柱 27 格里"能站进去"的最小真实代价（脚+头挖价+支撑放价），搜索开始算一次。
     * 全不可行（如基岩封死）返 0——不拿启发式炸天，让搜索自然穷尽到 NO_PATH；
     * 这个"只当启发式不当承诺"的口径与快照设计一致（设计卡 §B）。
     */
    private double computeGoalEntryCost(DigSampler sampler) {
        double min = Double.MAX_VALUE;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    double c = cellEntryCost(tx + dx, ty + dy, tz + dz);
                    if (c >= 0 && c < min) {
                        min = c;
                    }
                }
            }
        }
        return min == Double.MAX_VALUE ? 0 : min;
    }

    /** 单格入位代价（脚+头+支撑）；不可行返 -1。用 relax 同源公式，不另起一套。 */
    private double cellEntryCost(int x, int y, int z) {
        if (!s.inBounds(x, y, z)) {
            return -1;
        }
        double c = MOVE_BASE;
        for (int cy = y; cy <= y + 1; cy++) {
            if (!s.passable(x, cy, z)) {
                double sec = s.digSeconds(x, cy, z);
                if (!DigSampler.feasibleDig(sec)) {
                    return -1;
                }
                c += sec + DIG_OVERHEAD;
            }
        }
        if (!s.support(x, y - 1, z)) {
            if (!s.placeable(x, y - 1, z)) {
                return -1;
            }
            c += s.placeCost(x, y - 1, z);
        }
        return c;
    }

    /** 回溯路径并按"当下世界状态"补齐每步的挖/放清单（搜索期世界不变，重放即一致）。 */
    private List<Step> reconstruct(Node goal) {
        ArrayList<Node> chain = new ArrayList<>();
        for (Node n = goal; n != null; n = best.get(n.prev)) {
            chain.add(n);
        }
        Collections.reverse(chain);
        List<Step> steps = new ArrayList<>(chain.size());
        for (int i = 0; i < chain.size(); i++) {
            Node n = chain.get(i);
            if (i == 0) {
                steps.add(new Step(n.x, n.y, n.z, List.of(), List.of(), ACT_START));
                continue;
            }
            List<Long> dig = new ArrayList<>(2);
            List<Long> place = new ArrayList<>(1);
            for (int cy = n.y; cy <= n.y + 1; cy++) {
                if (!s.passable(n.x, cy, n.z)) {
                    dig.add(pack(n.x, cy, n.z));
                }
            }
            if (!s.support(n.x, n.y - 1, n.z) && s.placeable(n.x, n.y - 1, n.z)) {
                place.add(pack(n.x, n.y - 1, n.z));
            }
            steps.add(new Step(n.x, n.y, n.z, dig, place,
                    classify(chain.get(i - 1), n, dig, place)));
        }
        return steps;
    }

    private byte classify(Node from, Node to, List<Long> dig, List<Long> place) {
        int dx = to.x - from.x, dy = to.y - from.y, dz = to.z - from.z;
        boolean vert = dx == 0 && dz == 0;
        if (!place.isEmpty()) {
            return vert ? ACT_PILLAR : ACT_BRIDGE;
        }
        if (!dig.isEmpty()) {
            return ACT_DIG;
        }
        if (vert) {
            return dy > 0 ? ACT_JUMP : ACT_FALL;
        }
        if (dy > 0) {
            return ACT_JUMP;
        }
        if (dy < 0) {
            return ACT_FALL;
        }
        return ACT_WALK;
    }

    // ---- 坐标打包（int 三元组 ↔ long；x/z 各 26bit，y 12bit 偏置，负数安全）。
    // 约定：key=0 保留作链尾哨兵（需 y=-1024 才可能出现，世界高度限制在 ±64..320，永不可达） ----

    public static long pack(int x, int y, int z) {
        return (((long) x) & 0x3FFFFFFL) << 38
                | (((long) z) & 0x3FFFFFFL) << 12
                | ((y + 1024L) & 0xFFFL);
    }

    public static int unpackX(long k) {
        return (int) (k >> 38); // 存入时已带符号，算术右移自然补齐
    }

    public static int unpackY(long k) {
        return (int) (k & 0xFFFL) - 1024;
    }

    public static int unpackZ(long k) {
        return (int) ((k << 26) >> 38); // 取 bits 12..37 并作 26bit 符号扩展
    }
}
