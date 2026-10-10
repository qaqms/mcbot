# mcbot 架构（as-built）

> 本文描述**已建成**的结构；施工前的完整蓝图见根目录 `mcbot-DESIGN.md`，
> 进度与 API 防漂移笔记见 `STATUS.md`。与代码不一致时，以代码为准并请修订本文。

## 1. 三进程拓扑

```
┌─ N.E.K.O 宿主及适配连接器（外部进程）
│    通过任务级接口接入（§6 桥接）
│
├─ Minecraft 客户端（本地玩家）  src/client/
│    AgentRunner：大脑宿主（agent-core AgentLoop）
│    BridgeHttp：127.0.0.1 任务桥（供连接器接入）
│    McbotPanelScreen：G 面板（任务/模型/同伴三页，滚动、固定操作区）
│    ⇅ 自定义 payload mcbot:c2s / mcbot:s2c（单 JSON 信封）
│
└─ Minecraft 服务器（联机服） src/main/
     SummonService：假玩家（ServerPlayer）生命周期
     ServerToolDispatcher：三道闸 → 工具执行
     CompanionScheduler：跨 tick 任务推进（END_SERVER_TICK）
```

硬约束：**API key 与大脑只存在于所属玩家客户端**；服务器只见工具调用的结果，不见模型；
桥只绑回环。三者各是一层信任边界。

## 2. 线程模型

| 线程 | 跑什么 | 规则 |
|---|---|---|
| 服务器主线程 | 三道闸后的一切：工具执行、Scheduler tick、假玩家 | 世界操作只在这条线 |
| 客户端主线程（渲染） | `AgentRunner.tick()` 超时巡检、面板、聊天注入 | GUI 调用必须 `mc.execute()` 到此线 |
| HttpClient 线程 | HTTP/SSE 传输与解析 | CallbackChatEngine 将流式信号和整轮结果送到客户端主线程 |
| mcbot-bridge 线程池（8，daemon） | REST/MCP/SSE 处理 | 投令/取消/回答先 hop 客户端主线程；状态可读快照；关闭时关闭线程池 |

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
  loop/              AgentLoop 状态机（含 Ledger 保序落账 + PARK）/ToolExecutor 契约
                     PendingJobs(两段式等待: seq→终局 / 受理后 jobId→事件 + 超时口径)
  prompt/            PromptBuilder（基础准则+人设+技能）/SkillLoader(*.md）
  bridge/            BridgeService(REST+MCP 内核)/EventRing/BridgeBackend —— 不碰 HTTP
src/main/            公共+服务端
  body/              FakeConnection/CompanionPlayer/CompanionRoster/CompanionChunkPads/SummonService/SafeSpawn/SelfTest
  path/              DigAStar(纯算法零 MC 依赖,可单测)/DigSampler(契约)/LevelDigSampler(神圣集)
                     PathTask(搜索→确认→执行)/PlanCache(lastPlan 复用,纯逻辑可单测)
  server/            ToolRegistry/ServerTool(acceptanceMode/capTicks/acceptSubject)
                     ServerToolDispatcher(三道闸+tool_result/job_ack/job_event)/RateGuard + tools/(10 个服务端工具)
  task/              TickTask/CompanionScheduler
  common/            Envelope/McbotPayloads（两通道各一条）
  command/           /mcbot ping|summon|dismiss|list（需 OP，gamemaster 级）
src/client/          客户端
  agent/             AgentRunner(大脑宿主+事件生产者)/ClientToolDefs(模型侧描述)
  bridge/            BridgeHttp(JDK HttpServer 适配)/BridgeEvents(生产者总线)
  cfg/               ClientConfig（client.json + MCBOT_* 环境变量覆盖）
  ui/                McbotPanelScreen / PanelLayout / PanelFields（G 面板、GUI 像素布局、完整输入/密钥掩码）
```

## 4. 网络协议与三道闸

- 通道就两条：`mcbot:c2s`、`mcbot:s2c`，各携带一个 JSON 信封 `{kind, ...}`；
  语义演进只加 kind 不加密道。闸① 是**显式的字节尺寸闸**（不再是“靠 codec 默认上限”）：
  `C2s.CODEC` 自己读 VarInt 长度前缀并卡 `WireSize.MAX_BODY_BYTES`，超限**不读不抛**，
  交回 `C2s.OVERSIZED` 哨兵由接收处丢弃并记日志。为什么不靠原版 `STRING_UTF8`：
  它限的是**字符数**（32767，折算字节上限 98301 ≈ 96KB），且超限**抛** `DecoderException`，
  而 `Connection.exceptionCaught` 对非 `SkipPacketException` 一律关 channel——
  因此模型生成超大参数可能导致玩家断线。出站 S2C 也按字节量，超限时**不是截断**
  （截断只造出非法 JSON → 对端静默丢弃 → 那个 seq 白等 90s TIMEOUT），
  而是换一条**保留 seq 的合法瘦身回执**，把“范围改小”教给模型；客户端另有发送前自检。
- **C2S kind**：`summon` / `dismiss` / `companion_status` / `tool_call{seq,tool,args}` / `cancel` / `answer`。
- **S2C kind**：`tool_result{seq,ok,feedback,data?}` / `summon_result` / `dismiss_result` / `companion_state` /
  `cancel_ack` / `event`（服务器主动播报，当前无生产者——留给任务进度事件）。
- 全局 C2S receiver 只在 mod 初始化注册一次；每条消息取当前 dispatcher，并校验所属服务器，
  不捕获第一张单人世界。停服取消 scheduler 全部槽位并清空计划缓存；遣散立即取消对应任务。
  同伴生命周期回执带结构化 `companion` 字段；JOIN 查询当前本地玩家在当前世界的同伴。
  自动 `companion_state` 同步只更新同伴页与桥公共 state，不写聊天或 transcript；
  手动召唤/遣散的操作回执保留原有反馈。
- **三道闸**（`ServerToolDispatcher.handle`，按序）：
  ① 尺寸/格式（`C2s.CODEC` 显式字节闸 + `Envelope.decode` 判空丢弃）；
  ② 速率：按玩家 token bucket，容量 60、补充 20/秒，超频回 `DENIED:消息过于频繁`；
  ③ 白名单 + `args` 必须是 JSON 对象 + **owner 强校验**：`tool_call` 只能作用于
  发送者名下的同伴（名册 `ownerUuid == 发送者 UUID`），无从伪造"替别人下令"。

## 5. 大脑回路（AgentLoop）

```
submit(task_id, 指令) → pump → onTaskStarted → step → LLM(流式) → turn
  turn 无 tool_calls → onReply(展示) → onTaskFinished(COMPLETED) → [链尾压缩?] → pump
  turn 有 tool_calls → 串行执行（ToolExecutor）→ 每条回执进对话 → step
cancelTask(id)：匹配活动/排队项；0 全部。活动项立即终止并合成补账，旧回调按代际号丢弃。
close()：取消全部并拒绝后续 submit；配置重载/断线先关闭旧 loop 再丢引用。
```

### 5.1 受理即回执与 PARK（R2-S4 阶段 2）

```
turn 有 tool_calls → runTools 只把结果填进 Ledger（不直接写对话）
                     ↓
   Ledger.flush()：按 index 升序写出**已到达**的最长前缀
                     ↓
   全部落账 → step()（照旧）
   还有"已受理未到达" → parked=true，**不 step**（PARK）
                     ↓
   job_event(done/failed/cancelled/superseded) → Ledger.resolve → flush → step()
```

要点（细节契约在 `docs/BRIDGE.md` §5.1，工具侧授权在 `docs/TOOLS.md` §4.1）：

- **受理不等于结果**：服务端的 `job_ack` 只表示"开始了"，回执里带 `accepted=true` 字段。
  Ledger 记下 `jobId` 但**槽位保持空**——把它当结果写进对话，就等于告诉模型事情做完了。
- **落账必须保序**：一步里"第 0 条在跑、第 1 条当场有结果"是常态，而 `tool` 消息必须与
  `assistant.tool_calls` 严格同序，所以第 1 条也不能先写。Ledger 只写"已到达的**前缀**"。
- **PARK 不计步**：`steps` 只在 `step()` 里涨，所以等一条 3 分钟的移动不会吃掉 40 步帽。
- 部分长活结束而整轮仍 PARK 时，onParked 更新剩余等待数；全部落账后才解除 PARK。
- **PARK 的铁律**：解锁（叫停/新指令）之前，必须给**每一条** in-flight 的 `tool_call`
  补一条合成回执（`CANCELLED:`/`SUPERSEDED:`）。少一条，下一次请求里那个 tool_call 就有
  id 找不到配对的消息 → OpenAI 400，整段历史作废。取消/顶替的唯一补账实现是 `AgentLoop.stopActive`。
- 客户端侧两段式等待收在 `PendingJobs<T>`：`seq → 终局` / 受理后转 `jobId → job_event`，
  凭据（那个 future）跟着转段搬；**超时口径只有一处**：受理后的上限 = 服务端报的
  `cap_ticks`×50ms + 15s 余量（`JOB_GRACE_MS`）。
- 止血开关：`client.json` 的 `accept_mode`（默认 true）。关掉 = 客户端不在 `tool_call` 里点
  `accept`，服务端对 ACCEPT 工具仍走同步回执、大脑也不 PARK，整条链退回旧语义（不用换服务端）。

**真流式与早派发（R2-A）**：`LlmClient` 不用 `ofLines()`（行是收完才交出来的），自己实现
`BodySubscriber` 按字节增量解码、按行切分，每行到货即回调。于是"某个工具调用的参数写完了"
可以在**整轮落地之前**就知道，工具当场起跑（省下的是挂起时间）。
闭合判定两道人门：①顶层括号配平（自己维护"在不在字符串/是否被转义"）；②schema 的 required
字段在顶层全出现——只看配平会把 `{"x":1}` 这种半截参数当成品派发。
写回纪律不变：`Msg.Tool` 一律按 index 原序记账（协议要求与 `assistant.tool_calls` 严格同序），
所以等待是**顺序折叠**而不是 `allOf`（后者只保证都到、不保证按序）。
传输层在响应头到达时记录 `ttfb`，消费文本增量/闭合工具时记录 `ttft/first_tool` 与计数。
最终尝试（成功或失败）的 `TurnTimings.Snapshot` 经 `TurnSink.onTimings`、`CallbackChatEngine`
同一有序队列送到当前 StepReactor，在整轮处理之前冻结统计；取消/关闭后的旧流不发布统计。
打点 `ttfb/ttft/first_tool/after_chunk/after_tool/chunks/deltas/ready/early/first_dispatch/stream`
经 `onStreamStats` 出到 `[brain] llm stream`。口径不能混用：

- `chunks` 为非终止 SSE data 行（包含 finish/usage/错误/非法载荷，排除心跳和 `[DONE]`）；
  `ResponseDiagnostics.data` 包含终止 data 行，因此通常多一行，不是统计矛盾。
- `ready` 为传输层已闭合的工具数；`first_tool` 为首个工具就绪时间，与 index 0 的实际派发不等价。
- `early` 为整轮处理前通过就绪回调实际调用执行器的次数（只限 index 0），
  不表示服务端已受理，也不保证网络传输仍未结束。
- `first_dispatch` 为最终 HTTP 尝试起点到首次调用执行器，包含客户端队列延迟；
  `stream` 为请求到传输完成的耗时，不含随后客户端排队。若前者晚于后者，不能宣称省下流式等待。
- 未发生的时间为 -1；失败也报告已有统计，失败轮的 cache_waste 为 -1。
  只实现旧接口、不提供快照的引擎不伪造传输时间；观测回调异常不改变任务结果。
  既有一次 `/v1` 换道每次重建计时器，仅交付最终尝试快照，不混入前次耗时和计数。

回滚开关：把 `AgentLoop` 的 `engine.chat(..., reactor, true)` 换回三参 `chat(...)` 即走旧路径（代码保留同语义）。

护栏数值：每指令 40 步；同一调用连续 3 次 → Nudge 换思路、5 次 → 停链——但只有
**同调用且同结果**才计数（TIMEOUT 后原参重试是合法恢复，不算打转，M4.5 吸取参考项目实战教训）。
上下文闸门 M4.5 改真数驱动：每步记 API 回报的 prompt_tokens（无则 CJK 感知估算），
超 6000 在**任务步边界**压缩；切分铁律：保留段只能从 User/Assistant 起切，绝不拆孤儿 Tool；
同名工具旧回执出站前折叠成占位（存储全量）；摘要端点连败 2 次熔断至下个指令边界。
`ToolExecutor` 在 mod 侧的实现 = AgentRunner：发 `tool_call{seq}`，等对应 `tool_result`
（或先来的 `job_ack` → 转等 `job_event`）；普通工具 90 秒无回执 → 回 TIMEOUT 教学文本，
长活按服务端报的 `cap_ticks` 算上限（见 §5.1）。`ask_owner` 是唯一本地工具（不出客户端，见 §6）。

反问由 AgentRunner 绑定 task_id 与进程内递增的 question_id，回答必须使用原编号且只消费一次。
tick 和回答入口都检查 120s 期限；过期回失败回执，重复/未知/已取消问题拒绝回答。
回答确认先发布 state，再兑现工具 future 继续原任务；无有效问题的游戏内回答不创建新任务。
重载/关闭先清理旧 loop 和问题，再建立新历史，关闭可重复调用。
已关闭 runner 拒绝 reconfigure，不再保存配置或显示重启提示。
只读检查显示另外绑定大脑代际，防止同一个 runner 重载后显示旧检查的回执。
客户端服务通过内部 ClientServices 边界提供模型、提示词、时钟、聊天、取消、配置保存、
客户端队列与 C2S 网络副作用；
生产使用 MinecraftServices，离线回归替换这些边界并直接测试真实 AgentRunner，
不启动 Minecraft、不写玩家配置、不调用模型端点。

McbotClient 的 JOIN/DISCONNECT/S2C 交由 ClientSession 持有当前 runner：
退出先关闭旧 runner、再关桥、最后移除 runner；加入建立新 runner、查询同伴后启动桥。
重复加入先收尾旧会话；已排队 S2C 同时校验接收时捕获的 runner 身份。
配置重载复用 runner 和桥，新进世界更换 runner 与桥。

## 6. 桥接（连接器入口）

mcbot 提供任务桥及 MC agent 执行能力；连接器负责宿主侧任务适配、用户回答与事件展示。
连接器以外部任务契约为集成边界，不依赖内部游戏协议或寻路实现。

- 内核 `BridgeService`（agent-core）：REST `/v1/*` + MCP `/mcp` 双接口一内核；
  适配层 `BridgeHttp`（客户端）：JDK HttpServer 绑 `127.0.0.1:57121`，
  `Authorization: Bearer <token>`（token 在 `mcbot/bridge.token`，常量时间比较），
  SSE `Last-Event-ID` 从 200 条 `EventRing` 补发，15 秒心跳。
- 外部任务契约 v1.0：BridgeContract 共享输入 Schema 给 MCP discovery；
  status 与 SSE data 带 contract_version/session_id，桥重建换 session、事件编号重新从 1 起。
  REST/MCP 严格参数类型，安全 error_code；body 超限为 413。SSE 补发与订阅在同一锁内排序，
  不把旧桥游标带进新世界；无持久任务查询/幂等提交，超时与连接丢失不能自动重发。
- 生命周期：JOIN 起、DISCONNECT 关；桥只服务当前客户端的本地玩家。
- RunnerBackend 的投令/回答/取消进入客户端队列后再检查当前 runner 身份，
  防止排队命令跨重连作用于新会话；离线测试注入同一查询/队列边界，
  使用实际 backend 与本地 HTTP/SSE，不替代真实连接事件与服务端停手证明。
- 事件四类帧：progress（工具回执/护栏）、done（唯一任务终态，含 status）、question（反问，
  带 question_id）、state（queued/running/PARK/回答确认/公共生命周期）。仅匹配任务的 done
  结束窗口；task_id=0 为公共事件，不结束其他任务。状态与取消详见桥契约。
- 任务编号在投令时分配，但 current_task 只在 onTaskStarted 更新；排队不改变活动任务归属。
  ask 等答由 TaskReplies 按任务编号配对，不取其他任务的回复。
- fragments 兼容收集本任务及公共事件的所有非空 text，不是 progress 专用字段；
  结构化处理依赖 SSE。完整权威契约、机器 Schema/示例路径及连接器验收见 `docs/BRIDGE.md`。

## 7. 身体层（假玩家）

- 身份：`offlineUuid(name)`（OfflinePlayer 公式）→ 名册（`<世界目录>/mcbot/companions.json`）记归属；
  背包/坐标在原版 `playerdata/<uuid>.dat`，名册不备份身体数据。
  **1.21.11 的 `placeNewPlayer` 不负责加载存档**：SummonService 先调用
  `PlayerList.loadPlayerData(NameAndId)`，以 `TagValueInput` / `ServerPlayer.load` 恢复身体，
  用 `ServerPlayer.SavedPosition.MAP_CODEC` 选择存档维度，再 `snapTo` 确定入场坐标/朝向。
  之后才通过 `placeNewPlayer` 注册身体（FakeConnection：EmbeddedChannel + 丢弃出站发包 +
  吞 disconnect；不在 ServerConnectionListener 的连接表，因此没有 connection tick/keep-alive）。
- 进场后**必须重设**游戏模式/无敌，以覆盖存档中的值。服务器重进在**身体实际维度**做
  **安全落点检查**（存档点不可站 → 就近挪 → 该维度出生点）；缺失/不可用维度退回主世界出生点。
  显式召唤仍在原有主人附近/主世界出生点进场，读取同 UUID 的旧背包但不沿用旧位置。
  遣散与停服均走 `PlayerList.remove`，该方法先保存玩家再移除，不另外维护第二份身体存档。
  主世界/下界的跨进程位置、朝向、非空背包及遣散再召唤已有专项 SelfTest 正证；
  单人同进程退出世界再进入已有位置、非空背包占用与手持物正证，
  完整背包对照和整客户端重启已由维护者确认实测，原始验收记录待补齐，
  不将此前局部日志扩写为完整通过证据。坐骑/在途末影珍珠不在本轮恢复验收范围。
- `status` 只返回位置、生命/饥饿、36 格背包占用、手持物与着火状态，不枚举完整背包。
  `inventory` 额外枚举逐槽物品 ID/数量/耐久及装备映射槽，feedback 和 data 都带明细；
  重进世界重建历史后可重新查看，不能凭占用数还原其他物品清单。
- `equip` 仅切换主手：快捷栏选中或背包与当前选中槽交换原始堆栈；
  保留组件和数量，满背包可交换；scheduler 忙时拒绝切换，inventory 仍可读。
  1.21.11 Inventory 有 36 个存储槽和 7 个装备映射槽（总 43），主手只选 0-8，
  equip 来源范围 0-35，不把装备槽当可选快捷栏。
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

## 8. 配置文件与运行目录

| 文件 | 归属进程 | 说明 |
|---|---|---|
| `<gameDir>/mcbot/client.json` | 客户端 | `{base_url, model, api_key, persona}`；`MCBOT_*` 环境变量优先覆盖；缺任一必填 = 大脑不启动 |
| `<世界目录>/mcbot/companions.json` | 服务器 | 该存档的名册（uuid/name/ownerUuid/ownerName），Gson；同一存档重进恢复，不跨存档召唤 |
| `<gameDir>/mcbot/bridge.token` | 客户端 | 桥鉴权 token，首次进世界随机生成，跨重启稳定 |
| `<gameDir>/mcbot/skills/*.md` | 客户端 | 技能笔记原文拼进 system prompt |
| `<gameDir>/mcbot/autotest.flag` | 服务器 | 存在即启动约 3 秒后跑 SelfTest 并自删（仅开发） |
| `<gameDir>/mcbot/autotest-stop.flag` | 服务器 | 开发验收链结束后自删并正常停服；未设置则保持运行 |
| `<gameDir>/mcbot/autotest-persistence-seed.flag` | 服务器 | 独立身体恢复验收第一进程：设置主世界/下界样本，正常停服；只允许 `mcbot-persistence-*` 开发世界 |
| `<gameDir>/mcbot/autotest-persistence-verify.flag` | 服务器 | 独立第二进程：对照实际 UUID `.dat` 与在线身体，核验后测试遣散再召唤，正常停服 |
| `<gameDir>/mcbot/autotest-inventory.flag` | 服务器 | 独立背包/主手交换专项；仅允许新 `mcbot-inventory-*` 开发世界，拒绝并存普通/恢复测试 flag，结束正常停服 |

旧实例级名册仅在当前世界尚无名册时迁移：必须有同 UUID 的 `playerdata/*.dat` 才导入，
迁移结果（包括空列表）写入当前世界，旧文件保留且不修改。匹配原版玩家数据证明它曾在此世界出现，
不能区分旧 bug 造成的误重进；此类既有伙伴可在该世界正常遣散。

## 9. 交互入口速查

- 键位：**G** 开面板（`key.mcbot.panel`，MISC 分类，可重映射）。
- 面板：任务页派发/停止与换行记录；模型页完整配置/安全错误提示/连接测试；同伴页召唤/遣散。
  模型/记录区域可滚动，切页与 resize 复用输入控件保留草稿；仅完整落入视口的表单控件可交互。
  密钥通过 EditBox.addFormatter 星号显示，覆写读屏消息避免朗读原值。
- 查看状态/扫描附近直接派发白名单只读游戏工具，不调用模型、不生成 agent task，
  不干扰当前任务；同伴页显示当前世界的生命周期回执。未召唤仍由 owner 闸拒绝工具。
- 模型页连接测试：ModelConnectionTest 使用独立 LlmClient，仅声明 connection_probe，
  本地补配对工具回执后再请求最终文字，游戏工具不参与，配置不自动保存。
  非 200 为 LlmFailure（HTTP 状态/网页类别），未知异常不回显原文；200 空流不作为成功作答。
- 模型通信当前仅实现 OpenAI 兼容 Chat Completions SSE；模型名称可自定义，
  但尚未实现 Responses 或 Anthropic Messages。LlmClient 向宿主回调 ResponseDiagnostics：
  每次 HTTP 尝试只包含状态码、固定格式/结束原因/服务错误枚举及字节/帧/解析错误计数，
  不含 URL、请求正文、密钥或远端文本。RequestDiagnostics 在发请求前统计实际出站 JSON：
  角色/规模、工具配对/参数结构及流式选项；只含枚举与数值，不保留内容或内容哈希。
  任务写 `[brain] llm request/response`，面板测试写 `[model-test] llm request/response`，
  按本进程唯一 request 编号配对，避免相邻行误配或依赖游戏未收集的 System.Logger。
  ServiceErrorDiagnostics 将 error.code/type/param/status 映射到本地固定类别，
  错误文本匹配只记 MESSAGE_HINT，未知值 UNKNOWN，不回显原文；普通 JSON 错误诊断
  捕获上限 16384 字符，超限单独标记。此诊断不新增其他模型协议，也不改出站请求。
  HTTP 200 内的 error 帧单独报服务错误（即使此前有部分文字也不算正常成功），
  error 出现后后续帧不再触发新工具，已早派发动作不能回滚；200 HTML 报地址/网页问题。
  空响应仍失败、不自动重试，单次失败不是不兼容的证明。
- 聊天：`@bot <指令>`（不进服务器聊天）；整句 `停/停下/停手/取消/别干了/别做了/cancel/stop`
  = 叫停（不走模型）。
- 服务器命令（OP，gamemaster 级）：`/mcbot ping|summon <名>|dismiss <名>|list`。
- 桥/对外：`docs/BRIDGE.md` 或随包的 `dist/README-BRIDGE.md`。

## 10. 关键常数速查（改代码请同步这里）

| 位置 | 数值 |
|---|---|
| AgentLoop | 40 步/指令；nudge@3；abort@5（同调用**且同结果**才累计）；压缩闸门 6000 真 token（CJK 估算兜底）；近段保留预算 1500 token；熔断 2 次 |
| 超时 | LLM 连接 15s、任务请求 180s、模型页连接测试请求 30s（连接限制不等于所有响应头等待上限）；工具回执 90s；**长活**按服务端 `cap_ticks`×50ms+15s（move 3min⇒195s）；ask_owner **120s**；桥 ask **135s**（必须 > 反问 120s）；task 窗口 ≤120s |
| 闸② 速率 | 容量 60、补充 20/s（按玩家） |
| 信封 | 上限按 **UTF-8 字节**：32768（含前缀）/ 体 32765；超限入站丢弃、出站换瘦身回执 |
| 任务帽 | 默认 60s；break 60s；move 3min；wait n·20+100 tick |
| 行动参数 | 臂长 5.5（任务中 6.5 容忍）；滑步 ≤48 格、0.45 格/tick；挖掘进度公式 ÷30 |
| 寻路(R1 后) | 双帽：8000 节点 **或** 累计 CPU 400ms（先到先停）；单拍切片 6ms/300 节点；128 挖帽；放≤背包存量；搜索盒 64×32×64；单格挖 ≤20s；h=1.8×0.467×L1距体积+入柱价（**故意不可采纳**，代价上界 W×最优）；PARTIAL 下限 gain≥4；重规划 ≤2 且分帧（旧路格 ×0.7 降权，失效格周围不入集）；复核 20/5；memo 帽 262144 格（超帽退直读）；验尸 256 实查/抽 8 格/不符超 24 丢图重开≤2；dig 量化 0.25s |
| 同伴区块票(S3b) | 自定义超时票 40t（LOADING|SIMULATION，无 PERSIST）；半径 2 chunk（5×5 垫）；END_SERVER_TICK 每拍续票（先于 scheduler）；只续不撤，停续即过期自清；不设 owner 在线闸 |
| 感知(R2-S1) | classify 6 词表；ROCK_PATHS 10 路径常数（**不含**泥土沙/加工石）；ore=endsWith("_ore")；三层步长 1/2/3，名额 细列≤8/组≤10/远≤12，MAX_SAMPLES=900；坐标 `@(x,y,z) d3.2`+首行八向；准星注入≤120B（MISS/ENTITY 不注入） |
| 前缀(R2-S2) | FOLD_KEEP_TAIL=12；折叠只在 index<foldCheckpoint 冻结区；prefix reset 全库仅两事件（compaction / directive-boundary）；system 换发仅指令边界；压缩链尾 finishChain（叫停链不压） |
| 流式(R2-A/F0) | SSE 逐行解析；闭合判定=顶层括号配平且 required 齐；客户端 CallbackChatEngine 送主线程；仅 index 0 早派发，后序延迟串行；记账按 index 原序；arguments 只累积一次 |
| 受理/长活(R2-S4) | `ACCEPTED:` 前缀（agent-core 常量，唯一真源）；受理后等待上限 = `cap_ticks`×50ms + 15s（`PendingJobs.JOB_GRACE_MS`）；ACCEPT 工具仅 move_to(3600tick)/break_block(1200tick)，其余 SYNC；单终局（一个 seq 只会收到 result **或** ack）；lastPlan TTL 30s（判据：同伴+目标+起点）；`accept_mode` 默认 true（止血开关） |
| 桥 | 端口 57121、body ≤64KB、环 200、心跳 15s、线程池 **8**（R0：每 SSE 永占一线程，4 会饥饿；彻底解法归 R2-C） |
| 名册 | v1 每位玩家 1 同伴；名字 `[a-z0-9_]{2,16}` |
