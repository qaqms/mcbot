package com.neko.mcbot.common;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 坐标/方位/准星格式。这些是**模型的读数口径**：格式漂一格，模型就会把 break_block
 * 打到隔壁去，所以逐字符钉。
 */
class ScanFormatTest {

    @Test
    void 绝对坐标与距离格式() {
        assertEquals("@(12,63,-4)", ScanFormat.abs(12, 63, -4));
        assertEquals("@(-5,0,-1024)", ScanFormat.abs(-5, 0, -1024));
        assertEquals("d3.2", ScanFormat.dist(3.24));
        assertEquals("d0.0", ScanFormat.dist(0.04));
        assertEquals("d100.0", ScanFormat.dist(100.0));
        assertEquals("@(12,63,-4) d3.2", ScanFormat.pos(12, 63, -4, 3.2));
    }

    @Test
    void 八向折算覆盖四正四隅() {
        // MC 世界轴：-Z=north, +Z=south, +X=east, -X=west
        assertEquals("south", ScanFormat.facing(0, 1));
        assertEquals("east", ScanFormat.facing(1, 0));
        assertEquals("north", ScanFormat.facing(0, -1));
        assertEquals("west", ScanFormat.facing(-1, 0));
        assertEquals("south_east", ScanFormat.facing(1, 1));
        assertEquals("north_east", ScanFormat.facing(1, -1));
        assertEquals("north_west", ScanFormat.facing(-1, -1));
        assertEquals("south_west", ScanFormat.facing(-1, 1));
    }

    @Test
    void 扇区边界归到较近的方位且不越索引() {
        // 罗盘角正好落在分界上也不能跳出八向词表
        assertEquals("north", ScanFormat.facing(0, -1e9));
        assertEquals("north_east", ScanFormat.facing(Math.sin(Math.toRadians(45)), -Math.cos(Math.toRadians(45))));
        // 360° 环绕：与 0° 同一个词
        assertEquals("north", ScanFormat.facing(0, -1));
        // 任意方向都必须是八向词表之一
        for (int deg = 0; deg < 360; deg++) {
            String f = ScanFormat.facing(Math.sin(Math.toRadians(deg)), Math.cos(Math.toRadians(deg)));
            assertTrue(ScanFormat.EIGHT.contains(f), deg + "° -> " + f);
        }
    }

    @Test
    void 首行朝向文案() {
        assertEquals("我在 (12,63,-4) 面朝 east", ScanFormat.hereLine(12, 63, -4, 1, 0));
        assertEquals("我在 (0,64,0) 面朝 north", ScanFormat.hereLine(0, 64, 0, 0, -1));
    }

    @Test
    void 准星注入格式与字节帽() {
        String s = ScanFormat.crosshair("cobblestone", 12, 63, -4, 3.2);
        assertEquals("[我此刻盯着] cobblestone @(12,63,-4) 距3.2", s);
        assertTrue(WireSize.utf8Bytes(s) <= ScanFormat.CROSSHAIR_MAX_BYTES);

        // 超长路径（模组方块名可能很长）必须裁到帽内，且仍是合法 UTF-8/不切坏字符
        String fat = ScanFormat.crosshair("minecraft:a_very_long_modded_block_name_that_keeps_going_and_going_x",
                -1234567, 64, 1234567, 123.45);
        assertTrue(WireSize.utf8Bytes(fat) <= ScanFormat.CROSSHAIR_MAX_BYTES,
                "got " + WireSize.utf8Bytes(fat) + "B: " + fat);
        assertTrue(fat.startsWith("[我此刻盯着] "));
    }
}
