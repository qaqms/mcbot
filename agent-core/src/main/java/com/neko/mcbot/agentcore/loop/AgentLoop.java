package com.neko.mcbot.agentcore.loop;

import com.neko.mcbot.agentcore.convo.Conversation;
import com.neko.mcbot.agentcore.llm.AssistantTurn;
import com.neko.mcbot.agentcore.llm.ChatEngine;
import com.neko.mcbot.agentcore.llm.Msg;
import com.neko.mcbot.agentcore.llm.ToolCall;
import com.neko.mcbot.agentcore.llm.ToolSpec;

import java.util.ArrayDeque;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * 大脑状态机：指令 → 问模型 → （有工具调用则）执行并喂回执 → 再问 → 直到纯文本回复。
 * 全程异步；同一实例串行处理指令队列。调用方负责把执行链路上的回调
 * 送回到正确的线程（mod 侧是主线程入队，见 M3）。
 */
public final class AgentLoop {

    public interface Listener {
        default void onReply(String text) {
        }

        default void onToolInvoked(String name, String argsJson, boolean ok, String feedback) {
        }

        default void onNotice(String text) {
        }

        /** 每步模型的 token 真数（cached<0 = 后端没报）。观测用，不影响逻辑。 */
        default void onUsage(long prompt, long completion, long cached) {
        }
    }

    public record Config(int maxStepsPerDirective, int repeatNudgeAt, int repeatAbortAt) {

        public static Config defaults() {
            return new Config(40, 3, 5);
        }
    }

    private final ChatEngine engine;
    private final List<ToolSpec> tools;
    private final ToolExecutor executor;
    private final Config cfg;
    private final Listener listener;
    private final Supplier<String> systemPrompt;
    private final Conversation convo;

    private final ArrayDeque<String> pending = new ArrayDeque<>();
    private boolean running;
    /** 主人叫停标记：在当前链的下一个步边界生效；换发新指令时清零。 */
    private volatile boolean cancelRequested;
    private int steps;
    private String lastCallKey;
    private int repeatCount;
    private boolean stuckAbort;

    public AgentLoop(ChatEngine engine, List<ToolSpec> tools, ToolExecutor executor,
                     Config cfg, Listener listener, Supplier<String> systemPrompt,
                     int conversationSoftTokens) {
        this.engine = engine;
        this.tools = tools;
        this.executor = executor;
        this.cfg = cfg;
        this.listener = listener;
        this.systemPrompt = systemPrompt;
        this.convo = new Conversation(conversationSoftTokens);
    }

    public Conversation conversation() {
        return convo;
    }

    public synchronized void submit(String directive) {
        pending.add(directive);
        if (!running) {
            running = true;
            pump();
        }
    }

    /**
     * 主人叫停：排队中的指令直接丢弃；正在跑的这条在下一个边界停下
     * （若它正等某个工具回执，服务端会把该任务的 future 以 CANCELLED 完成，链即续跑到步首）。
     */
    public synchronized void cancelDirective() {
        pending.clear();
        cancelRequested = true;
    }

    private void pump() {
        String d = pending.poll();
        if (d == null) {
            running = false;
            cancelRequested = false;
            return;
        }
        cancelRequested = false; // 叫停只对当时那条链有效，不追溯新指令
        convo.onDirectiveBoundary(); // 熔断计数随新指令重置
        steps = 0;
        lastCallKey = null;
        repeatCount = 0;
        stuckAbort = false;
        convo.add(new Msg.User(d));
        step();
    }

    /** 消费叫停标记：命中则回一句停手并推进到下一条（队列已被清空即下线）。 */
    private boolean consumeCancel() {
        if (!cancelRequested) {
            return false;
        }
        cancelRequested = false;
        listener.onNotice("cancelled by owner");
        listener.onReply("（收到，我先停手，等你的下一步指示。）");
        pump();
        return true;
    }

    private void step() {
        if (consumeCancel()) {
            return;
        }
        if (stuckAbort) {
            listener.onReply("[内部] 我在同一个操作上反复无进展，先停下了。");
            pump();
            return;
        }
        if (++steps > cfg.maxStepsPerDirective()) {
            listener.onReply("[内部] 这个任务步数超限，我停下了。");
            pump();
            return;
        }

        CompletableFuture<?> ready = convo.needsCompaction()
                ? convo.compact(engine)
                : CompletableFuture.completedFuture(null);

        ready.thenCompose(v -> engine.chat(systemPrompt.get(), convo.outboundHistory(), tools))
                .thenAccept(turn -> {
                    convo.noteUsage(turn);
                    listener.onUsage(turn.promptTokens(), turn.completionTokens(), turn.cachedTokens());
                    if (consumeCancel()) {
                        return; // 叫停优先于这一轮：整轮丢弃，既不执行工具也不作答
                    }
                    convo.add(new Msg.Assistant(turn.text(), turn.toolCalls()));
                    if (!turn.hasToolCalls()) {
                        listener.onReply(turn.text());
                        pump();
                        return;
                    }
                    runToolCalls(turn.toolCalls()).thenRun(this::step);
                })
                .exceptionally(t -> {
                    String msg = t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
                    listener.onReply("[内部] 模型调用失败：" + msg);
                    pump();
                    return null;
                });
    }

    private CompletableFuture<Void> runToolCalls(List<ToolCall> calls) {
        CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
        for (ToolCall tc : calls) {
            chain = chain.thenCompose(v -> executor.execute(tc.name(), tc.argsJson())
                    .thenAccept(r -> {
                        convo.add(new Msg.Tool(tc.id(), tc.name(), r.feedback(), r.ok()));
                        listener.onToolInvoked(tc.name(), tc.argsJson(), r.ok(), r.feedback());
                        // 打转判定：同调用**且同结果**才累计——TIMEOUT 后原参重试是合法恢复
                        // （参考项目用真事故换的教训），结果一变说明世界在动，不是空转。
                        String key = tc.name() + '|' + tc.argsJson() + '|' + r.ok()
                                + '|' + r.feedback().hashCode();
                        repeatCount = key.equals(lastCallKey) ? repeatCount + 1 : 1;
                        lastCallKey = key;
                        if (repeatCount == cfg.repeatNudgeAt()) {
                            convo.add(new Msg.Nudge("[系统] 你已连续 " + repeatCount
                                    + " 次执行完全相同的操作且结果相同。换一个做法，或直接向主人说明障碍。"));
                            listener.onNotice("nudge@" + repeatCount);
                        } else if (repeatCount >= cfg.repeatAbortAt()) {
                            stuckAbort = true;
                            listener.onNotice("abort@" + repeatCount);
                        }
                    }));
        }
        return chain;
    }
}
