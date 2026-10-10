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
            new ToolSpec("inventory", "查看完整背包逐槽物品ID/数量/耐久、主手选中槽及装备栏。"
                    + "槽0-8是快捷栏，9-35是背包；切换工具或准备材料前先查看，未列出的背包槽为空",
                    "{\"type\":\"object\",\"properties\":{}}"),
            new ToolSpec("equip", "将 inventory 中指定槽的物品切换到主手。"
                    + "槽0-8直接选中；槽9-35与当前主手槽交换，原主手留在来源槽。"
                    + "只切换主手，不穿戴盔甲或切换副手；忙时先等任务结束或取消",
                    "{\"type\":\"object\",\"properties\":{\"slot\":{\"type\":\"integer\",\"minimum\":0,\"maximum\":35}},\"required\":[\"slot\"]}"),
            new ToolSpec("scan_area", "环顾四周，返回附近实体与可行动方块（容器/矿石/石材 rock/工作台/作物）的分层摘要；坐标均为绝对 @(x,y,z)。实体附entity_id/target_uuid供精确attack，不可猜编号",
                    "{\"type\":\"object\",\"properties\":{\"r\":{\"type\":\"integer\",\"description\":\"扫描半径1-32，默认16\"}}}"),
            new ToolSpec("craft", "按服务器实际普通合成配方制作背包材料。item 是产物注册ID，"
                    + "count 是至少需要的成品数1-64（默认1），按整次配方可能多产。"
                    + "query=true仅查询配方/材料/整批能否完成，recipe可指定配方ID；不自动递归制作缺料。"
                    + "2×2可随身合成，3×3需5.5格内工作台；忙时不可执行。失败不消耗材料或丢弃成品，"
                    + "缺料看回执，满背包先存入容器。特殊染色/修复、冶炼/锻造不由此工具执行",
                    "{\"type\":\"object\",\"properties\":{\"item\":{\"type\":\"string\",\"maxLength\":128},"
                            + "\"count\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":64},"
                            + "\"query\":{\"type\":\"boolean\"},\"recipe\":{\"type\":\"string\",\"maxLength\":128}},\"required\":[\"item\"]}"),
            new ToolSpec("smelt", "操作5.5格内原版熔炉/高炉/烟熏炉。action默认query只读，返回机器三槽、"
                    + "真实tick进度/剩余燃烧/配方/整批燃料是否够用/等待建议。load从inventory的input_slot/fuel_slot装入"
                    + "对应数量（各1-64，默认1）；可只补原料或燃料，也可给空炉先备燃料。装料不是烧制完成，机器自主运行，"
                    + "用wait等待后query/take。take的slot默认output，也可input/fuel回收，count为最多取出量"
                    + "1-64（默认64）；失败不移动物品。忙时仅query可用，取消任务不熄炉；"
                    + "停止后续烧制需显式take原料。只支持普通单件产物配方；机器不能用transfer",
                    "{\"type\":\"object\",\"properties\":{\"x\":{\"type\":\"integer\"},\"y\":{\"type\":\"integer\"},"
                            + "\"z\":{\"type\":\"integer\"},\"action\":{\"type\":\"string\",\"enum\":[\"query\",\"load\",\"take\"]},"
                            + "\"input_slot\":{\"type\":\"integer\",\"minimum\":0,\"maximum\":35},"
                            + "\"input_count\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":64},"
                            + "\"fuel_slot\":{\"type\":\"integer\",\"minimum\":0,\"maximum\":35},"
                            + "\"fuel_count\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":64},"
                            + "\"slot\":{\"type\":\"string\",\"enum\":[\"input\",\"fuel\",\"output\"]},"
                            + "\"count\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":64}},\"required\":[\"x\",\"y\",\"z\"]}"),
            new ToolSpec("break_block", "挖一格干燥方块，需主手有采收资格；先inventory/equip选工具。"
                    + "按真实进度耗时，原版处理破坏/耐久，附近本次新掉落先入包，余量留地；失败须核对实际回执。"
                    + "回执以 ACCEPTED: 开头表示已受理、还没挖完——别重发，做完系统会主动报结果",
                    "{\"type\":\"object\",\"properties\":{\"x\":{\"type\":\"integer\"},\"y\":{\"type\":\"integer\"},\"z\":{\"type\":\"integer\"}},\"required\":[\"x\",\"y\",\"z\"]}"),
            new ToolSpec("attack", "原地普通近战，只攻击一个目标，不追击、不自动换工具、不拾取掉落。"
                    + "entity_id用scan_area的正整数编号并带target_uuid；或hostile_nearby选一只可够到的未命名敌对生物。"
                    + "max_hits为挥击上限1-10，默认1，最多20秒；等满冷却与目标受伤恢复后出手。"
                    + "玩家/同伴/宠物/同队目标拒绝；中立或命名目标须ask_owner携带服务端authorization_id确认，"
                    + "再用同一实体编号/UUID/max_hits和批准编号调用。剑横扫范围有其他活物时拒绝；"
                    + "穿刺/动能武器和重锤不支持。ACCEPTED只是受理，等终态；挥击次数不等于伤害或击杀，"
                    + "看实际生命/吸收减少及target_dead，失败不自动重投",
                    "{\"type\":\"object\",\"properties\":{\"entity_id\":{\"oneOf\":[{\"type\":\"integer\",\"minimum\":1},"
                            + "{\"type\":\"string\",\"enum\":[\"hostile_nearby\"]}]},"
                            + "\"target_uuid\":{\"type\":\"string\",\"minLength\":36,\"maxLength\":36},"
                            + "\"max_hits\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":10},"
                            + "\"authorization_id\":{\"type\":\"string\",\"maxLength\":64}},"
                            + "\"required\":[\"entity_id\"],\"additionalProperties\":false}"),
            new ToolSpec("collect", "捡起指定点附近的地上掉落物进背包；坐标全部省略则以自己脚下为中心。"
                    + "r为1-12，默认3。忙时先等待或取消。空间不足会部分拾取，回执给已捡数量和仍在地上的数量，"
                    + "ok=false不代表完全没捡到；核对inventory后处理剩余物品",
                    "{\"type\":\"object\",\"properties\":{\"x\":{\"type\":\"integer\"},\"y\":{\"type\":\"integer\"},\"z\":{\"type\":\"integer\"},"
                            + "\"r\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":12}}}"),
            new ToolSpec("place_block", "将背包0-35里的方块物品放到准确目标格，item用物品ID如cobblestone。"
                    + "原版处理朝向/多格/组件，默认点击上面，可用face选择接触面；水中/可替换格和同种半砖合并由原版判断。"
                    + "暂不支持脚手架/告示牌等特殊物品；忙时先等待或取消。失败查看实际消耗和目标变化，勿自动重试",
                    "{\"type\":\"object\",\"properties\":{\"x\":{\"type\":\"integer\"},\"y\":{\"type\":\"integer\"},\"z\":{\"type\":\"integer\"},"
                            + "\"item\":{\"type\":\"string\",\"maxLength\":128},\"face\":{\"type\":\"string\","
                            + "\"enum\":[\"up\",\"down\",\"north\",\"south\",\"east\",\"west\"]}},\"required\":[\"x\",\"y\",\"z\",\"item\"]}"),
            new ToolSpec("move_to", "走向目标坐标：会用 DigAStar 规划绕路/挖穿/搭路（≤水平64/垂直32格）。"
                    + "若路需要改动世界，先回 NEED_CONFIRM 附具体清单和authorization_id。"
                    + "用ask_owner携带该编号取得主人明确确认，再用move_to携带相同编号重发。"
                    + "may_alter_terrain布尔不能代替主人授权，重规划新增改动须重新确认。"
                    + "搭路仅使用无额外组件的圆石/深板岩圆石/泥土/下界岩，缺料先准备；开路需合适主手工具。"
                    + "回执以 ACCEPTED: 开头表示已受理、人还在走——别重发（会被 BUSY 挡），做完系统会主动报结果",
                    "{\"type\":\"object\",\"properties\":{\"x\":{\"type\":\"integer\"},\"y\":{\"type\":\"integer\"},\"z\":{\"type\":\"integer\"},\"may_alter_terrain\":{\"type\":\"boolean\",\"description\":\"旧兼容字段，不授予权限\"},\"authorization_id\":{\"type\":\"string\",\"maxLength\":64}},\"required\":[\"x\",\"y\",\"z\"]}"),
            new ToolSpec("transfer", "与6.5格内未锁定普通容器存取物品：dir=out取出/in存入，item可选过滤；熔炉类机器改用smelt。"
                    + "遵守槽位、接触面与堆叠限制，忙时先等待或取消。可以部分搬运，回执给实际已搬及留在来源的数量；"
                    + "ok=false也可能已搬一部分，先核对inventory和剩余数量再行动",
                    "{\"type\":\"object\",\"properties\":{\"x\":{\"type\":\"integer\"},\"y\":{\"type\":\"integer\"},\"z\":{\"type\":\"integer\"},"
                            + "\"dir\":{\"type\":\"string\",\"enum\":[\"in\",\"out\"]},\"item\":{\"type\":\"string\",\"maxLength\":128}},\"required\":[\"x\",\"y\",\"z\",\"dir\"]}"),
            new ToolSpec("wait", "原地等待 seconds 秒（1-60），用于等熔炉出货、等作物长熟这类节奏，别用反复查看代替等待",
                    "{\"type\":\"object\",\"properties\":{\"seconds\":{\"type\":\"integer\"}},\"required\":[\"seconds\"]}"),
            new ToolSpec("ask_owner", "拿不准就问主人（会推送给主人与 neko，等待回复最长 2 分钟；游戏内主人可用 @bot 答 <文本> 回答）。"
                    + "只在方向性决策上用：要不要卖这批货/挖这条洞/用哪个方案。别为琐碎小事滥用",
                    "{\"type\":\"object\",\"properties\":{\"text\":{\"type\":\"string\"},\"authorization_id\":{\"type\":\"string\",\"maxLength\":64}},\"required\":[\"text\"]}"));
}
