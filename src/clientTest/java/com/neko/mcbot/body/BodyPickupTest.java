package com.neko.mcbot.body;

import com.neko.mcbot.server.ActionPermissions;
import net.minecraft.world.phys.AABB;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

class BodyPickupTest {
    private static final class Body implements BodyPickup.Access {
        boolean active = true, allowed = true, loaded = true;
        AABB bounds = new AABB(0, 90, 0, .6, 91.8, .6);
        AABB queried;
        AABB checked;
        AABB orbRegion;
        int delay;
        int points;
        int orbContacts;
        int remainingOrbs;
        BooleanSupplier permission = () -> allowed;
        final List<BodyPickup.Contact> items = new ArrayList<>();
        @Override public boolean active() { return active; }
        @Override public boolean allowed() { return permission.getAsBoolean(); }
        @Override public int experienceDelay() { return delay; }
        @Override public void experienceDelay(int ticks) { delay = ticks; }
        @Override public AABB bounds() { return bounds; }
        @Override public boolean loaded(AABB region) { checked = region; return loaded; }
        @Override public List<BodyPickup.Contact> items(AABB region) { queried = region; return items; }
        @Override public BodyPickup.Contact experienceOrb(AABB region) {
            orbRegion = region;
            if (remainingOrbs == 0) return null;
            // Controlled native boundary: the real ExperienceOrb decides XP and sets takeXpDelay.
            return () -> {
                orbContacts++;
                assertEquals(0, delay);
                delay = 2;
                remainingOrbs--;
                points += 3;
            };
        }
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
        assertEquals(body.queried, body.orbRegion);
    }

    @Test void eachTickUsesTheCurrentBodyBoundsRatherThanThePreviousWorkTarget() {
        var body = new Body();
        BodyPickup.tick(body);
        body.bounds = body.bounds.move(8, -2, 3);
        BodyPickup.tick(body);
        assertEquals(body.bounds.inflate(1, .5, 1), body.queried);
        assertEquals(body.queried, body.orbRegion);
    }

    @Test void inactiveAndDisallowedBodiesDoNotQueryOrTouchItems() {
        for (boolean inactive : List.of(true, false)) {
            var body = new Body();
            body.active = !inactive;
            body.allowed = inactive;
            body.remainingOrbs = 1;
            body.items.add(() -> fail("must not touch"));
            BodyPickup.tick(body);
            assertNull(body.checked);
            assertNull(body.queried);
            assertNull(body.orbRegion);
            assertEquals(0, body.points);
        }
    }

    @Test void unloadedTouchNeighborhoodNeverQueriesEntitiesOrForcesChunks() {
        var body = new Body();
        body.loaded = false;
        body.remainingOrbs = 1;
        body.items.add(() -> fail("must not touch"));
        BodyPickup.tick(body);
        assertNotNull(body.checked);
        assertNull(body.queried);
        assertNull(body.orbRegion);
        assertEquals(0, body.points);
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

    @Test void experienceContactContinuesAfterTheNativeCooldownInsteadOfStoppingAfterOneOrb() {
        var body = new Body();
        body.remainingOrbs = 3;
        BodyPickup.tick(body);
        assertEquals(3, body.points);
        assertEquals(2, body.delay);
        BodyPickup.tick(body);
        assertEquals(1, body.delay);
        assertEquals(1, body.orbContacts);
        BodyPickup.tick(body);
        assertEquals(6, body.points);
        BodyPickup.tick(body);
        BodyPickup.tick(body);
        assertEquals(9, body.points);
        assertEquals(3, body.orbContacts);
        assertEquals(0, body.remainingOrbs);
        for (int tick = 0; tick < 10; tick++) BodyPickup.tick(body);
        assertEquals(0, body.delay);
        assertEquals(9, body.points);
    }

    @Test void oneOrbContactPerReadyTickLeavesMergedOrCrowdedExperienceToNativeSettlement() {
        var body = new Body();
        body.remainingOrbs = 100;
        BodyPickup.tick(body);
        assertEquals(1, body.orbContacts);
        assertEquals(99, body.remainingOrbs);
        assertEquals(body.bounds.inflate(1, .5, 1), body.orbRegion);
    }

    @Test void experienceCooldownNeverBlocksOrdinaryItemContact() {
        var body = new Body();
        body.delay = 2;
        body.remainingOrbs = 1;
        int[] items = {0};
        body.items.add(() -> items[0]++);
        BodyPickup.tick(body);
        assertEquals(1, items[0]);
        assertEquals(0, body.orbContacts);
        assertNull(body.orbRegion);
        BodyPickup.tick(body);
        assertEquals(2, items[0]);
        assertEquals(1, body.orbContacts);
    }

    @Test void deniedOrUnloadedContactStillAdvancesTimeWithoutCollectingOrResettingDelay() {
        for (boolean denied : List.of(true, false)) {
            var body = new Body();
            body.allowed = !denied;
            body.loaded = denied;
            body.delay = 2;
            body.remainingOrbs = 1;
            BodyPickup.tick(body);
            assertEquals(1, body.delay);
            BodyPickup.tick(body);
            assertEquals(0, body.delay);
            assertEquals(0, body.orbContacts);
            assertNull(body.orbRegion);
            body.allowed = body.loaded = true;
            BodyPickup.tick(body);
            assertEquals(1, body.orbContacts);
            assertEquals(2, body.delay);
        }
    }

    @Test void inactiveBodyDoesNotAdvanceExperienceOrContactAnything() {
        var body = new Body();
        body.active = false;
        body.delay = 2;
        body.remainingOrbs = 1;
        BodyPickup.tick(body);
        assertEquals(2, body.delay);
        assertEquals(0, body.orbContacts);
        assertNull(body.orbRegion);
    }

    @Test void readOnlyAuthorityBlocksExperienceUntilTheOriginalTaskEnds() {
        var body = new Body();
        var permissions = new ActionPermissions();
        UUID owner = UUID.randomUUID(), companion = UUID.randomUUID();
        String dimension = "minecraft:overworld";
        body.permission = () -> permissions.passivePickupAllowed(owner, companion, dimension, body);
        body.remainingOrbs = 3;
        BodyPickup.tick(body);
        assertEquals(3, body.points);
        permissions.begin(new ActionPermissions.Context(owner, companion, dimension, 1, true), body);
        for (int tick = 0; tick < 5; tick++) BodyPickup.tick(body);
        assertEquals(3, body.points);
        assertEquals(0, body.delay);
        assertTrue(permissions.end(owner, 1));
        BodyPickup.tick(body);
        assertEquals(6, body.points);
        permissions.begin(new ActionPermissions.Context(owner, companion, dimension, 2, false), body);
        BodyPickup.tick(body);
        BodyPickup.tick(body);
        assertEquals(9, body.points);
    }

    @Test void deathOrPermissionChangeDuringItemContactDoesNotAlsoCollectExperience() {
        for (boolean death : List.of(true, false)) {
            var body = new Body();
            body.remainingOrbs = 1;
            body.items.add(() -> {
                if (death) body.active = false;
                else body.allowed = false;
            });
            BodyPickup.tick(body);
            assertEquals(0, body.orbContacts);
            assertNull(body.orbRegion);
        }
    }
}
