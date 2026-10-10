package com.neko.mcbot.server;

import com.google.gson.JsonObject;
import com.neko.mcbot.agentcore.loop.TaskPolicy;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.LongSupplier;

/** Server-held task identity and one-use approvals. All calls run on the server thread. */
public final class ActionPermissions {
    public record Context(UUID owner, UUID companion, String dimension, long taskId, boolean readOnly) {
    }

    public record Change(String operation, long position, Object expectedState) {
    }

    public record Scope(Context context, long target, Map<String, Change> changes) {
        public Scope {
            changes = Map.copyOf(changes);
        }

        public boolean contains(Change change) {
            return change.equals(changes.get(key(change)));
        }
    }

    /** A taken approval can only cover its original changes, each observed at most once. */
    public static final class Execution {
        private final Scope scope;
        private final java.util.Set<String> consumed = new java.util.HashSet<>();

        public Execution(Scope scope) { this.scope = scope; }
        public boolean allows(Change change) {
            return scope != null && scope.contains(change) && !consumed.contains(key(change));
        }
        public boolean covers(Map<String, Change> changes) {
            return changes.values().stream().allMatch(this::allows);
        }
        public void observed(String operation, long position) {
            consumed.add(key(new Change(operation, position, null)));
        }
    }

    private record Proposal(String id, Scope scope, long createdAt, boolean approved) {
    }

    public static final long TTL_MS = 180_000;
    private final Map<UUID, Context> tasks = new HashMap<>();
    private final Map<UUID, Object> bodies = new HashMap<>();
    private final Map<UUID, Proposal> proposals = new HashMap<>();
    private final LongSupplier clock;

    public ActionPermissions() { this(System::currentTimeMillis); }
    public ActionPermissions(LongSupplier clock) { this.clock = clock; }

    public void begin(Context context) {
        begin(context, null);
    }

    public void begin(Context context, Object body) {
        tasks.put(context.owner(), context);
        bodies.put(context.owner(), body);
        proposals.remove(context.owner());
    }

    public Object body(UUID owner) { return bodies.get(owner); }

    public Context current(UUID owner, UUID companion, String dimension, long taskId, Object body) {
        return bodies.get(owner) == body ? current(owner, companion, dimension, taskId) : null;
    }

    public Context current(UUID owner, UUID companion, String dimension, long taskId) {
        Context context = tasks.get(owner);
        return context != null && context.taskId() == taskId && context.companion().equals(companion)
                && context.dimension().equals(dimension) ? context : null;
    }

    public boolean end(UUID owner, long taskId) {
        Context context = tasks.get(owner);
        if (context != null && (taskId == 0 || context.taskId() == taskId)) {
            tasks.remove(owner);
            bodies.remove(owner);
            proposals.remove(owner);
            return true;
        }
        return false;
    }

    public static boolean allowed(Context context, String tool, JsonObject args) {
        return TaskPolicy.observation(tool, args) || context != null && !context.readOnly()
                && java.util.Set.of("equip", "craft", "smelt", "move_to", "break_block", "place_block",
                        "collect", "transfer", "wait").contains(tool);
    }

    public String propose(Scope scope) {
        if (scope.context() == null || scope.context().readOnly() || scope.changes().size() > 256
                || !scope.context().equals(tasks.get(scope.context().owner()))) return null;
        String id = UUID.randomUUID().toString();
        proposals.put(scope.context().owner(), new Proposal(id, scope, clock.getAsLong(), false));
        return id;
    }

    public boolean approve(Context context, String id) {
        Proposal proposal = valid(context, id);
        if (proposal == null || context.readOnly()) return false;
        proposals.put(context.owner(), new Proposal(id, proposal.scope(), proposal.createdAt(), true));
        return true;
    }

    public Scope take(Context context, String id, long target) {
        Proposal proposal = valid(context, id);
        if (proposal == null || !proposal.approved() || proposal.scope().target() != target) return null;
        proposals.remove(context.owner());
        return proposal.scope();
    }

    private Proposal valid(Context context, String id) {
        if (context == null || !context.equals(tasks.get(context.owner()))) return null;
        Proposal proposal = proposals.get(context.owner());
        if (proposal == null || !proposal.id().equals(id) || !proposal.scope().context().equals(context)) return null;
        long age = clock.getAsLong() - proposal.createdAt();
        if (age < 0 || age > TTL_MS) {
            proposals.remove(context.owner());
            return null;
        }
        return proposal;
    }

    public static String key(Change change) { return change.operation() + ":" + change.position(); }
}
