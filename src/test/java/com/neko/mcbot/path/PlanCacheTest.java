package com.neko.mcbot.path;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * lastPlan 缓存的纯逻辑单测（R2-S4）。
 *
 * <p>这里钉的是**复用判据**。静默复用一条不该复用的路有两个量级的后果：
 * 轻则白走（path[0] 是旧起点，同伴会被搬回去重走一遍），
 * 重则让"主人点头批准的清单"和"真正执行的清单"对不上。所以除了"该命中要命中"，
 * 更要逐条否定：过期、换目标、**换起点**、换同伴。
 */
class PlanCacheTest {

    private static final UUID NEKO = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID OTHER = UUID.fromString("00000000-0000-0000-0000-0000000000b2");
    private static final long T0 = 1_700_000_000_000L;

    private static final BlockPos ORIGIN = new BlockPos(0, 64, 0);
    private static final BlockPos TARGET = new BlockPos(10, 64, 20);

    private static List<DigAStar.Step> path(int nodes) {
        java.util.ArrayList<DigAStar.Step> out = new java.util.ArrayList<>();
        for (int i = 0; i < nodes; i++) {
            out.add(new DigAStar.Step(i, 64, 0, List.of(), List.of(), DigAStar.ACT_WALK));
        }
        return out;
    }

    private static void put(PlanCache c, UUID who, BlockPos origin, BlockPos target,
                            List<DigAStar.Step> p, long at) {
        c.put(who, origin, target, p, at);
    }

    /**
     * 比"是不是同一个 List"更强的断言：缓存存的是**快照**（另一个 List 实例），
     * 但里面每个 Step 都必须还是当初那些（Step 是不可变 record，浅拷即快照）。
     */
    private static void assertSamePath(List<DigAStar.Step> expected, List<DigAStar.Step> actual,
                                      String msg) {
        assertEquals(expected.size(), actual.size(), msg);
        for (int i = 0; i < expected.size(); i++) {
            assertSame(expected.get(i), actual.get(i), msg + "（第 " + i + " 个节点）");
        }
    }

    @Test
    void reusesTheSameRequest() {
        PlanCache cache = new PlanCache();
        List<DigAStar.Step> p = path(3);
        put(cache, NEKO, ORIGIN, TARGET, p, T0);

        PlanCache.Entry e = cache.take(NEKO, ORIGIN, TARGET, T0 + 1000);
        assertNotNull(e, "同一同伴/起点/目标且未过期，应当命中");
        assertSamePath(p, e.path, "复用的必须是同一条路（省的就是这次重搜）");
        assertEquals(PlanCache.Miss.NONE, cache.lastMiss());
    }

    @Test
    void ttlBoundaryIsInclusive() {
        PlanCache cache = new PlanCache();
        put(cache, NEKO, ORIGIN, TARGET, path(2), T0);

        assertNotNull(cache.take(NEKO, ORIGIN, TARGET, T0 + PlanCache.TTL_MS),
                "TTL 边界内仍算新鲜");
        assertEquals(1, cache.size(), "命中不消费槽位——同一趟确认流可能被重发两次");
        assertNull(cache.take(NEKO, ORIGIN, TARGET, T0 + PlanCache.TTL_MS + 1));
    }

    @Test
    void expiredPlanIsNotReusedAndItsSlotIsDropped() {
        PlanCache cache = new PlanCache();
        put(cache, NEKO, ORIGIN, TARGET, path(2), T0);
        assertEquals(1, cache.size());

        assertNull(cache.take(NEKO, ORIGIN, TARGET, T0 + PlanCache.TTL_MS + 1));
        assertEquals(PlanCache.Miss.EXPIRED, cache.lastMiss());
        assertEquals(0, cache.size(), "过期槽应被顺手清掉");
    }

    @Test
    void differentTargetIsNotReusedButSlotSurvives() {
        PlanCache cache = new PlanCache();
        put(cache, NEKO, ORIGIN, TARGET, path(2), T0);

        assertNull(cache.take(NEKO, ORIGIN, new BlockPos(11, 64, 20), T0));
        assertEquals(PlanCache.Miss.OTHER_TARGET, cache.lastMiss());
        // 换目标只是"这次不适用"，不该把原来那条路毁掉
        assertEquals(1, cache.size());
        assertNotNull(cache.take(NEKO, ORIGIN, TARGET, T0));
    }

    /**
     * 起点是判据里的关键一条：{@code path[0]} 就是起点，执行第一步会把同伴搬过去。
     * 同伴已经走开（例如上一趟已经抵达目标）却复用，会把它传送回旧起点重走一遍。
     */
    @Test
    void differentOriginIsNotReused() {
        PlanCache cache = new PlanCache();
        put(cache, NEKO, ORIGIN, TARGET, path(2), T0);

        assertNull(cache.take(NEKO, TARGET, TARGET, T0), "已经站在目标上了，绝不能复用以旧起点开头的老路");
        assertEquals(PlanCache.Miss.OTHER_ORIGIN, cache.lastMiss());
        assertNull(cache.take(NEKO, ORIGIN.above(), TARGET, T0), "下坠了一格也算起点变了");
        assertNotNull(cache.take(NEKO, ORIGIN, TARGET, T0));
    }

    /** 授权态**故意**不入判据：确认流本身就是 false → true，按授权态匹配会让优化永远不生效。 */
    @Test
    void authorizationIsNotPartOfTheKey() {
        PlanCache cache = new PlanCache();
        List<DigAStar.Step> p = path(2);
        put(cache, NEKO, ORIGIN, TARGET, p, T0);

        // 未授权那次算出的路，授权后重发时必须能命中（唯一该生效的场景）
        assertSamePath(p, cache.take(NEKO, ORIGIN, TARGET, T0 + 200).path,
                "授权态不同不该影响命中");
    }

    @Test
    void companionsDoNotShareSlots() {
        PlanCache cache = new PlanCache();
        put(cache, NEKO, ORIGIN, TARGET, path(2), T0);

        assertNull(cache.take(OTHER, ORIGIN, TARGET, T0));
        assertEquals(PlanCache.Miss.NO_SLOT, cache.lastMiss());
        assertNotNull(cache.take(NEKO, ORIGIN, TARGET, T0));
    }

    @Test
    void laterPlanOverwritesTheSlot() {
        PlanCache cache = new PlanCache();
        List<DigAStar.Step> first = path(2);
        List<DigAStar.Step> second = path(7);
        put(cache, NEKO, ORIGIN, TARGET, first, T0);
        put(cache, NEKO, new BlockPos(1, 64, 1), TARGET, second, T0 + 500);

        assertSamePath(second, cache.take(NEKO, new BlockPos(1, 64, 1), TARGET, T0 + 500).path,
                "每同伴一槽：新计划顶掉旧的");
        assertNull(cache.take(NEKO, ORIGIN, TARGET, T0 + 500));
    }

    @Test
    void dropRemovesTheSlot() {
        PlanCache cache = new PlanCache();
        put(cache, NEKO, ORIGIN, TARGET, path(2), T0);
        cache.drop(NEKO);

        assertEquals(0, cache.size());
        assertNull(cache.take(NEKO, ORIGIN, TARGET, T0));
        assertEquals(PlanCache.Miss.NO_SLOT, cache.lastMiss());
    }

    @Test
    void clockRollbackIsTreatedAsStale() {
        PlanCache cache = new PlanCache();
        put(cache, NEKO, ORIGIN, TARGET, path(2), T0);

        assertNull(cache.take(NEKO, ORIGIN, TARGET, T0 - 1),
                "时钟回拨时宁可重搜，也不按'没过期'复用");
        assertEquals(PlanCache.Miss.EXPIRED, cache.lastMiss());
    }

    /**
     * 缓存必须存**快照**：执行期 `PathTask` 会就地改写 `path`（把走过节点的待挖格递减，
     * `path.set(cursor, …)`）。若缓存持有同一个 List，上一条路执行到一半再复用就会拿到
     * "只剩后半段"的清单。这里直接模拟"存进去之后调用方就地改写原 List"。
     */
    @Test
    void cachedPathIsASnapshotNotAnAlias() {
        PlanCache cache = new PlanCache();
        List<DigAStar.Step> original = path(3);
        java.util.ArrayList<DigAStar.Step> live = new java.util.ArrayList<>(original);
        put(cache, NEKO, ORIGIN, TARGET, live, T0);

        // 模拟执行期把第一个节点换成"还剩 0 格待挖"的版本
        live.set(0, new DigAStar.Step(9, 9, 9, List.of(), List.of(), DigAStar.ACT_WALK));

        PlanCache.Entry e = cache.take(NEKO, ORIGIN, TARGET, T0);
        assertNotNull(e, "应当命中");
        assertEquals(3, e.path.size(), "节点数不该被调用方的就地改写影响");
        assertSame(original.get(0), e.path.get(0), "缓存里应当仍是当初那条路的节点");
    }

    @Test
    void nullArgumentsAreInert() {
        PlanCache cache = new PlanCache();
        put(cache, null, ORIGIN, TARGET, path(2), T0);
        put(cache, NEKO, null, TARGET, path(2), T0);
        put(cache, NEKO, ORIGIN, null, path(2), T0);
        cache.drop(null);

        assertEquals(0, cache.size());
        assertNull(cache.take(null, ORIGIN, TARGET, T0));
        assertFalse(cache.lastMiss() == PlanCache.Miss.NONE);
    }
}
