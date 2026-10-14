package com.neko.mcbot.body;

import net.minecraft.world.phys.AABB;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BodyPickupTest {
    private static final class Body implements BodyPickup.Access {
        boolean active = true, allowed = true, loaded = true;
        AABB bounds = new AABB(0, 90, 0, .6, 91.8, .6);
        AABB queried;
        AABB checked;
        final List<BodyPickup.Item> items = new ArrayList<>();
        @Override public boolean active() { return active; }
        @Override public boolean allowed() { return allowed; }
        @Override public AABB bounds() { return bounds; }
        @Override public boolean loaded(AABB region) { checked = region; return loaded; }
        @Override public List<BodyPickup.Item> items(AABB region) { queried = region; return items; }
    }

    @Test void idleAndWorkingBodiesTouchLocalItemsWithoutACollectCallOrTaskSlot() {
        var body = new Body();
        int[] touches = {0};
        body.items.add(() -> touches[0]++);
        BodyPickup.tick(body);
        BodyPickup.tick(body);
        assertEquals(2, touches[0]);
        assertEquals(body.bounds.inflate(1, .5, 1), body.queried);
        assertEquals(body.queried, body.checked);
    }

    @Test void eachTickUsesTheCurrentBodyBoundsRatherThanThePreviousWorkTarget() {
        var body = new Body();
        BodyPickup.tick(body);
        body.bounds = body.bounds.move(8, -2, 3);
        BodyPickup.tick(body);
        assertEquals(body.bounds.inflate(1, .5, 1), body.queried);
    }

    @Test void inactiveAndDisallowedBodiesDoNotQueryOrTouchItems() {
        for (boolean inactive : List.of(true, false)) {
            var body = new Body();
            body.active = !inactive;
            body.allowed = inactive;
            body.items.add(() -> fail("must not touch"));
            BodyPickup.tick(body);
            assertNull(body.checked);
            assertNull(body.queried);
        }
    }

    @Test void unloadedTouchNeighborhoodNeverQueriesEntitiesOrForcesChunks() {
        var body = new Body();
        body.loaded = false;
        body.items.add(() -> fail("must not touch"));
        BodyPickup.tick(body);
        assertNotNull(body.checked);
        assertNull(body.queried);
    }

    @Test void delayedOrFullContactCanBeRetriedByTheNextBodyTickWithoutInventingInventory() {
        var body = new Body();
        int[] contacts = {0}, inventory = {0};
        body.items.add(() -> {
            if (++contacts[0] == 3) inventory[0]++;
        });
        BodyPickup.tick(body);
        BodyPickup.tick(body);
        assertEquals(0, inventory[0]);
        BodyPickup.tick(body);
        assertEquals(1, inventory[0]);
    }
}
