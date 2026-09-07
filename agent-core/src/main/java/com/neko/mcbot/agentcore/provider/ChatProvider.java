package com.neko.mcbot.agentcore.provider;

import com.google.gson.JsonObject;
import com.neko.mcbot.agentcore.llm.Msg;
import com.neko.mcbot.agentcore.llm.ToolSpec;

import java.util.List;
import java.util.Map;

/** 各家 LLM 的"线上方言"。v1 只实现 OpenAI 兼容族；Anthropic 原生协议留 v2。 */
public interface ChatProvider {

    String name();

    /** chat/completions 完整 URL。 */
    String endpoint();

    Map<String, String> authHeaders();

    /** 组装请求体（含 stream:true）。systemPrompt 为空时省略 system 消息。 */
    JsonObject buildBody(String systemPrompt, List<Msg> convo, List<ToolSpec> tools);

    /** 解析一个 SSE data 载荷（已去掉 "data:" 前缀），增量喂进 builder。 */
    void acceptChunk(JsonObject chunk, TurnBuilder builder);

    /** 该方言的流结束哨兵（如 "[DONE]"）；返回 true 表示流终止。 */
    boolean isTerminalData(String dataPayload);
}
