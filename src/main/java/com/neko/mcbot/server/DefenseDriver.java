package com.neko.mcbot.server;

import com.google.gson.JsonObject;
import com.neko.mcbot.McbotMod;
import com.neko.mcbot.body.CompanionPlayer;
import com.neko.mcbot.path.NavigationBudget;
import com.neko.mcbot.path.NavigationGoal;
import com.neko.mcbot.path.PathSpec;
import com.neko.mcbot.path.PathTask;
import com.neko.mcbot.server.tools.AttackTool;
import com.neko.mcbot.task.CompanionScheduler;
import com.neko.mcbot.task.TickTask;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;

import java.util.List;

/** Per-body native boundary for the same defense lifecycle used by offline regressions. */
public final class DefenseDriver implements BodyDefense.Access {
    private final CompanionPlayer body;
    private final BodyDefense defense = new BodyDefense(this);
    private ActionPermissions.BodyPermit permit;
    private CompanionScheduler.ReflexLease lease;
    private PathSpec scope;
    private BlockPos anchor;
    private boolean higherPriority;
    private ThreatFacts.Snapshot latest = new ThreatFacts.Snapshot(List.of(), false);
    private String loggedState = "idle";
    private boolean unavailable;

    public DefenseDriver(CompanionPlayer body) { this.body = body; }
    public boolean active() { return defense.active(); }
    public void cancel() { defense.cancel(); }
    public void stopCurrent() { defense.stopCurrent(); }
    public void newTask() { unavailable = false; defense.newTask(); }

    public void tick(boolean waterActive) {
        higherPriority = waterActive;
        var dispatcher = McbotMod.dispatcher();
        boolean allowed = dispatcher != null && dispatcher.belongsTo(body.level().getServer())
                && body.level().getServer().getPlayerList().getPlayer(body.getUUID()) == body
                && body.level().getServer().getPlayerList().getPlayer(body.ownerUuid()) != null
                && body.isAlive() && !body.isRemoved() && body.gameMode.isSurvival()
                && !body.isSleeping() && !body.isPassenger() && dispatcher.passiveBodyActionAllowed(body);
        allowed &= !unavailable;
        try {
            latest = allowed ? ThreatFacts.scan(body) : new ThreatFacts.Snapshot(List.of(), false);
            defense.tick(new BodyDefense.Frame(body.level(), allowed, higherPriority,
                    McbotMod.scheduler().meleeActive(body.getUUID()), body.position(), body.getBoundingBox(),
                    body.getHealth(), body.getMaxHealth(),
                    !body.getMainHandItem().isEmpty() && AttackTool.supportedWeapon(body.getMainHandItem())
                            && body.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.ATTACK_DAMAGE) > 1,
                    latest));
        } catch (RuntimeException failure) {
            unavailable = true;
            latest = new ThreatFacts.Snapshot(List.of(), false);
            defense.cancel();
            McbotMod.LOG.warn("[body-defense] stopped after boundary failure type={}", failure.getClass().getSimpleName());
        }
        String state = defense.state();
        if (!state.equals(loggedState)) {
            loggedState = state;
            McbotMod.LOG.info("[body-defense] body_id={} state={} active={}",
                    body.observationBodyId(), state, defense.active());
        }
    }

    @Override public boolean acquire(BlockPos origin) {
        var dispatcher = McbotMod.dispatcher();
        permit = dispatcher == null ? null : dispatcher.defensePermit(body);
        if (permit == null) return false;
        lease = McbotMod.scheduler().reflexLease(body);
        if (lease == null) return false;
        scope = McbotMod.scheduler().movementScope(body.getUUID());
        anchor = origin.immutable();
        body.bodyControl().reflex(true);
        return true;
    }
    @Override public boolean valid() {
        return permit != null && permit.valid() && lease != null && lease.valid()
                && permit.dimension().equals(body.level().dimension().identifier().toString());
    }
    @Override public TickTask attack(ThreatFacts.Threat threat, int hits, boolean approach, NavigationBudget budget) {
        if (!(threat.identity() instanceof LivingEntity entity)) throw new IllegalArgumentException("source");
        return AttackTool.defensive(body, entity, McbotMod.scheduler(), permit, lease, anchor, scope,
                hits, approach, budget);
    }
    @Override public BlockPos haven(BodyDefense.Frame frame, BlockPos origin) {
        return RetreatWork.haven(frame, origin, scope, StandingGeometry.forPlayer(body));
    }
    @Override public TickTask retreat(BlockPos destination, NavigationBudget budget) {
        var local = new PathSpec(false, false, scope.underground(), 0, anchor, 6);
        return PathTask.approach(body.level(), destination, permit.source(), permit.context(), McbotMod.scheduler(),
                local, NavigationGoal.position(destination.getX(), destination.getY(), destination.getZ(), 0), budget)
                .limitTo(scope).constrainSteps((from, to) -> ThreatFacts.progresses(latest,
                        RetreatWork.at(body.getBoundingBox(), from), RetreatWork.at(body.getBoundingBox(), to)));
    }
    @Override public TickTask.Progress advance(TickTask task) { return task.tickSafe(body); }
    @Override public ServerTool.Result resume(TickTask task) { return task.onResume(body); }
    @Override public void stopInput() { body.releaseMovement(); }
    @Override public void release() {
        try {
            if (lease != null) lease.close();
        } finally {
            lease = null;
            permit = null;
            body.releaseMovement();
            body.bodyControl().reflex(higherPriority);
        }
    }
    public JsonObject observation() {
        var data = defense.observation();
        data.add("threats", latest.report(body.getBoundingBox()));
        return data;
    }
}
