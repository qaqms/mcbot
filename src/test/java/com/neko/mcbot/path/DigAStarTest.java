package com.neko.mcbot.path;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DigAStar 纯算法单测：用集合表示地形的假采样器驱动，
 * 钉死"节点定义、挖/放代价、神圣否决、预算帽、分帧推进"的语义。
 * 不碰任何 MC 类——这就是把搜索核从 LevelDigSampler 剥离的意义。
 */
class DigAStarTest {

    /** 假地形：solid=挡腿格；unbreak=不可挖；其余皆空气。y>=0 且 |x|,|z|<=64 界内。 */
    static final class GridSampler implements DigSampler {
        final Set<Long> solid = new HashSet<>();
        final Set<Long> unbreak = new HashSet<>();
        int placeStock = 16;
        int digsTried;

        void addSolid(int x, int y, int z) {
            solid.add(DigAStar.pack(x, y, z));
        }

        @Override
        public boolean passable(int x, int y, int z) {
            return inBounds(x, y, z) && !solid.contains(DigAStar.pack(x, y, z));
        }

        @Override
        public double digSeconds(int x, int y, int z) {
            long k = DigAStar.pack(x, y, z);
            if (!solid.contains(k)) {
                return 0;
            }
            digsTried++;
            return unbreak.contains(k) ? INFEASIBLE : 1.0;
        }

        @Override
        public boolean support(int x, int y, int z) {
            return solid.contains(DigAStar.pack(x, y, z));
        }

        @Override
        public boolean placeable(int x, int y, int z) {
            return passable(x, y, z) && placeStock > 0;
        }

        @Override
        public double placeCost(int x, int y, int z) {
            return 1.0;
        }

        @Override
        public int maxPlaces() {
            return placeStock;
        }

        @Override
        public boolean inBounds(int x, int y, int z) {
            return y >= 0 && y <= 60 && Math.abs(x) <= 64 && Math.abs(z) <= 64;
        }
    }

    /** 平地板（y=0 全实心假设：support 查 y=0 的 solid 集合）。 */
    private static void floor(GridSampler g, int fromX, int toX, int z) {
        for (int x = fromX; x <= toX; x++) {
            g.addSolid(x, 0, z);
        }
    }

    private static DigAStar run(GridSampler g, int sx, int sy, int sz, int tx, int ty, int tz) {
        DigAStar a = new DigAStar(g, sx, sy, sz, tx, ty, tz, 8000, 128);
        int guard = 0;
        while (!a.advance(300) && guard++ < 100) {
            // 分帧推进直至出结果
        }
        return a;
    }

    @Test
    void straightFlatWalk() {
        GridSampler g = new GridSampler();
        floor(g, -2, 10, 0);
        g.placeStock = 0;
        DigAStar a = run(g, 0, 1, 0, 8, 1, 0);
        assertNull(a.failure());
        assertEquals(0, a.dugCount());
        assertEquals(0, a.placedCount());
        var path = a.path();
        var first = path.get(0);
        assertEquals(DigAStar.ACT_START, first.action(), "首节点应是起点");
        assertEquals(0, first.x());
        assertEquals(1, first.y());
        var last = path.get(path.size() - 1);
        assertTrue(Math.abs(last.x() - 8) <= 1 && Math.abs(last.z()) <= 1, "落点在目标柱内");
        for (var st : path.subList(1, path.size())) {
            assertEquals(DigAStar.ACT_WALK, st.action(), "平路全是 WALK");
        }
    }

    @Test
    void digsThroughWall() {
        GridSampler g = new GridSampler();
        floor(g, -2, 10, 0);
        // x=4 处一堵 3 高石墙（y=1..3 全实心，需要挖穿脚+头两格或绕顶）
        for (int y = 1; y <= 3; y++) {
            g.addSolid(4, y, 0);
        }
        g.placeStock = 0;
        DigAStar a = run(g, 0, 1, 0, 8, 1, 0);
        assertNull(a.failure());
        assertTrue(a.dugCount() >= 1, "墙挡路必须有挖格计划");
        // 挖格必须都在 x=4 面上（不乱挖别处）
        for (var st : a.path()) {
            for (long cell : st.dig()) {
                assertEquals(4, DigAStar.unpackX(cell));
            }
        }
    }

    @Test
    void unbreakableSealFailsNoPath() {
        GridSampler g = new GridSampler();
        floor(g, -2, 10, 0);
        // 基岩笼：x=3 整面墙不可挖，且绕不过（z 方向墙无限长由界内限制兜底）
        for (int z = -20; z <= 20; z++) {
            for (int y = 0; y <= 20; y++) {
                long k = DigAStar.pack(3, y, z);
                g.solid.add(k);
                g.unbreak.add(k);
            }
        }
        g.placeStock = 0;
        DigAStar a = run(g, 0, 1, 0, 8, 1, 0);
        assertNotNull(a.failure(), "不可破封锁必须 NO_PATH 而不是硬穿");
        assertTrue(a.failure().startsWith("NO_PATH"));
    }

    @Test
    void pillarClimbNeedsPlace() {
        GridSampler g = new GridSampler();
        // 起点 (0,1,0) 站地板(0,0,0)上；目标 (0,3,0)：要登高一柱，靠"挖柱身格+垫支撑"组合
        floor(g, 0, 0, 0);
        g.addSolid(0, 2, 0); // 悬空实心：站进它需挖开它且脚下补垫
        g.placeStock = 4;
        DigAStar a = run(g, 0, 1, 0, 0, 3, 0);
        assertNull(a.failure());
        var last = a.path().get(a.path().size() - 1);
        assertTrue(last.y() >= 2 && last.y() <= 3, "目标柱 ±1 内即到（高度 2 就算贴到柱子）");
        assertTrue(a.dugCount() + a.placedCount() >= 1, "登柱必含挖或放"  );
    }

    @Test
    void digDownStaircasePreferredOverFreeFallIntoVoid() {
        GridSampler g = new GridSampler();
        floor(g, -2, 10, 0);
        // y=1 起点，目标同层，但中段地板被挖空成深渊（x=3..5 y=0 无实心、y=1/2 空气）
        for (int x = 3; x <= 5; x++) {
            g.solid.remove(DigAStar.pack(x, 0, 0));
        }
        g.placeStock = 8;
        DigAStar a = run(g, 0, 1, 0, 8, 1, 0);
        assertNull(a.failure(), "深渊段在 64 界内只有一维，可用桥搭过去");
        // 有桥就必有放格计划
        assertTrue(a.placedCount() >= 1, "跨深渊需要搭桥放格");
    }

    @Test
    void frameSlicingEventuallyFinishes() {
        GridSampler g = new GridSampler();
        floor(g, -2, 40, 0);
        g.placeStock = 0;
        DigAStar a = new DigAStar(g, 0, 1, 0, 30, 1, 0, 8000, 128);
        int ticks = 0;
        while (!a.advance(1) && ticks++ < 1000) {
            // 每次只展 1 节点：验证分帧推进最终有界
        }
        assertTrue(a.done());
        assertNull(a.failure());
        assertTrue(ticks > 1, "逐节点推进必然跨多帧（证明真在分帧）");
    }

    @Test
    void packUnpackRoundTripIncludingNegatives() {
        int[][] cases = {{0, -64, 0}, {-1, 1, -1}, {30_000_000, 319, -30_000_000},
                {-30_000_000, -64, 30_000_000}, {7, 1023, -13}};
        for (int[] c : cases) {
            long k = DigAStar.pack(c[0], c[1], c[2]);
            assertEquals(c[0], DigAStar.unpackX(k), "x " + java.util.Arrays.toString(c));
            assertEquals(c[1], DigAStar.unpackY(k), "y " + java.util.Arrays.toString(c));
            assertEquals(c[2], DigAStar.unpackZ(k), "z " + java.util.Arrays.toString(c));
        }
    }

    @Test
    void startOnLonePillarFailsCleanly() {
        // 起点站在单柱上、四周无接应：应干净地 NO_PATH（而不是拆地板或穿空）
        GridSampler g = new GridSampler();
        g.addSolid(0, 0, 0); // 唯一起点支撑
        g.placeStock = 0;
        DigAStar a = new DigAStar(g, 0, 1, 0, 8, 1, 0, 8000, 128);
        int guard = 0;
        while (!a.advance(300) && guard++ < 100) {
        }
        assertNotNull(a.failure(), "四周皆空无支撑应失败（而不是拆地板）");
        List<com.neko.mcbot.path.DigAStar.Step> p = a.path();
        assertTrue(p == null || p.stream().allMatch(s -> s.y() >= 1), "路径不钻地板以下");
    }
}
