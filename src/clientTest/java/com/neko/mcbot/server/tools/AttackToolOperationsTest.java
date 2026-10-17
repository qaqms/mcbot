package com.neko.mcbot.server.tools;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.neko.mcbot.server.BlockActionFixtures;
import com.neko.mcbot.server.EntityAttack;
import com.neko.mcbot.server.ActionPermissions;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class AttackToolOperationsTest {
    @Test void dynamicApproachRequiresAnExplicitBooleanAndKeepsTheOriginalHitAndTimeCaps() {
        assertNotNull(AttackTool.arguments(json("{\"entity_id\":\"hostile_nearby\",\"approach\":true,\"max_hits\":2}")));
        for (String invalid : new String[]{"\"true\"", "1", "null", "[]"})
            assertNull(AttackTool.arguments(json("{\"entity_id\":\"hostile_nearby\",\"approach\":" + invalid + "}")));
        assertEquals(400, new AttackTool().capTicks(json("{\"approach\":true}")));
    }
    @BeforeAll static void registriesOnly() { BlockActionFixtures.bootstrap(); }
    private static JsonObject json(String text) { return JsonParser.parseString(text).getAsJsonObject(); }
    private final String uuid = UUID.randomUUID().toString();

    @Test void fixedScopeRejectsFollowingTargetOrBodyOutsideOriginalThreeDimensionalSphere() {
        var anchor = net.minecraft.core.BlockPos.ZERO;
        var origin = new net.minecraft.world.phys.Vec3(.5, 0, .5);
        assertTrue(AttackTool.withinApproachScope(anchor, origin, origin.add(6.5, 0, 0)));
        assertFalse(AttackTool.withinApproachScope(anchor, origin, origin.add(6.501, 0, 0)));
        assertFalse(AttackTool.withinApproachScope(anchor, origin.add(5, 5, 0), origin.add(5, 5, 0)));
        assertFalse(AttackTool.withinApproachScope(anchor, origin.add(7, 0, 0), origin.add(6, 0, 0)));
        assertFalse(AttackTool.withinApproachScope(anchor, origin, new net.minecraft.world.phys.Vec3(Double.NaN, 0, 0)));
        assertFalse(AttackTool.arguments(json("{\"entity_id\":\"hostile_nearby\"}")).approach());
    }

    @Test void explicitIdentityAndNearbySelectorHaveStrictDistinctContracts() {
        var input = AttackTool.arguments(json("{\"entity_id\":47,\"target_uuid\":\"" + uuid + "\",\"max_hits\":10}"));
        assertNotNull(input);
        assertFalse(input.nearby());
        assertEquals(UUID.fromString(uuid), input.uuid());
        assertEquals(10, input.maxHits());
        input = AttackTool.arguments(json("{\"entity_id\":\"hostile_nearby\"}"));
        assertNotNull(input);
        assertTrue(input.nearby());
        assertEquals(1, input.maxHits());
        assertNull(AttackTool.arguments(json("{\"entity_id\":47}")));
        assertNull(AttackTool.arguments(json("{\"entity_id\":\"hostile_nearby\",\"target_uuid\":\"" + uuid + "\"}")));
        assertNull(AttackTool.arguments(json("{\"entity_id\":\"hostile_nearby\",\"authorization_id\":\"proposal\"}")));
    }

    @Test void invalidIdBudgetUuidApprovalAndExtraneousFieldsAreRejected() {
        for (String value : new String[]{"0", "-1", "1.5", "2147483648", "\"47\"", "null", "true", "{}", "[]", "\"nearby\""}) {
            assertNull(AttackTool.arguments(json("{\"entity_id\":" + value + ",\"target_uuid\":\"" + uuid + "\"}")), value);
        }
        for (String value : new String[]{"0", "11", "2.1", "\"2\"", "false", "null", "1e100"}) {
            assertNull(AttackTool.arguments(json("{\"entity_id\":\"hostile_nearby\",\"max_hits\":" + value + "}")), value);
        }
        for (String value : new String[]{"\"1-1-1-1-1\"", "\"bad\"", "null", "47", "true"}) {
            assertNull(AttackTool.arguments(json("{\"entity_id\":47,\"target_uuid\":" + value + "}")));
        }
        for (String value : new String[]{"\"\"", "\"" + "a".repeat(65) + "\"", "true", "null"}) {
            assertNull(AttackTool.arguments(json("{\"entity_id\":47,\"target_uuid\":\"" + uuid
                    + "\",\"authorization_id\":" + value + "}")));
        }
        assertNull(AttackTool.arguments(json("{\"entity_id\":\"hostile_nearby\",\"read_only\":false}")));
        assertNull(AttackTool.arguments(json("{\"entity_id\":\"hostile_nearby\",\"x\":0}")));
    }

    @Test void fullCooldownAndVictimRecoveryAreBothRequired() {
        assertTrue(AttackTool.ready(1, 10, false));
        for (float charge : new float[]{0, .99f, Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY}) {
            assertFalse(AttackTool.ready(charge, 0, false));
        }
        assertFalse(AttackTool.ready(1, 11, false));
        assertFalse(AttackTool.ready(1, 0, true));
        assertEquals(400, new AttackTool().capTicks(new JsonObject()));
        assertEquals(com.neko.mcbot.server.ServerTool.Acceptance.ACCEPT, new AttackTool().acceptanceMode());
    }

    @Test void spearsAndMaceCannotUseOrdinarySingleTargetNativeMelee() {
        assertTrue(AttackTool.supportedWeapon(ItemStack.EMPTY));
        assertTrue(AttackTool.supportedWeapon(new ItemStack(Items.IRON_SWORD)));
        assertTrue(AttackTool.supportedWeapon(new ItemStack(Items.IRON_AXE)));
        assertFalse(AttackTool.supportedWeapon(new ItemStack(Items.WOODEN_SPEAR)));
        assertFalse(AttackTool.supportedWeapon(new ItemStack(Items.DIAMOND_SPEAR)));
        assertFalse(AttackTool.supportedWeapon(new ItemStack(Items.MACE)));
    }

    @Test void scanFeedbackAndStructuredTargetCarrySameStableIdentityWithoutNames() {
        var target = new EntityAttack.Target(47, UUID.fromString(uuid), "minecraft:zombie",
                true, false, false, true, 20, 0);
        var data = ScanAreaTool.targetData(target);
        String line = ScanAreaTool.targetLine(target);
        assertEquals(47, data.get("entity_id").getAsInt());
        assertEquals(uuid, data.get("target_uuid").getAsString());
        assertTrue(line.contains("entity_id=47"));
        assertTrue(line.contains(uuid));
        assertFalse(data.get("requires_confirmation").getAsBoolean());
        target = new EntityAttack.Target(47, UUID.fromString(uuid), "a".repeat(300),
                false, true, true, true, 20, 0);
        data = ScanAreaTool.targetData(target);
        assertTrue(data.get("protected").getAsBoolean());
        assertTrue(data.get("requires_confirmation").getAsBoolean());
        assertEquals(128, data.get("type").getAsString().length());
        assertTrue(data.get("type_truncated").getAsBoolean());
        assertTrue(ScanAreaTool.targetLine(target).contains("攻击受保护"));
    }

    @Test void serverProposalRequiresExactApprovalAndCannotExpandBudgetOrRenameTarget() {
        var permissions = new ActionPermissions();
        var context = new ActionPermissions.Context(UUID.randomUUID(), UUID.randomUUID(), "world", 3, false);
        permissions.begin(context);
        var target = new EntityAttack.Target(47, UUID.fromString(uuid), "minecraft:pig",
                false, false, false, true, 20, 0);
        var unapproved = new ActionPermissions.Execution(null);
        var proposal = AttackTool.permission(permissions, context, unapproved, target, 2);
        assertTrue(proposal.feedback().startsWith("NEED_CONFIRM:"));
        String token = proposal.data().get("authorization_id").getAsString();
        String summary = proposal.data().get("authorization_summary").getAsString();
        assertTrue(summary.contains(uuid));
        assertTrue(summary.contains("最多挥击 2 次"));
        assertNull(permissions.take(context, token, 47));
        assertTrue(permissions.approve(context, token));
        var approved = new ActionPermissions.Execution(permissions.take(context, token, 47));
        assertNull(AttackTool.permission(permissions, context, approved, target, 2));
        assertTrue(AttackTool.permission(permissions, context, approved, target, 3).feedback().startsWith("NEED_CONFIRM:"));
        var renamed = new EntityAttack.Target(47, UUID.fromString(uuid), "minecraft:pig",
                false, true, false, true, 20, 0);
        assertTrue(AttackTool.permission(permissions, context, approved, renamed, 2).feedback().startsWith("NEED_CONFIRM:"));
    }

    @Test void approvalBindsApproachModeAndOriginalAnchorWithoutUpgradingStationaryApproval() {
        var permissions = new ActionPermissions();
        var context = new ActionPermissions.Context(UUID.randomUUID(), UUID.randomUUID(), "world", 3, false);
        permissions.begin(context);
        var target = new EntityAttack.Target(47, UUID.fromString(uuid), "minecraft:pig",
                false, false, false, true, 20, 0);
        long anchor = new net.minecraft.core.BlockPos(2, 64, 3).asLong();
        var proposal = AttackTool.permission(permissions, context, new ActionPermissions.Execution(null),
                target, 2, true, anchor);
        assertTrue(proposal.data().get("authorization_summary").getAsString().contains("初始6.5格"));
        String token = proposal.data().get("authorization_id").getAsString();
        assertTrue(permissions.approve(context, token));
        var approved = new ActionPermissions.Execution(permissions.take(context, token, 47));
        assertNull(AttackTool.permission(permissions, context, approved, target, 2, true, anchor));
        assertTrue(AttackTool.permission(permissions, context, approved, target, 2, true, anchor + 1)
                .feedback().startsWith("NEED_CONFIRM:"));
        assertTrue(AttackTool.permission(permissions, context, approved, target, 2, false, 0)
                .feedback().startsWith("NEED_CONFIRM:"));
        var stationary = EntityAttack.change(target, 2);
        token = permissions.propose(new ActionPermissions.Scope(context, 47,
                java.util.Map.of(ActionPermissions.key(stationary), stationary)));
        assertTrue(permissions.approve(context, token));
        var original = new ActionPermissions.Execution(permissions.take(context, token, 47));
        assertTrue(AttackTool.permission(permissions, context, original, target, 2, true, anchor)
                .feedback().startsWith("NEED_CONFIRM:"));
    }

    @Test void namedHostileNeedsApprovalButProtectedTargetCannotBeApproved() {
        var permission = new ActionPermissions();
        var context = new ActionPermissions.Context(UUID.randomUUID(), UUID.randomUUID(), "world", 2, false);
        permission.begin(context);
        var none = new ActionPermissions.Execution(null);
        var target = new EntityAttack.Target(47, UUID.fromString(uuid), "minecraft:zombie",
                true, false, false, true, 20, 0);
        assertNull(AttackTool.permission(permission, context, none, target, 1));
        target = new EntityAttack.Target(47, UUID.fromString(uuid), "minecraft:zombie",
                true, true, false, true, 20, 0);
        assertTrue(AttackTool.permission(permission, context, none, target, 1).feedback().startsWith("NEED_CONFIRM:"));
        target = new EntityAttack.Target(47, UUID.fromString(uuid), "minecraft:wolf",
                false, false, true, true, 20, 0);
        assertTrue(AttackTool.permission(permission, context, none, target, 1).feedback().startsWith("DENIED:"));
        target = new EntityAttack.Target(47, UUID.fromString(uuid), "a".repeat(12000),
                false, false, false, true, 20, 0);
        assertTrue(AttackTool.permission(permission, context, none, target, 1).feedback().startsWith("DENIED:"));
    }

    @Test void autonomousModeAllowsNeutralAndNamedTargetsButCannotApproveProtectedTargets() {
        var permissions = new ActionPermissions();
        var context = new ActionPermissions.Context(UUID.randomUUID(), UUID.randomUUID(), "world", 4, false, true);
        permissions.begin(context);
        var none = new ActionPermissions.Execution(null);
        var neutral = new EntityAttack.Target(47, UUID.fromString(uuid), "minecraft:pig",
                false, true, false, true, 20, 0);
        assertNull(AttackTool.permission(permissions, context, none, neutral, 2));
        assertFalse(ScanAreaTool.targetData(neutral, true).get("requires_confirmation").getAsBoolean());
        assertFalse(ScanAreaTool.targetLine(neutral, true).contains("需确认"));
        var protectedTarget = new EntityAttack.Target(47, UUID.fromString(uuid), "minecraft:wolf",
                false, true, true, true, 20, 0);
        assertTrue(AttackTool.permission(permissions, context, none, protectedTarget, 2).feedback().startsWith("DENIED:"));
        assertTrue(ScanAreaTool.targetLine(protectedTarget, true).contains("受保护"));
        assertTrue(ScanAreaTool.targetData(protectedTarget, true).get("protected").getAsBoolean());
        permissions.end(context.owner(), 4);
        assertNotNull(AttackTool.permission(permissions, context, none, neutral, 2),
                "ended autonomous tasks cannot authorize later attacks");
    }

    @Test void readOnlySelectionDoesNotGrantNeutralAttackEvenWhenAutonomyWasRequested() {
        var permissions = new ActionPermissions();
        var context = new ActionPermissions.Context(UUID.randomUUID(), UUID.randomUUID(), "world", 5, true, true);
        permissions.begin(context);
        var target = new EntityAttack.Target(47, UUID.fromString(uuid), "minecraft:pig",
                false, false, false, true, 20, 0);
        assertNotNull(AttackTool.permission(permissions, context, new ActionPermissions.Execution(null), target, 2));
        assertFalse(ActionPermissions.allowed(context, "attack", new JsonObject()));
    }
}
