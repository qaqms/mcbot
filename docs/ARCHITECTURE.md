# mcbot 架构（as-built）

> 本文描述**已建成**的结构；施工前的完整蓝图见根目录 `mcbot-DESIGN.md`，
> 进度与 API 防漂移笔记见 `STATUS.md`。与代码不一致时，以代码为准并请修订本文。

## 1. 三进程拓扑

```
┌─ neko（桌面猫娘，外部进程）
│    只看得到"任务级"接口（§6 桥接）
│
├─ Minecraft 客户端（主人）  src/client/
│    AgentRunner：大脑宿主（agent-core AgentLoop）
│    BridgeHttp：127.0.0.1 桥（对 neko）
│    McbotPanelScreen：G 面板（配置/召唤/聊天/叫停）
│    ⇅ 自定义 payload mcbot:c2s / mcbot:s2c（单 JSON 信封）
│
└─ Minecraft 服务器（联机服） src/main/
     SummonService：假玩家（ServerPlayer）生命周期
     ServerToolDispatcher：三道闸 → 工具执行
     CompanionScheduler：跨 tick 任务推进（END_SERVER_TICK）
```

硬约束：**API key 与大脑只存在于主人客户端**；服务器只见工具调用的结果，不见模型；
桥只绑回环。三者各是一层信任边界。

## 2. 线程模型

| 线程 | 跑什么 | 规则 |
|---|---|---|
| 服务器主线程 | 三道闸后的一切：工具执行、Scheduler tick、假玩家 | 世界操作只在这条线 |
| 客户端主线程（渲染） | `AgentRunner.tick()` 超时巡检、面板、聊天注入 | GUI 调用必须 `mc.execute()` 到此线 |
| HttpClient 线程 | SSE 流、AgentLoop 全部回调（onReply/onToolInvoked…） | 碰 GUI 前必须 hop；碰桥事件环安全（自带锁） |
| mcbot-bridge 线程池（4，daemon） | REST/MCP/SSE 处理 | 进大脑一律经 AgentRunner 的并发安全入口 |

实锤教训：GUI 不 hop 会报 `Rendersystem called from wrong thread`（曾真实发生，已修）。

## 3. 模块布局

```
agent-core/          纯 JVM（零 MC 依赖，独立构建+单测）
  llm/               Msg(sealed)/ToolCall/ToolSpec/AssistantTurn/ChatEngine/LlmClient(SSE 流式)
                     TurnSink(过程回调契约)/TurnTimings(时延打点)
  provider/          OpenAI 兼容线格式：请求体组装、tool_call 按 index 增量拼装
                     TurnBuilder(聚合器)/TurnSinkTarget(写入契约)
                     StreamingTurnReader(聚合+闭合判定)/ToolArgsScanner(配平+required 齐=可派发)
  convo/             Conversation：软水位压缩（"[对话前情提要]"前缀，保尾部）
  loop/              AgentLoop 状态机 + ToolExecutor 契约
  prompt/            PromptBuilder（基础准则+人设+技能）/SkillLoader(*.md）
  bridge/            BridgeService(REST+MCP 内核)/EventRing/BridgeBackend —— 不碰 HTTP
src/main/            公共+服务端
  body/              FakeConnection/CompanionPlayer/CompanionRoster/CompanionChunkPads/SummonService/SafeSpawn/SelfTest
  path/              DigAStar(纯算法零 MC 依赖,可单测)/DigSampler(契约)/LevelDigSampler(神圣集)/PathTask(搜索→确认→执行)
  server/            ToolRegistry/ServerTool/ServerToolDispatcher/RateGuard + tools/(9 个)
  task/              TickTask/CompanionScheduler
  common/            Envelope/McbotPayloads（两通道各一条）
  command/           /mcbot ping|summon|dismiss|list（需 OP，gamemaster 级）
src/client/          客户端
  agent/             AgentRunner(大脑宿主+事件生产者)/ClientToolDefs(模型侧描述)
  bridge/            BridgeHttp(JDK HttpServer 适配)/BridgeEvents(生产者总线)
  cfg/               ClientConfig（client.json + MCBOT_* 环境变量覆盖）
  ui/                McbotPanelScreen（G 面板）
```

## 4. 网络协议与三道闸

- 通道就两条：`mcbot:c2s`、`mcbot:s2c`，各携带一个 JSON 信封 `{kind, ...}`；
  语义演进只加 kind 不加密道。闸① 是**显式的字节尺寸闸**（不再是“靠 codec 默认上限”）：
  `C2s.CODEC` 自己读 VarInt 长度前缀并卡 `WireSize.MAX_BODY_BYTES`，超限**不读不抛**，
  交回 `C2s.OVERSIZED` 哨兵由接收处丢弃并记日志。为什么不靠原版 `STRING_UTF8`：
  它限的是**字符数**（32767，折算字节上限 98301 ≈ 96KB），且超限**抛** `DecoderException`，
  而 `Connection.exceptionCaught` 对非 `SkipPacketException` 一律关 channel——
  等价于“模型吐了一坨超大参数 → 主人被踢线”。出站 S2C 也按字节量，超限时**不是截断**
  （截断只造出非法 JSON → 对端静默丢弃 → 那个 seq 白等 90s TIMEOUT），
  而是换一条**保留 seq 的合法瘦身回执**，把“范围改小”教给模型；客户端另有发送前自检。
- **C2S kind**：`summon` / `dismiss` / `tool_call{seq,tool,args}` / `cancel` / `answer`。
- **S2C kind**：`tool_result{seq,ok,feedback,data?}` / `summon_result` / `dismiss_result` /
  `cancel_ack` / `event`（服务器主动播报，当前无生产者——留给任务进度事件）。
- **三道闸**（`ServerToolDispatcher.handle`，按序）：
  ① 尺寸/格式（`C2s.CODEC` 显式字节闸 + `Envelope.decode` 判空丢弃）；
  ② 速率：按玩家 token bucket，容量 60、补充 20/秒，超频回 `DENIED:消息过于频繁`；
  ③ 白名单 + `args` 必须是 JSON 对象 + **owner 强校验**：`tool_call` 只能作用于
  发送者名下的同伴（名册 `ownerUuid == 发送者 UUID`），无从伪造"替别人下令"。

## 5. 大脑回路（AgentLoop）

```
submit(指令) → pump → step → [压缩?] → LLM(流式) → turn
  turn 无 tool_calls → onReply(作答) → pump 下一条
  turn 有 tool_calls → 串行执行（ToolExecutor）→ 每条回执进对话 → step
叫停（cancelDirective）：清队列 + 标记；在下一个步边界生效；
  若正停在流式回答上，整轮作废（不执行其工具、不留悬挂 tool_calls）。
```

**真流式与早派发（R2-A）**：`LlmClient` 不用 `ofLines()`（行是收完才交出来的），自己实现
`BodySubscriber` 按字节增量解码、按行切分，每行到货即回调。于是"某个工具调用的参数写完了"
可以在**整轮落地之前**就知道，工具当场起跑（省下的是挂起时间）。
闭合判定两道人门：①顶层括号配平（自己维护"在不在字符串/是否被转义"）；②schema 的 required
字段在顶层全出现——只看配平会把 `{"x":1}` 这种半截参数当成品派发。
写回纪律不变：`Msg.Tool` 一律按 index 原序记账（协议要求与 `assistant.tool_calls` 严格同序），
所以等待是**顺序折叠**而不是 `allOf`（后者只保证都到、不保证按序）。
打点 `ttfb/ttft/first_tool/after_chunk/after_tool/chunks/deltas/early` 经 `onStreamStats` 出到 `[brain] llm stream`。
回滚开关：把 `AgentLoop` 的 `engine.chat(..., reactor, true)` 换回三参 `chat(...)` 即走旧路径（代码保留同语义）。

护栏数值：每指令 40 步；同一调用连续 3 次 → Nudge 换思路、5 次 → 停链——但只有
**同调用且同结果**才计数（TIMEOUT 后原参重试是合法恢复，不算打转，M4.5 吸取参考项目实战教训）。
上下文闸门 M4.5 改真数驱动：每步记 API 回报的 prompt_tokens（无则 CJK 感知估算），
超 6000 在**任务步边界**压缩；切分铁律：保留段只能从 User/Assistant 起切，绝不拆孤儿 Tool；
同名工具旧回执出站前折叠成占位（存储全量）；摘要端点连败 2 次熔断至下个指令边界。
`ToolExecutor` 在 mod 侧的实现 = AgentRunner：发 `tool_call{seq}`，等对应 `tool_result`，
90 秒无回执 → 回 TIMEOUT 教学文本。`ask_owner` 是唯一本地工具（不出客户端，见 §6）。

## 6. 桥接（neko 入口）

- 内核 `BridgeService`（agent-core）：REST `/v1/*` + MCP `/mcp` 双接口一内核；
  适配层 `BridgeHttp`（客户端）：JDK HttpServer 绑 `127.0.0.1:57121`，
  `Authorization: Bearer <token>`（token 在 `mcbot/bridge.token`，常量时间比较），
  SSE `Last-Event-ID` 从 200 条 `EventRing` 补发，15 秒心跳。
- 生命周期：JOIN 起、DISCONNECT 关；桥只服务当前进世界的主人。
- 事件四类帧：`progress`（工具回执/护栏）/ `done`（作答）/ `question`（ask_owner 反问，
  带 `question_id`）/ `state`（叫停/召唤等状态变化）。**任务级归组**：帧内 `task_id` 由
  投令时分配，`POST /v1/task` 的 `fragments` 只收同一 task_id（未标号的公共帧除外）。
- 完整契约见 `docs/BRIDGE.md`。

## 7. 身体层（假玩家）

- 身份：`offlineUuid(name)`（OfflinePlayer 公式）→ 名册（`mcbot/companions.json`）记归属；
  背包/坐标在原版 playerdata `.dat`。`placeNewPlayer` 全流程进场（FakeConnection：
  EmbeddedChannel + 丢弃一切出站发包 + 吞 disconnect；keep-alive 因出站被丢而自然失效）。
- 进场后**必须重设**游戏模式/无敌（placeNewPlayer 会重放旧存档数据），再做
  **安全落点检查**（存档点不可站 → 就近挪 → 世界出生点）。
- 每同伴一个活跃任务槽（无队列）：忙时工具层直接回 `BUSY` 教学回执。
- 挖掘：假玩家没有 connection tick，原版 `handleBlockBreakAction` 静默失效 →
  手工计时引擎：`progress += getDestroySpeed/hardness/30` 每 tick，广播
  `ClientboundBlockDestructionPacket`（-1 清除，onAbort 兜底），完成走
  `Block.getDrops`（错工具真没掉落）→ 背包吸附 → 装不下 `popResource`。
- 移动（M8 起）：move_to = DigAStar 任务。节点=落脚点（脚格+头格可通行、下格有支撑），
  边统一建模为"清两格(挖)+补支撑(放)+移动"，派生 走/跳/落/下挖/向前挖/垫脚/搭桥。
  约束全在 LevelDigSampler：神圣集（容器/工作台/床/机关本体与其支撑+任意方块实体）、
  岩浆邻接否决、起点脚下不挖、单格挖>20s 不值、搜索盒水平 64/垂直 32。
  要改世界的路先回 NEED_CONFIRM+清单，模型带 may_alter_terrain=true 重发才执行；
  执行期每 20 节点复核未来 5 节点，变了就地重规划（≤2 次）；同层走路保持 0.45 格/tick
  滑步节奏（M4 行为视觉回归）。搜索分帧：每 tick 最多展开 300 节点，主线程永不卡崩。

## 8. 配置文件与运行目录（`<gameDir>/mcbot/`）

| 文件 | 归属进程 | 说明 |
|---|---|---|
| `client.json` | 客户端 | `{base_url, model, api_key, persona}`；`MCBOT_*` 环境变量优先覆盖；缺任一必填 = 大脑不启动 |
| `companions.json` | 服务器 | 名册（uuid/name/ownerUuid/ownerName），Gson |
| `bridge.token` | 客户端 | 桥鉴权 token，首次进世界随机生成，跨重启稳定 |
| `skills/*.md` | 客户端 | 技能笔记原文拼进 system prompt |
| `autotest.flag` | 服务器 | 存在即启动约 3 秒后跑 SelfTest 并自删（仅开发） |

## 9. 交互入口速查

- 键位：**G** 开面板（`key.mcbot.panel`，MISC 分类，可重映射）。
- 聊天：`@bot <指令>`（不进服务器聊天）；整句 `停/停下/停手/取消/别干了/别做了/cancel/stop`
  = 叫停（不走模型）。
- 服务器命令（OP，gamemaster 级）：`/mcbot ping|summon <名>|dismiss <名>|list`。
- 桥/对外：`docs/BRIDGE.md` 或随包的 `dist/README-BRIDGE.md`。

## 10. 关键常数速查（改代码请同步这里）

| 位置 | 数值 |
|---|---|
| AgentLoop | 40 步/指令；nudge@3；abort@5（同调用**且同结果**才累计）；压缩闸门 6000 真 token（CJK 估算兜底）；近段保留预算 1500 token；熔断 2 次 |
| 超时 | LLM 180s；工具回执 90s；ask_owner **120s**（M4.6 由 300s 降，本行曾漂移）；桥 ask **135s**（R0 对齐：必须 > 反问 120s）；task 窗口 ≤120s |
| 闸② 速率 | 容量 60、补充 20/s（按玩家） |
| 信封 | 上限按 **UTF-8 字节**：32768（含前缀）/ 体 32765；超限入站丢弃、出站换瘦身回执 |
| 任务帽 | 默认 60s；break 60s；move 3min；wait n·20+100 tick |
| 行动参数 | 臂长 5.5（任务中 6.5 容忍）；滑步 ≤48 格、0.45 格/tick；挖掘进度公式 ÷30 |
| 寻路(R1 后) | 双帽：8000 节点 **或** 累计 CPU 400ms（先到先停）；单拍切片 6ms/300 节点；128 挖帽；放≤背包存量；搜索盒 64×32×64；单格挖 ≤20s；h=1.8×0.467×L1距体积+入柱价（**故意不可采纳**，代价上界 W×最优）；PARTIAL 下限 gain≥4；重规划 ≤2 且分帧（旧路格 ×0.7 降权，失效格周围不入集）；复核 20/5；memo 帽 262144 格（超帽退直读）；验尸 256 实查/抽 8 格/不符超 24 丢图重开≤2；dig 量化 0.25s |
| 同伴区块票(S3b) | 自定义超时票 40t（LOADING|SIMULATION，无 PERSIST）；半径 2 chunk（5×5 垫）；END_SERVER_TICK 每拍续票（先于 scheduler）；只续不撤，停续即过期自清；不设 owner 在线闸 |
| 感知(R2-S1) | classify 6 词表；ROCK_PATHS 10 路径常数（**不含**泥土沙/加工石）；ore=endsWith("_ore")；三层步长 1/2/3，名额 细列≤8/组≤10/远≤12，MAX_SAMPLES=900；坐标 `@(x,y,z) d3.2`+首行八向；准星注入≤120B（MISS/ENTITY 不注入） |
| 前缀(R2-S2) | FOLD_KEEP_TAIL=12；折叠只在 index<foldCheckpoint 冻结区；prefix reset 全库仅两事件（compaction / directive-boundary）；system 换发仅指令边界；压缩链尾 finishChain（叫停链不压） |
| 流式(R2-A) | SSE 逐行回调（自实现 BodySubscriber，增量 UTF-8 解码+跨 chunk 攒半行/半字符/CRLF）；闭合判定=顶层括号配平 **且** schema required 齐；就绪信号每 index 一生一次；工具就绪即在回调线程派发（execute 只投递不阻塞）；记账按 index 顺序折叠；同一 index 的 arguments 只许累积一次 |
| 桥 | 端口 57121、body ≤64KB、环 200、心跳 15s、线程池 **8**（R0：每 SSE 永占一线程，4 会饥饿；彻底解法归 R2-C） |
| 名册 | v1 每主人 1 同伴；名字 `[a-z0-9_]{2,16}` |

