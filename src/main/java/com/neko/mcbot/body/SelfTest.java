package com.neko.mcbot.body;

import com.google.gson.JsonObject;
import com.neko.mcbot.McbotMod;
import com.neko.mcbot.common.Envelope;
import com.neko.mcbot.server.ServerTool;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/**
 * 无头自测 harness（仅开发期使用）：存在 run/mcbot/autotest.flag 时，
 * 启动数秒后在服务器线程直跑 M1~M3 验收场景，异常与断言全量入日志，自动删 flag。
 */
public final class SelfTest {

    private static boolean done;

    private SelfTest() {
    }

    public static void onTick(MinecraftServer server) {
        if (done) {
            return;
        }
        Path flag = FabricLoader.getInstance().getGameDir().resolve("mcbot").resolve("autotest.flag");
        if (!Files.exists(flag)) {
            return;
        }
        done = true;
        try {
            Files.delete(flag);
        } catch (Exception ignored) {
        }

        var dispatcherCmd = server.getCommands().getDispatcher();
        var src = server.createCommandSourceStack();
        for (String cmd : new String[]{"mcbot ping", "mcbot summon steve", "mcbot list"}) {
            try {
                McbotMod.LOG.info("AUTOTEST [{}] rc={}", cmd, dispatcherCmd.execute(cmd, src));
            } catch (Throwable t) {
                McbotMod.LOG.error("AUTOTEST [{}] FAILED", cmd, t);
            }
        }

        m3Scenarios(server);
    }

    private static void m3Scenarios(MinecraftServer server) {
        var dispatcher = McbotMod.dispatcher();
        var summon = McbotMod.summonService();
        var registry = McbotMod.toolRegistry();
        if (dispatcher == null || summon == null || registry == null) {
            McbotMod.LOG.error("[m3] 装配未就绪，场景跳过");
            return;
        }
        ServerPlayer steve = server.getPlayerList().getPlayerByName("steve");
        if (!(steve instanceof CompanionPlayer cp)) {
            McbotMod.LOG.error("[m3] steve 不在场，场景跳过");
            return;
        }

        // 场景 1：闸③白名单——未知工具必须 DENIED（回执见 [m3] S2C 日志行）
        McbotMod.LOG.info("[m3] 场景1 白名单拒绝: tool=no_such_tool");
        dispatcher.handle(steve, env("tool_call", toolCall(1, "no_such_tool", "{}")));

        // 场景 2：闸③owner——steve 的同伴 owner=NO_OWNER，正向通道此时应被拒
        McbotMod.LOG.info("[m3] 场景2 owner 拒绝（当前同伴非 steve 所有）");
        dispatcher.handle(steve, env("tool_call", toolCall(2, "status", "{}")));

        // 场景 3：直调工具冒烟（绕过网络闸，验证工具本体与 MC API 通路）
        ServerTool.Result st = registry.get("status").run(cp, new JsonObject());
        McbotMod.LOG.info("[m3] 场景3 status: ok={} {}", st.ok(), st.feedback());
        JsonObject sa = new JsonObject();
        sa.addProperty("r", 8);
        ServerTool.Result sc = registry.get("scan_area").run(cp, sa);
        McbotMod.LOG.info("[m3] 场景3 scan_area: ok={} {}", sc.ok(), sc.feedback());

        // 场景 4：owner 正向全链路——临时把 steve 登记为自己的主人（仅内存，不 save）
        CompanionRoster.Entry temp = new CompanionRoster.Entry(
                cp.getUUID(), "steve-selftest", cp.getUUID(), "steve-selftest");
        summon.roster().add(temp);
        McbotMod.LOG.info("[m3] 场景4 tool_call status（正向，看 [m3] S2C 回执行）");
        dispatcher.handle(steve, env("tool_call", toolCall(42, "status", "{}")));
        summon.roster().remove(temp);

        // 场景 5：闸②速率——连打 80 发，burst 60 之后应出现 DENIED 频率回执
        McbotMod.LOG.info("[m3] 场景5 速率闸连打 80 发");
        for (int i = 0; i < 80; i++) {
            dispatcher.handle(steve, env("tool_call", toolCall(1000 + i, "no_such_tool", "{}")));
        }
        McbotMod.LOG.info("[m3] 场景结束。判读：场景2/4 之间必须出现一次 tool_result(seq=42)，"
                + "场景5 中段起出现大量 'DENIED:消息过于频繁'。");

        m4Scenarios(cp);
    }

    /** M4：给镐和圆石 → 放石头 → 真挖 → 滑步 → 放箱存入。链式 future，全部日志收尾。 */
    private static void m4Scenarios(CompanionPlayer cp) {
        // 先把同伴挪回地表（soak 硬杀可能把 .dat 停在洞底）
        var rd = cp.level().getRespawnData();
        cp.teleportTo(rd.pos().getX() + 0.5, rd.pos().getY() + 1, rd.pos().getZ() + 0.5);
        var registry = McbotMod.toolRegistry();
        var sched = McbotMod.scheduler();
        var inv = cp.getInventory();
        inv.clearContent();
        inv.add(new net.minecraft.world.item.ItemStack(
                net.minecraft.world.item.Items.WOODEN_PICKAXE));
        inv.add(new net.minecraft.world.item.ItemStack(
                net.minecraft.world.level.block.Blocks.COBBLESTONE, 3));
        inv.add(new net.minecraft.world.item.ItemStack(
                net.minecraft.world.level.block.Blocks.CHEST));

        var pos = findOpenSpot(cp);
        JsonObject place = new JsonObject();
        place.addProperty("x", pos.getX());
        place.addProperty("y", pos.getY());
        place.addProperty("z", pos.getZ());
        place.addProperty("item", "cobblestone");
        var placeR = registry.get("place_block").run(cp, place);
        McbotMod.LOG.info("[m4] place_block: {} {}", placeR.ok(), placeR.feedback());

        JsonObject brk = new JsonObject();
        brk.addProperty("x", pos.getX());
        brk.addProperty("y", pos.getY());
        brk.addProperty("z", pos.getZ());
        McbotMod.LOG.info("[m4] break_block 提交（看真实耗时，结束才有下行日志）");
        registry.get("break_block").runAsync(cp, brk, sched).thenAccept(r -> {
            McbotMod.LOG.info("[m4] break_block 完成: {} {}", r.ok(), r.feedback());
            // 滑步：向东 6 格
            JsonObject mv = new JsonObject();
            mv.addProperty("x", cp.blockPosition().getX() + 6);
            mv.addProperty("y", cp.blockPosition().getY());
            mv.addProperty("z", cp.blockPosition().getZ());
            McbotMod.LOG.info("[m4] move_to +6东 提交");
            McbotMod.toolRegistry().get("move_to").runAsync(cp, mv, sched).thenAccept(mr -> {
                McbotMod.LOG.info("[m4] move_to 完成: {} {}", mr.ok(), mr.feedback());
                // 放箱 + 存入
                var chestPos = findOpenSpot(cp);
                JsonObject pc = new JsonObject();
                pc.addProperty("x", chestPos.getX());
                pc.addProperty("y", chestPos.getY());
                pc.addProperty("z", chestPos.getZ());
                pc.addProperty("item", "chest");
                McbotMod.LOG.info("[m4] place chest: {}",
                        McbotMod.toolRegistry().get("place_block").run(cp, pc).feedback());
                JsonObject tf = new JsonObject();
                tf.addProperty("x", chestPos.getX());
                tf.addProperty("y", chestPos.getY());
                tf.addProperty("z", chestPos.getZ());
                tf.addProperty("dir", "in");
                tf.addProperty("item", "cobblestone");
                McbotMod.LOG.info("[m4] transfer in: {}",
                        McbotMod.toolRegistry().get("transfer").run(cp, tf).feedback());
                m4bScenarios(cp);
            });
        });
    }

    /** M4 收尾自测：真取消、忙时拒收、wait 正常走完（看 [m4b] 日志行）。 */
    private static void m4bScenarios(CompanionPlayer cp) {
        var registry = McbotMod.toolRegistry();
        var sched = McbotMod.scheduler();

        // A：长任务进行中 → 二次提交必须 BUSY → cancel 必须真停 → 空槽再 cancel 必须 false
        JsonObject longw = new JsonObject();
        longw.addProperty("seconds", 60);
        var f = registry.get("wait").runAsync(cp, longw, sched);
        var busyReply = registry.get("wait").runAsync(cp, longw, sched).getNow(null);
        boolean stopped = sched.cancel(cp.getUUID(), "自测叫停。");
        boolean idleStop = sched.cancel(cp.getUUID(), null);
        McbotMod.LOG.info("[m4b] busy拒收={} cancel命中={} 空槽cancel={}",
                busyReply != null && !busyReply.ok(), stopped, idleStop);
        f.thenAccept(r -> McbotMod.LOG.info("[m4b] 叫停回执: ok={} {}", r.ok(), r.feedback()));

        // B：wait 正常走完（约 2 秒后才见完成行）——完事接棒 m8 场景
        JsonObject w = new JsonObject();
        w.addProperty("seconds", 2);
        McbotMod.LOG.info("[m4b] wait 2s 提交");
        registry.get("wait").runAsync(cp, w, sched)
                .thenAccept(r -> {
                    McbotMod.LOG.info("[m4b] wait 完成: ok={} {}", r.ok(), r.feedback());
                    m8Scenarios(cp);
                });
    }

    /** 找一格开阔位：两格空气、脚下实心，先东后南绕圈。找不到就抬头放。 */
    private static net.minecraft.core.BlockPos findOpenSpot(CompanionPlayer cp) {
        var level = cp.level();
        int[][] dirs = {{1, 0}, {0, 1}, {-1, 0}, {0, -1}, {1, 1}, {-1, 1}, {1, -1}, {-1, -1}};
        for (int reach = 1; reach <= 4; reach++) {
            for (int[] d : dirs) {
                for (int dy = -1; dy <= 4; dy++) {
                    var p = cp.blockPosition().offset(d[0] * reach, dy, d[1] * reach);
                    if (level.getBlockState(p).isAir()
                            && level.getBlockState(p.above()).isAir()
                            && !level.getBlockState(p.below()).isAir()
                            && level.getBlockState(p.below()).getFluidState().isEmpty()) {
                        return p;
                    }
                }
            }
        }
        return cp.blockPosition().above(2);
    }

    /**
     * M8 DigAStar 无头验收（接在 m4b 之后，单槽串行）：
     * A 石墙拦路→NEED_CONFIRM 带清单；B 点头后真挖穿到达；
     * C 箱子嵌墙→箱子分毫不动（神圣集）；D 基岩笼→NO_PATH 干净失败。
     */
    private static void m8Scenarios(CompanionPlayer cp) {
        var registry = McbotMod.toolRegistry();
        var sched = McbotMod.scheduler();
        var level = cp.level();
        var base = cp.blockPosition();
        McbotMod.LOG.info("[m8] 基准点 {}", base.toShortString());

        // 铺一条测试大道：东 2..7 的地板填石，脚格/头格清成空气
        for (int dx = 2; dx <= 7; dx++) {
            level.setBlockAndUpdate(base.east(dx).below(), net.minecraft.world.level.block.Blocks.STONE.defaultBlockState());
            level.setBlockAndUpdate(base.east(dx), net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());
            level.setBlockAndUpdate(base.east(dx).above(), net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());
        }
        // A 墙：东 4 的脚+头两格石头（placeStock=0 爬不了顶，唯一路线就是挖穿）
        level.setBlockAndUpdate(base.east(4), net.minecraft.world.level.block.Blocks.STONE.defaultBlockState());
        level.setBlockAndUpdate(base.east(4).above(), net.minecraft.world.level.block.Blocks.STONE.defaultBlockState());

        // 场景 A：不带 may_alter_terrain → 必须 NEED_CONFIRM 且清单非空
        JsonObject a = moveArgs(base.east(6));
        McbotMod.LOG.info("[m8] A 需确认流提交");
        registry.get("move_to").runAsync(cp, a, sched).thenAccept(ra -> {
            boolean needConfirm = !ra.ok() && ra.feedback().startsWith("NEED_CONFIRM:");
            int listed = ra.data() != null && ra.data().has("blocks")
                    ? ra.data().getAsJsonArray("blocks").size() : 0;
            McbotMod.LOG.info("[m8] A 需确认={} 清单={} 格：{}", needConfirm, listed, ra.feedback());

            // 场景 B：点头 → 挖穿到达
            JsonObject b = moveArgs(base.east(6));
            b.addProperty("may_alter_terrain", true);
            McbotMod.LOG.info("[m8] B 确认后执行提交（看真挖耗时）");
            registry.get("move_to").runAsync(cp, b, sched).thenAccept(rb -> {
                boolean through = rb.ok();
                boolean wallGone = level.getBlockState(base.east(4)).isAir();
                McbotMod.LOG.info("[m8] B 到达={} 墙已被挖穿={}：{}", through, wallGone, rb.feedback());

                // 场景 C：把箱子嵌进墙里（脚格=箱子），验证神圣集：只能踩顶绕，绝不挖箱
                level.setBlockAndUpdate(base.east(4), net.minecraft.world.level.block.Blocks.CHEST.defaultBlockState());
                level.setBlockAndUpdate(base.east(4).above(), net.minecraft.world.level.block.Blocks.STONE.defaultBlockState());
                level.setBlockAndUpdate(base.east(4).above(2), net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());
                // 同伴拉回大道西端
                cp.teleportTo(base.east(2).getX() + 0.5, base.getY(), base.east(2).getZ() + 0.5);
                JsonObject c = moveArgs(base.east(6));
                c.addProperty("may_alter_terrain", true);
                McbotMod.LOG.info("[m8] C 箱子嵌墙提交");
                registry.get("move_to").runAsync(cp, c, sched).thenAccept(rc -> {
                    boolean chestIntact = level.getBlockState(base.east(4))
                            .is(net.minecraft.world.level.block.Blocks.CHEST);
                    McbotMod.LOG.info("[m8] C 箱子分毫未动={} 结果 ok={}：{}",
                            chestIntact, rc.ok(), rc.feedback());
                    // 收尾清箱子，免得残在存档里
                    level.destroyBlock(base.east(4), false, null);
                    m8dSealedBox(cp);
                });
            });
        });
        McbotMod.LOG.info("[m8] 判读基准：A 需确认=true 且清单≥1（踩脚挖头的最优解可以只挖 1 格）；"
                + "B 到达=true；C 箱子未动；D NO_PATH。全中即 M8 无头验收通过。");
    }

    /** 场景 D：基岩笼死→必须干净地 NO_PATH，拆笼后同伴归位。 */
    private static void m8dSealedBox(CompanionPlayer cp) {
        var registry = McbotMod.toolRegistry();
        var sched = McbotMod.scheduler();
        var level = cp.level();
        var home = cp.blockPosition();
        McbotMod.LOG.info("[m8] D home={}", home.toShortString());
        var bedrock = net.minecraft.world.level.block.Blocks.BEDROCK.defaultBlockState();
        var air = net.minecraft.world.level.block.Blocks.AIR.defaultBlockState();
        java.util.List<net.minecraft.core.BlockPos> cage = new java.util.ArrayList<>();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) {
                    continue; // 同伴占着中心
                }
                for (int dy = 0; dy <= 1; dy++) {
                    var p = home.offset(dx, dy, dz);
                    level.setBlockAndUpdate(p, bedrock);
                    cage.add(p);
                }
            }
        }
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                var lid = home.offset(dx, 2, dz);
                level.setBlockAndUpdate(lid, bedrock);
                cage.add(lid);
            }
        }
        JsonObject mv = moveArgs(home.east(4)); // 拉近：笼外东 6 可能落在未加载区块，会被工具层区块闸先拦
        mv.addProperty("may_alter_terrain", true); // 即使全盘授权，基岩也不许碰
        McbotMod.LOG.info("[m8] D 基岩笼死提交");
        registry.get("move_to").runAsync(cp, mv, sched).thenAccept(rd -> {
            boolean cleanFail = !rd.ok() && (rd.feedback().startsWith("NO_PATH:")
                    || rd.feedback().startsWith("BUDGET_EXCEEDED:"));
            // 拆笼归位
            for (var p : cage) {
                level.setBlockAndUpdate(p, air);
            }
            cp.teleportTo(home.getX() + 0.5, home.getY(), home.getZ() + 0.5);
            McbotMod.LOG.info("[m8] D 干净失败={}：{}", cleanFail, rd.feedback());
            McbotMod.LOG.info("[m8] 全部场景结束（笼已拆，同伴已归位）");
        });
    }

    private static JsonObject moveArgs(net.minecraft.core.BlockPos pos) {
        JsonObject o = new JsonObject();
        o.addProperty("x", pos.getX());
        o.addProperty("y", pos.getY());
        o.addProperty("z", pos.getZ());
        return o;
    }

    private static Envelope env(String kind, JsonObject body) {
        return new Envelope(kind, body);
    }

    private static JsonObject toolCall(int seq, String tool, String argsJson) {
        JsonObject o = new JsonObject();
        o.addProperty("seq", seq);
        o.addProperty("tool", tool);
        o.add("args", com.google.gson.JsonParser.parseString(argsJson).getAsJsonObject());
        return o;
    }
}
