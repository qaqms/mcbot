# 桥接完整契约（M6）

> 快速上手版见 `dist/README-BRIDGE.md`；本文是字面量规格。
> 内核：`agent-core/.../bridge/BridgeService`（有单测）；HTTP 壳：`src/client/.../bridge/BridgeHttp`。

## 1. 监听与鉴权

- 地址：`http://127.0.0.1:57121`（只绑回环，绝不 0.0.0.0；无 TLS——回环不设防没意义）。
- 生命周期：**客户端进世界起、退世界关**；桥只服务当前进世界的主人（一台机开多个
  1.21.11 客户端会撞端口——v1 已知边界）。
- 鉴权：`Authorization: Bearer <token>`；token = `<gameDir>/mcbot/bridge.token`
  文件内容（首次随机生成、跨重启稳定；G 面板顶部也显示端点+token 全串）。
  校验用常量时间比较；不合法一律 401。SSE 允许 `?token=`（EventSource 发不了 header）。
- 请求体上限 64KB（超限 500）。

## 2. REST

| 方法/路径 | 请求体 | 成功响应 | 失败 |
|---|---|---|---|
| `POST /v1/task` | `{"text":"…","wait_s":0..120}`（默认 8） | `{"task_id":N,"done":bool,"fragments":["…"]}` | 400 text 空 |
| `POST /v1/task/{id}/cancel` | — | `{"ok":true}` | — |
| `POST /v1/ask` | `{"text":"…"}` | `{"answer":"…"}` | **504**（60s 无作答）/400 |
| `POST /v1/answer` | `{"question_id":"qN","text":"…"}` | `{"ok":true}` | 404 无此问题 |
| `GET /v1/status` | — | 见下 | — |
| `GET /v1/events` | — | SSE 流 | 401 |

```jsonc
// /v1/status（字段全部并发安全快照）
{"in_game":true,"brain_enabled":true,"model":"deepseek-chat",
 "companion":"aimi","current_task":3,"pending_tools":1,"pending_questions":0}
```

**task 窗口语义**：投令后盯事件流最长 `wait_s` 秒——同 `task_id` 的 `progress` 收进
`fragments`，见到该任务的 `done`/`state` 即 `done:true` 提前返回；`wait_s=0` = 投了就走
（立即返回空 fragments，靠 `/v1/events` 追）。叫停是全局链级的（v1 一条链）。

## 3. SSE 事件模型

帧格式（标准三行）：

```
id: 17
event: progress
data: {"ev":"progress","task_id":3,"text":"break_block ✔ 挖掉了 Cobblestone，收到背包: 1×Cobblestone","tool":"break_block","ok":true}
```

| event | data 字段 | 出处 |
|---|---|---|
| `progress` | `task_id,text,tool?,ok?` | 每次工具回执；护栏 notice 也走这里（带"（护栏）"前缀） |
| `done` | `task_id,text` | 大脑对一条链的作答（含"[内部]…"故障文本） |
| `question` | `question_id,text,task_id` | 同伴 `ask_owner` 反问 |
| `state` | `text,task_id` | 召唤/遣散/叫停回执、大脑未配置、主人回答问题 |

- 环形缓冲 200 条：重连带 `Last-Event-ID` 自动补发（超出环的老事件不可恢复，
  从可用处开始——丢旧不丢新）。15 秒无事件发 `: ping` 心跳。
- 服务端 `mcbot:s2c` 的 `event` 型信封（服务器主动播报）也转发进 `state`——
  目前该型尚无生产者，任务级细粒度进度事件是 M5/债务项。

## 4. MCP（`POST /mcp`，JSON-RPC 2.0）

支持 `initialize`（protocol 回 `2025-03-26`）、`notifications/initialized`(202)、
`tools/list`、`tools/call`；其余 method 回 `-32601`。

| 工具 | arguments | 语义 |
|---|---|---|
| `mcbot_task` | `{text, wait_s?}` | 同 `POST /v1/task`，result.content[0].text 是那份 JSON |
| `mcbot_ask` | `{text, companion?}`（companion 当前忽略） | 同步等答，超时回文本"(同伴 60 秒内没能回答)" |
| `mcbot_answer` | `{question_id, text}` | `{"ok":bool}` |
| `mcbot_status` | `{}` | status JSON |
| `mcbot_cancel` | `{task_id?}` | `{"ok":true}` |

**全部工具是任务级的**——原子游戏操作（挖哪格/走哪去）永远不出现在这层：
外部大脑是老板不是操作员。

## 5. 端到端参考时序

```
neko ─ POST /mcp tools/call mcbot_task {"text":"看看附近有什么","wait_s":15}
桥   → AgentRunner.submitTask → AgentLoop 开链
大脑 → tool_call(status/scan_area) → 服务器执行 → tool_result
帧   id:1 progress{scan_area ✔ …}   id:2 done{"东边 20 格有露出铜矿…"}
neko ← REST/MCP 同步返回 {done:true, fragments:[…]}
—— 若中途同伴发难（方向性决策）——
帧   id:k question{"question_id":"q8","text":"铜矿在别人的房子底下，要挖吗？"}
neko → 问用户 → POST /v1/answer {"question_id":"q8","text":"绕开他家，从上面进"}
大脑 → 收到"主人说：绕开…"续跑 → 最终 done
```

## 6. 活体验收清单（手动过一遍即 M6 关账）

前置：装 `dist/mcbot-0.1.0.jar`（14:38 起含桥）+ fabric-api；开发服开着；进世界（面板顶出现桥行）。

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
```
