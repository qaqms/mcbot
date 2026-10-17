package com.neko.mcbot.server;

import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/** Bounded, nonloading observations shared by queries and action receipts. */
public final class TerrainSnapshots {
    private TerrainSnapshots() { }

    public static JsonObject around(ServerLevel level, BlockPos center, int radius) {
        if (radius < 0 || radius > 64) throw new IllegalArgumentException();
        var chunks = new JsonObject();
        for (int x = center.getX() - radius >> 4; x <= (center.getX() + radius >> 4); x++)
            for (int z = center.getZ() - radius >> 4; z <= (center.getZ() + radius >> 4); z++) {
                var chunk = level.getChunkSource().getChunkNow(x, z);
                chunks.add(x + "," + z, chunk instanceof TerrainVersion version
                        ? new com.google.gson.JsonPrimitive(version.mcbot$terrainStamp()) : JsonNull.INSTANCE);
            }
        var result = new JsonObject();
        result.addProperty("dimension", level.dimension().identifier().toString());
        result.add("chunks", chunks);
        return result;
    }
}
