# N.E.K.O 插件体系勘察（mcbot 怎么接进去）

- 勘察对象：**N.E.K.O 桌面 AI（主人本机的只读副本，路径不入仓）**（**只读**；未修改该仓任何文件）
- 目的：为 M7「neko 首亮」确定接入路径，区分"改配置就能用"与"必须写插件"
- 纪律：本文只记**接口事实与路径**，不含任何密钥/token 值；行号均为勘察时实读

## 1. 插件anatomy（最小可运行集）

| 文件 | 必需 | 说明 |
|---|---|---|
| `<插件目录>/plugin.toml` | ✔ | 既是清单也是默认配置 |
| `<插件目录>/__init__.py`（或 `entry` 指的模块） | ✔ | 必须定义 `[plugin].entry` 里那个类 |
| `config.example.toml` | – | 若存在则**用它**播种用户运行期配置，否则复制 `plugin.toml` |
| `plugin.meta.json` | – | **打包时生成**（作者机器上），host 只读不 import 插件代码；缺失则退回 manifest + 隔离 worker 扫描 |
| `static/` `ui/*.tsx` `surfaces/*.tsx` `i18n/<locale>.json` | – | 静态 UI / 托管界面 / 多语言 |

`plugin.toml` 关键字段：`[plugin]` 的 `id`（须等于目录名）、`name`、`entry`（`模块:类`）、
`type`（`plugin`/`adapter`）、`keywords`（进 agent 关键词/BM25 预筛）、
`[plugin_runtime]` 的 `enabled`/`auto_start`（**默认 false**）/`priority`/`timeout`/`startup_failure`、
`[plugin.ui]` 的 `panel`/`[[plugin.ui.guide]]`（`permissions` 需含 `action:call` 才允许
surface 调 `props.api.call`）、`[plugin.i18n]`。顶层允许自定义表（如 `[game_agent]`、`[mcp_servers]`）。

生命周期：`startup`/`shutdown`/`reload`/`freeze`/`unfreeze`/`config_change`。
**未文档化但受支持的钩子** `async def _on_command_loop_start(self)`：在**长驻**命令循环上调用，
是唯一安全的"起后台异步任务"的地方——因为 `@lifecycle(startup)` 跑在一个**临时** `asyncio.run()`
里，返回时会取消子任务（参考项目 `game_agent_minecraft` 的注释里写明了这个坑）。

运行期数据根（Windows）：`%LOCALAPPDATA%\N.E.K.O`（可用 `NEKO_SELECTED_STORAGE_ROOT` 或
`state/storage_policy.json` 覆盖）；每个插件在 `<root>/plugins/<id>/{config/plugin.toml,data,cache}`。
**活的配置是运行期那份副本**（由发布的 manifest 播种）。

## 2. 注册 LLM 工具

- 首选装饰器：`@llm_tool(name=None, description="", parameters=<JSON Schema>, timeout=30.0, role=None)`；
  `name` 默认取方法名，须匹配 `^[A-Za-z0-9_.\-]{1,64}$`；`timeout` 服务端上限 300s；
  `role` 限定某个角色，`None` = 全局；**同步/异步都收**。handler 以 `**kwargs` 收模型给的 JSON 参数。
- 自动注册发生在 `NekoPluginBase.__init__`；内部存成保留 id `__llm_tool__{name}` 的动态条目。
  运行期也可 `register_llm_tool(...)` / `unregister_llm_tool(...)` / `list_llm_tools()`。
- **结果与错误协议**：普通返回值 → `{"output":…,"is_error":false}`；handler 返回
  `{"output":…,"is_error":true,"error":…}` 则原样透传；失败**一律 HTTP 200 + is_error:true**
  （绝不 5xx），于是模型看到的是"工具级失败"。错误码：`TOOL_NOT_REGISTERED`/`PLUGIN_NOT_RUNNING`/`TOOL_TIMEOUT`。
  图片可经 `{"output":…,"images":[{"data_b64","mime","vision_prompt"}]}`（≤2 张、每张 ≤2MB，jpeg/png）。
- **注意**：`@plugin_entry` 一类普通条目**不是** LLM 工具，它们走 agent 分析器路由
  （拉 `GET /plugins` → 分析器选 `plugin_id`+`entry_id`+参数 → `POST /runs`）。
  可用 `metadata.agent_hidden` / `agent_exposed|llm_exposed:false` 把它从路由里藏掉。

## 3. 插件 ↔ host 通信

- **插件 → host（唯一的主动注入通道）**：`self.push_message(**kw)`，轴为
  `visibility`（`"chat"`/`"hud"`/`[]`：part 渲染到哪）× `ai_behavior`
  （`"respond"` 起一轮 / `"read"` 静默注入上下文 / `"blind"` 对模型不可见）× `parts`
  （text/image/audio/video/ui_action），另有 `source`/`priority`/`coalesce_key`/`target_lanlan`/`metadata`。
  内联载荷上限 512KB，更大图片走 `ctx.images.upload(...)`。
  `{MASTER_NAME}`/`{LANLAN_NAME}` 由 host 侧展开，插件不要自己替换。
  另有 `await self.finish(data=…, delivery=proactive|passive|silent, message=…)` 回任务结果、
  `self.report_status(dict)`、`await self.plugins.call_entry("别的插件:entry", {…})`、
  `await self.store.set/get`、`await self.config.get/dump`。
- **总线是只读/可 watch 的**：没有 `emit()`/`on()`；只能 `await self.bus.events.get(...)`
  → `.filter(...).sort(...).limit(n).watch(self.ctx)`，再 `@watcher.subscribe(on="add")`（只有 add/del/change）。
- **插件不能注册 HTTP 路由**：唯一的 UI 口子是 `register_static_ui` 与 `[plugin.ui]` 声明的托管界面
  （`sandbox="allow-scripts"` 的 iframe）。**外部进程无法直接 push 进 N.E.K.O**——
  这条事实决定了 §5 的结论。出站请求是插件自己的事（SDK 不带 HTTP 客户端）。

## 4. 两个既有插件（现状事实）

### `game_agent_minecraft`（完整的，不是壳）

- 传输：**一条长驻 WebSocket** 到一个独立的本地 "mc-agent" 程序，`ws://localhost:48909`，
  断线每 5s 重连。**不用 MCP、不用 HTTP**。那个 agent 的自身面板在 `http://localhost:8765`。
- 帧：→ agent `{"type":"task","task","task_id?"}`、`{"type":"query_inventory"}`；
  ← agent `log{text}`、`screenshot{image,encoding}`、`task_finished{status,text,task_id?}`
  （status 透传 `ok/interrupted/superseded/failed/timeout`）、`alert`、`inventory`、
  `bot_status_nl`、`ingame_chat`、`agent_status`。
- LLM 工具**只有一个** `minecraft_task`（`task` 必填 + `overwrite` 布尔），
  **fire-and-forget**：立刻回 `TASK_DISPATCHED_ACK`，真结果稍后以 push_message 送达。
- 另有三个面向 agent/UI 的条目（`game_agent_status` / `query_inventory` /
  `game_agent_reload_config`）与一个托管界面。`auto_start=false`，要手动启。

### `mcp_adapter`（**出站** MCP 客户端）

- 连接**外部** MCP 服务器，把它们的工具再发布成 N.E.K.O 条目。N.E.K.O 自己**不是** MCP 服务器。
- 传输三选：`stdio` / `sse`（老式 HTTP+SSE）/ `streamable-http`（单 URL POST JSON-RPC）。
- 握手 `protocolVersion` **硬编码 `2024-11-05`**，且**不校验**服务端回的版本；只认 JSON-RPC `error`。
- 鉴权：配置里的 `headers` 会合并进每个请求 ⇒ `Authorization: Bearer …` 可用；无 OAuth。
- 命名：工具 id = `mcp_{server}_{tool}`；以**动态条目**注册（不是 `@llm_tool`），
  显示名 `[{server}] {tool}`，结果经 `finish(...)` 叙述式返回。
- 服务器**可以只改配置就加**：`[mcp_servers.<name>]` 带 `transport|command|args|url|env|headers|enabled`，
  加 `[mcp_adapter]` 的 `connect_timeout|tool_timeout|auto_reconnect|reconnect_interval|max_reconnect_attempts`。
- **能力缺口**：`streamable-http` **只 POST、从不打开 GET SSE 流** ⇒ 永远收不到服务端→客户端的
  notification；`resources/read`/`prompts/get` 被归一化后仍一律发 `tools/call` ⇒ 实际不支持。

## 5. mcbot 的接入结论

mcbot 现有两个入口：REST+SSE（`127.0.0.1:57121/v1/*`，全部路径要 `Authorization: Bearer <bridge.token>`，
`/v1/events` 额外允许 `?token=`）与 MCP JSON-RPC（`POST /mcp`，
工具 `mcbot_task/ask/answer/status/cancel`；mcbot 的 `initialize` **忽略客户端请求版本**、
固定回 `2025-03-26`）。

### 路线 (b)：MCP —— **今天只改配置就能用**

1. 启用并启动内置 `mcp_adapter`（`enabled=true`，`auto_start=false`，需手动启）。
2. 在它的**运行期**配置里加一段 `[mcp_servers.mcbot]`：`transport="streamable-http"`、
   `url="http://127.0.0.1:57121/mcp"`、`enabled=true`、
   `headers` 里放 `Authorization = "Bearer <bridge.token>"`（token 从 `<gameDir>/mcbot/bridge.token` 读）。
   同时把 `tool_timeout` 设成 **≥130**（必须大于 `mcbot_task` 的 `wait_s` 上限 120 + 余量）。
3. 打开 agent 的 `user_plugin` 能力，否则这些条目根本不会呈给模型。

得到 `mcp_mcbot_mcbot_task` 等五个条目。**代价（可用但二流）**：
(i) 它们是**分析器路由的条目**而非对话 LLM 直调工具 ⇒ 多一跳，且没法按服务器调 `keywords`；
(ii) **没有服务端→客户端推送** —— mcbot 的 `/v1/events`（progress / `question` / 完成）在 REST 面，
     MCP 面没有，adapter 也从不打开 GET 流 ⇒ **"猫娘主动反应"这条产品特性拿不到**；
(iii) `mcbot_task` 会阻塞到 `wait_s`（默认 8、上限 120）⇒ 必须把 `tool_timeout` 抬到 130+；
(iv) token 必须抄进 N.E.K.O 的插件配置，**与 mcbot 卫生线（token 只留在 `mcbot/bridge.token`）冲突**
     ——这条要主人拍板。

### 路线 (a)：REST+SSE —— **必须写新插件**

现有通用桥只有 `mcp_adapter`，而外部进程又无法 push 进 N.E.K.O（§3）。所以要走 REST+SSE，
需要一个仿 `game_agent_minecraft` 形状的新插件（`plugin/plugins/mcbot_bridge/`）：

- `plugin.toml`：`[plugin]` + 自定义 `[mcbot]`（`base_url`/token/超时）+ `[plugin_runtime]` + `[plugin.i18n]`。
- `__init__.py`：`@neko_plugin` 类，`@lifecycle(id="startup")` 只读配置**不起任务**；
  后台 SSE/事件客户端在 **`_on_command_loop_start()`** 里起（长驻循环，见 §1 的坑）；
  `@llm_tool` 包 `mcbot_task/ask/answer/status/cancel`，失败回 `{"output":…,"is_error":true,"error":…}`；
  一个 SSE 客户端盯 `/v1/events?token=…`，把 progress/`question`/完成翻成
  `push_message(...)` —— **这正是 MCP 路线拿不到的主动性**。
- 可选：`@plugin_entry` 的 `mcbot_status`（人问"连上没有"）与 `mcbot_reload_config`。

### 建议

1. **Phase 0（几小时、零 mcbot 代码）**：先按 (b) 用 MCP 验协议兼容性
   （重点验 `2024-11-05` vs `2025-03-26` 这一点）与 `tools/call` 真能跑。
2. **Phase 1（真集成）**：写 `mcbot_bridge` 走 REST+SSE —— 只有这条路同时给到
   (a) 对话 LLM 直调工具（不经分析器）、(b) 主动事件注入、(c) 不依赖 adapter 的超时管线。
3. MCP 保留作后备/诊断；**不要指望 MCP notification**。

## 6. 未决 / 未验证（别当已验事实用）

1. mcbot ↔ `mcp_adapter` 的握手**未实跑**；版本容忍只是两侧代码读出来的结论，须真跑一次。
2. 内置插件的"仓库 manifest vs 运行期副本"哪份赢，未端到端追（两段代码路径都在）。
3. 分析器能否稳定挑中 `mcp_mcbot_mcbot_*`，取决于 BM25/粗筛，属经验问题；且按服务器调 `keywords` 做不到。
4. 全新第三方插件的发现规则未端到端追（内置树 vs 安装目录 vs 打包 CLI）。
5. `mcp_adapter` 对 SSE 分帧的 `tools/call` 回复容不容忍，代码有处理但未对 mcbot 实测（mcbot 固定回 JSON）。
6. 插件市场/安装桥、`sdk/adapter` 网关内核细节未勘察。
