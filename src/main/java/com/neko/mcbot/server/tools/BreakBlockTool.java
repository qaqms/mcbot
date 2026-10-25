package com.neko.mcbot.server.tools;

import com.google.gson.JsonObject;
import com.neko.mcbot.body.CompanionPlayer;
import com.neko.mcbot.server.BlockMining;
import com.neko.mcbot.server.BlockAim;
import com.neko.mcbot.server.MiningWork;
import com.neko.mcbot.server.ClearingMining;
import com.neko.mcbot.server.ActionPermissions;
import com.neko.mcbot.server.HandSnapshot;
import com.neko.mcbot.path.LevelDigSampler;
import com.neko.mcbot.task.ResourceLocks.Region;
import com.neko.mcbot.server.ServerTool;
import com.neko.mcbot.task.CompanionScheduler;
import com.neko.mcbot.task.TickTask;
import net.minecraft.core.BlockPos;

import java.util.concurrent.CompletableFuture;

/** Timed mining; final removal, harvest and durability use the vanilla player entry point. */
public final class BreakBlockTool implements ServerTool {
    public static final int CAP_TICKS = com.neko.mcbot.agentcore.loop.PendingJobs.UNLIMITED_CAP_TICKS;

    @Override public String name() { return "break_block"; }
    @Override public int capTicks(JsonObject args) { return CAP_TICKS; }
    @Override public Acceptance acceptanceMode() { return Acceptance.ACCEPT; }
    @Override public String acceptSubject(JsonObject args) {
        BlockPos pos = readPos(args);
        return pos == null ? "挖方块" : "挖 " + pos.toShortString() + " 的方块";
    }

    @Override
    public CompletableFuture<Result> runAsync(CompanionPlayer c, JsonObject args, CompanionScheduler sched) {
        return runAuthorized(c, args, sched, new ActionPermissions(), null);
    }

    @Override public CompletableFuture<Result> runAuthorized(CompanionPlayer c, JsonObject args,
            CompanionScheduler sched, ActionPermissions permissions, ActionPermissions.Context context) {
        return runMining(c, args, sched, permissions, context).thenApply(result -> withWorkHint(result, args));
    }

    private CompletableFuture<Result> runMining(CompanionPlayer c, JsonObject args,
            CompanionScheduler sched, ActionPermissions permissions, ActionPermissions.Context context) {
        BlockPos pos = readPos(args);
        if (pos == null) return CompletableFuture.completedFuture(
                new Result(false, "DENIED:参数需要数值整数坐标 x/y/z。", null));
        String expectedBlock;
        String siteRevision;
        boolean cleanup;
        try { expectedBlock = expectedBlock(args); }
        catch (RuntimeException invalid) {
            return CompletableFuture.completedFuture(new Result(false,
                    "DENIED:expected_block须为带命名空间的方块ID字符串。", null));
        }
        try {
            siteRevision = args.has("site_revision") ? com.neko.mcbot.building.BuildTarget.string(args.get("site_revision"), 64) : null;
            if (siteRevision != null && !siteRevision.matches("[0-9a-f]{64}")) throw new IllegalArgumentException();
            cleanup = args.has("temporary_cleanup") && strictBoolean(args, "temporary_cleanup");
            validateSiteMining(expectedBlock, siteRevision, cleanup, clearObstructions(args));
        } catch (RuntimeException invalid) {
            return CompletableFuture.completedFuture(new Result(false,
                    "DENIED:工地清理须site_revision/expected_block；temporary_cleanup须有工地修订且不与清遮挡组合。", null));
        }
        boolean clear;
        String id;
        int clearLimit;
        try {
            clear = clearObstructions(args);
            id = authorizationId(args);
            clearLimit = args.has("clear_limit") ? integer(args, "clear_limit") : BlockAim.DEFAULT_CLEAR_LIMIT;
            if (clearLimit < 0) throw new IllegalArgumentException();
        } catch (RuntimeException invalid) {
            return CompletableFuture.completedFuture(new Result(false,
                    "DENIED:clear_obstructions须为布尔，clear_limit为非负整数，authorization_id须为短字符串。", null));
        }
        if (sched.busy(c.getUUID())) return CompletableFuture.completedFuture(
                new Result(false, "BUSY:我正忙着上一件事，等它结束或取消。", null));
        if (clear) {
            var level = c.level();
            var sampler = new LevelDigSampler(level, c, c.blockPosition(), 0);
            var hand = new HandSnapshot[]{new HandSnapshot(c.getInventory().getSelectedSlot(),
                    c.getMainHandItem(), c.handPickups())};
            var sequence = new ClearingMining(new ClearingMining.Access() {
                @Override public BlockAim.MiningPlan plan() {
                    if (c.level() != level || !level.isLoaded(pos)) return new BlockAim.MiningPlan(
                            new BlockAim.Aim(null, "TARGET_LOST:身体换维度或目标未加载。"), java.util.List.of());
                    return MiningWork.plan(c, pos);
                }
                @Override public net.minecraft.world.level.block.state.BlockState state(BlockPos cell) {
                    return c.level() == level && level.isLoaded(cell) ? level.getBlockState(cell)
                            : net.minecraft.world.level.block.Blocks.AIR.defaultBlockState();
                }
                @Override public boolean safe(BlockPos cell) {
                    return MiningWork.safe(c, sampler, c.blockPosition(), cell);
                }
                @Override public BlockMining mining(BlockPos cell) {
                    boolean target = cell.equals(pos);
                    String revision = target ? siteRevision : c.buildSite().navigationRevision(cell,
                            new com.neko.mcbot.server.BuildSiteAccess(c));
                    return BlockMining.forPlayer(c, cell, cell.equals(pos) ? expectedBlock : null,
                            target ? "target" : "clearing", revision, !target && c.buildSite().temporary(cell));
                }
                @Override public boolean reserve(java.util.Map<String, ActionPermissions.Change> changes) {
                    return context != null && sched.reserve(c.getUUID(), changes.values().stream().map(change -> {
                        var cell = BlockPos.of(change.position());
                        return new Region(context.dimension(), cell.getX() - 1, cell.getY() - 1, cell.getZ() - 1,
                                cell.getX() + 1, cell.getY() + 1, cell.getZ() + 1);
                    }).toList());
                }
                @Override public boolean sameHand() {
                    return hand[0].matches(c.getInventory().getSelectedSlot(), c.getMainHandItem());
                }
                @Override public void completed() {
                    sampler.refreshTools();
                    hand[0] = new HandSnapshot(c.getInventory().getSelectedSlot(), c.getMainHandItem(), c.handPickups());
                }
                @Override public void selectedTool() {
                    hand[0] = new HandSnapshot(c.getInventory().getSelectedSlot(), c.getMainHandItem(), c.handPickups());
                }
                @Override public Result targetGuard() {
                    return BlockMining.forPlayer(c, pos, expectedBlock, "target", siteRevision, false).preflight(5.5);
                }
            }, pos, expectedBlock, permissions, context, permissions.take(context, id, pos.asLong()), clearLimit);
            var failure = sequence.prepare();
            if (failure != null) return CompletableFuture.completedFuture(failure);
            var future = new CompletableFuture<Result>();
            if (!sched.submit(c, new ClearingTask(sequence), future, capTicks(args)))
                return CompletableFuture.completedFuture(new Result(false, "BUSY:身体任务槽已占用。", null));
            return future;
        }
        BlockMining mining = BlockMining.forPlayer(c, pos, expectedBlock, cleanup ? "temporary_cleanup" : "target",
                siteRevision, cleanup);
        Result failure = mining.preflight(5.5);
        if (failure != null) return CompletableFuture.completedFuture(failure);
        CompletableFuture<Result> future = new CompletableFuture<>();
        if (!sched.submit(c, new Task(mining), future, capTicks(args))) {
            return CompletableFuture.completedFuture(new Result(false, "BUSY:身体任务槽已占用，先等待或取消。", null));
        }
        return future;
    }

    static void validateSiteMining(String expected, String revision, boolean cleanup, boolean clear) {
        if (revision != null && (!revision.matches("[0-9a-f]{64}") || expected == null)
                || cleanup && (revision == null || clear)) throw new IllegalArgumentException();
    }

    static Result withWorkHint(Result result, JsonObject args) {
        if (result.ok() || !(result.feedback().startsWith("TARGET_BLOCKED:")
                || result.feedback().startsWith("OUT_OF_REACH:"))) return result;
        BlockPos pos = readPos(args);
        if (pos == null) return result;
        var data = result.data() == null ? new JsonObject() : result.data().deepCopy();
        if (data.has("removed") && data.get("removed").getAsBoolean()
                || data.has("cleared_blocks") && data.get("cleared_blocks").getAsInt() > 0) return result;
        var target = new JsonObject();
        target.addProperty("x", pos.getX());
        target.addProperty("y", pos.getY());
        target.addProperty("z", pos.getZ());
        var move = target.deepCopy();
        move.addProperty("exact", true);
        move.addProperty("arrival", "work");
        move.add("work_target", target);
        // Navigation has no smaller terminal clearing allowance; do not widen a stricter request.
        move.addProperty("clear_obstructions", clearObstructions(args)
                && (!args.has("clear_limit") || integer(args, "clear_limit") == BlockAim.DEFAULT_CLEAR_LIMIT));
        data.addProperty("recommended_tool", "move_to");
        data.add("recommended_args", move);
        return new Result(false, result.feedback()
                + " 此动作不移动；普通走近不保证可挖。可先请求工作站位 move_to " + move
                + "，按其真实终态和新鲜观测再决定；建议未执行，不能视为已有可行路线。", data);
    }

    static boolean clearObstructions(JsonObject args) {
        if (!args.has("clear_obstructions")) return false;
        var value = args.get("clear_obstructions");
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) throw new IllegalArgumentException();
        return value.getAsBoolean();
    }

    private static boolean strictBoolean(JsonObject args, String key) {
        var value = args.get(key);
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) throw new IllegalArgumentException();
        return value.getAsBoolean();
    }

    static String authorizationId(JsonObject args) {
        if (!args.has("authorization_id")) return "";
        var value = args.get("authorization_id");
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()
                || value.getAsString().isBlank() || value.getAsString().length() > 64) throw new IllegalArgumentException();
        return value.getAsString();
    }

    static final class ClearingTask extends TickTask {
        private final ClearingMining sequence;
        private Result result;
        private int finishTicks;
        ClearingTask(ClearingMining sequence) { this.sequence = sequence; }
        @Override public Progress tick(CompanionPlayer c) {
            if (result != null) return ++finishTicks < 3 ? running() : new Progress.Done(sequence.refreshDrops(result));
            result = sequence.tick();
            return result == null || result.ok() ? running() : new Progress.Done(result);
        }
        @Override public void onAbort() { sequence.abort(); }
        @Override public void onSuspend() { sequence.suspend(); }
        @Override public Result onResume(CompanionPlayer c) {
            // tick rechecks the live plan, original approval, hand, target and mining preflight.
            return null;
        }
        @Override public Result interruptedResult(Result result) { return sequence.interrupted(result.feedback()); }
        @Override public JsonObject observation() { return sequence.observation(); }
    }

    static final class Task extends TickTask {
        private final BlockMining mining;
        private Result result;
        private int finishTicks;

        Task(BlockMining mining) { this.mining = mining; }

        @Override public Progress tick(CompanionPlayer c) {
            if (result != null) {
                // Preserve the two running ticks between removal and the final receipt.
                return ++finishTicks < 3 ? running() : new Progress.Done(mining.refreshDrops(result));
            }
            result = mining.tick();
            if (result != null && !result.data().get("removed").getAsBoolean()) return new Progress.Done(result);
            return running();
        }

        @Override public void onAbort() { mining.abort(); }
        @Override public void onSuspend() { mining.suspend(); }
        @Override public Result onResume(CompanionPlayer c) {
            return result == null ? mining.preflight(6.5) : null;
        }
        @Override public Result interruptedResult(Result reason) {
            var refreshed = mining.refreshDrops(result == null ? reason : result);
            return new Result(false, reason.feedback() + (result == null ? "" : " 已发生的挖掘结果保留。"),
                    refreshed.data());
        }
        @Override public JsonObject observation() { return mining.observation(); }
    }

    static BlockPos readPos(JsonObject args) {
        try {
            return new BlockPos(integer(args, "x"), integer(args, "y"), integer(args, "z"));
        } catch (RuntimeException invalid) {
            return null;
        }
    }

    static String expectedBlock(JsonObject args) {
        if (!args.has("expected_block")) return null;
        var value = args.get("expected_block");
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()
                || value.getAsString().length() > 128
                || !value.getAsString().matches("[a-z0-9_.-]+:[a-z0-9_./-]+"))
            throw new IllegalArgumentException();
        return value.getAsString();
    }

    private static int integer(JsonObject args, String key) {
        var value = args.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            throw new IllegalArgumentException("integer required");
        }
        return value.getAsBigDecimal().intValueExact();
    }
}
