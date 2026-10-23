import com.google.gson.JsonObject;
import com.neko.mcbot.agent.ClientToolDefs;
import com.neko.mcbot.agent.ToolCatalog;
import com.neko.mcbot.agentcore.llm.AssistantTurn;
import com.neko.mcbot.agentcore.llm.ChatEngine;
import com.neko.mcbot.agentcore.llm.Msg;
import com.neko.mcbot.agentcore.llm.ToolCall;
import com.neko.mcbot.agentcore.loop.AgentLoop;
import com.neko.mcbot.agentcore.loop.GoalCheckpoint;
import com.neko.mcbot.agentcore.loop.GoalJournal;
import com.neko.mcbot.agentcore.loop.ToolExecutor;
import com.neko.mcbot.agentcore.prompt.PromptBuilder;
import com.neko.mcbot.agentcore.prompt.SkillCatalog;
import com.neko.mcbot.agentcore.provider.OpenAiCompatProvider;
import com.neko.mcbot.path.PathSteering;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** Offline structural measurements; never loads player configuration or opens a connection. */
class FrameworkPerfProbe {
    private static volatile int sink;

    public static void main(String[] args) throws Exception {
        if (args.length != 0) throw new IllegalArgumentException("This offline probe takes no arguments");
        Locale.setDefault(Locale.ROOT);
        System.out.println("scope=offline synthetic_inputs=true native_game=false model_network=false");
        payload();
        knowledge();
        parkedLoop();
        steering();
        journal();
    }

    private static int bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8).length;
    }

    private static void payload() {
        var provider = new OpenAiCompatProvider("offline", "http://127.0.0.1", "unused", "fixture");
        var history = List.<Msg>of(new Msg.User("offline measurement"));
        String base = PromptBuilder.build("", List.of());
        var specs = ToolCatalog.MODEL_SPECS;
        int empty = bytes(provider.buildBody("", history, List.of()).toString());
        int baseOnly = bytes(provider.buildBody(base, history, List.of()).toString());
        var minimal = provider.buildBody("", history, specs);
        var actual = provider.buildBody(base, history, specs);
        System.out.printf("payload tools=%d base_prompt_utf8=%d empty_request=%d base_only_request=%d"
                        + " tools_only_request=%d base_and_tools_request=%d full_description_request=%d%n",
                specs.size(), bytes(base), empty, baseOnly, bytes(minimal.toString()), bytes(actual.toString()),
                bytes(provider.buildBody(base, history, ClientToolDefs.SPECS).toString()));
        int schemas = 0, descriptions = 0;
        var sizes = new ArrayList<String>();
        var tools = actual.getAsJsonArray("tools");
        for (int i = 0; i < tools.size(); i++) {
            var function = tools.get(i).getAsJsonObject().getAsJsonObject("function");
            int schema = bytes(function.get("parameters").toString());
            int description = bytes(function.get("description").toString());
            schemas += schema;
            descriptions += description;
            sizes.add(String.format("%06d tool=%s schema_bytes=%d description_json_bytes=%d",
                    bytes(tools.get(i).toString()), specs.get(i).name(), schema, description));
        }
        System.out.printf("payload_parts schemas_json=%d descriptions_json=%d tools_json=%d%n",
                schemas, descriptions, bytes(tools.toString()));
        sizes.sort(Comparator.reverseOrder());
        sizes.stream().limit(10).forEach(s -> System.out.println("largest_tool " + s));
        for (int size : new int[]{0, 4096, 16384}) {
            String prompt = PromptBuilder.build("", size == 0 ? List.of() : List.of("x".repeat(size)));
            System.out.printf("synthetic_full_text ascii_body_bytes=%d request_bytes=%d%n",
                    size, bytes(provider.buildBody(prompt, history, specs).toString()));
        }
        for (int i = 0; i < 30; i++)
            sink = provider.buildBody(base, history, specs).toString().length();
        var times = new long[150];
        for (int i = 0; i < times.length; i++) {
            long before = System.nanoTime();
            sink = provider.buildBody(base, history, specs).toString().length();
            times[i] = System.nanoTime() - before;
        }
        timing("serialize_base_and_tools_warm_jvm", times);
    }

    private static void knowledge() throws Exception {
        Path output = Path.of("build").toAbsolutePath().normalize();
        Files.createDirectories(output);
        Path directory = Files.createTempDirectory(output, "framework-perf-skills-");
        try {
            var provider = new OpenAiCompatProvider("offline", "http://127.0.0.1", "unused", "fixture");
            String base = PromptBuilder.build("", List.of());
            StringBuilder full = new StringBuilder(base);
            for (int i = 0; i < 4; i++) {
                String file = "note" + i + ".md", body = "x".repeat(16384);
                Files.writeString(directory.resolve(file), body, StandardCharsets.UTF_8);
                full.append("\n# \u6280\u80fd\u7b14\u8bb0\n## ").append(file).append('\n').append(body).append('\n');
            }
            var catalog = SkillCatalog.load(directory);
            String indexed = base + catalog.promptIndex();
            var history = new ArrayList<Msg>();
            history.add(new Msg.User("offline measurement"));
            int fullFirst = bytes(provider.buildBody(full.toString(), history, ToolCatalog.MODEL_SPECS).toString());
            int indexedFirst = bytes(provider.buildBody(indexed, history, ToolCatalog.MODEL_SPECS).toString());
            long cumulative = indexedFirst, onePage = 0;
            int reads = 0;
            for (var value : catalog.index().getAsJsonArray("skills")) {
                String selector = value.getAsJsonObject().get("name").getAsString();
                do {
                    String args = "{\"names\":[\"" + selector + "\"]}", id = "knowledge-" + reads;
                    var receipt = catalog.read(selector);
                    if (!receipt.ok()) throw new AssertionError(receipt.feedback());
                    history.add(new Msg.Assistant("", List.of(new ToolCall(id, "tool_help", args))));
                    history.add(new Msg.Tool(id, "tool_help", receipt.feedback(), true));
                    cumulative += bytes(provider.buildBody(indexed, history, ToolCatalog.MODEL_SPECS).toString());
                    if (++reads == 1) onePage = cumulative;
                    var data = receipt.data();
                    selector = data.has("next_name") ? data.get("next_name").getAsString() : null;
                } while (selector != null);
            }
            System.out.printf("knowledge synthetic_notes=4 body_bytes=65536 index_utf8=%d full_first_request=%d"
                            + " indexed_first_request=%d no_read_extra_turns=0 one_page_extra_turns=1"
                            + " one_page_cumulative_request_bytes=%d serial_all_pages_extra_turns=%d"
                            + " serial_all_pages_cumulative_request_bytes=%d actual_model_calls=0%n",
                    bytes(catalog.promptIndex()), fullFirst, indexedFirst, onePage, reads, cumulative);
            var batchedHistory = new ArrayList<Msg>();
            batchedHistory.add(new Msg.User("offline measurement"));
            var pending = new ArrayList<String>();
            for (var value : catalog.index().getAsJsonArray("skills"))
                pending.add(value.getAsJsonObject().get("name").getAsString());
            long batchedBytes = indexedFirst;
            int rounds = 0, batchedCalls = 0;
            while (!pending.isEmpty()) {
                var calls = new ArrayList<ToolCall>();
                var results = new ArrayList<Msg>();
                var next = new ArrayList<String>();
                for (String selector : pending) {
                    String id = "batch-knowledge-" + batchedCalls++;
                    calls.add(new ToolCall(id, "tool_help", "{\"names\":[\"" + selector + "\"]}"));
                    var receipt = catalog.read(selector);
                    results.add(new Msg.Tool(id, "tool_help", receipt.feedback(), true));
                    var data = receipt.data();
                    if (data.has("next_name")) next.add(data.get("next_name").getAsString());
                }
                batchedHistory.add(new Msg.Assistant("", calls));
                batchedHistory.addAll(results);
                batchedBytes += bytes(provider.buildBody(indexed, batchedHistory, ToolCatalog.MODEL_SPECS).toString());
                pending = next;
                rounds++;
            }
            System.out.printf("knowledge_batched max_independent_notes_per_turn=4 all_pages_extra_turns=%d"
                    + " help_calls=%d cumulative_request_bytes=%d actual_model_calls=0%n",
                    rounds, batchedCalls, batchedBytes);
            timing("catalog_load_four_16k_notes", sample(20,
                    () -> sink = SkillCatalog.load(directory).promptIndex().length()));
        } finally {
            try (var files = Files.list(directory)) {
                for (Path file : files.toList()) Files.delete(file);
            }
            Files.delete(directory);
        }
    }

    private static void parkedLoop() {
        var requests = new int[1];
        var invoked = new ArrayList<String>();
        ChatEngine model = (system, history, tools) -> {
            int number = ++requests[0];
            return CompletableFuture.completedFuture(number == 1
                    ? new AssistantTurn("", List.of(
                            new ToolCall("offline-c1", "offline_job", "{}"),
                            new ToolCall("offline-c2", "offline_read", "{}")), 0, 0, -1, "tool_calls")
                    : new AssistantTurn("finished", List.of(), 0, 0, -1, "stop"));
        };
        ToolExecutor executor = (name, json) -> {
            invoked.add(name);
            return CompletableFuture.completedFuture(name.equals("offline_job")
                    ? ToolExecutor.ToolOutcome.accepted("offline-job", "accepted")
                    : new ToolExecutor.ToolOutcome(true, "read finished"));
        };
        var loop = new AgentLoop(model, List.of(), executor, AgentLoop.Config.defaults(),
                new AgentLoop.Listener() {}, () -> "offline", 6000);
        loop.submit("offline task");
        if (requests[0] != 1 || !invoked.equals(List.of("offline_job")) || !loop.busy())
            throw new AssertionError("The accepted job did not hold the next tool and model turn");
        System.out.printf("actual_agent_loop after_ack model_calls=%d tool_calls=%d busy=%s%n",
                requests[0], invoked.size(), loop.busy());
        loop.onJobEvent("offline-job", new ToolExecutor.ToolOutcome(true, "job finished"));
        if (requests[0] != 2 || invoked.size() != 2 || loop.busy())
            throw new AssertionError("The terminal event did not release the next tool and model turn");
        System.out.printf("actual_agent_loop after_terminal model_calls=%d tool_calls=%d busy=%s%n",
                requests[0], invoked.size(), loop.busy());
    }

    private static void steering() {
        var body = new PathSteering.Body(1.5, 64, .5, .2, 0, true);
        boolean stop = new PathSteering(1, 64, 0, false).tick(body, false).arrived();
        boolean pass = new PathSteering(1, 64, 0, false).tick(body, true).arrived();
        if (stop || !pass) throw new AssertionError("Unexpected steering contract");
        System.out.printf("actual_path_steering centered_speed_0_2 stopping_arrived=%s passing_arrived=%s"
                + " native_physics_measured=false%n", stop, pass);
    }

    private static void journal() throws Exception {
        Path output = Path.of("build").toAbsolutePath().normalize();
        Files.createDirectories(output);
        Path directory = Files.createTempDirectory(output, "framework-perf-journal-");
        var journal = new GoalJournal(directory);
        var initial = new JsonObject();
        for (String key : List.of("world_id", "owner_uuid", "companion_uuid"))
            initial.addProperty(key, UUID.randomUUID().toString());
        String scope = GoalCheckpoint.scope(initial);
        var core = new JsonObject();
        core.add("initial", initial);
        core.addProperty("synthetic_payload", "x".repeat(16384));
        try {
            journal.block(scope);
            timing("journal_repeated_block_one_scope", sample(40, () -> journal.block(scope)));
            timing("journal_save_synthetic_16k_ready",
                    sample(40, () -> journal.save(scope, core, new JsonObject(), true)));
            for (int i = 0; i < 127; i++)
                journal.block(UUID.randomUUID() + "_" + initial.get("owner_uuid").getAsString()
                        + "_" + initial.get("companion_uuid").getAsString());
            timing("journal_repeated_block_128_scopes", sample(40, () -> journal.block(scope)));
        } finally {
            // This directory is created here and contains only this probe's journal fixtures.
            try (var files = Files.list(directory)) {
                for (Path file : files.toList()) Files.delete(file);
            }
            Files.delete(directory);
        }
    }

    private static long[] sample(int count, Runnable action) {
        var times = new long[count];
        for (int i = 0; i < count; i++) {
            long before = System.nanoTime();
            action.run();
            times[i] = System.nanoTime() - before;
        }
        return times;
    }

    private static void timing(String name, long[] nanos) {
        Arrays.sort(nanos);
        double mean = Arrays.stream(nanos).average().orElseThrow() / 1_000_000;
        System.out.printf("local_timing name=%s samples=%d mean_ms=%.3f p50_ms=%.3f p95_ms=%.3f max_ms=%.3f%n",
                name, nanos.length, mean, nanos[(nanos.length - 1) / 2] / 1_000_000.0,
                nanos[(int) Math.ceil(nanos.length * .95) - 1] / 1_000_000.0,
                nanos[nanos.length - 1] / 1_000_000.0);
    }
}
