package com.neko.mcbot.agentcore.loop;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;

import static org.junit.jupiter.api.Assertions.*;

class GoalJournalTest {
    @TempDir Path root;
    JsonObject core() {
        var data = new JsonObject(); data.add("initial", GoalCheckpoint.initial(AgentLoopRecoveryTest.state(5)));
        return data;
    }
    String scope() { return GoalCheckpoint.scope(AgentLoopRecoveryTest.state(5)); }
    @Test void actualAtomicFileSurvivesANewJournalInstanceWithoutMachineOrModelConfiguration() throws Exception {
        var journal = new GoalJournal(root);
        journal.save(scope(), core(), new JsonObject(), true);
        assertEquals(core(), new GoalJournal(root).load(scope()).get("core"));
        String text = Files.readString(root.resolve(scope() + ".json"));
        assertFalse(text.contains("api_key")); assertFalse(text.contains("base_url"));
        assertFalse(Files.exists(root.resolve(scope() + ".tmp")));
    }
    @Test void dispatchMarkerBlocksThePreviouslyValidCheckpointAcrossRestart() {
        var journal = new GoalJournal(root); journal.save(scope(), core(), new JsonObject(), true);
        journal.block(scope());
        assertThrows(RuntimeException.class, () -> new GoalJournal(root).load(scope()));
    }
    @Test void cancellationRemovesTheRecordAndASeparateExplicitGoalCanCreateANewOne() {
        var journal = new GoalJournal(root); journal.save(scope(), core(), new JsonObject(), true);
        journal.clear(scope()); assertNull(new GoalJournal(root).load(scope()));
        journal.save(scope(), core(), new JsonObject(), true); assertNotNull(journal.load(scope()));
    }
    @Test void incompleteSpatialStateIsNeverOfferedAsAResumableGoal() {
        var journal = new GoalJournal(root); journal.save(scope(), core(), new JsonObject(), false);
        assertThrows(RuntimeException.class, () -> journal.load(scope()));
    }
    @Test void malformedOversizedAndDeepFilesAreRejected() throws Exception {
        var journal = new GoalJournal(root);
        Path file = root.resolve(scope() + ".json");
        for (String text : new String[]{"{", " ".repeat(GoalJournal.FILE_LIMIT + 1),
                "[".repeat(34) + "0" + "]".repeat(34)}) {
            Files.writeString(file, text);
            assertThrows(RuntimeException.class, () -> journal.load(scope()));
        }
    }
    @Test void wrongScopeAndPathTraversalCannotReadAnotherRecord() {
        var journal = new GoalJournal(root);
        assertThrows(IllegalArgumentException.class, () -> journal.load("../other"));
        var wrong = core(); wrong.getAsJsonObject("initial").addProperty("world_id", "20000000-0000-0000-0000-000000000001");
        assertThrows(IllegalArgumentException.class, () -> journal.save(scope(), wrong, new JsonObject(), true));
    }
    @Test void failedReplacementKeepsADurableBlockOnTheOldCheckpoint() throws Exception {
        var journal = new GoalJournal(root); journal.save(scope(), core(), new JsonObject(), true);
        Files.createDirectory(root.resolve(scope() + ".tmp"));
        assertThrows(RuntimeException.class, () -> journal.save(scope(), core(), new JsonObject(), true));
        assertThrows(RuntimeException.class, () -> new GoalJournal(root).load(scope()));
    }
    @Test void markerOnlyScopesAreBoundedAndExplicitDiscardReclaimsCapacity() throws Exception {
        var journal = new GoalJournal(root);
        for (int i = 0; i < GoalJournal.SCOPE_LIMIT; i++) {
            String markerScope = "%08x-0000-0000-0000-000000000000".formatted(i) + scope().substring(36);
            journal.block(markerScope);
        }
        assertThrows(RuntimeException.class, () -> journal.block(scope()));
        journal.clear("00000000-0000-0000-0000-000000000000" + scope().substring(36));
        journal.block(scope());
        try (var files = Files.list(root)) { assertEquals(GoalJournal.SCOPE_LIMIT, files.count()); }
    }
}
