package com.neko.mcbot.body;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** 名册：哪些 UUID 是同伴、各归属谁。服务器目录 mcbot/companions.json，重启后据此重进。 */
public final class CompanionRoster {

    /** 控制台召唤的占位主人（仅供测试/服主预配；无主人不能玩游戏内功能）。 */
    public static final UUID NO_OWNER = new UUID(0L, 0L);

    private static final Logger LOG = LoggerFactory.getLogger("mcbot/roster");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public record Entry(UUID uuid, String name, UUID ownerUuid, String ownerName) {
    }

    private final Path store;
    private final List<Entry> entries = new ArrayList<>();

    public CompanionRoster(MinecraftServer server) {
        this.store = FabricLoader.getInstance().getGameDir().resolve("mcbot").resolve("companions.json");
    }

    public void load() {
        entries.clear();
        if (!Files.exists(store)) {
            return;
        }
        try {
            String json = Files.readString(store, StandardCharsets.UTF_8);
            List<Entry> loaded = GSON.fromJson(json, new TypeToken<List<Entry>>() {
            }.getType());
            if (loaded != null) {
                entries.addAll(loaded);
            }
        } catch (IOException | RuntimeException e) {
            LOG.error("名册读取失败，按空名册继续: {}", store, e);
        }
    }

    public void save() {
        try {
            Files.createDirectories(store.getParent());
            Files.writeString(store, GSON.toJson(entries), StandardCharsets.UTF_8);
        } catch (IOException e) {
            LOG.error("名册写入失败: {}", store, e);
        }
    }

    public List<Entry> entries() {
        return List.copyOf(entries);
    }

    public Entry byUuid(UUID uuid) {
        return entries.stream().filter(e -> e.uuid().equals(uuid)).findFirst().orElse(null);
    }

    public Entry byName(String name) {
        return entries.stream().filter(e -> e.name().equalsIgnoreCase(name)).findFirst().orElse(null);
    }

    public List<Entry> byOwner(UUID owner) {
        return entries.stream().filter(e -> e.ownerUuid().equals(owner)).toList();
    }

    public void add(Entry entry) {
        entries.add(entry);
    }

    public void remove(Entry entry) {
        entries.remove(entry);
    }
}
