package com.neko.mcbot.path;

/** One finite search account across dynamic goals, discarded routes and terrain replans. */
public final class NavigationBudget {
    public static final int NODES = 8000, RECORDS = 32768, REPLANS = 2;
    public static final long NANOS = 400_000_000L;
    private final int nodeCap, recordCap, replanCap;
    private final long timeCap;
    private int nodes, records, replans;
    private long nanos;
    private DigAStar current;
    private int lastNodes, lastRecords;
    private long lastNanos;

    public NavigationBudget() { this(NODES, RECORDS, NANOS, REPLANS); }

    NavigationBudget(int nodes, int records, long nanos, int replans) {
        if (nodes < 1 || nodes > NODES || records < 1 || records > RECORDS
                || nanos < 1 || nanos > NANOS || replans < 0 || replans > REPLANS)
            throw new IllegalArgumentException("budget");
        nodeCap = nodes; recordCap = records; timeCap = nanos; replanCap = replans;
    }

    public void observe(DigAStar search) {
        if (search == null) return;
        if (current != search) throw new IllegalArgumentException("retired or foreign search");
        int used = search.expanded() + search.estimateExpanded();
        nodes += Math.max(0, used - lastNodes);
        records += Math.max(0, search.storedNodes() - lastRecords);
        nanos += Math.max(0, search.elapsedNanos() - lastNanos);
        lastNodes = used; lastRecords = search.storedNodes(); lastNanos = search.elapsedNanos();
    }

    public DigAStar search(DigSampler sampler, int x, int y, int z, int tx, int ty, int tz,
                           NavigationGoal goal) {
        observe(current);
        if (exhausted()) return null;
        var search = new DigAStar(sampler, x, y, z, tx, ty, tz, nodeCap - nodes, 0, goal);
        search.totalBudget(timeCap - nanos);
        search.storageBudget(recordCap - records);
        current = search;
        lastNodes = lastRecords = 0;
        lastNanos = 0;
        observe(search);
        return search;
    }

    public boolean takeReplan() {
        observe(current);
        if (replans >= replanCap || exhausted()) return false;
        replans++;
        return true;
    }
    public boolean exhausted() { return nodes >= nodeCap || records >= recordCap || nanos >= timeCap; }
    public int nodes() { return nodes; }
    public int records() { return records; }
    public long nanos() { return nanos; }
    public int replans() { return replans; }

    public com.google.gson.JsonObject snapshot() {
        observe(current);
        var data = new com.google.gson.JsonObject();
        data.addProperty("nodes", Math.min(nodes, nodeCap)); data.addProperty("node_cap", nodeCap);
        data.addProperty("records", Math.min(records, recordCap)); data.addProperty("record_cap", recordCap);
        data.addProperty("nanos", Math.min(nanos, timeCap)); data.addProperty("time_cap", timeCap);
        data.addProperty("replans", replans); data.addProperty("replan_cap", replanCap);
        return data;
    }

    public static NavigationBudget restore(com.google.gson.JsonObject data) {
        var value = new NavigationBudget(count(data, "node_cap", NODES), count(data, "record_cap", RECORDS),
                number(data, "time_cap", NANOS), count(data, "replan_cap", REPLANS));
        value.nodes = count(data, "nodes", value.nodeCap);
        value.records = count(data, "records", value.recordCap);
        value.nanos = number(data, "nanos", value.timeCap);
        value.replans = count(data, "replans", value.replanCap);
        return value;
    }

    private static int count(com.google.gson.JsonObject data, String key, int max) {
        return Math.toIntExact(number(data, key, max));
    }
    private static long number(com.google.gson.JsonObject data, String key, long max) {
        var item = data.get(key);
        if (item == null || !item.isJsonPrimitive() || !item.getAsJsonPrimitive().isNumber())
            throw new IllegalArgumentException();
        long value = item.getAsBigDecimal().longValueExact();
        if (value < 0 || value > max) throw new IllegalArgumentException();
        return value;
    }
}
