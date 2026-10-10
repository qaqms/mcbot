package com.neko.mcbot.body;

import com.mojang.authlib.GameProfile;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;

/**
 * 同伴的身体：一个在册的真 ServerPlayer，走玩家原生路径。
 * 主人绑定只在这里存 owner UUID；其余玩家行为一律保持原版。
 */
public final class CompanionPlayer extends ServerPlayer {

    private final UUID ownerUuid;
    private final MeleeClock meleeClock = new MeleeClock();

    public CompanionPlayer(MinecraftServer server, ServerLevel level, GameProfile profile,
                           ClientInformation clientInformation, UUID ownerUuid) {
        super(server, level, profile, clientInformation);
        this.ownerUuid = ownerUuid;
    }

    public UUID ownerUuid() {
        return ownerUuid;
    }

    @Override
    public void tick() {
        super.tick();
        // FakeConnection has no listener tick, so Player.tick cannot supply these updates.
        ((com.neko.mcbot.mixin.LivingEquipmentAccess) (net.minecraft.world.entity.LivingEntity) this)
                .mcbot$detectEquipmentUpdates();
        var state = meleeClock.tick(getMainHandItem(), attackStrengthTicker, itemSwapTicker);
        attackStrengthTicker = state.attackTicks();
        itemSwapTicker = state.swapTicks();
    }
}
