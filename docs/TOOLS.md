# 工具契约与手册

> 服务端白名单（`ToolRegistry`）是真源；`ClientToolDefs` 只是给模型看的描述。
> 两者不同步时以服务端为准——未注册的工具会被闸③以 `DENIED:未知工具` 拒掉。

## 当前执行限制（2026-10-23）

攻击/挖掘后续覆盖见[ACTION-LIMITS-AND-GATHER](ACTION-LIMITS-AND-GATHER-2026-10-23.md)。
attack默认max_hits=0持续，attack/break_block/move_to无默认总时限；
cap_ticks=-1贯通服务端及客户端等待，取消及有限批准仍有效。
max_digs和clear_limit省略不限次数，非负显式值有效，0禁止相应挖掘/清障。
gather材料subtask默认total复用现有库存，additional须明确指定；
完整真实掉落错源后新鲜核验，未满足则SOURCE_MISMATCH交回模型，精确目标不放宽。
维护者追加按参考核对保护：权限/取消/节点资源参考均有，故保留；
当前每次4000节点/32768记录，无默认400ms搜索总时间门，仍分片且支持取消。

以[NAVIGATION-AND-EXECUTION-POLICY](NAVIGATION-AND-EXECUTION-POLICY-2026-10-23.md)覆盖下方历史额度。
仍31工具：run_program默认timeout_seconds/max_calls=0不限；长期max_runs默认0不限，
默认模型轮数、重复五次/空搜三次/拒绝假完成三次门取消。正数可主动限制单次程序/阶段。
gather默认不限时间/挖掘/探索次数，explore_steps=0仍关闭换区；explore省略max_steps不限。
workflow省略时间不限，无128件总额度；仍为1–12个明确步骤载荷。
mc.build的max_rounds默认0不限；工地轮数/编辑数、地下累计挖掘仅诊断。
每次导航4000节点/32768记录，无默认搜索总时间或累计两次重规划门；
move_to终态/观察有search_mode，后台结果有worker_search_ms，与主线程path_timing分列。
显式approach范围64格，原目标UUID/实际出手距离/批准绑定保留，被动自保仍近场。
权限、取消、身体控制、未知状态、来源/返程存储和单次动作保护保留。
局部失败交回模型/程序判断，不阻止后续新调用；真实最终目标仍须现场验收。

## P1 首阶段诊断（2026-10-23，历史检查点）

工具数与参数Schema不变。move_to普通/中断终态data增加path_timing，报告本路线单调时间、
互斥阶段及输入/节点计数；不是进度百分比或预算，空输入也可能是必要工作。
原出站上限不足时可省略此诊断，完整计时仍在path-perf日志，真实动作证据及结果不因此缩减。
attack终态approach及follow原navigation观察增加plans/retargets/in_range_stops/
route_arrivals/route_failures/budget_stops，原搜索和换位账保持。
数字entity_id仍必须配同一扫描条目的target_uuid，只有hostile_nearby选择器不带UUID；
search_resource仍targets=1–8、r=1–16。常驻短说明已补这些前提，不自动修参或重投。
完整口径见P1-PERFORMANCE-TIMELINE-2026-10-23，P1仍部分完成。

## K1 技能知识读取（2026-10-23）

技能正文不再全量进入系统提示，目录和任务快照说明见
[K1 技能与开销](K1-SKILL-KNOWLEDGE-2026-10-23.md)。
仍为31工具，原参数Schema不变。`tool_help {}`增加`data.skills`目录；
单独用`names:[目录name]`读取一页技能，续页照`next_name`。
普通工具帮助仍一次1–3项；技能仅为知识，不授予许可或证明动作/目标完成。
旧顶层`.md`继续支持，可选`defaults/`同名覆盖及`index.json`描述/禁用，
新快照仅在任务实际开始生效；整理后可从常驻索引重新读。下方全量技能为历史口径。

## L2 主人明确恢复（2026-10-17）

仍31项模型工具，无新增模型恢复工具。原任务输入完整发送`目标状态`、`恢复目标`或`放弃目标`，
仅空闲时处理；前两者先新鲜查身份，放弃先确定当前范围，均不把命令作为新模型指令。
恢复用原goal_id和新task_id，先查原目标/新权限；原基线、40步/阶段/失败/探索/工地账不重开。
status增加world_id/owner_uuid/companion_uuid/body_continuity_id；body_id仍每身体实例改变。
内部status recovery=true及task_begin的恢复载荷仅供本体接线，不加入模型Schema或外部桥必填字段。
退出/重进不自动恢复；在途/未知/身份或修订变化、旧容器身份失效拒绝，不回放Lua/动作/批准。
仅完整long_term检查点可恢复，单条512 KiB/128范围、空间16000 bytes，
原生保存/断线/多人仍待实机；详见L2-GOAL-RECOVERY。下方无L2为历史检查点。

## L1 多类目标与完整现场（2026-10-17，历史检查点）

仍31工具（22服务端+9本地）。set_goal的type省略/item沿原item/count/mode背包规则；
building须同build_diff的target与expected_revision；delivery须container={x,y,z}及item/count/mode。
世界目标登记先新鲜身体/完整现场，失败则终止，成功后不可换类型/修订/口径/基线或阶段帽。
building同BuildSurvey验全部目标，build_diff verify_only=true略过工作预检而保留完整稳定扫描；
goal_observation_key只含目标真实方块及明确属性，页尾/局部批次/耗材不替代整项目标。
inspect_block可选item给最多1024槽全本体item_total/contents_complete及有见证的容器身份；
分页只影响明细，原距离/锁/战利品/加载门保持，不拼双箱或全存储网络。
delivery additional取登记后同容器净新增与本任务已核实净存入的较小值，双向transfer须item过滤，
取出扣账，失败的已知部分效果保留；在途/未知/未过滤搬运不靠重登记补信用。
total只验当前指定容器总量，不证明由谁存入。未知/换箱/换身体/修订不完成；
仅明确OUT_OF_REACH可按原账继续有限材料阶段，返回原容器再核验。
三类同原task/long_term、总步帽与阶段账，取消/死亡不续，follow终态不代验外层目标；
桥v1.0不变，未登记普通作答仍是原语义，无L2跨退出恢复。
完整输入示例、参考/限制及原生待验见L1-GOAL-VERIFICATION；下方未接建筑/交付按历史阅读。

## S3 身体实例与死亡终态（2026-10-17）

S3不新增游戏工具。status的body_id为当前身体实例，alive/body_state/body_lifecycle报告
生命/清理/原生死亡/有界重建账，完整inventory/equipment仍是当前真实库存。
last_death中的observed_drops来自F2实际接受生成账；重建后snapshot=true为旧事实，
不代表当前掉落位置/可拾取状态，更不代表已入包；all_death_drops_proven恒false。
死亡使活动/排队任务failed（BODY_DIED），包括长期阶段、Lua/采集和持续行为；
try_call不可吞死亡继续。重建不发新模型请求，须明确新任务并重新status才工作。
内部task_begin/end、authorize、cancel及动作带冻结body_id；外部桥v1.0入口不增加必填字段。
当前有限重建最多8次，未知0血/坏元数据/耗尽/部分失败停用，无自动重试或修档。
完整支持与边界见S3-DEATH-RECONSTRUCTION。下方旧工具数量按对应历史阶段阅读。

## S1 共用环境事实与停工（2026-10-17）

工具仍30项。status的air_supply/max_air_supply为原生推进后的身体空气读数，
body_environment含active/state、hazard/work_safe、600刻原期限、calm_ticks、episodes及
water原锚点/累计探针/搜索CPU；environment_active/environment_state/environment_work_safe为概要。
water_recovery兼容字段是最后水中执行器状态，不单独表示正在游。
没顶触发原有限换气；岩浆、火、窒息、细雪、世界底部/未知保守停工，无自动挖放/传送。
原生空气回满且真实安全20刻才归还，原任务恢复仍须原onResume/许可/期限。
超过原范围/搜索/租约/期限不重开，取消后不自动接旧活；局部local_clear不验收外层目标。
直接动作、跨刻调度、材料/整任务采集、地下前进/返程及Lua读取同安全事实；
ENVIRONMENT_UNSAFE不能吞或靠换阶段刷新预算，查询仍可用。完整边界见S1-ENVIRONMENT-RECOVERY。

## D3 有界持续跟随（2026-10-17）

新增follow，当前30工具（21服务端+9本地）。仅可选target_uuid、distance整数2-6、
seconds整数1-60，默认主人/3格/30秒。只行走、固定初始32格球，已有工地内保守收缩。
到位休眠不发终态，distance+2迟滞唤醒；原UUID、范围、租约、导航账与期限含避险不重开。
主人暂不在且原任务仍有效才等待；指定实体丢失、无路、范围或许可失败立即交回。
ACCEPTED等真终态；behavior_ended只表示持续行为结束，goal_satisfied=false，不验收外层目标。
in_band仅in_band_known=true时可用；取消终态也重查当前距离，automatic_resume=false。
status.task.progress沿原观察报告阶段/固定范围/计数与导航账，休眠身体IDLE而槽仍busy。
直接/Lua/长期阶段同原入口、原子预算与失败账；具体参考取舍和边界见D3-CONTINUOUS-FOLLOW。
下方工具数量按历史检查点阅读。

## B5 双格门续卡（2026-10-17）

普通原版DoorBlock门现在可经同build_blueprint/build_record/build_diff/build_site/build_nearby/
mc.build施工：必须明确完整两半的half/facing/hinge，其他明确属性要求一致。
保留两格验收，下半发一次原版动作/消费一件，上半paired_pending不单独施工；两格须空气。
两格保护/占位/范围/来源共用，拆改须当前实例活来源且依赖解除；异常/取消保留部分效果。
床/装饰/组件与其他格式仍不支持，B5未整卡完成；详细规则见B5-STRUCTURE-BLUEPRINTS。

## B5 首格式历史检查点（2026-10-17）

新增build_blueprint，当前29项（20服务端+9本地）。list/inspect只读当前世界
mcbot/blueprints合法名称.nbt，inspect给source_revision、尺寸/格数及非法/不支持/载荷/实体计数。
import明确name/source_revision/design_id且需原数据修改许可；全部支持才存B4设计，
再经原build_record实例化、build_diff/工地/Lua及原动作，不立即施工或完成目标。
预算与Tag类型严格有界；未知状态、多格/特殊/组件/载荷/实体不能静默忽略或创建部分设计。
只支持原版gzip结构.nbt和B1已有单格/状态，多格/装饰及其他格式为B5后续续卡。
完整边界、字段、参考链和待验见B5-STRUCTURE-BLUEPRINTS，原模型/探索/动作账及许可不补。

## B4 设计与实例（2026-10-17）

新增build_record，当前28项（19服务端+9本地），详见B4-BUILDING-RECORDS。
design_create/patch/query/list保存相对目标，patch须明确design_revision、最多16项；
query可layer及16格分页。instance_create冻结设计快照/落点/朝向，query/list查看真实来源，
instance_revise在未登记工地的新调用中明确设计修订，目标不重叠。
target={instance_id,instance_revision}复用原build_diff/build_site/build_nearby和mc.build；
登记同实例工地后才能通过引用施工，原expected_revision仍是全目标内容哈希。
records.status区分intact/absent/conflict/unknown，action_count为真实变化次数；
同块替换/区块重载/保存重进不恢复拆除权，recording_known=false后停修改。
设计更新不无声改实例，不自动恢复已取消任务，不补原模型/探索/动作预算或许可。
旧文工具数量按当时检查点阅读。

## D2 身体自保事实（2026-10-17）

工具仍26项。身体可信威胁按刻触发，共用原attack与move_to执行核，不另增工具/模型往返。
只读不触发，保护/命名/中立来源不自动还击。status.body_defense报告固定来源/范围、
状态、实际出手/观察伤害、暂停及累计账；defense_active/defense_state/threat_scan_complete
概要同步Lua。local_clear只表示连续20刻局部安全，不保证甩掉追兵或整项目标完成。
2026-10-23已去掉400刻/10次门，6格局部节点球交原工作范围仍有效。
近期来源最多64条滚动去重，不因来源记录满而永久停用。
cancel停止自动自保至新task_begin，新任务不清旧来源账。原显式工具仍走原许可。
完整机制、边界与实机待验见D2-BODY-DEFENSE-2026-10-17.md。

## D1 动态近战（2026-10-17）

attack新增严格布尔approach，默认false保持原地；true在固定初始脚格锚点64格内
只行走接近同一实体，不挖放或换目标，2026-10-23已取消默认次数与总时限门。
授权绑定模式与原锚点，原地许可不能升级；移动前与出手前都核保护/世界/任务及实际几何。
迟滞、原累计导航账、真实效果及未覆盖见D1-DYNAMIC-MELEE，字段详见§2.7；
工具仍26项，不另加依赖规划器或持续自卫触发。

## B2 近身施工（2026-10-17）

新增B3 build_site，当前27项（18服务端+9本地）。query严格只读；begin提供B1 target、
2-64格水平明确通道和可选bounds，完整目标/实际经过格与支撑贯通规划、执行保护。
范围、16轮checkpoint/128次改动尝试、16条临时来源和原导航账不重开，登记不授予挖放许可。
run_program内mc.build(target,{access_route=...,bounds?,max_rounds?,max_actions?,temporary?})
有限组合原差异/只行走工作导航/清障/近身施工，最后独立核全目标；目标已满足不搭临时工程。
原mc.try_call门不变，未知、权限、安全、预算与取消停止；failure_kind为桥回执的有限原因码。
place_block及放置工作move_to可带temporary_purpose=support/workstation；
工地路线实际垫块同源记录，须实际消费/落地后才有来源。只支持稳定无组件单格整块，
临时工位不含方块实体。break_block的site_revision/expected_block显式选工地清障，
temporary_cleanup=true另选回收，逐刻复核确切状态、区块/逐格见证、依赖及原许可。
mc.cleanup_temporary(revision)只回收当前可拆记录，保留承重/通道/未知或被替换部分；
取消不自动拆，task_scoped事实不作为B4永久实例或L1外层建筑目标验收。
完整用法与保守限制见docs/B3-MULTI-STANCE-CONSTRUCTION-2026-10-17.md。

下方B2检查点：新增build_nearby，当时26项（17服务端+9本地）。输入同build_diff的target，
必带expected_revision；offset为当前16格页，max_actions默认/最多12，可显式1-12。
不移动/挖掘/找料，逐格复用原版放置和新鲜目标/材料/权限/控制/区域锁。
ACCEPTED只受理，真终态给action_attempts/placed_blocks/consumed_count/placement_results，
失败也保留实际效果；两刻后survey独立重查全目标，final_verified及goal_satisfied须分别读，
batch_finished不表示整栋完成或外层set_goal已完成。
12查询轮/1152面预检，执行另≤12次预检；工作协作400ms/查询50ms/终查另50ms、总200刻。
Lua按max_actions从原原子预算预扣，不够不派发，取消/缺料/无动作不退预算。
有界邻域效果/输出省略、支持范围和实机边界见B2-NEARBY-CONSTRUCTION。

## B1 建筑差异与材料（2026-10-17）

新增build_diff只读入口，当前25项工具（16服务端、9本地）。
target含anchor世界坐标、rotation默认0，以及cells或fill恰一个；
cells相对x/y/z及block，properties可选[{name,value}]；
fill.from/to为闭区间，退化表达线/面，或有界实体块。128格、相对各轴±32、
每格最多8属性，真实注册表校验，不接NBT/组件/实体载荷或重复格。
未指定格不动，air明确要求清空；查询不挖放或自动找料。

旋转0/90/180/270绕锚点，坐标与作者属性一起转；未写属性不要求，已写属性不忽略。
target_revision按完整规范目标生成；非零offset/material_offset须expected_revision一致。
cells每16格按next_offset续页，materials每16种按material_next_offset独立续页，
二者-1表示末页。counts/remaining每次对全目标重新读，不累加不同查询页的观察数。
当前页外的工作候选为not_assessed；每页至多96次预检，50ms协作工作帽，不保证原生回调可中断。

counts区分satisfied/ready/missing_material/needs_clear/needs_support/out_of_reach/protected/
unknown/unsupported/state_conflict/blocked/not_assessed/out_of_scope；
materials[].required是已知未满足格需求下界，available仅36槽默认组件材料，
allocated只对本页通过预检格有限分配且未预留，consumed=0。
material_bill_complete=false不能据缺项判零需求；unknown不是空格，
unsupported即使已存在也不默认为本体支持验收。
goal_satisfied只在全目标已知、支持、真实状态匹配且版本稳定时true；
并不登记或完成外层set_goal。ready尚待执行，B2才接有限施工，
查询不延长或重开原模型/探索/范围/许可/动作账。
详情、示例与原版/受控边界见B1-BUILD-TARGETS-2026-10-17.md。

## N3 地形与返程事实（2026-10-17）

status.spatial_facts含dimension及chunks映射；值为不透明版本串或null（未知/未加载）。
find_resource附同源版本及spatial_stable，完整分页期间变更会停止，不自动重开。
版本只说明已观察地形，不证明可达、可采或许可；未知不能补失败尝试。
status/move_to的navigation_trail含有界实际waypoints、feet_y、原bounds/underground、
actual_edits及省略/缺口/截断；return_guaranteed=false、requires_fresh_navigation=true。
move_to可提供return_waypoint正整数ID，必须同时exact=true、walk_only=true，
坐标与当前任务路标一致且保留原bounds/underground，不带工作目标。
失效支撑/脚高、旧任务/身体或范围变化拒绝，仍按原导航与预算重新检查途中路线。
不是回放命令，也不保证整段无编辑返回。细则见N3-SPATIAL-FACTS及STATUS。

## F2 来源与去向（2026-10-16）

break_block不再凭附近新实体认领产物。drop_accounting_known声明原生观察是否可用，
drop_accounting含ledger_id/origin_id、role/source/created_tick、spawned_count/live_count、
picked_up_self_count/picked_up_other_count/destroyed_count/unresolved_count及accounting_complete。
role为target/clearing/path_clear；同操作中未经源格确认的连带物不进入目标产出样本。
drops只列源格当前可归源份额，related_drops单列连带现物，drop_spawns为源格生成样本；
drop_spawns_complete=false时不可根据缺项判断来源错配。collected_count仍为0，
挖掘不直接塞包；拾取累计不能代替新鲜库存。清障的来源在clearing_results/clearing_drop_summary。

move_to的remaining_items为当前可归源在地量，分别报告本身体/其他实体拾取、损毁与未知；
status.drop_changes最多4来源摘要，scan_area.item_targets[].drop_origins报告本身体可证份额，
unattributed_to_body_count不按附近或物品类型自动认领。混堆部分转移可使整份来源未知，
卸载/失踪不当拾取，未知不等于零；区间外延迟生成不作归源。
短工具、Lua、采集与长期阶段共用，取消保留实际效果；原预算/权限不变。
数量/明细帽、源格覆盖及原生待验边界见F2-DROP-ACCOUNTING-2026-10-16及STATUS。

## N2b 放置工作导航（2026-10-16）

place_block query=true在受阻时分别返回stand_positions（原目标站位，整数x/y/z和feet_y）
与alternatives（改选目标格，x/y/z/face），各最多6个。不得把后者当原目标的站位。
checked_positions仍只计最多75个目标格检查；站位另用45格/最多24次工作检查。
查询不移动或消费，候选不是可达保证，执行仍重新核对。

move_to可在exact=true、arrival=work、work_target下增加place_item=物品ID和face（默认up）；
外层坐标须等于work_target，不与clear_obstructions=true组合。
寻路选择目标格外且满足真实面/落位预检的站位；终态work_kind=place、
can_work_here及placement_preflight是当前事实，尚未放置，随后仍须原place_block动作。
支持投影的类别为原版普通方块/原木/半砖/楼梯/工作台/炉，拒绝状态组件和多格/特殊类别。
到站前现场变化可失败并保留实际移动效果；失败/取消不自动放置或续投。
路线垫块同样检查本步清障之后的实际可执行点击前提。
目标邻域有未落地计划挖放、实际站位仍被占用时保守拒绝，尚不支持探身/搭柱。
导航是修改工具，query字段不授予只读能力；原范围/时间/材料/许可/探索账保持。
细则见N2B-PLACEMENT-WORK-2026-10-16.md及STATUS。

## N2a 实际脚高（2026-10-16）

move_to输入仍是整数脚格；x/y/z表示实际脚高所在格，碰撞浮点误差统一处理。
终态另给feet_y实际高度，工作/拾取stand_positions可附同字段；不要把它作为小数y回填。
半砖、雪层、楼梯站位/工作眼睛/触碰及路线复核同源，站稳必须满足实际高度。
源支撑与目标支撑不会为路线自动拆掉，未知/动态复杂形状保守拒绝，
同格内掉落也会重验旧路线，原最多两次重规划和挖放/材料/许可/总预算保持。
没有改变拾取延迟/归属/背包核验，实际入包仍由原版playerTouch决定；
持续行为触发与边沿探身尚待后续卡；放置到达见上方N2b的有限边界。
证据与边界见STATUS和N2A-STANDING-GEOMETRY。

## F1 身体区间观察（2026-10-16）

status追加data.body_changes，修改动作终态追加data.body_delta及同源feedback。
body_id/from_seq/through_seq标识观察区间；net为开始至结束净变，
carried_items为真实库存总量变化，equipment另外描述，不能把主手重复加总。
equipment_breaks仅含原版确认损坏；空槽或卸装不代表损坏。
history_truncated/breaks_truncated/carried_items_truncated及*_omitted显式说明缺失；
每份差量最多4096字节。observation_known=false表示观察不完整，不能解释为无副作用。
same_world=false时不跨世界计算net；status近期各最多4条，动作损坏最多8条。
差量只是观察，不证明本动作因果、掉落来源、入包成果或最终目标满足，
重复游标不能重复累计，换槽/受伤或重复差量不刷新失败账。
取消/部分失败/异常保留已发生效果；超大回执含receipt_truncated及有限效果，
先核对状态/现场，不重投修改。完整边界见F1-BODY-FACTS-2026-10-16与STATUS。

## C1 身体控制与等待（2026-10-16）

同步修改与跨刻工作均在同一身体控制门执行；避险持有身体时新修改返回BUSY，观察仍可用。
既有工作暂停不发新终态，归还先重验原任务和动作条件；取消后不自动续接。
status.task增加body_owner（REFLEX/WORK/IDLE）、suspended及execution_ticks；
elapsed_ticks为包括暂停的总刻数，cap_ticks仍是原总帽，暂停不延长客户端/程序预算。
默认未定义恢复钩子的动作以INTERRUPTED停止；已有部分效果保留，不据暂停推断动作失败可重放。
细则与尚未实现的持续触发入口见C1-BODY-CONTROL-2026-10-16及STATUS。

## N1 工作费用与路线诊断（2026-10-16）

move_to参数、许可和终态前缀保持。有限接近清理估价在原计时切片内运行，
与正式搜索共用8000展开/32768存储/400ms累计预算；只行走不做挖掘预估。
arrival=work比较移动加剩余清障费用，到站仍用活世界验证，未执行清障或目标挖掘。
深埋费用只作加权搜索引导，不保证最短路或以估价值宣布无路。

失败回执可含search_diagnostics：expanded、estimate_expanded、estimate_seed_probes、
stored_nodes、elapsed_ms、budget_kind及rejected_edges/最多8个samples。
复核拒绝另含path_failure：step、reason、from、to及能定位时的cell。
step=-1是未选路线的搜索样本；未知/越界/净空/支撑/挖放帽/工作视线分别记录。
样本仅说明原搜索拒绝过该步，不是全部路径的唯一根因，不触发放宽条件重搜。
原实际挖放/部分效果/取消不变；同入口继续供短任务、长期阶段和后续身体行为复用。
具体机制、参考链路与未测边界见N1-NAVIGATION-COSTS-2026-10-16。

## 工作导航的累计计划地形（2026-10-16第五次测试后）

move_to参数及终态不变，直接调用、Lua与采集/勘探/长期阶段复用同一身体路线。
搜索会按计划已挖/已放的地形判断后续支撑、净空、清理射线及工作到达；
每一步不得挖出发脚下的支撑，已清格及已放支撑不重复计入消耗。
不同地形/累计预算的到达分别保留，不能把成本更低但材料耗尽的路线当唯一候选。
所有计划仍须活世界重建和复核、原具体许可/材料/安全/取消及真实动作结算；
计划挖放不是实际效果，不增加gather成果或最终目标进度。

8000展开/400ms累计/6ms切片及原挖放帽保持，额外32768已创建节点帽限制存储。
存储/搜索预算不足仍是NO_PROGRESS或有实际推进的PARTIAL，不证明确定无路。
计划垫块不自动重挖回收，原实心格挖后不自动回填；任意半砖/坑内触碰、
完整放置接触规划、普通路线返程账仍待后续；费用按上方N1更新。
证据见STATUS与当日日志第五次测试节，下方“没有编辑状态A*”为之前检查点。

## 跨程序重复失败（2026-10-16）

当前任务的move_to/break_block按工具、结构化参数和明确局部失败类别共同记账，
直接调用、Lua、workflow/gather/explore内部原子动作及长期阶段共用。
同类别五次真实失败保留原回执，第六次`REPEATED_FAILURE:`且零派发；
data.failure_memory含reason/failures/limit/entry_limit/dispatched=false。
最多128条，满账`FAILURE_MEMORY_LIMIT:`，不淘汰旧记录补次数，查询与其他能力仍可用。
这些本地拒绝不属于try_call可捕获的路径失败，会停止当前组合，不自动重试。

仅成功details状态的完整36槽/脚格/维度变化，或原子工具的真实挖放/物品转移等效果
允许重评；第一次状态仅建基线，时钟/微移/换槽/血量/耐久、成功空动作、查询和组合
累计actual_progress不清账。受理/进度/重复包/超时取消补账及迟到不记为真实终态。
新任务/身体/会话独立，阶段或换程序不重置，原许可/状态门/总预算不变。
相邻目标/语义等价默认参数、外部地形变化、跨退出及往返旧位置的空间记忆仍缺；
本卡不修底层路径。完整机制审查见`AGENT-MECHANISM-AUDIT-2026-10-16.md`。

## 放置与错误诊断（2026-10-16）

- 第三次测试后的工具目录共24项（15服务端+9本地）。模型常驻稳定简要说明和
  全部原参数Schema；`tool_help {}`返回索引，`{"names":["craft","move_to"]}`返回
  1–3项完整原说明/Schema及`returns`返回字段说明，1024字符输入帽。
  Lua内帮助也始终展示，不必显式print；不执行游戏动作或改变后续目录。
  returns是核对生产输出的说明，不是强制类型Schema，也不是动态减少Schema的懒加载。
- run_program成功查询全文仅供Lua读取，自动反馈记录编号及有界负预检/覆盖/环境标记。
  `can_craft/preflight_passed/no_targets/unknown/unloaded/scan_complete/coverage_complete/
  results_truncated/work_truncated/next_offset/truncated/in_water/eyes_in_water/in_lava/water_recovery`
  等仅在实际data含有时保留，不推测缺失字段。动作和失败仍完整记录。
  第四次测试后，can_craft/preflight_passed=false还保留最多1024字节原因，
  同原因只展示一次；优先读data.failure，缺失则从真实feedback截短。
  craft不可制作时新增data.failure，查询ok仍只表示观察完成。
  显式输出精确feedback会展开一次并引用编号，Lua值和data.returned不变；输出帽仍生效。
- 脚本环境支持变量/表/函数/条件/循环及assert/error/ipairs/pairs/next/tonumber/tostring/type/print；
  不提供string/table/math库、pcall、require、元表或外部访问。
  追加表用`t[#t+1]=v`，查看表直接print/return，不用tostring。
  脚本错误给`data.program_error:{kind,line}`及有界最后查询提示，line=0表示无法定位；
  类别为SYNTAX/FIELD_ACCESS/FUNCTION_CALL/VALUE_TYPE/LUA_RUNTIME/VALUE_OR_DEPTH，
  不输出原异常或源码，修改剩余步骤而不重放已完成动作。
- `mc.call`失败即停。`mc.try_call`仅对move_to/break_block/gather/explore的
  NO_PATH/NO_PROGRESS/PARTIAL/TARGET_BLOCKED/OUT_OF_REACH/TARGET_LOST允许读取失败，
  不自动重发。继续修改前必须`mc.call("status",{details=true})`取得有限数值位置、
  非空维度及inventory.slots，再按新事实选同任务内的有限替代。
  相同无进展局部失败五次停止，插入状态查询不补次数。
  同程序按工具/结构化参数/失败类别累计，不受键顺序、耗时文字或成功空动作刷新；
  完整36槽/位置/维度事实变化或真实成果计数允许重评，时间/槽位重排/耐久不算进展。
  权限/确认、工具/材料、安全/范围、取消/超时及未知错误仍立即停止；
  调用/时间/计算/输出及嵌套原子预算不因恢复重开，不允许扩大原目标或许可。
- 掉落拾取使用新鲜item_targets[].stand_positions与move_to exact=true，再查背包；
  默认相邻到达不能证明触碰。scan的special_blocks/summary_lines/groups/entities为文字列表，
  不是带坐标的方块对象；指定资源用search_resource，inspect.data.block是ID字符串。
- 完整请求达到配置窗口75%且有足够可替换历史时提前整理；autoCompact关闭仍不整理。
  原较低软阈值、完整工具组、近段原文、净节省与失败熔断保持。
  摘要读取既有出站投影，成功后重新查询身体；任务目标/基线/总步帽及探索账独立保留。

- 第二次测试补充：status提供on_ground/in_water/eyes_in_water/in_lava、
  air_supply/max_air_supply及water_recovery，同时写入feedback；空气值仅当前原版读数，
  未启用完整氧气/伤害生命周期。没顶或岩浆使普通挖掘/陆地路线/gather以
  ENVIRONMENT_UNSAFE停止，保留实际部分效果；身体反射另有加载/权限/范围/时间帽。
- find_resource work=true可给dry_work_safe=false，表示六邻水/岩浆/未知或目标/上方
  重力块危险，不建议站位、不为其开路。search_resource排序/gather同步；
  最终BlockMining每刻重验，不把旧查询当安全承诺，不支持排水/水下采掘。
- wait严格仅选seconds整数1-60或ticks整数1-1200，不能同时填或附其他字段。
  成功data给waited_ticks/target_ticks；墙钟时间由服务器刻速决定。
  GroundItems增加pickup_delay_ticks；gather触碰候选等待delay+1（最多20刻），
  无该字段兼容20刻；已知掉落隐藏时最多有限10刻再查，原UUID/无进展/总预算仍在。
- 固定workflow的Schema错误给steps[index].args字段路径和可信类型/范围，
  整份零派发，不回显错误值；run_program仍按既有动态子验证。
- place_block的face表示真实点击面，默认up；先看目标轮廓及反向邻格的轮廓，
  必须首个命中block/face一致、实际够距且沿线已加载。空气/流体不能当点击锚点。
  每个点击格最多16盒、每盒9个面内样点；不再把目标空格视作合法MISS点击。
  原版BlockPlaceContext决定替换/相邻落位，落位必须精确等于请求格；
  原地雪/半砖、朝向/多格/组件继续按原版处理，墙/地面两用物品最终规则仍待实际place。
- query.ok只表示观察完成。执行前必须检查`data.preflight_passed==true`，
  通过仍有native_validation_pending；失败可读alternatives，由调用方按任务选址，
  不自动更换精确目标。固定workflow在preflight_passed=false时PLACEMENT_BLOCKED停止；
  run_program可读取失败查询的data，自行选择候选，无动作自动重试。
- 通过的原目标查询/成功动作给`placement_contact:{x,y,z,face,block}`，
  表示点击的格子及其原状态ID，区别于外层请求/放置坐标。
  no_clickable_face表示没有真实可点面，wrong_landing表示原版落位不匹配或不能替换；
  拒绝保持changed=false/consumed_count=0。执行不复用上次查询的世界/上下文。
- PathTask在同一搭路目标最多查六个面，通过后重验并只执行一次。
  不移动寻找点击面，不在有消费/变化的失败后换面；原许可/材料/支撑核验保留。
  尚无完整边缘桥接身体动作或搜索中的逐步点击几何，不能承诺所有旧搭路计划都可执行。
- run_program动态子参数的Schema拒绝给具体工具、字段路径和声明类型/范围/枚举，
  错误值/未知字段正文不回显，提示最多512字符；仍零原子派发，不自动改参数。
- gather的source_observations最多8条目标移除时的实际drops物品样本，
  含方块坐标/ID、observed_items及observed_goal_item（与本次acceptedItems交集）。
  每次最多展示8种ID，observed_item_count/items_truncated明示展示截断；
  匹配判断仍检查该次最多32条drops，不只检查展示的前8种。
  不混入清障掉落，不把样本当完整战利品表，不替换期望item，不省略实际背包核验。
  无进展时展示这些样本供模型核对来源；原三次无进展帽及等待/工作预算不变。

## 当前补充契约（2026-10-15）

第六次日志后的工作恢复与回执：

- `gather explore_steps=0`显式选择本次工区附近采集，不换区，不受旧探索球限制，
  仍限制本段目标/站位在起点r内、身体在r+2内及同维度；不重置已有探索预算/锚点/路标。
  局部模式回传原共享累计值及`exploration_enabled=false`，不登记新的探索区域或查询覆盖；
  真实地形改变仍使共享覆盖失效。默认启用探索仍拒绝超出原任务球，不自动降级。
  范围/配置拒绝提示局部模式供模型选择，不等于已经执行恢复。
- `break_block`仅在无已知挖除/清理效果的OUT_OF_REACH或TARGET_BLOCKED时，
  给`recommended_tool:"move_to"`与`recommended_args`，含exact/arrival=work/work_target。
  原错误类型保留，建议不执行、不保证可达、不携带旧授权；显式有限clear_limit时，
  不建议开启导航终点的不限次数清理评估。其他失败与部分效果不改写为可恢复。
- `run_program`终态子回执有编号；print/return中与已记录反馈完全相同的文字显示为编号，
  不折叠实际调用或只相似的文本。Lua实际值、`data.returned`、终态/计数/失败和原预算保持。
  这是显示去重，不是省略查询结果或新的错误恢复语言。
- scan_area近圈坐标是采样事实，不承诺当前可见、可达或可采收；工作仍用work查询/工作到达。

本节覆盖下方历史的“没有地下探索/工作目标A*/长期阶段”的限制描述；
工具数量仍23（15服务端+8本地），桥协议不变。

2026-10-15工作一致性与只读选址：

- `find_resource work=true`的候选可带`harvestable_with_hand`，表示当前主手的原版采收资格。
  false时不评几何/站位，反馈WRONG_TOOL；不表示周围无资源，也不自动换工具。
  gather查完本地分页/来源后，只有不合格候选时停止开路并交回模型。
  search_resource合并分页也将这些候选排后；旧回执缺字段时仍按动作实际复核。
- gather被跳过的同UUID在新观测可触碰或有新站位时可重评，六个不同站位总帽不刷新。
  无可用站位时继续本份扫描内其他物品，必要的动作后背包/位置/实体观测仍保留。
- `place_block`可选严格布尔`query:true`，仍要求x/y/z/item，face默认up。
  查询与实际放置共用前置检查；不转头、移动、挖放或消费，忙时及只读任务可用。
  无材料/不支持物品/规则拒绝仍失败；其他目标受阻可返回观察成功与失败诊断。
  `data.query=true`、`preflight_passed`表示原目标当前预检结果，
  `native_validation_pending=true`表示原版最终接受尚未验证，
  `changed=false`、`consumed_count=0`。不能把query.ok=true当作已放置。
  原目标受阻时，x/z±2、y±1内总最多75格，`alternatives`最多6个同face位置，
  `checked_positions`记录检查数量；精确目标不可自行替换。执行仍重读现场并按原版效果结算。
  墙上/地面两用物品及多格放置的最终规则继续交原版，预检不保证成功。
- 工作到达/实际挖掘共用眼睛到命中面判据，未扩大原版交互范围。
  scan_area/find_resource查询已加载范围事实，未限制为相机FOV；实际挖放仍要求可见射线。

2026-10-15拾取与放置修复补充：

- `scan_area.item_targets[].stand_positions`现在同时满足安全整格站立与实际物品触碰盒，
  最多6个；只是当前几何候选，不保证路线可达或原版延迟/容量/归属允许入包。
- gather在成功移动但未入包时也刷新实体，局部路线失败后不沿用旧实体站位；
  同UUID最多6个不同站位，用尽记skipped_items，再考虑其他资源。
  追物move_to带exact=true及剩余max_digs，开路计入本段预算。
  只有已知移除产生匹配掉落而当前无可处理物品时补一次1秒观察，
  data.drop_settle_waits计次数；正向背包进展清该标志，不每格固定等待。
- data.scope_exclusions按方块/物品各保留最多16条坐标、ID和reason，
  task_scope表示首次固定任务范围，collection_scope表示本段范围。
  剩余可选资源仅被范围排除时SCOPE_LIMIT返回，不额外耗探索步数或扩大范围；
  已在实际触碰范围的物品可原地等拾取，不向界外走。实际背包仍是成果依据。
- place_block的旧“目标空格可见点”检查已由上方2026-10-16真实接触面检查替换；
  完整遮挡不穿透，未知沿线仍拒绝。实际命中进入原生放置上下文。
  预检失败data.placement_failure为occupied_block/unsupported_state/self_collision/
  entity_collision，对应不能原地替换、无法形成可存活状态、自身或其他实体占位。
  均changed=false/consumed_count=0；自身占位提示move_to exact=true挪身。
  其他原生失败仍PLACE_FAILED，并按实际世界/库存效果记账，不能自动重放。
  墙上/地面两用物品保留原版形态选择，不用默认地面方块做新增状态/占位预检；
  此分支可能仍给原生PLACE_FAILED，不能宣称所有特殊形态都有细分诊断。

- `move_to`新增可选`arrival:"work"`，须`exact:true`及`work_target:{x,y,z}`。
  搜索实际可工作的脚格，输入x/y/z仍必填且为规划盒校验位置，不要求最终站在该格。
  `clear_obstructions:true`允许以实际射线安全清单评估终点，目标和其支撑不清；
  到站后再用活世界核对，不能把回执当成目标已挖除。
  可选`underground:true`限制相邻水平步/高差最多1、不搭桥或垫柱；
  `max_digs`为可选非负整数，省略不限开路次数；显式有限值重规划不重置，0禁止开路。
  `bounds:{x,y,z,radius}`是球形范围，radius为1..64；起终点、节点、挖放格及实际位置均受限。
  `walk_only:true`继续禁止全部挖放，不因上述参数扩大许可；省略新参数兼容位置到达。
- `explore`默认`mode:"surface"`保持只行走换区。
  `mode:"underground"`必须带`direction:north/south/east/west`、`target_y:-2048..2047`、
  `targets`1..8项，可选查询半径`r:1..16`默认8。
  每个有限4格请求路段前完整搜索，发现候选就停止；不自动选择矿种、层高或采收。
  默认生命下限`min_health:10`（1..20）、饥饿下限`min_food:6`（0..20）、
  工具耐久储备`reserve_durability:16`（1..1024）；着火/满背包/缺工具停止外出。
  地下开路任务总帽128格，单跳最多24且不透支耐久储备。
  外出预留移动次数供实际路标返程，`mode:"return"`只行走、零挖放返回；
  没有路标拒绝，路变化可能无法返回，不保证天然地形安全，不自动撤退或补给。
  与surface/gather共用首次max_steps（默认8，上限16）和radius（默认64，上限64）及起点，
  反复调用/阶段切换不补充；timeout_seconds仍为本次1..180秒。
  data含state/discovery/movement/return_trail/return_steps、survey_hops、
  underground_remaining_digs及累计探索账；发现不等于背包目标满足。
  NEED_CONFIRM仅完整无效果方案走现有双阶段批准后原参数续发一次，原预算计账；
  失败核对实际落点/已知改动，未知效果不重放，取消不自行返程。
- `set_goal`首次可选`lifecycle:"long_term"`，`max_runs:1..16`默认8。
  普通`task`模式保持原短任务守卫。long_term每个纯文本段尾重新取得完整背包与位置，
  最终条件满足才done；未满足且未触帽时在同task_id继续。
  规范化物品总数/脚格/维度判断新阶段事实，连续两段重复已见事实停止；
  槽位、tick、生命、耐久损耗不充当进展，材料或位移不充当最终成果。
  生命周期及阶段上限首次固定，省略沿用，不允许增加或改模式；
  原目标、背包基线、40模型步帽、权限、程序原子预算和探索账均按原各自作用域继续。
  最后一段仍先验目标再验帽；受阻、取消、错误或未知状态均终止且不自动重启。
  L1已让同生命周期接显式建筑和容器交付条件，见顶部；仍不支持跨退出恢复。

2026-10-15执行与事实更新（覆盖下方逐格查询的旧描述）：

- gather从当前查询复用同站位直接可工作的候选，最多8目标，并受本批开始的物品缺口、
  原挖掘/时间/调用预算及三次无入包进展约束；每格仍单独走真实break_block终态，
  expected_block、权限、采收工具、视线和清理限制不变，每格后核对完整背包。
  换脚格/移动/局部受阻即弃该批，达到批次边界再查掉落；没有新增服务端批量工具。
  data.batch_continuations记录省去中间掉落扫描/资源重查的续挖次数，不代表新增成果。
- 工作/拾取移动在TARGET_BLOCKED、OUT_OF_REACH、NO_PATH、NO_PROGRESS、PARTIAL后，
  先用新鲜status核对实际位移、维度、范围与背包，再有限换位。途中已捡够就停止，
  越界则SCOPE_CHANGED；所有候选失败后跳过该掉落，本段仍可尝试其他资源。
  权限/需确认/取消/工具等其他失败沿用原终态，不把未知失败当作可重试。
  失败记忆仍是本次gather局部状态，未新增跨段掉落失败缓存。
- 自动开路/遮挡清理保护工作台、制图台、制箭台、锻造台、织布机及既有工位和支撑；
  主动break_block拆除已点名目标仍是独立有意动作。
  move_to的work_target及其正下方在首搜、缓存重建、重规划和执行期间保留，
  不得作为路径挖放格；到站仍用原工作判据复核。这不是完整工作目标寻路。
- craft的query=true/material_index增加material.processes：recipe/process/workstation、
  produced_item/produced_count、单输入的input_count/inputs、requires_fuel及烹饪cooking_ticks。
  来自真实普通单输入合成与现有smelt支持的熔炉/高炉/烟熏炉配方，后者沿用smelt的
  普通单件产物、正时长和功能启用检查，不执行烧制或要求现场已有机器。
  最多16工艺、工艺条目合计8000 UTF-8字节、每条最多64物品选项；任何截断均明确标记。
  processes_truncated与原items/sources的truncated分别记账，
  process_scope仅表示本次支持范围，world_sources_verified=false。
  sources兼容保留一层普通合成的方块物品输入，绝不把非方块烧制原料虚构成矿块。
  配方候选不证明天然存在、掉落一致、现有工具合格或工位/燃料齐备，也不自动比较/执行路线。
- gather/explore显式更改任务探索配置被拒时，feedback/data补当前累计步数、上限、
  剩余步数、半径与已核验新区域，并说明省略参数可继承。拒绝不刷新预算或改变原配置。

2026-10-14后续：掉落物进入身体原版触碰盒后由身体每刻自动尝试拾取，
不要求模型发collect；活动只读任务暂禁，空闲及正常工作时恢复。
远处物品仍要实际走近，原版延迟/归属/容量不变，只有背包变化算进度。
gather已移除collect子调用：走近后刷新背包，未入包且仍可触碰时最多等一秒，
再观测仍受阻则PICKUP_BLOCKED；兼容collect仍可显式立即触碰，不扩距离。
下方旧的“走近后先collect/重试”等步骤已由此替代。

## 1. 契约

```java
interface ServerTool {
    String name();
    Result run(CompanionPlayer c, JsonObject args);                  // 同步：一拍出结果
    default CompletableFuture<Result> runAsync(c, args, scheduler)   // 跨 tick：交给任务槽
}
record Result(boolean ok, String feedback, JsonObject data)
```

- **feedback 是给模型看的人话**（教学式回执，DESIGN §7）：失败必须带"下一步怎么办"，
  模型靠它改策略。data 给人/UI/neko 播报。
- **每同伴单活跃任务槽**：同步/异步动作统一占身体资源，目标区域冲突回 `BUSY`；只读查询不占锁。
- 所有执行都发生在服务器主线程、作用于**发送者名下**的同伴（owner 校验在闸③）。

### 1.1 数量目标与有界连续采集

`set_goal {"item":"dirt","count":64,"mode":"additional"}`登记任务物品条件；
additional默认按任务开始完整36槽背包的净新增计算，total按当前总量计算。
既有目标不能降低数量、换物品或改口径；相同目标可重复登记。
模型须保留主人原目标和口径；本体没有通用自然语言目标解析器。
每轮和结束前观测实际背包，不累计矿格/collected_count/地上掉落。
未满足的结束回复继续同一task_id；三次无实际进展的结束回复后FAILED。
受阻可report_blocked，取消不会续目标。L1的显式建筑/容器交付见顶部；跨退出持久化仍未实现。

```json
{"item":"dirt","targets":["dirt","grass_block"],"count":64,"mode":"additional","r":8}
```

以上为gather参数。gather默认scope=task，自动登记同一最终目标，additional沿用任务开始基线；
本地优先走近匹配掉落；否则查来源方块、
选可工作站位、移动后挖掘、走近拾取、更新实际背包，直到满足或受阻。
应先用本轮最新完整装备事实选好工具；本地循环不自动合成镐或处理补给。
targets为1-8个方块ID/#标签，不是物品ID；item为期望入包的物品，count为1-4096。
本段起点附近目标r默认16、范围1-16；timeout_seconds默认600、最大900，
max_blocks默认128、最大256，目标移除与额外遮挡清理共用此预算；子调用最多1024，参数最多4096 UTF-8字节。
三次操作无入包成果且没有可继续拾取候选时停止；刚挖出的掉落先尝试拾取再判断。
掉落附近站位仅为已加载/无流体/完整地板/身体无碰撞候选，最多6个，
路径和实际触碰须子工具验证；复杂半砖站位暂不支持。
选定站位使用move_to的exact=true，必须在指定脚格实际站稳；
走近后核对自动拾取，无实际入包且候选可触碰时最多等一秒再观测，不跳过原版拾取延迟。
每个子动作沿用任务身份、只读限制、权限、资源锁和真正终态；job_ack不能推进。
2026-10-14工作站位更新：挖掘候选与当前动作共用BlockAim的原版轮廓、
已加载射线与眼睛到命中面的实际距离。目标附近45个脚格中，按当前距离排序，
最多检查24个安全站位的视线，返回最多6个可挖站位；含目标上方2–3格的地表站位。
这仍是有界、完整地板的局部候选，不证明其他位置都不能工作。
gather的move_to携带exact=true及work_target，移动真终态再次评估视线/距离；
到站后仍受遮挡/超距或挖掘预检失败时，尝试剩余不同站位，不重复同一站位。
仅耗尽本目标站位后计一次受阻；NO_PATH/NO_PROGRESS可换站位，
TARGET_LOST放弃旧目标并重新观测，权限/缺工具/未知效果直接交回模型。
真正移除方块后撤销此前的受阻目标记录，再按新查询评估。
2026-10-23已取消默认三次无成果、总调用/时间/矿格门。局部清理：
当前与假想站位、move_to终态和清理执行共用MiningWork/BlockAim.miningPlan，
允许实际射线上已核对安全的遮挡，保留射线资源保护，计划只读，不点击穿墙或改变世界。
gather自动携带clear_obstructions=true及clear_limit=剩余挖掘预算-1，
服务端按具体清单/自主模式裁决，不需模型逐格安排落叶或少量泥土清理。
清理超限、神圣方块/支撑、脚下支撑、液体邻接、重力方块或不具采收资格时仍停止。
目标与遮挡使用真实BlockMining，清理后重评射线；清单外新格/状态变化不自动扩大范围。
掉落站位不套用挖掘视线筛选，完全埋藏的大范围勘探仍需另行规划。
break_block可带expected_block完整ID；不匹配则TARGET_LOST，不能挖新出现的替换物。
gather内move_to/break_block首次NEED_CONFIRM若有当前任务的完整服务端方案，
直接通过既有question入口展示原清单并等待真人回答，最长120秒。
明确确认后仍须等原authorize成功回执，再按原坐标/目标/预算及该编号续发一次；
不新增模型轮次、不自行批准或扩展清单。续发再次NEED_CONFIRM交回模型，防止无界征询。
已移除目标/已清遮挡的失败不重放；缺方案、拒绝/超时、PARTIAL、缺工具/空间、
预算或未知事实仍返回模型判断。等待不延长原采集/程序总时限，续发计原子调用预算。
取消/超时清子票，迟到结果不续跑，保留已完成与在途未知。
data给goal_satisfied、mined_blocks、cleared_blocks、actual_progress、primitive_calls、
skipped_blocks/skipped_items及last_observation；真实进度仍按完整背包核对。
另给work_position_attempts，统计实际派发的工作换位次数，便于区分恢复与模型等待。
地下勘探使用本页当前explore契约；自动补给、危险响应和跟随护卫仍缺，
不保证自然世界找到64颗钻石。

2026-10-14换区更新：gather先用同中心/半径分页查询
剩余采样格，每页仍4096格/16结果/24工作评估/2048射线；最多5页覆盖r=16的17077格。
分页中心与当前身体不符时拒绝，不把不同中心的页拼成完整区域。
仍没有可工作候选时复用ExplorationRoute，从服务端已加载落脚样本选择新区域，
探索move_to固定walk_only=true，不挖路/搭路；真实status至少4格水平位移且不落回
已有观察位置附近才计新增区域。到达位置立即记账，选点结合水平新颖度、方向连续性及爬升成本。
探索不是木材专用，targets/item/作用域/原背包基线不变，可供任意来源方块的数量任务复用。
首次explore_steps默认8、0只关闭本次探索、最多16次移动尝试；explore_radius默认64、16-64。
2026-10-15起，与独立explore及后续gather共用同任务探索起点/维度/访问记录/累计预算；
后续省略探索参数继承首次值，显式变更拒绝，不能重开采集刷新步数或范围。
限制相对共享起点的目的地及采集区域，保留整个工作区域的半径余量。
采集探索每方向远点随本次r缩放（最少4、最多16格），近点约为一半，
仍最多8样本/200落脚探针；r8不再优先跳16格，默认r16仍优先16格/回退8格。
目的地水平跨度最多r+1（上限17），三维跨度最多2r；小半径/陡坡可能没有合格样本。
远点高度±6，近点-5至+6，并非旧版单距离±12。样本不证明路线可达。
这里约束目的地/新鲜观测及工作目标，不是每个导航节点都受球体约束的路线沙盒。
总时限、1024原子调用、挖掘/清理预算和三次无入包进展不因换区重置。
NO_PATH/NO_PROGRESS/TARGET_LOST和PARTIAL探索回执后先刷新位置，可能已实质移动；
原地成功文案不计新区域，试过的目的地不重复；探索walk_only不申请改地形，
权限/未知效果仍交回模型；工作/拾取路线的单次确认续发见上。
预算耗尽、样本不可达或没有新落脚点明确停止，不宣称资源不存在。
data增加exploration_attempts、exploration_max_steps、exploration_radius、explored_regions、
search_pages、search_radius、coverage_regions、estimated_new_coverage，
普通feedback也显示累计探索账；实际背包仍唯一数量成果。
2026-10-15新增实际查询覆盖账：只有有效、完整且unknown=0的同中心分页链才登记，
以目标批次/中心/半径区分，绑定任务维度，最多64条、120秒；本地真实移除/清理后清空。
选点用33个球体体积样本估计新增覆盖，并结合方向、水平间隔和高度成本评分。
这只是候选排序启发，不证明沿途、横向空隙或球体中的全部目标已检出；
材料来源超过8种时按批次记录，选点参考最后查询批次，不声明全来源联合覆盖率。
未实现任意远程定位、完整地形索引、地下挖掘勘探、补给与危险响应。

独立`explore {}`不绑定采集/配方/物品目标，每次完成一段真实换区并回传data.state和movement。
同任务explore和gather共用ExplorationRoute的起点/已观察位置/失败点/累计步数；
首次max_steps默认8、1-16，radius默认64、16-64；之后省略继承首次值，
显式参数须保持一致，重复调用不重置预算。访问记忆不等于完整搜索覆盖。
timeout_seconds仅限制本次段，默认180、1-180；只读拒绝、原权限/终态等待与取消机制共用。
exploration_advanced只证明新鲜位置确实变化，不等于任何发现/采集/建筑/战斗目标完成。
run_program可组合任意现有观测，以调用方自己的条件决定停止，例如寻找实体：

```lua
for i=1,5 do
    local s=mc.call("scan_area",{r=8})
    if #s.data.entity_targets>0 then return s.data.entity_targets[1] end
    if i<5 then mc.call("explore",{}) end
end
return {blocked="bounded observations ended without a target"}
```

这不是内置特定目标流程；同一入口可配find_resource寻找工作台/建筑方块，
或按状态/实体/掉落等已有观测决定下一阶段。模型负责选择条件，实际动作复用原入口。
仅已核验成功的explore可以重置“原地三次空查询”计数，普通移动文案不能重置；
程序总时间/计算/输出及API/嵌套原子预算不变。AgentLoop及Lua共用SearchPages：
成功回执证明同目标/中心/半径且offset严格连续的分页按一次搜索计，r16五页可查完。
重查首/中/末页、跳页、换查询及缺少/畸形覆盖事实不豁免，三次独立空搜仍停止。
gather仍优先本地处理分页；workflow没有条件分支，空结果仍立即停止后续步骤。

多阶段任务先用set_goal登记最终物品，再复用同一个gather采集材料：

```json
{"item":"oak_log","targets":["oak_log"],"count":4,"scope":"subtask"}
```

scope=subtask只建立局部材料条件，不登记/覆盖/降低最终目标，省略mode默认total。
additional从本次gather的第一份新鲜完整36槽状态起算；total表示当前总量足够，
因此已有足量材料时直接验收；明确要求再收集才用additional。不同材料可先后调用，
配方、工具和后续阶段仍据实际结果选择，没有成品专用流程。
子任务成功不等于主人任务完成，最终结束仍重新核验set_goal；
取消/超时发生在第一份状态前时明确无局部基线，不能编造进度。
scope只接受task/subtask，省略兼容旧行为；重复task段不重置任务开始基线。

配方允许替代材料时，不必提前固定oak_log。先查询实际配方，再准备其材料：

```json
{"scope":"subtask","count":2,"mode":"total",
 "material_for":{"item":"crafting_table","ingredient":0,"sources":true}}
```

material_for与外层item/targets互斥，仅限scope=subtask；item为配方产物，
ingredient默认0，指craft查询ingredients数组的分组序号，可选recipe固定普通配方。
先调用craft query=true/material_index，按服务器实际Ingredient及单输入普通合成配方
取得候选。sources=true采一层转换来源，省略或false采直接材料本身，只使用有方块ID的选项。
直接选项/来源各最多64条，截断或没有可用来源时停止，不猜测也不扩大为任意标签。
超过8种来源按最多8目标分批，各批分页完成后才探索；新鲜背包合计实际候选物品件数，
拾取只接纳这些ID。count不是转换后材料单位，不保证整批配方已经足量，
不自动转换/递归规划；后续合成仍须查询和真实执行。
例如桦木/橡木能否替代由所选配方判定，而非根据名字推定；原精确item模式不变。
配方给出的方块来源仍不保证实际掉落相同，采收工具、掉落与入包继续按真实回执核对。
最终set_goal保持精确成品ID，材料子任务不能修改或满足整项完成条件。

`search_resource {"targets":["#minecraft:logs"],"r":16,"work":true}`为新增本地只读入口，
一次查完同中心全部find_resource页，默认r16，最多5页/30秒，逐页复用原采样与工作预算。
中心/目标/半径/页序或覆盖事实不一致即停，取消/超时不接受迟到续页；
run_program中每个实际页都计入原子调用预算。它不移动、不强制加载、也不自动采收。
所有页完成后才回模型，最多16个候选，直接可工作/可清理/有站位优先，
候选或评估截断如实保留。反馈同时展示目标、半径、已读/未知格和完整状态。
data给search_pages/primitive_calls、累计samples_examined/cells_read/unknown/matches_observed、
scan_complete、coverage_complete、no_targets；coverage_complete只表示采样格全部已知，
不表示全部匹配已输出或都可工作。整体回执/数据各最多24000字节，超限停止。
三次独立完整空搜仍触发原护栏；底层find_resource保留显式单页兼容入口。
独立search_resource不登记采集路线覆盖，以上覆盖选点评分由gather的实际查询更新。

### 1.2 通用组合执行

run_program接收Lua文本code，不解析自定义任务语言，不按成品名选择专用流程。
变量、局部函数、if/for/while从mc.call的真实回执计算下一步：

```lua
local q = mc.call("craft", {item="stick", count=4, query=true})
if not q.data.can_craft then return {blocked=q.feedback} end
mc.call("craft", {item="stick", count=4})
local inv = mc.call("inventory", {})
for _, s in ipairs(inv.data.slots) do
    if s.item == "minecraft:stick" then
        return mc.call("equip", {slot=s.slot})
    end
end
error("产物未在最新背包中观察到")
```

这里只演示真实返回值依赖，同一语言机制可组合其他配方/材料/移动/攻击/放置。
mc.call返回{ok,feedback,data}；数组从1开始，空Lua表作为JSON对象，
连续正整数键表作为数组，不能用循环表/函数/混合键/非有限数字作为参数或返回值。
无观测字段不能当作满足条件；返回阶段阻塞不代表最终目标完成。
最终物品在程序外先set_goal；程序内gather只允许scope=subtask，total可复用现有材料。
它共用旧采集实现；workflow也可组合，原子动作仍走旧服务端权限/身体互斥。

允许15个游戏工具和gather/workflow/explore/search_resource，不允许run_program嵌套、
ask_owner/set_goal/report_blocked或任意命令。失败、需授权、未知终态立即停，
保留之前实际回执和本次原因，外层模型按原ask_owner流程处理具体授权。
job_ack不兑现子future；job_event真终态才恢复程序，取消/超时清子票、中断计算，
迟到结果不续动作。只读任务逐调用检查，已运行查询不回滚，修改子调用不派发；
不同于workflow整份静态预检，动态分支只校验实际将调用的参数。

参数：code最多16384 UTF-8字节、完整JSON最多20000字节；
timeout_seconds默认300/范围1-900，max_calls默认128/范围1-256。
max_calls同时限制mc.call数量及实际原子派发，嵌套gather/workflow共用后者，
不存在通过拆分嵌套绕过原子调用帽。时间超限也会停止正在等回执的子任务。
一次gather内部有多次status/scan_area/find_resource/move_to/break_block调用，
所以不能按Lua的mc.call行数给它设置很小的max_calls；通常省略沿用默认值。
查询大表留在局部变量，print/return只报告必要事实；上限不因组合恢复而放宽。
每次API之间最多25000指令，累计250000，调用深度48；
数据表/转换累计按65536单元计费，字符串累计8MiB计费、单值65536字节。
拼接前检查操作数及中间分配，避免指数增长；打印/返回/子步骤反馈共享20000字节，
返回值另限12000字节，JSON转换最多8192节点/16层。
三次空搜索（状态查询不清零）、五次无成果的同调用同回执停止；
真实制作/采集计数进展可清除打转计数。

只保留assert/error/ipairs/pairs/next/tonumber/tostring/type/print及语言算术/表/局部函数。
没有io/os/luajava/package/require/load/debug/coroutine/string/table/math库、
pcall/xpcall、元表或动态加载；字符串方法也不借用其他mod装入的LuaJ全局元表。
这些是本地受限解释器的资源/能力限制，不是独立OS进程或精确堆内存隔离。
data包含api_calls、terminal_calls、primitive_calls、last_tool、last_receipt、
returned及真实数量汇总；模型反馈保留阶段实际回执，不靠打印文本判成品完成。
23个工具仍全量下发，技能/帮助按需读取留给下一卡。

### 1.3 主人选择的自主执行

G面板设置/通用的“自主执行（免确认）”对应客户端autonomous_actions，默认false。
开启后新任务的路线开路/搭路及未受保护的中立/命名实体攻击免逐次批准，
不再走NEED_CONFIRM→ask_owner→重发动作的批准轮次；普通澄清问题仍可出现。
既有逐次确认模式不变，没有新增模型工具用来切换权限。

模式由主人客户端通过内部task_begin.autonomous布尔值传给服务端，
旧客户端缺字段为false，非法类型拒绝建立新任务。ActionPermissions.Context保存有效模式，
read_only始终优先；只读上下文不能携带有效自主动作许可。
PathTask计划清单及逐格执行、AttackTool目标批准共用ActionPermissions.allowsChange(s)，
自主许可仅对当前任务有效，终止/换任务后失效，工具args里的自主/许可布尔不授予权限。
原白名单、owner/身体/维度身份、保护规则、资源互斥、真实终态及有限动作预算保留。

status的实际服务端回执包含permission_mode=confirm/autonomous/read_only/none，
并在feedback说明当前任务模式；scan_area的攻击需确认提示同步使用有效模式，
受保护目标仍显示保护。自主新任务首次及后续轮次的观测须确认autonomous，
旧服务端/模式不符时在模型请求前停止，固定MODE_UNAVAILABLE原因保留到任务终态；
不自动批准旧方案、不降级为反复询问或回显任意失败原文。
保存模式变更停止当前与排队任务/待答批准，保留显示历史；下一条指令按新模式执行。
字段存在本实例客户端配置，对之后的桥接和面板任务一致生效，未提供世界级规则记忆。
外部桥v1.0字段、鉴权和事件不变；客户端/服务端须一起更新，本卡不自动安装或改实例。

## 2. 在册工具（15 服务端 + 7 本地，真实行为仍待验）

> 条数以 `McbotMod` 里 `tools.register(...)` 的**实际注册数**为准（现 15 个），
> 本表就是那15个与本地 `ask_owner` / `workflow` / `gather` / `explore` / `search_resource` / `run_program` / `set_goal` / `report_blocked`。
> set_goal/report_blocked由AgentLoop拦截，其余本地工具由AgentRunner编排，不发服务端同名工具。

| 工具 | 参数 | 型 | 说明 |
|---|---|---|---|
| `status` | `details?` | 同步 | 最新位置/生命/饥饿/背包占用/主手及实际任务计数；details=true包含背包/装备逐槽明细 |
| `find_resource` | `targets,r?,work?,offset?,scan_center?,frontiers?` | 同步 | 底层单页，默认r8；每页4096格/16输出；续页用next_offset与原中心/r/targets，中心变化拒绝；末页可给探索落脚样本；内部frontier_distance=4-16供gather缩放样本 |
| `inspect_block` | `x,y,z,offset?` | 同步 | 6.5格内完整加载方块/本体容器明细，每页24槽；拒锁/未展开战利品，不打开GUI |
| `inventory` | — | 同步 | 完整 36 格背包的槽位/物品 ID/数量/耐久、选中主手槽及 7 个装备映射槽；只读，忙时也能查看 |
| `equip` | `slot`(0-35 整数) | 同步 | 仅切换主手：快捷栏 0-8 直接选中；背包 9-35 与当前主手槽交换整个物品堆栈；忙时拒绝 |
| `craft` | `item,count?,query?,recipe?,material_index?` | 同步 | 实际普通合成配方；query只读，可查分组材料与一层来源；2×2随身，3×3需5.5格内工作台；整批预检，失败不改背包 |
| `smelt` | `x,y,z,action?,input_slot?,input_count?,fuel_slot?,fuel_count?,slot?,count?` | 同步 | query/load/take；三类原版机器三槽与真实计数，预检后移动物品；实现/离线完成，真实烧制待验 |
| `scan_area` | `r`(1-32，默认16),`items_only?,item?,frontiers?` | 同步 | 附近实体与可行动方块分层摘要，绝对坐标与八向朝向；主人准星为提交时记录。附地上item_targets，最多16实体、可按item过滤；items_only=true只查掉落，frontiers=true另给最多8个探索落脚样本。不保证候选可达，泥土/沙用find_resource定向查 |
| `break_block` | `x,y,z,expected_block?,clear_obstructions?,clear_limit?,authorization_id?` | 异步，无默认总时限 | 默认单格；可选实际射线安全清单，清障默认不限次数，走原批准/自主模式与实际BlockMining；目标移除和遮挡清理分列，不直接入包；每拍核验目标/主手/距离/视线 |
| `attack` | `entity_id,target_uuid?,max_hits?,authorization_id?,approach?` | 异步，无默认总时限 | 默认原地，approach=true在初始64格范围只行走接近同一目标；max_hits省略/0持续，正数为明确有限挥击；满冷却/恢复后出手，保留批准及保护目标/横扫核验 |
| `collect` | `x?,y?,z?,r?,item?` | 同步 | 坐标全省略为脚下，r=1-12默认3；查询半径不扩大身体触碰盒，生产调用原版playerTouch，尊重延迟/归属/余量；可过滤物品，忙时拒绝 |
| `place_block` | `x,y,z,item,face?` | 同步 | 物品 ID、准确目标格；先检查遮挡/实际交互距离并转向，再用原版玩家放置上下文，face 默认 up；实际目标变化/消耗核验，忙时拒绝 |
| `move_to` | `x,y,z,exact?,walk_only?,work_target?,clear_obstructions?,max_digs?,authorization_id?,may_alter_terrain?` | 异步，无默认总时限 | **DigAStar**：默认相邻到达，exact指定脚格；walk_only禁挖放。工作到达按最新视线/距离核验，不执行目标清理。每刻输入驱动原版travel/跳跃；确认模式改地形先批准，自主模式由主人任务放行。每次搜索4000节点/32768记录，无默认搜索总时间门；默认不限开路次数，放置受实际库存/来源保护，新增改动重验许可 |
| `transfer` | `x,y,z,dir(in/out),item?` | 同步 | 6.5 格内未锁定有效普通容器，遵守槽/接触面与堆叠上限；允许部分搬运，余量留来源；忙时拒绝，熔炉类须用 smelt |
| `wait` | `seconds`(1-60) | 异步 | 站定等待（熔炉/作物节奏用，别拿轮询代替等待） |
| `ask_owner` | `text,authorization_id?` | **本地** | 普通问题沿用 text；路线确认携带服务端编号，实际展示服务端完整清单。120s 期限，明确批准后仍等服务端授权回执才续跑 |
| `workflow` | `steps,timeout_seconds?` | **本地** | 明确近身清单，1-12步/默认180最多300秒/请求量合计≤128；逐步等终态，失败/空搜索/缺条件/需确认即停 |
| `run_program` | `code,timeout_seconds?,max_calls?` | **本地** | 通用Lua变量/条件/循环组合；mc.call只读实际data并等真终态；共用原动作/权限/取消和嵌套原子预算，详见§1.2 |
| `gather` | `count,item/targets或material_for,mode?,scope?,r?,timeout_seconds?,max_blocks?,explore_steps?,explore_radius?` | **本地** | 默认r16；精确物品或真实配方材料子目标，分页及换区后按新鲜背包结算，不覆盖最终目标 |
| `search_resource` | `targets,r?,work?` | **本地只读** | 默认r16，最多5页/30秒；同中心完整查询，累计已读/未知、最多16候选；原程序预算逐页计数 |
| `explore` | `max_steps?,radius?,timeout_seconds?` | **本地** | 通用一段换区观察；同任务共享步数/范围/访问记录，支持run_program任意已有观测条件；不采集或验证目标 |
| `set_goal` | `type?,item/count/mode?`或`target/expected_revision`或`container/item/count/mode?` | **本地** | 固定item/building/delivery条件及task/long_term阶段帽；结束前新鲜完整核验，未满足不发完成done；具体互斥必填规则见L1文档 |
| `report_blocked` | `reason` | **本地** | 1-512字符实际障碍，以FAILED结束并保留成果/目标事实；不能把受阻说成完成 |

游戏内 `@bot 答 <文本>` / `@bot answer <文本>` 只用于回答当前最新的有效问题。
没有待答问题、问题已回答或已过期时提示回答未提交，不作为新任务执行；
普通任务仍从任务页、桥或不带回答前缀的 `@bot <指令>` 投递。

工作朝向与可见性：移动朝下一落脚点，挖掘朝当前可见目标点，放置朝指定点击点，
攻击朝实体；不由主人的相机实时驱动。挖掘和开路共享同一视线守卫，放置也拒绝穿过
近处遮挡格；距离按身体眼睛和原版交互属性计算，旧5.5/6.5格球距不是额外臂长授权。
遮挡/无可点击轮廓回TARGET_BLOCKED，实际超距回OUT_OF_REACH，未知沿线/失效目标回
TARGET_LOST；空气本来就拒绝。单格工具拒绝不会挖邻格/移动补救；
gather按上述有界站位策略本地恢复，其他调用由模型依据回执重新规划。
扫描是周边采样，不是相机视锥；主人准星只记录提交瞬间，不能证明同伴此刻仍看见。
轮廓有限采样可能保守拒绝狭缝；精确格放置上下文保留，
路线移动已改原版物理输入，但水中导航/载具/复杂半砖站位不是本轮完整能力。
不宣称完整原版鼠标点击已实现。新场景见REAL-GAME-RETESTING。

未上（DESIGN §5 规划中）：
`locate` 等——M5/M8 分批补齐；`navigate` 并入 move_to 升级，`wait_until` 并入 wait。

### 2.1 背包明细与主手切换

模型需要材料或工具信息时调用 `inventory {}`，从回执选择实际槽号，再调用
`equip {"slot":13}`。`status` 默认轻量体感汇报，details=true增加完整背包/装备明细。
`inventory` 的 feedback 列出所有非空背包槽、选中主手及全部装备映射槽，
未列出的背包槽为空；当前 AgentRunner 仅将 feedback 写入模型历史，不能只依赖 data。

data 包含 `selected_slot`、`storage_size=36`、`slots_used`、
`slots`（36 项，含空槽）及 `equipment`（当前版本 7 项）。
背包项含 `slot`、`item`（带命名空间的注册 ID，空槽为 `""`）与 `count`；
可损耗物品额外含 `damage`、`max_damage`。装备项的 `slot` 是
`feet/legs/chest/head/offhand/body/saddle`，`inventory_slot` 对应 36-42。
这两个新增映射槽不等于玩家可穿戴身体盔甲或骑具，`equip` 不接受任何装备栏槽。
主手由 `selected_slot` 对应的背包项确定。

回执不输出自定义名称、附魔明细或原始组件/NBT；物品 ID 展示最多 128 UTF-8 字节，
超长时 `item_id_truncated=true` 且 feedback 标明截短，可继续按槽位切换。
交换使用完整原 ItemStack，保留数量、耐久、附魔及其他组件，不拆分、合并、消耗或生成物品，
满背包也可交换。来源槽为空、参数类型/范围错误回 `DENIED:`；
数值形式的整数如 `13.0` 可接受，数字字符串、布尔、小数、溢出等拒绝。

`equip` 成功 data 含 `source_slot`、`selected_slot`、`swapped` 和 `held`；
背包交换时额外给出 `source_after`。feedback 明确报告新主手和原主手的新槽位。
工具切换后旧槽位可能已经变更，继续选择物品前应查看最新回执或重新调用 inventory。
长任务占槽期间返回 `BUSY:`，不会影响当前挖掘或移动。
专项离线与真实服务端物品工装见 DEVELOPMENT §3.2；玩家模型驱动与连接器联合验收仍待安排。

### 2.2 配方查询与合成

`craft {"item":"stick","count":5,"query":true}` 仅查询；
`craft {"item":"stick","count":5,"recipe":"minecraft:stick"}` 执行。
item 是产物 ID，recipe 是可选的配方 ID，两者不必同名；不填命名空间默认 minecraft。
count 默认 1，表示至少所需成品数，按完整配方次数向上取整：木棍每次 4 根，
请求 5 根执行 2 次、产出 8 根，不丢弃多出的 3 根，也不把已有木棍抵扣本次请求。
数字字符串、小数、越界、非法 ID、非布尔 query 均 DENIED。

读取当前服务器 RecipeManager，不维护硬编码配方，不缓存旧数据包。
支持实际类型为原版 ShapedRecipe/ShapelessRecipe 的普通有序/无序配方，
包括数据包的同类配方与材料标签。按配方 ID 排序，自动选择整批当前可完成的配方；
指定 recipe 时只检查该配方，产物必须匹配 item。
不自动递归制作材料，不执行特殊动态配方（染色/修复等）、自定义 Recipe 子类、
冶炼/切石/锻造。不模拟 GUI、配方书解锁、制作统计或成就事件。
自动查询最多 4096 个加载配方、32 个匹配候选，超限请指定 recipe；
每次至多 64 次配方执行。

2×2 内的配方可随身合成。更大配方需要同伴所在维度、已加载区域、距离 5.5 格内
的原版工作台；工具自动找最近工作台，查询反馈和 data 同时给坐标。
不远程使用工作台，不自动放置或挖掉工作台。缺工作台先 scan_area/move_to，
或显式 place_block 放背包里的工作台。
只使用 0-35 存储槽作为材料与存放位置，不消耗副手/装备栏、不改变选中槽。
长任务占槽时执行回 BUSY，query=true 仍允许只读查询。

整批在 ItemStack 副本中模拟：使用原版 StackedContents 匹配重叠材料候选，
以真实堆栈组件匹配 Ingredient，并再次用实际 CraftingInput 验证 matches/assemble。
每次按真实 getRemainingItems 处理返还物（如牛奶桶返空桶），按完整组件合并，
优先已有堆栈再空槽；全部容纳后才一次提交。
任何一批缺料或成品/返还物装不下，整批回退、背包所有槽保持不变，绝不落地溢出。
未参与制作的堆栈及装备保留组件、数量与耐久。

query 合法且找到普通配方时 ok=true，即使材料/空间/工作台不足；
必须看 can_craft，不能把查询成功当作已制作。
data 给 recipe、requested_count、batches、produced_count（计划产量）、
crafted_count（查询/失败为 0，执行成功为实际产量）、output_per_batch、
requires_workbench、workbench（存在时）、ingredients、can_craft、query。
材料项含 options、per_batch、required_count、available_count；options 最多展示 4 个，
超出标 options_truncated。不同材料项共享候选，available_count 不能简单相加；
实际能否制作以 can_craft 为准。
feedback 同时列配方、单次材料/匹配存量、次数/计划数量、工作台坐标和失败原因，
供下一轮模型读取；不序列化自定义名称或原始组件。
query=true可加material_index=0-8，按ingredients分组序号返回material：
index/recipe/items/sources/truncated。items来自该Ingredient的真实候选；
sources仅来自产物匹配该Ingredient、恰好一个输入槽的普通有序/无序合成配方，
输入须为BlockItem、无制作返还物且不为产物自身；转换附recipe/produced_item/produced_count。
来源探查仍最多4096个加载配方，不做跨工艺/递归依赖；各列表最多64条，
材料查询不会消耗物品。执行模式携带material_index直接拒绝。
隔离专项与离线接线见 DEVELOPMENT §3.3；玩家模型驱动与连接器联调待验。

### 2.3 熔炉/高炉/烟熏炉（实现与离线完成，真实行为待验）

`smelt {"x":2,"y":90,"z":0}` 默认 action=query，只读三槽、实际烧制/燃烧计数、
配方、区块是否推进及等待建议。feedback 同时给当前原料预计剩余烧制 tick、可用燃烧 tick
和整批燃料是否够用。合法查询 ok=true 不代表机器正在烧制或已有新成品。
目标须在同维度 5.5 格内、已加载且是未锁定的原版熔炉/高炉/烟熏炉。

load 从背包 0-35 的 input_slot/fuel_slot 装料，至少提供一个来源槽；
对应 input_count/fuel_count 为 1-64，默认 1。同一来源槽先预留原料再核对燃料，
不能重复消费；组件不匹配、槽满或来源数量不足时不移动物品。
加入原料必须有该机器适用的普通配方、能容纳下一件产物，且有现存燃烧余量或可用燃料；
不满足时整次失败，连同同时提供的燃料也不移动。只补燃料可给空炉备料，
不要求产物槽可用；若机器已有原料，仍须有支持的普通配方，避免补燃料间接启动不支持的配方。
成功后仍应看 query 的状态，不把空炉备燃料当成点火。
take 的 slot=input/fuel/output，默认 output；count 为最多取出数量 1-64，默认 64。
背包装不下整次取出时保持两边不变；空产物槽返回 NOT_READY。
空原料/燃料槽返回 EMPTY_SLOT。合并按全部组件比较，先补已有同类堆栈再放空槽，
同时遵守物品与容器堆叠上限；只用 36 个存储槽，不借装备栏空间。
可以从 fuel 槽回收原版已返还的空桶/水桶，空桶本身不能作为燃料装入。
不同 action 的多余参数直接拒绝，避免错模式移动物品。

data 含 action、机器 ID 与 x/y/z、input/fuel/output 三槽的物品摘要，
burn_remaining_ticks/burn_total_ticks、cook_progress_ticks/cook_total_ticks、
fuel_ticks_per_item/available_fuel_ticks、needed_cook_ticks/fuel_sufficient_for_input，
以及 ticking、state、suggested_wait_seconds、loaded_input_count/loaded_fuel_count/taken_count。
有普通配方时另给 recipe/recipe_id_truncated、result_per_input、recipe_cook_ticks。
state 是 EMPTY_INPUT/NO_RECIPE/OUTPUT_BLOCKED/MISSING_FUEL/NOT_TICKING/COOKING；
COOKING 表示当前具备推进条件，不证明本次已经产生新成品。
首件剩余时间使用机器当前 cook_total_ticks，后续按当前配方估算，避免数据包重载时混用计时；
仅 COOKING 推荐 1-60 秒 wait。燃料/时间均为估算，低 TPS、返还容器或产物槽变满会影响整批完成。
不输出自定义名称或原始组件，配方 ID 展示限 128 UTF-8 字节并标明截短。

模型应按 inventory → load → wait → query/take → inventory 获取实际反馈。
烧制由原版机器推进，工具不生成成品；装料成功、等待结束和取消均不证明烧制完成。
取消 wait/agent task 不清机器，停止后续烧制须显式取回原料，燃烧余量仍按原版消耗。
长任务占槽时只允许 query，load/take 返回 BUSY。
暂仅支持原版普通烧制配方类且单原料产物数量为 1，拒绝多产物数据包配方。
不支持模组机器、GUI/成就/经验领取流程；实际机器行为仍需专项证明。
操作预检/物品保全、参数与 runner 取消接线的 28 项离线测试见 DEVELOPMENT §3.4；
离线夹具提供已返还桶，不模拟燃料生成桶或原版烧制，不替代真实服务端/连接器验收。

### 2.4 物品守恒与部分搬运

transfer 和 collect 可以部分完成。反馈的实际已搬/已捡数量和剩余数量是判断依据，
`ok=false` 可能已经移动一部分，不能据此假定两边没变化。
腾空间后仅处理留在来源的物品；取消或迟到回执不回滚已完成的同步移动，
应先重新查看 inventory，不能自动重投整个操作。

transfer 按来源逐槽搬运，先合并全部组件相同的堆栈再使用空槽。
背包来源/目标只用 0-35，装备/副手和选中槽保留。
拒收某项不阻断后续其他允许的物品；可选 item 按注册 ID 过滤，最多 128 字符。
坐标须是数值整数，dir 为 in/out；省略 dir 为 out 保留旧调用兼容，
模型 Schema 仍要求显式提供。非法方向、数字字符串、小数或越界整数均 DENIED。
data 含 moved_items、moved_stacks（发生实际移动的来源组数）、remaining_items、
partial 和 dir。没有匹配物品时 ok=true 但 moved_items=0，不证明目标已达成；
有剩余匹配物品时 ok=false，partial 只在已移动且有剩余时为 true。

普通容器须已加载、在当前世界边界和 6.5 格内、stillValid=true，锁定容器一律拒绝，
不通过此工具使用钥匙。当前操作目标方块自己的 Container，不自动合并双箱。
canPlaceItem/canTakeItem 和物品/容器堆叠上限参与移动；
WorldlyContainer 另检查同伴眼睛相对容器中心方向的最近面、该面的槽集合与进出权限，
不遍历其他面绕过拒绝。熔炉类仍须 smelt。
仅适用于遵守上述 Container 接口约定的存储容器；菜单独有规则、副作用、
任意模组 setter 行为和双箱/阻挡开启语义不在当前离线保证内。

collect 的 x/y/z 须完整提供或全部省略；r 为 1-12 数值整数，默认 3。
data 保留 collected，并加入 collected_count、remaining_count、partial；
反馈明细每类最多 32 项、使用注册 ID/数量/耐久，不序列化自定义名称或原始组件。
生产collect调用原版ItemEntity.playerTouch，由原版维护完整堆栈、部分余量和实体删除。
身体须未骑乘、存活，物品盒与body.inflate(1,.5,1)相交；搜索半径不能远程吸取。
原版删除实体后会恢复堆栈数量以发送拾取包，回执按实体是否已删除及堆栈实际缩减计数。
item为可选有效物品ID，省略时处理可触碰匹配范围的所有物品。
单格挖掘和开路只观察原版地上新掉落，不执行收取，不重复求战利品或生成掉落。
生产playerTouch已接入，原生动画/统计/成就和Mixin加载仍待实测；不新增被动自动拾取。
专项离线回归和真实行为待验范围见 DEVELOPMENT §3.5。
collect本身不调用move_to或寻路，超距拒绝而非自动靠近；
这只是单工具行为，不能证明普通任务的其他步骤没有移动权限。
collect会修改背包/地面实体，仍是非只读动作；当前没有独立“只拾取”许可模式，
权限表达评估见 `RG5-PERMISSION-REVIEW.md`。

### 2.5 挖放语义（C2 实现/离线完成，真实动作待验）

break_block 与 move_to 开路共用 BlockMining：预检 5.5 格、执行期 6.5 格，
要求当前生存模式、世界交互规则允许、目标及 3×3×3 邻域已加载，拒绝空气、
液体/含水方块、不可破坏目标及不具采收资格的主手。
与原版允许错误工具慢挖但无产物不同，本工具直接 WRONG_TOOL 且不破坏目标。
背包明细后先 equip 正确工具；规划同样将无采收资格的方块视为不可挖。
冻结目标状态、主手选中槽及完整堆栈，每 tick 检查变化并使用
BlockState.getDestroyProgress 的实时增量；零硬度的正无穷进度可立即完成，
无效进度连续 10 tick 停手。失败、完成、取消和路径重规划清理当前裂纹。

break_block默认仍为显式单格动作；可选clear_obstructions=true请求有界组合挖掘，
clear_limit为非负整数，省略不限清障次数，0禁止清障；authorization_id沿原主人批准回执使用。
ClearingMining先生成目标及实际射线遮挡的完整BlockState清单，
确认模式先NEED_CONFIRM，无世界效果；ask_owner显示服务端清单并确认后，
以相同坐标/expected_block/clear_obstructions和批准编号重发。
自主模式仅由当前主人任务上下文放行，模型布尔不授予权限。
全部改动邻域先租约扩展，逐拍检查状态/当前安全/主手，真实耐久变化在动作后刷新，
清理前先通过最终目标的工具/世界规则预检，只容许视线遮挡交给局部计划处理，
取消/超时保留已经清理的记录；不改宿主或外部桥v1.0。
data的removed仍只指请求目标；cleared_blocks/clearing_results单列实际遮挡移除，
清掉遮挡后目标受阻不能假报目标已挖或物品目标已满足。

最终只调用一次 ServerPlayerGameMode.destroyBlock，让原版执行采收/耐久/方块钩子。
其返回 true 不保证实际移除，因此还要核对目标原方块是否仍在。
移除失败不自行求战利品、不吸取新实体；原版仍可能已消耗耐久，勿自动重试。
成功移除后只观察目标AABB向外扩0.5格内、本次动作前不存在的物品实体；
不改新旧实体、不写背包、不删除掉落；需要物品时走近后由身体原版触碰拾取。
这是有界的附近新实体观测，不是所有掉落的精确归因：邻格钩子产生的物品可能在内，
远处/延迟生成的模组掉落不在内，经验球不自动收取。
data 含 removed、reported_success（已观察移除时）、collected_count、remaining_count、
collected/leftover（各最多32组）及drops实体坐标；collected_count固定0，collected为空，
remaining_count是移除时观测到的新掉落数量。留在地上是正常成功，不因未入包返回失败。
原版未报告成功但观察到移除时仍ok=false、removed=true；勿按原目标整次重挖。

place_block 的 item 是物品注册 ID，不是方块 ID；背包来源只用 0-35，
不交换主手或借用装备/副手。face 可选 up/down/north/south/east/west，省略为 up；
坐标须数值整数，数字字符串、小数或整数溢出拒绝，move_to 的共享坐标解析同样收紧。
以实际同伴、完整来源堆栈、指定面及当前朝向构造 BlockPlaceContext，
固定目标格，不把被占据且不可替换的目标自动转到邻格。
直接调用 BlockItem.place，原版负责朝向、支撑/碰撞、半砖合并、多格钩子、
方块实体组件和消费；不手工写默认状态或额外 shrink。
暂支持实际类为 BlockItem、BedItem、DoubleHighBlockItem、StandingAndWallBlockItem；
脚手架、告示牌及其他特殊子类拒绝，以免上下文重定向或专用交互越出当前边界。
邻域须在世界范围/边界内、已加载且允许交互，另核对 mayBuild/mayUseItemAt。
只有原版报告动作成功、目标状态已变且非空气、来源实际消耗恰好一件才 ok=true；
失败 data 给 changed/consumed_count，不假定零副作用或回滚；成功另给 placed 与坐标。
真实水中/替换格/半砖合并、多格、碰撞、朝向和组件落地仍需真实动作验证。

路径规划与执行共用 PathMaterials，只有无组件补丁的圆石、深板岩圆石、泥土、下界岩
计入 0-35 垫料预算；不自动使用重力方块、木材、工作台、贵重块或命名物品。
成功放置后还需确认真实支撑，否则停止移动；不因目标已占据就认定能站立。
开路掉落留地、可继续路线并记录留地量；原版报告失败则停手，即使目标已观察移除。
路径终态给removed_blocks/placed_blocks，按本趟实际执行计数。
observed_drop_items为各次移除时观测的新掉落历史总量；
remaining_items改为终态/观察当刻仍可见记录实体的数量，逐实体最多计原记录数，
合并增量不冒认为本趟产物。最多跟踪256个UUID、展示16个remaining_drops。
unresolved_drop_items给原记录中此刻未核实的量，remaining_items_complete只表示
移除时已记录量是否都仍可见，不代表全世界/全部模组战利品已覆盖。
实体消失/变少、合并、卸载或记录撞帽均不能证明入包；实际成果仍核对新鲜背包。
目前没有原版拾取/合并事件归因账，因此不能把未知去向分类为主人拾取或销毁。
途中失败/取消也保留已完成改动和同口径掉落观察，不把计划当作执行成果。
有限授权、缓存/重规划新增改动批准及统一资源互斥见下节，任务桥 v1.0 不变。
离线证据与范围见 DEVELOPMENT §3.6。

### 2.6 调度与权限（C3 实现/离线完成，真实行为待验）

同轮工具按顺序等待真正终态，ACCEPTED 只登记长任务，不放行下一项。
取消会给尚未派发的调用补齐历史回执，迟到事件不能继续派发。
同步动作、跨 tick 任务共用身体锁；挖放/容器/机器占目标及邻域，拾取占扫描区域，
路径在执行前原子取得具体改动邻域，冲突返回 BUSY，失败/取消/超时/身体替换统一释放。
锁只协调本插件动作，不锁真实玩家、原版机器 ticker 或第三方模组，也不是模组钩子沙盒。

任务入口支持明确的 `[只读]`（或 `[read-only]`）文本标记；若指令包含已列出的只读限制短语，
也只会收紧权限。策略在主人投令时计算，不读取模型的 read_only 参数。
只读允许 status/inventory/scan_area/find_resource/search_resource/inspect_block、craft query=true、smelt query；
其他身体或物品动作（包括wait）拒绝。
普通任务保留现有原子操作能力，工具仍按本次具体坐标、身体局部范围和原版规则校验。
这不是通用自然语言授权解析器：未识别的语言/否定表达不能声称已得到可靠语义解析；
需要确定只读边界时使用明确标记。只读任务不能通过反问升级，须另投新的普通任务。
当前“不要移动”“禁止移动”会触发整项只读，因此collect/transfer/equip也拒绝；
“不移动”没有被当前短语表识别，不能据此保证服务端禁止移动而允许拾取。
换措辞不等于权限粒度实现；需要“只准拾取”硬约束时先记录当前限制，
另卡协调任务能力，保留严格只读。RG5仅作评估，不修改上述允许名单。

服务端 ActionPermissions 将任务绑定发送者、同伴、task_id 和维度；
变更任务、任务结束、取消即撤销方案。旧客户端没有 task_begin/task_id 时，
仍可只读查询，修改工具拒绝；需与本轮客户端一起更新。这是内部 C2S 的收紧，
外部 REST/MCP/SSE 的 v1.0 字段、事件和终态不变。

服务端权限拒绝保留DENIED文案，内部tool_result的可选data附
`permission_denial {gate:"server_action",reason,tool,task_id?,dimension?}`。
reason分别为TASK_REQUIRED、TASK_IDENTITY_MISMATCH、READ_ONLY、TOOL_NOT_ALLOWED；
没有有效上下文时不附task_id/dimension，旧任务、身体替换和换维度由dispatcher返回TASK_REQUIRED。
只有有效只读上下文的READ_ONLY才能证明触达只读门，限流/白名单/owner拒绝不附此证据。
RG2游戏聊天命令 `/mcbot verify_readonly` 固定尝试wait(1)，保留真实派发守卫，
只接受当前任务/工具/维度匹配的只读拒绝；空闲前置和补测判据见REAL-GAME-RETESTING，
17项dispatcher离线回归及网络/模型未覆盖边界见DEVELOPMENT §3.11。

逐次确认模式的路线流程：move_to → NEED_CONFIRM → ask_owner(text,authorization_id) →
主人明确回答「确认」→ 服务端批准回执 → move_to(x,y,z,authorization_id)。
授权一次使用、180s 过期，绑定目的地和每一格的操作/完整 BlockState。
确认问题展示服务端生成的完整坐标/方块清单，忽略模型替代文案；未明确批准或超时不授权。
最多256个独立改动，完整清单12KB上限，超限要求缩短路线，不批准截断清单。
缓存只存节点，不存权限；复用/重规划重建清单逐项核对。
新增位置、改动类型、原状态变化或已经执行后被补回的格子，都不能借旧许可再次操作。
逐格挖放前复核，下一落脚格的脚/头/支撑变化先重规划，不直接进入新障碍。
may_alter_terrain 保留类型兼容，但 true 不能批准世界改动。
自主模式仅替代上述逐次批准要求，仍通过当前服务端任务上下文判断，
计划/执行复核与区域锁不跳过；只读任务不会因自主偏好而放行，详见§1.3。

collect 的中心需在身体6.5格内；实际候选同时在请求球体和身体6.5格内、已加载且存活，
没有拾取延迟，且 target 为空或匹配同伴 UUID。getOwner 是投掷来源，不能代替 target。
通过只读ItemTargetAccess取得原版私有target，并要求身体触碰盒相交；
生产调用原版playerTouch处理实际拾取/余量，统计/成就、动画和真实Mixin加载仍待验。
离线范围、测试与真实待验见 DEVELOPMENT §3.7 / STATUS。

### 2.7 有界近战（C4/D1 实现与离线验证，真实战斗待验）

先 scan_area 取得 entity_id 与 target_uuid，再用
`attack {"entity_id":47,"target_uuid":"00000000-0000-0000-0000-000000000047","max_hits":2}`。
整数编号必须为正并配合规范 UUID，避免旧编号重用误伤；
或者 `attack {"entity_id":"hostile_nearby"}`，从身体6.5格查询盒内最多64个最近候选中，
选择一只实际原版攻击距离内、可见、未命名且未受保护的 Enemy。
此选择只发生一次；后续锁定原实体引用/编号/UUID，不换目标或自动打分裂产物。
approach默认false保持原地；显式true可选择原范围内暂不可出手的目标，再只行走接近。
显式approach的身体/目标受固定初始脚格锚点64格球约束；自保仍为近场范围；
不挖放或自动换工具。目标位移明显才有界换路线，瞬时边缘变化不每刻重搜。
换路线、memo失效、地形重规划和暂停恢复累计诊断，每次搜索4000节点/32768记录，无默认总时间门，
无默认累计重规划次数门。工作到达按真实碰撞脚高、眼睛至目标AABB的AttackRange及射线，
出手仍须原版实际范围/视线，移动推进后同刻可合法挥击。详见D1-DYNAMIC-MELEE。
max_hits省略/0持续攻击原目标，无默认总时限；正整数仅用于明确的有限挥击请求。
ACCEPTED 不代表伤害已发生，等真正终态后再决定，不能重发或轮询。

参数拒绝保留 `DENIED:` 前缀，反馈现在包含固定原因码、字段及修正建议；
可选data附 `argument_error: {code, field, expected}`，三项均由本地固定定义生成。
不回显未知字段名、原始编号/UUID/授权值或完整参数，不新增自动重试。
按固定顺序报告第一个问题：未知字段 → entity_id → 对应target_uuid → max_hits → authorization_id → approach；
修正一个问题后仍须通过后续校验，不因参数合法而跳过保护/距离/授权。

| 原因码 | 字段/含义 |
|---|---|
| `ARGUMENTS_REQUIRED` / `UNKNOWN_FIELD` | args为空对象引用或含未知字段；线上非JSON对象仍由派发层先拒绝。 |
| `ENTITY_ID_REQUIRED` / `ENTITY_ID_TYPE` / `ENTITY_ID_VALUE` | 缺编号、类型/字符串选择器错误、数字不为正整数或超出32位范围；数字字符串不自动转换。 |
| `TARGET_UUID_REQUIRED` / `TARGET_UUID_TYPE` / `TARGET_UUID_FORMAT` | 显式编号缺UUID、UUID非字符串、不是标准小写36字符UUID；仍必须与扫描所得实体身份匹配。 |
| `NEARBY_TARGET_UUID` / `NEARBY_AUTHORIZATION` | hostile_nearby与显式UUID或具体目标授权不兼容。 |
| `MAX_HITS_TYPE` / `MAX_HITS_VALUE` | max_hits非数字、负数、小数或超出32位整数范围；省略/0表示持续。 |
| `APPROACH_TYPE` | approach不是JSON布尔，不将字符串或数字自动转换。 |
| `AUTHORIZATION_ID_TYPE` / `AUTHORIZATION_ID_VALUE` | 授权编号非字符串、空白或长度超过64；通过格式校验不等于已获许可。 |

例如数字字符串编号会返回 `[ENTITY_ID_TYPE]`，提示显式编号不能加引号；
缺UUID会返回 `[TARGET_UUID_REQUIRED]`，提示重新扫描并携带同一目标身份。
这项诊断不能反推旧T14现场的具体错误；新包实机回执另行采集。

每tick复核当前身体/维度、实体引用/身份、存活、保护规则、原版攻击范围及局部6.5格限制、
世界边界/已加载邻域/交互规则、视线、完整主手/选中槽及资源租约。
加载检查最多4096格，拒绝过大实体邻域，不为了视线强制加载区块。
等待 `getAttackStrengthScale(0)>=1`、目标 invulnerableTime<=10、
`cannotAttackWithItem(held,0)=false` 后，只调用一次 Player.attack 和主手 swing；
不手算伤害、不直接 hurt/setHealth/shrink。原版处理伤害、耐久、附魔、击退及统计。
本次原版回调后的主手耐久/破损可继续接受，外部主手变化则停手。
穿刺/动能武器与重锤暂拒绝；剑的目标 AABB 膨胀(1,.25,1)内有其他活物则拒绝，
即使当前运动本来可能不会触发横扫，也不冒险扩大本次目标范围。

玩家（含主人/同伴）、非Mob实体、同队、已驯服动物/马或有 owner reference 的生物始终拒绝。
未命名 Enemy 可直接执行；逐次确认模式的其余中立或命名Mob先返回 NEED_CONFIRM 和完整服务端清单。
主人选择的自主模式由同一服务端任务判据免去批准，仍先拒绝受保护目标，详见§1.3。
沿用 ask_owner/authorize，明确批准后重新携带相同编号、UUID、max_hits、approach及authorization_id调用；
hostile_nearby 不能携带批准编号，不能把具体目标许可改成自动选择许可。
许可绑定任务/身体/维度、目标编号/UUID/类型、Enemy/命名标志、挥击预算及approach/原锚点，
沿用180s/一次使用；批准后的原锚点由服务端恢复，不使用重发时新位置放大范围。
中途身份或命名分类变化重新检查；只读不能升级。
实体UUID资源锁独立于位置，目标走开不能让另一同伴认领同一只；
移动后的目标邻域仍须原子扩展区域锁，不与本插件其他动作抢区域。

data/feedback 同时给 strikes/max_hits、observed_health_loss/observed_absorption_loss、
entity_id/target_uuid/target_type、最后记录的 health/absorption/target_dead，以及 observation_known。
strikes 表示已经进入原版调用的次数，不等于命中数；生命/吸收减少是前后观测差，
不做独占伤害或击杀归因。上限完成可以 ok=true 且目标仍活着；死亡则停止，不主动追捡掉落，
身体原版触碰拾取照旧。显式接近另有data.approach：固定anchor脚格{x,y,z}/radius、
moving/search_nodes/search_records/search_nanos/replans/walk_only；不是伤害或击杀证据。
没有观测到生命或吸收减少则 ATTACK_FAILED，停止剩余挥击，不能自动重投。
取消/超时/异常通过 interruptedResult 保留此前出手计数，释放锁；原版回调抛错时
observation_known=false，最后一次结果未知，可能已有副作用，不回滚。
客户端主动取消时仍按既有立即补账/丢迟到结果规则处理，服务端保留部分结果不代表
它会覆盖已经完成的客户端取消回执。scan_area 的 entities 字符串兼容保留，
另加有界 entity_targets（最多20项），feedback 也含同一编号/UUID及保护/确认提示。

假连接不驱动 Player.tick；CompanionPlayer 的现有世界tick补两项近战时钟，
换主手物品类型重置，原版 onAttack 后持续充能；耐久/组件变化不重置同类物品时钟。
LivingEquipmentAccess 调原版 detectEquipmentUpdates 同步装备属性，不复制属性规则。
当前未开启完整doTick/自动playerTouch；被动重力/碰撞由身体每刻原版travel推进，
取消任务停止工具但不冻结下落，路径等待落地并按高度变化重规划，保留既有无敌身体；
自动防御、远程、追击、自动换武器/进食、受伤/死亡复活不在本卡。
Mixin实际加载、真实属性/冷却/伤害/附魔/耐久/横扫、玩家/模组并发与真实停手待安排，
原版/数据包/模组回调可能产生其他副作用，租约不是沙盒。离线范围见 DEVELOPMENT §3.8。

### 2.8 状态与任务资源感知（C5）

每轮模型请求前，宿主调用status details=true取得服务端同一线程中的身体、背包/装备、
游戏刻和调度槽观测；未知/失败/超时观测停止该任务，不复用旧快照继续猜测。
运行期快照只附在本轮请求尾部，不写入会话历史或改写真实工具回执，不改变system前缀。
status的旧位置/生命/饥饿/占用/手持/着火字段保留；新增game_tick/task及可选inventory。
task包含busy；忙时另给elapsed_ticks/cap_ticks/progress。路径报已提交节点与实际挖放，
攻击报已出手数，挖掘报原版实时累计进度，wait报已等刻数；耗时不是完成率。

find_resource的targets须1-8个实际方块ID或已加载非空#方块标签，如oak_log/#minecraft:logs，
不是物品ID或自然语言。r为1-16整数，默认8；查询以身体为中心，最近优先采样最多4096格，
最多返回16个绝对坐标。仅getChunkNow取得的完整区块可读，未知/界外不读，不加载或生成。
data同时给samples_planned/samples_examined/cells_read/unknown/truncated/results_truncated/no_targets。
分页另给offset/next_offset/scan_center/radius；按原targets/r及回执中心续页，
offset为0或4096的倍数、最大16384，非零须scan_center，身体中心变化在读取前拒绝。
next_offset=-1仅表示该中心/半径采样页结束，不证明未知格可读或全部匹配已输出；
truncated在非首/非末页仍为true，不能把单页当完整范围。frontiers=true只在末页给
exploration_points；scan_area可直接给同类样本，实际路线与发现仍由移动/新观测核验。
work=true保留全部已读匹配，用已读邻格的非实心/未知/封闭分层预筛，
不额外读邻格或把未知当露出；最多24目标、整次查询共享2048次原版轮廓射线，
每个工作计划最多64射线/3遮挡。先评当前眼睛，再评站位，最终将已核对直接工作、
可局部清理、有站位的候选排在受阻诊断前，最近16埋藏匹配不再直接占满输出。
can_work_here为无需清理；can_clear_here为当前有安全几何计划（可能零遮挡），
clear_blocks为计划遮挡数。work_checked/work_checks/work_traces/work_truncated
明确工作评估边界，WORK_BUDGET是未知，不是已证明不可工作。
站位最多6、完整地板规则不变；查询不证明路径可达或已取得清理许可。
普通模型只消费工具feedback，程序另外可读取data；work=true现在也把现有的
直接可挖/可清遮挡数/工作失败与候选脚格写进feedback，并给对应work_target。
移动需选脚格、exact=true及work_target，不把矿格当落脚点；不额外读取世界或增加射线预算。
候选只证明采样格中存在方块，不证明露出、可达、采收资格或实际掉落；空结果不证明不存在。
scan_area同样给no_targets并明确分类遗漏木材，不鼓励挖掘探查或改变半径持续空转。

inspect_block只读6.5格内已完整加载的方块和其自己的Container，offset默认0、范围0-1023，
每页24槽；data给slot_count/slots/next_offset（-1末页）。锁定、失效、未展开战利品容器拒绝。
物品显示注册ID/数量/耐久，原始组件与自定义名称不出站；不搬物品/开GUI/展开战利品，
不合并双箱或读取整个存储网络。熔炉原料/燃料/产物的实时进度仍用smelt query。
纯MC物品/状态与生产工具回归通过；实际区块缓存/身体/容器世界行为仍待验。

### 2.9 有界流程组合（C6）

`workflow` 只协调现有工具，不是脚本执行器或自动规划器：

```json
{"steps":[
  {"tool":"smelt","args":{"x":2,"y":90,"z":0,"action":"load","input_slot":0,"input_count":3,"fuel_slot":1,"fuel_count":1}},
  {"tool":"wait","args":{"seconds":30}},
  {"tool":"smelt","args":{"x":2,"y":90,"z":0,"action":"query"}},
  {"tool":"smelt","args":{"x":2,"y":90,"z":0,"action":"take","count":3}},
  {"tool":"inventory","args":{}}
],"timeout_seconds":90}
```

坐标、来源槽和数量须来自当前观测；示例不是固定配方/烧制时间承诺。
允许 status/inventory/equip/scan_area/find_resource/inspect_block/break_block/place_block/
craft/smelt/wait；不允许 move_to/attack/transfer/collect/ask_owner/嵌套workflow。
近身采集用显式break_block坐标，不从搜索结果自动填参数、不移动或递归补材料。
下一步若依赖新结果（例如制作后的物品槽），结束当前流程再规划。

开始前校验整份参数/工具名单/数量/只读模式，错误的后半段不能先执行前半段。
最多12步，每步挖放最多一个显式目标；时限默认180、1-300秒，子步骤等候也计时。
制作执行count、装料input_count/fuel_count、取出count均须明确填写，
连同放置每次1件，请求量合计≤128。预算限定**请求数量**，不是原版材料消费或产量上限：
craft按完整配方取整且可能使用多格材料/返还物；break掉落和place多格效果由原版处理，
不裁剪实际物品或把请求数当进包量；原工具的配方次数/范围/条件帽继续生效。
没有数量契约的批量存取/拾取不纳入，未来须先补原子工具数量契约。

每一步走现有task_id和executeRemote，三道闸/ServerActionGate/身体锁和原版条件仍生效。
tool_result或真正job_event终态才推进，job_ack只转段等待，不进模型历史。
失败（含部分副作用）、craft查询can_craft=false、搜索no_targets=true、
机器无燃料/配方/输出阻塞/区块不推进或本批燃料不足、需确认即停，不重试/轮询。
授权编号仍由runner保存，需要确认时退出流程，让模型走既有ask_owner明确确认。

返回逐步真实反馈与terminal_steps/total_steps；ok=true仅表示清单各步取得成功终态，
不证明主人目标达成、装料已烧完或take拿满请求数，短取出量按实际回执汇报。
累计反馈超过12000 UTF-8字节不派发下一步，结果全文展示预算24000字节，
超长单条明确标未展示全文，不把截断内容当授权清单。
取消/重载/关闭/时限先停止推进、清子票并发cancel，保留已取得回执，在途效果未知；
迟到终态不能启动下一步。不回滚已执行动作，不熄炉或自动取回装料；
取消后必须按最新状态再决策。

AgentLoop另设当前任务累计三次空搜索上限（含workflow搜索）：
变半径/目标、插入status/inventory/inspect/wait均不重置，找到目标或实际物品进展可重置。
第三次空结果停止本轮尚未派发工具并补配对回执，FAILED汇报范围，不声称资源不存在。
新主人指令重置计数；原40步帽不扩大，不从反馈措辞猜no_targets。

## 3. 回执词汇表（模型行为约定）

| 前缀 | 语义 | 期望的模型行为 |
|---|---|---|
| `DENIED:` | 参数非法/未知工具/超频/无同伴 | 改参数或先备条件，别原样重试 |
| `BUSY:` | 同伴正忙上一件事 | 等待、或叫停再派新活 |
| `TARGET_LOST:` | 目标不在加载区/已被动过/已是空气 | 先靠近/重扫再下结论 |
| `OUT_OF_REACH:` | 超出臂长（≈5.5 格） | `move_to` 靠近后重试 |
| `WRONG_TOOL:` | 当前主手无采收资格、已改变或进度无效 | inventory/equip 换合适工具，目标未按本工具继续破坏 |
| `BREAK_FAILED:` | 未确认原版破坏成功，可能已有耐久或世界副作用 | 核对实际目标、背包和工具，勿自动重试 |
| `PLACE_FAILED:` | 未确认目标格成功放置或不能形成路径支撑 | 核对回执的目标变化/实际消耗，勿自动重试 |
| `TARGET_BLOCKED:` | 工作视线、占位或放置条件不满足 | 查看具体遮挡或placement_failure；按事实换位/补条件，不原样重复 |
| `SCOPE_LIMIT:` | 已观测匹配候选被固定任务/本段范围排除 | 核对scope_exclusions，保留范围/预算，不将其解释为没有资源 |
| `ATTACK_FAILED:` | 已出手但没有观测到生命或吸收减少 | 核对目标/工具与实际状态，不能自动重投 |
| `OCCLUDED:` | 目标被遮挡，不能进行本次近战 | 重扫或显式移动，不隔墙攻击 |
| `UNSAFE_SWEEP:` | 剑的横扫邻域有其他活物 | 更换非横扫工具或等目标分开，不扩大授权对象 |
| `NO_RECIPE:` | 没有匹配的普通合成/烧制配方 | 核对物品/配方 ID 或机器类型，特殊制作换对应工具 |
| `MISSING_MATERIALS:` | 来源材料数量不足 | 对照回执与 inventory，取得材料后再调用 |
| `MISSING_FUEL:` | 新原料没有可用燃料或现存燃烧余量 | 用 load 同时供给燃料或先备燃料；本次未移动 |
| `INVALID_FUEL:` | 指定物品不是当前机器可用燃料 | 从 inventory 选可用燃料；空桶是返还物 |
| `SLOT_BLOCKED:` | 机器原料/燃料槽组件不匹配或装不下 | take 回收对应槽或减小数量；本次未移动 |
| `OUTPUT_BLOCKED:` | 机器下一件成品无法进入产物槽 | take 取走已有成品，再查询/装原料 |
| `NOT_READY:` | 机器产物槽当前为空 | 看机器状态，补足条件后 wait/query，别原样连发 take |
| `EMPTY_SLOT:` | 要回收的机器原料/燃料槽为空 | query 核对实际槽位，不换槽盲取 |
| `NEED_WORKBENCH:` | 合成需要附近工作台 | 扫描、靠近，或显式放置背包里的工作台 |
| `INVENTORY_FULL:` | 整批成品或配方返还物装不下 | 存入容器腾出空间后再调用；本次未消耗材料 |
| `UNBREAKABLE:` | 生存手段不可破坏 | 换目标 |
| `PATH_BLOCKED:` | 前方堵死/超搜索盒 | 绕路/拆障/分短段重发 |
| `NEED_CONFIRM:` | 路线改动或中立/命名目标攻击尚未获具体许可 | ask_owner 携带 authorization_id 展示服务器清单，获主人确认后用相同参数和编号重发对应工具；未同意就停手 |
| `NO_PATH:` | A* 搜索空间内无路（真封闭，**永不降级**）/无支撑 | 换路线方向或先造条件（拿材料/拆明障） |
| `PARTIAL:` | 撞搜索帽但已实质推进：半程段已走完，停在"能到的最近点" | 从回执里的当前点**重发 move_to**（可分多段抵达），别当失败 |
| `NO_PROGRESS:` | 撞帽且连短程都没推进（目标方向被纠缠堵死） | 换方向/换目的地，硬撞同一方向无益 |
| `ACCEPTED:` | **受理回执**（R2-S4）：这件事被受理了，**还没有结果** | 别重发（会被 BUSY 挡）、别干等；做完系统会主动报同一编号；这期间可以回主人一句话 |
| `CANCELLED:` | 主人主动叫停 | **停手**，向主人确认下一步，不许自作主张续上 |
| `SUPERSEDED:` | 被新指令顶掉（R2-S4，**本地合成**，不是服务端发的） | 别自作主张续上；要做就重新发一次 |
| `TIMEOUT:` | 服务器/主人超时未回执 | 别重复该操作，向主人说明 |
| `INTERNAL:` | 服务端异常/参数非 JSON | 报障，别重试 |
| `STATE_UNAVAILABLE:` | 本轮身体观测无有效结果 | 任务停止，说明无法取得状态，不能猜测完成 |

> `CANCELLED:`/`SUPERSEDED:`/`TIMEOUT:` 三种都会作为 `job_event` 的 `phase`
> （依次 `cancelled`/`superseded`/`failed`）出现，**相位与文本前缀分开**是有意的：
> 相位给客户端/面板做状态，前缀给模型做教学。

## 4. 加一个工具（配方）

1. `src/main/java/com/neko/mcbot/server/tools/` 新建实现 `ServerTool`：同步覆写 `run`；
   跨 tick 覆写 `runAsync`（构造 `TickTask`，`scheduler.submit` 撞 BUSY，设任务帽）。
2. `McbotMod.onInitialize` 的 SERVER_STARTED 里 `tools.register(new XxxTool())`（白名单）。
3. `ClientToolDefs.SPECS` 加同名描述 + JSON Schema（服务端校验 args 必须是对象）。
4. 参数校验失败要回 `DENIED:` 教学文本；成功回执里写清数量与位置。
5. 自测：SelfTest 直调（无头）→ 真机 `@bot` 驱动一遍；两处证据进 STATUS。
6. 桥接层**不用改**——它是任务级的，原子工具对 neko 不可见（by design）。

### 4.1 要不要走「受理即回执」（ACCEPT）？

默认 **不**（`acceptanceMode()` 返回 `SYNC`）。只有**跨 tick 且真的会花几秒以上**的活才覆写
`ACCEPT`——目前为 `move_to`（3600tick≈180s）、`break_block`（1200tick≈60s，挖一格实测 6–7s）
及有界 `attack`（400tick≈20s，可能等待冷却与目标恢复）。

短活走 ACCEPT 是**净亏**：先回受理再回结果 = 白多一跳，而且模型还得再问一次"好了没"。
另外注意服务端只有**一具身体 + 单槽**：走 ACCEPT 不会让两个"占身体"的活并行
（第二个仍会被 BUSY 挡），它省的是"大脑被一条长活占住"。

覆写时要**同时**给三样，缺一个就会出问题：

| 成员 | 为什么 |
|---|---|
| `acceptanceMode() → ACCEPT` | 决定走不走 job 通道 |
| `capTicks(args)` | **两个消费者共用一个来源**：`scheduler.submit` 拿它做服务端超时，派发层拿它算给客户端的受理回执（客户端等待上限 = cap×50ms+15s）。两边各写一份，就是"服务端允许跑 180s、客户端 90s 就判 TIMEOUT、真回执被当迟到丢掉"那条真缺陷 |
| `acceptSubject(args)` | 受理文案里"我在干什么"那一小段（如 `走到 12,63,-4`）。只给主语，统一模板说明还没结果、不要重发/轮询、后续工具等终态及做完主动报编号 |

`runAsync` 里 `sched.submit(..., capTicks(args))` 要传**同一个** `capTicks(args)`，别写死数字。
快路径（`DENIED`/`BUSY`/`TARGET_LOST`…）照常直接 `return CompletableFuture.completedFuture(...)`：
派发层发现 future 已完成就回普通 `tool_result`，不会走 ack。**这也是要遵守的**——
先 ack 再立刻报失败等于白多一跳。
