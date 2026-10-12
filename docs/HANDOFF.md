# 换设备开发交接（2026-10-13）

本文件用于快速接手，进度事实与开发日志仍以 `../STATUS.md` 为准，
执行卡以 `ROADMAP.md` 为准。维护者本轮要求整理文档、提交推送后换设备继续开发。

## 先读什么

按 `../AGENTS.md` → `DEVELOPMENT.md` → `../STATUS.md` → 本文件 →
`ARCHITECTURE.md` → `ROADMAP.md` 阅读。涉及工具看 `TOOLS.md`，涉及协作看 `BRIDGE.md`。
首轮实机核对见 `real-game-feedback-review-2026-10-12.md`，
集中补测筹备见 `REAL-GAME-RETESTING.md`。

## 当前检查点

- 分支为 `main`，RG1实现提交为 `1da3a10`：
  攻击参数拒绝细分14类固定原因码、字段与修正提示，新增8项离线回归。
  本次交接文档提交在该实现之后；接手时拉取远端最新main，而不是只停在RG1提交。
- 攻击专项41项全部执行通过；完整离线构建成功。
  已有XML共571项（核心214、根82、客户端275），失败/错误/跳过全0；
  其中核心214使用既有缓存，不能写成571项全部重新执行。
- RG1本机构建JAR SHA256：
  `b77a8bfc5a3ad1b8a9628caf783c5773fd06cd76c2dee974d8b03ed476debb76`。
  JAR与构建日志未纳入源码；该哈希标识本机记录，不预先保证新设备构建逐字节相同。
- C1–C6已合入main。首轮实机19项为15项认可、T12部分认可、T11/T14/B01阻塞。
  RG1不定位旧T14失败参数根因，也不证明玩家/牛保护已在游戏里触达。
- 旧0.1.0测试发行和标签保留；附件不含RG1，不能用于验证最新诊断。
  PR #5已关闭但未合并，继续不合并。

## 新设备准备

在目标设备选择自己的仓库位置，不沿用其他设备的绝对路径。
首次获取源码可执行：

```bash
git clone https://github.com/qaqms/mcbot.git
cd mcbot
git status --short
git log -3 --oneline
```

已有检出先检查本地改动；工作区干净、处于main且没有本地分叉时，
使用 `git pull --ff-only origin main`，不要用reset覆盖本地工作。

工具链保持JDK21、Gradle9.5.1及仓内钉死的Minecraft/Fabric/Loom版本。
JDK和代理设置放用户级 `<GRADLE_USER_HOME>/gradle.properties`，详见DEVELOPMENT §1.1。
仓内仍有历史代理默认值，新设备应在用户级按自己的网络覆盖；
直连将HTTP/HTTPS的proxyHost设为空值，不提交本机调整。

先确认工具链与缓存，再运行离线验证。Windows：

```powershell
.\gradlew.bat --version
.\gradlew.bat build --offline --console=plain --no-daemon
```

其他系统使用 `./gradlew`。新设备缺少Gradle发行包或依赖缓存时，离线失败属于环境缺项；
不能记成代码回归，需按维护者安排准备依赖。本轮不自动安装包、启动游戏或调用真实模型。
新设备执行结果另记时间、实际执行/缓存任务及XML统计；不沿用历史结果充当新证据。

## 下一张卡：RG2

目标是确定性的服务端只读接线验证，补T11未触达服务端门的证据缺口。
`ServerActionGateTest`已有4项离线测试；完整dispatcher依赖真实ServerPlayer/MinecraftServer，
现有测试通过不能证明整条生产派发链或实机已验。

先读 `ServerToolDispatcher`、`ServerActionGate`、`ActionPermissions`、
`AgentRunner`以及权限回归，明确实际接线边界，再确定最小实现与测试入口。
测试请求必须保留白名单、owner、速率、任务身份与维度检查，
到达服务端只读权限门后取得可定位的拒绝；不能以模型不调用动作或客户端预派发拒绝代替。
不要添加绕过权限的生产开关或后门；新增MC API调用必须先javap核签名并记入STATUS。
当前没有已就绪的T11实机入口，不要求维护者现在构造HTTP或进游戏。

一次推进一张卡，实现与离线验证完成后更新STATUS，再提交推送。
RG3只补T12视觉/不重载新任务；RG4连接器问答由协作者继续处理。

## 实机与协作边界

维护者愿意承担需要的实机补测，偏好相关开发与离线验证完成后一起测。
持续补充 `REAL-GAME-RETESTING.md`，最终交付时固定实现提交、新包哈希与连接器版本。
重点为T14显式目标/玩家/牛、T11服务端门、T12可见裂纹/非暂停10秒/直接新任务、
连接器就绪后的B01真实question与一次回答；不重跑已认可15项。
旧HTML、手册revision与2026-10-11原反馈保持原样，补测另建结果。

只修改mcbot本体；N.E.K.O宿主不动，独立连接器由协作者维护，参考项目只读参考不复制。
本体桥v1.0不变；接口兼容变更须按BRIDGE约定协调。
`[m9] A3`、历史模型间歇错误、复杂机器恢复/物品组件/多人等未验项继续保留，
不因换设备、构建通过或单一游戏正证销账。

## 不随Git迁移的材料

`build/`、`run/`、日志、原始实机反馈/录像和本地测试ZIP不随本仓源码推送。
如后续需要复核原件，由维护者另行带到新设备；已有核对结论和原件定位/哈希在STATUS及核对文档。
API密钥、桥令牌、用户级Gradle配置与登录凭据均不入仓，不打包进测试材料。

本机预存的连接器审查文档删除与未跟踪测试ZIP本轮不纳入提交。
`connector-code-review-2026-10-12.md`仍在提交历史中，新设备正常检出可读取；
本机若需只读复核可用 `git show HEAD:docs/connector-code-review-2026-10-12.md`，
不要为了交接擅自恢复或提交这项删除。
