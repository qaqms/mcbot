package com.neko.mcbot.path;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.neko.mcbot.McbotMod;
import com.neko.mcbot.body.CompanionPlayer;
import com.neko.mcbot.server.BlockMining;
import com.neko.mcbot.server.BlockPlacement;
import com.neko.mcbot.server.PathMaterials;
import com.neko.mcbot.server.ActionPermissions;
import com.neko.mcbot.task.CompanionScheduler;
import com.neko.mcbot.task.ResourceLocks.Region;
import com.neko.mcbot.server.ServerTool.Result;
import com.neko.mcbot.task.TickTask;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;

import java.util.ArrayList;
import java.util.List;

/**
 * 路径任务（M8）：SEARCH（分帧 A*）→ 需要点头的世界 → NEED_CONFIRM 出结果
 * → EXECUTE（逐节点：挖（真计时）→ 放（消耗背包）→ 落位）→ 到达。
 *
 * 执行期复核（DESIGN §8）：每提交 20 个节点，重验接下来 5 个节点的状态
 * （该清的还清着、支撑还在、放过的没被拆）；变了就切 REPLAN_SEARCH 相位从当前
 * 位置**分帧**重搜（R1-S3：旧版单拍同步 while 的单帧冻结点已杀），旧路格 ×0.7 降权
 * 抑抖动，重规划上限 2 次，再变就 NO_PATH 交还给模型决策。搜索双帽：节点 8000 +
 * 累计 CPU 400ms，先到先停；撞帽且推进≥4 格提交 PARTIAL 半程段，不足则 NO_PROGRESS。
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
    /** 单帧搜索时间切片（R1-S3 双帽之一）：节点帽 300 防无界，时间帽 6ms 防重节点撑爆单帧。 */
    private static final long SEARCH_SLICE_NS = 6_000_000L;
    /** 单次搜索累计 CPU 预算（另一帽）：越界即撞帽走 PARTIAL/NO_PROGRESS 定性，TPS 无关。 */
    private static final long SEARCH_TOTAL_NS = 400_000_000L;
    /**
     * NEED_CONFIRM 清单内联进回执文本的字节预算（效率评估 §5.4）。
     * 取 1200B：足够列十余种方块（同类合并后通常全列得下），同时把这段
     * 长期留在对话历史里的文字压在可控范围内（信封上限是 32765B，不是约束方）。
     */
    private static final int CONFIRM_INLINE_BUDGET = 1_200;

    /**
     * NEED_CONFIRM 后重发时的搜索结果复用缓存（R2-S4 "lastPlan"）。
     *
     * <p><b>为什么需要</b>：确认流是"两次 move_to"——第一次算出路、回 NEED_CONFIRM；
     * 主人批准清单、模型带 {@code authorization_id} 重发时，旧的 {@link PathTask} 实例已经随
     * {@code Progress.Done} 被丢弃（{@code MoveToTool} 每次都 {@code new PathTask}），于是**同一条路
     * 要完整重搜一遍**（含 memo 重建、{@code liveify} 活体清单重算）。
     *
     * <p><b>省多少取决于路的难度</b>：短直路本来就只展开几百节点（实测 6 节点直路
     * {@code expanded=302 / 12ms}），省下来可以忽略；难路（R1 那次 16 格复杂山地）才是
     * 设计卡记的"0.4–1.4s CPU + 分帧约 27 拍"量级。所以这条优化的判据不是"省了多少毫秒"，
     * 而是"**复用确实发生且不重搜**"——日志里以 {@code memo=reuse(未搜索)} 为读数特征。
     *
     * <p>判据、TTL、槽位规则都收在 {@link PlanCache} 里（纯逻辑、可单测）；
     * 本类只负责在"计划就绪"与"任务作废"两个时刻喂它。
     */
    private static final PlanCache PLANS = new PlanCache();

    private enum Phase { SEARCH, REPLAN_SEARCH, EXECUTE }

    private final BlockPos target;
    private final ActionPermissions permissions;
    private final ActionPermissions.Context context;
    private final ActionPermissions.Execution authorization;
    private final CompanionScheduler scheduler;
    private final ServerLevel level;
    /** 计划缓存的槽位键；tick 里从同伴取（构造时拿不到），作废时用它清槽。 */
    private java.util.UUID cacheKey;

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
    /** 本轮搜索是重规划（失败文案加"路被改变后"前缀；旧路降权集只在重搜时装）。 */
    private boolean wasReplan;
    /** PARTIAL 半程段：走完后的到达文案换成"缩短射程"话术（设计卡 §C）。 */
    private boolean partialTail;
    private int partialRemain = -1;
    /** 复核认定"变了"的格（§D：这点周围不入旧路降权集，防把坏格当旧路便宜复用）。 */
    private long replanBadKey;
    private boolean replanBadSet;
    /** 本趟搜索要携带的旧路降权集（验尸重开也自动续上；首搜=null）。 */
    private java.util.Set<Long> reusePending;
    private List<DigAStar.Step> path;
    private int cursor;            // 下一个要进入的节点（path[0] 是起点）
    private int executed;          // 已提交节点计数（复核节拍用）
    private int replans;
    private int tickWarmup;        // 进场让世界加载/防抖的缓冲拍

    // 挖掘子状态
    private BlockPos digPos;
    private BlockMining mining;
    private int actualDigs;
    private int actualPlaces;
    private int leftoverItems;

    // 行走插值子状态（同层 WALK 沿用滑步节奏，视觉与 M4 版一致）
    private double wx, wy, wz;

    public PathTask(ServerLevel level, BlockPos target, ActionPermissions permissions,
                    ActionPermissions.Context context, ActionPermissions.Scope approved,
                    CompanionScheduler scheduler) {
        this.level = level;
        this.target = target;
        this.permissions = permissions;
        this.context = context;
        this.authorization = new ActionPermissions.Execution(approved);
        this.scheduler = scheduler;
    }

    @Override
    public Progress tick(CompanionPlayer c) {
        if (c.level() != level) {
            onAbort();
            return terminal(new Result(false, "TARGET_LOST:身体已换维度，原路线作废。", null));
        }
        if (++tickWarmup < 2) {
            return running(); // 等一拍出出生/传送的下坠，起点判据才稳
        }
        Progress progress = phase == Phase.EXECUTE ? executeTick(c) : searchTick(c);
        return progress instanceof Progress.Done done ? terminal(done.result()) : progress;
    }

    private Progress terminal(Result result) {
        clearCrack();
        if (!result.feedback().startsWith("NEED_CONFIRM:")) dropPlan();
        JsonObject data = result.data() == null ? new JsonObject() : result.data().deepCopy();
        data.addProperty("removed_blocks", actualDigs);
        data.addProperty("placed_blocks", actualPlaces);
        data.addProperty("remaining_items", leftoverItems);
        String feedback = result.feedback();
        if (!result.ok() && !feedback.startsWith("PARTIAL:") && actualDigs + actualPlaces > 0) {
            feedback += "本趟实际挖 " + actualDigs + " 格、放 " + actualPlaces
                    + " 格，" + leftoverItems + " 个掉落未入包；已完成的改动不回滚。";
        }
        return new Progress.Done(new Result(result.ok(), feedback, data));
    }

    // ---- SEARCH ----

    private Progress searchTick(CompanionPlayer c) {
        // 先看有没有"确认前刚算好的那条路"可用（R2-S4 lastPlan）：省掉一整次重搜。
        // 只在首搜阶段尝试；重规划（REPLAN_SEARCH）是"世界变了"的产物，必须重新搜。
        if (search == null && phase == Phase.SEARCH && !wasReplan) {
            if (tryReusePlan(c)) {
                return afterPlanReady(c, path); // tryReusePlan 已把 path/planDigs/planPlaces 装好
            }
            // 未命中要留痕：这条优化一旦因起点微移而永远不命中，"省了一次搜索"就只是日志里的一句谎，
            // 必须能从这行读数看出它到底有没有生效、以及为什么没生效。
            McbotMod.LOG.info("[path] 未复用 lastPlan（{}），照常重搜", PLANS.lastMiss());
        }
        if (search == null) {
            BlockPos from = c.blockPosition();
            sampler = new LevelDigSampler(level, c, from,
                    LevelDigSampler.countPlaceables(c.getInventory()));
            search = newSearch(from);
        }
        if (!search.advance(EXPAND_PER_TICK, SEARCH_SLICE_NS)) {
            // 分帧搜索跨多拍，中途世界会被别人改：验尸超限则丢旧图，从当前位置重开
            if (memo != null && memo.worldChanged()) {
                McbotMod.LOG.info("[path] 验尸超限 stale={}，丢弃旧图重开搜索（第 {} 次）",
                        memo.staleCount(), memoRestarts + 1);
                memoRestarts++;
                search = newSearch(c.blockPosition());
            }
            return running(); // 还在算（分帧）
        }
        if (search.failure() != null) {
            if (search.budgetReached() && search.partialAvailable()) {
                // 撞帽≠失败：提交"距目标最近的已扩展节点"半程段，走完由 arrived 出 PARTIAL 话术
                partialTail = true;
                partialRemain = search.remainingL1();
                List<DigAStar.Step> plan = liveify(search.partialPath());
                McbotMod.LOG.info("[path] 撞帽→半程段：还差约 {} 格 L1，本段 {} 节点（挖 {} 放 {}）{}",
                        partialRemain, plan.size(), planDigs, planPlaces, memoStats());
                return afterPlanReady(c, plan);
            }
            McbotMod.LOG.info("[path] 搜索失败 from={} to={}: {} {}", c.blockPosition().toShortString(),
                    target.toShortString(), search.failure(), memoStats());
            // 撞帽但推进不足下限：不再冒充 BUDGET，换成对模型有行动意义的 NO_PROGRESS（§C）；
            // open 空的真 NO_PATH 永不降级（D 基岩笼场景钉死）
            String lead = wasReplan ? "路被改变后重规划失败：" : "";
            String why = search.budgetReached()
                    ? "NO_PROGRESS:" + lead + "搜索预算用尽（展开 " + search.expanded()
                            + " 节点/" + search.elapsedMillis() + "ms）而没能向目标实质推进。"
                            + "换条路绕开或分更短的段，别硬撞同一方向。"
                    : "NO_PATH:" + lead + search.failure();
            return new Progress.Done(new Result(false, why, null));
        }
        // 搜索成功≠清单真值：用裸 sampler 按当前世界重建挖/放清单，再出确认/执行/计数
        return afterPlanReady(c, liveify(search.path()));
    }

    /**
     * 计划就绪后的公共出口：登记清单、把结果写进 lastPlan 缓存（供确认后重发复用）、
     * 然后出 NEED_CONFIRM 或进执行。
     *
     * <p>把这条出口抽出来是为了让"刚搜出来的"和"从缓存复用的"走**同一条**判定与话术路径——
     * 两条路各写一份的话，迟早会在确认话术或计数上漂移。
     */
    private Progress afterPlanReady(CompanionPlayer c, List<DigAStar.Step> plan) {
        path = plan;
        cursor = 0;
        int digs = planDigs;
        int places = planPlaces;
        rememberPlan(c, plan);
        McbotMod.LOG.info("[path] {}→{} 路径 {} 节点（挖 {} 放 {}）replan={} partial={} {}",
                c.blockPosition().toShortString(), target.toShortString(), path.size(),
                digs, places, replans, partialTail, memoStats());
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
        var changes = changes(plan);
        if (!changes.isEmpty()) {
            if (changes.values().stream().anyMatch(change -> change.expectedState() == null)) {
                return new Progress.Done(new Result(false, "TARGET_LOST:路线改动格未加载，已停止。", null));
            }
            if (!authorization.covers(changes)) {
                return needConfirmation(changes, digs, places);
            }
            List<Region> regions = changes.values().stream().map(change -> {
                BlockPos pos = BlockPos.of(change.position());
                return new Region(context.dimension(), pos.getX() - 1, pos.getY() - 1, pos.getZ() - 1,
                        pos.getX() + 1, pos.getY() + 1, pos.getZ() + 1);
            }).toList();
            if (scheduler == null || !scheduler.reserve(c.getUUID(), regions)) {
                return new Progress.Done(new Result(false, "BUSY:路线改动区域被其他动作占用，已停手。", null));
            }
        }
        phase = Phase.EXECUTE;
        return executeTick(c);
    }

    private java.util.Map<String, ActionPermissions.Change> changes(List<DigAStar.Step> plan) {
        var changes = new java.util.LinkedHashMap<String, ActionPermissions.Change>();
        for (var step : plan) {
            for (long cell : step.dig()) addChange(changes, "dig", cell);
            for (long cell : step.place()) addChange(changes, "place", cell);
        }
        return changes;
    }

    private void addChange(java.util.Map<String, ActionPermissions.Change> changes, String op, long cell) {
        BlockPos pos = new BlockPos(DigAStar.unpackX(cell), DigAStar.unpackY(cell), DigAStar.unpackZ(cell));
        var change = new ActionPermissions.Change(op, pos.asLong(), level.isLoaded(pos) ? level.getBlockState(pos) : null);
        changes.put(ActionPermissions.key(change), change);
    }

    private Progress needConfirmation(java.util.Map<String, ActionPermissions.Change> changes, int digs, int places) {
        clearCrack();
        JsonObject data = confirmData(digs, places);
        StringBuilder cells = new StringBuilder();
        if (changes.size() <= 256) for (var change : changes.values()) {
            BlockPos pos = BlockPos.of(change.position());
            cells.append(change.operation()).append('@')
                    .append(pos.toShortString()).append(' ')
                    .append(sampler.blockName(pos.getX(), pos.getY(), pos.getZ())).append(';');
        }
        String id = com.neko.mcbot.common.WireSize.utf8Bytes(cells.toString()) > 12_000 ? null
                : permissions.propose(new ActionPermissions.Scope(context, target.asLong(), changes));
        if (id != null) data.addProperty("authorization_id", id);
        String instruction = id == null ? "当前无有效任务权限或清单超过展示上限，请缩短路线并重新提交任务。"
                : "用 ask_owner 携带 authorization_id=" + id
                + " 展示完整清单征得主人明确确认，再用 move_to 携带相同 authorization_id 重发；模型布尔不能授权。";
        if (id != null) data.addProperty("authorization_summary",
                "路线目的地 " + target.toShortString() + "；挖 " + digs + " 格、放 " + places + " 格。具体清单：" + cells);
        return new Progress.Done(new Result(false,
                "NEED_CONFIRM:到 " + target.toShortString() + " 需要挖 " + digs + " 格、放 " + places
                        + " 格。" + inlineList() + instruction, data));
    }

    private Progress checkChange(String op, BlockPos pos) {
        var change = new ActionPermissions.Change(op, pos.asLong(), level.getBlockState(pos));
        if (authorization.allows(change)) return null;
        return needConfirmation(changes(liveify(path.subList(cursor, path.size()))), planDigs, planPlaces);
    }

    // ---- lastPlan 缓存（R2-S4）----

    /** 记下刚算好的路（连同**起点**，见 {@link PlanCache} 里"起点必须相同"的理由）。只在主线程调用。 */
    private void rememberPlan(CompanionPlayer c, List<DigAStar.Step> plan) {
        if (c == null) {
            return;
        }
        cacheKey = c.getUUID();
        PLANS.put(cacheKey, c.blockPosition(), target, plan, System.currentTimeMillis());
    }

    /**
     * 尝试续用确认前算好的那条路（判据见 {@link PlanCache#take}）。
     *
     * <p><b>复用的只是节点序列，清单当场重算</b>：{@code liveify} 拿缓存里的节点按**当前**世界
     * 重建挖/放格并刷新 {@code planDigs/planPlaces}。所以
     * ① "要改动世界吗"这个判断用的是新鲜读数，授权态不入缓存键也不会绕过点头；
     * ② 路上新冒出来的方块会被记成待挖格（原来可走、现在不可通行）而不是被无视。
     *
     * @return true = 已装载 path/planDigs/planPlaces，调用方可直接走 afterPlanReady 的后续
     */
    private boolean tryReusePlan(CompanionPlayer c) {
        if (c == null) {
            return false;
        }
        PlanCache.Entry p = PLANS.take(c.getUUID(), c.blockPosition(), target, System.currentTimeMillis());
        if (p == null) {
            return false;
        }
        // 采样器仍要建（执行期 mineOne/placeOne/复核都用它 live 读世界），但**不必重搜**
        sampler = new LevelDigSampler(level, c, c.blockPosition(),
                LevelDigSampler.countPlaceables(c.getInventory()));
        path = liveify(p.path);
        cursor = 0;
        executed = 0;
        // 故意**不建** search 对象：这趟根本没搜，凭空造一个没 advance 过的 DigAStar 只为喂日志，
        // 既白付一次构造（含目标旁的 live 读），又会让日志出现"expanded=0"这种自相矛盾的读数。
        // memoStats() 已按 search==null 单独出一句"未搜索"。
        McbotMod.LOG.info("[path] 复用确认前的搜索结果（lastPlan）：目标 {} 共 {} 节点，"
                        + "按当前世界重算清单（挖 {} 放 {}），省掉一次重搜",
                target.toShortString(), path.size(), planDigs, planPlaces);
        return true;
    }

    /** 任务作废时清掉自己的缓存槽（避免留下一条早已作废的路）。 */
    private void dropPlan() {
        PLANS.drop(cacheKey);
    }

    /**
     * 清空 lastPlan 缓存（**只给无头验收用**，见 {@link PlanCache#clear}）。
     * 生产路径不需要它：槽位按同伴分、被新计划顶替、并受 TTL 约束。
     */
    public static void clearPlanCache() {
        PLANS.clear();
    }

    /**
     * 把"会动哪些方块"直接写进**给模型看的回执文本**（效率评估 §5.4 的真缺陷修复）。
     *
     * <p><b>原来错在哪</b>：文案写的是"明细见 {@code data.blocks}"，但 {@code data} 这条线
     * 从头到尾到不了模型——{@code ServerToolDispatcher} 把它塞进信封，而客户端
     * {@code AgentRunner} 只取 {@code env.str("feedback")}，{@code ToolOutcome} 也只有
     * {@code (ok, feedback)} 两个字段。于是模型**被要求去批准一份自己看不见的清单**，
     * 最可能的补偿动作是再发一次 {@code scan_area}（多一整轮 2–6s）或者盲点头。
     *
     * <p><b>为什么带字节预算而不是全列</b>：这段文字会**留在对话历史里**，而回执越小越省
     * prefill。所以先按**方块种类**合并同类项（比逐格列更省也更好读），再按
     * {@link #CONFIRM_INLINE_BUDGET} 截断，截断时明确写出"还有 N 格未列"——
     * 不能让模型以为清单已经完整（那会变成另一种"看不见却以为看见"）。
     */
    private String inlineList() {
        java.util.LinkedHashMap<String, Integer> byName = new java.util.LinkedHashMap<>();
        for (DigAStar.Step st : path) {
            for (long cell : st.dig()) {
                byName.merge(sampler.blockName(DigAStar.unpackX(cell), DigAStar.unpackY(cell),
                        DigAStar.unpackZ(cell)), 1, Integer::sum);
            }
            for (long cell : st.place()) {
                byName.merge("(放)" + sampler.blockName(DigAStar.unpackX(cell),
                        DigAStar.unpackY(cell), DigAStar.unpackZ(cell)), 1, Integer::sum);
            }
        }
        if (byName.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("要动的方块：");
        int total = 0;
        for (int c : byName.values()) {
            total += c;
        }
        int listedKinds = 0;
        int listedCount = 0;
        for (var e : byName.entrySet()) {
            String part = (listedKinds == 0 ? "" : "、") + e.getKey() + "×" + e.getValue();
            if (com.neko.mcbot.common.WireSize.utf8Bytes(sb + part) > CONFIRM_INLINE_BUDGET) {
                break;
            }
            sb.append(part);
            listedKinds++;
            listedCount += e.getValue();
        }
        if (listedCount < total) {
            sb.append("……还有 ").append(total - listedCount).append(" 格未列");
        }
        return sb.append('。').toString();
    }

    /** 搜索构造统一入口：裸 sampler 必在之前建好；memo 套在搜索侧，执行侧永不见缓存。 */
    private DigAStar newSearch(BlockPos from) {
        if (memoRestarts < 2) {
            memo = new MemoDigSampler(sampler);
        } else {
            memo = null; // 验尸两度超限：这趟世界改得太快，直接 live 读（宁慢不抽）
        }
        DigAStar d = new DigAStar(memo != null ? memo : sampler,
                from.getX(), from.getY(), from.getZ(),
                target.getX(), target.getY(), target.getZ(), MAX_NODES, MAX_DIGS);
        d.totalBudget(SEARCH_TOTAL_NS);
        if (reusePending != null) {
            d.reuseBias(reusePending);
        }
        return d;
    }

    /**
     * 用裸 sampler 按**当前世界**重建每节点的挖/放清单并统计 planDigs/planPlaces。
     * 为什么必须：NEED_CONFIRM 拿给主人点头、服务端绑定授权范围的清单，
     * 如果是 memo 期（搜索跨 27 tick）的旧答案，就是在让主人批准一份"幽灵清单"。
     * 动作字节不重分类（仅播报语义）；执行期 mineOne/placeOne 本就逐格 live 判定，双层自晦。
     */
    private List<DigAStar.Step> liveify(List<DigAStar.Step> plan) {
        long t0 = System.nanoTime();
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
        // 效率评估 §3.1：这一步是**单拍、无分帧、无预算**的全路径活体重扫，
        // 且紧跟在"搜索刚花掉最多 400ms CPU"之后（尖峰叠加）。给它单独计时，
        // 才能判定要不要把它也改成按拍切片——量级此前只能靠猜。
        McbotMod.LOG.info("[brain] liveify nodes={} {}ms", plan.size(),
                (System.nanoTime() - t0) / 1_000_000L);
        return out;
    }

    private String memoStats() {
        // 复用 lastPlan 时本轮**没有**搜索对象：如实说"这趟没搜"，别伪造成
        // memo=off(验尸超限)（那是另一回事）或 expanded=0（会被读成"搜了但没展开"）。
        if (search == null) {
            return "memo=reuse(未搜索)";
        }
        return memo == null ? "memo=off(验尸超限)" : "[memo] expanded=" + search.expanded()
                + " 命中=" + memo.memoHits() + " 实查=" + memo.memoMisses()
                + " 验尸不符=" + memo.staleCount() + " 耗时=" + search.elapsedMillis() + "ms";
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
            beginReplan(c);
            executed = 0;
            return running();
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

        // Newly blocked cells must replan before the body enters them.
        if (!sampler.passable(next.x(), next.y(), next.z())
                || !sampler.passable(next.x(), next.y() + 1, next.z())
                || !sampler.support(next.x(), next.y() - 1, next.z())) {
            if (replans >= MAX_REPLAN) return new Progress.Done(new Result(false,
                    "NO_PATH:下一落脚格已变化，重规划上限已到；已停止移动。", null));
            replanBadKey = DigAStar.pack(next.x(), next.y(), next.z());
            replanBadSet = true;
            beginReplan(c);
            executed = 0;
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
        int need = actualDigs + actualPlaces;
        JsonObject data = new JsonObject();
        data.addProperty("removed_blocks", actualDigs);
        data.addProperty("placed_blocks", actualPlaces);
        data.addProperty("remaining_items", leftoverItems);
        if (partialTail) {
            // §C 回执模板：撞帽从"失败"变"缩短射程"，行动指令写给模型（从本段落点重发）
            return new Progress.Done(new Result(false,
                    "PARTIAL:未能到 " + target.toShortString() + "。已走到能到的最近点 "
                            + at.toShortString() + "，离目标还差约 " + partialRemain + " 格。"
                            + "这一段已走完，请从该点重发 move_to（可分多段）"
                            + (need > 0 ? "；本段动了 " + need + " 个方块。" : "。")
                            + (leftoverItems > 0 ? "有 " + leftoverItems + " 个掉落未装入背包。" : ""), data));
        }
        String fb = "到了 (" + target.toShortString() + ") 附近，站定在 " + at.toShortString()
                + (need > 0 ? "（这一路动了 " + need + " 个方块）。" : "。")
                + (leftoverItems > 0 ? "有 " + leftoverItems + " 个掉落未装入背包。" : "");
        return new Progress.Done(new Result(true, fb, data));
    }

    /**
     * 复核发现路断了 → 切 REPLAN_SEARCH 相位，和首搜共用同一分帧出口（S3：杀掉旧版
     * 单拍同步 while 的单帧冻结点）。旧路格（脚+挖+放）装进降权集 ×0.7 抑抖；
     * 复核认定变了的格周围不入集（§D）——那恰好是不能再信的部分。
     */
    private void beginReplan(CompanionPlayer c) {
        clearCrack();
        BlockPos from = c.blockPosition();
        sampler = new LevelDigSampler(level, c, from,
                LevelDigSampler.countPlaceables(c.getInventory()));
        replans++;
        wasReplan = true;
        partialTail = false;
        reusePending = reuseCells();
        path = null;
        cursor = 0;
        search = newSearch(from);
        phase = Phase.REPLAN_SEARCH;
        McbotMod.LOG.info("[path] 复核失效→分帧重规划（第 {} 次，旧路降权格 {}）", replans, reusePending.size());
    }

    /** 旧路格集：从 cursor-1 往后（含待挖/待放格）；只往未来取——回头路降权会诱导读回振荡。 */
    private java.util.Set<Long> reuseCells() {
        java.util.HashSet<Long> set = new java.util.HashSet<>();
        if (path == null) {
            return set;
        }
        int from = Math.max(0, cursor - 1);
        for (int i = from; i < path.size(); i++) {
            DigAStar.Step st = path.get(i);
            set.add(DigAStar.pack(st.x(), st.y(), st.z()));
            for (long cell : st.dig()) {
                set.add(cell);
            }
            for (long cell : st.place()) {
                set.add(cell);
            }
        }
        if (replanBadSet) {
            int bx = DigAStar.unpackX(replanBadKey), by = DigAStar.unpackY(replanBadKey),
                    bz = DigAStar.unpackZ(replanBadKey);
            set.removeIf(cell -> {
                int x = DigAStar.unpackX(cell), y = DigAStar.unpackY(cell), z = DigAStar.unpackZ(cell);
                return Math.max(Math.abs(x - bx), Math.max(Math.abs(y - by), Math.abs(z - bz))) <= 1;
            });
        }
        return set;
    }

    /** 复核未来节点的格子状态：该清的还清着？支撑还在？失效格记入 replanBadKey。 */
    private boolean validateAhead(CompanionPlayer c) {
        replanBadSet = false;
        int end = Math.min(path.size(), cursor + RECHECK_AHEAD);
        for (int i = cursor; i < end; i++) {
            DigAStar.Step st = path.get(i);
            for (long cell : st.dig()) {
                int x = DigAStar.unpackX(cell), y = DigAStar.unpackY(cell), z = DigAStar.unpackZ(cell);
                if (!level.hasChunkAt(new BlockPos(x, y, z))) {
                    replanBadKey = cell; // 区块卸载，假设失效
                    replanBadSet = true;
                    return false;
                }
                // 挖过的应该还是空的（被人补上=变了）；没挖的仍待挖，不算坏
            }
            if (!sampler.support(st.x(), st.y() - 1, st.z()) && st.place().isEmpty()
                    && i > cursor) {
                replanBadKey = DigAStar.pack(st.x(), st.y() - 1, st.z()); // 原支撑被拆了
                replanBadSet = true;
                return false;
            }
        }
        return true;
    }

    // ---- Shared actions; the path supplies its live sacred/hazard checks. ----

    /** 返回 null=还在挖/刚清完交回主流程；Done=终局（含失败教学）。 */
    private Progress mineOne(CompanionPlayer c, long cell) {
        int x = DigAStar.unpackX(cell), y = DigAStar.unpackY(cell), z = DigAStar.unpackZ(cell);
        BlockPos pos = new BlockPos(x, y, z);
        if (!level.isLoaded(pos)) return miningFailure("TARGET_LOST:开路目标未加载，重新查看路线。");
        if (mining == null && sampler.cleared(x, y, z)) return null;
        Progress permission = checkChange("dig", pos);
        if (permission != null) return permission;
        if (digPos == null || !digPos.equals(pos)) {
            clearCrack();
            digPos = pos;
            mining = BlockMining.forPlayer(c, pos);
        }
        double seconds = sampler.digSeconds(x, y, z);
        if (!DigSampler.feasibleDig(seconds)) {
            return miningFailure("NO_PATH:开路目标现在不可安全挖掘，已停手；换工具或重新规划。");
        }
        Result result = mining.tick();
        if (result == null) return running();
        mining = null;
        digPos = null;
        if (!result.data().get("removed").getAsBoolean()) return new Progress.Done(result);
        actualDigs++;
        authorization.observed("dig", pos.asLong());
        leftoverItems += result.data().get("remaining_count").getAsInt();
        McbotMod.LOG.info("[path] 开路 {} 收进={} 留地={}", pos.toShortString(),
                result.data().get("collected_count").getAsInt(), result.data().get("remaining_count").getAsInt());
        if (!result.data().get("reported_success").getAsBoolean()) return new Progress.Done(result);
        return null;
    }

    private Progress miningFailure(String feedback) {
        clearCrack();
        return new Progress.Done(new Result(false, feedback, null));
    }

    /** Support must be real; an occupied non-support cell cannot advance the route. */
    private Progress placeOne(CompanionPlayer c, long cell) {
        int x = DigAStar.unpackX(cell), y = DigAStar.unpackY(cell), z = DigAStar.unpackZ(cell);
        BlockPos pos = new BlockPos(x, y, z);
        if (!level.isLoaded(pos)) return new Progress.Done(new Result(false, "TARGET_LOST:搭路目标未加载。", null));
        if (sampler.support(x, y, z)) return null;
        Progress permission = checkChange("place", pos);
        if (permission != null) return permission;
        if (!sampler.placeable(x, y, z)) return new Progress.Done(new Result(false,
                "NO_PATH:计划的搭路格现在不可安全放置；重新查看路线。", null));
        Result result = BlockPlacement.place(BlockPlacement.forPlayer(c), pos,
                PathMaterials.find(c.getInventory()), Direction.UP);
        if (!result.ok()) return new Progress.Done(result);
        actualPlaces++;
        authorization.observed("place", pos.asLong());
        if (!sampler.support(x, y, z)) return new Progress.Done(new Result(false,
                "PLACE_FAILED:材料已放置但未形成可站立支撑，已停止移动；查看现场。", result.data()));
        return null;
    }

    private void clearCrack() {
        if (mining != null) mining.abort();
        mining = null;
        digPos = null;
    }

    @Override
    public void onAbort() {
        clearCrack();
        dropPlan(); // 任务作废了，别把一条已废的路留在缓存里给下一趟复用
    }

    @Override public void onFinish() {
        clearCrack();
    }

    /** 供 MoveToTool 在提交前做的距离帽判断。 */
    public static boolean withinCap(BlockPos from, BlockPos to) {
        return Math.abs((long) to.getX() - from.getX()) <= LevelDigSampler.RADIUS_XZ
                && Math.abs((long) to.getZ() - from.getZ()) <= LevelDigSampler.RADIUS_XZ
                && Math.abs((long) to.getY() - from.getY()) <= LevelDigSampler.RADIUS_Y;
    }
}
