package com.neko.mcbot.agentcore.llm;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/** A harmless tool round trip; none of these calls are forwarded to Minecraft. */
public final class ModelConnectionTest {
    private ModelConnectionTest() {}

    private static final ToolSpec PROBE = ToolSpec.of("connection_probe",
            "Check the tool protocol. Call this exactly once with no arguments.",
            "{\"type\":\"object\",\"properties\":{},\"additionalProperties\":false}");

    public static CompletableFuture<Boolean> run(ChatEngine engine) {
        List<Msg> history = List.of(new Msg.User("Call connection_probe once, then reply OK."));
        return engine.chat("This is a connection test, not a game task.", history, List.of(PROBE))
                .thenCompose(turn -> {
                    if (turn.toolCalls().size() != 1) return CompletableFuture.completedFuture(false);
                    ToolCall call = turn.toolCalls().getFirst();
                    if (!"connection_probe".equals(call.name()) || call.id().isBlank()) {
                        return CompletableFuture.completedFuture(false);
                    }
                    List<Msg> continued = List.of(history.getFirst(),
                            new Msg.Assistant(turn.text(), turn.toolCalls()),
                            new Msg.Tool(call.id(), call.name(), "OK", true));
                    return engine.chat("This is a connection test, not a game task.", continued, List.of(PROBE))
                            .thenApply(reply -> reply.toolCalls().isEmpty() && !reply.text().isBlank());
                });
    }
}
