package com.neko.mcbot.body;

import com.neko.mcbot.common.WireSize;
import com.neko.mcbot.server.BlockActionFixtures;
import com.neko.mcbot.server.ServerTool;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class BodyChangeLogTest {
    @BeforeAll static void bootstrap() { BlockActionFixtures.bootstrap(); }

    static final class Body {
        final UUID id = UUID.randomUUID();
        Object world = new Object();
        long tick;
        Vec3 position = Vec3.ZERO;
        float hp = 20, absorption;
        int food = 20, air = 300, points, levels, selected;
        UUID vehicle;
        List<ItemStack> inventory = List.of(ItemStack.EMPTY, ItemStack.EMPTY, ItemStack.EMPTY);
        Map<EquipmentSlot, ItemStack> equipment = new HashMap<>();
        final BodyChangeLog log = new BodyChangeLog(id, () -> new BodyChangeLog.Snapshot(
                world, "minecraft:overworld", tick, position, hp, absorption, food, air,
                points, levels, vehicle, selected, inventory, equipment));
    }

    @Test void actionAndPassiveObservationsUseSameChangesWithoutCountingEquipmentTwice() {
        var body = new Body();
        var window = body.log.open();
        body.inventory = List.of(new ItemStack(Items.STONE, 3), new ItemStack(Items.STONE, 2));
        body.equipment.put(EquipmentSlot.MAINHAND, body.inventory.getFirst());
        body.hp = 18; body.food = 19; body.air = 280; body.absorption = 2;
        body.points = 7; body.levels = 1; body.vehicle = UUID.randomUUID();
        body.position = new Vec3(1, .5, 2);
        body.tick++;
        body.log.observe();
        var delta = window.finish(new ServerTool.Result(false, "PARTIAL:stopped", null))
                .data().getAsJsonObject("body_delta");
        var net = delta.getAsJsonObject("net");
        assertEquals(5, net.getAsJsonArray("carried_items").get(0).getAsJsonObject().get("count_delta").getAsInt());
        for (String field : List.of("health", "absorption", "food", "air", "experience_points",
                "experience_levels", "vehicle_after", "position_after", "equipment")) assertTrue(net.has(field), field);
        var recent = body.log.recent();
        assertEquals(delta.get("through_seq"), recent.get("through_seq"));
        var event = recent.getAsJsonArray("recent").get(0).getAsJsonObject().deepCopy();
        for (String field : List.of("seq", "from_tick", "through_tick")) event.remove(field);
        assertEquals(net, event);
    }

    @Test void slotMovesAndClockDoNotInventItemGainsAndNoopDoesNotMintEvents() {
        var body = new Body();
        body.inventory = List.of(new ItemStack(Items.STONE, 2), new ItemStack(Items.STONE, 3));
        var window = body.log.open();
        body.inventory = List.of(ItemStack.EMPTY, new ItemStack(Items.STONE, 5));
        body.tick = 90;
        var result = window.finish(new ServerTool.Result(true, "noop", null));
        var delta = result.data().getAsJsonObject("body_delta");
        assertTrue(delta.getAsJsonObject("net").isEmpty());
        assertEquals(0, delta.get("through_seq").getAsLong());
        assertEquals(0, body.log.recent().get("through_seq").getAsLong());
        body.inventory.get(1).grow(2);
        assertSame(result, window.finish(new ServerTool.Result(true, "ignored duplicate", null)));
        assertTrue(result.data().getAsJsonObject("body_delta").getAsJsonObject("net").isEmpty());
    }

    @Test void copiedStacksPreserveDurabilityAndComponentChangesButNeverSerializePrivateText() {
        var body = new Body();
        var tool = new ItemStack(Items.IRON_PICKAXE);
        body.inventory = List.of(tool);
        body.equipment.put(EquipmentSlot.MAINHAND, tool);
        var window = body.log.open();
        tool.setDamageValue(3);
        tool.set(DataComponents.CUSTOM_NAME, Component.literal("private text " + "x".repeat(10000)));
        body.selected = 1;
        var result = window.finish(new ServerTool.Result(true, "used", null));
        String json = result.data().toString();
        assertFalse(json.contains("private text"));
        var net = result.data().getAsJsonObject("body_delta").getAsJsonObject("net");
        assertFalse(net.has("carried_items"));
        assertEquals(1, net.get("component_changed_slots").getAsInt());
        assertEquals(3, net.getAsJsonArray("equipment").get(0).getAsJsonObject()
                .getAsJsonObject("after").get("damage").getAsInt());
    }

    @Test void emptyEquipmentIsNotEvidenceOfBreakageButNativeCallbackIs() {
        var body = new Body();
        body.equipment.put(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_PICKAXE));
        var window = body.log.open();
        body.equipment.clear();
        var result = window.finish(new ServerTool.Result(true, "unequipped", null));
        assertTrue(result.data().getAsJsonObject("body_delta").getAsJsonArray("equipment_breaks").isEmpty());
        var broken = body.log.open();
        body.log.equipmentBroken(Items.IRON_PICKAXE, EquipmentSlot.MAINHAND);
        var delta = broken.finish(new ServerTool.Result(false, "CANCELLED:owner", null))
                .data().getAsJsonObject("body_delta");
        assertEquals("minecraft:iron_pickaxe", delta.getAsJsonArray("equipment_breaks")
                .get(0).getAsJsonObject().get("item").getAsString());
        assertEquals("observation_only", delta.get("attribution").getAsString());
    }

    @Test void boundedHistoryHasExplicitLossAndKeepsLongActionNetInsteadOfAddingSnapshots() {
        var body = new Body();
        var window = body.log.open();
        for (int i = 1; i <= 50; i++) {
            body.position = new Vec3(i, 0, 0); body.tick++;
            body.log.observe();
        }
        var delta = window.finish(new ServerTool.Result(false, "TIMEOUT:cap", null))
                .data().getAsJsonObject("body_delta");
        assertTrue(delta.get("history_truncated").getAsBoolean());
        assertEquals(50, delta.getAsJsonObject("net").getAsJsonArray("position_after").get(0).getAsInt());
        var recent = body.log.recent();
        assertTrue(recent.get("history_truncated").getAsBoolean());
        assertTrue(recent.getAsJsonArray("recent").size() <= 4);
        assertTrue(WireSize.utf8Bytes(recent.toString()) <= BodyChangeLog.MAX_BYTES);
        assertTrue(WireSize.utf8Bytes(delta.toString()) <= BodyChangeLog.MAX_BYTES);
    }

    @Test void breaksSurviveMovementHistoryChurnButAreBoundedAndNeverInheritedByNewBody() {
        var body = new Body();
        var window = body.log.open();
        body.log.equipmentBroken(Items.IRON_PICKAXE, EquipmentSlot.MAINHAND);
        for (int i = 0; i < 40; i++) {
            body.position = new Vec3(i, 0, 0); body.log.observe();
        }
        var delta = window.finish(new ServerTool.Result(false, "PARTIAL:moved", null))
                .data().getAsJsonObject("body_delta");
        assertEquals(1, delta.getAsJsonArray("equipment_breaks").size());
        var many = body.log.open();
        for (int i = 0; i < 20; i++) body.log.equipmentBroken(Items.IRON_PICKAXE, EquipmentSlot.MAINHAND);
        var limited = many.finish(new ServerTool.Result(false, "TIMEOUT:cap", null))
                .data().getAsJsonObject("body_delta");
        assertTrue(limited.get("breaks_truncated").getAsBoolean());
        assertEquals(8, limited.getAsJsonArray("equipment_breaks").size());
        var replacement = new Body().log.recent();
        assertNotEquals(replacement.get("body_id"), limited.get("body_id"));
        assertTrue(replacement.getAsJsonArray("equipment_breaks").isEmpty());
    }

    @Test void worldSwitchDoesNotSubtractAnInventoryFromAnotherWorld() {
        var body = new Body();
        body.inventory = List.of(new ItemStack(Items.DIAMOND, 4));
        var window = body.log.open();
        body.world = new Object();
        body.inventory = List.of(ItemStack.EMPTY);
        var delta = window.finish(new ServerTool.Result(false, "TARGET_LOST:dimension", null))
                .data().getAsJsonObject("body_delta");
        assertFalse(delta.get("same_world").getAsBoolean());
        assertTrue(delta.getAsJsonObject("net").isEmpty());
    }

    @Test void recoveredObservationKeepsGapEvidenceInsteadOfClaimingCompleteInterval() {
        var unavailable = new java.util.concurrent.atomic.AtomicBoolean();
        var body = new Body();
        var log = new BodyChangeLog(body.id, () -> {
            if (unavailable.get()) throw new IllegalStateException("offline gap");
            return new BodyChangeLog.Snapshot(body.world, "world", body.tick, body.position,
                    body.hp, 0, 20, 300, 0, 0, null, 0, body.inventory, Map.of());
        });
        var window = log.open();
        unavailable.set(true);
        assertThrows(IllegalStateException.class, log::observe);
        unavailable.set(false);
        body.hp = 19;
        var delta = window.finish(new ServerTool.Result(false, "PARTIAL:gap", null))
                .data().getAsJsonObject("body_delta");
        assertFalse(delta.get("observation_known").getAsBoolean());
        assertEquals(19, delta.getAsJsonObject("net").getAsJsonArray("health").get(1).getAsInt());
        assertTrue(log.recent().get("observation_gap_seen").getAsBoolean());
        var fresh = log.open().finish(new ServerTool.Result(true, "new interval", null));
        assertTrue(fresh.data().getAsJsonObject("body_delta").get("observation_known").getAsBoolean());
    }
}
