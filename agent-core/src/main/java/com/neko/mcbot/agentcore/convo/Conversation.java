package com.neko.mcbot.agentcore.convo;

import com.neko.mcbot.agentcore.llm.AssistantTurn;
import com.neko.mcbot.agentcore.llm.ChatEngine;
import com.neko.mcbot.agentcore.llm.Msg;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * 对话窗口：全量消息 + 摘要前缀。超过软水位时，把旧消息交给模型自己压缩成
 * 要点清单（摘要以合成 user 消息开头），历史只保留最近若干条。
 */
public final class Conversation {

    private static final String SUMMARY_PREFIX = "[对话前情提要] ";

    private final List<Msg> msgs = new ArrayList<>();
    private final int softTokenLimit;

    public Conversation(int softTokenLimit) {
        this.softTokenLimit = softTokenLimit;
    }

    public void add(Msg m) {
        msgs.add(m);
    }

    public List<Msg> history() {
        return List.copyOf(msgs);
    }

    /** 粗估 token：中文约 1 字 ≈ 0.7 token，英文约 4 字符 ≈ 1 token，统一按 字符/3。 */
    public int approxTokens() {
        int chars = 0;
        for (Msg m : msgs) {
            chars += textOf(m).length();
        }
        return chars / 3;
    }

    public boolean needsCompaction() {
        return approxTokens() > softTokenLimit && msgs.size() > 8;
    }

    /** 交给引擎做摘要，成功后就地替换历史；失败则原样继续（宁可多花 token 不可丢上下文）。 */
    public CompletableFuture<Void> compact(ChatEngine engine, int keepRecent) {
        int cut = Math.max(0, msgs.size() - keepRecent);
        if (cut <= 0) {
            return CompletableFuture.completedFuture(null);
        }
        StringBuilder old = new StringBuilder();
        for (Msg m : msgs.subList(0, cut)) {
            old.append(m.getClass().getSimpleName()).append(": ")
                    .append(textOf(m)).append('\n');
        }
        List<Msg> prompt = List.of(
                new Msg.User("请把以下对话历史压缩成不超过 15 行的要点清单，必须保留：任务目标、"
                        + "已完成步骤与关键结论、未决问题、涉及的具体数量/位置。只输出清单。\n\n" + old));
        return engine.chat("你是对话压缩器。", prompt, List.of())
                .thenAccept(turn -> {
                    List<Msg> tail = new ArrayList<>(msgs.subList(cut, msgs.size()));
                    msgs.clear();
                    msgs.add(new Msg.User(SUMMARY_PREFIX + turn.text()));
                    msgs.addAll(tail);
                });
    }

    private static String textOf(Msg m) {
        if (m instanceof Msg.User u) {
            return u.text();
        } else if (m instanceof Msg.Nudge n) {
            return n.text();
        } else if (m instanceof Msg.Tool t) {
            return t.name() + " -> " + t.content();
        } else if (m instanceof Msg.Assistant a) {
            StringBuilder sb = new StringBuilder(a.text());
            a.toolCalls().forEach(tc -> sb.append(" [call ").append(tc.name())
                    .append(' ').append(tc.argsJson()).append(']'));
            return sb.toString();
        }
        return "";
    }
}
