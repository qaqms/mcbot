# D3 持续跟随与共用休眠

短任务、长期阶段、Lua与身体按刻跟随使用同一个follow入口、FollowWork和原调度槽。
本卡只补有限干燥地面跟随与可复用的休眠契约，不增加依赖规划器、成品链或目标恢复器。
离线证据及候选指纹以STATUS最新节为准；B5普通门续卡独立提交后才实施D3。

## 参考完整链与取舍

只读学习开发环境中的参考副本（路径不入仓），没有运行其构建或GameTest，
没有复制或翻译其代码、注释、文档、提示词或资源。

| 参考位置 | 核对的机制与本体落点 |
| --- | --- |
| MoveApi、FollowTaskRecord | 省略目标与明确实体的不同语义、距离/期限、稳定UUID；本体严格参数，默认30秒、最多60秒 |
| FollowCompanionTask | 到位仍保留任务、拉开再起步、目标移动有意义才改路线、必须接住导航失败；复用本体EntityApproach |
| CompanionBrain、TaskSelector、TaskSlot | 行为可运行性与实际身体所有权分开、切换先释放旧输入、休眠不能冒充完成；扩展原BodyControl/CompanionScheduler |
| AbstractCompanionTask及TaskControlGameTests | 抢占、等待、期限和下一步的关系；参考的部分等待会延长期限，本体明确不采用，原总帽包含休眠与避险 |
| MovementGameTests的跟随/无路/目标消失/取消/恢复场景 | 自写本体调度、几何、原搜索和Lua集成回归；参考的跨退出重播留L2，不在D3实现或宣称通过 |
| 原导航目标/路线层与D1、D2已核机制 | 到达判据供规划和实际执行共用；沿用本体StandingGeometry、NavigationGoal、PathTask，不新写路线求解器 |

本次只读内容SHA256：

- FollowCompanionTask：`7a11cb0b8712e1388ea75c40e3adc16c13ce5d6bc3943b504b15f7a92b756ce7`
- FollowTaskRecord：`38dee21d2513b03887d8550b27805453046398b7ef4f9ddb8c77a448060a21fb`
- MoveApi：`771d94669ee2373de3462895b67243eb3a419fc5ac0430767bd8814ac92b5f19`
- TaskSelector：`74a5c894eb8841a5f3cf98bd0e6e9d7de147b483da2ca26794e18abe4c9cf643`
- MovementGameTests：`0d20ca84005210abe90f26cc3bed2c6e1a8e0a18a7711c31845ef63e7da0e8f0`

参考会为悬空目标找下方落地锚点，本卡选择更保守的真实目标位置/身体盒距离：
飞行、虚空或不可达高度照实无路，不猜地面、不传送。参考的受理前另做准备规划不迁入；
本体搜索仍在原PathTask按刻分片，ACCEPTED只表示受理，无路由原终态通道返回。

## 共用执行契约

`TickTask.poll(body,elapsed)`只读事实和结算生命周期，不发运动、挥手或用物输入；
原调度器在身份/维度/许可检查后每刻调用，包括休眠与REFLEX抢占期间。
`wantsBody()`使用该次已读事实决定是否需要输入，默认true，其他任务的既有行为保持。

`BodyControl.dormant(originalToken,true)`只使原工作从WORK退到IDLE，原令牌仍保留。
其他工作不能借休眠插入原槽；REFLEX仍优先。切换走原释放回调/onSuspend，清掉旧路线与输入。
每次切换后核对槽身份，清理异常或回调取消不能再推进已移除任务。
唤醒清除同令牌休眠标记，恢复前走原onResume检查；不新建任务、权限、租约或搜索账。

`elapsed_ticks`包含休眠和避险，`execution_ticks`只统计真正执行的身体刻。
睡眠检查不执行PathTask，也不清除正在使用身体的REFLEX输入；
取消、身份变化、超时和异常按原终态/清理契约释放原槽及租约。
默认任务仍可用原TIMEOUT结束；follow在明确期限到达时单独结算持续行为。

## follow 输入与边界

```lua
local r=mc.call("follow",{seconds=30,distance=3})
return r.data
```

`target_uuid`省略时绑定原主人UUID；指定时使用scan_area的标准小写UUID，
不接受运行期编号、任意名称、替代选择器、自身、模型权限或额外参数。
`distance`为整数2-6，默认3；`seconds`为整数1-60，默认30。
原服务端cap为seconds*20；客户端沿用90秒普通等待余量，
ACCEPTED后再按同一cap_ticks使用原待办时钟，不续期或重新发起工具。

起始真实脚格为固定锚点，默认32格节点球。已有工地时，半径收为原工地球内以该脚格
为中心的保守内接球，最多32格；不足1格就SCOPE_LIMIT，不扩大到交集外的节点。
保留工地underground约束，原路径再limitTo原工地。
本卡的movementScope同样提供给原D2身体自保，因此休眠时借用身体也不放宽原工地。
内接球可能比可行交集更小，这是明确保守限制，不表示整个原工地不可达。

只走路和原版跳跃，不挖放、传送、装备或攻击。搜索使用同StandingGeometry碰撞脚高/
支撑/净空和目标真实身体盒，拒绝与目标重叠；原路径步骤另查原版实体碰撞。
未知、复杂不支持地形和无路均沿原失败返回，不能站在原地无限重新规划。

## 生命周期与累计账

- `following`：离开休眠迟滞带后用原EntityApproach协调原PathTask；实际进入distance内则停步。
- `sleeping`：仍是活跃任务，槽和租约保留，身体IDLE；大于distance+2或真实站立条件不再满足才唤醒。
- `waiting_owner`：原主人暂不在当前维度/不存活时，若原身体与任务仍有效，休眠等至原期限。
- 明确实体消失/死亡/卸载/离维度立即TARGET_LOST；同UUID是唯一目标，不选择另一只。
- 身体或目标离开固定范围、许可/租约失效、寻路失败均终止，不改锚点或补预算。
- 原期限到且目标在场：持续行为正常结束；主人仍不在则TARGET_LOST。到位本身不发终态。

以上不承诺客户端退出后仍保留原任务；断连/服务器关闭/身体卸载若触发原取消或身份失效，
仍按原机制停止，不为了等待主人绕过失效检查。跨退出恢复与重新授权留L2。

一个原NavigationBudget跨所有导航段、目标改位、休眠唤醒和避险恢复累计：
8000节点、32768记录、400ms协作搜索、2次重规划。首路线后每次重建也计重规划；
PathTask自身地形重算使用同账，所以实际可唤醒行走次数可能少于三趟。
已有工地直接借其同一导航账，不另开一份更大的额度；三刻起步去抖及目标移动
至少2格、至少10执行刻的改位节流继续用EntityApproach。

follow局部失败纳入原任务WorkFailureMemory：相同参数/原因累计五次后第六次拒发；
换Lua程序、计时、到期、休眠或wake_count/in_band变化不补次数。
mc.try_call只读取原白名单局部失败，后续修改前仍须完整新鲜status；
原Lua单程序第五次失败即停止的护栏保持。权限、安全、范围、预算和取消不吞。
真实完整身体/地形新事实仍按原规则重评，不建立新的失败恢复规划层。

## 事实、展示与验收

status.task共用原调度观察：busy/dormant/suspended/body_owner、原期限和执行刻，
progress含follow的阶段、固定身份/范围、计数与原累计导航账。
气泡沿原工具发送/结算绑定只显示“正在跟随”，不猜测远端每刻的休眠或距离。

终态保留原失败原因和实际身体差量，`data.follow`含最后生命周期阶段及当前位置、
target_present、in_band_known/in_band、原UUID/范围/期限和计数。
取消可能发生在两个tick之间，终态会重新读取当前目标与实际距离，不沿用上一刻已到位。
in_band_known=false时不得把in_band=false当作精确距离证据。

`behavior_ended=true`仅表示该持续行为已结束，`goal_satisfied=false`始终保持；
即使原期限正常结束、目标仍在身边，也不能代替物品/建筑/交付或其他外层目标验收。
`automatic_resume=false`，取消不恢复，旧终态不能推进新任务。
直接工具与Lua都沿原dispatcher/权限/控制/ACCEPTED真终态/取消与原子预算；
长期阶段不重发task_begin，保留原目标、基线和模型/探索账。

离线验证覆盖原生产调度、BodyControl、FollowWork、StandingGeometry和真实DigAStar搜索，
以及实际AgentRunner/Lua/长期目标、目录Schema和dispatcher。
世界/目标/身体移动、模型与网络边界受控，不创建游戏服务器或调用真实模型。
仍须实机验证原生travel/碰撞、主人暂离条件、实体卸载、避险交接、暂停/取消视觉和多人。
这些条目已加入REAL-GAME-RETESTING，不安装实例或改变玩家配置/无敌。
