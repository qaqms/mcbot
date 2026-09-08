package com.neko.mcbot.common;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * classify 词表的边界。这里钉的是**产品口径**（什么算 rock、优先级谁在前），
 * 不是 MC 行为——MC 侧只贡献"路径/是否容器/是否作物"三个事实。
 */
class ScanClassifyTest {

    @Test
    void 石材集按精确路径命中且不含泥土沙() {
        assertEquals(ScanCategory.ROCK, ScanClassify.classify("stone", false, false));
        assertEquals(ScanCategory.ROCK, ScanClassify.classify("cobblestone", false, false));
        assertEquals(ScanCategory.ROCK, ScanClassify.classify("deepslate", false, false));
        assertEquals(ScanCategory.ROCK, ScanClassify.classify("granite", false, false));
        assertEquals(ScanCategory.ROCK, ScanClassify.classify("tuff", false, false));

        // 泥土/沙是材料不是目标：进 rock 会让模型"见土就挖"
        assertNull(ScanClassify.classify("dirt", false, false));
        assertNull(ScanClassify.classify("grass_block", false, false));
        assertNull(ScanClassify.classify("sand", false, false));
        assertNull(ScanClassify.classify("gravel", false, false));
        // 加工产物不算野外石材（精确匹配，不是前缀匹配）
        assertNull(ScanClassify.classify("stone_bricks", false, false));
        assertNull(ScanClassify.classify("smooth_stone", false, false));
        assertTrue(ScanClassify.ROCK_PATHS.stream().noneMatch(p -> p.contains("dirt")
                || p.contains("sand")), "rock 集不许混进土沙");
    }

    @Test
    void 矿石走后缀判定不再误吞含ore字样的方块() {
        assertEquals(ScanCategory.ORE, ScanClassify.classify("coal_ore", false, false));
        assertEquals(ScanCategory.ORE, ScanClassify.classify("deepslate_iron_ore", false, false));
        assertEquals(ScanCategory.ORE, ScanClassify.classify("nether_gold_ore", false, false));
        // 旧口径 path.contains("ore") 会误报的名字，现在必须不当矿
        assertFalse(ScanClassify.isOrePath("oresome_odd_name"));
        assertFalse(ScanClassify.isOrePath("ore"));
        assertNull(ScanClassify.classify("oresome_odd_name", false, false));
        assertNull(ScanClassify.classify("ore", false, false));
    }

    @Test
    void 容器优先于其它类别() {
        // 容器集必须抢在矿/石材之前："装着矿的箱子"先算 container（可交互优先于可挖掘）
        assertEquals(ScanCategory.CONTAINER, ScanClassify.classify("chest", true, false));
        assertEquals(ScanCategory.CONTAINER, ScanClassify.classify("coal_ore", true, false));
        assertEquals(ScanCategory.CONTAINER, ScanClassify.classify("barrel", true, false));
    }

    @Test
    void 工作台与作物各自命中且优先级正确() {
        assertEquals(ScanCategory.WORKBENCH, ScanClassify.classify("crafting_table", false, false));
        assertEquals(ScanCategory.WORKBENCH, ScanClassify.classify("furnace", false, false));
        // 熔炉自身有 BlockEntity，但调用方给的 container 必须是"实现了 Container"，
        // 所以作物/工作台判定仍能得到；这里演算一遍顺序不回归。
        assertEquals(ScanCategory.FARM, ScanClassify.classify("wheat", false, true));
        assertEquals(ScanCategory.FARM, ScanClassify.classify("carrots", false, true));
        // 同一格既是作物又（错误地）被判为容器时：容器赢（可交互优先）
        assertEquals(ScanCategory.CONTAINER, ScanClassify.classify("wheat", true, true));
    }

    @Test
    void 空路径与普通方块归null() {
        assertNull(ScanClassify.classify(null, false, false));
        assertNull(ScanClassify.classify("", false, false));
        assertNull(ScanClassify.classify("oak_log", false, false));
        assertNull(ScanClassify.classify("water", false, false));
    }

    @Test
    void 词表token与设计卡一致() {
        assertEquals("container", ScanCategory.CONTAINER.key());
        assertEquals("ore", ScanCategory.ORE.key());
        assertEquals("rock", ScanCategory.ROCK.key());
        assertEquals("workbench", ScanCategory.WORKBENCH.key());
        assertEquals("farm", ScanCategory.FARM.key());
        assertEquals("hostile", ScanCategory.HOSTILE.key());
    }
}
