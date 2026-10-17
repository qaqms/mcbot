# L1 多类目标的独立现场验收

本卡从S3接入物品、建筑和容器交付的确定性条件，沿原AgentLoop的结束守卫、
GoalContinuation阶段账、AgentRunner及原服务端工具执行。没有新增模型裁判、材料规划器、
成品链或外部桥协议；完整构建、实际XML和候选哈希以STATUS为准。

## 参考核对与取舍

只读核对开发环境中的参考副本（路径不入仓），无Git元数据，不虚报版本提交。
未构建或运行参考测试，不复制或翻译源码、注释、提示词、文档或资源。

| 参考源码后缀 | 关联机制及本体处理 |
| --- | --- |
| agent/.../goal/GoalSteward、GoalState | 有限工作结束后才判断外层目标，停止及旧回调隔离；本体继续用原loop本地确定性检查，不采用额外模型裁判或跨run新预算 |
| agent/.../goal/GoalStewardTest | 有限身体任务尚未终结时不判断，常驻行为与目标分开；本体所有ACCEPTED仍等真终态，有限follow结束不验收建筑/交付 |
| task/build/BuildSurvey、build/BuildValidity | 查询、目标匹配和施工同源；本体核全部支持格、明确作者属性和稳定事实，不把跳过/未知/不支持归为完成，不引入宽松替代 |
| tools/ContainerOps、task/inventory/TransferCompanionTask | 按真实动作前后对账、权限及部分效果；本体保留原ItemTransfers和普通容器门，新增交付事实而非菜单点击系统 |
| numen-api/.../task/CompanionBrain | 身体任务槽与持续行为控制的分工；本体复用原scheduler、休眠和控制，不新增目标调度器或自动恢复入口 |

内容指纹SHA256，仅标识本次所读参考文件：

| 文件后缀 | 指纹 |
| --- | --- |
| goal/GoalSteward.java | `38b6e4c956cba164f8c972d119f0d257898f5d3930c047cb92a971973c96aaa4` |
| goal/GoalState.java | `04744f56d2feed456d1ce0e164bc2b5c1682dd8b46477a0501d33899a9c9be8e` |
| task/build/BuildSurvey.java | `cd61c14f4ffeed2ae94cff5ea9702cb144ad8343a480c50e059dafed1121d2b9` |
| build/BuildValidity.java | `cd476908d1473afe1421d86ab11138350c41f37affe1b5aca8e2009d50650080` |
| tools/ContainerOps.java | `3058e0843439d01260e165fe1573fa21fdf39a7a5df9b1c73be7fef271efe899` |
| task/inventory/TransferCompanionTask.java | `f8e77efed933e43c83772ef14e4202d33477c54917ef44fff1d8c1966e466668` |

## 登记与不可变条件

仍用原set_goal，31项工具数量不变（22服务端、9本地）。
type省略或item保持旧物品接口：item/count/mode，additional取任务开始完整36槽背包基线，
total取当前总量。默认gather仍登记物品目标；已有建筑/交付目标时采料须scope=subtask。

建筑输入为type=building、target及expected_revision；target直接复用build_diff的格集、
fill或instance_id/instance_revision引用。expected_revision为原build_diff返回的内容SHA256，
不是设计或实例的数据修订号。固定原点、旋转、完整格集和显式作者属性，不按建筑名称猜目标。

```json
{
  "type": "building",
  "target": {
    "anchor": {"x": 10, "y": 70, "z": 20},
    "cells": [{"x": 0, "y": 0, "z": 0, "block": "stone"}]
  },
  "expected_revision": "<先从同一target的build_diff取得64位内容修订>",
  "lifecycle": "long_term",
  "max_runs": 8
}
```

上述修订占位必须换成真实查询值，不能直接执行。

```json
{
  "type": "delivery",
  "container": {"x": 12, "y": 70, "z": 20},
  "item": "cobblestone",
  "count": 32,
  "mode": "additional",
  "lifecycle": "long_term"
}
```

容器坐标只是示例，须用实际观测。交付必须先靠近并取得完整基线，再开始搬运。
additional从登记时计，不能事后认领登记前的存入。total明确表示当前指定容器内总量，
允许复用已有满足，不证明由本体交付，也不校验接收玩家。

所有类型共用原lifecycle=task/long_term及max_runs（默认8、1..16）。
成功登记后类型、目标、修订、基线、口径及阶段帽固定，相同条件可重复登记但不重置。
世界目标还绑定原身体body_id和维度；服务端原task/owner/身体门继续检查。
有效世界条件若登记现场不可用则FAILED，不降级为没有目标的纯文本完成；
非法参数可按具体契约纠正，尚无通用自然语言目标提取器。

## 共用读取与完成判据

原ToolExecutor增加带目标的observe重载；默认宿主仍委托普通observe，缺目标证据不可验收。
AgentRunner先读原details status，再通过同任务/身体信封调用原build_diff或inspect_block。
每轮模型前仍只取原身体状态，展示的是“上次独立核验”，不是持续有效的世界断言；
登记及每次纯文本段尾才重新查询完整目标，不把模型回复、Lua return或局部动作结果当完成。

建筑的build_diff增加只读verify_only=true，直接走同BuildSurvey.verify，略过当前页放置预检。
仍在原128格、50ms协作扫描和地形版本规则内；一次读取全部目标，16格明细分页不切分总量。
内容修订、总数、全分类合计、remaining、完整/稳定/未耗尽及同源goal_satisfied交叉检查，
全部支持格实际匹配才完成。未知、卸载、保护/不支持、外力损毁、部分门或旧修订不会假完成。
未完成的已有事实可作为阶段进展，不因可施工集合为空而完成。

BuildSurvey新增goal_observation_key：覆盖完整目标的真实方块类型及作者明确要求属性。
原observation_key仍保留全状态、材料及区块版本供原查询使用；阶段指纹不拿时间、
库存变化、无关区块版本或未要求的炉火等动态属性充建筑进展。已有BodyFacts仍记录
规范化材料和实际位置供模型选择阶段；重复已见组合两段停止，原总40模型步不补充。

容器的inspect_block增加可选item，除原24槽明细外，在同一次服务端调用读取全部
1..1024个本体槽，给item_total/contents_complete；不拼接不同时间的页数、不打开菜单、
不展开战利品、不合并双箱或把全存储网络计作一个目标。锁/有效性/加载/6.5格距离门保留。
只按精确物品ID计件，名称、耐久和组件正文不出站；不承诺指定组件、附魔或损耗的交付条件。

每身体ContainerIdentity最多128个活记录，弱引用原世界、区块及实际容器，
并持有原逐格CellWitness。必须同原生对象/区块/世界及未失效格见证才能沿用container_id；
换箱、同状态拆换、区块新实例或新身体都不继承。无钩子/见证或容量不足不能编造身份。
具体方块状态变化也可能使原见证失效，这是保守拒绝，不自动重绑另一容器。

原transfer在明确item过滤时额外核对容器前后总量、36槽背包反向变化、实际moved_items、
维度/坐标、方向和前后容器身份；匹配才transfer_evidence_known=true。
不改原部分搬运、槽/接触面、组件堆叠、机器拒绝、许可、控制和资源租约。
失败回执中的已核实部分移动仍记账；异常或超时不靠背包少了多少猜交付。

每任务DeliveryLedger最多128组容器/物品，原executeRemote真正终态在交给直接调用或Lua前统一记录：
存入加、取出减，取消/迟到/其他任务和身体不消费，实际发送而未结算的transfer保留未知。
未过滤、未知、不守恒或超限搬运使本任务交付账不完整，不以查询/换阶段/重登记恢复信用。
additional进度为0与下列两者较小值中的较大值：

1. 同一容器当前总数减登记时总数。
2. 本任务同容器/物品已核实净存入减登记时该净存入余额。

累计来回取放不创造信用，旁人单独存入不创造本任务信用，部分交付不算整项目标。
并发相同物品的取换不提供逐件来源认证；这里是净存入与现存量的交叉条件，
不承诺保管、交易事务、崩溃事务或接收者实际获得。

## 生命周期与支持边界

只读入口仍只读，不登记动作批准或自动施工；后续动作走原服务端门。
阶段结束先验最终条件，成功后才done；未满足且未触帽才在同task_id继续，
不重发task_begin、不补权限、原子调用、探索、施工、导航、模型或阶段预算。
有限follow的到位/休眠/期限终态不验收物品、建筑或交付；ACCEPTED仍等真终态才进入段尾。

交付任务可以远行采料。只有明确OUT_OF_REACH读取失败允许保留条件、按新鲜身体
推进原有界阶段；本次进度不可沿用旧成功，回来完整核验后才完成。
未知、锁、换容器/修订/身体/维度、错误及其他读取失败结束为FAILED。
取消、换任务、死亡、断连或重载沿原generation/runner/服务端门清理，
旧查询/模型/动作回执不能重开目标；没有跨退出目标恢复，本卡不实现L2。

桥v1.0不增加输入/事件字段或改变completed的通用含义：
本体对成功登记的三类条件增加确定性守卫，未登记条件的普通作答仍按原语义结束。
本卡不解析任意自然语言、多目标逻辑组合、实体交付或任意红石功能验收，
不把建筑已匹配证明为本体独占施工成果，也不把现场已满足保证为将来永远满足。

## 验证范围

before在旧码复现缺多类set_goal契约。生产WorldGoal/DeliveryLedger、原AgentLoop/runner、
真实Lua、真实注册表/ItemStack/Inventory/SimpleContainer、原BuildSurvey与ItemTransfers参与回归。
覆盖跨页完整目标、外力变化、阶段/模型帽、重复事实、登记及段尾失败、取消/迟到、
实例修订原样传递、超距后归来、Lua部分存入、双向净账和持续跟随真终态。
原生世界/区块/身体和模型/网络采用受控边界；未启动Minecraft、服务器或真实模型，
未安装JAR，不改原无敌、宿主、协作者连接器、参考副本或玩家实例。

最终命令为 `./gradlew build --offline --console=plain --no-daemon --rerun-tasks`。
失败阶段、实际XML与JAR均记STATUS；真实钩子、容器更新/替换/卸载、原生施工及保存重进、
多人争用和模型是否正确登记条件仍待REAL-GAME-RETESTING，不以离线通过销实机债。
