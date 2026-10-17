package com.neko.mcbot.server;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.neko.mcbot.common.Envelope;
import com.neko.mcbot.server.tools.WaitTool;
import com.neko.mcbot.task.CompanionScheduler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class ServerToolDispatcherTest {
    @BeforeAll static void bootstrap() { BlockActionFixtures.bootstrap(); }
    private final UUID owner = UUID.randomUUID(), companion = UUID.randomUUID();
    private final AtomicLong clock = new AtomicLong();
    private final CompanionScheduler scheduler = new CompanionScheduler();
    private final Map<UUID, ServerToolDispatcher.Body> bodies = new HashMap<>();
    private final List<Envelope> receipts = new ArrayList<>();
    private final List<String> effects = new ArrayList<>();
    private final ToolRegistry registry = new ToolRegistry();
    private final com.neko.mcbot.body.BodyControl control = new com.neko.mcbot.body.BodyControl(previous -> {});
    private final com.neko.mcbot.body.BodyChangeFixtures facts = new com.neko.mcbot.body.BodyChangeFixtures();
    private CompletableFuture<ServerTool.Result> nextResult;
    private Runnable onRun = () -> {};
    private ActionPermissions.Context receivedContext;
    private ActionPermissions receivedPermissions;
    private final ServerToolDispatcher dispatcher;
    private final ServerToolDispatcher.Sender sender =
            new ServerToolDispatcher.Sender(owner, null, json -> receipts.add(Envelope.decode(json)));

    ServerToolDispatcherTest() {
        bodies.put(owner, body("world", new Object()));
        for (String name : List.of("status", "inventory", "scan_area", "find_resource", "inspect_block", "build_diff",
                "equip", "craft", "smelt", "move_to", "break_block", "place_block", "collect", "transfer", "attack", "build_nearby", "build_site", "build_record", "build_blueprint")) {
            registry.register(new ServerTool() {
                @Override public String name() { return name; }
                @Override public Acceptance acceptanceMode() {
                    return List.of("move_to", "break_block", "attack", "build_nearby").contains(name) ? Acceptance.ACCEPT : Acceptance.SYNC;
                }
            });
        }
        registry.register(new WaitTool());
        dispatcher = new ServerToolDispatcher(registry, scheduler, new RateGuard(20, 60, clock::get),
                new ServerToolDispatcher.ToolAccess() {
                    @Override public ServerToolDispatcher.Body companion(UUID caller) { return bodies.get(caller); }
                    @Override public com.neko.mcbot.body.BodyControl control(ServerToolDispatcher.Body body) {
                        return control;
                    }
                    @Override public com.neko.mcbot.body.BodyChangeLog changes(ServerToolDispatcher.Body body) {
                        return facts.log;
                    }
                    @Override public CompletableFuture<ServerTool.Result> run(ServerTool tool,
                            ServerToolDispatcher.Body body, JsonObject args, ActionPermissions permissions,
                            ActionPermissions.Context context) {
                        effects.add(tool.name());
                        receivedContext = context;
                        receivedPermissions = permissions;
                        onRun.run();
                        return nextResult == null ? CompletableFuture.completedFuture(
                                new ServerTool.Result(true, "effect", null)) : nextResult;
                    }
                });
    }

    private ServerToolDispatcher.Body body(String dimension, Object identity) {
        return new ServerToolDispatcher.Body(companion, dimension, identity, 2, 90, 0, "sample");
    }

    private static JsonObject json(String text) {
        return JsonParser.parseString(text).getAsJsonObject();
    }
    @Test void blueprintInspectionSharesReadOnlyPolicyAndImportUsesOriginalBodyControl() {
        begin(sender,1,true);
        assertTrue(call(1,"build_blueprint","{\"operation\":\"inspect\",\"name\":\"sample\"}").bool("ok"));
        assertFalse(call(1,"build_blueprint","{\"operation\":\"import\"}").bool("ok"));
        begin(sender,2,false); control.reflex(true);
        assertTrue(call(2,"build_blueprint","{\"operation\":\"list\"}").bool("ok"));
        assertTrue(call(2,"build_blueprint","{\"operation\":\"import\"}").str("feedback").startsWith("BUSY:"));
        control.reflex(false);
        assertTrue(call(2,"build_blueprint","{\"operation\":\"import\"}").bool("ok"));
        assertEquals(3,effects.size());
    }
    @Test void durableDataEditsShareTaskPermissionAndControlWhileRecordQueriesStayReadOnly() {
        begin(sender,1,true);
        assertFalse(call(1,"build_record","{\"operation\":\"design_create\"}").bool("ok"));
        assertTrue(call(1,"build_record","{\"operation\":\"design_list\"}").bool("ok"));
        begin(sender,2,false); control.reflex(true);
        assertTrue(call(2,"build_record","{\"operation\":\"instance_create\"}").str("feedback").startsWith("BUSY:"));
        assertTrue(call(2,"build_record","{\"operation\":\"instance_list\"}").bool("ok"));
        control.reflex(false);
        assertTrue(call(2,"build_record","{\"operation\":\"design_patch\"}").bool("ok"));
        assertEquals(List.of("build_record","build_record","build_record"),effects);
    }
    @Test void worksiteQueryRemainsReadOnlyWhileRegistrationSharesTheOriginalControlAndPermissionGate() {
        begin(sender,1,true);
        assertFalse(call(1,"build_site","{\"operation\":\"begin\"}").bool("ok"));
        assertTrue(call(1,"build_site","{\"operation\":\"query\"}").bool("ok"));
        assertEquals(List.of("build_site"),effects);
        begin(sender,2,false); control.reflex(true);
        assertTrue(call(2,"build_site","{\"operation\":\"checkpoint\"}").str("feedback").startsWith("BUSY:"));
        assertTrue(call(2,"build_site","{\"operation\":\"query\"}").bool("ok"));
        control.reflex(false);
        assertTrue(call(2,"build_site","{\"operation\":\"begin\"}").bool("ok"));
    }

    @Test void actualDispatcherUsesSameControlGateForSyncAndAcceptedCallsButKeepsQueriesAvailable() {
        begin(sender, 1, false);
        control.reflex(true);
        for (String tool : List.of("craft", "move_to", "break_block", "attack", "wait")) {
            assertTrue(call(1, tool, "{}").str("feedback").startsWith("BUSY:"), tool);
        }
        assertTrue(effects.isEmpty());
        assertTrue(call(1, "status", "{}").bool("ok"));
        assertTrue(call(1, "place_block", "{\"query\":true}").bool("ok"));
        control.reflex(false);
        assertTrue(call(1, "equip", "{}").bool("ok"));
        assertEquals(com.neko.mcbot.body.BodyControl.Owner.IDLE, control.owner());
    }

    @Test void buildingObservationRemainsAvailableUnderReadOnlyPolicyAndReflexControl() {
        begin(sender, 1, true);
        control.reflex(true);
        assertTrue(call(1, "build_diff", "{}").bool("ok"));
        assertEquals(List.of("build_diff"), effects);
        assertTrue(receivedContext.readOnly());
        assertEquals(com.neko.mcbot.body.BodyControl.Owner.REFLEX, control.owner());
    }

    @Test void buildingBatchRequiresNormalPermissionAndControlAndUsesTheAcceptedTerminalChannel() {
        begin(sender, 1, true);
        assertFalse(call(1, "build_nearby", "{}").bool("ok"));
        assertTrue(effects.isEmpty());
        begin(sender, 2, false);
        control.reflex(true);
        assertTrue(call(2, "build_nearby", "{}").str("feedback").startsWith("BUSY:"));
        assertTrue(effects.isEmpty());
        control.reflex(false);
        nextResult = new CompletableFuture<>();
        var ack = call(2, "build_nearby", "{}");
        assertEquals("job_ack", ack.kind());
        int count = receipts.size();
        nextResult.complete(new ServerTool.Result(true, "batch only",
                json("{\"placed_blocks\":2,\"consumed_count\":2,\"goal_satisfied\":false}")));
        assertEquals(count + 1, receipts.size());
        assertEquals("job_event", receipts.getLast().kind());
        assertFalse(receipts.getLast().obj("data").get("goal_satisfied").getAsBoolean());
    }
    @Test void acceptedPartialFailureCarriesBodyFactsExactlyOnceWithoutChangingEffectCounters() {
        begin(sender, 1, false);
        nextResult = new CompletableFuture<>();
        var ack = call(1, "break_block", "{}");
        assertEquals("job_ack", ack.kind());
        facts.hp = 17;
        facts.held = new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.STONE, 2);
        var data = json("{\"removed\":true,\"cleared_blocks\":1}");
        nextResult.complete(new ServerTool.Result(false, "PARTIAL:world changed", data));
        var terminal = receipts.getLast();
        assertEquals("failed", terminal.str("phase"));
        var result = terminal.body().getAsJsonObject("data");
        assertTrue(result.get("removed").getAsBoolean());
        assertEquals(1, result.get("cleared_blocks").getAsInt());
        assertEquals(17, result.getAsJsonObject("body_delta").getAsJsonObject("net")
                .getAsJsonArray("health").get(1).getAsInt());
        assertTrue(terminal.str("text").contains("身体区间观察"));
        assertEquals(2, receipts.size());
        assertFalse(nextResult.complete(new ServerTool.Result(true, "duplicate", null)));
        assertEquals(2, receipts.size());
    }

    @Test void cancellingAcceptedCallStillSettlesOriginalFutureAndKeepsObservedChanges() {
        begin(sender, 1, false);
        nextResult = new CompletableFuture<>();
        call(1, "move_to", "{}");
        facts.hp = 16;
        send(sender, "cancel", new JsonObject());
        assertTrue(nextResult.isDone());
        var event = receipts.stream().filter(e -> e.kind().equals("job_event")).findFirst().orElseThrow();
        assertEquals("cancelled", event.str("phase"));
        assertEquals(16, event.body().getAsJsonObject("data").getAsJsonObject("body_delta")
                .getAsJsonObject("net").getAsJsonArray("health").get(1).getAsInt());
        assertEquals(com.neko.mcbot.body.BodyControl.Owner.IDLE, control.owner());
    }

    @Test void nativeExceptionRetainsObservedBodyChangesAndNeverSuggestsRetry() {
        begin(sender, 1, false);
        nextResult = new CompletableFuture<>();
        call(1, "attack", "{}");
        facts.hp = 18;
        nextResult.completeExceptionally(new IllegalStateException("native callback"));
        var event = receipts.getLast();
        assertEquals("failed", event.str("phase"));
        assertTrue(event.str("text").startsWith("INTERNAL:"));
        assertEquals(18, event.body().getAsJsonObject("data").getAsJsonObject("body_delta")
                .getAsJsonObject("net").getAsJsonArray("health").get(1).getAsInt());
        assertEquals(List.of("attack"), effects);
    }

    @Test void unavailableTerminalObservationKeepsOriginalPartialEffectAndMarksUnknown() {
        begin(sender, 1, false);
        nextResult = new CompletableFuture<>();
        call(1, "break_block", "{}");
        facts.unavailable = true;
        nextResult.complete(new ServerTool.Result(false, "PARTIAL:removed", json("{\"removed\":true}")));
        var data = receipts.getLast().body().getAsJsonObject("data");
        assertTrue(data.get("removed").getAsBoolean());
        assertFalse(data.getAsJsonObject("body_delta").get("observation_known").getAsBoolean());
    }

    @Test void wireOverflowKeepsBoundedEffectsAndNeverLabelsTruncatedJobSuccessful() {
        begin(sender, 1, false);
        nextResult = new CompletableFuture<>();
        var ack = call(1, "break_block", "{}");
        facts.hp = 19;
        nextResult.complete(new ServerTool.Result(true, "huge".repeat(12000), json("{\"removed\":true}")));
        var event = receipts.getLast();
        assertEquals("job_event", event.kind());
        assertEquals(ack.str("job_id"), event.str("job_id"));
        assertEquals("failed", event.str("phase"));
        assertTrue(event.str("text").contains("不要重投"));
        var data = event.body().getAsJsonObject("data");
        assertTrue(data.get("removed").getAsBoolean());
        assertTrue(data.get("receipt_truncated").getAsBoolean());
        assertTrue(data.has("body_delta"));
        assertTrue(com.neko.mcbot.common.WireSize.fits(event.encode()));
    }

    @Test void synchronousMutationExceptionStillReportsFactsAndReleasesOriginalControl() {
        begin(sender, 1, false);
        onRun = () -> { facts.hp = 18; throw new IllegalStateException("controlled sync hook"); };
        var result = call(1, "equip", "{}");
        assertEquals("tool_result", result.kind());
        assertTrue(result.str("feedback").startsWith("INTERNAL:"));
        assertEquals(18, result.body().getAsJsonObject("data").getAsJsonObject("body_delta")
                .getAsJsonObject("net").getAsJsonArray("health").get(1).getAsInt());
        assertEquals(com.neko.mcbot.body.BodyControl.Owner.IDLE, control.owner());
        onRun = () -> {};
        assertTrue(call(1, "equip", "{}").bool("ok"));
    }

    @Test void refusedWorkAndQueriesNeverOpenAnActionObservation() {
        facts.unavailable = true;
        begin(sender, 1, true);
        var refused = call(1, "equip", "{}");
        assertTrue(refused.str("feedback").startsWith("DENIED:"));
        assertFalse(refused.body().getAsJsonObject("data").has("body_delta"));
        assertTrue(call(1, "status", "{}").bool("ok"));
        assertEquals(List.of("status"), effects);
    }

    @Test void oversizedCancellationRetainsCancellationPhaseAndPartialEffects() {
        begin(sender, 1, false);
        nextResult = new CompletableFuture<>();
        call(1, "break_block", "{}");
        nextResult.complete(new ServerTool.Result(false, "CANCELLED:" + "large".repeat(10000),
                json("{\"removed\":true}")));
        var result = receipts.getLast();
        assertEquals("cancelled", result.str("phase"));
        assertTrue(result.str("text").startsWith("CANCELLED:"));
        assertTrue(result.body().getAsJsonObject("data").get("removed").getAsBoolean());
        assertTrue(com.neko.mcbot.common.WireSize.fits(result.encode()));
    }

    @Test void oversizedNativeDropReceiptKeepsBoundedOriginCountersAndExplicitlyOmitsYieldSamples() {
        begin(sender, 1, false);
        nextResult = new CompletableFuture<>();
        call(1, "break_block", "{}");
        var data = json("{\"removed\":true,\"drop_accounting_known\":true,\"drop_spawns_complete\":true,"
                + "\"drop_accounting\":{\"origin_id\":7,\"spawned_count\":4,\"picked_up_other_count\":2,"
                + "\"unresolved_count\":2,\"accounting_complete\":false,\"spawns\":[\""
                + "x".repeat(40000) + "\"]}}");
        nextResult.complete(new ServerTool.Result(false, "CANCELLED:owner", data));
        var result = receipts.getLast();
        assertEquals("cancelled", result.str("phase"));
        var kept = result.body().getAsJsonObject("data");
        assertTrue(kept.get("receipt_truncated").getAsBoolean());
        assertTrue(kept.get("removed").getAsBoolean());
        assertTrue(kept.get("drop_accounting_known").getAsBoolean());
        assertFalse(kept.get("drop_spawns_complete").getAsBoolean());
        var origin = kept.getAsJsonObject("drop_accounting");
        assertEquals(7, origin.get("origin_id").getAsInt());
        assertEquals(2, origin.get("picked_up_other_count").getAsInt());
        assertEquals(2, origin.get("unresolved_count").getAsInt());
        assertFalse(origin.has("spawns"));
        assertTrue(origin.get("details_omitted").getAsBoolean());
        assertTrue(com.neko.mcbot.common.WireSize.fits(result.encode()));
        assertEquals(List.of("break_block"), effects);
    }

    @Test void companionIdentityIsScopedToTheRequestingOwnerAndClearedWhenAbsent() {
        send(sender, "companion_status", new JsonObject());
        assertEquals("companion_state", receipts.getLast().kind());
        assertEquals(companion.toString(), receipts.getLast().str("companion_uuid"));
        assertEquals("sample", receipts.getLast().str("companion"));
        var stranger = new ServerToolDispatcher.Sender(UUID.randomUUID(), null,
                text -> receipts.add(Envelope.decode(text)));
        send(stranger, "companion_status", new JsonObject());
        assertEquals("", receipts.getLast().str("companion_uuid"));
        assertEquals("", receipts.getLast().str("companion"));
        bodies.remove(owner);
        send(sender, "companion_status", new JsonObject());
        assertEquals("", receipts.getLast().str("companion_uuid"));
    }

    private void send(ServerToolDispatcher.Sender caller, String kind, JsonObject body) {
        dispatcher.handle(caller, Envelope.decode(new Envelope(kind, body).encode()));
    }

    private void begin(ServerToolDispatcher.Sender caller, long id, boolean readOnly) {
        var scope = new JsonObject();
        scope.addProperty("task_id", id);
        scope.addProperty("read_only", readOnly);
        send(caller, "task_begin", scope);
    }

    private Envelope call(ServerToolDispatcher.Sender caller, long task, String tool, JsonObject args) {
        var request = new JsonObject();
        request.addProperty("seq", 7);
        request.addProperty("task_id", task);
        request.addProperty("tool", tool);
        request.addProperty("accept", true);
        request.add("args", args);
        send(caller, "tool_call", request);
        return receipts.getLast();
    }

    private Envelope call(long task, String tool, String args) {
        return call(sender, task, tool, json(args));
    }

    private JsonObject denied(Envelope receipt, String reason) {
        assertEquals("tool_result", receipt.kind());
        assertFalse(receipt.bool("ok"));
        assertEquals(7, receipt.num("seq", 0));
        assertTrue(receipt.str("feedback").startsWith("DENIED:当前任务权限"));
        JsonObject detail = receipt.obj("data").getAsJsonObject("permission_denial");
        assertEquals("server_action", detail.get("gate").getAsString());
        assertEquals(reason, detail.get("reason").getAsString());
        return detail;
    }

    @Test void autonomyComesFromOwnerTaskControlAndNotToolArguments() {
        begin(sender, 60, false);
        assertTrue(call(60, "move_to", "{\"x\":2,\"y\":90,\"z\":0,\"autonomous\":true}").bool("ok"));
        var change = new ActionPermissions.Change("dig", 123, "stone");
        assertFalse(receivedContext.autonomous());
        assertFalse(receivedPermissions.allowsChange(receivedContext, new ActionPermissions.Execution(null), change));
        send(sender, "task_begin", json("{\"task_id\":61,\"read_only\":false,\"autonomous\":true}"));
        assertTrue(call(61, "move_to", "{\"x\":2,\"y\":90,\"z\":0}").bool("ok"));
        assertTrue(receivedContext.autonomous());
        assertTrue(receivedPermissions.allowsChange(receivedContext, new ActionPermissions.Execution(null), change));
        var previous = receivedContext;
        send(sender, "task_end", json("{\"task_id\":61}"));
        assertFalse(receivedPermissions.allowsChange(previous, null, change));
    }

    @Test void readOnlyTaskAlwaysOverridesAutonomyAndOtherOwnerCannotUseIt() {
        send(sender, "task_begin", json("{\"task_id\":62,\"read_only\":true,\"autonomous\":true}"));
        denied(call(62, "break_block", "{\"x\":2,\"y\":90,\"z\":0,\"read_only\":false}"), "READ_ONLY");
        assertTrue(effects.isEmpty());
        assertTrue(call(62, "status", "{}").bool("ok"));
        assertEquals("read_only", receivedContext.mode());
        assertFalse(receivedContext.autonomous());
        var stranger = new ServerToolDispatcher.Sender(UUID.randomUUID(), null,
                text -> receipts.add(Envelope.decode(text)));
        send(stranger, "task_begin", json("{\"task_id\":62,\"read_only\":false,\"autonomous\":true}"));
        assertFalse(call(stranger, 62, "wait", json("{\"seconds\":1}")).bool("ok"));
        assertEquals(List.of("status"), effects);
    }

    @Test void malformedAutonomyIsRejectedWithoutChangingAnExistingTask() {
        for (String value : List.of("\"true\"", "1", "null", "{}", "[]")) {
            send(sender, "task_begin", json("{\"task_id\":63,\"read_only\":false,\"autonomous\":" + value + "}"));
            denied(call(63, "wait", "{\"seconds\":1}"), "TASK_REQUIRED");
        }
        begin(sender, 64, true);
        send(sender, "task_begin", json("{\"task_id\":64,\"read_only\":false,\"autonomous\":\"true\"}"));
        denied(call(64, "wait", "{\"seconds\":1}"), "READ_ONLY");
        assertTrue(effects.isEmpty());
    }

    @Test void allMutatorsReachReadOnlyGateWithoutCallingEffectsOrAcknowledgingJobs() {
        begin(sender, 41, true);
        for (String tool : List.of("equip", "craft", "smelt", "move_to", "break_block",
                "place_block", "collect", "transfer", "wait", "attack")) {
            JsonObject detail = denied(call(41, tool,
                    "{\"x\":2,\"y\":90,\"z\":0,\"action\":\"load\",\"query\":false,\"seconds\":1,"
                            + "\"read_only\":false,\"may_alter_terrain\":true,\"authorization_id\":\"invented\"}"), "READ_ONLY");
            assertEquals(41, detail.get("task_id").getAsLong());
            assertEquals(tool, detail.get("tool").getAsString());
            assertEquals("world", detail.get("dimension").getAsString());
        }
        assertTrue(effects.isEmpty());
        assertEquals(10, receipts.size());
        assertFalse(scheduler.busy(companion));
        assertTrue(scheduler.execute(companion, List.of(), () -> CompletableFuture.completedFuture(
                new ServerTool.Result(true, "free", null))).join().ok());
    }

    @Test void observationsAndQueryModesStillRunInReadOnlyTask() {
        begin(sender, 42, true);
        for (String name : List.of("status", "inventory", "scan_area", "find_resource", "inspect_block")) {
            assertTrue(call(42, name, "{}").bool("ok"));
        }
        assertTrue(call(42, "craft", "{\"query\":true}").bool("ok"));
        assertTrue(call(42, "smelt", "{\"action\":\"query\"}").bool("ok"));
        assertTrue(call(42, "smelt", "{}").bool("ok"));
        assertEquals(8, effects.size());
        denied(call(42, "craft", "{\"query\":\"true\"}"), "READ_ONLY");
        denied(call(42, "smelt", "{\"action\":\"load\"}"), "READ_ONLY");
        assertEquals(8, effects.size());
    }

    @Test void whitelistAndObjectSchemaRejectBeforePermissionGate() {
        begin(sender, 43, true);
        Envelope unknown = call(43, "not_registered", "{}");
        assertTrue(unknown.str("feedback").startsWith("DENIED:未知工具"));
        assertFalse(unknown.obj("data").has("permission_denial"));
        var request = json("{\"seq\":7,\"task_id\":43,\"tool\":\"wait\",\"args\":[]}");
        send(sender, "tool_call", request);
        assertTrue(receipts.getLast().str("feedback").contains("args 必须是 JSON 对象"));
        assertFalse(receipts.getLast().obj("data").has("permission_denial"));
        assertTrue(effects.isEmpty());
    }

    @Test void senderWithoutOwnedCompanionCannotSelectAnotherBodyThroughArguments() {
        begin(sender, 44, true);
        var stranger = new ServerToolDispatcher.Sender(UUID.randomUUID(), null,
                text -> receipts.add(Envelope.decode(text)));
        JsonObject args = json("{\"seconds\":1}");
        args.addProperty("owner_uuid", owner.toString());
        args.addProperty("companion_uuid", companion.toString());
        Envelope result = call(stranger, 44, "wait", args);
        assertTrue(result.str("feedback").contains("没有在册的同伴"));
        assertFalse(result.obj("data").has("permission_denial"));
        assertTrue(effects.isEmpty());
    }

    @Test void missingStaleOrMalformedTaskIdsNeverCountAsReadOnlyEvidence() {
        begin(sender, 45, true);
        denied(call(46, "wait", "{\"seconds\":1}"), "TASK_REQUIRED");
        for (String value : List.of("\"45\"", "true", "null", "45.5", "9223372036854775808")) {
            send(sender, "tool_call", json("{\"seq\":7,\"task_id\":" + value
                    + ",\"tool\":\"wait\",\"args\":{\"seconds\":1}}"));
            JsonObject detail = denied(receipts.getLast(), "TASK_REQUIRED");
            assertFalse(detail.has("task_id"));
        }
        send(sender, "tool_call", json("{\"seq\":7,\"tool\":\"wait\",\"args\":{\"seconds\":1}}"));
        denied(receipts.getLast(), "TASK_REQUIRED");
        denied(call(45, "wait", "{\"seconds\":1}"), "READ_ONLY");
        assertTrue(effects.isEmpty());
    }

    @Test void replacementBodyAndChangedDimensionInvalidateWritableTaskIdentity() {
        ServerToolDispatcher.Body original = bodies.get(owner);
        begin(sender, 47, false);
        bodies.put(owner, body("world", new Object()));
        denied(call(47, "wait", "{\"seconds\":1}"), "TASK_REQUIRED");
        bodies.put(owner, body("nether", original.identity()));
        denied(call(47, "wait", "{\"seconds\":1}"), "TASK_REQUIRED");
        bodies.put(owner, original);
        assertTrue(call(47, "wait", "{\"seconds\":1}").bool("ok"));
        assertEquals(List.of("wait"), effects);
    }

    @Test void malformedTaskBeginCannotEstablishWritablePermissions() {
        for (String mode : List.of("\"false\"", "0", "null")) {
            send(sender, "task_begin", json("{\"task_id\":48,\"read_only\":" + mode + "}"));
            denied(call(48, "wait", "{\"seconds\":1}"), "TASK_REQUIRED");
        }
        begin(sender, 48, true);
        send(sender, "task_begin", json("{\"task_id\":48,\"read_only\":\"false\"}"));
        denied(call(48, "wait", "{\"seconds\":1}"), "READ_ONLY");
        assertTrue(effects.isEmpty());
    }

    @Test void taskEndOnlyRevokesItsOwnIdAndCancelledContextCannotMutate() {
        begin(sender, 49, true);
        send(sender, "task_end", json("{\"task_id\":50}"));
        denied(call(49, "wait", "{\"seconds\":1}"), "READ_ONLY");
        send(sender, "task_end", json("{\"task_id\":49}"));
        denied(call(49, "wait", "{\"seconds\":1}"), "TASK_REQUIRED");
        begin(sender, 51, false);
        send(sender, "cancel", new JsonObject());
        denied(call(51, "wait", "{\"seconds\":1}"), "TASK_REQUIRED");
        assertTrue(effects.isEmpty());
    }

    @Test void rateLimitPrecedesPermissionGateAndRefillsPerSender() {
        begin(sender, 52, true);
        for (int i = 0; i < 59; i++) denied(call(52, "wait", "{\"seconds\":1}"), "READ_ONLY");
        Envelope limited = call(52, "wait", "{\"seconds\":1}");
        assertTrue(limited.str("feedback").contains("消息过于频繁"));
        assertFalse(limited.obj("data").has("permission_denial"));
        var secondOwner = UUID.randomUUID();
        bodies.put(secondOwner, new ServerToolDispatcher.Body(UUID.randomUUID(), "world", new Object(),
                3, 90, 0, "second"));
        var second = new ServerToolDispatcher.Sender(secondOwner, null,
                text -> receipts.add(Envelope.decode(text)));
        begin(second, 53, true);
        denied(call(second, 53, "wait", json("{\"seconds\":1}")), "READ_ONLY");
        clock.addAndGet(50_000_000L);
        denied(call(52, "wait", "{\"seconds\":1}"), "READ_ONLY");
        assertTrue(effects.isEmpty());
    }

    @Test void explicitVerificationUsesActualGateAndLeavesNextTaskUsable() {
        var result = dispatcher.verifyReadOnly(sender);
        assertEquals("PASS", result.status());
        assertEquals(Long.MAX_VALUE, result.taskId());
        assertEquals("wait", result.tool());
        assertTrue(result.feedback().startsWith("DENIED:当前任务权限"));
        assertTrue(effects.isEmpty());
        assertTrue(receipts.isEmpty(), "command diagnostics are not unsolicited client tool receipts");
        begin(sender, 54, false);
        assertTrue(call(54, "wait", "{\"seconds\":1}").bool("ok"));
        assertEquals(List.of("wait"), effects);
    }

    @Test void verificationDoesNotReplaceAnActiveTaskEvenWhenBodyHasNoTickTask() {
        begin(sender, 55, true);
        assertEquals("BLOCKED", dispatcher.verifyReadOnly(sender).status());
        denied(call(55, "wait", "{\"seconds\":1}"), "READ_ONLY");
        assertTrue(effects.isEmpty());
    }

    @Test void verificationRequiresACompanionOwnedByItsCaller() {
        bodies.remove(owner);
        assertEquals("BLOCKED", dispatcher.verifyReadOnly(sender).status());
        assertTrue(effects.isEmpty());
        assertTrue(receipts.isEmpty());
    }

    @Test void rateLimitedProbeActionIsFailureAndCleanupSurvivesRateLimitedTaskEnd() {
        for (int i = 0; i < 59; i++) assertTrue(call(0, "status", "{}").bool("ok"));
        effects.clear();
        var result = dispatcher.verifyReadOnly(sender);
        assertEquals("FAIL", result.status());
        assertTrue(result.feedback().contains("消息过于频繁"));
        assertTrue(effects.isEmpty());
        clock.addAndGet(1_000_000_000L);
        assertEquals("PASS", dispatcher.verifyReadOnly(sender).status(), "old diagnostic context must be gone");
        begin(sender, 56, false);
        assertTrue(call(56, "wait", "{\"seconds\":1}").bool("ok"));
    }

    @Test void rateLimitedTaskBeginCannotBeMistakenForSuccessfulVerification() {
        for (int i = 0; i < 60; i++) call(0, "status", "{}");
        effects.clear();
        assertEquals("BLOCKED", dispatcher.verifyReadOnly(sender).status());
        clock.addAndGet(1_000_000_000L);
        assertEquals("PASS", dispatcher.verifyReadOnly(sender).status());
        assertTrue(effects.isEmpty());
    }

    @Test void missingProbeToolFailsWhitelistRatherThanInventingPermissionEvidence() {
        var empty = new ServerToolDispatcher(new ToolRegistry(), scheduler, new RateGuard(20, 60, clock::get),
                new ServerToolDispatcher.ToolAccess() {
                    @Override public ServerToolDispatcher.Body companion(UUID caller) { return bodies.get(caller); }
                    @Override public CompletableFuture<ServerTool.Result> run(ServerTool tool,
                            ServerToolDispatcher.Body body, JsonObject args, ActionPermissions permissions,
                            ActionPermissions.Context context) {
                        fail("unknown tool must not execute");
                        return null;
                    }
                });
        var result = empty.verifyReadOnly(sender);
        assertEquals("FAIL", result.status());
        assertTrue(result.feedback().contains("未知工具"));
    }

    @Test void normalAcceptedActionStillProducesExactlyOneAckAndOneTerminalEvent() {
        begin(sender, 57, false);
        nextResult = new CompletableFuture<>();
        Envelope ack = call(57, "break_block", "{\"x\":2,\"y\":90,\"z\":0}");
        assertEquals("job_ack", ack.kind());
        assertEquals(1, receipts.size());
        String job = ack.str("job_id");
        nextResult.complete(new ServerTool.Result(true, "finished", null));
        assertEquals(2, receipts.size());
        assertEquals("job_event", receipts.getLast().kind());
        assertEquals(job, receipts.getLast().str("job_id"));
        assertEquals("done", receipts.getLast().str("phase"));
        assertEquals(List.of("break_block"), effects);
    }

    @Test void replacingTaskCancelsTheOldActionAndPreservesNewReadOnlyScope() {
        begin(sender, 58, false);
        nextResult = new CompletableFuture<>();
        call(58, "break_block", "{\"x\":2,\"y\":90,\"z\":0}");
        begin(sender, 59, true);
        assertTrue(nextResult.isDone());
        assertEquals("cancelled", receipts.getLast().str("phase"));
        denied(call(59, "wait", "{\"seconds\":1}"), "READ_ONLY");
        assertEquals(List.of("break_block"), effects);
    }
}
