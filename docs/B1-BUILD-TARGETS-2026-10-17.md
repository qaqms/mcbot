# B1 建筑目标、只读差异与材料

本卡提供建筑共用数据和只读核对，不执行批量施工。B2再接近身有限施工，
B3组合多站位，B4设计及实例持久记录，B5导入/复杂对象；L1外层多类目标验收仍待实施。
短任务与长期阶段可直接或从Lua调用同一build_diff；后续身体工作复用BuildTarget、
BuildInventory及BlockPlacement.assessDesired，不另建材料依赖规划器。

## 参考机制链

只读核对开发环境中的参考副本，无Git元数据，不虚报参考提交号，未运行其测试。
不复制或翻译代码、注释、文案或资源。沿以下链路理解关联条件：

| 位置 | 学习点 | 本体取舍 |
| --- | --- | --- |
| tools/work/BuildApi、tools/BuildOps | 输入转统一目标，差异和执行共用判据 | 本体用结构化工具；查询与后续施工分开，无新命令语言 |
| build/Design、Placement、Changes | 稳定锚点、旋转、真实世界差异 | 目标内容修订由规范化内容生成，不靠名称；不认领已存在格 |
| task/build/BuildSurvey、BuildCellRules | 近身工作、阻挡、支撑、未知及缺料 | 当前页复用真实原版预检；未评估和未知明确分开 |
| BuildInventory、BuildTaskRecord.materialCount、BuildLedger、tools/BuildBill | 36槽、组件、全目标剩余量、多格与多次动作计费 | 只分配原放置核心已支持的普通单格；其他要求声明不支持，料单不假全 |
| BuildCompanionTask的endPass/rescan/finish/stop/cleanup | 局部批次、实际扣料、自然更新、中断与真验收分别处理 | 本卡只定义事实契约；B2执行后须重新核世界，不直接写状态或延迟邻居更新 |
| modules/build.lua、BuildGameTests、BuildTaskRecordTest | 差异驱动组合、支撑延后、无进展、已满足不重复、远处/缺料/取消 | 模型选阶段、Lua有限组合；不引入参考pcall或自动补料链，不把跳过视为完成 |

参考的宽松动态属性、按描述名替代、直接setBlock及跳过计完成均不采用。
本体仅忽略输入中从未要求的属性；明确写出的含水、朝向、半部或状态必须匹配。

参考内容指纹（SHA256，仅标识所读内容）：

| 文件后缀 | 指纹 |
| --- | --- |
| task/build/BuildSurvey.java | `cd61c14f4ffeed2ae94cff5ea9702cb144ad8343a480c50e059dafed1121d2b9` |
| task/build/BuildInventory.java | `1cad5ede1608a5bbbd6f808c6085813c5a22217420bf58cda502b5f3c13828f9` |
| task/build/BuildTaskRecord.java | `ce8091f75f2fbb6f8f38cd9a8c5dd832c60c5c16d92c35c57e82e76aab119145` |
| tools/BuildOps.java | `c23852f040a884134f3ede020eeeb325d68d6ecb884057e65de12ca41d8f82a4` |
| modules/build.lua | `849f62d7a25325196c4e8483d1f043269551309a991ffaae208c2570a30b1c5d` |

## 输入与身份

build_diff只接受target、offset、material_offset、expected_revision。
target.anchor是整数世界坐标，rotation默认0，允许0/90/180/270顺时针；
cells或fill恰选一个。cells各格相对锚点，fill.from/to为闭区间相对范围，
同一个填充表示退化的线、平面及实心体，Lua可自行生成其他有限格集。
最多128格，各相对轴±32，绝对XZ限29999984、Y限-2048..2047；
实际世界高度/边界仍在查询时检查。生成前检查体积，不接受重复坐标。

properties为最多8项的{name,value}列表，名字/值各32字符，按真实Block注册表校验，
拒绝重复属性、错误值、未知方块、额外字段、NBT/组件/实体载荷。
未指定格不属于目标；block=air是明确清空要求，但查询从不自动挖掘。
坐标与状态一起旋转。回执authored_properties/actual_properties使用旋转前的作者坐标系，
properties_frame明确标注；不把默认朝向偷偷加入作者要求。

target_revision是按完整目标的锚点、旋转、排序格与显式属性规范化后的SHA256。
输入顺序或隐式minecraft命名空间不改修订；位置/朝向/要求改变则修订不同。
非零明细或材料偏移必须携带expected_revision，不匹配在访问身体/世界前拒绝。
这不是持久设计/建筑实例身份，改变修订也不清任务失败账、范围、探索或任何原预算。

示例（仅查询，不施工）：

```json
{
  "target": {
    "anchor": {"x": 100, "y": 70, "z": 20},
    "rotation": 90,
    "cells": [
      {"x": 0, "y": 0, "z": 0, "block": "cobblestone"},
      {"x": 1, "y": 0, "z": 0, "block": "oak_log",
       "properties": [{"name": "axis", "value": "x"}]}
    ]
  }
}
```

## 差异、材料与边界

- BuildSurvey每次重新读取全目标，counts是本次总数，不累加多页。
  分类包括satisfied/missing_material/needs_clear/needs_support/out_of_reach/protected/
  unknown/unsupported/state_conflict/blocked/not_assessed/out_of_scope及ready。
  already-matching且支持的格不再要料；已有错误状态单列冲突，不自动拆放。
  不支持格即使当前类型匹配也不当本体可验收完成，matches_actual单独报告。
- cells每页16格，next_offset=-1为末页；只有当前页做近身放置预检及分配。
  页外可候选格为not_assessed，不拿抽样代表全图可施工。
  原工位、方块实体、当前工作格和直接地板复用WorksiteProtection，读取不加载区块。
- BlockPlacement.assessDesired复用单放/路线的guard、36槽、真实接触、原版落位、
  生存/碰撞检查，再验证原生拟放状态符合作者要求。最多六面选择，不设置方块状态。
  每页最多96次，现场核对协作时间帽50ms；不能中断一次原版回调，
  输入解析、有限料单输出和尾部版本观察仍有各自固定数量帽，不宣称严格主线程50ms保证。
  耗尽声明budget_exhausted及not_assessed，原探索/模型/动作/许可预算不增加。
- 材料每页16种，material_next_offset独立续查，material_types给全量种类数。
  design_required为已知计费规则的整目标量，required为已知未满足格需求下界，
  available只计36槽中与默认材料组件完全一致的物品。
  allocated只为当前明细页成功预检分配，跨页不累计预留，consumed始终0。
  required/available/allocated/consumed互不替代，已满足格及装备/副手不重复收费。
- 普通方块、原木、单层半砖、楼梯、工作台、炉类沿原投影支持范围。
  双层半砖明确需要2份但当前多次原生动作未支持；床/门等多格、
  流体/明确含水要求、特殊物品和复杂组件不承诺完整材料或施工。
  不从每格1件猜多格成本，不引入免费物品；未知/不支持使material_bill_complete=false。
  料单已知部分仍可读，不能把缺行当0需求。
- 目标涉及区块的版本前后稳定且全已知，全部目标支持且真实状态匹配，
  才允许goal_satisfied=true。原生观察钩子未知或中途变更时拒绝稳定验收，
  即使counts.satisfied是当时的已观察数量，也不当最终稳定证据。
  此字段没有自动登记、降低或完成外层set_goal；L1再接建筑目标生命周期。

只读/身体反射占用仍可查询，实际任务身份与服务端白名单照旧；
Lua使用同一远端入口、原子预算及取消/迟到隔离，风险标记保留不完整证据。
查询不占施工控制、不移动/转头/扣料，不预留材料、修改保护集合或创建依赖规划器。
批量动作、清障排序、支撑施工、临时工程、持久实例及导入均留后续卡。

## 验证

旧代码先复现缺少build_diff契约1失败，见build/b1-before-20261017.log。
两轮专项运行实际目标/注册表/旋转、Inventory/ItemStack组件、共用放置预检、
runner/Lua及dispatcher门；世界读取、原生上下文结果、网络、模型均为受控边界。
覆盖材料分页/分配、不支持/未知/预算、外部变更、版本失效、取消晚到及原只读控制。
最终强制全量/XML/候选与实机待验见STATUS；未创建真实世界或运行原版BlockItem.place，
不将参考GameTest阅读、受控native回调或编译成功写成实际建筑已验。
