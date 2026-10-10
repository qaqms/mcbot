package com.neko.mcbot.agent;

import com.neko.mcbot.agentcore.llm.AssistantTurn;
import com.neko.mcbot.agentcore.llm.CallbackChatEngine;
import com.neko.mcbot.agentcore.llm.ChatEngine;
import com.neko.mcbot.agentcore.llm.Msg;
import com.neko.mcbot.agentcore.llm.ToolCall;
import com.neko.mcbot.agentcore.llm.ToolSpec;
import com.neko.mcbot.agentcore.llm.TurnSink;
import com.neko.mcbot.agentcore.prompt.PromptBuilder;
import com.neko.mcbot.cfg.ClientConfig;
import com.neko.mcbot.common.Envelope;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

final class OfflineAgentClient implements AgentRunner.ClientServices {
    static final ClientConfig CONFIG =
            new ClientConfig("http://127.0.0.1", "offline-model", "synthetic", "initial");
    static final ClientConfig NEXT =
            new ClientConfig("http://127.0.0.1", "offline-next", "synthetic", "next", false);

    static final class Engine implements ChatEngine {
        final List<List<Msg>> histories = new ArrayList<>();
        final List<String> prompts = new ArrayList<>();
        final List<List<ToolSpec>> definitions = new ArrayList<>();
        final List<CompletableFuture<AssistantTurn>> responses = new ArrayList<>();
        final List<TurnSink> sinks = new ArrayList<>();

        @Override
        public CompletableFuture<AssistantTurn> chat(String system, List<Msg> history, List<ToolSpec> tools) {
            prompts.add(system);
            definitions.add(List.copyOf(tools));
            histories.add(List.copyOf(history));
            var response = new CompletableFuture<AssistantTurn>();
            responses.add(response);
            return response;
        }

        @Override
        public CompletableFuture<AssistantTurn> chat(String system, List<Msg> history, List<ToolSpec> tools,
                                                      TurnSink sink, boolean accumulate) {
            sinks.add(sink);
            return chat(system, history, tools);
        }

        void early(int turn, ToolCall call) { sinks.get(turn).onToolCallReady(0, call); }
        void tools(int turn, ToolCall call) {
            responses.get(turn).complete(new AssistantTurn("", List.of(call), 0, 0, -1, "tool_calls"));
        }
        void reply(int turn, String text) {
            responses.get(turn).complete(new AssistantTurn(text, List.of(), 0, 0, -1, "stop"));
        }
    }

    final List<Engine> engines = new ArrayList<>();
    final List<String> chat = new ArrayList<>();
    final List<ClientConfig> saved = new ArrayList<>();
    final List<Envelope> sent = new ArrayList<>();
    final ArrayDeque<Runnable> queue = new ArrayDeque<>();
    Supplier<AgentRunner> current;
    long now = 1_700_000_000_000L;
    boolean connected = true;

    AgentRunner create() {
        var runner = new AgentRunner(CONFIG, this);
        if (current == null) current = () -> runner;
        return runner;
    }
    Engine engine() { return engines.getLast(); }
    void drain() {
        int budget = 1000;
        while (!queue.isEmpty()) {
            if (--budget == 0) throw new AssertionError("client queue did not settle");
            queue.removeFirst().run();
        }
    }
    Envelope lastTool() {
        return sent.stream().filter(e -> "tool_call".equals(e.kind())).toList().getLast();
    }
    long toolCount() { return sent.stream().filter(e -> "tool_call".equals(e.kind())).count(); }
    long cancelCount() { return sent.stream().filter(e -> "cancel".equals(e.kind())).count(); }

    @Override public ChatEngine engine(ClientConfig cfg) {
        var engine = new Engine();
        engines.add(engine);
        return new CallbackChatEngine(engine, this::executeOnClient);
    }
    @Override public PromptBuilder prompt(ClientConfig cfg) { return new PromptBuilder(() -> cfg.persona); }
    @Override public void showChat(String text) { chat.add(text); }
    @Override public void sendCancel(AgentRunner owner) {
        executeOnClient(() -> {
            if (isCurrentRunner(owner) && canSend()) send(new Envelope("cancel", new com.google.gson.JsonObject()));
        });
    }
    @Override public void saveConfig(ClientConfig cfg) { saved.add(cfg); }
    @Override public long nowMs() { return now; }
    @Override public boolean inGame() { return connected; }
    @Override public void executeOnClient(Runnable action) { queue.addLast(action); }
    @Override public boolean canSend() { return connected; }
    @Override public void send(Envelope envelope) { sent.add(envelope); }
    @Override public boolean isCurrentRunner(AgentRunner owner) { return current.get() == owner; }
}
