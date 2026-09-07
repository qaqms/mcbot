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

    private record Node(long key, int x, int y, int z, int digs, int places,
                        double g, double f, long prev) {
    }

    private final DigSampler s;
    private final int maxNodes;
    private final int maxDigs;
    private final int sx, sy, sz, tx, ty, tz;

    private final PriorityQueue<Node> open =
            new PriorityQueue<>((a, b) -> Double.compare(a.f, b.f));
    private final Map<Long, Node> best = new HashMap<>();

    private int expanded;
    private boolean finished;
    private List<Step> path;
    private String failure;

    public DigAStar(DigSampler sampler, int sx, int sy, int sz, int tx, int ty, int tz,
                    int maxNodes, int maxDigs) {
        this.s = sampler;
        this.sx = sx;
        this.sy = sy;
        this.sz = sz;
        this.tx = tx;
        this.ty = ty;
        this.tz = tz;
        this.maxNodes = maxNodes;
        this.maxDigs = maxDigs;
        Node start = new Node(pack(sx, sy, sz), sx, sy, sz, 0, 0, 0, h(sx, sy, sz), 0);
        best.put(start.key, start);
        open.add(start);
    }

    /** 分帧推进：最多展开 budget 个节点。返回 true=已出结果（path() 或 failure()）。 */
    public boolean advance(int budget) {
        if (finished) {
            return true;
        }
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
                path = reconstruct(cur);
                finished = true;
                return true;
            }
            if (++expanded > maxNodes) {
                return fail("BUDGET_EXCEEDED:搜索展开超过 " + maxNodes
                        + " 节点。目标太远或地形太纠缠，请分短段移动。");
            }
            expand(cur);
        }
        return false;
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
        List<Long> dig = new ArrayList<>(2);
        double cost = MOVE_BASE + (dx != 0 && dz != 0 ? MOVE_DIAG : 0);
        int newDigs = 0;
        int places;
        for (int cy = by; cy <= by + 1; cy++) {
            if (s.passable(bx, cy, bz)) {
                continue;
            }
            double sec = s.digSeconds(bx, cy, bz);
            if (sec == DigSampler.INFEASIBLE) {
                return;
            }
            newDigs++;
            if (cur.digs + newDigs > maxDigs) {
                return;
            }
            cost += sec + DIG_OVERHEAD;
            dig.add(pack(bx, cy, bz));
        }
        // 2) 支撑：没有现成实心就放一块（PILLAR=直上、BRIDGE=横移补脚 都由此派生），
        //    放格同挖格一样累计预算（=背包存量），不够就无效——防"规划靠 0 库存的路"
        List<Long> place = new ArrayList<>(1);
        if (!s.support(bx, by - 1, bz)) {
            if (!s.placeable(bx, by - 1, bz) || cur.places + 1 > s.maxPlaces()) {
                return;
            }
            cost += s.placeCost(bx, by - 1, bz);
            places = cur.places + 1;
            place.add(pack(bx, by - 1, bz));
        } else {
            places = cur.places;
        }
        // 3) 下落风险
        if (dy < 0) {
            cost += FALL_PENALTY * dy * dy;
        }

        long key = pack(bx, by, bz);
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
        // 曼哈顿×0.95：每步最小真实代价≥1（可采纳），0.95 是轻微聚焦不动点
        return 0.95 * (Math.abs(x - tx) + Math.abs(z - tz) + Math.abs(y - ty));
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
