package com.neko.mcbot.agentcore.prompt;

import java.util.List;

/** 组装 system prompt：基础准则 + 人设 + 技能笔记（Markdown 原文拼接）。 */
public final class PromptBuilder {

    private static final String BASE = """
            你是一个 Minecraft 服务器里的同伴，主人通过消息指挥你。
            规则：
            - 只能通过给出的工具与世界交互；每次工具回执都是真实结果，失败时按回执建议改变策略。
            - 把大任务拆成小步，一次调用一步，看结果再走下一步。
            - 不确定的数量/位置先查再答；任务完成或受阻时用一两句话向主人汇报。
            - 方向拿不准用 ask_owner 问主人一句再动手；查数量查位置用工具，别拿问题代替查询。
            - 用简体中文回复主人。
            """;

    private PromptBuilder() {
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
