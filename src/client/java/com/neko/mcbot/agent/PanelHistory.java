package com.neko.mcbot.agent;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/** Session-local display archive; model compaction never rewrites these entries. */
public final class PanelHistory {
    public enum Kind { USER, REPLY, QUESTION, TOOL, NOTICE }
    public record Entry(long sequence, long taskId, Kind kind, String text) {}
    public record Task(long id, String title, String status) {}
    private static final int MAX_ENTRIES = 2000;
    private static final int MAX_TASKS = 20;
    private final ArrayDeque<Entry> entries = new ArrayDeque<>();
    private final LinkedHashMap<Long, Task> tasks = new LinkedHashMap<>();
    private long sequence;
    private long latest;
    private long revision;

    public synchronized void begin(long id, String text) {
        latest = id;
        revision++;
        tasks.put(id, new Task(id, text, "queued"));
        while (tasks.size() > MAX_TASKS) {
            long removed = tasks.keySet().iterator().next();
            tasks.remove(removed);
            entries.removeIf(e -> e.taskId() == removed);
        }
    }
    public synchronized void status(long id, String status) {
        Task old = tasks.get(id);
        if (old != null) {
            tasks.put(id, new Task(id, old.title(), status));
            revision++;
        }
    }
    public synchronized void add(long taskId, String raw) {
        String text = raw.replaceAll("§.", "").replace("\\n", "\n").replace("\\r", "");
        Kind kind = Kind.TOOL;
        if (text.startsWith("我> ")) { kind = Kind.USER; text = text.substring(3); }
        else if (text.startsWith("[同伴想问] ")) {
            kind = Kind.QUESTION; text = text.substring(7).replace("（回答：@bot 答 <文本>）", "").strip();
        } else if (text.startsWith("[同伴] ")) { kind = Kind.REPLY; text = text.substring(5); }
        else if (text.startsWith("[mcbot] ")) { kind = Kind.NOTICE; text = text.substring(8); }
        else if (text.startsWith("（护栏）")) kind = Kind.NOTICE;
        entries.addLast(new Entry(++sequence, taskId, kind, text));
        revision++;
        while (entries.size() > MAX_ENTRIES) entries.removeFirst();
    }
    public synchronized List<Entry> entries(long taskId) {
        return entries.stream().filter(e -> e.taskId() == taskId || e.taskId() == 0).toList();
    }
    public synchronized List<Task> tasks() {
        var result = new ArrayList<>(tasks.values());
        java.util.Collections.reverse(result);
        return List.copyOf(result);
    }
    public synchronized long latestTask() { return latest; }
    public synchronized long revision() { return revision; }
}
