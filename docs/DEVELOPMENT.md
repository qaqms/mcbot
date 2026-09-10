# 开发上手（环境 / 构建 / 验收 / 纪律）

> 给接手施工的人或 AI。先读这份，再读 `STATUS.md`，动手前扫一眼本文 §5 纪律。

## 1. 工具链（版本钉死，勿凭记忆改动）

| 项 | 值 | 备注 |
|---|---|---|
| Minecraft | 1.21.11 | 混淆时代；loom **remap** 变体 |
| Loom | `net.fabricmc.fabric-loom-remap` 1.17.20 | 普通 loom 在此版本不可用 |
| Loader / Fabric API | 0.19.5 / 0.141.6+1.21.11 | |
| 映射 | `loom.officialMojangMappings()` | 注意：`Identifier` 不叫 ResourceLocation 等命名坑见 STATUS 防漂移表 |
| JDK | Temurin 21 | 路径写死在 `gradle.properties`（换机器必改 `org.gradle.java.home`） |
| Gradle | 9.5.1 | wrapper 指向本地 zip（换机器要改回官方 URL） |
| 代理 | `gradle.properties` 内 systemProp 127.0.0.1:7897 | 不在墙内可删该段 |

`run/` 是开发运行目录（gitignore）：`eula.txt` 预置 true；`run/mcbot/` 放名册/配置/flag。

## 2. 常用命令

```bash
./gradlew build                 # 全量：agent-core 测试 + mod 编译 + remapJar
./gradlew :agent-core:test      # 只跑大脑层单测（报告 agent-core/build/test-results）
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
2. 判读基准都在日志行前缀里（`[m3]`/`[m4]`/`[m4b]`/`AUTOTEST`），STATUS 各节有逐条"期望值"。
3. ⚠️ **不要用控制台 stdin 做验收**：经 gradle 管道喂命令连原版 `list` 都报
   "unexpected error" 且吞堆栈——harness 缺陷，与代码无关。一切自动化验收走 SelfTest
   （它直接 `dispatcher.execute`）。
4. 收服：`gradlew --stop` 放 daemon；若 `run/world/session.lock` 卡下次启动，
   是孤儿 java——`powershell -File tools/list-java.ps1` 找 `MC-RUN` PID 再 `taskkill //PID x //F`。

## 4. 真实端到端（客户端侧）

开发服直连 `runClient`，或发测试包：`dist/` 两 jar 放进任意 1.21.11 Fabric 实例 `mods/`，
进 `localhost:25565`，G 面板填 key → 召唤 → `@bot` 对话（详见 `dist/README-DIST.md`）。
桥接自测（进世界后）见 `docs/BRIDGE.md` 底部清单。

## 5. 施工纪律（红线）

1. **Clean-room**：可以从任何同类开源项目读机制与设计动机，**禁止**复制/翻译其代码、
   注释文本、README 句子、美术与专有名称。机制的一手参考：Carpet（假玩家）、
   Baritone 公开文章（寻路思想）、arXiv 2410.08500（空间字符网格）、OpenAI 协议文档。
2. **密钥红线**：API key 只存主人客户端本地（`client.json`），永不进服务器/日志/聊天/仓库；
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
