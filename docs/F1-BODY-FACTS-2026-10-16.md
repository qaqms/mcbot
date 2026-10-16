# F1 共用动作与身体差量

本卡承接C1，完成实现与离线验收。实际构建、候选和剩余卡以STATUS为准。
只修改本体，不安装或启动游戏、服务器及真实模型。

## 参考复核与取舍

只读核对开发环境中的参考副本，无Git元数据，不复制或翻译代码或文字。
沿BodyDelta、Belongings及测试，继续核对AbstractCompanionTask的开始/收尾、
NumenPlayer原版装备损坏回调、PressReceipt及TaskDispatch同步/受理链。
学习的是动作前后共用事实来源、物品总量与槽位分离、损坏须有真实事件、
收尾保留已发生效果，以及一次受理和最终结果的区别。
身体归属、取消及恢复前提同时沿用C1已核的完整控制链。

本体没有照搬参考的字符串回执、无界损坏记录或每类动作另取快照的结构。
使用每身体独立的BodyChangeLog，同时供逐刻观察、status及网络动作终态读取。
差量只描述观察区间；其他玩家、自然拾取或避险都可能在同一区间改变身体，
因此不据它证明动作因果、掉落来源或最终目标满足。

| 参考文件 | 内容SHA256 |
| --- | --- |
| api/entity/BodyDelta.java | f34f6b840d66db4cb50e4824e77b015c53cb2f3fcc0c693bac4f218b6b619730 |
| api/entity/Belongings.java | 3de5cb1ba2a2a7d00247ae9fbc4b855d98eeaf1d5a094f7d5ea7baeebf6e3349 |

## 同一事实入口

CompanionPlayer建立基线并在每身体刻末采样；status即时读取同一记录。
库存按原版inventory实际槽位汇总，装备另外描述，主手不重复加到总量。
快照复制ItemStack，比较位置、生命、吸收、饥饿、空气、经验、骑乘、选中槽、
物品总量及装备/组件变化；不输出自定义名字、lore、NBT或任意组件正文。
槽位搬动不产生物品净增，时钟变化不产生事件。

onEquippedItemBroken先保留原版回调，再记真实损坏事件；
空装备槽、换手或物品减少都不足以推断工具损坏。
新身体拥有独立body_id和游标；跨world实例不相减库存。
采样异常不阻断身体tick，恢复后保留observation_gap，
跨缺口的动作标observation_known=false，不能把未知写成零副作用。

dispatcher在原权限/资源锁/身体控制通过后打开动作Window，
在scheduler.execute外的线路投影处附data.body_delta及同源feedback。
同步动作、跨刻动作、部分失败、取消和异常共用这一收尾；
查询不打开动作区间，status通过data.body_changes报告近期事实。
保持scheduler拥有原future，投影不改变原取消收尾链。
Window终态缓存稳定，重复读取不重新采样或执行动作。

持续身体行为即使没有模型调用也进入同一逐刻事实记录。
尚未实现的持续自卫/跟随不因此记作完成；后续触发必须复用该来源。
既有原子效果计数和最终目标验收保持；WorkFailureMemory不消费body_delta，
重复区间、血量、换槽或库存观察都不能给失败次数补账。

## 有界与异常

- 普通变化和原版损坏分别最多保留16条，使用同一单调seq，移动不能挤掉损坏记录。
  status各返回最近最多4条；动作返回开始到终态净变化及最多8条损坏。
- 物品净变明细最多8种，装备槽来自原版有限枚举；快照库存最多64槽，
  超出支持边界明确观察失败，不静默漏算。物品ID/维度文字分别最多128字节。
- 每份差量JSON最多4096字节，超限按完整集合省略并标记；
  history_truncated、breaks_truncated、carried_items_truncated及*_omitted说明缺口。
  长动作仍比较原开始与结束快照，不靠累加已淘汰的事件计算净变。
- 超大线路回执保留有限实际效果字段和有界差量，附receipt_truncated。
  过大的成功job降为failed；cancelled/superseded相位与前缀保留。
  先核对现场，不能因回执不完整重投可能已经生效的修改。
- 动作开始采样失败会在执行前停止；终态采样失败保留原结果及部分效果并标未知。
  同步/异步异常可能已有副作用，不引导自动重试。
- 同时修正控制取得时清理异常的回滚，以及stopUsingItem异常仍清挥手的finally边界，
  避免永久BUSY；不改变原许可、范围、探索账或任何预算。

## 验证与边界

新增18项：核心1、根1、客户端16。专项f1-focused1通过；
最终强制全量见build/f1-final-20261016.log及STATUS，1171项无失败/错误/跳过。
覆盖共用来源、物品与装备不重复、槽移动/空动作、快照副本、组件文本隔离、
损坏回调与卸装区分、有界历史/长区间、换世界/身体、采样缺口，
以及实际dispatcher取消链、同步/异步异常、部分效果、拒绝/查询和线路超限。
核心回归确认重复body_delta不能刷新既有失败账。

本地1.21.11官方映射javap已核onEquippedItemBroken(Item,EquipmentSlot)、
getItemBySlot、经验字段及骑乘读取，构建验证实际覆写签名。
世界/身体/网络边界受控，不是ServerLevel真实动作或原生损坏事件验收。
完整掉落来源/并堆/拾取归因属于F2；完整生存、自卫、跟随、建筑及通用目标核验
仍待各自实施卡。按REAL-GAME-RETESTING追加现场证据，不重跑无关已认可项目。
