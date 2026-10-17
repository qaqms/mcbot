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
    private final com.neko.mcbot.path.WorkTargets workTargets = new com.neko.mcbot.path.WorkTargets();
    private final com.neko.mcbot.building.BuildSite buildSite = new com.neko.mcbot.building.BuildSite();
    private final com.neko.mcbot.path.NavigationTrail navigationTrail = new com.neko.mcbot.path.NavigationTrail();
    private final BodyChangeLog bodyChanges = BodyChangeLog.forPlayer(observationBodyId, this);
    private boolean observationWarning;
    private boolean bodyChangeWarning;
    private final MovementInput movementInput = new MovementInput();
    private final BodyControl bodyControl = new BodyControl(previous -> {
        try {
            if (previous == BodyControl.Owner.WORK) McbotMod.scheduler().preempt(this);
        } finally {
            releaseMovement();
            try {
                stopUsingItem();
            } finally {
                swinging = false;
                swingTime = 0;
                attackAnim = oAttackAnim = 0;
            }
        }
    });
    private final EnvironmentRecovery environment = new EnvironmentRecovery();
    private final com.neko.mcbot.server.DefenseDriver defense = new com.neko.mcbot.server.DefenseDriver(this);
    private final com.neko.mcbot.server.MaintenanceDriver maintenance = new com.neko.mcbot.server.MaintenanceDriver(this);
    private boolean waterControlling, defenseDriving;
    private com.neko.mcbot.server.ActionPermissions.BodyPermit environmentPermit;
    private com.neko.mcbot.task.CompanionScheduler.ReflexLease environmentLease;
    private com.neko.mcbot.path.PathSpec environmentScope;
    private com.neko.mcbot.path.PathSpec environmentSiteScope;
    private final EnvironmentRecovery.Access waterAccess = new EnvironmentRecovery.Access() {
        @Override public boolean allowed() {
            var dispatcher = McbotMod.dispatcher();
            return isAlive() && !isRemoved() && !isPassenger() && !isSleeping() && gameMode.isSurvival()
                    && level().getServer().getPlayerList().getPlayer(getUUID()) == CompanionPlayer.this
                    && level().getServer().getPlayerList().getPlayer(ownerUuid) != null
                    && dispatcher != null && dispatcher.belongsTo(level().getServer())
                    && dispatcher.passiveBodyActionAllowed(CompanionPlayer.this);
        }
        @Override public int air() { return getAirSupply(); }
        @Override public int maxAir() { return getMaxAirSupply(); }
        @Override public String hazard() { return environmentHazard(); }
        @Override public boolean acquire() {
            maintenance.preempt();
            defense.stopCurrent();
            var dispatcher = McbotMod.dispatcher();
            environmentPermit = dispatcher == null ? null : dispatcher.defensePermit(CompanionPlayer.this);
            if (environmentPermit == null) return false;
            bindSpatialTask(environmentPermit.context());
            environmentScope = McbotMod.scheduler().movementScope(getUUID());
            environmentSiteScope = buildSite.active() ? buildSite.scope() : com.neko.mcbot.path.PathSpec.DEFAULT;
            environmentLease = McbotMod.scheduler().reflexLease(CompanionPlayer.this);
            if (environmentLease == null) { environmentPermit = null; return false; }
            bodyControl.reflex(true);
            return true;
        }
        @Override public boolean valid() {
            return environmentPermit != null && environmentPermit.valid()
                    && environmentLease != null && environmentLease.valid();
        }
        @Override public void release() {
            try {
                if (environmentLease != null) environmentLease.close();
            } finally {
                environmentLease = null;
                environmentPermit = null;
                releaseMovement();
                bodyControl.reflex(defense.active() || maintenance.active());
            }
        }
        @Override public boolean withinScope(Vec3 feet) {
            BlockPos pos = BlockPos.containing(feet);
            return environmentScope != null && environmentScope.contains(pos.getX(), pos.getY(), pos.getZ())
                    && environmentSiteScope.contains(pos.getX(), pos.getY(), pos.getZ());
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
            if (from.distanceToSqr(to) > 4 || from.distanceToSqr(position()) > 256
                    || !withinScope(from) || !withinScope(to)) return false;
            BlockPos start = BlockPos.containing(from), end = BlockPos.containing(to);
            if (!start.equals(end) && (!environmentScope.allowsStep(start.getX(), start.getY(), start.getZ(),
                    end.getX(), end.getY(), end.getZ())
                    || !environmentSiteScope.allowsStep(start.getX(), start.getY(), start.getZ(),
                    end.getX(), end.getY(), end.getZ()))) return false;
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
        @Override public Object worldIdentity() { return level(); }
        @Override public void unavailable() {
            environment.unavailable();
            waterControlling = false;
            bodyControl.reflex(defense.active() || maintenance.active());
        }
        @Override public MovementInput.Frame takeInput() { return movementInput.take(); }
        @Override public float yaw() { return getYRot(); }
        @Override public boolean grounded() { return onGround(); }
        @Override public void jump() { jumpFromGround(); }
        @Override public void updateFluids() {
            // ServerPlayer.tick omits the living base tick. It includes fluids/eyes exactly once,
            // native air/effects/fire/suffocation rules, but no Player.tick or extra travel.
            CompanionPlayer.this.baseTick();
        }
        @Override public void refreshEyes() {
            ((com.neko.mcbot.mixin.EntityFluidAccess) (net.minecraft.world.entity.Entity) CompanionPlayer.this)
                    .mcbot$updateFluidOnEyes();
        }
        @Override public MovementInput.Frame recoveryInput() {
            MovementInput.Frame frame = environment.tick(waterAccess);
            waterControlling = environment.active();
            bodyControl.reflex(waterControlling || defense.active() || maintenance.active());
            return frame == null && environmentFailure() != null ? MovementInput.Frame.IDLE : frame;
        }
        @Override public boolean inWater() { return isInWater(); }
        @Override public void swimUp() { jumpInLiquid(net.minecraft.tags.FluidTags.WATER); }
        @Override public void travel(Vec3 input) { CompanionPlayer.this.travel(input); }
        @Override public void applyBlockEffects() { applyEffectsFromBlocks(); }
        @Override public void checkFall(Vec3 movement) {
            doCheckFallDamage(movement.x, movement.y, movement.z, onGround());
        }
        @Override public void recordMovement(Vec3 movement) {
            setKnownMovement(movement);
            checkMovementStatistics(movement.x, movement.y, movement.z);
            calculateEntityAnimation(false);
            if (movement.lengthSqr() > 0) level().getChunkSource().move(CompanionPlayer.this);
        }
        @Override public void afterMovement() {
            // Capture the original scope/lease before the scheduler can settle newly unsafe work.
            // A new danger discovered after travel has not advanced an active episode this tick.
            if (!environment.holdsWork() && environmentFailure() != null) {
                environment.tick(waterAccess);
                waterControlling = environment.active();
                bodyControl.reflex(waterControlling || defense.active() || maintenance.active());
            }
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
        observeBodyChanges();
    }

    public UUID ownerUuid() {
        return ownerUuid;
    }

    public UUID observationBodyId() { return observationBodyId; }

    public com.neko.mcbot.path.WorkTargets workTargets() { return workTargets; }
    public com.neko.mcbot.building.BuildSite buildSite() { return buildSite; }
    public com.neko.mcbot.path.NavigationTrail navigationTrail() { return navigationTrail; }

    public void bindSpatialTask(com.neko.mcbot.server.ActionPermissions.Context context) {
        workTargets.bind(context);
        buildSite.bind(context, level());
        navigationTrail.bind(context, level());
    }

    public com.neko.mcbot.server.HandSnapshot.Pickups handPickups() { return handPickups; }

    public void walkForTick(double x, double z, boolean jump) {
        if (bodyControl.owner() == BodyControl.Owner.WORK
                || defenseDriving && bodyControl.owner() == BodyControl.Owner.REFLEX)
            movementInput.renew(x, z, jump);
    }

    public com.neko.mcbot.server.DefenseDriver defense() { return defense; }
    public com.neko.mcbot.server.MaintenanceDriver maintenance() { return maintenance; }

    public void tickDefense() {
        defenseDriving = true;
        try { defense.tick(waterControlling || environmentFailure() != null); }
        finally { defenseDriving = false; }
        maintenance.tick();
    }

    public void releaseMovement() { movementInput.clear(); }

    public BodyControl bodyControl() { return bodyControl; }

    public BodyChangeLog bodyChanges() { return bodyChanges; }

    public com.google.gson.JsonObject recentBodyChanges() {
        try {
            return bodyChanges.recent();
        } catch (RuntimeException failure) {
            warnBodyChanges(failure);
            var data = new com.google.gson.JsonObject();
            data.addProperty("body_id", observationBodyId.toString());
            data.addProperty("observation_known", false);
            return data;
        }
    }

    private void observeBodyChanges() {
        try {
            bodyChanges.observe();
        } catch (RuntimeException failure) {
            warnBodyChanges(failure);
        }
    }

    private void warnBodyChanges(RuntimeException failure) {
        if (!bodyChangeWarning) {
            bodyChangeWarning = true;
            McbotMod.LOG.warn("身体差量采样不可用 body_id={}", observationBodyId, failure);
        }
    }

    public String waterRecoveryState() { return environment.waterState(); }

    private String environmentHazard() {
        if (!level().isLoaded(blockPosition())) return "unknown";
        return EnvironmentRecovery.hazard(isInLava(), isOnFire(), isInWall(),
                isInPowderSnow || wasInPowderSnow,
                getY() < level().getMinY());
    }

    public com.google.gson.JsonObject environmentObservation() {
        var data = environment.observation();
        data.addProperty("hazard", environmentHazard());
        data.addProperty("air_supply", getAirSupply());
        data.addProperty("max_air_supply", getMaxAirSupply());
        data.addProperty("eyes_in_water", isEyeInFluid(net.minecraft.tags.FluidTags.WATER));
        data.addProperty("work_safe", environmentFailure() == null);
        return data;
    }

    public com.neko.mcbot.server.ServerTool.Result environmentFailure() {
        return EnvironmentRecovery.workFailure(environmentHazard(),
                isEyeInFluid(net.minecraft.tags.FluidTags.WATER), environment.holdsWork());
    }

    public boolean environmentActive() { return environment.active(); }

    public void cancelBodyBehaviors() {
        try { environment.cancel(); }
        finally { try { defense.cancel(); } finally { maintenance.cancel(); } }
    }
    public void stopBodyBehaviors() {
        try { environment.stopCurrent(); }
        finally { try { defense.stopCurrent(); } finally { maintenance.stopCurrent(); } }
    }
    public void newBodyTask() { environment.newTask(); defense.newTask(); maintenance.newTask(); }

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
        // FakeConnection has no listener tick, so Player.tick cannot supply these updates.
        ((com.neko.mcbot.mixin.LivingEquipmentAccess) (net.minecraft.world.entity.LivingEntity) this)
                .mcbot$detectEquipmentUpdates();
        var state = meleeClock.tick(getMainHandItem(), attackStrengthTicker, itemSwapTicker);
        attackStrengthTicker = state.attackTicks();
        itemSwapTicker = state.swapTicks();
        String previousWater = environment.state();
        var previousLevel = level();
        CompanionPhysics.tick(physics);
        if (isAlive() && !isRemoved() && level() == previousLevel && level().isLoaded(blockPosition())) {
            getCooldowns().tick();
            getFoodData().tick(this);
        }
        updateSwingTime();
        if (!previousWater.equals(environment.state())) {
            McbotMod.LOG.info("[body-environment] body_id={} facts={} pos={} tick={}",
                    observationBodyId, environmentObservation(), blockPosition().toShortString(), level().getGameTime());
        }
        BodyPickup.tick(pickup);
        observeHealth(HealthObservation.Phase.AFTER_TICK);
        observeBodyChanges();
    }

    @Override
    public void onEquippedItemBroken(net.minecraft.world.item.Item item,
                                     net.minecraft.world.entity.EquipmentSlot slot) {
        super.onEquippedItemBroken(item, slot);
        if (maintenance != null) maintenance.equipmentBroken(slot.getSerializedName());
        if (bodyChanges != null) {
            try {
                bodyChanges.equipmentBroken(item, slot);
            } catch (RuntimeException failure) {
                warnBodyChanges(failure);
            }
        }
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
