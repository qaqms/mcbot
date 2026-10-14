package com.neko.mcbot.server;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.neko.mcbot.body.CompanionPlayer;
import com.neko.mcbot.server.tools.InventoryTool;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ClientboundBlockDestructionPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** One timed block operation, shared by explicit mining and path clearing. */
public final class BlockMining {
    public interface Drop {
        Object identity();
        ItemStack stack();
        default JsonObject observation() { return null; }
    }

    public interface Access {
        ServerTool.Result guard(double reach);
        BlockState state();
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
        List<? extends Drop> nearbyDrops();
        boolean destroy();
        void crack(int stage);
    }

    private final Access access;
    private BlockState target;
    private HandSnapshot tool;
    private float progress;
    private int stage = -1;
    private int stalled;
    private ServerTool.Result terminal;

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
        return new BlockMining(new PlayerAccess(player, player.level(), pos.immutable(), expectedBlock));
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
            tool = new HandSnapshot(access.selectedSlot(), access.held());
        } else if (!tool.matches(access.selectedSlot(), access.held())) {
            return failure("WRONG_TOOL:挖掘期间主手已改变，已停手；重新选择工具后再调用。");
        }
        return null;
    }

    /** Null means still running. Terminal results are stable and never repeat world effects. */
    public ServerTool.Result tick() {
        if (terminal != null) return terminal;
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

        Set<Object> oldDrops = new HashSet<>();
        access.nearbyDrops().forEach(drop -> oldDrops.add(drop.identity()));
        boolean reported = access.destroy();
        // ServerPlayerGameMode.destroyBlock can return true even if removeBlock returned false.
        boolean removed = !access.state().is(target.getBlock());
        if (!removed) return finish(failure("BREAK_FAILED:目标没有移除；原版可能已处理工具耐久，先核对状态，勿自动重试。"));

        int remaining = 0;
        JsonArray left = new JsonArray();
        JsonArray drops = new JsonArray();
        for (Drop drop : access.nearbyDrops()) {
            if (!oldDrops.add(drop.identity())) continue;
            ItemStack stack = drop.stack();
            if (stack.isEmpty()) continue;
            remaining += stack.getCount();
            if (left.size() < 32) left.add(InventoryTool.describe(stack));
            JsonObject observation = drop.observation();
            if (observation != null && drops.size() < 32) drops.add(observation);
        }
        JsonObject data = new JsonObject();
        data.addProperty("removed", true);
        data.addProperty("reported_success", reported);
        data.addProperty("collected_count", 0);
        data.addProperty("remaining_count", remaining);
        data.add("collected", new JsonArray());
        data.add("leftover", left);
        data.add("drops", drops);
        String feedback = "已移除目标方块，挖掘不直接入包；移除时观测到本次地上掉落 " + remaining
                + " 个，明细（最多32组）：" + left + "；掉落位置：" + drops
                + "。走近后身体自动拾取，遵守原版拾取延迟；实际入包量须重新查看背包。"
                + "主手耐久由原版破坏流程处理。";
        if (!reported) feedback = "BREAK_FAILED:原版未报告成功，但已观察到目标移除。" + feedback + "先核对现场，勿自动重试。";
        return finish(new ServerTool.Result(reported, feedback, data));
    }

    public void abort() {
        clearCrack();
        if (terminal == null) terminal = failure("CANCELLED:挖掘已中止，等待新指令。");
    }

    private ServerTool.Result finish(ServerTool.Result result) {
        clearCrack();
        terminal = result.data() == null ? failure(result.feedback()) : result;
        return terminal;
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
        private BlockAim.Aim aim;

        private PlayerAccess(CompanionPlayer player, ServerLevel level, BlockPos pos, String expectedBlock) {
            this.player = player;
            this.level = level;
            this.pos = pos;
            this.expectedBlock = expectedBlock;
        }

        @Override public ServerTool.Result guard(double reach) {
            if (player.level() != level) return failure("TARGET_LOST:身体已换维度，原目标作废。");
            if (!level.isInWorldBounds(pos) || !level.getWorldBorder().isWithinBounds(pos)) {
                return failure("DENIED:目标超出世界范围，换位置。");
            }
            if (!level.isLoaded(pos)) return failure("TARGET_LOST:目标未加载，先靠近。");
            for (BlockPos near : BlockPos.betweenClosed(pos.offset(-1, -1, -1), pos.offset(1, 1, 1))) {
                if (!level.isInWorldBounds(near) || !level.isLoaded(near)) {
                    return failure("TARGET_LOST:挖掘邻域未加载或超出世界范围，换目标或先靠近。");
                }
            }
            if (player.distanceToSqr(pos.getX() + .5, pos.getY() + .5, pos.getZ() + .5) > reach * reach) {
                return failure("OUT_OF_REACH:目标超出臂长，先 move_to 靠近。");
            }
            if (!player.gameMode.isSurvival() || !level.mayInteract(player, pos)) {
                return failure("DENIED:当前生存模式或世界交互规则不允许破坏。");
            }
            return null;
        }
        @Override public BlockState state() { return level.getBlockState(pos); }
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
        @Override public boolean destroy() { return player.gameMode.destroyBlock(pos); }
        @Override public void crack(int stage) {
            level.getServer().getPlayerList().broadcastAll(
                    new ClientboundBlockDestructionPacket(player.getId(), pos, stage), level.dimension());
        }
        @Override public List<? extends Drop> nearbyDrops() {
            return level.getEntitiesOfClass(ItemEntity.class, new AABB(pos).inflate(.5),
                    item -> item.isAlive()).stream().map(LiveDrop::new).toList();
        }
    }

    private record LiveDrop(ItemEntity entity) implements Drop {
        @Override public Object identity() { return entity.getUUID(); }
        @Override public ItemStack stack() { return entity.getItem(); }
        @Override public JsonObject observation() { return GroundItems.describe(entity); }
    }
}
