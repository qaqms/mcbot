# mcbot 架构（as-built）

> 本文描述**已建成**的结构；施工前的完整蓝图见根目录 `mcbot-DESIGN.md`，
> 进度与 API 防漂移笔记见 `STATUS.md`。与代码不一致时，以代码为准并请修订本文。

## 施工出口与真实进展（2026-10-25）

活动工地的BlockPlacement在实际预检、动作前及累计地形工作位预检中调用StandingGeometry：
只对站位附近的预测变化核放置前后局部干燥出口，门/床纳入两格，地形计划与实际碰撞共用。
原有可走步骤被最后一格封死时报would_seal_exit，工作导航可以改从外侧完成同格。
不做全工地连通保证，不扩大挖脚下/通道/成品/设施权限；已无出口的现场仍需可执行的解困动作。

BuildSurvey保留observation_key与分页完整/稳定复核；另给work_observation_key，
由完整目标实际状态、目标库存量、身体实际坐标及全库存物品/组件指纹构成。
mc.build仅用后者重开本次失败候选，粗区块stamp变化不再令cursor归零重试；
局部场外障碍变化不会在同一次调用中单独重开旧候选，下一次调用按新鲜观测重新评估。
PathTask只有NO_PATH穷尽、expanded=1且created_nodes=1才返回departure_blocked，
Lua仍核新鲜status并返回原失败证据，但不继续枚举所有远端目标。
普通局部失败仍换候选，节点/时间资源失败不误报封闭起点，不增任务次数门或输出预算。

design_components只描述整图门/床主格、工位、收纳、光源、楼梯与半砖，不自动判定美观，
不强迫非住宅目标具有家具。原创建筑知识从空间用途、门地板脚高、屋顶剖面和实际家具着手，
登记前读整图计数查漏，冻结后仍复用原目标；没有固定房型或新规划层。

## 屋顶下地（2026-10-25）

DigAStar在原邻接边之外枚举四向两/三格干燥下降，PathTransition独立校验已有落点、
整段净空与StandingGeometry原版碰撞步进，不给这类边附加挖放动作。脚高使用实际碰撞面，
不能从半砖节点整数差漏掉额外半格；危险/未知碰撞仍拒绝。
StandingGeometry以原版BASE_SAFE_FALL_DISTANCE与当刻SAFE_FALL_DISTANCE较小值决定
普通下降能力；快照冻结同一值，采用和执行仍以现场适配器复核。
现有PathSteering逐刻输入/重力完成下落，较深下降不适用一级连续通过，必须落稳再续工。
地下原相邻台阶与半程返回复核保持；未迁移参考的伤害预算下落、水桶接落或直接写世界。

## 连续施工与普通搜索接续（2026-10-24）

本节以[完整住宅与大图纸](COMPLETE-BUILDINGS-2026-10-25.md)及STATUS顶部覆盖旧128格描述。
BuildTarget/WorldGoal共16384格，regions紧凑源与规范内容哈希分开，检查点保留原图。
BuildSurvey全目标计数/料单、有限明细与work_scan候选分开；BuildWork直接全目标选近身格，
mc.build消费有限候选并逐格重验，不在Lua攒全部页。公共mc.build_diff大于32格保留有限页。
床与门共两格足迹、唯一主动作和两格验收；空单箱/木桶及常用装饰沿原生放置。
空格预测朝向不符可走原工作导航，原图不改。没有直接世界写入或新增并行模型。

最新以[原登记设计复用](GOAL-DESIGN-AND-STARTUP-2026-10-24.md)为准。
WorldGoal保留完整条件，AgentLoop.registeredGoal以深副本提供当前任务真源；
程序启动快照经mc.goal读取，mc.build()/mc.build_diff()省略target时携带原修订复用。
set_goal operation=query只读，不重设目标；独立事实账在整理后仍说明原图可用。
内置施工成功回执计数/样本及所用字段视图减少重复输出/转换，公共Lua数据和原面板/
事件保持；失败全文与最终新鲜验收不省略。导航账务方法与单挖来源对象分别解析。
不新增另一套持久化或并行模型，原身份/许可/取消/资源保护保持。

本节后续以[连续清土与施工分页](CONSTRUCTION-PROGRESS-2026-10-24.md)及STATUS顶部为准。
mc.build内部重复查询显示合并但完整data、原面板/事件和失败保持；原20KB结果保护不扩大。
同层清障共全页观察且逐格重验，默认批次12动作；直接返回并自动输出实际进度与剩余缺口。
完整稳定观测中的不完整料单不再阻止其他可做格；默认不因同观测候选重复而死循环。
mc.build_diff复用原工具自动固定修订续明细/料单，build_nearby省略offset逐刻查全目标近身格。
显式页范围、原CPU/单格动作/材料/取消/权限保持；page与whole统计分开。
整块玻璃沿原版预检/消费/验收，其他复杂方块边界保持。没有并行模型或直接写世界分支。

详见[施工连续性](CONSTRUCTION-CONTINUITY-2026-10-24.md)，最终证据以STATUS顶部为准。
工地共用导航工厂传递真实剩余挖掘额度；普通搜索无默认整趟节点/记录完成配额，
4000节点为可续批次，32768记录为增量整理水位；显式有限账户仍约束。
工地未来格不代替现有碰撞，可清的同修订未满足格由原导航/BlockMining共用守卫；
树叶与静态整块不冒充贴附/重力依赖，真正占位、设施与完成格仍保护。
放置工作省略face时检查六面，普通无状态整块可按计划改动评估工作位，实际执行重查现场。
mc.build先准备低层，局部失败后按新鲜差异尝试其他格；活动工地也可预搜后段。
生产知识目录增加按需内置建筑笔记，保留用户覆盖/禁用，不增工具或并行模型。
下方默认整趟距离配额、32768记录终止、工地禁预搜和未来格禁站属于历史。

## 工地地形与连续准备（2026-10-24，前次检查点）

详见[工地地形与清障](BUILD-SITE-TERRAIN-2026-10-24.md)。
build_site仅需完整目标，不强制先声明通道或固定64格工地球；显式bounds仍保持，
可选通道用真实脚高/碰撞/相邻移动核验并保护实际身体所占格，允许草花与分数地板。
mc.build通过工作目标导航换站位，按原权限挖放，不再固定walk_only。
地基的未建目标与实际完成支撑分开，普通草花不冒充承重依赖；定向施工可翻修已走过的目标格，
实际身体占位、设施、来源、已完成支撑、显式通道及无关路线保护仍有效。
历史路线缺口只在需要证明通行依赖的临时回收中拒绝，不再禁止整个工地。
build_diff当前页增加实际surface脚格/脚高/支撑样本，保留真实手距分类、全目标核验及查询预算。
本体未复制参考代码或改为直接写世界，所有修改仍走原挖放入口；原生/模型效果待实机。
下方必填水平通道、默认64格球与只行走施工属于此前检查点。

## 库存采掘工具与等待显示（2026-10-24，前次检查点）

详见[采掘工具与等待显示](TOOL-EFFICIENCY-AND-WAITING-2026-10-24.md)。
MiningTools以真实库存/原生属性副本统一观测、路线估价与实际选手；BlockMining在原守卫后
才换手，同一方块期间外部换手仍停止。ClearingMining同步自身换手，gather复用库存采收资格。
地下按实际TOOL组件识别备用，原reserve_durability传到PathSpec并逐格按采收资格/原版消耗保留；
不把备用耐久视为全矿种资格。制作/升级仍由模型根据真实配方决定，没有新增成品链。
精确只行走位置目标先核实际碰撞站位并返回现场脚高建议，其他语义/可编辑路线保持。
气泡结构化modelWaiting经不可变Frame和单调时钟显示旋转，不参与控制或模型调度。
真实模型选择、原生注入/运动及GPU画面仍待实机；下方仅当前主手估价按旧检查点阅读。

## 掉落触碰目标与半程候选（2026-10-24，前次检查点）

详见[木头采集拾取寻路](WOOD-PICKUP-NAVIGATION-2026-10-24.md)及STATUS最新节。
move_to可用pickup_target_uuid选择同一实体的实际触碰落点；PickupGoal按身体尺寸、
碰撞脚高及累计地形判断，可独立快照。PathTask逐刻核实体/加载/归属/范围，
附近下降物品在搜索阶段等落稳，显著移位刷新目标，原版接触/背包决定入包。
BoundedGather不再逐个重搜精确拾取样本，无样本也可规划；同观测去重，真实现场变化再评。
DigAStar生成时保留少量不同坐标候选，批次末只对候选和原最近已展开节点核可逆前缀，
父链仍计入存储。原批次/账户/材料/权限保持；普通挖矿目标及疾跑策略不调整。
完整编辑后台/跨段学习及原生实机效果未完成，下方逐站位拾取按旧检查点阅读。

## 平地疾跑（2026-10-24，前次检查点）

详见[平地疾跑](SPRINT-MOVEMENT-2026-10-24.md)。MovementInput逐刻帧增加sprint，
CompanionPhysics在环境接管后按身体资格和正向输入调用原生setSprinting，再走原travel。
导航清待发输入与真正停止分开，连续节点不切断疾跑，取消/避险/未知区块仍释放。
PathTask仅在同向平坦无编辑三边窗口、真实全脚底/侧向净空和标准摩擦刹停条件下续发；
坡地、转弯、冰面、施工和末段保持原行走。sprint_input_ticks仅计请求。
没有迁移参考完整坡地疾跑/疾跑跳，不改搜索/挖矿/模型预算；实机消耗/动画/运动待验。

## 格边起步与剑攻击（2026-10-24，前次检查点）

详见[格边起步与剑攻击](EDGE-DEPARTURE-AND-SWORD-2026-10-24.md)及STATUS最新节。
PathTask首次搜索/重规划前核实际脚高对应的中心站位；无效时StandingGeometry只在
实际脚底覆盖列中找同高合法点，检查短距离扫掠和连续支撑，按原范围/步骤约束用输入挪回中心，
身体落稳后从新鲜脚格开始搜索。取消/暂停清理，不修改坐标、地形或原账户。
PlayerAttackAccess只读调用原Player.isSweepAttack，AttackTool先判距离/视线，
再按原版旁及的同队/marker/距离排除检查；可能波及未选中对象仍拒绝，不自动换武器。
挖矿搜索/清障/采集规则保持，新增能力只是共用导航起点恢复。
原生Mixin注入/玩家travel/实际伤害、完整现场和模型选择仍待实机，离线结果见STATUS。

## 追击、挖矿与补给（2026-10-24，前次检查点）

详见[PURSUIT-MINING-AND-SUPPLY](PURSUIT-MINING-AND-SUPPLY-2026-10-24.md)和STATUS最新节。
EntityApproach显著目标更新通过PathTask.retarget保留原路径、cursor、在途搜索与原账户；
按目标版本处理旧失败，当前末点不满足新目标则接续，默认动态路线也可预搜。
DigAStar分段先求可行答案，无答案时再建可选估价；首答案后最多64次改善展开。
低顶上阶将可清理起跳净空列入真实有序清单，直接半砖步行不额外清顶。
无编辑/跳跃的单级下坡可连续通过，下降末段避免空中追中心回拉，最终和段尾仍须落稳。
掉落增加可能需挖放的触碰脚格候选，沿原工具/材料/范围/许可导航；无同UUID六站位累计门，
只跳过已试坐标，最终仍以新鲜背包为准。尚非通用实体触碰语义导航。
默认模型轮数原本不限，面板删残留/40；地下给真实耐久/预留及durable_spares，
模型按配方补材料/工具后恢复原目标，不新增成品链或独立依赖规划器。
完整学习/紧凑节点/编辑后台及原生长追击、矿洞和模型补给决策仍待后续验证。

## 执行中路线预搜与存储（2026-10-24，前次检查点）

详见[挖矿接续与存储](MINING-CONTINUATION-AND-STORAGE-2026-10-24.md)和STATUS。
PathTask执行期间通过NavigationContinuation在世界线程余下切片推进一份后段搜索；
DigAStar初始地形可携带前段未落地编辑，材料/挖掘先预留，共同账户逐搜索增量结算。
衔接仍要求实际站稳，采用前现场重建清单和复核；未完成继续等，失败结论交原搜索出口，
改路/取消/避险作废。动态目标、活动工地和显式累计导航账户暂不预搜。
DigAStar到原存储边界先分片回收失效堆项和无引用记录，所有有效父链和编辑保持；
真实上限仍保留，历史分配和当前保留记录分列。没有恢复模型/工具默认次数门。
完整学习、编辑后台、坡地无刹停及不同工具间模型并行未实现，实机效果待验。

## 工作目标与连续支撑（2026-10-24）

当前见[工作导航与朝向修正](WORK-NAVIGATION-AND-LOOK-2026-10-24.md)和STATUS最新节。
采集统一按工作目标搜索有效站位，不再逐个强求精确样本；失败仍先核真实库存/范围。
工作干路可用独占快照后台搜索，编辑回退仍保留原世界线程分片及整趟账户。
普通路线垫路料可在累计地形中验证连续支撑/点击面，原版逐块执行及许可/扣料不变；
活动工地和特殊物品保持原预检，未迁移完整编辑后台或行进中预搜。
共同BodyLook保留连续yaw和上一帧角度，落点制动保持朝向，近战冷却继续注视合法目标。
原job_event传递合并的实际导航阶段，仅更新显示，不唤醒模型或解锁PARK。
旧节“工作到达只在主线程”按历史阅读；真实运动、搭路和视觉效果仍待实测。

## 探索搜索接续（2026-10-24）

当前见[探索接续修正](EXPLORATION-SEARCH-CONTINUATION-2026-10-24.md)与STATUS最新节。
分段预估与展开分账，保留共同存储/时间切片；多个候选按最终编辑地形验证返回，
不可逆尾部裁掉后用该前缀自身地形再验证。候选扫描同样在advance切片内执行。
没有可执行前缀时，节点批次结束可保留未展开队列继续，整趟账不重开；
时间/存储到界不续批，地形缓存变化先重规划。后台结果发布后可转世界线程切片接续，
原共享账只计新增加的资源；search_diagnostics.batches记录该搜索使用的批次数。
地下允许以真实库存、原版点击面/碰撞与原许可补支撑，walk_only仍零挖放；
慢速单格挖掘门槛从20秒调整为参考的60秒。没有新增模型或工具次数门。
尚无完整跨段估价学习、编辑后台快照与行进中预搜，不能保证所有路线成功或不停顿。

## 身体经验拾取（2026-10-24）

BodyPickup在原物理步进后的身体tick中，同时负责物品接触与经验球接触。
原版Player.tick未被FakeConnection驱动，因此单独递减takeXpDelay；
冷却不因只读或邻域未加载而重置，死亡/移除等非活动身体不推进拾取。
经验使用原物品触碰盒，仅查已加载邻域，冷却归零时随机选一个活经验球，
调用前重验身体、权限、同世界及接触范围，再执行原版ExperienceOrb.playerTouch。
合并球计数、冷却设置、经验修补与剩余经验归原版；不直接加经验、不远程删除球。
空闲/工作共用既有passivePickupAllowed，活动只读任务仍抑制所有拾取。
经验/等级与装备耐久变化由原BodyChangeLog记录；不增加工具或抢占身体控制。
参考副本通过Physics.step里的完整玩家doTick推进这些行为；本体仅补齐遗漏的
经验触碰与冷却，不切换物理入口，避免重复travel/baseTick和绕过现有只读门。
离线验证范围和真实经验球/修补待验以STATUS最新节为准。

## 地下范围与分段（2026-10-24）

当前见[地下导航修正](UNDERGROUND-SEGMENTS-2026-10-24.md)与STATUS顶部。
ExplorationRoute新任务默认总半径0不限，显式有限范围与历史证据保持；
BoundedSurvey不按曾经尝试同终点禁发，无总半径时不向move_to附加bounds。
DigAStar生产入口启用贪心可行解/加权改善并共享资源，保留已有可行答案，
几何剩余量下降32单位可交出中间段，可逆部分段也可执行后继续。
PathTask从实际站稳脚格重建下一段；NavigationAllowance按初始距离分配整趟节点账，
和NavigationBudget的显式有限共享账户同时约束，累计效果/许可不重开。
MoveToTool去掉整趟64/32距离门，局部采样窗仍移动且有界，远目标仍须已加载。
未实现完整成本估价/LSS-LRTA学习、编辑后台快照或行进中预搜接续；段末仍可能停顿。

## 动作与采集验收修正（2026-10-23）

当前动作契约见[ACTION-LIMITS-AND-GATHER](ACTION-LIMITS-AND-GATHER-2026-10-23.md)。
EntityAttack默认max_hits=0持续执行，attack/break_block/move_to的cap_ticks=-1
同时贯通scheduler和PendingJobs；显式有限额度/批准、取消与实际失败仍有效。
自保去掉400刻/10次及来源满64条停用，近期来源滚动去重。
BoundedGather材料subtask默认total，完整实际生成错源后先新鲜status核验再交回模型；
最终目标、精确物品与真实效果不放宽。旧单路线128格、射线清障3格门已取消。
搜索、射线、身体与来源存储仍有资源保护；工作到达主线程回退和段间/坡地缺口保持。
按维护者追加要求，逐次搜索改为4000节点、无默认400ms总时间门；
旧默认账户恢复迁移，显式有限账户不放宽。参考整趟估价账户/32格接续仍未完整迁移。

## 快照导航与执行策略（2026-10-23）

当前以[NAVIGATION-AND-EXECUTION-POLICY](NAVIGATION-AND-EXECUTION-POLICY-2026-10-23.md)为准。
默认模型/程序/阶段/探索/工地累计次数门取消，失败记忆仅诊断，不按重复或空搜次数拒发。
普通位置和近战到达先用NavigationSnapshot复制已加载区段，再交NavigationSearch后台搜索；
世界线程采用前核当前起点与路线，执行逐边核，取消丢弃迟到结果。
工作到达、编辑、工地和约束仍有主线程分帧回退；每次搜索资源保护保留，
默认累计两次重规划门取消。攻击边界进出保留有效路线，平地中间转弯支持连续通过。
未实现段间预搜、坡地连续步态或参考完整自适应资源账户，旧节中的默认次数帽按历史阅读。

## P1 导航诊断首阶段（2026-10-23，历史检查点）

PathTask在原tick入口采样PathTiming的单调经过时间、互斥阶段及输入/节点计数，
以原task/body及进程内route编号关联计划和退役摘要；普通/中断终态data增加path_timing。
不把计时表加入每轮status路径progress或模型feedback；诊断不参与运动/搜索预算决策。
EntityApproach沿原账户报告路线创建、目标更新及入攻击距离释放等原因计数。
active不等于线程CPU，空输入不等于浪费；P1仍部分完成，未改导航连续性或模型等待契约。
实机日志与测量边界见P1-PERFORMANCE-TIMELINE-2026-10-23，进度以STATUS为准。

## K1 知识目录与任务快照（2026-10-23）

SkillCatalog在客户端从旧顶层Markdown/可选defaults和index.json生成有界不可变知识快照。
AgentRunner在启动与原onTaskStarted实际开始事件刷新，同PromptBuilder生成常驻目录，
直接或Lua的tool_help从同一快照按name/next_name读取正文；链中/摘要不重新读盘换内容。
本地覆盖、禁用和坏设置保守处理，旧上下文正文不靠“已加载”标志保留，整理后从索引重读。
仍31工具/原参数Schema，无任意文件访问或服务端知识权限；原动作核/任务账不变。
这是模型上下文按需披露，本地仍有边界预读成本；机制、错误和发现轮次代价见
K1-SKILL-KNOWLEDGE-2026-10-23。下方技能全量描述为历史检查点。

## L2 目标数据恢复（2026-10-17）

GoalJournal在客户端按世界/主人/同伴范围保存原AgentLoop长期目标和host累计账；
先写在途标记，完整结算且新鲜空间可用才开放检查点。ClientSession退出停执行，
进世界不自动唤醒；主人用原submitTask明确恢复，原goal_id保持而session/传输task_id更新。
BuildRecords持久世界UUID，playerdata持久body_continuity_id，瞬态body_id保持S3语义；
死亡/显式召唤换continuity。服务端新task_begin复核身份及空间/工地修订，失败撤新权限。
原loop先核现场、原基线/40步/阶段/失败账；已满足零模型，否则在原剩余额度内选择阶段。
WorkFailureMemory、DeliveryLedger、ExplorationRoute及原WorkTargets/NavigationTrail/BuildSite/
NavigationBudget只恢复数据；临时/B4来源见证不伪造，不回放工具/Lua/批准或输入。
单记录512 KiB、128范围、空间16000 bytes，未知/在途/换箱/修订或身份变化保守停止；
只支持当前客户端数据，跨文件崩溃事务及原生重进仍待验。详见L2-GOAL-RECOVERY。
下方“无L2恢复”为历史检查点，外部桥v1.0保持。

## L1 多类目标的独立验收（2026-10-17，历史检查点）

原AgentLoop保留ItemGoal，并以WorldGoal冻结building/delivery条件；原GoalContinuation
在同task_id内处理阶段，不新增模型裁判、动作核或规划系统。
原runner带目标observe先details status，再经原工具/身体/权限门读取build_diff verify_only
或inspect_block item；登记及段尾查完整现场，每轮仅展示上次核验与新鲜身体事实。
BuildSurvey仍核全目标/明确属性/稳定版本，goal_observation_key不纳入无关动态状态或区块版本；
ContainerIdentity复用原CellWitness和实际容器/区块/世界弱身份，不继承换箱或新身体。
原transfer核实际双向库存变化，executeRemote同源终态供直接/Lua的DeliveryLedger记净账；
additional同时需要登记后净存入和同容器净增，total只验当前存量。
原阶段/模型/探索/许可等账不重开，follow终态不替代外层目标；仅明确超距可按原账续材料阶段，
未知/替换/死亡/取消不续跑。完整支持、数量帽与原生待验见L1-GOAL-VERIFICATION。
外部桥v1.0不变，本卡不接L2目标持久恢复；下方未实现建筑/交付按历史检查点阅读。

## S3 死亡与身体实例隔离（2026-10-17）

BodyLifecycle在原die入口先发布死亡，再撤精确身体权限、结束原scheduler/反射/手部占用，
最后由原版结算死亡和F2同源death掉落。原生作用导致死亡后，物理步不再推进后续作用。
客户端原AgentLoop统一失败活动和排队目标，清理模型/整理、工具/长活/反问及Lua等等待；
任务控制、批准、取消、动作均冻结body_id，服务器按当前实例复核，同伴UUID保持稳定。
原生restoreFrom(false)在死亡世界结算keepInventory后，把全新CompanionPlayer注册到主人当前层，
复用StandingGeometry有界安全搜索；无旧输入、攻击者、许可或任务自动续跑。
版本化playerdata只存死亡结算/原身份/累计搜索及有界快照，不恢复目标；未知或部分重建失败停用。
短任务/长期阶段/持续行为共用入口与事实，明确新任务仍先观测。完整边界见S3-DEATH-RECONSTRUCTION。

## S2 有限补给与装备维护（2026-10-17）

`consume` 和 `equip` 共用真实36槽库存、主手占用、原版装备槽资格、身体控制及权限。
`ConsumptionWork` 只通过原生持续用物完成一件已有食物，`MaintenanceDriver` 在每刻以同一
动作和事实入口处理有限已有库存维护；环境避险、自保和活动工作优先，取消/失败不重开。
自动维护只接受普通原版食物、空盔甲槽与真实正护甲属性，不自动找料、升级、卸装或改变副手。
`status`/Lua报告实际用物、饥饿、饱和度和维护阶段；消费/装备不替代外层目标验收。
完整参考取舍、边界和离线证据见 `docs/S2-SUPPLY-EQUIPMENT-2026-10-17.md`。

## S1 原生环境推进与共用避险（2026-10-17）

原CompanionPhysics以baseTick替换手动流体更新，补原生空气/环境/效果维护，
不调用doTick或另加travel，不改无敌；原生作用后重验活体、世界与加载再移动。
EnvironmentRecovery协调原WaterRecovery、BodyControl和冻结BodyPermit/ReflexLease；
移动后新危险先取得原工作范围/租约，再由原调度处理，不借工作结束重新放宽范围。
固定起点/工作与工地交集，600刻含回气，4096探针/400ms搜索及每身体64次上限。
岩浆/窒息等保守停工，真实脱离/回满空气连续20刻才归还，原onResume再验；
失败/取消不重开同次危险账、原工作或模型/探索/许可。
status.body_environment及Lua概要、BodyFacts/采集/地下前进与返程、原派发/调度共用安全事实；
局部恢复不验收外层目标。完整参考取舍、支持边界和实机待验见S1-ENVIRONMENT-RECOVERY。

## D3 共用休眠与持续跟随（2026-10-17）

TickTask只读poll/wantsBody在原CompanionScheduler的身份/许可门内决定是否需要身体；
BodyControl保留原工作令牌而退到IDLE，原槽/租约/期限/预算不变，REFLEX仍优先。
唤醒重验，清理回调取消后不推进旧槽；默认任务行为保持。
follow经FollowWork复用原EntityApproach/PathTask/StandingGeometry，不增加另一套寻路。
稳定UUID、固定32格范围在工地内保守收缩，到位休眠，distance+2迟滞唤醒；
主人暂离和明确实体丢失分别结算，原期限含休眠/避险、累计导航账不重开。
原WorkFailureMemory/Lua/dispatcher/长期目标入口共用，终态behavior_ended不替代外层验收。
完整参考取舍、有限支持和原生待验见D3-CONTINUOUS-FOLLOW。

## B5 双格门续卡（2026-10-17）

BuildObjects把普通原版门的两格配对和原生影响范围供原目标/料单、放置及拆改共用；
两半保留验收，下半唯一动作/一件料，上半不派发。原BlockPlacement检查两格空气/保护/
范围/占位，原BlockMining检查连带影响；两格真实变化一并入B4记录，消费仅记一次。
原生异常/取消保留部分效果，同块替换或重载不恢复来源；原动作、控制、Lua与累计预算不变。
完整支持范围和参考取舍见B5-STRUCTURE-BLUEPRINTS；床/装饰/组件仍未实现，B5未整卡关账。

## B5 首个蓝图格式历史检查点（2026-10-17）

StructureBlueprint仅把固定世界目录的有界gzip结构.nbt解析为同B1目标；
build_blueprint的list/inspect只读，import按source_revision明确存为B4设计，后续同实例引用/
差异/材料/原挖放与Lua。字节/深度/堆/维度/格数/调色板/时间独立限制，坏格/载荷不静默丢弃，
存在缺口整份不导入；不直接写世界、不自动找料/恢复目标或补原账/许可。
多格/装饰/组件/其他格式仍未实现，B5首阶段不等于整卡完成；扩展必须贯通原动作、真实材料、
占位/来源和全目标核验，详见B5-STRUCTURE-BLUEPRINTS。

## B4 世界内设计与实例记录（2026-10-17）

BuildRecords在原世界根目录存结构化设计/独立实例快照/确切状态与有界实际动作事件。
build_record负责显式数据编辑及分页；实例引用解析回BuildTarget，原diff/site/nearby/
mc.build沿原入口执行。原挖放核逐实际变化原子写记录，取消不回放，原预算/许可保持。
原地形见证证明当前格未变；重载不恢复见证，相同方块替换/来源不明保守冲突。
改图撤下旧自建格作为同目标air，经原依赖/工具/权限才清，普通路径与原挖放保护旧格/地板。
未实现自动目标恢复或跨文件崩溃事务；有限规模/同步IO与原生待验见B4-BUILDING-RECORDS。

## B3 多站位施工与临时工程（2026-10-17）

build_site显式登记任务内目标、水平进出通道及冻结范围；BuildSite的完整128格目标保护
独立于原64格WorkTargets。NavigationTrail的实际接地记录保护经过格及地板，原256帽/
观察缺口继续限制施工；LevelDigSampler规划/缓存重验和原挖放核共同执行保护。
run_program内置construction.lua的mc.build/cleanup_temporary，只组合原diff、move_to、
build_nearby和break/place，沿原Lua/原子预算等真终态，最后全目标独立重查。
工地move_to共原NavigationBudget，轮次/改动/临时来源不因换程序补额。
BlockPlacement实际动作后才记临时用途/状态/区块/CellWitness；原地形钩子逐格失效，
相同方块替换也不继承回收权。清理仍走原BlockMining/F2账及新鲜依赖/许可检查。
未增加材料规划器或成品链；任务对象/身体/世界隔离，B4持久实例与L1外层建筑目标另卡。
完整有限支持、参考链和原生待验见B3-MULTI-STANCE-CONSTRUCTION及STATUS。

## D2 身体威胁与有限自保（2026-10-17）

ThreatFacts统一可信来源、原生攻击盒/实际爬行者爆炸半径和身体AABB；
BodyDefense只负责固定来源的触发/生命周期，DefenseDriver委托原EntityAttack/
EntityApproach/PathTask执行。水中避险优先，BodyControl抢占/恢复仍走原契约。
冻结BodyPermit和资源租约、固定6格节点球交原工作范围，400刻含暂停/10次出手/
原累计导航账不重开。RetreatWork固定已知安全落点，每步复核全部威胁。
status及Lua风险概要共享实际结果；cancel锁自动入口至新任务且不清旧来源账。
每身体最多64来源各一次自动处理，是有限局部自保，不保证逃脱或完整生存。
完整参考链、支持边界与原生待验见D2-BODY-DEFENSE及STATUS。

## D1 共用动态接近与近战（2026-10-17）

attack显式approach=true经EntityApproach协调原PathTask与EntityAttack，不新增动作核。
固定实体/锚点，范围6.5格、只行走；原生AttackRange和StandingGeometry/BlockAim决定
可工作站位，真实出手仍走原版距离/视线/满冷却和效果核对。移动不阻断同刻合法出手。
NavigationBudget在换路线、地形重算与暂停恢复间累计原节点/记录/协作CPU及2次重规划，
原400刻总帽含暂停；默认原地模式保留，批准绑定模式和原锚点。
短任务/长期阶段及Lua走同入口，D2自保已按上节复用同一执行器。
完整机制、支持范围和原生待验见D1-DYNAMIC-MELEE及STATUS。

## B2 共用近身施工（2026-10-17）

build_nearby将同一B1目标/修订/指定页交给BuildWork身体任务，复用Survey/Inventory、
BlockPlacement.placeDesired及原权限/控制/区域锁，最多12动作，每刻至多一次。
cellState供差异与执行前现场重验共用，原版上下文再核点击、状态、材料和实际效果；
单放/路径/施工同源观察扣料及有界邻域变化，失败不重放。
两刻后verify独立核全目标，局部结束与整栋条件分开，外层set_goal建筑生命周期仍待L1。
Lua按声明动作数预扣原子预算；抢占/取消/换页不补预算，后续持续入口复用同一身体任务。
完整帽、部分效果与原生待验边界见B2-NEARBY-CONSTRUCTION及STATUS。

## B1 建筑共用目标与只读核对（2026-10-17）

building/BuildTarget统一有界cells/fill目标、稳定锚点/旋转及内容修订；
只匹配作者明确要求的属性，未指定格不属于目标，不按房型或成品名生成动作链。
BuildSurvey对真实全目标状态计差异，对当前16格做最多96次原放置预检及有限材料分配。
BlockPlacement.assessDesired在原生选态后检查目标属性，不写入或覆盖原版状态。
BuildInventory与料单共用36槽、完整组件匹配，需求/可用/本页分配/消费分开。
build_diff是同一只读服务端工具，可直接或Lua调用；不另设规划器或批次框架，
原身份/许可/控制/原子预算/取消与风险回执保持。全量状态与分页工作评估分别报告，
未知/不支持/地形版本不稳定不得验收完成。B2实际施工、L1外层目标及持久实例仍待后续卡。
完整机制与支持边界见B1-BUILD-TARGETS及STATUS。

## N3 空间事实与路线记录（2026-10-17）

TerrainVersion/只读TerrainSnapshots按区块实例与实际修改提供有界版本；
SpatialFacts供共用失败账、Lua局部重评及ExplorationRoute/完整分页失效。
未知/缺失不能证明失败条件改善，覆盖失效不清tried/visited或增加原预算。
WorksiteProtection统一工位、工作目标及直接地板保护，供搜索/缓存/执行前重验。
每身体NavigationTrail绑定当前任务/世界，只记录实际接地与实际挖放；
返程ID仍走原move_to/PathTask的新鲜支撑、原范围及walk_only复核。
无自动倒放或普通路线必返承诺，无永久建筑实例/跨退出记录。
完整限制、参考链和离线/原生区分见N3-SPATIAL-FACTS及STATUS。

## F2 共用掉落事实（2026-10-16）

BlockMining请求与原生destroyBlock匹配区间捕获实际接受的掉落；
DropTracker把生成/源格、并堆、原版拾取/损毁和卸载事件交给每世界有界DropLedger。
源格/其他格/仅操作区间与target/clearing/path_clear分开，不认领附近历史物。
全量与同质部分转移可精确结算，混堆部分转移保守转未知，不按任意先后分配来源。
单挖/清障/路线终态、status与扫描共用；BodyPickup/collect仍调用同一原生playerTouch，
本身体身份同时核UUID和body_id，最终成果仍查真实背包。
路线保存轻量令牌、一次刷新后聚合，不给失败账补次数或重开任何原预算。
缺钩子/未知/卸载/过期/样本截断显式声明；只覆盖同步破坏区间生成，
异步生成、任意战斗战利品及跨退出来源恢复不在本卡范围。
完整边界及离线/原生区分见F2-DROP-ACCOUNTING-2026-10-16与STATUS。

## N2b 共用放置工作（2026-10-16）

PlacementWork将目标/材料/面交给原NavigationGoal与PathTask，不另建导航器；
站位用StandingGeometry并拒绝整个身体占目标格。BlockPlacement的只读投影、
单放查询/执行和路线支撑共用预检；实际终态再按真实身体/库存/世界验当前可工作。
到站不自动放置；Lua可据真终态组合原place_block，取消迟到不续发。
查询分别给原目标stand_positions与改选目标alternatives；路线canPlaceFrom在本步清障后、
待放支撑前验证点击前提。原生上下文不读取计划世界，邻域未落地编辑保守拒绝。
投影仅支持有限原版单格类别，不含探身/搭柱/任意动态模组状态；
原控制/事实/预算与许可保持。完整边界和离线/原生区分见N2B-PLACEMENT-WORK及STATUS。

## N2a 共用站立几何（2026-10-16）

实际同伴的LevelDigSampler经NavigationGeometry读取StandingGeometry，
与WorkPositions/探索/工作眼睛/拾取接触共享原版碰撞脚高及支撑身份。
PathTransition统一搜索、累计编辑、计划/缓存及逐刻复核；每步保留feetY/jump，
PathSteering驱动原版travel并按实际脚高站稳，PathGrounding识别同脚格内下落。
工作射线不抹去同格支撑，末点新鲜支撑/净空再验。
每次64格/256盒/每格16盒/16段扫掠有界，未知/危险/动态复杂形状保守拒绝；
原导航/材料/许可/取消预算保持。详见N2A-STANDING-GEOMETRY及STATUS，
尚无边沿探身或原生运动/拾取验收，放置到达见上方N2b的有限支持边界。

## F1 共用身体事实（2026-10-16）

每身体BodyChangeLog供逐刻观察、status.body_changes及动作终态body_delta共用。
原权限/锁/身体控制后开区间，scheduler外投影终态，保持原future取消链；
同步/跨刻/部分失败/取消/异常均保留原效果。真实损坏只由原版回调确认。
每类历史16条、近期各4条、动作损坏8条、差量4096字节，省略/缺口显式报告。
差量不证明因果、掉落来源或目标完成，不参与失败账重置；
持续身体触发共用事实，不另建规划或刷新原预算。详见F1-BODY-FACTS及STATUS。

## C1 身体控制（2026-10-16）

每身体BodyControl固定REFLEX→WORK→IDLE，现有WaterRecovery是首个危险驱动。
ServerActionGate原权限/锁后统一同步和跨刻控制，工作失去控制立即释放输入/用手/裂纹；
onResume先核原任务身份/许可及各动作前提，默认拒绝隐式恢复。PathTask消耗原重规划账，
挖掘重验冻结目标和主手，取消/替换永不自动恢复。
elapsed_ticks包含暂停，execution_ticks单列；原总帽和客户端等待不延长。
1153项离线与未来触发边界见STATUS及C1-BODY-CONTROL-2026-10-16，F1差量已按上方完成。

## N1 导航费用与诊断（2026-10-16）

同一move_to/PathTask/DigAStar入口补有限接近清理估价和工作终点费用，
机制与参考取舍见`N1-NAVIGATION-COSTS-2026-10-16.md`，证据见STATUS。
ApproachEstimate在advance内分片，几何终点超集/放宽步型/下降扫掠及出发支撑与本体动作相容；
累计TerrainEdits只折价剩余估价链涉及的已清格，辅助费用不用于动作准入或实际效果。
NavigationGoal.arrivalCost复用BlockAim.miningPlan和同源挖价，比较路线加剩余清障，
昂贵起点仍能离开，不可工作站位不当完成；最终仍读活世界验工作。
原1.8权重用于整个估价，不声称最短路线或W倍最优界。

预计算最多原展开帽1/4，种子最多8192探测/预计算最多8192入队；
预计算与正式搜索共用原8000展开/32768存储/400ms累计/6ms切片，不扩大预算。
PathTransition.check向搜索与活体计划复核提供同一拒绝前提，
失败有原因计数/最多8条样本及具体失败步骤/格子；样本不冒充唯一根因。
终态/中断仍保留实际挖放，不为诊断另开搜索、放宽权限或重放动作。
持续行为触发尚待后续接线；本卡仅补共用导航层，任意脚高/完整放置/战斗/建筑仍另卡。

## 共用导航的计划地形（2026-10-16第五次测试后）

本批让同一move_to入口的搜索前提与执行前复核贯通；直接工具、Lua、gather/explore
和长期材料阶段继续经原入口，不增加第二套导航或材料规划器。
TerrainEdits只保存当前搜索路径前缀的计划挖放，不写入实际效果账或任务失败账。
PathTransition统一邻接、起跳头顶、斜角、有序清理、当前脚下保护、支撑及剩余挖放帽。
DigAStar按位置/累计挖放/计划地形区分状态，固定父节点及该步清单；
不再把廉价但已耗尽材料的到达覆盖可继续工作的到达，不重复计算已清格。

MemoDigSampler只缓存原世界；带地形的canClear交给LevelDigSampler/NavigationWork，
工作到达也读该前缀。BlockAim用原版shape/clip读取只读挖放视图，
已放的普通垫块按完整方块遮挡射线，未知仍未知；实际挖放始终读活世界。
PathTask缓存/新搜清单重建、整段复核和前方支撑检查读同口径计划地形；
原逐刻身体执行、许可/区域锁、部分成果、取消及重规划累计预算保持。

搜索另有32768个已创建节点的存储帽，连同原8000展开/400ms累计/6ms切片限制，
耗尽按预算不足及已有有效半程结算；不据撞帽断言世界绝对无路。
目前只适用于已有干燥整格动作，禁止回收计划垫块，不自动回填原实心格；
任意分数脚高、放置点击/边缘运动及普通路线返程生命周期仍缺；费用按上方N1更新。
最新证据见STATUS及当日日志第五次测试节，下方“无累计编辑世界”按历史阅读。

## 任务内共用失败证据（2026-10-16）

WorkFailureMemory由AgentRunner按task_id建立，跨程序、直接调用及长期阶段共用；
所有组合的远端原子调用也走executeRemote的实际发送门。匹配tool_result/job终态
先记账后交给等待方，受理/进度/合成停止及迟到包不计入。
move_to/break_block按结构化参数/局部原因累计五次，第六次拒绝派发并附failure_memory；
128条不淘汰旧失败补次数，满账明确停止工作调用，查询与其他能力可用。
完整details身体事实变化或原子实际效果可失效旧证据，查询/空动作/组合累计进度不清账。
新任务/会话/身体独立，旧身体排队动作拒发，旧终态不能影响新账。
这是任务内止损，不是空间失败索引、路径修复或第二套规划器，原权限/预算/取消保持。
整体职责、参考关联链及战斗/自保/导航等未完成范围见
`AGENT-MECHANISM-AUDIT-2026-10-16.md`；下方“仅同程序”为上一检查点。

## 生存放置与具体回执（2026-10-16）

第四次测试补齐帮助→真实data→错误定位→有限纠错链路，见当日日志第四次测试节。
tool_help增加returns说明，成功帮助在Lua内仍展示；普通大表留本地，负预检原因有界去重。
BoundedProgram报告静态错误类别/源码数字行号，不暴露原始异常；已完成效果不重放。
失败账按工具/JSON参数/类别累计，空动作及耗时文字变化不清账；
BodyFacts从既有长期阶段指纹提取复用，仅完整新鲜事实或真实计数进展允许重评。
新鲜状态门、原预算/权限/取消保持；仅同程序恢复，不是跨程序失败账或新的规划器。
拾取精确站位说明补齐，未修改默认相邻到达或底层路径算法。

第三次测试的上下文效率补丁见当日日志第三次测试节。
ToolCatalog从原ClientToolDefs生成稳定短说明，全部参数Schema仍下发；
tool_help由同一注册表按需返回完整说明/Schema，顶层和Lua均本地只读，不改服务端白名单。
BoundedProgram把成功查询全文留给Lua，自动回执保留编号及有界风险/覆盖标记；
显式print/return完全相同反馈时展开一次，动作与失败全文、结构化值和面板/桥原子记录不变。
mc.try_call只开放特定局部移动/工作失败，继续修改前必须新鲜完整status details=true；
状态查询不刷新相同失败五次帽，权限/前提/安全/范围/未知失败不捕获，原总预算与取消沿用。
Conversation增加完整输入75%压力且历史足够时提前整理，仍尊重autoCompact关闭/较低阈值；
摘要输入使用冻结出站投影，近期完整工具组和实际效果继续保留，成功后重查身体。
不是动态懒加载Schema、自动恢复规划器或技能正文按需读取；具体离线体积和限制见STATUS。

第二次测试补模型传输/身体环境链路，现场与完整参考对照见当日日志第二次测试节：
LlmClient在完整HTTP/SSE生命周期施加配置总时限及90秒无字节看门狗，
心跳仅刷新空闲；typed failure取消订阅/连接，忽略晚到，不自动重放已可能派发的动作。
ResponseDiagnostics仅记录idle_ms/max_byte_gap_ms数值；AgentLoop/runner动态显示等待秒数。
工具折叠条数改称记录，不表示动作序号。

WaitTool接受seconds或ticks互斥严格整数；GroundItems暴露原版pickupDelay，
gather按当前延迟有限等待，不跳过原版触碰、实际背包或取消/无进展/共享预算。
MiningEnvironment共用于资源安全事实、工作判据、路径挖价及BlockMining每刻执行；
六邻格流体/未知、目标及上方重力方块均拒绝普通干燥采掘，危险候选不为其开路。
PathTask重建清单时按先后移除/放置去重，并在执行前检查支撑依赖和出发脚下不得挖；
尚不是带完整编辑状态的A*，可能拒绝静态搜索给出的方案。

CompanionPhysics在流体更新后刷新眼部流体，WaterRecovery独立按刻选择有界连通空气路径，
按实际全身体扫掠/加载/边界/非岩浆验证，用下一路点输入和原版液体jump/travel，
不传送、不挖放。最多128探点、水平6/上8、20刻重评、没顶600刻帽；
只读/身份失配不启动反射，传送/换维度废弃旧路线，状态转换日志与status可见。
挖掘/普通陆地路线/采集没顶即停止并保留已发生效果；
完整水下作业、氧气伤害生命周期及一般游泳导航仍缺，原版空气读数不作完整生存承诺。

BlockAim.placementContact评估实际轮廓面、首个命中block/face、眼睛距离及已加载射线。
BlockPlacement.preflight共用于查询和执行，真实命中交给原版BlockPlaceContext；
删除强制getClickedPos/canPlace覆写，只在原版精确落位可替换时继续。
查询不转头/消费，执行重验、转向并调用原版BlockItem.place；placement_contact保存原目标点击证据。
PathTask复用同一预检，在既定目标最多查六面再执行一次，保留许可、材料和真实支撑核对；
尚未把完整点击几何/边缘身体动作加入路径搜索，不以合成命中保住不可执行计划。

BoundedWorkflow在放置查询的preflight_passed=false时停止，Lua仍可读取候选自主分支；
ArgumentFailure给可信Schema字段路径及类型/范围，不回显错误值或未知字段原文。
BoundedGather只保留目标回执drops的最多8次物品样本，与清障产物分离；
样本不改精确成果目标、不代替战利品表或背包，不新增成品模板/规划层。
现场、参考链路及余项见real-game-log-review-2026-10-16.md，测试/包见STATUS。

## Bot头顶任务显示（2026-10-15）

TaskBubbleState只维护当前任务显示、在途原子工具seq和短终态，不参与任务规划。
AgentRunner在真实发送、终态/超时和任务监听器更新显示，结构化读取问答、整理、
地下勘探/返程及ItemGoal.Progress。数量/材料子目标和最终目标保持分离，
job_ack不等于动作完成，长期检查点不等于任务结束，面板检查不会覆盖动作。
ServerToolDispatcher仅向请求主人回传companion_uuid；缺失/非法身份不按名字绑定，
同名替换、遣散及会话退出清旧显示。外部桥v1.0无变更。

CompanionTaskBubble在世界提取阶段读取实体插值/相机、VISUAL射线及只读任务快照，
将不可变帧附在LevelRenderState；BEFORE_ENTITIES只按帧提交面向相机的深度测试几何/文字。
不新增服务端轮询、模型调用、点击或输入处理。show_hud继续由原配置保存，
固定底部HUD已替换；具体几何、期限、兼容和实机边界见TASK-BUBBLE-DESIGN及STATUS。

## 当前导航、勘探与目标链路（2026-10-15）

第六次日志已确认铁镐及后续钻石镐均合成/装备，详细证据见当日日志第六次测试节。
局部gather显式explore_steps=0时不推进ExplorationRoute观测/覆盖/路标，
但保留同一账引用供反馈与真实地形覆盖失效；默认模式仍检查首次探索球。
范围/配置拒绝提示显式局部工作入口，不自动切换模式或补预算。
BreakBlockTool仅给无部分效果的几何失败附工作导航建议，所有预检/真终态经同一装配；
普通移动成功不保证能施工，ScanSummary不再作此暗示。
BoundedProgram只在显示中引用本程序已记录的完全相同反馈，Lua值和结构化返回不变；
实际动作和失败各有回执编号，取消/输出预算/原子预算与原权限保持。

第五次日志已确认铁镐合成/装备；本轮工作一致性补丁不新增规划层。
BlockMining的距离准入归BlockAim，去除与工作到达冲突的脚到中心闸。
CompanionPlayer持有每身体HandSnapshot.Pickups，GroundItems仅在真实playerTouch
前后记录来源；挖掘/局部清理接受动作开始后的同槽空手填充证据，其他变化仍拒绝。
find_resource给当前采收资格，gather跳过明确不合格目标、查完来源后交回工具前提，
search_resource跨页排序同步；最终动作仍复核工具/权限/视线/真实效果。
掉落UUID重评不刷新六站位帽，无动作无站位时直接处理同份扫描其他候选。

place_block query=true与执行共用BlockPlacement.preflight，使用只读方向上下文，
实际place才facePoint并调用原版BlockItem.place。受阻时有界预检附近75格/最多6建议，
不自动移动或更换精确目标；预检不是原版最终成功。TaskPolicy、服务端闸与气泡同步只读语义。
相机FOV不是资源查询边界；加载范围事实与实际动作可见性分别解释。
完整掉落事件账、跨阶段簇/失败记忆、分数脚高、统一看门狗、按需工具目录和
建筑让位/续建生命周期仍未实现，详细对照见当日日志第五次测试节。

拾取/放置新衔接：WorkPositions.describePickup用实际身体与ItemEntity碰撞盒共用
GroundItems.touches筛选当前候选；不修改通用可站候选与挖掘判据。
BoundedGather按UUID记录最多6个不同站位，成功未接触或局部失败后都重新status/scan_area，
实际途中入包即可结束；已知移除掉落但暂无可处理候选时有限等1秒再观测，
不把移动或物品消失当作成果。范围排除保留有界证据，SCOPE_LIMIT停止额外探索。
BlockAim放置支持同格有界可见点；BlockPlacement预检和执行共用选中点/原版上下文，
显式判断自身形状碰撞，仍由原生放置负责真实世界效果/消费。
本轮没有完整事件掉落账、簇索引、任意半格脚高或新的全局规划层；
全链路参考对照及现场事实边界见real-game-log-review-2026-10-15的第四次测试节。

本段覆盖下方历史检查点中未实现工作A*/地下组合/目标生命周期的描述。
只读参考学习到的关联机制为：感知给事实和未知范围，模型选择意图/工作区，
组合执行有限步骤，身体复核几何、许可与效果，阶段结束再核验最终条件。
三项能力共用该链路，不增加矿种层高表、成品升级模板或并行的自动依赖规划器。

- NavigationGoal统一终点与剩余距离。PathSpec携带工作到达、地下转移、挖掘帽与球形范围；
  DigAStar以目标判据选终点，NavigationWork通过BlockAim的原版轮廓/clip验证实际距离、
  视线与有序中间开路。假想站位只覆盖脚/头格及本步已核对清理格，不改变世界。
  工作目标及支撑继续保留；执行和最终工作评估仍读活世界，不以计划保证成功。
- LevelDigSampler用碰撞形状判断净空、完整顶面判断支撑；地下仅相邻水平一步、
  高差最多1格，可用真实材料和点击面补支撑，不垫柱/原地直挖，避未知流体邻格和重力方块。
  PathTask在搜索、计划、每刻位置与实际清理检查同一范围和剩余挖掘帽，
  特殊规格不复用旧计划缓存，重规划不补充实际账。
- BoundedSurvey经既有explore入口组合status、完整BoundedSearch及move_to；
  调用方给direction/target_y/targets，按4格请求短段推进，发现候选就返回。
  外出预留返回次数，地下开路任务累计128格、单跳最多24格且受实际耐久储备约束；
  生命、饥饿、火、满包或工具不足停止外出。实际位置形成有限路标，
  后续工作位移在下一次勘探/返程前核对补入，返程只走路并重查路线。
  具体许可仍走真人回答、服务端authorize、原参数单次续发；取消不自行返程。
- gather缺少附近工作站位时可请求arrival=work，交导航判断；实际路径清理计入本段预算。
  材料采集和短任务继续使用同一入口；发现、到站、挖除、入包分别记账。
- AgentLoop保留ItemGoal的最终数量条件及任务开始基线；首次set_goal可登记long_term。
  GoalContinuation只处理段尾新鲜事实和有限续跑：先判断实际满足，再检查重复事实/阶段帽。
  背包按物品合计、位置按脚格及维度规范化；换槽、tick、生命或耐久变化不算进展。
  每个阶段仍在原task_id下，不经过pump重启任务，不发送中间done，不重开40模型步或探索预算。
  原始目标和阶段事实每轮独立挂载，历史整理不改变它们；取消与迟到隔离沿用generation/反应器。

参考的目标维护器还支持一般目标的模型评估、事件续跑与持久化。
本体目前优先保留数量目标的确定性验收，阶段控制处于现有AgentLoop之内；
未移植参考的事件内核，也未声称实现跨退出恢复或建筑/交付验收。
地下仍是有界参数化勘探，不是任意天然洞穴发现/照明/危险应对系统；
没有全路径改动快照、终点清障成本排序、游泳或任意半砖站姿。
离线原版几何/A*及runner证据见STATUS，真实身体、天然地形与模型策略仍待补测。

2026-10-15参考机制修复：模型依据观测选择阶段，本地组合负责有限连续执行，
服务端身体负责真实动作与权限/工具/几何复核；不新增按成品名分派的规划器。
BoundedGather复用本次查询中同站位直接可工作的候选，最多8目标且不超过本批开始的缺口；
每格后仍刷新完整背包，换脚格、移动、受阻、无进展或预算边界即结束本批。
这是客户端组合的候选复用，不是一个新增的服务端批量挖掘任务或完整簇索引。
工作/拾取路线共用局部失败分类；成功或已知局部失败后先刷新位置/维度/背包/范围，
有限换位前可因目标已满足而停止，权限、取消及未知失败仍直接返回。
LevelDigSampler补保护普通工作台等无方块实体的工位；PathTask把work_target及其正下方
加入挖放保留约束，首搜、缓存重建、重规划和执行共用，最新清单在授权前复核。
这只补保留约束，到站工作几何仍后验核验，没有实现工作语义的A*终点。
craft材料查询增加单输入工艺事实（普通合成和现有smelt支持的三类烹饪），
processes与兼容的sources分开；工艺事实不提供世界掉落来源或自动依赖求解。
完整地下勘探、长期目标独立生命周期与跨退出恢复仍待实现，不能由上述改动推定完成。

2026-10-15四项优化：新增客户端BoundedSearch/search_resource，在同一次调用内串行
完成严格同中心的最多5页查询（30秒），每页仍经executeCompositionRemote，
嵌套程序按真实页计原子预算，读权限/取消/迟到隔离不变。服务端工具仍15项。
gather默认搜索r16，落脚样本随显式小半径缩短；ExplorationRoute另记有效完整且
无未知格的目标批次/中心/半径覆盖，最多64条/120秒，真实挖清后失效。
33点体积估计仅用于候选评分，不证明连续地带已查完，也不是持久地形索引。
CraftTool的material_index只读查询通过RecipeMaterials从实际Ingredient与一层单输入
普通配方得到替代来源；gather material_for仅支持材料subtask，来源分批查询，
ItemGoal仅在该作用域合计候选实际件数，不换算产量，不修改精确最终目标。
跨工艺依赖、自动转换、材料预留及长期阶段调度仍未实现，契约见TOOLS。

2026-10-14晚间实测恢复卡：BoundedGather在原子move_to/break_block的首次完整
NEED_CONFIRM后保留参数，调用runner既有question/authorize入口；真人确认及服务端批准
后续发一次并计入原调用预算，清单再次变化交回模型。拒绝/超时/已有效果不重放，
流程截止/取消清理所属问题与在途批准票；嵌套程序仍共用原子预算。
find_resource将既有工作判据/站位同时写feedback，修复普通模型看不到data的差异。
AgentLoop暴露真实摘要/模型等待状态，runner结合采集activity与问答显示当前动作；
不增加推理原文采集或伪造阶段完成。具体证据与剩余缺口统一见STATUS最新节。

2026-10-14后续：身体已接BodyPickup，在物理步进之后按原版未骑乘触碰盒查询物品实体，
与collect共用GroundItems.pickup/playerTouch。它不依赖模型调用或身体任务槽，
活动只读任务及身份/维度失配时禁用；非活动、骑乘/睡眠/旁观和未知邻域跳过。
gather现在只走近/有界等待并重新核对实际背包，不调用collect。
HandSnapshot容许原活堆栈的合法同组件拾取增量，避免误停挖掘/攻击。
gather的scope=task默认保留整项数量目标，scope=subtask只结算本次材料条件，
从第一份新鲜完整背包建立基线，不覆盖AgentLoop独立保存的最终目标。
最新范围与离线/实机边界以STATUS为准。

通用run_program已接BoundedProgram（上游LuaJ文本解释器）：独立虚拟线程仅计算/等待，
mc.call切回客户端，真实terminalOnly回执才恢复；变量、分支和循环由同一语言运行时处理。
嵌套gather/workflow复用旧执行器，局部材料不改最终目标，原子预算统一，
取消/超时释放子票并中断计算；解释器没有文件/网络/Java或任意命令入口。
不是新身体控制器；共享追击/跟随与批次放置仍待后续实现，详见TOOLS §1.2。

工作站位本卡统一BlockAim：WorkPositions.describeMining从假想眼睛筛选视线/距离，
find_resource work=true附当前失败原因及最多6站位；掉落describe不受挖掘筛选影响。
move_to可带work_target，在PathTask真正结束后重新检查几何工作条件并保留实际移动账。
BoundedGather在不同站位间有限恢复，移除后重新查询此前受阻目标；
break_block的expected_block防止旧目标被替换后误挖。后续已接MiningWork/ClearingMining：
work查询、假想站位、移动工作终态与组合挖掘使用同一有界清理判据；
射线上最多3格安全遮挡走原ActionPermissions具体批准/自主上下文及区域锁，
清理实际结果与目标移除分列，不添加完整工作目标A*，具体范围见TOOLS §1.1/2.5。
资源先对全部已读匹配做露出预筛，再分配24目标/2048共享射线与16输出名额。
PathTask用DropObservations重查最多256个记录UUID，历史量、当前可见量、
未知去向分开；没有拾取/合并事件精确归因，不用消失替代背包成果。

自主执行为主人客户端偏好autonomous_actions（默认false），新任务用内部
task_begin.autonomous建立服务端Context；模型工具参数不能升级，只读优先。
ActionPermissions.allowsChange(s)同时提供PathTask计划/逐格动作与AttackTool批准判定，
当前自主任务免具体批准，其他模式保持一次性清单语义；原动作安全/资源/终态不变。
StatusTool回传实际permission_mode，ScanAreaTool按有效模式输出攻击确认提示；
客户端自主任务观测模式不符时，在请求模型前停止，AgentLoop只映射固定失败原因，
不透传任意错误正文。保存模式变化先取消旧任务/排队/问题，保留大脑与显示历史。
外部桥v1.0不变，详细范围与兼容性见TOOLS §1.3/PANEL。

通用探索由客户端ExplorationRoute保存任务内起点、维度、已查位置、尝试目的地和预算。
独立BoundedExplore执行status → scan_area(frontiers) → move_to(walk_only) → 新鲜status，
只报告一段经实际位移核验的观察推进，发现条件由run_program/模型调用方决定。
BoundedGather复用同一路线逻辑，先补查find_resource同中心分页，耗尽候选后再换区，
原最终/材料目标、背包基线和总预算不重置。2026-10-15起，AgentRunner将同一份路线状态
注入所有启用探索的gather并供独立explore复用；新任务才清空，省略参数继承首次值，
显式变更拒绝。到达观察即记位置，至少4格水平新位移才计区域，选点结合方向与爬升成本。
取消清待核验位移，不接纳迟到结果。访问记录不是完整搜索索引。
ExplorationPoints共用WorkPositions.standable并排除已知危险落脚格，样本不证明路线可达。
每方向先约16格/13高度，再约8格/12高度，总8样本/200探针；山地高度跨度变化需复测。
AgentLoop与BoundedProgram共用SearchPages核对同目标/中心/半径的连续覆盖；
合法续页不新增空搜计数，首次页及重复/跳页/畸形回执仍计数，总调用预算不变。
PathTask.walkOnly从搜索预算、缓存、活体计划及执行入口共同禁用挖放，仍走原物理移动。
范围约束目的地/新鲜观测和采集目标，不约束路线每个中间节点；无完整索引或地下勘探。
工具现23项（15服务端+8本地）；只有成功且严格布尔exploration_advanced=true重置
原地空查询计数，普通move_to文案不重置。详细参数与边界见TOOLS §1.1。

## 1. 三进程拓扑

```
┌─ N.E.K.O 宿主及适配连接器（外部进程）
│    通过任务级接口接入（§6 桥接）
│
├─ Minecraft 客户端（本地玩家）  src/client/
│    AgentRunner：大脑宿主（agent-core AgentLoop）
│    BridgeHttp：127.0.0.1 任务桥（供连接器接入）
│    McbotPanelScreen：G 面板（对话/同伴/设置，滚动、固定多行输入）
│    PanelHistory：会话显示档案；CompanionTaskBubble：自己Bot头顶任务/待答显示
│    ⇅ 自定义 payload mcbot:c2s / mcbot:s2c（单 JSON 信封）
│
└─ Minecraft 服务器（联机服） src/main/
     SummonService：假玩家（ServerPlayer）生命周期
     ServerToolDispatcher：三道闸 → 工具执行
     CompanionScheduler：跨 tick 任务推进（END_SERVER_TICK）
```

硬约束：**API key 与大脑只存在于所属玩家客户端**；服务器只见工具调用的结果，不见模型；
桥只绑回环。三者各是一层信任边界。

## 2. 线程模型

| 线程 | 跑什么 | 规则 |
|---|---|---|
| 服务器主线程 | 三道闸后的一切：工具执行、Scheduler tick、假玩家 | 世界操作只在这条线 |
| 客户端主线程（渲染） | `AgentRunner.tick()` 超时巡检、面板、聊天注入 | GUI 调用必须 `mc.execute()` 到此线 |
| 程序虚拟线程 | Lua解释/预算钩子、等真正工具future | 不访问MC；游戏调用排入客户端队列，不能在主线程等待程序 |
| HttpClient 线程 | HTTP/SSE 传输与解析 | CallbackChatEngine 将流式信号和整轮结果送到客户端主线程 |
| mcbot-bridge 线程池（8，daemon） | REST/MCP/SSE 处理 | 投令/取消/回答先 hop 客户端主线程；状态可读快照；关闭时关闭线程池 |

实锤教训：GUI 不 hop 会报 `Rendersystem called from wrong thread`（曾真实发生，已修）。

## 3. 模块布局

```
agent-core/          纯 JVM（零 MC 依赖，独立构建+单测）
  llm/               Msg(sealed)/ToolCall/ToolSpec/AssistantTurn/ChatEngine/LlmClient(SSE 流式)
                     TurnSink(过程回调契约)/TurnTimings(时延打点)
  provider/          OpenAI 兼容线格式：请求体组装、tool_call 按 index 增量拼装
                     TurnBuilder(聚合器)/TurnSinkTarget(写入契约)
                     StreamingTurnReader(聚合+闭合判定)/ToolArgsScanner(配平+required 齐=可派发)
  convo/             Conversation：软水位压缩（"[对话前情提要]"前缀，保尾部）
  loop/              AgentLoop 状态机（含 Ledger 保序落账 + PARK）/ToolExecutor 契约
                     ItemGoal(任务/材料子任务作用域、各自背包基线、当前数量与结束校验)
                     PendingJobs(两段式等待: seq→终局 / 受理后 jobId→事件 + 超时口径)
  prompt/            PromptBuilder（基础准则+人设+技能）/SkillLoader(*.md）
  script/            BoundedProgram（上游LuaJ、变量/条件/循环、纯计算/数据预算与异步调用桥）
  bridge/            BridgeService(REST+MCP 内核)/EventRing/BridgeBackend —— 不碰 HTTP
src/main/            公共+服务端
  body/              FakeConnection/CompanionPlayer/CompanionRoster/CompanionChunkPads/SummonService/SafeSpawn/SelfTest
                     CompanionPhysics(原版重力/输入/碰撞步进，不依赖任务槽)/MovementInput(单刻输入)
  path/              DigAStar(纯算法零 MC 依赖,可单测)/DigSampler(契约)/LevelDigSampler(神圣集)
                     PathTask(搜索→确认→执行)/PathSteering(真实位置到短效输入)
                     PathGrounding(落地高度与旧路失效)/PlanCache(lastPlan 复用,纯逻辑可单测)
  server/            ToolRegistry/ServerTool(acceptanceMode/capTicks/acceptSubject)
                     ItemTransfers(实际移动量+组件完整的剩余堆栈、存储槽/容器/接触面约束)
                     BlockMining/BlockPlacement(单格与路径共用原版动作入口)
                     BlockAim(已加载视线、轮廓与实际命中距离)
                     tools/RecipeMaterials(实际Ingredient与一层单输入来源)
                     GroundItems(触碰盒与有界实体描述)/WorkPositions(安全站位候选)
                     PathMaterials(规划/执行共用四种无附加组件垫料)
                     ServerToolDispatcher(三道闸+tool_result/job_ack/job_event)/RateGuard + tools/(15 个服务端工具)
                     EntityAttack(单目标有界近战/实际出手与前后观测)
                     ActionPermissions(服务端任务身份/一次性具体路线授权)/ServerActionGate(统一动作入口)
  task/              TickTask/CompanionScheduler/ResourceLocks(身体、有界世界区域与实体UUID)
  common/            Envelope/McbotPayloads（两通道各一条）
  command/           /mcbot ping|summon|dismiss|list|verify_readonly（需 OP，gamemaster 级）
src/client/          客户端
  agent/             AgentRunner(大脑宿主+事件生产者)/ClientToolDefs(23项模型描述)
                     BoundedWorkflow(清单)/BoundedGather(本地有界采集循环)
                     BoundedSearch(同中心完整分页)
                     BoundedExplore(独立换区观察)/ExplorationRoute(任务内探索证据与预算)
                     PanelHistory(独立会话显示档案，不随模型压缩/重建删除)
  bridge/            BridgeHttp(JDK HttpServer 适配)/BridgeEvents(生产者总线)
  cfg/               ClientConfig（client.json + MCBOT_* 环境变量覆盖）
  ui/                McbotPanelScreen / PanelLayout / PanelFields（G 面板、GUI 像素布局、多行输入/密钥掩码）
                     PanelButton/PanelToggle/PanelDialog/PanelHistoryScreen/CompanionHud
```

## 4. 网络协议与三道闸

身体转向统一由CompanionPlayer.facePoint复用原版EYES lookAt并同步bodyYaw，
移动、挖掘、放置、攻击使用同一入口。身体tick补原版updateSwingTime，
不启用完整Player.doTick；BodyPickup在物理步进后自动触碰拾取，活动只读时抑制。
显式gather按任务权限组合移动/挖掘，拾取由身体自然执行。
BlockMining每拍通过BlockAim检查可见轮廓与原版blockInteractionRange，
转向后按实际getLookAngle再次确认第一命中为目标，才挥手、推进进度或移除。
沿射线未加载/越界先拒绝，遮挡/空气/目标与主手变化停止并清裂纹。
BlockPlacement在原版上下文/消费前检查视线与实际距离，转向后使用既有准确目标上下文。
移动路线由短效输入驱动原版travel/jumpFromGround，已移除水平与跨层路线传送；
完整鼠标点击、水中/载具导航仍未实现。原生动画/同步/复杂形状须实测，离线边界见STATUS。

- 通道就两条：`mcbot:c2s`、`mcbot:s2c`，各携带一个 JSON 信封 `{kind, ...}`；
  语义演进只加 kind 不加密道。闸① 是**显式的字节尺寸闸**（不再是“靠 codec 默认上限”）：
  `C2s.CODEC` 自己读 VarInt 长度前缀并卡 `WireSize.MAX_BODY_BYTES`，超限**不读不抛**，
  交回 `C2s.OVERSIZED` 哨兵由接收处丢弃并记日志。为什么不靠原版 `STRING_UTF8`：
  它限的是**字符数**（32767，折算字节上限 98301 ≈ 96KB），且超限**抛** `DecoderException`，
  而 `Connection.exceptionCaught` 对非 `SkipPacketException` 一律关 channel——
  因此模型生成超大参数可能导致玩家断线。出站 S2C 也按字节量，超限时**不是截断**
  （截断只造出非法 JSON → 对端静默丢弃 → 那个 seq 白等 90s TIMEOUT），
  而是换一条**保留 seq 的合法瘦身回执**，把“范围改小”教给模型；客户端另有发送前自检。
- **C2S kind**：`summon` / `dismiss` / `companion_status` / `tool_call{seq,task_id,tool,args}` /
  `task_begin{task_id,read_only}` / `task_end{task_id}` / `authorize{seq,task_id,authorization_id}` /
  `cancel` / `answer`。任务控制由客户端宿主构造，模型只能填写工具 args。
- **S2C kind**：`tool_result{seq,ok,feedback,data?}` / `summon_result` / `dismiss_result` / `companion_state` /
  `cancel_ack` / `event`（服务器主动播报，当前无生产者——留给任务进度事件）。
- 全局 C2S receiver 只在 mod 初始化注册一次；每条消息取当前 dispatcher，并校验所属服务器，
  不捕获第一张单人世界。停服取消 scheduler 全部槽位并清空计划缓存；遣散立即取消对应任务。
  同伴生命周期回执带结构化 `companion` 字段；JOIN 查询当前本地玩家在当前世界的同伴。
  自动 `companion_state` 同步只更新同伴页与桥公共 state，不写聊天或 transcript；
  手动召唤/遣散的操作回执保留原有反馈。
- **三道闸**（`ServerToolDispatcher.handle`，按序）：
  ① 尺寸/格式（`C2s.CODEC` 显式字节闸 + `Envelope.decode` 判空丢弃）；
  ② 速率：按玩家 token bucket，容量 60、补充 20/秒，超频回 `DENIED:消息过于频繁`；
  ③ 白名单 + `args` 必须是 JSON 对象 + **owner 强校验**：`tool_call` 只能作用于
  发送者名下的同伴（名册 `ownerUuid == 发送者 UUID`），无从伪造"替别人下令"。
- 动作入口随后检查服务端任务身份/维度与只读模式，经 ServerActionGate 取得身体和目标区域锁。
  没有有效任务上下文的旧客户端只能查询；客户端/服务端须一起更新，外部桥 v1.0 不变。
  权限拒绝保留DENIED文案，内部tool_result可附 `data.permission_denial`：
  gate=server_action、reason、tool，有上下文时另含task_id/dimension。
  reason区分TASK_REQUIRED、TASK_IDENTITY_MISMATCH、READ_ONLY、TOOL_NOT_ALLOWED；
  dispatcher查找失效任务/身体/维度时不给有效上下文，返回TASK_REQUIRED，
  不能把该结果当作READ_ONLY证据。
- RG2显式入口 `/mcbot verify_readonly` 由实际玩家身份查自己的空闲同伴，
  临时task_begin → 固定wait(1) → task_end共用生产handle；
  不替换已有任务、不绕开速率/白名单/owner/任务门，限流结束失败仍清理自身上下文。
  只有匹配seq/task/tool/维度的结构化READ_ONLY拒绝为PASS，其他为FAIL/BLOCKED。
  回执在服务端本地捕获，不投递客户端PendingJobs；命令不经过C2S codec/Fabric网络，
  不验证模型或外部连接器。游戏验收说明见REAL-GAME-RETESTING。
  包级Sender/Body/ToolAccess只隔离MC观测、效果和输出，离线17项执行同一dispatcher、
  实际权限/调度器/速率桶与回执编码，不创建真实世界/玩家。

## 5. 大脑回路（AgentLoop）

```
submit(task_id, 指令) → pump → onTaskStarted → step → LLM(流式) → turn
  turn 无 tool_calls → 已登记物品目标重新观测校验 → 满足/无目标才COMPLETED
                     → 未满足续step，反复无进展/受阻FAILED → [链尾压缩?] → pump
  turn 有 tool_calls → 串行执行（ToolExecutor）→ 每条回执进对话 → step
cancelTask(id)：匹配活动/排队项；0 全部。活动项立即终止并合成补账，旧回调按代际号丢弃。
close()：取消全部并拒绝后续 submit；连接配置变更/断线先关闭旧 loop 再丢引用。
```

### 5.1 受理即回执与 PARK（R2-S4 阶段 2）

```
turn 有 tool_calls → runTools 只把结果填进 Ledger（不直接写对话）
                     ↓
   Ledger.flush()：按 index 升序写出**已到达**的最长前缀
                     ↓
   全部落账 → step()（照旧）
   还有"已受理未到达" → parked=true，**不 step**（PARK）
                     ↓
   job_event(done/failed/cancelled/superseded) → Ledger.resolve → flush → step()
```

要点（细节契约在 `docs/BRIDGE.md` §5.1，工具侧授权在 `docs/TOOLS.md` §4.1）：

- **受理不等于结果**：服务端的 `job_ack` 只表示"开始了"，回执里带 `accepted=true` 字段。
  Ledger 记下 `jobId` 但**槽位保持空**——把它当结果写进对话，就等于告诉模型事情做完了。
- **终态后派发**：C3 用每槽 terminal gate 串联后续调用；受理不会打开 gate，
  同轮后续工具等待前一项真正结束，保守地包括查询。早派发仍只启动 index 0。
  Ledger 只写已到达前缀，模型下一轮必须获得本轮所有真正结果。
- **PARK 不计步**：`steps` 只在 `step()` 里涨，所以等一条 3 分钟的移动不会吃掉 40 步帽。
- 长活终局解除本次 PARK，再派发后续工具；若后续又受理，则再次 PARK。
  当前最多一条服务端长活在飞，不以同轮工具条数当作剩余长活数量。
- **PARK 的铁律**：解锁（叫停/新指令）之前，必须给**每一条** in-flight 的 `tool_call`
  补一条合成回执（`CANCELLED:`/`SUPERSEDED:`）。少一条，下一次请求里那个 tool_call 就有
  id 找不到配对的消息 → OpenAI 400，整段历史作废。取消/顶替的唯一补账实现是 `AgentLoop.stopActive`。
- 客户端侧两段式等待收在 `PendingJobs<T>`：`seq → 终局` / 受理后转 `jobId → job_event`，
  凭据（那个 future）跟着转段搬；**超时口径只有一处**：受理后的上限 = 服务端报的
  `cap_ticks`×50ms + 15s 余量（`JOB_GRACE_MS`）。
- 止血开关：`client.json` 的 `accept_mode`（默认 true）。关掉 = 客户端不在 `tool_call` 里点
  `accept`，服务端对 ACCEPT 工具仍走同步回执、大脑也不 PARK，整条链退回旧语义（不用换服务端）。

**真流式与早派发（R2-A）**：`LlmClient` 不用 `ofLines()`（行是收完才交出来的），自己实现
`BodySubscriber` 按字节增量解码、按行切分，每行到货即回调。于是"某个工具调用的参数写完了"
可以在**整轮落地之前**就知道，工具当场起跑（省下的是挂起时间）。
闭合判定两道人门：①顶层括号配平（自己维护"在不在字符串/是否被转义"）；②schema 的 required
字段在顶层全出现——只看配平会把 `{"x":1}` 这种半截参数当成品派发。
StreamingTurnReader按工具名称查required，tool_call.index只表示本轮调用槽，
不与工具目录顺序关联。名称迟到回放已累积参数，未知名称留正常整轮处理；
ID到齐、实际累积参数与检查对象一致后才报告，一槽一次、按实际索引升序。
写回纪律不变：`Msg.Tool` 一律按 index 原序记账（协议要求与 `assistant.tool_calls` 严格同序），
所以等待是**顺序折叠**而不是 `allOf`（后者只保证都到、不保证按序）。
传输层在响应头到达时记录 `ttfb`，消费文本增量/闭合工具时记录 `ttft/first_tool` 与计数。
最终尝试（成功或失败）的 `TurnTimings.Snapshot` 经 `TurnSink.onTimings`、`CallbackChatEngine`
同一有序队列送到当前 StepReactor，在整轮处理之前冻结统计；取消/关闭后的旧流不发布统计。
打点 `ttfb/ttft/first_tool/after_chunk/after_tool/chunks/deltas/ready/early/first_dispatch/stream`
经 `onStreamStats` 出到 `[brain] llm stream`。口径不能混用：

- `chunks` 为非终止 SSE data 行（包含 finish/usage/错误/非法载荷，排除心跳和 `[DONE]`）；
  `ResponseDiagnostics.data` 包含终止 data 行，因此通常多一行，不是统计矛盾。
- `ready` 为传输层已闭合的工具数；`first_tool` 为首个工具就绪时间，与 index 0 的实际派发不等价。
- `early` 为整轮处理前通过就绪回调实际调用执行器的次数（只限 index 0），
  不表示服务端已受理，也不保证网络传输仍未结束。
- `first_dispatch` 为最终 HTTP 尝试起点到首次调用执行器，包含客户端队列延迟；
  `stream` 为请求到传输完成的耗时，不含随后客户端排队。若前者晚于后者，不能宣称省下流式等待。
- 未发生的时间为 -1；失败也报告已有统计，失败轮的 cache_waste 为 -1。
  只实现旧接口、不提供快照的引擎不伪造传输时间；观测回调异常不改变任务结果。
  既有一次 `/v1` 换道每次重建计时器，仅交付最终尝试快照，不混入前次耗时和计数。

回滚开关：把 `AgentLoop` 的 `engine.chat(..., reactor, true)` 换回三参 `chat(...)` 即走旧路径（代码保留同语义）。

护栏数值：每指令 40 步；同一调用连续 3 次 → Nudge 换思路、5 次 → 停链——但只有
**同调用且同结果**才计数（TIMEOUT 后原参重试是合法恢复，不算打转，M4.5 吸取参考项目实战教训）。
上下文每步记API回报prompt_tokens（无则CJK感知估算），历史软水位扣除
system/工具/临时观测固定输入，默认6000、近期预算1500；
工具配对完整的请求边界及链尾只有足量可替换旧段才自动整理，空操作不提示或重复观测。
完整请求的硬窗口保留，不因软水位改变而省去工具/观测预算。
切分保留近期预算内最早完整User/Assistant轮次，绝不拆孤儿Tool；
仅status/inventory的可替代旧查询出站折叠为占位，动作回执不按同名折叠（存储全量）；
摘要端点连败2次熔断至下个指令边界，空白/带工具/超预算或旧代际摘要不能替换历史；
替换后至少节省32token或旧段20%（取大），拒绝膨胀摘要。
已有摘要单独累积更新；整理后重新观测身体，原指令和已登记目标事实独立注入。
完整请求预算含system/工具/历史/临时观测/目标，输出预留min(2048,窗口/4)；
usage加新增估算和完整本地估算取保守值，摘要输入也限预算，仍装不下则CONTEXT_LIMIT停下。
`ToolExecutor` 在 mod 侧的实现 = AgentRunner：发 `tool_call{seq}`，等对应 `tool_result`
（或先来的 `job_ack` → 转等 `job_event`）；普通工具 90 秒无回执 → 回 TIMEOUT 教学文本，
长活按服务端报的 `cap_ticks` 算上限（见 §5.1）。本地工具为
ask_owner/workflow/gather/explore/run_program/set_goal/report_blocked，后两者由loop本地拦截。
run_program只可调用15个游戏工具及gather/workflow/explore，不可反问/改目标/嵌套程序；
动态参数复用现有Schema校验（含攻击oneOf），任务只读仍在executeRemote和服务端核对。
失败/需授权停止程序并保留原回执，不能在程序内批准或捕获失败后继续依赖动作。
gather/workflow/explore的原子派发统一经executeCompositionRemote，程序活动期间共享max_calls，
独立调用各沿本地限额；取消同一任务的组合及terminalOnly子票，不取消其他任务。

BoundedGather默认scope=task使用任务开始背包基线并登记固定最终条件；
scope=subtask从本次第一份完整状态建立局部基线，不登记/修改最终条件。
状态、匹配掉落、站位/移动/有界等待由本地循环推进，不调用collect；
选中工作/拾取站位使用exact=true，走近后按新鲜背包核验自动拾取。
没有待拾取候选才查来源方块、工作站位、move_to、break_block，再刷新状态。
子调用复用terminalOnly票与原权限/资源锁，每步等真终态；取消/超时清子票且禁止迟到续跑。
目标固定item/count/mode，按实际背包净新增或总量完成，不累计挖掘格或掉落。
局部候选耗尽后可有界步行换区，目的地与工作目标受原起点探索半径约束；
不自行补给或挖掘勘探，需授权或缺事实时交回模型，详见TOOLS §1.1。

C6流程不占服务端外层槽：BoundedWorkflow逐步调用原子工具，子票terminalOnly=true时，
job_ack只搬PendingJobs、不兑现future；实际job_event兑现子票，不送入AgentLoop的普通job账本。
最多12步/300秒/128件请求量，参数与只读模式整份预检，失败/空搜索/缺条件即停。
取消先停止本地清单、清子票、补保留既有回执的workflow结果，再由loop完成历史配对；
在途未知与已装料机器自主运行分开说明。无移动/攻击/批量存取/拾取或脚本循环，
原版材料/产量/掉落不由请求量假定，完整契约见TOOLS §2.9。
loop累计三次结构化空搜索停任务，不因换参数/状态重置，真实目标或物品进展可重置；
第三次结果打开terminal gate前检查，阻止同轮尚未派发动作。
ToolOutcome新增防御复制的data，2/4参构造器兼容，普通模型工具反馈不改写。

取消/关闭先递增代际、取消当前模型future（含链尾摘要）、封闭旧反应器。
CallbackChatEngine取消向delegate传播，排队旧信号丢弃；LlmClient每次chat独立取消控制，
覆盖原HTTP future/SSE Flow.Subscription及既有一次/v1换道。
取消后不换道或消费后续帧，已发出的游戏动作无法回滚；真实断线/身体停止仍待验。
早派发取消且尚无完整assistant工具轮次时，以Nudge保留已经取得的停止回执，
不伪造assistant/tool配对；完整轮次已形成时仍用原账本工具回执。

反问由 AgentRunner 绑定 task_id 与进程内递增的 question_id，回答必须使用原编号且只消费一次。
tick 和回答入口都检查 120s 期限；过期回失败回执，重复/未知/已取消问题拒绝回答。
回答确认先发布 state，再兑现工具 future 继续原任务；无有效问题的游戏内回答不创建新任务。
连接重载/关闭先清理旧 loop 和问题；重载建立新模型上下文，关闭可重复调用。
只改人设/偏好不重建loop或取消任务；人设标脏后在下一条指令边界换发system。
显示档案PanelHistory独立于Conversation，保留当前世界会话内20任务/2000记录，
连接重载/模型整理/清空不删除，退出世界或重启不持久化。
面板问题快照和答复保留原ID，授权确认需读到全文末尾且问题仍有效；
面板直接读取runner快照/档案，没有将BridgeEvents改为多播。
Conversation暴露实际摘要与真数/估算用量，预算可配置；
手动整理仅空闲开始并与新任务串行，失败保留原文，清空取消在途摘要。
前缀重置原因现在为compaction、directive-boundary、context-clear。
已关闭 runner 拒绝 reconfigure，不再保存配置或显示重启提示。
只读检查显示另外绑定大脑代际，防止同一个 runner 重载后显示旧检查的回执。
客户端服务通过内部 ClientServices 边界提供模型、提示词、时钟、聊天、取消、配置保存、
客户端队列与 C2S 网络副作用；
生产使用 MinecraftServices，离线回归替换这些边界并直接测试真实 AgentRunner，
不启动 Minecraft、不写玩家配置、不调用模型端点。

McbotClient 的 JOIN/DISCONNECT/S2C 交由 ClientSession 持有当前 runner：
退出先关闭旧 runner、再关桥、最后移除 runner；加入建立新 runner、查询同伴后启动桥。
重复加入先收尾旧会话；已排队 S2C 同时校验接收时捕获的 runner 身份。
配置重载复用 runner 和桥，新进世界更换 runner 与桥。

### 5.2 长任务能力边界（2026-10-13实现）

LT1物品目标校验/任务内请求预算与有界LT2采集已接线，40模型轮数和原权限护栏保留。
最终数量条件需模型set_goal或默认gather登记，原始指令与目标事实独立保留，不能降低既有条件。
材料gather用scope=subtask，可先后处理不同物品，成功不代表整项完成；
additional按本次新鲜基线计，total按真实总量计，结束校验仍只认最终物品。
没有登记条件的普通文字结束仍按COMPLETED；没有任意自然语言通用验证器。
L1已接显式建筑及容器交付条件，见本文顶部；不等于通用语义裁判或L2跨退出恢复。
gather减少逐格模型安排，按真实回执查资源及有界换区；固定workflow保持12步。
独立explore可供非采集观测复用；补给/地下勘探/目标跨退出恢复、跟随护卫和技能
按需读取尚未实现，技能正文仍全量注入system。
实现与离线证据见STATUS，机制对照及剩余卡见 `AGENT-PERFORMANCE-AND-LONG-TASKS.md`。
64份离线回执测试不替代自然世界自主采集钻石或真实模型速度测量。

## 6. 桥接（连接器入口）

mcbot 提供任务桥及 MC agent 执行能力；连接器负责宿主侧任务适配、用户回答与事件展示。
连接器以外部任务契约为集成边界，不依赖内部游戏协议或寻路实现。

- 内核 `BridgeService`（agent-core）：REST `/v1/*` + MCP `/mcp` 双接口一内核；
  适配层 `BridgeHttp`（客户端）：JDK HttpServer 绑 `127.0.0.1:17897`，
  `Authorization: Bearer <token>`（token 在 `mcbot/bridge.token`，常量时间比较），
  SSE `Last-Event-ID` 从 200 条 `EventRing` 补发，15 秒心跳。
- 外部任务契约 v1.0：BridgeContract 共享输入 Schema 给 MCP discovery；
  status 与 SSE data 带 contract_version/session_id，桥重建换 session、事件编号重新从 1 起。
  REST/MCP 严格参数类型，安全 error_code；body 超限为 413。SSE 补发与订阅在同一锁内排序，
  不把旧桥游标带进新世界；无持久任务查询/幂等提交，超时与连接丢失不能自动重发。
- 生命周期：JOIN 起、DISCONNECT 关；桥只服务当前客户端的本地玩家。
- RunnerBackend 的投令/回答/取消进入客户端队列后再检查当前 runner 身份，
  防止排队命令跨重连作用于新会话；离线测试注入同一查询/队列边界，
  使用实际 backend 与本地 HTTP/SSE，不替代真实连接事件与服务端停手证明。
- 事件四类帧：progress（工具回执/护栏）、done（唯一任务终态，含 status）、question（反问，
  带 question_id）、state（queued/running/PARK/回答确认/公共生命周期）。仅匹配任务的 done
  结束窗口；task_id=0 为公共事件，不结束其他任务。状态与取消详见桥契约。
- 任务编号在投令时分配，但 current_task 只在 onTaskStarted 更新；排队不改变活动任务归属。
  ask 等答由 TaskReplies 按任务编号配对，不取其他任务的回复。
- fragments 兼容收集本任务及公共事件的所有非空 text，不是 progress 专用字段；
  结构化处理依赖 SSE。完整权威契约、机器 Schema/示例路径及连接器验收见 `docs/BRIDGE.md`。

## 7. 身体层（假玩家）

- 被动物理：CompanionPlayer在super.tick及装备/动作计时之后每刻调用CompanionPhysics，
  无任务/任务取消也继续推进。检查身体及速度扫过的邻域区块已加载，再更新原版流体状态、
  travel(本刻输入)与方块接触；空闲为Vec3.ZERO，速度/重力/碰撞由原版负责。
  MovementInput单刻消费/过期，任务续订世界方向、消费时换算身体朝向的局部输入；
  只在着地且请求跳跃时调用原版jumpFromGround，按实际位移更新行走动画。
  原版Player仍是client-authoritative，Entity.move不结算其摔落，因此按实际位移调用一次
  doCheckFallDamage；setKnownMovement记录本刻位移，移动后更新区块位置。
  不开启完整doTick/饥饿时钟/自动playerTouch；既有无敌保持，不宣称完整生存物理。
  死亡/移除/睡眠/骑乘跳过；未知邻域等待区块票，不强制读取未加载碰撞格。
  计划外下落不搜索/工作，清裂纹，落地高度偏离则有界重规划；
  计划内跳/落由PathSteering持续跟踪，终点/工作节点须实际位置/高度/近静止且着地；
  无挖放的同层直线中间节点保持运动通过，不逐格刹停。
  路径传送已移除，取消/终态/重规划清输入，不删除速度或冻结重力。
- 身份：`offlineUuid(name)`（OfflinePlayer 公式）→ 名册（`<世界目录>/mcbot/companions.json`）记归属；
  背包/坐标在原版 `playerdata/<uuid>.dat`，名册不备份身体数据。
  **1.21.11 的 `placeNewPlayer` 不负责加载存档**：SummonService 先调用
  `PlayerList.loadPlayerData(NameAndId)`，以 `TagValueInput` / `ServerPlayer.load` 恢复身体，
  用 `ServerPlayer.SavedPosition.MAP_CODEC` 选择存档维度，再 `snapTo` 确定入场坐标/朝向。
  之后才通过 `placeNewPlayer` 注册身体（FakeConnection：EmbeddedChannel + 丢弃出站发包 +
  吞 disconnect；不在 ServerConnectionListener 的连接表，因此没有 connection tick/keep-alive）。
- 进场后**必须重设**游戏模式/无敌，以覆盖存档中的值。服务器重进在**身体实际维度**做
  **安全落点检查**（存档点不可站 → 就近挪 → 该维度出生点）；缺失/不可用维度退回主世界出生点。
  显式召唤仍在原有主人附近/主世界出生点进场，读取同 UUID 的旧背包但不沿用旧位置。
  遣散与停服均走 `PlayerList.remove`，该方法先保存玩家再移除，不另外维护第二份身体存档。
  主世界/下界的跨进程位置、朝向、非空背包及遣散再召唤已有专项 SelfTest 正证；
  单人同进程退出世界再进入已有位置、非空背包占用与手持物正证，
  完整背包对照和整客户端重启已由维护者确认实测，原始验收记录待补齐，
  不将此前局部日志扩写为完整通过证据。坐骑/在途末影珍珠不在本轮恢复验收范围。
- `status` 返回位置、生命/饥饿、36 格背包占用、手持物、着火状态、游戏刻与实际任务计数；
  details=true增加完整背包/装备明细，每次模型请求前通过ToolExecutor.observe读取，
  作为本轮临时尾部消息，不写入历史；观测失败停止任务，取消/代际校验隔离迟到快照。
  `inventory` 额外枚举逐槽物品 ID/数量/耐久及装备映射槽，feedback 和 data 都带明细；
  重进世界重建历史后可重新查看，不能凭占用数还原其他物品清单。
- `equip` 仅切换主手：快捷栏选中或背包与当前选中槽交换原始堆栈；
  保留组件和数量，满背包可交换；scheduler 忙时拒绝切换，inventory 仍可读。
  1.21.11 Inventory 有 36 个存储槽和 7 个装备映射槽（总 43），主手只选 0-8，
  equip 来源范围 0-35，不把装备槽当可选快捷栏。
- 每同伴一个活跃任务槽（无队列）：忙时工具层直接回 `BUSY` 教学回执。
- transfer/collect 及两条挖掘掉落路径共用 ItemTransfers，按实际移动数而非
  Inventory.add 的布尔值记账。存取逐槽同步扣来源，拾取只写回余量，
  挖掘接收原版产生的本次附近新实体，余量写回原实体，不重复生成掉落；
  先合并完整组件再用空槽，背包限 0-35。
  transfer 检查普通容器锁/有效性、放入/取出槽与 WorldlyContainer 接触面权限，
  遵守容器/物品堆叠上限；transfer/collect 在 scheduler 忙时拒绝。
  部分搬运允许已产生副作用，ok=false 的反馈仍给实际移动/剩余量，不自动回滚或重发。
  内部 Access 只隔离身体/世界观测，离线回归运行实际工具处理与真实 Inventory/ItemStack；
  挖掘采收/耐久已接入 C2 共享动作入口，真实运行另待验；
  C3 的 collect 另约束身体6.5格、请求球体、加载/存活、延迟和原版 target，
  target 通过只读 Mixin accessor 获取；不使用 getOwner（投掷来源），实际加载仍待验。
- `craft` 是 SYNC：从当前服务器 RecipeManager 选普通 ShapedRecipe/ShapelessRecipe，
  不硬编码材料或产量。2×2 随身，3×3 要求 5.5 格内已加载的工作台。
  原版 StackedContents 以实际堆栈身份/Ingredient 谓词分配材料，真实 CraftingInput
  再验 matches/assemble；整批复制模拟、返还物/成品组件合并与空间检查通过后提交。
  只用 36 个存储槽，选中槽/装备不变，任一批失败全部不改背包；
  query 只读且忙时可用，feedback 同时给配方/材料/次数/工作台/失败建议。
  不递归制作缺料、不支持特殊动态配方或 GUI/制作统计/成就事件；细则见 TOOLS §2.2。
- `smelt`：SYNC query/load/take 操作附近原版熔炉/高炉/烟熏炉三槽，整次物品移动先复制预检。
  原版 tick 负责燃料/时间/成品，取消任务不熄炉；FurnaceAccess mixin 读取真实计数与配方选择。
  新原料须有可用配方/燃料/下一件成品空间，只补燃料可给空炉备料；返还桶从 fuel 槽回收。
  仅用背包 0-35，完整组件合并与容器/物品堆叠上限，预检失败不修改任何实际槽。
  当前机器计时与当前配方时间分别报告，反馈/数据都给整批剩余 tick 与燃料是否够用。
  transfer 拒绝熔炉类，避免用普通容器语义操作机器槽。
  内部 Access/Machine 仅隔离世界观测；生产 PlayerAccess/LiveMachine 读实际同伴/机器，
  离线替身运行同一 load/take/report 和真实 ItemStack/配方 assemble，20 项操作回归通过。
  另有参数 3 项、实际 runner 5 项（含取消）通过，实现/离线阶段完成；
  不模拟原版 ticker，真实烧制/燃料返还/Mixin 加载/保存重进与联合验收仍待验。
- 挖掘：假玩家没有 connection tick，原版 `handleBlockBreakAction` 的计时推进不可依赖。
  单格与路径共用 BlockMining，由 scheduler tick 按 `state.getDestroyProgress` 的
  原版实时增量计时，采收资格与 canDestroyBlock 先检查，目标/完整主手变化即停。
  裂纹以同伴 entity id 广播，完成/失败/取消/路径重规划清除。
  最终只调用一次 `player.gameMode.destroyBlock`，原版负责方块钩子、采收及实际耐久；
  返回 true 仍须核对目标原方块实际消失。仅吸取目标近邻内本次新产生的物品实体，
  原实体保留组件余量，旧掉落不动；不自行求战利品或 popResource，不自动收经验。
  真实原版动作/耐久/掉落尚未运行；局部新实体观测不保证任意模组掉落的精确归因。
- 放置：单格与搭路共用 BlockPlacement，直接用真实同伴及完整来源堆栈的
  BlockPlaceContext 调 `BlockItem.place`；固定目标格、面默认 UP、保留当前朝向，
  原版执行支撑/碰撞/多格/组件钩子及消费。不再 setBlock(defaultState)+手工 shrink。
  暂限四类已知物品上下文，脚手架/告示牌等特殊子类拒绝；
  成功需目标状态变化且非空气、原版 consumesAction、来源恰好消费一件。
  busy、范围/已加载邻域、原版交互/mayUseItemAt 先检查；失败报告实际副作用。
  离线 Access 观测夹具不创建世界/身体，不宣称原版放置行为已实测。
- 移动（M8 起）：move_to = DigAStar 任务。节点=落脚点（脚格+头格可通行、下格有支撑），
  边统一建模为"清身体经过的目标柱空间(挖)+补支撑(放)+移动"。
  平地/上行清落点两格，下坡还检查起步头高所经过的第三格；自上而下清理，
  搜索代价/挖帽、路径重建、活体确认清单和移动前复核共用高度范围。
  剩余清单重新确认时保留上一节点高度；新增格仍走原具体授权/自主模式和安全检查。
  由此派生 走/跳/落/下挖/向前挖/垫脚/搭桥。
  约束全在 LevelDigSampler：神圣集（容器/工作台/床/机关本体与其支撑+任意方块实体）、
  岩浆邻接否决、起点脚下不挖、单格挖>20s 不值、搜索盒水平 64/垂直 32。
  逐次确认模式改世界先回 NEED_CONFIRM+具体清单，主人确认后模型带 authorization_id 重发；
  主人选择自主模式时从当前任务上下文放行，计划及逐格动作仍走共同权限入口和安全复核；
  执行期每20节点复核未来5节点，变了就地重规划（≤2次）；
  每刻按测得的位置/速度选择方向/跳跃输入，卡住约60刻或单步超120刻有界重规划/失败，
  不传送兜底。默认相邻一格终点，exact=true为精确脚格；精确模式不复用附近终点缓存。
  多节点路线跳过ACT_START；同格路线仍需实际站稳。支撑查询包含世界底层minY。
  搜索分帧每tick最多300节点/6ms，累计8000节点/400ms，不能保证重节点无单拍尖峰。
  C2 的 PathMaterials 统一规划预算与执行选料，只用存储槽四种无组件补丁垫料；
  放置确认后必须复核支撑，路径终态按实际挖/放/留地量汇报，失败/重规划清旧裂纹。
  DigSampler.feasibleDig 在搜索/memo/执行共用，拒绝 Double.MAX_VALUE 哨兵及无效代价；
  邻接岩浆/神圣支撑查询先检查加载，未知邻格保守否决。
  may_alter_terrain 不授予权限。ActionPermissions 授权绑定 owner/companion/task/维度/目的地，
  每项含操作、坐标、完整 BlockState；缓存/重规划重建清单，逐格执行复核，
  已执行后被补回的格子、新位置/操作/状态均重新确认。落脚格变化先重规划。
  服务端完整清单通过 data 留在 runner，再由 ask_owner 的 question 展示给主人，
  不采用模型替代文案；明确确认后 C2S authorize 得到真正回执才放行模型。
  只读策略来自投令文本的明确标记/限制短语，只收紧权限，不声称理解任意自然语言限制。
  普通任务保留原子动作能力，每次目标/局部范围仍受工具和原版规则限制，详见 TOOLS §2.6。
  ResourceLocks 保持每次动作的身体及世界区域租约直到真正终局；
  路径取得具体改动邻域，补充区域时原子拒绝冲突，失败/取消/超时/身体替换统一收尾。
  正常终态用 onFinish，异常/中止用 onAbort；清理钩子失败也释放资源并完成唯一 future。
  锁仅协调本插件，不排斥玩家/ticker/其他模组；未做服务器抢占/续跑/progress 生产。

RG6在CompanionPlayer/SummonService/status读取处补 `[body-health]` 观测：
构造完成、加载/无存档、定位、注册、就绪各采样，tick/status相邻生命/吸收/无敌变化才输出；
原hurtServer前后采样并记录首个伤害请求。每个身体有本次body_id，UUID/名称/维度/游戏刻
与相邻快照用于对齐，body_id不是存档或桥session。原版返回/异常继续传播，
不补血或改无敌/移动/存档，不调用完整doTick。采样不能覆盖所有直接写值或间隔内恢复，
没有来源的变化不猜伤因；说明及真实待验见RG6-HEALTH-WORLD-OBSERVATIONS/STATUS。

### 7.1 有界近战（C4，D1扩展）

AttackTool 以实体编号+UUID或一次近身Enemy选择构造 EntityAttack，交给现有调度器，
最多10次挥击/400tick；默认1次。每tick检查身份/保护/范围/视线/主手与资源，
满冷却且目标恢复后调用原版 Player.attack/swing；默认不追击。
approach=true使用上方D1有界只行走接近；不换目标/自动换装备或主动追捡，
身体原版触碰拾取仍运行。
玩家/同伴/宠物/同队拒绝，中立/命名目标沿用服务端具体确认。
许可包括目标身份/类型/Enemy及命名标志、挥击预算、approach与原锚点，
无法借旧批准扩大次数、改目标或将原地许可升级为移动许可。
身体/实体UUID/目标区域租约到终态释放；实体移动后仍占其UUID，再扩展新的区域。
TickTask.interruptedResult 让取消/超时/异常保留已完成出手与未知效果标志，其他旧任务默认回原结果。
主手变更需停手；本次原版攻击的耐久/破损由原版负责，可在回调后更新快照。
回执区分已调用次数、前后生命/吸收观测与目标死亡，不做独占伤害/击杀归因。

现有假连接没有 listener tick，ServerPlayer.tick 本身也不调用 Player.tick。
因此 CompanionPlayer 的世界tick通过 MeleeClock 推进原生 attackStrengthTicker/itemSwapTicker，
换物品类型重置；LivingEquipmentAccess 调原生 detectEquipmentUpdates 完成装备属性同步。
不引入完整 ServerPlayer.doTick/食物/自动触碰拾取；被动物理由CompanionPhysics单独推进，
保留身体无敌。
新Mixin、实际身体时钟/装备属性/原版伤害/横扫/取消仅编译，离线使用可控观测及实际逻辑；
不代表完整生存战斗、自动防御/进食、死亡复活、模组钩子隔离或连接器联合验收已完成。
完整契约/边界见 TOOLS §2.7 / DEVELOPMENT §3.8 / STATUS。

### 7.2 任务资源感知（C5）

FindResourceTool对显式方块ID/标签做最近优先有界采样，报告已读/未知/撞帽及绝对候选坐标；
InspectBlockTool对近身完整加载方块的本体容器做24槽分页只读报告，拒锁/未展开战利品，
不生成区块、不展开战利品或开启菜单。它们经过相同owner闸、被TaskPolicy列为只读。
CompanionScheduler.observation取得当前实际槽与TickTask计数，不把age/cap换算完成率；
路径/挖掘/攻击/wait提供各自已有真实计数。无实时progress网络生产者仍单独挂账，
本轮不增加外部桥字段或改变completed含义，真实身体/世界感知另待验。

## 8. 配置文件与运行目录

| 文件 | 归属进程 | 说明 |
|---|---|---|
| `<gameDir>/mcbot/client.json` | 客户端 | 连接/人设、accept_mode及Preferences（context_window/compact_threshold/recent_budget/auto_compact/fold_tools/dark/show_hud）；`MCBOT_*`优先覆盖连接字段；缺任一必填 = 大脑不启动 |
| `<世界目录>/mcbot/companions.json` | 服务器 | 该存档的名册（uuid/name/ownerUuid/ownerName），Gson；同一存档重进恢复，不跨存档召唤 |
| `<gameDir>/mcbot/bridge.token` | 客户端 | 桥鉴权 token，首次进世界随机生成，跨重启稳定 |
| `<gameDir>/mcbot/skills/*.md` | 客户端 | 技能笔记原文拼进 system prompt |
| `<gameDir>/mcbot/autotest.flag` | 服务器 | 存在即启动约 3 秒后跑 SelfTest 并自删（仅开发） |
| `<gameDir>/mcbot/autotest-stop.flag` | 服务器 | 开发验收链结束后自删并正常停服；未设置则保持运行 |
| `<gameDir>/mcbot/autotest-persistence-seed.flag` | 服务器 | 独立身体恢复验收第一进程：设置主世界/下界样本，正常停服；只允许 `mcbot-persistence-*` 开发世界 |
| `<gameDir>/mcbot/autotest-persistence-verify.flag` | 服务器 | 独立第二进程：对照实际 UUID `.dat` 与在线身体，核验后测试遣散再召唤，正常停服 |
| `<gameDir>/mcbot/autotest-inventory.flag` | 服务器 | 独立背包/主手交换专项；仅允许新 `mcbot-inventory-*` 开发世界，拒绝并存普通/恢复测试 flag，结束正常停服 |
| `<gameDir>/mcbot/autotest-craft.flag` | 服务器 | 独立合成专项；仅允许新 `mcbot-craft-*` 开发世界，配套测试数据包仅复制到该世界，拒绝并存其他专项 flag，结束正常停服 |

旧实例级名册仅在当前世界尚无名册时迁移：必须有同 UUID 的 `playerdata/*.dat` 才导入，
迁移结果（包括空列表）写入当前世界，旧文件保留且不修改。匹配原版玩家数据证明它曾在此世界出现，
不能区分旧 bug 造成的误重进；此类既有伙伴可在该世界正常遣散。

## 9. 交互入口速查

- 键位：**G** 开面板（`key.mcbot.panel`，MISC 分类，可重映射）。
- 面板：对话页派发/改派/停止、回答反问与完整授权确认、会话历史回看；
  同伴页真实身体/36格背包/快捷栏/装备及召唤/确认遣散/刷新；
  设置分模型/上下文/通用，含差分保存、摘要管理、主题、HUD及桥令牌复制。
  居中窗口默认浅色，常规约80%屏宽/83%屏高、最大760×460 GUI像素，
  历史页/确认框最大440×320；外部游戏画面不加全屏底色或菜单模糊，右上角关闭。
  宽面板侧栏/小面板顶部导航；区域可滚动，多行草稿切页与resize保留，
  Enter发送/Shift+Enter换行，工具过程默认折叠；仅完整落入视口的表单控件可交互。
  密钥通过 EditBox.addFormatter 星号显示，覆写读屏消息避免朗读原值。
- 查看状态入口及同伴页刷新直接派发白名单只读游戏工具，不调用模型、不生成agent task；
  身体快照按runner/身体代际隔离；未召唤仍由owner闸拒绝工具。
  物品图标按注册ID展示基础外观，不假设未回传组件，点槽不操作背包。详见 `PANEL.md`。
- 设置的模型子页连接测试：ModelConnectionTest 使用独立 LlmClient，声明正式任务工具Schema及connection_probe，
  仅本地补测试工具的配对回执后再请求最终文字；选择游戏工具则失败，游戏工具不执行，配置不自动保存。
  非 200 为 LlmFailure（HTTP 状态/网页类别），未知异常不回显原文；200 空流不作为成功作答。
- 模型通信当前仅实现 OpenAI 兼容 Chat Completions SSE；模型名称可自定义，
  但尚未实现 Responses 或 Anthropic Messages。LlmClient 向宿主回调 ResponseDiagnostics：
  每次 HTTP 尝试只包含状态码、固定格式/结束原因/服务错误枚举及字节/帧/解析错误计数，
  不含 URL、请求正文、密钥或远端文本。RequestDiagnostics 在发请求前统计实际出站 JSON：
  角色/规模、工具配对/参数结构及流式选项；只含枚举与数值，不保留内容或内容哈希。
  任务写 `[brain] llm request/response`，面板测试写 `[model-test] llm request/response`，
  按本进程唯一 request 编号配对，避免相邻行误配或依赖游戏未收集的 System.Logger。
  ServiceErrorDiagnostics 将 error.code/type/param/status 映射到本地固定类别，
  错误文本匹配只记 MESSAGE_HINT，未知值 UNKNOWN，不回显原文；普通 JSON 错误诊断
  捕获上限 16384 字符，超限单独标记。此诊断不新增其他模型协议，也不改出站请求。
  HTTP 200 内的 error 帧单独报服务错误（即使此前有部分文字也不算正常成功），
  error 出现后后续帧不再触发新工具，已早派发动作不能回滚；200 HTML 报地址/网页问题。
  空响应仍失败、不自动重试，单次失败不是不兼容的证明。
- 聊天：`@bot <指令>`（不进服务器聊天）；整句 `停/停下/停手/取消/别干了/别做了/cancel/stop`
  = 叫停（不走模型）。
- 服务器命令（OP，gamemaster 级）：`/mcbot ping|summon <名>|dismiss <名>|list|verify_readonly`。
  verify_readonly只接受玩家，在自己的同伴空闲时显式验证服务端只读拒绝。
- 桥/对外：`docs/BRIDGE.md` 或随包的 `dist/README-BRIDGE.md`。

## 10. 关键常数速查（改代码请同步这里）

| 位置 | 数值 |
|---|---|
| AgentLoop | 固定40步/指令、nudge@3、abort@5（同调用**且同结果**才累计）；压缩阈值默认6000、近段预算默认1500 token（可配置，CJK估算兜底）；熔断2次 |
| 面板偏好/档案(R3) | 窗口默认64000（4096..2000000）、阈值≥2048且<窗口、近期≥256且<阈值；auto_compact/fold_tools/show_hud=true、dark=false；会话内20任务/2000记录，不跨退出/重启 |
| 流程(C6) | 本地1-12步、默认180/最多300秒；请求量合计≤128（不等于材料/产量）；明确挖放≤12；参数≤16384B；累计反馈12000B停后续、结果全文展示预算24000B；任务空搜索3次停 |
| 通用组合 | Lua code≤16384B/参数≤20000B；默认300/最多900秒；API调用和实际原子派发各默认128/最多256（嵌套共享原子预算）；总250000/每两次API间25000指令；48层调用、65536数据单元计费、8MiB字符串累计计费/单值≤65536B；回执/返回共享20000B；不等同OS进程内存隔离 |
| 换区探索 | 独立explore每次一段、默认/最多180秒、≤52子调用；与gather共用同任务默认8/最多16次移动、半径16-64默认64，省略继承/显式参数固定，gather可0关闭本次；采集样本步距随r缩放、默认16/8，8方向/200探针，水平新位移≥4格计区域；有效完整已知查询覆盖64条/120秒、33体积样本估计评分，挖清失效 |
| 目标/采集(LT1/LT2) | 最终item/count/mode固定；count1-4096，36槽完整基线；gather默认task用整任务基线，subtask用本次第一份状态且不改最终条件，material_for最多64种配方来源按实际件数计；结束前三次无进展回复FAILED；gather起点附近r默认16/范围1-16，默认600/最大900秒，默认128/最大256挖，子调用≤1024，参数≤4096B/子反馈≤24000B，候选站位≤6；三次操作无入包且无可拾取候选停 |
| 完整资源搜索 | search_resource默认r16/范围1-16、目标1-8、最多5页/30秒，返回≤16候选，反馈/data各≤24000B；页仍4096格/24工作评估/2048射线，程序预算逐页计；unknown保留，取消不续页 |
| 超时 | LLM 连接 15s、任务请求 180s、模型页连接测试请求 30s（连接限制不等于所有响应头等待上限）；工具回执 90s；**长活**按服务端 `cap_ticks`×50ms+15s（move 3min⇒195s）；ask_owner **120s**；桥 ask **135s**（必须 > 反问 120s）；task 窗口 ≤120s |
| 闸② 速率 | 容量 60、补充 20/s（按玩家） |
| 信封 | 上限按 **UTF-8 字节**：32768（含前缀）/ 体 32765；超限入站丢弃、出站换瘦身回执 |
| 任务帽 | 默认 60s；break 60s；move 3min；attack 400tick≈20s；wait n·20+100 tick |
| 行动参数 | break 预检 5.5、执行 6.5；place 6.5 与 3×3×3 已加载/可交互邻域；滑步 ≤48 格、0.45 格/tick；挖掘用实时 getDestroyProgress，零硬度可立即完成、无效增量连续 10t 停手；新掉落范围目标 AABB 膨胀 0.5 |
| 路径垫料(C2) | 存储 0-35；无 components patch 的圆石/深板岩圆石/泥土/下界岩；规划预算和执行选择同一谓词，装备/副手不算 |
| 动作权限(C3) | 服务端proposal TTL180s，一次使用；最多256个独立改动，完整清单≤12000B；每任务一个待确认方案；区域初始≤256，累计≤512；collect中心/实体≤6.5格且在请求球体内，还须与body.inflate(1,.5,1)相交，原版playerTouch执行拾取 |
| 攻击(C4) | 原版isWithinAttackRange(AABB,0)且身体局部≤6.5；近身候选最近≤64；已加载邻域≤4096格；默认1/最多10次；完整冷却及目标invulnerableTime≤10；剑扫域AABB.inflate(1,.25,1)不能有第三活物；每租约实体UUID≤256 |
| 感知(C5) | 每轮快照≤16384 UTF-8字节、沿用status90s超时；find_resource目标1-8、r1-16默认8、4096格/16输出；work≤24目标/2048共享射线，单计划≤64射线/3遮挡；路径掉落≤256 UUID/16展示；inspect_block6.5格、每页24槽/容器≤1024槽 |
| 寻路(R1/N1 后) | 双帽：预估与搜索共8000节点 **或** 累计CPU400ms；每拍6ms/300工作项；32768累计存储记录；128挖帽；放≤背包存量；搜索盒64×32×64；单格挖≤20s；h=1.8×(0.467×剩余距离+有限接近清理价)，无最优界保证；终点比较路线+清障费用；PARTIAL下限gain≥4；重规划≤2且分帧（旧路格×0.7，失效格周围排除）；复核20/5；memo帽262144，超帽直读；验尸256实查/抽8格/不符超24丢图重开≤2；dig量化0.25s |
| 同伴区块票(S3b) | 自定义超时票 40t（LOADING|SIMULATION，无 PERSIST）；半径 2 chunk（5×5 垫）；END_SERVER_TICK 每拍续票（先于 scheduler）；只续不撤，停续即过期自清；不设 owner 在线闸 |
| 感知(R2-S1) | classify 6 词表；ROCK_PATHS 10 路径常数（**不含**泥土沙/加工石）；ore=endsWith("_ore")；三层步长 1/2/3，名额 细列≤8/组≤10/远≤12，MAX_SAMPLES=900；坐标 `@(x,y,z) d3.2`+首行八向；准星注入≤120B（MISS/ENTITY 不注入） |
| 前缀(R2-S2/R3/LT1) | FOLD_KEEP_TAIL=12；仅status/inventory在index<foldCheckpoint冻结区折叠；prefix reset为compaction/directive-boundary/context-clear；system换发仅指令边界；自动整理在工具结算后的请求边界和正常链尾，手动仅空闲；完整窗口预留min(2048,窗口/4) |
| 流式(R2-A/F0) | SSE 逐行解析；闭合判定=顶层括号配平且 required 齐；客户端 CallbackChatEngine 送主线程；仅 index 0 早派发，后序延迟串行；记账按 index 原序；arguments 只累积一次 |
| 受理/长活(R2-S4) | `ACCEPTED:` 前缀（agent-core 常量，唯一真源）；受理后等待上限 = `cap_ticks`×50ms + 15s（`PendingJobs.JOB_GRACE_MS`）；ACCEPT 工具为 move_to(3600tick)/break_block(1200tick)/attack(400tick)，其余 SYNC；单终局（一个 seq 只会收到 result **或** ack）；lastPlan TTL 30s（判据：同伴+目标+起点）；`accept_mode` 默认 true（止血开关） |
| 桥 | 端口 17897、body ≤64KB、环 200、心跳 15s、线程池 **8**（R0：每 SSE 永占一线程，4 会饥饿；彻底解法归 R2-C） |
| 名册 | v1 每位玩家 1 同伴；名字 `[a-z0-9_]{2,16}` |
