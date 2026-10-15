package com.neko.mcbot.server;

import com.google.gson.JsonObject;
import com.neko.mcbot.body.CompanionPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.phys.AABB;

/** Pickup reach follows the unmounted vanilla player's touch box, not block reach. */
public final class GroundItems {
    private GroundItems() { }

    public static AABB touchBox(AABB body) {
        return body.inflate(1, .5, 1);
    }

    public static boolean touches(AABB body, AABB item) {
        return touchBox(body).intersects(item);
    }

    /** Both passive contact and the compatibility tool use the same native pickup operation. */
    public static int pickup(CompanionPlayer body, ItemEntity item) {
        if (!body.isAlive() || body.isRemoved() || body.isSpectator() || body.isPassenger()
                || body.isSleeping() || !item.isAlive() || item.level() != body.level()
                || !body.level().isLoaded(item.blockPosition())
                || !touches(body.getBoundingBox(), item.getBoundingBox())) return 0;
        int before = item.getItem().getCount();
        int slot = body.getInventory().getSelectedSlot();
        var hand = body.getMainHandItem().copy();
        // Vanilla owns delay, target, inventory capacity, components, animation and discard.
        item.playerTouch(body);
        int left = item.isRemoved() ? 0 : item.getItem().getCount();
        int taken = Math.max(0, before - left);
        body.handPickups().recorded(slot, hand, body.getInventory().getSelectedSlot(), body.getMainHandItem(), taken);
        return taken;
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
