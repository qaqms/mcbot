package com.neko.mcbot.agentcore.loop;

import java.util.concurrent.CompletableFuture;

/** 工具执行抽象：harness 里是本地实现；mod 里是"payload 发往服务器、回执异步回来"。 */
public interface ToolExecutor {

    /**
     * 一次工具调用的落账结果。
     *
     * <p><b>{@code accepted} 与 {@code jobId} 是 R2-S4「受理即回执」的机器可读信号</b>：
     * 服务端对 {@code move_to}/{@code break_block} 这类跨 tick 长活，先回一条
     * <b>受理回执</b>（"我开始了，这条还没有结果"），真正的结果晚几秒到几十秒才来。
     * 于是：
     * <ul>
     *   <li>{@code accepted=true} ⇒ 这条 {@code tool_call} **还不能写进对话**（写了就等于告诉模型
     *       "事情做完了"），{@link AgentLoop} 会把这条指令 **PARK** 住，等 {@code job_event} 再续；</li>
     *   <li>{@code jobId} 是后续事件的关联键（服务端生成，同一同伴内唯一）。</li>
     * </ul>
     *
     * <p><b>为什么是字段而不是去认 {@code ACCEPTED:} 前缀</b>：前缀是给**模型看**的话术
     * （契约要求回执文本以 {@code ACCEPTED:} 开头，让模型学会"别干等"），而"要不要 park"
     * 是控制流。用字符串前缀做控制流，任何一个工具的回执恰好以这几个字开头就会误判；
     * 字段是编译期可查的。两者都要：文本前缀管教学，字段管逻辑。
     *
     * <p>2 参构造器保持旧调用点零改动（{@code accepted=false, jobId=null}）。
     */
    record ToolOutcome(boolean ok, String feedback, boolean accepted, String jobId) {

        /** 受理回执的文本前缀（**契约常量**，模型教学与桥播报都认它）。 */
        public static final String ACCEPTED_PREFIX = "ACCEPTED:";

        /** 旧语义：一次调用 = 一个终局结果。 */
        public ToolOutcome(boolean ok, String feedback) {
            this(ok, feedback, false, null);
        }

        /** 服务端只说"我受理了"：还没有结果，别当成结果写进对话。 */
        public static ToolOutcome accepted(String jobId, String feedback) {
            return new ToolOutcome(true, feedback, true, jobId);
        }

        /** 合成回执（叫停/被顶）：本地生成，不来自服务端。 */
        public static ToolOutcome synthetic(String feedback) {
            return new ToolOutcome(false, feedback, false, null);
        }
    }

    CompletableFuture<ToolOutcome> execute(String name, String argsJson);
}
