package com.neko.mcbot.common;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * scan_area 的**采样计划**（R2-D 分层摘要）：给定半径，算出这一拍该看哪些格子（相对同伴
 * 的位移），以及每格属于哪一层环带。零 MC 依赖——扫描"看哪些格子"是纯算术，
 * "格子里是什么"才是 MC 的活；拆开才能把 900 上限与层带归组写成单测。
 *
 * <p><b>为什么要分层步长：</b>近处要能直接下指令（所以步长 1、逐格列），
 * 远处只回答"那个方向有没有矿/有多少"（步长 2/3）。不加层次地按步长 1 扫 r=16
 * 是 33³≈35k 格，主线程必炸；按现在的分配 r=16 只要 787 格。
 *
 * <p>层带用**水平切比雪夫距离**（{@code max(|dx|,|dz|)}）而不是欧氏距离：
 * 环带边界要和回执里"半径 r"这个说法对齐（r 就是正方形的半边长），
 * 且竖直方向单独给窗口（{@link #NEAR_Y}/{@link #MID_Y}/{@link #FAR_Y}），
 * 因为"我脚周围有什么"比"头顶 16 格有什么"可行动得多。
 *
 * <p><b>已知口径（不是 bug）：</b>中环/远环的网格按步长对齐到中心，所以半径刚跨过
 * 层带边界时（r=7、r=13..14）对齐网格还碰不到那一层，该层就是空的（r=16 时三层齐备，
 * 共 787 格）。宁缺勿假：不给模型一个"看过了但什么都没有"的错觉，回执里的
 * 只印存在的层，空层直接不出行。
 */
public final class ScanPlan {

    private ScanPlan() {
    }

    /** 近环：切比雪夫 ≤6，步长 1，竖直 -1..+1（脚/身/头）。 */
    public static final int NEAR_RADIUS = 6;
    public static final int NEAR_STEP = 1;
    public static final int[] NEAR_Y = {-1, 0, 1};

    /** 中环：7..12，步长 2，竖直只取 -4/0 两层（矿脉常见高度差量级）。 */
    public static final int MID_RADIUS = 12;
    public static final int MID_STEP = 2;
    public static final int[] MID_Y = {-4, 0};

    /** 远环：13..r，步长 3，竖直只看同伴所在层（远环只给计数，不给坐标）。 */
    public static final int FAR_STEP = 3;
    public static final int[] FAR_Y = {0};

    /** 单拍允许读多少格（设计卡 §D 新常数）。撞帽就截断并在回执里承认截断。 */
    public static final int MAX_SAMPLES = 900;

    /** 层带序号。 */
    public static final int BAND_NEAR = 0;
    public static final int BAND_MID = 1;
    public static final int BAND_FAR = 2;
    public static final int BAND_NONE = -1;

    /** 一格采样点：相对同伴脚格的位移 + 所属环带。 */
    public record Cell(int dx, int dy, int dz, int band) {
    }

    /** 计划结果；{@code truncated}=true 表示 MAX_SAMPLES 先耗尽、远环有格子没看到。 */
    public record Plan(List<Cell> cells, boolean truncated) {
    }

    /** 水平切比雪夫距离。 */
    public static int bandDistance(int dx, int dz) {
        return Math.max(Math.abs(dx), Math.abs(dz));
    }

    /** 这一格属于哪一层（BAND_NONE = 在半径外，或落在更近的层里由更细的步长负责）。 */
    public static int bandOf(int dx, int dz, int radius) {
        int d = bandDistance(dx, dz);
        if (d > radius) {
            return BAND_NONE;
        }
        if (d <= Math.min(radius, NEAR_RADIUS)) {
            return BAND_NEAR;
        }
        if (d <= Math.min(radius, MID_RADIUS)) {
            return BAND_MID;
        }
        return BAND_FAR;
    }

    /**
     * 生成采样计划。层带**由近到远**排，所以撞 MAX_SAMPLES 时先牺牲远环，
     * 回执随后带一句"远环未看全"——反过来（远环优先）会让模型以为近处什么都没有。
     */
    public static Plan build(int radius) {
        int r = Math.max(1, radius);
        List<Cell> cells = new ArrayList<>();
        boolean truncated = false;
        int[][] bands = {{NEAR_RADIUS, NEAR_STEP, BAND_NEAR},
                {MID_RADIUS, MID_STEP, BAND_MID},
                {r, FAR_STEP, BAND_FAR}};
        int[][] yWindows = {NEAR_Y, MID_Y, FAR_Y};
        for (int b = 0; b < bands.length; b++) {
            int hi = Math.min(r, bands[b][0]);
            int step = bands[b][1];
            int band = bands[b][2];
            if (hi < 1) {
                continue;
            }
            // 网格以同伴为原点对齐（不是从 -hi 起算）：否则半径不是步长整数倍时
            // 一侧会多出一列、另一侧少一列，摘要里的"周围"会偏心。
            int edge = hi - hi % step;
            for (int dx = -edge; dx <= edge; dx += step) {
                for (int dz = -edge; dz <= edge; dz += step) {
                    // 网格点可能落在更近的层（那里由细步长负责），跳过避免重复计数。
                    if (bandOf(dx, dz, r) != band) {
                        continue;
                    }
                    for (int dy : yWindows[b]) {
                        if (cells.size() >= MAX_SAMPLES) {
                            truncated = true;
                            break;
                        }
                        cells.add(new Cell(dx, dy, dz, band));
                    }
                    if (truncated) {
                        break;
                    }
                }
                if (truncated) {
                    break;
                }
            }
            if (truncated) {
                break;
            }
        }
        return new Plan(Collections.unmodifiableList(cells), truncated);
    }
}
