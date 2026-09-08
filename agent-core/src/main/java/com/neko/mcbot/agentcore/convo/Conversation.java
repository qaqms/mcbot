package com.neko.mcbot.agentcore.convo;

import com.neko.mcbot.agentcore.llm.AssistantTurn;
import com.neko.mcbot.agentcore.llm.ChatEngine;
import com.neko.mcbot.agentcore.llm.Msg;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * 对话窗口：全量存储 + 出站视图 + 摘要前缀。上下文经济学三件套：
 *
 * 1) 触发用真数：闸门比较 max(API 回报的 prompt_tokens, 本地 CJK 感知估算)——
 *    估算只当兜底（不少中转站不回 usage 帧）。
 * 2) 压缩切分守铁律：新摘要只替换"旧段"；近段按 token 预算原文保留，
 *    切点只允许落在 User（优先）或 Assistant 边界——**绝不从 Tool 消息下刀**，
 *    否则孤儿工具回执会让下一请求直接 400。预算内找不到合法切点就整段总结。
 * 3) 过期回执折叠（R2-B 后：检查点 + 冻结决定）：折叠只发生在 foldCheckpoint 之前
 *    的"冻结区"，且每条消息折/不折在一次步边界算定后永不重算——同历史多次
 *    outboundHistory() 字节恒等，增长中的历史旧前缀不被新消息撕裂，prompt cache
 *    才吃得到。存储永远是全量（展示/未来持久化用），瘦身只发生在出站视图。
 *
 * 前缀 reset 仅两处合法事件：压缩（摘要插 index 0，旧字节前缀整体作废）与
 * 指令边界归零策略；每次 reset 计数并回调（纯库无日志框架，[brain] 日志由宿主接）。
 *
 * 熔断：摘要端点连续失败 2 次后停止自动压缩（每条新指令恢复尝试资格），
 * 免得每步都拖一次注定失败的往返。
 */
public final class Conversation {

    private static final String SUMMARY_PREFIX = "[对话前情提要] ";
    /** 近段原文保留的 token 预算（估算口径见 estimateTokens）。 */
    public static final int KEEP_BUDGET_TOKENS = 1500;
    /** 步边界时保留原文的尾部窗口（条）：此内的工具回执永不折叠，
     *  免得模型看不见刚才的坐标（R2-B 定数）。 */
    public static final int FOLD_KEEP_TAIL = 12;
    private static final int COMPACT_FAILURES_MAX = 2;
    /** 单条消息的结构开销（角色/定界），粗粒度吸收误差。 */
    private static final int MSG_OVERHEAD = 8;

    private final List<Msg> msgs = new ArrayList<>();
    private final int compactAtTokens;
    private int realPromptTokens;
    private int compactFailures;

    /** 折叠检查点：index < cp 的消息进入冻结区；同一条指令内单调不回退，
     *  只在两类合法 prefix reset 事件（压缩/指令边界归零）时归零。 */
    private int foldCheckpoint;
    /** 冻结的折叠决定：frozenFolded.get(i) = 第 i 条冻结时算定的折/不折；
     *  invariant: frozenFolded.size() == foldCheckpoint。 */
    private final List<Boolean> frozenFolded = new ArrayList<>();
    private int prefixResets;
    private Consumer<String> prefixResetHook;

    public Conversation(int compactAtTokens) {
        this.compactAtTokens = compactAtTokens;
    }

    public void add(Msg m) {
        msgs.add(m);
    }

    /** 全量存储（面板回看/持久化视图）。 */
    public List<Msg> history() {
        return List.copyOf(msgs);
    }

    /**
     * 出站视图（R2-B：纯函数）：只读 frozenFolded，不再现场扫"后来者"。
     * 同一历史多次调用字节恒等；assistant.tool_calls ↔ tool.tool_call_id 配对不动。
     */
    public List<Msg> outboundHistory() {
        List<Msg> out = new ArrayList<>(msgs.size());
        for (int i = 0; i < msgs.size(); i++) {
            Msg m = msgs.get(i);
            if (m instanceof Msg.Tool t && i < foldCheckpoint && frozenFolded.get(i)) {
                out.add(new Msg.Tool(t.callId(), t.name(),
                        "(过期回执已折叠：后来又有 " + t.name() + " 更新的结果)", t.ok()));
            } else {
                out.add(m);
            }
        }
        return out;
    }

    /**
     * 步边界（每次问模型之前调）：检查点推进到 max(旧, size - FOLD_KEEP_TAIL)，
     * 新入冻结区的消息在此一次性算定折/不折（判据：当时历史里是否有同名更新的
     * 回执），之后永不重算——这是前缀字节稳定的全部机制。
     */
    public void onStepBoundary() {
        int target = Math.max(foldCheckpoint, Math.min(Math.max(msgs.size() - FOLD_KEEP_TAIL, 0), msgs.size()));
        while (foldCheckpoint < target) {
            Msg m = msgs.get(foldCheckpoint);
            // 只有工具回执有折叠语义；其余类型冻结为"不折"
            frozenFolded.add(m instanceof Msg.Tool t && hasNewerToolWithSameName(foldCheckpoint, t.name()));
            foldCheckpoint++;
        }
    }

    /**
     * 指令边界：检查点与冻结决定归零（卡上的"归零策略"，两类合法 prefix reset 之一）。
     * 归零后下一条指令的首个步边界按新窗口重算——新指令本就开新前缀，代价只裂一次。
     */
    public void onNewDirective() {
        resetFoldState();
        firePrefixReset("directive-boundary");
    }

    /** 观测用（测试/宿主日志）：当前检查点。 */
    public int foldCheckpoint() {
        return foldCheckpoint;
    }

    /** 观测用：已冻结决定的条数（不变式下恒等于 foldCheckpoint）。 */
    public int frozenCount() {
        return frozenFolded.size();
    }

    /** 合法 prefix reset 事件累计次数（压缩 + 指令边界归零）。 */
    public int prefixResets() {
        return prefixResets;
    }

    /** 宿主接日志用（纯库不引 slf4j）：每次 prefix reset 回调原因。 */
    public void setPrefixResetHook(Consumer<String> hook) {
        this.prefixResetHook = hook;
    }

    private void resetFoldState() {
        foldCheckpoint = 0;
        frozenFolded.clear();
    }

    private void firePrefixReset(String reason) {
        prefixResets++;
        if (prefixResetHook != null) {
            prefixResetHook.accept(reason);
        }
    }

    private boolean hasNewerToolWithSameName(int index, String name) {
        for (int j = msgs.size() - 1; j > index; j--) {
            if (msgs.get(j) instanceof Msg.Tool t && t.name().equals(name)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 真数驱动的水位判断：API 报过就用 API 的（含 system+tools 的真实体量），
     * 本地估算作为下限哨兵。压缩失败熔断期内一律 false。
     */
    public boolean needsCompaction() {
        if (compactFailures >= COMPACT_FAILURES_MAX) {
            return false;
        }
        return Math.max(realPromptTokens, approxTokens()) > compactAtTokens && msgs.size() > 8;
    }

    /** 每条新指令的边界：恢复压缩尝试资格（熔断只惩罚同一条链内的连续失败）。 */
    public void onDirectiveBoundary() {
        compactFailures = 0;
    }

    /** 记录后端回报的真实提示词体量（0/负=没报，保持估算口径）。 */
    public void noteUsage(AssistantTurn turn) {
        if (turn != null && turn.promptTokens() > 0) {
            realPromptTokens = (int) Math.min(Integer.MAX_VALUE, turn.promptTokens());
        }
    }

    /** 压缩成功后调用：估算与真数都按新历史作废——旧段没了，真数还挂着压缩前的大值
     *  会让 needsCompaction 下一步又真（可连发压缩白打端点）；归零退回估算兜底，
     *  下一次真调用会用新口径刷新。 */
    public void noteCompacted() {
        compactFailures = 0;
        realPromptTokens = 0;
        // 折叠坐标同步作废：msgs 被重写（摘要插 index 0），旧 index 的冻结决定已无指代对象；
        // 这也是两类合法 prefix reset 之一。
        resetFoldState();
        firePrefixReset("compaction");
    }

    /** CJK 感知估算：中日韩字符≈1 token/字，其余≈4 字符/token，每条消息 +结构开销。 */
    public static int estimateTokens(String text) {
        if (text == null || text.isEmpty()) {
            return MSG_OVERHEAD;
        }
        long cjk = 0;
        int total = text.length();
        for (int i = 0; i < total; i++) {
            char c = text.charAt(i);
            if (c >= 0x2E80) { // CJK/假名/韩文/全角标点的公共下限
                cjk++;
            }
        }
        return (int) (cjk + (total - cjk) / 4 + MSG_OVERHEAD);
    }

    public int approxTokens() {
        long sum = 0;
        for (Msg m : msgs) {
            sum += estimateTokens(textOf(m));
            if (m instanceof Msg.Assistant a) {
                for (var tc : a.toolCalls()) {
                    sum += estimateTokens(tc.name() + tc.argsJson());
                }
            }
        }
        return (int) Math.min(Integer.MAX_VALUE, sum);
    }

    /**
     * 压缩切分：从最新往回攒到 KEEP_BUDGET_TOKENS，取预算内**最早**的合法切点
     * （User 优先，Assistant 次选；Tool 永不可切）。整段都在预算内则不压缩。
     */
    public static int findCutIndex(List<Msg> history, int keepBudgetTokens) {
        int firstUser = -1;
        int firstAssistant = -1;
        long acc = 0;
        for (int i = history.size() - 1; i >= 0; i--) {
            acc += estimateTokens(textOf(history.get(i)));
            if (acc > keepBudgetTokens) {
                break; // 预算用尽：此位置之前的都归旧段
            }
            Msg m = history.get(i);
            if (m instanceof Msg.User) {
                firstUser = i;
            } else if (m instanceof Msg.Assistant) {
                firstAssistant = i;
            }
        }
        if (firstUser >= 0) {
            return firstUser;
        }
        if (firstAssistant >= 0) {
            return firstAssistant;
        }
        return history.size(); // 预算内没有合法切点（极端：一条消息就超预算）
    }

    /**
     * 交给引擎做摘要：旧段压缩成要点清单，近段原文保留。
     * 失败就地记账并原样返回（宁可多花 token 不可丢上下文）；熔断由 needsCompaction 执行。
     */
    public CompletableFuture<Void> compact(ChatEngine engine) {
        if (compactFailures >= COMPACT_FAILURES_MAX) {
            return CompletableFuture.completedFuture(null); // 熔断期内不打端点
        }
        int cut = findCutIndex(msgs, KEEP_BUDGET_TOKENS);
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
                    noteCompacted();
                })
                .exceptionally(t -> {
                    compactFailures++;
                    return null; // 失败不丢历史，只是这轮不压
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
