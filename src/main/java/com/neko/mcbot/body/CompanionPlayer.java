package com.neko.mcbot.body;

import com.mojang.authlib.GameProfile;
import com.neko.mcbot.McbotMod;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.UUID;

/**
 * 同伴的身体：一个在册的真 ServerPlayer，走玩家原生路径。
 * 主人绑定只在这里存 owner UUID；其余玩家行为一律保持原版。
 */
public final class CompanionPlayer extends ServerPlayer {

    private final UUID ownerUuid;
    private final MeleeClock meleeClock = new MeleeClock();
    private final HealthObservation healthObservation = new HealthObservation();
    private final UUID observationBodyId = UUID.randomUUID();
    private boolean observationWarning;
    private final MovementInput movementInput = new MovementInput();
    private final WaterRecovery waterRecovery = new WaterRecovery();
    private final WaterRecovery.Access waterAccess = new WaterRecovery.Access() {
        @Override public boolean allowed() {
            var dispatcher = McbotMod.dispatcher();
            return !isSpectator() && dispatcher != null && dispatcher.belongsTo(level().getServer())
                    && dispatcher.passiveBodyActionAllowed(CompanionPlayer.this);
        }
        @Override public boolean submerged() { return isEyeInFluid(net.minecraft.tags.FluidTags.WATER); }
        @Override public Object worldIdentity() { return level(); }
        @Override public Vec3 position() { return CompanionPlayer.this.position(); }
        @Override public WaterRecovery.Medium eyesAt(Vec3 feet) {
            Vec3 eyes = feet.add(0, getEyeY() - getY(), 0);
            BlockPos pos = BlockPos.containing(eyes);
            if (!level().isInWorldBounds(pos) || !level().isLoaded(pos)) return WaterRecovery.Medium.BLOCKED;
            var state = level().getBlockState(pos);
            var fluid = state.getFluidState();
            if (fluid.is(net.minecraft.tags.FluidTags.WATER) && eyes.y < pos.getY() + fluid.getHeight(level(), pos))
                return WaterRecovery.Medium.WATER;
            return (fluid.isEmpty() || fluid.is(net.minecraft.tags.FluidTags.WATER))
                    && state.getCollisionShape(level(), pos).isEmpty()
                    ? WaterRecovery.Medium.AIR : WaterRecovery.Medium.BLOCKED;
        }
        @Override public boolean passage(Vec3 from, Vec3 to) {
            if (from.distanceToSqr(to) > 4 || from.distanceToSqr(position()) > 256) return false;
            Vec3 delta = from.subtract(position());
            var sweep = getBoundingBox().move(delta).expandTowards(to.subtract(from));
            for (BlockPos pos : BlockPos.betweenClosed(BlockPos.containing(sweep.minX, sweep.minY, sweep.minZ),
                    BlockPos.containing(sweep.maxX, sweep.maxY, sweep.maxZ))) {
                if (!level().isInWorldBounds(pos) || !level().getWorldBorder().isWithinBounds(pos)
                        || !level().isLoaded(pos)
                        || level().getFluidState(pos).is(net.minecraft.tags.FluidTags.LAVA)) return false;
            }
            return level().noCollision(CompanionPlayer.this, sweep);
        }
    };
    private final com.neko.mcbot.server.HandSnapshot.Pickups handPickups =
            new com.neko.mcbot.server.HandSnapshot.Pickups();
    private final CompanionPhysics.Access physics = new CompanionPhysics.Access() {
        @Override public boolean active() {
            return isAlive() && !isRemoved() && !isPassenger() && !isSleeping();
        }
        @Override public AABB bounds() { return getBoundingBox(); }
        @Override public Vec3 velocity() { return getDeltaMovement(); }
        @Override public boolean loaded(AABB region) {
            // Use the horizontal overload so falling below the build floor does not freeze the body.
            BlockPos from = BlockPos.containing(region.minX, 0, region.minZ);
            BlockPos to = BlockPos.containing(region.maxX, 0, region.maxZ);
            return level().hasChunksAt(from.getX(), from.getZ(), to.getX(), to.getZ());
        }
        @Override public Vec3 position() { return CompanionPlayer.this.position(); }
        @Override public MovementInput.Frame takeInput() { return movementInput.take(); }
        @Override public float yaw() { return getYRot(); }
        @Override public boolean grounded() { return onGround(); }
        @Override public void jump() { jumpFromGround(); }
        @Override public void updateFluids() {
            updateInWaterStateAndDoFluidPushing();
            refreshEyes();
        }
        @Override public void refreshEyes() {
            ((com.neko.mcbot.mixin.EntityFluidAccess) (net.minecraft.world.entity.Entity) CompanionPlayer.this)
                    .mcbot$updateFluidOnEyes();
        }
        @Override public MovementInput.Frame recoveryInput() { return waterRecovery.tick(waterAccess); }
        @Override public boolean inWater() { return isInWater(); }
        @Override public void swimUp() { jumpInLiquid(net.minecraft.tags.FluidTags.WATER); }
        @Override public void travel(Vec3 input) { CompanionPlayer.this.travel(input); }
        @Override public void applyBlockEffects() { applyEffectsFromBlocks(); }
        @Override public void checkFall(Vec3 movement) {
            doCheckFallDamage(movement.x, movement.y, movement.z, onGround());
        }
        @Override public void recordMovement(Vec3 movement) {
            setKnownMovement(movement);
            calculateEntityAnimation(false);
            if (movement.lengthSqr() > 0) level().getChunkSource().move(CompanionPlayer.this);
        }
    };
    private final BodyPickup.Access pickup = new BodyPickup.Access() {
        @Override public boolean active() {
            return isAlive() && !isRemoved() && !isSpectator() && !isPassenger() && !isSleeping();
        }
        @Override public boolean allowed() {
            var dispatcher = McbotMod.dispatcher();
            return dispatcher != null && dispatcher.belongsTo(level().getServer())
                    && dispatcher.passivePickupAllowed(CompanionPlayer.this);
        }
        @Override public AABB bounds() { return getBoundingBox(); }
        @Override public boolean loaded(AABB region) { return physics.loaded(region); }
        @Override public java.util.List<? extends BodyPickup.Item> items(AABB region) {
            return level().getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class, region,
                    item -> item.isAlive()).stream()
                    .<BodyPickup.Item>map(item -> () -> com.neko.mcbot.server.GroundItems.pickup(
                            CompanionPlayer.this, item)).toList();
        }
    };

    public CompanionPlayer(MinecraftServer server, ServerLevel level, GameProfile profile,
                           ClientInformation clientInformation, UUID ownerUuid) {
        super(server, level, profile, clientInformation);
        this.ownerUuid = ownerUuid;
    }

    public UUID ownerUuid() {
        return ownerUuid;
    }

    public com.neko.mcbot.server.HandSnapshot.Pickups handPickups() { return handPickups; }

    public void walkForTick(double x, double z, boolean jump) {
        movementInput.renew(x, z, jump);
    }

    public void releaseMovement() { movementInput.clear(); }

    public String waterRecoveryState() { return waterRecovery.state(); }

    public void facePoint(Vec3 point) {
        if (getEyePosition().distanceToSqr(point) < 1e-10) return;
        lookAt(EntityAnchorArgument.Anchor.EYES, point);
        // FakeConnection does not drive the normal living tick that aligns the torso.
        setYBodyRot(getYRot());
    }

    @Override
    public void tick() {
        observeHealth(HealthObservation.Phase.BEFORE_TICK);
        super.tick();
        updateSwingTime();
        // FakeConnection has no listener tick, so Player.tick cannot supply these updates.
        ((com.neko.mcbot.mixin.LivingEquipmentAccess) (net.minecraft.world.entity.LivingEntity) this)
                .mcbot$detectEquipmentUpdates();
        var state = meleeClock.tick(getMainHandItem(), attackStrengthTicker, itemSwapTicker);
        attackStrengthTicker = state.attackTicks();
        itemSwapTicker = state.swapTicks();
        String previousWater = waterRecovery.state();
        CompanionPhysics.tick(physics);
        if (!previousWater.equals(waterRecovery.state())) {
            McbotMod.LOG.info("[body-water] companion={} state={} in_water={} eyes_in_water={} pos={} tick={}",
                    getUUID(), waterRecovery.state(), isInWater(),
                    isEyeInFluid(net.minecraft.tags.FluidTags.WATER), blockPosition().toShortString(), level().getGameTime());
        }
        BodyPickup.tick(pickup);
        observeHealth(HealthObservation.Phase.AFTER_TICK);
    }

    @Override
    public boolean hurtServer(ServerLevel level, DamageSource source, float amount) {
        observeHealth(HealthObservation.Phase.BEFORE_DAMAGE);
        Boolean returned = null;
        try {
            returned = super.hurtServer(level, source, amount);
            return returned;
        } finally {
            observeHealth(HealthObservation.Phase.AFTER_DAMAGE, source, amount, returned);
        }
    }

    public void observeHealth(HealthObservation.Phase phase) {
        observeHealth(phase, null, 0, null);
    }

    private void observeHealth(HealthObservation.Phase phase, DamageSource source,
                               float amount, Boolean returned) {
        // Superclass construction can call virtual methods before these fields exist.
        if (healthObservation == null) return;
        try {
            var snapshot = new HealthObservation.Snapshot(getHealth(), getMaxHealth(),
                    getAbsorptionAmount(), isInvulnerable(), onGround(), fallDistance,
                    getX(), getY(), getZ(), level().dimension().identifier().toString(),
                    level().getGameTime());
            var damage = source == null ? null : new HealthObservation.Damage(
                    source.typeHolder().unwrapKey().map(key -> key.identifier().toString())
                            .orElse("unregistered"), amount, returned);
            var event = healthObservation.sample(phase, snapshot, damage);
            if (event != null) {
                var data = event.data();
                data.addProperty("body_id", observationBodyId.toString());
                data.addProperty("companion_uuid", getUUID().toString());
                data.addProperty("companion", getGameProfile().name());
                McbotMod.LOG.info("[body-health] {}", data);
            }
        } catch (RuntimeException failure) {
            if (!observationWarning) {
                observationWarning = true;
                McbotMod.LOG.warn("[body-health] observation unavailable body_id={}", observationBodyId);
            }
        }
    }
}
