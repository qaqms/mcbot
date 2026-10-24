package com.neko.mcbot.path;

/** Per-search resource bounds with cumulative diagnostics; explicit accounts can still impose a total. */
public final class NavigationBudget {
    public static final int NODES = 4000, RECORDS = 32768, REPLANS = Integer.MAX_VALUE;
    public static final long NANOS = Long.MAX_VALUE;
    private static final int LEGACY_NODE_CAP = 8000;
    private final int nodeCap, recordCap, replanCap;
    private final long timeCap;
    private int nodes, records, replans;
    private long nanos;
    private DigAStar current;
    private int lastNodes, lastRecords;
    private long lastNanos;
    private boolean perSearch;
    private record Observed(int nodes, int records, long nanos) { }
    private final java.util.Map<DigAStar, Observed> observations = new java.util.WeakHashMap<>();

    public NavigationBudget() { this(NODES, RECORDS, NANOS, REPLANS); perSearch = true; }

    NavigationBudget(int nodes, int records, long nanos, int replans) {
        if (nodes < 1 || nodes > LEGACY_NODE_CAP || records < 1 || records > RECORDS
                || nanos < 1 || nanos > NANOS || replans < 0 || replans > REPLANS)
            throw new IllegalArgumentException("budget");
        nodeCap = nodes; recordCap = records; timeCap = nanos; replanCap = replans;
    }

    public void observe(DigAStar search) {
        if (search == null) return;
        if (current != search && (!perSearch || !observations.containsKey(search)))
            throw new IllegalArgumentException("retired or foreign search");
        if (perSearch) {
            var previous = observations.getOrDefault(search, new Observed(0, 0, 0));
            lastNodes = previous.nodes();
            lastRecords = previous.records();
            lastNanos = previous.nanos();
        }
        int used = search.chargedNodes();
        nodes = (int) Math.min(Integer.MAX_VALUE, (long) nodes + Math.max(0, used - lastNodes));
        records = (int) Math.min(Integer.MAX_VALUE, (long) records + Math.max(0, search.storedNodes() - lastRecords));
        long delta = Math.max(0, search.elapsedNanos() - lastNanos);
        nanos = Math.min(Long.MAX_VALUE - delta, nanos) + delta;
        lastNodes = used; lastRecords = search.storedNodes(); lastNanos = search.elapsedNanos();
        if (perSearch) observations.put(search, new Observed(lastNodes, lastRecords, lastNanos));
    }

    public DigAStar search(DigSampler sampler, int x, int y, int z, int tx, int ty, int tz,
                           NavigationGoal goal) {
        return search(sampler, x, y, z, tx, ty, tz, goal, Integer.MAX_VALUE);
    }

    DigAStar search(DigSampler sampler, int x, int y, int z, int tx, int ty, int tz,
                    NavigationGoal goal, int available) {
        observe(current);
        if (available <= 0 || exhausted()) return null;
        var search = new DigAStar(sampler, x, y, z, tx, ty, tz,
                Math.min(available, perSearch ? nodeCap : nodeCap - nodes), 0, goal);
        search.totalBudget(perSearch ? timeCap : timeCap - nanos);
        search.storageBudget(perSearch ? recordCap : recordCap - records);
        if (perSearch) observations.remove(current);
        current = search;
        lastNodes = lastRecords = 0;
        lastNanos = 0;
        observe(search);
        return search;
    }

    public boolean takeReplan() {
        observe(current);
        if (!perSearch && (replans >= replanCap || exhausted())) return false;
        if (replans < Integer.MAX_VALUE) replans++;
        return true;
    }
    public boolean exhausted() { return !perSearch && (nodes >= nodeCap || records >= recordCap || nanos >= timeCap); }
    public int nodes() { return nodes; }
    public int records() { return records; }
    public long nanos() { return nanos; }
    public int replans() { return replans; }
    public boolean perSearch() { return perSearch; }

    public void recordCompleted(DigAStar search) {
        if (!perSearch) throw new IllegalStateException("detached search requires per-search bounds");
        if (current != search) {
            observe(current);
            observations.remove(current);
            current = search;
            lastNodes = lastRecords = 0;
            lastNanos = 0;
        }
        observe(search);
    }

    void trackConcurrent(DigAStar search) {
        if (!perSearch) throw new IllegalStateException("concurrent search requires per-search bounds");
        observations.putIfAbsent(search, new Observed(0, 0, 0));
        observe(search);
    }

    void retireConcurrent(DigAStar search) {
        observe(search);
        if (search != current) observations.remove(search);
    }

    public com.google.gson.JsonObject snapshot() {
        observe(current);
        var data = new com.google.gson.JsonObject();
        data.addProperty("per_search", perSearch);
        data.addProperty("nodes", nodes); data.addProperty("node_cap", nodeCap);
        data.addProperty("records", records); data.addProperty("record_cap", recordCap);
        data.addProperty("nanos", nanos); data.addProperty("time_cap", timeCap);
        data.addProperty("replans", replans); data.addProperty("replan_cap", replanCap);
        return data;
    }

    public static NavigationBudget restore(com.google.gson.JsonObject data) {
        int nodeLimit = count(data, "node_cap", LEGACY_NODE_CAP);
        int recordLimit = count(data, "record_cap", RECORDS);
        long timeLimit = number(data, "time_cap", NANOS);
        var value = new NavigationBudget(nodeLimit, recordLimit,
                timeLimit, count(data, "replan_cap", REPLANS));
        if (data.has("per_search")) {
            if (!data.get("per_search").isJsonPrimitive() || !data.getAsJsonPrimitive("per_search").isBoolean())
                throw new IllegalArgumentException();
            value.perSearch = data.get("per_search").getAsBoolean();
        } else value.perSearch = true; // Old default accounts used a cumulative two-replan policy.
        if (value.perSearch) {
            // Old default snapshots must not restore the retired 400ms search deadline.
            value = new NavigationBudget(Math.min(nodeLimit, NODES), recordLimit, NANOS, REPLANS);
            value.perSearch = true;
        }
        value.nodes = count(data, "nodes", value.perSearch ? Integer.MAX_VALUE : value.nodeCap);
        value.records = count(data, "records", value.perSearch ? Integer.MAX_VALUE : value.recordCap);
        value.nanos = number(data, "nanos", value.perSearch ? Long.MAX_VALUE : value.timeCap);
        value.replans = count(data, "replans", value.perSearch ? Integer.MAX_VALUE : value.replanCap);
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
