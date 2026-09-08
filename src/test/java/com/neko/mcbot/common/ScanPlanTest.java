package com.neko.mcbot.common;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 采样计划：层带划分与 900 格预算。这里钉的是"扫描会不会把主线程吃光"的上界，
 * 以及默认半径下**远环必须真的被看到**（不然后果是"模型以为远处没矿"这种静默误导）。
 */
class ScanPlanTest {

    @Test
    void 层带按水平切比雪夫距离归组() {
        assertEquals(ScanPlan.BAND_NEAR, ScanPlan.bandOf(0, 0, 16));
        assertEquals(ScanPlan.BAND_NEAR, ScanPlan.bandOf(6, -6, 16));
        assertEquals(ScanPlan.BAND_MID, ScanPlan.bandOf(7, 0, 16));
        assertEquals(ScanPlan.BAND_MID, ScanPlan.bandOf(-12, 5, 16));
        assertEquals(ScanPlan.BAND_FAR, ScanPlan.bandOf(13, -13, 16));
        assertEquals(ScanPlan.BAND_FAR, ScanPlan.bandOf(16, 0, 16));
        assertEquals(ScanPlan.BAND_NONE, ScanPlan.bandOf(17, 0, 16));
    }

    @Test
    void 半径小于层带时只保留存在的层() {
        assertEquals(ScanPlan.BAND_NEAR, ScanPlan.bandOf(3, 3, 4));
        assertEquals(ScanPlan.BAND_NONE, ScanPlan.bandOf(5, 0, 4));
        assertEquals(ScanPlan.BAND_NONE, ScanPlan.bandOf(6, 0, 3), "r=3 时 6 在半径外");
    }

    @Test
    void 默认半径的预算够看完三层() {
        ScanPlan.Plan p = ScanPlan.build(16);
        assertFalse(p.truncated(), "r=16 不该撞 MAX_SAMPLES，否则远环永远看不到");
        assertTrue(p.cells().size() <= ScanPlan.MAX_SAMPLES);
        assertTrue(p.cells().size() > 300 && p.cells().size() < ScanPlan.MAX_SAMPLES,
                "实际 " + p.cells().size() + " 格");
        // 三层都在
        assertTrue(p.cells().stream().anyMatch(c -> c.band() == ScanPlan.BAND_NEAR));
        assertTrue(p.cells().stream().anyMatch(c -> c.band() == ScanPlan.BAND_MID));
        assertTrue(p.cells().stream().anyMatch(c -> c.band() == ScanPlan.BAND_FAR));
    }

    @Test
    void 计划绝不超帽且近环优先() {
        for (int r : new int[]{1, 2, 5, 6, 7, 12, 13, 16, 24, 32}) {
            ScanPlan.Plan p = ScanPlan.build(r);
            assertTrue(p.cells().size() <= ScanPlan.MAX_SAMPLES, "r=" + r + " 超帽");
            assertFalse(p.cells().isEmpty(), "r=" + r + " 空计划");
            // 近→中→远：序列里的 band 号必须单调不减（截断只会砍掉尾部=最远的）
            int prev = -1;
            for (ScanPlan.Cell c : p.cells()) {
                assertTrue(c.band() >= prev, "r=" + r + " 层带顺序倒转");
                prev = c.band();
            }
            // 中心格必被采到（同伴自己站的那格），且只有近环含 (0,0,0)
            assertTrue(p.cells().stream().anyMatch(
                    c -> c.dx() == 0 && c.dy() == 0 && c.dz() == 0));
        }
    }

    @Test
    void 每格只属于一层不重复() {
        ScanPlan.Plan p = ScanPlan.build(16);
        long uniq = p.cells().stream()
                .map(c -> c.dx() + ":" + c.dy() + ":" + c.dz()).distinct().count();
        assertEquals(p.cells().size(), uniq);
    }

    @Test
    void 网格以同伴为对称中心() {
        // 半径不是步长整数倍时（r=16, 远环步长 3）不许一侧多一列、另一侧少一列
        ScanPlan.Plan p = ScanPlan.build(16);
        boolean has15 = p.cells().stream().anyMatch(c -> Math.abs(c.dx()) == 15);
        assertFalse(p.cells().stream().anyMatch(c -> Math.abs(c.dx()) == 16),
                "越出半径");
        assertTrue(has15, "远环应铺到 15（步长 3 的对齐网格）");
        long plus = p.cells().stream().filter(c -> c.dx() > 0).count();
        long minus = p.cells().stream().filter(c -> c.dx() < 0).count();
        assertEquals(plus, minus);
    }

    @Test
    void 步长口径与设计卡一致() {
        assertEquals(6, ScanPlan.NEAR_RADIUS);
        assertEquals(1, ScanPlan.NEAR_STEP);
        assertEquals(2, ScanPlan.MID_STEP);
        assertEquals(3, ScanPlan.FAR_STEP);
        assertEquals(900, ScanPlan.MAX_SAMPLES);
    }
}
