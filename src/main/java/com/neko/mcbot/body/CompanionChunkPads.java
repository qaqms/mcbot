package com.neko.mcbot.body;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;

/**
 * 同伴区块票（R1-S3b）。为什么必须存在：假玩家（FakeConnection）拿不到原版
 * PLAYER_LOADING/PLAYER_SIMULATION 票（09-08 探针实测：同伴自己脚下格 hasChunkAt=false），
 * 而 R1-S2 起搜索把未加载一律读成 UNKNOWN 墙——不持票的同伴在野外等于瘫痪。
 * 参考项目的思路（机制层，非代码）：自定义带超时的票 + 只续不撤 + 每拍服务端驱动。
 *
 * 三个关键设计，每个都对应一类已被省掉的 bug：
 * ① 自定义 TicketType（timeout 票）而非蹭 PLAYER_*：原版玩家票半径=view-distance 整球，
 *    成本失控；我们只要 5×5 的垫子（PAD_RADIUS=2，靠 {@code addTicketWithRadius} 的
 *    level=FULL-radius 语义扩散，字节码坐实）。FLAG 不带 PERSIST → 不落盘，重启零残留。
 * ② 只续不撤：{@code TicketStorage.addTicket} 对同 type 同 level 的已有票走
 *    resetTicksLeft()（字节码坐实，不产生重复票）。于是不需要"撤旧票"代码——
 *    多同伴共用一块垫子时互不抽干；同伴死亡/dismiss/崩溃 = 停续 → 40 tick 自然过期自清。
 * ③ 驱动点必须在 server tick（END_SERVER_TICK 遍历 players），**绝不能挂 entity tick**：
 *    垫子一旦过期，同伴所在 chunk 退出 entity-ticking → 它的 tick 不再跑 → 票永远刷不回来
 *    （自锁死）。放在 scheduler.tick 之前，保证传送后的下一拍 PathTask 先见到票再搜索。
 *
 * 与参考实现的一处刻意分歧：他们"owner 离线即停续（垫子 2s 蒸发）"；我们不设这道闸——
 * mcbot 的卖点是同伴独立跑长活（挖穿山体以分钟计），主人 AFK 不该冻住它。
 * 代价是每同伴常驻 ≤25 chunk，上限=名册同伴数，可控。
 */
public final class CompanionChunkPads {

    /** 垫子半径（chunk）。2 → 5×5。搜索盒(64 格=4 chunk)够得着垫缘+1 拍缓冲；
     *  再远的读成 UNKNOWN → PARTIAL/分段推进 → 人动垫动，天然有界。 */
    private static final int PAD_RADIUS = 2;
    /** 票寿命（tick）。每拍续一次的话 40 拍=2 秒容忍度（服务端卡死也会自愈）。 */
    private static final int TICKET_TIMEOUT_TICKS = 40;

    /** 加载+仿真都要（要能读方块，也要让同伴自己继续被 tick）；不带 PERSIST。 */
    private static final TicketType PAD = new TicketType(
            TICKET_TIMEOUT_TICKS,
            TicketType.FLAG_LOADING | TicketType.FLAG_SIMULATION);

    private CompanionChunkPads() {
    }

    /** 每 END_SERVER_TICK 调一次。成本=每同伴一次 list 扫描+resetTicksLeft，忽略不计。 */
    public static void tick(MinecraftServer server) {
        for (ServerLevel level : server.getAllLevels()) {
            for (ServerPlayer p : level.players()) {
                if (!(p instanceof CompanionPlayer) || p.isRemoved()) {
                    continue;
                }
                level.getChunkSource().addTicketWithRadius(PAD, new ChunkPos(p.blockPosition()), PAD_RADIUS);
            }
        }
    }
}
