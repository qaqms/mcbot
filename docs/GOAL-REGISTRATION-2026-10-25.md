# 建筑目标登记失败与反馈衔接

## 现场与证据

维护者报告STATE_UNAVAILABLE，正文为登记前缺少同身体、修订或容器的完整现场事实。
只读冻结build/goal-registration-session-20261025.log，39531 bytes，原件末写
2026-10-25 13:40:18（Asia/Shanghai），SHA256
`f99ced36af1b01b88633ffd511a04148791dc1236a95932863dc11cd089e83ae`。
实例磁盘JAR为上一轮b0e50bcf候选，磁盘哈希不独立证明运行进程的加载来源。
开工本体工作树干净，HEAD为b261218；宿主、参考、连接器及玩家实例保持只读。

- 13:39:56普通build_diff成功，查询287格、已满足0格，unsupported为0；
  反馈461字符，结构化信封13207 bytes。
- 13:40:04首次set_goal先status，再build_diff；后者反馈46字符、信封146 bytes，
  然后目标登记被统一包装为STATE_UNAVAILABLE，整任务结束，没有开始施工。
- 原BuildDiffTool的TARGET_REVISION_CHANGED分支反馈恰为46字符，位置和大小与日志相符。
  日志未保留该调用参数或失败正文，不能仅凭长度独立证明用户目标如何变化或哈希如何产生。
  这次不是已完成建筑验收失败，也没有换身体或容器的正证据。
- 确认的代码缺口是两层失败原因覆盖，以及普通工具消息只有feedback。
  BuildSurvey把实际target_revision、设计构件/朝向诊断放在data里，原feedback没有这些字段；
  模型直接查询时看不到它们，Lua有完整data，二者不能混同。缺修订可导致模型猜值或重拼目标，
  但旧日志不足以还原该次原始参数。

## 参考机制

只读核对开发环境参考副本的BuildApi.diff、BuildOps.Diff/plan与GoalSteward有限工作结束契约：
查询的结构化事实应由实际返回值消费，设计数据作为真源，目标判断与局部执行分开。
只学习机制，不复制或翻译源码、注释、提示、文档或资产；不引入另一模型裁判或直接写世界。
本次参考文件SHA256：

- work/BuildApi.java：85d72dd6b51624ed46c0bfef3f19c3b2dc87a5d33d23b09adf3c7060b3fd7fa3
- tools/BuildOps.java：ffd31abe6aa08980f6ab9cc1c804cb8783c741e36db335d1d8821ea8edfabfd2
- goal/GoalSteward.java：f5eeb0803c6a9ad59e1977863fc1243c54d7333f60a48f57207e2fc7b071af54

## 实现与边界

1. AgentRunner只对模型直接build_diff成功结果，把实际修订、续页、完整性/稳定性、
   design_components/design_review和clearing_work投影进feedback。格明细、任意data字段及完整库存
   不复制；原ToolOutcome.data、Lua及内部目标观测不变，服务端信封不增大。
2. 目标观测保留原现场工具的失败反馈，并附同次身体事实。核心登记检查先复核身体、
   维度和存活，再处理明确的登记前TARGET_REVISION_CHANGED。
3. 该冲突尚未固定目标，交回模型用最终target的真实build_diff修订纠正，不自动改哈希或改图。
   不重开任务、模型/阶段预算或授权。没有新增默认重试次数门或自动重发。
4. 待登记标记阻止模型在失败后以纯文字或物品条件完成；正确登记后清理标记，
   WorldGoal及GoalContinuation才按原规则固定。取消后新任务、恢复及身体失败清理原标记。
5. 成功登记后的修订改变、缺完整现场、未知容器/交付账和身份失效仍失败。
   报告保留具体现场反馈或BODY_IDENTITY_CHANGED，不回显运行时异常原文。

新增五项回归先在旧码失败：缺直接修订/设计事实、修订冲突立即结束、原失败原因丢失、
未登记完成保护及身份/任务隔离。专项15秒通过，最终强制全量及产物以STATUS顶部为准。
测试运行实际AgentLoop、AgentRunner、对话历史与异步信封接线，模型、身体及世界回执受控；
不代表已在玩家现场用真实模型验收。不安装包，不启动游戏、服务器或真实模型。
