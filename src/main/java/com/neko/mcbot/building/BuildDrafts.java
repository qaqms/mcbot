package com.neko.mcbot.building;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Observed drawings survive transcript compaction, but never select work or grant authority. */
public final class BuildDrafts {
    public static final int LIMIT = 16, BYTE_LIMIT = 2 * 1024 * 1024, CELL_LIMIT = 65_536;
    private final Map<String, BuildTarget> targets = new LinkedHashMap<>();
    private int bytes, cells;

    public boolean remember(BuildTarget target) {
        if (targets.containsKey(target.revision())) return true;
        int size = target.input().toString().getBytes(StandardCharsets.UTF_8).length;
        if (targets.size() >= LIMIT || size > BYTE_LIMIT - bytes || target.cells().size() > CELL_LIMIT - cells) return false;
        targets.put(target.revision(), target);
        bytes += size;
        cells += target.cells().size();
        return true;
    }

    public void clear() { targets.clear(); bytes = cells = 0; }

    public BuildTarget resolve(JsonObject reference) {
        BuildTarget.keys(reference, Set.of("draft_revision"));
        String revision = BuildTarget.string(reference.get("draft_revision"), 64);
        if (!revision.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("draft_revision");
        var target = targets.get(revision);
        if (target == null) throw new IllegalArgumentException("draft_unavailable");
        return target;
    }

    public static JsonObject reference(BuildTarget target) {
        var ref = new JsonObject();
        ref.addProperty("draft_revision", target.revision());
        return ref;
    }

    private static JsonObject describe(BuildTarget target) {
        var row = new JsonObject();
        row.add("target", reference(target));
        row.addProperty("target_revision", target.revision());
        row.addProperty("cell_count", target.cells().size());
        row.add("anchor", BuildTarget.coordinates(target.anchor()));
        row.addProperty("rotation", target.rotation());
        return row;
    }

    public JsonObject list() {
        var data = new JsonObject();
        var rows = new JsonArray();
        targets.values().forEach(target -> rows.add(describe(target)));
        data.add("entries", rows);
        data.addProperty("next_offset", -1);
        data.addProperty("task_scoped", true);
        data.addProperty("persistent", false);
        return data;
    }

    public JsonObject query(JsonObject reference, int offset) {
        var target = resolve(reference);
        if (offset < 0 || offset % 16 != 0 || offset >= target.cells().size())
            throw new IllegalArgumentException("draft_page");
        var data = describe(target);
        var cells = new JsonArray();
        target.cells().stream().skip(offset).limit(16).map(target::relativeCell).forEach(cells::add);
        data.add("cells", cells);
        data.addProperty("offset", offset);
        data.addProperty("next_offset", offset + cells.size() < target.cells().size() ? offset + cells.size() : -1);
        data.addProperty("task_scoped", true);
        data.addProperty("persistent", false);
        data.add("design_review", BuildDesignReview.describe(target));
        return data;
    }
}
