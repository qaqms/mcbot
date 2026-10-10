# 工具契约与手册

> 服务端白名单（`ToolRegistry`）是真源；`ClientToolDefs` 只是给模型看的描述。
> 两者不同步时以服务端为准——未注册的工具会被闸③以 `DENIED:未知工具` 拒掉。

## 1. 契约

```java
interface ServerTool {
    String name();
    Result run(CompanionPlayer c, JsonObject args);                  // 同步：一拍出结果
    default CompletableFuture<Result> runAsync(c, args, scheduler)   // 跨 tick：交给任务槽
}
record Result(boolean ok, String feedback, JsonObject data)
```

- **feedback 是给模型看的人话**（教学式回执，DESIGN §7）：失败必须带"下一步怎么办"，
  模型靠它改策略。data 给人/UI/neko 播报。
- **每同伴单活跃任务槽**：同步/异步动作统一占身体资源，目标区域冲突回 `BUSY`；只读查询不占锁。
- 所有执行都发生在服务器主线程、作用于**发送者名下**的同伴（owner 校验在闸③）。

## 2. 在册工具（15 服务端 + 2 本地，真实行为仍待验）

> 条数以 `McbotMod` 里 `tools.register(...)` 的**实际注册数**为准（现 15 个），
> 本表就是那 15 个 + 客户端本地的 `ask_owner` / `workflow`。别拿本表数字反推代码。

| 工具 | 参数 | 型 | 说明 |
|---|---|---|---|
| `status` | `details?` | 同步 | 最新位置/生命/饥饿/背包占用/主手及实际任务计数；details=true包含背包/装备逐槽明细 |
| `find_resource` | `targets,r?` | 同步 | 方块ID/#标签定向搜索；r默认8/最多16，最近优先4096格采样，最多16候选，明确未知/撞帽 |
| `inspect_block` | `x,y,z,offset?` | 同步 | 6.5格内完整加载方块/本体容器明细，每页24槽；拒锁/未展开战利品，不打开GUI |
| `inventory` | — | 同步 | 完整 36 格背包的槽位/物品 ID/数量/耐久、选中主手槽及 7 个装备映射槽；只读，忙时也能查看 |
| `equip` | `slot`(0-35 整数) | 同步 | 仅切换主手：快捷栏 0-8 直接选中；背包 9-35 与当前主手槽交换整个物品堆栈；忙时拒绝 |
| `craft` | `item,count?,query?,recipe?` | 同步 | 实际普通合成配方；count 是至少所需成品数 1-64，query 只读；2×2 随身，3×3 需 5.5 格内工作台；整批预检，失败不改背包 |
| `smelt` | `x,y,z,action?,input_slot?,input_count?,fuel_slot?,fuel_count?,slot?,count?` | 同步 | query/load/take；三类原版机器三槽与真实计数，预检后移动物品；实现/离线完成，真实烧制待验 |
| `scan_area` | `r`(1-32，默认16) | 同步 | 附近实体 + 可行动方块分层摘要（classify 词表 container/ore/**rock**/workbench/farm/hostile）；坐标一律**绝对** `@(x,y,z) d距离`，首行含同伴位置+八向朝向；客户端随指令注入准星目标（`[我此刻盯着]`）。目标=直接可下指令；泥土沙**不是**目标（材料走 place/transfer 显式指令） |
| `break_block` | `x,y,z` | 异步(≤60s) | 实时原版挖掘进度、采收门、裂纹；完成调用玩家破坏入口处理耐久/掉落，回执核对实际移除与入包/留地量；真实动作待验 |
| `attack` | `entity_id,target_uuid?,max_hits?,authorization_id?` | 异步(≤20s) | 原地普通近战，指定实体编号+UUID或 hostile_nearby；默认1次、最多10次，满冷却/恢复后出手；中立/命名目标确认，拒绝玩家/宠物/同队及不安全横扫 |
| `collect` | `x?,y?,z?,r?` | 同步 | 坐标全部省略则自己脚下，r=1-12 默认 3；中心/物品均限身体6.5格内，尊重延迟/拾取目标；余量留地上，忙时拒绝 |
| `place_block` | `x,y,z,item,face?` | 同步 | 物品 ID、准确目标格；原版玩家放置上下文，face 默认 up，实际目标变化/消耗核验，忙时拒绝 |
| `move_to` | `x,y,z,authorization_id?,may_alter_terrain?` | 异步(≤3min) | **DigAStar**：改动世界先回 `NEED_CONFIRM`+清单与编号，主人确认后携带编号执行；旧布尔不授权。搜索预算 8000 节点/128 挖/放≤背包存量；重规划新改动重新确认 |
| `transfer` | `x,y,z,dir(in/out),item?` | 同步 | 6.5 格内未锁定有效普通容器，遵守槽/接触面与堆叠上限；允许部分搬运，余量留来源；忙时拒绝，熔炉类须用 smelt |
| `wait` | `seconds`(1-60) | 异步 | 站定等待（熔炉/作物节奏用，别拿轮询代替等待） |
| `ask_owner` | `text,authorization_id?` | **本地** | 普通问题沿用 text；路线确认携带服务端编号，实际展示服务端完整清单。120s 期限，明确批准后仍等服务端授权回执才续跑 |
| `workflow` | `steps,timeout_seconds?` | **本地** | 明确近身清单，1-12步/默认180最多300秒/请求量合计≤128；逐步等终态，失败/空搜索/缺条件/需确认即停 |

游戏内 `@bot 答 <文本>` / `@bot answer <文本>` 只用于回答当前最新的有效问题。
没有待答问题、问题已回答或已过期时提示回答未提交，不作为新任务执行；
普通任务仍从任务页、桥或不带回答前缀的 `@bot <指令>` 投递。

未上（DESIGN §5 规划中）：
`locate` 等——M5/M8 分批补齐；`navigate` 并入 move_to 升级，`wait_until` 并入 wait。

### 2.1 背包明细与主手切换

模型需要材料或工具信息时调用 `inventory {}`，从回执选择实际槽号，再调用
`equip {"slot":13}`。`status` 默认轻量体感汇报，details=true增加完整背包/装备明细。
`inventory` 的 feedback 列出所有非空背包槽、选中主手及全部装备映射槽，
未列出的背包槽为空；当前 AgentRunner 仅将 feedback 写入模型历史，不能只依赖 data。

data 包含 `selected_slot`、`storage_size=36`、`slots_used`、
`slots`（36 项，含空槽）及 `equipment`（当前版本 7 项）。
背包项含 `slot`、`item`（带命名空间的注册 ID，空槽为 `""`）与 `count`；
可损耗物品额外含 `damage`、`max_damage`。装备项的 `slot` 是
`feet/legs/chest/head/offhand/body/saddle`，`inventory_slot` 对应 36-42。
这两个新增映射槽不等于玩家可穿戴身体盔甲或骑具，`equip` 不接受任何装备栏槽。
主手由 `selected_slot` 对应的背包项确定。

回执不输出自定义名称、附魔明细或原始组件/NBT；物品 ID 展示最多 128 UTF-8 字节，
超长时 `item_id_truncated=true` 且 feedback 标明截短，可继续按槽位切换。
交换使用完整原 ItemStack，保留数量、耐久、附魔及其他组件，不拆分、合并、消耗或生成物品，
满背包也可交换。来源槽为空、参数类型/范围错误回 `DENIED:`；
数值形式的整数如 `13.0` 可接受，数字字符串、布尔、小数、溢出等拒绝。

`equip` 成功 data 含 `source_slot`、`selected_slot`、`swapped` 和 `held`；
背包交换时额外给出 `source_after`。feedback 明确报告新主手和原主手的新槽位。
工具切换后旧槽位可能已经变更，继续选择物品前应查看最新回执或重新调用 inventory。
长任务占槽期间返回 `BUSY:`，不会影响当前挖掘或移动。
专项离线与真实服务端物品工装见 DEVELOPMENT §3.2；玩家模型驱动与连接器联合验收仍待安排。

### 2.2 配方查询与合成

`craft {"item":"stick","count":5,"query":true}` 仅查询；
`craft {"item":"stick","count":5,"recipe":"minecraft:stick"}` 执行。
item 是产物 ID，recipe 是可选的配方 ID，两者不必同名；不填命名空间默认 minecraft。
count 默认 1，表示至少所需成品数，按完整配方次数向上取整：木棍每次 4 根，
请求 5 根执行 2 次、产出 8 根，不丢弃多出的 3 根，也不把已有木棍抵扣本次请求。
数字字符串、小数、越界、非法 ID、非布尔 query 均 DENIED。

读取当前服务器 RecipeManager，不维护硬编码配方，不缓存旧数据包。
支持实际类型为原版 ShapedRecipe/ShapelessRecipe 的普通有序/无序配方，
包括数据包的同类配方与材料标签。按配方 ID 排序，自动选择整批当前可完成的配方；
指定 recipe 时只检查该配方，产物必须匹配 item。
不自动递归制作材料，不执行特殊动态配方（染色/修复等）、自定义 Recipe 子类、
冶炼/切石/锻造。不模拟 GUI、配方书解锁、制作统计或成就事件。
自动查询最多 4096 个加载配方、32 个匹配候选，超限请指定 recipe；
每次至多 64 次配方执行。

2×2 内的配方可随身合成。更大配方需要同伴所在维度、已加载区域、距离 5.5 格内
的原版工作台；工具自动找最近工作台，查询反馈和 data 同时给坐标。
不远程使用工作台，不自动放置或挖掉工作台。缺工作台先 scan_area/move_to，
或显式 place_block 放背包里的工作台。
只使用 0-35 存储槽作为材料与存放位置，不消耗副手/装备栏、不改变选中槽。
长任务占槽时执行回 BUSY，query=true 仍允许只读查询。

整批在 ItemStack 副本中模拟：使用原版 StackedContents 匹配重叠材料候选，
以真实堆栈组件匹配 Ingredient，并再次用实际 CraftingInput 验证 matches/assemble。
每次按真实 getRemainingItems 处理返还物（如牛奶桶返空桶），按完整组件合并，
优先已有堆栈再空槽；全部容纳后才一次提交。
任何一批缺料或成品/返还物装不下，整批回退、背包所有槽保持不变，绝不落地溢出。
未参与制作的堆栈及装备保留组件、数量与耐久。

query 合法且找到普通配方时 ok=true，即使材料/空间/工作台不足；
必须看 can_craft，不能把查询成功当作已制作。
data 给 recipe、requested_count、batches、produced_count（计划产量）、
crafted_count（查询/失败为 0，执行成功为实际产量）、output_per_batch、
requires_workbench、workbench（存在时）、ingredients、can_craft、query。
材料项含 options、per_batch、required_count、available_count；options 最多展示 4 个，
超出标 options_truncated。不同材料项共享候选，available_count 不能简单相加；
实际能否制作以 can_craft 为准。
feedback 同时列配方、单次材料/匹配存量、次数/计划数量、工作台坐标和失败原因，
供下一轮模型读取；不序列化自定义名称或原始组件。
隔离专项与离线接线见 DEVELOPMENT §3.3；玩家模型驱动与连接器联调待验。

### 2.3 熔炉/高炉/烟熏炉（实现与离线完成，真实行为待验）

`smelt {"x":2,"y":90,"z":0}` 默认 action=query，只读三槽、实际烧制/燃烧计数、
配方、区块是否推进及等待建议。feedback 同时给当前原料预计剩余烧制 tick、可用燃烧 tick
和整批燃料是否够用。合法查询 ok=true 不代表机器正在烧制或已有新成品。
目标须在同维度 5.5 格内、已加载且是未锁定的原版熔炉/高炉/烟熏炉。

load 从背包 0-35 的 input_slot/fuel_slot 装料，至少提供一个来源槽；
对应 input_count/fuel_count 为 1-64，默认 1。同一来源槽先预留原料再核对燃料，
不能重复消费；组件不匹配、槽满或来源数量不足时不移动物品。
加入原料必须有该机器适用的普通配方、能容纳下一件产物，且有现存燃烧余量或可用燃料；
不满足时整次失败，连同同时提供的燃料也不移动。只补燃料可给空炉备料，
不要求产物槽可用；若机器已有原料，仍须有支持的普通配方，避免补燃料间接启动不支持的配方。
成功后仍应看 query 的状态，不把空炉备燃料当成点火。
take 的 slot=input/fuel/output，默认 output；count 为最多取出数量 1-64，默认 64。
背包装不下整次取出时保持两边不变；空产物槽返回 NOT_READY。
空原料/燃料槽返回 EMPTY_SLOT。合并按全部组件比较，先补已有同类堆栈再放空槽，
同时遵守物品与容器堆叠上限；只用 36 个存储槽，不借装备栏空间。
可以从 fuel 槽回收原版已返还的空桶/水桶，空桶本身不能作为燃料装入。
不同 action 的多余参数直接拒绝，避免错模式移动物品。

data 含 action、机器 ID 与 x/y/z、input/fuel/output 三槽的物品摘要，
burn_remaining_ticks/burn_total_ticks、cook_progress_ticks/cook_total_ticks、
fuel_ticks_per_item/available_fuel_ticks、needed_cook_ticks/fuel_sufficient_for_input，
以及 ticking、state、suggested_wait_seconds、loaded_input_count/loaded_fuel_count/taken_count。
有普通配方时另给 recipe/recipe_id_truncated、result_per_input、recipe_cook_ticks。
state 是 EMPTY_INPUT/NO_RECIPE/OUTPUT_BLOCKED/MISSING_FUEL/NOT_TICKING/COOKING；
COOKING 表示当前具备推进条件，不证明本次已经产生新成品。
首件剩余时间使用机器当前 cook_total_ticks，后续按当前配方估算，避免数据包重载时混用计时；
仅 COOKING 推荐 1-60 秒 wait。燃料/时间均为估算，低 TPS、返还容器或产物槽变满会影响整批完成。
不输出自定义名称或原始组件，配方 ID 展示限 128 UTF-8 字节并标明截短。

模型应按 inventory → load → wait → query/take → inventory 获取实际反馈。
烧制由原版机器推进，工具不生成成品；装料成功、等待结束和取消均不证明烧制完成。
取消 wait/agent task 不清机器，停止后续烧制须显式取回原料，燃烧余量仍按原版消耗。
长任务占槽时只允许 query，load/take 返回 BUSY。
暂仅支持原版普通烧制配方类且单原料产物数量为 1，拒绝多产物数据包配方。
不支持模组机器、GUI/成就/经验领取流程；实际机器行为仍需专项证明。
操作预检/物品保全、参数与 runner 取消接线的 28 项离线测试见 DEVELOPMENT §3.4；
离线夹具提供已返还桶，不模拟燃料生成桶或原版烧制，不替代真实服务端/连接器验收。

### 2.4 物品守恒与部分搬运

transfer 和 collect 可以部分完成。反馈的实际已搬/已捡数量和剩余数量是判断依据，
`ok=false` 可能已经移动一部分，不能据此假定两边没变化。
腾空间后仅处理留在来源的物品；取消或迟到回执不回滚已完成的同步移动，
应先重新查看 inventory，不能自动重投整个操作。

transfer 按来源逐槽搬运，先合并全部组件相同的堆栈再使用空槽。
背包来源/目标只用 0-35，装备/副手和选中槽保留。
拒收某项不阻断后续其他允许的物品；可选 item 按注册 ID 过滤，最多 128 字符。
坐标须是数值整数，dir 为 in/out；省略 dir 为 out 保留旧调用兼容，
模型 Schema 仍要求显式提供。非法方向、数字字符串、小数或越界整数均 DENIED。
data 含 moved_items、moved_stacks（发生实际移动的来源组数）、remaining_items、
partial 和 dir。没有匹配物品时 ok=true 但 moved_items=0，不证明目标已达成；
有剩余匹配物品时 ok=false，partial 只在已移动且有剩余时为 true。

普通容器须已加载、在当前世界边界和 6.5 格内、stillValid=true，锁定容器一律拒绝，
不通过此工具使用钥匙。当前操作目标方块自己的 Container，不自动合并双箱。
canPlaceItem/canTakeItem 和物品/容器堆叠上限参与移动；
WorldlyContainer 另检查同伴眼睛相对容器中心方向的最近面、该面的槽集合与进出权限，
不遍历其他面绕过拒绝。熔炉类仍须 smelt。
仅适用于遵守上述 Container 接口约定的存储容器；菜单独有规则、副作用、
任意模组 setter 行为和双箱/阻挡开启语义不在当前离线保证内。

collect 的 x/y/z 须完整提供或全部省略；r 为 1-12 数值整数，默认 3。
data 保留 collected，并加入 collected_count、remaining_count、partial；
反馈明细每类最多 32 项、使用注册 ID/数量/耐久，不序列化自定义名称或原始组件。
部分拾取时只将剩余完整堆栈写回实体，全部拾取才 discard。
单格挖掘和开路掉落共用同一入包/剩余量逻辑；C2 已改为接收原版产生的本次附近新实体，
余量写回原实体，不重复求战利品或生成掉落。collect 的身体局部范围与拾取延迟/目标规则
已由 C3 限制身体距离、实体延迟和拾取目标，不宣称原版 playerTouch 已接入。
专项离线回归和真实行为待验范围见 DEVELOPMENT §3.5。

### 2.5 挖放语义（C2 实现/离线完成，真实动作待验）

break_block 与 move_to 开路共用 BlockMining：预检 5.5 格、执行期 6.5 格，
要求当前生存模式、世界交互规则允许、目标及 3×3×3 邻域已加载，拒绝空气、
液体/含水方块、不可破坏目标及不具采收资格的主手。
与原版允许错误工具慢挖但无产物不同，本工具直接 WRONG_TOOL 且不破坏目标。
背包明细后先 equip 正确工具；规划同样将无采收资格的方块视为不可挖。
冻结目标状态、主手选中槽及完整堆栈，每 tick 检查变化并使用
BlockState.getDestroyProgress 的实时增量；零硬度的正无穷进度可立即完成，
无效进度连续 10 tick 停手。失败、完成、取消和路径重规划清理当前裂纹。

最终只调用一次 ServerPlayerGameMode.destroyBlock，让原版执行采收/耐久/方块钩子。
其返回 true 不保证实际移除，因此还要核对目标原方块是否仍在。
移除失败不自行求战利品、不吸取新实体；原版仍可能已消耗耐久，勿自动重试。
成功移除后只处理目标 AABB 向外扩 0.5 格内、本次动作前不存在的物品实体；
旧实体不动，完整入包才删除新实体，部分入包只回写完整组件余量。
这是有界的附近新实体观测，不是所有掉落的精确归因：邻格钩子产生的物品可能在内，
远处/延迟生成的模组掉落不在内，经验球不自动收取，不宣称 playerTouch。
data 含 removed、reported_success（已观察移除时）、collected_count、remaining_count、
collected/leftover（各最多 32 组）；feedback 同时给实际数量和注册 ID/数量/耐久明细。
溢出允许 ok=false 且 removed=true，留在地上的物品不能再次按原目标整次重挖。

place_block 的 item 是物品注册 ID，不是方块 ID；背包来源只用 0-35，
不交换主手或借用装备/副手。face 可选 up/down/north/south/east/west，省略为 up；
坐标须数值整数，数字字符串、小数或整数溢出拒绝，move_to 的共享坐标解析同样收紧。
以实际同伴、完整来源堆栈、指定面及当前朝向构造 BlockPlaceContext，
固定目标格，不把被占据且不可替换的目标自动转到邻格。
直接调用 BlockItem.place，原版负责朝向、支撑/碰撞、半砖合并、多格钩子、
方块实体组件和消费；不手工写默认状态或额外 shrink。
暂支持实际类为 BlockItem、BedItem、DoubleHighBlockItem、StandingAndWallBlockItem；
脚手架、告示牌及其他特殊子类拒绝，以免上下文重定向或专用交互越出当前边界。
邻域须在世界范围/边界内、已加载且允许交互，另核对 mayBuild/mayUseItemAt。
只有原版报告动作成功、目标状态已变且非空气、来源实际消耗恰好一件才 ok=true；
失败 data 给 changed/consumed_count，不假定零副作用或回滚；成功另给 placed 与坐标。
真实水中/替换格/半砖合并、多格、碰撞、朝向和组件落地仍需真实动作验证。

路径规划与执行共用 PathMaterials，只有无组件补丁的圆石、深板岩圆石、泥土、下界岩
计入 0-35 垫料预算；不自动使用重力方块、木材、工作台、贵重块或命名物品。
成功放置后还需确认真实支撑，否则停止移动；不因目标已占据就认定能站立。
开路溢出可继续路线并记录留地量；原版报告失败则停手，即使目标已观察移除。
路径终态给 removed_blocks/placed_blocks/remaining_items，按本趟实际执行计数，
途中失败也保留已完成改动，不把计划清单当作执行成果。
有限授权、缓存/重规划新增改动批准及统一资源互斥见下节，任务桥 v1.0 不变。
离线证据与范围见 DEVELOPMENT §3.6。

### 2.6 调度与权限（C3 实现/离线完成，真实行为待验）

同轮工具按顺序等待真正终态，ACCEPTED 只登记长任务，不放行下一项。
取消会给尚未派发的调用补齐历史回执，迟到事件不能继续派发。
同步动作、跨 tick 任务共用身体锁；挖放/容器/机器占目标及邻域，拾取占扫描区域，
路径在执行前原子取得具体改动邻域，冲突返回 BUSY，失败/取消/超时/身体替换统一释放。
锁只协调本插件动作，不锁真实玩家、原版机器 ticker 或第三方模组，也不是模组钩子沙盒。

任务入口支持明确的 `[只读]`（或 `[read-only]`）文本标记；若指令包含已列出的只读限制短语，
也只会收紧权限。策略在主人投令时计算，不读取模型的 read_only 参数。
只读允许 status/inventory/scan_area、craft query=true、smelt query；其他身体或物品动作拒绝。
普通任务保留现有原子操作能力，工具仍按本次具体坐标、身体局部范围和原版规则校验。
这不是通用自然语言授权解析器：未识别的语言/否定表达不能声称已得到可靠语义解析；
需要确定只读边界时使用明确标记。只读任务不能通过反问升级，须另投新的普通任务。

服务端 ActionPermissions 将任务绑定发送者、同伴、task_id 和维度；
变更任务、任务结束、取消即撤销方案。旧客户端没有 task_begin/task_id 时，
仍可只读查询，修改工具拒绝；需与本轮客户端一起更新。这是内部 C2S 的收紧，
外部 REST/MCP/SSE 的 v1.0 字段、事件和终态不变。

路线确认流程：move_to → NEED_CONFIRM → ask_owner(text,authorization_id) →
主人明确回答「确认」→ 服务端批准回执 → move_to(x,y,z,authorization_id)。
授权一次使用、180s 过期，绑定目的地和每一格的操作/完整 BlockState。
确认问题展示服务端生成的完整坐标/方块清单，忽略模型替代文案；未明确批准或超时不授权。
最多256个独立改动，完整清单12KB上限，超限要求缩短路线，不批准截断清单。
缓存只存节点，不存权限；复用/重规划重建清单逐项核对。
新增位置、改动类型、原状态变化或已经执行后被补回的格子，都不能借旧许可再次操作。
逐格挖放前复核，下一落脚格的脚/头/支撑变化先重规划，不直接进入新障碍。
may_alter_terrain 保留类型兼容，但 true 不能批准世界改动。

collect 的中心需在身体6.5格内；实际候选同时在请求球体和身体6.5格内、已加载且存活，
没有拾取延迟，且 target 为空或匹配同伴 UUID。getOwner 是投掷来源，不能代替 target。
通过只读 ItemTargetAccess 取得原版私有 target；仍使用 C1 的实际入包/余量写回，
不调用 playerTouch，不宣称已验证统计/成就或真实 Mixin 加载。
离线范围、测试与真实待验见 DEVELOPMENT §3.7 / STATUS。

### 2.7 有界近战（C4 实现与离线验证，真实战斗待验）

先 scan_area 取得 entity_id 与 target_uuid，再用
`attack {"entity_id":47,"target_uuid":"00000000-0000-0000-0000-000000000047","max_hits":2}`。
整数编号必须为正并配合规范 UUID，避免旧编号重用误伤；
或者 `attack {"entity_id":"hostile_nearby"}`，从身体6.5格查询盒内最多64个最近候选中，
选择一只实际原版攻击距离内、可见、未命名且未受保护的 Enemy。
此选择只发生一次；后续锁定原实体引用/编号/UUID，不追击、不换目标或自动打分裂产物。
max_hits 是**调用原版攻击的上限**，默认1、范围1-10；整个任务400tick（20秒，低TPS会更久）。
ACCEPTED 不代表伤害已发生，等真正终态后再决定，不能重发或轮询。

每tick复核当前身体/维度、实体引用/身份、存活、保护规则、原版攻击范围及局部6.5格限制、
世界边界/已加载邻域/交互规则、视线、完整主手/选中槽及资源租约。
加载检查最多4096格，拒绝过大实体邻域，不为了视线强制加载区块。
等待 `getAttackStrengthScale(0)>=1`、目标 invulnerableTime<=10、
`cannotAttackWithItem(held,0)=false` 后，只调用一次 Player.attack 和主手 swing；
不手算伤害、不直接 hurt/setHealth/shrink。原版处理伤害、耐久、附魔、击退及统计。
本次原版回调后的主手耐久/破损可继续接受，外部主手变化则停手。
穿刺/动能武器与重锤暂拒绝；剑的目标 AABB 膨胀(1,.25,1)内有其他活物则拒绝，
即使当前运动本来可能不会触发横扫，也不冒险扩大本次目标范围。

玩家（含主人/同伴）、非Mob实体、同队、已驯服动物/马或有 owner reference 的生物始终拒绝。
未命名 Enemy 可直接执行；其余中立或命名Mob先返回 NEED_CONFIRM 和完整服务端清单。
沿用 ask_owner/authorize，明确批准后重新携带相同编号、UUID、max_hits及authorization_id调用；
hostile_nearby 不能携带批准编号，不能把具体目标许可改成自动选择许可。
许可绑定任务/身体/维度、目标编号/UUID/类型、Enemy/命名标志及挥击预算，沿用180s/一次使用。
中途身份或命名分类变化重新检查；只读不能升级。
实体UUID资源锁独立于位置，目标走开不能让另一同伴认领同一只；
移动后的目标邻域仍须原子扩展区域锁，不与本插件其他动作抢区域。

data/feedback 同时给 strikes/max_hits、observed_health_loss/observed_absorption_loss、
entity_id/target_uuid/target_type、最后记录的 health/absorption/target_dead，以及 observation_known。
strikes 表示已经进入原版调用的次数，不等于命中数；生命/吸收减少是前后观测差，
不做独占伤害或击杀归因。上限完成可以 ok=true 且目标仍活着；死亡则停止，不收掉落。
没有观测到生命或吸收减少则 ATTACK_FAILED，停止剩余挥击，不能自动重投。
取消/超时/异常通过 interruptedResult 保留此前出手计数，释放锁；原版回调抛错时
observation_known=false，最后一次结果未知，可能已有副作用，不回滚。
客户端主动取消时仍按既有立即补账/丢迟到结果规则处理，服务端保留部分结果不代表
它会覆盖已经完成的客户端取消回执。scan_area 的 entities 字符串兼容保留，
另加有界 entity_targets（最多20项），feedback 也含同一编号/UUID及保护/确认提示。

假连接不驱动 Player.tick；CompanionPlayer 的现有世界tick补两项近战时钟，
换主手物品类型重置，原版 onAttack 后持续充能；耐久/组件变化不重置同类物品时钟。
LivingEquipmentAccess 调原版 detectEquipmentUpdates 同步装备属性，不复制属性规则。
当前仍未开启完整 doTick/玩家物理/自动 playerTouch，保留既有无敌身体；
自动防御、远程、追击、自动换武器/进食、受伤/死亡复活不在本卡。
Mixin实际加载、真实属性/冷却/伤害/附魔/耐久/横扫、玩家/模组并发与真实停手待安排，
原版/数据包/模组回调可能产生其他副作用，租约不是沙盒。离线范围见 DEVELOPMENT §3.8。

### 2.8 状态与任务资源感知（C5）

每轮模型请求前，宿主调用status details=true取得服务端同一线程中的身体、背包/装备、
游戏刻和调度槽观测；未知/失败/超时观测停止该任务，不复用旧快照继续猜测。
运行期快照只附在本轮请求尾部，不写入会话历史或改写真实工具回执，不改变system前缀。
status的旧位置/生命/饥饿/占用/手持/着火字段保留；新增game_tick/task及可选inventory。
task包含busy；忙时另给elapsed_ticks/cap_ticks/progress。路径报已提交节点与实际挖放，
攻击报已出手数，挖掘报原版实时累计进度，wait报已等刻数；耗时不是完成率。

find_resource的targets须1-8个实际方块ID或已加载非空#方块标签，如oak_log/#minecraft:logs，
不是物品ID或自然语言。r为1-16整数，默认8；查询以身体为中心，最近优先采样最多4096格，
最多返回16个绝对坐标。仅getChunkNow取得的完整区块可读，未知/界外不读，不加载或生成。
data同时给samples_planned/samples_examined/cells_read/unknown/truncated/results_truncated/no_targets。
候选只证明采样格中存在方块，不证明露出、可达、采收资格或实际掉落；空结果不证明不存在。
scan_area同样给no_targets并明确分类遗漏木材，不鼓励挖掘探查或改变半径持续空转。

inspect_block只读6.5格内已完整加载的方块和其自己的Container，offset默认0、范围0-1023，
每页24槽；data给slot_count/slots/next_offset（-1末页）。锁定、失效、未展开战利品容器拒绝。
物品显示注册ID/数量/耐久，原始组件与自定义名称不出站；不搬物品/开GUI/展开战利品，
不合并双箱或读取整个存储网络。熔炉原料/燃料/产物的实时进度仍用smelt query。
纯MC物品/状态与生产工具回归通过；实际区块缓存/身体/容器世界行为仍待验。

### 2.9 有界流程组合（C6）

`workflow` 只协调现有工具，不是脚本执行器或自动规划器：

```json
{"steps":[
  {"tool":"smelt","args":{"x":2,"y":90,"z":0,"action":"load","input_slot":0,"input_count":3,"fuel_slot":1,"fuel_count":1}},
  {"tool":"wait","args":{"seconds":30}},
  {"tool":"smelt","args":{"x":2,"y":90,"z":0,"action":"query"}},
  {"tool":"smelt","args":{"x":2,"y":90,"z":0,"action":"take","count":3}},
  {"tool":"inventory","args":{}}
],"timeout_seconds":90}
```

坐标、来源槽和数量须来自当前观测；示例不是固定配方/烧制时间承诺。
允许 status/inventory/equip/scan_area/find_resource/inspect_block/break_block/place_block/
craft/smelt/wait；不允许 move_to/attack/transfer/collect/ask_owner/嵌套workflow。
近身采集用显式break_block坐标，不从搜索结果自动填参数、不移动或递归补材料。
下一步若依赖新结果（例如制作后的物品槽），结束当前流程再规划。

开始前校验整份参数/工具名单/数量/只读模式，错误的后半段不能先执行前半段。
最多12步，每步挖放最多一个显式目标；时限默认180、1-300秒，子步骤等候也计时。
制作执行count、装料input_count/fuel_count、取出count均须明确填写，
连同放置每次1件，请求量合计≤128。预算限定**请求数量**，不是原版材料消费或产量上限：
craft按完整配方取整且可能使用多格材料/返还物；break掉落和place多格效果由原版处理，
不裁剪实际物品或把请求数当进包量；原工具的配方次数/范围/条件帽继续生效。
没有数量契约的批量存取/拾取不纳入，未来须先补原子工具数量契约。

每一步走现有task_id和executeRemote，三道闸/ServerActionGate/身体锁和原版条件仍生效。
tool_result或真正job_event终态才推进，job_ack只转段等待，不进模型历史。
失败（含部分副作用）、craft查询can_craft=false、搜索no_targets=true、
机器无燃料/配方/输出阻塞/区块不推进或本批燃料不足、需确认即停，不重试/轮询。
授权编号仍由runner保存，需要确认时退出流程，让模型走既有ask_owner明确确认。

返回逐步真实反馈与terminal_steps/total_steps；ok=true仅表示清单各步取得成功终态，
不证明主人目标达成、装料已烧完或take拿满请求数，短取出量按实际回执汇报。
累计反馈超过12000 UTF-8字节不派发下一步，结果全文展示预算24000字节，
超长单条明确标未展示全文，不把截断内容当授权清单。
取消/重载/关闭/时限先停止推进、清子票并发cancel，保留已取得回执，在途效果未知；
迟到终态不能启动下一步。不回滚已执行动作，不熄炉或自动取回装料；
取消后必须按最新状态再决策。

AgentLoop另设当前任务累计三次空搜索上限（含workflow搜索）：
变半径/目标、插入status/inventory/inspect/wait均不重置，找到目标或实际物品进展可重置。
第三次空结果停止本轮尚未派发工具并补配对回执，FAILED汇报范围，不声称资源不存在。
新主人指令重置计数；原40步帽不扩大，不从反馈措辞猜no_targets。

## 3. 回执词汇表（模型行为约定）

| 前缀 | 语义 | 期望的模型行为 |
|---|---|---|
| `DENIED:` | 参数非法/未知工具/超频/无同伴 | 改参数或先备条件，别原样重试 |
| `BUSY:` | 同伴正忙上一件事 | 等待、或叫停再派新活 |
| `TARGET_LOST:` | 目标不在加载区/已被动过/已是空气 | 先靠近/重扫再下结论 |
| `OUT_OF_REACH:` | 超出臂长（≈5.5 格） | `move_to` 靠近后重试 |
| `WRONG_TOOL:` | 当前主手无采收资格、已改变或进度无效 | inventory/equip 换合适工具，目标未按本工具继续破坏 |
| `BREAK_FAILED:` | 未确认原版破坏成功，可能已有耐久或世界副作用 | 核对实际目标、背包和工具，勿自动重试 |
| `PLACE_FAILED:` | 未确认目标格成功放置或不能形成路径支撑 | 核对回执的目标变化/实际消耗，勿自动重试 |
| `ATTACK_FAILED:` | 已出手但没有观测到生命或吸收减少 | 核对目标/工具与实际状态，不能自动重投 |
| `OCCLUDED:` | 目标被遮挡，不能进行本次近战 | 重扫或显式移动，不隔墙攻击 |
| `UNSAFE_SWEEP:` | 剑的横扫邻域有其他活物 | 更换非横扫工具或等目标分开，不扩大授权对象 |
| `NO_RECIPE:` | 没有匹配的普通合成/烧制配方 | 核对物品/配方 ID 或机器类型，特殊制作换对应工具 |
| `MISSING_MATERIALS:` | 来源材料数量不足 | 对照回执与 inventory，取得材料后再调用 |
| `MISSING_FUEL:` | 新原料没有可用燃料或现存燃烧余量 | 用 load 同时供给燃料或先备燃料；本次未移动 |
| `INVALID_FUEL:` | 指定物品不是当前机器可用燃料 | 从 inventory 选可用燃料；空桶是返还物 |
| `SLOT_BLOCKED:` | 机器原料/燃料槽组件不匹配或装不下 | take 回收对应槽或减小数量；本次未移动 |
| `OUTPUT_BLOCKED:` | 机器下一件成品无法进入产物槽 | take 取走已有成品，再查询/装原料 |
| `NOT_READY:` | 机器产物槽当前为空 | 看机器状态，补足条件后 wait/query，别原样连发 take |
| `EMPTY_SLOT:` | 要回收的机器原料/燃料槽为空 | query 核对实际槽位，不换槽盲取 |
| `NEED_WORKBENCH:` | 合成需要附近工作台 | 扫描、靠近，或显式放置背包里的工作台 |
| `INVENTORY_FULL:` | 整批成品或配方返还物装不下 | 存入容器腾出空间后再调用；本次未消耗材料 |
| `UNBREAKABLE:` | 生存手段不可破坏 | 换目标 |
| `PATH_BLOCKED:` | 前方堵死/超搜索盒 | 绕路/拆障/分短段重发 |
| `NEED_CONFIRM:` | 路线改动或中立/命名目标攻击尚未获具体许可 | ask_owner 携带 authorization_id 展示服务器清单，获主人确认后用相同参数和编号重发对应工具；未同意就停手 |
| `NO_PATH:` | A* 搜索空间内无路（真封闭，**永不降级**）/无支撑 | 换路线方向或先造条件（拿材料/拆明障） |
| `PARTIAL:` | 撞搜索帽但已实质推进：半程段已走完，停在"能到的最近点" | 从回执里的当前点**重发 move_to**（可分多段抵达），别当失败 |
| `NO_PROGRESS:` | 撞帽且连短程都没推进（目标方向被纠缠堵死） | 换方向/换目的地，硬撞同一方向无益 |
| `ACCEPTED:` | **受理回执**（R2-S4）：这件事被受理了，**还没有结果** | 别重发（会被 BUSY 挡）、别干等；做完系统会主动报同一编号；这期间可以回主人一句话 |
| `CANCELLED:` | 主人主动叫停 | **停手**，向主人确认下一步，不许自作主张续上 |
| `SUPERSEDED:` | 被新指令顶掉（R2-S4，**本地合成**，不是服务端发的） | 别自作主张续上；要做就重新发一次 |
| `TIMEOUT:` | 服务器/主人超时未回执 | 别重复该操作，向主人说明 |
| `INTERNAL:` | 服务端异常/参数非 JSON | 报障，别重试 |
| `STATE_UNAVAILABLE:` | 本轮身体观测无有效结果 | 任务停止，说明无法取得状态，不能猜测完成 |

> `CANCELLED:`/`SUPERSEDED:`/`TIMEOUT:` 三种都会作为 `job_event` 的 `phase`
> （依次 `cancelled`/`superseded`/`failed`）出现，**相位与文本前缀分开**是有意的：
> 相位给客户端/面板做状态，前缀给模型做教学。

## 4. 加一个工具（配方）

1. `src/main/java/com/neko/mcbot/server/tools/` 新建实现 `ServerTool`：同步覆写 `run`；
   跨 tick 覆写 `runAsync`（构造 `TickTask`，`scheduler.submit` 撞 BUSY，设任务帽）。
2. `McbotMod.onInitialize` 的 SERVER_STARTED 里 `tools.register(new XxxTool())`（白名单）。
3. `ClientToolDefs.SPECS` 加同名描述 + JSON Schema（服务端校验 args 必须是对象）。
4. 参数校验失败要回 `DENIED:` 教学文本；成功回执里写清数量与位置。
5. 自测：SelfTest 直调（无头）→ 真机 `@bot` 驱动一遍；两处证据进 STATUS。
6. 桥接层**不用改**——它是任务级的，原子工具对 neko 不可见（by design）。

### 4.1 要不要走「受理即回执」（ACCEPT）？

默认 **不**（`acceptanceMode()` 返回 `SYNC`）。只有**跨 tick 且真的会花几秒以上**的活才覆写
`ACCEPT`——目前为 `move_to`（3600tick≈180s）、`break_block`（1200tick≈60s，挖一格实测 6–7s）
及有界 `attack`（400tick≈20s，可能等待冷却与目标恢复）。

短活走 ACCEPT 是**净亏**：先回受理再回结果 = 白多一跳，而且模型还得再问一次"好了没"。
另外注意服务端只有**一具身体 + 单槽**：走 ACCEPT 不会让两个"占身体"的活并行
（第二个仍会被 BUSY 挡），它省的是"大脑被一条长活占住"。

覆写时要**同时**给三样，缺一个就会出问题：

| 成员 | 为什么 |
|---|---|
| `acceptanceMode() → ACCEPT` | 决定走不走 job 通道 |
| `capTicks(args)` | **两个消费者共用一个来源**：`scheduler.submit` 拿它做服务端超时，派发层拿它算给客户端的受理回执（客户端等待上限 = cap×50ms+15s）。两边各写一份，就是"服务端允许跑 180s、客户端 90s 就判 TIMEOUT、真回执被当迟到丢掉"那条真缺陷 |
| `acceptSubject(args)` | 受理文案里"我在干什么"那一小段（如 `走到 12,63,-4`）。只给主语，统一模板说明还没结果、不要重发/轮询、后续工具等终态及做完主动报编号 |

`runAsync` 里 `sched.submit(..., capTicks(args))` 要传**同一个** `capTicks(args)`，别写死数字。
快路径（`DENIED`/`BUSY`/`TARGET_LOST`…）照常直接 `return CompletableFuture.completedFuture(...)`：
派发层发现 future 已完成就回普通 `tool_result`，不会走 ack。**这也是要遵守的**——
先 ack 再立刻报失败等于白多一跳。
