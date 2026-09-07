package com.neko.mcbot.server.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.neko.mcbot.McbotMod;
import com.neko.mcbot.body.CompanionPlayer;
import com.neko.mcbot.server.ServerTool;
import com.neko.mcbot.task.CompanionScheduler;
import com.neko.mcbot.task.TickTask;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ClientboundBlockDestructionPacket;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * break_block：手工计时的原版语义挖掘——
 * 进度/刻 = getDestroySpeed(含工具与饥饿修正) / defaultDestroyTime / 30，
 * 全程广播 ClientboundBlockDestructionPacket（所有客户端可见裂纹动画，-1 清除），
 * 完成后走 Block.getDrops(..., player, tool) 战利品表（拿错工具真的没掉落）→ 直接吸附进背包，
 * 装不下的按原版 popResource 落地。level.destroyBlock 负责实际移除。
 * （不用 handleBlockBreakAction：假玩家没有 connection tick 驱动它的内部进度。）
 */
public final class BreakBlockTool implements ServerTool {

    @Override
    public String name() {
        return "break_block";
    }

    @Override
    public CompletableFuture<Result> runAsync(CompanionPlayer c, JsonObject args, CompanionScheduler sched) {
        BlockPos pos = readPos(args);
        CompletableFuture<Result> f = new CompletableFuture<>();
        if (pos == null) {
            return CompletableFuture.completedFuture(
                    new Result(false, "DENIED:参数需要整数坐标 x/y/z。", null));
        }
        var level = c.level();
        if (!level.isLoaded(pos) || !level.hasChunkAt(pos)) {
            return CompletableFuture.completedFuture(
                    new Result(false, "TARGET_LOST:那个位置不在已加载区域，先靠近它。", null));
        }
        BlockState before = level.getBlockState(pos);
        if (before.isAir()) {
            return CompletableFuture.completedFuture(
                    new Result(false, "TARGET_LOST:那里已经是空气了，换个目标。", null));
        }
        if (!before.getFluidState().isEmpty()) {
            return CompletableFuture.completedFuture(
                    new Result(false, "DENIED:液体不能被挖。要排水就得放方块填掉它。", null));
        }
        float hardness = before.getBlock().defaultDestroyTime();
        if (hardness < 0) {
            return CompletableFuture.completedFuture(new Result(false,
                    "UNBREAKABLE:" + before.getBlock().getName().getString() + "（生存手段不可破坏），换目标。", null));
        }
        if (c.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) > 5.5 * 5.5) {
            return CompletableFuture.completedFuture(new Result(false,
                    "OUT_OF_REACH:那格超过我的臂长（约 5.5 格）。先 move_to 靠近。", null));
        }
        ItemStack tool = c.getInventory().getSelectedItem().copy();
        if (!sched.submit(c, new Task(pos, before, tool), f, 1200)) {
            return CompletableFuture.completedFuture(new Result(false,
                    "BUSY:我正忙着上一件事，等它结束或让我取消。", null));
        }
        return f;
    }

    private static final class Task extends TickTask {
        private final BlockPos pos;
        private final BlockState before;
        private final ItemStack tool;
        private float progress;
        private int lastStage = -1;
        private int noSpeedTicks;
        private int collectTicks;
        private boolean finished;
        private net.minecraft.server.level.ServerLevel seenLevel;
        private final List<String> collected = new ArrayList<>();
        private final List<String> leftover = new ArrayList<>();

        Task(BlockPos pos, BlockState before, ItemStack tool) {
            this.pos = pos;
            this.before = before;
            this.tool = tool;
        }

        @Override
        public Progress tick(CompanionPlayer c) {
            var level = c.level();
            seenLevel = level;
            if (level.hasChunkAt(pos) && level.getBlockState(pos) != before && !finished) {
                clearDestruction(level);
                return new Progress.Done(new Result(false,
                        "TARGET_LOST:目标方块在我挖的过程中被人动过了。", null));
            }
            if (c.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) > 6.5 * 6.5) {
                clearDestruction(level);
                return new Progress.Done(new Result(false,
                        "OUT_OF_REACH:走开了，挖断了。要挖就站到近处重新来。", null));
            }
            if (finished) {
                return finishPhase(level, c);
            }

            float speed = c.getDestroySpeed(before);
            float hardness = before.getBlock().defaultDestroyTime();
            if (speed <= 0) {
                if (++noSpeedTicks >= 10) {
                    clearDestruction(level);
                    String held = tool.isEmpty() ? "空手" : tool.getItemName().getString();
                    return new Progress.Done(new Result(false,
                            "WRONG_TOOL:" + before.getBlock().getName().getString()
                                    + " 用" + held + "根本挖不动，先做或找合适的工具（石头类需要镐）。", null));
                }
                return running();
            }
            progress += speed / hardness / 30f;
            int stage = Math.min(9, (int) (progress * 10f));
            if (stage != lastStage) {
                lastStage = stage;
                level.getServer().getPlayerList()
                        .broadcastAll(new ClientboundBlockDestructionPacket(0, pos, stage),
                                level.dimension());
            }
            if (progress >= 1f) {
                BlockEntity be = level.getBlockEntity(pos);
                List<ItemStack> drops = Block.getDrops(before, level, pos, be, c, tool);
                clearDestruction(level);
                level.destroyBlock(pos, false, c);
                // 吸附掉落：先背包，装不下落地
                for (ItemStack s : drops) {
                    if (s.isEmpty()) {
                        continue;
                    }
                    if (c.getInventory().add(s.copy())) {
                        collected.add(s.getCount() + "×" + s.getItemName().getString());
                    } else {
                        Block.popResource(level, pos, s);
                        leftover.add(s.getCount() + "×" + s.getItemName().getString());
                    }
                }
                finished = true;
                collectTicks = 0;
            }
            return running();
        }

        /** 完成后的收尾：给动画留两拍再交回执（顺带保持"到 done 才回包"的节奏）。 */
        private Progress finishPhase(net.minecraft.server.level.ServerLevel level, CompanionPlayer c) {
            if (++collectTicks < 3) {
                return running();
            }
            JsonObject data = new JsonObject();
            JsonArray got = new JsonArray();
            collected.forEach(got::add);
            data.add("collected", got);
            String fb = "挖掉了 " + before.getBlock().getName().getString()
                    + (collected.isEmpty() ? "（没有掉落）" : "，收到背包: " + String.join("、", collected))
                    + (leftover.isEmpty() ? "" : "；背包满了落地: " + String.join("、", leftover));
            return new Progress.Done(new Result(leftover.isEmpty(), fb, data));
        }

        private void clearDestruction(net.minecraft.server.level.ServerLevel level) {
            if (lastStage >= 0) {
                level.getServer().getPlayerList()
                        .broadcastAll(new ClientboundBlockDestructionPacket(0, pos, -1),
                                level.dimension());
                lastStage = -1;
            }
        }

        /** 被叫停/超时也要把裂纹动画抹掉，不然客户端上留着假进度。 */
        @Override
        public void onAbort() {
            if (seenLevel != null) {
                clearDestruction(seenLevel);
            }
        }
    }

    static BlockPos readPos(JsonObject args) {
        try {
            return new BlockPos(args.get("x").getAsInt(), args.get("y").getAsInt(), args.get("z").getAsInt());
        } catch (RuntimeException e) {
            return null;
        }
    }
}
