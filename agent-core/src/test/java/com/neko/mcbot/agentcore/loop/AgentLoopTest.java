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
        return new AssistantTurn("", List.of(new ToolCall("c-" + name, name, args)), 0, 0, "tool_calls");
    }

    private static AssistantTurn textTurn(String text) {
        return new AssistantTurn(text, List.of(), 0, 0, "stop");
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
                new AgentLoop.Config(40, 3, 5, 12),
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
        for (int i = 0; i < 20; i++) {
            loop.conversation().add(new Msg.User("x".repeat(200) + i));
        }
        loop.submit("小问题");

        var h = loop.conversation().history();
        // 压缩产物在开头，尾部保留最近若干条，且摘要确实来自一次无工具调用
        assertTrue(h.get(0) instanceof Msg.User);
        assertTrue(((Msg.User) h.get(0)).text().startsWith("[对话前情提要] 【要点】历史摘要"),
                "首条应为前情提要，实际: " + h.get(0));
        assertTrue(engine.calls >= 2, "压缩 + 主对话各至少一次");
    }
}
