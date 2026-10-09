# 路线图与债务清单

> 里程碑总览见 `mcbot-DESIGN.md` §11；本文件是**当前排期的执行卡**。
> 当前开发顺序（2026-10-09，经维护者确认）：**基础闭环 F0（先固定任务桥契约）→ MC 基础真机验收 → 连接器联调**。
> 原 R1/R2/R3 排期保留为历史与后续增强，不在基础验收之前扩展游戏技能。
> 原序 M7/M5 仍成立但降为二线：侦察把寻路撞帽/轮次爆炸/伪功能三项定位成**已确证的真缺陷**，
> 且它们直接阻塞 M7 首亮体验；改道理由全文见 STATUS「09-08 三线整改侦察定案」节。

## F0：MC agent 基础任务闭环（2026-10-10 当前卡）

模块范围：mcbot 提供 MC agent 本体与任务级桥接接口；N.E.K.O 适配连接器负责宿主侧任务接入与反馈。
模块间以 `docs/BRIDGE.md` 的契约协作，可独立完善内部实现；兼容变更与联合验收按其 §0/§6 执行。
游戏聊天/CLI 为可选调试入口，不增设独立聊天功能。参考项目仅只读调研，不复制代码；宿主不在本轮改动范围内。

**本轮收尾（2026-10-10）**：按维护者决定暂不继续功能开发，先同步文档、验证并提交当前检查点。
F0 整体未关账；恢复开发时先补单人完整背包/整客户端重启，再验真实移动/挖掘、
ACCEPT/PARK 取消、反问与活动任务重载/重连，最后进行连接器联合验收。
执行中 wait 取消与真实拾取已有正证，不重复作为首要补测项。

- [x] 任务编号贯穿排队/启动/终态；current_task 不被后来的排队项覆盖。
- [x] 桥窗口只认匹配任务的 done；新增 completed/failed/cancelled/superseded 状态。
- [x] 按编号取消；模型/工具/反问等待立即收尾；旧回调不得恢复旧任务。
- [x] 断线/配置重载先关闭旧 loop；pending 与问题清理；ask 按任务配对。
- [x] 串行工具派发及同步/异步异常回执；CLI 多轮等待修复；回归测试与完整构建。
- [x] 首轮真机暴露的阻塞项修复：配置重载 32 字符截断、模型 HTTP 错误不可诊断、G 面板遮挡。
  三页/滚动/固定操作区与长输入保留已落地；模型页可测试独立流式工具往返，修复包待重测。
- [x] 第二轮真机修复：名册按世界隔离、全局 receiver 不再捕获首个世界；
  生命周期结果在同伴页显示，状态/扫描按钮直调只读工具，不烧模型调用。
- [x] 15:30–15:35 真机获得必要面板/静默提示、同进程 A→B 新世界隔离与正常退出、
  两轮模型连接测试及三次真实模型只读任务闭环正证，详见 STATUS。
- [ ] 模型流内 error 帧具体原因：三次 HTTP 200 但 errors=1，不按成功验收；
  15:59 已安装错误安全分类 + 实际请求结构/工具配对摘要包（新增 22 例、252/252 全绿），
  16:05–16:07 真机 12 次 HTTP 全通过（四个实际只读任务 + 两次两轮连接测试），
  新摘要接线与工具配对零异常；没有失败样本，历史间歇错误留观察、不据此销账。
  23:53:54 自然复现 request=9：HTTP 200/SSE、errors=1、UPSTREAM（来源 TYPE）；
  此前任务已于 23:52:13 取消，迟到错误未恢复旧任务。工具配对结构摘要零异常，
  具体根因仍未取得，不重复盲测远端；不能据本地上游分类宣称提供商内部原因已定位。
  身体位置/背包恢复另需专项验证；流式统计接线修复独立记录如下，不视为模型根因已解决。
- [x] 流式统计真机复核（23:48–23:54）：安装包哈希与修复包一致，
  纯文本/工具/最终汇报共 9 条统计有效，区分 ready 与实际 early；
  first_dispatch/stream 取得“早于网络完成约 790ms”及“晚于完成 1–9ms”两类样本。
  模型等待期间取消后旧流不发布统计，新任务正常闭环；详见 STATUS 第十一轮。
  该轮 wait 已结束后才取消；执行中的 wait 取消由下方 10-10 补测单独确认。
- [x] 执行中 wait 取消（2026-10-10 01:03–01:08）：两次实际中止，
  服务端计时 15748ms/749ms，均确认“已叫停”；迟到取消回执被丢弃，
  观察超过 65 秒无旧任务续跑，后续纯文本新任务正常。详见 STATUS 第十二轮。
  不替代 move_to/break_block 的 ACCEPT/PARK 取消验收。
- [x] 身体位置/非空背包服务端受控恢复（10-10 01:22–01:29）：
  先取得 `identity/disk=true、body/position/rotation/inventory=false` 红证；
  javap 确认 1.21.11 placeNewPlayer 不加载玩家存档，补入场前加载、存档维度选择及实际维度安全检查。
  同一份旧流程保存样本在两个新进程中，主世界/下界坐标、朝向、六槽物品/耐久/selected 全匹配；
  遣散再召唤同 UUID/旧背包/新召唤落点均通过。详情及本地日志见 STATUS 第十三轮。
  完整服务端动作回归通过（已知 `[m9] A3` 仍红），283/283 JUnit 全绿，不计入新 SelfTest 场景数。
- [ ] 单人客户端恢复复核：安装第十三轮修复包后，记录非空背包和安全位置，
  正常保存退出并重进，对比状态/背包；不以服务端无头结果替代单人客户端验收。
  10-10 02:03 同进程世界退出/重进已获状态级正证：位置、4/36 非空占用与手持原木一致；
  尚未逐槽核对其他三样物品/数量、朝向/耐久/副手或完整客户端重启，不将完整项关账。
- [x] 真实模型拾取闭环（10-10 02:02）：scan_area → collect → 最终汇报，
  四样物品各 1，后续 status 确认背包从 0/36 到 4/36。详见 STATUS 第十五轮。
  同进程共 8 次模型请求均 HTTP 200/SSE 正常；一次 ttfb=34552ms 后成功，
  当前模型链路可用，不销除第十四轮连接超时/解析异常或历史 UPSTREAM 根因。
- [ ] MC 剩余基础真机验收：真实移动/挖掘、ACCEPT 长活取消、反问回答、
  配置重载/保存期间任务清理、断线重连。
- [x] 任务桥 v1.0 固定：对齐文档/实现、共享 Schema/示例、会话隔离、参数/错误与本地 HTTP
  回归；17:00 提交前复核补 Unicode Schema 边界，272/272 自动化全绿（累计新增 20），
  新 dist 包已产出，游戏实例未替换。提交 `7e10502` 已推送至功能分支并快进合并至 main，
  本地与远端 HEAD 已核验一致；提交过程见 STATUS。
  接入与兼容规则见 BRIDGE §0/§6；这是外部接口基线，新包真机/连接器仍待验，不代表 F0 整体通过。
- [ ] 连接器联合验收：task/progress/question/answer/done/cancel，不以游戏聊天为验收目标。

本卡不包含 scheduler 抢占、剩余路线续跑、progress 限速、合成/冶炼或完整 R3 面板增强。
2026-10-09 真机测试发现面板重叠影响基础验收，因此将必要的布局修复纳入 F0；HUD/反问专用区等仍后置。
服务端三道闸、单槽 BUSY 与既有游戏工具不改；`[m9] A3` 历史红项另卡排查。
诊断小债：迟到回执日志当前将取消/会话清理也写成“超时”，应改中性文案；
late_results 增加不单独证明超时帽配置错误。10-10 补测已确认该措辞不改变取消行为。
自动化证据与真机限制见 STATUS 顶部；连接器接入契约见 `docs/BRIDGE.md`。

## R0 小清账（穿插偿还，不开新战线；均已到行号自证）——✅ 09-08 12:40 关账

〔原排期；当前优先执行上方 F0 卡。〕

- [x] `McbotMod` tickCount 永不归零 → 开发工装漏入生产。**修法：过 60 拍后归零重计**，
      Files.exists 从 20 次/秒降到 1 次/3 秒；兼顾"跑起来后才补 flag"用法（检测延迟 ≤3s）
- [x] `BreakBlockTool` 注释澄清："留两拍"其实**是对的**（前两拍 running、第三拍回包），
      审计代理 off-by-one——只把注释写到不可误读，行为零改动（累计四处代理结论被主会话复核推翻）
- [x] 桥 `/v1/ask` 60s→**135s**（必须 > 反问 120s，否则 neko 先 504、大脑空等后答案塞进已作废 future）；
      MCP `mcbot_ask` 描述同步；新回归用例钉住（见下）
- [x] `Conversation.noteCompacted()` 补 `realPromptTokens=0` + **判别性单测**
      `compactedClearsRealTokenGateSoItCannotFireTwiceInARow`（水位 6000/40→110 小消息/真数 9000：
      压后估算 ≈1500 且条数 >8，熄火只剩"旧真数作废"一个变量）；**变异检验：拆修复恰好挂这 1 例**
- [x] ARCHITECTURE §10 同步（ask 135s、桥池 8；ask_owner 300→120s 早些时候已修）
- [x] 桥池 4→8 + 注释（SSE 不占线程的彻底解法归 R2-C）
- 验收（12:39–12:40 无头）：build 绿；ConversationTest 7/7；`[m5a]` 四条全中；`[m4b]`
  `busy=true cancel=true 空槽=false`；`[m8]` A 需确认=true 清单 1 格/B 到达/C 箱未动/D NO_PATH；
  MC 侧 0 异常；停服后无孤儿 java（只剩 daemon）。另新增漂移记录：`CompanionScheduler:95` 文案
  "60 秒"实为 move 帽 3600tick=3min——归 R2-C 一并修（它要动同一段代码）。
- [ ] `CompanionScheduler:95` 超时文案硬写"60 秒"而 move_to 帽 3600tick=3min（主会话已证；R2-C 一并修）

## R1 寻路整改 ✅ 无头全线收尾 09-08 16:31（S1–S4+S3b；真机山体待合并会话；靶子：16 格山地撞 8000 帽已拆；设计卡：`docs/plan/R1-pathfinding.md`）

- [x] **S1 纯算法（09-08 13:44 关账）**：加权 h（W=1.8×H_UNIT=0.467×**距体积** L1 + 入柱价，
      注释公开"故意不可采纳"）+ 部分提交 API（budgetReached/partialAvailable/partialPath，
      PARTIAL_MIN_GAIN=4）+ expanded() 等 getter；**对外失败字符串契约零改动**（BUDGET/NO_PATH
      不动，PARTIAL 话术归 S3）。校准实据：山体用例 w1.0=519 vs w1.8=242 展开（2.1× 聚焦）；
      150 帽下 partial 真实可用。用例①②③⑥入，DigAStarTest 12/12；现有 8 例**零改动仍绿**；
      无头 `[m8]` A 确认=true 清单 1/B 到达/C 未动/D NO_PATH、`[m4]/[m4b]/[m5a]/[m3]` 全不回退，0 异常。
      意外发现：合成山体没复现真机爆炸（旧口径 519≪8000）——真机撞帽必然叠加了**未加载区
      同步生成/昂贵现查**因素，S2 memo+UNKNOWN 才是主刀，S1 的聚焦是减常数那半。——设计卡 §B 优先级上调
- [x] **S2 memo 快照（09-08 14:12 关账）**：`MemoDigSampler`（每格存 pass 类别+量化 dig 秒；
      容量帽 262144 格超帽退直读不失败；每 256 miss 验尸 8 格、累计不符越 24 → worldChanged
      丢图重开≤ 2 次再退裸读）+ `liveify`（搜索完成后裸 sampler 按当前世界重建清单——
      **NEED_CONFIRM 清单永远现算真值，快照只当启发式**）+ 堵上 passable/support/placeable
      缺 isLoaded 的同步生成洞（UNKNOWN=墙）+ sacred/Registry 集合预烘。单测④⑤⑥：
      memo 等价 3×500×4（实查 1440/命中 4560）、验尸 24→25 越限+判坏后短路、超帽降级；
      path 系 15/15、全量 20/20 绿。**新世界** `[m8]` 全中（A 需确认=true 清单 1 格最优解
      「踩脚挖头」/B 到达/C 箱未动/D NO_PATH，验尸不符=0，memo 去重 4.9:1），0 异常。
      ⚠关账前踩坑入册：旧 dev 世界连测多轮后 A 跑出"需确认=false 挖 0 绕行"——真因是历史
      测试挖穿点堆在走廊封闭段（侧墙只封 x=7..12）之外的绕行洞，**非 S2 回归**；新世界受控
      实验一次定性。教训入 DEVELOPMENT §3：**`[m8]` 判读必须在未挖穿的干净世界**（旧世界已删）。
      预烘数组大盒/双帽时间预算/搜索框收窄——那些归 S3（撞帽主因已由 memo+UNKNOWN 拆掉，
      大盒收益边际，不预先支付 9MB 常驻）。
- [x] ~~新发现 passable 缺 isLoaded~~（S2 已堵，UNKNOWN=墙，见上）
- [x] ~~h 修正~~（S1 已做：显式加权 A*，注释公开取舍）
- [x] ~~世界快照 + memo~~（S2 已做，等价性由单测④钉死）
- [x] ~~部分提交~~（S1 已做算法侧：budgetReached/partialAvailable/partialPath；对模型的
      PARTIAL 话术归 S3）
- [x] ~~回归用例~~（①②③⑥随 S1、④⑤⑥随 S2 入册；山体聚焦实据 w1.0=519 vs w1.8=242）
- [x] **重规划真分帧 + 双帽 + PARTIAL 话术（S3，09-08 15:52 关账）**：`PathTask.replan` 同步
      while 冻结点已杀（新相位 REPLAN_SEARCH 与首搜共用分帧出口）；旧路格降权 ×0.7 抑抖
      （只往未来取、失效格周围不入集；单测⑨原路复现 cost 恰 ×0.7、expanded 143→29）；
      双帽：节点 8000 + 累计 CPU 400ms，单拍另 6ms 切片（单测⑧）；PARTIAL/NO_PROGRESS
      话术入 PathTask，真 NO_PATH 永不降级。新世界全红定性：假玩家无 chunk 票 + m4 残留
      污染场景（expanded=1 是正确算法行为），**非 S3 回归**；harness 持票+净带修复，
      证据见 STATUS「R1-S3 关账证据」。
- [x] **R1-S3b（09-08 16:31 关账）：同伴正式持票**——`CompanionChunkPads`：自定义超时票
      （40t，LOADING|SIMULATION，无 PERSIST）5×5 垫子，END_SERVER_TICK 每拍续（排在
      scheduler 前，防自锁死），只续不撤=零释放代码/零抽干互踩；**不设 owner 在线闸**
      （同伴独立跑长活是卖点，代价≤25chunk/同伴）。`[m9]` 三断言全中 + `[m8]` 纯产品票
      dogfood 不回退；机制思路经只读调研取得（clean-room，javap 坐实参考写法在 1.21.11
      本不可移植），三组新 API 事实入 STATUS 漂移表。
- [x] **S4 常数对账（09-08 16:05）**：全部 R1 常数已进 ARCHITECTURE §10「寻路(R1 后)」行
      （双帽 8000/400ms、切片 6ms、h=1.8×0.467+入柱价公开不可采纳、PARTIAL gain≥4、memo 帽
      262144、验尸 256/8/24、DIG_QUANT 0.25s、降权 0.7）；TOOLS §3 词汇表换血：
      BUDGET_EXCEEDED 不再对外（降为内部串），新增 PARTIAL/NO_PROGRESS 两行及期望行为；
      债务表销"16 格山地撞帽"旧账（转真机终验）+ 立"假玩家无票→R1-S3b"新账。
- 验收：同一山体用例修复前后对比（展开数、降级是否命中）；`[m8]` 四场景不回退（新世界口径）
      ——S1–S3 均已按此口径验收；真机山体 PARTIAL/无单帧冻结归最终合并真机会话

## R2 延迟整改（靶子：实测 8 轮/任务 = 20–50s；跳数仅 100–150ms 不是主因；
**设计卡：`docs/plan/R2-latency.md`**，性价比序 D>C>B>A）

- [x] **真流式 + 早派发（S3，09-10 关账）**：`LlmClient` 弃 `ofLines()`，自实现 `BodySubscriber`
      按字节增量解码+按行切分（跨 chunk 半行/UTF-8 残缺序列/CRLF 都自己管）；`TurnSink` +
      `TurnTimings`；`StreamingTurnReader` + `ToolArgsScanner`（**两道人门**：顶层括号配平
      **且** schema required 齐——只看配平会把 `{"x":1}` 半截参数当成品派发）；`AgentLoop`
      早派发（就绪即在回调线程 execute；记账按 index 原序**顺序折叠**，不是 `allOf`）。
      `SseIncrementalTest` 11 例（真 HTTP 200ms 帧距）+ `ToolArgsScannerTest` 21 例 +
      `AgentLoopEarlyDispatchTest` 4 例；全量 **111/111 绿**；**三次变异自证**（早派发失效/
      记账改 allOf/required 门失效 → 各自精硬红）。⚠ 偏差：本轮未跑无头 SelfTest
      （只动客户端大脑层、`src/main` 零改动，且 harness 验不了真流式）→ 下次动服务端必补。
      注：设计卡写的是 `fromLineSubscriber`，实施时因"订阅者/结果容器两层泛型冲突"
      改为自实现约 40 行 BodySubscriber（语义更明确且可单测，理由见 ARCHITECTURE §5）
- [x] **前缀稳定打穿缓存（S2，09-08 17:0x merge 0ed915b；客户端接线 09-10 还清）**：
      `foldCheckpoint`+`frozenFolded`（决定一次算定永不重算，折叠只作用检查点前的 Msg.Tool，
      outboundHistory 纯函数，FOLD_KEEP_TAIL=12）；PromptBuilder 实例缓存（AtomicReference，
      启动读盘一次，`reloadSkills()` 钩子留给 R3）；压缩挪链尾 finishChain+两处合法 prefix reset
      计数/回调。ConversationPrefixTest 7 例字节级断言；主会话独立变异抽检（砍单调保护→精硬红）。
      偏差 4 条见 STATUS。**客户端接线欠账已在 R2-S3 一并还清**（AgentRunner 真接上
      PromptBuilder 实例 + `onStreamStats`/`onPrefixReset` 打点）
 - [x] **长动作受理即回执（S4）阶段 1（09-10 关账）：lastPlan 复用**——`path/PlanCache`
       （按同伴分槽，判据＝同伴+目标+**起点**+TTL 30s，授权态刻意不入键，复用时清单按当前世界
       `liveify` 重算）；`PathTask` 的"计划就绪"出口抽成 `afterPlanReady`，让"搜出来的"与"复用的"
       走同一条判定与话术路径；未命中打 `未复用 lastPlan（原因）` 便于长期观测命中率。
       无头 `[m8]` B 那趟实测 `memo=reuse(未搜索)`（真的没重搜）且清单逐格一致。偏差 4 条见 STATUS。
 - [x] **S4 阶段 2（09-10 关账）：受理即回执 + PARK**——信封 `job_ack`/`job_event`
       （`progress|done|failed|cancelled|superseded`，单终局契约）；`ServerTool.acceptanceMode()`+
       `capTicks(args)`+`acceptSubject(args)`（cap 只有一个来源，根治"服务端 180s/客户端 90s"）；
       `move_to`/`break_block` 走 ACCEPT，其余六条 SYNC；大脑新增 `Ledger`（按 index 升序只写
       "已到达"的最长前缀，**受理不算已到达**）与 PARK（挂起不再问模型、不计步）；
       客户端 `PendingJobs<T>` 两段式等待（受理后上限＝cap×50ms+15s）；PARK 铁律
       （解锁前必须给每条 in-flight 补合成回执，否则下一请求 400）；`accept_mode` 止血开关。
       协议契约进 `docs/BRIDGE.md` §5.1，教学进 `PromptBuilder`/`ClientToolDefs`。
       单测 145/145（新增 PARK 7 + PendingJobs 7 + JobEnvelope 6）；无头 `[r2c]` 策略/文案/契约六项全中；
       `[m4b]`/`[m8]`/`[r2d]` 无回归。偏差见 STATUS（本卡**未做**抢占与 progress 帧）
 - [ ] **S4 阶段 3（未完）**：抢占——`sched.submit(...,preempt)` 顶掉旧活并向旧 seq 发
       `phase=superseded`；`PathTask.remaining()` + lastPlan 存"剩余节点"（阶段 1 的缓存判据可原样复用）
       → 新任务 target 逐格相同就续节点；`generation` 代际号（丢旧事件）；`phase=progress` 真正
       接进 `BridgeEvents` 并限速（1 帧/s/job、全局 4 帧/s，Ring 200）。**落地后**才把 `[m4b]`
       判读基准从"BUSY 拒收"改写成"顶替"
 - [ ] S4 附带：`[brain] llm stream` 补 `t_send`/末行/`wire_rtt`/本地间隙四项打点（阶段 1/2 未动它）
- [x] **感知给可行动信息（S1，09-08 17:3x merge bbd09ea）**：`scan_area` 换 classify 词表
      （container/ore/rock/workbench/farm/hostile，ROCK_PATHS 常数集不含泥土沙）、绝对坐标
      `@(x,y,z) d3.2`、首行八向朝向、三层摘要（近 1/中 2/远 3，名额 8/10/12，MAX_SAMPLES=900）；
      客户端准星注入 `[我此刻盯着]`（≤120B，MISS/ENTITY 不注入，本地拼不过网络）。
      拆出 5 个零依赖 common 类+26 新单测；无头 `[r2d]` 三项+结构三项全中（证据 STATUS）。
      偏差 5 条（中环按路径归组/名额轮转/竖直窗口/ore 收窄/StatusTool 不动）已复核接受
- [x] 打点补齐（S3 已落一半；F0 2026-10-09 修接线）：`ttfb`/`ttft`/`first_tool`/`after_chunk`/
      `after_tool`/`chunks`/`deltas` 已进 `[brain] llm stream`；`ready` 与实际 `early` 分列，
      `first_dispatch`/`stream` 区分派发与传输结束，23:48–23:54 新包真机接线已复核；
      **t_send/末行/wire_rtt/本地间隙仍缺**，归 S4 一并，不因本轮修复销账
- 验收：同一任务（挖三块石头进箱）改前后轮次数与总时长对比，用 `[brain] step tokens` + 新打点作数
      （归 S4 后的合并真机会话；S1/S2/S3 已各自无头口径收口）

## R3 面板可控性整改（目标：补齐游戏内任务控制入口；**设计卡：`docs/plan/R3-panel.md`**）

- [ ] 反问闭环进面板：醒目横幅 + 作答输入框 + 120s 倒计时（`latestQuestion` 暴露给 UI）
- [x] 工具回执/护栏进 transcript（2026-10-09 F0 真机修复，`onToolInvoked`/`onNotice` 补 record()）
- [ ] 当前步数进 transcript
- [ ] 常驻 HUD（不进面板也看得见在干什么/等答/失败）
- [x] 「查状态」不再烧整轮 LLM（2026-10-09 F0）：直调服务端 status，保留真实身体读数；
  扫描附近同样直调 scan_area。本地桥状态仍由 statusJson() 提供。
- [ ] `BridgeEvents:15` 单 sink → 多播（面板与桥同源订阅）——面板获得进度流的前置
- [ ] 热重载拆级：persona/skills 零重建（supplier 已就位，`reconfigure` 别再一刀切）；
      端点/模型类改动才重建，且面板**显式告知**「会清空对话」
- [ ] 护栏/超时可调化（40/3/5/90s/120s 进配置，带校验与默认）；MCBOT_* 静默覆盖加提示
- 验收：游戏客户端真机测试，仅使用面板完成「召唤→指派→插话→改主意→应答反问」全链路

## M6 收口：桥接活体联调 ✅ 已关账（23:44，证据见 STATUS）

- [x] 按 `docs/BRIDGE.md` §6 清单跑通 7 项（401/状态/task/事件流/补发/MCP/反问闭环）+ cancel

## M4.5：上下文经济学 ✅ 已完成（00:30，证据见 STATUS）

- [x] usage 真数驱动压缩 + 每步 token 日志（deepseek/openai 缓存两方言）
- [x] 切分铁律（轮边界/User 优先/绝不断 Tool 配对）+ CJK 感知估算 + 失败熔断
- [x] 过期回执出站折叠（存储全量/出站瘦身两视图）；打转判定改"同调用且同结果"
- [ ] P2：压缩水位 6000 的数值校准等新 jar 上线后的 `[brain] step tokens` 真数曲线

## M7：neko 首亮（桥已验证可用，主要是 neko 侧）

- [ ] neko 侧接 MCP（推荐，工具即得）或 REST+SSE；端点+token 从 `bridge.token` 读
- [ ] 首亮场景验收："用户让猫娘使唤矿哥挖三块石头进箱子"——
      任务投递 → 猫娘旁白 progress 帧 → 同伴 `ask_owner` 反问 → 猫娘代答 → done
- [ ] 反向通道预留：同伴事件（done/question）能触发猫娘主动说话（SSE 消费者）

## M5：感知与记忆（感知格式由 M7 场景倒逼）

> 注（09-08 改道）：本卡的**闸①已关**；「字符网格/event 生产者」两项与 R1/R2 重叠，
> 实做时并入 R2（感知可行动化、受理即回执）一起推，勿双开同一条目的两张卡。

- [x] **闸① 加固：C2S 显式尺寸闸** ✅ 09-08 11:11 无头关账（`[m5a]` 四条全中 + WireSize JUnit 5 例）。
      做实的是“按 **UTF-8 字节**判”而不是按字符数（旧写法用 `json.length()` 比 32K，
      中文一字三字节等于把上限抬到 ~96KB）；入站超限返哨兵不报错（报错=踢线），
      出站超限换合法瘦身回执（截断=非法 JSON=对端白等 90s），客户端另加发送前自检。
      附带：修了 `[m8]` 验收场景本身的地形依赖（补砌侧墙），详见 STATUS。
- [ ] **语义字符网格**：自我中心朝向相对（arXiv 2410.08500 适配方块世界），
      替换 scan_area 世界轴朴素版；真·“以面朝方向为前”
      ——注意：网格一回执体量就变大，设格式时先按 `WireSize.MAX_BODY_BYTES=32765` 算账（分批取数）
- [ ] `craft`（配方查询+合成）、`smelt`（熔炉放取+火候，配 wait 用）、`inspect_block`
      （不开 GUI 读容器/机器内容）
- [ ] 对话 **JSONL 持久化**（跨重启/跨世界）+ 压缩产物落盘
- [ ] `event` 生产者：任务级进度（挖了第几块/走到哪）S2C 播报 + 桥转发
      ——补"有管道没水源"最后一截
- 验收：无头 m5 场景（craft 一把石镐全流程）+ 真机"熔 5 个铁矿"自主完成

## M8：DigAStar（DESIGN §8 全套）——无头 ✅（22:38）+ 真机 ✅（23:44）

- [x] 加权 A*（跳跃/坠落/搭路/垫脚/下挖动作集），预算 8000 节点/128 次可挖/300 节点每拍
- [x] `may_alter_terrain` 确认流：路径要挖/放时把会动的方块列给模型点头才走（NEED_CONFIRM 回执）
- [x] 执行期成本复核（每 20 节点验未来 5）+ 自动重规划≤2 次；神圣集（容器/床/机关/方块实体+其支撑、岩浆邻接、起点脚下）
- [x] 纯算法核入 DigAStar（零 MC 依赖）+ 根工程 JUnit 8 例；无头 [m8] A确认/B挖穿到达/C箱子神圣/D基岩NO_PATH 全中
- [x] 真机验收（23:41–23:44）：真实山丘 NEED_CONFIRM→ask_owner→授权挖 2 格登顶；复杂地形两次 BUDGET_EXCEEDED 教学回执生效；扫出 coal_ore×7
- 已知边界/债：挖子机与 BreakBlockTool 重叠 ~40 行未抽公共；无自动换工具；斜穿不挖角落；流体不可排；TPS 假设 20；
  ~~8000 帽在 16 格山地就撞（真机实锤）~~ → **R1 已整改**（加权 h 2.1× 聚焦 + memo 4:1 去重 +
  UNKNOWN 墙 + 双帽 PARTIAL 降级），无头全绿；**真机山体 PARTIAL/无单帧冻结待合并真机会话终验**
- 已知边界/债：斜穿不挖角落；流体不可排；TPS 假设 20；**假玩家无 chunk 票（09-08 探针实锤）
  → R1-S3b 待做**：不修则野外孤伴寻路全读 UNKNOWN；harness 已用 PLAYER_* 票绕过

## 债务清单（穿插偿还，勿开新战线时盯着这里）

| 债 | 来源 | 去向 |
|---|---|---|
| 真实多人服长时驻留未测（现只测过 localhost） | M1–M4 单人 | 找公网服跑一轮 |
| 加入/退出消息未隐藏（假玩家会广播 join/leave） | M1 偏差#1 | 拦 PlayerList 广播，装饰性 |
| dismiss 后 `.dat` 清档策略未定 | M1 偏差#5 | M5 一并定 |
| 多玩家名册/每位玩家多个同伴（v1 每位玩家限 1 同伴） | 设计 v1 约束 | 视需求 |
| ~~Cli harness done 闩锁一次性~~ | M2 | F0 已修，每轮独立等待；3 例回归 |
| 同伴死亡→复活到所属玩家身边（现无敌免死） | M4 未含战斗 | 战斗里程碑一起做 |
| 桥一台机一个（多客户端撞 57121） | M6 边界 | 端口可配化（下轮顺手） |
| ~~游戏内收到 ask_owner 反问后玩家无回答入口~~ 已清：`@bot 答 <文本>`（M4.6，待真机验）+ 超时降 2min | M6 真机 | — |
| 非 ASCII 桥测试：git-bash curl 发中文请求体乱码（服务端 UTF-8 无罪） | M6 真机 | 约定测试脚本用 python urllib 发（已入 STATUS） |
| 工具 10/14（见 TOOLS.md §2） | M5/M8 | 随卡走 |
| 闸① 已在 32765B：`scan_area` 换字符网格后 32 半径回执可能撞上限 | M5.1 | 到那步走“分批取数”，**不得改松这道闸** |
| `[m8]` 验收场景曾依赖基准点周围自然地形封死（本机不封→A/B 假失败） | 09-08 首跑实锤 | 已修：SelfTest 大道补砌两层侧墙，使“唯一路线=挖穿”与地形无关 |
| 测试 JAR 不随源码仓库分发（gitignore）；本地产物版本需核验 | 换机 | 真机测试前运行 `./gradlew build` 生成当前版本；已有产物记录见 STATUS |
