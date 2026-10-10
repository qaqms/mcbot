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
            - 只能通过给出的工具与世界交互；每次工具回执都是真实结果，失败时按回执建议改变策略。
            - 把大任务拆成小步，一次调用一步，看结果再走下一步。
            - 长活的回执以 ACCEPTED: 开头时，**它不是结果**：那件事只是被受理了，还没做完。
              别为它重发或轮询；系统等待真正终态后才派发后续工具，再把结果报给你。
              根据真实结果决定下一步，主人可以在等待期间叫停任务。
            - 不确定的数量/位置先查再答；任务完成或受阻时用一两句话向主人汇报。
            - 方向拿不准用 ask_owner 问主人一句再动手；查数量查位置用工具，别拿问题代替查询。
            - 主人明确要求只读时，只观察并汇报；未发现可行动目标不能成为移动或挖掘的理由。
            - NEED_CONFIRM 的路线改动须用 ask_owner 携带服务器的 authorization_id 征得主人确认，
              再把该编号交给 move_to；may_alter_terrain 不能授权，新增改动要重新确认。
            - attack 只处理一次有界近战，先扫描取得实体编号/UUID；挥击上限完成不等于击杀。
              中立或命名目标的 NEED_CONFIRM 同样须携带服务器编号问主人，失败不得自动重投。
            - 用简体中文回复主人。
            """;

    // ---- 实例（缓存）形态的字段；静态形态不用实例，下列字段恒为默认值 ----
    private final Supplier<String> source;
    private final AtomicReference<String> text = new AtomicReference<>();
    private volatile boolean dirty;
    private volatile boolean armed; // 只有在指令边界才允许兑现脏

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
    public void onDirectiveBoundary() {
        armed = true;
        if (dirty) {
            rerender();
        }
    }

    private synchronized void rerender() {
        if (dirty && armed) {
            text.set(source.get());
            dirty = false;
            armed = false;
        }
    }

    @Override
    public String get() {
        rerender();
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
