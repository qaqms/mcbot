package com.neko.mcbot.agentcore.prompt;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SkillLoaderTest {
    @TempDir Path directory;

    @Test void initialPromptContainsDiscoveryInsteadOfTheNoteBody() throws Exception {
        Files.writeString(directory.resolve("navigation.md"), "UNLOADED_BODY_MARKER\n" + "detail ".repeat(600));
        String prompt = PromptBuilder.build("", SkillLoader.load(directory));
        assertFalse(prompt.contains("UNLOADED_BODY_MARKER"));
        assertTrue(prompt.contains("navigation"));
        assertTrue(prompt.contains("tool_help"));
    }

    private String name(SkillCatalog catalog) {
        return catalog.index().getAsJsonArray("skills").get(0).getAsJsonObject().get("name").getAsString();
    }

    @Test void pagesPreserveUnicodeAndAllContentWithoutPuttingItInTheIndex() throws Exception {
        String body = "nearby \u77ff\u77f3 \ud83d\ude80\n".repeat(1200);
        Files.writeString(directory.resolve("mining.md"), body);
        var catalog = SkillCatalog.load(directory);
        String selector = name(catalog);
        var actual = new StringBuilder();
        int pages = 0;
        do {
            assertTrue(selector.length() <= 32);
            var result = catalog.read(selector);
            assertTrue(result.ok(), result.feedback());
            var data = result.data();
            String part = data.get("body").getAsString();
            assertTrue(part.getBytes(StandardCharsets.UTF_8).length <= SkillCatalog.PAGE_BYTES);
            actual.append(part);
            pages++;
            selector = data.has("next_name") ? data.get("next_name").getAsString() : null;
        } while (selector != null);
        assertTrue(pages > 1);
        assertEquals(body, actual.toString());
        assertFalse(catalog.promptIndex().contains("nearby"));
    }

    @Test void localOverrideAndOwnerDisableUseOneEffectiveIndex() throws Exception {
        Path defaults = Files.createDirectory(directory.resolve("defaults"));
        Files.writeString(defaults.resolve("work.md"), "DEFAULT_MUST_NOT_LEAK");
        Files.writeString(defaults.resolve("hidden.md"), "DISABLED_MUST_NOT_LEAK");
        Files.writeString(directory.resolve("work.md"), "LOCAL_OVERRIDE");
        Files.writeString(directory.resolve("index.json"), """
                {"version":1,"skills":{"work.md":{"description":"Working knowledge"},
                "hidden.md":{"enabled":false}}}
                """);
        var catalog = SkillCatalog.load(directory);
        var entries = catalog.index().getAsJsonArray("skills");
        assertEquals(1, entries.size());
        assertTrue(catalog.promptIndex().contains("Working knowledge"));
        assertEquals("local", entries.get(0).getAsJsonObject().get("source").getAsString());
        assertEquals("LOCAL_OVERRIDE", catalog.read(name(catalog)).data().get("body").getAsString());
        Files.delete(directory.resolve("work.md"));
        var fallback = SkillCatalog.load(directory);
        assertEquals(name(catalog), name(fallback));
        assertEquals("DEFAULT_MUST_NOT_LEAK", fallback.read(name(fallback)).data().get("body").getAsString());
    }

    @Test void disabledHandlesCannotReadPreviouslyLoadedDefaultKnowledge() throws Exception {
        Files.writeString(directory.resolve("note.md"), "a private note");
        var original = SkillCatalog.load(directory);
        Files.writeString(directory.resolve("index.json"),
                "{\"version\":1,\"skills\":{\"note.md\":{\"enabled\":false}}}");
        var disabled = SkillCatalog.load(directory);
        assertTrue(disabled.index().getAsJsonArray("skills").isEmpty());
        assertFalse(disabled.read(name(original)).ok());
        assertFalse(disabled.read(name(original)).feedback().contains("private"));
    }

    @Test void unreadableOverrideDoesNotFallBackToDefaults() throws Exception {
        Path defaults = Files.createDirectory(directory.resolve("defaults"));
        Files.writeString(defaults.resolve("note.md"), "DEFAULT_MUST_NOT_LEAK");
        Files.createDirectory(directory.resolve("note.md"));
        var catalog = SkillCatalog.load(directory);
        assertTrue(catalog.promptIndex().contains("SKILL_FILE_UNAVAILABLE"));
        assertFalse(catalog.read(name(catalog)).ok());
        assertFalse(catalog.read(name(catalog)).feedback().contains("DEFAULT"));
    }

    @Test void malformedOrDeepSettingsCannotSilentlyReenableNotes() throws Exception {
        Files.writeString(directory.resolve("note.md"), "MUST_NOT_LEAK");
        for (String settings : List.of("null", "{}",
                "{\"version\":1,\"skills\":{},\"version\":1}",
                "{\"version\":1,\"skills\":{\"note.md\":{\"enabled\":\"false\"}}}",
                "{\"version\":1,\"skills\":{\"note.md\":{\"enabled\":false,\"enabled\":true}}}",
                "{\"version\":1,\"skills\":{\"../note.md\":{}}}",
                "[".repeat(2000) + "]".repeat(2000))) {
            Files.writeString(directory.resolve("index.json"), settings);
            var catalog = SkillCatalog.load(directory);
            assertTrue(catalog.index().getAsJsonArray("skills").isEmpty(), settings.substring(0, Math.min(50, settings.length())));
            assertFalse(catalog.index().getAsJsonArray("skill_diagnostics").isEmpty());
            assertFalse(catalog.promptIndex().contains("MUST_NOT_LEAK"));
        }
    }

    @Test void oversizeOrInvalidUtf8HasAnExplicitErrorInsteadOfTruncatedKnowledge() throws Exception {
        Path file = directory.resolve("note.md");
        for (byte[] content : List.of(new byte[]{(byte) 0xc3, (byte) 0x28}, new byte[]{0, 1},
                "x".repeat(SkillCatalog.FILE_BYTES + 1).getBytes(StandardCharsets.UTF_8))) {
            Files.write(file, content);
            var catalog = SkillCatalog.load(directory);
            assertTrue(catalog.promptIndex().contains("SKILL_FILE_UNAVAILABLE"));
            assertFalse(catalog.read(name(catalog)).ok());
        }
    }

    @Test void snapshotHasNoReadTimeFileAccessAndRefreshGetsANewRevision() throws Exception {
        Path file = directory.resolve("note.md");
        Files.writeString(file, "original body");
        var before = SkillCatalog.load(directory);
        Files.writeString(file, "replacement body");
        var after = SkillCatalog.load(directory);
        Files.delete(file);
        assertEquals(name(before), name(after));
        var first = before.read(name(before)).data();
        var second = after.read(name(after)).data();
        assertEquals("original body", first.get("body").getAsString());
        assertEquals("replacement body", second.get("body").getAsString());
        assertNotEquals(first.get("revision"), second.get("revision"));
        first.addProperty("body", "caller mutation");
        assertEquals("original body", before.read(name(before)).data().get("body").getAsString());
    }

    @Test void unknownPathsAndPageNumbersDoNotEchoInputOrExposeFiles() throws Exception {
        Files.writeString(directory.resolve("note.md"), "visible knowledge");
        var catalog = SkillCatalog.load(directory);
        for (String value : List.of("private-path", "../index.json", name(catalog) + ":0",
                name(catalog) + ":99", name(catalog) + ":02", name(catalog) + ":100")) {
            var result = catalog.read(value);
            assertFalse(result.ok());
            assertFalse(result.feedback().contains("private-path"));
            assertFalse(result.feedback().contains("index.json"));
        }
    }

    @Test void catalogAndTotalBodyCapsAreVisibleAndDoNotChangeReadPermissions() throws Exception {
        for (int i = 0; i < 17; i++)
            Files.writeString(directory.resolve(String.format("%02d.md", i)), "x".repeat(SkillCatalog.FILE_BYTES));
        var catalog = SkillCatalog.load(directory);
        long available = catalog.index().getAsJsonArray("skills").asList().stream()
                .filter(entry -> !entry.getAsJsonObject().has("error")).count();
        assertEquals(16, available);
        assertTrue(catalog.promptIndex().contains("SKILL_FILE_UNAVAILABLE"));
        for (int i = 17; i <= SkillCatalog.MAX_SKILLS; i++)
            Files.writeString(directory.resolve(String.format("%02d.md", i)), "small");
        var full = SkillCatalog.load(directory);
        assertTrue(full.index().getAsJsonArray("skills").isEmpty());
        assertTrue(full.promptIndex().contains("SKILL_CATALOG_LIMIT"));
    }

    @Test void missingDirectoryIsEmptyAndDoesNotCreateFiles() {
        var missing = directory.resolve("missing");
        assertTrue(SkillLoader.load(missing).isEmpty());
        assertFalse(Files.exists(missing));
        assertEquals("", SkillCatalog.empty().promptIndex());
    }

    @Test void packagedDesignKnowledgeIsDiscoverableWithoutCreatingUserFiles() throws Exception {
        var missing=directory.resolve("not-created");
        var bundled=SkillCatalog.loadBundled(missing);
        var entry=bundled.index().getAsJsonArray("skills").get(0).getAsJsonObject();
        assertEquals("bundled",entry.get("source").getAsString());
        assertEquals("construction-design.md",entry.get("label").getAsString());
        assertFalse(bundled.promptIndex().contains("先做下面五件事"),"body is read on demand");
        String selector=entry.get("name").getAsString();
        var body=new StringBuilder();
        do {
            var result=bundled.read(selector);
            assertTrue(result.ok());
            body.append(result.data().get("body").getAsString());
            selector=result.data().has("next_name")?result.data().get("next_name").getAsString():null;
        } while(selector!=null);
        assertTrue(body.toString().contains("屋顶"));
        assertTrue(body.toString().contains("mc.build"));
        assertFalse(Files.exists(missing));
    }

    @Test void bundledDesignStillHonorsOwnerDisableLocalOverrideAndMalformedSettings() throws Exception {
        Files.writeString(directory.resolve("construction-design.md"),"OWNER_DESIGN_KNOWLEDGE");
        var overridden=SkillCatalog.loadBundled(directory);
        assertEquals("OWNER_DESIGN_KNOWLEDGE",overridden.read(name(overridden)).data().get("body").getAsString());
        Files.writeString(directory.resolve("index.json"),
                "{\"version\":1,\"skills\":{\"construction-design.md\":{\"enabled\":false}}}");
        assertTrue(SkillCatalog.loadBundled(directory).index().getAsJsonArray("skills").isEmpty());
        Files.writeString(directory.resolve("index.json"),"{broken");
        var invalid=SkillCatalog.loadBundled(directory);
        assertTrue(invalid.index().getAsJsonArray("skills").isEmpty());
        assertFalse(invalid.index().getAsJsonArray("skill_diagnostics").isEmpty());
    }
}
