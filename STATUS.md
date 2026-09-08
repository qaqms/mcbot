# mcbot 工程进度（STATUS）

> 给后续施工者（人或 AI）：先读仓内 `AGENTS.md`（纪律），再读本文件（唯一进度事实源，
> 每完成一个里程碑更新），as-built 细节看 `docs/`，完整蓝图 `mcbot-DESIGN.md` 也在仓内。

## 当前状态：M0–M4 ✅ · M4.5/M4.6 ✅ · M6 桥接 ✅ 活体关账 · M8 DigAStar ✅ 双验收 · **M5.1 闸① 字节尺寸闸 ✅ 无头关账** · **09-08 三线整改侦察定案**（寻路/延迟/面板）→ 整改卡 R1–R4

| 里程碑 | 状态 |
|---|---|
| M5.1 闸①：C2S/S2C 显式 **UTF-8 字节**尺寸闸（原靠 STRING_UTF8 隐式上限） | ✅ 无头关账（11:03 发现缺陷 + 11:11 `[m5a]` 四条全中；JUnit 5 例） |
| M0 脚手架 | ✅ |
| M1 假玩家身体（soak 30分36秒 0 踢线） | ✅ |
| M2 agent-core（真实端点通过） | ✅ |
| M3 握手回路 | ✅ **端到端通过**：真实账号 owner 召唤、status/scan_area 往返、模型主动扩大扫描半径再作答；线程修复生效无渲染崩溃 |
| M3.5 G 面板（配置/召唤/聊天，热重启大脑） | ✅ 实测通过（key 问题为用户侧凭证，已闭环） |
| M4 行动工具批 + 跨 tick 任务框架 | ✅ 完成（无头链路 11:28 + 真实实测 11:38：自主寻矿→两次 move_to 机动→发现铜煤矿，差最后 break 时用户退出） |
| M4 收尾：真取消 / wait / 重进安全落点 / 挖掘 onAbort 清裂纹 | ✅ 14:09 无头 `[m4b]` 全命中 + loop 单测 7/7 |
| M6 桥接（neko 入口） | ✅ **活体联调关账**（23:29–23:44）：401×2/status/task 管道/SSE id1-30/Last-Event-ID 补发/MCP list+call/cancel/ask-answer 反问闭环——7 项清单全中 |
| M8 真机（PCL 客户端+真实山丘） | ✅ 登顶链路：BUDGET_EXCEEDED×2 教学→NEED_CONFIRM(挖2格)→**ask_owner q8 主动征求**→授权→真挖上山 (-512,95)→扫出 coal_ore×7→诚实作答 |
| M5 感知记忆 / M7 neko | ⬜ 见设计文档 §11；M5 五项中**闸① 已关账**（09-08 11:11），余：字符网格 / craft·smelt·inspect / JSONL 持久化 / event 生产者 |
| M8 DigAStar（纯算法核+执行器+确认流+神圣集） | ✅ 代码+无头（22:38 `[m8]` 四场景全中 + JUnit 8/8）；真机见上行 |

### 活体联调证据（M6+M8 合并验收，2026-09-07 23:08–23:44）

环境：PCL 实例（E:\我的世界\测试，mods=本仓 build/libs jar+fabric-api 0.141.6）进 dev runServer（离线模式），同机桥 127.0.0.1:57121。

- **M6 七项清单全过**：①无/错 token 401；②status 字段实时准确（含 pending_questions）；③POST /v1/task 投令+窗口 fragments；④SSE 帧 id1-30 带 ev/task_id；⑤Last-Event-ID 从 18/23 断点重放准确；⑥MCP tools/list 五工具+tools/call status 成功；⑦ask→answer 闭环（q8 问→答"可以挖"→续跑到登顶）；cancel 接口 ok:true。
- **M8 真机**：模型规划上山 → 真实地形两次 `BUDGET_EXCEEDED`（8000 节点帽在 16 格短距复杂地形就撞——观察点，候选调优：可达性预检/启发权重/预算帽）→ 换目标 `NEED_CONFIRM 挖2格` → 模型**没有擅自批准而是 ask_owner 征求** → 授权后真挖登顶 (-512,95,-129) → scan 发现 coal_ore×7 → done 帧诚实汇报。
- 坑录：①用户中转站只挂 /v1 下，根路径被 CF 人机验证页接管→"一大堆看不懂的东西"=错误体整坨进聊天（已修 LlmClient：HTML 折叠成人话+非200 自动 /v1 换道重试一次，新 jar 待发）；②**git-bash curl 发中文请求体乱码**（服务端 UTF-8 无罪，python urllib 重发即正常）——以后非 ASCII 桥测试一律用 python 发；③游戏内收到"[同伴想问]"后**主人没有回答入口**（只能等超时或靠桥）——已修（见下 M4.6）。
- jar 进度：M4.5 包用户已换好真机在跑（`[brain] step tokens` 日志为证）。**09-08 11:43 已重出 dist 包**（含
  M4.5/M4.6/M8/闸①；核过嵌套 agent-core 与 `agent-core/build/libs` MD5 一致、无重复打包、
  `class_` 中间名残留 0）。主人真机下次换包直接用 `dist/mcbot-0.1.0.jar`，fabric-api 不变。
  同时修正了 `dist/README-DIST.md`：它的“已知边界”当时还停在“当前构建 = M3.5、只有
  status/scan_area 两个工具”——发版说明里这种失真必须清。

### M4.6 反问体验修正（00:52 真机实锤）

真机实锤：模型 ask_owner 后主人在普通聊天里回答→回路不通→5min 空等死循环（连吃两轮，体感"世界信息特别慢"）。修复：
①`@bot 答 <文本>` 就地喂给最新挂起问题（反问气泡附回答指引；无挂起时降为普通指令并提示）；
②反问超时 300s→120s；③scan_area 主线程耗时计入工具层，>50ms 进警告日志（"谁偷了 tick"的直接证据；
实测扫描本身 <1s 不是本次瓶颈，真凶是空等）。单测/编译全绿。

### M5.1 实现备忘（闸① 加固，2026-09-08）

- **先说修掉的是什么真缺陷**：旧闸用 `json.length()`（**字符数**）去比 `32*1024`。本项目
  回执几乎全中文（一字三字节），等于上限被抬到 ~96KB；而原版 `STRING_UTF8` 自己的
  字节红线是 98301——两边套起来就是“中文大回执能一路到 ~96KB 还不被拦，一旦越过
  98301B 就抛 `DecoderException` → **对端连接直接断**”（详见防漂移表）。
- **入站（`C2s.CODEC`）**：自己预扮 VarInt 长度前缀（`getByte`，不动 readerIndex）、自己卡
  `WireSize.MAX_BODY_BYTES=32765`；超限/畸形一律返回 `C2s.OVERSIZED` 哨兵而非报错，
  接收处（`McbotMod`）记一条 `闸①：C2S 信封超过 32768B，丢弃` 日志后丢弃。
  入站不抛 = 不踢线；且 32765B < 32767 字符红线，所以**过了本闸的信封原版解码器不可能再拒**。
- **出站（`ServerToolDispatcher#send`）**：按字节量。超限时**不再截断**（截断只会造出非法
  JSON → 对端 `Envelope.decode` 静默丢弃 → 那个 `seq` 白等 90s TIMEOUT，“尺寸闸反而造成
  一次难查的卡住”），而是换一条 **`shrink()` 生成的合法瘦身回执**（保留 `seq`/`task_id`，
  `ok=false` + “缩小范围或分批”教学）。
- **客户端发送前自检（`AgentRunner#execute`）**：超上限直接就地回 `DENIED:参数过大`，
  不进 `pending`、不占任务槽——模型能立刻学到“是我参数太肥”，而不是 90s 后一条无关的 TIMEOUT。
- **分层理由**：尺寸算术全部抽进 `common/WireSize`（零 MC、零 Gson），所以能进根工程
  JUnit（与 `path/DigAStar` 同一手法）；真 codec 往返只能在服务器运行期测，留在 SelfTest `[m5a]`。
- **顺带清的两笔**：① `Envelope.MAX_BYTES` 改为指向 `WireSize`（两处 32KB 各写一份数字必定漂移）；
  ② **`FakeConnection` 的 javadoc 里有事实错误**，已按 javap 重写：keep-alive 对假玩家**根本不跑**
  （`ServerConnectionListener.tick()` 只遍历自己受理的连接，假连接从不在其中），
  而不是旧稿说的“每 15s 踢一次、靠 disconnect 闸门捣住”；丢包与 disconnect 覆写都不是长驻的原因。
- **验收**（11:11 实跑，新机器首次无头）：`[m5a] 小包往返=true 超限被拒且不抛=true 限内放行=true
  畸形不抛=true (fat=12039字符/36039B 线上=36042B 上限=32765B)` —— 那包只有 12039 **字符**，
  旧字闸判“没超”，实际 36039 **字节**（且仍在原版 98301B 红线之内，旧世界它会一路放行）。
  全链路回归：`[m3]` 5 场景 + S2C 84 行、`[m4]` 真挖 7s/移动/存箱、`[m4b]` `busy=true cancel=true
  空槽=false` + wait 走完、JUnit 13 例（WireSize 5 + DigAStar 8）全绿，MC 侧 0 异常。
- **顺手修了验收器本身的脆弱性**：`[m8]` A/B 在本机首次实跑**不达标**（A 需确认=false，
  日志“站定在 10,63,**1**”）——原因是场景只铺了 z=0 那条道，两侧不封，而本机自然地形
  z=±1 恰好可走，A* 找到一条**真的不用挖**的绕行（算法无错，是场景假设依赖运气；
  上一台机器恰好封得住）。已给大道补砌两层侧墙，使“唯一路线就是挖穿”与地形无关：
  重跑后 `A 需确认=true 清单=1 格`（最优解只挖头格、踩脚格翻墙，与 22:38 基准吻合）、
  `B 到达=true`、`C 箱子分毫未动=true ok=true`（封侧后 C 才真的在考神圣集，
  之前是从 z=1 绕过去的——断言过了但没测到东西）、`D NO_PATH=true`。
- **已知边界（不隐藏）**：闸① 卡在 32765B 意味着未来 `scan_area` 换成字符网格（M5）后
  32 半径的回执可能真的撞上限——届时走“分批取数”，而不是把闸改松。

### M4.5 实现备忘（上下文经济学，机制参考公开项目思路、代码全自写）

- **动机**：真机反馈"回复慢"。实测：中转站小请求往返 ≈2s，但历史每步全量重发且旧估算
  （字符/3）把中文低估 ~3 倍；另有压缩按下标硬切可能切出孤儿 Tool（下一请求直接 400 的真雷）。
- **落地**：①AssistantTurn/TurnBuilder 透传 usage（含 deepseek `prompt_cache_hit_tokens` 与
  openai `prompt_tokens_details.cached_tokens` 两方言，未报=-1）；②压缩闸门改真数驱动
  （max(API 数, CJK 感知估算)>6000 且在任务步边界触发），摘要失败连续 2 次熔断、指令边界恢复；
  ③新 `Conversation.findCutIndex`：近段按 1500 token 预算从新往回攒，切点只能 User/Assistant，
  预算内无合法点则退化全量总结；④`outboundHistory()` 出站视图：同名工具被更新过的旧回执折成
  一行占位（callId/配对不动，存储全量）；⑤打转判定改为"同调用+同结果"才累计（参考项目
  用真事故换来的教训：TIMEOUT 重试合法）；⑥AgentRunner 每步日志 `[brain] step tokens prompt/completion/cached`，
  后续调参有数据可依（P2：水位数值等真机 usage 曲线再定）。
- **验收**（00:30）：ConversationTest 新增 6 例（估算口径/切点铁律/退化/折叠/真数/熔断）全绿；
  agent-core 19 例 + path 8 例全绿；mod 全编译；旧进程意外重跑的无头 `[m8]` 四场景+
  速率闸 59/21 账目吻合（服务端代码本轮零改动，此作旁证）。待用户下次开客户端换
  dist 新 jar 后，观察 `[brain] step tokens` 曲线体感提速。

### M8 实现备忘（可挖寻路）

- **分层**：`path/DigAStar`（纯算法，零 MC 导入，坐标用自打包 long，根工程新加 JUnit 8 例直测）
  + `path/DigSampler`（契约：passable/digSeconds/support/placeable/placeCost/maxPlaces/inBounds）
  + `path/LevelDigSampler`（世界翻译层，**全部安全规则在此**：神圣名册+方块实体检测、岩浆邻接否决、
  起点脚下不挖、单格>20s 不值挖、搜索盒 64/32）+ `path/PathTask`（SEARCH→确认门→EXECUTE 的 TickTask）。
  注意：放格与挖格一样吃预算（maxPlaces=背包存量，曾规划出"放2格"而背包只有1块的野路）。
- **统一动作模型**：每个节点的进入成本 = 清脚格+头格（挖）+补支撑（放），派生
  WALK/JUMP/FALL/DIG/PILLAR/BRIDGE；斜穿只走现成缝（角落不挖）。终点判据 = 目标柱 3×3×3
  （"到附近"语义与滑步版一致）。同层 WALK 保持 0.45/tick 插值——视觉回归不破。
- **NEED_CONFIRM 确认流**：未授权时不执行，回执带挖/放清单（data.blocks ≤32 格）；模型带
  `may_alter_terrain=true` 重发才执行；执行期重规划出新需改世界的路也会就地回 NEED_CONFIRM（不绕闸）。
- **执行期复核**：每提交 20 节点验未来 5 节点的支撑/清单格存在性，失败就地重规划（≤2 次），
  再失败 `NO_PATH:路被改变得太多…重扫再定目的地`。
- **无头验收 `[m8]`（22:38 基准，接在 m4b 后串行）**：A 石墙拦路→`NEED_CONFIRM 清单≥1`
  （最优解只挖头格踩脚格翻墙——比人预判还省）；B 授权→真挖到达，掉落进背包；
  C 箱子嵌墙→**踩箱顶绕过去，箱子分毫未动**；D 基岩笼死→`NO_PATH` 干净失败。
  同轮 m3 闸账（59×82B+21×77B）/m4 全链/m4b 真取消全绿；m4 的 move_to 已由 A* 接管（纯走路计划挖0放0）。
- **坑录**：两次"场景异常"实为旧 jar 僵尸服/双服并跑污染——跑验收前必杀干净 KnotServer（killmc 套路）；
  日志输出带 GBK，管道先 iconv。
- 已知债：挖子机与 BreakBlockTool 重叠 ~40 行未抽公共；不会自动换工具（手持不动就绕/失败）；
  不可排流体；TPS 假设 20。

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

## 09-08 三线整改侦察（六路子代理 + 逐条自证；结论入档，整改卡见 ROADMAP）

红线执行记录：参考仓只取机制动机（三路侦察 prompt 均禁止贴源码/用其专有名）；入库前做了
机器自查——三份报告的**正式结论零命中**其专有名/路径（命中全在我自己 prompt 的回显里），
疑似源码行数 **0/0/0**；所有对我方代码的断言由主会话到行号级复核后才写在这里。

### 寻路（16 格复杂山地撞 8000 帽的真因，按因果序）

1. **h 不可采纳（注释断言是假的）**：`DigAStar:224-227` `h=0.95×曼哈顿`，但斜走成本
   `MOVE_BASE+MOVE_DIAG=1.4`（`relax:177`）一次消掉 2 个曼哈顿单位 = **0.7/单位 < 0.95**
   → 对斜路**高估**；h 又完全不含挖/放价 → 对必挖区**低估**。两头都错，f 排序退化为
   按 g 的球形扩散（≈Dijkstra）——这是撞帽的**结构性主因**，不是"预算数字小"。
2. **零 memo**：每节点 26 邻居，各触碰 Level 约 100–300 次 `getBlockState`（每次 new BlockPos）；
   同格被最多 6 邻居重复全套神圣+岩浆+注册表反查（`LevelDigSampler:80,179-194`）。8000 节点=百万级方块读。
3. **撞帽即全弃**：`DigAStar:90` 直接 BUDGET_EXCEEDED，无"最近可达点部分提交"。
4. **重规划一拍同步算完**：`PathTask:250-252` 的 `while(!advance(300))` 循环（代码自注"等价于一次算完"）
   → 单帧冻主线程；初次搜索反而是分帧的（27 拍≈1.35s，可接受）。
   另：重规划整条重算、不复用 open/best（`replan:241-268`）。
5. 测试缺口：`DigAStarTest` 8 例全是平地+单墙/单柱，**无 3D 实心山体**→ 这类退化本来就测不到。

参考侧可采纳的机制（动机层，代码全自写）：预算改**墙钟**且撞预算时**降级输出半程**（"缩短射程
而非失败"）；挖/放降为**边税+待挖清单**而非一等节点；神圣/方块实体/硬度**一次性预采集进快照**供搜索只读；
重规划对旧路径格位降权抑抖动；"证明放不上的格"回填进本次拒绝集。**不采纳**：它无挖/放硬帽（一次能拆半座山，
靠模型授权兜底）——我们留帽。

### 链路延迟（重要反转：**跳数不是主因**）

实测核称：tick/网络跳合计仅 **100–150ms/工具**；一个"挖三块石头进箱子"≈**8 轮 × 2–6s = 20–50s**。
主体是**轮次数 × 每轮全量 prefill**，四个真凶全部到行号自证：
1. **SSE 是假的**：`LlmClient:57` `BodyHandlers.ofLines()` 要整条流收完 future 才完成
   （代理实测：5 帧各隔 1s，future 在 5059ms 才回）→ `stream:true` 零收益，无 TTFB/无早停。
2. **前缀每轮自毁**：`Conversation.outboundHistory:56-68` 每次现折叠历史**中段**（同名工具后来出现
   就换掉旧回执）→ 前缀逐轮分裂，缓存必不命中；`AgentLoop:142` 每步 `systemPrompt.get()`
   → `SkillLoader:21-26` 每步 `Files.list`+`readString` 读盘；压缩在 index 0 插新摘要。
   固定开销实测：tools **2972B** + system 638B ≈ 800 token 起步/步全量重发。
3. **一次一步 + tool_calls 严格串行**：`AgentLoop:166-168` `thenCompose` 链，`PromptBuilder:12`
   自注"一次调用一步"；且 `move_to`(≤3min)/`break_block`(≤60s) 的 future 在动作做完前不完成
   → **大脑整链挂起**，期间不能说话/感知/接新话。
4. **感知不给可行动信息**：`ScanAreaTool.describe:109-126` 只报 容器/含`ore`/工作台熔炉/作物，
   **石头/深板岩永不返回**；且只给 `@相对(x,y,z)` **不给绝对坐标** → 模型只能猜坐标，猜错=一整轮。
   （扫描本身不贵：步长 2、y±2、24 样本早停，r=16 实采 867 格 <2ms，回执 0.5–1.5KB 离 32765B 闸远）。

附带抓到的 4 个独立小 bug：① `McbotMod:108-113` `tickCount>=60` 永不归零 → 正常运行时
**每拍**跑 `SelfTest.onTick`，内含 `Files.exists` 磁盘检查 **20 次/秒**（开发工装漏进生产路径）；
② `BreakBlockTool:155-157` 注释"留两拍"代码 `<3`（注释与行为不符，+50ms）；
③ 桥 `/v1/ask` 60s < 反问 120s → neko 放弃后大脑还空等 60s；
④ 桥 `newFixedThreadPool(4)`（`BridgeHttp:97`）而每个 SSE 长连接**永久占 1 线程** → 2 SSE + 2 长 wait 即饥饿。
另 `Conversation.noteCompacted:102-104` 只复位 `compactFailures`、**不复位 `realPromptTokens`**
→ 压缩可连发；压缩本身还在关键路径上**串行多打一次模型**（+2–5s）。

参考侧可采纳：长动作改"**受理即回执 + 完成事件开新轮**"（身体后台跑、大脑当场自由）；易变事实
**不入历史**（服务端推镜像、限速、只读不算）；静态前缀**字节级稳定**以打穿 prompt cache。
**不采纳**：它工具派发等整条流落地、批内纯串行——那正是我们的病；我们反向做首动作增量派发。

### 面板可控性（大量"白扔的现成能力"）

- **"查状态"是伪功能**（`McbotPanelScreen:82`）：发自然语言烧一整轮 LLM，而本地免费的
  `AgentRunner.statusJson()` 只有桥在用、面板零调用。
- **persona 不必重启大脑**：`AgentRunner:109` 已是 supplier（每次现读 `cfg.persona`），但
  `reconfigure()` 一律 `loop=null; start()` → 改一句人设 = 对话清零 + 挂起反问作废，面板无提示。
- **面板挂不上事件流**：`BridgeEvents:15` 是单 sink（被 `BridgeHttp` 占死）→ 桥能看到的
  task/进度/反问，坐在屏幕前的主人反而看不到。
- 反问在面板上无横幅/无作答框/无 120s 倒计时（M4.6 只补了聊天文字入口，格式打错就变成新指令）。
- **准星目标零使用**（`hitResult`/`CrosshairTarget` 全仓零命中）→ 每条涉及方块的指令都要模型自己
  scan+猜坐标；键位只有 G 一枚（`McbotClient:31-33`）。
- 护栏/超时全硬编码（40 步/3-5 打转/90s/120s/180s），面板与配置都调不了。
- 实现约束：面板是 `init()` 里 `y+=38` 游标布局 + no-op Button 当 label + EditBox 手动 render；
  加多标签/滚动/HUD 属**中等重写**（init/render/事件三层重排），但已知漂移点都有解。
  参考侧入口分工可采纳（机制层）：面板=低频高信息 / 命令=**本地跑完不经模型** / HUD 常驻 /
  停止键＝队列外物理开关且"事件可唤醒故障停、永不撤销主人停"。**不采纳**：它对玩家无世界改动
  确认闸（只交模型）——我们的 NEED_CONFIRM→ask_owner 更稳。

**交叉点（一次改动双收益，优先做）**：感知给**绝对坐标+可挖目标** 与 **准星目标注入**
同时砍轮次和失控感；"受理即回执"既是最大延迟项的解，也是面板/HUD"当前任务"显示的前提。

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
| 寻路可用 API（M8 javap） | `DimensionType.minY()/height()` 取维度高度范围（Level 无 buildheight 方法）；`Block.byItem(Item)` 可 null；`Level.setBlockAndUpdate(pos,state)`；`Inventory` 实现 `Container.getItem/setItem/getContainerSize(41)`；`ItemStack.isSameItemSameComponents/shrink/grow`；`BlockPos.east()/above(n)` 链式可用 |
| **STRING_UTF8 的真实上限** | `ByteBufCodecs.STRING_UTF8 = stringUtf8(32767)`（clinit 里 `sipush 32767`），限的是**字符数**；`Utf8String.read` 用 `ByteBufUtil.utf8MaxBytes(32767)` = **98301 字节**卡 VarInt 声明长度，再解出来校 `s.length() ≤ 32767`。三处 `throw DecoderException`（声明>utf8MaxBytes / <0 / >readableBytes）——所以“32767 看着像 32KB”其实能放到 ~96KB 中文包。**闸① 因此自己读前缀、自己卡字节，不靠它** |
| **解码报错 = 断线** | `Connection.exceptionCaught` **只宽容** `SkipPacketException`（debug 一行就 return）；其余一律置 `handlingFault` 并走关 channel 的路。结论：自定义 codec 里报错不是“丢包”，是“踢线” |
| **监听器根本没有 tick()** | 1.21.11 的 `ServerCommonPacketListenerImpl` **无** `tick()` 方法；`keepConnectionAlive()` 在 `ServerGamePacketListenerImpl.tick()` 里被调（且先过 `isSingleplayerOwner()` 与 `now-keepAliveTime>=15000` 两道门，`keepAlivePending=true` 的**置位在 else 分支内、先过 `checkIfClosed`**，不是“无条件先置位再 send”） |
| **keep-alive 驱动链（假玩家不在环上）** | `ServerConnectionListener.tick()` 只遍历**自己受理的** `connections` 列表 → `Connection.tick()` → 监听器是 `TickablePacketListener` 才调 `tick()` → `keepConnectionAlive()`。`FakeConnection` 是手工造 + `placeNewPlayer` 直接进场，**从不进那个列表**，所以第一环就进不去——**这才是同伴能长驻的真正原因**（不是丢包、也不是 disconnect 闸门） |
| `Connection.handleDisconnection()` | 开头是 `if (channel != null && channel.isOpen()) return;`（字节码 `16: ifeq 20` 是“没开才继续”）——channel 还开着时它**直接空转**；`setReadOnly()` 则是 `channel!=null` 就 `setAutoRead(false)`，内存 channel 不 null → **会真执行** |
| `StreamCodec` 手写形状 | `StreamCodec.ofMember(StreamMemberEncoder, StreamDecoder)`；**`StreamMemberEncoder.encode(T value, O buf)` 参数是“值在前”**（与 `StreamEncoder.encode(B,V)` 相反）；`VarInt` 只有 `read/write/hasContinuationBit/getByteSize`（无 peek，所以预扮得用 `getByte`）。**闸① codec 用** |
| `Connection.pendingActions` | `Queue<Consumer<Connection>>`，由 `runOnceConnected` 在 channel 就绪时冲刷——这就是不覆写 `send` 时的慢性泄漏源 |
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
| 构建 JDK | Temurin **21.0.10**，Gradle 自动置备，`C:\Users\lulu\.gradle\jdks\eclipse_adoptium-21-amd64-windows.2`（写在 gradle.properties） | 换机器必改；本机 PATH 上的 java 是 17，不能拿来编 MC 1.21.11 |
| 代理 | 127.0.0.1:7897（gradle.properties systemProp 已配） | 本机实测代理 0.47s / 直连 0.77s 皆通；不需要时删 Proxy 两行 |
| AI 执行者 shell | pi 的 `bash` 需 `~/.pi/agent/settings.json` 配 `shellPath: "D:/git/Git/bin/bash.exe"` | Git 装在 D:\git\Git（非标准路径），pi 只默认扫 C:\Program Files\Git；改完**必须重启 pi** 才生效 |
| 仓库路径 | 本机 `D:\ai\mcbot`（origin `https://github.com/qaqms/mcbot.git`，私有） | 拉取用一次性 `http.extraHeader`，token 不落 `.git/config`/URL |

### 机器迁移记录（2026-09-08 10:20，hostname mio）

从远端拉到 `0c7f244`（本地原在 `7dc6f9f`，快进 5 个提交：`a9a4877`/`a9f4373`/`1459fa8`/`c3cca77`/`0c7f244`）。
`a9a4877` 把构建配置改到了另一台机器（用户 `ms`、`C:\Users\ms\.gradle\jdks`、wrapper 指 `F:/ai/`），
而**本机无 `C:\Users\ms`、无 F 盘**，两项均不可用，故改回本机（JDK → `C:\Users\lulu\.gradle\jdks\...`，
wrapper → `D:/ai/gradle-9.5.1-bin.zip`（zip 实测存在，140MB），代理 → 7897），与本文件上表口径重新对齐。

证据：`./gradlew build` → **BUILD SUCCESSFUL in 30s**（18 任务，16 执行/2 最新）；仅“过时 API”提示，无编码告警。
单测 XML 核对：agent-core **19 例**（BridgeService 5 / Conversation 6 / AgentLoop 5 / OpenAiCompat 3）
+ path DigAStarTest **8 例** = 27/27，**0 failures 0 errors 0 skipped**。本轮零代码改动（纯构建配置），
故未跑无头 SelfTest；下次动服务端代码按惯例补 `[m4*]`/`[m8]` 级验收。
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
