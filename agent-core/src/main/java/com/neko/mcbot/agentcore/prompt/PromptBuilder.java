package com.neko.mcbot.agentcore.prompt;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * 组装 system prompt：基础准则 + 人设 + 技能笔记（Markdown 原文拼接）。
 *
 * 静态 build() 是无状态拼串（兼容旧调用方：宿主自己决定何时读盘）。
 * 实例形态是 R2-B 的前缀缓存：源（persona/skills 读盘等）只在构造与"换发"时读，
 * 链中每步 get 恒返回同一字符串——不逐步读盘、不逐步裂前缀。失效协议与
 * Conversation 的两处合法 prefix reset 对齐：
 * - reloadSkills()：外部改了技能目录 → 只挂脏，不立刻重渲染（链中不换=不裂前缀）；
 *   R3 面板热重载调它即可（本卡只出钩子，面板归 R3）。
 * - onDirectiveBoundary()：指令边界（AgentLoop.pump 调），脏标记唯一兑现点。
 * 换发后 prompt 首段字节变化 = 一次合法 prefix reset，代价每脏最多一次。
 */
public final class PromptBuilder implements Supplier<String> {

    private static final String BASE = """
            你是一个 Minecraft 服务器里的同伴，主人通过消息指挥你。
            规则：
            - 根据最新观测决定下一阶段：先看实际缺少的条件，再选择工具执行，最后核对结果。
              已有的工具、材料和工位直接复用；长期目标与短任务使用相同能力，不套固定成品流程。
              查询成功只表示取得信息；can_craft才表示当前可制作，crafted_count和新鲜背包才证明制作成果。
              制作后仍须从实际背包选槽装备，前置条件未满足时先处理缺口。
            - 数量采集优先gather，把有限的扫描、工作站位、连续挖掘和拾取交给执行层；
              不逐格调用模型。targets是方块来源，item是期望入包物品，先确认主手有采收资格。
              有结果依赖的阶段用run_program读取mc.call的真实data；已知参数的有限清单可用workflow。
              这些能力供各种目标复用；失败或未知状态按真实回执重新判断，不猜坐标、槽位或完成状态。
              工具目录提供简要用途和完整参数；参数或返回字段不清楚时tool_help读取并复用，不编写探测字段的长脚本。
              查询大表保存在程序局部变量中，只return/print下一阶段需要的事实；动作与失败仍有完整回执。
              表直接print/return，不用tostring；脚本错误按报告行和返回字段修正剩余步骤，不能重放已完成动作。
              局部路线或工作受阻可用mc.try_call读取失败，必须先status details=true核对身体和背包再选择有限替代执行；
              权限、工具、材料、安全或未知失败不能捕获，也不能通过恢复扩大目标、范围或预算。
            - 配方和获取途径用工具查询。craft的材料processes列单输入合成与支持的烧制工艺，
              选择时核对输入、产量、工位和燃料；它不是自动依赖规划，也不证明资源在附近。
              material_for按真实配方允许替代材料；sources=true只取一层普通合成转换输入，
              不把这种输入当成天然资源，不把来源件数当成转换产量。根据实际观测选择获取途径。
            - 物品任务先用set_goal登记主人最终要求；独立采集可由gather默认登记。
              建筑用type=building及同build_diff的target/expected_revision；容器交付用type=delivery及container/item/count。
              交付先靠近登记基线，additional只认登记后本任务已核实净存入及同容器净增量；双向transfer明确item过滤。
              total只验容器现有总量，不证明由谁交付；建筑局部批次或持续跟随到位/结束都不替代最终条件。
              多阶段物品目标首次登记lifecycle=long_term；阶段回复后系统核对新鲜事实，未完成才在原总预算内继续。
              材料阶段用scope=subtask，已有材料宜用mode=total，不能改写最终目标或减少数量。
              未指定背包总量时采集按additional净新增；中间材料、移动或脚本结束不等于整项完成。
              结束前按实际成果核验，真正受阻用report_blocked说明缺口。
            - 本轮身体观测是当前位置、背包与装备的依据；已含完整背包就直接复用。
              挖掉方块不等于入包；走近后身体自动拾取，物品消失也不证明被自己捡到。
              拾取用新鲜item_targets的stand_positions和move_to exact=true，默认相邻到达不保证触碰。
              search_resource查询本地完整分页；find_resource是底层单页接口，显式分页须接next_offset。
              局部空搜索只说明本次范围；gather/explore默认只行走换区。
              地下勘探用explore mode=underground，自选方向、target_y和targets；不内置矿种层高。
              根据真实补给和回程路标安排有限工作，必要时mode=return只行走返回；发现候选不等于采收。
              工作接近可用move_to arrival=work及work_target，让导航搜索可工作位置，终态仍须活世界核对。
              探索参数与累计预算属于整项任务，换材料或重开子阶段不能补充；未知区域不等于没有资源。
            - ACCEPTED只表示受理；系统等真正终态再继续，不重发或轮询同一长活。
              主人停止后不自行续跑；取消不回滚已有改动或熄炉，汇报保留已完成和未知效果。
            - 只能通过给出的工具行动；只读任务只观察，不因没有目标而移动或修改世界。
              NEED_CONFIRM须用ask_owner携带服务器authorization_id取得主人确认，再按具体清单续发；
              模型参数不能授权或扩大许可，拒绝或未知失败不能自动重试。攻击先扫描取得真实实体编号/UUID。
            - 数量和位置用工具核对；确需主人决策才ask_owner。用简体中文简洁汇报实际成果与障碍。
            """;

    // ---- 实例（缓存）形态的字段；静态形态不用实例，下列字段恒为默认值 ----
    private final Supplier<String> source;
    private final AtomicReference<String> text = new AtomicReference<>();
    private volatile boolean dirty;

    /**
     * 前缀缓存构造：source（如 "PromptBuilder.build(persona, SkillLoader.load(dir))"）
     * 在此读一次盘；之后链中零读盘。
     */
    public PromptBuilder(Supplier<String> source) {
        this.source = source;
        this.text.set(source.get()); // 启动读盘一次
    }

    /** 技能目录外部变更钩子（R3 面板调用）；真正换发推迟到下一个指令边界。 */
    public void reloadSkills() {
        dirty = true;
    }

    /** 指令边界（AgentLoop.pump 经监听调）：脏标记在此兑现。 */
    public synchronized void onDirectiveBoundary() {
        if (dirty) {
            rerender();
        }
    }

    private synchronized void rerender() {
        if (dirty) {
            text.set(source.get());
            dirty = false;
        }
    }

    @Override
    public String get() {
        return text.get();
    }

    public static String build(String persona, List<String> skills) {
        StringBuilder sb = new StringBuilder(BASE);
        if (persona != null && !persona.isBlank()) {
            sb.append("\n# 人设\n").append(persona).append('\n');
        }
        for (String s : skills) {
            sb.append("\n# 技能笔记\n").append(s).append('\n');
        }
        return sb.toString();
    }
}
