package com.neko.mcbot.agentcore.llm;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/** loop 只依赖这个窄接口（单测可换假实现），真实实现是 LlmClient + provider。 */
public interface ChatEngine {

    CompletableFuture<AssistantTurn> chat(String systemPrompt, List<Msg> convo, List<ToolSpec> tools);
}
