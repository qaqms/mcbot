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
            new ToolSpec("scan_area", "环顾四周，返回附近实体与可行动方块（容器/矿石/石材 rock/工作台/作物）的分层摘要；坐标均为绝对 @(x,y,z)",
                    "{\"type\":\"object\",\"properties\":{\"r\":{\"type\":\"integer\",\"description\":\"扫描半径1-32，默认16\"}}}"),
            new ToolSpec("break_block", "挖掉一格方块（按真实硬度耗时，需要合适工具，掉落自动进背包）。"
                    + "回执以 ACCEPTED: 开头表示已受理、还没挖完——别重发，做完系统会主动报结果",
                    "{\"type\":\"object\",\"properties\":{\"x\":{\"type\":\"integer\"},\"y\":{\"type\":\"integer\"},\"z\":{\"type\":\"integer\"}},\"required\":[\"x\",\"y\",\"z\"]}"),
            new ToolSpec("collect", "捡起指定点附近的地上掉落物进背包",
                    "{\"type\":\"object\",\"properties\":{\"x\":{\"type\":\"integer\"},\"y\":{\"type\":\"integer\"},\"z\":{\"type\":\"integer\"},\"r\":{\"type\":\"integer\"}}}"),
            new ToolSpec("place_block", "把背包里的方块放到目标格（item 用注册表路径如 cobblestone）",
                    "{\"type\":\"object\",\"properties\":{\"x\":{\"type\":\"integer\"},\"y\":{\"type\":\"integer\"},\"z\":{\"type\":\"integer\"},\"item\":{\"type\":\"string\"}},\"required\":[\"x\",\"y\",\"z\",\"item\"]}"),
            new ToolSpec("move_to", "走向目标坐标：会用 DigAStar 规划绕路/挖穿/搭路（≤水平64/垂直32格）。"
                    + "若路需要改动世界，先回 NEED_CONFIRM 附方块清单——确认没问题就带 may_alter_terrain=true 重发。"
                    + "回执以 ACCEPTED: 开头表示已受理、人还在走——别重发（会被 BUSY 挡），做完系统会主动报结果",
                    "{\"type\":\"object\",\"properties\":{\"x\":{\"type\":\"integer\"},\"y\":{\"type\":\"integer\"},\"z\":{\"type\":\"integer\"},\"may_alter_terrain\":{\"type\":\"boolean\",\"description\":\"允许这条路挖/放方块改动世界；首次被 NEED_CONFIRM 后确认再带\"}},\"required\":[\"x\",\"y\",\"z\"]}"),
            new ToolSpec("transfer", "与容器存取物品：dir=out取出/in存入，item可选过滤",
                    "{\"type\":\"object\",\"properties\":{\"x\":{\"type\":\"integer\"},\"y\":{\"type\":\"integer\"},\"z\":{\"type\":\"integer\"},\"dir\":{\"type\":\"string\",\"enum\":[\"in\",\"out\"]},\"item\":{\"type\":\"string\"}},\"required\":[\"x\",\"y\",\"z\",\"dir\"]}"),
            new ToolSpec("wait", "原地等待 seconds 秒（1-60），用于等熔炉出货、等作物长熟这类节奏，别用反复查看代替等待",
                    "{\"type\":\"object\",\"properties\":{\"seconds\":{\"type\":\"integer\"}},\"required\":[\"seconds\"]}"),
            new ToolSpec("ask_owner", "拿不准就问主人（会推送给主人与 neko，等待回复最长 2 分钟；游戏内主人可用 @bot 答 <文本> 回答）。"
                    + "只在方向性决策上用：要不要卖这批货/挖这条洞/用哪个方案。别为琐碎小事滥用",
                    "{\"type\":\"object\",\"properties\":{\"text\":{\"type\":\"string\"}},\"required\":[\"text\"]}"));
}
