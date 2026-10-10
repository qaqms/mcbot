package com.neko.mcbot.server;

import com.neko.mcbot.server.AttackFixtures.Access;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class EntityAttackTest {
    @BeforeAll static void registriesOnly() { BlockActionFixtures.bootstrap(); }

    @Test void cooldownWaitsAndOneHitIsNotAClaimOfDeath() {
        var access = new Access();
        access.ready = false;
        var attack = new EntityAttack(access, 1);
        assertNull(attack.preflight());
        assertNull(attack.tick());
        assertEquals(0, access.strikes);
        access.ready = true;
        var result = attack.tick();
        assertTrue(result.ok());
        assertEquals(1, result.data().get("strikes").getAsInt());
        assertEquals(3, result.data().get("observed_health_loss").getAsFloat());
        assertFalse(result.data().get("target_dead").getAsBoolean());
        assertEquals(17, result.data().get("health").getAsFloat());
        assertSame(result, attack.tick());
        assertSame(result, attack.interrupt("CANCELLED:late"));
        assertEquals(1, access.strikes);
    }

    @Test void finiteHitBudgetStopsDespiteLivingTargetAndNativeWearIsAccepted() {
        var access = new Access();
        access.inventory.setItem(40, new ItemStack(Items.TORCH, 7));
        access.held().set(DataComponents.CUSTOM_NAME, Component.literal("kept"));
        access.effect = () -> {
            access.health--;
            access.held().setDamageValue(access.held().getDamageValue() + 1);
        };
        var attack = new EntityAttack(access, 3);
        assertNull(attack.tick());
        assertNull(attack.tick());
        var result = attack.tick();
        assertTrue(result.ok());
        assertEquals(3, access.strikes);
        assertEquals(3, access.held().getDamageValue());
        assertEquals("kept", access.held().get(DataComponents.CUSTOM_NAME).getString());
        assertEquals(7, access.inventory.getItem(40).getCount());
        assertFalse(result.data().get("target_dead").getAsBoolean());
    }

    @Test void observedDeathEndsBeforeBudgetButDoesNotCollectOrSwitchToSplitEntities() {
        var access = new Access();
        access.effect = () -> { access.health = 0; access.alive = false; };
        var attack = new EntityAttack(access, 10);
        var result = attack.tick();
        assertTrue(result.ok());
        assertTrue(result.data().get("target_dead").getAsBoolean());
        access.uuid = UUID.randomUUID();
        access.alive = true;
        assertSame(result, attack.tick());
        assertEquals(1, access.strikes);
        assertEquals(1, access.inventory.getItem(0).getCount());
    }

    @Test void externalDeathIsOnlyAnObservationAndAlreadyDeadTargetDoesNotStrike() {
        var access = new Access();
        var attack = new EntityAttack(access, 10);
        assertNull(attack.tick());
        access.alive = false;
        access.health = 0;
        var result = attack.tick();
        assertTrue(result.ok());
        assertEquals(1, access.strikes);
        assertEquals(3, result.data().get("observed_health_loss").getAsFloat());
        assertEquals(0, result.data().get("health").getAsFloat());
        var dead = new Access();
        dead.alive = false;
        assertTrue(new EntityAttack(dead, 1).tick().feedback().startsWith("TARGET_LOST:"));
        assertEquals(0, dead.strikes);
    }

    @Test void absorptionLossCountsWithoutRequiringHealthDamage() {
        var access = new Access();
        access.absorption = 5;
        access.effect = () -> access.absorption -= 2;
        var result = new EntityAttack(access, 1).tick();
        assertTrue(result.ok());
        assertEquals(0, result.data().get("observed_health_loss").getAsFloat());
        assertEquals(2, result.data().get("observed_absorption_loss").getAsFloat());
    }

    @Test void noObservedDamageStopsWithoutAutomaticSecondStrike() {
        var access = new Access();
        access.effect = () -> {};
        var attack = new EntityAttack(access, 10);
        var result = attack.tick();
        assertFalse(result.ok());
        assertTrue(result.feedback().startsWith("ATTACK_FAILED:"));
        assertSame(result, attack.tick());
        assertEquals(1, access.strikes);
    }

    @Test void guardsAreRecheckedDuringCooldownAndAfterCompletedEffects() {
        for (String prefix : new String[]{"OUT_OF_REACH:", "OCCLUDED:", "TARGET_LOST:", "UNSAFE_SWEEP:", "DENIED:"}) {
            var access = new Access();
            var attack = new EntityAttack(access, 10);
            assertNull(attack.tick());
            access.guard = new ServerTool.Result(false, prefix + "changed observation", null);
            var result = attack.tick();
            assertFalse(result.ok());
            assertTrue(result.feedback().startsWith(prefix));
            assertEquals(1, result.data().get("strikes").getAsInt());
            assertEquals(1, access.strikes);
        }
    }

    @Test void reusedIdWithDifferentUuidOrDifferentIdCannotContinue() {
        for (boolean changeUuid : new boolean[]{false, true}) {
            var access = new Access();
            var attack = new EntityAttack(access, 10);
            assertNull(attack.preflight());
            if (changeUuid) access.uuid = UUID.randomUUID();
            else access.id++;
            assertTrue(attack.tick().feedback().startsWith("TARGET_LOST:"));
            assertEquals(0, access.strikes);
        }
    }

    @Test void protectedTargetOrUnapprovedProposalNeverStrikes() {
        var access = new Access();
        access.protectedTarget = true;
        assertTrue(new EntityAttack(access, 1).tick().feedback().startsWith("DENIED:"));
        assertEquals(0, access.strikes);
        access = new Access();
        var proposal = new com.google.gson.JsonObject();
        proposal.addProperty("authorization_id", "server-proposal");
        access.permission = new ServerTool.Result(false, "NEED_CONFIRM:exact target", proposal);
        var result = new EntityAttack(access, 1).tick();
        assertEquals("server-proposal", result.data().get("authorization_id").getAsString());
        assertEquals(0, access.strikes);
        assertEquals(0, result.data().get("strikes").getAsInt());
    }

    @Test void externalSelectedSlotCountDamageOrComponentsChangeStopsBeforeStrike() {
        for (int mode = 0; mode < 5; mode++) {
            var access = new Access();
            var attack = new EntityAttack(access, 10);
            assertNull(attack.preflight());
            switch (mode) {
                case 0 -> access.inventory.setSelectedSlot(1);
                case 1 -> access.held().setCount(2);
                case 2 -> access.held().setDamageValue(3);
                case 3 -> access.held().set(DataComponents.CUSTOM_NAME, Component.literal("changed"));
                case 4 -> access.held().remove(DataComponents.WEAPON);
            }
            assertTrue(attack.tick().feedback().startsWith("WRONG_TOOL:"));
            assertEquals(0, access.strikes);
        }
    }

    @Test void resourceConflictKeepsPriorStrikeCountAndStops() {
        var access = new Access();
        var attack = new EntityAttack(access, 10);
        assertNull(attack.tick());
        access.reserved = false;
        var result = attack.tick();
        assertTrue(result.feedback().startsWith("BUSY:"));
        assertEquals(1, access.strikes);
        assertEquals(1, result.data().get("strikes").getAsInt());
    }

    @Test void nativeExceptionMarksLastAttemptUnknownAndCannotReplayAfterInterruption() {
        var access = new Access();
        access.effect = () -> { access.health--; throw new IllegalStateException("native callback"); };
        var attack = new EntityAttack(access, 10);
        assertThrows(IllegalStateException.class, attack::tick);
        var result = attack.interrupt("INTERNAL:stopped");
        assertFalse(result.data().get("observation_known").getAsBoolean());
        assertEquals(1, result.data().get("strikes").getAsInt());
        assertSame(result, attack.tick());
        assertEquals(1, access.strikes);
    }

    @Test void approvalBindsTargetUuidTypeNamingClassificationAndHitBudgetOnce() {
        var access = new Access();
        access.hostile = false;
        var permission = new ActionPermissions();
        var context = new ActionPermissions.Context(UUID.randomUUID(), UUID.randomUUID(), "world", 3, false);
        permission.begin(context);
        var original = access.target();
        var change = EntityAttack.change(original, 2);
        var token = permission.propose(new ActionPermissions.Scope(context, original.id(),
                Map.of(ActionPermissions.key(change), change)));
        assertNotNull(token);
        assertTrue(permission.approve(context, token));
        var execution = new ActionPermissions.Execution(permission.take(context, token, original.id()));
        assertTrue(execution.allows(change));
        assertFalse(execution.allows(EntityAttack.change(original, 3)));
        access.uuid = UUID.randomUUID();
        assertFalse(execution.allows(EntityAttack.change(access.target(), 2)));
        access.uuid = original.uuid();
        access.named = true;
        assertFalse(execution.allows(EntityAttack.change(access.target(), 2)));
        access.named = false;
        access.type = "minecraft:pig";
        assertFalse(execution.allows(EntityAttack.change(access.target(), 2)));
        assertNull(permission.take(context, token, original.id()));
    }

    @Test void cancellationBeforeFirstStrikeHasNoEffectAndIsTerminal() {
        var access = new Access();
        var attack = new EntityAttack(access, 10);
        assertNull(attack.preflight());
        var result = attack.interrupt("CANCELLED:owner");
        assertSame(result, attack.tick());
        assertEquals(0, access.strikes);
        assertEquals(0, result.data().get("strikes").getAsInt());
    }

    @Test void unsupportedBudgetFailsBeforeAccess() {
        assertThrows(IllegalArgumentException.class, () -> new EntityAttack(new Access(), 0));
        assertThrows(IllegalArgumentException.class, () -> new EntityAttack(new Access(), 11));
    }
}
