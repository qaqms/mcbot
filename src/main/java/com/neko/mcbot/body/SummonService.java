package com.neko.mcbot.body;

import com.mojang.authlib.GameProfile;
import com.neko.mcbot.McbotMod;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.players.NameAndId;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.storage.LevelData;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * 同伴生命周期：召唤 / 遣散 / 服务器重启重进 / 停服清理。
 * 入场前读取原版玩家存档，再由 placeNewPlayer 注册身体，连接是 FakeConnection；
 * 名册记录归属，原版玩家存档记录身体，名册本身不备份位置或背包。
 */
public final class SummonService {

    private final MinecraftServer server;
    private final CompanionRoster roster;
    private final Map<UUID, FakeConnection> connections = new HashMap<>();

    public SummonService(MinecraftServer server, CompanionRoster roster) {
        this.server = server;
        this.roster = roster;
    }

    public CompanionRoster roster() {
        return roster;
    }

    /** 召唤。owner 可为 null（控制台/服主预配，名册记 NO_OWNER）。返回给执行者看的文本。 */
    public String summon(ServerPlayer owner, String rawName) {
        String name = rawName.toLowerCase(Locale.ROOT);
        if (!name.matches("[a-z0-9_]{2,16}")) {
            return "名字不合法：需要 2-16 位字母/数字/下划线";
        }
        var playerList = server.getPlayerList();
        if (playerList.getPlayerByName(name) != null) {
            return "名字 " + name + " 正被在线玩家占用";
        }
        if (roster.byName(name) != null) {
            return "已存在同名同伴，请先 /mcbot dismiss " + name;
        }
        if (owner != null && !roster.byOwner(owner.getUUID()).isEmpty()) {
            return "每个主人 v1 限 1 名同伴——先遣散现有的";
        }

        UUID companionUuid = offlineUuid(name);
        GameProfile profile = new GameProfile(companionUuid, name);
        ServerLevel overworld = server.overworld();
        BlockPos where = owner != null
                ? SafeSpawn.findNear(overworld, owner.blockPosition())
                : overworld.getRespawnData().pos();

        CompanionPlayer companion;
        FakeConnection connection = new FakeConnection();
        connections.put(companionUuid, connection);
        try {
            companion = loadCompanion(profile,
                    owner != null ? owner.getUUID() : CompanionRoster.NO_OWNER, where, false);
            companion.resetBodyContinuity();
            companion.setRespawnPosition(
                    new ServerPlayer.RespawnConfig(
                            LevelData.RespawnData.of(overworld.dimension(), where, 0.0F, 0.0F), true),
                    false);
            playerList.placeNewPlayer(connection, companion,
                    CommonListenerCookie.createInitial(profile, false));
            companion.observeHealth(HealthObservation.Phase.JOINED);
        } catch (RuntimeException e) {
            connections.remove(companionUuid);
            connection.closeQuietly();
            McbotMod.LOG.error("同伴读取存档或进场失败", e);
            return "召唤失败：" + e.getMessage();
        }
        // Override persisted game mode/invulnerability only after loading and joining.
        companion.setGameMode(GameType.SURVIVAL);
        companion.setInvulnerable(true); // M4 战斗里程碑再放开伤害
        companion.teleportTo(where.getX() + 0.5, where.getY(), where.getZ() + 0.5);
        companion.observeHealth(HealthObservation.Phase.READY);

        roster.add(new CompanionRoster.Entry(companionUuid, name,
                owner != null ? owner.getUUID() : CompanionRoster.NO_OWNER,
                owner != null ? owner.getGameProfile().name() : "console"));
        roster.save();
        McbotMod.LOG.info("同伴 {} 已召唤 (owner={})", name,
                owner != null ? owner.getGameProfile().name() : "console");
        return "同伴 " + name + " 出现了";
    }

    /** 遣散。requester 可为 null（控制台放行一切）。 */
    public String dismiss(ServerPlayer requester, String rawName) {
        CompanionRoster.Entry entry = roster.byName(rawName);
        if (entry == null) {
            return "名册里没有同伴 " + rawName;
        }
        boolean privileged = requester == null
                || entry.ownerUuid().equals(requester.getUUID())
                || server.getPlayerList().isOp(new NameAndId(
                        requester.getUUID(), requester.getGameProfile().name()));
        if (!privileged) {
            return "你只能遣散自己的同伴";
        }
        roster.remove(entry);
        roster.save();
        McbotMod.scheduler().cancel(entry.uuid(), "同伴已被遣散。");
        ServerPlayer player = server.getPlayerList().getPlayer(entry.uuid());
        if (player != null) {
            if (player instanceof CompanionPlayer companion) companion.cancelBodyBehaviors();
            server.getPlayerList().remove(player);
        }
        FakeConnection connection = connections.remove(entry.uuid());
        if (connection != null) {
            connection.closeQuietly();
        }
        McbotMod.LOG.info("同伴 {} 已遣散", entry.name());
        return "同伴 " + entry.name() + " 离开了";
    }

    /** 服务器启动：名册内的同伴全部重新进场（位置/背包由原版玩家存档恢复）。 */
    public void respawnAllFromRoster() {
        for (CompanionRoster.Entry entry : roster.entries()) {
            if (server.getPlayerList().getPlayerByName(entry.name()) != null) {
                McbotMod.LOG.warn("跳过同伴 {}：名字被占用", entry.name());
                continue;
            }
            GameProfile profile = new GameProfile(entry.uuid(), entry.name());
            FakeConnection connection = null;
            try {
                CompanionPlayer companion = loadCompanion(profile, entry.ownerUuid(),
                        server.overworld().getRespawnData().pos(), true);
                connection = new FakeConnection();
                connections.put(entry.uuid(), connection);
                server.getPlayerList().placeNewPlayer(connection, companion,
                        CommonListenerCookie.createInitial(profile, false));
                companion.observeHealth(HealthObservation.Phase.JOINED);
                companion.setGameMode(GameType.SURVIVAL);
                companion.setInvulnerable(true);
                // .dat 可能停在死角（上次硬杀/被埋/被水淹）：进场后检查落点，不可站就挪
                ServerLevel lvl = companion.level();
                BlockPos here = companion.blockPosition();
                if (companion.isAlive() && !companion.lifecycle().retired() && !SafeSpawn.isStandable(lvl, here)) {
                    BlockPos safe = SafeSpawn.findNear(lvl, here);
                    if (!SafeSpawn.isStandable(lvl, safe)) {
                        safe = SafeSpawn.findNear(lvl, lvl.getRespawnData().pos());
                    }
                    companion.teleportTo(safe.getX() + 0.5, safe.getY(), safe.getZ() + 0.5);
                    McbotMod.LOG.info("同伴 {} 存档落点 {} 不可站立，已挪到 {}",
                            entry.name(), here.toShortString(), safe.toShortString());
                }
                companion.observeHealth(HealthObservation.Phase.READY);
                McbotMod.LOG.info("同伴 {} 随服务器重进", entry.name());
            } catch (RuntimeException e) {
                connections.remove(entry.uuid());
                if (connection != null) {
                    connection.closeQuietly();
                }
                McbotMod.LOG.error("同伴 {} 重进失败", entry.name(), e);
            }
        }
    }

    private CompanionPlayer loadCompanion(GameProfile profile, UUID ownerUuid, BlockPos fallback,
                                          boolean restorePosition) {
        // In 1.21.11 placeNewPlayer no longer loads data; vanilla does this in PrepareSpawnTask.
        var data = server.getPlayerList().loadPlayerData(new NameAndId(profile));
        try (var problems = new ProblemReporter.ScopedCollector(McbotMod.LOG)) {
            var input = data.map(tag -> TagValueInput.create(problems, server.registryAccess(), tag));
            var saved = input.flatMap(value -> value.read(ServerPlayer.SavedPosition.MAP_CODEC))
                    .orElse(ServerPlayer.SavedPosition.EMPTY);
            var savedLevel = saved.dimension().map(server::getLevel);
            ServerLevel level = restorePosition ? savedLevel.orElse(server.overworld()) : server.overworld();
            CompanionPlayer companion = new CompanionPlayer(server, level, profile,
                    ClientInformation.createDefault(), ownerUuid);
            companion.observeHealth(HealthObservation.Phase.CREATED);
            input.ifPresent(companion::load);
            companion.observeHealth(input.isPresent()
                    ? HealthObservation.Phase.LOADED : HealthObservation.Phase.NO_SAVED_DATA);
            Vec3 fallbackPosition = new Vec3(fallback.getX() + 0.5, fallback.getY(), fallback.getZ() + 0.5);
            Vec3 position = restorePosition && savedLevel.isPresent()
                    ? saved.position().orElse(fallbackPosition) : fallbackPosition;
            Vec2 rotation = saved.rotation().orElse(Vec2.ZERO);
            // Explicit summon/fallback positions must be chosen before login packets/world registration.
            companion.snapTo(position, rotation.x, rotation.y);
            companion.observeHealth(HealthObservation.Phase.POSITIONED);
            return companion;
        }
    }

    /** Native death has already settled inventory. Never reload the pre-death playerdata here. */
    public boolean rebuild(CompanionPlayer original) {
        var players = server.getPlayerList();
        if (players.getPlayer(original.getUUID()) != original || !original.lifecycle().rebuilding()
                || roster.byUuid(original.getUUID()) == null) return false;
        ServerPlayer owner = players.getPlayer(original.ownerUuid());
        if (owner == null || !owner.isAlive() || owner.isRemoved()) return false;
        ServerLevel target = owner.level();
        var fresh = new CompanionPlayer(server, target, original.getGameProfile(),
                original.clientInformation(), original.ownerUuid());
        var geometry = com.neko.mcbot.server.StandingGeometry.forPlayer(fresh);
        BlockPos center = owner.blockPosition();
        Vec3 where = null;
        // A fixed local search uses the same loaded, dry standing geometry as normal work.
        search:
        for (int y : new int[]{0, 1, -1}) for (int x = -2; x <= 2; x++) for (int z = -2; z <= 2; z++) {
            BlockPos cell = center.offset(x, y, z);
            var foot = geometry.standing(cell.getX(), cell.getY(), cell.getZ(),
                    com.neko.mcbot.path.TerrainEdits.EMPTY);
            if (foot == null) continue;
            var bounds = geometry.body(cell.getX(), foot.height(), cell.getZ());
            if (!target.noCollision(fresh, bounds)) continue;
            where = new Vec3(cell.getX() + .5, foot.height(), cell.getZ() + .5);
            break search;
        }
        if (where == null) return false;
        // restoreFrom(false) checks this body's level for keepInventory. Settle that rule in
        // the death world before registration at the owner's current level.
        fresh.setServerLevel(original.level());
        try { fresh.restoreFrom(original, false); }
        finally { fresh.setServerLevel(target); }
        fresh.copyRespawnPosition(original);
        fresh.setInvulnerable(original.isInvulnerable());
        fresh.rebuiltFrom(original);
        fresh.snapTo(where, owner.getYRot(), 0);
        // Remove/save the actual corpse before joining the replacement under the same profile UUID.
        players.remove(original);
        FakeConnection old = connections.remove(original.getUUID());
        if (old != null) old.closeQuietly();
        var connection = new FakeConnection();
        connections.put(fresh.getUUID(), connection);
        try {
            players.placeNewPlayer(connection, fresh,
                    CommonListenerCookie.createInitial(fresh.getGameProfile(), false));
            fresh.teleportTo(where.x, where.y, where.z);
            var dispatcher = McbotMod.dispatcher();
            if (dispatcher != null && dispatcher.belongsTo(server)) dispatcher.publishBody(fresh);
        } catch (RuntimeException | Error failure) {
            // Do not replay a partially joined replacement or reload an older inventory snapshot.
            try { fresh.reconstructionFailed(); }
            finally {
                try {
                    if (players.getPlayer(fresh.getUUID()) == fresh) players.remove(fresh);
                } finally {
                    connections.remove(fresh.getUUID(), connection);
                    connection.closeQuietly();
                }
            }
            throw failure;
        }
        McbotMod.LOG.info("[body-lifecycle] rebuilt companion={} old_body={} new_body={}",
                fresh.getUUID(), original.observationBodyId(), fresh.observationBodyId());
        return true;
    }

    /** 停服：把同伴从玩家列表摘除（名册保留）。存档交给原版流程。 */
    public void dismissAllForShutdown() {
        for (UUID uuid : new HashMap<>(connections).keySet()) {
            ServerPlayer player = server.getPlayerList().getPlayer(uuid);
            if (player != null) {
                if (player instanceof CompanionPlayer companion) companion.cancelBodyBehaviors();
                server.getPlayerList().remove(player);
            }
            FakeConnection connection = connections.remove(uuid);
            if (connection != null) {
                connection.closeQuietly();
            }
        }
    }

    /** 主人 → 其在线同伴（可能为 null）。 */
    public ServerPlayer companionOf(UUID ownerUuid) {
        for (CompanionRoster.Entry entry : roster.byOwner(ownerUuid)) {
            ServerPlayer player = server.getPlayerList().getPlayer(entry.uuid());
            if (player != null) {
                return player;
            }
        }
        return null;
    }

    /** 名字 → 同伴 UUID 的确定性推导（与原版离线模式同公式）。 */
    public static UUID offlineUuid(String name) {
        return UUID.nameUUIDFromBytes(("OfflinePlayer:" + name.toLowerCase(Locale.ROOT))
                .getBytes(StandardCharsets.UTF_8));
    }
}
