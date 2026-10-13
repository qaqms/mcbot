# RG5 权限表达与测试说明评估

日期：2026-10-13，Asia/Shanghai。范围为本体文档与现有策略评估，
不修改宿主/连接器、只读判定、服务端动作门或外部桥v1.0。
新补充手册见 `REAL-GAME-TESTING-RG5.md`，
revision为 `2026-10-13-rg5-clarifications-v1`；首轮手册和反馈保持原版本。

## 只拾取但禁止移动：当前结论

当前没有独立的“只准拾取”任务权限模式。
只读禁止物品修改，collect会消耗/更新地面实体并修改背包，
即使不移动身体也不能归为只读。
本卡保留现有防线，不把collect加进observation、不删除“不要移动”短语，
不以模型传入read_only=false升级任务，不将未识别的否定词当作授权方案。

| 检查点 | 实际代码与含义 |
|---|---|
| 主人文本判定 | `agent-core/src/main/java/com/neko/mcbot/agentcore/loop/TaskPolicy.java` 的readOnlyDirective包含“只读”“仅查看”“仅扫描”“不要移动”“禁止移动”及既有英文短语；不理解任意自然语言，也不是否定句解析器。 |
| 客户端动作门 | `src/client/java/com/neko/mcbot/agent/AgentRunner.java` 投令时计算taskPolicies，executeRemote对非observation工具预先拒绝；不是由模型参数决定权限。 |
| 服务端动作门 | `src/main/java/com/neko/mcbot/server/ServerActionGate.java` 独立检查任务身份/维度和readOnly，collect不在只读查询名单。 |
| 单工具行为 | `src/main/java/com/neko/mcbot/server/tools/CollectTool.java` 同步拾取局部掉落，不调用move_to或寻路；中心与实体都受身体6.5格及请求球体、加载/存活/延迟/target检查。 |
| 普通任务边界 | 普通任务仍有其他工具能力，模型可能在另一步移动；collect的局部性不能证明整项任务有“禁止移动”的硬权限。 |
| workflow边界 | `src/client/java/com/neko/mcbot/agent/BoundedWorkflow.java` 不允许collect或move_to，不可把拾取塞入workflow来形成新的许可模式。 |

“不要移动”会触发只读，“不移动”目前不会；这解释了不同措辞下拒绝差异，
不证明换句话就实现了安全的权限粒度。
首轮T02的实际拾取正证和先前客户端拒绝分别保留，不用其中一个覆盖另一个。
超距collect的反馈建议先靠近，是给后续决策的提示，不是工具自动移动。
拾取半径参数最大12也不能把身体6.5格范围扩成远程取物。

## 为什么本卡不改权限

仅在客户端排除move_to，或只把collect列为只读，会造成客户端/服务端不一致，
并将本来应拒绝的物品修改混入只读。
仅修改提示词不能保证模型不选择其他动作，也不能给旧客户端形成服务端约束。
当前真实需求只有“只捡物、不移动”的说明与评估，没有批准新增权限模式的完整语义。
本卡以明确记录限制和拒绝层作为交付，不扩权限能力。

后续若单独授权实现，可保留严格只读，并增加独立的有界拾取许可：

- 由主人投令确定，而非模型工具args；绑定现有owner/companion/task/维度生命周期。
- 客户端预派发与服务端共用一致的能力判定，仅放行约定查询与collect；
  move_to、挖放、装备、存取、制作/烧制修改、攻击等仍拒绝。
- 明确拾取目标、数量与允许范围，复用现有加载/延迟/target/守恒/资源门，
  不默认扩为全半径拾取或跨区域寻路；目前collect没有指定数量契约。
- 旧客户端、重载、取消、身体替换、维度变化、伪造参数、workflow组合分别回归；
  新能力若需外部字段，先按BRIDGE约定协调版本，不悄悄增必填字段。

这些是后置实现的设计条件，不是已经开放的模式或本卡的自动扩展任务。

## 其余说明的代码依据

T16的QuestionRecord以客户端nowMs登记，tick与回答入口均检查120秒期限；
reconfigure先shutdownBrain再start，旧loop/问题作废。
补测须在问题仍待答时保存，已TIMEOUT的样本不能证明重载清理；
仅暂停世界不能可靠停止客户端期限。

workflow在AgentRunner本地parse和只读整份预检后，由BoundedWorkflow逐项执行，
子调用走executeRemote并等待真实tool_result/job_event终态。
区分流程预检和服务端子工具拒绝，不把服务端包装为一个外层workflow槽。

SmeltTool读取实际burnRemaining/cookProgress/cookTotal；
空槽和剩余燃烧时间是独立观测，取消工具链不会强制熄炉。
服务端权限门的同一DENIED文案对应多类原因，应看permission_denial.reason，
没有匹配READ_ONLY不能替代T11专门证据。

## 验证与未覆盖边界

本卡不新增生产逻辑或重复行为测试；复核现有TaskPolicy、runner问答/权限/流程、
服务端权限与拾取/机器测试，再运行离线build，执行/缓存事实写入 `../STATUS.md`。
生成器新增显式 `--rg5` 输出，旧路径和revision默认保持；
校验旧生成内容逐字节相同、新页七个关联编号/独立revision及反馈隔离。
浏览器实际验证结果另记STATUS，不能用生成成功当作导入/导出已经实测。

说明和既有离线测试不能证明真实模型理解任意限制，
也不新增拾取模式、物理停止、机器熄炉或连接器联合验收的正证。
15项认可、T12部分认可、T11/T14/B01阻塞继续保留，RG6本轮未开始。
