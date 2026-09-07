package com.neko.mcbot.agentcore.loop;

import com.neko.mcbot.agentcore.ScriptedEngine;
import com.neko.mcbot.agentcore.llm.AssistantTurn;
import com.neko.mcbot.agentcore.llm.Msg;
import com.neko.mcbot.agentcore.llm.ToolCall;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentLoopTest {

    private static AssistantTurn toolTurn(String name, String args) {
        return new AssistantTurn("", List.of(new ToolCall("c-" + name, name, args)), 0, 0, -1, "tool_calls");
    }

    private static AssistantTurn textTurn(String text) {
        return new AssistantTurn(text, List.of(), 0, 0, -1, "stop");
    }

    @Test
    void runsToolThenReplies() {
        var engine = new ScriptedEngine().queue(toolTurn("add", "{\"a\":1}"), textTurn("完成 1"));
        var executed = new ArrayList<String>();
        var replies = new ArrayList<String>();
        var loop = new AgentLoop(engine, List.of(),
                (name, args) -> {
                    executed.add(name);
                    return CompletableFuture.completedFuture(new ToolExecutor.ToolOutcome(true, "执行了 " + name));
                },
                AgentLoop.Config.defaults(),
                new AgentLoop.Listener() {
                    @Override
                    public void onReply(String text) {
                        replies.add(text);
                    }
                },
                () -> "sys", 1_000_000);

        loop.submit("做一下");
        assertEquals(List.of("add"), executed);
        assertEquals(List.of("完成 1"), replies);
        // user, assistant(tool_call), tool, assistant
        assertEquals(4, loop.conversation().history().size());
        assertTrue(loop.conversation().history().get(2) instanceof Msg.Tool);
    }

    @Test
    void nudgesThenAbortsRepeatedIdenticalCalls() {
        var engine = new ScriptedEngine().queue(toolTurn("dig", "{\"x\":0}"));
        int[] invocations = {0};
        var notices = new ArrayList<String>();
        var replies = new ArrayList<String>();
        var loop = new AgentLoop(engine, List.of(),
                (name, args) -> {
                    invocations[0]++;
                    return CompletableFuture.completedFuture(new ToolExecutor.ToolOutcome(false, "挖不动"));
                },
                new AgentLoop.Config(40, 3, 5),
                new AgentLoop.Listener() {
                    @Override
                    public void onReply(String text) {
                        replies.add(text);
                    }

                    @Override
                    public void onNotice(String text) {
                        notices.add(text);
                    }
                },
                () -> "sys", 1_000_000);

        loop.submit("一直挖");
        assertEquals(5, invocations[0], "第 5 次后必须中止");
        assertTrue(notices.contains("nudge@3"));
        assertTrue(notices.contains("abort@5"));
        assertTrue(replies.get(0).contains("停"));
        // nudge 消息确实进了对话
        boolean hasNudge = loop.conversation().history().stream().anyMatch(m -> m instanceof Msg.Nudge);
        assertTrue(hasNudge);
    }

    @Test
    void cancelStopsChainButKeepsToolResultContext() {
        var engine = new ScriptedEngine().queue(toolTurn("dig", "{}"), textTurn("不该到这"));
        var hold = new CompletableFuture<ToolExecutor.ToolOutcome>();
        var executed = new ArrayList<String>();
        var replies = new ArrayList<String>();
        var loop = new AgentLoop(engine, List.of(),
                (name, args) -> {
                    executed.add(name);
                    return hold;
                },
                AgentLoop.Config.defaults(),
                new AgentLoop.Listener() {
                    @Override
                    public void onReply(String text) {
                        replies.add(text);
                    }
                },
                () -> "sys", 1_000_000);

        loop.submit("挖到死");
        assertEquals(1, engine.calls);
        assertEquals(List.of("dig"), executed);

        loop.cancelDirective();
        // 服务端叫停会把挂着的 future 以 CANCELLED 完成——链续跑到步首即停
        hold.complete(new ToolExecutor.ToolOutcome(false, "CANCELLED:主人叫停了"));
        assertEquals(1, engine.calls, "叫停后不得再问模型");
        assertTrue(replies.get(replies.size() - 1).contains("停手"));
        var h = loop.conversation().history();
        assertTrue(h.get(h.size() - 1) instanceof Msg.Tool, "被取消的工具回执应留在对话里");

        // 旧叫停标记不许追溯杀伤新指令（engine 耗尽后重复最后一轮文本 turn）
        loop.submit("打个招呼");
        assertTrue(replies.contains("不该到这"), "新指令必须正常走完");
    }

    @Test
    void compactionTriggersAndKeepsRecent() {
        var engine = new ScriptedEngine().queue(textTurn("【要点】历史摘要"));
        var loop = new AgentLoop(engine, List.of(),
                (n, a) -> CompletableFuture.completedFuture(new ToolExecutor.ToolOutcome(true, "")),
                AgentLoop.Config.defaults(), new AgentLoop.Listener() {
                },
                () -> "sys", 10 /* 立刻超水位 */);
        // 每条 ≈1008 token：近段预算 1500 只装得下末尾一两条，旧段必然被总结
        for (int i = 0; i < 20; i++) {
            loop.conversation().add(new Msg.User("x".repeat(4000) + i));
        }
        loop.submit("小问题");

        var h = loop.conversation().history();
        // 压缩产物在开头，近段原文保留（预算 1500 token ≈ 尾部两条），且摘要来自一次无工具调用
        assertTrue(((Msg.User) h.get(0)).text().startsWith("[对话前情提要] 【要点】历史摘要"),
                "首条应为前情提要，实际: " + h.get(0));
        assertTrue(h.stream().anyMatch(m -> m instanceof Msg.User u && u.text().equals("小问题")),
                "近段必须原文保留最新指令");
        assertTrue(h.size() <= 5, "旧段应已被摘要替换掉，实际长度 " + h.size());
        assertTrue(engine.calls >= 2, "压缩 + 主对话各至少一次");
    }

    @Test
    void sameCallDifferentOutcomeIsNotStuck() {
        // M4.5：同调用但结果在变（如 TIMEOUT 后原参重试）不得累计打转计数
        var engine = new ScriptedEngine().queue(toolTurn("move_to", "{}"));
        var notices = new ArrayList<String>();
        var replies = new ArrayList<String>();
        int[] n = {0};
        var loop = new AgentLoop(engine, List.of(),
                (name, args) -> CompletableFuture.completedFuture(
                        new ToolExecutor.ToolOutcome(false, "TIMEOUT:第 " + (++n[0]) + " 次没等到结果")),
                AgentLoop.Config.defaults(),
                new AgentLoop.Listener() {
                    @Override
                    public void onNotice(String t) {
                        notices.add(t);
                    }

                    @Override
                    public void onReply(String t) {
                        replies.add(t);
                    }
                },
                () -> "sys", 1_000_000);

        loop.submit("走去箱子");
        assertTrue(notices.isEmpty(), "结果在变就不是打转，不得 nudge/abort");
        assertEquals(40, engine.calls, "护栏未触发，一路跑到步数帽才停");
        assertTrue(replies.stream().anyMatch(r -> r.contains("步数超限")),
                "终点应是步数帽而非 abort，实际: " + replies);
    }
}
