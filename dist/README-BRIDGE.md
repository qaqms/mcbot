# mcbot 任务桥 v1.0（连接器上手）

> 本页只是索引；唯一权威规格、Schema/示例路径、兼容变更与验收清单见仓内 `docs/BRIDGE.md`。

> 前提：mcbot 客户端 mod 已进世界（桥随世界起、退世界关）。只监听 127.0.0.1:57121。

## 鉴权

```
Authorization: Bearer <token>
```
token = 你游戏目录 `mcbot/bridge.token` 文件内容（首次进世界自动生成，跨重启稳定）。
G 面板底部显示端点；模型页的“复制桥接令牌”可复制 token，不再全串展示。

## 手动验证（Git Bash，JSON 必须 UTF-8）

```bash
TOKEN=$(cat "<你的游戏目录>/mcbot/bridge.token")
curl -H "Authorization: Bearer $TOKEN" http://127.0.0.1:57121/v1/status

# 派个任务，等 20 秒看进度片段
curl -X POST -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"text":"看看你附近有什么，然后简报给我","wait_s":20}' \
  http://127.0.0.1:57121/v1/task

# 事件流（Ctrl+C 退出）：progress / done / question / state 四类帧
curl -N -H "Authorization: Bearer $TOKEN" http://127.0.0.1:57121/v1/events

# 同伴反问时（question 帧里有 question_id）回答它：
curl -X POST -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"question_id":"q3","text":"用箱子里那把镐"}' \
  http://127.0.0.1:57121/v1/answer
```

## 端点一览

| 方法/路径 | 语义 |
|---|---|
| `POST /v1/task {text, wait_s≤120 默认8}` | 派一件事。返回 `{task_id, done, fragments[]}`，结束时另带 `status` |
| `POST /v1/task/{id}/cancel` | 只取消匹配的活动/排队任务；0 为全部；不存在或已结束返回 ok=false |
| `POST /v1/ask {text}` | 同步拿本次任务的回答（≤135s，超时 504） |
| `POST /v1/answer {question_id, text}` | 回答同伴的反问 |
| `GET /v1/status` | 含 current_task、queued_tasks、pending_tools/jobs/questions/asks、parked 等状态 |
| `GET /v1/events` | SSE；`Last-Event-ID` 自动补发（最近 200 条） |
| `POST /mcp` | JSON-RPC：`initialize`/`tools/list`/`tools/call`，工具=上面五个的 `mcbot_*` 版 |

中文请求在终端编码不确定时使用 UTF-8 JSON 文件和 `--data-binary @文件名`，
不要通过可能将中文转为本地编码的管道。也不要记录包含 token 的 URL。

## 推荐任务闭环（neko 侧）

```
查询 status → 记录 session_id → 建立 SSE（先于投令）
用户 → 猫娘(LLM) → mcbot_task("查看状态并扫描附近") → {task_id}
事件流   → progress(工具回执/长活受理/护栏)         → 猫娘展示反馈
question → 猫娘问用户 → mcbot_answer            → 同伴续跑
done     → 读取 status 与 text → 猫娘汇报结束结果
```

`done:true` 只表示已结束。`status` 为 completed / failed / cancelled / superseded；
completed 是大脑正常作答，不是目标成功的独立证明。state（含 PARK、回答确认）不能作为
任务终态；只消费对应 task_id 的 done。窗口返回 done=false 时继续追 SSE。
MC agent 不要求新增闲聊功能：任务投递、进度、必要时反问、最终结果就是基本桥接闭环。

`fragments` 是兼容展示文字，可能包含公共 state、问题和最终回答，不只进度；
结构化业务处理以 SSE 为准，不能重复消费窗口片段。长活受理不表示工具成功。
内部 `job_event.phase=progress` 仅预留，当前没有服务端发送方，无须等细粒度进度才继续任务。

status 与每个 SSE data 都带 contract_version="1.0"/session_id。
同一 session 的 SSE 用 Last-Event-ID 补发最近 200 条，按 session_id + SSE id 去重；
新 session 清零游标、作废旧问题，旧任务保留未知状态，不自动重发。
POST 超时/断开可能已执行；ask 等 135s 超时只释放等答，不自动取消任务。
本版没有历史任务查询与幂等提交功能；状态计数不能证明旧任务成功。

参数严格按 Schema 类型传；REST 错误看 error_code，MCP 先看 JSON-RPC error /
result.isError，再解析 content[0].text。新增字段忽略。
连接器本地用替身做契约验收后，再共同进行真实 MC 联调；固定接口不代表 F0 全部完成。

原子游戏操作（挖这格、走去哪）不会出现在这层——猫娘是老板，不是操作员。
