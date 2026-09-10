package com.neko.mcbot.agentcore.llm;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/** loop 只依赖这个窄接口（单测可换假实现），真实实现是 LlmClient + provider。 */
public interface ChatEngine {

    CompletableFuture<AssistantTurn> chat(String systemPrompt, List<Msg> convo, List<ToolSpec> tools);

    /**
     * 带流式回调的重载（R2-A）。
     *
     * @param accumulate {@code true} = 仍要把整轮拼出来（默认路径，历史与 usage 都靠它）；
     *                   {@code false} = 宿主只关心过程（例如已经派发完工具、不需要整轮对象），
     *                   实现可以省掉拼接。返回的 future 仍必须完成（失败照旧以异常完成）。
     *
     * <p>默认实现是给"只会阻塞式回答"的引擎的兜底：照旧拿整轮，再补一次
     * {@link TurnSink#onComplete}。真正流式的实现（LlmClient）覆盖它。
     */
    default CompletableFuture<AssistantTurn> chat(String systemPrompt, List<Msg> convo,
                                                  List<ToolSpec> tools, TurnSink sink,
                                                  boolean accumulate) {
        CompletableFuture<AssistantTurn> f = chat(systemPrompt, convo, tools);
        if (sink != null) {
            f.whenComplete((turn, err) -> sink.onComplete(turn, err));
        }
        return f;
    }
}
