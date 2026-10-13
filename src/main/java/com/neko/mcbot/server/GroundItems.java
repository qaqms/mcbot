package com.neko.mcbot.server;

import com.google.gson.JsonObject;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.phys.AABB;

/** Pickup reach follows the unmounted vanilla player's touch box, not block reach. */
public final class GroundItems {
    private GroundItems() { }

    public static boolean touches(AABB body, AABB item) {
        return body.inflate(1, .5, 1).intersects(item);
    }

    public static JsonObject describe(ItemEntity entity) {
        var data = new JsonObject();
        data.addProperty("entity_id", entity.getId());
        data.addProperty("target_uuid", entity.getUUID().toString());
        String id = BuiltInRegistries.ITEM.getKey(entity.getItem().getItem()).toString();
        data.addProperty("item", com.neko.mcbot.common.WireSize.truncateToBytes(id, 128));
        data.addProperty("item_id_truncated", com.neko.mcbot.common.WireSize.utf8Bytes(id) > 128);
        data.addProperty("count", entity.getItem().getCount());
        data.addProperty("x", entity.getX());
        data.addProperty("y", entity.getY());
        data.addProperty("z", entity.getZ());
        data.addProperty("pickup_delayed", entity.hasPickUpDelay());
        return data;
    }
}
