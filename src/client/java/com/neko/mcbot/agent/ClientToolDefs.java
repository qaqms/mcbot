package com.neko.mcbot.agent;

import com.neko.mcbot.agentcore.llm.ToolSpec;

import java.util.List;

/** 给模型看的工具描述（服务端白名单是真源；这里只管提示词形状）。 */
public final class ClientToolDefs {

    private ClientToolDefs() {
    }

    public static final List<ToolSpec> SPECS = List.of(
            new ToolSpec("gather", "优先用于连续采集数量型物品：一轮调用本地扫描、选工作站位、移动、挖掘、走近拾取并核对背包，"
                    + "无需模型逐格列坐标/每12格重新规划。item是期望入包物品，targets是来源方块ID/#标签（泥土含grass_block/dirt），"
                    + "count和mode沿用主人原目标，additional是任务开始背包净新增、total是总量，自动登记不可降低的完成条件。"
                    + "应先据最新背包装备合适工具；r默认8、最大16，限定本段起点附近目标，不挖勘探洞。"
                    + "本地复用真实终态/权限，路线需确认、缺工具/空间、无进展或未知状态即交回模型决策。"
                    + "timeout_seconds默认600、最大900，max_blocks默认128、最大256，子调用最多1024。"
                    + "掉落必须走近拾取；矿格数不是成果。返回实际物品进度及阻塞，不保证自然世界已找到钻石",
                    "{\"type\":\"object\",\"properties\":{\"item\":{\"type\":\"string\",\"maxLength\":128},"
                            + "\"targets\":{\"type\":\"array\",\"minItems\":1,\"maxItems\":8,\"items\":{\"type\":\"string\",\"maxLength\":128}},"
                            + "\"count\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":4096},"
                            + "\"mode\":{\"type\":\"string\",\"enum\":[\"additional\",\"total\"]},"
                            + "\"r\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":16},"
                            + "\"timeout_seconds\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":900},"
                            + "\"max_blocks\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":256}},"
                            + "\"required\":[\"item\",\"targets\",\"count\"],\"additionalProperties\":false}"),
            new ToolSpec("set_goal", "数量型物品任务开始时登记本任务完成条件，原始主人的数量/口径不可改写。"
                    + "item是物品ID，count是目标件数；mode=additional指相对任务开始背包净新增，total指背包总量。"
                    + "首次登记后不能换目标或降低数量。完成回复会重新查询实际背包核对，挖掘格数/地上掉落不算入包。"
                    + "交付、建筑等目标不由此验证；无法继续用report_blocked，不能声称已完成",
                    "{\"type\":\"object\",\"properties\":{\"item\":{\"type\":\"string\",\"maxLength\":128},"
                            + "\"count\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":4096},"
                            + "\"mode\":{\"type\":\"string\",\"enum\":[\"additional\",\"total\"]}},"
                            + "\"required\":[\"item\",\"count\"],\"additionalProperties\":false}"),
            new ToolSpec("report_blocked", "确实无法继续时停止当前任务并报告实际障碍，保留已完成动作与物品。"
                    + "不用于临时思考或让主人逐步操作；取消后不会续跑。目标未满足按失败/受阻结束，不能写成完成",
                    "{\"type\":\"object\",\"properties\":{\"reason\":{\"type\":\"string\",\"minLength\":1,\"maxLength\":512}},"
                            + "\"required\":[\"reason\"],\"additionalProperties\":false}"),
            new ToolSpec("workflow", "将已确认的近身采集/放置/制作/烧制组合成1-12个明确步骤，一轮执行，逐步等待真实终态。"
                    + "steps每项为tool与args，支持status/inventory/equip/scan_area/find_resource/inspect_block/"
                    + "break_block/place_block/craft/smelt/wait；不支持移动/攻击/存取/拾取/反问/嵌套。"
                    + "timeout_seconds默认180、最多300；制作count、装料input_count/fuel_count、取出count必须明确填写，"
                    + "请求数合计≤128（放置算1件），明确挖放≤12格。这是请求预算，不限制原版配方取整产量/消耗或真实掉落。"
                    + "全部参数先校验；失败/缺制作条件/搜索无目标/需确认立即停止，保留已完成回执，不重试。"
                    + "只读任务须所有步骤只读；装料/等待成功不是成品完成，须查询和取出真实产物；取消不熄炉。"
                    + "根据新状态确定真实槽与坐标，不在清单里猜搜索结果或合成后的槽号",
                    "{\"type\":\"object\",\"properties\":{\"steps\":{\"type\":\"array\",\"minItems\":1,\"maxItems\":12,"
                            + "\"items\":{\"type\":\"object\",\"properties\":{\"tool\":{\"type\":\"string\"},"
                            + "\"args\":{\"type\":\"object\"}},\"required\":[\"tool\",\"args\"],\"additionalProperties\":false}},"
                            + "\"timeout_seconds\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":300}},"
                            + "\"required\":[\"steps\"],\"additionalProperties\":false}"),
            new ToolSpec("status", "查看最新位置/生命/饥饿/主手/任务实际进度；details=true含背包与装备逐槽明细。耗时不等于完成率",
                    "{\"type\":\"object\",\"properties\":{\"details\":{\"type\":\"boolean\"}},\"additionalProperties\":false}"),
            new ToolSpec("find_resource", "只读定向查找材料方块：targets为方块ID或#方块标签，如#minecraft:logs、oak_log、iron_ore。"
                    + "r默认8、最多16；最近优先采样4096格、最多16候选，返回绝对坐标及未加载/撞帽信息。"
                    + "不强制加载、不证明可达/可挖/实际掉落；空结果不等于没有资源，勿改变参数连续空转或挖掘探查",
                    "{\"type\":\"object\",\"properties\":{\"targets\":{\"type\":\"array\",\"minItems\":1,\"maxItems\":8,"
                            + "\"items\":{\"type\":\"string\",\"maxLength\":128}},\"r\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":16},"
                            + "\"work\":{\"type\":\"boolean\",\"description\":\"附当前能否看见/够到及附近安全站位候选，路径可达性仍需move_to\"}},"
                            + "\"required\":[\"targets\"],\"additionalProperties\":false}"),
            new ToolSpec("inspect_block", "只读查看6.5格内已加载方块及本体容器逐槽ID/数量/耐久。"
                    + "offset默认0，每页24槽，next_offset=-1表示末页；拒绝锁定/未展开战利品容器。"
                    + "不打开GUI、不搬物品、不合并双箱；机器计时用smelt query",
                    "{\"type\":\"object\",\"properties\":{\"x\":{\"type\":\"integer\"},\"y\":{\"type\":\"integer\"},\"z\":{\"type\":\"integer\"},"
                            + "\"offset\":{\"type\":\"integer\",\"minimum\":0,\"maximum\":1023}},\"required\":[\"x\",\"y\",\"z\"],\"additionalProperties\":false}"),
            new ToolSpec("inventory", "查看完整背包逐槽物品ID/数量/耐久、主手选中槽及装备栏。"
                    + "槽0-8是快捷栏，9-35是背包；切换工具或准备材料前先查看，未列出的背包槽为空",
                    "{\"type\":\"object\",\"properties\":{}}"),
            new ToolSpec("equip", "将 inventory 中指定槽的物品切换到主手。"
                    + "槽0-8直接选中；槽9-35与当前主手槽交换，原主手留在来源槽。"
                    + "只切换主手，不穿戴盔甲或切换副手；忙时先等任务结束或取消",
                    "{\"type\":\"object\",\"properties\":{\"slot\":{\"type\":\"integer\",\"minimum\":0,\"maximum\":35}},\"required\":[\"slot\"]}"),
            new ToolSpec("scan_area", "环顾四周，返回附近实体与可行动方块分层摘要，绝对坐标；entity_id/target_uuid供attack，不可猜编号。"
                    + "另含地上item_targets数量/延迟/站位；item可过滤掉落物品，items_only=true只查掉落，不扫方块，实际拾取仍需走近",
                    "{\"type\":\"object\",\"properties\":{\"r\":{\"type\":\"integer\",\"description\":\"扫描半径1-32，默认16\"},"
                            + "\"items_only\":{\"type\":\"boolean\"},\"item\":{\"type\":\"string\",\"maxLength\":128}}}"),
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
                    + "按真实进度耗时，原版处理破坏/耐久，掉落留在地上，返回掉落实体位置；不会直接入包。"
                    + "走近掉落物会自动拾取，须核对最新背包，挖掉矿块不等于获得物品；失败须核对实际回执。"
                    + "回执以 ACCEPTED: 开头表示已受理、还没挖完——别重发，做完系统会主动报结果",
                    "{\"type\":\"object\",\"properties\":{\"x\":{\"type\":\"integer\"},\"y\":{\"type\":\"integer\"},\"z\":{\"type\":\"integer\"}},\"required\":[\"x\",\"y\",\"z\"]}"),
            new ToolSpec("attack", "原地普通近战，只攻击一个目标，不追击、不自动换工具、不拾取掉落。"
                    + "entity_id用scan_area的正整数编号并带target_uuid；或hostile_nearby选一只可够到的未命名敌对生物。"
                    + "max_hits为挥击上限1-10，默认1，最多20秒；等满冷却与目标受伤恢复后出手。"
                    + "玩家/同伴/宠物/同队目标拒绝；中立或命名目标须ask_owner携带服务端authorization_id确认，"
                    + "再用同一实体编号/UUID/max_hits和批准编号调用。剑横扫范围有其他活物时拒绝；"
                    + "穿刺/动能武器和重锤不支持。ACCEPTED只是受理，等终态；挥击次数不等于伤害或击杀，"
                    + "看实际生命/吸收减少及target_dead，失败不自动重投。"
                    + "参数拒绝会给原因码和字段；按回执核对扫描身份/类型/上限，不猜目标或授权",
                    "{\"type\":\"object\",\"properties\":{\"entity_id\":{\"oneOf\":[{\"type\":\"integer\",\"minimum\":1},"
                            + "{\"type\":\"string\",\"enum\":[\"hostile_nearby\"]}]},"
                            + "\"target_uuid\":{\"type\":\"string\",\"minLength\":36,\"maxLength\":36},"
                            + "\"max_hits\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":10},"
                            + "\"authorization_id\":{\"type\":\"string\",\"maxLength\":64}},"
                            + "\"required\":[\"entity_id\"],\"additionalProperties\":false}"),
            new ToolSpec("collect", "兼容工具：立即尝试原版触碰拾取；身体平时会自动拾取，无需挖后逐次调用。"
                    + "远处物品先move_to走近，r只限查询范围，不能隔空吸取。"
                    + "坐标全部省略则以自己脚下为中心，item可过滤物品ID。r为1-12，默认3，遵守拾取延迟和归属。"
                    + "忙时先等待或取消。空间不足会部分拾取，回执给已捡数量和仍在地上的数量，"
                    + "ok=false不代表完全没捡到；核对inventory后处理剩余物品",
                    "{\"type\":\"object\",\"properties\":{\"x\":{\"type\":\"integer\"},\"y\":{\"type\":\"integer\"},\"z\":{\"type\":\"integer\"},"
                            + "\"r\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":12},\"item\":{\"type\":\"string\",\"maxLength\":128}}}"),
            new ToolSpec("place_block", "将背包0-35里的方块物品放到准确目标格，item用物品ID如cobblestone。"
                    + "原版处理朝向/多格/组件，默认点击上面，可用face选择接触面；水中/可替换格和同种半砖合并由原版判断。"
                    + "暂不支持脚手架/告示牌等特殊物品；忙时先等待或取消。失败查看实际消耗和目标变化，勿自动重试",
                    "{\"type\":\"object\",\"properties\":{\"x\":{\"type\":\"integer\"},\"y\":{\"type\":\"integer\"},\"z\":{\"type\":\"integer\"},"
                            + "\"item\":{\"type\":\"string\",\"maxLength\":128},\"face\":{\"type\":\"string\","
                            + "\"enum\":[\"up\",\"down\",\"north\",\"south\",\"east\",\"west\"]}},\"required\":[\"x\",\"y\",\"z\",\"item\"]}"),
            new ToolSpec("move_to", "走向目标坐标：会用 DigAStar 规划绕路/挖穿/搭路（≤水平64/垂直32格）。"
                    + "默认到目标相邻一格范围；拾取或精确工作站位用exact=true，要求指定脚格实际站稳。"
                    + "若路需要改动世界，先回 NEED_CONFIRM 附具体清单和authorization_id。"
                    + "用ask_owner携带该编号取得主人明确确认，再用move_to携带相同编号重发。"
                    + "may_alter_terrain布尔不能代替主人授权，重规划新增改动须重新确认。"
                    + "搭路仅使用无额外组件的圆石/深板岩圆石/泥土/下界岩，缺料先准备；开路需合适主手工具。"
                    + "回执以 ACCEPTED: 开头表示已受理、人还在走——别重发（会被 BUSY 挡），做完系统会主动报结果",
                    "{\"type\":\"object\",\"properties\":{\"x\":{\"type\":\"integer\"},\"y\":{\"type\":\"integer\"},\"z\":{\"type\":\"integer\"},\"exact\":{\"type\":\"boolean\"},\"may_alter_terrain\":{\"type\":\"boolean\",\"description\":\"旧兼容字段，不授予权限\"},\"authorization_id\":{\"type\":\"string\",\"maxLength\":64}},\"required\":[\"x\",\"y\",\"z\"]}"),
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
