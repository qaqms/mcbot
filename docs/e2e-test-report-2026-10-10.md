# mcbot × N.E.K.O. 桥接：端到端测试报告（2026-10-10）

| 项 | 内容 |
|---|---|
| **日期** | 2026-10-10 |
| **测试对象** | mcbot 0.1.0（任务桥契约 v1.0）+ `mcbot_bridge` 插件（连接器侧） |
| **测试环境** | 见下 |
| **测试范围** | 首次真机联调：派活 → 执行 → 事件 → 宿主侧呈现 的完整闭环 |
| **未覆盖** | `cancel`（指定/全部/未知）、`answer` 反问闭环、`ask`、MCP 面、配置重载、忙碌/抢占 |

## 结论摘要

**全链路已跑通**：对话 LLM → `mcbot_task` → 桥 57121 → 同伴真实执行 → SSE 事件 →
连接器转 cue → 宿主 `proactive_message` → 角色口播。契约 v1.0 的帧格式、会话标识、
受理回执、终态语义在真机上均按预期出现。

**发现 7 项问题**：

| 编号 | 归属 | 问题 | 严重度 | 状态 |
|---|---|---|---|---|
| M-1 | **模组** | 只读探查任务打满 40 步上限而失败 | 中 | 待修 |
| M-2 | **模组** | 建桥时推送基础设施类 `state` 帧，措辞易被误当作用户事件 | 低（接口建议） | 待议 |
| P-1 | 连接器 | 进度/状态 cue 刷屏，疑似淹掉用户请求 | **高** | 待修 |
| P-2 | 连接器 | 失败任务被叙述成成功 | 高 | 已修未复测 |
| P-3 | 连接器 | 完成等待预算 300s 过短，提前释放任务槽 | 中 | 已修未复测 |
| P-4 | 连接器 | 无 token 时每 5s 刷协议错误 | 低 | 已修未复测 |
| P-5 | 连接器 | cue 发射无日志，端到端排查缺证据 | 低 | 已修未复测 |

> **修复分工**：M-* 属模组侧，请负责人处理；P-* 属连接器侧（本插件），
> 其中 P-2…P-5 的补丁已在本地仓库完成、**尚未同步到运行实例、尚未复测**。

---

## 测试环境

| 项 | 值 |
|---|---|
| Minecraft | **1.21.11**，Fabric Loader **0.19.5**，Fabric API 0.141.6+1.21.11 |
| 客户端 | PCL 启动器，**版本隔离**实例 `F:\pcl\.minecraft\versions\1.21.11-Fabric 0.19.5` |
| 服务端 | 开发服 `cd E:\mcbot && ./gradlew runServer`，`online-mode=false`，端口 25565 |
| mcbot | 0.1.0（当日 13:01 从 v1.0 冻结后的源码重建；jar 内含 `BridgeContract` + `bridge-v1.schema.json`） |
| 模型 | 阿里云 DashScope OpenAI 兼容端点，`brain_enabled=true` |
| 同伴 | `xiaotian` |
| 宿主 | N.E.K.O. 打包稳定版 v0.9.0.1（Electron，`F:\N.E.K.O\N.E.K.O_v0.9.0.1_win`） |
| 连接器 | `mcbot_bridge`，装于 `%LOCALAPPDATA%\N.E.K.O\plugins\mcbot_bridge\` |
| 桥 | `127.0.0.1:57121`，token 位于 `<gameDir>/mcbot/bridge.token`（32 字节） |

---

# 第一部分：模组（mcbot）

## M-1 · 只读探查任务打满 40 步上限而失败

**测试项目**
派发一个**纯只读**任务（「看看附近有什么，然后简报给我」），观察其终态与工具调用序列。

**测试结果**
**失败。** 任务以 `done.status="failed"` 收尾，大脑自述原因为步数超限。
一个不需要改动世界、只需 `status` + `scan_area` 即可完成的指令，用满了 40 步预算。

**证据日志**

1) 终态帧（SSE，`task_id=1`）：

```json
{"text":"[内部] 这个任务步数超限，我停下了。","status":"failed","ev":"done","task_id":1,"contract_version":"1.0","session_id":"2ea1677e-fb74-4b34-b583-d2abe0906fe2"}
```

2) 同期进度帧显示工具在反复调用（30 秒窗口内抓到 42 条 `progress` + 33 条 `state`），
其中 `scan_area` 多次重复、半径在 16/32 之间来回，随后是 `move_to` 长活：

```json
{"text":"scan_area ✔ 我在 (-6,-60,-8) 面朝 south\n半径 16 内：...\n这一圈里没有可行动的东西（土/沙/木头一类不算目标）。","tool":"scan_area","ok":true,"ev":"progress","task_id":1,...}
{"text":"ACCEPTED:我已开始「走到 -3, -60, 4」编号 j1，最多约 180 秒。...","tool":"move_to","job_id":"j1","ev":"progress","task_id":1,...}
{"text":"move_to ✔ 到了 (-3, -60, 4) 附近，站定在 -4, -60, 3。","tool":"move_to","ok":true,"ev":"progress","task_id":1,...}
{"text":"scan_area ✔ 我在 (-4,-60,3) 面朝 south\n半径 8 内：...","tool":"scan_area","ok":true,"ev":"progress","task_id":1,...}
{"text":"ACCEPTED:我已开始「挖 -3, -60, 4 的方块」编号 j2，最多约 60 秒。...","tool":"break_block","job_id":"j2","ev":"progress","task_id":1,...}
```

注意 `scan_area` 的结论始终是「这一圈里没有可行动的东西」——**视野里没有目标，
但大脑没有据此收尾，而是继续移动并开始挖掘**。

**修复建议**

1. **优先调查不收敛的原因**：连续多步 `scan_area` 返回「无可行动目标」时，大脑应当收尾
   并汇报，而不是改成去动地形。这看起来是规划层的早停条件缺失。
2. **只读类指令可考虑单独放宽/收紧步数帽**：一个明确只要求「看 + 简报」的指令，
   不应与「砍一片树」共用同一个 40 步预算。可考虑按指令类型给不同上限，
   或在纯探查分支命中时提前返回。
3. 这是本次唯一发现的**模组侧功能性缺陷**。

## M-2 · 建桥时推送基础设施类 `state` 帧（接口建议，非缺陷）

**测试项目**
进世界后观察桥的初始事件序列，判断其对连接器的可解释性。

**测试结果**
桥在建桥时推送一串与用户任务无关的 `state` 帧。对连接器而言这些**是有用的**
（用于判断桥/同伴就绪），但**措辞是面向用户的口吻**，容易被连接器误当作「该转述给用户的事件」。

**证据日志**（新会话，id 从 1 起）

```json
{"text":"当前世界尚未召唤伙伴。","ev":"state","task_id":0,...}
{"text":"同伴 steve 出现了","ev":"state","task_id":0,...}
{"text":"同伴 steve 离开了","ev":"state","task_id":0,...}
{"text":"同伴 xiaotian 出现了","ev":"state","task_id":0,...}
{"text":"它现在手头没有进行中的任务。","ev":"state","task_id":0,...}
{"status":"queued","ev":"state","task_id":1,...}
{"parked":true,"outstanding":1,"ev":"state","task_id":1,...}
```

**修复建议**
**可选、低优先**。若希望连接器能干净地区分「基础设施通知」与「值得转述的消息」，
可以在纯基础设施的 `state` 帧上加一个标记字段（例如 `internal:true`）。
当前连接器只能靠「有没有 `text`」来粗筛（`parked`/`outstanding` 那类无 `text` 的已被丢弃），
带 `text` 的那几条仍会进入上下文。**这不阻塞使用，列在这里只为接口演进时参考。**

---

# 第二部分：插件（mcbot_bridge，连接器侧）

## P-1 · 进度/状态 cue 刷屏 【高，未修】

**测试项目**
任务运行期间统计连接器向宿主推送 `proactive_message` 的频率与收敛情况。

**测试结果**
**异常。** 节流参数为 6 秒（`progress_min_interval_seconds = 6`），预期 ≤ 10 条/分钟；
实测出现 **3 秒内 7 条**。且 `coalesce_key="mcbot_progress"` 未观察到折叠效果——
它们是一条条独立入队的。

**证据日志**（宿主主日志，节选）

```
2026-10-10 18:29:02 - N.E.K.O.Main - WARNING - [EventBus] proactive_message rerouted: lanlan=None missing, fallback_session=小天
2026-10-10 18:29:02 - N.E.K.O.Main - INFO - [EventBus] proactive_message enqueued callback (passive); next user turn will carry it
2026-10-10 18:29:02 - N.E.K.O.Main - INFO - [EventBus] proactive_message enqueued callback (passive); next user turn will carry it
2026-10-10 18:29:02 - N.E.K.O.Main - INFO - [EventBus] proactive_message enqueued callback (passive); next user turn will carry it
2026-10-10 18:29:03 - N.E.K.O.Main - INFO - [EventBus] proactive_message enqueued callback (passive); next user turn will carry it
2026-10-10 18:29:03 - N.E.K.O.Main - INFO - [EventBus] proactive_message enqueued callback (passive); next user turn will carry it
2026-10-10 18:29:04 - N.E.K.O.Main - INFO - [EventBus] proactive_message enqueued callback (passive); next user turn will carry it
```

**影响（这是本次最值得担心的一条）**
`progress` / `state` 用的是 `ai_behavior="read"`（被动）语义——它们会**攒在队列里**，
等用户下一次开口时整批注入模型上下文。一个 3 分钟的复合任务足以攒下几十条
「同伴走到了哪、扫到了什么」。用户随后发出的指令会被这批陈旧进展**稀释甚至淹没**，
现象就是**「我发了指令但同伴好像没动 / 角色没执行」**。

**修复建议**

1. 收紧 `progress_min_interval_seconds`（6s → 20~30s），并给 `state` 补上同等节流
   （当前 `state` **完全没有节流**）。
2. 排查 `coalesce_key` 在**被动队列**上为何不折叠——若宿主只对 `respond` 路径折叠，
   则连接器侧必须自己限流。
3. 考虑给被动进展加总量上限（例如同一任务最多累计 N 条，超出只留最新）。

## P-2 · 失败任务被叙述成成功 【高，已修未复测】

**测试项目**
任务以非成功终态结束时，观察角色是否如实叙述。

**测试结果**
**失败（当时的运行版本）。** 桥的终态是 `status="failed"`，但角色对用户说的是
「草方块已经挖好啦」「之前砍的树也收齐了」。

**证据日志**

1) 桥的终态帧（`status` 为 `failed`）：

```json
{"text":"[内部] 这个任务步数超限，我停下了。","status":"failed","ev":"done","task_id":1,...}
```

2) 同期对话记录：

```
小天 13:33:11  喵，草方块已经挖好啦。
小天 13:33:13  之前砍的树也收齐了，要一起带回来吗？
```

3) 补充事实：整个过程中桥**只收到过 `task_id=1` 一个任务**——「草方块」那次请求
从未被派发过（角色只是回问「要多少个？」）。也就是说这条「已完成」在事实层面也不成立。

**修复建议**
已修：非 `completed` 的 `done` 改走独立的失败措辞（`CUE_DONE_FAILED`，
措辞为「did not finish … don't dress it up as done」）；
`cancelled` / `superseded` 两种终态改为**完全不播报**（被有意停掉/取代，播报只会诱发重投）。
**待复测**——首次真机跑的是不含该修复的旧代码。

## P-3 · 完成等待预算 300s 过短 【中，已修未复测】

**测试项目**
观察连接器等待 `done` 事件的预算是否覆盖真实任务时长。

**测试结果**
**过短。** 看门狗在整 300 秒时开火、释放了任务槽，而真正的 `done` 是之后才到的。
后果两层：一是向用户报了「没等到回音」而任务其实还在跑；二是槽位被提前释放，
后续派活可能叠在一个仍在执行的同伴身上。

**证据日志**（连接器日志）

```
2026-10-10 13:29:09 - INFO - [Mcbot] task dispatched (id=1)
2026-10-10 13:34:09 - INFO - [Mcbot] task 1 exceeded the wait budget
```

（间隔恰为 300.0s，与配置一致——看门狗本身工作正常，是预算给短了。）

**修复建议**
已改：`task_wait_timeout_seconds` 300 → **900**。理由：单个 `move_to` 上限即 180s、
`break_block` 上限 60s，一个复合任务合法地超过 5 分钟。**待复测**。

## P-4 · 无 token 时每 5 秒刷协议错误 【低，已修未复测】

**测试项目**
在 `game_dir` 未配置（读不到 `bridge.token`）的状态下观察连接器行为。

**测试结果**
**噪声过大。** token 为空时连接器仍发起请求，产生 `Authorization: Bearer `（空值），
被 httpx 判为非法头，于是**每 5 秒刷一条**同错，把真正的病因（没读到 token）淹没了。

**证据日志**

```
2026-10-10 13:17:19 - WARNING - [Mcbot] event stream failed: LocalProtocolError: Illegal header value b'Bearer '
2026-10-10 13:17:24 - WARNING - [Mcbot] event stream failed: LocalProtocolError: Illegal header value b'Bearer '
...（每 5 秒一条，持续数分钟）
```

**修复建议**
已修：token 为空时**不发起连接**，只记一行
`no bridge token yet (<原因>); waiting Ns`。**待复测**。

## P-5 · cue 发射无日志 【低，已修未复测】

**测试项目**
端到端排查时，能否仅凭连接器日志判断它发了哪些 cue。

**测试结果**
**不能。** `_push_cue` 成功时不记录任何日志，导致「角色没提到同伴」这类问题
只能从**宿主**日志反推连接器发了什么，排查成本高。

**修复建议**
已修：补一行 debug 级日志，只记**类别 / ai_behavior / 优先级 / 字符数**，
**不记正文**（正文属对话内容，隐私线要求不进日志）。**待复测**。

---

# 附录 A：本次已确认正常（可作为后续回归基线）

| 项 | 证据 |
|---|---|
| 桥鉴权负例 | 不带 token → `HTTP 401` |
| 契约版本 | `status` 与每个事件帧均带 `contract_version:"1.0"` |
| 会话标识 | `session_id` 存在，且**重建桥时确实改变**（`2ea1677e-…` → `dcb47e0f-…`） |
| 令牌稳定 | 退出再进世界后 `bridge.token` 未变（32 字节） |
| SSE 游标补发 | 带 `Last-Event-ID: 15` 重连 → 从 `id: 16` 起补发，未退回 1；94 帧单调递增、无重复 |
| 新桥 id 归零 | 新会话事件从 `id: 1` 重新发号 |
| 受理回执 | `ACCEPTED:…编号 jN` 与 `job_id` 字段齐备，`parked`/`outstanding` 状态随长活流转 |
| 工具注册 | 4 个 `@llm_tool` 进入宿主 `ToolRegistry`（`source=plugin:mcbot_bridge`），对话 LLM 实际发起调用 |
| 派发链路 | 插件日志 `TRIGGER entry='__llm_tool__mcbot_task'` → `task dispatched (id=1)` |
| 只读工具免模型 | 文档所述「查看状态 / 扫描附近 无需模型」与实际观察一致（但需要世界内已召唤同伴） |
| 未配置大脑时 | 派发返回 `done=true status=failed`，未被误报为正常完成（清单第 12 条行为成立） |

# 附录 B：本次未覆盖项

- `cancel`：指定 id / 全部取消（id=0）/ 未知 id 三种语义**均未实测**
- `question` → `answer` 反问闭环**未触发到**（本次同伴没有反问）
- `ask`（同步问答，含 135s 超时路径）**未实测**
- MCP 面（`POST /mcp`）**未实测**——本连接器走 REST+SSE，不走 MCP
- 配置重载（`mcbot_reload_config`）、忙碌/`overwrite` 抢占**未实测**
- 「运行中投新任务 / current_task 归属」等 §6 清单第 8、10、13 项**未实测**

---

**报告人**：（测试方）
**下次测试计划**：待负责人提供新测试版本与方法后，优先复测 P-1…P-3，
并补齐附录 B 中的 `cancel` 与反问闭环两项。
