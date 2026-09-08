package com.neko.mcbot.common;

/**
 * scan_area 的**可行动词表**（R2-D）：模型读到的每个类别都直接对应"能做的一件事"，
 * 而不是原来那种"容器:chest"式中文描述——描述性词表要模型自己再推一遍"所以我能干嘛"。
 *
 * <p>为什么放 common 且不 import MC：分类判定要能在普通 JVM 里被单测钉住
 * （{@code src/test/java} 拿不到 minecraft classpath，与 {@link WireSize} 同一约束），
 * 服务器侧只负责"把注册表路径/是否容器/是否作物"这三样事实喂进来。
 */
public enum ScanCategory {

    /** 能存取东西的方块（箱/桶/潜影盒……判定看 BlockEntity 是否 Container）。 */
    CONTAINER("container"),
    /** 挖了能拿材料的矿石。 */
    ORE("ore"),
    /** 纯石材：可挖可垫，**是材料不是目标**（见 {@link ScanClassify#ROCK_PATHS} 注释）。 */
    ROCK("rock"),
    /** 合成/烧炼的工作站点。 */
    WORKBENCH("workbench"),
    /** 能收的作物。 */
    FARM("farm"),
    /** 敌对生物（要打或要绕）。 */
    HOSTILE("hostile");

    private final String key;

    ScanCategory(String key) {
        this.key = key;
    }

    /** 词表 token（进回执，故意用英文与工具参数/注册表路径同一套词汇）。 */
    public String key() {
        return key;
    }

    @Override
    public String toString() {
        return key;
    }
}
