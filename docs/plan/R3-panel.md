# R3 面板可控性整改 · 设计卡（09-08 定稿）

> 靶子：**桥（外部进程）能做的，坐在屏幕前的主人反而不能**（证据见 STATUS 09-08 节）。
> 代理卡 + 主会话复核；关键 API 均 javap 实证（fabric-rendering 新版 API 在 remapped
> fabric-api jar 里查过）。

## A. 事件总线 fan-out（一切前置）

- `bridge/BridgeEvents.java` 单 sink → in-client 总线：`CopyOnWriteArrayList<Sink>`，
  attach/detach → add/remove，publish 逐 sink try/catch。**BridgeHttp 降为订阅者之一**
  （入环+SSE），新增第二订阅者"面板镜像"：只进 `synchronized ArrayDeque`，**不碰 GUI**。
- 线程契约：生产者 = HttpClient 线程 + 主线程；Sink 必须 O(1) 非阻塞；GUI 动作在
  `Screen.tick()`（已证存在）/render 于渲染线程抽干——§2 "Rendersystem called from wrong thread"
  教训的制度化。JsonObject 消费即快照、禁改。
- 帧类与 R2 对齐：progress/done/question/state 四类 + 另立 `usage`；**R2 的
  受理即回执/进度镜像复用此总线**（同源，不另造）。
- **命名裁决（主会话定）**：类名沿用 `BridgeEvents`（STATUS/ARCHITECTURE/BRIDGE.md 已引用，改名
  是文档债不是收益），但 javadoc 写明它现在是**总线**不是桥属物；桥只是它的一个订阅者。
- 验收：M6 桥七项清单不回归 + 面板收到同帧。

## B. 热重载拆级（diff 驱动）

- 实证：`AgentRunner.start()` 的 supplier 每步现读 `cfg.persona`——persona 本可零重建，
  被 `reconfigure()` 一刀切毁掉（对话清零+挂起反问作废）。
- 新 `applyConfig()`：diff `base_url/model/api_key/brainEnabled`——未变 → 仅存盘+换 cfg
  （对话保留）；变了 → 重建，但**面板先弹确认**："将清空对话 + 作废 N 条挂起反问"。
- skills 失效钩子：调 R2-B 的 `reloadSkills()`（两卡冲突裁决见 R2-B，不再每步读盘）。
- `MCBOT_*` 覆盖检测：`ClientConfig.load()` 记录被覆盖字段集，面板黄条
  "X 由环境变量覆盖，改面板无效"。

## C. 反问闭环进面板

- `QuestionRecord` 补 text；暴露 `questionSnapshot()`→(qid, text, deadline=at+120s) 与
  `answerLatest(text)`（直调现有 `answerQuestion`，**不靠"答 "前缀格式**）。
- 面板：品红横幅 + 专用作答 EditBox（常建、`setVisible` 切换——已证）+ 逐 tick 倒计时刷新；
  聊天入口保留（双通道）。
- 验收：面板作答 → 桥 `/v1/status` 的 `pending_questions` 1→0。

## D. transcript 与观测

- `onToolInvoked/onNotice` 补 `record()` **人话行**："工具 break_block 成/败 + 回执首行截 120 字"、
  "护栏 nudge@3"；JSON 参数只进日志（模型侧 convo 仍全量——给人看的与给模型看的分层）。
- `onUsage` 进 volatile + 本地步数计数（submit 清零、每 usage +1；AgentLoop 无 getter，
  口径 ±1 属已知风险，少动 agent-core）。
- 顶部常驻条：`模型 | 步/40 | 等答 | prompt/cached token | 挂起工具数`。
- **"查状态"按钮改直读本地 `statusJson()` 入 transcript，零 LLM**（现在它发自然语言烧整轮）。
- token 延迟打点归 R2-A，面板只消费。

## E. 布局演进（两档）

- **最小改动版（本整改采用）**：现有 `y+=38` 硬排**撑得到 A–D 全部落地**——横幅/黄条/状态条
  全走 render 直绘（`GuiGraphics.fill/enableScissor` 已证），零新 widget。
  transcript 滚轮 = 覆写 `mouseScrolled(double,double,double,double)`（接口默认在，Screen 是否
  已覆写**实施前补验**）+ scissor 裁剪。
- 结构整理版（后置）：`rebuildWidgets()` + LinearLayout/GridLayout；label 用
  `addRenderableOnly(MultiLineTextWidget)`（构造已证）替 no-op Button 伪装；EditBox 直接
  `addRenderableWidget`——**AbstractWidget implements Renderable 已证，手动 render 循环可废**。

## F. 常驻 HUD（次优先）

- 新 API（javap remapped fabric-rendering-v1 实证）：
  `HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("mcbot","status"), HudElement)`；
  `HudElement.render(GuiGraphics, DeltaTracker)`；可 `attachElementAfter(VanillaHudElements.CHAT,…)`
  锚位。旧 `HudRenderCallback` 仍在（deprecated 与否未证——用新的）。
- 最小方案：仅"任务进行/等答"时热 bar 上方一条
  `同伴: break_block 3/40 · 等答 42s`；空闲、开面板、hideGui 即隐；读 A 的镜像，天然渲染线程。
- 与 actionbar/其他 mod 冲突的配置开关：先钉"可关"，进护栏配置一起落。

## G. 步序（每步独立验收；GUI 无头测不到 → 真机清单集中一次换包）

1. 纯逻辑：A+B+C 访问器 + D record/查状态 + 护栏常量（40/3/5/90/120/6000 进 ClientConfig，
   带默认校验；EditBox `setFilter` 纯数字）。验收 = build 绿 + 单测全绿 + `[m3]/[m4b]/[m8]` 不破。
2. 面板 GUI 最小版（A 订阅 + 横幅/作答框/倒计时/状态条/黄条/确认框/滚动）。
   验收 = 编译绿 + **真机清单①**。
3. HUD + 结构整理版。验收 = 编译 + **真机清单②**。

真机清单①：查状态本地即回；改 persona 不清对话；改 model 弹重建确认；反问横幅+倒计时+作答框；
滚轮翻史；设 `MCBOT_MODEL` 现黄条。②：HUD 可见不打扰（含 hideGui/F3）。

**新常数（进 §10）**：倒计时口径=复用 QUESTION_TIMEOUT_MS；人话行截断 120 字；护栏配置默认
40/3/5/90s/120s/6000；HUD 仅忙时显示。

## 开放问题（已裁决项写明，未裁决项待主人）

1. 总线命名 → **裁决：沿用 BridgeEvents，javadoc 正名**（见 A）。
2. skills 每步读盘 vs 缓存 → **裁决：R2-B 缓存+`reloadSkills()` 钩子，R3 调钩子**。
3. 护栏值持久化进 client.json 还是仅运行时 → **待主人**（持久化=重启保留、语义与 env 覆盖要对齐；
   建议持久化）。
4. HUD 冲突开关 → **裁决：先钉可关**。
5. 桥 `/v1/ask` 60s 与反问 120s 不齐（R0 项）→ **裁决：并入 R0 顺手修**，不占本卡步序。
