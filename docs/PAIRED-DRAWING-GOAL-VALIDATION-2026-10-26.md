# 床头脚错误与目标登记（2026-10-26）

## 新现场

本次实例JAR匹配上一轮及时修补候选
`d881f4f94127512b022e2adda62b4253d653755a5c412c5469f153a74b670751`。
日志冻结为`build/bed-goal-evidence-20261026.log`，99002 bytes，末时刻16:56:57，
SHA256为`d1f0b8781e6725912033d349e0ae6c7d4d2449c14d6a93dcf2baffe2ecbff170`。
这次没有进入上轮的屋面寻路与临时修补阶段，不能当作该修补实现已经实测失败。

- 16:55:00（360–361附近）build_diff查询686格，满足1、剩685，
  material_bill_complete=false，但首页明细是基础，未露出后面的床格错误坐标。
- 16:55:29（426附近）set_goal接受修订
  `ad514a0a3ec620fec1faef88a943e3e4a7ca69c85630460fd7083e4926defe9f`，
  把该图固定为最终目标。
- 16:55:34（445附近）build_site才拒绝(-235,64,-432)的
  bed_pair_missing_or_inconsistent。设计床头相对z8、床脚z9，两半却写facing=south。
  原版语义要求床头在床脚沿facing的一格，因此这组坐标应朝north。
- 随后修正图纸得到`44934b416fcf1a9a5f97610b37bedaa5b7aaed471b458587d3c43668a4487a0a`，
  但485附近被“最终目标已登记，不允许草稿纠正”拒绝。
  中间另有一次参数拒绝、一次从空字段组参数导致的Schema拒绝；日志不含完整调用源码，
  不据输出null猜测它是服务端丢图还是脚本用了错误字段。
- 16:56:22（493附近）模型报告blocked并要求主人重设目标。施工没有开始；
  1/686是实际现场原已有的匹配格，不是本轮已建一格。
  日志前部认证/Realms报错后模型与工具仍正常运行，没有依据将此次卡住归因于登录或模型接口。

## 根因

床的头脚与朝向确实矛盾，施工拒绝这张图是正确的。
插件的问题是目标登记只核完整稳定现场及计数，允许unsupported格作为未完成部分，
未检查与施工登记同一套双格结构约束。
于是目标先接受一张确定无法按原版放置的图，施工再拒绝，而正确的目标冻结规则已经生效。
本轮把这一项确定的图纸错误挡在冻结前，不通过开放已登记目标换图来绕过。

## 只读参考

开发环境只读副本的相关机制复核：

- `numen/common/src/main/java/com/dwinovo/numen/tools/work/BuildApi.java`：
  对外图纸、查询、施工和门/床主格表达。
- `numen/common/src/main/java/com/dwinovo/numen/tools/BuildOps.java`：
  diff与place从同一plan消费设计解析、放置坐标和变化集。
- `numen/common/src/main/java/com/dwinovo/numen/build/Canvas.java`：
  主格与另一半占地在设计层处理，不等执行后才发现目标互相覆盖。
- 同目录`BuildStates.java`及`task/build/BuildTaskRecord.java`：
  主次半、原生物品动作和一次材料口径，床头独立放置会产生多余格的问题。
- `agent/src/main/java/com/dwinovo/numen/api/agent/goal/GoalSteward.java`：
  长期条件、有限工作和停止/迟到评估的隔离。

学习查询和施工共享设计解释、在动作前明确成对占地以及失败回到可纠正阶段。
参考以主格表达并排除次半，本插件已有完整两格目标和原生一次放置；
本轮不迁移删除次格的表达，也不自动推断床的转向或覆盖原图外位置。
不复制/翻译参考代码、注释、文案，不迁移另开模型判完成或直接写世界的分支。

## 实现

BuildObjects.validation复用problem对完整规范图纸检查，生成object_validation：

- valid与invalid_cells计完整目标的非法格数，保留两半在完整目标中。
- issues最多4条，issues_omitted明确省略数。
- 样本含世界位置、block、作者属性、reason；声明足够时含world_facing及expected_partner，
  后两者使用目标rotation后的世界方向/位置，作者属性仍明确为旋转前。
- 只诊断门/床的显式属性和成对一致性，不新增美观、房型、材料或距离禁令。

BuildSurvey普通、工作与验收模式都给同一诊断。
普通build_diff保持只读查询成功，仍可保留候选短引用；verify_only在非法配对时拒绝为
INVALID_BUILD_TARGET，不能生成可接受的初始目标证据。
BuildSite.begin也沿同一诊断，床错误反馈不再只教门的half/facing。
AgentRunner把诊断投影到直接工具历史，内部Lua也保留；目标观测保留同次身体与配对证据，
并拒绝成功信封中明确非法的标记。WorldGoal在核心再拒绝已明确非法的配对证据。

AgentLoop先核原身体、维度和存活，只在尚未登记目标时将INVALID_BUILD_TARGET交回模型：
纠正候选、重新查询实际修订、在同一任务登记，不补模型/动作/阶段预算或重开任务。
未登记门槛仍禁止以物品目标或纯文字完成，取消、换身体、已冻结目标和未知现场规则保持。
不自动纠正已冻结旧目标或修改玩家恢复文件。
知识首页与工具/常驻提示明确床头=床脚沿facing一格，并说明先改候选再登记。

## 验证

三项新回归在旧码失败，`build/bed-goal-before-20261026.log`记录43秒：
后页错误首页无诊断、核心提前结束纠正机会、实际Survey→Runner链接受非法床。
实现后`build/bed-goal-focused-20261026.log`记录相关专项45秒通过。
最后强制全量与JAR指纹见STATUS顶部。

覆盖四种旋转的世界诊断、后页错误、32格全计数/4样本/20KB保护、
普通查询与verify_only的不同职责、合法床仍原生一次动作/两格验收、
两种服务端回执经实际Survey→Runner→Loop的登记拒绝与同任务纠正、核心防伪完成、
错误身体和取消迟到。原已登记目标不可纠正、未加载/缺材料和原施工回归继续保留。
世界/身体、网络与原生放置回调仍受控，不代表真实模型必能一次改对图纸或完整686格施工已验。
只改插件；宿主、连接器、参考与玩家实例只读，没有安装JAR或启动游戏/服务器/宿主/真实模型。
