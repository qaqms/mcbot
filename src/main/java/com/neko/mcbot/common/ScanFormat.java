package com.neko.mcbot.common;

import java.util.Locale;

/**
 * 扫描回执的坐标/方位/准星格式化（R2-D："坐标一律绝对"）。零 MC 依赖，纯字符串算术。
 *
 * <p>为什么绝对坐标是硬要求：回执里给 {@code @相对(dx,dy,dz)} 时，模型每下一步都要
 * 自己做"我的位置 + 位移"的加法，而它一边要规划一边要算加法就会算错（旧回执甚至专门
 * 补了一句"加上我的位置即为目标坐标"来教它算）。把加法挪到服务端，模型只做匹配。
 */
public final class ScanFormat {

    private ScanFormat() {
    }

    /** 准星注入的字节帽（设计卡 §D 新常数）：超了就裁，宁缺不肥——它每轮都进 prompt。 */
    public static final int CROSSHAIR_MAX_BYTES = 120;

    /** 八向词表（与注册表路径同为英文，模型对这套方位词的复用率最高）。 */
    private static final String[] COMPASS = {
            "north", "north_east", "east", "south_east",
            "south", "south_west", "west", "north_west"};

    /** 同一个词表的只读视图（单测/调用方校验"必须是八向之一"）。 */
    public static final java.util.List<String> EIGHT = java.util.List.of(COMPASS);

    /** {@code @(12,63,-4)}。 */
    public static String abs(int x, int y, int z) {
        return "@(" + x + "," + y + "," + z + ")";
    }

    /** {@code d3.2}（一位小数足够指路，两小数白烧 token）。 */
    public static String dist(double d) {
        return "d" + String.format(Locale.ROOT, "%.1f", d);
    }

    /** {@code @(12,63,-4) d3.2}。 */
    public static String pos(int x, int y, int z, double d) {
        return abs(x, y, z) + " " + dist(d);
    }

    /**
     * 视线水平投影折算成八向之一。
     *
     * <p>MC 世界轴：{@code -Z=north, +Z=south, +X=east, -X=west}。先取
     * {@code atan2(lx, lz)}（从 +Z 起、向 +X 为正），再折成罗盘角（north=0、east=90）。
     * 正对上下（pitch=±90）时 lx/lz 在 double 下是 ~1e-16 而不是精确 0，符号仍由 yaw 决定，
     * 所以不需要额外的退化分支。
     */
    public static String facing(double lookX, double lookZ) {
        double compass = 180.0 - Math.toDegrees(Math.atan2(lookX, lookZ));
        int idx = (int) Math.floor((compass + 22.5) / 45.0) % 8;
        return COMPASS[idx];
    }

    /** 回执首行：{@code 我在 (12,63,-4) 面朝 east}。 */
    public static String hereLine(int x, int y, int z, double lookX, double lookZ) {
        return "我在 (" + x + "," + y + "," + z + ") 面朝 " + facing(lookX, lookZ);
    }

    /**
     * 客户端准星注入：{@code [我此刻盯着] cobblestone @(12,63,-4) 距3.2}。
     *
     * <p>字节帽用 {@link WireSize#truncateToBytes} 而不是 substring：中文按字符裁会
     * 一路裁到 360B（每字符 3 字节），且可能切坏代理对。
     */
    public static String crosshair(String regPath, int x, int y, int z, double d) {
        String s = "[我此刻盯着] " + regPath + " " + abs(x, y, z) + " 距"
                + String.format(Locale.ROOT, "%.1f", d);
        return WireSize.truncateToBytes(s, CROSSHAIR_MAX_BYTES);
    }
}
