package com.neko.mcbot.body;

import com.neko.mcbot.server.BlockActionFixtures;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MeleeClockTest {
    @BeforeAll static void registriesOnly() { BlockActionFixtures.bootstrap(); }

    @Test void equipResetsBothTimersAndServerTicksThenRechargeWithoutConnection() {
        var clock = new MeleeClock();
        var held = new ItemStack(Items.IRON_SWORD);
        var state = clock.tick(held, 60, 60);
        assertEquals(new MeleeClock.State(0, 0), state);
        for (int i = 0; i < 13; i++) state = clock.tick(held, state.attackTicks(), state.swapTicks());
        assertEquals(new MeleeClock.State(13, 13), state);
        // Native onAttack resets only attackStrengthTicker.
        state = clock.tick(held, 0, state.swapTicks());
        assertEquals(new MeleeClock.State(1, 14), state);
        state = clock.tick(new ItemStack(Items.IRON_AXE), state.attackTicks(), state.swapTicks());
        assertEquals(new MeleeClock.State(0, 0), state);
    }

    @Test void nativeWearAndComponentsKeepCooldownButBreakageResetsAndDoesNotMutateStack() {
        var clock = new MeleeClock();
        var held = new ItemStack(Items.IRON_SWORD);
        clock.tick(held, 0, 0);
        held.setDamageValue(11);
        held.set(DataComponents.CUSTOM_NAME, Component.literal("kept"));
        assertEquals(new MeleeClock.State(4, 9), clock.tick(held, 3, 8));
        assertEquals(11, held.getDamageValue());
        assertEquals("kept", held.get(DataComponents.CUSTOM_NAME).getString());
        held.setCount(0);
        assertEquals(new MeleeClock.State(0, 0), clock.tick(held, 4, 9));
        assertEquals(new MeleeClock.State(1, 1), clock.tick(ItemStack.EMPTY, 0, 0));
    }
}
