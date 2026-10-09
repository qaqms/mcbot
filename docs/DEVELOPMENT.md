# 开发上手（环境 / 构建 / 验收 / 纪律）

> 面向项目维护者、贡献者与自动化开发工具。环境配置见本文，当前进度见 `STATUS.md`，开发约定见 §5。

## 1. 工具链（版本钉死，勿凭记忆改动）

| 项 | 值 | 备注 |
|---|---|---|
| Minecraft | 1.21.11 | 混淆时代；loom **remap** 变体 |
| Loom | `net.fabricmc.fabric-loom-remap` 1.17.20 | 普通 loom 在此版本不可用 |
| Loader / Fabric API | 0.19.5 / 0.141.6+1.21.11 | |
| 映射 | `loom.officialMojangMappings()` | 注意：`Identifier` 不叫 ResourceLocation 等命名坑见 STATUS 防漂移表 |
| JDK | Temurin 21 | **仓内不写路径**；本机路径放用户级 `gradle.properties`（见 §1.1） |
| Gradle | 9.5.1 | wrapper 走**官方源**；到不了 services.gradle.org 的机器按 wrapper 注释临时改 `file:`（别提交） |
| 代理 | `gradle.properties` 内 systemProp 127.0.0.1:7897 | **本机网络相关**；换环境删该段即回退直连 |

`run/` 是开发运行目录（gitignore）：`eula.txt` 预置 true；`run/mcbot/` 放名册/配置/flag。

### 1.1 多设备：哪些东西**不许**进仓（09-10 定死）

这份仓要在多台设备上接着干，所以**任何机器相关的绝对路径都不许写进受版本控制的文件**。
历史上踩过两次：一次是另一位用户名下的 `.gradle/jdks`、一次是别的盘上的 Gradle zip，
每次都让另一台机器直接构建不起来（记录见 `STATUS.md` 环境迁移节）。

**换机器只需三步**：

1. **JDK**：在**用户级** `<GRADLE_USER_HOME>/gradle.properties`（默认 `~/.gradle/gradle.properties`，
   仓外）里写 `org.gradle.java.home=<你的 JDK 21 路径>`。**什么都不写也行**——只要
   `java -version` 是 21（MC 1.21.11 编不了 17）。
2. **Gradle 发行包**：不用管，wrapper 会自己去官方源下（缓存在 `~/.gradle/wrapper/dists`）。
   只有网络到不了 `services.gradle.org` 时，才手动下 zip 并**临时**把
   `gradle/wrapper/gradle-wrapper.properties` 的 `distributionUrl` 指过去——**改完别提交**。
   （Gradle 用 URL 的 MD5 当缓存目录名：换了 URL 就等于换了缓存，所以要重新下。）
3. **代理**：仓内 `gradle.properties` 的 `systemProp.*Proxy*` 段按你的网络删改。

文档里引用本仓一律用**相对路径**（`docs/...`、仓根写成 `<仓库根>`）；引用参考项目/桌面 AI
一律写成"开发环境中的只读副本（路径不入仓）"。写文档时检查：`git ls-files` 里不该出现
盘符路径（`gradle/wrapper` 的注释除外，那是反例说明）。

## 2. 常用命令

```bash
./gradlew build                 # 全量：agent-core 测试 + mod 编译 + remapJar
./gradlew :agent-core:test      # 只跑大脑层单测（报告 agent-core/build/test-results）
./gradlew clientTest           # 客户端实际 EditBox 与 GUI 像素布局测试，不启动游戏
./gradlew runServer             # 起开发服（25565，配合 autotest.flag 做无头验收）
./gradlew runClient             # 起开发客户端（需 GUI 环境）
```

Windows 控制台输出是 **GBK**：管道里用 `iconv -f GBK -t UTF-8` 转，否则日志乱码误判。

## 3. 无头验收 harness（标准姿势）

1. `touch run/mcbot/autotest.flag` → `./gradlew runServer` → 约 3 秒后服务器线程自动跑：
   - `m1`：三条命令（ping/summon steve/list）；
   - `m3`：五场景（白名单拒/owner 拒/直调冒烟/临时 owner 正向全链路/速率连打 80）；
   - `m4`：真挖链路（发镐+圆石+箱子 → place → break(看真实耗时) → move_to → transfer 存箱），
     前置把同伴从坏 `.dat` 落点传回世界出生点，并扫 14 格水平净带迁址（m4 仍吃地形运气，
     悬空出生点/残留箱体会让 `expanded=1` 成为"正确的 NO_PATH"）；脏世界删 `run/world` 重建。
   - `m4b`：真取消（busy 拒收 / cancel 命中 / 空槽 false / CANCELLED 回执）+ wait 走完。
   - `m8`：四场景（A 需确认流 / B 授权后真挖到达 / C 箱子神圣集未动 / D 基岩笼 NO_PATH）。
     票的事已由产品层 `CompanionChunkPads` 接管（同伴在哪 5×5 垫在哪，见 R1-S3b/STATUS 漂移表）；
     harness 不再自己持票，`[m8env]` warn 出现=产品票断供，先查 CompanionChunkPads/McbotMod 挂点。
     **A/B/C 的地形自 09-10 起是"完全密闭的石砌短隧道"**（四壁/顶/底/东端全石、只留西端门洞，
     在基准点现铺）：早先"露天两排侧墙"版本会吃地形运气，本机实测 A* 从东端外侧绕进走廊，
     于是 A 报 `需确认=false`、整道验收门变成抽奖。**密闭版与出生点无关**，故现在不必先找净带；
     坑只剩一条：**别把东端塞子一起挖空**（`dx<=6` 写成 `dx<=7` 就复现漏法）。
     判读基准：`A 需确认=true 且清单≥1` / `B 到达=true 且墙位被打通=true` / `C 箱子分毫未动=true` /
     `D 干净失败=true`。`[m8]` 开头会 `PathTask.clearPlanCache()`：m4 与 m8 用同一个
     `基准点.east(6)`，不清的话 A 会命中 m4 那条缓存，判读就不再是"A 搜、B 复用"。
     m4 的 `move_to 完成: false NEED_CONFIRM 放N格` 在凹凸地形下是正常语义（搭路=改世界需授权）。
   - `m9`：同伴持票验收（接 m8d 后自动跑）。判读基准：**A1 新家票=true；A2 远环=false
     （排除碰巧全域加载）；A3 再跳后旧家自清=true 且新家继续=true**——三条全中即垫子
     跟人/非全域/过期自清三个性质同时成立（无任何显式释放代码）。
     ⚠️ **A3 现在是红的，且原因未定（09-10 记录，别当成自己改坏了）**：
     `A3 旧家断续后自清=false` 在 09-10 16:53 那次复跑（**早于**当日任何 R2-S4 改动）就已出现，
     A1/A2 照旧通过，说明"垫子跟人 + 非全域"两条没坏、**只有"过期自清"这一条读数不对**。
     已排除一个假设：把 `run/server.properties` 的 `view-distance` 从默认 10 调到 4
     （64 < A3 的 96 格跳距）后 A3 **仍是 false**，所以不是"被自身视野掩盖"（实验完已改回默认 10）。
     待查方向（留给专项卡）：假玩家是否在出生点持有不自清的票（`ChunkTicketType` 的
     玩家票与 `POST_TELEPORT` 语义要 javap 实测）、或 4 秒观察窗不够卸块。
     **在查清之前，别把 `[m9] A3` 当作回归信号，也别据此宣布 R1-S3b 仍然成立。**
2. 判读基准都在日志行前缀里（`[m3]`/`[m4]`/`[m4b]`/`AUTOTEST`），STATUS 各节有逐条"期望值"。
   可同时建立 `run/mcbot/autotest-stop.flag`，验收链结束后通过 server.halt(false) 正常停服，
   不用控制台 stdin。`[f0-world]` 额外验当前存档名册/dispatcher/receiver 装配，
   以及遣散取消任务、再召唤、停服槽清空；不能替代客户端同进程换世界实测。
3. ⚠️ **不要用控制台 stdin 做验收**：经 gradle 管道喂命令连原版 `list` 都报
   "unexpected error" 且吞堆栈——harness 缺陷，与代码无关。一切自动化验收走 SelfTest
   （它直接 `dispatcher.execute`）。
4. 收服：`gradlew --stop` 放 daemon；若 `run/world/session.lock` 卡下次启动，
   是孤儿 java——`powershell -File tools/list-java.ps1` 找 `MC-RUN` PID 再 `taskkill //PID x //F`。

## 4. 真实端到端（客户端侧）

开发服直连 `runClient`，或发测试包：`dist/` 两 jar 放进任意 1.21.11 Fabric 实例 `mods/`，
进 `localhost:25565`，G 面板填 key → 召唤 → `@bot` 对话（详见 `dist/README-DIST.md`）。
桥接自测（进世界后）见 `docs/BRIDGE.md` 底部清单。

### 4.1 模型空响应诊断

先区分任务与只读按钮：状态/扫描按钮不经过模型，成功不证明模型任务可用。
模型页“测试连接”检查独立两轮工具往返；真正模型任务的诊断在
`[brain] llm response`，连接测试在 `[model-test] llm response`。
配对的 `[brain] llm request` / `[model-test] llm request` 记录实际出站 JSON 的结构计数。
两类摘要均只含本地枚举、布尔与数值，不记录密钥、URL、模型名、提示词、工具参数、
回执正文或内容哈希；`request` 是本进程内唯一的 HTTP 尝试编号，响应必须按它匹配请求，
不能按异步日志的相邻行配对。`attempt=2` 仅表示既有非 200 网页触发的一次 /v1 换道。

- `data=0`：没解析到 SSE data 行，结合 `format` 判断是否返回 JSON/网页或空体。
- `message>0` 且 `delta=0`：发现整轮 message 形状，不是当前实现消费的 delta 流。
- `malformed>0` / `parse_errors>0`：分别表示非对象/非法 JSON 载荷与 provider/回调消费异常。
- `reasoning>0` / `refusal>0`：对应字段出现，不把思考或拒答原文伪装成正常回答。
- `errors>0`：HTTP 200 中也可能有服务错误；该轮报失败，不因已有部分文本宣布成功。
- `empty=true`：无已识别回答/工具。不得自动重发游戏任务，以免早派发动作重复执行。

服务错误与失败请求差异（2026-10-09 补齐）：

- `error_category` 区分鉴权、权限、限流、额度、模型不可用、上下文超限、工具协议、
  不支持的参数、请求校验、内容策略、超时及上游/路由；未知值为 UNKNOWN，多帧冲突为 MIXED。
  这是本地对白名单信号的归类，不是对真实根因的独立证明。
- `error_source=CODE|TYPE|STATUS` 表示结构化证据；MESSAGE_HINT 只是有界错误文本的匹配线索。
  `error_code` / `error_type` 本身也是本地类别，不是提供商原始字符串；
  `error_param` 仅映射 model/messages/tools/tool_choice/stream/stream_options 等字段族。
  不得把未知错误直接解释成余额不足、服务宕机或协议不兼容。
- JSON 错误体可跨行解析，只保留最多 16384 字符供诊断，处理后清空。
  `error_body_truncated=true` 表示捕获超限且没有推测其内容；此时 errors=0 不证明没有服务错误。
  非流式 JSON 仍不当作成功的 Chat Completions 回答。HTTP 状态与流内错误状态分开记录。
- 先对照成功/失败请求的 messages、各角色条数/字符数、工具定义数量/字节数；
  再看 missing_results/orphan_results/duplicate_calls/duplicate_results/name_mismatches/
  invalid_calls/invalid_arguments/interrupted_groups。配对统计按每个连续工具组检查，不输出 ID。
  invalid_arguments 指参数不能解析为 JSON 对象，invalid_calls 指 ID/名称/type 的结构异常。
- 对照 stream/include_usage/tool_choice/parallel_tool_calls 与 last_role。
  null_assistant 是正常的纯工具调用形状，不能单凭它判错；回执折叠和压缩后统计的是实际出站视图。
  摘要不改变请求、不拦截或重写历史，只用于定位；结构相同也不能证明正文相同。
- 真机用同一配置与同一世界，在已有伙伴时从任务框连续两次发
  “查看自己的状态，然后扫描附近并简报”，每次等终态后再发下一次，不点只读按钮代替。
  遇错保留日志、不要反复刷；必要时再显式做一次独立连接测试或重置会话作对照，
  不同时换模型/世界/人设/参数，否则无法归因。
- 已识别 error 后不再派发后续流帧里的工具；此前已早派发的动作不能回滚。
  不新增自动重试，不额外循环付费探测；旧日志没有结构摘要，无法事后还原其请求。

仓内 `tools/ModelProbe.java` 是显式选择的无游戏执行器探针：
先 `./gradlew build`，再用 JDK 21 源文件启动模式运行它，classpath 包含
`build/classes/java/client`、`agent-core/build/classes/java/main` 和构建依赖的 Gson JAR
（平台路径分隔符 Windows 为 `;`，其他系统为 `:`）。
参数是 `<实例目录>/mcbot/client.json` 与 `task` 或 `connection`，**不要把密钥写成命令行参数**。
`task` 单请求使用真实提示词/技能/九个工具定义，只统计响应，不执行任何游戏工具；
`connection` 最多两请求，只使用本地 connection_probe 回执。会产生实际 API 用量，
不得加入 build/定时任务或自动循环。它读取磁盘配置，不模拟启动器的环境变量覆盖与游戏历史。

## 5. 施工纪律（红线）

1. **Clean-room**：可以从任何同类开源项目读机制与设计动机，**禁止**复制/翻译其代码、
   注释文本、README 句子、美术与专有名称。机制的一手参考：Carpet（假玩家）、
   Baritone 公开文章（寻路思想）、arXiv 2410.08500（空间字符网格）、OpenAI 协议文档。
2. **密钥红线**：API key 只存所属玩家客户端本地（`client.json`），永不进服务器/日志/聊天/仓库；
   桥只绑 127.0.0.1 + token；三道闸必须随网络层共存，不许"先跑通再补安全"。
3. **不猜 API**：1.21.11 映射漂移极多，签名以 javap 为准，实测结果记进 STATUS
   "1.21.11 API 实测字段笔记"（示例流程见 §6）。
4. **一次一个里程碑**；验收不达标不写下一里程碑的代码；STATUS.md 是唯一进度事实源，
   关账必须写证据（时间戳+日志行），已知偏差必须写明。
5. 提交前扫密：`git add -A` 后核对暂存清单不含 `run/`、`*.jar`（wrapper 除外）、
   含 `sk-` 字样的内容；token/密钥永不落 `.git/config` 与远端 URL。

## 6. javap 防漂移速查

```bash
# loom 缓存里的官方映射合集 jar（文件名含 minecraft 版本号，用 ls 找最新的）：
MCJAR=$(ls ~/.gradle/caches/fabric-loom/*/minecraft-*-merged*.jar 2>/dev/null | tail -1)
javap -cp "$MCJAR" net.minecraft.server.level.ServerPlayer | grep placeNew
javap -cp "$MCJAR" net.minecraft.network.protocol.common.custom.CustomPacketPayload
```

编译器永远是对的、javap 永远比记忆可信。把新学到的真实签名补进 STATUS 表格。
