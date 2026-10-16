# N2b 放置工作到达与点击前提

本卡承接已独立验收提交的N2a，实际构建/XML与候选以STATUS顶部为准。
只改本体，宿主、协作者连接器、开发环境中的只读参考副本和玩家实例未改；
未安装或启动游戏/服务器/真实模型。本轮到N2b收尾，不开始第三张卡。

## 参考机制与取舍

沿参考api/entity的Reach、Faces、Replaceable及PlacementTest，pathing/search的
Goals.place、plan/Draft.place与placeInSight，以及task/build/BuildSurvey核对：
几何够距、目标格占位、可贴面、射线首个命中、真实落位、查询与执行同源、
累计地形变化和有限工作集必须关联考虑，不能只移植“走到附近”。
读取参考测试不是运行通过，未复制或翻译参考实现、注释、测试正文或参数。

参考部分施工采用直接状态写入，放置目标只要求够距且不占格；
本体保留生存BlockItem.place，因此工作终点还须通过原版真实点击面和精确落位预检。
不把参考的宽松建筑到达条件直接用于生存放置，不引入建筑专用寻路器或依赖规划器。

| 参考文件 | 本次只读SHA256 |
| --- | --- |
| pathing/search/Goals.java | 1f923175d3e18cc877cb5fdbfb9e5e5cac5329b1a4a57b0e886c83072623b221 |
| pathing/plan/Draft.java | bb4ba5cd20538c1402f5d1d081b31588a9f2f3ea3bd6c439ecca366fd67038e5 |
| api/entity/Faces.java | 9cf2b00b5c158912081da39e68fb8fe041d6340fcf87afa8c0ef2c3b89a1fcba |

## 共用入口与真实效果

- place_block查询增加stand_positions：原目标的候选脚格，最多6个，可附feet_y。
  alternatives仍表示改选另一个目标格，最多6个；二者不混用，不自动移动或改目标。
  候选复用WorkPositions的45格范围、最多24个工作检查，无建议不证明无路。
  原75格目标替代查询帽保持，checked_positions仅统计原目标及替代目标检查。
- move_to增加place_item及可选face，必须exact=true、arrival=work并有work_target，
  外层x/y/z与work_target一致；不与clear_obstructions=true组合。face默认up。
  没有place_item时保持原挖掘工作契约，孤立face拒绝；非法输入在访问身体前拒绝。
  缺料或不支持投影在消费路线许可前停止。
- PlacementWork把原目标、材料与面编为现有NavigationGoal，仍走DigAStar/PathTask。
  用N2a真实脚高、身体尺寸和净空构造假想眼睛，整个身体不得占用请求目标格，
  即使最终放置形状为空或只有半格，也不能将站在目标格当工作到达。
- BlockPlacement.assess/assessAt和查询、单格执行共用预检；真实BlockAim轮廓、
  射线和身体实际交互距离选择接触点，原版BlockPlaceContext判准确落位与可替换。
  只读方向上下文不移动身体，不覆写getClickedPos/canPlace来强制落点。
  墙面/地面两用物品的实际查询经Mixin invoker调用原物品状态选择器后再检查占位，
  不再绕过公共Site检查；最终仍由原版place执行并核对真实变化/消费。
- 导航成功终态重新用实际身体/当前库存/世界评估，增加work_kind=place、
  can_work_here及placement_preflight；预检失败保留实际移动/挖放数据，
  原取消/失败不评估、不放置。到站只表示当前可工作，未执行place_block。
  Lua可查询、导航、等真终态、再调用原单格动作，无需新增模型回合或自动动作。
- DigSampler.canPlaceFrom贯通memo、TerrainEdits、PathTransition的抽象与真实形状分支。
  路线支撑在本步清障后、添加本步支撑前检查真实放置前提，不能拿待放的方块当锚点。
  搜索、活体清单和逐步复核同源；生产仍用PathMaterials与BlockPlacement.placeAttached，
  六面有限只读试探后只执行一次，失败已有消费/变化时不再换面重试。

## 保守边界与预算

假想站位仅支持原版普通Block、原木柱、半砖、楼梯、工作台和炉类，
并拒绝BLOCK_STATE组件；多格、特殊方块物品、模组形状或实体相关状态不预测。
这些限制只针对新工作投影，实际单格place_block保留原支持范围并做原生最终验证。
站位需满足实际世界的noCollision；不能把还未清空的空间当实际安全站位。

原版放置上下文仍读实际Level，不能假装它读取TerrainEdits。
目标及相邻一格内有尚未落地的计划挖放时，投影明确拒绝并要求实际完成该段后重查。
远处累计编辑仅用于射线投影；未知不因计划移除而变为已知空气。
因此依赖连续待放锚点、未落地邻域清障的长路线可能保守失败，
不承诺任意边缘桥接、探身、搭柱或完整建筑施工，后续能力仍须独立验收。

查询不取得身体控制、不转头/移动/消费，place_item导航仍是修改工具，query字段不能变只读。
短任务、长期阶段和后续持续行为共用原move_to/BlockPlacement及C1控制、F1事实；
尚无持续跟随/自卫触发，不据此宣称该入口完成。
8000展开/32768状态/400ms累计/6ms切片、挖放/材料/模型/程序/探索/权限预算均不增加，
取消、失败账和最终目标验收不变，不自动找料/升级/重试。

## 离线验证与未测范围

旧实现先复现原目标站位与替代目标无法区分的一项失败：
build/n2b-before-20261016.log，20项中1失败。
focused1验证初始接线；补齐16项新回归后focused2通过，包含真实注册表/shape/clip、
生产A*选择目标格外站位、分数脚高/距离一致、支撑未知或变化、
共用预检的材料/落位/占位拒绝和一次消费、邻域计划拒绝，
生产路径两分支及memo/累计前缀、参数/权限、实际runner/Lua真终态及取消迟到。
实体、原版上下文和执行回调为受控边界，不把注入失败回执说成真实实体动作。

完整build命令、XML数量和候选见STATUS。Mixin注入、原版BlockPlaceContext/
BlockItem.place、身体travel、多人动态占位和真实模型阶段选择仍须游戏内验收。
API签名/编译/JAR核对不能代替原生放置测试。
相关原目标/站位/半砖/墙面接触/取消场景并入REAL-GAME-RETESTING。
