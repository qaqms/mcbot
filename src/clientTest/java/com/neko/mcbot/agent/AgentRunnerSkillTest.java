package com.neko.mcbot.agent;

import com.google.gson.JsonObject;
import com.neko.mcbot.agentcore.llm.Msg;
import com.neko.mcbot.agentcore.llm.ToolCall;
import com.neko.mcbot.agentcore.prompt.SkillCatalog;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(10)
class AgentRunnerSkillTest {
    @Test void aFreshInstallationCanDiscoverAndReadThePackagedBuildingKnowledgeThroughTheRealRunner() {
        client.bundledSkills=true;
        client.skillsDirectory=directory.resolve("missing");
        runner.start();
        runner.submitTask("[只读] consult building design");
        var catalog=SkillCatalog.loadBundled(client.skillsDirectory);
        String name=catalog.index().getAsJsonArray("skills").get(0).getAsJsonObject().get("name").getAsString();
        assertTrue(client.engine().prompts.getFirst().contains("construction-design.md"));
        assertFalse(client.engine().prompts.getFirst().contains("先选屋脊方向"));
        help(0,name);
        assertTrue(result("help-0").ok());
        assertTrue(result("help-0").content().contains("建筑方案"));
        assertEquals(31,client.engine().definitions.getFirst().size());
        assertEquals(0,client.toolCount());
        assertFalse(Files.exists(client.skillsDirectory));
    }
    @TempDir Path directory;
    private final OfflineAgentClient client = new OfflineAgentClient();
    private final AgentRunner runner = client.create();
    @AfterEach void close() { runner.close(); client.drain(); }

    private String start(String body) throws Exception {
        Files.writeString(directory.resolve("working.md"), body);
        client.skillsDirectory = directory;
        String name = SkillCatalog.load(directory).index().getAsJsonArray("skills")
                .get(0).getAsJsonObject().get("name").getAsString();
        runner.start();
        runner.submitTask("[只读] consult the available knowledge");
        return name;
    }
    private void help(int turn, String name) {
        var args = new JsonObject();
        var names = new com.google.gson.JsonArray();
        names.add(name); args.add("names", names);
        client.engine().tools(turn, new ToolCall("help-" + turn, "tool_help", args.toString()));
        client.drain();
    }
    private Msg.Tool result(String id) {
        return client.engine().histories.getLast().stream().filter(Msg.Tool.class::isInstance)
                .map(Msg.Tool.class::cast).filter(value -> value.callId().equals(id)).findFirst().orElseThrow();
    }
    private void until(BooleanSupplier condition) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            client.drain();
            java.util.concurrent.locks.LockSupport.parkNanos(100000);
        }
        assertTrue(condition.getAsBoolean());
    }

    @Test void realRunnerReadsKnowledgeLocallyAndKeepsTheSameToolSchemas() throws Exception {
        String name = start("BODY_ON_DEMAND_ONLY");
        var definitions = client.engine().definitions.getFirst();
        assertEquals(31, definitions.size());
        assertFalse(client.engine().prompts.getFirst().contains("BODY_ON_DEMAND_ONLY"));
        assertTrue(client.engine().prompts.getFirst().contains(name));
        help(0, name);
        assertEquals(0, client.toolCount());
        assertTrue(result("help-0").ok());
        assertTrue(result("help-0").content().contains("BODY_ON_DEMAND_ONLY"));
        assertEquals(definitions, client.engine().definitions.getLast());
        assertEquals(2, client.skillLoads, "startup and directive boundary, never one load per model step");
    }

    @Test void currentTaskUsesItsSnapshotAndNextTaskRefreshesTheIndex() throws Exception {
        String name = start("FIRST_REVISION");
        Files.writeString(directory.resolve("working.md"), "SECOND_REVISION");
        help(0, name);
        assertTrue(result("help-0").content().contains("FIRST_REVISION"));
        assertFalse(result("help-0").content().contains("SECOND_REVISION"));
        client.engine().reply(1, "consulted"); client.drain();
        runner.submitTask("[只读] consult again");
        help(2, name);
        assertTrue(result("help-2").content().contains("SECOND_REVISION"));
        assertEquals(3, client.skillLoads);
        assertEquals(0, client.toolCount());
    }

    @Test void everyActuallyStartedQueuedTaskGetsItsOwnFreshSnapshot() throws Exception {
        String name = start("FIRST_REVISION");
        runner.submitTask("[只读] queued second");
        runner.submitTask("[只读] queued third");
        Files.writeString(directory.resolve("working.md"), "SECOND_REVISION");
        client.engine().reply(0, "first complete"); client.drain();
        help(1, name);
        assertTrue(result("help-1").content().contains("SECOND_REVISION"));
        Files.writeString(directory.resolve("working.md"), "THIRD_REVISION");
        client.engine().reply(2, "second complete"); client.drain();
        help(3, name);
        assertTrue(result("help-3").content().contains("THIRD_REVISION"));
        assertEquals(4, client.skillLoads);
        assertEquals(0, client.toolCount());
    }

    @Test void nextTaskDisableCannotReadOldHandlesOrGrantAnAction() throws Exception {
        String name = start("Advice cannot grant movement permission");
        help(0, name);
        client.engine().reply(1, "consulted"); client.drain();
        Files.writeString(directory.resolve("index.json"),
                "{\"version\":1,\"skills\":{\"working.md\":{\"enabled\":false}}}");
        runner.submitTask("[只读] consult again");
        assertFalse(client.engine().prompts.getLast().contains(name));
        help(2, name);
        assertFalse(result("help-2").ok());
        client.engine().tools(3, new ToolCall("forbidden", "move_to", "{\"x\":1,\"y\":90,\"z\":0}"));
        client.drain();
        assertEquals(0, client.toolCount());
    }

    @Test void luaHelpIsModelVisibleAndUsesTheOriginalCallBudget() throws Exception {
        String name = start("PROGRAM_VISIBLE_KNOWLEDGE");
        var args = new JsonObject();
        args.addProperty("code", "local h=mc.call('tool_help',{names={'" + name
                + "'}}); return {knowledge=h.data.knowledge_only}");
        args.addProperty("max_calls", 1);
        client.engine().tools(0, new ToolCall("program", "run_program", args.toString()));
        until(() -> client.engine().responses.size() == 2);
        assertTrue(result("program").ok(), result("program").content());
        assertTrue(result("program").content().contains("PROGRAM_VISIBLE_KNOWLEDGE"));
        assertTrue(result("program").content().contains("\"knowledge\":true"));
        assertEquals(0, client.toolCount());
        args.addProperty("code", "mc.call('tool_help',{names={'" + name + "'}}); mc.call('inventory',{})");
        client.engine().tools(1, new ToolCall("limited", "run_program", args.toString()));
        until(() -> client.engine().responses.size() == 3);
        assertFalse(result("limited").ok());
        assertTrue(result("limited").content().contains("ACTION_LIMIT"));
        assertEquals(0, client.toolCount());
    }

    @Test void onePageReadInsideLuaPreservesQuotedContentWithoutBreakingTheOutputBudget() throws Exception {
        String name = start("\"\\".repeat(2048));
        var args = new JsonObject();
        args.addProperty("code", "local h=mc.call('tool_help',{names={'" + name
                + "'}}); return {page=h.data.page}");
        client.engine().tools(0, new ToolCall("program", "run_program", args.toString()));
        until(() -> client.engine().responses.size() == 2);
        assertTrue(result("program").ok(), result("program").content());
        assertTrue(result("program").content().contains("\"page\":1"));
        assertEquals(0, client.toolCount());
    }

    @Test void knowledgePagesCannotBeMixedIntoAnUnboundedHelpBatch() throws Exception {
        String name = start("page");
        var outcome = ToolCatalog.read("{\"names\":[\"" + name + "\",\"craft\"]}", SkillCatalog.load(directory));
        assertFalse(outcome.ok());
        assertTrue(outcome.feedback().startsWith("SKILL_SINGLE_READ"));
        assertEquals(0, client.toolCount());
    }

    @Test void realCompactionCanDiscardTheBodyAndTheIndexStillAllowsRediscovery() throws Exception {
        String name = start("OLD_KNOWLEDGE_MARKER\n" + "bounded knowledge ".repeat(3000));
        runner.reconfigure(runner.config().withPreferences(
                new com.neko.mcbot.cfg.ClientConfig.Preferences(64000, 6000, 1500, false, false, false, true)));
        String selector = name;
        var catalog = SkillCatalog.load(directory);
        for (int turn = 0; turn < 6; turn++) {
            help(turn, selector);
            var data = catalog.read(selector).data();
            selector = data.get("next_name").getAsString();
        }
        client.engine().reply(6, "consulted"); client.drain();
        var compact = runner.compactContext();
        assertEquals(8, client.engine().responses.size(), "one actual conversation summary request");
        client.engine().reply(7, "The previous task consulted reference knowledge; reread details when needed.");
        client.drain();
        assertTrue(compact.join());
        runner.submitTask("[只读] consult the same note after summarization");
        assertTrue(client.engine().prompts.getLast().contains(name));
        assertFalse(client.engine().histories.getLast().stream().filter(Msg.Tool.class::isInstance)
                .map(Msg.Tool.class::cast).anyMatch(value -> value.content().contains("OLD_KNOWLEDGE_MARKER")));
        help(8, name);
        assertTrue(result("help-8").content().contains("OLD_KNOWLEDGE_MARKER"));
        assertEquals(0, client.toolCount());
    }
}
