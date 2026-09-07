# AGENTS.md — 给 AI 执行者的开工指令

你在接手 **mcbot**（LLM 驱动的 Minecraft 同伴框架）。开工前按序读完：

1. 本文件（纪律）
2. `docs/DEVELOPMENT.md`（环境、验收 harness、红线）
3. `STATUS.md`（唯一进度事实源；含 **1.21.11 API 实测防漂移表**）
4. `docs/ARCHITECTURE.md`（as-built 结构）；动手范围相关的
   `docs/TOOLS.md` / `docs/BRIDGE.md` / `docs/ROADMAP.md` 再看

## 硬红线（违反任何一条 = 停）

- **Clean-room**：可从他仓读机制动机，禁止复制/翻译任何代码、注释文本、README 句子、
  美术、专有名称。他仓（含参考项目）永远只读不抄。
- **密钥**：LLM API key 只存在于主人客户端 `mcbot/client.json`；永不写入代码/文档/日志/
  聊天/仓库/`.git/config`；桥只绑 127.0.0.1 + token；服务器三道闸不许绕行或"先跑通再补"。
- **验收**：不达标不进下一步；一次只推进一个里程碑；证据（时间戳+日志判读）写进 STATUS。
  无头验收只走 SelfTest（autotest.flag），**禁用控制台 stdin**（该 harness 连原版命令都吞错）。
- **API 签名**：1.21.11 映射漂移极多，别信任何记忆（包括本文）——javap 实测，
  新事实补进 STATUS 防漂移表。
- 构建输出/日志在 Windows 上是 GBK，管道先 `iconv -f GBK -t UTF-8` 再判读。
- TaskStop/杀进程后检查孤儿 java（`tools/list-java.ps1`），否则 `session.lock` 卡死下次启动。

## 工作节奏

- 改动 → `./gradlew build`（含 agent-core 单测）→ 需要则起服跑 `[m4*]` 级 SelfTest →
  更新 STATUS（证据+偏差）→ 有远端则按 docs/DEVELOPMENT.md §5-5 扫密后提交推送。
- 加原子工具看 `docs/TOOLS.md` §4；动桥看 `docs/BRIDGE.md`；排期争议以
  `docs/ROADMAP.md` 当前卡为准，改排期须在 STATUS 记录理由。
- 与主人对话用简体中文；代码注释解释"为什么"，不复述"是什么"。
