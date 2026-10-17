package com.neko.mcbot.server;

import com.google.gson.JsonObject;
import com.neko.mcbot.body.CompanionPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ClientboundBlockDestructionPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

/** One timed block operation, shared by explicit mining and path clearing. */
public final class BlockMining {
    public interface Access {
        ServerTool.Result guard(double reach);
        BlockState state();
        default boolean environmentSafe() { return true; }
        default String expectedBlock() { return null; }
        float hardness(BlockState state);
        boolean correctTool(BlockState state);
        boolean mayDestroy(BlockState state);
        ServerTool.Result sight(double reach);
        ServerTool.Result faceTarget();
        void swing();
        float progress(BlockState state);
        int selectedSlot();
        ItemStack held();
        default HandSnapshot.Pickups handPickups() { return null; }
        default JsonObject dropReport() { return null; }
        boolean destroy();
        default ServerTool.Result beforeDestroy() { return null; }
        default boolean recordingKnown() { return true; }
        default JsonObject effects() { return null; }
        void crack(int stage);
    }

    private final Access access;
    private BlockState target;
    private HandSnapshot tool;
    private float progress;
    private int stage = -1;
    private int stalled;
    private ServerTool.Result terminal;
    private boolean destructionStarted;

    public BlockMining(Access access) {
        this.access = access;
    }

    public JsonObject observation() {
        var data = new JsonObject();
        data.addProperty("action", "break_block");
        data.addProperty("mining_progress", Float.isFinite(progress) ? Math.min(1, progress) : 1);
        data.addProperty("stalled_ticks", stalled);
        if (terminal != null && terminal.data() != null) data.add("result", terminal.data().deepCopy());
        return data;
    }

    public static BlockMining forPlayer(CompanionPlayer player, BlockPos pos) {
        return forPlayer(player, pos, null);
    }

    public static BlockMining forPlayer(CompanionPlayer player, BlockPos pos, String expectedBlock) {
        return forPlayer(player, pos, expectedBlock, "target");
    }

    public static BlockMining forPlayer(CompanionPlayer player, BlockPos pos, String expectedBlock, String role) {
        return forPlayer(player, pos, expectedBlock, role, null, false);
    }

    public static BlockMining forPlayer(CompanionPlayer player, BlockPos pos, String expectedBlock, String role,
                                       String siteRevision, boolean cleanup) {
        return new BlockMining(new PlayerAccess(player, player.level(), pos.immutable(), expectedBlock, role, siteRevision, cleanup));
    }

    public static boolean matchesExpected(BlockState state, String expectedBlock) {
        return expectedBlock == null || expectedBlock.equals(
                net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString());
    }

    /** Freeze the target and hand identity; native pickup growth and live mining speed remain valid. */
    public ServerTool.Result preflight(double reach) {
        ServerTool.Result failure = access.guard(reach);
        if (failure != null) return failure;
        BlockState current = access.state();
        if (!matchesExpected(current, access.expectedBlock()))
            return failure("TARGET_LOST:目标不再是观测到的方块，未挖替换物；重新查看后再决定。");
        if (current.isAir()) return failure("TARGET_LOST:那里已经是空气，重新查看目标。");
        if (!current.getFluidState().isEmpty()) return failure("DENIED:不挖液体或含水方块，先选择干燥目标。");
        if (!access.environmentSafe()) return failure(
                "ENVIRONMENT_UNSAFE:挖掘会暴露流体、重力方块或未知邻格，未继续破坏；先观察并选择安全工作面。");
        if (target != null && current != target) return failure("TARGET_LOST:目标方块已改变，重新查看后再决定。");
        if (access.hardness(current) < 0) return failure("UNBREAKABLE:该方块不能用生存手段破坏，换目标。");
        if (!access.mayDestroy(current)) return failure("DENIED:当前玩家或物品不能破坏这格，换目标或工具。");
        if (!access.correctTool(current)) {
            return failure("WRONG_TOOL:当前主手没有采收资格；先 inventory/equip 换合适工具，目标未破坏。");
        }
        failure = access.sight(reach);
        if (failure != null) return failure;
        if (target == null) {
            target = current;
            tool = new HandSnapshot(access.selectedSlot(), access.held(), access.handPickups());
        } else if (!tool.matches(access.selectedSlot(), access.held())) {
            return failure("WRONG_TOOL:挖掘期间主手已改变，已停手；重新选择工具后再调用。");
        }
        return null;
    }

    /** Null means still running. Terminal results are stable and never repeat world effects. */
    public ServerTool.Result tick() {
        if (terminal != null) return terminal;
        if (destructionStarted) {
            var data = new JsonObject();
            data.addProperty("removal_known", false);
            data.addProperty("recording_known", access.recordingKnown());
            addDropReport(data);
            return finish(new ServerTool.Result(false,
                    "TOOL_EXCEPTION:原破坏调用已发出，结果不完整，未重试；先核对现场。", data));
        }
        ServerTool.Result failure = preflight(6.5);
        if (failure != null) return finish(failure);
        float gain = access.progress(target);
        // Zero-hardness blocks yield +infinity in vanilla and complete in this tick.
        if (Float.isNaN(gain) || gain <= 0) {
            if (++stalled >= 10) return finish(failure("WRONG_TOOL:当前挖掘速度无效，已停手；换工具或检查状态。"));
            return null;
        }
        stalled = 0;
        failure = access.faceTarget();
        if (failure != null) return finish(failure);
        access.swing();
        progress += gain;
        int nextStage = Math.min(9, (int) (progress * 10));
        if (nextStage != stage) {
            stage = nextStage;
            access.crack(stage);
        }
        if (progress < 1) return null;
        clearCrack();

        failure = access.beforeDestroy();
        if (failure != null) return finish(failure);
        destructionStarted = true;
        boolean reported = access.destroy();
        // ServerPlayerGameMode.destroyBlock can return true even if removeBlock returned false.
        boolean removed = !access.state().is(target.getBlock());
        JsonObject data = new JsonObject();
        data.addProperty("removed", removed);
        data.addProperty("reported_success", reported);
        data.addProperty("recording_known",access.recordingKnown());
        addDropReport(data);
        if (!removed) return finish(new ServerTool.Result(false,
                "BREAK_FAILED:目标没有移除；原版可能已处理耐久或产生连带效果，先核对状态，勿自动重试。", data));
        String feedback = "已移除目标方块，挖掘不直接入包；移除时可归源地上掉落 " + data.get("remaining_count")
                + " 个，明细（最多16组）：" + data.get("leftover") + "；目标来源掉落位置：" + data.get("drops")
                + "；原生来源观察可用=" + data.get("drop_accounting_known")
                + "。走近后身体自动拾取，遵守原版拾取延迟；实际入包量须重新查看背包。"
                + "主手耐久由原版破坏流程处理。";
        if (!reported) feedback = "BREAK_FAILED:原版未报告成功，但已观察到目标移除。" + feedback + "先核对现场，勿自动重试。";
        return finish(new ServerTool.Result(reported, feedback, data));
    }

    private void addDropReport(JsonObject data) {
        DropTracker.addReport(data, access.dropReport());
    }

    /** A fresh observation only; the terminal action and its world effects remain stable. */
    public ServerTool.Result refreshDrops(ServerTool.Result result) {
        if (!destructionStarted) return result;
        var data = result.data() == null ? new JsonObject() : result.data().deepCopy();
        addDropReport(data);
        addEffects(data);
        var observations = new DropObservations();
        observations.record(data);
        // Counters at removal are historical; the appended observation is the terminal state.
        var snapshot = observations.snapshotSources(ignored -> data.getAsJsonObject("drop_accounting"));
        return new ServerTool.Result(result.ok(), result.feedback() + DropObservations.sentence(snapshot), data);
    }

    public void abort() {
        clearCrack();
        if (terminal == null) terminal = failure("CANCELLED:挖掘已中止，等待新指令。");
    }

    public void suspend() {
        clearCrack();
        // Native mining progress is tied to uninterrupted contact, not a reusable resource.
        progress = 0;
    }

    private ServerTool.Result finish(ServerTool.Result result) {
        clearCrack();
        terminal = result.data() == null ? failure(result.feedback()) : result;
        if (destructionStarted) {
            var data = terminal.data().deepCopy();
            addEffects(data);
            terminal = new ServerTool.Result(terminal.ok(),terminal.feedback(),data);
        }
        return terminal;
    }
    private void addEffects(JsonObject data) {
        if (!destructionStarted) return;
        data.addProperty("recording_known",access.recordingKnown());
        var effects=access.effects();
        if (effects!=null) {
            data.add("native_effects",effects.deepCopy());
            data.addProperty("changed",effects.get("changed").getAsBoolean());
        }
    }

    private void clearCrack() {
        if (stage >= 0) {
            access.crack(-1);
            stage = -1;
        }
    }

    private static ServerTool.Result failure(String feedback) {
        JsonObject data = new JsonObject();
        data.addProperty("removed", false);
        data.addProperty("collected_count", 0);
        data.addProperty("remaining_count", 0);
        return new ServerTool.Result(false, feedback, data);
    }

    private static final class PlayerAccess implements Access {
        private final CompanionPlayer player;
        private final ServerLevel level;
        private final BlockPos pos;
        private final String expectedBlock;
        private final DropTracker.Request dropRequest;
        private final String siteRevision;
        private final boolean cleanup;
        private BlockAim.Aim aim;
        private JsonObject effects;

        private PlayerAccess(CompanionPlayer player, ServerLevel level, BlockPos pos, String expectedBlock, String role,
                             String siteRevision, boolean cleanup) {
            this.player = player;
            this.level = level;
            this.pos = pos;
            this.expectedBlock = expectedBlock;
            this.dropRequest = DropTracker.request(player, pos, role);
            this.siteRevision = siteRevision;
            this.cleanup = cleanup;
        }

        @Override public ServerTool.Result guard(double reach) {
            if (player.level() != level) return failure("TARGET_LOST:身体已换维度，原目标作废。");
            var environment = MiningEnvironment.bodyFailure(player);
            if (environment != null) return environment;
            if (!level.isInWorldBounds(pos) || !level.getWorldBorder().isWithinBounds(pos)) {
                return failure("DENIED:目标超出世界范围，换位置。");
            }
            if (!level.isLoaded(pos)) return failure("TARGET_LOST:目标未加载，先靠近。");
            var recordFailure=BuildRecordAccess.guard(player,pos,true,siteRevision);
            if (recordFailure!=null) return recordFailure;
            var siteFailure = player.buildSite().miningGuard(pos, siteRevision, cleanup, new BuildSiteAccess(player));
            if (siteFailure != null) return siteFailure;
            if (com.neko.mcbot.building.BuildObjects.door(state())) {
                var other=com.neko.mcbot.building.BuildObjects.other(pos,state());
                if (!level.isLoaded(other) || !level.isInWorldBounds(other)
                        || !level.getWorldBorder().isWithinBounds(other) || !level.mayInteract(player,other))
                    return failure("DENIED:门另一半未知或不允许交互，未破坏任一半。");
                var otherFailure=BuildRecordAccess.guard(player,other,true,siteRevision);
                if (otherFailure!=null) return otherFailure;
                var otherState=level.getBlockState(other);
                if (!otherState.isAir()) {
                    if (!MiningEnvironment.safe(MiningEnvironment.world(level),other))
                        return failure("ENVIRONMENT_UNSAFE:门另一半邻域有流体、重力方块或未知，未破坏任一半。");
                    otherFailure=player.buildSite().miningGuard(other,siteRevision,cleanup,new BuildSiteAccess(player));
                    if (otherFailure!=null) return otherFailure;
                }
                if (!otherState.isAir() && (!com.neko.mcbot.building.BuildObjects.door(otherState)
                        || otherState.getBlock()!=state().getBlock()
                        || com.neko.mcbot.building.BuildObjects.upper(otherState)
                        ==com.neko.mcbot.building.BuildObjects.upper(state())
                        || otherState.getDestroySpeed(level,other)<0 || !player.hasCorrectToolForDrops(otherState)
                        || player.blockActionRestricted(level,other,player.gameMode.getGameModeForPlayer())
                        || !player.getMainHandItem().canDestroyBlock(otherState,level,other,player)))
                    return failure("DENIED:门另一半已变更或工具不能处理，未破坏。");
            }
            for (BlockPos near : BlockPos.betweenClosed(pos.offset(-1, -1, -1), pos.offset(1, 1, 1))) {
                if (!level.isInWorldBounds(near) || !level.isLoaded(near)) {
                    return failure("TARGET_LOST:挖掘邻域未加载或超出世界范围，换目标或先靠近。");
                }
            }
            // BlockAim owns reach from eyes to the visible hit, as it does for work arrival.
            if (!player.gameMode.isSurvival() || !level.mayInteract(player, pos)) {
                return failure("DENIED:当前生存模式或世界交互规则不允许破坏。");
            }
            return null;
        }
        @Override public BlockState state() { return level.getBlockState(pos); }
        @Override public boolean environmentSafe() {
            return MiningEnvironment.safe(MiningEnvironment.world(level), pos);
        }
        @Override public String expectedBlock() { return expectedBlock; }
        @Override public float hardness(BlockState state) { return state.getDestroySpeed(level, pos); }
        @Override public boolean correctTool(BlockState state) { return player.hasCorrectToolForDrops(state); }
        @Override public boolean mayDestroy(BlockState state) {
            return !player.blockActionRestricted(level, pos, player.gameMode.getGameModeForPlayer())
                    && player.getMainHandItem().canDestroyBlock(state, level, pos, player);
        }
        @Override public float progress(BlockState state) { return state.getDestroyProgress(player, level, pos); }
        @Override public ServerTool.Result sight(double reach) {
            aim = BlockAim.mining(BlockAim.forPlayer(player), pos, Math.min(reach, player.blockInteractionRange()));
            return aim.ok() ? null : failure(aim.failure());
        }
        @Override public ServerTool.Result faceTarget() {
            player.facePoint(aim.point());
            var eye = player.getEyePosition();
            var actualPoint = eye.add(player.getLookAngle().scale(eye.distanceTo(aim.point())));
            var actual = BlockAim.miningRay(BlockAim.forPlayer(player), pos, actualPoint,
                    Math.min(6.5, player.blockInteractionRange()));
            return actual.ok() ? null : failure(actual.failure());
        }
        @Override public void swing() { player.swing(InteractionHand.MAIN_HAND); }
        @Override public int selectedSlot() { return player.getInventory().getSelectedSlot(); }
        @Override public ItemStack held() { return player.getMainHandItem(); }
        @Override public HandSnapshot.Pickups handPickups() { return player.handPickups(); }
        @Override public boolean destroy() {
            var before=state();
            var footprint=com.neko.mcbot.building.BuildObjects.footprint(pos,before);
            var previous=new java.util.LinkedHashMap<BlockPos,BlockState>();
            footprint.forEach(p -> previous.put(p,level.getBlockState(p)));
            try { return DropTracker.destroy(dropRequest, () -> player.gameMode.destroyBlock(pos)); }
            finally {
                var after=new java.util.LinkedHashMap<BlockPos,BlockState>();
                footprint.forEach(p -> after.put(p,level.getBlockState(p)));
                if (footprint.size()==1) player.buildSite().recordRemoval(pos,before,state());
                else player.buildSite().recordObjectRemoval(previous,after);
                effects=new JsonObject(); var cells=new com.google.gson.JsonArray();
                for (var p:footprint) if (previous.get(p)!=after.get(p)) {
                    var row=com.neko.mcbot.building.BuildTarget.coordinates(p);
                    row.add("before",com.neko.mcbot.building.BuildTarget.exactState(previous.get(p)));
                    row.add("after",com.neko.mcbot.building.BuildTarget.exactState(after.get(p))); cells.add(row);
                }
                effects.add("cells",cells); effects.addProperty("changed",!cells.isEmpty());
                effects.addProperty("scope","native_object_footprint_not_global");
                if (cleanup && state().isAir()) player.buildSite().removedTemporary(pos);
            }
        }
        @Override public JsonObject effects() { return effects; }
        @Override public ServerTool.Result beforeDestroy() {
            var failure=guard(6.5);
            return failure!=null ? failure : player.buildSite().charge();
        }
        @Override public boolean recordingKnown() {
            var records=BuildRecordAccess.store(player);
            return records==null || records.healthy();
        }
        @Override public JsonObject dropReport() { return dropRequest.report(); }
        @Override public void crack(int stage) {
            level.getServer().getPlayerList().broadcastAll(
                    new ClientboundBlockDestructionPacket(player.getId(), pos, stage), level.dimension());
        }
    }
}
