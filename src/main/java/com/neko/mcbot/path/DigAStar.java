package com.neko.mcbot.path;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

/**
 * 可挖通道 A*（DESIGN §8，M8）：节点=落脚点（脚格+头格可通行、下格有支撑），
 * 动作边统一建模为"清除经过的目标柱空间（挖）+ 补支撑（放）+ 移动"，
 * 由此派生 走/跳/落/下挖/向前挖/垫脚/搭桥 全套动作。
 *
 * 三条工程保险丝（搜索可分帧或在独占快照上交后台线程）：
 * ① 展开节点 ≤ maxNodes；② 计划挖掘 ≤ maxDigs（按节点累计并剪枝）；
 * ③ 分帧：每次 advance(budget) 限量展开，绝不一口气算崩主线程。
 *
 * 纯算法，零 Minecraft 依赖：地形判断全部经 {@link DigSampler} 接口（可单测）。
 * 分段模式先找可行答案再改善；两遍共享展开账，可选预估另受有界资源保护。
 * 线程约定：单线程独占使用，无内部锁；后台发布后由世界线程采用。
 */
public final class DigAStar {

    /** 一格=打包 long（见 pack()）。一步落点：坐标 + 本步要挖/要放的格子。 */
    public record Step(int x, int y, int z, List<Long> dig, List<Long> place, byte action,
                       double feetY, boolean jump) {
        public Step(int x, int y, int z, List<Long> dig, List<Long> place, byte action) {
            this(x, y, z, dig, place, action, y, action == ACT_JUMP || action == ACT_PILLAR);
        }
    }

    /** 动作仅作播报语义；执行逻辑统一为 挖→放→落位。 */
    public static final byte ACT_START = 0, ACT_WALK = 1, ACT_JUMP = 2, ACT_FALL = 3,
            ACT_DIG = 4, ACT_PILLAR = 5, ACT_BRIDGE = 6, ACT_LOWER = 7;

    private static final double MOVE_BASE = 1.0;  // 每步基础代价（≈1 格/秒的节奏单位）
    private static final double MOVE_DIAG = 0.4;  // 斜走略贵
    private static final double DIG_OVERHEAD = 0.3; // 每挖一格的固定开销（换工具/瞄准）
    private static final double FALL_PENALTY = 0.15; // 下落罚（每格²），让楼梯优于直落

    /**
     * 启发权重（docs/plan/R1-pathfinding.md §A，主人拍板 1.8 实测后校）：
     * 加权估价优先减少展开；累计编辑状态、重用折扣和工作到达费用不提供最优界保证。
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

    /** Bounded algorithm callers use this cap; continuous navigation uses it as a collection watermark. */
    public static final int MAX_STORED_NODES = 32_768;
    public static final int SEGMENT_DISTANCE = 32;
    private static final int GREEDY_NODES = 1000;
    private static final int IMPROVE_NODES = 64;
    private static final int PARTIAL_CANDIDATES = 16;
    private static final int EXISTING_PATH_NODES = 256;
    private int firstAnswerAt = -1;
    private boolean preferExisting, existingChecked, existingPass, existingPath;
    private int existingExpanded;

    private record State(long position, int digs, int places, TerrainEdits terrain) { }

    private record Node(State key, int x, int y, int z, int digs, int places,
                        double g, double f, Node prev, Step step) {
    }

    private final DigSampler s;
    private final int maxNodes;
    private int nodeLimit;
    private final int maxDigs;
    private final int sx, sy, sz;
    private final double hWeight;
    private NavigationGoal goal;
    private ApproachEstimate estimate;
    private boolean prepared;
    private Node stoppingNode;
    private double stoppingCost = Double.POSITIVE_INFINITY;
    private double goalCost = -1;
    private final Map<String, Integer> rejected = new java.util.LinkedHashMap<>();
    private final List<PathFailure> rejectionSamples = new ArrayList<>();
    private String budgetKind;
    private boolean segmented, greedy, segmentEnd;
    private double segmentRemaining;
    private TerrainEdits initialTerrain = TerrainEdits.EMPTY;

    private final PriorityQueue<Node> open =
            new PriorityQueue<>(java.util.Comparator.comparingDouble(Node::f)
                    // Reach alone can leave many nonworking stances tied at zero. Try dry approaches
                    // before inventing edit histories, without changing admission or accumulated cost.
                    .thenComparingInt(n -> n.digs + n.places)
                    .thenComparingDouble(n -> goal.approach(n.x, n.y, n.z))
                    .thenComparingDouble(Node::g).thenComparingLong(n -> n.key.position()));
    private final Map<State, Node> best = new HashMap<>();
    private int storedNodes = 1;
    private int maxStoredNodes = MAX_STORED_NODES;
    private int collectionAt = MAX_STORED_NODES;
    private int createdNodes = 1, storageCollections;
    private java.util.ArrayDeque<Node> retaining;
    private java.util.Set<Node> retained;

    private int expanded;
    private int batches = 1;
    /** 累计搜索 CPU 时长（验尸/双帽口径用）；totalBudgetNanos 到点即撞帽，与节点帽同位。 */
    private long usedNanos;
    private long totalBudgetNanos = Long.MAX_VALUE / 8;
    /** 旧路线格集（R1-S3 抑抖）；null=首搜。 */
    private java.util.Set<Long> reuse;
    private boolean finished;
    private boolean budgetReached;
    /** 已扩展（settled）节点中距目标体积最近者（同 L1 取 g 小）——部分提交候选。 */
    private Node bestPartial;
    private java.util.Iterator<Node> partialCandidates;
    private final List<Node> partialFrontier = new ArrayList<>();
    private int partialCandidateChecks;
    private Node partialProbe, partialCursor;
    private boolean preparingPartial;
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
        this(sampler, sx, sy, sz, tx, ty, tz, maxNodes, maxDigs, hWeight, 1);
    }

    public DigAStar(DigSampler sampler, int sx, int sy, int sz, int tx, int ty, int tz,
                    int maxNodes, int maxDigs, double hWeight, int goalRadius) {
        this.s = sampler;
        this.sx = sx;
        this.sy = sy;
        this.sz = sz;
        this.maxNodes = maxNodes;
        this.nodeLimit = maxNodes;
        this.maxDigs = maxDigs;
        this.hWeight = hWeight;
        if (goalRadius < 0 || goalRadius > 1) throw new IllegalArgumentException("Invalid goal radius");
        this.goal = NavigationGoal.position(tx, ty, tz, goalRadius);
        Node start = start();
        best.put(start.key, start);
        open.add(start);
    }

    public DigAStar(DigSampler sampler, int sx, int sy, int sz, int tx, int ty, int tz,
                    int maxNodes, int maxDigs, NavigationGoal goal) {
        this(sampler, sx, sy, sz, tx, ty, tz, maxNodes, maxDigs, H_WEIGHT, 0);
        this.goal = java.util.Objects.requireNonNull(goal);
        // Replace the initial priority, whose estimate used the positional constructor.
        open.clear();
        Node start = start();
        best.put(start.key, start);
        open.add(start);
    }

    private Node start() {
        var foot = prepared && s.geometry() != null ? s.geometry().foot(sx, sy, sz, initialTerrain) : null;
        return new Node(new State(pack(sx, sy, sz), 0, 0, initialTerrain),
                sx, sy, sz, 0, 0, 0, h(sx, sy, sz, initialTerrain), null,
                new Step(sx, sy, sz, List.of(), List.of(), ACT_START, foot == null ? sy : foot.height(), false));
    }

    /** Edits belong to the preceding route, so they do not consume this search's remaining stock. */
    DigAStar after(TerrainEdits terrain) {
        if (prepared || estimate != null || expanded != 0) throw new IllegalStateException("search already started");
        initialTerrain = java.util.Objects.requireNonNull(terrain);
        open.clear();
        best.clear();
        Node initial = start();
        best.put(initial.key, initial);
        open.add(initial);
        return this;
    }

    /** Both passes share the node, storage and time account and the same transition checks. */
    public DigAStar segments() {
        if (prepared || estimate != null || expanded != 0) throw new IllegalStateException("search already started");
        segmented = greedy = true;
        segmentRemaining = Math.max(0, goal.remaining(sx, sy, sz) - SEGMENT_DISTANCE);
        open.clear();
        best.clear();
        Node initial = start();
        best.put(initial.key, initial);
        open.add(initial);
        return this;
    }

    public boolean segmentEnd() { return segmentEnd; }
    boolean startsAt(int x, int y, int z) { return sx == x && sy == y && sz == z; }

    /** Construction movement shares this preference; ordinary mining keeps its existing search policy. */
    public DigAStar preferExistingPaths() {
        if (prepared || estimate != null || existingChecked) throw new IllegalStateException("search already started");
        preferExisting = true;
        return this;
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
        try {
        for (int i = 0; i < budget; i++) {
            if (retaining != null) {
                if (usedNanos + System.nanoTime() - t0 >= totalBudgetNanos) {
                    retaining = null;
                    retained = null;
                    return budgetFailure("TIME", "搜索整理耗尽累计时间预算。");
                }
                if (System.nanoTime() - t0 >= sliceNanos) return false;
                if (!retainOne()) {
                    i--; // Reclaiming records spends slice time, not forward expansion allowance.
                    continue;
                }
                if (storedNodes() >= maxStoredNodes)
                    return budgetFailure("STORAGE", "当前仍被引用的路径状态已到存储上限，尚未证明目标无路。");
                continue;
            }
            if (preparingPartial) {
                if (usedNanos + System.nanoTime() - t0 >= totalBudgetNanos) {
                    preparingPartial = false;
                    partialCandidates = null;
                    finished = true;
                    return true;
                }
                if (System.nanoTime() - t0 >= sliceNanos) return false;
                if (preparePartial()) return true;
                i--; // Candidate inspection spends slice time, not forward expansion nodes.
                continue;
            }
            if (!prepared) {
                if (!existingChecked) {
                    boolean requested = preferExisting || goal.estimateWorkStances();
                    if (requested) {
                        if (usedNanos + System.nanoTime() - t0 >= totalBudgetNanos)
                            return budgetFailure("TIME", "搜索准备耗尽累计时间预算。");
                        if (System.nanoTime() - t0 >= sliceNanos) return false;
                    }
                    existingChecked = true;
                    // Strict work goals often have a wide reach plateau. Search existing access with
                    // travel cost before the greedy pass can turn that plateau into edit histories.
                    existingPass = requested && maxNodes >= 8
                            && (maxDigs > 0 || s.maxPlaces() > 0);
                    if (existingPass) {
                        prepared = true;
                        restartFrontier();
                        continue;
                    }
                }
                if (estimate == null) {
                    if (goal.remaining(sx, sy, sz) == 0
                            && usedNanos + System.nanoTime() - t0 < totalBudgetNanos
                            && goal.arrivalCost(s, sx, sy, sz, initialTerrain) == 0) {
                        prepared = true;
                        return arrive(start(), 0);
                    }
                    estimate = new ApproachEstimate(s, goal, sx, sy, sz,
                            goal.estimateWorkStances() || !greedy && maxDigs > 0 ? maxNodes / 4 : 0);
                }
                if (!estimate.done()) {
                    // Preprocessing is optional, but its CPU, work and allocations are never free.
                    if (usedNanos + System.nanoTime() - t0 >= totalBudgetNanos)
                        return budgetFailure("TIME", "搜索预估与展开耗尽累计时间预算。");
                    if (System.nanoTime() - t0 >= sliceNanos) return false;
                    estimate.advance();
                    continue;
                }
                prepared = true;
                open.clear();
                best.clear();
                Node initial = start();
                best.put(initial.key, initial);
                open.add(initial);
            }
            if (existingPass && (existingExpanded >= Math.min(EXISTING_PATH_NODES, maxNodes / 8)
                    || open.isEmpty())) {
                existingPass = false;
                prepared = false;
                restartFrontier();
                continue;
            }
            if (!existingPass && greedy && expanded >= Math.min(GREEDY_NODES, maxNodes / 4))
                weightedPass();
            if (!prepared) { i--; continue; }
            if (segmented && stoppingNode != null && expanded - firstAnswerAt >= IMPROVE_NODES)
                return arrive(stoppingNode, stoppingCost);
            if (!greedy && stoppingNode != null && (open.isEmpty() || stoppingCost <= open.peek().f))
                return arrive(stoppingNode, stoppingCost);
            Node cur = open.poll();
            if (cur == null) {
                return fail("NO_PATH:搜索空间走完了也没有路——那边是封闭的。");
            }
            Node b = best.get(cur.key);
            if (b != cur) {
                continue; // 陈旧堆项
            }
            double arrival = goal.arrivalCost(s, cur.x, cur.y, cur.z, cur.key.terrain());
            if (!Double.isFinite(arrival) && segmented && segmentRemaining > 0
                    && goal.remaining(cur.x, cur.y, cur.z) > 0
                    && goal.remaining(cur.x, cur.y, cur.z) <= segmentRemaining) arrival = 0;
            if (existingPass && arrival == 0) {
                existingPath = true;
                return arrive(cur, cur.g);
            }
            if (!existingPass && Double.isFinite(arrival) && arrival >= 0 && cur.g + arrival < stoppingCost) {
                stoppingNode = cur;
                stoppingCost = cur.g + arrival;
                if (firstAnswerAt < 0) firstAnswerAt = expanded;
                if (greedy) {
                    weightedPass();
                    continue;
                }
                if (stoppingCost <= cur.f && (open.isEmpty() || stoppingCost <= open.peek().f))
                    return arrive(cur, stoppingCost);
            }
            // 部分提交候选：只在 settled 节点里选（陈旧堆项已过滤），距体积最近、同距取 g 小
            if (bestPartial == null || l1(cur.x, cur.y, cur.z) < l1(bestPartial.x, bestPartial.y, bestPartial.z)
                    || (l1(cur.x, cur.y, cur.z) == l1(bestPartial.x, bestPartial.y, bestPartial.z)
                        && cur.g < bestPartial.g)) {
                bestPartial = cur;
            }
            if (chargedNodes() >= nodeLimit) {
                // This node has not been expanded yet. Retain it for the next batch.
                open.add(cur);
                return budgetFailure("NODES", "本批路线搜索已展开 " + expanded
                        + " 节点，尚未证明目标不可达。");
            }
            expanded++;
            if (existingPass) existingExpanded++;
            expand(cur);
            if (storedNodes() >= Math.min(collectionAt, maxStoredNodes)) {
                beginStorageCollection();
                return false;
            }
            // 每 16 拓才探一次钟：nanoTime 本身不免费，粗粒度换开销（帽语义不差这 16 节点）
            if ((expanded & 15) == 0) {
                long now = System.nanoTime();
                if (usedNanos + (now - t0) >= totalBudgetNanos) {
                    return budgetFailure("TIME", "搜索耗尽了 " + (totalBudgetNanos / 1_000_000)
                            + "ms 时间预算（展开 " + expanded + " 节点）。目标太远或地形太纠缠，请分短段移动。");
                }
                if (now - t0 >= sliceNanos) {
                    return false; // 本拍切片用尽，下拍续（未出结果）
                }
            }
        }
        return false;
        } finally {
            usedNanos += System.nanoTime() - t0;
        }
    }

    private boolean arrive(Node node, double cost) {
        goalNode = node;
        goalCost = cost;
        segmentEnd = segmented && goal.remaining(node.x, node.y, node.z) > 0;
        path = reconstruct(node);
        finished = true;
        return true;
    }

    private void weightedPass() {
        greedy = false;
        // Pay for relaxed terrain guidance only if the fast feasibility pass found no answer.
        if (stoppingNode == null) {
            prepared = false;
            estimate = null;
        }
        restartFrontier();
    }

    private void restartFrontier() {
        open.clear();
        best.clear();
        Node initial = start();
        storedNodes++;
        if (createdNodes < Integer.MAX_VALUE) createdNodes++;
        best.put(initial.key, initial);
        open.add(initial);
    }

    private void beginStorageCollection() {
        open.removeIf(node -> best.get(node.key) != node);
        retaining = new java.util.ArrayDeque<>(best.values());
        retaining.addAll(partialFrontier);
        if (stoppingNode != null) retaining.add(stoppingNode);
        if (bestPartial != null) retaining.add(bestPartial);
        retained = Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        storageCollections++;
    }

    /** Parent chains preserve edit history; only unreachable records and stale heap entries are reclaimed. */
    private boolean retainOne() {
        Node node = retaining.poll();
        if (node != null) {
            if (retained.add(node) && node.prev != null) retaining.add(node.prev);
            return false;
        }
        storedNodes = retained.size();
        retaining = null;
        retained = null;
        collectionAt = (int) Math.min(Integer.MAX_VALUE, (long) storedNodes() + MAX_STORED_NODES);
        return true;
    }

    private boolean budgetFailure(String kind, String why) {
        // A resource boundary limits improvement; it cannot invalidate an already feasible answer.
        if (stoppingNode != null) return arrive(stoppingNode, stoppingCost);
        budgetReached = true;
        budgetKind = kind;
        if (segmented && !"TIME".equals(kind)) {
            failure = "BUDGET_EXCEEDED:" + why;
            preparingPartial = true;
            partialCandidates = List.copyOf(partialFrontier).iterator();
            partialProbe = partialCursor = bestPartial;
            bestPartial = null;
            return false;
        }
        if (segmented) bestPartial = null;
        return fail("BUDGET_EXCEEDED:" + why);
    }

    /** Keep the frontier and edit histories; another batch does not repeat the settled search. */
    boolean continueSearch(int nodes) {
        if (!finished || !segmented || !budgetReached || !"NODES".equals(budgetKind)
                || nodes < 1 || nodes > NavigationBudget.NODES || nodeLimit > Integer.MAX_VALUE - nodes)
            return false;
        nodeLimit += nodes;
        batches++;
        finished = budgetReached = false;
        failure = budgetKind = null;
        partialCandidates = null;
        partialProbe = partialCursor = null;
        return true;
    }

    /**
     * Inspect candidates and return legs inside advance's time slice. A bad tail is cut before
     * its failing edge, then the shorter prefix is checked against its own final terrain.
     */
    private boolean preparePartial() {
        if (partialProbe != null) {
            if (partialCursor.prev == null) {
                if (betterPartial(partialProbe)) bestPartial = partialProbe;
                partialProbe = partialCursor = null;
            } else if (PathTransition.check(s, partialProbe.key.terrain(),
                    partialCursor.x, partialCursor.y, partialCursor.z,
                    partialCursor.prev.x, partialCursor.prev.y, partialCursor.prev.z, 0, 0).plan() == null) {
                partialProbe = partialCursor = partialCursor.prev;
                if (!betterPartial(partialProbe)) partialProbe = partialCursor = null;
            } else {
                partialCursor = partialCursor.prev;
            }
            return false;
        }
        if (partialCandidates.hasNext()) {
            Node candidate = partialCandidates.next();
            partialCandidateChecks++;
            if (betterPartial(candidate)) partialProbe = partialCursor = candidate;
            return false;
        }
        partialCandidates = null;
        preparingPartial = false;
        finished = true;
        return true;
    }

    private boolean betterPartial(Node candidate) {
        if (candidate.prev == null || goal.remaining(sx, sy, sz) - l1(candidate.x, candidate.y, candidate.z) < 1)
            return false;
        return bestPartial == null || l1(candidate.x, candidate.y, candidate.z) < l1(bestPartial.x, bestPartial.y, bestPartial.z)
                || l1(candidate.x, candidate.y, candidate.z) == l1(bestPartial.x, bestPartial.y, bestPartial.z)
                && candidate.g < bestPartial.g;
    }

    private void nominatePartial(Node candidate) {
        if (!segmented || candidate.prev == null
                || goal.remaining(sx, sy, sz) - l1(candidate.x, candidate.y, candidate.z) < 1) return;
        for (int i = 0; i < partialFrontier.size(); i++) {
            var previous = partialFrontier.get(i);
            if (previous.key.position() != candidate.key.position()) continue;
            if (previous.g <= candidate.g) return;
            partialFrontier.remove(i);
            break;
        }
        var order = java.util.Comparator.comparingDouble((Node node) -> l1(node.x, node.y, node.z))
                .thenComparingDouble(Node::g).thenComparingLong(node -> node.key.position());
        int index = 0;
        while (index < partialFrontier.size() && order.compare(partialFrontier.get(index), candidate) <= 0) index++;
        if (index >= PARTIAL_CANDIDATES) return;
        partialFrontier.add(index, candidate);
        if (partialFrontier.size() > PARTIAL_CANDIDATES) partialFrontier.removeLast();
    }

    /** 累计搜索 CPU 预算（纳秒）；PathTask 传 SEARCH_TOTAL_NS。越界后与节点帽同位撞帽。 */
    public void totalBudget(long nanos) {
        this.totalBudgetNanos = nanos;
    }

    public void storageBudget(int records) {
        if (records < 1 || records > MAX_STORED_NODES) throw new IllegalArgumentException("records");
        if (prepared || estimate != null) throw new IllegalStateException("search already prepared");
        maxStoredNodes = records;
    }

    /** Ordinary navigation collects stale records incrementally instead of failing at a fixed record count. */
    public DigAStar continuousStorage() {
        if (prepared || estimate != null) throw new IllegalStateException("search already prepared");
        maxStoredNodes = Integer.MAX_VALUE;
        return this;
    }

    public long elapsedNanos() { return usedNanos; }

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

    /** 成功时的路径（含 ACT_START 起点）；失败时为 null。 */
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

    public int storedNodes() {
        return storedNodes + (estimate == null ? 0 : estimate.admitted());
    }
    public int createdNodes() { return createdNodes; }
    public int storageCollections() { return storageCollections; }
    public int partialCandidateChecks() { return partialCandidateChecks; }

    public int estimateExpanded() { return estimate == null ? 0 : estimate.expanded(); }
    /** Segmented route expansion and optional preprocessing have separate bounded accounts. */
    public int chargedNodes() { return expanded + (segmented ? 0 : estimateExpanded()); }
    public int batches() { return batches; }
    public int estimateSeedProbes() { return estimate == null ? 0 : estimate.seedProbes(); }
    public int estimatedWorkStances() { return estimate == null ? 0 : estimate.workStances(); }
    public int existingPathExpanded() { return existingExpanded; }
    public boolean usedExistingPath() { return existingPath; }
    public boolean departureBlocked() {
        return failure != null && failure.startsWith("NO_PATH:") && best.size() == 1;
    }
    public String budgetKind() { return budgetKind; }
    public Map<String, Integer> rejections() { return Map.copyOf(rejected); }
    public List<PathFailure> rejectionSamples() { return List.copyOf(rejectionSamples); }

    /** 是否因节点预算帽而结束（区别于 open 空的真 NO_PATH）。 */
    public boolean budgetReached() {
        return budgetReached;
    }

    /**
     * Legacy searches require PARTIAL_MIN_GAIN; segmented candidates have already been cut
     * and checked inside advance, including alternative frontiers and their final edit history.
     * 非撞帽结束（成功/真封闭/推进不足）返回 false。
     */
    public boolean partialAvailable() {
        if (!finished || !budgetReached || bestPartial == null) {
            return false;
        }
        if (segmented) return true;
        if (goal.remaining(sx, sy, sz) - l1(bestPartial.x, bestPartial.y, bestPartial.z)
                < PARTIAL_MIN_GAIN) return false;
        return true;
    }

    /** 半程路径（含 ACT_START 起点）；仅 {@link #partialAvailable()} 为真时非 null。 */
    public List<Step> partialPath() {
        return partialAvailable() ? reconstruct(bestPartial) : null;
    }

    /** 降级终点距目标体积的 L1（PARTIAL 文案里的"还差 d 格"）；无候选时 -1。 */
    public int remainingL1() {
        return bestPartial == null ? -1 : (int) Math.ceil(l1(bestPartial.x, bestPartial.y, bestPartial.z));
    }

    /** 成功路径的总代价 g（抑抖断言/观测用）；未成功为 -1。 */
    public double pathCost() {
        return goalNode == null ? -1 : goalCost;
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

    private void expand(Node cur) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                for (int dy = -1; dy <= 1; dy++) {
                    if (dx != 0 || dy != 0 || dz != 0) {
                        relax(cur, dx, dy, dz);
                    }
                }
                if (Math.abs(dx) + Math.abs(dz) == 1 && s.geometry() != null)
                    for (int depth = 2; depth <= Math.ceil(s.geometry().maxDrop()); depth++)
                        relax(cur, dx, -depth, dz);
            }
        }
    }

    private void relax(Node cur, int dx, int dy, int dz) {
        if (storedNodes() >= maxStoredNodes) return;
        int bx = cur.x + dx, by = cur.y + dy, bz = cur.z + dz;
        var check = PathTransition.check(s, cur.key.terrain(), cur.x, cur.y, cur.z, bx, by, bz,
                existingPass ? 0 : maxDigs - cur.digs, existingPass ? 0 : s.maxPlaces() - cur.places);
        if (check.plan() == null) {
            // Preference-only refusals do not describe the permissions of the full search.
            if (existingPass) return;
            int seen = rejected.merge(check.reason(), 1, Integer::sum);
            if (seen == 1 && rejectionSamples.size() < 8)
                rejectionSamples.add(new PathFailure(-1, check.reason(), cur.key.position(),
                        pack(bx, by, bz), check.cell()));
            return;
        }
        var plan = check.plan();
        long destKey = pack(bx, by, bz);
        // 抑抖（R1-S3）：旧路线上的落脚格本步基础价先打折，后面逐项再打
        double cost = (MOVE_BASE + (dx != 0 && dz != 0 ? MOVE_DIAG : 0))
                * (reuse != null && reuse.contains(destKey) ? BIAS_REUSE : 1.0);
        for (long dc : plan.dig()) {
            double sec = s.geometry()!=null && dx==0 && dz==0 && dy==-1
                    ? s.loweringSeconds(cur.x,cur.y,cur.z,cur.key.terrain())
                    : s.digSeconds(unpackX(dc), unpackY(dc), unpackZ(dc));
            cost += digCost(sec) * (reuse != null && reuse.contains(dc) ? BIAS_REUSE : 1.0);
        }
        for (long pc : plan.place()) {
            cost += s.placeCost(bx, by - 1, bz)
                    * (reuse != null && reuse.contains(pc) ? BIAS_REUSE : 1.0);
        }
        // 3) 下落风险
        if (dy < 0) {
            cost += FALL_PENALTY * dy * dy;
        }

        int digs = cur.digs + plan.dig().size(), places = cur.places + plan.place().size();
        State key = new State(destKey, digs, places, plan.terrain());
        double g = cur.g + cost;
        Node old = best.get(key);
        if (old != null && old.g <= g) {
            return;
        }
        var step = new Step(bx, by, bz, plan.dig(), plan.place(),
                classify(cur, bx, by, bz, plan.dig(), plan.place()), plan.feetY(), plan.jump());
        Node n = new Node(key, bx, by, bz, digs, places, g,
                (greedy && !existingPass ? 0 : g) + h(bx, by, bz, plan.terrain()), cur, step);
        nominatePartial(n);
        storedNodes++;
        if (createdNodes < Integer.MAX_VALUE) createdNodes++;
        best.put(key, n);
        open.add(n);
    }

    private double h(int x, int y, int z, TerrainEdits edits) {
        // 距目标**体积**（3×3×3，与 isGoal 同口径）而非距中心：旧 h 到中心在体积边缘最多多算 3，
        // 与体积终点判据不自洽也是噪声源。详见 H_WEIGHT 注释：加权是**故意**的，不可采纳。
        double l = Math.max(0, l1(x, y, z) - (segmented ? segmentRemaining : 0));
        if (existingPass) return H_UNIT * l * (reuse == null ? 1 : BIAS_REUSE);
        // The first answer follows the goal gradient. Optional edit guidance is reserved for
        // improvement: its truncated field can otherwise pull a short tunnel back to the surface.
        double work = goal.estimateWorkStances() && estimate != null
                ? estimate.cost(x, y, z, edits) : 0;
        if (greedy) return Math.max(H_UNIT * l, work);
        double guidance = l == 0 ? 0 : H_UNIT * l + (estimate == null ? 0
                : estimate.cost(x, y, z, edits) * (reuse == null ? 1 : BIAS_REUSE));
        if (goal.estimateWorkStances()) guidance = Math.max(H_UNIT * l, work);
        return (segmented ? 1.5 : hWeight) * guidance;
    }

    /** 到目标体积的 L1（体积内=0）。部分提交用它做"推进量"口径，与 h 同一把尺。 */
    private double l1(int x, int y, int z) {
        return goal.remaining(x, y, z);
    }

    public static double digCost(double seconds) { return seconds + DIG_OVERHEAD; }

    /** Parent objects retain the exact edit history even when a cheaper sibling replaces a map entry. */
    private List<Step> reconstruct(Node goal) {
        List<Step> steps = new ArrayList<>();
        for (Node n = goal; n != null; n = n.prev) steps.add(n.step);
        Collections.reverse(steps);
        return steps;
    }

    /** Upper cells open sight to the lower ones; descent also needs clearance at departure height. */
    public static List<Long> clearanceCells(int fromY, int x, int y, int z) {
        List<Long> cells = new ArrayList<>(3);
        for (int cy = clearanceTop(fromY, y); cy >= y; cy--) {
            cells.add(pack(x, cy, z));
        }
        return cells;
    }

    private static int clearanceTop(int fromY, int toY) {
        return Math.max(fromY, toY) + 1;
    }

    private byte classify(Node from, int x, int y, int z, List<Long> dig, List<Long> place) {
        int dx = x - from.x, dy = y - from.y, dz = z - from.z;
        boolean vert = dx == 0 && dz == 0;
        if (!place.isEmpty()) {
            return vert ? ACT_PILLAR : ACT_BRIDGE;
        }
        if (!dig.isEmpty()) {
            return s.geometry()!=null && vert && dy < 0 ? ACT_LOWER : ACT_DIG;
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
