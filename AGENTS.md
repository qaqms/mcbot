# AGENTS.md — 给 AI 执行者的开工指令

你在接手 **mcbot**（LLM 驱动的 Minecraft 同伴框架）。开工前按序读完：

1. 本文件（纪律）
2. `docs/DEVELOPMENT.md`（环境、验收 harness、红线）
3. `STATUS.md`（唯一进度事实源；含 **1.21.11 API 实测防漂移表**）
4. `docs/ARCHITECTURE.md`（as-built 结构）；动手范围相关的
   `docs/TOOLS.md` / `docs/BRIDGE.md` / `docs/ROADMAP.md` 再看

## 当前阶段（2026-10-10，维护者确认）

- 先开发 MC agent 本体，以离线单元测试、替身集成测试和本地 HTTP/SSE 契约回归为主。
- 暂不启动 `runClient` / `runServer` 做真实游戏验收；真实烧制、Mixin 加载、
  身体动作、保存重进与连接器联合验收保留待验，后续按维护者安排执行。
- 当前卡先完成实现与离线验证；离线通过和真实行为验收分别记账，不因暂缓实测停止开发，
  也不把替身测试写成真实游戏通过。下方 SelfTest 流程供恢复游戏验收时使用。

## 硬红线（违反任何一条 = 停）

- **Clean-room**：可从他仓读机制动机，禁止复制/翻译任何代码、注释文本、README 句子、
  美术、专有名称。他仓（含参考项目）永远只读不抄。
- **密钥**：LLM API key 只存在于所属玩家客户端 `mcbot/client.json`；永不写入代码/文档/日志/
  聊天/仓库/`.git/config`；桥只绑 127.0.0.1 + token；服务器三道闸不许绕行或"先跑通再补"。
- **验收**：不达标不进下一步；一次只推进一个里程碑；证据（时间戳+日志判读）写进 STATUS。
  无头验收只走 SelfTest（autotest.flag），**禁用控制台 stdin**（该 harness 连原版命令都吞错）。
- **API 签名**：1.21.11 映射漂移极多，别信任何记忆（包括本文）——javap 实测，
  新事实补进 STATUS 防漂移表。
- **多设备可移植**：这份仓要在多台设备上接着干，所以**受版本控制的文件里不许出现机器相关的
  绝对路径**（盘符路径、某个用户名下的目录、某台机器的代理端口）。本机专属设置写进
  **用户级** `<GRADLE_USER_HOME>/gradle.properties`（仓外）；文档引用本仓用相对路径、
  引用参考项目写成"开发环境中的只读副本（路径不入仓）"。提交前自查一遍（见
  `docs/DEVELOPMENT.md` §1.1 的三步换机法）。
- 构建输出/日志在 Windows 上是 GBK，管道先 `iconv -f GBK -t UTF-8` 再判读。
- TaskStop/杀进程后检查孤儿 java（`tools/list-java.ps1`），否则 `session.lock` 卡死下次启动。

## 工作节奏

- 改动 → 相应离线回归 → `./gradlew build`（含 agent-core 单测）→
  更新 STATUS（证据+偏差）→ 有远端则按 docs/DEVELOPMENT.md §5-5 扫密后提交推送。
- 当前阶段按上方安排暂缓真实游戏验收；恢复后，服务端改动补 `[m4*]` 级 SelfTest。
- 加原子工具看 `docs/TOOLS.md` §4；动桥看 `docs/BRIDGE.md`；排期争议以
  `docs/ROADMAP.md` 当前卡为准，改排期须在 STATUS 记录理由。
- 与用户对话用简体中文；代码注释解释"为什么"，不复述"是什么"。
