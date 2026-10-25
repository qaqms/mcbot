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
            "cells":{"type":"array","minItems":1,"maxItems":16384,"items":%s}},
            "required":["anchor","cells"],"additionalProperties":false},
            {"type":"object","properties":{"anchor":%s,"rotation":{"type":"integer","enum":[0,90,180,270]},"fill":%s},
            "required":["anchor","fill"],"additionalProperties":false},
            {"type":"object","properties":{"anchor":%s,"rotation":{"type":"integer","enum":[0,90,180,270]},
            "regions":{"type":"array","minItems":1,"maxItems":128,"items":%s}},
            "required":["anchor","regions"],"additionalProperties":false},
            {"type":"object","properties":{"instance_id":{"type":"string","minLength":36,"maxLength":36},
            "instance_revision":{"type":"integer","minimum":1,"maximum":1024}},
            "required":["instance_id","instance_revision"],"additionalProperties":false}]}
            """.formatted(BUILD_ANCHOR, BUILD_CELL, BUILD_ANCHOR, BUILD_FILL, BUILD_ANCHOR, BUILD_FILL);

    /** Result fields alongside the input registry; optional mode-specific fields must be checked before use. */
    static String resultGuide(String name) {
        return switch (name) {
            case "consume" -> "ACCEPTED只受理，等一次原生用物真正终态；consumed_count为实际库存观测差额，"
                    + "须ok且observation_known=true并为1才确认一次消费。food/hp及food_before/hp_before为实际读数，"
                    + "不作独占归因。source_after保留原版返还物；hand_restored=false须重查主手。"
                    + "goal_satisfied=false，automatic_resume=false；抢占/取消/超时不重新开始食用，部分效果不重放。";
            case "equip" -> "target省略/mainhand保持选中/整堆交换；head/chest/legs/feet仅补空槽，"
                    + "不替换已有装备。changed/equipped及source_after/worn为实际结果，consumed_count=0；"
                    + "不支持副手或自动升级。";
            case "follow" -> "ACCEPTED只受理，到位休眠仍未终态，等原期限结束或失败；goal_satisfied=false。"
                    + "data.behavior_ended及follow.phase/target_uuid/owner_target为行为事实；in_band仅in_band_known=true时可用，"
                    + "target_present为原目标当前在场，position为实际身体位置。"
                    + "elapsed_ticks含休眠与避险，duration_ticks固定，wake_count/owner_absent_ticks是实际计数。"
                    + "follow.navigation含原累计search_nodes/search_records/search_nanos/replans，walk_only=true；"
                    + "到位、期限结束、当前in_band均不验收外层目标；automatic_resume=false，失败/取消不自动重投。";
            case "build_blueprint" -> "list返回names/next_offset。inspect返回source_revision、size/cells/palette_entries及"
                    + "invalid_cells/invalid_palette_entries/unsupported_cells/payload_cells/entity_count，importable=false不能导入。"
                    + "materials仅设计全量需求，完整现场材料走实例build_diff。import须同source_revision，返回design_id/design_revision；"
                    + "不产生实例或世界动作，goal_satisfied=false；不支持部分不得忽略。";
            case "build_record" -> "data.design_id/design_revision及content_revision是设计修订；设计修改不更新实例。"
                    + "instance_id/instance_revision及target为原build_diff/build_site/build_nearby通用引用；"
                    + "records按16条分页，status为intact/absent/conflict/unknown，action_count为真实动作数。"
                    + "同块替换/重载后来源不可证为conflict，不授权拆除；automatic_resume=false。";
            case "build_site" -> "data.active/task_scoped/target_revision为当前任务工地；scope_limited=false时没有bounds，未限制总半径；"
                    + "access_route_explicit表示是否显式声明通道，passage_observation_complete仅为历史通行记录完整性；"
                    + "checkpoints/edit_attempts及navigation_*累计不重开，temporary含实际来源与cleanup_allowed；"
                    + "body_obstructs_target/body_on_temporary为实际身体状态，relocation是只读局部换位或安全下落建议，"
                    + "仍须原工具执行重验；repairs_pending是临时开路后尚未补回的原图格；"
                    + "登记不改世界或授予许可，return_guaranteed=false；只保护声明通道，不证明全局逃生。";
            case "status" -> "data.x/y/z为脚格整数，dimension为字符串；没有data.position或data.pos。"
                    + "hp/food为数值，on_ground/in_water/eyes_in_water/in_lava为布尔。"
                    + "air_supply/max_air_supply为原版空气读数；body_environment含真实危险、单次避险期限/累计搜索账及结果，"
                    + "environment_active/state/work_safe为概要。environment_work_safe=false时先停工观察；"
                    + "body_maintenance及maintenance_active/state为有限已有库存补给/空盔甲槽维护，"
                    + "saturation/using_item为原生事实；消费不替代最终库存目标。"
                    + "body_id为当前身体实例；alive/body_state/body_lifecycle报告死亡与有限重建，"
                    + "last_death为旧身体死亡/已观察掉落快照，未知掉落不能推断已拾取。"
                    + "BODY_DIED终止旧工作及目标；重建后须明确新任务与新鲜status，不自动续跑。"
                    + "换气/停工不等于潜水或岩浆脱困能力，local_clear不验收原目标，取消不自动恢复。"
                    + "details=true才有data.inventory：selected_slot、slots[{slot,item,count,damage?,max_damage?}]、equipment。"
                    + "slots是Lua从1起的列表，但slot字段是实际0-35槽号；空槽item为空字符串、count=0。"
                    + "drop_changes为本身体近期有界来源摘要，拾取事件不替代当前inventory。"
                    + "body_defense为共用自保状态/实际出手/固定局部范围及威胁来源；defense_active/state与"
                    + "threat_scan_complete为概要，局部拉开不保证甩掉追兵，取消后不自动接旧活。"
                    + "spatial_facts为区块版本；navigation_trail给当前任务实际路标和有限改动，非可返保证。";
            case "inventory" -> "data.selected_slot、storage_size、slots_used；"
                    + "data.slots是[{slot,item,count,damage?,max_damage?,mining_tool?}]，data.equipment是装备列表。"
                    + "mining_tools_known=true表示mining_tool依据真实TOOL组件；它不保证具体方块的采收资格。"
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
            case "scan_area" -> "data.item_targets是[{item,count,x,y,z,target_uuid,stand_positions,approach_positions,"
                    + "can_touch_here,pickup_allowed,pickup_delayed,pickup_delay_ticks,drop_origins}]；"
                    + "drop_origins含recorded_own_count、unattributed_to_body_count及有界parts，不认领历史掉落；"
                    + "stand_positions是现成站位；approach_positions是可能需挖放的触碰候选，不证明可达。"
                    + "两者为整数脚格{x,y,z}样本；拾取用target_uuid填move_to pickup_target_uuid统一寻路，之后核对新鲜背包。"
                    + "一般扫描另有entity_targets、summary_lines、groups、special_blocks；"
                    + "entities/summary_lines/groups/special_blocks是文字列表，不是带坐标的对象。"
                    + "entity_targets条目的entity_id/target_uuid供attack，target_uuid也供follow，不可猜编号或身份。"
                    + "items_only=true不含方块/活物字段；frontiers=true才有exploration_points。"
                    + "没有blocks字段；查指定方块应search_resource，不逐格inspect代替区域搜索。";
            case "find_resource", "search_resource" -> "data.targets是[{block,x,y,z,distance,...}]；"
                    + "work=true时按预算附work_checked/can_work_here/can_clear_here/stand_positions/work_failure/"
                    + "harvestable_with_hand/harvestable_with_inventory/dry_work_safe，缺字段表示未知。"
                    + "有库存采收资格时即使当前主手不合适也可工作，实际挖掘会选工具。"
                    + "mining_tool含harvestable/slot/stack/estimated_seconds/base_speed及preferred_kind/preparation_recommended；"
                    + "时间只估当前状态的单格破坏，批量工作先按真实配方补高效工具，未自动制作。stand_positions是脚格{x,y,z}。"
                    + "no_targets为布尔，unknown为数量，results_truncated/work_truncated表示输出/工作评估截断；"
                    + "find_resource续页看next_offset及scan_center；search_resource给scan_complete/coverage_complete/"
                    + "search_pages。局部空结果不等于没有资源；目标发现不等于可采或入包。";
            case "set_goal" -> "type省略/item保持背包物品目标；building固定target与expected_revision，"
                    + "operation=query单独传入，只读返回registered及原goal（含完整target/expected_revision），不更换条件。"
                    + "Lua的mc.goal()不经远端；已登记建筑直接mc.build()/mc.build_diff()，不重写原图或伪造实例引用。"
                    + "delivery固定container/item/count/mode。世界目标登记须先完整现场读取，失败不登记。"
                    + "additional交付从登记时计，只认本任务显式item过滤的已核实净存入与同容器净增量两者较小值；"
                    + "total只验容器当前总量，不证明由谁存入。取出会扣账，超时/未知搬运不授予交付信用。"
                    + "结束前重新读完整目标，building未知/不支持/不稳定不能完成；"
                    + "超距不能验收，可在原预算内先完成材料阶段再返回。生命周期与目标首次固定，不跨退出恢复。";
            case "inspect_block" -> "data.block是方块ID字符串，container是布尔，slots是[{slot,item,count,...}]，"
                    + "slot_count/offset/next_offset为整数。没有block.name或pos；next_offset=-1为末页。"
                    + "指定item时item_total是单次扫描全部本体槽位的总数，contents_complete声明完整；"
                    + "container_id/container_identity_known绑定原生容器、区块及未失效格见证，替换/重载不可继承。"
                    + "普通方块也返回成功且slots为空；区域搜索用search_resource。";
            case "place_block" -> "query时data.preflight_passed布尔；false时failure是原因字符串，"
                    + "alternatives是改选目标格的最多6个{x,y,z,face}，checked_positions为这些目标检查数；"
                    + "stand_positions是原目标的最多6个整数脚格{x,y,z,feet_y}，不是其他目标格。"
                    + "建议已做有界预检，不必再次遍历邻域，执行前仍重验。"
                    + "native_validation_pending表示原版尚待执行，changed=false/consumed_count=0不是已放置。"
                    + "执行后看changed/consumed_count/placed/x/y/z及placement_contact?，失败可能已有实际效果。";
            case "build_diff" -> "data.target_revision由完整目标内容生成；counts对全部目标分类，remaining不含已满足格。"
                    + "goal_satisfied仅完整稳定现场且全部支持/满足才true，不直接登记或完成外层任务。"
                    + "verify_only=true仅重查全目标，略过当前页工作预检；goal_observation_key只含目标真实状态，用于阶段核对。"
                    + "cells是当前16格明细，next_offset=-1为末页；materials独立material_next_offset续页。"
                    + "手动非零偏移必须带同target的expected_revision=首张data.target_revision；16.0等整数值合法。"
                    + "Lua的mc.build_diff(target)直接返回data对象；大于32格时返回全局统计与有限明细页，"
                    + "按next_offset及同修订调用mc.build_diff(target,revision,offset)续页，不把万格明细全存入Lua或输出。"
                    + "work_scan=true返回最多16个未满足候选，work_cursor/work_next_cursor沿规范格索引续取，"
                    + "候选source_offset供普通16格页重新核查；counts/remaining始终覆盖全目标。"
                    + "page_counts/page_remaining/page_satisfied仅本页，counts/remaining是全目标，二者不是矛盾。"
                    + "料单required为全目标已知未满足格需求下界，available仅36储物槽的无额外组件材料，"
                    + "shortfall是差额；allocated仅当前明细页通过原版预检的份数，未预留，consumed始终0。"
                    + "material_bill_complete=false有未知或不支持缺口，不能把缺行当零需求。"
                    + "not_assessed表示当前页外或工作预算未查，unknown表示现场不完整；"
                    + "ready仅近身状态预检，不证明实际放置。observation_complete/spatial_stable和预算字段必须核对。"
                    + "observation_key供跨页核同一次世界/材料事实；cells.item/material_available供选择有限工作，"
                    + "clear_allowed仅已登记同修订工地的当前安全清障提示，真实挖掘仍重验。"
                    + "clearing_work汇总全目标已观察待清格，tools按preferred_kind分组给cells、estimated_seconds、"
                    + "estimate_complete、preparation_recommended和sample_tool；assessment_complete=false表示建议有缺口。"
                    + "耗时按当前身体与库存估计，不是实际完工时间；建议备工具不改变施工目标或禁止空手。"
                    + "design_review提示未明确的楼梯facing/half及相邻上排在高背后方的疑似反坡，"
                    + "仅建议登记前核对，不禁止装饰或自动改图。progress_observation_key只跟踪目标实际状态和料量，"
                    + "身体移动不单独重开同一施工格的失败记录；work_observation_key另含身体和全库存现场。"
                    + "out_of_reach只表示当前手够不着，应让mc.build或工作导航换站位，不是设计大小限制。"
                    + "cells[].surface给真实feet/feet_y/support/support_block；支撑方块格与脚格不同，不自动改设计。";
            case "build_nearby" -> "ACCEPTED只表示受理，等真终态。action_attempts为本批尝试，placed_blocks/"
                    + "consumed_count为实际结果，placement_results含逐格效果；失败可能已改变世界或扣料。"
                    + "work_scope区分whole_target_nearby自动全目标近身选格/current_page显式页，whole_remaining为整目标剩余；"
                    + "page_satisfied/no_work_here不等于整目标满足，当前站位无工作应换位或处理清障/材料。"
                    + "survey是收尾重查全目标，仍有cells/materials分页；final_verified=false不可验收。"
                    + "batch_finished只表示局部批次结束，goal_satisfied才是同修订全目标现场满足，仍不完成外层set_goal。";
            case "break_block" -> "data.removed表示真实移除；collected_count=0表示挖掘不直接塞包。"
                    + "tool_selection为原许可下真实换手，含source_slot/previous_slot/selected_slot/held/changed；观测和预检不换手。"
                    + "drop_accounting_known表示原生来源观察可用；可用时drop_accounting含ledger_id/origin_id/role/source、"
                    + "spawned_count/live_count/picked_up_self_count/picked_up_other_count/destroyed_count/unresolved_count。"
                    + "accounting_complete仅结算已观察生成物；同步区间外延迟生成不归源，混堆部分转移可变未知。"
                    + "drops仅源格当前可归源物，related_drops为连带/来源不明物；"
                    + "drop_spawns是源格生成样本，须drop_spawns_complete=true才可据未出现判断。"
                    + "clearing_results/clearing_drop_summary与目标分开。拾取累计不替代新鲜背包或最终目标。";
            case "move_to" -> "真正终态data.x/y/z为实际脚格，feet_y为碰撞脚高，arrival为position/work/pickup，"
                    + "采掘工作目标在受理或途中已为空时，TARGET_BLOCKED且target_already_clear=true；"
                    + "重新核对目标再续其他工作，不把目标消失算作本身体移除。放置工作目标不套用此判断。"
                    + "tool_changes为最多16次实际开路换手，tool_changes_truncated为是否截短；已有工具可自动切换，制作仍由模型安排。"
                    + "removed_blocks/placed_blocks为本段实际改动。默认相邻到达不保证触碰拾取，"
                    + "拾取用scan_area.item_targets[].target_uuid填pickup_target_uuid；工作用arrival=work。"
                    + "TARGET_LOST且pickup_target_missing=true、target_uuid匹配时，仅表示原掉落不在场；先核新鲜背包再重扫，不据消失算入包。"
                    + "放置工作可加place_item和face，can_work_here仅当前放置预检，到站未执行放置。"
                    + "remaining_items是开路来源当前在地量，picked_up_self_items/picked_up_other_items/"
                    + "destroyed_drop_items/unresolved_drop_items分别结算，未知不冒充入包。"
                    + "navigation_trail.waypoints最多16项含原起点/近期实际路标{id,x,y,z,feet_y,bounds?,underground}；"
                    + "return_waypoint引用本任务ID，沿用原bounds/underground并exact=true、walk_only=true重新导航，不能据路标保证零编辑可返。"
                    + "失败数据可能仅含已知部分效果，须status details=true重新核对，不假定到了请求格。";
            case "attack" -> "ACCEPTED只表示受理；终态strikes为原版尝试次数，max_hits=0表示不限，"
                    + "observed_health_loss/observed_absorption_loss为实际前后观测，不作独占归因。"
                    + "target_dead仅目标存活事实，达到挥击上限不代表击杀；observation_known=false可能已有未知副作用，不重投。"
                    + "approach仅显式接近时存在，moving/search_nodes/search_records/search_nanos/replans是原累计导航账，"
                    + "walk_only=true，anchor为固定脚格{x,y,z}、radius为本次接近范围；路线到达或停步不代表出手/击杀。";
            case "gather" -> "data.goal_satisfied、actual_progress、scope、mined_blocks、cleared_blocks、"
                    + "primitive_calls、skipped_blocks/skipped_items、pickup_reobservations及last_observation。"
                    + "成果是实际背包增量/总量，mined_blocks或source_observations不是入包数。"
                    + "SOURCE_MISMATCH带mismatched_source实际来源/物品/inventory_counts及新鲜last_observation，交回调整。"
                    + "失败回执保留实际效果与原因，材料subtask默认total，不改变最终目标。";
            case "explore" -> "data.state为新鲜身体/背包，discovery?为搜索结果，movement?为本段移动；"
                    + "exploration_advanced只表示已换观察区；exploration_attempts/exploration_max_steps/"
                    + "exploration_radius/return_steps是原任务范围和返程证据，underground_digs为累计诊断，underground_remaining_digs为单路线挖掘上限。";
            case "smelt" -> "data.input/fuel/output是{item,count,...}；state、cook_progress_ticks/cook_total_ticks、"
                    + "burn_remaining_ticks、fuel_sufficient_for_input、suggested_wait_seconds描述机器。"
                    + "实际装取看loaded_input_count/loaded_fuel_count/taken_count；装料不表示产物已完成。";
            case "tool_help" -> "有names时data.definitions是[{name,description,parameters,returns}]；"
                    + "省略names时data.tools是[{name,summary}]，data.skills为技能索引。"
                    + "单独传技能目录name可读data.body/page/pages/revision，续页照next_name。"
                    + "说明自动展示，无须tostring表；同revision且上下文仍有正文时复用。";
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
                    + "code最多16384字节；默认不限总时长和调用次数，不必设置timeout_seconds/max_calls。"
                    + "仅需要主动缩短本次程序时才指定正数；0或省略均不限。正数max_calls同时约束API与嵌套原子调用。"
                    + "查询大表留在局部变量，只返回所需事实。"
                    + "计算/表/字符串/输出都有独立预算，动作仍走任务权限、身体互斥与真实终态。"
                    + "成功查询只记录调用编号，全文供程序读取；需要模型判断的事实须显式return/print。"
                    + "tool_help说明始终展示给模型；负预检保留有界原因。运行错误带program_error.kind/line，"
                    + "核对该行及最近查询后改写剩余步骤，勿重放已经完成的动作。"
                    + "动作与失败反馈完整记录；显式输出的查询反馈展开一次，完全相同的文字引用回执编号，data与脚本读值不变。"
                    + "mc.try_call(name,args)仅可读取NO_PATH/NO_PROGRESS/PARTIAL/TARGET_BLOCKED/OUT_OF_REACH/TARGET_LOST局部失败，"
                    + "失败后须status details=true核对位置/维度/完整背包，再按新事实选择同一任务内的有限替代动作。"
                    + "权限/缺工具/缺材料/安全/取消/超时/未知失败仍立即停止，不能捕获；不自动重试。重复和空搜索不按次数拦截，由程序据真实结果选择。"
                    + "return/print只报告阶段结果，不代表主人整项任务完成；用新鲜状态验证",
                    "{\"type\":\"object\",\"properties\":{\"code\":{\"type\":\"string\",\"minLength\":1,\"maxLength\":16384},"
                            + "\"timeout_seconds\":{\"type\":\"integer\",\"minimum\":0,\"maximum\":900},"
                            + "\"max_calls\":{\"type\":\"integer\",\"minimum\":0,\"maximum\":2147483647}},"
                            + "\"required\":[\"code\"],\"additionalProperties\":false}"),
            new ToolSpec("tool_help", "只读查询已注册工具的完整说明、参数Schema和返回字段。names一次1-3项；省略返回能力目录。"
                    + "也可单独传技能目录name按需读一页正文，续页照next_name；技能仅作知识，不授予权限。"
                    + "参数或结果字段不清楚时先读说明并在同一任务复用；Lua内调用的帮助也会展示给模型。",
                    "{\"type\":\"object\",\"properties\":{\"names\":{\"type\":\"array\",\"minItems\":1,\"maxItems\":3,"
                            + "\"items\":{\"type\":\"string\",\"minLength\":1,\"maxLength\":32}}},\"additionalProperties\":false}"),
            new ToolSpec("explore", "独立、通用的换区观察能力，用新鲜status核验实际位移。"
                    + "默认mode=surface只行走；mode=underground按模型选择的direction和target_y分段开阶梯，并每段完整查询targets，r默认8。"
                    + "发现即停供后续工作；mode=return按实际地下路标只行走返回。显式有限移动额度时外出预留返程次数；地下与单路线默认无挖掘次数帽，仍受实际权限/工具及搜索资源保护。"
                    + "地下检查min_health默认10、min_food默认6、reserve_durability默认16及背包容量；不自动合成补给或决定矿种层高。"
                    + "需要具体许可时等待主人及服务端批准后原参数只续发一次；取消停止且不自行返程。"
                    + "调用后按任务需要scan_area/find_resource/inspect等核对；run_program可用自己的条件/有界循环决定继续探索或结束。"
                    + "探索默认不限总移动次数和总半径；max_steps可显式限制次数，radius省略/0不限总范围；与gather共享已查点。"
                    + "后续省略参数继承首次值，显式修改拒绝；耗尽后重复调用不会补充，按回执汇报范围与障碍。"
                    + "timeout_seconds默认0不限总时长，可选0-180；失败/权限/未知状态交回模型，取消迟到不续跑。"
                    + "data.state是本段终态新鲜身体/背包，exploration_advanced仅表示实际换区，不证明找到任务目标。"
                    + "地下data.supply给selected_remaining/reserve_durability/selected_dig_capacity、inventory_dig_capacity及durable_spares；"
                    + "新鲜背包带mining_tools_known时按可用采掘工具开路，逐格选有采收资格且保留耐久的工具；"
                    + "inventory_dig_capacity只是工具中最大剩余额度，不保证全部矿种可采。须按配方补给后继续原目标；一次局部失败不等于整体受阻",
                    "{\"type\":\"object\",\"properties\":{\"max_steps\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":2147483647},"
                            + "\"radius\":{\"type\":\"integer\",\"minimum\":0,\"maximum\":2147483647},"
                            + "\"timeout_seconds\":{\"type\":\"integer\",\"minimum\":0,\"maximum\":180},"
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
                    + "为制作/建造等准备材料须scope=subtask，不改最终目标，默认mode=total复用已有材料；"
                    + "明确需要再收集才用additional，按本次采集开始背包净新增；task省略mode仍为additional。"
                    + "短长任务都可复用同一采集能力；子任务成功不表示整项完成。"
                    + "准备配方材料可仅传count、scope=subtask和material_for={item:配方产物,ingredient:材料分组序号从0起}，"
                    + "省略外层item/targets，默认采直接材料。sources=true仅采一层普通合成转换输入，须先核对实际获取途径；"
                    + "这些候选不是天然资源或掉落证明，烧制途径先查craft的material.processes，不自动选择工艺。"
                    + "count按来源物品的实际件数合计，不是转换产量；来源转换和后续合成须另行查询执行。recipe可固定配方。"
                    + "应先据最新背包装备合适工具；r默认16、最大16，为每个采集区域半径，不挖勘探洞。"
                    + "未找到可工作资源会分页查完，再从已加载落脚样本只行走换区寻找，实际到达后重查继续采集。"
                    + "探索默认不限总移动次数和总半径，可选正整数explore_steps主动限制；explore_radius省略/0不限总范围。"
                    + "explore_steps=0仅在本次起点附近采集r内目标，不换区且保留原探索账；主动到达新工区后可用此模式复用采集。"
                    + "与explore及后续gather共用任务探索起点/访问记录/累计移动预算，省略参数继承首次值，不能重开采集刷新范围或步数。"
                    + "原地空查询或移动回执本身不算发现资源/入包成果，有限探索耗尽才说明范围与障碍。"
                    + "本地复用真实终态/权限，路线或局部清理需确认时直接展示服务端清单等待主人，批准回执后原参数续发一次。"
                    + "拒绝/超时即停止本段；续发清单再次变化、缺工具/空间、无进展或未知状态交回模型决策，不自行批准。"
                    + "默认不限采集总时长、挖掘总数及子调用次数；可显式timeout_seconds=0-900或正整数max_blocks主动限制。"
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
                            + "\"explore_steps\":{\"type\":\"integer\",\"minimum\":0,\"maximum\":2147483647},"
                            + "\"explore_radius\":{\"type\":\"integer\",\"minimum\":0,\"maximum\":2147483647},"
                            + "\"timeout_seconds\":{\"type\":\"integer\",\"minimum\":0,\"maximum\":900},"
                            + "\"max_blocks\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":2147483647}},"
                            + "\"required\":[\"count\"],\"additionalProperties\":false}"),
            new ToolSpec("set_goal", "登记本任务不可降低的最终条件，type省略或item保持物品接口。"
                    + "单独传operation=query只读取回当前原登记条件，不重设；建筑登记后run_program直接return mc.build()，无需再生成target。"
                    + "item是物品ID，count是目标件数；mode=additional指相对任务开始背包净新增，total指背包总量。"
                    + "type=building须target和build_diff读到的expected_revision，完整目标同修订独立验收。"
                    + "type=delivery须container={x,y,z}及item/count/mode，先靠近登记完整容器基线；"
                    + "additional仅登记后本任务已核实净存入且容器净新增，双向transfer须明确item过滤；total为容器当前总量。"
                    + "首次登记后不能换目标或降低数量。完成回复会重新查询实际背包核对，挖掘格数/地上掉落不算入包。"
                    + "多阶段目标可首次设lifecycle=long_term，默认不限模型轮数和阶段次数。"
                    + "max_runs仅在希望主动限制时指定正数，0或省略不限；本段回复触发新鲜事实检查，未完成则继续同一任务。"
                    + "重复事实不按次数终止；阶段不重置背包基线、权限或探索记录。"
                    + "生命周期和阶段上限首次固定，省略沿用；仅当前任务会话内，不跨退出恢复。"
                    + "建筑局部批次/持续跟随结束不等于整项完成；无法继续用report_blocked",
                    "{\"type\":\"object\",\"properties\":{\"operation\":{\"type\":\"string\",\"enum\":[\"query\"]},"
                            + "\"type\":{\"type\":\"string\",\"enum\":[\"item\",\"building\",\"delivery\"]},"
                            + "\"item\":{\"type\":\"string\",\"maxLength\":128},"
                            + "\"count\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":4096},"
                            + "\"mode\":{\"type\":\"string\",\"enum\":[\"additional\",\"total\"]},"
                            + "\"target\":" + BUILD_TARGET + ",\"expected_revision\":{\"type\":\"string\",\"minLength\":64,\"maxLength\":64},"
                            + "\"container\":" + BUILD_ANCHOR + ","
                            + "\"lifecycle\":{\"type\":\"string\",\"enum\":[\"task\",\"long_term\"]},"
                            + "\"max_runs\":{\"type\":\"integer\",\"minimum\":0,\"maximum\":2147483647}},"
                            + "\"additionalProperties\":false}"),
            new ToolSpec("report_blocked", "确实无法继续时停止当前任务并报告实际障碍，保留已完成动作与物品。"
                    + "缺工具/耐久/材料先核对真实配方、备用工具、补给及原路返回等替代途径，自主补缺后继续原目标；一次局部NO_PATH不证明整体受阻。"
                    + "不用于临时思考或让主人逐步操作；取消后不会续跑。目标未满足按失败/受阻结束，不能写成完成",
                    "{\"type\":\"object\",\"properties\":{\"reason\":{\"type\":\"string\",\"minLength\":1,\"maxLength\":512}},"
                            + "\"required\":[\"reason\"],\"additionalProperties\":false}"),
            new ToolSpec("workflow", "将已确认的近身采集/放置/制作/烧制组合成1-12个明确步骤，一轮执行，逐步等待真实终态。"
                    + "steps每项为tool与args，支持status/inventory/equip/consume/scan_area/find_resource/inspect_block/"
                    + "break_block/place_block/craft/smelt/wait；不支持移动/攻击/存取/拾取/反问/嵌套。"
                    + "timeout_seconds默认0不限、显式正数最多300；制作count、装料input_count/fuel_count、取出count必须明确填写，"
                    + "没有额外累计请求件数额度；每步保留原工具参数范围和真实物品检查。"
                    + "全部参数先校验；失败/放置预检未过/缺制作条件/搜索无目标/需确认立即停止，保留已完成回执，不重试。"
                    + "只读任务须所有步骤只读；装料/等待成功不是成品完成，须查询和取出真实产物；取消不熄炉。"
                    + "根据新状态确定真实槽与坐标，不在清单里猜搜索结果或合成后的槽号",
                    "{\"type\":\"object\",\"properties\":{\"steps\":{\"type\":\"array\",\"minItems\":1,\"maxItems\":12,"
                            + "\"items\":{\"type\":\"object\",\"properties\":{\"tool\":{\"type\":\"string\"},"
                            + "\"args\":{\"type\":\"object\"}},\"required\":[\"tool\",\"args\"],\"additionalProperties\":false}},"
                            + "\"timeout_seconds\":{\"type\":\"integer\",\"minimum\":0,\"maximum\":300}},"
                            + "\"required\":[\"steps\"],\"additionalProperties\":false}"),
            new ToolSpec("status", "查看最新位置/生命/饥饿/主手/任务实际进度，以及接地、入水、眼睛没水、岩浆、水中避险与body_defense自保事实；details=true含背包与装备逐槽明细。耗时不等于完成率",
                    "{\"type\":\"object\",\"properties\":{\"details\":{\"type\":\"boolean\"}},\"additionalProperties\":false}"),
            new ToolSpec("build_blueprint", "读取当前世界mcbot/blueprints/<name>.nbt的原版gzip结构格式，只转换数据，绝不直接写世界。"
                    + "operation=list只读目录，每16项分页offset；inspect(name)只读尺寸、状态/材料和拒绝计数，不创建目录。"
                    + "import(name,source_revision,design_id)明确将全部支持的同一文件快照存为B4设计，需数据修改许可；"
                    + "再用build_record实例化、同build_diff/build_site/mc.build原动作施工，不自动执行或找料。"
                    + "名称1-48小写字母/数字/下划线/连字符，首位字母/数字，不带扩展名；禁止路径/符号链接。"
                    + "只支持size、palette、blocks与空entities的结构；调色板Name/Properties逐项进B1注册表/作者状态，不忽略属性。"
                    + "显式air是清空要求，缺省格不碰；非法/不支持格、方块实体载荷或任何实体使整份不可导入，不导入部分图假完成。"
                    + "不接容器内容、战利品、命令、点击文本、可执行载荷或物品组件。原版双格门须完整两半，"
                    + "两半明确half/facing/hinge且其他属性要求一致；床须完整part/facing两格，其他家具/装饰范围同build_diff。"
                    + "两格对象均保留验收，仅主格动作/一件料。此文件格式仍有独立128显式格与字节限制，不等于内联regions容量。"
                    + "压缩64KiB/解压128KiB/NBT计费256KiB/深16/解析200ms协作帽，目录至多128项、调色板64项，"
                    + "各轴1-33/体积4096/显式格1-128；保留原施工/模型/探索/范围/许可与取消预算。",
                    """
                    {"type":"object","properties":{"operation":{"type":"string","enum":["list","inspect","import"]},
                    "name":{"type":"string","minLength":1,"maxLength":48,"pattern":"^[a-z0-9][a-z0-9_-]{0,47}$"},
                    "source_revision":{"type":"string","minLength":64,"maxLength":64},
                    "design_id":{"type":"string","minLength":1,"maxLength":48,"pattern":"^[a-z0-9][a-z0-9_-]{0,47}$"},
                    "offset":{"type":"integer","minimum":0,"maximum":112}},
                    "required":["operation"],"additionalProperties":false}
                    """),
            new ToolSpec("build_record", "保存可复用设计与世界内建筑实例，只有数据、不含脚本；不执行世界动作或自动恢复目标。"
                    + "design_create(design_id,definition)创建；definition为cells/fill/regions之一，相对±32、最多16384格。"
                    + "regions为最多128个fill区域，后写覆盖前写同格；用区域表达墙面、开口、屋面和室内，避免展开列表超出传输载荷。"
                    + "design_patch(design_id,design_revision,edits)逐步修改最多16格，remove=true删除，其他项为完整cell；同坐标不能重复。"
                    + "design_query(design_id,design_revision,offset?,layer?)按16格/切层查询，design_list列设计。"
                    + "instance_create(design_id,design_revision,anchor,rotation?)冻结副本；同设计可多落点，世界/维度/主人/身体隔离，实例不能重叠。"
                    + "instance_query(instance_id,instance_revision,offset?)查询来源；instance_list列当前身体/维度实例。"
                    + "instance_revise(instance_id,instance_revision,design_revision)显式应用同设计新修订，只能在新任务未登记工地时进行。"
                    + "返回target={instance_id,instance_revision}直接交原build_diff/build_site/build_nearby及mc.build，仍需原通道/范围/许可。"
                    + "设计修订不清失败账或补模型/探索/动作账。逐真实动作落盘，取消保留半成品；旧格拆除须有效逐格见证和真实依赖检查。"
                    + "同块替换、缺钩子、卸载和重进后来源不可证均保守冲突；已满足的他人方块不认领。"
                    + "每世界最多32设计/32实例、32MiB实际记录；每实例最多16384来源格，逐格事件容量为max(256,目标格数×4)。双格对象一次动作预留两条记录，"
                    + "主格记消费、次格记0，不重复计价；无淘汰换额，读写失败停修改。",
                    """
                    {"type":"object","properties":{"operation":{"type":"string","enum":["design_create","design_patch","design_query","design_list",
                    "instance_create","instance_query","instance_revise","instance_list"]},
                    "design_id":{"type":"string","minLength":1,"maxLength":48,"pattern":"^[a-z0-9][a-z0-9_-]{0,47}$"},
                    "design_revision":{"type":"integer","minimum":1,"maximum":1024},
                    "definition":{"oneOf":[{"type":"object","properties":{"cells":{"type":"array","minItems":1,"maxItems":16384,"items":%s}},
                    "required":["cells"],"additionalProperties":false},{"type":"object","properties":{"fill":%s},
                    "required":["fill"],"additionalProperties":false},{"type":"object","properties":{
                    "regions":{"type":"array","minItems":1,"maxItems":128,"items":%s}},
                    "required":["regions"],"additionalProperties":false}]},
                    "edits":{"type":"array","minItems":1,"maxItems":16,"items":{"oneOf":[%s,
                    {"type":"object","properties":{"x":{"type":"integer","minimum":-32,"maximum":32},"y":{"type":"integer","minimum":-32,"maximum":32},
                    "z":{"type":"integer","minimum":-32,"maximum":32},"remove":{"type":"boolean","enum":[true]}},
                    "required":["x","y","z","remove"],"additionalProperties":false}]}},
                    "instance_id":{"type":"string","minLength":36,"maxLength":36},
                    "instance_revision":{"type":"integer","minimum":1,"maximum":1024},
                    "anchor":%s,"rotation":{"type":"integer","enum":[0,90,180,270]},
                    "offset":{"type":"integer","minimum":0,"maximum":16368},"layer":{"type":"integer","minimum":-32,"maximum":32}},
                    "required":["operation"],"additionalProperties":false}
                    """.formatted(BUILD_CELL,BUILD_FILL,BUILD_FILL,BUILD_CELL,BUILD_ANCHOR)),
            new ToolSpec("build_diff", "只读核对有限建筑目标及材料；不施工、不挖掘、不移动或自动找料。"
                    + "target.anchor为世界锚点，cells的x/y/z为相对坐标；或用fill闭区间、regions区域列表，三者仅选其一。"
                    + "最多16384唯一格、相对各轴±32；cells不许重复，regions后写覆盖前写，最多128区域及65536次展开写入。"
                    + "大设计用紧凑regions或实例引用；传输大小仍受原保护。未指定格不动，air为明确清空要求。"
                    + "rotation默认0，允许0/90/180/270顺时针，坐标和作者状态一起旋转。"
                    + "properties可选[{name,value}]，只匹配明确要求的属性；不忽略已写的朝向/含水等，也不自动替代方块。"
                    + "target_revision按完整规范目标生成，任一非零偏移续页必须带expected_revision。改变目标不补任何任务预算。"
                    + "run_program可直接local d=mc.build_diff(target)取得全局统计；大于32格仅带一页明细，"
                    + "细查用mc.build_diff(target,d.target_revision,d.next_offset)，施工直接mc.build，不逐页攒完整大表。"
                    + "work_scan=true按work_cursor取最多16个未满足候选，work_next_cursor=-1为候选末尾；source_offset是原明细页。"
                    + "offset和material_offset独立16格/16种分页；counts及全目标剩余需求每次重新核对，不累加各页观察数。"
                    + "当前页最多96次原版放置预检，共用50ms协作时间帽；未查明/不支持/未知保留缺口，不能误报完成。"
                    + "支持普通单格、玻璃、楼梯/半砖、工作台、空单箱/木桶、灯笼/火把、活板门、栅栏/墙及地毯；属性须原版可放出。"
                    + "双格门明确half/facing/hinge；床明确part/facing且含完整foot/head，其他属性要求一致；"
                    + "只从门下半或床foot动作/一件料，次格paired_pending仍验收。不能覆盖另一半或合并邻箱，不搬家具内容。"
                    + "其他多格、流体、双层半砖多次动作及复杂组件仍不支持，绝不直接写状态。"
                    + "需求/可用/本页分配/实际消费分开，已满足格不再要料，只有36槽无额外组件材料可分配。"
                    + "ready仍待真实放置，goal_satisfied仅本次同修订现场条件，不是外层目标已验收。",
                    """
                    {"type":"object","properties":{"target":%s,
                    "offset":{"type":"integer","minimum":0,"maximum":16368},
                    "material_offset":{"type":"integer","minimum":0,"maximum":16368},
                    "work_scan":{"type":"boolean"},"work_cursor":{"type":"integer","minimum":0,"maximum":16383},
                    "expected_revision":{"type":"string","minLength":64,"maxLength":64},"verify_only":{"type":"boolean"}},
                    "required":["target"],"additionalProperties":false}
                    """.formatted(BUILD_TARGET)),
            new ToolSpec("build_site", "登记任务内完整施工目标，供原挖放和导航共用保护。"
                    + "operation=query只读；begin提供target即可，expected_revision可选且须匹配；无需手写进出通道。"
                    + "可选access_route为2-64格无重复相邻脚格，允许可通行坡地/半砖/草花，含当前脚格；"
                    + "按真实碰撞/支撑逐段核对，非空气目标不得封住显式通道，至少一端伸出全目标水平包围范围。"
                    + "bounds省略不设工地总半径，显式范围保留且任务内冻结。实际路线缺口仍不可冒充退路证明。"
                    + "同任务只选一份目标与通道，施工轮数和原生修改尝试只作累计诊断，不按次数或重复事实禁用；寻路按4000节点批次续搜，32768记录是增量整理水位，不是默认停止额度；无默认整趟节点、记录、总时间或累计重算次数帽。"
                    + "checkpoint带expected_revision复核整目标真实状态与站位；重复只作诊断，不停止后续派发，失败不重开许可。"
                    + "run_program内mc.build(target)或mc.build(target,{access_route?,bounds?,max_rounds?,max_actions?,temporary?})"
                    + "组合差异/工作导航/清障/近身施工，先准备低层障碍，局部站位失败后按新鲜差异尝试其他工作格；导航按原权限自动挖放，max_rounds默认0不限，可显式指定正数；temporary为最多16个明确{x,y,z,item,face?,purpose}工程，"
                    + "purpose为support/workstation。mc.cleanup_temporary(revision)另行有界回收，依赖未解除保留。"
                    + "mc.build直接返回{status,goal_satisfied,placed_blocks,cleared_blocks,consumed_count,remaining,blocked,counts,unresolved}，"
                    + "不包在data里，并自动输出施工结果；脚本执行成功不代表建筑完成。默认批次12动作，不主动加短max_rounds拆断连续施工。"
                    + "身体挡格时先按只读relocation换位，出口受阻时可按原动作临时开路、搭支撑或确认安全落点后逐格下落。"
                    + "同修订普通施工格可在有补料、无占位/设施/依赖时暂拆，repair_pending保留原图缺口，其他工作之后再补回。"
                    + "不自动找料合成；需要升高才能够到目标时，导航可按真实库存、头顶净空和点击面连续起跳垫柱，不必逐格手写脚手架。额外工位用temporary指定。"
                    + "完成后从高到低重查并回收本任务临时工程，站在临时柱上可安全逐格拆降并等待落稳；temporary_retained仍有缺口，不跨任务认领；外层完整目标另验。",
                    """
                    {"type":"object","properties":{"operation":{"type":"string","enum":["query","begin","checkpoint"]},
                    "target":%s,"access_route":{"type":"array","minItems":2,"maxItems":64,"items":%s},
                    "bounds":{"type":"object","properties":{"x":{"type":"integer"},"y":{"type":"integer"},"z":{"type":"integer"},
                    "radius":{"type":"integer","minimum":1,"maximum":2147483647}},"required":["x","y","z","radius"],"additionalProperties":false},
                    "expected_revision":{"type":"string","minLength":64,"maxLength":64}},
                    "required":["operation"],"additionalProperties":false}
                    """.formatted(BUILD_TARGET, BUILD_ANCHOR)),
            new ToolSpec("build_nearby", "在原地对build_diff同一target/expected_revision施工；省略offset自动查全目标中的近身可做格，显式offset限定16格页。"
                    + "每刻最多一次原版放置，max_actions默认12/最多12；每格重新核材料、接触、目标状态、区域锁及任务控制权。"
                    + "不移动、不挖、不自动找料或补全依赖；支撑随实际放置重新评估，缺口单独报告。"
                    + "400ms累计协作工作帽/每次查询50ms/200游戏刻；自动模式直接筛选全目标近身候选，不逐刻等待所有空页，不中断单个原版回调。"
                    + "失败不重放，取消后不再动作；实际扣料与半成品保留，两个游戏刻后重新核全目标。"
                    + "已有满足格不消费；沿用36槽默认组件及build_diff支持范围。双格对象仅主格一次动作/消费一件，"
                    + "include_repairs默认true；false在原料分配和候选筛选前延后临时拆开的待补格，mc.build完成其他工作后再恢复。"
                    + "两半逐格记录；任一半未知/保护/占位不得动作。ACCEPTED等真终态；本批结束不是整栋完成。"
                    + "Lua按max_actions预扣原子预算，换页/换阶段不补原预算。",
                    """
                    {"type":"object","properties":{"target":%s,
                    "offset":{"type":"integer","minimum":0,"maximum":16368},
                    "expected_revision":{"type":"string","minLength":64,"maxLength":64},
                    "max_actions":{"type":"integer","minimum":1,"maximum":12},
                    "include_repairs":{"type":"boolean"}},
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
                    + "harvestable_with_inventory=false时先补可用采收工具，不为该目标移动开路；"
                    + "mining_tool给已有最优工具与单格耗时/专用工具建议，挖掘可自动换手，观测不换手。"
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
                    + "可选item另给全部本体槽位总数与容器身份，用于交付验收；不从单页猜总量。"
                    + "不打开GUI、不搬物品、不合并双箱；机器计时用smelt query",
                    "{\"type\":\"object\",\"properties\":{\"x\":{\"type\":\"integer\"},\"y\":{\"type\":\"integer\"},\"z\":{\"type\":\"integer\"},"
                            + "\"offset\":{\"type\":\"integer\",\"minimum\":0,\"maximum\":1023},"
                            + "\"item\":{\"type\":\"string\",\"minLength\":1,\"maxLength\":128}},\"required\":[\"x\",\"y\",\"z\"],\"additionalProperties\":false}"),
            new ToolSpec("inventory", "查看完整背包逐槽物品ID/数量/耐久、主手选中槽及装备栏。"
                    + "槽0-8是快捷栏，9-35是背包；切换工具或准备材料前先查看，未列出的背包槽为空",
                    "{\"type\":\"object\",\"properties\":{}}"),
            new ToolSpec("equip", "将 inventory 中指定槽的物品切换到主手。"
                    + "槽0-8直接选中；槽9-35与当前主手槽交换，原主手留在来源槽。"
                    + "target省略/mainhand保持原行为；head/chest/legs/feet仅穿戴空的原版盔甲槽，"
                    + "原版槽不接受则拒绝；不卸装/升级或切副手，忙时等待终态或取消",
                    "{\"type\":\"object\",\"properties\":{\"slot\":{\"type\":\"integer\",\"minimum\":0,\"maximum\":35},"
                            + "\"target\":{\"type\":\"string\",\"enum\":[\"mainhand\",\"head\",\"chest\",\"legs\",\"feet\"]}},"
                            + "\"required\":[\"slot\"],\"additionalProperties\":false}"),
            new ToolSpec("consume", "从实际slot消费一件有营养的食物，复用原版持续用物和食物/效果/返还物。"
                    + "只支持原生用时1-100刻的食物，固定总帽120刻；不会连吃、找料、制作或直接回血。"
                    + "ACCEPTED等真终态，消费和food/hp为真实观测，goal_satisfied=false。"
                    + "临时持食后只在原位置未被外部改动时恢复原主手；手部/许可变动、抢占或取消立即停，不自动重吃",
                    "{\"type\":\"object\",\"properties\":{\"slot\":{\"type\":\"integer\",\"minimum\":0,\"maximum\":35}},"
                            + "\"required\":[\"slot\"],\"additionalProperties\":false}"),
            new ToolSpec("scan_area", "环顾四周，返回附近实体与可行动方块分层摘要，绝对坐标；entity_id/target_uuid供attack，target_uuid也供follow，不可猜编号或身份。"
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
            new ToolSpec("break_block", "挖一格干燥方块，执行时从真实背包选有采收资格的最快工具，同速优先不消耗耐久；不自动制作工具。"
                    + "expected_block可填写最新观测的完整方块ID，目标被替换则停止；gather自动携带。"
                    + "工地障碍显式site_revision并带expected_block，可同时clear_obstructions；"
                    + "目标与每个遮挡分别核原图补料、占位、设施、依赖和来源，普通施工格可临时开路后恢复；"
                    + "temporary_cleanup=true回收实际来源仍有效且依赖解除的临时格，不与clear_obstructions组合。"
                    + "clear_obstructions=true按具体清单清理工作射线上的安全遮挡，默认无清理格数上限；"
                    + "需要主动限制时填写非负clear_limit，0禁止清理。射线计算资源、实际距离及原权限仍受保护；"
                    + "确认模式需ask_owner批准服务端完整清单，再带authorization_id重发；自主模式复用主人当前权限。"
                    + "按真实进度耗时，原版处理破坏/耐久；返回有界来源、并堆/拾取/损毁结算与当前掉落位置，不直接塞包。"
                    + "走近掉落物会自动拾取，须核对最新背包，挖掉矿块不等于获得物品；失败须核对实际回执。"
                    + "回执以 ACCEPTED: 开头表示已受理、还没挖完——别重发，做完系统会主动报结果",
                    "{\"type\":\"object\",\"properties\":{\"x\":{\"type\":\"integer\"},\"y\":{\"type\":\"integer\"},\"z\":{\"type\":\"integer\"},"
                            + "\"expected_block\":{\"type\":\"string\",\"maxLength\":128},\"clear_obstructions\":{\"type\":\"boolean\"},"
                            + "\"clear_limit\":{\"type\":\"integer\",\"minimum\":0,\"maximum\":2147483647},"
                            + "\"site_revision\":{\"type\":\"string\",\"minLength\":64,\"maxLength\":64},\"temporary_cleanup\":{\"type\":\"boolean\"},"
                            + "\"authorization_id\":{\"type\":\"string\",\"maxLength\":64}},\"required\":[\"x\",\"y\",\"z\"]}"),
            new ToolSpec("follow", "有界持续步行跟随，省略target_uuid跟主人；指定时用scan_area的标准小写UUID，不跟自身或自动换目标。"
                    + "distance=2-6默认3，seconds=1-60默认30；到位休眠不结束、不惊动模型，距离超过distance+2才唤醒。"
                    + "只用原PathTask走路，不挖放/传送/攻击/自动换工具；起始脚格32格固定球，工地内收为原范围的保守内接球。"
                    + "搜索按4000节点批次续搜，状态记录增量整理，没有默认整趟节点、记录或总时间额度；换路/醒来/避险没有默认累计重规划次数帽，已有工地沿用其导航账户。"
                    + "主人暂离休眠等至原期限，指定实体死亡/卸载/离维度立即TARGET_LOST；无路/未知/范围/许可失败立即交回。"
                    + "原期限包含休眠/避险，期限结束仅持续行为结束，goal_satisfied=false，当前距离另查in_band。"
                    + "同参数局部失败仅记录诊断，不按失败次数拒发；程序中mc.try_call读取局部失败后仍须新鲜完整status才可再动作。"
                    + "ACCEPTED等真终态，取消不恢复，不以重发补范围/探索/模型或许可预算。",
                    "{\"type\":\"object\",\"properties\":{\"target_uuid\":{\"type\":\"string\",\"minLength\":36,\"maxLength\":36},"
                            + "\"distance\":{\"type\":\"integer\",\"minimum\":2,\"maximum\":6},"
                            + "\"seconds\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":60}},\"additionalProperties\":false}"),
            new ToolSpec("attack", "普通近战锁定一个目标；默认原地不追击。approach=true允许在初始脚格锚点64格范围内只行走接近，"
                    + "不挖放、不换目标、不自动换工具；不主动拾取掉落，身体原版触碰拾取仍运行。"
                    + "entity_id用scan_area的正整数编号并带target_uuid；或hostile_nearby一次选择原范围内未命名敌对生物。"
                    + "目标移动时保留仍有效的路线，搜索按4000节点批次续搜，状态记录增量整理，无默认整趟节点、记录、总时间或累计重规划次数帽。"
                    + "默认持续攻击至该目标死亡、失效、实际受阻或主人取消，不设总时长及挥击次数上限。"
                    + "max_hits省略或0表示不限；仅主人明确要求有限挥击时填写正整数。等满冷却与目标受伤恢复后出手。"
                    + "玩家/同伴/宠物/同队目标拒绝；最新服务端观测为自主执行时可直接调用，"
                    + "确认模式的中立或命名目标收到NEED_CONFIRM后才用ask_owner携带服务端authorization_id确认，"
                    + "再用同一实体编号/UUID/max_hits/approach和批准编号调用，原接近锚点随授权保留。"
                    + "普通安全近战可先inventory/equip选合适的剑；剑并非禁用。"
                    + "仅当本次原版攻击会横扫其他未选中目标时拒绝，远处目标仍可先接近；"
                    + "遇UNSAFE_SWEEP按当前旁及风险换非横扫工具或等分开，不要把一次拒绝概括成不能用剑。"
                    + "穿刺/动能武器和重锤不支持。ACCEPTED只是受理，等终态；挥击次数不等于伤害或击杀，"
                    + "看实际生命/吸收减少及target_dead，失败不自动重投。"
                    + "参数拒绝会给原因码和字段；按回执核对扫描身份/类型/上限，不猜目标或授权",
                    "{\"type\":\"object\",\"properties\":{\"entity_id\":{\"oneOf\":[{\"type\":\"integer\",\"minimum\":1},"
                            + "{\"type\":\"string\",\"enum\":[\"hostile_nearby\"]}]},"
                            + "\"target_uuid\":{\"type\":\"string\",\"minLength\":36,\"maxLength\":36},"
                            + "\"max_hits\":{\"type\":\"integer\",\"minimum\":0,\"maximum\":2147483647},"
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
            new ToolSpec("move_to", "走向已加载的目标坐标：规划绕路/挖穿/搭路，长路线分段并按实际落点自动接续。"
                    + "walk_only=true只走路/跳跃/下落，规划/重规划与执行均不挖放，探索使用此模式。"
                    + "return_waypoint可填status/move_to实际路标id，坐标须匹配、exact=true、walk_only=true且保留路标bounds/underground；"
                    + "先核当前支撑再正常导航，不继承许可，不自动连续返回或重开预算。"
                    + "普通坐标默认到目标相邻一格范围；指定脚格用exact=true，要求实际站稳。"
                    + "work_target={x,y,z}且exact=true保留工作对象；arrival='work'让寻路选择实际够得着/看得见或可有限清理的工作终点，x/y/z此时填工作对象坐标。"
                    + "放置站位另填place_item=物品ID，face省略自动比较真实可点击的六面，显式face只用该面；须exact=true、arrival=work且x/y/z与work_target相同；"
                    + "从当前材料/真实点击面和原版精确落位选目标格外站位，到站不放置。普通方块/原木/半砖/楼梯/工作台/炉可用，特殊/多格/状态组件待扩展。"
                    + "普通导航可在已知整块支撑上连续起跳垫柱，身体完全离开放置格后才放块，落稳再续行；手距和实际点击面仍须同时满足。"
                    + "省略arrival仍精确走指定脚格；两者终态都复核工作条件。underground=true走相邻横向阶梯，可用真实材料和点击面补支撑，不原地直挖或垫柱，避流体/重力块。"
                    + "拾取填pickup_target_uuid及该物品的取整坐标，以身体实际可触碰的任意落点到达；"
                    + "物品移位复核，消失不当作入包，仍核对背包。不能与work_target/arrival/place_item/return_waypoint组合。"
                    + "单批节点用完保留边界续搜或执行可返回的安全前缀，状态记录按水位整理；默认没有整趟节点或记录停止额度。"
                    + "默认无总时长及累计挖掘次数上限；仅需限制开路时填写非负max_digs，0禁止挖掘。"
                    + "reserve_durability默认0，0-1024；规划与每格实际选工具均保留该耐久，不能借无采收资格的备用工具续挖。"
                    + "bounds={x,y,z,radius}限制所有路径节点和改动；逐次搜索资源及实际卡住保护保留。"
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
                            + "\"pickup_target_uuid\":{\"type\":\"string\",\"minLength\":36,\"maxLength\":36},"
                            + "\"arrival\":{\"type\":\"string\",\"enum\":[\"work\"]},\"underground\":{\"type\":\"boolean\"},"
                            + "\"max_digs\":{\"type\":\"integer\",\"minimum\":0,\"maximum\":2147483647},"
                            + "\"reserve_durability\":{\"type\":\"integer\",\"minimum\":0,\"maximum\":1024},"
                            + "\"bounds\":{\"type\":\"object\",\"properties\":{\"x\":{\"type\":\"integer\"},\"y\":{\"type\":\"integer\"},"
                            + "\"z\":{\"type\":\"integer\"},\"radius\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":2147483647}},"
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
                            + "\"minProperties\":1,\"maxProperties\":1,\"additionalProperties\":false}"),
            new ToolSpec("ask_owner", "拿不准就问主人（会推送给主人与 neko，等待回复最长 2 分钟；游戏内主人可用 @bot 答 <文本> 回答）。"
                    + "只在方向性决策上用：要不要卖这批货/挖这条洞/用哪个方案。别为琐碎小事滥用",
                    "{\"type\":\"object\",\"properties\":{\"text\":{\"type\":\"string\"},\"authorization_id\":{\"type\":\"string\",\"maxLength\":64}},\"required\":[\"text\"]}"));
}
