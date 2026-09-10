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

    /** 解析一个 SSE data 载荷（已去掉 "data:" 前缀），增量喂进 target（聚合器或流式读取器）。 */
    void acceptChunk(JsonObject chunk, TurnSinkTarget target);

    /** 该方言的流结束哨兵（如 "[DONE]"）；返回 true 表示流终止。 */
    boolean isTerminalData(String dataPayload);

    /**
     * 换一个 baseUrl 的同款 provider（R2-A：根路径被 CF 墙接管时换道到 /v1 重试）。
     * 返回 {@code this} 表示该方言不支持换道——此时自动重试直接不发生。
     *
     * <p>为什么要求返回新对象而不是就地改：provider 实例被 {@code LlmClient} 长期持有，
     * 就地改 baseUrl 会把一次偶然的重试变成"以后所有请求都走新地址"的持久副作用。
     */
    default ChatProvider withBaseUrl(String baseUrl) {
        return this;
    }
}
