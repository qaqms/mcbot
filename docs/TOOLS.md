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

## 2. 在册工具（8 服务端 + 1 本地）

> 条数以 `McbotMod` 里 `tools.register(...)` 的**实际注册数**为准（现 8 个），
> 本表就是那 8 个 + 客户端本地的 `ask_owner`。别拿本表数字反推代码。

| 工具 | 参数 | 型 | 说明 |
|---|---|---|---|
| `status` | — | 同步 | 位置/生命/饥饿/背包占用/手持物 |
| `scan_area` | `r`(1-32，默认16) | 同步 | 附近实体 + 可行动方块分层摘要（classify 词表 container/ore/**rock**/workbench/farm/hostile）；坐标一律**绝对** `@(x,y,z) d距离`，首行含同伴位置+八向朝向；客户端随指令注入准星目标（`[我此刻盯着]`）。目标=直接可下指令；泥土沙**不是**目标（材料走 place/transfer 显式指令） |
| `break_block` | `x,y,z` | 异步(≤60s) | 手工计时挖掘：真速度、真战利品表（错工具真没掉落）、全客户端可见裂纹；掉落先背包后落地 |
| `collect` | `x,y,z,r?` | 同步 | 吸指定点附近掉落物进背包 |
| `place_block` | `x,y,z,item` | 同步 | 背包拿方块放（item 用注册路径如 `cobblestone`） |
| `move_to` | `x,y,z,may_alter_terrain?` | 异步(≤3min) | **DigAStar（M8）**：节点=落脚点，会绕路/跳/落/挖穿/垫脚/搭桥；改动世界的路先回 `NEED_CONFIRM`+方块清单，点头（`may_alter_terrain=true` 重发）才执行；搜索预算 8000 节点/128 挖/放≤背包存量；执行期每 20 节点复核，世界变了自动重规划 |
| `transfer` | `x,y,z,dir(in/out),item?` | 同步 | 原版 `Container` 接口存取，堆叠合并（不开 GUI） |
| `wait` | `seconds`(1-60) | 异步 | 站定等待（熔炉/作物节奏用，别拿轮询代替等待） |
| `ask_owner` | `text` | **本地**（不出客户端） | 方向性决策问主人：question 事件进桥 → `/v1/answer` 回复续跑；120s 无回答回失败回执，再由大脑决定后续 |

未上（DESIGN §5 规划中）：`craft`、`smelt`、`inspect_block`、`attack`、`equip`、
`locate` 等——M5/M8 分批补齐；`navigate` 并入 move_to 升级，`wait_until` 并入 wait。

## 3. 回执词汇表（模型行为约定）

| 前缀 | 语义 | 期望的模型行为 |
|---|---|---|
| `DENIED:` | 参数非法/未知工具/超频/无同伴 | 改参数或先备条件，别原样重试 |
| `BUSY:` | 同伴正忙上一件事 | 等待、或叫停再派新活 |
| `TARGET_LOST:` | 目标不在加载区/已被动过/已是空气 | 先靠近/重扫再下结论 |
| `OUT_OF_REACH:` | 超出臂长（≈5.5 格） | `move_to` 靠近后重试 |
| `WRONG_TOOL:` | 当前手持挖不动 | 去做/去找合适工具（回执会指方向） |
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
