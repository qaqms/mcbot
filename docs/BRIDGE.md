# 连接器与 MC agent 任务级契约（v1.0）

> 本文是连接器接入与接口维护的唯一权威规格，2026-10-09 固定 v1.0 基础接口。
> 接口已通过自动化回归，真实 MC + N.E.K.O 联合验收仍待完成。
> 快速上手版见 `dist/README-BRIDGE.md`，接入与验收步骤见本文 §6。
> 内核：`agent-core/.../bridge/BridgeService`；HTTP 适配层：`src/client/.../bridge/BridgeHttp`。

## 0. 固定范围与兼容规则

**模块职责**：
- **mcbot** 提供 MC agent 本体、游戏工具执行及任务桥，维护本契约、Schema、示例与桥接回归测试。
- **连接器** 适配 N.E.K.O 等宿主的任务入口、用户回答与结果展示，处理任务关联、事件消费和会话重连。
- **联合验收** 按 §6 验证完整任务链路；接口变更需协调兼容方案，不能仅以单侧测试代替联调。

只固定外部任务层：**投递 → 状态/事件 → 必要时反问与回答 → 取消或最终结果**。
连接器不调用原子游戏工具，不依赖 AgentLoop、C2S/S2C、寻路或模型供应商的内部协议。
`ask` 保留为同步等待任务回答的兼容入口，不是新增闲聊功能。召唤/遣散仍走游戏面板或命令，
本版没有桥接召唤接口；已有伙伴才能验游戏动作，brain_enabled=true 也不保证模型服务正常。

**五部分基础契约**：

| 部分 | v1.0 已固定 | 保证边界 |
|---|---|---|
| 基础能力 | task/status/cancel/question-answer/events；ask 兼容入口 | 不暴露原子工具/桥接召唤，不承诺新增技能或内部细粒度 progress |
| 字段与结果 | Schema 的类型、必填/可选、默认、错误码及四种终态 | completed 是正常作答，不是目标成功证明；未知字段忽略 |
| 事件规则 | task_id 归组、唯一 done、SSE 顺序/游标/去重 | 窗口 fragments 仅展示；200 条补发不是持久队列 |
| 生命周期 | session_id、退出世界/重载/取消/问题过期 | 旧任务不跨世界续跑；断线结果未知，不能自动重投 |
| 契约测试 | mcbot 的 Bridge/AgentLoop/TaskReplies 与本地 HTTP 回归 | 连接器按 §6 验证接入行为；真实 MC + N.E.K.O 链路另行联合验收 |

各模块可以独立迭代内部实现，但每次改动须保持这些可观察行为并通过相应契约回归。
仅增加可选字段可在 v1 内演进；新能力先补约定/示例/测试；删除字段、改变结果或事件语义、
把可选字段变必填等破坏性变更须发布新版本，并提前与接入方协调迁移方案；内部重构不应静默改变契约。

机器可读文件（UTF-8，随 agent-core JAR 打包）：

- Schema：`agent-core/src/main/resources/com/neko/mcbot/agentcore/bridge/bridge-v1.schema.json`，
  JSON Schema draft 2020-12，按 `$defs/taskInput|askInput|answerInput|cancelInput|statusInput`
  校验输入，按 `$defs/taskResult|askResult|okResult|error|status|event` 校验输出。
  根对象只组织定义，不用于直接校验所有 payload。MCP tools/list 直接复用这些输入定义。
- 示例：同目录 `bridge-v1.examples.json`；包含投令/回答/取消、状态快照、事件序列及窗口返回。
  示例用替身数据，不证明某项游戏动作已经真机验收。
- 回归：`BridgeContractTest`、`BridgeServiceTest`、客户端 `BridgeHttpContractTest`。

冻结既有路径、五个工具名及字段含义；可以增加可选字段，连接器必须忽略未知字段。
不删除或改义已有字段；破坏性变更另开版本并提前协调接入迁移。`contract_version:"1.0"` 出现在 status
与每个 SSE data 中。旧包没有该字段时只作为旧版兼容接入，不当作 v1.0 验收通过。
MCP initialize 的 `protocolVersion:"2025-03-26"` 是既有传输协议值，与任务契约版本不是一回事；
本桥不是通用 MCP Streamable HTTP 服务（无会话协商、批请求及其它通知支持）。

v1.0 相对旧版的兼容变更：参数类型错误不再静默转字符串/截小数/取默认；REST 返回 400，
MCP 返回工具错误。过期问题的 MCP 回答改为 isError=true；保留错误文本并新增 error_code。
请求体超限修为确定的 413（旧实现直接断开连接，旧文档的 500 不准确）。

## 1. 监听与鉴权

- 地址：`http://127.0.0.1:57121`（只绑回环，绝不 0.0.0.0；本地 HTTP，无 TLS）。
- 生命周期：**客户端进世界起、退世界关**；桥只服务当前客户端的本地玩家（一台机开多个
  1.21.11 客户端会撞端口——v1 已知边界）。
- 鉴权：`Authorization: Bearer <token>`；token = `<gameDir>/mcbot/bridge.token`
  文件内容（首次随机生成、跨重启稳定；G 面板模型页可复制令牌，底部仅显示端点，不展示 token）。
  校验用常量时间比较；不合法一律 401。SSE 允许 `?token=`（EventSource 发不了 header）。
- JSON 请求/响应使用 UTF-8；建议请求头 `Content-Type: application/json`。
- 请求体上限 **65536 字节**，超限 413。401/413 时不会提交任务。
- 先鉴权再执行；OPTIONS 仅空返回 204，不执行任何任务。token 不进入日志或共享文档；
  能带 header 时优先 header，SSE query token 同样是凭据，不记录完整 URL。

## 2. REST

| 方法/路径 | 请求体 | 成功响应 | 失败 |
|---|---|---|---|
| `POST /v1/task` | `{"text":"…","wait_s":8}` | `{"task_id":N,"done":bool,"status":"completed|failed|cancelled|superseded","fragments":["…"]}`（未结束时不带 status） | 400 参数错误；内部错误 500，受理结果可能未知 |
| `POST /v1/task/{id}/cancel` | — | `{"ok":bool}`；id=0 取消全部 | 未知或已结束任务 ok=false |
| `POST /v1/ask` | `{"text":"…"}` | `{"answer":"…"}`，只返回本次投令的最终作答 | **504**（135s 无作答）/400；任务取消、失败、顶替为 500 |
| `POST /v1/answer` | `{"question_id":"qN","text":"…"}` | `{"ok":true}` | 400 参数错误；404 无此问题/已回答/已过期 |
| `GET /v1/status` | — | 见下 | — |
| `GET /v1/events` | — | SSE 流 | 401 |

**输入规则**：text 和 question_id 必须是非空字符串（不接受 number/bool/null）。
空白判定按 JDK 21 String.isBlank；Schema 用显式 Unicode 范围保持一致，避免不同正则引擎
对 `\S` 的解释差异（例如全角空格为空白，NBSP 不为空白）。
wait_s 是可选整数，缺省 8，超出范围夹到 0..120；不接受字符串、非整数数值或 null。
MCP task_id 是可选非负 64 位整数，缺省 0；拒绝负数、非整数数值、字符串、null 和越界值。
REST cancel 的路径 id 同样为非负 64 位十进制整数。未知字段忽略，不用于选世界/同伴。
task/ask 的指令原文不会由桥裁剪或改写；客户端仍可能附加当前准星提示供大脑理解上下文。

```jsonc
// /v1/status：并发安全观测值，不是跨所有计数的原子事务快照
{"contract_version":"1.0","session_id":"d10ace27-136d-4f91-8ad9-a4a50e40538f",
 "in_game":true,"brain_enabled":true,"model":"example-model",
 "companion":"aimi","current_task":3,"queued_tasks":1,"pending_asks":0,
 "pending_tools":1,"pending_jobs":0,"pending_questions":0,"parked":false,
 "parked_jobs":0,"accept_mode":true,"late_results":0}
```

status 必有 contract_version/session_id/in_game/brain_enabled/companion；
companion="" 表示尚未确认当前伙伴。其他字段为诊断字段，关闭中的降级快照可能省略，
缺失表示“不可用”，不要擅自解释为 0。current_task=0 是没有活动任务；
queued_tasks 是未启动任务数，pending_* 是等待项数，parked/outstanding 不是完成标识。
本版没有按 task_id 查询历史终态或任务列表的接口，status 不能找回丢失的 done。

**task 窗口语义**：投令前取事件游标，投令后观察最长 wait_s 秒；
**仅见到与请求编号严格匹配的 done 事件**才 done:true 提前返回。
`state`（排队、启动、PARK、回答确认、召唤）一律不终止等待；公共 `task_id=0`
事件也不能结束别的任务。`wait_s=0` 不等待，但仍收集投令时已同步产生的事件。
窗口结束而 `done:false` 表示仍需从 `/v1/events` 追踪，不是任务失败。
wait_s 是响应收集窗口，不是任务执行超时；未配置大脑也可返回 HTTP 200 + done=true/status=failed。

**fragments 的兼容语义**：按观察顺序收集本任务以及公共 task_id=0 事件中的非空 text，
不限 progress；可能含 question、state、最终 done 文本。其他任务不收；
遇本任务首个 done 后不再收后续事件。它是无类型的展示片段，不是最终回答/进度专用字段，
不能从片段猜状态、去重或代答问题。question_id、tool/ok 等结构化信息只从 SSE 获取。
窗口和 SSE 可包含同一事件，不要把 fragments 当另一份业务事件再执行一遍。

**取消范围**：正数 id 只取消该活动或排队任务；取消排队项不影响活动项。0 或 MCP
缺省 id 取消活动项和全部排队项；不存在/已结束的编号返回 `ok:false`，不会停掉其他任务。
`ok:true` 是客户端逻辑取消已生效并已发起身体叫停，不是服务器已经确认停止的保证。
重复取消已结束任务为 ok=false，连接器不得因此把其它任务一起停掉。

**ask 的边界**：每次调用同样创建一条任务，只等该任务最终作答；不返回 task_id。
135s 超时只释放 HTTP 回答等待项，**不自动取消正在执行的任务**，也不保证任务未曾执行。
因此连接器主通路用 task + SSE；不要用 ask 超时后再次投相同任务作补偿。

**REST 错误体**：`{"error":"安全可读提示","error_code":"固定类别"}`，
错误提示不是机器分支条件，不回显异常原文；answer 404 还带 ok=false。

| HTTP | error_code | 含义 |
|---|---|---|
| 400 | INVALID_REQUEST | JSON/参数类型或必填字段错误，未提交本次任务 |
| 401 | UNAUTHORIZED | token 缺失或不正确 |
| 404 | NOT_FOUND | 未知路由或已无等待的问题 |
| 413 | BODY_TOO_LARGE | 请求体超过 65536 字节 |
| 504 | ANSWER_TIMEOUT | ask 等答超时，任务可能仍执行 |
| 500 | TASK_FAILED | ask 任务未正常完成 |
| 500 | INTERNAL | 内部/会话调度错误，操作结果可能未知 |

客户端主线程桥命令调度等待最多 5s；超时不是“不曾执行”的保证。HTTP 断开、
请求超时或 500 后都不自动重发 POST。本版没有 idempotency_key 或可保证只执行一次的提交协议。

## 3. SSE 事件模型

帧格式（id/event/data 加空行；每个 data 为单行 JSON）：

```
id: 17
event: progress
data: {"contract_version":"1.0","session_id":"d10ace27-136d-4f91-8ad9-a4a50e40538f","ev":"progress","task_id":3,"text":"status 回执","tool":"status","ok":true}

```

| event | data 字段 | 出处 |
|---|---|---|
| `progress` | `task_id,text,tool?,ok?,job_id?` | 工具最终回执、长活受理通知、护栏 notice（带"（护栏）"前缀） |
| `done` | `task_id,text,status` | 该任务唯一终态；正常作答、故障、取消或顶替 |
| `question` | `question_id,text,task_id` | 同伴 `ask_owner` 反问 |
| `state` | `task_id,text?,status?,parked?,outstanding?` | queued/running、PARK、回答确认；召唤/遣散/服务器叫停回执用公共 task_id=0 |

每个 data 都必含 contract_version/session_id/ev/task_id；ev 与 SSE event 一致。
progress/done/question 的 task_id 为正数；state 可以是公共 0。问答按当前 session_id
与 question_id 配对，任务按 session_id + task_id 归组；不要用文本或 tool/job_id 代替 task_id。
工具回执 progress 的 ok 表示该工具结果；长活受理 progress 不带 ok，仅说明收到受理，
job_id 是内部工作编号，不是 task_id。progress 不保证是实时百分比，也不保证按秒出现。

**终态 status**：
- `completed`：大脑正常结束并作答；不代表自然语言目标已经被独立验证成功，应结合 text/工具回执判断。
- `failed`：大脑未配置、模型调用失败或护栏中止。
- `cancelled`：用户取消、配置重载或断线关闭。
- `superseded`：PARK 时收到新指令，旧活动项及其排队项被替换。

连接器必须按 `task_id` 归组，以 `done` 为终态，不能根据 `state` 或文字猜测结束。
`current_task=0` 表示无活动指令；任务编号在同一客户端进程内跨重连递增，不是跨进程持久 ID。
question_id 在当前客户端进程内递增，但只在对应问题未超时/未回答且任务未结束时有效。
回答必须原样使用 question 事件提供的 question_id，不能添加符号或补零改写编号。
反问等待 120s，由游戏 tick 巡检，回答入口也检查期限；即使下一次 tick 尚未巡检，
超过期限的回答仍返回 NOT_FOUND，并给大脑一条超时失败回执。
超时后大脑可继续，不等于整个任务自动失败。
回答成功只表示已接收，后续是否完成仍看 done；重答、取消后答、重载后答返回 NOT_FOUND。
回答确认 state 先于该回答触发的工具回执 progress 或后续任务终态。
queued → running → done 是通常顺序，不是每次都有的强制状态机：
大脑未上线可直接 done(failed)；排队项取消可直接 done(cancelled)。
PARK 时新投令会顶替旧活动项及旧排队项，发 done(superseded)，不是服务端抢占/续跑。

旧版 `done` 没有 status 时，**窗口响应**兼容视为 completed；连接器读取旧 SSE 时不得
在没协商的情况下据此宣称目标成功。v1.0 新生产的 done 总是携带明确 status。
断线会关闭桥与当前会话，但不能保证最后的取消事件能送到正在断开的 SSE 连接，连接器需把
连接丢失视为未知状态并重新查询，不能自行报成功。

- **session_id 是桥实例标识**：同一世界同一次桥运行期间稳定，配置重载不变；
  退世界/进另一世界/重启后新建桥时改变，不是世界存档 UUID，也不是可续跑任务凭据。
  每个新桥的 SSE id 从 1 开始，不能把旧会话游标带到新桥。
- 环形缓冲 200 条：同一 session 重连带 Last-Event-ID，补发 id 大于游标的现存事件；
  不带则从当前环里最早的事件开始。超出环的老事件无法恢复，丢失不额外产生 gap 事件。
  非法 header 视为 0；超过本桥最大 id 的游标不会得到已有事件，连接器须正确重置。
  15 秒无事件发 `: ping` 心跳，心跳不是业务事件。
- HTTP 使用 8 条工作线程，长等待与 SSE 均占线程；连接器复用一条 SSE，
  不为每个任务新开长连接。无背压/持久队列保证，慢消费者与大量并发连接是既有边界。
- SSE 不是跨会话持久队列或 exactly-once 通道。按 session_id + SSE id 去重；
  同一会话游标只在完整帧成功处理后前移。发现 id 缺口、断线或任务终态已被挤出环时，
  保留未知状态；status 的活动/排队计数不能证明旧任务 completed。
- 服务端 `mcbot:s2c` 的 `event` 型信封（服务器主动播报）也转发进 `state`——
  目前该型尚无生产者，任务级细粒度进度事件是 M5/债务项。
- **两种 progress 不要混淆**：外部 SSE progress 已有工具回执/受理/护栏生产者；
  内部 job_event.phase=progress 目前只有接收与转发预留，没有服务器发送方，
  连接器不得等待它才认为任务在执行，也不得以“挖了第几块”作为当前验收必需信号。

## 4. MCP（`POST /mcp`，JSON-RPC 2.0）

支持 `initialize`（protocol 回 `2025-03-26`）、`notifications/initialized`(202 空体)、
`tools/list`、`tools/call`；其余 method 回 `-32601`。
每个请求带 jsonrpc="2.0" 和字符串/数值 id（兼容接受 null，不建议使用）；
initialized 通知可无 id。
坏 JSON 为 -32700，非法信封/批请求为 -32600，tools/call 的 params/arguments 不是对象
或 name 缺失为 -32602；这类 HTTP 200 的 JSON-RPC error 不可当工具成功。

| 工具 | arguments | 语义 |
|---|---|---|
| `mcbot_task` | `{text, wait_s?}` | 同 `POST /v1/task`，result.content[0].text 是那份 JSON |
| `mcbot_ask` | `{text, companion?}`（companion 当前忽略） | 同步等本次任务的回答，≤135s；超时/失败 isError=true |
| `mcbot_answer` | `{question_id, text}` | 成功 `{"ok":true}`；过期/未知问题 isError=true |
| `mcbot_status` | `{}` | status JSON |
| `mcbot_cancel` | `{task_id?}` | `{"ok":bool}`；缺省/0 为全部，正数为指定任务 |

**全部工具是任务级的**。连接器提交任务并处理反馈，不直接调用挖掘、移动等原子游戏工具；
具体动作的规划与执行由 MC agent 负责。
成功/工具失败均用 `result.content[0]={"type":"text","text":"序列化 JSON"}`：
先检查 result.isError，再将 text 解析为 task/status/answer/ok 或上述 error 对象。
缺省 arguments 视为 {}，显式 null/数组拒绝；不合法工具参数不调用 backend。
未知工具、过期问题、参数错误、等待超时和 backend 异常均 isError=true，不泄露原始异常。

## 5. 端到端参考时序

```
neko ─ POST /mcp tools/call mcbot_task {"text":"看看附近有什么","wait_s":15}
桥   → AgentRunner.submitTask → AgentLoop 开链
大脑 → tool_call(status/scan_area) → 服务器执行 → tool_result
帧   id:1 progress{scan_area ✔ …}   id:2 done{"东边 20 格有露出铜矿…"}
neko ← REST/MCP 同步返回 {task_id:3, done:true, status:"completed", fragments:[…]}
—— 如任务需要用户确认（方向性决策）——
帧   id:k question{"question_id":"q8","text":"铜矿在别人的房子底下，要挖吗？"}
neko → 问用户 → POST /v1/answer {"question_id":"q8","text":"绕开他家，从上面进"}
大脑 → 接收用户回答并继续任务 → 最终 done
```

## 5.1 内部实现参考：长任务受理与完成（R2-S4 阶段 2）

本节说明 mcbot 内部 C2S/S2C 协议，不属于连接器必须实现的外部接口。
`move_to`/`break_block` 等长任务采用两段式回执：先返回受理信息，再发送最终结果。

C2S 的 `tool_call` 多一个**可选**字段 `accept`：

```json
{"kind":"tool_call","seq":7,"tool":"move_to","accept":true,"args":{"x":12,"y":63,"z":-4}}
```

- **缺省（没有这个字段）= 老语义同步回执**。这是刻意的向后兼容：老客户端不认识
  `job_ack`，服务端擅自换形态会让它白等到 90 秒超时。要新形态就**由发送方点名**。
- 客户端 `client.json` 的 `accept_mode`（默认 true）是兼容回退开关；关闭后使用同步回执语义。

S2C 新增两条 kind（与 `tool_result` 共用一条通道，`kind` 区分）：

```json
{"kind":"job_ack","seq":7,"job_id":"j3","tool":"move_to","cap_ticks":3600,
 "text":"ACCEPTED:我已开始「走到 12,63,-4」编号 j3，最多约 180 秒。这条还没有结果——别猜、别等着，可以先回我一句话或做别的，做完我会主动报 j3。"}
{"kind":"job_event","seq":7,"job_id":"j3","tool":"move_to","phase":"done","text":"到了 (12,63,-4) 附近…"}
```

**契约（四条，改之前先读）**：

1. **单终局**：一个 `seq` 要么收到 `tool_result`，要么收到 `job_ack`——**不会两条都来**。
   已经出结果的快路径（`DENIED`/`BUSY`/`TARGET_LOST`…）即使工具是 ACCEPT 模式也直接回
   `tool_result`：先 ack 再立刻报失败等于白多一跳，还会让模型以为"被打回了"和"跑完了"是两件事。
2. **`job_ack` 自带教学**：文案必须以 `ACCEPTED:` 开头，且三句齐（还没结果 / 别干等 / 做完主动报编号）。
   前缀是给**模型**看的契约；"要不要 park"是控制流，走 `ToolOutcome.accepted` **字段**，不认字符串。
3. **`phase ∈ progress|done|failed|cancelled|superseded`**。`progress` 是预留接收相位，
   只播报不进对话，**当前服务端不生产，限速也未实现**；
   1 帧/s/job、全局 4 帧/s 是后续计划，不是 v1.0 保证。其余四者解锁 PARK。`cancelled`（用户取消）与
   `superseded`（被新指令顶掉）必须与 `failed` 分开，因为客户端补账的话术不同。
4. **`cap_ticks` 是客户端等待上限的来源**，客户端的超时必须**严格大于** `cap*50ms`
   （agent-core 的 `PendingJobs.JOB_GRACE_MS` 给 15s 余量）。两边各拍一个常数，就是
   "服务端跑 180s、客户端 90s 判 TIMEOUT"那条真缺陷的成因。

客户端侧的对应状态机：`tool_call` → 等 `tool_result`；收到 `job_ack` → **同一 seq 转段**
（`seq → jobId`）改等 `job_event`；大脑收到受理**不把这条写进对话**，转 **PARK**——
这条指令挂起、不再问模型（PARK 期间不计步），事件到了才按 index 原序补 `tool` 消息并开新轮。

⚠️ **PARK 的铁律**：解锁（用户取消 / 新指令 / 本地超时）之前，**每一条 in-flight 的
`tool_call` 都必须有一条回执**，哪怕是本地合成的 `CANCELLED:`/`SUPERSEDED:`。少一条，
下一次请求里那个 `assistant.tool_calls` 就有 id 找不到配对的 `tool` 消息——OpenAI 直接 400，
一整段历史当场作废。`AgentLoop.stopActive` 是取消/顶替时补账的唯一实现，别在别处补回执。

客户端通过代际号丢弃旧模型/工具回调，并清理旧任务的 pending/question/ask。
配置重载后排队的旧只读检查显示也被丢弃；关闭后的 runner 不再接受配置保存/重载。
退出重进更换 runner，已排队的旧 S2C 回调和旧桥命令校验原 runner 身份后拒绝执行；
这些属于内部会话隔离，不新增 v1.0 的协议字段或续跑能力。
PARK 顶替会先发 C2S cancel 再开始新任务；服务端单槽仍保留 BUSY，**不是** scheduler 抢占或
`remaining()` 续跑。progress 的服务端生产与限速也仍是后续卡，不应据此宣称已完成。

## 6. 连接器接入与验收

连接器推荐顺序：

1. 用 header token 查询 status，确认契约版本、记录 session_id；提示用户先配置大脑与召唤伙伴。
2. 建立 SSE，再用 task（建议 wait_s=0）投递。投递返回前的事件先暂存，
   返回 task_id 后归组，避免同步 done 或快速 question 先于 HTTP 响应造成漏接。
3. state 只更新观测状态；progress 展示回执，不从文字解析动作；
   question 转给用户，回答时使用收到的 question_id，直到 done.status 才汇报任务结束。
4. 取消只带目标 task_id；收到 ok=true/done(cancelled) 也不宣称物理动作已停止。
5. SSE 断开后查询 status：session 相同才用旧 Last-Event-ID 补发；
   session 改变则旧任务标未知、旧问题作废、游标清零，不自动续跑/重投。
   新会话事件的 session_id 与记录不符时同样停止把事件写入旧任务。

连接器离线验收（不需要模型/游戏，示例与本地替身）：

- [ ] 正确解析五个工具的 inputSchema 与嵌套 content[0].text。
- [ ] 快速 done 在 POST 返回前到达时仍能归组；窗口 done=false 不误判失败。
- [ ] 四种 done.status 全部处理；state/PARK/工具 accepted 不误当任务完成。
- [ ] question → answer，过期/重复回答展示错误，不重复执行用户选择。
- [ ] 指定取消/全部取消/未知取消语义分开。
- [ ] SSE 重复帧不重复通知，重新建桥 id=1 不被旧游标吞掉。
- [ ] 未知可选字段不导致解析失败；错误码/RPC error/isError 三层都识别。
- [ ] POST 超时、断线或环内缺帧标未知，不自动重发可能已执行的动作。

mcbot 桥接回归入口：
`./gradlew :agent-core:test --tests '*Bridge*'` 与
`./gradlew clientTest --tests '*BridgeHttpContractTest'`。
测试使用本地回环随机端口和替身 backend，不读取真实 token/client.json，不请求远端模型。
这些是桥的契约/HTTP 回归，不替代真实 MC + N.E.K.O 联调。

本体活动任务与重连接线另由 AgentRunnerLifecycleTest / ClientSessionLifecycleTest
覆盖，入口见 `docs/DEVELOPMENT.md` §4.3。该专项使用实际 runner/session/backend
与本地 HTTP/SSE，但模型、配置落盘和游戏发包采用替身，不作为下方真机清单的通过证据。

**端到端联合验收清单（v1.0 包与连接器仍须逐项实测）**：

前置：装当前 `dist/mcbot-0.1.0.jar` + fabric-api；开发服开着；进世界（面板底部出现桥端点）。

```bash
TOKEN=$(cat "<游戏目录>/mcbot/bridge.token")
B=http://127.0.0.1:57121
# 1 鉴权负例：必须 401
curl -s $B/v1/status
# 2 状态：companion 应为你的同伴名
curl -s -H "Authorization: Bearer $TOKEN" $B/v1/status
# 3 派任务+盯窗口：fragments 里要有工具回执
curl -s -X POST -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"text":"看看附近有什么然后简报","wait_s":25}' $B/v1/task
# 4 事件流：另开终端跑着，重跑第 3 步应看到成串 progress/done 帧
curl -N "$B/v1/events?token=$TOKEN"
# 5 断线补发：Ctrl+C 后带 Last-Event-ID 重连
curl -N -H "Last-Event-ID: <刚才最后一个 id>" "$B/v1/events?token=$TOKEN"
# 6 MCP：五个工具 + status 调用
curl -s -X POST -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/list"}' $B/mcp
# 7 反问闭环（让它拿不准一次）：question 帧 → answer → 大脑续跑
# 8 A 运行时投 B：A 的 progress/done 仍必须属于 A；current_task 在 B 启动前仍为 A
# 9 分别取消排队项、活动项、未知编号：只影响匹配项，未知项 ok=false
# 10 等反问/长活时叫停或保存配置：旧问题 answer 应为 404，迟到回执不能再派发工具
# 11 断线重连：旧会话不可续跑，pending_* 与 current_task 归零
# 12 未配置大脑投令：done=true status=failed，不能误报正常完成
# 13 同一世界重载配置：session_id 不变，但旧任务/问题终止
# 14 退出再进世界：session_id 改变，SSE 游标重置；旧任务不得自动重投
# 15 ask 超时/HTTP 断开：连接器标未知，不自动重发；无任务历史查询时不猜完成
```
