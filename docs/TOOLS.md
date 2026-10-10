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
- **每同伴单活跃任务槽**：异步工具撞车直接回 `BUSY`（无队列——模型本就串行思考）。
- 所有执行都发生在服务器主线程、作用于**发送者名下**的同伴（owner 校验在闸③）。

## 2. 在册工具（12 服务端 + 1 本地，含未验收冶炼草稿）

> 条数以 `McbotMod` 里 `tools.register(...)` 的**实际注册数**为准（现 12 个），
> 本表就是那 12 个 + 客户端本地的 `ask_owner`。别拿本表数字反推代码。

| 工具 | 参数 | 型 | 说明 |
|---|---|---|---|
| `status` | — | 同步 | 位置/生命/饥饿/背包占用/手持物 |
| `inventory` | — | 同步 | 完整 36 格背包的槽位/物品 ID/数量/耐久、选中主手槽及 7 个装备映射槽；只读，忙时也能查看 |
| `equip` | `slot`(0-35 整数) | 同步 | 仅切换主手：快捷栏 0-8 直接选中；背包 9-35 与当前主手槽交换整个物品堆栈；忙时拒绝 |
| `craft` | `item,count?,query?,recipe?` | 同步 | 实际普通合成配方；count 是至少所需成品数 1-64，query 只读；2×2 随身，3×3 需 5.5 格内工作台；整批预检，失败不改背包 |
| `smelt` | `x,y,z,action?,input_slot?,input_count?,fuel_slot?,fuel_count?,slot?,count?` | 同步，草稿 | query/load/take；原版熔炉类机器三槽与真实计数，原版 tick 自主烧制；真实服务端验收未完成 |
| `scan_area` | `r`(1-32，默认16) | 同步 | 附近实体 + 可行动方块分层摘要（classify 词表 container/ore/**rock**/workbench/farm/hostile）；坐标一律**绝对** `@(x,y,z) d距离`，首行含同伴位置+八向朝向；客户端随指令注入准星目标（`[我此刻盯着]`）。目标=直接可下指令；泥土沙**不是**目标（材料走 place/transfer 显式指令） |
| `break_block` | `x,y,z` | 异步(≤60s) | 手工计时挖掘：真速度、真战利品表（错工具真没掉落）、全客户端可见裂纹；掉落先背包后落地 |
| `collect` | `x,y,z,r?` | 同步 | 吸指定点附近掉落物进背包 |
| `place_block` | `x,y,z,item` | 同步 | 背包拿方块放（item 用注册路径如 `cobblestone`） |
| `move_to` | `x,y,z,may_alter_terrain?` | 异步(≤3min) | **DigAStar（M8）**：节点=落脚点，会绕路/跳/落/挖穿/垫脚/搭桥；改动世界的路先回 `NEED_CONFIRM`+方块清单，点头（`may_alter_terrain=true` 重发）才执行；搜索预算 8000 节点/128 挖/放≤背包存量；执行期每 20 节点复核，世界变了自动重规划 |
| `transfer` | `x,y,z,dir(in/out),item?` | 同步 | 原版普通 `Container` 接口存取，堆叠合并（不开 GUI）；熔炉类须用 smelt |
| `wait` | `seconds`(1-60) | 异步 | 站定等待（熔炉/作物节奏用，别拿轮询代替等待） |
| `ask_owner` | `text` | **本地**（不出客户端） | 方向性决策问主人：question 事件进桥 → `/v1/answer` 回复续跑；120s 无回答回失败回执，tick 与回答入口都检查期限，再由大脑决定后续 |

游戏内 `@bot 答 <文本>` / `@bot answer <文本>` 只用于回答当前最新的有效问题。
没有待答问题、问题已回答或已过期时提示回答未提交，不作为新任务执行；
普通任务仍从任务页、桥或不带回答前缀的 `@bot <指令>` 投递。

未上（DESIGN §5 规划中）：`inspect_block`、`attack`、
`locate` 等——M5/M8 分批补齐；`navigate` 并入 move_to 升级，`wait_until` 并入 wait。

### 2.1 背包明细与主手切换

模型需要材料或工具信息时调用 `inventory {}`，从回执选择实际槽号，再调用
`equip {"slot":13}`。`status` 仍是轻量体感汇报，不承担完整背包枚举。
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

### 2.3 冶炼草稿（真实服务端未验收）

`smelt {"x":2,"y":90,"z":0}` 默认 action=query，只读三槽、实际烧制/燃烧计数、
配方、区块是否推进及等待建议。合法查询 ok=true 不代表机器正在烧制或已有新成品。
目标须在同维度 5.5 格内、已加载且是未锁定的原版熔炉/高炉/烟熏炉。

load 从背包 0-35 的 input_slot/fuel_slot 装料，至少提供一个来源槽；
对应 input_count/fuel_count 为 1-64，默认 1。同一来源槽先预留原料再核对燃料，
不能重复消费；组件不匹配、槽满、配方不支持、缺料或缺燃料时不移动物品。
take 的 slot=input/fuel/output，默认 output；count 为最多取出数量 1-64，默认 64。
背包装不下整次取出时保持两边不变；空产物槽返回 NOT_READY。
不同 action 的多余参数直接拒绝，避免错模式移动物品。

模型应按 inventory → load → wait → query/take → inventory 获取实际反馈。
烧制由原版机器推进，工具不生成成品；装料成功、等待结束和取消均不证明烧制完成。
取消 wait/agent task 不清机器，停止后续烧制须显式取回原料，燃烧余量仍按原版消耗。
长任务占槽时只允许 query，load/take 返回 BUSY。
暂仅支持原版普通烧制配方类且单原料产物数量为 1，拒绝多产物数据包配方。
不支持模组机器、GUI/成就/经验领取流程；实际机器行为仍需专项证明。
离线参数与 runner 接线测试见 DEVELOPMENT §3.4，未替代真实服务端或连接器验收。

## 3. 回执词汇表（模型行为约定）

| 前缀 | 语义 | 期望的模型行为 |
|---|---|---|
| `DENIED:` | 参数非法/未知工具/超频/无同伴 | 改参数或先备条件，别原样重试 |
| `BUSY:` | 同伴正忙上一件事 | 等待、或叫停再派新活 |
| `TARGET_LOST:` | 目标不在加载区/已被动过/已是空气 | 先靠近/重扫再下结论 |
| `OUT_OF_REACH:` | 超出臂长（≈5.5 格） | `move_to` 靠近后重试 |
| `WRONG_TOOL:` | 当前手持挖不动 | 去做/去找合适工具（回执会指方向） |
| `NO_RECIPE:` | 没有匹配的普通合成配方 | 核对产物与配方 ID，特殊制作换对应工具 |
| `MISSING_MATERIALS:` | 材料不足以完成整批合成 | 对照回执材料与 inventory，取得材料后再调用 |
| `NEED_WORKBENCH:` | 合成需要附近工作台 | 扫描、靠近，或显式放置背包里的工作台 |
| `INVENTORY_FULL:` | 整批成品或配方返还物装不下 | 存入容器腾出空间后再调用；本次未消耗材料 |
| `UNBREAKABLE:` | 生存手段不可破坏 | 换目标 |
| `PATH_BLOCKED:` | 前方堵死/超搜索盒 | 绕路/拆障/分短段重发 |
| `NEED_CONFIRM:` | 最优路要改动世界（挖/放），未授权 | 把清单说给主人听；同意后带 `may_alter_terrain=true` 重发；主人不愿就换目的地 |
| `NO_PATH:` | A* 搜索空间内无路（真封闭，**永不降级**）/无支撑 | 换路线方向或先造条件（拿材料/拆明障） |
| `PARTIAL:` | 撞搜索帽但已实质推进：半程段已走完，停在"能到的最近点" | 从回执里的当前点**重发 move_to**（可分多段抵达），别当失败 |
| `NO_PROGRESS:` | 撞帽且连短程都没推进（目标方向被纠缠堵死） | 换方向/换目的地，硬撞同一方向无益 |
| `ACCEPTED:` | **受理回执**（R2-S4）：这件事被受理了，**还没有结果** | 别重发（会被 BUSY 挡）、别干等；做完系统会主动报同一编号；这期间可以回主人一句话 |
| `CANCELLED:` | 主人主动叫停 | **停手**，向主人确认下一步，不许自作主张续上 |
| `SUPERSEDED:` | 被新指令顶掉（R2-S4，**本地合成**，不是服务端发的） | 别自作主张续上；要做就重新发一次 |
| `TIMEOUT:` | 服务器/主人超时未回执 | 别重复该操作，向主人说明 |
| `INTERNAL:` | 服务端异常/参数非 JSON | 报障，别重试 |

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
`ACCEPT`——目前就 `move_to`（3600tick≈180s）与 `break_block`（1200tick≈60s，挖一格实测 6–7s）。

短活走 ACCEPT 是**净亏**：先回受理再回结果 = 白多一跳，而且模型还得再问一次"好了没"。
另外注意服务端只有**一具身体 + 单槽**：走 ACCEPT 不会让两个"占身体"的活并行
（第二个仍会被 BUSY 挡），它省的是"大脑被一条长活占住"。

覆写时要**同时**给三样，缺一个就会出问题：

| 成员 | 为什么 |
|---|---|
| `acceptanceMode() → ACCEPT` | 决定走不走 job 通道 |
| `capTicks(args)` | **两个消费者共用一个来源**：`scheduler.submit` 拿它做服务端超时，派发层拿它算给客户端的受理回执（客户端等待上限 = cap×50ms+15s）。两边各写一份，就是"服务端允许跑 180s、客户端 90s 就判 TIMEOUT、真回执被当迟到丢掉"那条真缺陷 |
| `acceptSubject(args)` | 受理文案里"我在干什么"那一小段（如 `走到 12,63,-4`）。**只给主语**——"别猜、别等着、做完我主动报 j1"这套模板由派发层统一拼，每个工具一字不差，模型才学一遍就够 |

`runAsync` 里 `sched.submit(..., capTicks(args))` 要传**同一个** `capTicks(args)`，别写死数字。
快路径（`DENIED`/`BUSY`/`TARGET_LOST`…）照常直接 `return CompletableFuture.completedFuture(...)`：
派发层发现 future 已完成就回普通 `tool_result`，不会走 ack。**这也是要遵守的**——
先 ack 再立刻报失败等于白多一跳。
