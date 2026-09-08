package com.neko.mcbot.common;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 分层摘要装配：归组、名额分配、体量上界。
 * 体量这一条是 R2-D 的核心账——回执从"看不见能干什么"变成"2KB 内给出可下指令的格子"，
 * 但如果哪天真撑到 WireSize 的闸，那就是把感知换成了断线，所以这里当场钉死。
 */
class ScanSummaryTest {

    private static ScanSummary around(int r) {
        return new ScanSummary(0, 64, 0, r);
    }

    @Test
    void 观测按层带归组() {
        ScanSummary s = around(16);
        s.add(ScanCategory.ROCK, "stone", 3, 64, 0);      // 近环
        s.add(ScanCategory.ROCK, "stone", 9, 64, 0);      // 中环
        s.add(ScanCategory.ROCK, "stone", 15, 64, 0);     // 远环
        List<String> lines = s.lines();
        assertEquals(3, lines.size());
        assertTrue(lines.get(0).startsWith("近圈"));
        assertTrue(lines.get(1).startsWith("中圈"));
        assertTrue(lines.get(2).startsWith("远圈"));
        // 远环只计数：不许出现坐标（否则模型会拿一个 15 格外的格子直接下 break_block）
        assertFalse(lines.get(2).contains("@("), lines.get(2));
        assertTrue(lines.get(1).contains("@("), "中环必须给最近一格绝对坐标");
        assertTrue(lines.get(0).contains("@("));
    }

    @Test
    void 半径外的观测不进摘要() {
        ScanSummary s = around(4);
        s.add(ScanCategory.ROCK, "stone", 9, 64, 0);
        assertTrue(s.lines().isEmpty());
        assertEquals(0, s.total());
    }

    @Test
    void 近环名额按路径轮转不被单一石材刷掉() {
        // 石头地上站着扫：46 格 stone + 3 格箱子 + 3 格铜矿
        ScanSummary s = around(6);
        for (int i = 0; i < 46; i++) {
            s.add(ScanCategory.ROCK, "stone", i % 6, 63, i / 6);
        }
        for (int i = 0; i < 3; i++) {
            s.add(ScanCategory.CONTAINER, "chest", i, 64, 2);
            s.add(ScanCategory.ORE, "copper_ore", i, 63, 4);
        }

        String near = s.lines().get(0);
        assertTrue(near.contains("[container] chest"), near);
        assertTrue(near.contains("[ore] copper_ore"), near);
        assertTrue(near.contains("[rock] stone x42"), near);
        // x42 而不是 x46：i>=42 的 4 格 dz=7 在 r=6 外，被层带守卫丢弃（环带外的观测不许进回执）
        // 名额发完：细列恰好 8 格坐标，且 stone 不得吃掉全部名额
        assertEquals(8, count(near, "@("), near);
    }

    @Test
    void 三种名额与组数上限都被守住() {
        ScanSummary s = around(32);
        // 每层灌 30 种不同路径（名字等长，不影响排序）
        for (int i = 0; i < 30; i++) {
            String n = String.format(Locale.ROOT, "kind_%02d", i);
            s.add(ScanCategory.ORE, "near_" + n, i % 6, 63, 0);              // 近环
            s.add(ScanCategory.ORE, "mid_" + n, 7 + i % 6, 64, 0);            // 中环
            s.add(ScanCategory.ORE, "far_" + n, 13 + i % 10, 64, 0);          // 远环
        }
        List<String> lines = s.lines();
        assertEquals(3, lines.size());
        assertEquals(ScanSummary.NEAR_MAX_GROUPS, groups(lines.get(0)), lines.get(0));
        assertEquals(ScanSummary.MID_MAX_GROUPS, groups(lines.get(1)), lines.get(1));
        assertEquals(ScanSummary.FAR_MAX_GROUPS, groups(lines.get(2)), lines.get(2));
        assertTrue(count(lines.get(0), "@(") <= ScanSummary.NEAR_MAX_CELLS,
                count(lines.get(0), "@(") + "\n" + lines.get(0));
        assertEquals(ScanSummary.NEAR_MAX_GROUPS, count(lines.get(0), "@("),
                "每组至少一格坐标——这就是'不被刷掉'的保证");
    }

    @Test
    void 体量目标约2KB并远低于线路闸() {
        // 最坏情况：三层都灌满、每层都是长名字，再加 20 条实体行（工具侧封顶）
        ScanSummary s = around(32);
        for (int i = 0; i < 40; i++) {
            s.add(ScanCategory.ORE, "deepslate_diamond_ore_" + i, i % 6, 63, 1 + i % 2);   // 近环
            s.add(ScanCategory.CONTAINER, "chest_of_loot_" + i, 8 + i % 5, 64, i % 4);     // 中环
            s.add(ScanCategory.ROCK, "cobblestone_variant_" + i, 14 + i % 8, 64, 0);       // 远环
        }
        List<String> lines = s.lines();
        StringBuilder fb = new StringBuilder(ScanFormat.hereLine(0, 64, 0, 1, 0)).append('\n');
        for (String l : lines) {
            fb.append(l).append('\n');
        }
        for (int i = 0; i < 20; i++) {
            fb.append("zombie [敌对] @(-19,64,20) d27.6 hp=20\n");
        }
        int bytes = WireSize.utf8Bytes(fb.toString());
        assertTrue(bytes < 2_600, "摘要体量超预期: " + bytes + "B\n" + fb);
        assertTrue(bytes > WireSize.utf8Bytes(ScanSummary.join(s.lines())), "装配不完整");
        assertTrue(WireSize.fits(fb.toString()), "回执必须远在线路闸内: " + bytes);
    }

    @Test
    void 同类别多格累加计数与最近坐标() {
        ScanSummary s = around(16);
        s.add(ScanCategory.ROCK, "cobblestone", 5, 64, 0);
        s.add(ScanCategory.ROCK, "cobblestone", 1, 64, 0);
        s.add(ScanCategory.ROCK, "cobblestone", -2, 64, 0);
        String near = s.lines().get(0);
        assertTrue(near.contains("cobblestone x3"), near);
        // 最近的那格必须排在第一个
        assertTrue(near.indexOf("@(1,64,0)") < near.indexOf("@(-2,64,0)"), near);
        assertTrue(near.contains("d1.0"), near);
    }

    @Test
    void 备注每组只挂一次且敌对生物也走同一词表() {
        ScanSummary s = around(16);
        s.add(ScanCategory.FARM, "wheat", 2, 64, 0, "成熟度7");
        s.add(ScanCategory.FARM, "wheat", 3, 64, 0, "成熟度7");
        s.add(ScanCategory.HOSTILE, "zombie", 4, 64, 4, null);
        String near = s.lines().get(0);
        assertEquals(1, count(near, "成熟度7"), near);   // 每组一次，不是每格一次
        assertTrue(near.contains("[hostile] zombie x1"), near);
        assertTrue(near.contains("[farm] wheat x2"), near);
    }

    @Test
    void 空摘要不印空行() {
        ScanSummary s = around(16);
        assertTrue(s.lines().isEmpty());
        assertEquals(0, s.total());
        assertTrue(s.groupTokens().isEmpty());
    }

    private static int count(String s, String needle) {
        int n = 0;
        for (int i = s.indexOf(needle); i >= 0; i = s.indexOf(needle, i + 1)) {
            n++;
        }
        return n;
    }

    /** 数一行里有几个组（以 "] " 结尾的类别标记为界）。 */
    private static int groups(String line) {
        return count(line, "[");
    }
}
