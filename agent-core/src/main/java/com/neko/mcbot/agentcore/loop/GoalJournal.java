package com.neko.mcbot.agentcore.loop;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;

import java.io.IOException;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;

/** Bounded client-local checkpoints. A durable marker closes the replay window before any dispatch. */
public final class GoalJournal {
    public static final int FILE_LIMIT = 512 * 1024;
    public static final int SCOPE_LIMIT = 128;
    private final Path directory;

    public GoalJournal(Path directory) { this.directory = directory.toAbsolutePath().normalize(); }

    private Path path(String scope, String suffix) throws IOException {
        if (!scope.matches("[0-9a-f-]{36}_[0-9a-f-]{36}_[0-9a-f-]{36}"))
            throw new IllegalArgumentException("Invalid journal scope");
        Files.createDirectories(directory);
        if (Files.isSymbolicLink(directory)) throw new IOException("journal_link");
        Path result = directory.resolve(scope + suffix);
        if (Files.isSymbolicLink(result)) throw new IOException("journal_link");
        return result;
    }

    private static void durable(Path file, byte[] bytes) throws IOException {
        try (var channel = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING, LinkOption.NOFOLLOW_LINKS)) {
            var buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) channel.write(buffer);
            channel.force(true);
        }
    }

    public synchronized void block(String scope) {
        try {
            Path marker = path(scope, ".blocked");
            var scopes = new java.util.HashSet<String>();
            try (var files = Files.newDirectoryStream(directory)) {
                for (var file : files) {
                    String name = file.getFileName().toString();
                    if (name.matches("[0-9a-f-]{36}_[0-9a-f-]{36}_[0-9a-f-]{36}\\.(json|blocked|tmp)"))
                        scopes.add(name.substring(0, name.lastIndexOf('.')));
                    if (scopes.size() >= SCOPE_LIMIT && !scopes.contains(scope)
                            && !Files.exists(marker, LinkOption.NOFOLLOW_LINKS)
                            && !Files.exists(path(scope, ".json"), LinkOption.NOFOLLOW_LINKS)
                            && !Files.exists(path(scope, ".tmp"), LinkOption.NOFOLLOW_LINKS))
                        throw new IOException("journal_capacity");
                }
            }
            durable(marker, new byte[]{1});
        }
        catch (IOException failure) { throw new UncheckedIOException("Checkpoint unavailable", failure); }
    }

    public synchronized void save(String scope, JsonObject core, JsonObject host, boolean ready) {
        if (!scope.equals(GoalCheckpoint.scope(core.getAsJsonObject("initial")))) throw new IllegalArgumentException();
        block(scope);
        try {
            Path file = path(scope, ".json"), temporary = path(scope, ".tmp");
            var record = new JsonObject();
            record.addProperty("version", 1);
            record.add("core", core.deepCopy());
            record.add("host", host.deepCopy());
            byte[] bytes = record.toString().getBytes(StandardCharsets.UTF_8);
            if (bytes.length > FILE_LIMIT) throw new IOException("journal_size");
            durable(temporary, bytes);
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            if (ready) Files.deleteIfExists(path(scope, ".blocked"));
        } catch (IOException failure) { throw new UncheckedIOException("Checkpoint unavailable", failure); }
    }

    public synchronized JsonObject load(String scope) {
        try {
            Path file = path(scope, ".json");
            if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return null;
            if (Files.exists(path(scope, ".blocked"), LinkOption.NOFOLLOW_LINKS)) throw new IOException("journal_in_flight");
            if (Files.size(file) > FILE_LIMIT) throw new IOException("journal_size");
            byte[] bytes;
            try (var stream = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
                bytes = stream.readNBytes(FILE_LIMIT + 1);
            }
            if (bytes.length > FILE_LIMIT) throw new IOException("journal_size");
            String text = new String(bytes, StandardCharsets.UTF_8);
            // Validate structural limits through the JSON token API before allocating a tree.
            try (var reader = new JsonReader(new StringReader(text))) {
                int depth = 0, tokens = 0;
                while (reader.peek() != JsonToken.END_DOCUMENT) {
                    if (++tokens > 100000) throw new IOException("journal_tokens");
                    switch (reader.peek()) {
                        case BEGIN_OBJECT -> { reader.beginObject(); depth++; }
                        case BEGIN_ARRAY -> { reader.beginArray(); depth++; }
                        case END_OBJECT -> { reader.endObject(); depth--; }
                        case END_ARRAY -> { reader.endArray(); depth--; }
                        case NAME -> reader.nextName();
                        case STRING, NUMBER -> reader.nextString();
                        case BOOLEAN -> reader.nextBoolean();
                        case NULL -> reader.nextNull();
                        default -> throw new IOException("journal_json");
                    }
                    if (depth > 32 || depth < 0) throw new IOException("journal_depth");
                }
            }
            var record = JsonParser.parseString(text).getAsJsonObject();
            GoalCheckpoint.bounded(record, "version", 1, 1);
            if (!scope.equals(GoalCheckpoint.scope(record.getAsJsonObject("core").getAsJsonObject("initial")))
                    || !record.has("host") || !record.get("host").isJsonObject()) throw new IOException("journal_identity");
            return record;
        } catch (IOException failure) { throw new UncheckedIOException("Checkpoint unavailable", failure); }
    }

    public synchronized void clear(String scope) {
        try {
            Path file = path(scope, ".json");
            if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
                block(scope);
                Files.delete(file);
            }
            Path temporary = path(scope, ".tmp");
            if (Files.isRegularFile(temporary, LinkOption.NOFOLLOW_LINKS)) Files.delete(temporary);
            Files.deleteIfExists(path(scope, ".blocked"));
        }
        catch (IOException failure) { throw new UncheckedIOException("Checkpoint unavailable", failure); }
    }
}
