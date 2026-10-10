package com.neko.mcbot.body;

import com.google.gson.JsonObject;
import com.neko.mcbot.McbotMod;
import com.neko.mcbot.common.Envelope;
import com.neko.mcbot.common.McbotPayloads;
import com.neko.mcbot.common.ScanFormat;
import com.neko.mcbot.common.WireSize;
import com.neko.mcbot.path.PathTask;
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
        if (CraftSelfTest.runIfRequested(server)) {
            done = true;
            return;
        }
        if (InventorySelfTest.runIfRequested(server)) {
            done = true;
            return;
        }
        if (PersistenceSelfTest.runIfRequested(server)) {
            done = true;
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
        McbotMod.LOG.info("[f0-world] 名册位于当前存档={} dispatcher属于当前服务器={} 全局C2S已注册={}",
                McbotMod.summonService().roster().store().normalize().equals(
                        server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT)
                                .resolve("mcbot/companions.json").normalize()),
                McbotMod.dispatcher().belongsTo(server),
                net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.getGlobalReceivers()
                        .contains(McbotPayloads.C2s.TYPE.id()));
        for (String cmd : new String[]{"mcbot ping", "mcbot summon steve", "mcbot list"}) {
            try {
                McbotMod.LOG.info("AUTOTEST [{}] rc={}", cmd, dispatcherCmd.execute(cmd, src));
            } catch (Throwable t) {
                McbotMod.LOG.error("AUTOTEST [{}] FAILED", cmd, t);
            }
        }

        m5aSizeGate();
        m3Scenarios(server);
    }

    /**
     * 闸①（信封尺寸）无头验收。不依赖同伴，所以跑在主链路之前；且走**真的**
     * {@code C2s.CODEC} 编解码往返——那才是收包时真正经过的路径，手写字符串判长度不算验收。
     *
     * <p>三个点必测：①超限包被换成哨兵而不是抛异常（抛了就是踢主人线）；
     * ②中文包按**字节**而非字符判定（旧闸量错单位的地方）；③正常小包往返无损。
     */
    private static void m5aSizeGate() {
        io.netty.buffer.ByteBuf okBuf = io.netty.buffer.Unpooled.buffer();
        McbotPayloads.C2s.CODEC.encode(okBuf, new McbotPayloads.C2s(
                "{\"kind\":\"tool_call\",\"seq\":7,\"tool\":\"scan_area\",\"args\":{\"r\":16}}"));
        McbotPayloads.C2s okBack = McbotPayloads.C2s.CODEC.decode(okBuf);
        boolean 小包往返无损 = !okBack.oversize() && okBack.json.contains("tool_call")
                && okBack.json.contains("scan_area");

        // 中文堆到超限：按字符数看“没超 32768”，按字节数必须把它拦住
        String fat = "{\"kind\":\"tool_call\",\"args\":{\"note\":\"" + "矿".repeat(12000) + "\"}}";
        io.netty.buffer.ByteBuf fatBuf = io.netty.buffer.Unpooled.buffer();
        McbotPayloads.C2s.CODEC.encode(fatBuf, new McbotPayloads.C2s(fat));
        int fatWire = fatBuf.readableBytes();
        McbotPayloads.C2s fatBack;
        String threw = "none";
        try {
            fatBack = McbotPayloads.C2s.CODEC.decode(fatBuf);
        } catch (Throwable t) {
            fatBack = McbotPayloads.C2s.OVERSIZED;      // 只是为了不让下面的日志抩掉
            threw = t.getClass().getSimpleName();
        }
        boolean 超限被拒且不抛 = fatBack.oversize() && "none".equals(threw);

        // 刚好在线内：验证闸没关得过紧（否则正常回执会被误杀）
        int budget = WireSize.MAX_BODY_BYTES - "{\"kind\":\"x\",\"v\":\"\"}".length();
        String edge = "{\"kind\":\"x\",\"v\":\"" + "a".repeat(budget) + "\"}";        io.netty.buffer.ByteBuf edgeBuf = io.netty.buffer.Unpooled.buffer();
        McbotPayloads.C2s.CODEC.encode(edgeBuf, new McbotPayloads.C2s(edge));
        McbotPayloads.C2s edgeBack = McbotPayloads.C2s.CODEC.decode(edgeBuf);
        boolean 限内放行 = !edgeBack.oversize() && edge.equals(edgeBack.json);

        // 畸形声明（长度前缀吹大）同样不能抛：这是“不踢线”的另一半保证
        io.netty.buffer.ByteBuf badBuf = io.netty.buffer.Unpooled.buffer();
        net.minecraft.network.VarInt.write(badBuf, 900000);   // 声明 900000 字节，实际只写几十字节
        badBuf.writeBytes("只有几个字节".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        McbotPayloads.C2s badBack;
        String badThrew = "none";
        try {
            badBack = McbotPayloads.C2s.CODEC.decode(badBuf);
        } catch (Throwable t) {
            badBack = McbotPayloads.C2s.OVERSIZED;
            badThrew = t.getClass().getSimpleName();
        }
        boolean 畸形不抛 = badBack.oversize() && "none".equals(badThrew);

        McbotMod.LOG.info("[m5a] 小包往返={} 超限被拒且不抛={} 限内放行={} 畸形不抛={}"
                        + " (fat={}字符/{}B 线上={}B 上限={}B)",
                小包往返无损, 超限被拒且不抛, 限内放行, 畸形不抛,
                fat.length(), WireSize.utf8Bytes(fat), fatWire, WireSize.MAX_BODY_BYTES);
        fatBuf.release();
        edgeBuf.release();
        okBuf.release();
        badBuf.release();
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
        // 【09-08 定性】出生点本身可能就是悬空格/雪原尖柱（实测脚撑=false 且无料可垫）。
        // 票的事已由产品层 CompanionChunkPads 接管（R1-S3b，[m9] 验收）；这里只管净带落脚。
        var strip = findClearStrip(cp.level(), cp.blockPosition());
        if (strip != null) {
            cp.teleportTo(strip.getX() + 0.5, strip.getY(), strip.getZ() + 0.5);
            McbotMod.LOG.info("[m4] 迁至 14 格净带 {}", strip.toShortString());
        } else {
            McbotMod.LOG.warn("[m4] 出生点周围 40 格内无净带，原地照跑");
        }
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
        // [m4] 用的也是 `基准点.east(6)`，中间只隔几秒——不清 lastPlan 缓存的话，场景 A 会
        // 命中 m4 那条路，于是"A 是搜出来的、B 是复用的"这条判读不成立（09-10 实测踩到）。
        PathTask.clearPlanCache();
        var base = cp.blockPosition();
        McbotMod.LOG.info("[m8] 基准点 {}", base.toShortString());
        // 环境断言（轻量常驻）：产品票失效时这里立刻暴䁓，不等搜索谜之 expanded=1。
        {
            for (int d = 2; d <= 6; d += 2) {
                net.minecraft.core.BlockPos p = base.east(d);
                if (!level.hasChunkAt(p)) {
                    McbotMod.LOG.warn("[m8env] 走廊({}) 未加载！产品票断供，先查 CompanionChunkPads",
                            p.toShortString());
                }
            }
        }

        // 铺一条**完全密闭**的石砌短隧道：x=base+2..base+7，1 格宽 2 格高，四壁/顶/底/东端全石，
        // 只留西端（base+1 那侧）一个门洞。
        //
        // 为什么是"密闭盒子"而不是"露天的两排侧墙"（09-10 改）：旧版只砌了脚+头两层的侧墙，
        // 剩下全靠天然地形，结果本机实测 A* 直接从**东端外侧**绕进走廊并落在目标旁边
        // （`7,63,0→13,63,0` 路径 10 节点、挖 0 放 0，A 报"不用挖就能到"）。
        // 算法没错，是验收场景在吃地形运气——而"[m8] A 必须 NEED_CONFIRM"一旦靠运气，
        // 它就不是验收而是抽奖。密闭盒子把地形自由度清零：唯一进得去的路是西端门洞，
        // 进去走三格就在 base+4 撞上必须挖穿的墙（脚+头两格）。
        var stone = net.minecraft.world.level.block.Blocks.STONE.defaultBlockState();
        var air = net.minecraft.world.level.block.Blocks.AIR.defaultBlockState();
        for (int dx = 2; dx <= 7; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                for (int dy = -1; dy <= 3; dy++) {
                    // dx<=6 才是隧道本体；dx==7 是**东端塞子**——曾经写成"整段都挖空"，
                    // 结果东口敞着，A* 从东侧绕进来又变成 0 挖（本机 base=25,67,0 实测复现）。
                    boolean tunnel = dz == 0 && (dy == 0 || dy == 1) && dx <= 6;
                    level.setBlockAndUpdate(base.east(dx).offset(0, dy, dz), tunnel ? air : stone);
                }
            }
        }
        // 西端引道：同伴脚下垫石、脚/头清空——起点判据不再受原地形（水/树叶/台阶）影响
        for (int dx = 0; dx <= 1; dx++) {
            level.setBlockAndUpdate(base.east(dx).below(), stone);
            level.setBlockAndUpdate(base.east(dx), air);
            level.setBlockAndUpdate(base.east(dx).above(), air);
        }
        // A 墙：东 4 的脚+头两格石头（隧道内唯一可挖的通路；两侧/顶/东端全已封死）
        level.setBlockAndUpdate(base.east(4), stone);
        level.setBlockAndUpdate(base.east(4).above(), stone);

        // 场景 A：不带 may_alter_terrain → 必须 NEED_CONFIRM 且清单非空
        JsonObject a = moveArgs(base.east(6));
        McbotMod.LOG.info("[m8] A 需确认流提交");
        registry.get("move_to").runAsync(cp, a, sched).thenAccept(ra -> {
            boolean needConfirm = !ra.ok() && ra.feedback().startsWith("NEED_CONFIRM:");
            int listed = ra.data() != null && ra.data().has("blocks")
                    ? ra.data().getAsJsonArray("blocks").size() : 0;
            // 效率评估 §5.4：清单必须**内联在给模型看的文字里**。以前文案让模型"见 data.blocks"，
            // 而 data 到不了模型（AgentRunner 只取 feedback）——所以这条断言钉的是
            // "回执文本本身必须含清单"，而不是"信封里有没有 data"。
            boolean inlined = ra.feedback().contains("要动的方块：")
                    && ra.feedback().contains("×");
            int fbBytes = WireSize.utf8Bytes(ra.feedback());
            boolean withinBudget = fbBytes < 4096; // 内联预算 1200B + 固定文案，留足余量
            McbotMod.LOG.info("[m8] A 需确认={} 清单={} 格 内联={} 回执={}B：{}",
                    needConfirm, listed, inlined, fbBytes, ra.feedback());

            // 场景 B：点头 → 挖穿到达
            JsonObject b = moveArgs(base.east(6));
            b.addProperty("may_alter_terrain", true);
            McbotMod.LOG.info("[m8] B 确认后执行提交（看真挖耗时）");
            registry.get("move_to").runAsync(cp, b, sched).thenAccept(rb -> {
                boolean through = rb.ok();
                // "打通"而不是"墙那一格变空气"：同伴只有 2 格高，穿墙只要清掉脚+头**两格**中的
                // 任意一对（实测它会跳上墙顶再挖顶棚，走的是 (x,94+1..94+2) 那对）——
                // 只盯 base.east(4) 那一格会得出"没挖穿"的假读数（09-10 实测踩到）。
                boolean breached = false;
                for (int dy = 0; dy <= 2; dy++) {
                    breached |= level.getBlockState(base.east(4).above(dy)).isAir();
                }
                McbotMod.LOG.info("[m8] B 到达={} 墙位被打通={}：{}", through, breached, rb.feedback());

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
        McbotMod.LOG.info("[m8] 判读基准：A 需确认=true 且清单≥1（密闭隧道：不挖开就没有第二条路进得去）；"
                + "B 到达=true 且墙位被打通=true；C 箱子未动；D NO_PATH。全中即 M8 无头验收通过。");
    }

    /** 找 14 格水平净带（脚/头可通行、下为实心且非容器）：m4 场景不吃出生点的地形运气。 */
    private static net.minecraft.core.BlockPos findClearStrip(
            net.minecraft.server.level.ServerLevel level, net.minecraft.core.BlockPos origin) {
        for (int off = 0; off <= 40; off++) {
            int[] dirs = (off == 0) ? new int[]{1} : new int[]{1, -1};
            for (int dir : dirs) {
                for (int dy : new int[]{0, 1, -1}) {
                    var cand = origin.offset(dir * off, dy, 0);
                    if (stripOk(level, cand)) {
                        return cand;
                    }
                }
            }
        }
        return null;
    }

    private static boolean stripOk(net.minecraft.server.level.ServerLevel level,
                                   net.minecraft.core.BlockPos start) {
        for (int i = 0; i < 14; i++) {
            var p = start.east(i);
            var foot = level.getBlockState(p);
            var head = level.getBlockState(p.above());
            var down = level.getBlockState(p.below());
            boolean open = !foot.blocksMotion() && foot.getFluidState().isEmpty()
                    && !head.blocksMotion() && head.getFluidState().isEmpty();
            boolean ground = down.blocksMotion() && down.getFluidState().isEmpty()
                    && !down.hasBlockEntity();
            if (!open || !ground) {
                return false;
            }
        }
        return true;
    }

    /**
     * 场景 D：基岩笼死→必须干净地 NO_PATH，拆笼后同伴归位。
     */
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
            m9PadScenario(cp);
        });
    }

    /**
     * R1-S3b 无头验收（产品票 dogfood）：同伴 5×5 垫子要能——A1 跟人到新家；
     * A2 只刷自己周围（远环不得为真，排除“碰巧全域加载”）；A3 再跳一次后旧家
     * 垫子停止续期→自然过期自清（无任何显式释放代码）。
     */
    private static void m9PadScenario(CompanionPlayer cp) {
        var level = cp.level();
        var sched = McbotMod.scheduler();
        var registry = McbotMod.toolRegistry();
        var home = cp.blockPosition();
        // 新家：东 96 格（6 chunk，超出旧垫 5×5 与出生常驻区）；地面安全点用 SafeSpawn
        var dest = SafeSpawn.findNear(level, home.east(96));
        cp.teleportTo(dest.getX() + 0.5, dest.getY(), dest.getZ() + 0.5);
        McbotMod.LOG.info("[m9] 跳新家 {}（旧家 {}）", dest.toShortString(), home.toShortString());
        JsonObject w = new JsonObject();
        w.addProperty("seconds", 4); // 80 拍 > 票寿命 40：续票中→常在；断续→必过期
        registry.get("wait").runAsync(cp, w, sched).thenAccept(r -> {
            var dChunk = new net.minecraft.world.level.ChunkPos(dest);
            boolean padHere = level.hasChunkAt(dChunk.getMiddleBlockPosition(dest.getY()));
            boolean farRing = level.hasChunkAt(
                    new net.minecraft.core.BlockPos((dChunk.x + 10) * 16, dest.getY(), (dChunk.z + 10) * 16));
            McbotMod.LOG.info("[m9] A1 新家票={} A2 远环也加载(必须false)={}", padHere, farRing);
            // 二次跳：往东再 96 格；旧家 padHere 的票从此断续，等它自焚
            var dest2 = SafeSpawn.findNear(level, dest.east(96));
            cp.teleportTo(dest2.getX() + 0.5, dest2.getY(), dest2.getZ() + 0.5);
            JsonObject w2 = new JsonObject();
            w2.addProperty("seconds", 4);
            registry.get("wait").runAsync(cp, w2, sched).thenAccept(r2 -> {
                boolean oldGone = !level.hasChunkAt(dChunk.getMiddleBlockPosition(dest.getY()));
                boolean newHere = level.hasChunkAt(new net.minecraft.world.level.ChunkPos(dest2)
                        .getMiddleBlockPosition(dest2.getY()));
                McbotMod.LOG.info("[m9] A3 旧家断续后自清={} 新家继续跟={}", oldGone, newHere);
                McbotMod.LOG.info("[m9] 判读：A1=true 且 A2=false 且 A3 两 true 即产品票成立；"
                        + "全中则 R1 寻路线无头部分全部收尾。归位。");
                cp.teleportTo(home.getX() + 0.5, home.getY(), home.getZ() + 0.5);
                r2dScanScenario(cp);
            });
        });
    }

    /**
     * R2-S1（设计卡 §D）无头验收：把 3 块 STONE 嵌进同伴身边（近环），扫一次，
     * 验证“可行动化”的三件事真的成立：分类词表、绝对坐标、体量在线路闸内。
     *
     * <p>为什么嵌在紧贴身旁的 1～2 格：近环只保留每种路径最近的几格，嵌远了
     * 会被自然石材挤掉名额——那时“含 stone”仍能由周围地形满足，断言就白给了。
     */
    private static void r2dScanScenario(CompanionPlayer cp) {
        var level = cp.level();
        var registry = McbotMod.toolRegistry();
        var base = cp.blockPosition();
        // 嵌三块：脚旁、头顶、东侧 2 格（都在近环 y=-1..1 以内）
        var embeds = new java.util.ArrayList<net.minecraft.core.BlockPos>(3);
        embeds.add(base.east(1));
        embeds.add(base.east(1).above());
        embeds.add(base.east(2));
        var prior = new java.util.ArrayList<net.minecraft.world.level.block.state.BlockState>();
        for (var p : embeds) {
            prior.add(level.getBlockState(p));
            level.setBlockAndUpdate(p, net.minecraft.world.level.block.Blocks.STONE.defaultBlockState());
        }

        ServerTool.Result res = registry.get("scan_area").run(cp, new JsonObject());
        String fb = res.feedback();
        int bytes = WireSize.utf8Bytes(fb);

        boolean 含stone = fb.contains("stone");
        boolean 含绝对坐标 = fb.contains("@(");
        boolean 体量在线闸内 = bytes < WireSize.MAX_BODY_BYTES;
        String firstLine = fb.substring(0, Math.max(0, fb.indexOf('\n') < 0 ? fb.length() : fb.indexOf('\n')));
        boolean 首行朝向 = firstLine.startsWith("我在 (") && firstLine.contains(" 面朝 ");
        boolean 词表分类 = fb.contains("[rock]");
        int faceAt = firstLine.indexOf(" 面朝 ");
        boolean 朝向合法 = faceAt >= 0
                && ScanFormat.EIGHT.contains(firstLine.substring(faceAt + 4).trim());
        // 参考项（不进通过基准）：近环每种路径只留最近 4 格，脚下本来就是石材时
        // 把我们嵌的三块挤出名额是**正确行为**，所以这里只报数不断言。
        boolean 嵌块可见 = false;
        for (var p : embeds) {
            if (fb.contains("@(" + p.getX() + "," + p.getY() + "," + p.getZ() + ")")) {
                嵌块可见 = true;
                break;
            }
        }

        // 收尾：把嵌进去的石头恢复成原状，不给存档留验收残留
        for (int i = 0; i < embeds.size(); i++) {
            level.setBlockAndUpdate(embeds.get(i), prior.get(i));
        }

        McbotMod.LOG.info("[r2d] 含stone={} 含绝对坐标={} 体量{}B<{}B={} 首行朝向={} 朝向合法={} 词表[rock]={} 嵌块可见(参考)={}",
                含stone, 含绝对坐标, bytes, WireSize.MAX_BODY_BYTES, 体量在线闸内, 首行朝向, 朝向合法, 词表分类, 嵌块可见);
        McbotMod.LOG.info("[r2d] 回执快照（前 {} 字）：{}", Math.min(fb.length(), 400),
                fb.replace('\n', '¶'));
        McbotMod.LOG.info("[r2d] 判读基准（卡 §F-S1）：含stone / 含@( / 字节<{} 三项全 true 即无头验收通过；"
                + "另钉结构三项：首行'我在 (' / 面朝∈八向 / 含[rock] 词表。嵌块已按原状恢复。",
                WireSize.MAX_BODY_BYTES);

        r2cAcceptScenario(cp);
    }

    /**
     * R2-S4 阶段 2（受理即回执 + PARK）无头验收。
     *
     * <p><b>这个场景验得到什么、验不到什么（先读，别误读成"整卡验过了"）</b>：
     * 无头 harness **没有连着的客户端**，`job_ack`/`job_event` 发出去是空操作
     * （`ServerPlayNetworking.send` 对未连接玩家直接返回 false），所以这里只能验
     * **策略表 / 文案 / 跨模块契约**这三件纯逻辑；真正那条"受理 → 客户端 PARK →
     * 事件回来 → 续跑"的往返，由 agent-core 的 `AgentLoopParkTest`（7 例）+
     * `PendingJobsTest`（7 例）+ 根工程 `JobEnvelopeTest`（6 例）覆盖；
     * 两端对接要等主人联机时看 `[brain] job …` 日志。
     */
    private static void r2cAcceptScenario(CompanionPlayer cp) {
        var registry = McbotMod.toolRegistry();
        var args = new JsonObject();
        var here = cp.blockPosition().east(8);
        args.addProperty("x", here.getX());
        args.addProperty("y", here.getY());
        args.addProperty("z", here.getZ());

        // ① 策略表：只有跨 tick 长活走 ACCEPT。这条表就是性能取舍的载体——
        //    短活走 ACCEPT 是净亏（先受理再结果 = 白多一跳 + 模型多问一次）。
        int accept = 0;
        int sync = 0;
        boolean policyOk = true;
        var names = new java.util.TreeSet<>(registry.names());
        for (String n : names) {
            var t = registry.get(n);
            boolean isAccept = t.acceptanceMode() == com.neko.mcbot.server.ServerTool.Acceptance.ACCEPT;
            boolean shouldAccept = "move_to".equals(n) || "break_block".equals(n);
            policyOk &= isAccept == shouldAccept;
            if (isAccept) {
                accept++;
            } else {
                sync++;
            }
            McbotMod.LOG.info("[r2c] 策略 {} mode={} cap={}tick 主语={}",
                    n, isAccept ? "ACCEPT" : "SYNC", t.capTicks(args), t.acceptSubject(args));
        }

        // ② 文案与契约：受理回执必须以 ACCEPTED: 开头，且三句教学齐（还没有结果/别等/我主动报编号）
        var moveTool = registry.get("move_to");
        int cap = moveTool.capTicks(args);
        String text = com.neko.mcbot.server.ServerToolDispatcher.acceptText(
                moveTool, args, "j1", cap);
        String prefix = com.neko.mcbot.agentcore.loop.ToolExecutor.ToolOutcome.ACCEPTED_PREFIX;
        boolean 前缀对 = text.startsWith(prefix);
        boolean 教学齐 = text.contains("还没有结果") && text.contains("别猜")
                && text.contains("别等") && text.contains("j1");
        boolean 秒数按帽 = text.contains(String.valueOf(cap / 20));

        // ③ 超时口径：客户端等待上限必须**严格大于**服务端帽，否则 TIMEOUT 是"客户端先跑了"
        long clientWait = com.neko.mcbot.agentcore.loop.PendingJobs.jobTimeoutMs(cap);
        boolean 客户端等更久 = clientWait > cap * 50L;
        // 相位映射：叫停/顶替与普通失败必须分得开（客户端补账话术不同）
        boolean 相位对 = "cancelled".equals(com.neko.mcbot.server.ServerToolDispatcher
                .phaseOf(false, "CANCELLED:主人叫停了。"))
                && "superseded".equals(com.neko.mcbot.server.ServerToolDispatcher
                .phaseOf(false, "SUPERSEDED:被顶了。"))
                && "done".equals(com.neko.mcbot.server.ServerToolDispatcher.phaseOf(true, "到了。"))
                && "failed".equals(com.neko.mcbot.server.ServerToolDispatcher.phaseOf(false, "NO_PATH:x"));

        McbotMod.LOG.info("[r2c] ACCEPT={} 条 SYNC={} 条 策略全对={}", accept, sync, policyOk);
        McbotMod.LOG.info("[r2c] 受理文案快照：{}", text);
        McbotMod.LOG.info("[r2c] 前缀对={} 教学齐={} 秒数按帽({}s)={} 客户端等{}ms>帽{}ms={} 相位映射={}",
                前缀对, 教学齐, cap / 20, 秒数按帽, clientWait, cap * 50L, 客户端等更久, 相位对);
        McbotMod.LOG.info("[r2c] 判读基准：策略全对=true 且 前缀对/教学齐/秒数按帽/客户端等更久/相位映射 五 true。"
                + "**本场景只验策略与契约**——job 往返（受理→PARK→事件→续跑）在 agent-core 单测里；"
                + "真机对接看 [brain] job 受理/结束 与 [brain] park= 日志。");
        f0LifecycleScenario(cp);
    }

    private static void f0LifecycleScenario(CompanionPlayer cp) {
        var summon = McbotMod.summonService();
        var scheduler = McbotMod.scheduler();
        var waitArgs = new JsonObject();
        waitArgs.addProperty("seconds", 30);
        var waiting = McbotMod.toolRegistry().get("wait").runAsync(cp, waitArgs, scheduler);
        String name = cp.getGameProfile().name();
        summon.dismiss(null, name);
        ServerTool.Result stopped = waiting.getNow(null);
        boolean dismissed = summon.roster().byName(name) == null
                && !scheduler.busy(cp.getUUID())
                && stopped != null && stopped.feedback().startsWith("CANCELLED:");
        summon.summon(null, name);
        var entry = summon.roster().byName(name);
        ServerPlayer restored = entry == null ? null : cp.level().getServer()
                .getPlayerList().getPlayer(entry.uuid());
        boolean summoned = restored instanceof CompanionPlayer;
        boolean shutdownCleared = false;
        if (restored instanceof CompanionPlayer replacement) {
            var next = McbotMod.toolRegistry().get("wait").runAsync(replacement, waitArgs, scheduler);
            scheduler.cancelAll("验收停服清理。");
            var cancelled = next.getNow(null);
            shutdownCleared = !scheduler.busy(replacement.getUUID())
                    && cancelled != null && cancelled.feedback().startsWith("CANCELLED:");
        }
        McbotMod.LOG.info("[f0-world] 遣散取消任务={} 再召唤成功={} 停服槽清空={}",
                dismissed, summoned, shutdownCleared);
        Path stopFlag = FabricLoader.getInstance().getGameDir().resolve("mcbot/autotest-stop.flag");
        if (Files.exists(stopFlag)) {
            try {
                Files.delete(stopFlag);
                McbotMod.LOG.info("AUTOTEST 完成，按开发标记正常停服。");
                cp.level().getServer().halt(false);
            } catch (java.io.IOException failure) {
                McbotMod.LOG.error("AUTOTEST 停服标记处理失败", failure);
            }
        }
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
