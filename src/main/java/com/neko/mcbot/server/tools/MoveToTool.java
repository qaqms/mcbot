package com.neko.mcbot.server.tools;

import com.google.gson.JsonObject;
import com.neko.mcbot.body.CompanionPlayer;
import com.neko.mcbot.server.ServerTool;
import com.neko.mcbot.task.CompanionScheduler;
import com.neko.mcbot.task.TickTask;
import net.minecraft.core.BlockPos;

import java.util.concurrent.CompletableFuture;

/**
 * move_to（滑步占位版，M8 可挖 A* 替换）：每 tick 沿直线推进一段，
 * 自动上 1 格台阶、脚下空则坠落 1 格；前方被实心挡死则 PATH_BLOCKED 停下。
 * 全程只 teleport 不改造世界（符合"走路默认不改世界"原则）。
 */
public final class MoveToTool implements ServerTool {

    @Override
    public String name() {
        return "move_to";
    }

    @Override
    public CompletableFuture<Result> runAsync(CompanionPlayer c, JsonObject args, CompanionScheduler sched) {
        BlockPos to = BreakBlockTool.readPos(args);
        CompletableFuture<Result> f = new CompletableFuture<>();
        if (to == null) {
            return CompletableFuture.completedFuture(
                    new Result(false, "DENIED:需要整数坐标 x/y/z。", null));
        }
        double dx = to.getX() + 0.5 - c.getX();
        double dz = to.getZ() + 0.5 - c.getZ();
        if (Math.hypot(dx, dz) > 48) {
            return CompletableFuture.completedFuture(new Result(false,
                    "PATH_BLOCKED:直线超过 48 格滑步走不动（要更聪明的长途规划等我升级）。分几段过来。", null));
        }
        if (!sched.submit(c, new Task(to), f, 3600)) {
            return CompletableFuture.completedFuture(new Result(false,
                    "BUSY:我正忙着上一件事，等它结束或让我取消。", null));
        }
        return f;
    }

    private static final class Task extends TickTask {
        private final BlockPos to;
        private int stuck;

        Task(BlockPos to) {
            this.to = to;
        }

        @Override
        public Progress tick(CompanionPlayer c) {
            var level = c.level();
            double dx = to.getX() + 0.5 - c.getX();
            double dz = to.getZ() + 0.5 - c.getZ();
            double dist = Math.hypot(dx, dz);
            if (dist < 0.9) {
                // 落地收尾：脚下找可站立面
                BlockPos feet = c.blockPosition();
                for (int dy = -3; dy <= 1; dy++) {
                    BlockPos p = feet.below(dy < 0 ? -dy : 0).above(dy > 0 ? dy : 0);
                    if (standable(level, p)) {
                        c.teleportTo(p.getX() + 0.5, p.getY(), p.getZ() + 0.5);
                        return new Progress.Done(new Result(true,
                                "到了 (" + to.getX() + "," + to.getY() + "," + to.getZ()
                                        + ") 附近，站定在 " + p.toShortString() + "。", null));
                    }
                }
                return new Progress.Done(new Result(false,
                        "PATH_BLOCKED:目的地到了但周围没有落脚处，先清一下或换个目标。", null));
            }
            double step = Math.min(0.45, dist);
            double nx = c.getX() + dx / dist * step;
            double nz = c.getZ() + dz / dist * step;
            BlockPos ahead = BlockPos.containing(nx, c.getY(), nz);
            BlockPos stand = ahead;
            if (solid(level, ahead.above())) { // 头碰 -> 尝试上一步台阶
                stand = ahead.above();
            }
            if (solid(level, stand)) {
                if (++stuck > 30) {
                    return new Progress.Done(new Result(false,
                            "PATH_BLOCKED:被 " + level.getBlockState(ahead).getBlock().getName().getString()
                                    + " 挡死了（滑步版不会挖路）。要么绕路，要么等我学会挖穿它。", null));
                }
                return running();
            }
            stuck = 0;
            double y = c.getY();
            if (solid(level, BlockPos.containing(nx, y - 0.1, nz))) {
                y = Math.min(y + 1.05, stand.getY() + 1.0); // 上台阶
            } else if (!solid(level, BlockPos.containing(nx, y - 0.5, nz))) {
                y = Math.max(y - 0.5, stand.getY() - 1); // 下小坎
            }
            c.teleportTo(nx, y, nz);
            return running();
        }

        private boolean solid(net.minecraft.server.level.ServerLevel l, BlockPos p) {
            var st = l.getBlockState(p);
            return !st.isAir() && st.getFluidState().isEmpty() && st.blocksMotion();
        }

        private boolean standable(net.minecraft.server.level.ServerLevel l, BlockPos p) {
            return !solid(l, p) && !solid(l, p.above()) && solid(l, p.below());
        }
    }
}
