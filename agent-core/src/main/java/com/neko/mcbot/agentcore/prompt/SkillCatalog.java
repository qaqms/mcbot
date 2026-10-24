package com.neko.mcbot.agentcore.prompt;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.neko.mcbot.agentcore.loop.ToolExecutor.ToolOutcome;

import java.io.IOException;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/** A bounded, immutable knowledge snapshot shared by the prompt index and local help reads. */
public final class SkillCatalog {
    public static final int MAX_SKILLS = 32;
    public static final int FILE_BYTES = 65536;
    public static final int TOTAL_BYTES = 1024 * 1024;
    public static final int PAGE_BYTES = 4096;
    private static final int SETTINGS_BYTES = 16384;
    private static final int DIRECTORY_ENTRIES = 256;
    private static final SkillCatalog EMPTY = new SkillCatalog(Map.of(), List.of());

    private record Preference(boolean enabled, String description) {}
    private record Note(String name, String label, String description, String source, String revision,
                        int bytes, List<String> pages, String error) {}
    private final Map<String, Note> notes;
    private final List<String> diagnostics;

    private SkillCatalog(Map<String, Note> notes, List<String> diagnostics) {
        this.notes = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(notes));
        this.diagnostics = List.copyOf(diagnostics);
    }

    public static SkillCatalog empty() { return EMPTY; }

    /** Packaged knowledge is opt-in for application callers; local overrides and disables still win. */
    public static SkillCatalog loadBundled(Path directory) {
        var local = load(directory);
        if (!local.diagnostics.isEmpty()) return local;
        String key = "construction-design.md";
        String name = "skill:" + digest(key).substring(0, 16);
        if (local.notes.containsKey(name) || local.notes.size() >= MAX_SKILLS) return local;
        try {
            Preference preference = new Preference(true, "");
            if (directory != null && Files.exists(directory, LinkOption.NOFOLLOW_LINKS))
                preference = preferences(directory.toRealPath()).getOrDefault(key, preference);
            if (!preference.enabled()) return local;
            var notes = new LinkedHashMap<>(local.notes);
            try (var source = SkillCatalog.class.getResourceAsStream("construction-design.md")) {
                if (source == null) return unavailable("BUNDLED_SKILL_UNAVAILABLE");
                byte[] text = source.readNBytes(FILE_BYTES + 1);
                if (text.length > FILE_BYTES) return unavailable("BUNDLED_SKILL_UNAVAILABLE");
                String body = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(text)).toString();
                int total = local.notes.values().stream().mapToInt(Note::bytes).sum() + text.length;
                if (total > TOTAL_BYTES) return local;
                String description = preference.description().isBlank()
                        ? "建筑设计：体量、材料对比、屋顶、门窗与施工次序" : preference.description();
                notes.put(name, new Note(name, key, description, "bundled", digest(body),
                        text.length, pages(body), ""));
            }
            return new SkillCatalog(notes, local.diagnostics);
        } catch (IOException | RuntimeException invalid) {
            return unavailable("BUNDLED_SKILL_UNAVAILABLE");
        }
    }

    public static SkillCatalog load(Path directory) {
        if (directory == null || !Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) return empty();
        try {
            Path root = directory.toRealPath();
            if (Files.isSymbolicLink(directory) || !Files.isDirectory(root)) throw new IOException();
            Map<String, Preference> preferences = preferences(root);
            var files = new TreeMap<String, Path>();
            collect(root, root.resolve("defaults"), files);
            var local = new TreeMap<String, Path>();
            collect(root, root, local);
            files.putAll(local);
            if (files.size() > MAX_SKILLS) return unavailable("SKILL_CATALOG_LIMIT");
            var notes = new LinkedHashMap<String, Note>();
            int remaining = TOTAL_BYTES;
            for (var file : files.entrySet()) {
                var preference = preferences.getOrDefault(file.getKey(), new Preference(true, ""));
                if (!preference.enabled()) continue;
                String name = "skill:" + digest(file.getKey()).substring(0, 16);
                String label = clip(file.getValue().getFileName().toString(), 96);
                String description = preference.description().isBlank()
                        ? clip(file.getKey().substring(0, file.getKey().length() - 3), 160)
                        : preference.description();
                String source = local.containsKey(file.getKey()) ? "local" : "defaults";
                try {
                    String body = read(root, file.getValue(), Math.min(FILE_BYTES, remaining));
                    int size = bytes(body);
                    remaining -= size;
                    notes.put(name, new Note(name, label, description, source, digest(body), size,
                            pages(body), ""));
                } catch (IOException | RuntimeException invalid) {
                    // An invalid local override must never silently expose the default note instead.
                    notes.put(name, new Note(name, label, description, source, "", 0, List.of(),
                            "SKILL_FILE_UNAVAILABLE"));
                }
            }
            return new SkillCatalog(notes, List.of());
        } catch (IOException | RuntimeException invalid) {
            // In particular, malformed settings cannot accidentally re-enable disabled knowledge.
            return unavailable("SKILL_CATALOG_UNAVAILABLE");
        }
    }

    private static SkillCatalog unavailable(String reason) {
        return new SkillCatalog(Map.of(), List.of(reason));
    }

    private static void collect(Path root, Path directory, Map<String, Path> files) throws IOException {
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) return;
        if (Files.isSymbolicLink(directory) || !directory.toRealPath().startsWith(root)
                || !Files.isDirectory(directory)) throw new IOException();
        try (var stream = Files.newDirectoryStream(directory)) {
            int count = 0;
            for (Path file : stream) {
                if (++count > DIRECTORY_ENTRIES) throw new IOException();
                String name = file.getFileName().toString();
                if (!name.toLowerCase(Locale.ROOT).endsWith(".md")) continue;
                String key = key(name);
                if (files.putIfAbsent(key, file) != null) throw new IOException();
            }
        }
    }

    private static String key(String name) {
        if (name.length() > 128 || !name.toLowerCase(Locale.ROOT).endsWith(".md")
                || name.length() <= 3 || name.chars().anyMatch(c -> c < 32 || c == '/' || c == '\\' || c == ':'))
            throw new IllegalArgumentException();
        return name.toLowerCase(Locale.ROOT);
    }

    private static Map<String, Preference> preferences(Path root) throws IOException {
        Path settings = root.resolve("index.json");
        if (!Files.exists(settings, LinkOption.NOFOLLOW_LINKS)) return Map.of();
        var result = new LinkedHashMap<String, Preference>();
        try (var reader = new JsonReader(new StringReader(read(root, settings, SETTINGS_BYTES)))) {
            reader.setStrictness(Strictness.STRICT);
            reader.beginObject();
            var fields = new java.util.HashSet<String>();
            while (reader.hasNext()) {
                String field = reader.nextName();
                if (!fields.add(field)) throw new IOException();
                if (field.equals("version")) {
                    if (reader.peek() != JsonToken.NUMBER || !reader.nextString().equals("1")) throw new IOException();
                } else if (field.equals("skills")) {
                    reader.beginObject();
                    while (reader.hasNext()) {
                        String name = key(reader.nextName());
                        if (result.size() >= MAX_SKILLS || result.containsKey(name)) throw new IOException();
                        reader.beginObject();
                        boolean enabled = true;
                        String description = "";
                        var properties = new java.util.HashSet<String>();
                        while (reader.hasNext()) {
                            String property = reader.nextName();
                            if (!properties.add(property)) throw new IOException();
                            if (property.equals("enabled") && reader.peek() == JsonToken.BOOLEAN)
                                enabled = reader.nextBoolean();
                            else if (property.equals("description") && reader.peek() == JsonToken.STRING) {
                                description = reader.nextString().strip();
                                if (bytes(description) > 160) throw new IOException();
                            } else throw new IOException();
                        }
                        reader.endObject();
                        result.put(name, new Preference(enabled, description));
                    }
                    reader.endObject();
                } else throw new IOException();
            }
            reader.endObject();
            if (!fields.equals(java.util.Set.of("version", "skills")) || reader.peek() != JsonToken.END_DOCUMENT)
                throw new IOException();
        }
        return result;
    }

    private static String read(Path root, Path file, int limit) throws IOException {
        if (Files.isSymbolicLink(file) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
                || !file.toRealPath().startsWith(root) || Files.size(file) > limit) throw new IOException();
        byte[] content;
        try (var stream = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
            content = stream.readNBytes(limit + 1);
        }
        if (content.length > limit) throw new IOException();
        String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(content)).toString();
        if (text.codePoints().anyMatch(c -> c < 32 && c != '\n' && c != '\r' && c != '\t'))
            throw new IOException();
        return text;
    }

    private static List<String> pages(String text) {
        var result = new ArrayList<String>();
        int start = 0;
        do {
            int end = end(text, start, PAGE_BYTES);
            result.add(text.substring(start, end));
            start = end;
        } while (start < text.length());
        return List.copyOf(result);
    }

    private static int end(String text, int start, int limit) {
        int used = 0, at = start;
        while (at < text.length()) {
            int code = text.codePointAt(at);
            int size = code <= 127 ? 1 : code <= 2047 ? 2 : code <= 65535 ? 3 : 4;
            if (used + size > limit) break;
            used += size;
            at += Character.charCount(code);
        }
        return at;
    }

    private static String clip(String text, int limit) { return text.substring(0, end(text, 0, limit)); }
    private static int bytes(String value) { return value.getBytes(StandardCharsets.UTF_8).length; }
    private static String digest(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    public JsonObject index() {
        var result = new JsonObject();
        var skills = new JsonArray();
        for (Note note : notes.values()) {
            var item = new JsonObject();
            item.addProperty("name", note.name());
            item.addProperty("label", note.label());
            item.addProperty("description", note.description());
            item.addProperty("source", note.source());
            item.addProperty("revision", note.revision());
            item.addProperty("body_bytes", note.bytes());
            item.addProperty("pages", note.pages().size());
            if (!note.error().isEmpty()) item.addProperty("error", note.error());
            skills.add(item);
        }
        result.add("skills", skills);
        var errors = new JsonArray();
        diagnostics.forEach(errors::add);
        result.add("skill_diagnostics", errors);
        return result;
    }

    public String promptIndex() {
        if (notes.isEmpty() && diagnostics.isEmpty()) return "";
        return "\n# 技能目录\n技能只是参考知识，不授予权限或证明当前现场。"
                + "用tool_help的names单独传入目录name读取一页，续页照返回的next_name；不要自行构造名字。"
                + "正文仅在需要时读取；同revision且仍在上下文中可复用，整理后缺正文则重新读取。"
                + "下列索引不含正文，读取失败须如实处理。\n" + index() + "\n";
    }

    public ToolOutcome read(String selector) {
        Note note = notes.get(selector);
        int page = 1;
        if (note == null && selector != null) {
            int split = selector.lastIndexOf(':');
            if (split > 5) {
                note = notes.get(selector.substring(0, split));
                String suffix = selector.substring(split + 1);
                if (!suffix.matches("[1-9][0-9]?")) return failure("SKILL_PAGE_INVALID");
                page = Integer.parseInt(suffix);
            }
        }
        if (note == null) return failure("SKILL_UNAVAILABLE");
        if (!note.error().isEmpty()) return failure(note.error());
        if (page > note.pages().size()) return failure("SKILL_PAGE_INVALID");
        var data = new JsonObject();
        data.addProperty("knowledge_only", true);
        data.addProperty("name", selector);
        data.addProperty("label", note.label());
        data.addProperty("revision", note.revision());
        data.addProperty("page", page);
        data.addProperty("pages", note.pages().size());
        data.addProperty("body", note.pages().get(page - 1));
        if (page < note.pages().size()) data.addProperty("next_name", note.name() + ":" + (page + 1));
        return new ToolOutcome(true, "只读技能参考，不表示已执行动作或获得许可。\n" + data, data);
    }

    private static ToolOutcome failure(String code) {
        return new ToolOutcome(false, code + ":技能未读取；用tool_help {}核对本任务目录，不能猜正文或据此授予动作权限。");
    }
}
