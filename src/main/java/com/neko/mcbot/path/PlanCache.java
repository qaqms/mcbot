package com.neko.mcbot.path;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import net.minecraft.core.BlockPos;

/**
 * R2-S4「lastPlan 复用」的缓存：记住**上一次算完的那条路**，
 * 供"确认后重发同一次 move_to"直接续用，省掉一整次重搜（实测首搜 ≈27 tick ≈1.35s）。
 *
 * <p><b>为什么必须落在任务对象外面</b>：确认流程产出的是**两个 PathTask 实例**
 * （{@code MoveToTool} 每次都 new 一个），字段带不过去；而静态单槽会在多同伴时互相顶掉，
 * 所以按同伴 UUID 分槽。全部读写都在服务器主线程（`TickTask`/工具派发都在那条线上），
 * 不需要加锁。
 *
 * <p><b>命中判据 = 同伴 + 目标 + 起点 + TTL</b>，三条都要：
 * <ul>
 *   <li><b>起点必须也相同</b>：路是"从起点 S 走到目标 T"的序列，{@code path[0]} 就是 S，
 *       执行第一步会把同伴搬到 S。同伴要是已经不在 S 了（比如上一趟已经走到 T），
 *       复用会把它**传送回旧起点**再走一遍——这是可见的行为回归，比"少省一次搜索"严重得多。</li>
 *   <li><b>授权态刻意不入判据</b>：确认流本来就从 {@code may_alter_terrain=false}（回 NEED_CONFIRM）
 *       走到 {@code true}（执行），把授权态当键会让这条优化在唯一该生效的场景里 100% 不命中。
 *       授权态影响的是"要不要先征求点头"，而那个判断在复用时会**用当场重算的清单**重新做
 *       （见 {@link PathTask#tryReusePlan}），所以不存在"绕过点头"的通路。</li>
 * </ul>
 *
 * <p><b>复用的路必须重新 liveify</b>：缓存里的挖/放清单是"当时"世界的读数。复用时不直接用旧清单，
 * 而是拿旧**节点序列**按当前世界重建——这顺带把"路上新冒出一块方块"也治了：
 * 原本可走的节点现在不可通行，重建后会把它记成**待挖格**，于是清单变非空、审批与计数都跟着变，
 * 该问主人点头就还是会问。代价是 O(节点数) 次方块读（微秒级），换来不必重搜（0.4–1.4s）。
 *
 * <p><b>存的是快照，不是引用</b>：执行期 `PathTask` 会就地把 `path` 里的节点换成"还剩几格待挖"
 * （`path.set(cursor, …)`），若缓存直接持有同一个 List，缓存里的清单会被执行过程**悄悄改写**。
 * `put` 存 `path`（Step 是不可变 record，浅拷即快照），于是每次复用拿到的都是
 * "当初算出来的那条完整路"，而不是"上一趟走到哪就剩多少"。注意复用方**依然不得信任**
 * 快照里的 dig/place 清单——世界可能已经变了，必须重新 `liveify`。
 *
 * <p>独立成类（而不是散在 {@code PathTask} 里的静态字段+私有方法）是为了能被单测直接覆盖：
 * TTL 边界、目标/起点不匹配、多同伴互不串味这些正是"静默复用错路"的高风险点。
 */
final class PlanCache {

    /** 复用窗口：主人看到 NEED_CONFIRM 后回一句"确认"的合理间隔；超时按未命中处理（重搜）。 */
    static final long TTL_MS = 30_000L;

    /** 一次已算完的搜索结果。 */
    static final class Entry {
        final UUID who;
        final BlockPos origin;
        final BlockPos target;
        final List<DigAStar.Step> path;
        final long atMs;

        Entry(UUID who, BlockPos origin, BlockPos target, List<DigAStar.Step> path, long atMs) {
            this.who = who;
            this.origin = origin;
            this.target = target;
            this.path = path;
            this.atMs = atMs;
        }

        boolean fresh(long nowMs) {
            long age = nowMs - atMs;
            return age >= 0 && age <= TTL_MS; // 时钟回拨（age<0）按过期处理，不冒险复用
        }
    }

    /** 未命中的原因（只用于日志/自测判读——"复用到底有没有生效"必须可观测）。 */
    enum Miss {
        NONE, NO_SLOT, EXPIRED, OTHER_TARGET, OTHER_ORIGIN
    }

    private final Map<UUID, Entry> slots = new HashMap<>();
    private Miss lastMiss = Miss.NONE;

    /** 上一次 {@link #take} 未命中的原因（NONE = 命中了）。 */
    Miss lastMiss() {
        return lastMiss;
    }

    /**
     * 取一条能用的路。命中返回 entry（槽位保留）；任何一条判据不满足返回 null，
     * 且过期槽顺手清掉（过期条目留着只占内存）。
     */
    Entry take(UUID who, BlockPos origin, BlockPos target, long nowMs) {
        lastMiss = Miss.NONE;
        if (who == null) {
            return miss(Miss.NO_SLOT);
        }
        Entry e = slots.get(who);
        if (e == null) {
            return miss(Miss.NO_SLOT);
        }
        if (!e.fresh(nowMs)) {
            slots.remove(who);
            return miss(Miss.EXPIRED);
        }
        if (!e.target.equals(target)) {
            return miss(Miss.OTHER_TARGET);
        }
        if (!e.origin.equals(origin)) {
            return miss(Miss.OTHER_ORIGIN);
        }
        return e;
    }

    /** 记下刚算好的路（存快照：见类注释"存的是快照，不是引用"）。 */
    void put(UUID who, BlockPos origin, BlockPos target, List<DigAStar.Step> path, long nowMs) {
        if (who == null || origin == null || target == null || path == null) {
            return;
        }
        slots.put(who, new Entry(who, origin, target, List.copyOf(path), nowMs));
    }

    /** 任务作废（取消/超时）：别把一条已废的路留给下一趟。 */
    void drop(UUID who) {
        if (who != null) {
            slots.remove(who);
        }
    }

    /** 测试/日志用：槽位数。 */
    int size() {
        return slots.size();
    }

    /**
     * 全清。产品路径不调（槽位靠 TTL 和"新计划顶旧计划"自然收敛）；给无头验收用：
     * `[m4]` 与 `[m8]` 用同一个基准点、同一个 `+6` 目标，中间只隔几秒——不清的话
     * m8 的场景 A 会命中 m4 留下的那条路，"A 是搜出来的 / B 是复用的"这条判读就不成立。
     */
    void clear() {
        slots.clear();
    }

    private Entry miss(Miss why) {
        lastMiss = why;
        return null;
    }
}
