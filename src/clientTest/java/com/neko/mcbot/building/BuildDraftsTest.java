package com.neko.mcbot.building;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.neko.mcbot.server.BlockActionFixtures;
import com.neko.mcbot.server.tools.BuildRecordTool;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class BuildDraftsTest {
    @BeforeAll static void boot() { BlockActionFixtures.bootstrap(); }
    @TempDir Path root;
    static JsonObject json(String text) { return JsonParser.parseString(text).getAsJsonObject(); }
    static BuildTarget drawing(String facing) {
        return BuildTarget.parse(json("""
                {"anchor":{"x":-60,"y":64,"z":-131},"rotation":90,"regions":[
                {"from":{"x":0,"y":0,"z":0},"to":{"x":10,"y":8,"z":5},"block":"stone"},
                {"from":{"x":0,"y":9,"z":0},"to":{"x":3,"y":9,"z":0},"block":"oak_stairs",
                "properties":[{"name":"facing","value":"%s"},{"name":"half","value":"bottom"}]}]}
                """.formatted(facing)));
    }
    @Test void corrected598CellDrawingIsRetrievableAndCapturableWithoutReconstructingTheCells() {
        var site = new BuildSite(); var world = new BuildSiteTest.World();
        site.bind("task",world);
        var old = drawing("west"); var corrected = drawing("east");
        assertEquals(598,corrected.cells().size());
        assertTrue(corrected.expandedInput().toString().length() > 20000);
        assertTrue(site.drafts().remember(old)); assertTrue(site.drafts().remember(corrected));
        var owner = UUID.randomUUID(); var builder = UUID.randomUUID();
        var records = new BuildRecords(root); records.load();
        assertTrue(records.list(owner,builder,"world",true,0).getAsJsonArray("entries").isEmpty());
        var list = BuildRecordTool.execute(records,owner,builder,"world",site,world,json("{\"operation\":\"draft_list\"}"));
        assertTrue(list.ok()); assertEquals(2,list.data().getAsJsonArray("entries").size());
        var ref = list.data().getAsJsonArray("entries").get(1).getAsJsonObject().getAsJsonObject("target");
        assertEquals(corrected.revision(),site.drafts().resolve(ref).revision());
        var query = json("{\"operation\":\"draft_query\",\"offset\":592}");
        query.add("target",ref);
        var result = BuildRecordTool.execute(records,owner,builder,"world",site,world,query);
        assertTrue(result.ok(),result.feedback());
        assertEquals(6,result.data().getAsJsonArray("cells").size());
        assertEquals(-1,result.data().get("next_offset").getAsInt());
        var capture = json("{\"operation\":\"design_capture\",\"design_id\":\"house\"}");
        capture.add("target",ref);
        assertTrue(BuildRecordTool.execute(records,owner,builder,"world",site,world,capture).ok());
        var reloaded = new BuildRecords(root); reloaded.load(); assertTrue(reloaded.healthy());
        var instance = reloaded.createInstance(owner,builder,"world","house",1,corrected.anchor(),corrected.rotation());
        assertEquals(corrected.revision(),instance.target().revision());
        assertEquals(corrected.cells(),instance.target().cells());
        assertFalse(site.active(),"reading and capturing a draft never selects a worksite");
    }
    @Test void draftsAreImmutableDeduplicatedAndDoNotLeakAcrossTaskOrWorldBindings() {
        var world = new BuildSiteTest.World(); var site = new BuildSite();
        var task = new Object(); site.bind(task,world);
        var target = drawing("east"); var ref = BuildDrafts.reference(target);
        assertTrue(site.drafts().remember(target)); assertTrue(site.drafts().remember(target));
        assertEquals(1,site.drafts().list().getAsJsonArray("entries").size());
        var page = site.drafts().query(ref,0); page.getAsJsonArray("cells").remove(0);
        assertEquals(16,site.drafts().query(ref,0).getAsJsonArray("cells").size());
        assertThrows(RuntimeException.class,()->site.drafts().query(ref,1));
        var mixed = ref.deepCopy(); mixed.addProperty("instance_id","unexpected");
        assertThrows(RuntimeException.class,()->site.drafts().resolve(mixed));
        site.bind(task,world); assertSame(target,site.drafts().resolve(ref));
        site.bind(new Object(),world);
        assertThrows(RuntimeException.class,()->site.drafts().resolve(ref));
        site.drafts().remember(target); site.bind(task,new Object());
        assertThrows(RuntimeException.class,()->site.drafts().resolve(ref));
    }
    @Test void capacityNeverEvictsAnExistingDrawingAndExposesFailureToRetainANewOne() {
        var drafts = new BuildDrafts();
        var first = BuildSiteTest.target(); assertTrue(drafts.remember(first));
        for (int n = 1; n < BuildDrafts.LIMIT; n++) {
            var input = first.input(); input.add("anchor",BuildTarget.coordinates(new BlockPos(n+20,90,0)));
            assertTrue(drafts.remember(BuildTarget.parse(input)));
        }
        assertFalse(drafts.remember(drawing("east")));
        assertSame(first,drafts.resolve(BuildDrafts.reference(first)));
        assertEquals(BuildDrafts.LIMIT,drafts.list().getAsJsonArray("entries").size());
    }
    @Test void explicitGoalVerificationRetainsTheSelectedDraftInTheExistingSpatialCheckpointBeforeSiteRegistration() {
        var world = new BuildSiteTest.World(); var original = new BuildSite(); original.bind("task",world);
        var target = drawing("east");
        original.retainVerifiedDraft(target);
        var restored = new BuildSite(); restored.bind("new-epoch",world);
        restored.restore(original.snapshot(),BuildSiteTest.FEET,world);
        assertFalse(restored.active());
        assertEquals(target.revision(),restored.drafts().resolve(BuildDrafts.reference(target)).revision());
        assertNull(restored.begin(target,List.of(),BuildSiteTest.FEET,world));
        var copy = new BuildSite(); copy.bind("third-epoch",world);
        copy.restore(restored.snapshot(),BuildSiteTest.FEET,world);
        assertEquals(target.revision(),copy.drafts().resolve(BuildDrafts.reference(target)).revision());
    }

    @Test void realConversationCompactionMayRemoveEveryDesignCellWhileTheCorrectedDrawingStillLoadsByReference() {
        var site = new BuildSite(); var world = new BuildSiteTest.World(); site.bind("task",world);
        var old = drawing("west"); var corrected = drawing("east");
        site.drafts().remember(old); site.drafts().remember(corrected);
        assertNull(site.begin(old,List.of(),BuildSiteTest.FEET,world));
        var history = new com.neko.mcbot.agentcore.convo.Conversation(1000);
        history.configure(1000,100,true);
        history.add(new com.neko.mcbot.agentcore.llm.Msg.User(corrected.input().toString()));
        for (int i = 0; i < 10; i++)
            history.add(new com.neko.mcbot.agentcore.llm.Msg.User("material facts ".repeat(120)));
        history.compact((system,messages,tools) -> java.util.concurrent.CompletableFuture.completedFuture(
                new com.neko.mcbot.agentcore.llm.AssistantTurn("Corrected revision "+corrected.revision(),
                        List.of(),0,0,-1,"stop"))).join();
        assertFalse(history.summary().isEmpty());
        assertFalse(history.history().toString().contains("oak_stairs"));
        var restored = site.drafts().resolve(BuildDrafts.reference(corrected));
        assertEquals(corrected.cells(),restored.cells());
        assertNull(site.revise(restored,old.revision(),BuildSiteTest.FEET,world));
        assertEquals(corrected.revision(),site.report(world).get("target_revision").getAsString());
        assertEquals(598,site.target().cells().size());
    }
}
