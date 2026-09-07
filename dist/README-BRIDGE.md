# mcbot 桥接（neko 看到的 mcbot）

> 本页是快速上手；字面量规格（事件字段、错误码、窗口语义、验收清单）见仓内 `docs/BRIDGE.md`。

> 前提：mcbot 客户端 mod 已进世界（桥随世界起、退世界关）。只监听 127.0.0.1:57121。

## 鉴权

```
Authorization: Bearer <token>
```
token = 你游戏目录 `mcbot/bridge.token` 文件内容（首次进世界自动生成，跨重启稳定）。
G 面板顶部也会直接显示端点和 token。

## 手动验证（Git Bash 直接可用）

```bash
TOKEN=$(cat "<你的游戏目录>/mcbot/bridge.token")
curl -H "Authorization: Bearer $TOKEN" http://127.0.0.1:57121/v1/status

# 派个任务，等 20 秒看进度片段
curl -X POST -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"text":"看看你附近有什么，然后简报给我","wait_s":20}' \
  http://127.0.0.1:57121/v1/task

# 事件流（Ctrl+C 退出）：progress / done / question / state 四类帧
curl -N -H "Authorization: Bearer $TOKEN" \
  "http://127.0.0.1:57121/v1/events?token=$TOKEN"

# 同伴反问时（question 帧里有 question_id）回答它：
curl -X POST -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"question_id":"q3","text":"用箱子里那把镐"}' \
  http://127.0.0.1:57121/v1/answer
```

## 端点一览

| 方法/路径 | 语义 |
|---|---|
| `POST /v1/task {text, wait_s≤120 默认8}` | 派一件事；`wait_s=0` 投了就走。返回 `{task_id, done, fragments[]}` |
| `POST /v1/task/{id}/cancel` | 叫停当前链 |
| `POST /v1/ask {text}` | 闲聊一句，同步拿回答（≤60s，超时 504） |
| `POST /v1/answer {question_id, text}` | 回答同伴的反问 |
| `GET /v1/status` | `{in_game, brain_enabled, model, companion, current_task, pending_tools, pending_questions}` |
| `GET /v1/events` | SSE；`Last-Event-ID` 自动补发（最近 200 条） |
| `POST /mcp` | JSON-RPC：`initialize`/`tools/list`/`tools/call`，工具=上面五个的 `mcbot_*` 版 |

## 推荐对话闭环（neko 侧）

```
用户 → 猫娘(LLM) → mcbot_task("去挖一组铁矿")   → {task_id}
事件流   → progress("开始下矿"/"挖到 3 铁矿")   → 猫娘实时旁白
question → 猫娘问用户 → mcbot_answer            → 同伴续跑
done     → 完成事件 + mcbot_ask 收尾闲聊
```

原子游戏操作（挖这格、走去哪）不会出现在这层——猫娘是老板，不是操作员。
