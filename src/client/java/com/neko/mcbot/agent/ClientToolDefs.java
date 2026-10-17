package com.neko.mcbot.agent;

import com.neko.mcbot.agentcore.llm.ToolSpec;
import com.neko.mcbot.agentcore.script.BoundedProgram;

import java.util.List;

/** 给模型看的工具描述（服务端白名单是真源；这里只管提示词形状）。 */
public final class ClientToolDefs {

    private ClientToolDefs() {
    }

    private static final String BUILD_COORDINATE = """
            {"type":"object","properties":{"x":{"type":"integer","minimum":-32,"maximum":32},
            "y":{"type":"integer","minimum":-32,"maximum":32},"z":{"type":"integer","minimum":-32,"maximum":32}},
            "required":["x","y","z"],"additionalProperties":false}
            """;
    private static final String BUILD_PROPERTIES = """
            {"type":"array","minItems":0,"maxItems":8,"items":{"type":"object",
            "properties":{"name":{"type":"string","minLength":1,"maxLength":32},
            "value":{"type":"string","minLength":1,"maxLength":32}},
            "required":["name","value"],"additionalProperties":false}}
            """;
    private static final String BUILD_CELL = """
            {"type":"object","properties":{"x":{"type":"integer","minimum":-32,"maximum":32},
            "y":{"type":"integer","minimum":-32,"maximum":32},"z":{"type":"integer","minimum":-32,"maximum":32},
            "block":{"type":"string","minLength":1,"maxLength":128},"properties":%s},
            "required":["x","y","z","block"],"additionalProperties":false}
            """.formatted(BUILD_PROPERTIES);
    private static final String BUILD_FILL = """
            {"type":"object","properties":{"from":%s,"to":%s,"block":{"type":"string","minLength":1,"maxLength":128},
            "properties":%s},"required":["from","to","block"],"additionalProperties":false}
            """.formatted(BUILD_COORDINATE, BUILD_COORDINATE, BUILD_PROPERTIES);
    private static final String BUILD_ANCHOR = """
            {"type":"object","properties":{"x":{"type":"integer","minimum":-29999984,"maximum":29999984},
            "y":{"type":"integer","minimum":-2048,"maximum":2047},"z":{"type":"integer","minimum":-29999984,"maximum":29999984}},
            "required":["x","y","z"],"additionalProperties":false}
            """;
    private static final String BUILD_TARGET = """
            {"oneOf":[{"type":"object","properties":{"anchor":%s,"rotation":{"type":"integer","enum":[0,90,180,270]},
            "cells":{"type":"array","minItems":1,"maxItems":128,"items":%s}},
            "required":["anchor","cells"],"additionalProperties":false},
            {"type":"object","properties":{"anchor":%s,"rotation":{"type":"integer","enum":[0,90,180,270]},"fill":%s},
            "required":["anchor","fill"],"additionalProperties":false}]}
            """.formatted(BUILD_ANCHOR, BUILD_CELL, BUILD_ANCHOR, BUILD_FILL);

    /** Result fields alongside the input registry; optional mode-specific fields must be checked before use. */
    static String resultGuide(String name) {
        return switch (name) {
            case "build_site" -> "data.active/task_scoped/target_revision/bounds为当前任务工地；"
                    + "checkpoints/edit_attempts及navigation_*累计不重开，temporary含实际来源与cleanup_allowed；"
                    + "登记不改世界或授予许可，return_guaranteed=false；只保护声明通道，不证明全局逃生。";
            case "status" -> "data.x/y/z为脚格整数，dimension为字符串；没有data.position或data.pos。"
                    + "hp/food为数值，on_ground/in_water/eyes_in_water/in_lava为布尔。"
                    + "details=true才有data.inventory：selected_slot、slots[{slot,item,count,damage?,max_damage?}]、equipment。"
                    + "slots是Lua从1起的列表，但slot字段是实际0-35槽号；空槽item为空字符串、count=0。"
                    + "drop_changes为本身体近期有界来源摘要，拾取事件不替代当前inventory。"
                    + "body_defense为共用自保状态/实际出手/固定局部范围及威胁来源；defense_active/state与"
                    + "threat_scan_complete为概要，局部拉开不保证甩掉追兵，取消后不自动接旧活。"
                    + "spatial_facts为区块版本；navigation_trail给当前任务实际路标和有限改动，非可返保证。";
            case "inventory" -> "data.selected_slot、storage_size、slots_used；"
                    + "data.slots是[{slot,item,count,damage?,max_damage?}]，data.equipment是装备列表。"
                    + "slots是从1起的列表，传给equip的是entry.slot实际0-35槽号。";
            case "craft" -> "data.can_craft布尔；false时data.failure说明缺口，不是脚本异常。"
                    + "data.ingredients是[{options:物品ID字符串列表,per_batch,required_count,available_count,options_truncated}]；"
                    + "没有materials或missing字段。requested_count/batches/produced_count为计划数，crafted_count为实际数；"
                    + "output_per_batch是{item,count}，requires_workbench为布尔，workbench?为{x,y,z}。"
                    + "material_index查询才有material:{index,recipe,items,sources,"
                    + "processes,truncated,processes_truncated,world_sources_verified}；"
                    + "items/sources条目为{item,block?,conversion?}，processes条目有process/workstation/"
                    + "produced_item/produced_count/input_count/inputs/requires_fuel/cooking_ticks?。"
                    + "ingredients[n]的材料分组序号是n-1；缺口按required_count与available_count计算，"
                    + "options可能重叠，最终可制作仍以can_craft为准。";
            case "scan_area" -> "data.item_targets是[{item,count,x,y,z,target_uuid,stand_positions,"
                    + "can_touch_here,pickup_allowed,pickup_delayed,pickup_delay_ticks,drop_origins}]；"
                    + "drop_origins含recorded_own_count、unattributed_to_body_count及有界parts，不认领历史掉落；"
                    + "stand_positions是整数脚格{x,y,z}，用exact=true走到候选后核对新鲜背包。"
                    + "一般扫描另有entity_targets、summary_lines、groups、special_blocks；"
                    + "entities/summary_lines/groups/special_blocks是文字列表，不是带坐标的对象。"
                    + "entity_targets条目的entity_id/target_uuid供attack，不可猜编号。"
                    + "items_only=true不含方块/活物字段；frontiers=true才有exploration_points。"
                    + "没有blocks字段；查指定方块应search_resource，不逐格inspect代替区域搜索。";
            case "find_resource", "search_resource" -> "data.targets是[{block,x,y,z,distance,...}]；"
                    + "work=true时按预算附work_checked/can_work_here/can_clear_here/stand_positions/work_failure/"
                    + "harvestable_with_hand/dry_work_safe，缺字段表示未知。stand_positions是脚格{x,y,z}。"
                    + "no_targets为布尔，unknown为数量，results_truncated/work_truncated表示输出/工作评估截断；"
                    + "find_resource续页看next_offset及scan_center；search_resource给scan_complete/coverage_complete/"
                    + "search_pages。局部空结果不等于没有资源；目标发现不等于可采或入包。";
            case "inspect_block" -> "data.block是方块ID字符串，container是布尔，slots是[{slot,item,count,...}]，"
                    + "slot_count/offset/next_offset为整数。没有block.name或pos；next_offset=-1为末页。"
                    + "普通方块也返回成功且slots为空；区域搜索用search_resource。";
            case "place_block" -> "query时data.preflight_passed布尔；false时failure是原因字符串，"
                    + "alternatives是改选目标格的最多6个{x,y,z,face}，checked_positions为这些目标检查数；"
                    + "stand_positions是原目标的最多6个整数脚格{x,y,z,feet_y}，不是其他目标格。"
                    + "建议已做有界预检，不必再次遍历邻域，执行前仍重验。"
                    + "native_validation_pending表示原版尚待执行，changed=false/consumed_count=0不是已放置。"
                    + "执行后看changed/consumed_count/placed/x/y/z及placement_contact?，失败可能已有实际效果。";
            case "build_diff" -> "data.target_revision由完整目标内容生成；counts对全部目标分类，remaining不含已满足格。"
                    + "goal_satisfied仅完整稳定现场且全部支持/满足才true，不直接登记或完成外层任务。"
                    + "cells是当前16格明细，next_offset=-1为末页；materials独立material_next_offset续页。"
                    + "料单required为全目标已知未满足格需求下界，available仅36储物槽的无额外组件材料，"
                    + "shortfall是差额；allocated仅当前明细页通过原版预检的份数，未预留，consumed始终0。"
                    + "material_bill_complete=false有未知或不支持缺口，不能把缺行当零需求。"
                    + "not_assessed表示当前页外或工作预算未查，unknown表示现场不完整；"
                    + "ready仅近身状态预检，不证明实际放置。observation_complete/spatial_stable和预算字段必须核对。"
                    + "observation_key供跨页核同一次世界/材料事实；cells.item/material_available供选择有限工作，"
                    + "clear_allowed仅已登记同修订工地的当前安全清障提示，真实挖掘仍重验。";
            case "build_nearby" -> "ACCEPTED只表示受理，等真终态。action_attempts为本批尝试，placed_blocks/"
                    + "consumed_count为实际结果，placement_results含逐格效果；失败可能已改变世界或扣料。"
                    + "survey是收尾重查全目标，仍有cells/materials分页；final_verified=false不可验收。"
                    + "batch_finished只表示局部批次结束，goal_satisfied才是同修订全目标现场满足，仍不完成外层set_goal。";
            case "break_block" -> "data.removed表示真实移除；collected_count=0表示挖掘不直接塞包。"
                    + "drop_accounting_known表示原生来源观察可用；可用时drop_accounting含ledger_id/origin_id/role/source、"
                    + "spawned_count/live_count/picked_up_self_count/picked_up_other_count/destroyed_count/unresolved_count。"
                    + "accounting_complete仅结算已观察生成物；同步区间外延迟生成不归源，混堆部分转移可变未知。"
                    + "drops仅源格当前可归源物，related_drops为连带/来源不明物；"
                    + "drop_spawns是源格生成样本，须drop_spawns_complete=true才可据未出现判断。"
                    + "clearing_results/clearing_drop_summary与目标分开。拾取累计不替代新鲜背包或最终目标。";
            case "move_to" -> "真正终态data.x/y/z为实际脚格，feet_y为碰撞脚高，arrival为position或work，"
                    + "removed_blocks/placed_blocks为本段实际改动。默认相邻到达不保证触碰拾取，"
                    + "拾取用scan_area.item_targets[].stand_positions及exact=true；工作用arrival=work。"
                    + "放置工作可加place_item和face，can_work_here仅当前放置预检，到站未执行放置。"
                    + "remaining_items是开路来源当前在地量，picked_up_self_items/picked_up_other_items/"
                    + "destroyed_drop_items/unresolved_drop_items分别结算，未知不冒充入包。"
                    + "navigation_trail.waypoints最多16项含原起点/近期实际路标{id,x,y,z,feet_y,bounds?,underground}；"
                    + "return_waypoint引用本任务ID，沿用原bounds/underground并exact=true、walk_only=true重新导航，不能据路标保证零编辑可返。"
                    + "失败数据可能仅含已知部分效果，须status details=true重新核对，不假定到了请求格。";
            case "attack" -> "ACCEPTED只表示受理；终态strikes/max_hits是原版尝试次数/上限，"
                    + "observed_health_loss/observed_absorption_loss为实际前后观测，不作独占归因。"
                    + "target_dead仅目标存活事实，达到挥击上限不代表击杀；observation_known=false可能已有未知副作用，不重投。"
                    + "approach仅显式接近时存在，moving/search_nodes/search_records/search_nanos/replans是原累计导航账，"
                    + "walk_only=true，anchor为固定脚格{x,y,z}、radius为原6.5格范围；路线到达或停步不代表出手/击杀。";
            case "gather" -> "data.goal_satisfied、actual_progress、scope、mined_blocks、cleared_blocks、"
                    + "primitive_calls、skipped_blocks/skipped_items及last_observation。"
                    + "成果是实际背包增量/总量，mined_blocks或source_observations不是入包数。"
                    + "失败回执保留实际效果与原因，材料subtask不改变最终目标。";
            case "explore" -> "data.state为新鲜身体/背包，discovery?为搜索结果，movement?为本段移动；"
                    + "exploration_advanced只表示已换观察区；exploration_attempts/exploration_max_steps/"
                    + "exploration_radius/return_steps/underground_remaining_digs是原任务累计账。";
            case "smelt" -> "data.input/fuel/output是{item,count,...}；state、cook_progress_ticks/cook_total_ticks、"
                    + "burn_remaining_ticks、fuel_sufficient_for_input、suggested_wait_seconds描述机器。"
                    + "实际装取看loaded_input_count/loaded_fuel_count/taken_count；装料不表示产物已完成。";
            case "tool_help" -> "有names时data.definitions是[{name,description,parameters,returns}]；"
                    + "省略names时data.tools是[{name,summary}]。说明自动展示，无须tostring表；同任务直接复用。";
            case "run_program" -> "data.returned保留Lua返回原值；api_calls/terminal_calls/query_calls/"
                    + "primitive_calls记录原预算；last_tool/last_receipt是最后子调用。"
                    + "错误时program_error?={kind,line}；line为本段源码行，0表示无法定位。"
                    + "mc.call返回{ok,feedback,data}；列表从1开始，缺字段为nil。"
                    + "只读例：local r=mc.call('status',{details=true}); "
                    + "return {x=r.data.x,slots=r.data.inventory.slots}。";
            default -> "mc.call返回{ok:布尔,feedback:说明字符串,data:结构化对象}；"
                    + "字段依实际终态/模式可能省略。先检查nil，不能根据参数名猜返回值；"
                    + "不清楚可直接return r.data或print(r.data)，避免tostring表丢失结构。";
        };
    }

    public static final List<ToolSpec> SPECS = List.of(
            new ToolSpec("run_program", "优先组合有结果依赖的阶段：Lua局部变量/函数/if/for/while从真实回执选下一步，减少逐动作模型往返。"
                    + "通过mc.call('工具名',{参数})顺序执行现有游戏工具及gather/workflow/explore/search_resource；返回表含ok/feedback/data，数组从1开始。"
                    + "查询配方缺口、inventory槽位、工作站位均据data，不猜坐标/槽；缺字段或条件不够则return说明阻塞，核验后才继续。"
                    + "最终物品先在外层set_goal登记；组合内gather必须scope='subtask'，已有足量材料宜mode='total'。"
                    + "失败/需确认立即停止并保留回执，交回外层ask_owner；不在程序内重试或自动批准。"
                    + BoundedProgram.LANGUAGE_GUIDE
                    + "不能嵌套run_program或调用set_goal/report_blocked/ask_owner；这些只能外层调用。"
                    + "code最多16384字节；timeout_seconds默认300最多900，max_calls默认128最多256；"
                    + "max_calls同时约束mc.call次数和嵌套内部的原子调用总数，gather一次会消耗多次扫描/移动/挖掘/背包调用；"
                    + "不要按Lua里的mc.call行数给嵌套采集设置很小的预算，通常省略沿用默认值。查询大表留在局部变量，只返回所需事实。"
                    + "计算/表/字符串/输出都有独立预算，动作仍走任务权限、身体互斥与真实终态。"
                    + "成功查询只记录调用编号，全文供程序读取；需要模型判断的事实须显式return/print。"
                    + "tool_help说明始终展示给模型；负预检保留有界原因。运行错误带program_error.kind/line，"
                    + "核对该行及最近查询后改写剩余步骤，勿重放已经完成的动作。"
                    + "动作与失败反馈完整记录；显式输出的查询反馈展开一次，完全相同的文字引用回执编号，data与脚本读值不变。"
                    + "mc.try_call(name,args)仅可读取NO_PATH/NO_PROGRESS/PARTIAL/TARGET_BLOCKED/OUT_OF_REACH/TARGET_LOST局部失败，"
                    + "失败后须status details=true核对位置/维度/完整背包，再按新事实选择同一任务内的有限替代动作。"
                    + "权限/缺工具/缺材料/安全/取消/超时/未知失败仍立即停止，不能捕获；不自动重试，所有调用仍共享原预算与无进展帽。"
                    + "return/print只报告阶段结果，不代表主人整项任务完成；用新鲜状态验证",
                    "{\"type\":\"object\",\"properties\":{\"code\":{\"type\":\"string\",\"minLength\":1,\"maxLength\":16384},"
                            + "\"timeout_seconds\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":900},"
                            + "\"max_calls\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":256}},"
                            + "\"required\":[\"code\"],\"additionalProperties\":false}"),
            new ToolSpec("tool_help", "只读查询已注册工具的完整说明、参数Schema和返回字段。names一次1-3项；省略返回能力目录。"
                    + "参数或结果字段不清楚时先读说明并在同一任务复用；Lua内调用的帮助也会展示给模型。",
                    "{\"type\":\"object\",\"properties\":{\"names\":{\"type\":\"array\",\"minItems\":1,\"maxItems\":3,"
                            + "\"items\":{\"type\":\"string\",\"minLength\":1,\"maxLength\":32}}},\"additionalProperties\":false}"),
            new ToolSpec("explore", "独立、通用的换区观察能力，用新鲜status核验实际位移。"
                    + "默认mode=surface只行走；mode=underground按模型选择的direction和target_y分段开阶梯，并每段完整查询targets，r默认8。"
                    + "发现即停供后续工作；mode=return按实际地下路标只行走返回。两者共享任务移动帽，外出预留返程次数，地下累计开路最多128格。"
                    + "地下检查min_health默认10、min_food默认6、reserve_durability默认16及背包容量；不自动合成补给或决定矿种层高。"
                    + "需要具体许可时等待主人及服务端批准后原参数只续发一次；取消停止且不自行返程。"
                    + "调用后按任务需要scan_area/find_resource/inspect等核对；run_program可用自己的条件/有界循环决定继续探索或结束。"
                    + "首次探索默认8步/半径64，可选max_steps=1-16、radius=16-64；与gather共用本任务起点/已查点/累计预算。"
                    + "后续省略参数继承首次值，显式修改拒绝；耗尽后重复调用不会补充，按回执汇报范围与障碍。"
                    + "timeout_seconds为本次一段的时限，默认180、1-180；失败/权限/未知状态/耗尽即停止，取消迟到不续跑。"
                    + "data.state是本段终态新鲜身体/背包，exploration_advanced仅表示实际换区，不证明找到任务目标",
                    "{\"type\":\"object\",\"properties\":{\"max_steps\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":16},"
                            + "\"radius\":{\"type\":\"integer\",\"minimum\":16,\"maximum\":64},"
                            + "\"timeout_seconds\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":180},"
                            + "\"mode\":{\"type\":\"string\",\"enum\":[\"surface\",\"underground\",\"return\"]},"
                            + "\"direction\":{\"type\":\"string\",\"enum\":[\"north\",\"south\",\"east\",\"west\"]},"
                            + "\"target_y\":{\"type\":\"integer\",\"minimum\":-2048,\"maximum\":2047},"
                            + "\"targets\":{\"type\":\"array\",\"minItems\":1,\"maxItems\":8,\"items\":{\"type\":\"string\",\"minLength\":1,\"maxLength\":128}},"
                            + "\"r\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":16},"
                            + "\"min_health\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":20},"
                            + "\"min_food\":{\"type\":\"integer\",\"minimum\":0,\"maximum\":20},"
                            + "\"reserve_durability\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":1024}},\"additionalProperties\":false}"),
            new ToolSpec("gather", "优先用于连续采集数量型物品：一轮调用本地扫描、选工作站位、移动、挖掘、走近拾取并核对背包，"
                    + "无需模型逐格列坐标/每12格重新规划。item是期望入包物品，targets是来源方块ID/#标签（泥土含grass_block/dirt），"
                    + "scope默认task，沿用主人原目标/count/mode，自动登记不可降低的完成条件。"
                    + "为制作/建造等准备材料须scope=subtask，不改最终目标；additional按本次采集开始背包净新增，total是总量。"
                    + "短长任务都可复用同一采集能力；子任务成功不表示整项完成。"
                    + "准备配方材料可仅传count、scope=subtask和material_for={item:配方产物,ingredient:材料分组序号从0起}，"
                    + "省略外层item/targets，默认采直接材料。sources=true仅采一层普通合成转换输入，须先核对实际获取途径；"
                    + "这些候选不是天然资源或掉落证明，烧制途径先查craft的material.processes，不自动选择工艺。"
                    + "count按来源物品的实际件数合计，不是转换产量；来源转换和后续合成须另行查询执行。recipe可固定配方。"
                    + "应先据最新背包装备合适工具；r默认16、最大16，为每个采集区域半径，不挖勘探洞。"
                    + "未找到可工作资源会分页查完，再从已加载落脚样本只行走换区寻找，实际到达后重查继续采集。"
                    + "首次探索默认8步/半径64，可选explore_steps=0-16、explore_radius=16-64。"
                    + "explore_steps=0仅在本次起点附近采集r内目标，不换区且保留原探索账；主动到达新工区后可用此模式复用采集。"
                    + "与explore及后续gather共用任务探索起点/访问记录/累计移动预算，省略参数继承首次值，不能重开采集刷新范围或步数。"
                    + "原地空查询或移动回执本身不算发现资源/入包成果，有限探索耗尽才说明范围与障碍。"
                    + "本地复用真实终态/权限，路线或局部清理需确认时直接展示服务端清单等待主人，批准回执后原参数续发一次。"
                    + "拒绝/超时即停止本段；续发清单再次变化、缺工具/空间、无进展或未知状态交回模型决策，不自行批准。"
                    + "timeout_seconds默认600、最大900，max_blocks默认128、最大256，子调用最多1024。"
                    + "掉落必须走近拾取；矿格数不是成果。返回实际物品进度及阻塞，不保证自然世界已找到钻石",
                    "{\"type\":\"object\",\"properties\":{\"item\":{\"type\":\"string\",\"maxLength\":128},"
                            + "\"targets\":{\"type\":\"array\",\"minItems\":1,\"maxItems\":8,\"items\":{\"type\":\"string\",\"maxLength\":128}},"
                            + "\"count\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":4096},"
                            + "\"mode\":{\"type\":\"string\",\"enum\":[\"additional\",\"total\"]},"
                            + "\"scope\":{\"type\":\"string\",\"enum\":[\"task\",\"subtask\"]},"
                            + "\"material_for\":{\"type\":\"object\",\"properties\":{\"item\":{\"type\":\"string\",\"maxLength\":128},"
                            + "\"ingredient\":{\"type\":\"integer\",\"minimum\":0,\"maximum\":8},\"sources\":{\"type\":\"boolean\"},"
                            + "\"recipe\":{\"type\":\"string\",\"maxLength\":128}},\"required\":[\"item\"],\"additionalProperties\":false},"
                            + "\"r\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":16},"
                            + "\"explore_steps\":{\"type\":\"integer\",\"minimum\":0,\"maximum\":16},"
                            + "\"explore_radius\":{\"type\":\"integer\",\"minimum\":16,\"maximum\":64},"
                            + "\"timeout_seconds\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":900},"
                            + "\"max_blocks\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":256}},"
                            + "\"required\":[\"count\"],\"additionalProperties\":false}"),
            new ToolSpec("set_goal", "数量型物品任务开始时登记本任务完成条件，原始主人的数量/口径不可改写。"
                    + "item是物品ID，count是目标件数；mode=additional指相对任务开始背包净新增，total指背包总量。"
                    + "首次登记后不能换目标或降低数量。完成回复会重新查询实际背包核对，挖掘格数/地上掉落不算入包。"
                    + "多阶段物品目标可首次设lifecycle=long_term，max_runs默认8、最多16；本段回复只触发新鲜事实检查，"
                    + "未完成则继续同一任务，重复事实两段/阶段或原总步数耗尽/受阻/取消即终止。阶段不重置背包基线、权限或探索预算。"
                    + "生命周期和阶段上限首次固定，省略沿用；仅当前任务会话内，不跨退出恢复。"
                    + "交付、建筑等目标不由此验证；无法继续用report_blocked，不能声称已完成",
                    "{\"type\":\"object\",\"properties\":{\"item\":{\"type\":\"string\",\"maxLength\":128},"
                            + "\"count\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":4096},"
                            + "\"mode\":{\"type\":\"string\",\"enum\":[\"additional\",\"total\"]},"
                            + "\"lifecycle\":{\"type\":\"string\",\"enum\":[\"task\",\"long_term\"]},"
                            + "\"max_runs\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":16}},"
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
                    + "全部参数先校验；失败/放置预检未过/缺制作条件/搜索无目标/需确认立即停止，保留已完成回执，不重试。"
                    + "只读任务须所有步骤只读；装料/等待成功不是成品完成，须查询和取出真实产物；取消不熄炉。"
                    + "根据新状态确定真实槽与坐标，不在清单里猜搜索结果或合成后的槽号",
                    "{\"type\":\"object\",\"properties\":{\"steps\":{\"type\":\"array\",\"minItems\":1,\"maxItems\":12,"
                            + "\"items\":{\"type\":\"object\",\"properties\":{\"tool\":{\"type\":\"string\"},"
                            + "\"args\":{\"type\":\"object\"}},\"required\":[\"tool\",\"args\"],\"additionalProperties\":false}},"
                            + "\"timeout_seconds\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":300}},"
                            + "\"required\":[\"steps\"],\"additionalProperties\":false}"),
            new ToolSpec("status", "查看最新位置/生命/饥饿/主手/任务实际进度，以及接地、入水、眼睛没水、岩浆、水中避险与body_defense自保事实；details=true含背包与装备逐槽明细。耗时不等于完成率",
                    "{\"type\":\"object\",\"properties\":{\"details\":{\"type\":\"boolean\"}},\"additionalProperties\":false}"),
            new ToolSpec("build_diff", "只读核对有限建筑目标及材料；不施工、不挖掘、不移动或自动找料。"
                    + "target.anchor为世界锚点，cells的x/y/z为相对坐标；或用fill.from/to闭区间生成线/面/体，二者仅选其一。"
                    + "最多128格、相对各轴±32，无重复格；未指定格不动，air为明确清空要求，清空仍需后续原挖掘许可。"
                    + "rotation默认0，允许0/90/180/270顺时针，坐标和作者状态一起旋转。"
                    + "properties可选[{name,value}]，只匹配明确要求的属性；不忽略已写的朝向/含水等，也不自动替代方块。"
                    + "target_revision按完整规范目标生成，任一非零偏移续页必须带expected_revision。改变目标不补任何任务预算。"
                    + "offset和material_offset独立16格/16种分页；counts及全目标剩余需求每次重新核对，不累加各页观察数。"
                    + "当前页最多96次原版放置预检，共用50ms协作时间帽；未查明/不支持/未知保留缺口，不能误报完成。"
                    + "支持已有普通单格投影类别；多格、流体、双层半砖多次动作及复杂组件留后续卡，绝不直接写状态。"
                    + "需求/可用/本页分配/实际消费分开，已满足格不再要料，只有36槽无额外组件材料可分配。"
                    + "ready仍待真实放置，goal_satisfied仅本次同修订现场条件，不是外层目标已验收。",
                    """
                    {"type":"object","properties":{"target":%s,
                    "offset":{"type":"integer","minimum":0,"maximum":112},
                    "material_offset":{"type":"integer","minimum":0,"maximum":112},
                    "expected_revision":{"type":"string","minLength":64,"maxLength":64}},
                    "required":["target"],"additionalProperties":false}
                    """.formatted(BUILD_TARGET)),
            new ToolSpec("build_site", "登记任务内工地、完整目标与明确进出通道，供所有原挖放和路线共用保护。"
                    + "operation=query只读；begin提供target和2-64格无重复水平相邻世界脚格access_route，必须包含当前脚格，"
                    + "每格空气净空及完整整格地板，非空气目标不得封路，至少一个通道端点须伸出全目标水平包围范围。"
                    + "实际经过格及地板也保护，原路线256条帽/观察缺口时停工。bounds可显式限原范围，默认首次脚格64半径，任务内冻结。"
                    + "同任务只选一份目标与通道，begin重复不重开16轮/128次原生修改尝试和8000节点/32768记录/400ms/2重算导航账。"
                    + "checkpoint带expected_revision按整目标真实状态与站位检重复往返；失败不重开原模型/探索/许可。"
                    + "run_program内mc.build(target,{access_route=...,bounds?,max_rounds?,max_actions?,temporary?})"
                    + "有限组合原查询/只行走工作导航/清障/近身施工；temporary为最多16个明确{x,y,z,item,face?,purpose}工程，"
                    + "purpose为support/workstation。mc.cleanup_temporary(revision)另行有界回收，依赖未解除保留。"
                    + "不自动找料、合成、生成支撑或解依赖；外层建筑目标验收仍另行执行。任务结束不自动清理或跨任务认领。",
                    """
                    {"type":"object","properties":{"operation":{"type":"string","enum":["query","begin","checkpoint"]},
                    "target":%s,"access_route":{"type":"array","minItems":2,"maxItems":64,"items":%s},
                    "bounds":{"type":"object","properties":{"x":{"type":"integer"},"y":{"type":"integer"},"z":{"type":"integer"},
                    "radius":{"type":"integer","minimum":1,"maximum":64}},"required":["x","y","z","radius"],"additionalProperties":false},
                    "expected_revision":{"type":"string","minLength":64,"maxLength":64}},
                    "required":["operation"],"additionalProperties":false}
                    """.formatted(BUILD_TARGET, BUILD_ANCHOR)),
            new ToolSpec("build_nearby", "在原地对build_diff同一target/expected_revision的指定16格页施工。"
                    + "每刻最多一次原版放置，max_actions默认12/最多12；每格重新核材料、接触、目标状态、区域锁及任务控制权。"
                    + "不移动、不挖、不自动找料或补全依赖；支撑随实际放置重新评估，缺口单独报告。"
                    + "400ms累计协作工作帽/每次查询50ms/最多1152次查询预检/200游戏刻，不中断单个原版回调。"
                    + "失败不重放，取消后不再动作；实际扣料与半成品保留，两个游戏刻后重新核全目标。"
                    + "已有满足格不消费；沿用36槽默认组件与B1单格支持范围。ACCEPTED等真终态；本批结束不是整栋完成。"
                    + "Lua按max_actions预扣原子预算，换页/换阶段不补原预算。",
                    """
                    {"type":"object","properties":{"target":%s,
                    "offset":{"type":"integer","minimum":0,"maximum":112},
                    "expected_revision":{"type":"string","minLength":64,"maxLength":64},
                    "max_actions":{"type":"integer","minimum":1,"maximum":12}},
                    "required":["target","expected_revision"],"additionalProperties":false}
                    """.formatted(BUILD_TARGET)),
            new ToolSpec("search_resource", "优先用此只读入口查找资源：一次调用在本地完成同中心全部分页，最多5页、30秒；"
                    + "r默认16，targets为1-8个方块ID/#标签。返回真实覆盖、未知范围及最多16个候选；"
                    + "work=true评估可工作站位。空结果仍不证明未加载处没有资源。不要重复首张页代替完整搜索。"
                    + "每页仍受原采样/工作预算及程序共享原子预算，取消立即停；发现候选后由调用方实际采集",
                    "{\"type\":\"object\",\"properties\":{\"targets\":{\"type\":\"array\",\"minItems\":1,\"maxItems\":8,"
                            + "\"items\":{\"type\":\"string\",\"maxLength\":128}},\"r\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":16},"
                            + "\"work\":{\"type\":\"boolean\"}},\"required\":[\"targets\"],\"additionalProperties\":false}"),
            new ToolSpec("find_resource", "底层单页资源查询；一般发现资源优先search_resource自动查完分页。targets为方块ID或#方块标签，如#minecraft:logs、oak_log、iron_ore。"
                    + "r默认8、最多16；每页采样4096格、最多16候选，work=true在预算内优先露出/可工作资源。"
                    + "harvestable_with_hand=false时先核对/装备工具，不为该目标移动开路。"
                    + "dry_work_safe=false表示流体/重力/未知邻域危险，当前不进行干燥采掘，换工作面。"
                    + "offset默认0；续页须用next_offset并携带原scan_center、同一targets/r，-1为页末；身体移动后从0重查。"
                    + "frontiers=true在末页给exploration_points已加载落脚样本，路径仍须move_to walk_only=true核验。"
                    + "不强制加载、不证明可达/可挖/实际掉落；空结果不等于没有资源，采集宜gather本地分页/有界探索",
                    "{\"type\":\"object\",\"properties\":{\"targets\":{\"type\":\"array\",\"minItems\":1,\"maxItems\":8,"
                            + "\"items\":{\"type\":\"string\",\"maxLength\":128}},\"r\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":16},"
                            + "\"work\":{\"type\":\"boolean\",\"description\":\"附直接工作/有界清理可行性及安全站位，路径可达性仍需move_to\"},"
                            + "\"offset\":{\"type\":\"integer\",\"minimum\":0,\"maximum\":16384,\"multipleOf\":4096},"
                            + "\"scan_center\":{\"type\":\"object\",\"properties\":{\"x\":{\"type\":\"integer\"},\"y\":{\"type\":\"integer\"},"
                            + "\"z\":{\"type\":\"integer\"}},\"required\":[\"x\",\"y\",\"z\"],\"additionalProperties\":false},"
                            + "\"frontiers\":{\"type\":\"boolean\"}},"
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
                            + "\"items_only\":{\"type\":\"boolean\"},\"item\":{\"type\":\"string\",\"maxLength\":128},"
                            + "\"frontiers\":{\"type\":\"boolean\",\"description\":\"附已加载探索落脚样本，不证明可达或目标存在\"}}}"),
            new ToolSpec("craft", "按服务器实际普通合成配方制作背包材料。item 是产物注册ID，"
                    + "count 是至少需要的成品数1-64（默认1），按整次配方可能多产。"
                    + "query=true仅查询配方/材料/整批能否完成，recipe可指定配方ID；不自动递归制作缺料。"
                    + "query成功不等于can_craft=true或已制作，执行后须看crafted_count和背包。"
                    + "query=true可带material_index=0-8返回真实替代物品、单输入合成来源及支持的烧制processes（原料/产量/工位/时长/需燃料）；"
                    + "配方不证明资源天然存在、可采收或当前已具备工位/燃料，按真实条件选途径。"
                    + "2×2可随身合成，3×3需5.5格内工作台；忙时不可执行。失败不消耗材料或丢弃成品，"
                    + "缺料看回执，满背包先存入容器。特殊染色/修复、冶炼/锻造不由此工具执行",
                    "{\"type\":\"object\",\"properties\":{\"item\":{\"type\":\"string\",\"maxLength\":128},"
                            + "\"count\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":64},"
                            + "\"query\":{\"type\":\"boolean\"},\"material_index\":{\"type\":\"integer\",\"minimum\":0,\"maximum\":8},"
                            + "\"recipe\":{\"type\":\"string\",\"maxLength\":128}},\"required\":[\"item\"]}"),
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
                    + "expected_block可填写最新观测的完整方块ID，目标被替换则停止；gather自动携带。"
                    + "工地障碍显式site_revision并带expected_block，只清非成品且有替换材料、无支撑依赖的目标；"
                    + "temporary_cleanup=true回收实际来源仍有效且依赖解除的临时格，不与clear_obstructions组合。"
                    + "clear_obstructions=true可先清理射线上最多3格安全遮挡，clear_limit为0-3剩余清理预算；"
                    + "确认模式需ask_owner批准服务端完整清单，再带authorization_id重发；自主模式复用主人当前权限。"
                    + "按真实进度耗时，原版处理破坏/耐久；返回有界来源、并堆/拾取/损毁结算与当前掉落位置，不直接塞包。"
                    + "走近掉落物会自动拾取，须核对最新背包，挖掉矿块不等于获得物品；失败须核对实际回执。"
                    + "回执以 ACCEPTED: 开头表示已受理、还没挖完——别重发，做完系统会主动报结果",
                    "{\"type\":\"object\",\"properties\":{\"x\":{\"type\":\"integer\"},\"y\":{\"type\":\"integer\"},\"z\":{\"type\":\"integer\"},"
                            + "\"expected_block\":{\"type\":\"string\",\"maxLength\":128},\"clear_obstructions\":{\"type\":\"boolean\"},"
                            + "\"clear_limit\":{\"type\":\"integer\",\"minimum\":0,\"maximum\":3},"
                            + "\"site_revision\":{\"type\":\"string\",\"minLength\":64,\"maxLength\":64},\"temporary_cleanup\":{\"type\":\"boolean\"},"
                            + "\"authorization_id\":{\"type\":\"string\",\"maxLength\":64}},\"required\":[\"x\",\"y\",\"z\"]}"),
            new ToolSpec("attack", "普通近战锁定一个目标；默认原地不追击。approach=true允许在初始脚格锚点6.5格范围内只行走接近，"
                    + "不挖放、不换目标、不自动换工具；不主动拾取掉落，身体原版触碰拾取仍运行。"
                    + "entity_id用scan_area的正整数编号并带target_uuid；或hostile_nearby一次选择原范围内未命名敌对生物。"
                    + "目标移动/暂停沿用原8000节点/32768记录/400ms协作搜索帽及共2次重规划，不每刻重搜。"
                    + "max_hits为挥击上限1-10，默认1，最多20秒；等满冷却与目标受伤恢复后出手。"
                    + "玩家/同伴/宠物/同队目标拒绝；最新服务端观测为自主执行时可直接调用，"
                    + "确认模式的中立或命名目标收到NEED_CONFIRM后才用ask_owner携带服务端authorization_id确认，"
                    + "再用同一实体编号/UUID/max_hits/approach和批准编号调用，原接近锚点随授权保留。剑横扫范围有其他活物时拒绝；"
                    + "穿刺/动能武器和重锤不支持。ACCEPTED只是受理，等终态；挥击次数不等于伤害或击杀，"
                    + "看实际生命/吸收减少及target_dead，失败不自动重投。"
                    + "参数拒绝会给原因码和字段；按回执核对扫描身份/类型/上限，不猜目标或授权",
                    "{\"type\":\"object\",\"properties\":{\"entity_id\":{\"oneOf\":[{\"type\":\"integer\",\"minimum\":1},"
                            + "{\"type\":\"string\",\"enum\":[\"hostile_nearby\"]}]},"
                            + "\"target_uuid\":{\"type\":\"string\",\"minLength\":36,\"maxLength\":36},"
                            + "\"max_hits\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":10},"
                            + "\"approach\":{\"type\":\"boolean\"},"
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
                    + "须实际命中目标可替换轮廓或邻接方块接触面，不能点空气放置；face默认up即下方邻格的顶面，可指定侧面。"
                    + "原版处理准确落位/朝向/多格/组件，水中/可替换格和同种半砖合并由原版判断。"
                    + "选址不确定时先query=true，只读核对可替换/视线/支撑/实体占位；受阻时分别给原目标站位stand_positions和改选目标格alternatives，各最多6个。"
                    + "query.ok仅表示查询完成，执行须检查data.preflight_passed==true；通过仍不保证原版最终接受。"
                    + "查询不转头/挖放/消耗；精确指定目标不能自行改选其他格。"
                    + "temporary_purpose可为support/workstation，仅当前登记工地内稳定无组件单格整方块；实际放置后记录来源，"
                    + "有方块实体工位不作临时工程，不把自放当成以后可任意拆。"
                    + "暂不支持脚手架/告示牌等特殊物品；忙时先等待或取消。失败查看实际消耗和目标变化，勿自动重试",
                    "{\"type\":\"object\",\"properties\":{\"x\":{\"type\":\"integer\"},\"y\":{\"type\":\"integer\"},\"z\":{\"type\":\"integer\"},"
                            + "\"item\":{\"type\":\"string\",\"maxLength\":128},\"query\":{\"type\":\"boolean\"},"
                            + "\"temporary_purpose\":{\"type\":\"string\",\"enum\":[\"support\",\"workstation\"]},\"face\":{\"type\":\"string\","
                            + "\"enum\":[\"up\",\"down\",\"north\",\"south\",\"east\",\"west\"]}},\"required\":[\"x\",\"y\",\"z\",\"item\"]}"),
            new ToolSpec("move_to", "走向目标坐标：会用 DigAStar 规划绕路/挖穿/搭路（≤水平64/垂直32格）。"
                    + "walk_only=true只走路/跳跃/下落，规划/重规划与执行均不挖放，探索使用此模式。"
                    + "return_waypoint可填status/move_to实际路标id，坐标须匹配、exact=true、walk_only=true且保留路标bounds/underground；"
                    + "先核当前支撑再正常导航，不继承许可，不自动连续返回或重开预算。"
                    + "默认到目标相邻一格范围；拾取或精确工作站位用exact=true，要求指定脚格实际站稳。"
                    + "work_target={x,y,z}且exact=true保留工作对象；arrival='work'让寻路选择实际够得着/看得见或可有限清理的工作终点，x/y/z此时填工作对象坐标。"
                    + "放置站位另填place_item=物品ID、face（默认up），须exact=true、arrival=work且x/y/z与work_target相同；"
                    + "从当前材料/真实点击面和原版精确落位选目标格外站位，到站不放置。普通方块/原木/半砖/楼梯/工作台/炉可用，特殊/多格/状态组件待扩展。"
                    + "省略arrival仍精确走指定脚格；两者终态都复核工作条件。underground=true只开有地板的横向阶梯、不垫柱搭桥，避流体/重力块。"
                    + "max_digs=0-128为整段含重规划的挖掘帽；bounds={x,y,z,radius}限制所有路径节点和改动。"
                    + "clear_obstructions=true时可按有界安全清理评估工作站位，移动本身不会额外清理目标射线。"
                    + "place_item不与clear_obstructions=true组合；原放置查询可给原目标stand_positions，不能把alternatives当原目标站位。"
                    + "逐次确认模式下，路线需要改动世界先回 NEED_CONFIRM 附具体清单和authorization_id。"
                    + "用ask_owner携带该编号取得主人明确确认，再用move_to携带相同编号重发。"
                    + "主人已选自主执行时直接按服务端模式执行可行路线；may_alter_terrain布尔不能切换模式或授权。"
                    + "确认模式的重规划新增改动仍须重新确认，自主模式仍复核实际安全/材料/距离。"
                    + "搭路仅使用无额外组件的圆石/深板岩圆石/泥土/下界岩，缺料先准备；开路需合适主手工具。"
                    + "回执以 ACCEPTED: 开头表示已受理、人还在走——别重发（会被 BUSY 挡），做完系统会主动报结果",
                    "{\"type\":\"object\",\"properties\":{\"x\":{\"type\":\"integer\"},\"y\":{\"type\":\"integer\"},\"z\":{\"type\":\"integer\"},\"exact\":{\"type\":\"boolean\"},"
                            + "\"work_target\":{\"type\":\"object\",\"properties\":{\"x\":{\"type\":\"integer\"},\"y\":{\"type\":\"integer\"},\"z\":{\"type\":\"integer\"}},"
                            + "\"required\":[\"x\",\"y\",\"z\"],\"additionalProperties\":false},"
                            + "\"place_item\":{\"type\":\"string\",\"maxLength\":128},"
                            + "\"temporary_purpose\":{\"type\":\"string\",\"enum\":[\"support\",\"workstation\"]},"
                            + "\"face\":{\"type\":\"string\",\"enum\":[\"up\",\"down\",\"north\",\"south\",\"east\",\"west\"]},"
                            + "\"clear_obstructions\":{\"type\":\"boolean\"},"
                            + "\"arrival\":{\"type\":\"string\",\"enum\":[\"work\"]},\"underground\":{\"type\":\"boolean\"},"
                            + "\"max_digs\":{\"type\":\"integer\",\"minimum\":0,\"maximum\":128},"
                            + "\"bounds\":{\"type\":\"object\",\"properties\":{\"x\":{\"type\":\"integer\"},\"y\":{\"type\":\"integer\"},"
                            + "\"z\":{\"type\":\"integer\"},\"radius\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":64}},"
                            + "\"required\":[\"x\",\"y\",\"z\",\"radius\"],\"additionalProperties\":false},"
                            + "\"walk_only\":{\"type\":\"boolean\"},"
                            + "\"return_waypoint\":{\"type\":\"integer\",\"minimum\":1},"
                            + "\"may_alter_terrain\":{\"type\":\"boolean\",\"description\":\"旧兼容字段，不授予权限\"},\"authorization_id\":{\"type\":\"string\",\"maxLength\":64}},\"required\":[\"x\",\"y\",\"z\"]}"),
            new ToolSpec("transfer", "与6.5格内未锁定普通容器存取物品：dir=out取出/in存入，item可选过滤；熔炉类机器改用smelt。"
                    + "遵守槽位、接触面与堆叠限制，忙时先等待或取消。可以部分搬运，回执给实际已搬及留在来源的数量；"
                    + "ok=false也可能已搬一部分，先核对inventory和剩余数量再行动",
                    "{\"type\":\"object\",\"properties\":{\"x\":{\"type\":\"integer\"},\"y\":{\"type\":\"integer\"},\"z\":{\"type\":\"integer\"},"
                            + "\"dir\":{\"type\":\"string\",\"enum\":[\"in\",\"out\"]},\"item\":{\"type\":\"string\",\"maxLength\":128}},\"required\":[\"x\",\"y\",\"z\",\"dir\"]}"),
            new ToolSpec("wait", "原地等待：整数 seconds（1-60秒）或 ticks（1-1200游戏刻）二选一。短拾取延迟用ticks，长工艺用seconds；结束后核对实际结果",
                    "{\"type\":\"object\",\"properties\":{\"seconds\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":60},"
                            + "\"ticks\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":1200}},"
                            + "\"oneOf\":[{\"type\":\"object\",\"properties\":{\"seconds\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":60}},\"required\":[\"seconds\"],\"additionalProperties\":false},"
                            + "{\"type\":\"object\",\"properties\":{\"ticks\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":1200}},\"required\":[\"ticks\"],\"additionalProperties\":false}],\"additionalProperties\":false}"),
            new ToolSpec("ask_owner", "拿不准就问主人（会推送给主人与 neko，等待回复最长 2 分钟；游戏内主人可用 @bot 答 <文本> 回答）。"
                    + "只在方向性决策上用：要不要卖这批货/挖这条洞/用哪个方案。别为琐碎小事滥用",
                    "{\"type\":\"object\",\"properties\":{\"text\":{\"type\":\"string\"},\"authorization_id\":{\"type\":\"string\",\"maxLength\":64}},\"required\":[\"text\"]}"));
}
