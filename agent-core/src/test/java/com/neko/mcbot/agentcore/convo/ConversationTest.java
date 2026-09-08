package com.neko.mcbot.agentcore.convo;

import com.neko.mcbot.agentcore.llm.AssistantTurn;
import com.neko.mcbot.agentcore.llm.ChatEngine;
import com.neko.mcbot.agentcore.llm.Msg;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** M4.5 上下文经济学：估算口径、切分铁律、出站折叠、真数水位与熔断。 */
class ConversationTest {

    private static Msg.User user(int size) {
        return new Msg.User("x".repeat(size));
    }

    private static Msg.Assistant asst() {
        return new Msg.Assistant("好的", List.of());
    }

    private static Msg.Tool tool(String name, int size) {
        return new Msg.Tool("c-" + name, name, "回执内容".repeat(size), true);
    }

    @Test
    void cjkEstimatorCountsOneTokenPerChar() {
        // 100 个汉字 ≈ 100 token + 结构开销；纯 ASCII 100 字符 ≈ 25 + 开销
        assertEquals(108, Conversation.estimateTokens("汉".repeat(100)));
        assertEquals(33, Conversation.estimateTokens("a".repeat(100)));
        // 旧口径（/3）会把中文场景低估约 3 倍——这就是回归锚点
        assertTrue(Conversation.estimateTokens("挖掉".repeat(500)) > 500 * 2 / 3);
    }

    @Test
    void cutNeverLandsOnToolAndPrefersUserBoundary() {
        // [U, A, T, A(带call), T, U, A, T] 近段预算只够后段时，切点必须落在 U（优先）
        List<Msg> hist = List.of(
                user(2000), asst(), tool("status", 200),
                asst(), tool("scan", 200), user(200), asst(), tool("status", 5));
        int cut = Conversation.findCutIndex(hist, 1500);
        assertTrue(hist.get(cut) instanceof Msg.User, "切点必须是 User，实际 index=" + cut);
        assertEquals(5, cut);
    }

    @Test
    void degradesToEmptyTailWhenNoLegalCutInBudget() {
        // 单条巨型消息超预算：找不到合法切点 → cut=size → compact 退化为不压缩（不产生孤儿 Tool）
        List<Msg> hist = List.of(tool("scan", 9999));
        assertEquals(1, Conversation.findCutIndex(hist, 10));
    }

    @Test
    void outboundFoldsSupersededToolReceiptsOnly() {
        // R2-B 后语义：折叠只作用于检查点（步边界推进到 size-FOLD_KEEP_TAIL）之前的
        // 冻结区——所以场景要拉长到 13 条才有折叠资格，尾巴 12 条永远保留原文。
        var c = new Conversation(1_000_000);
        c.add(user(10));
        c.add(tool("status", 100));   // 旧 status 回执（冻结区内）→ 应折叠
        c.add(tool("scan_area", 100)); // 最新 scan → 保留
        c.add(tool("status", 5));      // 检查点之后的未冻结区 → 保留
        for (int i = 0; i < 8; i++) {
            c.add(user(1));            // 拉长历史使旧回执获得折叠资格（保留尾 12 条）
        }
        c.add(tool("status", 7));      // 真正的最新 status → 保留
        assertEquals(13, c.history().size());
        c.onStepBoundary();            // 步边界：检查点 = 13-12 = 1，只冻 index0
        // 要折 index1 需要检查点 > 1：再补一条后推进（窗口前移，决定冻结不重算）
        c.add(user(1));
        c.onStepBoundary();
        var out = c.outboundHistory();
        assertEquals(14, out.size(), "只瘦内容不动结构");
        var old = (Msg.Tool) out.get(1);
        assertTrue(old.content().contains("过期回执已折叠"), "被更新者应折叠");
        assertEquals("c-status", old.callId(), "配对信息（callId）不许丢");
        assertTrue(((Msg.Tool) out.get(2)).content().startsWith("回执内容"),
                "同名只出现一次的不折（scan 无后来者）");
        assertFalse(((Msg.Tool) out.get(3)).content().contains("折叠"),
                "检查点之后（保留窗口/未冻结区）不得折");
        assertTrue(((Msg.Tool) out.get(12)).content().startsWith("回执内容"), "保留窗口内不折");
        assertEquals(14, c.history().size(), "存储侧永远全量");
    }

    @Test
    void realPromptTokensFromUsageDriveTheGate() {
        var c = new Conversation(1000);
        for (int i = 0; i < 9; i++) {
            c.add(user(50)); // 估算很小（9*~21 token）
        }
        assertFalse(c.needsCompaction(), "估算未超水位");
        c.noteUsage(new AssistantTurn("t", List.of(), 5000, 10, 4000, "stop"));
        assertTrue(c.needsCompaction(), "API 真数 5000 超水位必须触发（中文低估问题的解药）");
    }

    @Test
    void compactionFailureBreakerOpensAndResetsAtDirectiveBoundary() {
        int[] calls = {0};
        ChatEngine failing = (s, c, t) -> {
            calls[0]++;
            return CompletableFuture.failedFuture(new RuntimeException("summary endpoint down"));
        };
        var c = new Conversation(100);
        for (int i = 0; i < 20; i++) {
            c.add(user(4000));
        }
        assertTrue(c.needsCompaction());
        c.compact(failing).join();
        c.compact(failing).join();
        assertEquals(2, calls[0]);
        assertFalse(c.needsCompaction(), "连续失败 2 次必须熔断自动路");
        c.compact(failing).join();
        assertEquals(2, calls[0], "熔断期内直调 compact 也不得再打端点");
        c.onDirectiveBoundary();
        c.compact(failing).join();
        assertEquals(3, calls[0], "新指令边界恢复尝试资格");
        // 失败绝不丢历史
        assertEquals(20, c.history().size());
    }

    @Test
    void compactedClearsRealTokenGateSoItCannotFireTwiceInARow() {
        ChatEngine ok = (s, m, t) ->
                CompletableFuture.completedFuture(new AssistantTurn("- 摘要", List.of(), 0, 0, -1, "stop"));
        var conv = new Conversation(6000);   // 生产同口径水位
        for (int i = 0; i < 110; i++) {
            conv.add(user(200));             // ≈58 token/条：压后近段 ≈1500（<6000）但条数 ≈26（>8），
                                             // 闸门的熄火与否只剩"旧真数是否作废"一个变量——才有鉴别力
        }
        conv.noteUsage(new AssistantTurn("t", List.of(), 9000, 10, 0, "stop"));
        assertTrue(conv.needsCompaction());
        conv.compact(ok).join();
        // R0 回归钉：真数若不随压缩作废，max(旧 9000, 新估算 1500) 会下一步又真 → 白打端点的连发压缩
        assertFalse(conv.needsCompaction(), "压缩成功后闸门必须立即熄火");
        // 下一次 API 真数回来照常驱动
        conv.noteUsage(new AssistantTurn("t", List.of(), 9000, 10, 0, "stop"));
        assertTrue(conv.needsCompaction());
    }
}
