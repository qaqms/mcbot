# S3 死亡与身体重建隔离

本卡接已提交的S2 `096af1f`，只改MC agent本体，参考为开发环境中的只读副本（路径不入仓）。
S3完成有界身体重建与旧工作的隔离；外层多类目标验收L1、跨退出目标恢复L2仍另卡。
不改无敌，不安装或启动游戏、服务器、真实模型，不改宿主、协作者连接器或玩家实例。

## 参考机制与取舍

核对参考的以下实际链路，未复制或翻译代码、注释、提示词或资源：

| 参考位置 | 核对的问题 | 本体取舍 |
| --- | --- | --- |
| `numen-api/common/src/main/java/com/dwinovo/numen/api/entity/NumenPlayer.java` 的死亡/逐刻入口 | 假玩家没有客户端重生包，稳定UUID不能表示身体实例 | 本体原die入口一次性退役，END_SERVER_TICK有界协调，复用已有observationBodyId |
| 同目录 `Companions.java` 的onDeath/tickRespawns/respawnDead与`CompanionFactory.java` | 先通知死亡再发任务终态，安全落点、主人离线和存档 | 采用顺序与新鲜几何原则；不把尸体补满血保存，不无限逐刻重试，不重载死亡前库存 |
| `api/task/CompanionBrain.java` 的死亡/离场清理 | 动作槽、准备调用、手部占用和旧任务保存如何终结 | 复用原scheduler/HandLease清理与真实部分效果；客户端原loop统一失败，排队目标也终止 |
| `api/network/payload/NumenDeathPayload.java`、`NumenRespawnPayload.java` | 死亡与重建如何传到大脑 | 只扩本体内部companion_state；外部桥继续使用既有failed终态 |
| `agent/.../loop/HaltReason.java`及目标生命周期 | 身体恢复和目标继续不是同一事件 | S3不自动开新模型轮次、续阶段或重放旧目标；明确新任务仍先新鲜status |

参考指纹（SHA256，仅标识本次读取内容）：

| 文件 | 指纹 |
| --- | --- |
| `entity/Companions.java` | `6b50d8ac7dee45fecbdb8501c406c41735365370b59741ba61086ffd1c9c75b6` |
| `entity/NumenPlayer.java` | `4914440acacc15810842cdb2398341f98551c6ccd883ef5c98781e8a3aba6753` |
| `task/CompanionBrain.java` | `455f4cc61b64a21e9a8f987074d7d5b11e0bc19ce42466665dbef4c6eeedc666` |

## 共用执行契约

`BodyLifecycle`在原生死亡入口先锁住旧身体、发布死亡，再撤销精确身体的权限和批准，
由原scheduler结束活动工作/反射租约，结束环境、自保、维护及手部占用，清输入/使用/挥手。
清理后才调用原生`ServerPlayer.die`，避免租借主手在掉落之后恢复出旧物品。
异常仍尝试后续清理与原生死亡；通知、撤权、清理或原生结算不完整即停止自动重建。
物理步在travel、方块效果和落地作用后重验身体/世界，死亡后不再运行后续作用或恢复工作。

客户端先阻止新派发，再由原`AgentLoop.failTasks`终止活动及排队目标、模型/整理future、
受理前调用、挂起长活、反问与授权、Lua/采集/探索等本地组合；工具历史保持配对。
旧阶段、迟到模型帧/工具回执/长活终态不能启动下一轮。
新身体沿用同伴UUID，但`body_id`不同；任务控制、授权、取消和动作在排队时冻结原body_id，
服务端再次按当前身体检查。旧取消/task_end/task_begin也不能影响同UUID的新身体任务。
服务端调度在poll/休眠唤醒前检查生命状态；只读状态仍可查看尸体的真实库存和事实。

短任务、长期阶段及持续行为共用上述入口，没有第二套动作、权限、失败账或目标恢复器。
重建本身不完成外层目标，不清除失败后重开旧任务的预算；只有明确的新任务才建立新上下文。
新任务仍先走原status观测门，重建通知本身不触发模型请求或自动寻找掉落。

## 有界重建

- 原身体仍为玩家列表中的同一对象、在册且主人在线存活时才处理。遣散/替换后不再重建。
- 等待40游戏刻；其后每20刻最多一次安全落点搜索，累计最多8次。主人不在不花搜索次数。
- 每次只查主人当前世界附近75个固定候选，复用StandingGeometry的已加载、干燥、危险排除、
  分数脚高/支撑与真实实体碰撞。不强制加载、不挖放、不回退到未知或被占的位置。
- 新建CompanionPlayer，先在死亡世界用原生`restoreFrom(old,false)`处理keepInventory语义，
  再移到目标世界注册；不手工生成背包、护甲、经验或掉落，不复制旧攻击者/输入/控制/许可。
  保留原无敌设置。原生玩家列表移除保存实际尸体后，再注册新身体。
- 搜索耗尽为recovery_blocked；部分注册/通知失败为recovery_failed，部分新身体先退役再清理注册。
  均不自动重试。当前没有“重置重建次数”按钮或自动修档，终止态需维护者显式处理。

## 事实与保存

`status`有当前`body_id/alive/body_state/body_lifecycle`，完整装备仍来自真实inventory；
`last_death`保存旧身体身份、死因、原生/清理是否完成及F2同一DropLedger的death来源。
只计原生die区间中实际接受生成的物品，不预测战利品，不把空捕获当成没有掉落的证明。
新身体持有有界快照及snapshot=true，不持有旧实体；旧掉落被新身体拾取也不继承旧身体的拾取记功。
最终物品成果仍查当前完整背包。异步掉落、墓碑模组等保持未知，all_death_drops_proven=false。
Lua查询摘要保留当前body_id/body_state/alive；BODY_DIED不能被try_call吞掉后继续动作。

原版playerdata附加有版本的mcbot_body_lifecycle，保存死亡是否结算、原身份和已花搜索次数；
重启不重播die，不补搜索次数或旧目标。只恢复可证明完整的dead状态。
未知0血/坏字段/未知版本/中断的rebuilding保持停用，不补血、不猜库存。
上次死亡快照有界写入mcbot_last_death，读取上限12288字节。
没有跨玩家数据与世界掉落实体的崩溃事务；断电早于原版保存仍属于原版保存窗口风险，
不承诺崩溃后物品恢复。此处只保存身体生命周期，不是L2的目标/预算/取消意图恢复。

## 验证

旧码先复现死亡后活动任务仍运行：build/s3-before-20261017.log，1项1失败，18秒。
首次编译及该回归通过：s3-compile1，25秒。
focused1中124项客户端专项有4失败，都是新NBT夹具漏传HolderLookup.Provider；
改用已核API的RegistryAccess.EMPTY，保留断言后focused2通过，28秒。
final1全量1090项客户端有1失败：取消通知被新增网络可发送检查拦截。
恢复“runner通知、客户端边界检查实际网络”的原契约后final2强制全量通过，54秒/20任务执行。
随后保留有界实际掉落样本并补try_call死亡停止测试，final3强制全量52秒/20任务全执行，
三根XML合计1585项/174 suites（356/41、139/20、1090/113），失败/错误/跳过全0。
日志第61–62行及JAR指纹见STATUS。

离线覆盖生产loop/runner/dispatcher/scheduler/权限、Lua、原生NBT读写与共用掉落账：
五类等待、排队、旧控制/取消/授权、同UUID新实例、长期目标的新鲜观测、Lua子调用、
异常清理/原生死亡各阶段、主人不在、安全搜索耗尽、存档剩余次数及坏元数据。
身体、世界、原生死亡/重建/掉落实体事件、网络与模型边界受控。
API/编译/离线通过不等于原生keepInventory/附魔/墓碑/动画/多人或实际重进已验；
完整补测见REAL-GAME-RETESTING。
