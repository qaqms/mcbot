# N3 空间事实、保护与实际返程记录

本卡只改本体。参考开发环境中的只读副本，不复制或翻译代码、文字或资源；
未运行参考测试。独立短任务、Lua及长期阶段继续使用同一服务端动作和事实入口。

## 机制核对

沿参考的route/Planning、nav/Terrain/Trip/Journey/RouteQueries、
build/Built及CellRouteGameTests核对实际地形、路线前提、实际改动、停止与成品保护。
学习的是实际记录供普通路线重验及保护、停止不抹掉部分效果。
参考没有在本轮核对中发现与本体完全相同的区块版本失败索引或普通返程账，
不能把本体的新设计写成对参考现成功能的移植。

## 共用实现

- TerrainVersionMixin记录LevelChunk实际setBlockState的有效变更；卸载也失效。
  每区块惰性UUID与单调版本，TerrainSnapshots仅getChunkNow，不为观察加载区块。
  status最多81个区块列；find_resource在扫描前后观察，声明spatial_stable。
- SpatialFacts供任务内WorkFailureMemory和同程序BoundedProgram读取。
  已知同维度重叠区块确有变更才允许重评；未知或缺失本身不补失败次数。
  挖掘按目标附近区块筛证据；移动使用原观察邻域。
  同程序使用完整status的已观察邻域变化，未实现精确到每条路线依赖格的索引。
- ExplorationRoute失效变更/缺失版本的覆盖；保留原tried/visited/attempts。
  BoundedSearch在跨页版本变化时停止，不重启查询或补页数。
  gather仍按当页新鲜候选行动，并共用覆盖失效；它不把跨页候选拼成稳定快照。
- WorksiteProtection供搜索/缓存/活体复核共用工位、方块实体、当前目标及直接地板保护。
  每任务64个工作目标，满后拒绝新增，不淘汰旧保护。显式挖掘不等于自动开路。
- NavigationTrail按身体/任务/世界记录实际接地位置与实际挖放，各256条。
  不以搜索节点当实际到达；断续观察标缺口，满后保留原记录并声明截断。
  回执给首路标及最近15条、最近16次编辑与省略数。
  move_to return_waypoint须当前任务ID、相同坐标、exact/walk_only及原范围/地下约束，
  新鲜支撑和脚高一致后仍由原PathTask导航。原挖放/探索/模型/许可/时间账不重开。

## 边界与验收

return_guaranteed始终false。普通路标不保证零编辑返程、历史整条路线仍通或反射避险轨迹完整，
不跨任务/退出复用，也没有自动倒放路线。永久成品实例保护留B4，不宣称任意结构依赖可证。
版本钩子不涵盖绕过LevelChunk入口的模组直接调色板写入或方块实体内容变更。
本轮未创建真实ServerLevel、注入Mixin或启动游戏/模型，原生事件顺序与实际运动待补证。

旧实现的静止身体地形变化回归先复现1失败，见build/n3-before-20261017.log。
专项两轮与强制全量见STATUS；实际执行生产失败账、Lua/runner、搜索/覆盖、
几何保护、返程拒绝及有界记录。映射反射验证setBlockState描述符及实际回调逻辑，
不冒充原生注入已验。原预算、许可、取消和迟到隔离保持。
