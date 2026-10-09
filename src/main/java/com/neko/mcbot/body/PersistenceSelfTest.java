package com.neko.mcbot.body;

import com.mojang.authlib.GameProfile;
import com.neko.mcbot.McbotMod;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.phys.Vec3;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

/** Separate seed/verify processes so the ordinary action harness cannot overwrite the samples. */
final class PersistenceSelfTest {
    private static final String MARKER = "persistence-fixture-v1";
    private static final Fixture[] FIXTURES = {
            new Fixture("persist_over", Level.OVERWORLD, new BlockPos(37, 90, -43), 71.0F, -19.0F),
            new Fixture("persist_nether", Level.NETHER, new BlockPos(53, 90, 29), -113.0F, 24.0F)
    };

    private PersistenceSelfTest() {
    }

    static boolean runIfRequested(MinecraftServer server) {
        Path flags = FabricLoader.getInstance().getGameDir().resolve("mcbot");
        Path seed = flags.resolve("autotest-persistence-seed.flag");
        Path verify = flags.resolve("autotest-persistence-verify.flag");
        boolean seedRequested = Files.exists(seed);
        boolean verifyRequested = Files.exists(verify);
        if (!seedRequested && !verifyRequested) {
            return false;
        }
        try {
            Files.deleteIfExists(seed);
            Files.deleteIfExists(verify);
            Path world = server.getWorldPath(LevelResource.ROOT).normalize();
            // This harness deliberately changes terrain/items; reject ordinary worlds even with a stray flag.
            if (!world.getFileName().toString().startsWith("mcbot-persistence-")) {
                throw new IllegalStateException("Requires an isolated mcbot-persistence-* development world");
            }
            if (seedRequested == verifyRequested) {
                throw new IllegalStateException("Choose exactly one persistence phase");
            }
            Path marker = world.resolve("mcbot/persistence-fixture.txt");
            if (seedRequested) {
                if (Files.exists(marker)) {
                    throw new IllegalStateException("Seed already exists; use a fresh development world");
                }
                seed(server);
                Files.createDirectories(marker.getParent());
                Files.writeString(marker, MARKER);
                McbotMod.LOG.info("[f0-persist] SEED PASS; normal shutdown will save the samples");
            } else {
                if (!MARKER.equals(Files.readString(marker))) {
                    throw new IllegalStateException("Missing or incompatible seed");
                }
                boolean passed = true;
                for (Fixture fixture : FIXTURES) {
                    passed &= verify(server, fixture);
                }
                if (passed) {
                    for (Fixture fixture : FIXTURES) {
                        passed &= verifyResummon(server, fixture);
                    }
                }
                McbotMod.LOG.info("[f0-persist] VERIFY {}", passed ? "PASS" : "FAIL");
            }
        } catch (Exception failure) {
            McbotMod.LOG.error("[f0-persist] FAILED", failure);
        } finally {
            McbotMod.LOG.info("[f0-persist] Normal shutdown requested; no console stdin used");
            server.halt(false);
        }
        return true;
    }

    private static void seed(MinecraftServer server) {
        for (Fixture fixture : FIXTURES) {
            var service = McbotMod.summonService();
            if (service.roster().byName(fixture.name()) != null) {
                throw new IllegalStateException("Fixture companion already exists");
            }
            service.summon(null, fixture.name());
            ServerPlayer player = server.getPlayerList().getPlayer(SummonService.offlineUuid(fixture.name()));
            if (!(player instanceof CompanionPlayer)) {
                throw new IllegalStateException("Fixture summon failed");
            }
            var level = server.getLevel(fixture.dimension());
            if (level == null) {
                throw new IllegalStateException("Fixture dimension unavailable");
            }
            for (int dx = -2; dx <= 2; dx++) {
                for (int dz = -2; dz <= 2; dz++) {
                    BlockPos feet = fixture.feet().offset(dx, 0, dz);
                    level.setBlockAndUpdate(feet.below(), Blocks.BEDROCK.defaultBlockState());
                    for (int dy = 0; dy <= 3; dy++) {
                        level.setBlockAndUpdate(feet.above(dy), Blocks.AIR.defaultBlockState());
                    }
                }
            }
            if (!player.teleportTo(level, fixture.position().x, fixture.position().y, fixture.position().z,
                    Set.of(), fixture.yaw(), fixture.pitch(), true)) {
                throw new IllegalStateException("Fixture teleport failed");
            }
            Inventory inv = player.getInventory();
            inv.clearContent();
            for (int slot = 0; slot < inv.getContainerSize(); slot++) {
                inv.setItem(slot, expectedItem(slot));
            }
            inv.setSelectedSlot(4);
            boolean valid = bodyMatches(player, fixture);
            logBody("seed", fixture.name(), player, valid);
            if (!valid || !SafeSpawn.isStandable(level, fixture.feet())) {
                throw new IllegalStateException("Seed state is not valid");
            }
        }
    }

    private static boolean verify(MinecraftServer server, Fixture fixture) throws java.io.IOException {
        var uuid = SummonService.offlineUuid(fixture.name());
        var entry = McbotMod.summonService().roster().byName(fixture.name());
        ServerPlayer player = server.getPlayerList().getPlayer(uuid);
        boolean identity = entry != null && entry.uuid().equals(uuid)
                && entry.ownerUuid().equals(CompanionRoster.NO_OWNER)
                && player instanceof CompanionPlayer cp && cp.ownerUuid().equals(entry.ownerUuid());

        // Read the actual UUID .dat before shutdown can replace it with the restored body.
        Path data = server.getWorldPath(LevelResource.PLAYER_DATA_DIR).resolve(uuid + ".dat");
        var tag = NbtIo.readCompressed(data, NbtAccounter.unlimitedHeap());
        boolean disk;
        try (var problems = new ProblemReporter.ScopedCollector(McbotMod.LOG)) {
            var input = TagValueInput.create(problems, server.registryAccess(), tag);
            var saved = input.read(ServerPlayer.SavedPosition.MAP_CODEC).orElse(ServerPlayer.SavedPosition.EMPTY);
            var probe = new CompanionPlayer(server, server.overworld(), new GameProfile(uuid, fixture.name()),
                    ClientInformation.createDefault(), CompanionRoster.NO_OWNER);
            probe.load(input);
            disk = saved.dimension().filter(fixture.dimension()::equals).isPresent()
                    && saved.position().filter(p -> p.distanceToSqr(fixture.position()) < 0.000001).isPresent()
                    && saved.rotation().filter(r -> r.x == fixture.yaw() && r.y == fixture.pitch()).isPresent()
                    && inventoryMatches(probe.getInventory());
            McbotMod.LOG.info("[f0-persist] disk name={} uuid={} dimension={} position={} inventory={} matches={}",
                    fixture.name(), uuid, saved.dimension(), saved.position(),
                    inventorySummary(probe.getInventory()), disk);
        }
        boolean body = player != null && bodyMatches(player, fixture);
        if (player != null) {
            logBody("restored", fixture.name(), player, body);
        }
        McbotMod.LOG.info("[f0-persist] result name={} identity={} disk={} body={} position={} rotation={} inventory={}",
                fixture.name(), identity, disk, body,
                player != null && positionMatches(player, fixture),
                player != null && player.getYRot() == fixture.yaw() && player.getXRot() == fixture.pitch(),
                player != null && inventoryMatches(player.getInventory()));
        return identity && disk && body;
    }

    private static boolean positionMatches(ServerPlayer player, Fixture fixture) {
        return player.level().dimension().equals(fixture.dimension())
                && player.position().distanceToSqr(fixture.position()) < 0.000001;
    }

    private static boolean verifyResummon(MinecraftServer server, Fixture fixture) {
        var service = McbotMod.summonService();
        var uuid = SummonService.offlineUuid(fixture.name());
        service.dismiss(null, fixture.name());
        service.summon(null, fixture.name());
        ServerPlayer player = server.getPlayerList().getPlayer(uuid);
        var spawn = server.overworld().getRespawnData().pos();
        Vec3 expected = new Vec3(spawn.getX() + 0.5, spawn.getY(), spawn.getZ() + 0.5);
        boolean passed = player instanceof CompanionPlayer
                && player.level() == server.overworld()
                && player.position().distanceToSqr(expected) < 0.000001
                && inventoryMatches(player.getInventory());
        McbotMod.LOG.info("[f0-persist] resummon name={} sameUuid={} freshSpawn={} inventory={} matches={}",
                fixture.name(), player != null && player.getUUID().equals(uuid),
                player != null && player.level() == server.overworld()
                        && player.position().distanceToSqr(expected) < 0.000001,
                player != null && inventoryMatches(player.getInventory()), passed);
        // Leave the fixture unchanged for another independent process restart.
        if (player != null) {
            player.teleportTo(server.getLevel(fixture.dimension()),
                    fixture.position().x, fixture.position().y, fixture.position().z,
                    Set.of(), fixture.yaw(), fixture.pitch(), true);
        }
        return passed;
    }

    private static boolean bodyMatches(ServerPlayer player, Fixture fixture) {
        return player.getUUID().equals(SummonService.offlineUuid(fixture.name()))
                && positionMatches(player, fixture)
                && player.getYRot() == fixture.yaw() && player.getXRot() == fixture.pitch()
                && inventoryMatches(player.getInventory());
    }

    private static boolean inventoryMatches(Inventory inventory) {
        boolean matches = inventory.getSelectedSlot() == 4;
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            matches &= ItemStack.matches(inventory.getItem(slot), expectedItem(slot));
        }
        return matches;
    }

    private static ItemStack expectedItem(int slot) {
        return switch (slot) {
            case 0 -> new ItemStack(Items.COBBLESTONE, 23);
            case 4 -> {
                var pick = new ItemStack(Items.IRON_PICKAXE);
                pick.setDamageValue(17);
                yield pick;
            }
            case 13 -> new ItemStack(Items.OAK_LOG, 11);
            case 27 -> new ItemStack(Items.DIAMOND, 3);
            case 38 -> new ItemStack(Items.GOLDEN_CHESTPLATE);
            case 40 -> new ItemStack(Items.TORCH, 7);
            default -> ItemStack.EMPTY;
        };
    }

    private static String inventorySummary(Inventory inventory) {
        StringBuilder text = new StringBuilder("selected=").append(inventory.getSelectedSlot());
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (!stack.isEmpty()) {
                text.append("; ").append(slot).append('=')
                        .append(net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem()))
                        .append('x').append(stack.getCount()).append("/damage=").append(stack.getDamageValue());
            }
        }
        return text.toString();
    }

    private static void logBody(String phase, String name, ServerPlayer player, boolean matches) {
        McbotMod.LOG.info("[f0-persist] {} name={} uuid={} dimension={} position={} yaw={} pitch={} inventory={} matches={}",
                phase, name, player.getUUID(), player.level().dimension().identifier(), player.position(),
                player.getYRot(), player.getXRot(), inventorySummary(player.getInventory()), matches);
    }

    private record Fixture(String name, ResourceKey<Level> dimension, BlockPos feet, float yaw, float pitch) {
        Vec3 position() {
            return new Vec3(feet.getX() + 0.5, feet.getY(), feet.getZ() + 0.5);
        }
    }
}
