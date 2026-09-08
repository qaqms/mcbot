# R1 寻路整改 · 设计卡（09-08 定稿）

> 病灶与证据见 STATUS「09-08 三线整改侦察定案」。本卡 = Plan 代理设计 + 主会话复核修正。
> **复核改动了两处卡的论断**（见 §0），以本卡为准。

## 0. javap/读码实测（主会话亲自复核过的）

- `LevelReader.getChunk(II)` default = `getChunk(x, z, ChunkStatus.FULL, /*create=*/true)`
  → **未加载区块上调 `getBlockState` 会同步加载/生成区块**（代理卡"同步加载"半句成立）。
- `Level.getBlockState`：仅**垂直越界**返回 VOID_AIR（`isInValidBounds` 分支）；未加载**不会**
  读成空气——代理卡"VOID_AIR 洞"**归因错了**，真正的洞在下面这条：
- **`LevelDigSampler.passable()`（:53-59）没查 `isLoaded` 就 `getBlockState`** → 搜索伸进未加载
  区时在主线程触发同步生成（分帧救不了它，尖峰藏在 relax 里）。`digSeconds()`（:65）有 isLoaded
  防护、`inBounds`（:141-148）只管 Y 与半径。**这是本次新确认的独立病灶，并入 B。**
- `ServerLevel.getChunkNow(II)→LevelChunk|null`（不加载）✓；`ChunkAccess.getBlockEntitiesPos()`✓；
  `Registry.getId(T)`✓；`BlockPos$MutableBlockPos` 存在（顶层 MutableBlockPos **不存在**）✓。
- 单价实测（读 `DigAStar` 常数与 `relax`）：正交 1.0、纯垂直 1.0、平面对角 1.4、(1,1,1) 1.4
  → 每消 1 曼哈顿单位最低 **1.4/3 ≈ 0.467**；现 h=0.95×L1 高估达 2 倍——"可采纳"注释为假坐实。

## A. 启发：显式加权 A*（否决"重推可采纳下界"）

- 否决理由（代理卡论证，采纳）：可采纳下界必须假设挖价=0（总能声称绕行）→ h 与地形无关
  → 必挖区零梯度，山体仍球形扩散，**治不了病根**。
- 采：`h = W × 0.467 × L1 + goalEntryCost`，**W=1.8 起**（1.5 备选）；`goalEntryCost` =
  目标柱 27 格的最小真实入柱价（脚+头挖价+放价，搜索开始算一次）。
- 注释公开取舍："故意不可采纳，代价上界 ≤ W×最优"；回执可写"最多比最优贵 80%"。
- 构造参数增 `hWeight`；新 getter：`expanded()/memoHits()/staleCount()/partial()/partialCoord()`。

## B. 惰性 memo 快照（否决全盒预采集）

- 否决理由：全盒 129×65×129 ≈ 108 万格，预采集本身就单帧冻结，实碰只有数百格。
- 形状：`SnapshotDigSampler` 装饰 `LevelDigSampler`；格粒度 `long → 2B`
  （1B 类别 PASS/SOLID/UNBREAK/FLUID/LAVA/SACRED/UNKNOWN + 1B 量化挖秒 ÷0.25s）
  + chunk 粒度 BE 位置集（替掉每格 `getKey` 反查 + `getBlockEntity` 现查）。
- **一致性取舍（明说）**：快照**只当启发式，不当承诺**——NEED_CONFIRM 清单与执行期复核本来
  就用 live 读，"搜旧世界、执行新现实"可接受。三护栏：
  ① 每片抽 8 格与 live 比对，不符丢该格 `stale++`；`stale>24` → 新失败码 `WORLD_CHANGED`，
     本拍重开，绝不用旧图执行；
  ② **UNKNOWN（未加载）一律不可通行**——同时修掉 §0 的 passable 同步加载洞
     （isLoaded 判空走 UNKNOWN 分支，不再 getBlockState）；
  ③ 回滚面 = 不套装饰器。
- 内存：搜索盒收到 `min(64, 距离+32)`；硬帽 `MEMO_MAX_CELLS=262144`，超帽**停 memoize 退回直读**
  （不失败）。

## C. 部分提交（撞帽 ≠ 失败）

- 降级终点 = 已扩展节点中 h 最小者（expand 时增量维护，不扫全表）。
- 判据三分：`open` 空 → `NO_PATH`（真封闭，**永不降级**）；帽到且 L1 改善 ≥4 → `PARTIAL`；
  帽到但改善 <4 → `NO_PROGRESS`（不再冒充 BUDGET）。
- 回执模板：`PARTIAL:未能到 (tx,ty,tz)。已走到能到的最近点 (px,py,pz)，离目标还差 d 格。
  这一段已走完，请从该点重发 move_to（可分多段）；本段动了 N 个方块。`
- 铁交互：PARTIAL 含挖/放且未授权 → **NEED_CONFIRM 优先**（不绕闸）；半程仍受 maxDigs/背包
  存量约束，不重置。

## D. 真分帧重规划 + 抑抖 + 双帽

- `Phase` 加 `REPLAN_SEARCH`：复核失败进该相位，回 `searchTick` 同一分帧出口，
  **删除 `PathTask:250-252` 的同步 while**；重搜后的 NEED_CONFIRM 复用首搜出口（不复制逻辑）。
- 抑抖选**旧路径格位 ×0.7 降权**（复核失败处坐标不入降权集）；不复用 open/best——旧 g/f 建在
  旧地形上，复用会带进失效代价，风险大于收益。
- 双帽：节点帽 8000 留兜底 + 墙钟帽 `SEARCH_SLICE_MS=6`/拍、`SEARCH_TOTAL_MS=400`（防单帧尖峰；
  TPS<20 服取值留观察，见 ROADMAP 问 5）。

## E. 测试矩阵（JUnit，假 sampler）

① `mountain_w1_vs_w18`：3D 实心山体 16 格外目标，展开数 ≤ 旧 1/4 且出路径；
② `budget800_partial`：PARTIAL 命中、h 单调改善；③ `sealed_basalt_notPartial`：仍 NO_PATH；
④ `memo_equivalence`：500 随机格 memo 与直读全等；⑤ `snapshot_staleness`：中途改天 →
WORLD_CHANGED 且不出 path；⑥ `gain_below_floor → NO_PROGRESS`；⑦ 现有 8 例零改动 = 语义不破。
无头闸：`[m8]` A/B/C/D 不回退；新增 `[path]` 展开/命中/陈旧计数日志行。

## F. 实施步序（每步独立验收）

S1 纯算法（A+C+getter+用例①②③⑥）→ `./gradlew build`；回滚 W=1.0。
S2 采样器 memo（B+用例④⑤，含 passable 的 UNKNOWN 修正）→ build + `[m8]` 无头。
S3 PathTask（D + PARTIAL 文案）→ build + `[m8]`；真机山体看 PARTIAL 命中且无单帧冻结。
S4 常数进 STATUS §10 与 ARCHITECTURE §10 对账。

**新常数**：H_WEIGHT=1.8｜H_UNIT=0.467｜PARTIAL_MIN_GAIN=4｜STALE_SAMPLE=8/片｜STALE_MAX=24｜
MEMO_MAX_CELLS=262144｜DIG_QUANT=0.25s｜SEARCH_SLICE_MS=6｜SEARCH_TOTAL_MS=400｜
BIAS_REUSE=0.7｜SNAPSHOT_PAD_XZ=32
