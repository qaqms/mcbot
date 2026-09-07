# mcbot

一个 LLM 驱动的 Minecraft 同伴 **agent 框架**：主人用自然语言下指令，模型自主拆解、
规划、逐步执行（移动、挖矿、采集、放置、存取、合成、熔炼），失败时拿到真实反馈再改策略。

## 拓扑（三进程）

```
neko(桌面猫娘) ⇄ 127.0.0.1 桥(HTTP/SSE/MCP) ⇄ mcbot 大脑+工具宿主(owner 客户端) ⇄ Minecraft 网络 ⇄ 联机服务器(假玩家身体 + 服务端工具执行 + 三道闸)
```

- **大脑在客户端**：agent loop 与 LLM 调用跑在主人自己的机器上，用主人自己的 API key；
  key 永不经过服务器。
- **身体在服务端**：同伴是服务端的假玩家（`ServerPlayer`），每个动作走原生玩家代码路径、被服务端逐一校验。
- **桥接是任务级**：外部（neko / 任何 MCP 客户端）只能派任务，摸不到原子游戏操作。

## 结构

- `agent-core/` — 纯 JVM 大脑层（LLM 客户端、对话压缩、agent loop、护栏、桥接内核），**零 Minecraft 依赖**，可独立测试。
- `src/main/` — 服务端 + 公共层：假玩家身体、任务调度、服务端工具与三道闸、命令。
- `src/client/` — 客户端层：agent 宿主、S2C 通道、G 面板、桥接 HTTP 服务。

## 构建

需要 JDK 21。`./gradlew build` 产出 mod jar；`./gradlew runServer` 起本地开发服做无头自测。
工具链版本钉在 `gradle.properties`（Fabric 1.21.11 + loom-remap）。

## 文档

- `mcbot-DESIGN.md` — 完整技术方案（拓扑、协议、护栏、寻路、桥接契约、里程碑）。
- `STATUS.md` — 进度事实源 + 1.21.11 API 实测防漂移笔记。
- `dist/README-DIST.md` / `dist/README-BRIDGE.md` — 测试包安装 与 neko 侧对接说明。

## Clean-room 声明

本项目为独立实现，仅借鉴公开的框架思路（假玩家机制参考 Carpet、寻路思想参考 Baritone 公开文章、
空间表征参考 arXiv:2410.08500、function calling 参考 OpenAI 协议文档）；
不含任何第三方同类项目的代码、注释文本、美术或专有名称。
