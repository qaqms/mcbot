# 开发上手（环境 / 构建 / 验收 / 纪律）

> 面向项目维护者、贡献者与自动化开发工具。环境配置见本文，当前进度见 `STATUS.md`，开发约定见 §5。

## 当前验证安排（2026-10-14）

2026-10-17本轮D2→B3逐卡收尾。B3最终强制全量1387项/160 suites，
核心347/40、根137/20、客户端903/100，失败/错误/跳过全0；
build/b3-final3-20261017.log第61–62行，45秒/20任务全部执行。
当前十二卡完成、10待实施，下一B4→B5，本轮不启第三卡。
完整参考/有限支持与失败阶段见B3-MULTI-STANCE-CONSTRUCTION和STATUS。
新增41项/3类验证实际Lua/runner/dispatcher及原挖放核、来源失效/保护/预算与取消；
世界/身体/原生实际动作受控，未另跑旧基线缺功能before，不虚构原生实测。
任务内工地不等于B4持久实例或L1外层目标。未安装包或启动游戏/服务器/真实模型，
原日志目录本环境不可用，无新实机证据；只改本体，不改宿主/连接器/参考/实例及玩家配置。

2026-10-17本轮D2→B3，D2独立强制全量1346项/157 suites全过，
核心337/根137/客户端872，失败/错误/跳过全0；build/d2-final2-20261017.log第61–62行，
46秒/20任务全部执行。十一卡完成、11待实施，当前接B3，前置通过前未写B3。
新增32项/3类，可信来源/只读/保护/固定范围、同原近战/有限撤退、累计账/水中暂停、
原工作归还/租约失效/取消全清及status/Lua风险可见已离线验证。
focused失败为新增测试接口与跨来源预算断言问题，未放宽产品门；完整机制见D2-BODY-DEFENSE。
原生新访问器注入、实际受击/移动/爆炸仍待实机，无新日志，不安装或启动游戏/模型。

2026-10-17本轮B2→D1逐卡完成。D1最终1314项/154 suites（核心336/39、根136/20、
客户端842/95），失败/错误/跳过全0；build/d1-final2-20261017.log第61–62行，
47秒/20任务全部执行。十卡完成、12待实施，下一D2，本轮不启第三卡。
完整参考链和边界见D1-DYNAMIC-MELEE：稳定实体/固定接近范围、原批准模式/锚点、
原生范围投影/真实形状及A*、同刻合法出手、累计搜索/暂停账、原scheduler与Lua真终态。
新增21项/3类；before复现缺输入契约，focused失败为新异步测试等待/显示断言问题，
修正后两轮强制全量通过。世界/身体/原生伤害回调与模型/网络受控；
未安装或启动游戏/服务器/模型，没有新实机日志，D2威胁/自卫/撤退留后续。

2026-10-17本轮B2→D1，B2强制全量1293项/151 suites全过，失败/错误/跳过全0；
build/b2-final-20261017.log第61–62行，63秒/20任务执行，九卡完成、13待实施，当前接D1。
新增19项覆盖同页逐刻/支撑/已有满足、外部改动/真实扣料/异常部分效果、取消/身份/保护/
库存/区域锁/未知/查询及时间帽、Lua预算预扣与受理/迟到、实际dispatcher权限与控制。
完整链、失败阶段和边界见B2-NEARBY-CONSTRUCTION及STATUS；原版回调/世界受控，
未安装或启动游戏/服务器/模型，原日志目录不可用，没有新实机证据。

2026-10-17 N3→B1两卡已依次验收，N3提交推送b424100。
B1最终1274项/149 suites，核心336/根132/客户端806，失败/错误/跳过全0；
build/b1-final2-20261017.log第61–62行，41秒、20任务执行。
旧码先复现缺建筑查询契约1失败；两轮专项及首轮全量通过后，包名改为building以避免
源码被现有build忽略规则排除，再强制全量并核JAR/源码/测试跟踪。
目标/状态/旋转/组件、分页/有限分配、保护/支撑/未知/冲突、预算及实际runner/dispatcher
已离线覆盖；真实世界和原版预检上下文结果受控，建筑实际放置未运行。
机制和范围见B1-BUILD-TARGETS，候选以STATUS顶部为准；八卡完成、14卡待实施，下一B2。
本轮不启第三卡，不安装或启动游戏/模型，宿主/连接器/参考/实例保持。

2026-10-17本轮N3→B1。N3强制全量1251项/146 suites全部通过，
日志build/n3-final2-20261017.log第61–62行，53秒、20任务执行，失败/错误/跳过全0。
旧码静止地形变化先复现1失败；生产失败账/Lua/runner、分页与覆盖、工作保护及
有界实际路标/返程拒绝已离线验证，细则见N3-SPATIAL-FACTS-2026-10-17与STATUS。
原生注入/世界事件/实际运动仍待实机，本轮不安装或启动游戏/模型，
环境已有MC进程保持不动。七卡完成、15卡待实施，前置收尾后接B1。

2026-10-16本轮只推进F2，已完成1234项/142 suites强制全量，失败/错误/跳过全0；
40秒、20任务全部执行，build/f2-final-20261016.log第61–62行，候选以STATUS顶部为准。
新增30项：数量守恒、部分混堆未知、源格/清障/历史分离、本人/他人及换身体、
卸载/损毁/丢失、帽/过期/组件隐私、动作取消刷新、实际Lua/runner背包复核、
失败账及过大回执摘要；旧码已复现缺来源可用性声明1失败。
事件前后样本和世界边界受控；实际映射描述符/打包核对不等于原生Mixin注入或世界事件已验。
只覆盖同步破坏区间的实际接受生成，不推断异步生成或任意战斗产物来源，
完整边界见F2-DROP-ACCOUNTING-2026-10-16。当前六卡完成、16卡待实施，下一N3，本轮止于F2。
宿主/连接器/参考/玩家实例不改，未安装或启动游戏/模型；下方旧进度按历史阅读。

2026-10-16 N2b承接N2a完成1204项、140 suites强制全量，失败/错误/跳过全0；
最终日志第61行/候选以STATUS顶部为准，40秒、20任务全部执行。
新增16项覆盖生产A*/shape/clip、共用放置预检、原目标占位/脚高、
路径两分支的点击前提、参数/权限及真实runner/Lua受理/终态/取消迟到。
旧实现先复现查询缺原目标站位字段1失败；focused1及补齐后focused2通过，
受控世界/原生放置回调不冒充BlockPlaceContext/BlockItem.place或Mixin注入实机验收。
本轮两卡收尾，当前五卡完成、17卡待实施，下一组F2→N3；细则见N2B-PLACEMENT-WORK。
原预算和权限不变，宿主/连接器/参考/玩家实例不改，不安装或启动游戏/模型。
下方“当前接N2b/18卡”为历史检查点。

2026-10-16 N2a完成1188项、138 suites强制全量，失败/错误/跳过全0；
最终日志/XML/候选见STATUS顶部，细则见N2A-STANDING-GEOMETRY。
旧实现半砖/雪层2项失败已复现，原版碰撞/clip与生产A*/活体复核参与，
补隧道有序清理/零编辑返程及低顶保护；受控身体不冒充原生travel/playerTouch。
当前四卡完成、18卡待实施，本轮接N2b，仍逐卡验收提交，不安装或启动游戏/模型。

2026-10-16 F1已承接C1完成，最终1171项、136 suites、失败/错误/跳过全0；
强制全量46秒、20任务执行，日志/XML/候选见STATUS顶部，细则见F1-BODY-FACTS。
共用差量/真实损坏区别、有界历史/缺口/身份、生产dispatcher取消及异常/线路超限、
重复差量不补失败次数均已离线验证；同时加固控制清理异常回滚。
当前N1/C1/F1完成，19卡待实施，下一组优先N2a→N2b，不继续第三张。
原生损坏/身体持续行为待实机，不安装或启动游戏/模型；下方“当前接F1”为历史检查点。

2026-10-16 C1见C1-BODY-CONTROL-2026-10-16.md与STATUS顶部。
BodyControl/生产scheduler/dispatcher/挖掘任务验证同步和跨刻仲裁、暂停及原总时限、
取消不恢复、许可/身体失效、裂纹/主手重验和移除后取消保留效果。
首轮强制构建发现包装future破坏取消收尾，保持原future身份后最终1153项强制全量通过。
允许一轮至多两张适合衔接的卡，先逐卡验收再接下一张；当前接F1，不启动游戏/模型或安装。

2026-10-16 N1共用导航费用/诊断：
旧实现先复现构造阶段未计时读世界，见build/n1-before-20261016.log。
ApproachEstimate验证深浅/开放入口、已清格来源折价、同源步型及阶梯逐步回放、
预算/存储/种子帽、未知与无界、只行走和已满足零动作。
真实BlockAim shape/clip验证多工作站位的移动加清障费用；
PathTask生产复核验证精确步骤/支撑格、剩余挖掘帽与取消回执中的实际部分效果。
首轮全量/deep-recovery1/focused3的深层撞帽、浮点精度及放宽步型问题均保留失败记录，
未扩大预算或移除深层断言；focused4通过后最终强制build以STATUS顶部XML/候选为准。
共用导航层验证不冒充持续行为触发、ServerLevel实际动作或真实模型/自然地形速度通过。
下一卡C1，宿主/连接器/参考/玩家实例不改，不运行或安装游戏/模型。

2026-10-16第五次日志后的共用导航卡：
修复前封闭竖井/下坡回放两项失败，见build/edited-terrain-before-20261016.log。
新增回归检查累计移除/垫块、同位置不同预算/地形、重访不重复消费、
不可变前缀/固定父链及32768已创建节点存储帽；撞帽不等于无路。
真实shape/clip验证先前开口、垫块遮挡和未知，生产PathTask搜索/活体重建/复核
验证下坡与保留支撑的阶梯零新挖放返回；不是实际ServerLevel/玩家运动。
core1旧DigCostSafety夹具只有脚格inBounds，促使叠层对未改格完整委托base，保留原契约；
focused2新返程夹具错误假设任意折返开路都可返、旧阶梯夹具漏头顶遮挡，分别改为
单向保留支撑阶梯及完整清单/新增漏净空拒绝断言，不弱化生产门或旧安全断言。
收尾回归还在旧检查顺序复现切片耗尽先返回、绕过累计时间帽；
见build/edited-terrain-time-before-20261016.log。现每16节点先检查原累计时限，
再决定切片返回，不增加CPU预算。
专项及最终强制全量/XML/候选见STATUS；不运行游戏/模型或安装实例。
整体机制按卡推进，埋深费用/任意脚高/掉落账/身体控制权及战斗不在本批验收内。

2026-10-16整体机制与任务失败账卡：
WorkFailureMemory验证结构化参数/原因、有限存储、完整身体事实及原子真实效果失效；
查询/空动作/组合累计进度、首次/不完整状态不补次数。实际AgentRunner验证直接/Lua/
workflow/gather、长期检查点、受理/进度/重复终态、取消晚到及同名新身体隔离。
修复前已复现跨程序第六次仍派发；首轮测试一处等待写法阻塞后已终止测试worker并修正，
专项stage1的smelt夹具误用默认只读query已改显式load/take，生产只读判据未放宽。
最终强制全量/XML/候选见STATUS，整体关联机制见AGENT-MECHANISM-AUDIT-2026-10-16。
世界/网络/模型受控，不运行实际游戏或模型、不安装实例，战斗/生存和真实速度仍待验。

2026-10-16第四次测试后的程序契约卡：
BoundedProgram实际Lua验证错误类别/数字行号、语法零动作、禁止库、嵌套函数、
Unicode临界输出、负原因有界去重、程序内帮助可见与已完成效果不重放。
同程序稳定失败计数覆盖变动耗时/参数键顺序/空动作；完整36槽事实允许真实进展，
时钟/槽位/耐久不补次数。既有新鲜状态门/权限/取消/原预算回归保持。
AwarenessToolTest使用生产status/inventory/find_resource/inspect输出经过真实Lua桥，
仅世界Access受控；实际runner验证帮助可见和字段错误反馈。工具帮助的returns为说明，
不是强制输出Schema。stage1诊断/夹具及stage2漏实体身份用法均修正，未弱化旧回归。
最终强制全量1095项与候选见STATUS；不安装或运行游戏/模型，不据此声称实际速度已提升。

2026-10-16第三次测试后的上下文效率卡：
ToolCatalogTest用实际provider序列化比较同一24项工具的最小请求，
27514→16272 bytes；所有Schema/顺序一致，帮助与原注册表说明一致且输入有界。
实际StreamingTurnReader/AgentRunner/Lua验证帮助只读、参数错误可信路径、
完整查询数据供程序使用、风险标记/动作/失败可见，以及原子预算/输出帽/取消晚到。
mc.try_call验证只有明确局部失败可读，修改前强制完整新鲜details状态，
相同失败五次不因状态查询刷新，权限/前提/安全/范围/未知仍停止。
Conversation/实际AgentLoop验证完整输入压力、短历史不误整理、autoCompact关闭、
摘要用冻结出站投影，整理后重新观测、原task/目标基线/阶段/步帽及取消隔离不变。
stage1有四项常驻关键语义回归失败，修短说明保留约束；stage2新增长期目标夹具缺坐标，
导致目标未登记，补完整身体夹具后stage3核心/客户端全过。失败阶段不记通过。
最终强制build/XML/候选见STATUS；未运行真实模型或游戏，不据体积下降宣称真实提速。

2026-10-16第二次日志修复：实际回环HTTP/SSE及订阅器验证响应体看门狗、
取消/晚到隔离、typed failure、零自动重试；实际AgentLoop验证早派发效果不重放，
第二个半截工具不执行。WaterRecovery受控空间验证封顶连通路线、未知、搜索/时间帽、
只读/传送/换维度，CompanionPhysics验证原版液体跳跃接线顺序。
真实BlockState验证共用流体/重力保护及工作中危险清裂纹；
实际FindResource/合并分页/gather/runner/workflow验证安全前置、tick等待、入水停止、
具体字段及原成果/预算/取消；PathTask计划复核验证有序支撑与重复列。
没有ServerLevel游泳、原版playerTouch延迟或真实模型速度验收；
最终强制build/XML/候选见STATUS顶部，不安装或启动游戏/模型。

2026-10-16放置接触卡：真实BlockAim shape/clip覆盖空气/流体无锚、
错误面/遮挡、部分形状、未知/距离、雪/半砖轮廓及侧接。
BlockPlacement/PathTask共享入口验证六面有界选择、一次动作及原目标接触证据，
实际runner/workflow/Lua验证负查询停止与候选分支、具体Schema拒绝及零派发，
gather验证来源样本与清障隔离、原数量目标和等待预算不变。
未创建ServerLevel执行真实BlockPlaceContext/BlockItem.place或边缘桥接身体动作；
几何回归不宣称雪替换/半砖合并/墙火把的原生规则已验。
最终强制全量/XML及候选以STATUS为准，未安装或启动游戏/模型。

2026-10-15工作恢复/回执卡：第六次日志已取得铁镐和钻石镐成果。
BoundedGather/实际runner验证旧探索球外显式局部采集、固定本段范围、
原耗尽预算/地下路标/开路账保留、重复调用不补探索步数和默认模式仍拒绝。
BlockToolOperations使用实际BlockMining预检失败，验证工作建议参数能过生产解析、
零动作、较小清理帽不放大，其他失败及部分效果不误解释为可恢复。
BoundedProgram/实际runner与Lua验证显示编号去重、不同调用与新文本完整保留、
Lua读取/分支及结构化返回原样，失败后不派发；原权限/取消/嵌套预算回归继续执行。
专项及强制全量1008项、XML与候选见STATUS顶部；世界/网络/模型仍受控，
未运行真实ServerLevel动作、玩家travel/拾取事件或真实模型，不自动安装。

2026-10-15工作一致性卡：第五次日志已有铁镐合成/装备正证，本轮离线不冒充复测。
HandSnapshot/BlockMining回归验证空主手仅凭本身体、动作后真实拾取证据续挖，
换槽/工具/组件、旧证据和错误采收资格仍拒绝。
BlockAim覆盖高处目标的工作到达与出手几何一致，不扩大交互距离。
Awareness/BoundedGather/BoundedSearch覆盖工具前提零开路、其他来源及后页合格候选、
同UUID新站位/落入触碰可重评且六站位帽不重置，取消和无成果帽保留。
BlockPlacement查询/执行共用预检，验证75格/6建议、零消费、精确目标与过时建议重验；
只读方向与原版Marker.lookAt/Direction.orderedByNearest对照124个方向，包括轴向/相等分量。
Marker未加入世界，不执行身体travel；放置预检的世界状态/实体占位仍为受控边界。
原权限闸、实际runner与气泡验证严格布尔query只读，未运行ServerLevel放置/原版playerTouch。
最终强制build、XML和候选见STATUS；不启动游戏/模型或自动安装。

2026-10-15漏拾取/放置卡：WorkPositionsTest使用真实GroundItems触碰盒判交，
BoundedGatherTest覆盖成功未接触、移动后实体换位、每UUID六站位帽、范围排除、
边界原地触碰、已知掉落有限等待、无成果与取消/超时迟到隔离。
BlockAimTest执行原版shape/clip验证局部遮挡另取可见点、整墙和未知读取拒绝。
BlockPlacementTest执行实际预检函数/原版形状，验证原地替换/支撑/自身和其他实体
分别给原因，预检拒绝不执行或消费；环境与replaceability/survival仍受控，
没有运行真实ServerLevel放置上下文、玩家travel或原版并堆/拾取事件。
墙上/地面两用物品保留原版形态选择，新增预检不猜其默认形态；原生墙面放置仍待实测。
专项、最终强制全量和JAR哈希见STATUS；不据此宣称雪层导航或真实模型策略已验。

2026-10-15气泡文字修复：维护者截图背景已出现但文字全空，确认submitText参数错位，
真实顺序为DisplayMode后lightCoords/color/backgroundColor/outlineColor。
文字提交回归改用真实SubmitNodeStorage/TextSubmit字段；旧生产代码先复现2项失败，
修复后检查各状态/浅深色/淡出下的实际内容、位置、亮度和颜色，不再由代理假定颜色槽位。
强制全量961项及新候选见STATUS；这不是GPU画面通过，待维护者用新包复测文字恢复。

2026-10-15头顶气泡：TaskBubbleStateTest与实际AgentRunnerTaskBubbleTest验证活动seq、
受理不清除、实际终态/超时/取消及迟到隔离、问题优先、数量净增/材料scope、
终态期限与隐藏期间过期、配置保存失败及身份替换。原GoalSurvey测试补长期检查点、
地下/返程状态断言，ServerToolDispatcherTest补owner范围UUID/无身体清除。
TaskBubbleLayoutTest核对长文本/Unicode边界、计数行及距离准入；CompanionTaskBubbleTest
实际调用生产提交/顶点生成/原版矩阵和管线，验证相机相对跟随、PoseStack恢复、
NORMAL文字、光照属性和有深度测试；队列/字体宽度为受控边界，不运行GPU或游戏画面。
最终强制构建、XML及JAR见STATUS；不启动游戏/真实模型、不安装实例或修改宿主/连接器。

2026-10-15导航/地下/生命周期卡：NavigationWorkTest执行原版BlockAim/clip及实际A*，
检查绕墙工作终点、有序清理、未知/占位拒绝；石块夹具逐格应用阶梯计划后零挖放反搜返程。
这不是ServerLevel/玩家travel/PathTask完整实机执行。PathSpecTest验证严格类型与范围。
BoundedSurvey/实际runner验证完整分页、补给停止、真实落点/部分效果、预留返程、后续工作位移、
无路标拒绝、双阶段许可与真终态、取消迟到；Lua验证每页占同一程序原子预算。
AgentLoopLongGoalTest验证中间材料与实际最终数量分离、阶段/模型预算累计、固定生命周期、
重复状态/循环/时钟/换槽/耐久、受阻/模型失败、检查点取消及独立事实账；
实际runner另验单task_begin/task_end和阶段切换不重建探索账。
普通短任务守卫保留。会话外持久化、建筑/交付条件、天然洞穴/照明/水下及真实模型策略
不在已通过范围；最终强制全量/XML/候选证据见STATUS，未自动安装或启动游戏/模型。

2026-10-15参考职责对齐修复：WorksiteProtectionTest执行真实注册表的普通工位判定，
并以实际A*检查目标保留/邻近站位和缓存清单复核；未创建ServerLevel或运行完整PathTask。
BoundedGather/实际runner验证工作/拾取恢复前的新鲜状态、途中入包即停、越界不续发、
同站位有界批次、非矿石及长短任务作用域、每格真终态、缺工具/无进展和取消。
RecipeMaterialsTest使用实际三类原版烹饪配方assemble及smelt共同准入判据，验证原料、
工位、产量和时长与普通转换分开、非铁/食物复用、多件/动态子类拒绝、截断显式。
没有启动真实世界、服务器、模型或安装实例；提示精简不等于真实模型阶段选择已验。
最终强制全量命令、实际XML计数和新候选哈希统一见STATUS顶部。

2026-10-15四项优化卡：BoundedSearch/AgentRunnerSearch/Program测试验证完整五页、
末页候选、只读、逐页原子预算、取消及迟到不续跑；三次独立完整空搜仍停止。
ExplorationRoute/Points验证半径与步距联动、严格完整已知分页才记覆盖、过期/挖清失效；
覆盖估计只是选点评分，世界与落脚谓词仍用受控边界。
RecipeMaterialsTest执行实际Ingredient/ItemStack，验证异种材料及转换出处；
GatherMaterialTest验证非候选物品不计数、超8来源分批查完、截断停止，
AgentRunnerProgramTest验证替代原木材料成功仍保留铁镐最终背包守卫。
CraftToolArgumentsTest覆盖material_index仅查询及分组数值边界。
最终强制全量命令、实际XML统计/JAR哈希见STATUS；不启动游戏/模型或自动安装。

2026-10-15维护者探索日志优先卡：日志判读见real-game-log-review-2026-10-15.md。
AgentRunnerGatherTest验证explore→材料gather→重复gather共用预算/访问记录及新任务隔离；
ExplorationRouteTest验证到达记忆、水平去重、方向持续/换向、取消和长步爬升。
ExplorationPointsTest验证16格优先/8格回退及200探针帽，仍为可站谓词夹具。
SearchPagesTest及实际loop/Lua/runner测试验证五页续查、重复/跳页/换查询拒绝豁免；
已有只读、取消/迟到、终态、嵌套原子预算回归保持。完整构建/XML/哈希见STATUS，
不启动真实游戏/模型、不自动安装，不把离线发现写成自然世界发现率已验。

晚间卡聚焦采集授权续跑与工作事实反馈，不改长期目标调度或权限策略。
AgentRunnerGatherTest/ProgramTest通过实际runner/loop与可控回执验证完整清单、真人/
服务端双阶段批准、原参数单次续发、变化方案交回、拒绝/取消/迟到/超时/总时限，
以及嵌套原子预算不可绕过。AwarenessToolTest验证普通feedback可见既有工作事实，
不增加世界读取；ContextBudgetTest验证任务内摘要与普通模型等待状态。
最终执行数量、日志和候选以STATUS最新节为准；没有启动游戏/真实模型或安装实例。

通用换区观察与采集探索：独立explore与gather共用ExplorationRoute，调用方决定
完成条件，不新增木材/配方专用流程。BoundedExplorationTest检查本地分页、真实位移、
不可达换方向、PARTIAL重观测、范围/维度/步数、取消/超时及禁用探索。
AgentRunnerProgramTest执行实际Lua/runner/终态接线，以多次空实体观测后换区发现
验证非采集复用；AgentRunnerGatherTest验证同模型轮推进、任务预算固定及迟到隔离。
AwarenessToolTest验证r=16五页17077格及旧中心拒绝；ExplorationPointsTest用受控
可站谓词检查下坡样本、最多8落脚样本/200探针，不运行原版碰撞或真实世界导航。
PathClearanceTest执行生产walk_only搜索工厂和实际A*，证明唯一挖路通道被拒绝；
只读和嵌套原子预算继续保留。最终强制全量日志/XML/产物见STATUS最新节。
不运行游戏或真实模型、不替换实例；真实换区发现率、路线和速度仍待维护者补测。
下方各卡为历史检查点，不能把离线受控发现写成真实自主探索已验。

资源/清理/掉落事实卡：find_resource先露出预筛再分配工作/输出名额，
整查询共享2048射线、24目标；MiningWork连接站位/移动终态/ClearingMining，
最多3个实际遮挡，原权限/安全/区域锁/主手/取消及采集预算保留。
BlockAimTest执行原版shape/clip；ClearingMiningTest执行实际组合与BlockMining逻辑，
世界回调/身体与网络仍受控；AgentRunnerGatherTest检查实际runner无需新增模型轮次
及批准回执传递。DropObservationsTest检查当前/历史区分及合并/消失未知，
没有运行真实拾取/合并事件或PathTask完整世界遍历。最终证据以STATUS为准。

下坡净空修复：搜索/重建/活体清单/移动前复核检查身体迈出边沿时经过的上方格，
挖掘顺序改为自上而下；途中重建剩余授权清单携带前一落脚高度。
DescendingClearanceTest验证实际A*的三格费用/帽及不可挖拒绝，
PathClearanceTest执行生产活体清单函数，BlockAimTest使用原版shape/clip验证遮挡次序。
未运行真实PathTask世界遍历、玩家物理或模型；完整证据与候选包以STATUS为准。

自主执行模式：配置默认关闭，由主人面板保存，在task_begin建立服务端有效模式。
只读优先，路径计划/逐格执行和攻击批准共用ActionPermissions决策；
状态/扫描反馈反映有效模式，自主任务先观测确认，不兼容以固定原因终止。
模式变更取消旧任务/排队/问题，写盘失败保留原运行配置。
本卡用实际权限/dispatcher/runner和受控模型/网络/世界边界验证，
不启动游戏/模型、不安装或改变玩家配置。完整证据与实机待验项以STATUS为准。

工作站位/局部恢复优先卡：WorkPositions/移动终态/BlockMining共用BlockAim，
gather在最多6个站位间本地恢复并保留权限/终态/预算；目标ID变化停止，不挖替换物。
原版shape/clip在方块表夹具中运行；MoveToWorkTest检查实际终态处理函数，
AgentRunnerGatherTest执行真实runner/loop的受理等待和本地恢复接线。
不运行PathTask真实世界寻路、玩家物理/网络画面或模型；具体构建/XML/哈希见STATUS。
参考参数与计数粒度对照见性能/长任务文档第7节，自动遮挡清理仍待后续卡。

本轮通用组合执行引入上游org.luaj:luaj-jse:3.0.1，未引入参考项目维护的Lua分支。
依赖由Maven Central解析，版本已固定；新设备先准备该依赖缓存，再运行离线强制构建。
根mod的include同时嵌入LuaJ，许可在META-INF/LICENSE-luaj.txt，不能只更新agent-core而漏运行库。
BoundedProgram实际运行Lua解析/解释与预算钩子，AgentRunnerProgramTest运行实际runner及原终态派发，
游戏/模型/网络仍使用可控边界。详情见TOOLS §1.2、STATUS「通用组合执行层」。
不启动游戏或真实模型、不自动安装包，不把一次模型夹具推进当作实际提速基准。

维护者随后要求自然自动拾取及可复用任务能力。BodyPickup已经接身体每刻，
gather不再依赖collect；活动只读仍禁止拾取，原版延迟/归属/容量继续由playerTouch决定。
新增身体调度/权限、自动入包/他人抢走/满包阻塞及主手合并回归，最终证据以STATUS新节为准。
下方旧卡“不开自动触碰”不覆盖此项授权与实现；不因增加拾取而开启完整doTick。

随后已修最终目标与材料子目标冲突：gather默认scope=task沿用任务开始基线，
scope=subtask从本次第一份完整观测计数，不登记/替换最终目标；total仍核对总量。
专项覆盖不同材料共用采集、原目标不可降低、取消无基线、非法scope和实际runner结束守卫。
最终强制构建/XML/哈希以STATUS「材料子目标与最终目标分离」为准；
真实自主制作、性能和原生身体效果仍待维护者补测，不启动游戏/模型或安装实例。

最新完成连续物理移动、精确采集站位、底层支撑及上下文净节省修复，
同时移除走近拾取后的固定一秒等待。日志/参考机制判读见
`real-game-log-review-2026-10-14.md`，最终强制全量日志/XML/产物哈希见STATUS本轮收尾。
新移动回归为受控动力+原版Shapes碰撞，不运行原生玩家travel或网络动画；
上下文使用可控模型，不能宣称真实提速已验。仍不启动游戏/模型或自动安装。
下方10/13各段为此前检查点，不覆盖本轮进度；完整测试继续用§4.4的强制全量命令。

最新维护者初步反馈重力包正常，已核对实例哈希及无mcbot异常的进出世界日志；
日志不含连续下落轨迹，其他物理场景继续待验。随后转入效率/长任务评估，
P0流式参数按工具名称匹配的历史检查点为656项；随后日志确认早派发已生效，
批次停顿主要来自模型往返。本轮顺序完成实体掉落/触碰拾取、LT1目标与任务内整理、
有界LT2连续采集初版；最后审查修正第三次挖掘先拾取再判无进展，强制全量681项通过。
证据与候选包见STATUS「有界连续采集与最终交付」，机制及剩余范围见
`AGENT-PERFORMANCE-AND-LONG-TASKS.md`。探索/补给/持久化/跟随护卫仍未实现，
不启动游戏/模型或自动安装。

维护者其后自行安装e800942候选，17:40日志取得本地连续采集、显式拾取与净新增校验正证；
目标净新增64最终21，路径受阻而FAILED，不记为完整任务通过。
详细判读见 `real-game-log-review-2026-10-13.md`；实际摘要只看到触发提示，成功仍待验。
本轮恢复Git并归档此前本体改动/测试/文档，没有覆盖工作文件或改实例；
无代码修改时沿用已完成的681项强制全量，不为文档收尾重复运行。

维护者最新反馈为挖脚下方块后悬浮；被动重力/碰撞已接入每刻身体tick。
CompanionPhysics在已加载运动邻域复用原版travel(Vec3.ZERO)，先更新流体，
随后处理方块接触、实际位移的落地结算与区块位置；不调用完整doTick或playerTouch。
PathGrounding与PathTask让下落期间暂停路线/清裂纹，落地高度改变后按旧上限重规划，
有意提交的跨层节点不误算为被动下落。新增7项物理调度和5项落地状态回归；
物理回调使用替身，碰撞样本执行原版Shapes.collide，不运行真实travel/世界/玩家。
专项与全量日志见STATUS「被动重力与落地」；原生下落已有维护者初步反馈，
复杂落地/路径场景继续待验，最新包按STATUS当前节核对。

上一轮维护者要求优先修工作朝向与虚空工作，本体动作朝向/可见性实现和离线回归已完成。
新增BlockAim原版轮廓/clip回归及挖放停手/物品保全，共16项；
完整强制构建633项通过，具体执行与JAR哈希见STATUS「动作朝向与可见性」。
每拍目标与实际视线、交互距离和动作转向已经接线，未启动真实身体/世界、
模型或安装包，原生画面/路径补测追加在REAL-GAME-RETESTING。
本轮不是完整原版输入层重构，不以离线clip运行代替实体转头/动画或网络同步验收。

维护者已批准G面板HTML原型，R3原生重设计已实现并通过离线构建；
实际行为、配置默认及会话历史边界见 `PANEL.md`，本轮执行/缓存范围见
`../STATUS.md`「R3 G面板重设计」。新增面板体验清单并入集中补测筹备；
本轮不启动游戏、真实模型或安装包，不把GUI像素布局单测当作原生渲染已验。

开发端以离线测试为主：使用单元测试、可控模型/网络/时钟替身、
实际 runner/桥接线与临时回环 HTTP/SSE 验证，不调用真实模型。
暂不通过 `runClient` / `runServer` 启动真实游戏验收；本文保留的服务端工装与真机流程
供维护者安排时使用。C1–C6 已合入 main，PR #5 已关闭但未合并；
首轮外部反馈已核对，RG1攻击参数诊断与RG2服务端只读验证实现/离线完成，
T11实机入口为 `/mcbot verify_readonly`，真实结果仍待集中补测。
维护者选择相关开发完成后集中实机补测，筹备见 `REAL-GAME-RETESTING.md`；
旧顺序/反馈入口 `REAL-GAME-TESTING.md`、`../dist/game-testing.html` 和
`templates/GAME-TEST-FEEDBACK.md` 保留原revision。本轮开发端不启动游戏/模型或安装包。
构建及离线通过只证明对应实现范围；首轮基本烧制/动作/保存重进已有正证，
复杂边界、T11/T12/T14剩余项和连接器联合问答仍单独挂账，
不以未安排实测阻断当前实现与离线验证。
RG6身体生命采样与世界名称切换回归已准备，现场首次变化/连接器展示仍待证据，
交接见 `RG6-HEALTH-WORLD-OBSERVATIONS.md`；没有自动补血或完整生存战斗。
RG1–RG6开发与补测准备阶段已完成，卡片范围、未关账项和下一步统一见
`../STATUS.md`「RG阶段总览与下一步」。其后维护者批准先落实G面板重设计，
集中补测时固定包含RG6诊断及R3面板的包和源码检查点，并确认协作者连接器实际版本；
尚未取得新的游戏或联合验收结果。

浏览器测试手册与文字反馈模板由 `tools/build-game-testing-docs.mjs` 从
`docs/REAL-GAME-TESTING.md` 生成，仓根执行 `node tools/build-game-testing-docs.mjs`；
脚本只用 Node 标准库，不访问网络或游戏。修改源文档后同步生成两份输出；
调整测试编号/字段时同步修改版本和反馈兼容校验，避免混入另一批验收记录。
当前首轮手册已固定；RG5使用独立源 `REAL-GAME-TESTING-RG5.md`，
运行 `node tools/build-game-testing-docs.mjs --rg5` 只生成
`../dist/game-testing-rg5.html` 与 `templates/RG5-GAME-TEST-FEEDBACK.md`。
新revision为2026-10-13-rg5-clarifications-v1，旧默认输出及旧反馈导入能力保留；
新旧反馈页拒绝互相导入，不把旧结果自动转成新复测结果。

0.1.0 测试发行说明见 `releases/0.1.0.md`，发布附件与源码提交分开：
JAR/测试ZIP/校验清单通过GitHub Release交付，不提交源码仓。
每次发布固定标签目标提交、核对JAR内部版本与SHA256，再检查附件清单和远端下载哈希；
发布记录写入STATUS，真实验收债务保持独立。已有版本标签/资产不静默覆盖。

## 1. 工具链（版本钉死，勿凭记忆改动）

| 项 | 值 | 备注 |
|---|---|---|
| Minecraft | 1.21.11 | 混淆时代；loom **remap** 变体 |
| Loom | `net.fabricmc.fabric-loom-remap` 1.17.20 | 普通 loom 在此版本不可用 |
| Loader / Fabric API | 0.19.5 / 0.141.6+1.21.11 | |
| 映射 | `loom.officialMojangMappings()` | 注意：`Identifier` 不叫 ResourceLocation 等命名坑见 STATUS 防漂移表 |
| JDK | Temurin 21 | **仓内不写路径**；本机路径放用户级 `gradle.properties`（见 §1.1） |
| Gradle | 9.5.1 | wrapper 走**官方源**；到不了 services.gradle.org 的机器按 wrapper 注释临时改 `file:`（别提交） |
| 代理 | 仓内仍有历史回环代理默认值 | **本机网络相关**；新设备按 §1.1 在用户级配置覆盖，不假定该端口可用 |

`run/` 是开发运行目录（gitignore）：`eula.txt` 预置 true；`run/mcbot/` 放名册/配置/flag。

### 1.1 多设备：哪些东西**不许**进仓（09-10 定死）

这份仓要在多台设备上接着干，所以**任何机器相关的绝对路径都不许写进受版本控制的文件**。
历史上踩过两次：一次是另一位用户名下的 `.gradle/jdks`、一次是别的盘上的 Gradle zip，
每次都让另一台机器直接构建不起来（记录见 `STATUS.md` 环境迁移节）。

**换机器只需三步**：

1. **JDK**：在**用户级** `<GRADLE_USER_HOME>/gradle.properties`（默认 `~/.gradle/gradle.properties`，
   仓外）里写 `org.gradle.java.home=<你的 JDK 21 路径>`。**什么都不写也行**——只要
   `java -version` 是 21（MC 1.21.11 编不了 17）。
2. **Gradle 发行包**：不用管，wrapper 会自己去官方源下（缓存在 `~/.gradle/wrapper/dists`）。
   只有网络到不了 `services.gradle.org` 时，才手动下 zip 并**临时**把
   `gradle/wrapper/gradle-wrapper.properties` 的 `distributionUrl` 指过去——**改完别提交**。
   （Gradle 用 URL 的 MD5 当缓存目录名：换了 URL 就等于换了缓存，所以要重新下。）
3. **代理**：仓内仍保留历史 `systemProp.*Proxy*` 默认值，不能假定新设备也有同一代理。
   优先在用户级 `gradle.properties` 覆盖 `systemProp.http.proxyHost/proxyPort`、
   `systemProp.https.proxyHost/proxyPort`，端口按本机填写；直连时将两项proxyHost设为空值。
   用户级配置优先于项目配置。本机网络调整不提交；本轮交接不改变既有构建配置。

换设备接手的代码检查点、材料边界和下一张卡见 `HANDOFF.md`。
`STATUS.md` 同时承担开发日志，不另建与其竞争的进度事实源；
换机后的新测试要另记时间与结果，不能把本机历史缓存证据写成新设备已经运行。

文档里引用本仓一律用**相对路径**（`docs/...`、仓根写成 `<仓库根>`）；引用参考项目/桌面 AI
一律写成"开发环境中的只读副本（路径不入仓）"。写文档时检查：`git ls-files` 里不该出现
盘符路径（`gradle/wrapper` 的注释除外，那是反例说明）。

## 2. 常用命令

```bash
./gradlew build                 # 全量：agent-core 测试 + mod 编译 + remapJar
./gradlew :agent-core:test      # 只跑大脑层单测（报告 agent-core/build/test-results）
./gradlew clientTest           # 客户端实际 EditBox 与 GUI 像素布局测试，不启动游戏
./gradlew runServer             # 起开发服（25565，配合 autotest.flag 做无头验收）
./gradlew runClient             # 起开发客户端（需 GUI 环境）
```

Windows 控制台输出是 **GBK**：管道里用 `iconv -f GBK -t UTF-8` 转，否则日志乱码误判。

## 3. 无头验收 harness（标准姿势）

1. `touch run/mcbot/autotest.flag` → `./gradlew runServer` → 约 3 秒后服务器线程自动跑：
   - `m1`：三条命令（ping/summon steve/list）；
   - `m3`：五场景（白名单拒/owner 拒/直调冒烟/临时 owner 正向全链路/速率连打 80）；
   - `m4`：真挖链路（发镐+圆石+箱子 → place → break(看真实耗时) → move_to → transfer 存箱），
     前置把同伴从坏 `.dat` 落点传回世界出生点，并扫 14 格水平净带迁址（m4 仍吃地形运气，
     悬空出生点/残留箱体会让 `expanded=1` 成为"正确的 NO_PATH"）；脏世界删 `run/world` 重建。
   - `m4b`：真取消（busy 拒收 / cancel 命中 / 空槽 false / CANCELLED 回执）+ wait 走完。
   - `m8`：四场景（A 需确认流 / B 授权后真挖到达 / C 箱子神圣集未动 / D 基岩笼 NO_PATH）。
     票的事已由产品层 `CompanionChunkPads` 接管（同伴在哪 5×5 垫在哪，见 R1-S3b/STATUS 漂移表）；
     harness 不再自己持票，`[m8env]` warn 出现=产品票断供，先查 CompanionChunkPads/McbotMod 挂点。
     **A/B/C 的地形自 09-10 起是"完全密闭的石砌短隧道"**（四壁/顶/底/东端全石、只留西端门洞，
     在基准点现铺）：早先"露天两排侧墙"版本会吃地形运气，本机实测 A* 从东端外侧绕进走廊，
     于是 A 报 `需确认=false`、整道验收门变成抽奖。**密闭版与出生点无关**，故现在不必先找净带；
     坑只剩一条：**别把东端塞子一起挖空**（`dx<=6` 写成 `dx<=7` 就复现漏法）。
  判读基准：`A 需确认=true 且清单≥1` / `B 工装明确批准具体清单=true，随后到达=true 且墙位被打通=true` / `C 箱子分毫未动=true` /
     `D 干净失败=true`。`[m8]` 开头会 `PathTask.clearPlanCache()`：m4 与 m8 用同一个
     `基准点.east(6)`，不清的话 A 会命中 m4 那条缓存，判读就不再是"A 搜、B 复用"。
     m4 的 `move_to 完成: false NEED_CONFIRM 放N格` 在凹凸地形下是正常语义（搭路=改世界需授权）。
   - `m9`：同伴持票验收（接 m8d 后自动跑）。判读基准：**A1 新家票=true；A2 远环=false
     （排除碰巧全域加载）；A3 再跳后旧家自清=true 且新家继续=true**——三条全中即垫子
     跟人/非全域/过期自清三个性质同时成立（无任何显式释放代码）。
     ⚠️ **A3 现在是红的，且原因未定（09-10 记录，别当成自己改坏了）**：
     `A3 旧家断续后自清=false` 在 09-10 16:53 那次复跑（**早于**当日任何 R2-S4 改动）就已出现，
     A1/A2 照旧通过，说明"垫子跟人 + 非全域"两条没坏、**只有"过期自清"这一条读数不对**。
     已排除一个假设：把 `run/server.properties` 的 `view-distance` 从默认 10 调到 4
     （64 < A3 的 96 格跳距）后 A3 **仍是 false**，所以不是"被自身视野掩盖"（实验完已改回默认 10）。
     待查方向（留给专项卡）：假玩家是否在出生点持有不自清的票（`ChunkTicketType` 的
     玩家票与 `POST_TELEPORT` 语义要 javap 实测）、或 4 秒观察窗不够卸块。
     **在查清之前，别把 `[m9] A3` 当作回归信号，也别据此宣布 R1-S3b 仍然成立。**
2. 判读基准都在日志行前缀里（`[m3]`/`[m4]`/`[m4b]`/`AUTOTEST`），STATUS 各节有逐条"期望值"。
   可同时建立 `run/mcbot/autotest-stop.flag`，验收链结束后通过 server.halt(false) 正常停服，
   不用控制台 stdin。`[f0-world]` 额外验当前存档名册/dispatcher/receiver 装配，
   以及遣散取消任务、再召唤、停服槽清空；不能替代客户端同进程换世界实测。
3. ⚠️ **不要用控制台 stdin 做验收**：经 gradle 管道喂命令连原版 `list` 都报
   "unexpected error" 且吞堆栈——harness 缺陷，与代码无关。一切自动化验收走 SelfTest
   （它直接 `dispatcher.execute`）。
4. 收服：`gradlew --stop` 放 daemon；若 `run/world/session.lock` 卡下次启动，
   是孤儿 java——`powershell -File tools/list-java.ps1` 找 `MC-RUN` PID 再 `taskkill //PID x //F`。

### 3.1 身体位置与非空背包恢复（独立双进程）

入口仍由 SelfTest 检查，不经过模型或控制台 stdin。**仅用于独立开发存档**：
先在 `run/server.properties` 设置未使用的 `level-name=mcbot-persistence-<测试名>`、
回环 `server-ip=127.0.0.1` 与空闲端口。普通世界名会被工装拒绝。
不要与 `autotest.flag` 同时使用，后者会清背包、改地形，污染恢复样本。

1. 创建 `run/mcbot/autotest-persistence-seed.flag`，运行 `./gradlew runServer`。
   工装只允许在无 seed 标记、无同名样本的世界写入：主世界 `(37.5,90,-42.5)`、
   下界 `(53.5,90,29.5)`，各铺安全平台，设置确定朝向与六个非空槽位
   （圆石 23、耐久损耗 17 的铁镐、原木 11、钻石 3、金胸甲、副手火把 7），选中槽 4。
   `[f0-persist] SEED PASS` 后自动 `halt(false)`，等正常保存与 Gradle 进程结束。
2. 保留 seed 日志；**测试旧包失败路径前先复制整个开发世界**，因为旧包重进后的空身体
   会在下一次正常保存时覆盖原 `.dat`。复制须在停服后完成，不能复制正在写入的世界。
   seed 标记在该世界的 `mcbot/persistence-fixture.txt`，不在实例级名册旁。
3. 同一开发世界创建 `run/mcbot/autotest-persistence-verify.flag`，再起一个新进程。
   工装先读取实际 UUID `.dat`，核对存档维度/坐标/朝向/背包；
   再独立核对当前在线身体的身份、相同状态及全部空/非空槽位。
   两名同伴都通过后，才测试遣散再召唤：相同 UUID、按显式召唤语义到主世界出生点、
   保留旧背包。之后恢复样本姿态并正常停服，允许再次创建 verify flag 做第三进程复核。
4. 判读：每名同伴 `result` 的 identity/disk/body/position/rotation/inventory 全 true；
   `resummon` 的 sameUuid/freshSpawn/inventory/matches 全 true；最后 `VERIFY PASS`。
   **Gradle BUILD SUCCESSFUL 不等于专项通过**，必须检查上述日志，无 `FAILED`/`VERIFY FAIL`。
   两种 phase flag 同时存在会拒绝执行；phase flag 都自删，且本工装无需 autotest-stop.flag。

该验收不替代单人客户端保存重进、同进程换世界，也不覆盖坐骑或在途末影珍珠恢复。
没有 phase flag 时，不生成 seed 标记、不修改身体或地形。

### 3.2 背包明细与主手切换（独立工装）

仅使用未用过的 `level-name=mcbot-inventory-<测试名>` 开发世界、
回环 `server-ip=127.0.0.1` 与空闲端口。创建 `run/mcbot/autotest-inventory.flag` 后运行
`./gradlew runServer`，SelfTest 自动进入专项，flag 自删，结束后 `halt(false)` 正常保存停服。
工装拒绝普通世界、已有同名 fixture 及同时存在的普通/身体恢复测试 flag。
此专项会写测试物品，不能在玩家世界执行，也不需要客户端、模型或控制台 stdin。
超平坦开发世界需同时提供包含 layers/biome 的 generator-settings，不能只改 level-type。

判读 `[inventory-test]` 各场景均为 true 且最终 PASS，无 FAILED。
使用真实 CompanionPlayer/Inventory/ItemStack 验证空背包和 43 槽映射、逐槽反馈、
只读性与超长自定义名称下的回执尺寸、快捷栏选择、完整堆栈往返交换、
满背包/空主手、非法参数/空槽拒绝、忙时不可切换但可读取、取消后可切换。
每次交换对照全部槽位的计数与 components，不以“主手名字相同”替代保全检查。
普通 `[m4*]` 回归需另用新开发世界与 `autotest.flag` / `autotest-stop.flag`，
不能混跑污染专项；既有 `[m9] A3` 红项仍按 §3 历史边界判读。

客户端离线接线与参数回归可单独运行：

```bash
./gradlew clientTest --tests com.neko.mcbot.agent.AgentRunnerInventoryTest --tests com.neko.mcbot.server.tools.EquipToolArgumentsTest
```

离线接线使用可控模型和游戏回执替身，验证 inventory → equip → 最终汇报的调用配对、
明细进入下一轮模型历史、BUSY 不被自动重发及实际工具 Schema。
隔离服务端专项不验证玩家客户端/多人可见装备更新、模型理解或连接器联合验收。

### 3.3 合成（独立工装）

只允许未使用的 `level-name=mcbot-craft-<测试名>` 开发世界，回环监听与空闲端口。
先将 `tools/fixtures/crafting-pack/` 整个复制到该世界的 `datapacks/crafting-pack/`，
再创建 `run/mcbot/autotest-craft.flag` 并运行 `./gradlew runServer`。
fixture 数据包不在 mod 资源内、不应安装到玩家世界；它提供不同于物品 ID 的配方 ID、
重叠 planks 标签/精确 oak_planks 材料及 7 个金粒的产量，用实际加载配方验证，
不依赖在代码里替换 RecipeManager。min/max_format 94.1 来自当前 MC JAR 的 version.json。
flag 自删，专项结束 halt(false) 正常保存停服，不需要客户端、模型或控制台 stdin。
工装拒绝旧 fixture 名册与普通/背包/恢复测试 flag 并存；超平坦仍须包含 layers/biome。

判读 `[craft-test]` 每项 true，最终 PASS，且无 FAILED/ERROR。
覆盖只读查询与数量取整、原木/木棍/石镐、制作并放置工作台的连续流程、
工作台缺失/超距、分散材料/标签、数据包重叠候选与不可重复消费、可用替代配方、
蛋糕空桶返还、满背包/组件合并/返还物溢出、后续批次失败整批不改背包、
装备/选中槽保全、非法参数/错配及非合成配方拒绝、忙时执行拒绝/查询可读、取消后可合成。
普通动作回归须另用新 `mcbot-action-*` 世界，不装 fixture 数据包。

```bash
./gradlew clientTest --tests com.neko.mcbot.server.tools.CraftToolArgumentsTest --tests com.neko.mcbot.agent.AgentRunnerCraftTest
```

离线测试运行实际 runner/loop，以可控模型和网络回执替身检查 Schema、query → craft →
inventory 的参数/回执配对、材料信息可见及缺料不自动重发。真实服务端专项直接运行实际工具，
不替代玩家模型驱动、GUI/统计/成就事件、真实多人或连接器联合验收。

### 3.4 熔炉/高炉/烟熏炉的离线回归

```bash
./gradlew clientTest --offline --tests com.neko.mcbot.server.tools.SmeltToolOperationsTest --tests com.neko.mcbot.server.tools.SmeltToolArgumentsTest --tests com.neko.mcbot.agent.AgentRunnerSmeltTest
```

3 项参数测试覆盖 query/load/take、槽与数量边界、错模式字段拒绝；
5 项实际 runner 测试覆盖模型 Schema、load → wait → query → take → inventory
的调用/回执配对、NOT_READY/BUSY 不自动重发、等待/装料回执未到时取消与迟到结果隔离。
20 项操作测试只初始化 MC 注册表，不创建 MinecraftServer/世界/假玩家或运行 tick；
执行生产工具的同一套参数、守卫、load/take 预检/提交及反馈逻辑。
使用真实 ItemStack/SimpleContainer 和原版三类配方的 assemble/产量检查，
世界范围/距离/加载、机器槽权限、配方选择、燃料时长和当前计时由可控依赖提供。
覆盖三类机器、锁与未加载拒绝、同槽原料/燃料不可重复消费、
缺料/非法燃料/满槽/满背包失败保全、组件/耐久/装备保全、空炉备燃料、
整次取出回退、返还桶回收、容器堆叠上限、重载配方时间估算及回执尺寸。
BUSY 由可控观测驱动，runner 取消使用真实 AgentRunner/AgentLoop；
不把夹具释放 busy 写成真实 scheduler/服务器停手证明。
桶样本由测试直接准备，只证明回收/拒作燃料，不证明原版已经产生返还桶。
tools/fixtures/smelting-pack 是独立开发数据包，当前尚无对应 SelfTest 专项入口，
不安装到玩家世界，不加入生产资源。真实烧制、燃料桶返还、组件保全、失败原子性、
Mixin 实际加载、机器保存重进和普通动作回归暂缓，分别挂账；
本卡实现/离线阶段完成，PR 保留 Draft 等待上述真实行为证据，不因此停止后续开发。

### 3.5 物品守恒离线回归

```bash
./gradlew clientTest --offline --tests com.neko.mcbot.server.ItemTransfersTest --tests com.neko.mcbot.server.tools.TransferToolOperationsTest --tests com.neko.mcbot.server.tools.CollectToolOperationsTest --tests com.neko.mcbot.agent.AgentRunnerItemTransferTest
```

仅初始化 MC 注册表；真实 Inventory(null, EntityEquipment)、ItemStack、SimpleContainer
及无世界的 ChestBlockEntity 参与测试，不创建玩家/掉落实体、世界或服务器。
执行生产 ItemTransfers、transfer/collect 参数、守卫、物品移动及反馈；
身体位置、加载、锁/有效性和 scheduler.busy 使用可控观测，拾取实体写回/删除使用替身。
覆盖双向半满/满载/重复调用守恒、低容量/组件堆叠限制、槽及接触面拒绝、
按组件合并/损耗工具保全、选择/装备不变、不能借装备槽、后续允许物品继续搬运、
参数拒绝、锁/未加载守卫顺序、实际数量进入 feedback 与实体余量。
共享 receive 以真实背包验证部分掉落只回传余量；两条挖掘生产调用点编译验证，
不将此写成已运行真实破坏或落地。
runner 使用实际 AgentRunner/AgentLoop 和可控模型/C2S 回执，验证部分完成反馈、
无自动重发、BUSY 与取消/迟到结果隔离，不调用模型端点。
任意模组菜单/setter、双箱整体与阻挡开启、真实容器/实体/掉落、身体停手、
保存重进及普通游戏动作回归仍待安排；历史 F0/冶炼/区块票待验项保留。

### 3.6 挖放语义离线回归

```bash
./gradlew :test --offline --tests com.neko.mcbot.path.DigCostSafetyTest
./gradlew clientTest --offline --tests com.neko.mcbot.server.BlockMiningTest --tests com.neko.mcbot.server.BlockPlacementTest --tests com.neko.mcbot.server.PathMaterialsTest --tests com.neko.mcbot.server.tools.BlockToolOperationsTest --tests com.neko.mcbot.agent.AgentRunnerBlockActionsTest
```

C2 新增 39 项：路径代价 2、共享挖掘 15、放置 8、垫料 4、工具参数/计时任务 6、
实际 runner 4。仅初始化注册表，真实 Inventory(null, EntityEquipment)、ItemStack、
BlockState 与完整组件参与处理；世界/权限/挖掘速度/原版动作返回与目标变化、
掉落实体余量写回均通过受控 Access。没有世界、服务器、身体或真实物品实体。
破坏回调损耗原堆栈的样本只证明动作边界与次数，不是原版耐久已经实测。
水/可替换格/半砖样本提供观察结果，只证明工具不预先限制为空气且正确记账，
不证明原版实际放置、朝向、组件落地或多格钩子已经运行。

覆盖无采收资格先拒绝、目标/选槽/数量/耐久/组件变化停手、实时速度、零硬度/
有界停滞、裂纹清理/取消、原版返回成功但目标未移除、失败且已移除的实际副作用、
旧掉落不动、新实体只处理一次、部分余量组件保全、不再求战利品或二次生成。
放置检查完整来源堆栈/面/准确目标透传、恰好一件消费、误报成功拒绝、
失败副作用、装备/选中槽保全、特殊类拒绝及忙时不动世界。
垫料预算与执行选择一致，命名/新增或移除组件不消耗；纯算法测试实际运行
DigAStar 与 MemoDigSampler，不可行哨兵/NaN/无穷/负值不能穿过唯一障碍。
坐标严格性同样覆盖 move_to 使用的共享解析器。
runner 实际运行 AgentRunner/AgentLoop，ACCEPT 等终态后再给模型掉落反馈、
WRONG_TOOL/BUSY/部分放置不自动重投，受理前/挖掘等待期间取消忽略迟到成功并补齐历史。
服务端 Task 的延迟终态和取消通过直接 tick 验证，不将 runner 取消写成真实身体停手证明。

两条路径调用点及原版 API 编译/字节码核验，当前未运行真实 PathTask 世界遍历。
真实采收/附魔/耐久、裂纹显示、掉落/经验、朝向/碰撞/水中/替换/多格/组件放置、
路径真实支撑与材料消耗、保存重进及普通动作回归均待维护者安排。
3×3×3 已加载邻域不是任意模组钩子的沙盒，远处/延迟掉落和任意模组副作用不在保证内。
统一授权/资源互斥/异常收尾见下节 C3，桥 v1.0、F0、冶炼及 `[m9] A3` 待验继续保留。

### 3.7 调度与权限离线回归

```bash
./gradlew :agent-core:test --offline --tests com.neko.mcbot.agentcore.loop.AgentLoopParkTest --tests com.neko.mcbot.agentcore.loop.AgentLoopLifecycleTest --tests com.neko.mcbot.agentcore.loop.TaskPolicyTest
./gradlew :test --offline --tests com.neko.mcbot.server.ActionPermissionsTest --tests com.neko.mcbot.task.ResourceLocksTest
./gradlew clientTest --offline --tests com.neko.mcbot.task.CompanionSchedulerTest --tests com.neko.mcbot.server.ServerActionGateTest --tests com.neko.mcbot.agent.AgentRunnerPermissionsTest --tests com.neko.mcbot.server.tools.CollectToolOperationsTest
```

C3 新增39项（agent-core 5、根工程12、clientTest22），并修改旧并发假设回归。
实际 AgentLoop/AgentRunner/CallbackChatEngine 执行同轮受理与终局依赖、早派发、
只读无目标越界拒绝、完整服务端确认清单、明确/否定回答、授权回执等待、
取消/迟到结果隔离与历史配对；模型/客户端队列/C2S/S2C 均为可控依赖，
不调用模型、不启动 Minecraft、桥协议仍由既有契约回归验证。

真实 CompanionScheduler 使用 Bodies 边界提供可控在线/丢失观测，
TickTask 真实 tickSafe/年龄/终态/异常/清理计数；无世界任务不会访问 null 身体。
测试执行生产 ServerActionGate、ResourceLocks 和 ActionPermissions 的同一实现，
覆盖身体/容器/挖放区域冲突、只读查询可用、正常/异常/超时/取消清理，
清理自身抛错仍完成唯一终态、派发抛错/异步异常停任务、状态/目标/操作变化、
缓存/重规划清单越权、被补回格不能二次挖、撤销/超时/回拨/伪造/重复授权与范围上限，
同UUID新身体不能继承旧任务，以及换维度在下次动作 tick 前中止。
不把可控 Bodies 写成真实假玩家生命周期或物理停止已验。

collect 操作继续用真实 Inventory/ItemStack；候选通过受控 GroundItem，
另直接验证生产 eligibility 谓词的存活/延迟/target/加载/双距离条件。
私有 target 的只读 Mixin accessor 仅编译，未加载 Mixin 或创建真实实体。
PathTask 的生产调用点、逐格检查、下一落脚格变化重规划与更新的 m8 工装编译验证，
没有运行真实世界遍历、清单确认网络链路、原版多人/机器 ticker 或第三方模组并发。
完整强制构建和实核 XML 证据见 STATUS；真实行为待维护者安排，不以离线通过销账。

### 3.8 有界攻击离线回归

```bash
./gradlew :test --offline --tests com.neko.mcbot.task.ResourceLocksTest --tests com.neko.mcbot.server.JobEnvelopeTest
./gradlew clientTest --offline --tests com.neko.mcbot.server.EntityAttackTest --tests com.neko.mcbot.server.tools.AttackToolOperationsTest --tests com.neko.mcbot.task.AttackSchedulerTest --tests com.neko.mcbot.agent.AgentRunnerAttackTest --tests com.neko.mcbot.body.MeleeClockTest --tests com.neko.mcbot.task.CompanionSchedulerTest --tests com.neko.mcbot.server.ServerActionGateTest
```

RG1参数诊断的针对性回归：

```bash
./gradlew clientTest --offline --tests com.neko.mcbot.server.tools.AttackArgumentDiagnosticsTest --tests com.neko.mcbot.server.tools.AttackToolOperationsTest --tests com.neko.mcbot.agent.AgentRunnerAttackTest --tests com.neko.mcbot.server.EntityAttackTest --tests com.neko.mcbot.task.AttackSchedulerTest
```

新增8项：参数诊断7、实际runner收到生产工具拒绝1。诊断测试直接调用AttackTool，
非法参数必须在访问身体/世界/scheduler前同步完成；检查原因/字段、边界与隐私，
不创建实体或世界。runner使用实际工具产生的拒绝和受控S2C，验证模型历史收到修正提示、
发送参数不被重写、没有自动重发。保留原有效选择器、编号+UUID、保护及具体授权回归。
不调用真实模型，也不将本项离线通过写成T14玩家/牛保护已经实机触达。
维护者选择各本体卡离线完成后一起实机补测，汇总见 `REAL-GAME-RETESTING.md`；
旧 `REAL-GAME-TESTING.md` / HTML 与反馈revision保持原样。

C4新增37项：EntityAttack15、AttackToolOperations7、AttackScheduler5、AgentRunnerAttack6、
MeleeClock2、ResourceLocks新增2；原受理策略用例增加 attack 的实际工具/帽断言。
仅初始化注册表，真实 Inventory/ItemStack/组件参与主手/损耗/装备保全；
Access提供实体身份/生命/吸收/保护/距离/视线/横扫等观测及原版攻击回调，不创建实体/世界/服务器。
实际 EntityAttack/AttackTool.Task/CompanionScheduler/ActionPermissions/ResourceLocks、
ServerActionGate、runner/loop执行，Bodies/模型/网络/时钟仍为可控边界。
验证冷却/无敌帧、同目标身份/主手变化停手、预算、观测死亡/未伤害/吸收、
具体确认与预算不可扩大、命名标志变化/超长清单拒绝、回调异常未知效果、
取消/超时/身体替换/换维度后不再出手，计数保留与资源释放、迟到结果/历史配对。
实体UUID租约与移动区域独立，实体移动不能绕开占用；真实 scan 组装 helper验证编号/UUID一致性。

MeleeClock以真实物品测试换类型重置、原版onAttack的计数输入后恢复、耐久/组件/破损处理；
CompanionPlayer.tick到原生字段与LivingEquipmentAccess的接线仅编译/字节码核验。
Invoker对应的原版装备属性应用、Player.attack/swing、实时范围/视线/保护实体判定、
附近候选选择与扫描、实际冷却/伤害/附魔/耐久/击退/横扫和物理停止均未跑真实世界。
保留原身体无敌，不开启完整Player.doTick/自动触碰拾取；
不以此宣称生存战斗、自动进食/防御、死亡复活、追击/远程、模组副作用隔离或连接器联调完成。
全量强制构建/XML/哈希及真实待验边界写入 STATUS。

### 3.9 状态与资源感知离线回归

```bash
./gradlew :agent-core:test --offline --tests com.neko.mcbot.agentcore.loop.AgentLoopObservationTest
./gradlew clientTest --offline --tests com.neko.mcbot.server.tools.AwarenessToolTest --tests com.neko.mcbot.agent.AgentRunnerObservationTest --tests com.neko.mcbot.task.CompanionSchedulerTest
```

C5新增21项：核心观测5、工具11、runner4、调度槽观测1。实际loop异步等待本轮快照，
历史不积累快照/不改工具回执、失败/超长/异常停任务、取消及跨任务迟到隔离；
runner通过实际内部status信封和可控S2C验证每轮刷新、当前task绑定、失败/超时停止。
旧runner测试在ClientServices观测边界提供空成功夹具，新4项启用真实请求/等待接线，
不把前者写成服务端快照已经运行。
工具仅初始化注册表，实际Inventory/ItemStack/BlockState/SimpleContainer参与；
世界/区块/位置/锁/有效性/未展开战利品由Access给定。木材/多变体/最近坐标、采样与结果帽、
未知不读、参数/未知标签拒绝、新扫描不继承旧目标、分页和耐久/组件保全得到验证。
状态报告测试逐槽材料变动/选槽、旧字段兼容与任务计数；调度测试实际年龄/终态清除。
运行时标签加载/区块缓存、真实身体/世界/容器/菜单和单人模型/连接器联合验收仍待验，
没有启动MinecraftServer/实体/玩家/真实模型，未增加progress网络生产者。

### 3.10 有界流程与传输取消离线回归

```bash
./gradlew :agent-core:test clientTest --offline --console=plain --no-daemon
```

C6新增29项：AgentRunnerWorkflowTest19、AgentLoopSearchGuardTest5、
LlmCancellationTest4、CallbackChatEngineTest新增1；原摘要生命周期测试补取消断言。
实际runner/loop/BoundedWorkflow/PendingJobs/CallbackChatEngine运行，
模型、游戏网络/原子工具回执和时钟由可控边界提供，不读取玩家配置或创建游戏世界。
覆盖采集受理后等终态、单模型轮次烧制组合、整份拒绝、数量/步数/时限边界、
缺制作/燃料条件/空目标、部分取出、失败/取消/重载结果保留与迟到隔离、
授权编号保存不自动批准、长活帽/精确截止/排队未发送子调用清理。
流式早派发尚未形成完整工具轮次时取消，以真实停止回执Nudge保留部分结果，不伪造工具配对。
核心验证改半径/穿插状态不重置空搜索、第三次空结果不派发同轮后续动作、
历史配对、新任务/物品进展重置、取消模型future及data复制。
HTTP测试仅起临时127.0.0.1端口，用真实JDK HttpClient/SSE订阅：
流中取消后服务端观察到连接关闭、响应头前取消不触发HTML换道，
订阅者直接测试晚订阅取消与晚帧忽略；监听器/线程池结束关闭，不调用真实API。
HTTP取消不回滚已发送的游戏动作；真实世界/机器/取消停手、模型与连接器另待验。
请求预算与原版材料消耗、取整产量和掉落的区别见TOOLS §2.9，不是硬消费沙盒。

### 3.11 确定性服务端只读验证（RG2）

```bash
./gradlew clientTest --offline --tests com.neko.mcbot.server.ServerToolDispatcherTest --tests com.neko.mcbot.server.ServerActionGateTest --tests com.neko.mcbot.agent.AgentRunnerPermissionsTest
```

RG2新增17项实际dispatcher回归，专项共27项通过；完整构建与XML统计见STATUS。
生产网络入口、显式验证命令和离线夹具共用同一个信封处理函数，
仅替换MC身体查找、工具效果和包投递，RateGuard使用可控时钟；
白名单/args/owner、任务身份/维度、ActionPermissions、ServerActionGate、
CompanionScheduler与回执编码都执行生产实现。
覆盖10类修改工具只读拒绝、观察及craft/smelt查询可用、伪造参数无效、
失效/畸形任务与身体替换/换维度、任务结束/取消、按发送者限流、
验证PASS/FAIL/BLOCKED及临时任务清理、正常受理唯一ack/终态。
没有创建MinecraftServer、世界或玩家，也未调用真实模型。

游戏内由有OP/gamemaster权限的同伴主人在聊天栏执行 `/mcbot verify_readonly`；
必须已有自己的同伴且无活动任务，命令不接受控制台代投，不替换任务。
固定建立只读任务并尝试wait(1)，只接受服务端结构化READ_ONLY拒绝为PASS；
限流/未知工具/任务未建立分别失败或阻塞，不伪装成通过，最后清理临时上下文。
该命令验证实际服务端dispatcher/权限与身体查找，不经过自定义C2S codec/Fabric传输、
客户端模型循环或外部桥；T11真实结果仍待维护者补测，操作/判据见REAL-GAME-RETESTING。
没有权限绕行开关或任意工具诊断接口，桥v1.0不变。

### 3.12 取消视觉补证准备（RG3）

RG3只补T12实机缺证：可见裂纹后取消/清除、回到非暂停世界连续10秒停手、
不重载/换世界/重召直接投新status任务。连续录像时间线和分项判据见
REAL-GAME-RETESTING的T12节，反馈用 `templates/RG3-T12-FEEDBACK.md`；
不改首轮REAL-GAME-TESTING/HTML/原反馈revision，不因缺画面预先修改动作实现。
本地候选包只包含既有测试JAR、补测步骤、未测模板与相对路径/哈希清单，
产物放忽略的build，位置与SHA256记入STATUS；不覆盖发行附件，不含配置/日志/凭据。
构建缓存检查和打包验证不等于新增取消实机证据，开发端仍不启动游戏或真实模型。

### 3.13 宿主问答本体离线与交接准备（RG4）

```bash
./gradlew clientTest --offline --console=plain --no-daemon --tests com.neko.mcbot.agent.AgentRunnerBridgeQuestionTest --tests com.neko.mcbot.agent.AgentRunnerQuestionTest --tests com.neko.mcbot.agent.AgentRunnerPermissionsTest --tests com.neko.mcbot.agent.ClientSessionLifecycleTest --tests com.neko.mcbot.bridge.BridgeHttpContractTest
```

新增3项使用既有OfflineAgentClient/RunnerBridgeHarness，执行实际runner/loop、
RunnerBackend、BridgeHttp/BridgeService与回环随机端口HTTP/SSE。
原样中文/多行/空格任务、session/task/question、超过400字符的授权全文和尾项、
一次回答与服务端回执前等待、重复REST/MCP/回执、过期旧qid与新问题隔离均有断言。
模型future、客户端发包、服务端授权回执及可控时钟采用替身；
未创建世界或服务器、不等待真实120秒、不读玩家配置/令牌、不调用真实模型。
监听器、流、HTTP客户端和runner在测试结束关闭，无生产逻辑或桥v1.0修改。

本体专项/完整构建的执行与缓存事实见STATUS。
协作者整改交接见 `RG4-CONNECTOR-HANDOFF.md`，
B01操作与分项反馈见REAL-GAME-RETESTING及 `templates/RG4-B01-FEEDBACK.md`。
协作者源码保持只读；本体离线通过不证明连接器cue编号/全文展示或真人回答已修好，
无真实question时继续记“问题未生成”，不推定丢弃事件。

### 3.14 测试说明与权限表达评估（RG5）

本卡只调整说明和文档生成器，不修改Java生产/测试、权限策略或桥v1.0。
`REAL-GAME-TESTING-RG5.md`是七个关联项目的补充，不要求重跑已认可项：
T16从真实问题生成起120秒内保存（建议60秒内操作），保存前TIMEOUT不能证明重载清理；
workflow由客户端校验/编排，每步仍走原服务端门；拒绝看实际层及reason，
机器空槽和剩余燃烧时间分别记录，取消工具链不熄炉。

权限评估见 `RG5-PERMISSION-REVIEW.md`。collect属于物品修改，单工具不寻路，
当前没有“允许拾取、禁止移动及其他修改”的独立许可；“不要移动”触发整项只读。
未识别的“不移动”措辞不是新的硬权限，不以改提示词或放宽observation解决。

生成校验只使用Node标准库，对旧默认生成器输出作内存候选/原文件逐字节比较，
检查新版路径、revision、七项编号及实际安装JAR哈希留空。
反馈逻辑可在内存DOM边界执行生成的实际脚本，验证本机保存、JSON/Markdown导出、
同版本导入和新旧revision互相拒绝；这是替身验证，不是浏览器UI或截图验收。
浏览器工具禁止file协议时记录该限制，不据生成成功宣称视觉/文件选择器已测。
校验与完整离线build的执行/缓存、哈希和本地日志见STATUS。
旧HTML/模板/手册及RG3候选包不覆盖，仍不启动游戏/真实模型或安装包。

### 3.15 生命与世界身份观察（RG6）

```bash
./gradlew :test --offline --tests com.neko.mcbot.body.HealthObservationTest
./gradlew clientTest --offline --tests com.neko.mcbot.agent.ClientSessionLifecycleTest --tests com.neko.mcbot.bridge.CompanionStateTest --tests com.neko.mcbot.body.CompanionRosterTest --tests com.neko.mcbot.server.tools.AwarenessToolTest
```

新增6项HealthObservation回归不创建MC身体或世界：加载后低血量、稳定采样不刷日志、
最近采样位置/时间、首次拒绝伤害、实际血量/吸收/无敌/最大生命变化、
异常无返回与新身体互不继承。执行生产采样/JSON，输入仍是可控快照，
不把它写成原版load/hurtServer、tick或现场掉血已运行。
新增1项实际ClientSession/runner/backend/回环HTTP/SSE回归，验证A名字→B新桥空名字→
B名字更新、召唤/配置重载session不变、旧回调不写B、回A重新确认；
游戏S2C、模型、配置与客户端队列仍为替身，监听器/流关闭，不调用真实模型。

CompanionPlayer观测入口经编译/字节码核对，新增hurtServer覆写只调用原版一次并返回原值，
finally观察不吞原异常；诊断RuntimeException每身体固定警告一次，不改动作。
不调用完整doTick，不添加伤害处理Mixin，不改变无敌/食物/拾取/移动/存档。
`[body-health]`阶段、字段与采样遗漏边界见RG6-HEALTH-WORLD-OBSERVATIONS；
日志不是上一条输出之间的连续轨迹，首次变化须按实际相邻采样判读。
原日志G:808仅为模型HP19，G:1562才是直接status19；不能事后归因T04移动。
连接器名称传递缺口由协作者修，实际session与显示仍待采集，本卡桥v1.0不变。
独立反馈见 `templates/RG6-OBSERVATION-FEEDBACK.md`，不强制重跑已认可T04/T18。
完整build、专项执行范围、XML、JAR哈希及原件指纹核对见STATUS。

### 3.16 流式工具Schema匹配（P0）

```bash
./gradlew :agent-core:test clientTest --offline --console=plain --no-daemon --tests com.neko.mcbot.agentcore.provider.StreamingTurnReaderTest --tests com.neko.mcbot.agentcore.provider.ToolArgsScannerTest --tests com.neko.mcbot.agentcore.llm.LlmStreamDispatchTest --tests com.neko.mcbot.agentcore.loop.AgentLoopEarlyDispatchTest --tests com.neko.mcbot.agentcore.loop.AgentLoopParkTest --tests com.neko.mcbot.agentcore.llm.LlmCancellationTest --tests com.neko.mcbot.agent.ClientToolStreamingTest --tests com.neko.mcbot.agent.AgentRunnerWorkflowTest
```

新增读取器9/SSE接线1/生产工具目录1，共11项；旧实现先复现7项失败。
工具必填字段按名称取，调用index只标本轮槽位；晚到名称/ID、未声明工具、
目录长度外的重复调用、非连续索引及异常独立对象不误判。
实际生产SSE字节订阅器、provider、reader与AgentLoop在可控模型/执行器边界运行，
确认完整坐标到达但流未EOF时执行，终态仅一次且assistant/tool配对完整。
不发送HTTP模型请求或创建游戏；原取消回归仍只用临时回环HTTP/SSE。
70项专项、656项强制全量及最新JAR见STATUS，不据此声称真实提供商速度已提升。
后续现场比较同attempt的first_dispatch/stream，不将ready或early计数直接当作省时。

## 4. 真实端到端（客户端侧）

开发服直连 `runClient`，或使用本次构建的 `build/libs/mcbot-0.1.0.jar`，
另行准备对应 Fabric API，两 jar 放进任意 1.21.11 Fabric 实例 `mods/`。
`dist/` 保留部署说明、不提交 JAR；不得把该目录的历史包当作最新构建。
进 `localhost:25565`，G 面板填 key → 召唤 → `@bot` 对话（详见 `dist/README-DIST.md`）。
桥接自测（进世界后）见 `docs/BRIDGE.md` 底部清单。

### 4.1 模型空响应诊断

先区分任务与只读按钮：状态/扫描按钮不经过模型，成功不证明模型任务可用。
模型页“测试连接”检查独立两轮工具往返；真正模型任务的诊断在
`[brain] llm response`，连接测试在 `[model-test] llm response`。
配对的 `[brain] llm request` / `[model-test] llm request` 记录实际出站 JSON 的结构计数。
两类摘要均只含本地枚举、布尔与数值，不记录密钥、URL、模型名、提示词、工具参数、
回执正文或内容哈希；`request` 是本进程内唯一的 HTTP 尝试编号，响应必须按它匹配请求，
不能按异步日志的相邻行配对。`attempt=2` 仅表示既有非 200 网页触发的一次 /v1 换道。

- `data=0`：没解析到 SSE data 行，结合 `format` 判断是否返回 JSON/网页或空体。
- `message>0` 且 `delta=0`：发现整轮 message 形状，不是当前实现消费的 delta 流。
- `malformed>0` / `parse_errors>0`：分别表示非对象/非法 JSON 载荷与 provider/回调消费异常。
- `reasoning>0` / `refusal>0`：对应字段出现，不把思考或拒答原文伪装成正常回答。
- `errors>0`：HTTP 200 中也可能有服务错误；该轮报失败，不因已有部分文本宣布成功。
- `empty=true`：无已识别回答/工具。不得自动重发游戏任务，以免早派发动作重复执行。

连接超时与慢响应须分开判读：

- `http=0` 是未取得 HTTP 状态的本地占位，不是服务器返回的状态码；
  `bytes=data=0` 且 `ttfb=-1` 表示没有响应头/流数据证据，不是服务返回了空 SSE。
- 默认连接建立限制为 15s；任务请求 timeout 为 180s，模型页独立连接测试请求为 30s。
  约 15 秒失败且没有响应头，强烈符合连接阶段超时；没有异常子类型时不推断具体 TCP/TLS 原因。
  响应头等待超过 15 秒后仍成功并不矛盾，不能因此判连接限制失效或改大工具回执帽。
- DNS/TCP/无凭据 HEAD 只能帮助排查端点与网络；根路径 403 不等于实际模型鉴权失败，
  后续成功也不能事后证明旧请求连接了哪个 IP 或网络故障已经永久消除。
  不固定旧 IP、不关闭证书校验、不自动重发可能已派发游戏动作的请求。

服务错误与失败请求差异（2026-10-09 补齐）：

- `error_category` 区分鉴权、权限、限流、额度、模型不可用、上下文超限、工具协议、
  不支持的参数、请求校验、内容策略、超时及上游/路由；未知值为 UNKNOWN，多帧冲突为 MIXED。
  这是本地对白名单信号的归类，不是对真实根因的独立证明。
- `error_source=CODE|TYPE|STATUS` 表示结构化证据；MESSAGE_HINT 只是有界错误文本的匹配线索。
  `error_code` / `error_type` 本身也是本地类别，不是提供商原始字符串；
  `error_param` 仅映射 model/messages/tools/tool_choice/stream/stream_options 等字段族。
  不得把未知错误直接解释成余额不足、服务宕机或协议不兼容。
- JSON 错误体可跨行解析，只保留最多 16384 字符供诊断，处理后清空。
  `error_body_truncated=true` 表示捕获超限且没有推测其内容；此时 errors=0 不证明没有服务错误。
  非流式 JSON 仍不当作成功的 Chat Completions 回答。HTTP 状态与流内错误状态分开记录。
- 先对照成功/失败请求的 messages、各角色条数/字符数、工具定义数量/字节数；
  再看 missing_results/orphan_results/duplicate_calls/duplicate_results/name_mismatches/
  invalid_calls/invalid_arguments/interrupted_groups。配对统计按每个连续工具组检查，不输出 ID。
  invalid_arguments 指参数不能解析为 JSON 对象，invalid_calls 指 ID/名称/type 的结构异常。
- 对照 stream/include_usage/tool_choice/parallel_tool_calls 与 last_role。
  null_assistant 是正常的纯工具调用形状，不能单凭它判错；回执折叠和压缩后统计的是实际出站视图。
  摘要不改变请求、不拦截或重写历史，只用于定位；结构相同也不能证明正文相同。
- 真机用同一配置与同一世界，在已有伙伴时从任务框连续两次发
  “查看自己的状态，然后扫描附近并简报”，每次等终态后再发下一次，不点只读按钮代替。
  遇错保留日志、不要反复刷；必要时再显式做一次独立连接测试或重置会话作对照，
  不同时换模型/世界/人设/参数，否则无法归因。
- 已识别 error 后不再派发后续流帧里的工具；此前已早派发的动作不能回滚。
  不新增自动重试，不额外循环付费探测；旧日志没有结构摘要，无法事后还原其请求。

仓内 `tools/ModelProbe.java` 是显式选择的无游戏执行器探针：
先 `./gradlew build`，再用 JDK 21 源文件启动模式运行它，classpath 包含
`build/classes/java/client`、`agent-core/build/classes/java/main` 和构建依赖的 Gson JAR
（平台路径分隔符 Windows 为 `;`，其他系统为 `:`）。
参数是 `<实例目录>/mcbot/client.json` 与 `task` 或 `connection`，**不要把密钥写成命令行参数**。
`task` 单请求使用真实提示词/技能/当前工具定义，只统计响应，不执行任何游戏工具；
`connection` 最多两请求，只使用本地 connection_probe 回执。会产生实际 API 用量，
不得加入 build/定时任务或自动循环。它读取磁盘配置，不模拟启动器的环境变量覆盖与游戏历史。

### 4.2 反问回答离线回归

```bash
./gradlew clientTest --tests com.neko.mcbot.agent.AgentRunnerQuestionTest
```

该测试直接运行 AgentRunner 与 AgentLoop，以可控模型 future、时钟和客户端副作用替身
验证问题事件、回答续跑、任务配对、取消/超时清理、配置重载和关闭。
120s 超时通过推进测试时钟验证，不等待真实两分钟；配置只保存在测试内存，
不读取或写入玩家配置，不调用模型 API，不启动游戏/服务器或 HTTP 监听器。
REST/MCP 问答错误通过实际 BridgeService 与 AgentRunner 接线验证；
BridgeEventCapture 只挂载实际 BridgeEvents 总线，测试结束移除。
该结果不替代玩家回答、Minecraft DISCONNECT/JOIN 和外部连接器联合验收。

### 4.3 活动任务重载与重连离线集成

```bash
./gradlew clientTest --tests com.neko.mcbot.agent.AgentRunnerLifecycleTest --tests com.neko.mcbot.agent.ClientSessionLifecycleTest
```

AgentRunnerLifecycleTest 运行实际 AgentRunner/AgentLoop/CallbackChatEngine，
控制客户端回调队列与模型 future，覆盖重载/关闭期间各类等待项与迟到回调隔离，
以及检查显示、配置禁用和取消/新工具发送顺序。
ClientSessionLifecycleTest 运行 McbotClient 使用的实际 ClientSession 处理函数、
RunnerBackend、BridgeHttp/BridgeService，通过临时回环端口与测试令牌验证 HTTP/SSE
会话编号、事件重建、任务取消/续投与旧桥命令拒绝；监听器/客户端在测试结束关闭。

配置保存仅记录到测试内存，C2S 发送仅记录信封，不读写玩家配置、令牌或世界，
不调用模型端点。该结果不证明真实 Fabric JOIN/DISCONNECT 事件触发、
断网后的服务器身体停手或连接器联调通过。失败复现报告可另存到本地 build 目录，
不能仅以完整构建成功代替 XML 的 failures/errors/skipped 核对。

### 4.4 掉落、目标、任务内整理与连续采集离线验证

```bash
./gradlew build --offline --console=plain --no-daemon --rerun-tasks
```

最终日志为 `../build/long-task-final-build-20261013.log`，20任务全部执行，
XML核心240/根93/客户端348，共681项，失败/错误/跳过全0；产物哈希见STATUS。
BlockMining/Collect回归检查不直接入包、组件保全、身体触碰和物品过滤；
生产collect的playerTouch入口以本地官方映射javap与编译确认，未运行原版实体。
AgentLoopGoalTest/AgentLoopContextBudgetTest/ConversationTaskMemoryTest
覆盖数量基线、拒绝假完成、取消、完整请求预算、摘要失败保留和工具配对。
BoundedGatherTest以可控回执验证超过12步的64份真实计数格式、走近拾取、无进展、
第三次挖掘掉落、授权/满包、取消/超时；WorkPositionsTest检查有界候选排序。
AgentRunnerGatherTest运行实际runner，验证job_ack不推进、终态/最终数量与取消迟到隔离。
这些结果不替代真实拾取动画、世界中的可达性、真实模型提速或野外钻石任务验收。

## 5. 施工纪律（红线）

1. **Clean-room**：可以从任何同类开源项目读机制与设计动机，**禁止**复制/翻译其代码、
   注释文本、README 句子、美术与专有名称。机制的一手参考：Carpet（假玩家）、
   Baritone 公开文章（寻路思想）、arXiv 2410.08500（空间字符网格）、OpenAI 协议文档。
2. **密钥红线**：API key 只存所属玩家客户端本地（`client.json`），永不进服务器/日志/聊天/仓库；
   桥只绑 127.0.0.1 + token；三道闸必须随网络层共存，不许"先跑通再补安全"。
3. **不猜 API**：1.21.11 映射漂移极多，签名以 javap 为准，实测结果记进 STATUS
   "1.21.11 API 实测字段笔记"（示例流程见 §6）。
4. **一次一个里程碑**；验收不达标不写下一里程碑的代码；STATUS.md 是唯一进度事实源，
   关账必须写证据（时间戳+日志行），已知偏差必须写明。
5. 提交前扫密：`git add -A` 后核对暂存清单不含 `run/`、`*.jar`（wrapper 除外）、
   含 `sk-` 字样的内容；token/密钥永不落 `.git/config` 与远端 URL。
   推送使用既有凭据管理器或 GitHub CLI credential helper，不将令牌拼进远端 URL、
   命令参数、文档或日志。环境变量凭据失效时，可仅在该次进程临时去掉覆盖，
   检查已有登录；不删除或覆盖用户的凭据存储。

## 6. javap 防漂移速查

```bash
# loom 缓存里的官方映射合集 jar（文件名含 minecraft 版本号，用 ls 找最新的）：
MCJAR=$(ls ~/.gradle/caches/fabric-loom/*/minecraft-*-merged*.jar 2>/dev/null | tail -1)
javap -cp "$MCJAR" net.minecraft.server.level.ServerPlayer | grep placeNew
javap -cp "$MCJAR" net.minecraft.network.protocol.common.custom.CustomPacketPayload
```

编译器永远是对的、javap 永远比记忆可信。把新学到的真实签名补进 STATUS 表格。
