package com.neko.mcbot.common;

import java.util.Set;

/**
 * 把一个方块的"事实"（注册表路径 + 是否有容器 BlockEntity + 是否作物）折成一个
 * {@link ScanCategory}。零 MC 依赖：MC 侧不许在这里做硬度/tag 反查，判定必须是
 * **查表**，因为扫描一拍要看几百格（R2-D 的"不做每格硬度反查"就落在这）。
 *
 * <p><b>为什么 rock 用常数路径集而不是 tag：</b>1.21.11 上
 * {@code state.is(BlockTags.X)} 要走 HolderSet 查找 + holder 比对，逐格调用在主线程上
 * 是可见成本；而"哪些石头算石料"是产品口径（泥土沙**不入** rock：那是材料不是目标，
 * 见设计卡 §D），写死成常数集既快又能被单测钉住口径。
 */
public final class ScanClassify {

    private ScanClassify() {
    }

    /**
     * 石材集（注册表路径，**精确匹配**）。故意不含 dirt/sand/gravel：
     * 让它们进 rock 会让模型"见土就挖"，材料需求走 place/transfer 的显式指令。
     * 也不含 stone_bricks/polished_* 这类加工产物——那是背包里的东西，不是野外目标。
     */
    public static final Set<String> ROCK_PATHS = Set.of(
            "stone", "cobblestone", "mossy_cobblestone",
            "granite", "diorite", "andesite",
            "deepslate", "cobbled_deepslate", "polished_deepslate",
            "tuff");

    /** 工作站点集（沿用改造前的口径：只有合成台与熔炉，别顺手扩到高炉/烟熏炉——那是另一张卡的契约变更）。 */
    public static final Set<String> WORKBENCH_PATHS = Set.of("crafting_table", "furnace");

    /**
     * 矿石判定：以 {@code _ore} 结尾。
     *
     * <p>改造前用的是 {@code path.contains("ore")}，那会把任何名字里带 "ore" 的方块误判成
     * 矿石（口径不干净）。后缀判定覆盖原版全部矿石（coal_ore / deepslate_iron_ore /
     * nether_gold_ore…），且不命中 stone/granite 一类；代价是 ancient_debris 这类
     * 不以 _ore 结尾的"矿"不进 ore——它需要的是"靠近看"而不是"直接挖"，交给后续卡显式列。
     */
    public static boolean isOrePath(String regPath) {
        return regPath != null && regPath.endsWith("_ore");
    }

    /**
     * 分类；返回 null = 不值得告诉模型（空气、水、普通泥土草木）。
     *
     * <p>判定顺序即优先级：容器 &gt; 矿石 &gt; 工作台 &gt; 作物 &gt; 石材。
     * 顺序有实质意义——比如"装着矿的箱子"必须先算 container（可交互），
     * 而带 BlockEntity 的工作台（熔炉自身有 BlockEntity）**不能**被容器判定吃掉，
     * 所以调用方给的 {@code container} 必须是"Container 接口"而不是"有 BlockEntity"。
     */
    public static ScanCategory classify(String regPath, boolean container, boolean crop) {
        if (regPath == null || regPath.isEmpty()) {
            return null;
        }
        if (container) {
            return ScanCategory.CONTAINER;
        }
        if (isOrePath(regPath)) {
            return ScanCategory.ORE;
        }
        if (WORKBENCH_PATHS.contains(regPath)) {
            return ScanCategory.WORKBENCH;
        }
        if (crop) {
            return ScanCategory.FARM;
        }
        if (ROCK_PATHS.contains(regPath)) {
            return ScanCategory.ROCK;
        }
        return null;
    }
}
