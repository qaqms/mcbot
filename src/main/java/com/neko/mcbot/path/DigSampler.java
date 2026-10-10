package com.neko.mcbot.path;

/**
 * DigAStar 的地形查询契约（DESIGN §8）：纯 int/坐标进、代价值出，
 * 不 import 任何 Minecraft 类——搜索核因此可脱离游戏单测。
 * 服务器实现把每个方法翻译成方块状态判断（LevelDigSampler）。
 */
public interface DigSampler {

    /** 挖掘不可行的哨兵代价（不可破坏/神圣方块/岩浆邻接等）。 */
    double INFEASIBLE = Double.MAX_VALUE;

    /** Shared by planning, memoization and execution; the finite sentinel is not a usable cost. */
    static boolean feasibleDig(double seconds) {
        return Double.isFinite(seconds) && seconds >= 0 && seconds < INFEASIBLE;
    }

    /** 该格当前是否可通行（空气等价：不挡腿、非流体、在界内）。 */
    boolean passable(int x, int y, int z);

    /**
     * 把该格挖开需要多少"代价秒"（按手持工具的真实挖速折算）。
     * 已可通行应返回 0；不可挖/不许挖返回 {@link #INFEASIBLE}。
     */
    double digSeconds(int x, int y, int z);

    /** 该格能否当脚下支撑（实心顶面）。 */
    boolean support(int x, int y, int z);

    /** 该格是否适合放方块搭路/垫脚（空气、在界内、非保护位）。 */
    boolean placeable(int x, int y, int z);

    /** 放一格的代价（含材料稀缺惩罚）。 */
    double placeCost(int x, int y, int z);

    /** 坐标是否处于可搜索范围（世界边界 + 维度高度 + 起点的搜索半径盒）。 */
    boolean inBounds(int x, int y, int z);

    /** 全程可放的方块总数上限（=背包可用方块存量）：放格与挖格一样要预算化。 */
    int maxPlaces();
}
