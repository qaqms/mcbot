# R2 链路延迟整改 · 设计卡（09-08 定稿）

> 靶子：一次"挖三块石头进箱"≈8 轮/20–50s；跳数仅 100–150ms 不是主因（证据见 STATUS 09-08 节）。
> 性价比序（代理算式，采纳）：**D > C > B > A**；A/B 是 C 的放大器。目标 3 轮/6–14s（±50%）。

## 0. javap 实测（代理自证 + 主会话抽查）

- `HttpResponse$BodyHandlers.fromLineSubscriber(S)` 与 `(S, Function, String)` **存在**；
  `BodySubscribers.fromLineSubscriber/ofString(Charset)/ofPublisher` 存在。
  `BodyHandler.apply(ResponseInfo)` → **statusCode() 在体未收前可得** ⇒ 错误分流可在 handler 内做。
  `Flow.Subscription` 仅 `request(long)/cancel()`。
- `Minecraft.hitResult`（public `HitResult`）、`crosshairPickEntity`（public）——**主会话已复核**；
  `HitResult$Type{MISS,BLOCK,ENTITY}`、`BlockHitResult.getBlockPos()/getDirection()` ✓。
  客户端侧 `Level.getBlockState` 未单独 javap（实施前补，R1 主会话复核见 R1 卡 §0）。
- 主会话新证：`LevelReader.getChunk(II)` = FULL+create=true → 搜索触未加载区会同步生成（修在 R1-B）。

## A. 真流式 + tool_call 早派发（不省轮，是 C 的使能器）

- `LlmClient` 弃 `ofLines()`：自建 `Flow.Subscriber<String>`（onSubscribe `request(MAX_VALUE)`）
  交 `BodyHandlers.fromLineSubscriber(sub, s->null, "UTF_8")`；`apply(ResponseInfo)` 按状态码分流：
  200→喂 TurnBuilder；非 200→`ofString` 收错误体，复用 `summarizeError` 判 HTML → `/v1`
  **整请求重发一次**（流未开，无残留态）。
- agent-core 新 `TurnSink{onTextDelta; onToolCallReady(idx, ToolCall); onComplete(turn, err)}`；
  `ChatEngine` 加 `default chat(...,sink)` 转调旧 3 参（ScriptedEngine/既有单测零破坏）。
- **闭合判定**：TurnBuilder 对每个 PartialCall 扫**括号深度+字符串/转义态**，depth 归 0 且末字符
  `}` → 就绪；再按 ToolSpec schema 校 required 字段齐（防半截 `{"x":1}`）。
- **AgentLoop 边界**：早派发只**启动** execute 并存 `idx→future`；`Msg.Assistant` 仍等整轮落地
  才入对话；`runToolCalls` 只 await + **按 idx 原序**写 `Msg.Tool`（配对/顺序不破 = 不伤 B 前缀）。
  叫停命中 → 整轮丢弃（已派发 future 本地取消 + 发 C2S cancel），`onNotice` 记"已派发作废"。
- 打点：`[brain] llm ttfb=412ms ttft=803ms tok/s=38.4 lines=57 finish=tool_calls early=1
  prompt=3120 cached=2048`。
- 回滚：`useStreaming` 开关，false 走旧路。
- 风险：中转站不发 usage 帧 → cached 永 -1（沿用）；同轮 3 个 move_to → BUSY 噪声由 C 吸收。

## B. 前缀稳定（折叠检查点，打穿 prompt cache）

- `Conversation` 加 `foldCheckpoint`（**单调不回退**）+ `frozenFolded` 集合（折叠决定一次算定
  永不重算）；折叠只作用 `index < foldCheckpoint` 的 `Msg.Tool`；`outboundHistory()` 变纯函数。
  检查点只在**步/指令边界**推进：`foldCheckpoint = max(旧, size - FOLD_KEEP_TAIL=12)`。
- system/skills：`PromptBuilder.build` 结果进 `AtomicReference`，启动读盘一次；换发只在指令边界
  （链中不换 = 不裂前缀）；**暴露 `reloadSkills()` 失效钩子供 R3 面板热重载调用**（两卡冲突在此定死：
  R2 出缓存+钩子，R3 调钩子，persona/skills 不再每步读盘）。
- 压缩 = 合法 checkpoint：摘要仍插 index 0，视为 **prefix reset** 事件（reset 仅两处：压缩、
  指令边界归零策略），打 `[brain] prefix reset reason=compaction`；
  **`noteCompacted()` 补 `realPromptTokens=0`**（R0 项并入）；压缩从 step 关键路径挪**链尾后台**
  （省 2–5s/次）。
- 验收：新 `ConversationPrefixTest`——同历史连问 3 次，`buildBody` 的 messages 序列化后断言
  **前 90% 字节逐次相同**；真机判据 `cached/realPromptTokens ≥ 0.6` 连 4 步。
- 风险：tools 增删毁一次缓存（预期，写发布说明）；FOLD_KEEP_TAIL 太小模型看不见刚才的坐标（=12）。

## C. 受理即回执 + 完成事件开新轮（最大单项，动语义）

- 信封（**只加 kind 不加密道**）：`job_ack{seq,job_id,text}`、
  `job_event{seq,job_id,phase:progress|done|failed|superseded,tool,text,data?}`。
  **契约：一个 seq 恰好一条终局**（job_ack 或 tool_result 二选一，不留双发窗——自家客户端自控，
  见裁决）。`ServerTool.acceptanceMode()` 默认 SYNC；`move_to`/`break_block` 覆写 ACCEPT
  （status/scan/transfer/wait 保持同步，accept 反多一跳）。
- 客户端：`pending` 拆 `pendingTool`(90s) / `pendingJob`(capTicks+15s)；job_ack 即 complete → 续跑。
- **PARK 语义**：回执以常量前缀 `ACCEPTED:` 开头 ⇒ 该 call 记 in-flight job，本轮结束**不 step**
  进 PARK；`job_event(done/failed)` → 注入 `Msg.Tool(callId,…)` + `step()`（=开新轮）。
  **铁律：PARK 被解锁前（新指令/叫停），必须为每个 in-flight job 补一条
  `SUPERSEDED:`/`CANCELLED:` 合成回执**——孤儿 tool_calls 下一请求直接 400（M4.5 切分铁律延伸）。
  代际号 `generation`：pump/cancel 时 ++；事件带旧 gen → 丢弃并走补齐路径。
- 抢占：`sched.submit(...,preempt)` 顶掉旧活、向旧 seq 发 `phase=superseded`；`PathTask` 暴露
  `remaining()`，被顶时存 `lastPlan=(target,remaining,sampler快照)` **TTL 30s**，新任务 target
  逐格相同 → 跳 SEARCH 直接续节点。
- 回执模板（教模型别干等）：`ACCEPTED:我已开始「挖 (12,63,-4) 石头」编号 j7，预计约 6 秒，
  这条还没有结果——别猜、别等着，可以先回我一句话或做别的，做完我会主动报 j7。`
  同步进 `PromptBuilder` 规则与 `ClientToolDefs` 描述。
- **M5 event 水源落地**：`phase=progress` → `BridgeEvents.publish("progress",…)`（复用四类帧，
  `/v1/task` fragments 过滤不动，M6 七项不回退）。粒度：dig 每成 1 格 `挖掉 X (i/n)`；
  move 每 5 节点或 1s 取大 `走到 (x,y,z) 剩 n 格`；限速 1 帧/s/job、全局 4 帧/s（Ring 200≈50s）。
  桥池 4→8 + SSE 占线程告警（彻底解法另议）。
- 顺带修：`CompanionScheduler:95` 超时文案硬写"60 秒"而 move 帽 3600tick=3min（主会话已证，
  文案随 capTicks 计算）。
- 回滚：`accept_mode=false` 整体退回今日语义。
- 风险：PARK 与 40 步帽/打转计数交互（park 期不计步）；重复终局按 job_id 幂等去重；
  **`[m4b]` 的"BUSY 拒收"判读基准要改为"顶替"基准**（进 STATUS，别悄悄）。

## D. 感知可行动化（最便宜的第一个交付）

- `describe` 换 `classify`：container/ore/**rock**/workbench/farm/hostile；`rock` 用常数
  `ROCK_PATHS`（stone/cobblestone/deepslate*/granite/diorite/andesite/tuff…）查注册表路径，
  不做每格硬度/tag 反查。（泥土沙**暂不入** rock 目标集：是材料不是目标，防模型见土就挖；
  要当材料用走 place/transfer 的显式指令。）
- 坐标一律**绝对** `@(12,63,-4) d3.2`；feedback 首行 `我在 (x,y,z) 面朝 east`（getLookAngle 折算）。
- 分层：近环 ≤6 步长 1 细列（≤8 条）、中环步长 2（每类计数+最近 1 绝对坐标，≤10 组）、
  远环步长 3 只计数（≤12 组）、`MAX_SAMPLES=900`。体量账 ≈2.2KB ≪ 32765B（WireSize 闸保留兜底）。
  **近环步长 1 前必须实测主线程耗时**（>50ms 告警线在，超了退步长 2——实施时定）。
- **准星注入**：`submitTask` 入口把 `[我此刻盯着] cobblestone @(12,63,-4) 距3.2` 追加 user 文本尾
  （≤120B，客户端本地拼，不过网络；MISS 不注入；"发话瞬间"语义写进注释）。

## E. 轮数账（基线 8 轮/20–50s；每轮 = prefill 0.8–2s + decode 1–3s + 挂起 0–6s）

D：−3 轮 ≈ 8–14s｜C：−12–35s（挂起改 0.2s ack）+ 连发再 −2 轮｜B：−3–7s（prefill ÷3~4 ×4-5 步）｜
A：−2–4s + TTFB 体感 ~0.8s。**合计 → 3 轮/6–14s。**

## F. 步序（每步独立无头验收）

S1=D（`[r2d]` 新场景：体内嵌 3 块 STONE → scan 断言含 `stone` 且含 `@(` 绝对坐标、字节<32765）
S2=B（ConversationPrefixTest 前缀字节恒等）
S3=A（新 JUnit `SseIncrementalTest`：自起 HttpServer 5 帧隔 200ms，断言行到达间隔>150ms 且早于
    future 完成、onToolCallReady 在 [DONE] 前触发；404/HTML 自动 /v1 重试一次）
S4=C（`[r2c]`：move 提交 100ms 内拿 ACCEPTED、done 事件续跑放槽；抢占+`reused_remaining=n`；
    桥侧 **python urllib**（禁 git-bash curl，中文乱码是记过的坑）断言 fragments 含 progress；
    `[m4b]` 基准同步改写）
    **进度（09-10）**：
    - **阶段 1 = lastPlan 复用** ✅ 关账（`PlanCache` + `[m8]` A/B 实测 B 零重搜，见 STATUS）。
      与卡上 §C 的差别：走的是"**确认重发**复用"（判据＝同伴+目标+**起点**+TTL，授权态不入键、
      清单用当前世界重算），不是"**抢占续跑**"。
    - **阶段 2 = 受理即回执 + PARK** ✅ 关账（`job_ack`/`job_event` 信封、`acceptanceMode`/
      `capTicks`/`acceptSubject`、`Ledger` 保序落账 + PARK、`PendingJobs` 两段式等待、
      PARK 铁律合成回执、`accept_mode` 止血开关、教学进提示词；`[r2c]` 六项全中，单测 145/145）。
      与卡上 §C 的差别：**抢占未做**（无 `preempt`/`superseded` 服务端相位/`generation`/
      `remaining()`），**progress 帧未发**（客户端已能收，服务端无调用点），故 `[m4b]` 的
      "BUSY 拒收"基准**照旧**、`reused_remaining=n` 读数不适用。
      另：卡上 §E 的"−12–35s"**别照抄**——对严格串行的活 ACCEPT 不缩短总时长，真收益是
      "大脑不被长活占住"与"同轮后续调用不被阻塞"（后者今天受限于服务端单槽，吃到的是只读工具）。
    - **阶段 3（未完）**：抢占 + `remaining()` 续节点 + `generation` + progress 入桥限速。

**新常数（进 §10）**：FOLD_KEEP_TAIL=12｜`ACCEPTED:` 前缀常量｜job 超时=capTicks+15s｜
progress 限速 1/s/job、4/s 全局｜lastPlan TTL=30s｜ROCK_PATHS、环带步长 1/2/3、MAX_SAMPLES=900、
细列≤8/组≤10/远≤12、实体≤20｜准星注入≤120B｜桥池 8｜request(MAX_VALUE)。
