# mcbot 工程进度（STATUS）

> 给后续施工者（人或 AI）：先读仓内 `AGENTS.md`（纪律），再读本文件（唯一进度事实源，
> 每完成一个里程碑更新），as-built 细节看 `docs/`，完整蓝图 `mcbot-DESIGN.md` 也在仓内。

## 当前状态：M0–M4 ✅ · 联机首测 ✅（14:20）· M6 桥接代码 ✅（14:38），待活体联调 + M5/M7/M8

| 里程碑 | 状态 |
|---|---|
| M0 脚手架 | ✅ |
| M1 假玩家身体（soak 30分36秒 0 踢线） | ✅ |
| M2 agent-core（真实端点通过） | ✅ |
| M3 握手回路 | ✅ **端到端通过**：真实账号 owner 召唤、status/scan_area 往返、模型主动扩大扫描半径再作答；线程修复生效无渲染崩溃 |
| M3.5 G 面板（配置/召唤/聊天，热重启大脑） | ✅ 实测通过（key 问题为用户侧凭证，已闭环） |
| M4 行动工具批 + 跨 tick 任务框架 | ✅ 完成（无头链路 11:28 + 真实实测 11:38：自主寻矿→两次 move_to 机动→发现铜煤矿，差最后 break 时用户退出） |
| M4 收尾：真取消 / wait / 重进安全落点 / 挖掘 onAbort 清裂纹 | ✅ 14:09 无头 `[m4b]` 全命中 + loop 单测 7/7 |
| M6 桥接（neko 入口） | ✅ 代码完成（14:38，单测 12/12：桥内核 5 条全绿）——**待活体联调**（主人客户端进世界后用 curl/neko 打） |
| M5 感知记忆 / M7 neko / M8 DigAStar | ⬜ 见设计文档 §11 |

### M6 实现备忘（桥接）

- **内核在 agent-core**（`bridge/BridgeService+EventRing+BridgeBackend`，零 MC 依赖，可单测）；
  客户端 `bridge/BridgeHttp` 只是薄适配（JDK HttpServer 绑 **127.0.0.1:57121**，绝不监听 0.0.0.0）。
- **鉴权**：`Authorization: Bearer <token>`，token 存 `<gameDir>/mcbot/bridge.token`（生成后跨重启稳定）；
  常量时间比较；SSE 允许 `?token=`（EventSource 带不了 header）。401 拒一切未授权。
- **REST**：`POST /v1/task {text,wait_s≤120}`→`{task_id,done,fragments[]}`（窗口内盯事件流，
  同 task_id 的 progress 收片段、done/state 即停）；`POST /v1/task/{id}/cancel`；
  `POST /v1/ask {text}`→`{answer}`（≤60s，超时 504）；`POST /v1/answer {question_id,text}`；
  `GET /v1/status`；`GET /v1/events`（SSE，`Last-Event-ID` 按 200 条环形缓冲补发，15s 心跳）。
- **MCP**：`POST /mcp` JSON-RPC（initialize / tools/list / tools/call，protocol 2025-03-26），
  五工具 `mcbot_task/ask/answer/status/cancel`——全是任务级，原子操作不出现在这层。
- **事件生产者**：AgentRunner 在 onReply/onToolInvoked/onNotice/handleS2c/requestCancel 处
  `BridgeEvents.publish(type,{ev,task_id,text,...})`；桥没起时 publish 空操作。
- **ask_owner 闭环**（本次补齐 answer 半件）：模型本地工具（不占服务器槽位），
  question 事件带 `question_id=qN` → neko/主人经 `/v1/answer` 或 MCP 回 → 大脑续跑；5 分钟不回按"自行定夺"教。
- 生命周期：JOIN 起、DISCONNECT 关；面板顶部显示端点+token；热重启大脑会作废旧 ask/answer。
- 已知边界：桥只服务当前进世界的这个主人（runner 生命周期）；多主人各开各的客户端天然隔离；
  `wait_s=0` 即"投了就走"模式。

### M4 收尾备忘（14:09 SelfTest `[m4b]` 判读用）

- **真取消全链路**：`@bot 停/停下/停手/取消/别干了/cancel/stop`（整句匹配才拦截，普通句子仍走大脑）
  或面板"叫停进行中的任务"按钮 → C2S `cancel` → 服务端按 owner 校验找同伴 →
  `CompanionScheduler.cancel` 走 `onAbort` 收尾 + future 以 `CANCELLED:` 教学回执完成
  （这条回执**就是**当时挂着的 tool_result，seq 对得上，顺流回大脑）。
  客户端 `AgentLoop.cancelDirective`：清空排队指令 + 标记叫停，在**下一个边界**生效——
  步首命中则回"（收到，我先停手…）"；若正停在模型流式回答上，整轮作废不执行工具（不留悬挂 tool_calls）。
  叫停标记不追溯新指令（pump 换发时清零）。
- **wait 工具**（第 8 个）：站定等 1-60 秒，TickTask 计数，给 M5 熔炉/作物节奏用。
- **重进安全落点**：`respawnAllFromRoster` 进场后检查存档落点可站立（两格空气+实地），
  不行就近 findNear，再不行去世界出生点。上线即抓到老 bug：`steve 存档落点 0,0,0 不可站立，已挪到 0,63,0`。
- **break_block 中止/超时现在会广播 -1 清裂纹**（onAbort），客户端不留假进度条。
- 判读基准（14:09 实测行）：`busy拒收=true cancel命中=true 空槽cancel=false`；
  叫停回执 `ok=false CANCELLED:`；约 2 秒后 `wait 完成: ok=true`。
- 测试：agent-core 7/7（新增 cancel 语义测试：叫停后续跑链在步首停、工具回执留在对话、新指令不受旧标记杀伤）。

### M4 实现备忘（判读日志用）

- **挖掘不走 `handleBlockBreakAction`**：假玩家没有 connection tick 驱动其内部进度，
  START 会静默失效（实测 40 秒无进展）。改为手工计时：`progress += getDestroySpeed/hardness/30`
  每 tick + 广播 `ClientboundBlockDestructionPacket`（-1 清除），完成走
  `Block.getDrops(..., player, tool)` 战利品表（错工具真的没掉落）→ 背包吸附 → `destroyBlock(pos,false,c)`。
  裂纹动画全客户端可见，与真人无异。
- **move_to 是滑步占位版**：每 tick teleport ≤48 格直线推进，自动上坎/下坎，
  实心挡死 → `PATH_BLOCKED`（不挖不改世界）。M8 DigAStar 替换。
- 本世界地表有 **Leaf Litter** 覆盖层会挡滑步路（实测发现）。
- break_block 实测圆石+木镐 ≈ 6.5s（含采集收尾拍），速度公式真实生效。
- transfer 用原版 `Container` 接口 + `isSameItemSameComponents` 堆叠合并。
- 每同伴单活跃任务槽：忙时回 `BUSY` 教学回执（无队列，链式组合留给任务链）。
- 实测修正：scan_area 回执曾谎称"以面朝方向为前"，数据实为世界轴偏移——已改诚实措辞；
  真·朝向相对坐标随 M5 字符网格一起上。

## 环境迁移记录（2026-09-07，新机 qaqms@F:\ai\mcbot）

机器从原开发机（D:\ai、用户 lulu/mise）迁到本机，以下三项为本机适配（仓库内已改，换机仍需改）：

| 项 | 新值 | 实测依据 |
|---|---|---|
| `org.gradle.java.home` | `C:\Users\ms\.gradle\jdks\eclipse_adoptium-21-amd64-windows.2`（Temurin 21.0.10，Gradle 自动置备） | java -version 确认 |
| wrapper distributionUrl | `file:/F:/ai/gradle-9.5.1-bin.zip`（140MB，curl 断点续传+unzip -t 验完） | 官方源经 github 重定向链路不稳，10s 超时实锤 |
| 代理 systemProp | 已全部移除，Gradle 走直连 | 直连实测：fabricmc 200 / piston-meta 200 / plugins.gradle 200 / central 200；central 走 10809 代理反回 403 |

网络事实：本机系统代理 127.0.0.1:10809 仅浏览器/curl 用；JVM 不读注册表代理，构建一律直连；Maven Central 直连偶发 000，重试即过（未上镜像，若再频发考虑 aliyun）。

新机无头验收（21:29，`gradlew build` 4m48s 全绿 + autotest.flag SelfTest）：
- 单测 12/12（bridge 5 + loop 4 + provider 3，XML 核实 0 fail）；产出 mcbot-0.1.0.jar。
- M1：`steve[embedded] logged in` + `同伴 steve 已召唤 (owner=console)`。
- M3 五场景全命中：79B 白名单拒 / 97B owner 拒 / status+scan 直调 / 255B 正向(seq=42) / 速率闸 59×82B + 21×77B（与旧基线完全吻合）。新世界出生点 (-512,105,-128)。
- M4：place → break(7s 真耗时) → move_to → chest → transfer(存 3 圆石)；M4b：`busy拒收=true cancel命中=true 空槽cancel=false`，叫停回执 CANCELLED，2 秒后 wait ok=true。
- 唯一 ERROR 为新 run 目录首启缺 server.properties（自动生成，良性）；收服后无孤儿 java。

## 1.21.11 API 实测字段笔记（javap 自证，勿凭记忆改写）

| 事项 | 真实形状（Mojang 映射） |
|---|---|
| 假玩家进场 | `PlayerList.placeNewPlayer(Connection, ServerPlayer, CommonListenerCookie)`；cookie 用 `CommonListenerCookie.createInitial(GameProfile, false)`（record：profile/latency/ClientInformation/transferred） |
| 客户端信息类 | `net.minecraft.server.level.ClientInformation`（**不在** network 包），`createDefault()` |
| ServerPlayer 构造 | `(MinecraftServer, ServerLevel, GameProfile, ClientInformation)` ✓ 公开可子类 |
| GameProfile | authlib **7.0.61** 起为 record：`new GameProfile(uuid, name)`，访问器 `id()/name()`（**无 getName/getId**） |
| 重生点 | `player.setRespawnPosition(new ServerPlayer.RespawnConfig(LevelData.RespawnData.of(dimensionKey, pos, angle, rot), true), false)` |
| 游戏模式 | `player.setGameMode(GameType.SURVIVAL)`；常量名是 `SURVIVAL`（**无 GAME_TYPE_ 前缀**） |
| OP 判定 | `PlayerList.isOp(NameAndId)`；`new NameAndId(uuid, name)` 或 `NameAndId.createOffline(name)` |
| 连接发包 | `Connection.send(Packet<?>) / (Packet<?>, ChannelFutureListener) / (Packet<?>, ChannelFutureListener, boolean)` —— PacketSendListener 已不存在，**三个重载都要覆盖**才能全丢 |
| 连接断连 | `Connection.disconnect(DisconnectionDetails)` ✓ 可覆盖吞掉 |
| 命令权限 | `Commands.hasPermission(Commands.LEVEL_GAMEMASTERS)` 做 `requires`；取玩家用 `source.getPlayer()`（可 null；getEntity() 返回 Entity） |
| 世界出生点 | `serverLevel.getRespawnData().pos()`（getSharedSpawnPos 已不存在） |
| 进场日志 | 假玩家 `steve[embedded] logged in with entity id N`，一切正常 |

## M1 已获证据

- 编译：身体层全部按上表签名修正后 `gradlew build` 全绿（含 client sourceset）。
- 召唤（SelfTest 直跑 dispatcher）：`mcbot ping→pong`；`mcbot summon steve` →
  `steve[embedded] logged in` + `steve joined the game` + `同伴 steve 已召唤 (owner=console)`；
  `mcbot list` → `steve 主人=console [在线]`；companions.json 落盘。
- 重启重进：第二次启动 `steve[embedded] logged in` + `同伴 steve 随服务器重进`；停服 0 异常。
- **soak（09-07 重测，前一次被会话休眠静默带走不算数）**：`stdin=/dev/null` 脱钩运行，
  09:29:03 入场 → 09:59:39 仍 LISTENING，**30 分 36 秒零踢出、零异常**；`gradlew --stop` 收服。

## M2 已获证据（离线部分）

- `gradlew :agent-core:test` → **6/6 绿**（XML 报告核实 0 fail 0 skip）：
  SSE 分片拼装 tool_call（id/name 首片、arguments 跨片）；OpenAI wire 消息序
  （system/user/assistant/tool + tool_calls/tool_call_id 字段）；baseUrl 规整；
  loop 工具往返+回复；重复调用 nudge@3→abort@5 护栏；超水位压缩出"前情提要"且保留尾部。
- 依赖策略：Gson 在 agent-core 为 compileOnly+runtimeOnly——mod 运行时用 MC 自带 Gson，
  独立 `:agent-core:run` 自备，避免内嵌 jar 重复打包。
- **真实端点验收已通过（2026-09-07，临时 key，用后即焚）**：`now {}` 与
  `add {"a":137,"b":245}` 两次 SSE 流式 tool_call 往返，模型最终合并作答"09:27:49 / 382"。
- 已知 harness 小缺陷（仅 Cli）：done 闩锁一次性，多轮输入会在第二轮立刻返回——harness 专用，
  不影响 mod 路径（M3 重写监听侧）。

## M3 已获证据（无头，2026-09-07 10:11）

SelfTest 五场景 + `[m3] S2C` 协议痕迹：

1. 白名单闸：`tool=no_such_tool` → tool_result(79B) DENIED ✓
2. owner 闸：同伴属 console 时 steve 发 status → tool_result(97B) DENIED ✓
3. 工具直调：status "我在 minecraft:overworld (0,63,0)，生命 20…" / scan_area 空场摘要 ✓
4. 正向全链路：临时 owner 自指 → tool_call seq=42 → tool_result(241B 含 data) ✓
5. 速率闸：80 连发 = 59×通过(82B 白名单拒) + 21×频率拒(77B)，与 burst=60 扣 3 的账吻合 ✓

1.21.11 网络 API 实测：`PayloadTypeRegistry.playC2S/playS2C().register(Type, Codec)`、
`ServerPlayNetworking.send/receiver(payload, context)`、codec 用
`ByteBufCodecs.STRING_UTF8.map(ctor, v -> v.field)`（本映射层 ByteBuf 无 writeUtf 暴露）；
标识类真名 `net.minecraft.resources.Identifier`；ItemStack 用 `getItemName()`。
已知简化：C2S 尺寸闸靠 STRING_UTF8 默认上限 + 解析失败丢弃（readUtf(max) 不可用），M5 加固。

**M3 客户端端到端验收步骤（需要主人，2 分钟）**：
1. 新 key 写入 `run/mcbot/client.json`：`{"base_url":"https://api.deepseek.com","model":"deepseek-chat","api_key":"sk-新key","persona":"你话少但靠谱"}`（该目录已 gitignore）
2. `./gradlew runClient` → 进 localhost 服 → `/mcbot summon 你的名字后缀`（若还没有）→ 聊天框输入 `@bot 看看附近有什么`
3. 期望：`[同伴]` 蓝色消息复述 scan 结果；服务器日志出现 `[m3] S2C -> ... tool_result`

## M3.5 配置面板（客户端 GUI，2026-09-07 追加）

需求方："端到端测试要可视化面板配置"。已交付并编译通过，等待主人实机体验：

- 键位 **G**（可改）打开 `McbotPanelScreen`：左列回看 transcript、右侧配置区
  （base_url/model/api_key/persona + 保存并应用 + 召唤/遣散 + 查状态快捷指令 + 底部聊天框回车即发）。
- 保存即写 `<gameDir>/mcbot/client.json` 并**热重启大脑**（对话清零）。
- 测试包已备：`dist/mcbot-0.1.0.jar` + `dist/fabric-api-0.141.6+1.21.11.jar` +
  `dist/README-DIST.md`（启动器实例 mods/ 放两个 jar → runServer 起服 → 进 localhost）。

1.21.11 GUI 实测漂移（补录进防漂移表）：键位类真名 `net.minecraft.client.KeyMapping`
（Mojang 风），**无 wasPressed() 用 `consumeClick()`**，分类 `KeyMapping.Category.MISC`（record 嵌套）；
`Screen.keyPressed(net.minecraft.client.input.KeyEvent)`（record: key()/scancode()）；
`shouldPause()` → `isPauseScreen()`；输入框 `EditBox(Font,x,y,w,h,Component)`，
`setHint(Component)`，**无 setShouldMaskInput（key 明文显示，已知边界）**；
fabric `KeyBindingHelper.registerKeyBinding(KeyMapping)`。
**Screen.addWidget() 只注册事件不渲染**——EditBox 一类需在 render() 里手动
`box.render(g,mx,my,delta)`（或确认 addRenderableWidget 的泛型边界后用它）。

## 无头测试工装（M1 起长期可用）

- **SelfTest harness**：创建 `run/mcbot/autotest.flag` → 启动约 3 秒后在服务器线程直跑
  `dispatcher.execute("mcbot ping"/"mcbot summon steve"/"mcbot list")`，异常全量入日志，自动删 flag。
  这是当前自动化验收的标准入口（M3 起扩展成工具调用往返测试）。
- ⚠️ **开发 harness 的控制台 stdin 有缺陷**：经 gradle stdin 管道送进去的**原版命令（如 `list`）同样报
  "An unexpected error occurred trying to execute that command"**，且该路径吞堆栈。已验证与 mcbot 无关，
  游戏内玩家命令与 payload 直调均正常。**不要**用控制台 stdin 做验收，一律走 SelfTest。
- `tools/list-java.ps1`：区分 Gradle daemon / Kotlin / 残留 MC 进程（TaskStop 后孤儿 java 要用它找出来杀，
  否则 run/session.lock 会锁死下次启动）。

## 已知偏差与限制（M1）

1. 加入/退出消息未隐藏（需拦截玩家列表广播，方案留给 M3 网络层一并定，属装饰性）。
2. 控制台召唤的同伴 owner=NO_OWNER，仅测试/预配用。
3. 同伴 v1 全程 `setInvulnerable(true)`，M4 战斗里程碑放开。
4. 新召唤会 `teleportTo` 到主人脚边/世界出生点；重启重进位置由原版玩家存档恢复。
5. 名册文件：`run/mcbot/companions.json`（Gson)；同伴背包/位置存原版 playerdata（`run/world/players/*.dat`），dismiss 不删存档（保留再召唤复原，清档策略待定）。`.dat` 落点坏点已由进场安全落点检查自愈（见 M4 收尾备忘）。
6. 服务端工具现共 **8/14**（status/scan_area/break/collect/place/move_to/transfer/wait）；剩余：craft/smelt/inspect_block/wait_until(并入 wait)/talk/navigate(并入 move_to)/attack——craft、smelt、inspect 随 M5 上。

## M0 验收证据

- `./gradlew clean build` → BUILD SUCCESSFUL，0 编码告警（javac 已钉 UTF-8）。
- `./gradlew runServer` → `(mcbot) mcbot initializing; agent-core version=0.1.0` → `Done (4.780s)!`。

## 工具链事实（勿随意改动）

| 项 | 值 | 来源/说明 |
|---|---|---|
| Minecraft | 1.21.11（混淆时代，loom-remap 插件） | fabric-example-mod `1.21.11` 分支 |
| Loom | `net.fabricmc.fabric-loom-remap` **1.17.20** | fabric maven 确认存在 |
| Fabric Loader | 0.19.5 | meta.fabricmc.net |
| Fabric API | 0.141.6+1.21.11 | Modrinth |
| 映射 | `loom.officialMojangMappings()` | 官方示例默认 |
| Gradle | 9.5.1，wrapper 走 `file:/D:/ai/gradle-9.5.1-bin.zip`（本地缓存） | 删 zip 可换回官方 URL |
| 构建 JDK | Temurin 21.0.12（mise，路径在 gradle.properties） | 换机器要改 |
| 代理 | 127.0.0.1:7897（gradle.properties systemProp 已配） | 不需要时删该段 |
| 运行约束 | TaskStop 杀不掉 javaexec 子进程 → 用 tools/list-java.ps1 找 PID 再 taskkill | 见上节 |

## 目录结构（现状）

```
D:\ai\mcbot\
  settings.gradle / gradle.properties / build.gradle   工具链与三模块装配
  agent-core/          纯 JVM 大脑层（零 MC 依赖）+ bridge 内核
  src/main/            公共+服务端：body/ server(+tools/) task/ command/ common/
  src/client/          客户端：agent/ bridge/ cfg/ ui/
  docs/                ARCHITECTURE / DEVELOPMENT / TOOLS / BRIDGE / ROADMAP（as-built 文档组）
  AGENTS.md            AI 执行者开工第一页（纪律入口）
  mcbot-DESIGN.md      施工前完整蓝图   README.md 项目首页
  tools/               list-java.ps1（进程清理助手）
  dist/                测试包 + README-DIST / README-BRIDGE
  run/                 dev 运行目录（gitignore；eula 预置；mcbot/ 为名册与 flag）
```

## 联机首测 ✅（2026-09-07 14:20–14:23，真实 TCP，非整合服）

主人客户端 `1[/127.0.0.1:58903]` 进 `runServer`（dev 实例临时切 `online-mode=false` 供局域网测试，
正式联机按 §10 再议）→ 面板召唤 aimi（owner=1）→ `cancel_ack` → 5 次 `tool_result`（最大 773B）→
主人退出后假玩家留场，全程 0 异常 0 误拒。§3 网络信封首次过真网络，验证通过。

## 下一步 A（历史）：联机首测步骤存档

步骤：

1. 用 `dist/mcbot-0.1.0.jar`（14:11 起含 M4 收尾）替换启动器实例 mods/ 里的旧包，fabric-api 不变。
2. 开发机起服：`./gradlew runServer`（默认 25565 端口，离线模式可进）。
3. 你的客户端进 `localhost:25565`，G 面板召唤同伴 → `@bot 看看附近有什么`。
4. **另开一个账号**（或朋友进服）当旁观者验证：假玩家对别的客户端同样可见、裂纹动画、行走、说"停"后立刻站住。
5. 观察点：聊天回显 `[同伴]`/`[mcbot]`；服务器日志 `[m3] S2C -> ... tool_result`；客户端断线重连后能否继续驱动同伴。

## 下一步 B：M6 桥接（联机首测通过即开工）

neko 的"最后一公里"：客户端侧起 127.0.0.1 HTTP/SSE（JDK HttpServer），随机 token，
暴露 `mcbot_task/ask/status/cancel`（设计 §9）；同时补"事件生产者"——任务起止/工具回执
以 SSE 推给 neko，改变现状"有管道没水源"。

## 施工纪律（所有 AI 执行者必读）

- **Clean-room**：允许从 `D:\1mcckao\minecraft-numen-1.21.1` 读**机制与设计动机**；
  禁止复制/翻译其任何代码、注释文本、README 句子、资源；禁止使用 "Numen/言出法随" 名称与美术。
  机制一手参考：Carpet 同版本分支（假玩家）、Baritone 公开文章（寻路思想）、
  arXiv 2410.08500（空间字符网格）、OpenAI 协议文档（function calling/SSE）。
- 验收不达标不进下一里程碑；一次只推进一个里程碑。
- 桥接（M6）只绑 127.0.0.1 + 随机 token；服务器侧三道闸（尺寸/速率/Schema + owner 校验）
  必须随 M3 一起落地，不许"先跑通再补安全"。
- API 签名一律以 javap/编译器为准（见"1.21.11 API 实测字段笔记"），别信任何人的记忆，包括我的。
