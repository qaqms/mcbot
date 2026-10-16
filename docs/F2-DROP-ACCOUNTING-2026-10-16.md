# F2 掉落来源与实际去向

本轮只推进F2；最终验收、候选及提交以STATUS为准。N3留待下轮。
只改本体，不安装或启动游戏、服务器及真实模型，不改宿主、连接器或参考副本。

## 参考机制核对

只读核对开发环境中的参考副本；该副本无Git元数据，未运行其构建或GameTest。
沿act/Drops、ServerPlayerGameModeDropsMixin、ServerLevelDropsMixin、
ItemEntityDropsMixin、LivingEntityTakeMixin，继续读BodyDelta、PressReceipt、
ItemEntityTouchMixin、DigCompanionTask的开始/等待/取消/超时/cleanup及DigGameTests。
学习动作区间认领实际生成、并堆转移、拾取与损毁事件、同源文字与数据收尾，
并区分身体净变化与物品来源。未复制或翻译参考代码、注释、参数或说明文字。

| 参考文件后缀 | 内容SHA256 |
| --- | --- |
| act/Drops.java | 40fd6a1885fe51b0c8bcc63003179337d40544642c87bef8f6c56df80e2b727b |
| mixin/ItemEntityDropsMixin.java | e41fa7ce530e23abf82c2dd91820542c7abf33e4998f4ccd42a55df950c32925 |
| mixin/ServerLevelDropsMixin.java | fc6edba09cf8596fb69b8cee8eb218383dfd19ad0f1a93980288b6fe7e4267e0 |
| mixin/ServerPlayerGameModeDropsMixin.java | b5bea9d3dbc4faeaeb692f0042016e9713cfd329343a785943749e2ba99f0a6c |
| mixin/LivingEntityTakeMixin.java | e7cc3c6b56dcb8a5f966c2ba06fb8cfa699e3da9351052b8e9d42f1c33e5f68d |

本体没有采用按参考观察顺序分配混堆来源、只凭take记录经手者、
落地便永久结账或新增固定等待窗的做法。部分混堆不能证明哪份来源被拿走，
直接将涉及的来源量转为未核实；不把最近一次经手者套给后来的损失。
本地1.21.11字节码还确认playerTouch的take参数是原堆数量，
全拾取后会恢复已移除实体的旧stack数量供统计，因此按动作前后实际存活栈减少结算，
不能用动画参数或单看最终getItem().getCount()代替。

## 共用入口

- BlockMining的实际破坏调用建立有限Request，原生destroyBlock进入匹配身体/坐标的
  捕获区间。嵌套的其他破坏遮蔽外层区间，finally归还；动作不重复执行。
- 只有该区间内ServerLevel.addFreshEntity实际接受的ItemEntity记入来源。
  现有世界实体、失败的实体加入、区间外生成不按距离认领。
  原版Block.popResource及popResourceFromFace提供真实源格：
  source_cell、other_cell和operation_only分别为源格、其他格及仅知同操作区间。
  后两者不进入目标资源产出样本；容器内容等未经源格确认的产物保守处理。
- 单挖为target，局部遮挡为clearing，路线开路为path_clear，分别记录实际源坐标。
  短工具、Lua、gather、长期阶段及将来的身体触发复用这一实现，不新建采集规划。
- DropLedger按世界实例独立，Origin含ledger_id/origin_id、actor_uuid/body_id、
  role/source/created_tick。实体只保存有界份额、物品/组件身份和固定观察字段，
  不保存Level/Entity引用，不输出名字、NBT或组件正文。
- 标准原版ItemEntity的并堆、playerTouch、hurtServer，以及Mob.pickUpItem提供
  前后事实；与BodyPickup/collect共用同一playerTouch，无额外入包或拾取动作。
  原许可、只读闸、区域锁、身体控制、原生延迟/归属/容量均保持。
- 每服务器刻、status及动作收尾重验原记录身份，不强加载区块。
  被删、卸载、失踪、未知数量/组件变化、观察异常均保留未核实量。
  从未观察到原生损毁事件时，不凭消失断言烧毁或掉虚空。

## 数量与歧义

对一个已观察来源，生成量始终由当前可归源在地量、本身体实际拾取、其他实体实际拾取、
原生损毁和未核实量构成。它们不等于背包现存量；后续消费、转移或死亡仍须查真实库存。

全堆合并可以携带多个来源；单一来源覆盖整个捐出栈时，部分转移也能精确分量。
与历史物或其他来源混合后再部分转移/拾取，无法区分具体来源，
将捐出栈涉及的已知份额全部转为未核实，接收栈原有可证份额保留。
全混堆拾取仍可结算其中各已知来源，不会把历史份额也认领。
同UUID新身体不能记为原身体自行拾取；新世界或新身体的查询不继承旧来源身份。

accounting_complete只表示已观察生成集合的数量去向可核实，
不证明战利品表已完整产生、全部已落定、任务完成或背包达标。
attribution_scope固定synchronous_admitted_items，delayed_spawns_attributed=false。
脱离破坏调用、未来调度或模组异步生成没有可信来源，后续扫描仍作为未归源现物处理。
标准Mob入口以外的覆写、漏斗、传送、其他模组搬运无明确事件时同样保持未知。
当前不是全世界任意动作/战斗战利品溯源，也不持久化跨退出的来源账。

## 回执与失效

break_block：

- drop_accounting_known声明原生捕获是否可用；缺钩子明确未知，不降级为附近实体差集。
- drop_accounting报告生成、在地、本人/他人拾取、损毁及未核实计数和有界事件。
- drops只含源格当前可归源实体，related_drops包含其他格/仅操作区间的现物；
  每项count是可归源份额，实际整堆可能还含历史物。
- drop_spawns是源格实际生成样本；drop_spawns_complete=false时不可用缺项推断来源错配。
  原remaining_count是所有已观察来源当前可归源在地量，collected_count仍为0，
  表示挖掘不直接塞包，实际拾取另列，最终成果仍查背包。
- clearing_results和clearing_drop_summary独立于目标来源。
  原移除后两拍窗口不延长，终态/取消刷新去向；异常后不重发原破坏动作。

move_to通过DropObservations保存轻量来源令牌，避免逐格重复保留完整明细；
刷新一轮世界记录后聚合，不对每个来源重复刷新整个集合。
observed_drop_items为已观察生成量，remaining_items为当前可归源在地量，
picked_up_self_items/picked_up_other_items/destroyed_drop_items/unresolved_drop_items分别报告去向。
旧身份观察回执仍可读取，但没有事件证据就保持旧的未知口径。

status.drop_changes给当前身体最近四个来源摘要；scan_area.item_targets[].drop_origins
给当前身体可证份额和unattributed_to_body_count，不把历史物认成本次产出。
gather仅用完整源格生成样本作有限产出观察，仍用新鲜完整背包验收。
来源/并堆/拾取计数不参与WorkFailureMemory失效，不补失败次数或模型/探索/动作预算。
超大线路回执保留有界来源摘要并显式省略样本，原失败/取消含义及真实部分效果保持。

## 有界与验证

每世界最多128来源、256在跟实体、512份额；来源保留2400游戏刻。
满账或时间失效将可跟踪份额转为未核实，旧令牌不可用时不猜零。
每来源最多16生成样本、16当前份额、8事件，JSON最多8192字节；
省略及样本/事件/现物截断分别声明。status最多4摘要，扫描每现物最多8份额，
路线保留最多256轻量令牌、输出最多16来源摘要和16现物份额。
服务器关闭/重开清理瞬态账，弱世界键且值不引用世界；不保存执行许可。
未跟踪实体的并堆/拾取/受损直接走原生动作，不额外复制栈或轮询。

旧实现在f2-before-20261016.log复现缺来源可用性声明：24项1失败；
focused1通过，补数量/生命周期/真实runner及线路边界后focused2通过。
最终专项、强制全量XML、日志行号及候选哈希见STATUS。
原有大掉落断言按新16明细帽更新，同时明确断言总量40及截断标记，不删除总量/组件保全检查。
测试运行生产账、BlockMining/ClearingMining、取消收尾、实际runner/Lua及dispatcher；
原版注册物品、映射方法和JAR钩子配置参与验证，世界事件前后样本为受控边界。
反射检查实际映射描述符及资源打包不等于Mixin注入或原生世界动作已执行。
原生事件顺序、模组兼容及真实ServerLevel入世界/合并/拾取/损毁仍待维护者实机补证；
不把离线通过记作完整生存或持续自卫/跟随已实现。
