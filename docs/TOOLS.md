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

## 2. 在册工具（9 服务端 + 1 本地）

| 工具 | 参数 | 型 | 说明 |
|---|---|---|---|
| `status` | — | 同步 | 位置/生命/饥饿/背包占用/手持物 |
| `scan_area` | `r`(1-32，默认16) | 同步 | 附近实体 + 特殊方块（容器/矿石/工作台熔炉/作物）摘要；坐标为世界轴偏移（朝向相对网格在 M5） |
| `break_block` | `x,y,z` | 异步(≤60s) | 手工计时挖掘：真速度、真战利品表（错工具真没掉落）、全客户端可见裂纹；掉落先背包后落地 |
| `collect` | `x,y,z,r?` | 同步 | 吸指定点附近掉落物进背包 |
| `place_block` | `x,y,z,item` | 同步 | 背包拿方块放（item 用注册路径如 `cobblestone`） |
| `move_to` | `x,y,z` | 异步(≤3min) | 滑步占位版：≤48 格直线、自动上下坎、实心挡死如实报 `PATH_BLOCKED`，**绝不改世界**（M8 换可挖 A* + may_alter_terrain 确认流） |
| `transfer` | `x,y,z,dir(in/out),item?` | 同步 | 原版 `Container` 接口存取，堆叠合并（不开 GUI） |
| `wait` | `seconds`(1-60) | 异步 | 站定等待（熔炉/作物节奏用，别拿轮询代替等待） |
| `ask_owner` | `text` | **本地**（不出客户端） | 方向性决策问主人：question 事件进桥 → `/v1/answer` 回复续跑；300s 不回教它自行定夺 |

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
| `PATH_BLOCKED:` | 滑步走不过去 | 绕路/清障/等升级 |
| `CANCELLED:` | 主人主动叫停 | **停手**，向主人确认下一步，不许自作主张续上 |
| `TIMEOUT:` | 服务器/主人超时未回执 | 别重复该操作，向主人说明 |
| `INTERNAL:` | 服务端异常/参数非 JSON | 报障，别重试 |

## 4. 加一个工具（配方）

1. `src/main/java/com/neko/mcbot/server/tools/` 新建实现 `ServerTool`：同步覆写 `run`；
   跨 tick 覆写 `runAsync`（构造 `TickTask`，`scheduler.submit` 撞 BUSY，设任务帽）。
2. `McbotMod.onInitialize` 的 SERVER_STARTED 里 `tools.register(new XxxTool())`（白名单）。
3. `ClientToolDefs.SPECS` 加同名描述 + JSON Schema（服务端校验 args 必须是对象）。
4. 参数校验失败要回 `DENIED:` 教学文本；成功回执里写清数量与位置。
5. 自测：SelfTest 直调（无头）→ 真机 `@bot` 驱动一遍；两处证据进 STATUS。
6. 桥接层**不用改**——它是任务级的，原子工具对 neko 不可见（by design）。
