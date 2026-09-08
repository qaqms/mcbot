package com.neko.mcbot.agentcore.convo;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.neko.mcbot.agentcore.llm.AssistantTurn;
import com.neko.mcbot.agentcore.llm.ChatEngine;
import com.neko.mcbot.agentcore.llm.Msg;
import com.neko.mcbot.agentcore.llm.ToolCall;
import com.neko.mcbot.agentcore.llm.ToolSpec;
import com.neko.mcbot.agentcore.provider.OpenAiCompatProvider;
import com.neko.mcbot.agentcore.prompt.PromptBuilder;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R2-B 前缀稳定验收：折叠检查点（foldCheckpoint + 冻结决定）让 outboundHistory()
 * 变纯函数——同历史多次 buildBody 的 messages 序列化必须字节级前缀恒等，
 * 增长中的历史旧前缀不被后续步撕裂（prompt cache 才吃得到）。
 * 断言口径取卡上"前 90% 字节相同"（实现若做不到 100%，这条先红，有鉴别力）。
 */
class ConversationPrefixTest {

    private static final List<ToolSpec> NO_TOOLS = List.of();
    private static final String SYS = "sys";

    private static OpenAiCompatProvider provider() {
        // "test-key" 是占位假值：本用例只组 body，从不发请求；绝非真实密钥
        return new OpenAiCompatProvider("t", "http://localhost", "test-key", "m");
    }

    private static Msg.Tool tool(String callId, String name) {
        return new Msg.Tool(callId, name, "回执 " + name, true);
    }

    private static Msg.Assistant asst(String callId, String name) {
        return new Msg.Assistant("", List.of(new ToolCall(callId, name, "{}")));
    }

    private static byte[] wireBytes(JsonObject body) {
        JsonArray messages = body.getAsJsonArray("messages");
        return messages.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static int commonPrefixLen(byte[] a, byte[] b) {
        int n = Math.min(a.length, b.length);
        for (int i = 0; i < n; i++) {
            if (a[i] != b[i]) {
                return i;
            }
        }
        return n;
    }

    /** 公共节奏：一轮 status（assistant 调用 + tool 回执）+ 步边界推进检查点。 */
    private static void statusRound(Conversation c, int k) {
        c.add(asst("a" + k, "status"));
        c.add(tool("t" + k, "status"));
        c.onStepBoundary();
    }

    private static boolean isFolded(Msg m) {
        return m instanceof Msg.Tool t && t.content().contains("过期回执已折叠");
    }

    // ---------------------------------------------------------------
    // 1) 卡上验收主命题：同历史连续 3 次 buildBody，messages 前 90% 字节相同
    // ---------------------------------------------------------------

    @Test
    void sameHistoryThreeBuildsShareByteIdenticalPrefix() {
        var c = new Conversation(1_000_000);
        c.add(new Msg.User("挖三块石头进箱"));
        c.onStepBoundary();
        for (int i = 0; i < 10; i++) {
            statusRound(c, i);
        }
        var p = provider();
        byte[] b1 = wireBytes(p.buildBody(SYS, c.outboundHistory(), NO_TOOLS));
        byte[] b2 = wireBytes(p.buildBody(SYS, c.outboundHistory(), NO_TOOLS));
        byte[] b3 = wireBytes(p.buildBody(SYS, c.outboundHistory(), NO_TOOLS));
        assertTrue(b1.length > 100, "历史要有体量，断言才有意义");
        int n90 = b1.length * 90 / 100;
        for (int i = 0; i < n90; i++) {
            assertEquals(b1[i], b2[i], "第 2 次构建在前 90% 内第 " + i + " 字节裂开");
            assertEquals(b1[i], b3[i], "第 3 次构建在前 90% 内第 " + i + " 字节裂开");
        }
        assertArrayEquals(b1, b3, "同历史纯函数应整体相等（outboundHistory 不许有副作用）");
    }

    // ---------------------------------------------------------------
    // 2) 增长中的前缀恒等：每步边界后，旧请求体仍是新请求体的字节前缀
    // ---------------------------------------------------------------

    @Test
    void prefixStableAcrossStepBoundaries() {
        var c = new Conversation(1_000_000);
        var p = provider();
        c.add(new Msg.User("开始"));
        c.onStepBoundary();
        var prevOut = c.outboundHistory();
        int prevCp = c.foldCheckpoint();
        byte[] prev = wireBytes(p.buildBody(SYS, prevOut, NO_TOOLS));
        for (int i = 0; i < 12; i++) {
            statusRound(c, i);
            var curOut = c.outboundHistory();
            byte[] cur = wireBytes(p.buildBody(SYS, curOut, NO_TOOLS));
            // 不变式：上一请求里 index < 旧检查点 的那段字节，必是新请求同段字节的真前缀
            //（冻结区只追不改；唯一的字节翻转发生在跨越检查点的尾部，位置随历史增长而推进）。
            byte[] frozenPrev = wireBytes(p.buildBody(SYS, prevOut.subList(0, prevCp), NO_TOOLS));
            byte[] frozenCur = wireBytes(p.buildBody(SYS, curOut.subList(0, prevCp), NO_TOOLS));
            assertArrayEquals(frozenPrev, frozenCur,
                    "第 " + i + " 步：旧检查点之前的字节前缀被撕裂（cache 必不命中）");
            // 整请求层面：新旧序列化的公共前缀至少盖住整个旧冻结区（排除数组收尾符差异，
            // 取冻结区序列化长度与 90% 旧体的较小者作为下限太花哨，直接按公共前缀单调推进断言）
            int common = commonPrefixLen(prev, cur);
            assertTrue(common >= frozenPrev.length - 2,
                    "第 " + i + " 步：公共字节前缀 " + common + " < 冻结区体量 " + frozenPrev.length);
            prevOut = curOut;
            prevCp = c.foldCheckpoint();
            prev = cur;
            assertTrue(prevCp > 0 || i < 5, "热身过后检查点必须开始推进");
        }
    }

    // ---------------------------------------------------------------
    // 3) FOLD_KEEP_TAIL 边界：13 条时折第 1 条，12 条时不折
    // ---------------------------------------------------------------

    @Test
    void foldKeepsTailWindowExactly() {
        // 13 条：检查点 = 13-12 = 1 → index 0 的旧 status 有后来者 → 折叠
        var c13 = new Conversation(1_000_000);
        c13.add(tool("c-old", "status"));
        for (int i = 0; i < 11; i++) {
            c13.add(new Msg.User("filler " + i));
        }
        c13.add(tool("c-new", "status"));
        assertEquals(13, c13.history().size());
        c13.onStepBoundary();
        assertEquals(1, c13.foldCheckpoint());
        assertTrue(isFolded(c13.outboundHistory().get(0)), "13 条时第 1 条必须折叠");

        // 12 条：检查点 = max(0, 12-12) = 0 → 不折
        var c12 = new Conversation(1_000_000);
        c12.add(tool("c-old", "status"));
        for (int i = 0; i < 10; i++) {
            c12.add(new Msg.User("filler " + i));
        }
        c12.add(tool("c-new", "status"));
        assertEquals(12, c12.history().size());
        c12.onStepBoundary();
        assertEquals(0, c12.foldCheckpoint());
        assertEquals("回执 status", ((Msg.Tool) c12.outboundHistory().get(0)).content(),
                "12 条时旧回执落在保留窗口里，不许折");
    }

    // ---------------------------------------------------------------
    // 4) 检查点单调不回退 + 折叠决定一次算定永不重算
    // ---------------------------------------------------------------

    @Test
    void checkpointMonotonicAndFoldDecisionsFrozen() {
        var c = new Conversation(1_000_000);
        c.add(new Msg.User("u"));                      // 0
        c.add(tool("t-a", "status"));                  // 1 ← 冻结时"最新"，未折
        for (int i = 0; i < 12; i++) {                 // 2..13 无同名后来者
            c.add(new Msg.User("filler " + i));
        }
        c.onStepBoundary();                            // size=14 cp=2 → 冻结 index 0..1
        assertTrue(c.foldCheckpoint() > 1);
        assertFalse(isFolded(c.outboundHistory().get(1)), "冻结时点无后来者 → 未折");

        // 后来者出现：已冻结的决定不得重算翻案
        c.add(tool("t-b", "status"));
        assertFalse(isFolded(c.outboundHistory().get(1)), "frozenFolded 永不重算");

        // 单调性：继续推进只会更大不会回退
        int before = c.foldCheckpoint();
        for (int i = 0; i < 12; i++) {
            c.add(new Msg.User("more " + i));
        }
        c.onStepBoundary();
        assertTrue(c.foldCheckpoint() >= before, "foldCheckpoint 单调不回退");
        assertTrue(c.foldCheckpoint() > 0);
    }

    // ---------------------------------------------------------------
    // 5) 指令边界归零 + prefix reset 事件（计数 + 回调可观测）
    // ---------------------------------------------------------------

    @Test
    void directiveBoundaryZeroesCheckpointAndCountsPrefixReset() {
        var c = new Conversation(1_000_000);
        c.add(new Msg.User("旧指令"));
        for (int i = 0; i < 8; i++) {
            statusRound(c, i);
        }
        assertTrue(c.foldCheckpoint() > 0, "步边界推进过");
        int resetsBefore = c.prefixResets();
        c.onNewDirective();
        assertEquals(0, c.foldCheckpoint(), "指令边界归零策略");
        assertEquals(0, c.frozenCount(), "归零时冻结决定一并作废");
        assertEquals(resetsBefore + 1, c.prefixResets(), "归零是一次合法 prefix reset");
    }

    @Test
    void compactionIsAPrefixResetEvent() {
        ChatEngine ok = (s, m, t) -> CompletableFuture.completedFuture(
                new AssistantTurn("- 摘要", List.of(), 0, 0, -1, "stop"));
        var c = new Conversation(6000);
        for (int i = 0; i < 110; i++) {
            c.add(new Msg.User("x".repeat(200)));
        }
        for (int i = 0; i < 8; i++) {
            statusRound(c, i);
        }
        assertTrue(c.foldCheckpoint() > 0);
        int resetsBefore = c.prefixResets();
        String[] hook = {null};
        c.setPrefixResetHook(r -> hook[0] = r);
        c.noteUsage(new AssistantTurn("t", List.of(), 9000, 10, 0, "stop"));
        c.compact(ok).join();
        assertEquals(resetsBefore + 1, c.prefixResets(), "压缩=合法 prefix reset 事件");
        assertEquals("compaction", hook[0], "回调带原因，供 [brain] prefix reset 日志");
        assertEquals(0, c.foldCheckpoint(), "摘要重写历史后旧坐标全部作废");
        assertEquals(0, c.frozenCount());
        assertTrue(c.needsCompaction() == false, "压后闸门熄火（R0 回归口径不变）");
    }

    // ---------------------------------------------------------------
    // 6) PromptBuilder 缓存：启动渲染一次、链中不换（reloadSkills 只挂脏）、
    //    换发只在指令边界
    // ---------------------------------------------------------------

    @Test
    void promptBuilderCachesAndRerendersOnlyAtDirectiveBoundary() {
        int[] renders = {0};
        var pb = new PromptBuilder(() -> {
            renders[0]++;
            return "prompt-v" + renders[0];
        });
        assertEquals("prompt-v1", pb.get(), "构造即读源一次（启动渲染）");
        assertEquals(1, renders[0]);
        pb.get();
        pb.reloadSkills();          // R3 面板钩子：只挂脏
        pb.get();
        assertEquals(1, renders[0], "链中不得重渲染（不裂前缀）");
        assertEquals("prompt-v1", pb.get());
        pb.onDirectiveBoundary();   // 指令边界才换发
        assertEquals("prompt-v2", pb.get(), "脏标记在指令边界兑现");
        pb.onDirectiveBoundary();
        assertEquals("prompt-v2", pb.get(), "没挂脏就不重渲染");
    }
}
