package com.neko.mcbot.agent;

import com.neko.mcbot.agentcore.llm.ToolSpec;

import java.util.List;

/** 给模型看的工具描述（服务端白名单是真源；这里只管提示词形状）。 */
public final class ClientToolDefs {

    private ClientToolDefs() {
    }

    public static final List<ToolSpec> SPECS = List.of(
            new ToolSpec("status", "查看你自己的状态：位置/生命/饥饿/背包占用/手持物",
                    "{\"type\":\"object\",\"properties\":{}}"),
            new ToolSpec("scan_area", "环顾四周，返回附近实体与特殊方块（容器/矿石/工作台熔炉/作物）的摘要",
                    "{\"type\":\"object\",\"properties\":{\"r\":{\"type\":\"integer\",\"description\":\"扫描半径1-32，默认16\"}}}"),
            new ToolSpec("break_block", "挖掉一格方块（按真实硬度耗时，需要合适工具，掉落自动进背包）",
                    "{\"type\":\"object\",\"properties\":{\"x\":{\"type\":\"integer\"},\"y\":{\"type\":\"integer\"},\"z\":{\"type\":\"integer\"}},\"required\":[\"x\",\"y\",\"z\"]}"),
            new ToolSpec("collect", "捡起指定点附近的地上掉落物进背包",
                    "{\"type\":\"object\",\"properties\":{\"x\":{\"type\":\"integer\"},\"y\":{\"type\":\"integer\"},\"z\":{\"type\":\"integer\"},\"r\":{\"type\":\"integer\"}}}"),
            new ToolSpec("place_block", "把背包里的方块放到目标格（item 用注册表路径如 cobblestone）",
                    "{\"type\":\"object\",\"properties\":{\"x\":{\"type\":\"integer\"},\"y\":{\"type\":\"integer\"},\"z\":{\"type\":\"integer\"},\"item\":{\"type\":\"string\"}},\"required\":[\"x\",\"y\",\"z\",\"item\"]}"),
            new ToolSpec("move_to", "走向目标坐标（滑步版≤48格，不会挖路；被挡会如实报告）",
                    "{\"type\":\"object\",\"properties\":{\"x\":{\"type\":\"integer\"},\"y\":{\"type\":\"integer\"},\"z\":{\"type\":\"integer\"}},\"required\":[\"x\",\"y\",\"z\"]}"),
            new ToolSpec("transfer", "与容器存取物品：dir=out取出/in存入，item可选过滤",
                    "{\"type\":\"object\",\"properties\":{\"x\":{\"type\":\"integer\"},\"y\":{\"type\":\"integer\"},\"z\":{\"type\":\"integer\"},\"dir\":{\"type\":\"string\",\"enum\":[\"in\",\"out\"]},\"item\":{\"type\":\"string\"}},\"required\":[\"x\",\"y\",\"z\",\"dir\"]}"),
            new ToolSpec("wait", "原地等待 seconds 秒（1-60），用于等熔炉出货、等作物长熟这类节奏，别用反复查看代替等待",
                    "{\"type\":\"object\",\"properties\":{\"seconds\":{\"type\":\"integer\"}},\"required\":[\"seconds\"]}"),
            new ToolSpec("ask_owner", "拿不准就问主人（会推送给主人与 neko，等待回复最长 5 分钟）。"
                    + "只在方向性决策上用：要不要卖这批货/挖这条洞/用哪个方案。别为琐碎小事滥用",
                    "{\"type\":\"object\",\"properties\":{\"text\":{\"type\":\"string\"}},\"required\":[\"text\"]}"));
}
