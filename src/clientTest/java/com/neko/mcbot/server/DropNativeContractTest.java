package com.neko.mcbot.server;

import com.google.gson.JsonParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerPlayerGameMode;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/** Native mapped descriptors and packaging only; this does not apply Mixins or start a world. */
class DropNativeContractTest {
    @BeforeAll static void bootstrap() { BlockActionFixtures.bootstrap(); }

    @Test void experienceContactAndItsPlayerCooldownUseTheMappedNativeApi() throws Exception {
        assertEquals(void.class, ExperienceOrb.class.getDeclaredMethod("playerTouch", Player.class).getReturnType());
        var delay = Player.class.getDeclaredField("takeXpDelay");
        assertEquals(int.class, delay.getType());
        assertTrue(Modifier.isPublic(delay.getModifiers()));
        assertEquals(void.class, Player.class.getDeclaredMethod("giveExperiencePoints", int.class).getReturnType());
    }

    @Test void eventTargetsMatchTheActualMappedRuntimeIncludingPrivateMergeAndFinalRemoval() throws Exception {
        assertEquals(boolean.class, ServerLevel.class.getDeclaredMethod("addFreshEntity", Entity.class).getReturnType());
        assertEquals(boolean.class, ServerPlayerGameMode.class.getDeclaredMethod("destroyBlock", BlockPos.class).getReturnType());
        assertEquals(ServerPlayer.class, ServerPlayerGameMode.class.getDeclaredField("player").getType());
        assertTrue(Modifier.isFinal(ServerPlayerGameMode.class.getDeclaredField("player").getModifiers()));
        var merge = ItemEntity.class.getDeclaredMethod("merge", ItemEntity.class, ItemStack.class,
                ItemEntity.class, ItemStack.class);
        assertTrue(Modifier.isPrivate(merge.getModifiers()));
        assertTrue(Modifier.isStatic(merge.getModifiers()));
        assertEquals(void.class, merge.getReturnType());
        assertEquals(void.class, ItemEntity.class.getDeclaredMethod("playerTouch", Player.class).getReturnType());
        assertEquals(boolean.class, ItemEntity.class.getDeclaredMethod(
                "hurtServer", ServerLevel.class, DamageSource.class, float.class).getReturnType());
        assertTrue(Modifier.isFinal(Entity.class.getDeclaredMethod(
                "setRemoved", Entity.RemovalReason.class).getModifiers()));
        assertEquals(void.class, Mob.class.getDeclaredMethod("pickUpItem", ServerLevel.class, ItemEntity.class).getReturnType());
        assertTrue(Modifier.isStatic(Block.class.getDeclaredMethod(
                "popResource", Level.class, BlockPos.class, ItemStack.class).getModifiers()));
        assertEquals(void.class, Block.class.getDeclaredMethod(
                "popResourceFromFace", Level.class, BlockPos.class, Direction.class, ItemStack.class).getReturnType());
    }

    @Test void everyNewHookIsRequiredAndPackagedAlongsideTheExistingMixins() throws Exception {
        try (var stream = getClass().getClassLoader().getResourceAsStream("mcbot.mixins.json")) {
            assertNotNull(stream);
            var config = JsonParser.parseString(new String(stream.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
            assertTrue(config.get("required").getAsBoolean());
            assertEquals(1, config.getAsJsonObject("injectors").get("defaultRequire").getAsInt());
            var names = config.getAsJsonArray("mixins").asList().stream().map(v -> v.getAsString()).toList();
            for (String name : java.util.List.of("DropAdmissionMixin", "DropBreakMixin", "DropSourceMixin",
                    "DropRemovalMixin", "DropMutationMixin", "DropMobPickupMixin")) {
                assertTrue(names.contains(name));
                assertNotNull(getClass().getClassLoader().getResource("com/neko/mcbot/mixin/" + name + ".class"));
            }
        }
    }
}
