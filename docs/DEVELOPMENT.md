# 开发上手（环境 / 构建 / 验收 / 纪律）

> 面向项目维护者、贡献者与自动化开发工具。环境配置见本文，当前进度见 `STATUS.md`，开发约定见 §5。

## 当前验证安排（2026-10-10）

维护者确认当前只推进开发，以离线测试为主：使用单元测试、可控模型/网络/时钟替身、
实际 runner/桥接线与临时回环 HTTP/SSE 验证，不调用真实模型。
暂不通过 `runClient` / `runServer` 启动真实游戏验收；本文保留的服务端工装与真机流程
供后续维护者安排时使用。构建及离线通过只证明对应实现范围，真实烧制、Mixin 实际加载、
身体动作、保存重进和连接器联合验收仍单独挂账，不以未安排实测阻断当前实现与离线验证。

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
  判读基准：`A 需确认=true 且清单≥1` / `B 工装明确批准具体清单=true，随后到达=true 且墙位被打通=true` / `C 箱子分毫未动=true` /
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

### 3.1 身体位置与非空背包恢复（独立双进程）

入口仍由 SelfTest 检查，不经过模型或控制台 stdin。**仅用于独立开发存档**：
先在 `run/server.properties` 设置未使用的 `level-name=mcbot-persistence-<测试名>`、
回环 `server-ip=127.0.0.1` 与空闲端口。普通世界名会被工装拒绝。
不要与 `autotest.flag` 同时使用，后者会清背包、改地形，污染恢复样本。

1. 创建 `run/mcbot/autotest-persistence-seed.flag`，运行 `./gradlew runServer`。
   工装只允许在无 seed 标记、无同名样本的世界写入：主世界 `(37.5,90,-42.5)`、
   下界 `(53.5,90,29.5)`，各铺安全平台，设置确定朝向与六个非空槽位
   （圆石 23、耐久损耗 17 的铁镐、原木 11、钻石 3、金胸甲、副手火把 7），选中槽 4。
   `[f0-persist] SEED PASS` 后自动 `halt(false)`，等正常保存与 Gradle 进程结束。
2. 保留 seed 日志；**测试旧包失败路径前先复制整个开发世界**，因为旧包重进后的空身体
   会在下一次正常保存时覆盖原 `.dat`。复制须在停服后完成，不能复制正在写入的世界。
   seed 标记在该世界的 `mcbot/persistence-fixture.txt`，不在实例级名册旁。
3. 同一开发世界创建 `run/mcbot/autotest-persistence-verify.flag`，再起一个新进程。
   工装先读取实际 UUID `.dat`，核对存档维度/坐标/朝向/背包；
   再独立核对当前在线身体的身份、相同状态及全部空/非空槽位。
   两名同伴都通过后，才测试遣散再召唤：相同 UUID、按显式召唤语义到主世界出生点、
   保留旧背包。之后恢复样本姿态并正常停服，允许再次创建 verify flag 做第三进程复核。
4. 判读：每名同伴 `result` 的 identity/disk/body/position/rotation/inventory 全 true；
   `resummon` 的 sameUuid/freshSpawn/inventory/matches 全 true；最后 `VERIFY PASS`。
   **Gradle BUILD SUCCESSFUL 不等于专项通过**，必须检查上述日志，无 `FAILED`/`VERIFY FAIL`。
   两种 phase flag 同时存在会拒绝执行；phase flag 都自删，且本工装无需 autotest-stop.flag。

该验收不替代单人客户端保存重进、同进程换世界，也不覆盖坐骑或在途末影珍珠恢复。
没有 phase flag 时，不生成 seed 标记、不修改身体或地形。

### 3.2 背包明细与主手切换（独立工装）

仅使用未用过的 `level-name=mcbot-inventory-<测试名>` 开发世界、
回环 `server-ip=127.0.0.1` 与空闲端口。创建 `run/mcbot/autotest-inventory.flag` 后运行
`./gradlew runServer`，SelfTest 自动进入专项，flag 自删，结束后 `halt(false)` 正常保存停服。
工装拒绝普通世界、已有同名 fixture 及同时存在的普通/身体恢复测试 flag。
此专项会写测试物品，不能在玩家世界执行，也不需要客户端、模型或控制台 stdin。
超平坦开发世界需同时提供包含 layers/biome 的 generator-settings，不能只改 level-type。

判读 `[inventory-test]` 各场景均为 true 且最终 PASS，无 FAILED。
使用真实 CompanionPlayer/Inventory/ItemStack 验证空背包和 43 槽映射、逐槽反馈、
只读性与超长自定义名称下的回执尺寸、快捷栏选择、完整堆栈往返交换、
满背包/空主手、非法参数/空槽拒绝、忙时不可切换但可读取、取消后可切换。
每次交换对照全部槽位的计数与 components，不以“主手名字相同”替代保全检查。
普通 `[m4*]` 回归需另用新开发世界与 `autotest.flag` / `autotest-stop.flag`，
不能混跑污染专项；既有 `[m9] A3` 红项仍按 §3 历史边界判读。

客户端离线接线与参数回归可单独运行：

```bash
./gradlew clientTest --tests com.neko.mcbot.agent.AgentRunnerInventoryTest --tests com.neko.mcbot.server.tools.EquipToolArgumentsTest
```

离线接线使用可控模型和游戏回执替身，验证 inventory → equip → 最终汇报的调用配对、
明细进入下一轮模型历史、BUSY 不被自动重发及实际工具 Schema。
隔离服务端专项不验证玩家客户端/多人可见装备更新、模型理解或连接器联合验收。

### 3.3 合成（独立工装）

只允许未使用的 `level-name=mcbot-craft-<测试名>` 开发世界，回环监听与空闲端口。
先将 `tools/fixtures/crafting-pack/` 整个复制到该世界的 `datapacks/crafting-pack/`，
再创建 `run/mcbot/autotest-craft.flag` 并运行 `./gradlew runServer`。
fixture 数据包不在 mod 资源内、不应安装到玩家世界；它提供不同于物品 ID 的配方 ID、
重叠 planks 标签/精确 oak_planks 材料及 7 个金粒的产量，用实际加载配方验证，
不依赖在代码里替换 RecipeManager。min/max_format 94.1 来自当前 MC JAR 的 version.json。
flag 自删，专项结束 halt(false) 正常保存停服，不需要客户端、模型或控制台 stdin。
工装拒绝旧 fixture 名册与普通/背包/恢复测试 flag 并存；超平坦仍须包含 layers/biome。

判读 `[craft-test]` 每项 true，最终 PASS，且无 FAILED/ERROR。
覆盖只读查询与数量取整、原木/木棍/石镐、制作并放置工作台的连续流程、
工作台缺失/超距、分散材料/标签、数据包重叠候选与不可重复消费、可用替代配方、
蛋糕空桶返还、满背包/组件合并/返还物溢出、后续批次失败整批不改背包、
装备/选中槽保全、非法参数/错配及非合成配方拒绝、忙时执行拒绝/查询可读、取消后可合成。
普通动作回归须另用新 `mcbot-action-*` 世界，不装 fixture 数据包。

```bash
./gradlew clientTest --tests com.neko.mcbot.server.tools.CraftToolArgumentsTest --tests com.neko.mcbot.agent.AgentRunnerCraftTest
```

离线测试运行实际 runner/loop，以可控模型和网络回执替身检查 Schema、query → craft →
inventory 的参数/回执配对、材料信息可见及缺料不自动重发。真实服务端专项直接运行实际工具，
不替代玩家模型驱动、GUI/统计/成就事件、真实多人或连接器联合验收。

### 3.4 熔炉/高炉/烟熏炉的离线回归

```bash
./gradlew clientTest --offline --tests com.neko.mcbot.server.tools.SmeltToolOperationsTest --tests com.neko.mcbot.server.tools.SmeltToolArgumentsTest --tests com.neko.mcbot.agent.AgentRunnerSmeltTest
```

3 项参数测试覆盖 query/load/take、槽与数量边界、错模式字段拒绝；
5 项实际 runner 测试覆盖模型 Schema、load → wait → query → take → inventory
的调用/回执配对、NOT_READY/BUSY 不自动重发、等待/装料回执未到时取消与迟到结果隔离。
20 项操作测试只初始化 MC 注册表，不创建 MinecraftServer/世界/假玩家或运行 tick；
执行生产工具的同一套参数、守卫、load/take 预检/提交及反馈逻辑。
使用真实 ItemStack/SimpleContainer 和原版三类配方的 assemble/产量检查，
世界范围/距离/加载、机器槽权限、配方选择、燃料时长和当前计时由可控依赖提供。
覆盖三类机器、锁与未加载拒绝、同槽原料/燃料不可重复消费、
缺料/非法燃料/满槽/满背包失败保全、组件/耐久/装备保全、空炉备燃料、
整次取出回退、返还桶回收、容器堆叠上限、重载配方时间估算及回执尺寸。
BUSY 由可控观测驱动，runner 取消使用真实 AgentRunner/AgentLoop；
不把夹具释放 busy 写成真实 scheduler/服务器停手证明。
桶样本由测试直接准备，只证明回收/拒作燃料，不证明原版已经产生返还桶。
tools/fixtures/smelting-pack 是独立开发数据包，当前尚无对应 SelfTest 专项入口，
不安装到玩家世界，不加入生产资源。真实烧制、燃料桶返还、组件保全、失败原子性、
Mixin 实际加载、机器保存重进和普通动作回归暂缓，分别挂账；
本卡实现/离线阶段完成，PR 保留 Draft 等待上述真实行为证据，不因此停止后续开发。

### 3.5 物品守恒离线回归

```bash
./gradlew clientTest --offline --tests com.neko.mcbot.server.ItemTransfersTest --tests com.neko.mcbot.server.tools.TransferToolOperationsTest --tests com.neko.mcbot.server.tools.CollectToolOperationsTest --tests com.neko.mcbot.agent.AgentRunnerItemTransferTest
```

仅初始化 MC 注册表；真实 Inventory(null, EntityEquipment)、ItemStack、SimpleContainer
及无世界的 ChestBlockEntity 参与测试，不创建玩家/掉落实体、世界或服务器。
执行生产 ItemTransfers、transfer/collect 参数、守卫、物品移动及反馈；
身体位置、加载、锁/有效性和 scheduler.busy 使用可控观测，拾取实体写回/删除使用替身。
覆盖双向半满/满载/重复调用守恒、低容量/组件堆叠限制、槽及接触面拒绝、
按组件合并/损耗工具保全、选择/装备不变、不能借装备槽、后续允许物品继续搬运、
参数拒绝、锁/未加载守卫顺序、实际数量进入 feedback 与实体余量。
共享 receive 以真实背包验证部分掉落只回传余量；两条挖掘生产调用点编译验证，
不将此写成已运行真实破坏或落地。
runner 使用实际 AgentRunner/AgentLoop 和可控模型/C2S 回执，验证部分完成反馈、
无自动重发、BUSY 与取消/迟到结果隔离，不调用模型端点。
任意模组菜单/setter、双箱整体与阻挡开启、真实容器/实体/掉落、身体停手、
保存重进及普通游戏动作回归仍待安排；历史 F0/冶炼/区块票待验项保留。

### 3.6 挖放语义离线回归

```bash
./gradlew :test --offline --tests com.neko.mcbot.path.DigCostSafetyTest
./gradlew clientTest --offline --tests com.neko.mcbot.server.BlockMiningTest --tests com.neko.mcbot.server.BlockPlacementTest --tests com.neko.mcbot.server.PathMaterialsTest --tests com.neko.mcbot.server.tools.BlockToolOperationsTest --tests com.neko.mcbot.agent.AgentRunnerBlockActionsTest
```

C2 新增 39 项：路径代价 2、共享挖掘 15、放置 8、垫料 4、工具参数/计时任务 6、
实际 runner 4。仅初始化注册表，真实 Inventory(null, EntityEquipment)、ItemStack、
BlockState 与完整组件参与处理；世界/权限/挖掘速度/原版动作返回与目标变化、
掉落实体余量写回均通过受控 Access。没有世界、服务器、身体或真实物品实体。
破坏回调损耗原堆栈的样本只证明动作边界与次数，不是原版耐久已经实测。
水/可替换格/半砖样本提供观察结果，只证明工具不预先限制为空气且正确记账，
不证明原版实际放置、朝向、组件落地或多格钩子已经运行。

覆盖无采收资格先拒绝、目标/选槽/数量/耐久/组件变化停手、实时速度、零硬度/
有界停滞、裂纹清理/取消、原版返回成功但目标未移除、失败且已移除的实际副作用、
旧掉落不动、新实体只处理一次、部分余量组件保全、不再求战利品或二次生成。
放置检查完整来源堆栈/面/准确目标透传、恰好一件消费、误报成功拒绝、
失败副作用、装备/选中槽保全、特殊类拒绝及忙时不动世界。
垫料预算与执行选择一致，命名/新增或移除组件不消耗；纯算法测试实际运行
DigAStar 与 MemoDigSampler，不可行哨兵/NaN/无穷/负值不能穿过唯一障碍。
坐标严格性同样覆盖 move_to 使用的共享解析器。
runner 实际运行 AgentRunner/AgentLoop，ACCEPT 等终态后再给模型掉落反馈、
WRONG_TOOL/BUSY/部分放置不自动重投，受理前/挖掘等待期间取消忽略迟到成功并补齐历史。
服务端 Task 的延迟终态和取消通过直接 tick 验证，不将 runner 取消写成真实身体停手证明。

两条路径调用点及原版 API 编译/字节码核验，当前未运行真实 PathTask 世界遍历。
真实采收/附魔/耐久、裂纹显示、掉落/经验、朝向/碰撞/水中/替换/多格/组件放置、
路径真实支撑与材料消耗、保存重进及普通动作回归均待维护者安排。
3×3×3 已加载邻域不是任意模组钩子的沙盒，远处/延迟掉落和任意模组副作用不在保证内。
统一授权/资源互斥/异常收尾见下节 C3，桥 v1.0、F0、冶炼及 `[m9] A3` 待验继续保留。

### 3.7 调度与权限离线回归

```bash
./gradlew :agent-core:test --offline --tests com.neko.mcbot.agentcore.loop.AgentLoopParkTest --tests com.neko.mcbot.agentcore.loop.AgentLoopLifecycleTest --tests com.neko.mcbot.agentcore.loop.TaskPolicyTest
./gradlew :test --offline --tests com.neko.mcbot.server.ActionPermissionsTest --tests com.neko.mcbot.task.ResourceLocksTest
./gradlew clientTest --offline --tests com.neko.mcbot.task.CompanionSchedulerTest --tests com.neko.mcbot.server.ServerActionGateTest --tests com.neko.mcbot.agent.AgentRunnerPermissionsTest --tests com.neko.mcbot.server.tools.CollectToolOperationsTest
```

C3 新增39项（agent-core 5、根工程12、clientTest22），并修改旧并发假设回归。
实际 AgentLoop/AgentRunner/CallbackChatEngine 执行同轮受理与终局依赖、早派发、
只读无目标越界拒绝、完整服务端确认清单、明确/否定回答、授权回执等待、
取消/迟到结果隔离与历史配对；模型/客户端队列/C2S/S2C 均为可控依赖，
不调用模型、不启动 Minecraft、桥协议仍由既有契约回归验证。

真实 CompanionScheduler 使用 Bodies 边界提供可控在线/丢失观测，
TickTask 真实 tickSafe/年龄/终态/异常/清理计数；无世界任务不会访问 null 身体。
测试执行生产 ServerActionGate、ResourceLocks 和 ActionPermissions 的同一实现，
覆盖身体/容器/挖放区域冲突、只读查询可用、正常/异常/超时/取消清理，
清理自身抛错仍完成唯一终态、派发抛错/异步异常停任务、状态/目标/操作变化、
缓存/重规划清单越权、被补回格不能二次挖、撤销/超时/回拨/伪造/重复授权与范围上限，
同UUID新身体不能继承旧任务，以及换维度在下次动作 tick 前中止。
不把可控 Bodies 写成真实假玩家生命周期或物理停止已验。

collect 操作继续用真实 Inventory/ItemStack；候选通过受控 GroundItem，
另直接验证生产 eligibility 谓词的存活/延迟/target/加载/双距离条件。
私有 target 的只读 Mixin accessor 仅编译，未加载 Mixin 或创建真实实体。
PathTask 的生产调用点、逐格检查、下一落脚格变化重规划与更新的 m8 工装编译验证，
没有运行真实世界遍历、清单确认网络链路、原版多人/机器 ticker 或第三方模组并发。
完整强制构建和实核 XML 证据见 STATUS；真实行为待维护者安排，不以离线通过销账。

## 4. 真实端到端（客户端侧）

开发服直连 `runClient`，或使用本次构建的 `build/libs/mcbot-0.1.0.jar`，
另行准备对应 Fabric API，两 jar 放进任意 1.21.11 Fabric 实例 `mods/`。
`dist/` 保留部署说明、不提交 JAR；不得把该目录的历史包当作最新构建。
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

连接超时与慢响应须分开判读：

- `http=0` 是未取得 HTTP 状态的本地占位，不是服务器返回的状态码；
  `bytes=data=0` 且 `ttfb=-1` 表示没有响应头/流数据证据，不是服务返回了空 SSE。
- 默认连接建立限制为 15s；任务请求 timeout 为 180s，模型页独立连接测试请求为 30s。
  约 15 秒失败且没有响应头，强烈符合连接阶段超时；没有异常子类型时不推断具体 TCP/TLS 原因。
  响应头等待超过 15 秒后仍成功并不矛盾，不能因此判连接限制失效或改大工具回执帽。
- DNS/TCP/无凭据 HEAD 只能帮助排查端点与网络；根路径 403 不等于实际模型鉴权失败，
  后续成功也不能事后证明旧请求连接了哪个 IP 或网络故障已经永久消除。
  不固定旧 IP、不关闭证书校验、不自动重发可能已派发游戏动作的请求。

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
`task` 单请求使用真实提示词/技能/当前工具定义，只统计响应，不执行任何游戏工具；
`connection` 最多两请求，只使用本地 connection_probe 回执。会产生实际 API 用量，
不得加入 build/定时任务或自动循环。它读取磁盘配置，不模拟启动器的环境变量覆盖与游戏历史。

### 4.2 反问回答离线回归

```bash
./gradlew clientTest --tests com.neko.mcbot.agent.AgentRunnerQuestionTest
```

该测试直接运行 AgentRunner 与 AgentLoop，以可控模型 future、时钟和客户端副作用替身
验证问题事件、回答续跑、任务配对、取消/超时清理、配置重载和关闭。
120s 超时通过推进测试时钟验证，不等待真实两分钟；配置只保存在测试内存，
不读取或写入玩家配置，不调用模型 API，不启动游戏/服务器或 HTTP 监听器。
REST/MCP 问答错误通过实际 BridgeService 与 AgentRunner 接线验证；
BridgeEventCapture 只挂载实际 BridgeEvents 总线，测试结束移除。
该结果不替代玩家回答、Minecraft DISCONNECT/JOIN 和外部连接器联合验收。

### 4.3 活动任务重载与重连离线集成

```bash
./gradlew clientTest --tests com.neko.mcbot.agent.AgentRunnerLifecycleTest --tests com.neko.mcbot.agent.ClientSessionLifecycleTest
```

AgentRunnerLifecycleTest 运行实际 AgentRunner/AgentLoop/CallbackChatEngine，
控制客户端回调队列与模型 future，覆盖重载/关闭期间各类等待项与迟到回调隔离，
以及检查显示、配置禁用和取消/新工具发送顺序。
ClientSessionLifecycleTest 运行 McbotClient 使用的实际 ClientSession 处理函数、
RunnerBackend、BridgeHttp/BridgeService，通过临时回环端口与测试令牌验证 HTTP/SSE
会话编号、事件重建、任务取消/续投与旧桥命令拒绝；监听器/客户端在测试结束关闭。

配置保存仅记录到测试内存，C2S 发送仅记录信封，不读写玩家配置、令牌或世界，
不调用模型端点。该结果不证明真实 Fabric JOIN/DISCONNECT 事件触发、
断网后的服务器身体停手或连接器联调通过。失败复现报告可另存到本地 build 目录，
不能仅以完整构建成功代替 XML 的 failures/errors/skipped 核对。

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
   推送使用既有凭据管理器或 GitHub CLI credential helper，不将令牌拼进远端 URL、
   命令参数、文档或日志。环境变量凭据失效时，可仅在该次进程临时去掉覆盖，
   检查已有登录；不删除或覆盖用户的凭据存储。

## 6. javap 防漂移速查

```bash
# loom 缓存里的官方映射合集 jar（文件名含 minecraft 版本号，用 ls 找最新的）：
MCJAR=$(ls ~/.gradle/caches/fabric-loom/*/minecraft-*-merged*.jar 2>/dev/null | tail -1)
javap -cp "$MCJAR" net.minecraft.server.level.ServerPlayer | grep placeNew
javap -cp "$MCJAR" net.minecraft.network.protocol.common.custom.CustomPacketPayload
```

编译器永远是对的、javap 永远比记忆可信。把新学到的真实签名补进 STATUS 表格。
