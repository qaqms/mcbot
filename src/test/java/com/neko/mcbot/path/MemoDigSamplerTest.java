package com.neko.mcbot.path;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MemoDigSampler（R1-S2）单测：等价性 + 验尸重启。零 MC 依赖（同 DigAStarTest 约束）。
 * 抽查键序用 HashMap 迭代序——对同插入序列确定性，本测试不依赖时钟/随机源。
 */
class MemoDigSamplerTest {

    @Test
    void memoAnswersIdenticalToDirectAndAbsorbsRepeatProbes() {
        DigAStarTest.GridSampler g = new DigAStarTest.GridSampler();
        for (int x = -2; x <= 30; x++) {
            for (int z = -4; z <= 4; z++) {
                g.addSolid(x, 0, z); // 地板
            }
        }
        for (int x = 10; x <= 12; x++) {
            for (int y = 1; y <= 4; y++) {
                g.addSolid(x, y, 0); // 石柱
            }
        }
        MemoDigSampler m = new MemoDigSampler(g);
        // ④ 等价 + 去重：三遍同构查询——第一遍填缓存，后两遍应几乎全命中
        for (int pass = 0; pass < 3; pass++) {
            for (int i = 0; i < 500; i++) {
                int x = (i * 7) % 40 - 10, y = (i * 3) % 6, z = (i * 5) % 9 - 4;
                assertEquals(g.passable(x, y, z), m.passable(x, y, z), "passable " + x + "," + y + "," + z);
                assertEquals(g.support(x, y, z), m.support(x, y, z), "support");
                assertEquals(g.digSeconds(x, y, z), m.digSeconds(x, y, z), 0.25, "dig（量化 250ms 粒度）");
                assertEquals(g.placeable(x, y, z), m.placeable(x, y, z), "placeable");
            }
        }
        // 后两遍 2000×4 全命中、首遍只按 distinct 格实查：比值必须拉得开（百万级世界读的削减来源）
        assertTrue(m.memoHits() > m.memoMisses() * 1.5,
                "重复查询必须被吸收 hits=" + m.memoHits() + " misses=" + m.memoMisses());
        System.out.println("[memo④] 3×500×4 查询 misses=" + m.memoMisses() + " hits=" + m.memoHits());
    }

    @Test
    void stalenessVerificationDetectsMidSearchWorldChange() {
        // ⑤ 验尸：先种 30 个 pass 格缓存，把世界翻成实心，逐轮 verifyOnce 应
        //    先积累 stale、过 STALE_MAX 后 worldChanged（图不可信，调用方须丢图重开）
        DigAStarTest.GridSampler g = new DigAStarTest.GridSampler();
        MemoDigSampler m = new MemoDigSampler(g);
        for (int i = 0; i < 30; i++) {
            m.passable(i, 1, 0); // 全空气→pass=true 入缓存
        }
        assertFalse(m.worldChanged());
        for (int i = 0; i < 30; i++) {
            g.addSolid(i, 1, 0); // 变天：全部实心→pass 应为 false
        }
        // 每轮验 8 格不符；第 4 轮的第 1 不符让 stale 到 25 > STALE_MAX(24)
        m.verifyOnce();
        m.verifyOnce();
        m.verifyOnce();
        assertEquals(24, m.staleCount(), "前三轮各 8 格全不符");
        assertFalse(m.worldChanged(), "stale=24 尚未越限");
        m.verifyOnce();
        assertTrue(m.worldChanged(), "第 25 次不符必须判图不可信");
        // 判坏后缓存里被验的格已清走；继续验不再计数（短路）
        int before = m.staleCount();
        m.verifyOnce();
        assertEquals(before, m.staleCount(), "worldChanged 后验尸短路");
    }

    @Test
    void memoCapFallsBackToDirectReadNotFailure() {
        // §B 护栏③：超帽停 memoize 退回直读——行为仍全对，只是不再缓存
        DigAStarTest.GridSampler g = new DigAStarTest.GridSampler();
        MemoDigSampler m = new MemoDigSampler(g);
        // 键必须真 distinct：x 轮回 120 个、z 单调增长 → 270000 个互异格 > 帽 262144
        long capped = DigAStar.MEMO_MAX_CELLS + 8;
        for (long i = 0; i < capped; i++) {
            int x = (int) (i % 120) - 60;
            int z = (int) (i / 120);
            m.passable(x, 1, z);
        }
        // 超帽后答案依旧正确（退回 base 直读）
        assertEquals(g.passable(0, 1, 0), m.passable(0, 1, 0), "降级后行为必须仍全对");
        assertTrue(m.memoMisses() > DigAStar.MEMO_MAX_CELLS,
                "misses 继续增长=不再缓存新格，属预期降级（hits=" + m.memoHits() + "）");
    }
}
