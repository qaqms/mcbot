# mcbot v1 技术方案（Fabric 1.21.11 · 联机服 · neko 猫娘接入）

> 定位：LLM 驱动的 Minecraft 游戏内同伴（假玩家），跑在可装 mod 的联机服务器上；
> 主人客户端持有大脑与本地桥接服务；桌面猫娘（neko，另一个 LLM）经本地桥接下达任务、
> 接收进度播报与反问。mcbot 独立可玩（游戏内聊天即可指挥），猫娘只是其中一个前端。
>
> 纪律：本方案只借鉴公开机制与思想（假玩家、可挖寻路、function calling、MCP 规范、
> agent-as-tool 分层），不复制 Numen/Baritone/Carpet 的任何代码、注释文本、美术与命名。

---

## 0. 一页总览

```
┌─ 主人桌面（同机）─────────────────────────────────────┐
│  neko 猫娘  ⇄  127.0.0.1 HTTP/SSE/MCP  ⇄  mcbot-bridge │
│                                    └── agent loop ──┐  │
│  Minecraft 客户端 ← mcbot-client（loop 宿主）←──────┘  │
└──────────────┬───────────────────────────────────────┘
               │ 游戏网络（Fabric custom payloads）
┌──────────────▼───────────────────────────────────────┐
│  MC 服务器（联机，装 mcbot-server 部分）              │
│  假玩家身体 · 工具执行 · 跨tick任务 · 寻路 · 感知     │
│  所有权校验：只执行 roster 中 owner=发送者 的指令     │
│  其他玩家：原版客户端，无需安装任何 mod               │
└──────────────────────────────────────────────────────┘
```

核心决策与代价：

1. **世界只能在服务端主线程改** → 手（工具执行、任务、寻路）全在服务器侧。
2. **key、neko、猫娘都在主人桌面** → 大脑与桥接在客户端，服务器**不开任何额外端口**。
3. **代价（v1 明示）**：同伴只在主人客户端在线期间工作。全天候挂机需要"服务端大脑 +
   服务器托管 key + 桥接上公网"，那是另一套安全模型，v2 再议。
4. **前提**：联机服务器允许安装服务端 mod。若目标服只跑 Bukkit/Spigot，v2 可做插件端口
   （身体/工具契约不变，只换宿主层）。

---

## 1. 进程与线程模型

| 组件 | 进程 | 线程 | 铁律 |
|---|---|---|---|
| agent loop | 客户端 | 专用 worker（一同伴一线程/虚拟线程） | 不触碰任何 MC 对象，只发消息、等回调 |
| LLM HTTP | 客户端 | `HttpClient` 异步回调池 | 流式解析 tool_call，回 loop 队列 |
| bridge（HTTP/SSE） | 客户端 | JDK `com.sun.net.httpserver` 线程池（4~8 线程） | 只做协议翻译，入队后返回 |
| 身体+任务+寻路 | 服务器 | **主线程（tick）** | 一切重计算预算化（见 §8） |
| 事件回传 | 服务器→客户端→neko | payload → SSE | 每事件带自增 seq，支持重连补拉 |

客户端↔服务器全部经 custom payload 握手（见 §3），correlation id = `seq`；
`tool_result` 超时 90s → 回 `TIMEOUT` 教学反馈。

---

## 2. Gradle 与模块划分

三个 Gradle 子项目（单仓多模块，一个 mod jar）：

```
mcbot/
├── agent-core/          纯 JVM，零 MC 依赖（可独立 main 与单测）
│   ├── llm/      LlmClient(HttpClient+SSE) · ProviderOpenAICompat
│   │             StreamToolCallAccumulator · RetryPolicy
│   ├── convo/    Conversation(环形窗口) · Compactor(超长摘要) · ConvoStore(JSONL)
│   ├── loop/     AgentLoop(状态机) · Directive · StepGuard · PendingQuestion
│   ├── prompt/   SystemPromptBuilder · PersonaBuilder · SkillLoader(markdown)
│   └── schema/   ToolSchema(JSON Schema 轻量子集) · ToolSpec
├── src/main/            mod 公共 + 服务端代码
│   ├── common/   Payloads(通道id与DTO) · WireTypes · McbotConstants
│   ├── body/     FakeConnection · CompanionPlayer · Roster · SummonService
│   │             SafeSpawn · CompanionPersistence
│   ├── server/   McbotServer(init+注册) · ServerToolDispatcher · EventRelay
│   │             RateGuard(每owner令牌桶) · McbotCommands
│   ├── task/     Task · TaskOutcome · TaskChain · CompanionScheduler(一活跃+队列)
│   ├── tools/    服务端工具实现（§5 清单，一工具一类）
│   ├── perception/ GridScanner · EntityScanner · BlockInspector · ContainerReader
│   ├── path/     WalkNav(v1) · DigAStar(v2) · NavBudget
│   └── cfg/      ServerConfig(summon开关/预算上限/速率)
└── src/client/          仅客户端执行
    ├── McbotClient      bridge启动、聊天捕获、按键
    ├── agent/    AgentRunner(装配loop与payload) · DirectiveRouter
    ├── bridge/   BridgeServer · McpHandler(JSON-RPC) · RestHandler · EventStream
    │             (SSE + 环形缓冲200条) · TokenAuth
    └── cfg/      ClientConfig(model/key/persona/skills/bridge端口·token)
```

规模估算：~48 个源文件、5500–6500 行（v2 寻路再 +800）。
Loom 配置：yarn `1.21.11+build.6`、loader `0.19.5`、Java 21、国内镜像照旧换 alias。
`fabric.mod.json` 声明 `environment: *`，客户端逻辑走 `ClientModInitializer`，
服务端纯 vanilla 客户端可连（不 required item resource）。

---

## 3. 客户端↔服务器协议（custom payloads）

命名空间 `mcbot:`，JSON 字符串体（小消息，先不上 NBT 编解码）。

**C2S（主人客户端 → 服务器）**

| 通道 | 载荷 | 说明 |
|---|---|---|
| `summon` | name, persona_id? | 校验 owner 同伴数 < 上限 |
| `dismiss` | companion_id | |
| `tool_call` | task_id?, seq, tool, args_json, accept? | seq 由客户端自增；`accept`（R2-S4 追加，缺省=老语义）点名要不要走"受理即回执" |
| `cancel` | task_id | |
| `answer` | question_id, text | 回复同伴的反问 |

**S2C（服务器 → 主人客户端）**

| 通道 | 载荷 | 说明 |
|---|---|---|
| `roster` | companions[] | 登录/变更时全量推 |
| `tool_result` | seq, ok, feedback_text, data_json | |
| `job_ack` | seq, job_id, tool, cap_ticks, text | **R2-S4 追加**：长活受理回执（文本以 `ACCEPTED:` 开头）。一个 seq 只会收到 result **或** ack |
| `job_event` | seq, job_id, tool, phase(progress/done/failed/cancelled/superseded), text, data_json | **R2-S4 追加**：受理后的后续。契约见 `docs/BRIDGE.md` §5.1 |
| `event` | companion_id, type(started/progress/done/fail/question/state), body | 经 loop 转 SSE 给 neko |

**服务器侧三道闸**（安全生命线，逐 payload 执行）：
① 载荷 ≤ 32KB、每 owner 速率限制；② tool 名在注册表内、args 过 JSON Schema；
③ **owner 校验**：`sender.Profile.id == companion.owner_uuid`，不符即静默丢弃 + 日志。
反作弊角度：假玩家动作走玩家原生路径，服务器行为日志与普通玩家同构。

---

## 4. Agent Loop（大脑状态机）

```
IDLE ─新指令(neko任务 / 游戏聊天 / answer)→ PLAN
PLAN: 组 prompt = system(persona+skills+游戏规则简述) + tool schemas
      + 压缩后的对话窗口 + 上一步工具回执 → 异步调 LLM
   ├─ 返回 tool_calls → ACT
   └─ 纯文本回复 → 上报（聊天框/ neko 事件流）→ IDLE
ACT: 逐个 tool_call 发 payload，等 tool_result（本线程阻塞在 future 上，不占游戏线程）
FEEDBACK: 回执文本（服务端生成，教学式，见 §7）追加进对话 → PLAN
```

护栏（StepGuard）：
- 单任务 ≤ 40 步，超限强制收尾汇报；
- 同 (tool, args) 连续 3 次 → 注入 nudge 系统消息"你似乎在原地打转，换个思路"；5 次 → 中止上报；
- 上下文 token 水位 > 80% → Compactor 摘要旧对话（保留任务主线与关键事实清单）；
- LLM 请求失败 → 指数退避重试 ×2 → 以 fail 事件上报而非静默。

多同伴 = 多 loop 实例，各自独立 Conversation 与队列；v1 每 owner 限 1~2 个。

---

## 5. 工具清单 v1（14 个）

| 工具 | 参数 | 回执要点 |
|---|---|---|
| `status` | — | 位置/血量/饥饿/手持/当前任务/背包占用 |
| `scan_area` | radius(默认16,≤32) | 字符网格 + 实体摘要（§6） |
| `inspect_block` | x,y,z | 硬度/是否可挖/容器内容摘要/机器状态 |
| `move_to` | x,y,z \| "chest"/"bed" 等语义 | 到达/被堵/超时三态 |
| `break_block` | x,y,z | 真实硬度耗时，掉落入背包 |
| `collect` | x,y,z范围 \| nearby | 捡到的东西列表 |
| `place_block` | x,y,z, item | 放置校验（不盖容器/不悬空） |
| `craft` | item, count | 按配方表匹配背包，缺料回执列出缺什么 |
| `transfer` | 箱子pos, in/out, item?, all? | 双向存取（原版 Container 接口） |
| `smelt` | furnace_pos, item, count | 放入燃料/原料；不盯完工（用 wait/定时） |
| `attack` | entity_id \| "hostile_nearby" | 近战；被反击自动吃（v1 手动 eat 工具兜底） |
| `eat` | item? | 自动挑食物 |
| `ask_neko` | question | 生成 question 事件 → 阻塞等 `answer` payload（超时 120s 放弃） |
| `wait` | seconds | 简单等待，给熔炉/门动画留时间 |

契约（自研，示意）：

```java
interface BotTool {
  String name();  ToolSchema schema();
  // SERVER_TICK: 入队主线程执行 | ASYNC: 可自己起异步（如外部查询）
  RunMode mode();
  CompletableFuture<ToolResult> invoke(JsonObject args, ToolContext ctx);
}
record ToolResult(boolean ok, String feedback, JsonElement data) {}
```

`feedback` 永远是一句**给模型看的人话**（§7），`data` 给 UI/neko 播报用。

---

## 6. 感知设计（喂给模型的世界长什么样）

**自中心字符网格**：以同伴为原点、**面朝方向为上**，三层（脚下一格/脚底平面/头上一格）
15×15；边缘标相对坐标。字符集（示例，自定）：`╬`空气可行 `▓`实心 `░`水 `#`熔岩
`♣`树 `✿`作物 `O`矿石 `C`容器 `T`工作台 `F`熔炉 `S`火把 `?`不确定；
实体字母表 `z`僵尸 `c`苦力怕 `P`玩家 `A`动物；目标格 `★`。

**实体扫描**：半径内 id/类型/距离/血量/敌我标记。**方块检视**：硬度、工具要求、
方块实体标签；是 `Container` 则逐槽摘要（物品/数量），非原版接口的模组机器 v1 标
`C?` 并回执"这似乎是一台需要专门适配的机器，我读不了它的内部"。

网格的 token 成本要控：15×15×3 ≈ 700 字符，可接受；radius>16 时只给摘要层。

---

## 7. 反馈措辞（教学式回执 —— 提示词工程的核心资产）

原则：**失败回执 = 现象 + 原因 + 下一步建议**，写给"不懂 Minecraft 的模型"看。
失败类型枚举与示例（全部自写文案，形成自己的措辞规范）：

| 类型 | 回执示例 |
|---|---|
| `WRONG_TOOL` | "徒手挖不动石头。做一把木镐需要 4 块木板——先用手破坏 4 段原木吧。" |
| `TARGET_LOST` | "目标方块已经不在了——可能被沙子埋了或有人拿走了。要我重新扫一遍吗？" |
| `PATH_BLOCKED` | "去那里的路被堵死了（3 格厚的石头，我的挖掘预算不够）。可以让我挖穿它——那会消耗更多，需要你确认。" |
| `OUT_OF_REACH` | "太远够不着，走过去再试。" |
| `INVENTORY_FULL` | "背包满了，粗铁掉在了地上。先回箱子卸货吧。" |
| `HAZARD` | "挖开这格旁边是岩浆，我不会为了省事送死。绕开或先填掉它。" |
| `DENIED` | "这个箱子/这片地不是主人授权的，我不能碰。" |
| `TIMEOUT` | "这件事卡住超时了，我把已完成的半程记下来，等你指示要不要重来。" |

同一措辞规范也用于 `started/progress/done` 事件——neko 拿到的播报文本就是这些，
猫娘转述成人设话术前信息零损失。

---

## 8. 寻路

**v1（M4 就能用）**：原版 GroundPathNavigation 走"纯空气已通路径"（可达性检查用
navigation 的 can_reach 预估）+ 够得着（4格）直接 gameMode 挖。堵死 → `PATH_BLOCKED`
回执 + 模型决策（绕路/放弃/要求挖穿）。v1 阶段"挖穿"只允许挖阻挡路径上的**直线路径**，
单次 ≤ 32 格，作为临时土办法。

**v2（M8，可挖通道 A*，设计要点背好再动手）**：
- 节点=落脚点（2 格高净空位置），动作边：走/跳/落≤3格/向上垫1/向下挖/向前挖；
- 代价 = 距离 + 挖掘方块数×挖速 + 风险项；
- **预算**：单次搜索节点 ≤ 8000、计划挖掘 ≤ 128 方块（服务器配置可调上限）；
  **主线程每 tick 只算 ≤ 300 节点**，跨 tick 分帧，绝不一次算崩；
- **保护集**：目标矿格本身神圣（不许被中途顺路挖掉）；脚下不挖；容器/工作台所在格
  及其下不挖；岩浆邻接的方块的"挖"代价 → 无穷（`HAZARD` 否决）；
- **执行期复核**：每执行 20 格提交一次、重验接下来 5 格状态（方块变了/沙塌了/水来了），
  不符即局部重规划；
- 失败结构化：`NO_PATH(原因)` / `BUDGET_EXCEEDED(已挖多少/还差多少)`。

---

## 9. 桥接层（neko 看到的 mcbot）

**形态**：客户端内嵌 JDK `HttpServer`，绑 `127.0.0.1`（LAN 可选，需 HTTPS+明确开关），
端口默认 57121，`Authorization: Bearer <token>`（配置生成随机值）。

**双接口，一套内核**（`AgentRunner` 的消息队列）：

1. **MCP 服务器**（给猫娘的 function calling / 或任何 MCP 客户端）：
   JSON-RPC `initialize` / `tools/list` / `tools/call`，通知走 SSE。
   暴露工具：

   | 工具 | 参数 | 返回 |
   |---|---|---|
   | `mcbot_task` | text, wait_s(0–120，默认8) | `task_id` + 窗口内已产生的进度片段 |
   | `mcbot_ask` | companion, text | 同伴的回答文本（同步等 loop 回复，≤60s） |
   | `mcbot_answer` | question_id, text | 回复同伴的反问 |
   | `mcbot_status` | — | 同伴状态 JSON |
   | `mcbot_cancel` | task_id | |

   注：这些工具**全是任务级的**，原子游戏操作不出现在这层——猫娘是老板不是操作员。

2. **REST + SSE**（给不能跑 MCP 的 neko 插件位做降级）：
   `POST /v1/task`、`POST /v1/task/{id}/cancel`、`POST /v1/ask`、`GET /v1/status`、
   `GET /v1/events`（SSE 帧：`{ev:"progress"|"done"|"question"|"state", ...}`，
   带 `id:` 自增，断线重连按 Last-Event-ID 从 200 条环形缓冲补发）。

**推荐对话闭环**（写进 neko 侧集成文档，也方便你对拍）：

```
用户 → 猫娘(LLM) → mcbot_task("去挖一组铁矿")        → {task_id}
服务器进度流 → SSE progress("开始下矿"/"挖到 3 铁矿") → 猫娘实时旁白
同伴反问(ask_neko) → SSE question → 猫娘问用户 → mcbot_answer → 同伴继续
完成 → done 事件 + mcbot_ask 收尾闲聊
```

---

## 10. 配置与安全

- **客户端配置** `config/mcbot/`：provider 预设（base_url/model/显示名，先只支持
  OpenAI 兼容一族，Anthropic 原生协议 v2）、`api_key`（支持环境变量覆盖）、persona 目录、
  skills 目录、bridge 端口/token。明文 key 提示：建议 env 或系统凭据管理器。
- **日志脱敏**：LLM 请求日志只记模型名/消息数/字数，绝不记 body 全文含 key。
- **服务器配置**：是否允许 summon、每 owner 同伴上限、挖掘预算上限、速率限制。
- 服务器不存任何 key。大脑崩溃不影响服务器（同伴静止站立，roster 仍在）。

---

## 11. 里程碑（每级独立可验收）

| M | 内容 | 新增文件 | 验收 |
|---|---|---|---|
| **M0** | Loom 脚手架 + 三模块 + 镜像 | ~10 | `gradlew build` 出 jar；runServer+runClient 连上，日志双端 init |
| **M1** | 身体：`FakeConnection`(EmbeddedChannel+丢出站+吞keepalive) + `CompanionPlayer` + summon/dismiss/roster/登出清理/持久化 | ~10 | 联机构造：服务端独立 runServer，客户端连入；steve 出现在世界里、tab 可见、加入消息隐藏；**30 分钟无踢出 soak**；服务器重启后同伴自动重进；主人退出后同伴原地挂机不崩。⚠️ 1.21.9+ 档案系统改过，构造 profile 时以当前版 javadoc/Carpet 同版本分支为机制参考，写→编译→修收敛 |
| **M2** | agent-core 脱离 MC 裸测：LlmClient+流式解析+压缩+loop 状态机（工具执行用假实现） | ~14 | 命令行喂话，解析出 tool_call 并假回执跑完整回路；单测过 |
| **M3** | 握手回路：payload 全表 + 工具 `status/scan_area` + 游戏聊天当指令源 + 服务端三道闸 | ~10 | 聊天框"看看附近" → 同伴复述扫描结果；伪造他人 payload 被丢 |
| **M4** | 行动工具第一批：move/break/collect/place/transfer + 跨tick任务框架 + 失败回执全套 | ~12 | "把脚边三块石头挖了扔进箱子里"一气呵成；背包满/被堵路径走 `INVENTORY_FULL`/`PATH_BLOCKED` 教学回执 |
| **M5** | 感知与记忆：字符网格 + 容器/方块检视 + craft/smelt/wait + 对话持久化与压缩 + StepGuard 调参 | ~8 | 重启客户端对话不丢；长对话不爆 token；"造一把木镐"会缺料回执 |
| **M6** | 桥接：MCP + REST/SSE + token + 事件环形缓冲 | ~6 | `npx @modelcontextprotocol/inspector` 连上，列工具、发任务、收 done 事件；curl 等价验证 |
| **M7** | neko 接入：猫娘 function schema + 播报人设 + question 往返 | 0（neko侧另计） | 给猫娘发消息 → 同伴干活 → 猫娘实时旁白 → 反问 → 你答 → 干完 |
| **M8** | DigAStar v2 + 预算/保护集/执行期复核 全量 | ~6 | "往北 20 格地下找铁矿"自己开路挖到；箱子在路径上时**绝不**被顺路挖掉；脚下保护生效 |

依赖关系：M2 与 M1 可并行；M6 前所有东西游戏内可玩，桥接是纯加法。

---

## 12. 风险清单与对策

| 风险 | 对策 |
|---|---|
| 主线程被寻路/扫描卡崩 | 全预算化分帧（§8）+ watchdog 计时日志；扫描半径上限 |
| LLM 死循环烧钱 | 步数帽 + 重复调用 nudge/abort + 每任务 token 用量日志 |
| 客户端崩溃/断网时任务半路死 | 服务器侧任务幂等收尾：掉线即暂停入 pending 队列，重连后 `roster`+`state` 全量同步 |
| 假玩家被 keepalive 踢/存盘污染 | `FakeConnection` 吞 disconnect；登出时按需清档（v1：dismiss 才删，退出保留） |
| 他人服务器 payload 攻击面 | §3 三道闸：尺寸/速率/Schema + owner 强校验；非同伴主人一切 payload 无关通道回 `DENIED` |
| 1.21.11 内部 API 与 1.21.1 参考有代差（网络/档案两次大改） | M1/M3 预留 1.5 倍工期；只按机制理解、签名以本地 yarn javadoc 为准 |
| neko 断线丢事件 | SSE Last-Event-ID 重放（200 条缓冲）+ `mcbot_status` 兜底快照 |
| 领地/保护 mod | 走真实玩家路径天然触发其 BreakEvent/InteractEvent，被拦即 `DENIED` 教学回执——这是假玩家路线的红利，保住它 |
| key 泄漏 | 仅本地；日志脱敏；桥接只绑 loopback |

---

## 13. v1 不做（边界）

语音、皮肤编辑器、Anthropic 原生协议、MCP 客户端（挂外部工具）、插件系统、
多服务器/跨维度、建筑蓝图、战斗走位、精确红石、neko 反向让同伴"有声音人格"（那是猫娘侧的事）。
```
