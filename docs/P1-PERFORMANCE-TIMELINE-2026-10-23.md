# P1 首阶段：实机日志与导航时间分解

2026-10-23，Asia/Shanghai。源码起点 `48d54e16b36584e31a62e9c72a74fa96e340a440`。
本轮先收测量与已暴露的短说明缺口，P1仍部分完成，不计为第21张完整卡。
最终构建、测试及候选以 `../STATUS.md` 为准。

2026-10-24后续实机日志已确认工作精确站位的主线程搜索长等待；
本轮工作目标、连续支撑与视角修正见[工作导航](WORK-NAVIGATION-AND-LOOK-2026-10-24.md)。
下文保留首阶段测量，不代表最新候选的导航能力；P1仍部分完成。

## 1. 本次测试的版本与范围

维护者提供的实例日志包含两次模型任务：

| 原件 | 可见任务区间 | SHA256 |
| --- | --- | --- |
| `2026-10-23-1.log.gz` | 16:04:26至16:05:19退出 | `a624ecc702f4b93dd3f0d845a0e7bc497aab6ccc6328259444740b3f9a7f52e3` |
| `latest.log` | 17:18:10至17:19:37退出 | `71f1ecf4033d9a574b3fd8f7dcd5d3579088704460fbfc31c9a37d5dcfa97c09` |

原件只读，不复制进仓；路径不入仓。实例当前mcbot JAR哈希为
`9807170593d7b6af1a5ee1e2a369411e6592aa2ab62b4c7ccf79b960725e6fd6`，
对应此前L2/源码刷新候选，早于K1。不能只凭0.1.0版本号把它当作K1构建，
也不能把当前磁盘哈希作为历史运行进程加载了同一文件的独立证明。
两场日志均为31个工具，首包system_chars=2068；并无K1新包已实测的证据。

没有读取玩家模型配置或凭据，没有发模型请求或启动游戏，没有安装JAR。
只对现有日志、源码与实例JAR作只读核对；宿主、连接器、参考副本和实例不改。

## 2. 模型等待与工具时间

`tools/PerformanceLogReview.java`以JDK21源文件模式运行，不需要模型或游戏。
工具只输出白名单数字聚合、工具名/有限错误码与输入文件指纹，不输出正文、参数、
玩家名称、端点、凭据或reasoning内容。支持明确传入的`.log`/`.log.gz`，不扫描配置目录。
不把没有request编号的旧stream行按相邻位置强行绑定请求。

| 指标 | 16:04场 | 17:18场 |
| --- | ---: | ---: |
| request记录/response记录 | 7/6 | 12/11 |
| 无response的末次请求 | 1 | 1 |
| first_dispatch有效样本 | 6 | 11 |
| first_dispatch累计 | 32343ms | 57067ms |
| first_dispatch最近秩中位数 | 2905ms | 3593ms |
| first_dispatch最大 | 16268ms | 15925ms |
| stream累计 | 32933ms | 56840ms |
| ttfb最近秩中位数 | 1690ms | 1733ms |
| 已报告prompt token累计 | 92821 | 178553 |
| 已报告cached token累计 | 71424 | 151808 |
| 已报告completion token累计 | 749 | 1836 |

末次请求只说明退出前没有完整response记录，不能当作已完成的极慢样本、
空响应、超时或模型错误。token数来自日志，不能推导统一费用；
ttfb包含网络/路由/服务等待，first_dispatch也不是纯模型计算时间。
流尾与早派发动作可重叠，不能把上述累计再与工具耗时相加作为关键路径。

17:18场两次move_to记录3422/2464ms，合计5886ms；三次attack合计16ms且均拒绝。
五页find_resource合计23ms，同一Lua程序连续查完，没有每页重新询问模型。
scan_area四次合计19ms。status全日志13次合计34ms，含启动/内部观测，不全属于模型工具轮。
这些工具日志口径有同步调用与跨刻工作的差异，不能统一叫CPU时间。
没有C2S、服务端排队及S2C全链路单调时间戳，尚不能拆出准确网络往返或所有空闲间隙。

## 3. 已定位的无效往返与身体边界

17:18场的直接反馈：

- 17:18:17，attack缺扫描目标的target_uuid而被拒绝。
- 17:18:19、17:18:35，显式接近原目标已在初始6.5格球外，零出手、SCOPE_LIMIT。
- 17:18:25至17:18:29，路线7节点、搜索404ms/416节点，实际到达较低落点后PARTIAL，
  并未抵达目标高度；不能把走完局部段当作成功。
- 17:18:51至17:18:53，搜索405ms/864节点后NO_PROGRESS。
  局部PLACE_LIMIT、WORK_SIGHT等样本不能证明整个世界没有路线。
- 17:19:08，search_resource参数违反当前targets/r契约而在客户端拒绝。
  原日志不打印实际参数，不能断言究竟是r越界、targets数量或别的类型错误。
- 17:19:14，Lua中的search_resource五页查询已完成，当前范围没找到所查资源；
  完整范围查询成功不等于主人整个任务完成。

16:04场同样先出现缺UUID及SCOPE_LIMIT。16:04:56至16:04:58的attack有三段路线，
最终原版出手2/4次、观察生命减少2，因ACTION_LIMIT停止，不能称为完全没行动。
源码中首次路线外的两次重搜累计上限为2；目标移动、到达旧快照、进入攻击距离后
再接近、暂停后重建都会消费这一原账。旧日志无法唯一分配这两次的实际触发原因。
本轮增加原因计数，保留原限制，不通过扩大额度掩盖问题。

常驻ToolCatalog短说明现在明确：

- 数字entity_id必须同时带同一扫描条目的target_uuid；
  只有`entity_id="hostile_nearby"`选择器不带UUID，且只选附近未命名敌对生物。
- search_resource的targets为1–8个，r为1–16，默认16。

仍31工具，参数Schema/顺序及服务端验证保持；不会替模型猜UUID、裁剪半径、自动重投，
也不承诺短说明一定能防住真实模型参数错误。其收益须在新包日志中另验。

## 4. 新导航计时

PathTask复用原执行入口，每条路线拥有进程内唯一route编号。
计划成功/失败的原path日志带route；结束或退役至多输出一条`[path-perf]`摘要，
含原task、body_id和数值指标。原生tick中不逐帧打印。
普通/中断终态的结构化data增加`path_timing`，不把诊断全文追加到模型feedback，
也不往每轮status的路径progress中注入这份计时表。
若完整出站tool_result/job_event因附加计时而超过原线路上限，先从出站副本移除path_timing，
保留真实成功/失败相位、原feedback及全部动作证据；完整计时仍在日志。
去掉计时后仍超限才沿原缩减/未知效果规则处理，原32KiB上限不变。
不会修改生产者的原始结果对象，小回执仍保留计时。

| 指标 | 口径 |
| --- | --- |
| started_nanos/finished_nanos | 本进程System.nanoTime时间点；不与其他进程、重启或墙钟直接相减 |
| wall_nanos | 第一次实际tick至结束，含两tick间隔、暂停和服务端调度；不含构造后未开工等待 |
| active_nanos | 实际PathTask.tick调用内的单调经过时间；含线程抢占/GC影响，并非线程CPU计数器 |
| phases | GUARD/GROUND_WAIT/WARMUP/SEARCH/VALIDATE/DIG/PLACE/STEER/HANDOFF/FINALIZE互斥累计nanos |
| phase ticks | 一刻进入过该阶段即计一次；同刻可经过多阶段，跨阶段ticks不可求和 |
| input_ticks/no_input_ticks | 本路线该刻是否请求非零水平或跳跃输入；两者之和等于已结算路线ticks |
| settling_ticks | 必须停稳的节点附近、已接地/同脚高且水平速度仍大于0.04的steering刻数 |
| through_nodes/stopped_nodes | 实际按原穿越条件或停稳条件提交的节点数 |
| suspensions | 此路线实际收到onSuspend次数；不补原时间或搜索账 |

SEARCH涵盖采样器/搜索构造、memo及搜索后的liveify；
VALIDATE包括计划/下一步几何复核，STEER只含朝向/输入选择等本地控制工作。
搜索原预算日志的elapsed_ms与新SEARCH不是同一计量边界，不能相加。
各阶段只计tick内部；外部恢复/取消的清理不冒充tick执行时间。
空输入可来自必要的挖掘、放置、搜索、落地或收尾，不等于浪费时间。
settling_ticks只是窄条件观察，不证明每个低速刻都是不必要制动。

终态后计时冻结，迟到清理/重复读取不延长或重计。未开工即退役的路线ticks/wall为0。
`outcome=ABORTED`表示内部路线退役，也可能是目标已进攻击距离或换路线，
不能独自解释为主人取消。TERMINAL也不代表成功，须结合原真实结果判读。
诊断不发输入、不改原落点/穿越/终态/累计账或控制权。

EntityApproach的原observation/终态approach中增加：
`plans/retargets/in_range_stops/route_arrivals/route_failures/budget_stops`。
这使“目标变动重搜”和“进入距离后释放、再接近”可分别观察；
同一原导航预算继续参与直接攻击、自保及跟随，原因计数不等于新增预算。

## 5. 参考取舍与下一步

本轮只读复核参考的AttackCompanionTask走位入口和Driver.retarget：
它能让移动与出手分别判断，并在旧路线终点仍满足新目标时保留路线；
当前在飞搜索也不一定因每个目标更新而丢弃。
参考retarget还会重置其allowance，这部分不能带入本体原累计账。
没有复制/翻译代码、注释、提示或技能，也没有运行参考构建。

关键只读指纹：

```text
c244b08bf2de5d91c306aaf089a0fcbe7ad131586624459dd675322a75ded707 numen/common/src/main/java/com/dwinovo/numen/task/combat/AttackCompanionTask.java
d7b27b0b6e67f9243e6e1ad9a7f027df7c2595aca6e1be18bab503cf7b67f6f4 numen/pathing/src/main/java/com/dwinovo/numen/pathing/drive/Driver.java
```

下一P1续卡优先用相同地形的无模型路线和新计时，选择一个已证实瓶颈：
节点制动、重复几何工作、段间搜索或动态目标的有效路线保留。
仍按[框架审计](FRAMEWORK-PERFORMANCE-COMPARISON-2026-10-23.md)的平直/转弯/高差/半程组
区分能力与性能，不同时更改模型、搜索预算及成功标准。
若采用路线保留，必须重新核目标、世界/身体和原范围，旧路线或目标变动不能重开额度。
本轮不实施后台寻路、路线拼接、服务端采集批次、工作中模型对话或预测下一阶段。

## 6. 离线复核

仓根使用JDK21：

```text
java tools/PerformanceLogReview.java --self-test
java tools/PerformanceLogReview.java <明确的原始.log或.log.gz路径> ...
```

最近秩分位数包括全部有效非负样本；缺失及-1不当作零延迟。
request/attempt配对保留未完成与孤立响应计数，stream单独聚合；
同名文件来自不同进程时必须分别输入，不能拼接后合并request编号。
工具的自检覆盖attempt换道/缺失、无ID流、负哨兵、未知工具、前缀隔离、
分位数及gzip解码。首轮自检的模拟失败标记误写ASCII字母，未匹配真实标记；
修正夹具为真实符号后通过，未放宽解析白名单。
本地聚合证据为`build/p1a-log-review-20261023.log`。

PathTiming用可控单调时钟验证阶段互斥、同刻重入、tick间隔不计active、
输入/跳跃和空输入口径、取消冻结/迟到隔离、未开工0时间和独立ID。
PathClearance执行真实中断出口，确保实际挖放/失败证据保留、计时冻结且不发新搜索。
EntityApproach运行实际原预算，复现三次进入攻击距离的路线释放；
第四次仍拒绝，原因统计不会补换位账。游戏、身体及模型/网络边界仍受控。
收尾由30KiB实际证据加可选计时复现新诊断把成功job变成超限失败，
`build/p1a-wire-before-20261023.log`为1项1失败/20秒。
原dispatcher优先省略附加计时后，新增回归同时核job/同步通道、真实相位及全文证据、
生产者对象不变和正常小回执保留计时；既有真实过大回执的保守缩减回归保持。
专项及强制全量结果以STATUS为准；没有新原生动作或双边实机A/B证据。
