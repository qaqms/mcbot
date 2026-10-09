# mcbot 工程进度（STATUS）

> 面向项目维护者、贡献者与自动化开发工具：先读仓内 `AGENTS.md`（开发约定），再读本文件（唯一进度事实源，
> 每完成一个里程碑更新），as-built 细节看 `docs/`，完整蓝图 `mcbot-DESIGN.md` 也在仓内。

## 当前状态（2026-10-10）：身体恢复已获服务端专项与单人状态级正证，模型链路和真实拾取本轮正常；完整背包/移动挖掘/ACCEPT 长活/连接器仍待真机验，上游历史错误根因未销账

当前优先级：稳定 MC agent 的任务投递、执行、反问、取消、结束与会话清理，
不新增独立聊天功能。

**模块职责与协作边界**：
- **mcbot**：提供 MC agent 本体、游戏执行能力与任务级桥接接口，并维护接口契约及回归测试。
- **N.E.K.O 适配连接器**：将宿主侧任务和用户回答映射到桥接接口，将任务事件与结果反馈给宿主。
  连接器通过公开契约接入，不依赖 agent 的内部实现。
- **协作依据**：接口与兼容规则以 `docs/BRIDGE.md` 为准；接入流程、离线验证与联合验收见其 §6。
  各模块可独立迭代，涉及契约的变更需先协调版本与兼容方案。

原 R2-S4 阶段 3（服务端抢占/剩余路线续跑/progress 生产及限速）后置，未宣称完成。
既有 M0–M4/M4.5/M4.6/M6/M8/R1/R2 阶段 1/2 历史证据保留在下方；`[m9] A3` 仍是已知未解红项。

### 阶段收尾：文档同步与发布前复核（2026-10-10 02:14，Asia/Shanghai）

- 按维护者决定，本轮停止扩展功能，整理并提交当前 F0 检查点，不将 F0 整体关账。
  README、ARCHITECTURE、DEVELOPMENT、TOOLS、ROADMAP 与测试包说明同步当前证据：
  流式统计接线、双维度身体恢复、执行中 wait 取消、真实拾取、单人状态级恢复，
  以及仍待验的完整背包/整客户端重启、移动挖掘/ACCEPT 取消、反问、活动任务生命周期与连接器。
  TOOLS 的 ask_owner 旧 300s 文案对齐代码 120s；明确连接 15s 与请求 180s/30s 的区别。
- Git 元数据已恢复：从已有文档与本机其他副本确认原远端，再用 GitHub CLI 查询核对；
  当前 origin 为无凭据的原仓库地址，远端 main 基线 `20f63a6`。
  只创建本副本的 Git 元数据与索引，不 checkout/覆盖源码，不改其他副本、宿主或只读参考。
  当前发布分支 `fix/companion-persistence`，不直接改 main，不强推。
- 环境变量 GitHub 凭据返回 401；仅在发布命令进程内临时去掉该覆盖后，
  已有 GitHub CLI 登录确认属于维护者账号且可访问目标仓库。
  提交身份沿用既有 Git 配置；推送走命令级 credential helper，
  不读取/回显令牌内容、不将凭据写入 URL/仓库，也不删除或改写用户凭据存储。
- 本轮源码零新增修改，提交范围包含此前尚未发布的流式统计修复、身体恢复修复、
  相应回归与隔离 SelfTest 工装，以及文档；所有历史证据保留各轮当时的发布状态。
  当前暂存 21 个文件，`diff --cached --check` 通过；
  暂存危险路径/凭据模式/机器绝对路径检查均零命中，不含 run/logs/build、
  client.json/bridge.token/companions.json 或 JAR。
- **发布前全量复核**：`build --rerun-tasks --console=plain --no-daemon`
  使用单次命令直连参数，BUILD SUCCESSFUL **27s / 20 个任务全部执行**。
  02:14:16 实核 XML **283/283**（agent-core 190 + 根工程 66 + clientTest 27），
  failures/errors/skipped 全 0。仅既有过时 API 与 Gradle 10 兼容性警告。
  JAR SHA256 仍为 `f18f491c319cddc044f126854bcded93850fd52e26c8d400ea48dc85d3fb88ab`；
  list-java 无 Java 进程。未再起 MC/模型/连接器，也未修改玩家配置或存档。
  构建与扫密通过不冒充远端发布成功；实际提交/推送结果在核验后补记。

### F0 第十五轮：模型链路恢复、真实拾取与单人状态级恢复（2026-10-10 02:04，Asia/Shanghai）

- 只读维护者新一轮 latest.log、实例 JAR 与 status/collect 实现；本轮没有网络探测、
  读取密钥/配置/令牌、模型调用、源码修改、存档操作或安装包。
  实例 JAR 仍为第十三轮修复包 f18f491c…88ab。进程窗口为 02:00:32–02:04:02，
  同一客户端进程两次进入同一显示名世界，不混用上一进程 request 编号。
- **模型链路正证**：latest.log:153–196 / 236–247 共 8 次 brain 请求与 8 个响应，
  全部 attempt=1 / HTTP 200 / SSE / done=true / empty=false，
  malformed/parse_errors/errors 与出站工具配对/重复/参数结构异常计数均 0；
  4 轮 STOP、4 轮 TOOL_CALLS，均完成实际回复或游戏工具回执。
  本轮没有 model-test 请求，不能写为独立连接测试通过。
  日志仍显示与第十四轮相同 base URL 和模型名；不能据此推断维护者调整了哪些网络设置。
  本轮证明当前观察窗口已可用，不证明上一轮超时或历史 UPSTREAM 根因解决；
  上轮可疑 DNS/HEAD 对照不是“该域名永久失效”的结论。
- **时延**：7 次响应头等待在 2534–3519ms；request=2 为 34552ms，
  整轮 34761ms，最终正常工具调用，不是超时。
  15 秒是连接建立限制，不是所有响应头等待的统一上限；
  本轮没有把任务请求的 180 秒上限误改为 15 秒。
  仍不能从 ttfb 单独区分网络、网关排队或后端生成。
- **真实拾取闭环**（02:02:01–02:02:43）：request=2 触发 scan_area，
  request=3 触发 collect，服务端反馈丛林原木/白桦木按钮/丛林木/去皮丛林原木各 1，
  request=4 正常汇报。02:03:02 的实际 status 回执为背包 4/36、手持 1 个丛林原木，
  与最初 02:01:10 的空背包形成对照；不是只读按钮成功冒充真实模型工具链。
  本轮只验拾取，不扩展为移动/挖掘、ACCEPT/PARK 或取消已通过。
- **单人状态级恢复**：02:03:24 正常退出世界，Saving players/worlds 与
  All dimensions are saved 完整；02:03:26 同进程重进，steve 自动入场坐标
  (-3.5,-60,9.5)，与本轮初次入场相同，无坏落点自愈警告。
  request=7 于 02:03:40 得到实际 status：位置 (-4,-60,9)、生命/饥饿 20、
  背包 4/36、手持 1 个丛林原木，与退出前 status 一致；request=8 正常回复。
  重进后的首个请求只有 system/user，证明新大脑历史重新建立，而非延续旧拾取对话。
  这取得单人世界卸载/重载后的身体位置、非空背包占用与手持物正证，
  不等同于整客户端重启，也未逐槽核对其他物品类型/数量、朝向/耐久/副手。
- **“没有完整清单”不是丢物证据**：重进后模型正确指出 status 只提供占用与手持物，
  StatusTool 的 feedback/data 确实不枚举背包；没有旧 collect 历史时不能从 4/36 推出其余明细。
  不以模型文字猜测代替身体状态，不因此修改持久化或添加新工具。
- 02:04:01–02:04:02 第二次正常保存退出完整；启动 Mojang/Realms 401 不属于模型链路。
  当前应继续独立测试世界的真实移动/挖掘和 ACCEPT 取消，并补完整背包对照；
  wait 执行中取消已有第十二轮正证，无需重复刷模型。
  本轮仅更新 STATUS/ROADMAP，未构建；283/283 仍引用第十三轮。

### F0 第十四轮：模型连接超时与端点解析排查（2026-10-10 01:58，Asia/Shanghai）

- 本轮只读玩家测试实例日志/JAR 与相关源码，执行无凭据 DNS、TCP 和 HTTPS HEAD 对照；
  未读取 client.json 或桥令牌、未调用模型、未改代码/系统网络/配置/存档、未安装包。
  实例 JAR 与第十三轮 build/libs 的 SHA256 同为 f18f491c…88ab，排除此次使用旧包。
- **游戏证据**：最新进程 01:40:11 启动至 01:45:36 退出，
  latest.log:167–180 / 235–250 共 5 次 HTTP 尝试（brain 2 次、model-test 3 次），
  全部 attempt=1，约 15 秒失败，均 http=0 / format=UNKNOWN / bytes=data=0。
  两个任务统计 stream=15237ms/15002ms，ttfb/ttft/first_tool/first_dispatch 均 -1，
  ready/early=0；没有响应头、流帧或模型工具派发证据。
  本地 status/scan_area 于 01:44:12–13 成功，不代表模型网络正常。
  出站结构计数无配对/重复/参数异常，不能由此推断服务已收到请求。
- **超时口径**：LlmClient 默认 connectTimeout=15s，AgentRunner 请求 timeout=180s，
  模型页连接测试请求 timeout=30s。此次失败时长强烈符合连接阶段超时，
  不是等满 180 秒后的模型处理失败；现有日志未保留异常子类型，不能从日志进一步分离 TCP/TLS。
  http=0 是本地“未取得 HTTP 状态”占位，不是服务返回的状态码；
  errors=0 / error_category=NONE 也不证明远端服务健康。
- **同机网络对照**（01:55–01:58，均无 Authorization/POST/模型请求）：
  首次系统 A 查询得到 103.73.220.77，无凭据 HEAD 等满 12 秒未连通，
  对该 IP 的 TCP 443 等待 6 秒亦失败。
  同期 AAAA 查询出现 CNAME www.shopify.com；随后系统 A 查询变为
  104.18.42.163 / 172.64.145.93，HEAD 在约 0.48 秒返回 HTTP 403。
  同一 JDK 21 新进程解析到后两者、ProxySelector 选择 DIRECT，HEAD 约 1 秒返回 403。
  阿里 DNS 的 HTTPS A/AAAA 查询也报告 api.mengluo.work → www.shopify.com，
  A 为同一组地址、AAAA 无最终地址；hosts 没有相关覆盖。
  Cloudflare DNS HTTPS 查询被重置、Google DNS HTTPS 与 1.1.1.1 查询超时，
  不将失败的对照当作其他解析器的确认结果。
- **结论边界**：已取得连接阶段失败与排查时端点解析异常/变化的证据；
  不能事后证明游戏进程使用了哪个缓存 IP，不能判定域名停服、DNS 污染或具体网络设备根因。
  HTTPS DNS 对照复现该 CNAME，因此不能只归咎本机 DNS 缓存。
  根路径 HEAD 403 不等于实际 chat/completions 的鉴权/协议测试，
  更不作为“密钥无效”证据。优先向服务提供方确认当前官方 API base URL 与域名状态，
  不把旧 IP 写进 hosts、不关闭证书校验、不以拉长超时或自动重试掩盖未通的连接。
- 本轮不将单人身体恢复关账：日志只有自动重进/召唤，没有退出前非空背包与坐标对照。
  仅更新本节，未构建；283/283 自动化仍引用第十三轮，不冒充本轮重跑。
  当前模型链路需先恢复，再继续 F0 真实动作/ACCEPT 取消等真机验收。

### F0 第十三轮：身体位置与非空背包恢复（2026-10-10 01:33，Asia/Shanghai）

- 本轮只推进身体持久化专项；不改宿主、只读参考、模型配置、桥/工具协议或调度策略。
  所有 MC 实验使用项目 `run/` 内新建的独立开发世界，回环监听、专用端口；
  未读写玩家测试实例存档、未安装 JAR、未调用真实模型。当前副本无 `.git`，不声称提交/推送。
- **先复现再修**：新增由 SelfTest 挂接的 PersistenceSelfTest，专用 seed/verify flags，
  两种 flag 互斥、自删并正常 `halt(false)`；拒绝非 `mcbot-persistence-*` 世界，
  seed 拒绝已有样本，verify 不经过原动作链（防背包被工装清空）。
  样本为主世界 `(37.5,90,-42.5)` 与下界 `(53.5,90,29.5)`，确定朝向；
  六个非空槽位含圆石 23、损耗 17 的铁镐、原木 11、钻石 3、金胸甲、副手火把 7，选中槽 4。
  比较所有空/非空槽位及 ItemStack components，不以“名册里有伙伴”或空背包冒充恢复验收。
- **红证**：01:22:59 seed 两名同伴 matches=true，01:23:00 正常保存三维度。
  停服后先复制完整世界到修复验证目录，再让旧流程重进原实验目录。
  01:24:08 两名样本均 `identity=true disk=true body=false position=false rotation=false inventory=false`，
  `VERIFY FAIL`。实际 UUID `.dat` 仍含正确维度、位置与物品，但在线身体都落到主世界出生点，
  背包为空/selected=0。保存链没有丢数据；缺的是入场加载步骤。
  本次正常停服会将错误身体再写回原实验目录，所以修复验证必须使用提前保留的样本副本。
- **根因/API 实证**：javap 官方映射字节码确认 1.21.11 `PlayerList.placeNewPlayer`
  只用传入身体的 level/position 注册入场，没有读档调用；原版在 PrepareSpawnTask 中
  用 `loadPlayerData(NameAndId)` 与 SavedPosition codec 选维度，Ready.spawn 再加载身体后进场。
  原 SummonService 直接 new + place，错误地假定 place 会读档。
  `PlayerList.remove` 会先 `save(player)` 再移除；本轮不更改原版保存出口。
- **修复**：召唤/重进共享入场前加载，使用原版 loadPlayerData（保留原版数据升级/回退），
  TagValueInput + ServerPlayer.load 恢复背包/身体字段，SavedPosition 选择实际维度，
  入场前 snapTo 确定位置/朝向；缺失/不可用维度回退主世界出生点。
  重进后的安全检查改用身体实际维度，不再总检查主世界。
  显式召唤仍到既有主人附近/主世界出生点，读取旧背包但不沿用旧位置；
  生存/无敌设置仍覆盖存档值，不新增私有身体存档或自动迁移用户数据。
- **绿证**：使用旧流程正常保存、未经重发物品的同一份样本副本，
  01:27:56 与 01:29:15 两个独立新进程中，两名同伴的
  identity/disk/body/position/rotation/inventory 均 true。主世界/下界、朝向、耐久与 selected=4
  全匹配；每次随后遣散再召唤，sameUuid/freshSpawn/inventory/matches 均 true，
  01:27:57 / 01:29:16 两次 `VERIFY PASS`，均正常保存退出，无 `(0,0,0)` 自愈掩盖恢复。
  复制的 level.dat 保留旧世界显示名，日志中的 ServerLevel 名称不等于其新存档目录名。
- **服务端回归**：另建动作验收世界，01:31:35–01:32:12 跑完整既有 SelfTest；
  `[m5a]` 四项 true；`[m3]` 白名单/owner/seq=42/速率链仍成立；
  `[m4]` 放置/真挖/移动/存箱成功；`[m4b]` busy=true/cancel=true/空槽=false，wait 正常结束；
  `[m8]` A NEED_CONFIRM 清单 2 格且内联/B 到达且打通/C 箱子不动/D 干净 NO_PATH；
  `[r2d]` 分类/坐标/尺寸/朝向通过；`[r2c]` 策略及五契约断言通过；
  `[f0-world]` 当前世界装配、遣散取消、再召唤、停服槽清空全 true。
  `[m9]` A1=true/A2=false/A3旧家自清=false、新家=true，保持已登记历史红项，不宣称全链全绿。
- **构建**：工装阶段强制 build 27s、修复后强制 build 28s，均 20/20 任务执行；
  XML 实核 **283/283**（agent-core 190 + 根工程 66 + clientTest 27），
  failures/errors/skipped 均 0；新跨进程场景属于 SelfTest，不加算到 JUnit 数量。
  最终 `build/libs/mcbot-0.1.0.jar` SHA256：
  `f18f491c319cddc044f126854bcded93850fd52e26c8d400ea48dc85d3fb88ab`；
  未更新 dist 或用户实例。仅既有过时 API/Gradle 10 警告。
  文档同步后再强制全量构建 25s / 20/20 任务执行，01:36:20 实核 XML 仍 283/283、
  JAR 哈希一致，list-java 仍无 Java 进程。
- **证据/收尾**：`run/persistence-evidence/` 保留 red-seed.log、red-verify.log、
  fixed-verify-1.log、fixed-verify-2.log、body-regression.log（本地工装产物，不入仓）；
  前两次恢复日志 result 分别在 82/85 与 77/80 行，红证在 79/82 行。
  所有五次服务进程/Gradle 命令均已结束，01:32:40 list-java 无 Java 进程。
  ARCHITECTURE §7 与 DEVELOPMENT §3.1 已同步新契约和复跑方式。
- **验收边界**：本项服务端自动化可以关账，单人客户端正常保存重进仍待换包后实测；
  未验证坐骑、在途末影珍珠、坏落点/缺失维度分支或活动任务保存清理，不将其计为已通过。
  修复不会自动找回旧包已覆盖的物品；没有对用户 `.dat` / `.dat_old` 做修复操作。
  后续仍按 F0：先单人恢复与真实动作，再 ACCEPT/PARK 取消、反问/重载/重连及连接器。
  已有流式统计/执行中 wait 取消真机证据保留；上游流内错误根因与 `[m9] A3` 未销账。

### F0 第十二轮：执行中 wait 取消补测（2026-10-10 01:11，Asia/Shanghai）

- 来源为同一测试实例新进程的 `logs/latest.log`，快照 197 行，
  观察窗口 01:01:48 启动至 01:08:07 正常保存停服；上一进程已轮转为
  `2026-10-09-1.log.gz`，其中 23:58:07–23:58:08 的保存退出链完整，不混用两进程 request 编号。
  实例 JAR 哈希仍为 ba013eed…85b75cb，与统计修复包一致。
- **第一次命中执行中 wait**（latest.log:151–163）：01:03:08 投令，
  request=1 于 01:03:18 正常工具轮落地（ready=1/early=1，first_dispatch=10045ms）。
  01:03:34 本地取消产生 CANCELLED 合成工具回执与“先停手”回复；
  服务端记录 `tool wait 15748ms`，发送取消工具回执及 cancel_ack，
  客户端显示“已叫停 steve 手头的活”。这是实际占用调度槽的 wait 被中止，
  与上一轮 wait 已结束后的“手头没有任务”不同。
- **第二次命中执行中 wait**（latest.log:164–176）：01:06:09 新指令，
  request=2 于 01:06:15 工具轮落地，01:06:16 取消；
  服务端 `tool wait 749ms`，再次确认“已叫停”。两次均未收到“等完了”的成功回执。
  补测按维护者的 60 秒 wait 清单进行，现有安全日志不输出 arguments，
  不从计数反推实际 seconds；执行中取消的正证来自服务端槽命中与取消回执。
- **不续跑及新任务正证**：首次取消到下一条新指令间隔约 155 秒，
  第二次取消到纯文本新指令间隔约 103 秒，均无旧 wait 成功回复、续发模型请求或工具。
  01:07:59 新指令 request=3，01:08:02 正常回复“新任务正常。”，
  ttfb/ttft=2748ms、chunks=7、deltas=4、ready/early=0。
  新请求工具配对/重复/参数结构异常计数均 0，合成取消回执没有破坏后续出站配对。
- 本进程共 3 次模型 HTTP 尝试、3 个响应与 3 条流式统计；
  均 attempt=1、HTTP 200/SSE、errors/malformed/parse_errors=0。
  这不销除上一进程 request=9 的 UPSTREAM 样本或宣称模型历史间歇错误已解决。
- **迟到日志的观测偏差**（latest.log:162/175）：两条 WARN 写“已被超时收走”，
  feedback 实为 CANCELLED。源码 AgentRunner.clearTaskWaits 在取消时已 drain 等待票据，
  随后的服务端取消回执进入 takeTool 为空的迟到分支，按设计丢弃；
  当前日志文案将超时/取消/会话清理混称为超时，且注释把 late_results 增加直接归因于超时帽，
  该归因不成立。记录为诊断文案债务，不按这两条 WARN 判 wait 超时或取消失败；
  本轮不修改 Java、事件契约或 late_results 含义。
- **恢复线索**（latest.log:124–127）：重进时 steve 初始 `(0,0,0)`，
  日志报告该落点不可站立并移到 `(0,-60,0)`，随后“随服务器重进”。
  上一进程 status 曾报 `(9,-60,9)`；这不能视为精确位置恢复通过。
  未读取存档/NBT、未核对伙伴 UUID 或做退出前非空背包对照，
  不单凭该警告宣称存档丢失，也不直接修改持久化逻辑；下一专项优先建立受控证据。
- 01:08:06–01:08:07 桥关闭、玩家退出、服务器停服、Saving players/worlds 与
  All dimensions are saved 完整。只证明本次正常收尾，不替代活动任务断线/重载清理或数据恢复验收。
- 本轮仅只读日志/JAR/相关源码，并更新 STATUS 与路线图；
  未读取模型配置/令牌、未请求模型、未改存档或安装包、未构建。
  自动化 283/283 仍引用第十轮，不冒充本轮重跑。执行中 wait 取消项可以关账，
  不扩展为 move_to/break_block 的 ACCEPT/PARK 取消、恢复、反问或连接器全链验收。

### F0 第十一轮：新统计包真机日志复核（2026-10-09 23:57，Asia/Shanghai）

- 来源为维护者提供的测试实例 `logs/latest.log`；本轮复核快照 264 行，
  观察窗口为 23:43:00 启动至 23:54:22 最后回复。当前日志已核对为 2026-10-09，
  旧压缩日志的文件名/内容不混入本轮验收。仅只读日志与 JAR，不读取模型配置、桥令牌或存档。
- 实例为 MC 1.21.11 / Fabric Loader 0.19.5 / Fabric API 0.141.6+1.21.11 / Java 21；
  实例 mcbot JAR 与本轮 build/libs 的 SHA256 完全一致（ba013eed…85b75cb），
  排除本次只安装了历史同版本号旧包。23:48:25 保存配置后开始本轮模型测试。
- 共观察 12 次 HTTP 尝试：独立连接测试 request=1/2 两轮成功；
  真实大脑 request=3–12 共 10 轮，9 轮正常、1 轮返回流内错误；
  9 条当前任务流式统计，被取消的 request=9 没有发布旧统计。
  全部请求 attempt=1，无 `/v1` 换道；出站工具配对/重复/参数结构异常计数全零，
  响应 malformed/parse_errors 全零。HTTP 200 不将错误轮伪装成成功。
- **纯文本正证**（latest.log:174–177，23:48:58，request=3）：
  回复“测试完成。”，ttfb=11921ms、ttft=12217ms、chunks=6、deltas=3，
  ready/early=0、first_tool/first_dispatch=-1、stream=12217ms；
  response.data=7 比 chunks 多终止行。文本增量数不等于所有 delta 形状帧数，空/控制增量不计文本。
- **真实工具与最终汇报正证**（latest.log:186–208，23:49:22–23:49:56）：
  request=4 执行 status，request=5 执行 scan_area，request=6 正常文字汇报；
  两个工具轮均 ready=1/early=1，工具回执成功。request=6 的
  ttfb=7080ms、ttft=7081ms、chunks=78、deltas=75、stream=10873ms，
  文本轮工具字段为 -1 符合“未发生”口径，不是统计未接通。
- **真实派发与传输结束的区别**：6 个工具轮中，request=8 的 first_dispatch=9449ms，
  stream=10239ms，执行器调用提前约 790ms；另 5 个工具轮 first_dispatch 晚于 stream 1–9ms。
  因此不能仅凭 early=1 声称每轮都在网络结束前执行或获得显著端到端加速。
  9 个统计样本的 ttfb 在 7080–12641ms，主要等待发生在响应头到达前；
  现有日志不能进一步分离网络、代理排队与后端生成原因，不以此擅自修改模型/端点。
- **取消的实际范围**（latest.log:213–240）：wait 于 23:50:24 后起跑，
  23:50:56 成功回执“这 30 秒等完了”（服务端计时 32255ms）；随后 status 于 23:51:06 已完成，
  request=9 在等模型回复。23:52:13 用户取消时，服务端回“手头没有进行中的任务”。
  本样本证明模型等待阶段逻辑取消与身体叫停请求已发生，**不证明执行中的 wait/移动被中断**。
  原测试清单将 wait 的日志视为开始/受理提示不准确：当前 wait 走完成回执，
  服务端工具计时日志在 future 完成时输出。补测不能等“等完了”再取消。
- **迟到流隔离与新任务正证**（latest.log:237–264）：取消后，23:53:41 新指令产生 request=10，
  23:53:54 旧 request=9 才返回；没有旧 onStreamStats、旧回复或后续工具派发。
  新任务 request=10/11 的 status/scan_area 正常，request=12 于 23:54:22 正常汇报。
  这只确认本观察窗口的旧回调隔离，不当作断线/重载或跨世界验收。
- **新增自然失败样本**（latest.log:243）：request=9 为 HTTP 200/SSE，
  data=43、errors=1、finish=ABSENT、empty=false，
  error_category=UPSTREAM、error_source=TYPE、error_type=UPSTREAM、error_status=0。
  这是服务返回的结构化 type 被本地归类，不证明具体提供商、网络或中转内部根因。
  请求工具配对无结构异常也不能证明正文语义无误；已取消的任务不因部分文本/迟到错误恢复。
  不重发旧任务，不循环付费探测。历史间歇错误由“无新样本”变为已有上游类别样本，根因仍未销账。
- 启动阶段的 Mojang 用户属性/Realms 鉴权 401 与上述模型 HTTP 200/SSE 不是同一链路，
  不将它们作为本轮模型失败原因。本轮未修改 Java/资源、未构建、未替换包、未发起任何模型请求。
  更新仅记录验收事实与路线图；11 例新增回归及 283/283 自动化证据仍取上一轮，不冒充本轮重跑。
- 流式统计接线的新包真机项可以关账；F0 整体不关账。
  仍需执行中的长活取消、身体位置/非空背包受控保存重进、真实动作、反问回答、
  配置重载/重连及连接器联合验收；`[m9] A3` 保持另卡未解。

### F0 第十轮：流式时延与计数接线修复（2026-10-09 23:14，Asia/Shanghai）

- 范围只含客户端/agent-core 流式统计与必要回归，不改游戏动作、工具账本/PARK、
  桥 v1.0 的字段或任务语义，不改宿主、只读参考、模型配置、凭据与存档。
  当前开发副本没有 `.git`；下方历史提交/远端/实例哈希保留为历史证据，不作本副本当前状态证明。
- 修复三个已确认的断点：`ttfb` 原在整轮完成后才记录，现移到 HTTP BodyHandler 响应头回调；
  文本/工具就绪原未更新传输层计时器，现每个已识别事件同步更新；
  StepReactor 原读取未被填充的本地计时器，现接收最终尝试的不可变 `TurnTimings.Snapshot`。
- 新增可选 `TurnSink.onTimings`，CallbackChatEngine 与已有流式/整轮回调共用有序队列。
  成功与失败均在完成回调前交付快照；取消/关闭后的旧流不写入当前步骤或发布旧统计。
  保留既有一次 `/v1` 换道，每次重新起表，仅交付最终尝试，不新增失败/游戏任务自动重试。
  counters/timings/流式统计监听器异常按观测失败处理，不改变请求或任务终态。
- 明确口径：`chunks` 为非终止 data 行，不含心跳和 `[DONE]`，而响应诊断 `data` 包含终止行；
  `ready` 为传输层工具就绪数，`early` 为整轮处理前经就绪回调实际调用执行器的次数，
  仍只限 index 0，既不等于服务端受理，也不保证网络传输仍未结束。
  新增 `first_dispatch`（请求到执行器调用，包含客户端排队）与 `stream`（请求到传输完成）。
  首个就绪工具未必是 index 0，不能把 first_dispatch - first_tool 一概解释成纯队列耗时。
  失败轮 cache_waste=-1，不沿用上轮读数；未发生的时间仍为 -1。
- 本轮新增 11 例，覆盖确定时钟/不可变快照、响应头先于延迟正文、文本和闭合工具计数、
  `/v1` 尝试起点隔离、延迟客户端队列、多个就绪但只派发一次、取消/关闭旧回调、
  失败轮部分统计、空 sink 与观测异常隔离；强化既有 SSE 失败/重试/不累积路径断言。
  原有早派发只执行一次、后序串行记账、PARK、生命周期、桥/客户端回归一并通过。
- 环境证据：Gradle 9.5.1，构建 daemon 使用用户级配置中的 JDK 21。
  初次普通/堆栈/离线测试均阻塞在 Loom 的 Minecraft 版本清单下载（ConnectException），
  未进入测试阶段；获准仅在本次命令用 `-Dhttp.proxyHost= -Dhttps.proxyHost=` 直连后恢复。
  仓内/用户级代理及 Java 配置未修改，不为换机写入新绝对路径。
- `:agent-core:test` 首次成功 **2m 6s / 4 任务**。
  随后强制全量 `build --rerun-tasks --console=plain --no-daemon`（同上述单次直连参数）
  **BUILD SUCCESSFUL in 42s / 20 任务全部执行**。
  XML **283/283**（agent-core 190 + 根工程 66 + clientTest 27），
  failures/errors/skipped 全 0，比历史 272 增加 11 项。
  23:17 再次强制全量复核 **29s / 20 任务全部执行**，最新 XML 仍为 283/283；
  按 GBK 解码输出后仅见既有过时 API 与 Gradle 10 兼容性警告。
- 已产出 `build/libs/mcbot-0.1.0.jar`，未复制到 dist 或替换游戏实例。
  两次构建 SHA256 一致：
  `ba013eede3bd8592ea0e562b2224ad07864f41def3e3f814e8e69475885b75cb`。
  核验内嵌 `META-INF/jars/agent-core-0.1.0.jar` 包含新 Snapshot、StreamStats 与 CallbackChatEngine；
  23:18 获准运行 `tools/list-java.ps1`，未发现 Java 进程；所改 Java 文件无冲突标记或行尾空白。
  本轮未启动 MC/连接器、未运行服务端 SelfTest、未调用真实模型。
  本修复不涉及新的 MC API 或服务端业务，不以自动化结果代替真机动作/取消/反问验收。
- 后续顺序保持 F0：先在真实游戏日志复核新统计，再用受控保存/重启验证身体位置与背包恢复，
  取得证据后才决定是否修持久化；真实动作、长活取消、反问回答、配置重载/重连与连接器仍待验。
  模型历史间歇流内 error 根因与 `[m9] A3` 均未销账。

### 协作文档整理（2026-10-09 17:22，Asia/Shanghai）

- 将当前进度、路线图、桥接契约及上手入口调整为面向维护者和接入开发者的表述，
  按 mcbot、连接器、宿主及玩家角色说明职责，不再以会话中的人称指代协作模块。
  接入依据、兼容变更流程和联合验收要求保持一致；内部 C2S/S2C 说明明确标为实现参考。
- 共修改 9 份 Markdown 文档；同步补充提交 `7e10502` 已合并 main 的结果，
  测试包说明更新到 272 项自动化及既有 12 次真实模型请求的证据边界。
  历史日志原文、协议字段、Schema、示例资源、源代码与未完成验收项保持不变。
- 强制全量 `build --rerun-tasks --console=plain --no-daemon`：
  BUILD SUCCESSFUL in 21s，20/20 任务执行；XML 272/272，
  failures/errors/skipped 均为 0。仅既有过时 API 与 Gradle 10 兼容性警告。
  未启动游戏或连接器、未运行 SelfTest、未调用真实模型、未替换测试实例 JAR 或修改配置/存档。

### F0 第九轮：五部分契约提交前核对（2026-10-09 17:00，Asia/Shanghai）

- 经维护者确认，在五部分基础契约无阻塞后提交推送。BRIDGE §0 增加“基础能力 / 字段与结果 /
  事件规则 / 生命周期 / 契约测试”总表，明确各部分保证与不保证什么。
  各模块可独立迭代内部实现，但必须保持已固定的可观察行为；破坏性变更须新版本并协调接入方迁移。
- 核对发现 Schema 的 `\S` 与 Java String.isBlank 在 Unicode 空白判定上不完全一致；
  Schema 改显式 Unicode 范围，不改实际输入处理。新增回归逐个对照 65536 个 BMP 字符，
  并验全角空格拒绝/NBSP 接受，任务/问答的非空字符串定义保持同源；未扩展游戏/模型能力。
- 修订前全量强制 build 21s 通过；修订后最终强制 build **23s / 20 个任务**，
  XML **272/272**（agent-core 179 + 根工程 66 + clientTest 27），
  failures/errors/skipped 全 0。新增 1 例，相对上一诊断包累计新增 20 例契约/HTTP 回归。
  没有启动 MC/连接器、运行服务端 SelfTest 或请求真实模型，真机待验项保持不变。
- 16:59:59 核验 build/libs 与 dist 的 SHA256 均为
  `6bf2d5c38a0eba86855c817d426c49b3c3240c5eef4d7780b0f400d93dc9b99d`；
  旧 dist 保留在 `dist/mcbot-0.1.0-before-contract-audit.jar`（9bcfa188…）。
  游戏实例仍未替换（50c0675e…），配置、凭据与存档未改。
- 58 个变更/新文件提交前实际凭据/token、禁入路径、新增绝对路径扫描全零，
  diff --check 通过，核验时无 Java/javaw。提交范围包含本次契约依赖的既有 F0
  生命周期、面板/世界隔离与模型诊断改动，不包含测试 JAR、run/logs、配置或凭据。
- 远端核对 main 仍为 ac1dc10，现有本地分支 fix/task-lifecycle；
  `push --dry-run` 成功，随后 ls-remote 确认仍仅 main，**没有实际推送或新建远端分支**。
  此前本机 user.name/user.email 均未配置，因此未借用历史作者或伪造提交。
- 17:08 维护者授权使用本地凭据中的身份提交并推送此前更新。通过既有 Git 凭据
  向 GitHub /user 核验账号为 qaqms，采用该账号数字 ID 对应的 GitHub noreply 邮箱，
  仅为本仓设置提交身份；凭据只在内存使用，不输出、不写仓库或远端 URL。
  复核 XML 仍为 272/272、两份 JAR 哈希一致；发布目标为 fix/task-lifecycle，
  不直接改 main。实际发布以 Git 提交记录及远端同名分支 HEAD 核验为准，不以 dry-run 为证据。
- 后续发布结果：提交 `7e10502` 已推送至 fix/task-lifecycle，并经维护者确认快进合并至 main；
  本地与远端 main HEAD 核验一致。上述 dry-run 与功能分支发布记录为合并前的历史过程。

### F0 第八轮：固定连接器与 MC agent 的任务级接口（2026-10-09，Asia/Shanghai）

**范围与接入约定**：
- 本轮目标为固定连接器与 MC agent 之间的任务级接口；仅推进 F0 对外契约，
  不固定 agent 内部实现、不扩展聊天/游戏技能，不改宿主、只读参考、模型配置或存档。
- `docs/BRIDGE.md` 为唯一权威 v1.0 规格，`dist/README-BRIDGE.md` 为上手索引。
  固定既有 REST 路径、五个 MCP 工具名、任务窗口、终态、取消、问答及重连边界，
  标明本轮类型校验/错误返回的兼容收口变更。文档 §6 含连接器离线验证与端到端验收清单。
- 新机器 Schema 与示例位于
  `agent-core/src/main/resources/com/neko/mcbot/agentcore/bridge/`：
  `bridge-v1.schema.json` / `bridge-v1.examples.json`，随模块打包；
  BridgeContract 直接给 MCP tools/list 提供同一份输入定义，避免文档/发现两份定义漂移。

**实现对齐**：
- status 与每个 SSE data 增加 contract_version=1.0/session_id；同一桥稳定，重建桥换 UUID。
  SSE id 只在该桥内递增、重建从 1 起，连接器按会话清零游标，旧任务不能自动重投。
  配置重载不会重建桥，但旧任务/问题终止；没有历史任务查询/幂等提交，不用计数猜旧任务完成。
- REST/MCP 共用严格非空字符串、整数与非负 64 位 task_id 校验；
  wait_s 缺省 8/夹到 0..120 保留，未知字段忽略，非法参数不调用 backend。
  JSON 严格解析；RPC 信封/工具错误分层，MCP 过期问题报 isError=true。
- REST 安全 error_code 与 MCP JSON 错误统一，不回显异常/输入原文；
  65536 字节请求体超限明确 413，不再因 IOException 直接无响应断开。
- SSE 补发与订阅/发布在同一锁内排序，避免注册窗口重叠及并发发布顺序错乱。
  HttpServer 可注入替身 backend 在本地随机回环端口测试，生产仍只绑既有 57121。
- 保留 fragments 的真实兼容行为：本任务及公共事件所有非空 text 都会收集，
  不限 progress；结构化处理依赖 SSE。ask 是同步等待任务结果的兼容入口，
  135s 超时只释放等待项、不取消任务；取消回执不是服务器物理停机确认。
- 明确外部 SSE progress 已有工具回执/受理/护栏生产者；
  内部 job_event.phase=progress 只有预留接收，无服务器发送/限速实现，仍后置。

**验证与产物（16:43–16:45 核验）**：
- 首轮定向 `:agent-core:test clientTest` BUILD SUCCESSFUL in 30s；
  最终 `build --rerun-tasks --console=plain --no-daemon` BUILD SUCCESSFUL in 22s，
  20/20 任务执行。XML **271/271**（agent-core 178 + 根工程 66 + clientTest 27），
  failures/errors/skipped 全 0；相对上一包新增 **19** 例（契约 13 + HTTP 6）。
- 覆盖 MCP discovery 与 Schema 相等、示例 REST/MCP 返回一致、metadata/会话隔离、
  早到事件/窗口/公共片段、类型/越界/坏 JSON/RPC 校验、安全异常、ask 超时不取消/不重投、
  真 HTTP 鉴权/UTF-8/65536 字节边界、SSE 补发+实时/并发有序/200 条淘汰。
  示例/Schema 均可解析且发现输入同源；未引入通用 JSON Schema validator，
  输出行为由具体字段与示例回归断言覆盖，不宣称所有 Schema 分支都经第三方验证器验证。
- 16:45:10 更新 build/libs 与 dist，SHA256 一致：
  `9bcfa188608b5165688ee67d948fda2fe326db152e186c757738ea2c4dd5a8bd`。
  旧 dist 备份 `dist/mcbot-0.1.0-before-bridge-v1.jar`，哈希
  `50c0675eb193a3502079244c488caf4fff509dc34b9a25a81969078674da6b3b`。
  内嵌 agent-core 与模块产物哈希一致，BridgeContract 与两个 JSON 资源均确认在包中。
- **未替换游戏实例 mods 包**；仍是上一服务错误诊断包（50c067…）。
  本轮未启动 MC/连接器、未请求真实模型、未改动凭据配置/存档；
  本地测试不读取真实 token/client.json。收尾检查凭据扫描只在内存读已有值，不输出/复制。
- 58 个变更/新文件的实际 key/token 扫描零命中，新增绝对路径零命中，
  git diff --check 通过；核验时无 Java/javaw。未提交/推送，既有未提交改动保留。
  src/main 本轮零新增修改，未跑服务端 SelfTest；后续动服务端仍须按原纪律回归。

**尚未验收**：
- 固定外部契约不代表 F0 完成。连接器实现先按 §6 使用替身验证，再进行联合验收，覆盖真实
  task/progress/question/answer/done/cancel 与配置重载/退世界新会话。
- 新 v1.0 包尚未真机运行；模型间歇错误、流式时延接线、身体位置/背包恢复、
  一个真实动作/长活取消等保持原待验证项，不在本轮混入修复。

### F0 第七轮：新诊断包真机日志复核（2026-10-09，Asia/Shanghai）

**范围与安装核对**：
- latest.log 会话 16:05:24–16:07:36，33295 字节；读取时先在内存替换实际 API key/token，
  不把凭据或远端原始错误体写入仓库。
- 已安装 JAR 与 dist SHA256 都为
  `50c0675eb193a3502079244c488caf4fff509dc34b9a25a81969078674da6b3b`。
  本轮仅复核日志并更新记录，不改代码、安装包、配置、凭据或存档，不调用远端或重跑构建。

**模型与游戏工具真实结果**：
- 两次独立连接测试：request 1/2（16:05:58–16:06:05）及 7/8（16:07:01–07），
  每次工具轮 TOOL_CALLS → 文字轮 STOP，四次 HTTP 均成功。
- 两张既有存档，同一客户端进程先 `(3)` 再 `(2)`（由保存日志及存档目录时间核对），
  不是同一存档退出重进，也不是两张未召唤的新世界。各自恢复在册 steve，不据此判跨世界误召唤。
- 第一存档：request 3/4（16:06:20–32）查状态闭环，request 5/6（16:06:32–41）扫描闭环。
  第二存档：request 9/10（16:07:10–21）查状态闭环，request 11/12（16:07:21–29）扫描闭环。
  共 **四个实际任务、八次任务 HTTP**，真实工具各执行一次，均有回执与最终汇报。
  本次实际是每张存档状态/扫描分开投递，未把它写成“两次相同的状态+扫描合并任务”。
- 总共 **12 个请求及 12 条配对响应**；全部 HTTP 200、SSE、errors=0、DONE=true、
  empty=false、malformed=0、parse_errors=0、error_body_truncated=false、error_category=NONE。
  六个工具轮和六个文字轮；SSE tools 帧数不等于游戏工具调用数量。
- 全部请求的 missing_results/orphan_results/duplicate_calls/duplicate_results/
  name_mismatches/invalid_calls/invalid_arguments/interrupted_groups 均 0。
  两组游戏请求均使用 9 个工具定义、definition_bytes=3213、system_chars=333；
  messages 从 2→4→6→8，request bytes 分别 4262→4620→4822→5353/5345。
  最后一轮字节差不能在不存正文的前提下精确归因；未发现结构或流式选项差异。
- stream=TRUE/include_usage=TRUE、tool_choice/parallel_tool_calls=ABSENT 的当前组合成功；
  纯工具轮 null_assistant=1/2 也通过。不能把这些字段或小规模历史增长直接当故障原因。
  本轮没有失败请求，无法进行成功/失败因果对照，更不能反推上一包三次错误的具体类别。

**保存与剩余问题**：
- 16:06:53 第一存档、16:07:35 第二存档所有维度均保存完成；两次桥按断线关闭，
  16:07:36 正常 Stopping。未见新 mcbot 异常栈/卡保存；无新崩溃报告，
  最近仍为 14:21:34 旧报告；复核时无 Java/javaw 进程。
- Mojang 账号 401、Realms JWT、PerfOS 与 Java 25/LWJGL JNI 警告仍有，不能当模型失败证据。
- `[brain] llm stream` 仍为 ttfb/ttft/first_tool=-1、chunks/deltas/early=0，
  与请求 3/9 的游戏工具在模型整轮完成前已执行及真实 SSE 计数矛盾。
  时延统计接线仍待修，不能据这些占位数断言未流式/未早派发或测算收益。
- 两张存档的伙伴均先 0,0,0，再 SafeSpawn 移到 0,-60,0；本轮背包本来为空、
  无伙伴移动样本。位置/背包持久化没有获得有效对照，不宣称恢复通过，也不单凭日志判数据丢失。

**结论与下一步**：
- 新摘要的真机接线及本轮短历史只读闭环通过。历史间歇流内错误保留观察，
  不为取得失败样本而盲目重复付费请求；后续自然出现错误时按 request 配对安全分类。
- F0 尚未关账。后续优先补流式统计接线和身体数据恢复验证，再验真实移动/长活取消/
  反问/重载/重连及连接器；仍不扩展游戏技能。

### F0 第六轮：服务错误类别与失败请求差异诊断（2026-10-09，Asia/Shanghai）

**旧证据与本轮边界**：
- 开工复核的 latest.log 仍为 15:30:50–15:35:03、29207 字节，三次 HTTP 200 流内错误
  的事实不变；旧包没有错误字段分类与请求结构，无法事后还原具体原因。
- 本轮只改 agent-core 模型诊断、客户端两处日志接线及显式探针/文档，不改服务端、
  N.E.K.O、只读参考、凭据/配置/存档；不额外请求远端模型，不重放游戏动作，不开其他里程碑。

**本轮落地**：
- 新 ServiceErrorDiagnostics：error.code/type 的白名单映射，param 仅字段族，
  status 仅常见固定状态；类别区分鉴权、权限、限流、额度、模型、上下文、工具协议、
  参数、请求校验、策略、超时与上游/路由。只输出本地枚举/数值，未知为 UNKNOWN，
  多帧不一致为 MIXED，不把原始错误字符串或内容哈希带出。
- 有界 message 匹配仅标 MESSAGE_HINT，不当结构化证明。LlmFailure 的用户提示
  使用同一安全分类，明确未知/线索/不会自动重发，不再对所有流内错误笼统建议查额度。
- 非 200 / 200 JSON 错误支持跨行诊断；捕获上限 16384 字符，超限单独标记、
  不解析残片猜根因。非流式 JSON 回答仍不接受，未新增 Responses/Messages 等模型协议。
  网页 /v1 一次换道保持原规则；error 后的后续流帧不得再派发工具，
  此前已早派发的动作不能回滚，本轮不宣称可回滚。
- 新 RequestDiagnostics：统计实际序列化出站 body 的字节数、角色/字符规模、工具定义、
  未配对/孤儿/重复调用与回执、名称不符、调用结构/参数对象异常、被中断的工具组，
  以及末角色与 stream/include_usage/tool_choice/parallel_tool_calls。
  不记录密钥、URL、模型名、提示词、参数、回执正文、ID 或内容哈希，不改/拦截原请求。
- LlmClient 每次 HTTP 尝试分配本进程唯一 request 编号，request/response 同号，
  /v1 换道另号并标 attempt=2。任务与连接测试各自写游戏 SLF4J 日志；
  诊断 Consumer 抛异常不影响派发、结果或重试。ModelProbe 也使用同一安全摘要。

**验证与部署**：
- 定向本地 HTTP/分类/请求结构回归 build 17s 通过；15:55 全量
  `build --rerun-tasks --console=plain --no-daemon`：BUILD SUCCESSFUL in 26s，
  20/20 任务执行；XML **252/252**（agent-core 165 + 根工程 66 + clientTest 21），
  failures/errors/skipped 全 0，相对上一包新增 22 例。
- 新测试覆盖结构分类/未知隐私/弱线索/多帧冲突、实际 HTTP 请求与摘要逐字段一致、
  调用回执配对/历史变化、跨行 JSON 额度分流、超限标记、错误后工具禁止、
  错标 Content-Type 与 /v1 换道兼容、日志异常隔离及分类后的用户提示。
  所有 HTTP 测试仅用本地替身，不消耗真实 API 用量。
- 15:59:20 确认无 Java/javaw 后备份并安装，build/libs、dist、测试实例 mods 三份
  SHA256 一致：`50c0675eb193a3502079244c488caf4fff509dc34b9a25a81969078674da6b3b`。
  旧包备份 `dist/mcbot-0.1.0-before-service-error-diagnostics.jar`，
  SHA256 为 `46065b4d360dce8502e81c912b3c1c7c88c49a9e120d60c994553acbe281cf22`。
- JAR 内嵌 agent-core 与模块产物哈希一致，并确认两个新诊断类已打包；
  ModelProbe 无参数编译/运行只显示用法，不发请求。53 个变更/新文件真实凭据/token
  与新增绝对路径扫描零命中；无提交/推送。本轮 src/main 零新增改动，不重跑 SelfTest。

**下一步与未完成**：
- 主人用诊断包在同一配置/世界、已有伙伴时，从任务框连续两次发同一只读任务，
  各次等结束再发；错误出现后保留日志不连续刷。按 request 配对查 error_category/source/
  param 与历史/配对/流式选项差异，必要时再显式做独立连接测试或清会话对照。
- 本包尚未真机复现流内错误，不能宣布已定位根因或修好服务。
  UNKNOWN 要继续收证，不默认归服务宕机/额度/历史；结构相同不证明正文相同。
  流式时延接线、身体数据恢复与剩余 F0 真机项保持待验证，未在本轮混入修复。

### F0 第五轮：诊断包完整真机日志复核（2026-10-09，Asia/Shanghai）

**范围与产物**：
- 最新日志 15:30:50–15:35:03；安装包 SHA256 与上一节 model-diagnostics 包一致。
  本轮只复核日志与记录证据，不调用远端模型、不改代码/配置/存档或替换包，未重跑构建。
- 日志中两次完整保存（15:34:04 / 15:35:03），最终正常 Stopping；
  无新增崩溃报告，最近仍为 14:21:34 的旧报告，收尾无 Java/javaw 进程。

**已经通过的真实链路**：
- 世界 A 15:32:49 恢复在册 steve；15:32:50 companion_state 静默同步，
  status（2ms）与 scan_area（6ms）按钮正常。
- 同一客户端退出 A 后进全新 B：15:34:15 旧名册匹配 0，
  直到 15:34:19 主人手动召唤才出现 steve；B 的 status/scan 按钮正常。
  这是同进程 A→B 后当前 dispatcher/receiver 及新世界隔离的正证，
  不是完整 A→B→A/遣散/取消验收。
- 模型页两次独立连接测试：15:33:08/11 与 15:33:50/53，
  每次工具调用轮 TOOL_CALLS + 最终文字轮 STOP，四次 HTTP 都为 200 SSE、
  errors=0、empty=false、DONE=true；证实新诊断确实进入游戏 latest.log。
- 世界 B 三次实际任务闭环：15:34:29→38 模型 scan_area→真实回执→模型汇报；
  15:34:39→45 模型 status→真实回执→模型汇报；
  15:34:50→57 模型 scan_area→真实回执→模型汇报。
  这些不是只读按钮的假替代，日志同时有 brain 模型轮、tool_result、tool 成功记录与最终回答。
  三次共六次 HTTP，全部 DONE=true / errors=0 / empty=false / parse_errors=0。
  回答与超平坦读数一致：未扫描出可行动方块，不能据此说扫描能力缺失。

**仍有的真实问题，不销账**：
- 世界 A 的三个模型任务在 15:33:16、15:33:49、15:33:58 报失败，摘要一致：
  `http=200 format=SSE bytes=361 data=2 json=2 malformed=0 parse_errors=0
  delta=1 message=0 tools=0 reasoning=0 refusal=0 errors=1 done=false finish=ABSENT empty=true`。
  现已证实它们含远端 error 帧，不是无数据/坏 JSON 或客户端 provider 解析异常。
  HTTP 200 只是响应头成功，不代表模型任务成功。
- 15:33:48 那个任务中途确实成功调用 status 与 scan_area，下一轮 15:33:49 才报错；
  因此不能解释成“不会调用游戏工具”。其余两次在本任务工具派发前失败。
  全部六个任务共十次模型 HTTP：七次无错误响应、三次流内错误；不能把七次当七个成功任务。
- 日志不存错误原文，仍无法区分上游故障、请求约束、会话内容或配额等具体原因。
  新世界三次成功、旧世界三次失败是本轮关联，不证明“换世界修好模型”或存档本身致错；
  也不把 Mojang 账号 401/Realms 错误当模型鉴权失败。
- `[brain] llm stream` 的 ttfb/ttft/first_tool 均 -1、chunks/deltas 为 0，
  与真实 llm response 的 SSE 帧计数矛盾；旧时延统计接线失真，不能用于性能结论，
  不说明本次没收到流。cached=-1 仅表示后端未报告缓存读数。
- A 重进的身体先出现在 0,0,0，再被 SafeSpawn 挪到 0,-60,0；
  上次该世界召唤后的读数曾是 -4,-60,-5。安全落点自愈正常，但位置/背包持久化
  尚需单独对照存档实测，不能凭“重进成功”宣称完整恢复数据通过。

**结论与下一步**：
- 面板必要修复、静默同步、A→B 新世界隔离/操作/保存、真实模型只读闭环有正证；
  F0 整体仍未完成：流内错误具体原因、游戏动作、长活取消、反问、配置重载/重连与连接器未全验。
- 后续模型定位先对 error 做本地固定类别映射、对照失败请求差异，仍不输出原始错误体，
  不自动重复可能已执行的游戏动作。时延统计和身体数据恢复分别记录，不混作模型根因。

### F0 第四轮：静默真机通过与模型空响应排查（2026-10-09，Asia/Shanghai）

**最新真机（15:01:42–15:03:59，silent-join 包）**：
- 15:03:25 新超平坦旧名册匹配 0；15:03:26 companion_state 正常同步，
  没有再向聊天显示“当前世界尚未召唤伙伴”，本项获得真机正证。
- 15:03:44/45 无伙伴检查正确拒绝；15:03:47 手动召唤 steve 成功，
  15:03:48 status 成功（2ms），15:03:49 scan_area 成功（9ms）。
- 15:03:56 停服、15:03:57 所有维度保存、15:03:59 Stopping，无新 mcbot 异常栈。
  仍有此前原版账号 401/Realms、PerfOS 与 LWJGL 警告；不是新增模型失败证据。
  本轮日志没有模型任务或模型连接测试，不能据此判断模型已恢复。
- 已安装包 SHA256 与上一节一致；无新的崩溃报告（最近仍是 14:21:34 的旧报告）。
  本次仅一个世界，跨存档 A→B→A 的客户端验收仍未替代。

**独立真实模型探测（有限共 5 个 POST，不执行游戏动作）**：
- 原磁盘配置的 connection_probe 单请求：HTTP 200，SSE 6 条 data / 5 条 JSON，
  工具增量 2，finish=tool_calls，DONE=true，无异常/非法帧；约 3224ms。
- JDK 21 + 当前真实 LlmClient、PromptBuilder、人设/技能与 ClientToolDefs 九工具定义：
  “查看自己的状态，然后扫描附近并简报”首轮返回一个工具调用，没有游戏执行器参与。
- ModelConnectionTest 两请求完成工具调用→本地配对回执→文字回答，tool_round_trip=true；
  这能证明当前磁盘配置的工具协议可用，不等于游戏历史/线程/环境下实际任务已通过。
- 接入新摘要后 15:15 再测真实九工具首轮：
  `http=200 format=SSE bytes=1406 data=6 json=5 malformed=0 parse_errors=0 delta=4
  message=0 tools=2 reasoning=0 refusal=0 errors=0 done=true finish=TOOL_CALLS empty=false`；
  一个工具调用且 read_only=true。仅记录计数和分类，不输出正文/参数/密钥。
- 历史 14:43:47 空响应尚未复现，不能确认根因，更不能宣布已修复。
  独立探针没有重放游戏历史，也不模拟启动器环境变量或 Java 25 运行环境；
  本轮不修改凭据/配置、不自动重试游戏任务、不新增其他模型协议。

**补齐可诊断性及错误分流**：
- 旧 System.Logger 摘要不在先前游戏 latest.log 中；现 LlmClient 回调只含固定类型和数值的
  ResponseDiagnostics，AgentRunner/模型页分别接游戏 logger 的
  `[brain] llm response` / `[model-test] llm response`，每次 HTTP 尝试一条，含成功和失败。
  内容包括 HTTP、格式、字节/data/JSON/解析异常数、delta/message/tool/reasoning/refusal/error
  帧数、DONE 与固定 finish 枚举；未知远端 finish 映射 OTHER，原文不跨边界。
- HTTP 200 的 error 帧现在单独报 SERVICE_ERROR，不再混同空流或因部分文字伪报成功；
  同一错误帧的工具字段不产生早派发信号。已早派发的先前动作不能回滚，本卡未宣称可回滚。
  200 HTML 归网页问题；普通空流仍失败、不自动重试。未添加 JSON/Responses/Messages 兼容实现。
- 新 tools/ModelProbe.java 是显式无游戏执行器源文件探针，不接 build、不自动循环；
  只读取参数指定 client.json、输出分类/计数，具体调用与限制写入 DEVELOPMENT §4.1。
- 新增 9 例真实本地 HTTP 回归：成功摘要、HTTP 200 错误/部分文字、JSON message 格式、
  reasoning/refusal/未知结束原因隐私、非法载荷与 provider 解析计数分离、日志异常隔离、
  空流无自动重试、错误帧不早派发、/v1 换道每次尝试计数独立。

**最终验收与部署**：
- 15:18 全量 `build --rerun-tasks --console=plain --no-daemon`：
  BUILD SUCCESSFUL in 22s，20/20 任务执行；XML **230/230**
  （agent-core 143 + 根工程 66 + clientTest 21），failures/errors/skipped 全 0。
  仅既有过时 API/Gradle 10 警告；本轮服务端无新增改动，不重复启动 SelfTest。
- 15:19:39 无 Java/javaw 进程后备份并安装诊断包，build/libs、dist、测试实例 mods 三份
  SHA256 一致：`46065b4d360dce8502e81c912b3c1c7c88c49a9e120d60c994553acbe281cf22`。
  旧包 `dist/mcbot-0.1.0-before-model-diagnostics.jar` 的 SHA256 为
  `0972bd00de68d7ec0ade20eca67fa68d61c1a9f233a0263bd8981053fab13d73`。
  不改原版存档、其他 mod、宿主/只读参考或客户端凭据；未提交推送。
- JAR 内嵌 agent-core 与本轮模块产物 SHA256 一致，打包客户端含新的游戏日志回调；
  ModelProbe 无参数启动只输出用法、不发请求。49 个变更/新文件实际凭据/token 扫描零命中，
  新增行/新文件机器路径零命中，git diff --check 通过，全部命令完成后无 Java/javaw 进程。

**下一次真机重点**：模型页测试连接一次 → 保存/确认现有配置 → 已有伙伴的世界，
在任务输入框发“查看自己的状态，然后扫描附近并简报”（不是点击只读按钮），
等结果后正常退出；用两类 llm response 摘要与真实 tool_result 对照。
新摘要是否在真实游戏日志出现、实际任务闭环和历史空响应根因仍待确认，不销 F0 验收项。

### F0 第三轮日志复核与入世界静默提示（2026-10-09，Asia/Shanghai）

**真机日志事实（14:41:44–14:44:02，旧 world-fix 包）**：
- 14:42:13 全新超平坦迁移匹配 0 名伙伴，未出现自动恢复 steve；14:42:14 JOIN 状态同步
  被客户端通用分支当作聊天显示，主人要求去掉“当前世界尚未召唤伙伴”的自动提示。
- 14:43:22/28 未召唤时只读检查正常被 owner 闸拒绝；14:43:31 主人手动召唤 steve 成功。
  14:43:35 status 成功（1ms，位置 7,-60,-8）；14:43:38 scan_area 成功（5ms），
  超平坦当前扫描未找到可行动方块，主人在附近。
- 14:43:45 模型任务启动，14:43:47 返回 EMPTY_STREAM，未见模型派发游戏工具；
  不能宣称自定义模型任务已通过。当前日志缺少空响应格式诊断，响应结构与根因仍未确定。
  本轮不发送额外付费探测、不修改主人模型配置，也不自动重试游戏指令。
- 14:44:00 正常停服，14:44:02 三维度全部保存并 Stopping，未复现保存卡死。
  本次只进一个世界，不能替代同一客户端进程跨世界生命周期验收。
- 未见新增 mcbot 异常栈。账号 user-properties 401 与 Realms JWT 错误属于原版账号服务，
  不是模型凭据 401；另有 PerfOS 注册表及 LWJGL Unsupported JNI version 警告，
  日志使用 Java 25，不能仅凭这些警告推断本次模型失败原因。

**本轮改动与验收**：
- AgentRunner 明确处理 `companion_state`：更新结构化伙伴名及同伴页回执，继续发布
  task_id=0 的桥公共 state；不调用 say、不写聊天或 transcript。
  有伙伴时的自动恢复同步也静默；手动召唤/遣散、只读检查与其他操作反馈不变。
- 新 CompanionStateTest 两例：无伙伴同步仍更新面板/桥且无聊天记录；
  已恢复伙伴同步静默，随后空状态清除旧名。测试不启动 MC，不访问磁盘配置或真实模型。
- 14:56 全量强制重跑 `build --rerun-tasks --console=plain --no-daemon`：
  BUILD SUCCESSFUL in 22s，20/20 任务执行；XML **221/221**
  （agent-core 134 + 根工程 66 + clientTest 21），failures/errors/skipped 全 0。
  仅既有过时 API 与 Gradle 10 兼容性警告；本轮服务端代码零新增改动，不重复起 SelfTest。
- 14:56:50 确认无 Java/javaw 进程后，备份旧测试包至
  `dist/mcbot-0.1.0-before-silent-join.jar`，再替换 dist 与主人指定实例 mods 的 JAR；
  build/libs、dist、已安装包 SHA256 三份一致：
  `0972bd00de68d7ec0ade20eca67fa68d61c1a9f233a0263bd8981053fab13d73`。
  备份 SHA256 为 `ee36cad54a88451092981632f022566f3a2db7ac1b5fd978509af280b218d839`。
  未修改原版存档、客户端凭据或其他 mod；未提交推送。
- 47 个变更/新文件实际凭据与 token 扫描零命中，新增行/新文件机器路径扫描零命中；
  git diff --check 通过（仅既有 LF/CRLF 提醒），收尾无 Java/javaw 进程。

**下一步与仍待验收**：
- 静默提示的本轮新包尚未真机点击验收；进入已有/全新世界检查聊天无自动伙伴状态提示，
  G 同伴页仍显示正确状态，再测同进程 A→B→A、召唤/遣散及保存退出。
- 模型连接/实际工具往返仍是 F0 阻塞项；先收集安全的格式证据并解决空响应，
  不提前新增游戏技能，不把只读按钮成功等同于模型任务成功。

### F0 第二轮真机：世界隔离与生命周期（2026-10-09，Asia/Shanghai）

**主人反馈与日志事实**：
- 主人确认新版面板布局可用。13:53:07、13:53:24 两个新超平坦在没有召唤时，
  都先记录 steve 从 `(0,0,0)` 进场及“随服务器重进”；身体 playerdata 属世界，
  名册却取实例目录，因而跨存档错误恢复。改为 `server.getWorldPath(LevelResource.ROOT)` 下保存。
- 13:53:40 遣散有回执；14:02:20 普通世界 status 返回“没有在册的同伴”，随后模型
  14:02:23 正常汇报。当前普通世界没有伙伴，这不是模型鉴权失败。
- Fabric 5.1.6 本地源 JAR 明确说明 registerGlobalReceiver 重复注册返回 false 且不替换；
  旧代码在 SERVER_STARTED 注册并捕获该次 dispatcher，后续世界仍用首个世界对象。
  现在初始化时仅注册一次，每条消息取当前 dispatcher 并与 context.server() 比较身份。
- 14:03:03 / 14:10:11 扫描指令模型返回无有效内容，没有对应 scan_area 执行记录。
  当前配置下，一次无游戏动作的 connection_probe POST 实测 HTTP 200、text/event-stream、
  5 个 SSE 记录且有工具调用；原失败的响应内容未保存，根因仍不能仅凭旧日志确定。
  当前服务不是“必然不支持 Chat Completions”；也未冒称其所有请求均兼容。

**修复与边界**：
- 名册按存档隔离；旧实例名册只在新路径不存在且当前世界有匹配 UUID 的 playerdata 时导入，
  迁移结果（含空列表）落盘，旧文件不改。旧 bug 已在某个世界留下的伙伴可能迁移，
  此类既有伙伴需在该世界正常遣散；不会仅凭共享名册向真正新世界导入。
- 停服取消全部 scheduler 槽并清空 PathTask 计划缓存；遣散立即取消对应身体任务。
- JOIN 查询当前世界同伴，生命周期回执携带结构化 companion 名称；同伴页显示操作回执，
  未连服务端/非法名字本地明确提示，不再靠聊天字符串推断新服务端的同伴状态。
- 状态/扫描按钮直调白名单只读工具，不经过模型、不进入 agent task，不干扰活动模型任务。
  无伙伴仍由服务端 owner 闸拒绝；这是查看真实身体/附近，不是全世界透视。
- 模型协议仍仅 OpenAI 兼容 Chat Completions SSE；未实现 Responses/Anthropic Messages。
  空响应提示不再断言协议不支持，日志只增加本地格式分类/chunks/deltas，不记录原始响应或密钥。

**服务端实测（独立开发存档）**：
- 14:22:24–14:23:21 `runServer`，通过 autotest.flag + autotest-stop.flag 启动/正常收服，
  仅绑定回环，不用控制台 stdin。`[f0-world]` 当前存档名册/dispatcher/receiver 装配三 true。
- `[m5a]` 四 true；`[m4]` 真挖/移动/存箱成功；`[m4b]` busy=true、cancel=true、空槽=false，
  wait 正常完成；`[m8]` A 确认=true/清单2，B 到达及墙打通=true，C 箱未动=true，D 干净失败=true。
- `[r2d]` 六项 true、`[r2c]` 策略与契约通过；`[f0-world]` 遣散取消任务/再召唤/停服槽清空三 true。
  开发启动生成区及净带探测出现一次 Can't keep up，未出现 mod ERROR 或任务异常。
- 本次 seed=12345、view-distance=4 下 `[m9] A3` 为 true；这只是新增观察，不证明历史问题已解决，
  本卡未研究其原因，不销掉已知债务。
- 14:31:14–14:32:07 第二次启动同一开发存档，14:31:27 明确记录“同伴 steve 随服务器重进”；
  装配/生命周期及 m4b/m8/r2d/r2c 再次通过，正常停服。两次均无需 stdin，最后无遗留 Java 进程。

**最终构建与部署**：
- 强制重跑 `build --rerun-tasks --console=plain --no-daemon`：BUILD SUCCESSFUL in 20s，
  20/20 任务执行；XML **219/219**（agent-core 134 + 根工程 66 + clientTest 19），
  failures/errors/skipped 均 0。相对上一包新增名册隔离/迁移 8 例、错误提示 1 例。
- SHA256：`ee36cad54a88451092981632f022566f3a2db7ac1b5fd978509af280b218d839`；
  build/libs、dist、主人指定的测试实例 mods 三份一致。
  确认游戏进程已退出后替换 JAR，上一包保留为 `dist/mcbot-0.1.0-before-world-fix.jar`，
  SHA256 仍为 `7f30203476cbab581c3c9f8b76acfd60d7f97c89f1e7772837f4df83aa3589f9`。
  未修改主人配置/凭据/原版存档/Fabric API；开发服只使用仓库忽略的 run 目录。
- 46 个变更/新文件的真实凭据及 token 扫描零命中，新增行/新文件机器路径扫描零命中，
  git diff --check 通过（仅既有 LF/CRLF 提醒）；未提交推送。

**仍待验收**：
- 本卡服务端无头实测不等于客户端同进程切换世界、按钮点击及桥连接器真机通过。
  重测清单：全新 A 无伙伴 → 召唤/状态/扫描 → 退出进 B 无伙伴 → 召唤/遣散 →
  返回 A 恢复其伙伴 → 自定义模型任务。无须删除配置/原版玩家数据或密钥。
- 保留前两轮尚未提交的工作区改动；本轮只推进 F0，不新增游戏技能或修改宿主/只读参考。

### F0 退出卡住的崩溃补证（2026-10-09）

- 主人补充：此前卡在“保存游戏”，随后用任务管理器结束。指定实例仅发现
  `crash-2026-10-09_14.21.34-client.txt`，报告 Time=14:21:34、
  Description=Client shutdown、java.lang.Error: Watchdog；latest.log 仍止于 14:10:49。
- 不是只凭报告顶部的 GLFW 等待栈归因：完整 Thread Dump 中 Server thread 等待
  CompletableFuture.join，调用链是区块读取 → SafeSpawn.isStandable:33 →
  SafeSpawn.findNear:14 → SummonService.summon:62 →
  ServerToolDispatcher.handle:48 → McbotMod.lambda$onInitialize$0:93。
  说明退出前服务器已卡在召唤的区块读取，不能进入正常停服保存流程。
- javap 对比保留的旧包与当前安装包：旧 lambda$onInitialize$0 的参数含捕获的
  ServerToolDispatcher，当前包不捕获 dispatcher，而是 getstatic 当前对象并
  belongsTo(context.server()) 校验；结合 13:53/13:54 的连续换世界日志，
  支持旧 receiver 访问已关闭世界、区块等待无法完成的根因。
- 这份报告早于当前包 14:27:00 的构建时间，不能算新包回归。重新核对已安装 SHA256
  与本节第二轮最终产物一致，当前无 Java/javaw 进程；未再改代码或改用户存档。
  当前包已经包含对应根因修复，但单人客户端“换世界→召唤→保存退出”仍待真机确认。
- 强杀可能丢失未写盘进度；报告未给出存档损坏证据，也不据此保证所有数据完整。
  退出超时报告不等于用户操作造成了最初卡死；本次只补定位证据，不销其他验收债务。

### F0 真机阻塞项修复（2026-10-09，Asia/Shanghai）

**主人首测与定位证据**：
- 主人启动器实例 latest.log：11:34:54 保存完整长模型名，11:35:06 保存时尾部被截至 32 字符；
  11:39:30 / 11:40:15 模型调用失败，但旧回路只展示通用错误，无法区分凭据/模型/网络问题。
- 只读取客户端配置的非敏感元数据：当前密钥长度恰为 32；未输出或复制密钥。
  使用原配置向其既定服务 GET /models，**HTTP 401**。这能证明当前凭据不被接受，
  不能证明完整密钥下的模型名可用或工具调用兼容；也不把 Minecraft 账号的 401 当模型故障。
- javap 确认 EditBox 构造器 maxLength 默认 32，setValue 当场 substring 截断；
  旧面板先 setValue 再 setMaxLength(256)，所以重开面板后保存会破坏原有长模型名/密钥。
  新 clientTest 在实际 MC EditBox（非仿造控件）上复现这个顺序问题。
- 旧面板固定 y 游标配置区、无宽度限制的记录、标题与 token 共用顶行、底部输入与操作区无分隔；
  小 GUI 视口必然越界/重叠。配置字段列表还会在 init 重复累积。

**修复范围**：
- PanelFields 先设置长度上限再赋值；长 API 地址/模型名/密钥/任务输入完整保留。
  不自动修补旧配置，丢失的字符串只能由主人重新粘贴完整值。
- G 面板分任务/模型/同伴三页；按 GUI 像素宽高布局，操作区固定，表单与换行记录可滚动/拖动滚动条。
  仅完全进入视口的表单控件可交互；切页/resize 复用输入控件保留草稿，
  标签不再用空按钮，回车只在任务输入框聚焦时派发，工具回执/护栏加入记录。
- 密钥通过 addFormatter 默认星号显示，读屏消息也不包含原值；
  桥端点只显示在独立状态行，令牌改为模型页主动复制，不全串展示。
- LlmFailure 只展示本地产生的错误类别和 HTTP 状态，未知异常/远端原始错误体不回显；
  401/403/404/400/422/429/5xx 可区分，HTTP 200 无有效 SSE 内容不得伪报成功。
- 自定义地址支持根地址、/v1、完整 /chat/completions；拒绝带 URL 凭据/查询参数的地址，
  防止完整路径重复追加；/v1 网页换道后的失败只通知一次。
- 模型页测试连接使用当前草稿、独立 LlmClient，两轮流式 connection_probe 往返；
  不派发游戏动作、不自动保存，文字回答不能假装工具兼容。测试期间修改配置会提示重新测试。
  未配置/无效地址在客户端安全提示，不因 URI 异常导致进世界失败。

**验证与产物**：
- 完整强制重跑 build：**BUILD SUCCESSFUL in 20s，20/20 任务执行**；最终补密钥显示测试
  与面板回执后再 build：**BUILD SUCCESSFUL in 17s**。
- 最终 XML：**210/210**（agent-core 133 + 根工程 66 + 新 clientTest 11），
  failures/errors/skipped 均 0；相对上一 F0 包新增 25 例。
  GUI 布局覆盖 7 种宽度 × 7 种高度，检查边界/分区/操作按钮、表单可达性、滚动条两端；
  实际 EditBox 测长模型/密钥/任务、resize、掩码和读屏隐私。
- 最终包 SHA256：`7f30203476cbab581c3c9f8b76acfd60d7f97c89f1e7772837f4df83aa3589f9`。
  build/libs、dist 与主人明确指定的测试实例 mods 包一致；旧包备份为
  `dist/mcbot-0.1.0-before-panel-fix.jar`（不安装进 mods，按既有 ignore 不入仓）。
  只替换测试 JAR，未修改主人配置、凭据、存档或 Fabric API。
- 变更文件的真实凭据/token 与机器绝对路径扫描零命中；git diff --check 通过。

**仍待验收**：
- 没有运行新包的实际 MC 图形界面截图验收；布局与输入框测试不等于 GPU 渲染/点击真机通过。
- 当前原凭据 401，无法完成其真实模型工具往返；主人需重新填写完整密钥/完整模型名，
  测试连接通过后依次验证 status/scan/移动/取消/反问/重载/重连。已有 steve 无须重复召唤。
- 本轮 src/main 身体/服务端零改动，未运行 SelfTest；后续修改服务端仍须 [m4*]/[m8] 回归。
  N.E.K.O 宿主与只读参考未改，连接器未联调；[m9] A3 与其他既有债务不宣称解决。

### F0 基础任务闭环（2026-10-09 10:40，Asia/Shanghai）

**已修复的实问题**：
1. 投递 B 就改 current_task，导致仍在跑的 A 的事件被标成 B；改为由 onTaskStarted 更新，
   每个终态携带明确编号，结束后 current_task=0，状态另带 queued_tasks/pending_asks。
2. 桥将所有 state 当 done，PARK、回答确认、公共生命周期会虚假结束任务；现在只认严格
   匹配编号的 done，新增 completed/failed/cancelled/superseded，done=true 只表示结束。
3. REST 取消从整条路径提取数字，把 /v1 的 1 混入 task_id；改为只读取任务路径段。
   正数取消指定活动/排队项，0 取消全部，未知/已结束编号返回 false，不影响其他任务。
4. ask 用 FIFO 吃下一次任意回复，可能把 A 的作答发给问 B 的调用方；TaskReplies 按编号配对，
   投令前登记，超时释放应答等待项，失败/取消/顶替不作为成功作答返回。
5. 取消、配置重载、断线只丢引用/等边界，旧模型或工具 future 仍能回来执行；现在立即终止、
   补齐未完成 tool_call 的合成回执、关闭旧 loop、清理 pending 工具/job/问题/ask，代际隔离旧回调。
   客户端桥绑定当前 runner；S2C 处理捕获所属 runner，旧连接排队动作不能写进新会话。
6. 工具 future 在串行链外提前创建，实际上后续工具一起执行；改为链内延迟派发。
   仅 index 0 可流式早派发，后序就绪不能抢跑；同步/异步异常和空结果回配对 INTERNAL 回执。
7. 早派发的 job 在整轮模型完成前已结束，ledger 未建时结果会丢失；当前步暂存已认领 job 终态，
   建账时消费，避免永久 PARK。CallbackChatEngine 将模型结果与流式信号送到客户端同一主线程队列。
8. CLI 一次性闩锁使第二次输入不等待自己的回答；每轮独立等答，超时结束会话。
   模型/执行器错误不回显异常原文，避免端点凭据或请求详情进入聊天与桥。

**自动化证据**：
- 改桥之前 /v1/task 的三个新回归用例确实红（state 误终止、公共 done 误终止、缺少失败 status）。
  完整构建中的编号取消用例又抓到 v1 路径数字混入缺陷，修正后绿。
- 10:39 `./gradlew build --rerun-tasks --console=plain --no-daemon`：BUILD SUCCESSFUL in 18s，
  18/18 任务执行；XML 共 **185/185**（agent-core 119 + 根工程 66），0 failures/errors/skipped。
  本卡相对远端基线新增 40 例，覆盖基本回路、任务生命周期、应答配对、回调队列、桥终态与等待清理。
- 新建/恢复 Git 工作区时仅导入远端 .git 元数据，未覆盖本地源码。origin 为主人提供的仓库，
  基线 ac1dc10，当前工作分支 fix/task-lifecycle。本机缺少 Git user.name/user.email，未冒用身份提交或推送。
- 文档同步 `docs/BRIDGE.md` / `dist/README-BRIDGE.md` / `docs/ARCHITECTURE.md` / `docs/ROADMAP.md`。
  最终复跑 `./gradlew build` 仍绿（19s）；构建包已复制至 `dist/mcbot-0.1.0.jar`，该产物按既有 gitignore 不进版本控制。
  包 SHA256 = `89e5cc71d3a87ed9551bd674fe635d9d86618a8e7c4e9d24785f9b0d709ee758`，
  build/libs 与 dist 相同，META-INF/jars/agent-core-0.1.0.jar 与模块产物 SHA256 相同。
  `git diff --check` 通过；改动文件凭据模式与机器绝对路径检查零发现。

**尚未验收与限制**：
- 本轮仅 agent-core/src/client 与文档改动，服务端 src/main、宿主与只读参考未改；未跑 runServer SelfTest，
  它无法覆盖客户端桥/模型队列。下次动服务端必须补 [m4*]/[m8] 级回归。
- **未运行真实 MC 客户端或 N.E.K.O 连接器**，不把单测/编译等同于新版本端到端通过。
  真机要验状态/扫描、一个动作、中途取消、反问回答、配置重载和断线重连，见 BRIDGE §6。
- 客户端逻辑取消立即生效；身体叫停仍需 C2S 到服务器，done(cancelled)/ok=true 不保证服务器已确认停止。
  断线期间最后事件不保证送达，连接器要将连接丢失视为未知，不能自行报成功。
- completed 表示大脑正常作答，不是目标达成的独立证明；工具失败后模型也可能正常汇报障碍。
- 旧 HTTP 模型请求未物理中断，可继续到返回/超时，但其派发与结果已被隔离；关闭后迟到的摘要
  可以更新旧 Conversation，却不能启动旧排队任务（有单测）。
- 服务端仍为单槽 BUSY；PARK 顶替是先取消再新投令，不是 scheduler preempt/remaining() 续跑。
  progress 服务端生产/限速、合成冶炼、界面增强及 [m9] A3 排查均未纳入本卡。
- 编译仍有现存过时 API 提示及 Gradle 10 兼容性警告，不影响本轮构建通过。

**下一步**：先用本轮测试包完成 MC 基础真机清单，再让连接器按 task_id + done.status 联调；
基础验收不达标就回此卡修复，不提前开游戏技能扩展。

### R2-S4 阶段 2 关账证据（受理即回执 + PARK，2026-09-10）

**问题**：`move_to`/`break_block` 这类要跑几秒到几分钟的活走"一问一答"，模型在那边干等一整跳——
既不能改主意也不能催，白烧一次 prefill+decode；而且客户端把长活的等待帽和服务端能力帽
（move 3600tick=180s vs 客户端 90s）对不上，超时后真回执还被当"迟到"丢弃。

**做了什么**（跨 agent-core / 服务端 / 客户端三层，协议契约写进 `docs/BRIDGE.md` §5.1）：

1. **信封**：C2S 的 `tool_call` 多一个**可选**字段 `accept`；S2C 新增 `job_ack`（受理）
   与 `job_event`（`progress|done|failed|cancelled|superseded`）。
   **缺省 = 老语义同步回执**——老客户端不认识 `job_ack`，服务端擅自换形态会让它白等 90 秒；
   新形态永远由发送方点名。快路径（DENIED/BUSY/TARGET_LOST…）即使工具是 ACCEPT 也直接回
   `tool_result`：**单终局契约**（一个 seq 要么 ack 要么 result，不会两条都来）。
2. **策略表只有一个来源**：`ServerTool.acceptanceMode()`（默认 SYNC）+
   `capTicks(args)`（工具自己 `submit` 用它、派发层报给客户端也用它——**同一个数**，
   这是"服务端 180s / 客户端 90s"那条真缺陷的根治）+ `acceptSubject(args)`（只提供主语，
   教学模板统一由派发层拼，模型学一遍就够）。
3. **大脑 PARK**（agent-core）：新增 `Ledger` 按 index 升序只写"已到达"的最长前缀——
   受理**不算已到达**（写进对话就等于告诉模型事情做完了，正是这一卡要消灭的谎）。
   受理 ⇒ 不再问模型、链挂起（**PARK 期间不计步**，长活不吃 40 步帽）；`job_event` 到了
   才补 `tool` 消息并开新轮。
4. **PARK 铁律**（卡点、也是本卡最容易写错的地方）：解锁（叫停/新指令）之前必须给
   **每一条** in-flight 的 `tool_call` 补一条合成回执（`CANCELLED:`/`SUPERSEDED:`），
   否则下一次请求里 `assistant.tool_calls` 有 id 找不到配对的 tool 消息 → **OpenAI 直接 400**。
   唯一实现是 `AgentLoop.supersedeAll`；未知 `jobId` 的事件是幂等空操作。
5. **客户端两段式记账**：新 `PendingJobs<T>`（agent-core，可单测）把 `seq → jobId` 的转段
   与**超时口径**收在一处——受理后的等待上限 = 服务端报的 `cap` × 50ms + 15s 余量。
   `AgentRunner` 的 `statusJson` 新增 `pending_jobs` / `parked` / `parked_jobs` / `accept_mode`。
6. **教学进提示词与工具描述**：`PromptBuilder.BASE` 与 `move_to`/`break_block` 描述都写明
   "`ACCEPTED:` 开头≠结果，别重发（会被 BUSY 挡）、别干等，做完系统会主动报"。
7. **回滚开关**：`client.json` 的 `accept_mode`（默认 true）。关掉即整条链退回今日语义，
   **不需要换服务端**——发布后唯一能一键止血的地方。面板"保存并应用"会原样带过该值，
   免得不小心把手关的开关拧回去。

**无头验收（`runServer` + `autotest.flag`，新世界）**：
- `[r2c] ACCEPT=2 条 SYNC=6 条 策略全对=true`
  （ACCEPT：`move_to` cap=3600tick / `break_block` cap=1200tick；其余六条 SYNC）
- `[r2c] 受理文案快照：ACCEPTED:我已开始「走到 2, 64, -16」编号 j1，最多约 180 秒。这条还没有结果——别猜、别等着，可以先回我一句话或做别的，做完我会主动报 j1。`
- `[r2c] 前缀对=true 教学齐=true 秒数按帽(180s)=true 客户端等195000ms>帽180000ms=true 相位映射=true`
- **无回归**：`[m4b] busy拒收=true cancel命中=true 空槽cancel=false`｜`[m8]` A 需确认=true 清单=2 格
  → B 到达=true 墙位被打通=true（且 B 那趟仍是 `memo=reuse(未搜索)`，阶段 1 未回退）
  → C 箱子分毫未动=true → D 干净失败=true｜`[r2d]` 六项全 true
- 单测 **145/145 全绿**（agent-core 79 + 根工程 66）：新增 `AgentLoopParkTest` 7 例、
  `PendingJobsTest` 7 例、`JobEnvelopeTest` 6 例。

**`[r2c]` 验不到什么（先读，别误读成"整卡验过了"）**：无头 harness **没有连着的客户端**，
`job_ack`/`job_event` 发出去是空操作（`ServerPlayNetworking.send` 对未连接玩家直接返回 false），
所以它只能验**策略表 / 文案 / 跨模块契约**这三件纯逻辑。真正的 job 往返
（受理 → 客户端 PARK → 事件回来 → 原序补账 → 续跑）由 `AgentLoopParkTest`（含
"第 0 条在跑、第 1 条当场有结果时**一条都不许先写**"与"缺口没补齐就不许开新轮"两条）
+ `PendingJobsTest` 覆盖；**两端对接要等主人联机看 `[brain] job 受理/结束` 与 `[brain] park=`**。

**偏差与诚实口径（不隐藏）**：
1. **本卡不是设计卡 §C 的全部**：已做「受理即回执 + PARK + 客户端两段式等待 + 铁律 +
   回滚开关 + 教学 + `[r2c]`」；**未做** 抢占（`sched.submit(...,preempt)` 向旧 seq 发
   `superseded`、`PathTask.remaining()` 续跑、`generation` 代际号）与 **progress 事件入桥
   + 限速（1/s/job、4/s 全局）**。`phase=progress` 的通路已通（客户端会播报到桥的 progress 帧），
   但**服务端目前不发 progress 帧**（没有调用点）。故 `[m4b]` 的"BUSY 拒收"基准**仍照旧有效**，
   要等抢占落地才改写成"顶替"。
2. **"最大单项收益"这个说法要打折**：对**严格串行**的活（走一段、再挖一格），
   总时长 = ack + 干活 + 一轮推理，和同步版一样——ACCEPT 不缩短它。真收益在两处：
   ①**大脑不再被一条长活占住**（HTTP 请求不长时间挂着，中转站/网关超时风险一起消失，
   主人也能中途插话）；②**同一轮里的后续调用不再被长活阻塞**（长活 0.2s 就让位）。
   但 ② 今天受限于"服务端一具身体 + 单槽"：第二个占身体的调用仍会拿 BUSY，
   所以真正吃到 ② 的是只读工具——那正是"工具复数化 / 只读工具可并行"那张卡的欠账。
   **下一步要量的就是它**，不是照着设计卡的 −12–35s 记账。
3. **`accept` 缺省 false 是刻意的不对称**：服务端只在发送方点名时才走新形态。
   代价是新客户端必须显式带上（已带），好处是老客户端/老服务端混跑不会互相坑。
4. **`[m9] A3` 转红与本卡无关，但必须记**：`A3 旧家断续后自清=false` 在 09-10 16:53 那次
   复跑（**早于**当日任何 R2-S4 改动）就已出现，A1/A2 照旧通过。已排除
   "被自身 view-distance 掩盖"这个假设（把 `view-distance` 从默认 10 调到 4 后仍 false）。
   原因未定，待查方向与排查纪律写进 `docs/DEVELOPMENT.md` §3。**别把它当本卡回归，
   也别据此宣布 R1-S3b 仍然成立。**

### R2-S4 阶段 1 关账证据（lastPlan 复用，2026-09-10）

**问题**：确认流是"两次 move_to"——第一次算出带挖/放清单的路、回 `NEED_CONFIRM`；
模型带 `may_alter_terrain=true` 重发时旧的 `PathTask` 实例已随 `Progress.Done` 丢掉
（`MoveToTool` 每次 `new`），于是**同一条路要完整重搜一遍**（连同 memo 重建、`liveify`
清单重算）。本阶段把"刚算好的那条路"存进新类 `PlanCache`
（`src/main/.../path/PlanCache.java`），确认后重发直接续用节点序列。

**判据（`PlanCacheTest` 12 例钉死）**：同一同伴 + **同一目标** + **同一起点** + TTL 30s 内。
- **起点必须也相同**：`path[0]` 就是起点，执行第一步会把同伴搬过去；同伴已经走开
  （如上一趟已抵达目标）却复用，会把它**传送回旧起点重走一遍**——这是可见回归，
  比"少省一次搜索"严重得多。实测若 A 已走开再发 B，日志出
  `未复用 lastPlan（OTHER_ORIGIN）`，行为退回今日语义（第三次复跑就是这种情况，见偏差 3）。
- **授权态刻意不入判据**：确认流本来就从 `may_alter_terrain=false`（回 NEED_CONFIRM）
  走到 `true`（执行），把授权态当键会让本优化在**唯一该生效的场景**里 100% 不命中。
  不担心"绕过点头"的理由是下面这条。
- **复用只复用节点序列，清单按当前世界当场重算**（`liveify`）：所以"要不要先点头"
  这个判断吃的是新鲜读数，不是缓存里的旧账；而且顺带把"路上新冒出来的方块"记成
  待挖格（原来可走、现在不可通行），该问主人点头仍会问。
- **未命中留痕**：`[path] 未复用 lastPlan（NO_SLOT|EXPIRED|OTHER_TARGET|OTHER_ORIGIN），照常重搜`。
  这条优化一旦因起点微移而永不命中，必须能从日志看出来，而不是只留一句"省了一次搜索"。

**无头验收（`runServer` + `run/mcbot/autotest.flag`，删除 `run/world` 的新世界）**：
- `[m8] 基准点 -7, 64, 32`；A（首搜）`未复用 lastPlan（NO_SLOT），照常重搜`
- A 那趟：`[path] -7,64,32→-1,64,32 路径 6 节点（挖 2 放 0）replan=0 partial=false
  [memo] expanded=302 命中=29758 实查=3253 验尸不符=0 耗时=12ms`
- `[m8] A 需确认=true 清单=2 格 内联=true 回执=188B`
- B 那趟（同秒、同目标同起点）：
  `[path] 复用确认前的搜索结果（lastPlan）：目标 -1,64,32 共 6 节点，按当前世界重算清单（挖 2 放 0），省掉一次重搜`
  → 紧跟 `[path] -7,64,32→-1,64,32 路径 6 节点（挖 2 放 0）replan=0 partial=false memo=reuse(未搜索)`。
  **两行逐字段对照**：同一目标、同样 6 节点/挖 2 放 0，但 `expanded/命中/实查/耗时` 全部消失、
  只剩 `memo=reuse(未搜索)`＝这趟**真的没搜**。两次 `计划清单` 也逐格相同
  （`D[-3,65,32] D[-3,66,32]`），即"重算清单"与"重搜清单"一致。
- `[m8] B 到达=true 墙位被打通=true`｜`[m8] C 箱子分毫未动=true 结果 ok=true`｜`[m8] D 干净失败=true`
- `[m4b] busy拒收=true cancel命中=true 空槽cancel=false`（本阶段**未**动受理语义，基准不变）
- `[r2d]`（R2-S1 感知）六项全 true，无回归
- 单测 **125/125 全绿**（agent-core 65 + 根工程 60；新增 `PlanCacheTest` 12 例，
  含一次变异自证：把 `put` 的 `List.copyOf(path)` 改回存引用 → `cachedPathIsASnapshotNotAnAlias` **精硬红**）

**收益的诚实口径（别照抄设计卡的数字）**：设计卡写"省 0.4–1.4s CPU + 27 拍"，那是
**R1 那次 16 格复杂山地**的量级；本轮验收用的是一条 6 节点直路，实测搜索只有
`expanded=302 / 耗时=12ms`——省下的就是这 12ms 加几个分帧拍。**收益与路的难度成正比**：
短直路省得可以忽略（本轮 B 的 12s 全是挖 2 格石头的固定耗时），难路才省得多。
所以本阶段的判据不是"省了多少毫秒"，而是"**复用确实发生了且不重搜**"（`memo=reuse(未搜索)`），
以及"复用没有把授权判断带偏"（清单按当前世界重算）。

**顺带修掉的 `[m8]` 验收夹具缺陷（不修就取不到上面的证据）**：原"测试大道"只给 1 格宽走廊
砌了脚+头两层侧墙，其余交给天然地形。后果是**验收门变成抽奖**：本机实测 A* 两次从走廊
**东端外侧**绕进来（`7,63,0→13,63,0` 路径 10 节点、挖 0 放 0），于是 A 报
`需确认=false` ——算法没坏，是场景没封住。改成**完全密闭的石砌短隧道**（四壁/顶/底/东端
全石，只留西端门洞），并在三次复跑里用它把两种漏法都钉死：
① 只砌两层侧墙 → 从东侧绕入；② 挖隧道时把东端塞子一起挖空（`dx<=6` 写成 `dx<=7`）
→ 又从东侧绕入。第三次起（基准点分别落在 49,69,-84 / -7,64,32 这类完全不同的出生点）
A 都必出 NEED_CONFIRM，说明夹具**已与地形无关**。另把 B 的观测从"`base.east(4)` 那一格变空气"
改成"墙位三格里有任意一格变空气"：同伴只有 2 格高，穿墙只要清掉两格中的一对，
实测有两次它是**跳上墙顶挖顶棚**（清 `(x,y+1)`/`(x,y+2)`），旧判据会给出
`墙已被挖穿=false` 的假读数。

**由复跑捞到的一个真实现象（写进风险）**：`[m4]` 的 move_to 与 `[m8]` 的场景 A 在无头脚本里
用**同一个基准点、同一个 `+6` 目标**，中间只隔几秒——于是有一轮 A **自己就命中了缓存**
（日志里 A/B 两次都打 `memo=reuse(未搜索)`，清单也从 2 格变成 4 格）。这不是 bug
（同一同伴、同起点、同目标，按定义就是同一个请求），而且它顺带证明了**这套设计能容忍
"世界已经变了"的旧计划**：那次命中的路是在隧道**建成之前**搜出来的，复用时按新世界重算
清单（2 格→4 格）后仍安全到达。但判读会因此变糊，所以 `[m8]` 开头显式调
`PathTask.clearPlanCache()`（该入口**只为无头验收存在**，产品路径不需要）。

**偏差（不隐藏）**：
1. **本阶段是设计卡 §C 里 lastPlan 的"确认重发"变体，不是卡上写的"抢占续跑"变体**。
   卡 §C 原文是"被顶时存 `lastPlan=(target,remaining,sampler快照)`，新任务 target 逐格相同
   → 跳 SEARCH 直接续节点"，那需要 `remaining()` + 代际号 + 抢占，属阶段 2/3。
   本阶段先落地**同一套缓存机制的最小可用面**，它也是抢占变体的前提：抢占版只要在
   `put` 时改存"剩余节点"即可，`take` 的判据可原样复用。卡上的 `reused_remaining=n`
   读数属抢占版，本阶段不适用。
2. **省下的是重搜，不是重算清单**：复用路径仍做一次 `liveify`（O(节点数) 次方块读，
   本机 4–14 节点样本实测 0ms），相对省掉的搜索 CPU 可忽略；换来的是
   "授权判断不吃旧读数"。
3. **起点判据可能偏严**：同伴若在 NEED_CONFIRM 与确认之间下坠/被推动一格就不命中，
   退回重搜（安全，只是优化不生效）。本机三次复跑里 A→B 位置都稳定（同一 `base`），
   故先按精确相等；长期命中率由上面那行"未复用"日志观测，真机若发现总不命中再谈放宽。
4. **缓存只按同伴分槽、不按"指令/任务代"分**：所以同一同伴在 30s 内对同一目标、同一
   起点重发，哪怕中间世界已经变了，也会复用（本轮复跑就捞到一例，见上）。行为上安全
   （清单重算 + 执行期每 20 节点复核），但**它不是"这次搜索的答案"，而是"上次搜索的答案"**
   ——将来若引入并发/多任务，这条判据要跟着代际号一起收紧。
5. **本阶段只覆盖"确认重发"这一条收益路径**：跨指令复用、抢占续跑、受理即回执
   （PARK / `ACCEPTED:` / job_ack）都还没做，`[m4b]` 的"BUSY 拒收"基准**照旧**有效。

### R2-S3 关账证据（真流式 + tool_call 早派发，2026-09-10）

**做了什么**：`LlmClient` 弃 `BodyHandlers.ofLines()`（它把"响应完成"与"能看见行"绑死，早派发
物理上不可能），自实现 `BodySubscriber` 按字节增量解码、按行切分（跨 chunk 攒半行、UTF-8 残缺
序列攒在 carry、CRLF 归一），每行到货即回调；新增 `TurnSink`（onTextDelta/onToolCallReady/
onComplete/onCounters）与 `TurnTimings`（时延打点）；新增 `StreamingTurnReader` +
`ToolArgsScanner`（闭合判定）；`AgentLoop` 早派发（工具就绪即在回调线程 `executor.execute`，
记账仍按 index 原序折叠）；`ScriptedEngine` 覆盖带 sink 的重载，使纯单测能真走早派发路径；
`AgentRunner` 接 `onStreamStats`（`[brain] llm stream ...`）与 `onPrefixReset`
（`[brain] prefix reset reason=...`），并**把 R2-S2 欠的 PromptBuilder 缓存真接上**
（此前 AgentRunner 仍每步读盘，S2 的缓存等于没生效——本轮补上）。

**验收（`./gradlew build` 全绿）**：
- **111 例全绿**（agent-core 63 + 根工程 48）：新增 `ToolArgsScannerTest` 21 例、
  `SseIncrementalTest` 11 例（自起 HttpServer、200ms 帧距真流式）、
  `AgentLoopEarlyDispatchTest` 4 例；既有 agent-core 27 例与根工程 48 例**零改动仍绿**。
- `SseIncrementalTest` 关键断言：`toolCallIsReadyBeforeTurnCompletes` —— 工具就绪时刻
  **严格早于**整轮完成（断言 `readyAtMs < completeAtMs`）；`deltasArriveWhileStreamIsStillOpen`
  —— 三片文本逐段到达且首段在流还开着时就到；`multibyteCharSplitAcrossChunks` —— 把一条帧
  切在汉字三字节序列**内部**，解出来仍是"你好世界"；`retryDoesNotPoisonLaterRequests`
  —— 换道 `/v1` 重试后第二次请求仍从原 baseUrl 出发（两次各打一次根路径）。
- **三次变异自证（主会话亲自抽检，非子代理报告）**：
  ① 早派发失效（`onToolCallReady` 直接 return）→ `toolStartsBeforeTurnLands` **精硬红**；
  ② 记账改回 `allOf`（只等齐、不排序）→ `toolResultsAreRecordedInIndexOrder` **精硬红**；
  ③ required 门失效（`if (false && ...)`）→ 4 例红（`balancedButMissingRequiredIsNotReady` /
  `nullRequiredValueIsNotReady` / `secondTopLevelObjectReplacesTheFirst` /
  `partialToolArgsDoNotReportReady`）。三次均改回后复跑全绿。
- 修复过程中被单测抓出的真 bug 三处（记下来免得重犯）：① `ToolArgsScanner` 的键位状态机把
  值字符串 `"}"` 当成键名收集，导致后续真键漏采（改为"最近顶层有效字符"判键位）；
  ② `AgentLoop` 早派发分支把工具续跑错接成 `finishChain()`（那是"这条指令完了"的出口，
  会把工具回执直接丢掉、链断在工具调用上），应接 `step()`；
  ③ tool_call 碎片被**累积两次**（外层自己解析一遍 + provider 又走一遍），raw 变成
  `{"x":1{"x":1,"y":2,"z":3}}` 永远解析失败——改为 provider 走 `toolCallDeltaChecked`
  把"写进去 + 判定结果"合成唯一一次写入。

**偏差与已知边界（不隐藏）**：
1. **本轮未跑无头 SelfTest**：改动全在客户端大脑层（`agent-core` + `src/client`），
   `src/main`（服务端/三道闸/寻路/票）**一行未动**，而无头 harness 恰好验不了真流式
   （SelfTest 直跑 dispatcher，不经过 `LlmClient`）。故按"证据匹配被改面"用
   `SseIncrementalTest`（真 HTTP 流式）+ `AgentLoopEarlyDispatchTest` 顶替。
   **这不是豁免**：下次动服务端代码时必须补 `[m4*]/[m8]` 级回归。
2. **早派发的真实收益待真机量化**：`[brain] llm stream` 打点已就位，"省了多少秒"要等主人下次
   真机会话的 `first_tool` vs 整轮耗时才作数。中转站若把整轮攒成一坨再吐，`after_chunk`
   会把它暴露出来，此时早派发收益归零（可观测，不是猜的）。
3. **`toolResultsAreRecordedInIndexOrder` 是契约钉、不是事故复盘**：真实 mod 路径工具是串行
   执行的（一次一个 payload、等回执才发下一个），"逆序完成"在现网不会自然发生；本用例用测试
   掌控的 future 刻意造出逆序，目的是钉死"`ToolExecutor` 允许并行时也不破配对顺序"。
4. **`ToolArgsScanner` 的"两个独立对象"规则**：顶层对象已闭合、又来一个以 `{` 开头的片段 ⇒
   丢弃旧的以新的为准。真协议不这么发，这是为"中转站什么都干得出来"兜底；代价是端点若真把
   两段**续写**拆成两个对象，我们只认最后一个（取舍写在类注释里）。
5. **`ChatEngine` 新增带 sink 重载**：只实现旧三参的引擎由接口默认实现兜底（拿整轮后补发
   `onComplete`），故存量替身零改动；但这类引擎**不会**有早派发（`ScriptedEngine` 已覆盖，
   属测试替身行为，不影响 mod 路径）。
6. **`AgentLoop` 的 `reactor` 字段可空**：`thenAccept` 回调里读 `reactor.stats()` 依赖
   "回调与 step 同链、不会跨步"，与既有 `convo/steps` 等字段同一假设；若将来引入并发多步，
   这里要一并加锁（已在该字段注释点明）。

### 效率评估（2026-09-10，主会话实测 + 两份只读深挖）→ `docs/EFFICIENCY-AUDIT.md`

主人要求"对项目整体效率做评估、目标是高效"。做了一轮评估，**发现并当场修掉 3 个真缺陷**
（其中一个是本会话 R2-S3 自己引入的回归），并把测量能力补上。

**修掉的 3 个真缺陷（都有代码 + 测试）**：
1. **早派发绕过串行链 → 撞服务端单槽（R2-S3 回归，我引入的）**：`StepReactor` 对每个就绪的
   tool_call 都立刻 `executor.execute`（mod 侧 = 立刻发 payload），**绕过了 `awaitAndRecord` 的
   串行链**。而服务端只有一具身体 + 单槽调度器（忙即回 BUSY），所以"同轮 3 个占身体的调用"
   会让后两个白拿 BUSY——**n−1 次白跑的往返**。R2-A 之前这个保护天然存在（同轮多调用被串行链排住）。
   修法：新增常量 `MAX_EARLY_DISPATCH = 1`，**只有最先就绪的那个提前起跑**，其余等整轮落地后按
   index 串行（单调用场景零损失）。测试 `onlyFirstToolIsDispatchedEarlyWhenSeveralAreReady`
   （断言落在"整轮还没落地"的时刻才分得出早发/晚发）；**变异自证**：撤掉额度纪律 → 精硬红。
2. **`data` 字段到不了模型（真缺陷，非本轮引入）**：`PathTask` 的 `NEED_CONFIRM` 回执写着
   "明细见 `data.blocks`"、并生成 ≤32 格清单进 `data`，但链路上
   `ServerToolDispatcher`（塞进信封）→ `AgentRunner` **只取 `env.str("feedback")`** →
   `ToolOutcome(ok, feedback)`（**只有两个字段**）→ `Msg.Tool(...feedback...)`。
   全仓 grep 确认 **`data` 在客户端/桥/面板零消费者**。后果：模型**拿着一句"明细见 data.blocks"
   去批准一份自己看不见的清单**，最可能的补偿动作是**再发一次 `scan_area`（多一整轮 2–6s）**。
   已记入评估 §5.4 与排期表（内联进 `feedback`），**本轮未改**（属行为改动，需单独验收）。
3. **客户端 90s 超时 < 服务端 move 帽 180s（真缺陷）**：`AgentRunner.TOOL_TIMEOUT_MS=90s` 对
   所有工具一律，而 `MoveToTool` 提交 `3600 tick = 180s`。任何 90–180s 的移动，模型都会收到
   "**先别重复这个操作**"——**一句错误的教它别重试**（同伴其实还在走），而那条真回执因 `seq`
   已被移除被**静默丢弃**，随后再撞 BUSY。修法：`toolTimeoutMs(tool, args)` 按工具给帽
   （move 210s / wait 80s / 其余 90s，均严格大于服务端 cap）；**迟到回执改为留痕**
   （`late_results` 计数 + WARN 日志 + 进 `/v1/status`），这个计数持续 >0 就是帽配错的直接证据。

**补上的测量能力（评估 §5，"让效率可测量"）**：
- **`cache_waste` 诊断**（`AgentLoop.cacheWasteOf` + 进 `[brain] llm stream`）：
  `max(0, min(上轮prompt, 本轮prompt) − 本轮cached − 1024)`。**它是诊断不是统计**：
  正常长期贴近 0，一抬头就说明前缀被谁动了（system 里混进会变的字段/工具表增删/历史被中间剪了/
  间隔太久缓存过期）。首轮与后端没报 cached 时不判定。5 例单测钉住（含噪声底）。
- **每工具耗时归因**（`ServerToolDispatcher`）：`[brain] tool <name> <ms> chars=<n>`
  ——此前**完全没有**（`src/main` 里除 SelfTest 外零 per-tool 打点），"模型慢"与"工具慢"混在一起
  无法归因。顺手补上"`runAsync` 异步异常静默丢失→客户端白等 90s TIMEOUT"的缺口（改 `whenComplete`）。
- **`liveify` 单独计时**：`[brain] liveify nodes=N Xms`——它是单拍、无分帧、无预算的全路径活体
  重扫（评估 §3.1），量级此前只能猜。

**评估的三条主要结论（细节见文档）**：
1. **瓶颈不在链路**：实测 tick+网络跳仅 100–150ms/工具，而基线是 **8 轮 × 2–6s = 20–50s**；
   `break_block` 实打实 ~7s。**剩下的 12–42s 全是模型往返与挂起** → 优化方向是"减轮次 + 不冻回合"。
2. **协议在主动阻止批量化**：`PromptBuilder.BASE` 原文"**一次调用一步**"，而 `AgentLoop` **本来就支持**
   一轮多调用。能力有、提示词不让用——但**必须先做 S4/单槽顶替**，否则批量会把 BUSY 放大成 n−1 条。
3. **⚠ 方向性分歧（最大发现）**：参考项目的搜索跑在 **`max(2, 核数−2)` 守护线程池 + 墙钟限流
   （首段 2s/接续 5s）+ 节点帽 2,000,000 且明文"不许当 CPU 上限"**；而 mcbot 是**在主线程上分帧**
   （300 节点/6ms/400ms）。他们文档直接点名"拿节点帽当 CPU 上限"会导致**误判无路→拉黑近处矿→舍近求远**。
   搬离 tick 线程是**长途寻路的分水岭，但需要"主线程造、worker 只读的世界快照"**（大改，已后置）。

**⚠ 勘误：推翻了我们自己的侦察报告一节**。`docs/recon/reference-project-recon.md` §4.3 曾把参考项目的
**分层粗图（16³ 段摘要 + 多源 Dijkstra 距离场）当作已实现**写进排期参考。本轮复核：
全仓无 `hier`/`coarse` 目录；`*.java` grep `COARSE_|HierPath|CoarsePath|hierarchical` 只有 3 处命中，
**全是 `Items.COARSE_DIRT`（粗泥）这类方块名误报**；且"7 档启发式"是**同一个 A\* 循环里顺序试**，
不是并行 7 次搜索。**该节内容不得作为排期依据**，已在原文顶部加勘误块。
教训：**读他仓必须先 grep 代码，再信文档。**

**⚠ 一条会改变预期的风险（评估 §0）**：STATUS 里既有代理实测记着"5 帧各隔 1s 发出、future 5059ms 才回
→ `stream:true` 零收益"。也就是说**当前中转站把整条流攒完才交出来**。此时 **R2-S3 的早派发与流式的
端到端收益 ≈ 0**（能力就位、对端不给机会）。S3 不算白做（换真分片端点立刻生效，且 `after_chunk`
打点把这件事变成可观测），但**体感提速不能指望它**。发布说明/面板提示应写明这一条。

**本轮验收**：`./gradlew build` 全绿，**113 例**（agent-core 65 + 根工程 48）；
新增 2 例（早派发额度 + 缓存浪费），并做了一次变异自证（撤额度纪律 → 精硬红后复原）。

**无头 SelfTest 复跑（16:20，干净世界全新生成 `run/world`，无孤儿 java）**——本轮动了 `src/main`
（`ServerToolDispatcher` 加耗时日志与异步异常兜底、`PathTask` 回执内联、`SelfTest` 加断言），
按纪律补 `[m8]` 级回归。判读（ASCII 事实项，逐条核对）：
- **`[m8] A 需确认=true 清单=4 格 内联=true 回执=208B`** —— 三件事一次钉住：
  ① 确认流未被改动破坏；② **清单真的内联在给模型看的文字里**（`内联=true`，本轮新缺陷的修复）；
  ③ 回执 208B 远小于 4096B 预算。回执原文含 `要动的方块：Spruce Leaves×2、Stone×2`
  （同类合并，4 格合成 2 类）。
- `[m8] B 到达=true`、`[m8] C 箱子分毫未动=true`、`[m8] D 清除失败=true NO_PATH` —— 四场景全中。
  （B 的"墙已被挖穿=false"在本世界无意义：那是 m4 自建墙的断言，干净世界换成了天然地形。）
- `[m8] 判读基准：A 需确认=true 清单≥1（最优解是挖头格、踩脚格翻墙，只挖 1 格）` —— 基准行确认。
- `[m4b] busy拒收=true cancel命中=true 空槽cancel=false`、`[m5a]` 四条全中 —— 零回退。
- **本轮新打点实测**：`[brain] tool status 1ms chars=62`（per-tool 归因首次生效）；
  `[brain] liveify nodes=4..14 0ms`（4 次）。
- ⚠ **liveify 计时只有短路径样本**（4–14 节点，全 0ms）：**不能据此说"liveify 不是热点"**。
  评估里那个"单拍无分帧、可能 5–15ms"的假设**仍然未被证伪**——必须等真机长途（数百节点）样本。
  已把这条写进评估，避免拿弱证据当结论。

**偏差与已知边界（效率评估）**：
1. **`data` 字段仍到不了模型**（见上"修掉的 3 个真缺陷"第 2 条）：本轮只把清单内联进了
   `feedback`（模型这下看得见了），但**`data` 通道本身依旧没有消费者**——`ScanAreaTool` 等
   仍在往 `data` 里塞一份客户端丢弃的副本。**语义上无害（模型不依赖它），但白花线材与分配**；
   要么让面板/桥真的消费它，要么瘦身，**归 R3 面板一并决定**，本轮不动。
2. **内联预算 1200B 是估的**：实测这次 4 格只用 208B；极端情况（32 种不同方块）会触发截断并写出
   "还有 N 格未列"。截断分支**本轮没有实测样本**（干净世界里没造出 32 类方块的路），
   逻辑单测也没覆盖（`PathTask` 依赖 MC 类，进不了根工程 JUnit）——**已知测试盲区，记在这里**。
3. **早派发额度 `MAX_EARLY_DISPATCH=1` 是"安全默认"，不是最优**：纯只读工具
   （`status`/`scan_area`）其实可以并行早派发，但 agent-core 不认识"哪些工具占身体"
   （那是宿主知识）。**将来若在 `ToolSpec` 上加一个 `occupiesBody` 标志，这个上限就能按类放开**
   ——记入债表，等"工具复数化"那张卡一起做。


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

环境：PCL 实例（实例目录在主人本机，路径不入仓；mods=本仓 build/libs jar+fabric-api 0.141.6）进 dev runServer（离线模式），同机桥 127.0.0.1:57121。

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

## 环境迁移记录（2026-09-07；09-10 补：已改成多设备可移植）

> **09-10 口径变更（多设备开发）**：主人明确要在多台设备上接着干，所以**机器相关的绝对路径
> 一律不进仓**。仓内 `gradle.properties` 现在只留工具链版本 + 代理，`org.gradle.java.home`
> 挪到**用户级** `<GRADLE_USER_HOME>/gradle.properties`（仓外）；wrapper 的 `distributionUrl`
> 改回**官方源**。下面这张历史表的路径保留作记录，但**不要照着填**——换机只需做
> `docs/DEVELOPMENT.md` §2 的那三步。

机器从原开发机迁到新机时，以下三项属"本机适配"（**历史上曾写进仓内，现已移出**）：

| 项 | 当时的写法（历史记录） | 现在的口径 |
|---|---|---|
| 构建 JDK | `org.gradle.java.home=<用户目录>\.gradle\jdks\eclipse_adoptium-21-amd64-windows.2`（Temurin 21.0.10，Gradle 自动置备） | **移出仓**：写进用户级 `gradle.properties`；不设也行，只要 PATH 上的 `java` 是 21 |
| wrapper distributionUrl | `file:/<盘>:/ai/gradle-9.5.1-bin.zip`（140MB，curl 断点续传+unzip -t 验完） | **回官方源**（多设备必须）；到不了 services.gradle.org 的机器按 `gradle-wrapper.properties` 注释临时改 `file:`（只改本机、别提交） |
| 代理 systemProp | 曾全部移除走直连 | 仓内保留 `127.0.0.1:7897` 并标注"本机网络相关，换环境按需删改" |

网络事实：JVM 不读注册表代理，构建是否走代理只由 `systemProp.*` 决定；某台机器上
Maven Central 直连偶发 000、重试即过（未上镜像，若再频发考虑 aliyun）。

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

### 整改总案与合并裁决（09-08，设计卡已入 `docs/plan/R1-pathfinding.md` / `R2-latency.md` / `R3-panel.md`）

三张 Plan 卡（寻路/延迟/面板）均 javap 实证；主会话合并时**推/改了三处代理结论**：
1. R1 卡"未加载读成 VOID_AIR 洞"归因错——实测 VOID_AIR 仅垂直越界；真病灶是
   `passable()` 不查 isLoaded → `getBlockState` → `getChunk(II)` 默认 FULL+create=true →
   **主线程同步生成区块**（新发现，入 R1-B）；单价重查：最低 0.467/曼哈顿单（非 0.7），
   现 h 高估 2 倍坐实。
2. R3 卡"passable 有 isLoaded 防护"——实测只有 `digSeconds` 有（:65），passable 没有（:53-59）。
3. R2 卡与 R3 卡的 skills 读盘冲突——裁决：**R2 出 AtomicReference 缓存+`reloadSkills()` 失效钩子，
   R3 调钩子**；persona 零重建（diff 驱动 `applyConfig`）。总线命名：**沿用 BridgeEvents**，
   javadoc 正名（改名是文档债）。一发契约（一 seq 恰一终局）：**不留双发兼容窗**（自家客户端自控）。
   `ROCK_PATHS` 不含泥土/沙（目标≠材料）。多 call 并行：**不做**（撞单槽，串行+PARK 已够）。
   PARTIAL 后服务端自动接力：**不做**，回执教模型重发（R2-C 落地后再评）。
   待主人拍板：PARK 中新指令自动顶替还是需明示；护栏值持久化；W=1.5/1.8/面板可调；真机验收批次。

### 主人拍板记录（09-08 午）与开工序

1. **顺序：R0 小清账先行 → 再进 R1 寻路主线**；R2-S1 感知卡紧随 R1。
2. **PARK 抢占：新活自动顶替旧活**（SUPERSEDED 合成回执 + lastPlan 30s 可续），不留 BUSY 拒收。
3. **W 钉 1.8**，山体用例实测展开数后校终值；不进面板可调。
4. **验收：无头先行，然后一次集中真机**（山体 PARTIAL 手感 + [brain] 新打点轮次账 + 面板清单①）。
5. 合并复核又推翻一处代理结论：`BreakBlockTool` 注释"留两拍"其实**是对的**（`++collectTicks<3`：
   前两拍 running、第三拍回包），审计代理 off-by-one——R0 该项降为"把注释写到不可误读"，
   不改行为。（累计四处代理结论被主会话推翻：VOID_AIR 归因、isLoaded 位置、单价 0.467、留两拍。）
   护栏值持久化（R3 开放③）未拍板 → 按"持久化进 client.json"默认实现，有异议再改。

### R1-S1 关账证据（09-08 13:44 无头）

加权 h（W=1.8×0.467×距体积 L1 + 入柱价，公开"故意不可采纳"，代价上界 W×最优）+
部分提交 API（budgetReached/partialAvailable/partialPath；下限 gain=4；**对外失败字符串不动**，
PARTIAL 话术归 S3）+ expanded() 等 getter + 山体用例①②③⑥。
**校准数据（山体假 sampler，确定性）**：旧球形口径实测 w=1.0 需 **519** 展开、新 w=1.8 需 **242**
（2.1× 聚焦）；150 帽现场验证 partial 真实可用（终点推进≥4 钉死）。现有 8 例零改动仍绿
（12/12）；无头 `[m8]` A/B/C/D、`[m4]/[m4b]/[m5a]/[m3]` 全不回退，0 异常。
**诚实的意外**：合成山体没能复现真机的 8000 爆炸（519≪8000）——真机撞帽大概率叠加
**未加载区同步生成 + 每节点 100–300 次现查**这两个本卡治不到的因素——S2（memo+UNKNOWN）
才是主刀，S1 的聚焦只是减常数那半；设计卡 §B 优先级因此上调，不得因 S1 绿就宣布撞帽已愈。

### R1-S2 关账证据（09-08 14:12 无头，**新世界**口径）

落地：① `MemoDigSampler` 装饰层（每格 pass 类别+量化 dig 秒（0.25s 格）+support/placeable，
容量帽 262144 格→超帽**退回直读不失败**；每 256 次 miss 抽验 8 格，累计不符越 STALE_MAX=24
→ `worldChanged`，PathTask 丢图重开≤ 2 次后再不 memo——宁慢不抽）；② `liveify`：搜索完成后
用裸 sampler 按**当前世界**重建每节点挖/放清单并入 `planDigs/planPlaces`——**NEED_CONFIRM
清单永远是现算真值，快照只当启发式**（执行期 mineOne 本就逐格 live，双层自晦）；
③ 堵 `LevelDigSampler.passable/support/placeable` 缺 isLoaded 的同步生成洞（UNKNOWN=墙，
搜索伸进未加载区不再主线程生成区块）；④ sacred/Registry 集合预烘（IdentityHashMap）。
重规划那层不加验尸重启：replan 单拍内同步算完，世界不可能中途变，memo 在那是纯去重。
**单测**：+3（④等价 3×500×4 查询 实查1440/命中4560；⑤验尸 24→25 越限定性+判坏后验尸短路；
⑥超帽降级行为仍全对）；path 系 15/15、全仓 20/20 绿。
**新世界 `[m8]` 全中**（14:12:44–14:12:56）：A 需确认=true 清单 1 格（最优「踩脚挖头」）
/B 到达=true 真挖 6s/C 箱分毫未动/D NO_PATH；`[m5a]` 四 true；验尸不符=0；memo 去重 4.9:1
（A 搜索 expanded=62 命中 5850/实查 1202），0 异常，flag 自删。
⚠ **关账前踩坑入册（harness 事实，非代码 bug）**：旧 dev 世界连测多轮（含本卡前的 S1 验
跑+多次历史 `[m8]` 挖穿）后，A 场景跑出"需确认=false 挖 0 绕行 expanded=89"——侧墙只封
x=7..12，历史挖穿点在封闭段外围成绕行洞；删 run/world 重建后一次定性 **非 S2 回归**。
教训已写进 DEVELOPMENT §3：`[m8]` 判读必须在未挖穿的干净世界；旧 dev 世界已废弃（无独有
数据，纯测试垫块）。〔09-10 补：该"干净世界"教训的**根因已消除**——A/B/C 场景改成
完全密闭的石砌隧道后与出生点/历史挖掘无关，见本文顶部 R2-S4 阶段 1 节；本段保留作历史。〕下张卡：**R1-S3（REPLAN_SEARCH 真分帧 + 旧路复用偏置 ×0.7 +
双帽时间预算 + PARTIAL 话术）**。

### R1-S3 关账证据（09-08 15:52 无头，含一次重大定性）

**代码四项**（编译绿，单测 22/22：DigAStar 14 + Memo 3 + WireSize 5）：
① `REPLAN_SEARCH` 真分帧相位：`beginReplan` 只装盘（新 sampler/降权集/相位），旧版单拍
同步 while 冻结点已杀；重搜与首搜共用同一分帧出口，失败/确认/计数不复制逻辑。
② 旧路降权 ×0.7（`BIAS_REUSE`）：只取 cursor-1 往后的未来路（回头路降权会诱导读回振荡），
复核认定"变了"的格周围切比雪夫 ≤1 不入集；降权集挂 `reusePending`，验尸重开自动续。
单测⑨实据：同地形重搜原路复现、cost 恰 ×0.7（29→20.3）、expanded 143→29。
③ 双帽：节点 8000（旧）+ 累计 CPU 400ms（`SEARCH_TOTAL_NS`，TPS 无关的纯搜索时长；
每 16 拓探一次钟换开销），先到先停；单拍另设 6ms 切片（`SEARCH_SLICE_NS`），节点/切片
/总预算三层都兜。撞帽失败串仍 BUDGET_EXCEEDED 前缀（对外契约不破）；单测⑧钉死。
④ PARTIAL/NO_PROGRESS 话术（PathTask 层，§C 模板）：撞帽且推进≥4 → 半程段照走（含挖放
仍先过 NEED_CONFIRM 闸，清单前缀"本段（半程推进）"），走完到达文案换
"PARTIAL:…已走到最近点…请从该点重发 move_to（可分多段）"；推进不足 → NO_PROGRESS
（不再冒充 BUDGET）；open 空的真 NO_PATH 永不降级。

**⚠ 本卡真正的收获是被新世界全红逼出来的两个产品级事实（探针定性，非猜测）**：
  事实 A（产品缺陷，立卡 R1-S3b）：**假玩家不持任何 chunk 票**——`[m8dbg-pre]` 在任何
  getBlockState 触碰前实测同伴自己脚下格 `hasChunkAt=false`；之前所有世界能跑绿纯属
  踩在出生点区块常驻区/主人在线加载区上。`getBlockState` 的同步强载**撑不过一拍**
  （拍尾无票即回收，B 场景 TARGET_LOST vs 探针 loaded=true 的"同秒矛盾"即此机制）。
  影面：真实产品里同伴单独在远处行动时根本无路可寻——**不修此洞，R1 所有搜索改进
  对野外孤伴无效**。harness 已用 `ServerChunkCache.addTicketWithRadius(PLAYER_LOADING/
  PLAYER_SIMULATION, pos, 8)` 持票（无 PERSIST 标，重启自清），产品侧需在 summon/
  移动跟踪/dismiss 挂同机制→R1-S3b，待主人拍板。
  事实 B（harness 缺陷，已修）：`[m8]` 场景被 m4 残留（箱子正好塞在走廊唯一入口）与
  悬空出生点（脚撑=false 且 placeStock=0 → 26 邻居合法拒绝 → expanded=1 是**正确算法
  行为**）污染。修：SelfTest 新增 `findClearStrip`（14 格水平净带扫描，脚/头可通行、
  下为实心非容器）+ m4/m8 各自迁带 + `[m8env]` 常驻断言（票失效立刻 warn，不再靠猜）。
  新世界（-672,79,-608 雪地尖柱，最难啃的地形）实测：m4 的 `move_to 完成: false
  NEED_CONFIRM 放2格` 是悬空点搭柱需授权的**正确新语义**，非退步。

**关账验收（15:51:54–15:52:37，票+净带后的最终代码，异常 0，停服无孤儿）**：
`[m5a]` 四 true；`[m4b]` busy/cancel/空槽全中；`[m8]` A 需确认=true 清单 1（挖头格最优解，
净带坐标 -661）/ B 到达=true 真挖 6s（站定 -662,80,-607）/ C 箱未动=true / D NO_PATH ✓；
`[m8env]` 零告警；memo 去重 3970/962 ≈ 4:1；`partial=false` 字段已入 `[path]` 日志。
真机山体 PARTIAL/无单帧冻结验证：并入最终一次性真机会话清单（不单独约）。

### R1-S4 常数对账（09-08 16:05，纯文档）

ARCHITECTURE §10「寻路(R1 后)」行重写全部新常数（双帽 8000/400ms、切片 6ms、
h=1.8×0.467×L1距体积+入柱价（公开故意不可采纳）、PARTIAL gain≥4、memo 帽 262144、
验尸 256/8/24、DIG_QUANT 0.25s、降权 0.7）；TOOLS §3：BUDGET_EXCEEDED 退出对外词汇
（grep 坐实仅剩 DigAStar 内部与 SelfTest 容忍位），新增 PARTIAL/NO_PROGRESS 两行；
ROADMAP 债务表：销"16 格山地撞帽"（转真机终验）、立"假玩家无 chunk 票→R1-S3b"。
至此 R1 主线（S1–S4）无头口径关账；尾随一笔 S3b（持票）同日下午落地，见下节。

### R1-S3b 关账证据（09-08 16:31，无头；经主人同意先做机制调研）

调研方式：Explore 子代理**只读**参考项目拿机制思路（结论已验：零代码行/零注释文本/
零专有名称；且 1.21.11 连 TicketType.create 都已移除，参考的写法在目标版根本不可移植，
事实侧封死抄袭），落地 `CompanionChunkPads` 全自写。javap 实测三件事已入漂移表（见本节末）。

落地 `body/CompanionChunkPads`（新类 + McbotMod 挂点，共 ~60 行）：自定义
`TicketType(40t 超时, LOADING|SIMULATION)`（1.21.11 该类是**公开 record 构造器**，无需
access widener/mixin），`addTicketWithRadius` 在同伴中心挂 5×5 垫子；END_SERVER_TICK 每拍
续票（**排在 scheduler.tick 之前**：传送后下一拍 PathTask 先见票再搜索）；只续不撤
（字节码坐实同 type+同 level 重加走 `resetTicksLeft`，天然去重）；dismiss/死亡/崩溃=停续
→40t 自然过期，无释放代码；无 PERSIST→重启零残留；多同伴共 chunk 同键互不抽干。
**与参考实现的刻意分歧：不设"owner 在线"闸**——mcbot 卖点是同伴独立跑长活，代价每同伴
≤25 chunk（上限=名册数）；驱动绝不挂 entity tick（票过期→chunk 退 entity-ticking→tick 断→
永远刷不回的自锁死，注释已钉）。
**[m9] 新验收（16:31:10–18）**：A1 跳 96 格外新家垫子跟到=true；A2 远环（+10 chunk）
不加载=false（排除"碰巧全域加载"）；A3 再跳后旧家票断续→区块真的卸载=true。
**dogfood**：harness 临时票（holdChunks/releaseChunks PLAYER_*）全部删除，`[m8]` 仅靠产品票
跑：A 需确认清单 1/B 真挖 6s 到达/C 箱未动/D NO_PATH，`[m8env]` 零告警；本轮 m4 move
还是 挖0放0 直走 expanded=5（世界残留少了一格坦路，正确行为）。
异常 0，停服无孤儿。**R1 无头部分至此全部收尾**；真机山体 PARTIAL/面板清单① 归最终
合并真机会话。

⚠ harness 教训入册：S2 的 UNKNOWN=墙方向对但不完整——配套必须同时给同伴发票，
否则"墙"砌在自己人门口（本卡就是这笔债的偿还）。另 S3b 的反向收获：harness 临时票
全部删除后 `[m8]` 仍全绿，产品票独力成立，临时票机制已死化删除（不是注释掉）。

### R2 波次 1 进展（09-08 17:3x，首次子代理流水线作业）

**模式**：按设计卡文件面交集拆包——S1(D 感知：server+client+common) 与 S2(B 前缀：纯
agent-core) 零交集，各在 **git worktree** 并行；S3(A 流式) 因碰 AgentLoop 排波 2；S4(C 协议)
碰面最广排最后。代理交付均要求：白名单外禁改/禁碰文档/禁 commit-main/变异自证。
主会话按 trust-but-verify 合入。

**S2 已合（merge `0ed915b`）**：Conversation 折叠检查点/冻结决定/PromptBuilder 实例缓存
+reloadSkills 钩子/压缩链尾化；agent-core 27/27；主会话**独立变异抽检**（砍 checkpoint
单调保护→`checkpointMonotonicAndFoldDecisionsFrozen` 精确红，恢复即绿）确认断言有鉴别力。
偏差 4 条已复核接受（尾部窗口新语义使旧 4 条折叠场景逻辑上不可存→拉长到 14 条断言逐条保留；
链尾压缩为"等效非严格后台"；叫停链不压缩）；**客户端接线欠账**（AgentRunner 约 3 行：
PromptBuilder 实例化+双传、onPrefixReset 接 [brain] 日志）——与 S3 的 useStreaming/打点接线
合并做，避免接两次半截线。prompt cache 收益（cached/real≥0.6）归最终真机会话终验。

**S1 已合（merge `bbd09ea`）**：5 个零依赖 common 类（ScanCategory/Classify/Format/Plan/Summary）
+26 新单测（根目 48/48）；ScanAreaTool 重写为薄胶水（classify 6 词表/ROCK_PATHS 常数集/
绝对坐标/首行八向/三层名额 8-10-12/MAX_SAMPLES 900）；客户端准星注入（[我此刻盯着]，
MISS/ENTITY/未加载一律不注入，只读不写跨线程，异常吃掉=宁缺勿噪）；偏差 5 条全部台理
已接受（中环按路径归组保"哪种矿"/名额轮转防 stone 刷掉一切/ore 收窄 endsWith/StatusTool 契约
不动等）。**无头 `[r2d]`（17:31:51）：含 stone/含 @(/体量 561B<32765 三项全 true，结构三项
（首行我在/面朝八向/[rock]）全中**；回执快照实测格式干净（实读 68/计划 787，近环含 d 距离）；
`[m5a]/[m8] A-D/[m9] A1A2` 同跑不回退，异常 0，无 WARN 撞 50ms 线。

**⚠ 新观察（存疑入册，非本波 diff 引入）**：`[m9]` A3 "旧家自清" 同代码两次运行结果翻转
（16:31 true → 17:31 false）。S1 未碰 m9/票代码（diff 空）；aimi 两场均在。首要嫌疑：
**票过期→真卸载有 purge 周期/卸载队列延迟，wait2=80t 处在边界竞态**（javap 查 purge 频率
做到一半被收工打断）。产品语义不受影响（票必会过期，只是晚几拍）；下回合把 m9 wait2
拉到 6s 复跑三场定性，或实测 purge 周期后把"自清延迟 ≤N t"写进注释基准。

**S3 半成品存档**：代理被中途叫停于"写完实现、尚未编译验证/未写 SseIncrementalTest"；
分支 `pi-agent-4e7d4c32-8709-478`@f1c9060 保留**不入 main**（未验证不 merge 的纪律）。下回合
从该分支 checkout 续做（编译→测试→铁律变异），或评估重写成本后重开。

**子代理纪律审计**：两单交付零越界（git diff --name-only 均白名单内）；两单均报偏差未自静
口径；代理报告中的"sk-xxx"命中经定位为 Cli.java 既有 javadoc 占位非本次引入。流水线成立。

**推送与密钥**：主人重发 PAT 用于推送，未落任何盘（remote 无凭据、.git/config 干净、
一次性 header）；该 token 已在聊天出现多次，**尽快去 GitHub 作废重发**。本次待推：
S3 关账 commit（代码+探针+harness 修+文档）。

### R0 关账证据（09-08 12:39–12:40，本机无头）

六项全部落地：tick 探测 20次/s→每 3s（首拍时序不变）；`BreakBlockTool` 注释经复核**审计代理
 off-by-one**——"留两拍"本就对，改为不可误读措辞、行为零动（此项入"第 4 处被推翻的代理结论"）；
桥 ask 60→135s + MCP 文本 + 池 4→8（客户端侧，行为验归真机/R2-C）；`noteCompacted` 复位真数
 + 新回归用例（**变异检验**：临时撤修复该例精确红、恢复即绿；配置用水位 6000/110 条小消息，
 压后近段 ≈1500 且条数 >8，确保只有"旧真数作废"能决定断言）。
验收：`[m5a]` 四条全 true、`[m3]`5 场景、`[m4b]` `busy=true cancel=true 空槽=false`、
`[m8]` A 需确认=true 清单=1 格 / B 到达=true（真挖 6s）/ C 未动 / D NO_PATH；
单测 agent-core 20 例（含新增）+ WireSize 5 + DigAStar 8 全绿，MC 侧 0 异常，flag 自删正常。
下张卡：**R1-S1 纯算法（加权 h + PARTIAL 三分 + getter + 山体用例①②③⑥）**。

## 1.21.11 API 实测字段笔记（javap 自证，勿凭记忆改写）

| 事项 | 真实形状（Mojang 映射） |
|---|---|
| 世界路径与正常停服（F0 第二轮） | javap：MinecraftServer.getWorldPath(LevelResource)、LevelResource.ROOT/PLAYER_DATA_DIR、MinecraftServer.halt(boolean) 均存在；名册取当前世界 ROOT，开发验收 opt-in 标记走 halt(false) |
| 全局 receiver 生命周期（F0 第二轮） | Fabric networking 5.1.6 源 JAR：registerGlobalReceiver 已注册则返回 false 且不替换，覆盖当前及未来连接；handler 在服务器线程调用，Context.server() 可取实际服务器。因此只能初始化注册一次并动态选当前 dispatcher |
| EditBox 初始值与隐私（F0 真机修复） | 构造器 maxLength 默认 32；setValue 先按该值截断，因此必须先 setMaxLength 再 setValue。存在 addFormatter(EditBox.TextFormatter)，format(String,int) 返回 FormattedCharSequence；可覆写 createNarrationMessage 屏蔽读屏原值，无需 setShouldMaskInput |
| GUI 布局与鼠标（F0 真机修复） | AbstractWidget.setRectangle(width,height,x,y)；Screen.resize(int,int) 走 repositionElements/rebuildWidgets。鼠标为 mouseClicked(MouseButtonEvent,boolean)、mouseDragged(MouseButtonEvent,double,double)、mouseReleased(MouseButtonEvent)，滚轮 mouseScrolled(double,double,double,double)；EditBox 可直接 addRenderableWidget |
| 客户端任务入队（F0） | `BlockableEventLoop.execute(Runnable)` 先 `wrapRunnable`，`scheduleExecutables()` true 时 schedule，否则 doRunTask；宿主用 Minecraft.execute 统一承接桥命令/流式工具/整轮回调，不能依赖任意回调线程直接操作网络或世界 |
| TicketType（1.21.11） | **无公开 `create(name, comparator, timeout)`**（那是更老版本/他映射的写法）；但 record 构造公开：`new TicketType(long timeoutTicks, int flags)`，flags=FLAG_LOADING/FLAG_SIMULATION/FLAG_PERSIST 等；自定义票类型无需 mixin/AW |
| `addTicketWithRadius(type, pos, r)` | 实为**单条票** `new Ticket(type, ChunkLevel.byStatus(FULL) - r)` 落在中心 chunk（半径靠 level 逐级衰减扩散，非逐 chunk 加票）；`addTicket(long,Ticket)` 对**同 type 同 level** 的已有票只 `resetTicksLeft()` 不新增——"每拍续票不撤"安全且幂等，多同伴共 chunk 互不抽干；超时递减在 `TicketStorage.purgeStaleTickets`（canTicketExpire 门），关闭时 `deactivateTicketsOnClosing`，无 PERSIST 不落盘 |
| `ChunkPos` 取块坐标 | 方法叫 `getBlockAt(int,int,int)` / `getMiddleBlockPosition(int)` / `getWorldPosition()`；**无 `getBlockPosition`**（凭记忆写会编译炸） |
| 假玩家 chunk 票 | **假玩家（FakeConnection）不入 PlayerMap/不发 PLAYER_* 票**（09-08 探针：任何 getBlockState 前同伴脚下 `hasChunkAt=false`）；`getBlockState` 强载的区块**拍尾无票即回收**（同秒内"探针 true/下拍工具 false"即此机制）；harness 用 `ServerChunkCache.addTicketWithRadius(TicketType.PLAYER_LOADING/PLAYER_SIMULATION, ChunkPos, r)` 持票，`removeTicketWithRadius` 释；PLAYER_* 无 FLAG_PERSIST，重启自清 |
| `Level.isLoaded(BlockPos)` | = `isInValidBounds(pos) && ChunkSource.hasChunk(x>>4, z>>4)`（**存在性**，不是状态≥FULL）；与 `hasChunkAt` 同源，两者对假玩家同时 false |
| 探针防污染 | 同一 LOG 行内参数左→右求值：`getBlockState` 在前会强载污染后面的 `isLoaded`——纯加载断言必须**单独成行且先于一切方块读** |
| `BlockHitResult.getType()` | **读私有 miss 标志：一个 BlockHitResult 对象可以本身就是 MISS**——判命中必须比 `getType()!=MISS`，不能只 `instanceof BlockHitResult`（R2-S1 javap 实测） |
| 客户端方块读 | `Level.getBlockState(BlockPos)` 声明在 `net.minecraft.world.level.Level`，`ClientLevel` **不覆写**→客户端可直接用；`hasChunkAt` 是 `LevelReader` 默认方法；`Direction.fromYRot(double)`/`CropBlock.getMaxAge()`/`BlockStateBase.hasBlockEntity()` public 可用（后者省逐格 BE 查表） |
| 假玩家进场 | `PlayerList.placeNewPlayer(Connection, ServerPlayer, CommonListenerCookie)`；cookie 用 `CommonListenerCookie.createInitial(GameProfile, false)`（record：profile/latency/ClientInformation/transferred） |
| 玩家存档加载（F0 第十三轮） | javap 字节码：placeNewPlayer 不读档；`PlayerList.loadPlayerData(NameAndId): Optional<CompoundTag>`（NameAndId 有 GameProfile 构造）。原版 PrepareSpawnTask 先解析 SavedPosition 选 level，Ready.spawn 在 place 前 `ServerPlayer.load(ValueInput)`，然后 snapTo；不是调用 place 就自动恢复 |
| 玩家输入与位置（F0 第十三轮） | `TagValueInput.create(ProblemReporter, HolderLookup.Provider, CompoundTag): ValueInput`；`new ProblemReporter.ScopedCollector(Logger)` 可 try-with-resources；`ValueInput.read(MapCodec<T>)`；`ServerPlayer.SavedPosition.MAP_CODEC` 返回 dimension/position/rotation 三个 Optional；`Entity.load(ValueInput)` 会读 Pos/Rotation，维度选择仍需在身体构造前完成；`snapTo(Vec3,float,float)` 公开 |
| 玩家保存出口（F0 第十三轮） | `PlayerList.remove(ServerPlayer)` 字节码先 `save(player)` 再移除身体/索引；save 调 `PlayerDataStorage.save(Player)`；`saveAll()` 遍历在线玩家同出口。读盘测试用 `NbtIo.readCompressed(Path,NbtAccounter.unlimitedHeap())` 与 `LevelResource.PLAYER_DATA_DIR` |
| 身体恢复工装（F0 第十三轮） | `Inventory.getSelectedSlot/setSelectedSlot`、getItem/setItem/getContainerSize；`ItemStack.matches(a,b)` 比较计数与 components，setDamageValue/getDamageValue；`ServerPlayer.teleportTo(ServerLevel,double,double,double,Set<Relative>,float,float,boolean): boolean` 用于双维度样本；Vec2.ZERO/x/y 均公开 |
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
4. 新召唤到主人附近/主世界出生点，读取旧背包但不沿用旧位置；重启由 SummonService 在 placeNewPlayer 前显式读取原版玩家存档（2026-10-10 更正，不再假定 place 自行加载）。
5. 名册在 `<世界目录>/mcbot/companions.json`（Gson）；同伴背包/位置存原版 `playerdata/<uuid>.dat`。dismiss 不删存档；重进在身体实际维度检查安全落点。历史 M1 的“重进成功”未验非空背包，身体恢复以顶部 F0 第十三轮对照证据为准。
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
| Gradle | 9.5.1，wrapper 走**官方源** `services.gradle.org/distributions/` | 09-10 起不再指向本机盘上的 zip（那会让别的设备构建不起来） |
| 构建 JDK | Temurin **21.0.10**，路径写在**用户级** `<GRADLE_USER_HOME>/gradle.properties` 的 `org.gradle.java.home`（仓内不写） | 换机器只需改仓外那一个文件；本机 PATH 上的 java 是 17，不能拿来编 MC 1.21.11 |
| 代理 | 127.0.0.1:7897（仓内 `gradle.properties` 的 systemProp 已配，并标注"本机网络相关"） | 换环境删 Proxy 两行即回退直连 |
| AI 执行者 shell | pi 的 `bash` 需在 `~/.pi/agent/settings.json` 配 `shellPath` 指向你的 git-bash | Git 若装在非标准路径，pi 默认扫不到；改完**必须重启 pi** 才生效 |
| 仓库路径 | 仓根可放任意位置（origin `https://github.com/qaqms/mcbot.git`，私有） | 文档一律用相对路径引用本仓；推送用一次性 token URL，token 不落 `.git/config` |

### 机器迁移记录（2026-09-08 10:20，hostname mio）

从远端拉到 `0c7f244`（本地原在 `7dc6f9f`，快进 5 个提交：`a9a4877`/`a9f4373`/`1459fa8`/`c3cca77`/`0c7f244`）。
当时 `a9a4877` 把构建配置改到了另一台机器（另一个用户名下的 `.gradle/jdks`、wrapper 指另一个盘），
而本机既没有那个用户目录也没有那个盘，两项都不可用，于是改回了本机路径——**这正是"机器相关路径
不该进仓"的教训来源**。09-10 已按这个教训收口：仓内不再出现任何本机绝对路径（见上方环境迁移记录与
`docs/DEVELOPMENT.md` §2 的换机三步）。

证据：`./gradlew build` → **BUILD SUCCESSFUL in 30s**（18 任务，16 执行/2 最新）；仅“过时 API”提示，无编码告警。
单测 XML 核对：agent-core **19 例**（BridgeService 5 / Conversation 6 / AgentLoop 5 / OpenAiCompat 3）
+ path DigAStarTest **8 例** = 27/27，**0 failures 0 errors 0 skipped**。本轮零代码改动（纯构建配置），
故未跑无头 SelfTest；下次动服务端代码按惯例补 `[m4*]`/`[m8]` 级验收。
| 运行约束 | TaskStop 杀不掉 javaexec 子进程 → 用 tools/list-java.ps1 找 PID 再 taskkill | 见上节 |

## 目录结构（现状）

```
<仓库根>/
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

- **Clean-room**：允许从**参考项目（主人本地的只读副本，路径不入仓）**读**机制与设计动机**；
  禁止复制/翻译其任何代码、注释文本、README 句子、资源；禁止使用 "Numen/言出法随" 名称与美术。
  机制一手参考：Carpet 同版本分支（假玩家）、Baritone 公开文章（寻路思想）、
  arXiv 2410.08500（空间字符网格）、OpenAI 协议文档（function calling/SSE）。
- 验收不达标不进下一里程碑；一次只推进一个里程碑。
- 桥接（M6）只绑 127.0.0.1 + 随机 token；服务器侧三道闸（尺寸/速率/Schema + owner 校验）
  必须随 M3 一起落地，不许"先跑通再补安全"。
- API 签名一律以 javap/编译器为准（见"1.21.11 API 实测字段笔记"），别信任何人的记忆，包括我的。
