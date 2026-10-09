package com.neko.mcbot.body;

import com.google.gson.Gson;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class CompanionRosterTest {
    @TempDir Path directory;

    private final CompanionRoster.Entry entry = new CompanionRoster.Entry(
            UUID.fromString("7aef43ed-214e-47c3-b5e4-20b1b01f77af"), "steve",
            UUID.fromString("56a6d6e8-2ec4-4c41-a32d-c7c8ca7488ae"), "owner");

    private Path legacy(List<CompanionRoster.Entry> entries) throws Exception {
        Path file = directory.resolve("instance/mcbot/companions.json");
        Files.createDirectories(file.getParent());
        Files.writeString(file, new Gson().toJson(entries));
        return file;
    }

    private void playerData(Path world) throws Exception {
        Files.createDirectories(world.resolve("playerdata"));
        Files.write(world.resolve("playerdata/" + entry.uuid() + ".dat"), new byte[]{1});
    }

    @Test
    void newWorldDoesNotImportSharedRosterWithoutItsPlayerData() throws Exception {
        Path old = legacy(List.of(entry));
        String original = Files.readString(old);
        CompanionRoster roster = new CompanionRoster(directory.resolve("new-world"), old);
        roster.load();
        assertTrue(roster.entries().isEmpty());
        assertTrue(Files.exists(roster.store()));
        assertEquals(original, Files.readString(old));
    }

    @Test
    void twoWorldsHaveIndependentRostersEvenWithTheSameCompanionUuid() throws Exception {
        Path old = directory.resolve("missing-legacy.json");
        CompanionRoster first = new CompanionRoster(directory.resolve("first"), old);
        CompanionRoster second = new CompanionRoster(directory.resolve("second"), old);
        first.add(entry);
        first.save();
        second.load();
        assertTrue(second.entries().isEmpty());
        second.add(entry);
        second.save();
        first.remove(entry);
        first.save();
        second.load();
        assertEquals(List.of(entry), second.entries());
        assertNotEquals(first.store(), second.store());
    }

    @Test
    void restartRestoresOnlyThisWorldsEntries() throws Exception {
        Path world = directory.resolve("world");
        Path old = directory.resolve("missing-legacy.json");
        CompanionRoster first = new CompanionRoster(world, old);
        first.add(entry);
        first.save();
        CompanionRoster restarted = new CompanionRoster(world, old);
        restarted.load();
        assertEquals(List.of(entry), restarted.entries());
    }

    @Test
    void legacyMigrationRequiresMatchingPlayerDataAndKeepsOriginal() throws Exception {
        Path old = legacy(List.of(entry));
        Path world = directory.resolve("existing-world");
        playerData(world);
        CompanionRoster roster = new CompanionRoster(world, old);
        roster.load();
        assertEquals(List.of(entry), roster.entries());
        assertTrue(Files.isRegularFile(old));
        assertTrue(Files.isRegularFile(roster.store()));
    }

    @Test
    void dismissedLegacyCompanionCannotBeImportedAgain() throws Exception {
        Path old = legacy(List.of(entry));
        Path world = directory.resolve("world");
        playerData(world);
        CompanionRoster roster = new CompanionRoster(world, old);
        roster.load();
        roster.remove(entry);
        roster.save();
        roster.load();
        assertTrue(roster.entries().isEmpty());
    }

    @Test
    void emptyMigrationCannotLaterReimportSharedEntries() throws Exception {
        Path old = legacy(List.of(entry));
        Path world = directory.resolve("world");
        CompanionRoster roster = new CompanionRoster(world, old);
        roster.load();
        playerData(world);
        roster.load();
        assertTrue(roster.entries().isEmpty());
    }

    @Test
    void worldLocalRosterTakesPrecedenceOverLegacyRoster() throws Exception {
        Path old = legacy(List.of(entry));
        Path world = directory.resolve("world");
        playerData(world);
        CompanionRoster roster = new CompanionRoster(world, old);
        roster.save();
        roster.load();
        assertTrue(roster.entries().isEmpty());
    }

    @Test
    void migrationDoesNotImportEntriesBelongingOnlyToAnotherWorld() throws Exception {
        var other = new CompanionRoster.Entry(UUID.randomUUID(), "alex", entry.ownerUuid(), "owner");
        Path old = legacy(List.of(entry, other));
        Path world = directory.resolve("world");
        playerData(world);
        CompanionRoster roster = new CompanionRoster(world, old);
        roster.load();
        assertEquals(List.of(entry), roster.entries());
    }
}
