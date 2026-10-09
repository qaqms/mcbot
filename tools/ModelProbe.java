import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.neko.mcbot.agent.ClientToolDefs;
import com.neko.mcbot.agentcore.llm.LlmClient;
import com.neko.mcbot.agentcore.llm.LlmFailure;
import com.neko.mcbot.agentcore.llm.ModelConnectionTest;
import com.neko.mcbot.agentcore.llm.Msg;
import com.neko.mcbot.agentcore.prompt.PromptBuilder;
import com.neko.mcbot.agentcore.prompt.SkillLoader;
import com.neko.mcbot.agentcore.provider.OpenAiCompatProvider;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Explicit, bounded model probes: credentials stay in memory and no game executor is installed. */
class ModelProbe {
    public static void main(String[] args) {
        if (args.length != 2 || !List.of("task", "connection").contains(args[1])) {
            System.out.println("Usage: ModelProbe <client.json> task|connection");
            return;
        }
        try {
            Path configPath = Path.of(args[0]);
            JsonObject config = JsonParser.parseString(Files.readString(configPath)).getAsJsonObject();
            var provider = new OpenAiCompatProvider("probe",
                    config.get("base_url").getAsString(), config.get("api_key").getAsString(),
                    config.get("model").getAsString());
            LlmClient engine = new LlmClient(provider, Duration.ofSeconds(45),
                    diagnostic -> System.out.println("request " + diagnostic.summary()),
                    diagnostic -> System.out.println("response " + diagnostic.summary()));
            if ("connection".equals(args[1])) {
                boolean compatible = ModelConnectionTest.run(engine).get(100, TimeUnit.SECONDS);
                System.out.println("tool_round_trip=" + compatible);
            } else {
                String persona = config.has("persona") ? config.get("persona").getAsString() : "";
                String prompt = PromptBuilder.build(persona,
                        SkillLoader.load(configPath.getParent().resolve("skills")));
                var turn = engine.chat(prompt,
                        List.of(new Msg.User("查看自己的状态，然后扫描附近并简报。")),
                        ClientToolDefs.SPECS).get(50, TimeUnit.SECONDS);
                System.out.println("task_response text_chars=" + turn.text().length()
                        + " tool_calls=" + turn.toolCalls().size()
                        + " read_only=" + (!turn.toolCalls().isEmpty()
                            && turn.toolCalls().stream().allMatch(call ->
                                List.of("status", "scan_area").contains(call.name()))));
            }
        } catch (Exception failure) {
            // Neither response content nor exception details may contain credentials in output.
            System.out.println(LlmFailure.userMessage(failure));
        }
    }

}
