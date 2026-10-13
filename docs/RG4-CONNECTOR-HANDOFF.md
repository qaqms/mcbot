# RG4 宿主问答协作交接

日期：2026-10-13，Asia/Shanghai。维护者已授权进入RG4。
本仓负责本体和桥的离线回归；连接器由协作者修改，宿主不改。
本文是可交给协作者的核对清单，本轮没有发送消息、安装连接器或运行联合链路。
接口仍以 `BRIDGE.md` v1.0为准，不新增回答必填字段或开放原子工具。

## 当前依据

首轮B01的问答时间窗没有实际ask_owner/question，记录为“问题未生成”。
不能用这个现场证明连接器漏转；详情见
`real-game-feedback-review-2026-10-12.md` §3.4。
2026-10-12固定提交的七类接线缺口见 `connector-code-review-2026-10-12.md`，
该历史审查及56项原测试/9组失败探针不是本轮重新执行的结果。

本轮只读核对维护者提供的本地连接器源码副本，仍能看到
`service.py:779–801` 缓存question_id但推送消息没有三个关联编号，
且把text裁为400字符；`prompts.py:135`没有补编号，
`__init__.py:123`回答入口却要求cue提供question_id。
本地副本无Git元数据，未验证它与协作者当前上游版本一致，不能称为最新上游。

| 本地副本文件 | SHA256 |
|---|---|
| service.py | `6649f4ba366479f2ef064946868a5cd952711474775fb6b9f10498efd077a337` |
| client.py | `207035a121cbb8582807f6781ba0a23a1e9e2b9da0261985d8a239294f5d883f` |
| prompts.py | `a56fc18a59c7d84d1bd7768fa66215e89cc1382dc1c92a9a76aa90d7e311314f` |
| __init__.py | `a145d0a78d20d36733c6b1085b5e49f2448aec07cf4a448f8108eabac442aaad` |

## 协作者整改与离线核对

下列初始均未取得修复提交或新测试证据，不把本体回归通过记到连接器名下。

| 项目 | 要完成的行为与判据 | 当前交付 |
|---|---|---|
| 指令映射 | 对照主人原句、宿主mcbot_task/mcbot_ask实际text、桥task_id及游戏收到的任务；保留问主人、等待、只执行所选一项等限制。不能用宿主口头答复替代任务投递。 | 待协作者 |
| 问题归属 | 实际模型可读取的cue/上下文保留session_id、task_id、question_id和全文；回答用原qid，核对仍有效的会话/任务。编号可不口播，不能猜最新编号。快速question先于POST响应时暂存后归组。 | 待协作者 |
| 完整授权 | 长度超过400字符、末尾有独立目标的授权问题完整可查看；不以模型摘要替代服务端清单。无法完整展示时不请求或转交批准，提示缩小范围。 | 待协作者 |
| 一次真人回答 | 宿主将问题交给主人，只有真实主人对该问题的回答才调用answer；不自答、不重投任务。首个成功answer仅为受理，继续等匹配done；重复/过期/取消后回答返回NOT_FOUND。 | 待协作者 |
| 取消与替换 | 未知取消ok=false不清当前槽；取消排队项不清活动项；非法显式编号不降级为全取消。overwrite提供打断语义时先取消旧任务、处理不确定结果，再派新任务。 | 待协作者 |
| 未知结果 | POST/本地等待超时只报结果未知、仍可能执行，保留关联处理晚到结果，不自动重发；不使用“做完/已停”成功模板。 | 待协作者 |
| 重放与投递 | session/id有界去重；失败帧及宿主推送失败不能被后续成功游标越过。问题按会话/问题、终态按会话/任务区分，避免全局键覆盖；失效问题清缓存和旧通知。 | 待协作者 |

协作者可先修编号、全文、回答归属，再补取消/替换、未知结果和重放；
现有历史审查中的其他缺口仍保留。选择具体实现时沿用连接器已有结构，
无需修改本体v1.0来迁就缺失编号或截断。
离线探针必须检查实际推给宿主模型的内容，不能仅直接给service.answer传测试qid。
重复帧不可重复派游戏任务；宿主通知通道不据此承诺exactly-once。

## 本体离线证据

`AgentRunnerBridgeQuestionTest`新增三项，使用生产AgentRunner/AgentLoop、
RunnerBackend、BridgeHttp/BridgeService、真实JDK HttpClient和SSE完整帧：

1. 中文、多行、首尾空格的任务text原样进入模型历史，SSE提供准确session/task/question；
   HTTP回答配对原工具调用，重复REST/MCP回答NOT_FOUND且只恢复一次，最终done仍属原任务。
2. 模拟服务端返回超过400字符且含尾项的具体清单，SSE逐字保全并替换模型自写问题；
   一次确认只发送一次authorize，收到服务端回执前不续跑，重复回执不再恢复。
3. 推进替身时钟使问题过期，HTTP回答在下一tick前拒绝；
   后续任务的问题保持待答，旧qid不能消费新问题，新问题只恢复一次。

专项命令：

```bash
./gradlew clientTest --offline --console=plain --no-daemon --tests com.neko.mcbot.agent.AgentRunnerBridgeQuestionTest --tests com.neko.mcbot.agent.AgentRunnerQuestionTest --tests com.neko.mcbot.agent.AgentRunnerPermissionsTest --tests com.neko.mcbot.agent.ClientSessionLifecycleTest --tests com.neko.mcbot.bridge.BridgeHttpContractTest
```

执行时间、XML统计、完整构建及产物以 `../STATUS.md` 本次RG4记录为准。
模型、客户端调度/发包、服务端授权回执和时钟采用可控替身；
长清单是测试构造的信封，不是世界生成的路线，没有真实MC身体动作或主人授权。
这些证据证明本体边界的接线，不能证明连接器cue、宿主实际展示或真人回答已通过。

## B01集中补测与交付

先交固定修复提交/本地源码指纹、实际安装的连接器与宿主版本、本体JAR哈希、
离线回归命令及实际通过/失败/缓存结果；未知版本写未知，不用目录名代替版本。
本体18项旧手册不重跑，B01保留原四步顺序，具体操作见
`REAL-GAME-RETESTING.md` B01节，反馈使用 `templates/RG4-B01-FEEDBACK.md`。

问答必须先有实际question事件，再核对宿主全文呈现、原主人回答、原qid、
一次续跑与匹配done。没有问题时记阻塞并定位哪一层没有产生，
不将本地T16成功或离线替身当联合正证。
完整授权显示由连接器离线探针必测；普通B01选择问题不证明路线授权。
本轮不强制新手额外构造长路线；若实机自然出现授权，必须全文可查看后才接受确认。
尚无实机授权正证时继续注明“完整授权展示仅离线验证/实机未测”。

取消受理和done(cancelled)不能代替身体停手；仍看实际后续扫描及新任务。
退出重进记录新session，旧问题失效、游标重置，不自动重投旧任务。
每个子项单独记账，未测或阻塞不被一个总通过覆盖。
