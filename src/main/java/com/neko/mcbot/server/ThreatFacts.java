package com.neko.mcbot.server;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.neko.mcbot.body.CompanionPlayer;
import com.neko.mcbot.mixin.CreeperThreatAccess;
import com.neko.mcbot.mixin.MobThreatAccess;
import com.neko.mcbot.server.tools.AttackTool;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.NeutralMob;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.spider.Spider;
import net.minecraft.world.entity.monster.piglin.Piglin;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** One observed danger geometry for triggering, retreat selection and fresh arrival checks. */
public final class ThreatFacts {
    public static final int LIMIT = 64;
    public static final double RADIUS = 12;

    public record Threat(Object identity, int id, UUID uuid, Vec3 position, AABB attackBox,
                         double blastRadius, boolean attackAllowed, boolean targeting, boolean recentAttacker) {
        public Threat(Object identity, int id, UUID uuid, Vec3 position, AABB attackBox,
                      double blastRadius, boolean attackAllowed) {
            this(identity, id, uuid, position, attackBox, blastRadius, attackAllowed, true, false);
        }
        public boolean explosive() { return blastRadius > 0; }
        public boolean dangerous(AABB body, double padding) {
            if (explosive()) {
                double radius = blastRadius + padding;
                return body.distanceToSqr(position) <= radius * radius;
            }
            return attackBox.inflate(padding, .25, padding).intersects(body);
        }
    }

    public record Snapshot(List<Threat> threats, boolean complete) {
        public Snapshot { threats = List.copyOf(threats); }
        public JsonObject report(AABB body) {
            return report(body, "targeting_body");
        }
        public JsonObject reportGuard(AABB owner) {
            return report(owner, "targeting_owner");
        }
        private JsonObject report(AABB body, String targetingKey) {
            var data = new JsonObject();
            var samples = new JsonArray();
            for (var threat : threats.stream().limit(8).toList()) {
                var sample = new JsonObject();
                sample.addProperty("entity_id", threat.id());
                sample.addProperty("target_uuid", threat.uuid().toString());
                sample.addProperty("dangerous", threat.dangerous(body, .75));
                sample.addProperty("explosive", threat.explosive());
                sample.addProperty("automatic_attack_allowed", threat.attackAllowed());
                sample.addProperty(targetingKey, threat.targeting());
                sample.addProperty("recent_attacker", threat.recentAttacker());
                samples.add(sample);
            }
            data.add("sources", samples);
            data.addProperty("source_count", threats.size());
            data.addProperty("scan_complete", complete);
            data.addProperty("samples_omitted", Math.max(0, threats.size() - samples.size()));
            return data;
        }
    }

    public static boolean credible(boolean targeting, boolean recentAttacker, boolean armedBlast) {
        return targeting || recentAttacker || armedBlast;
    }

    public static double clearance(Threat threat, AABB body) {
        if (threat.explosive()) return Math.sqrt(body.distanceToSqr(threat.position())) - threat.blastRadius();
        var box = threat.attackBox();
        double x = Math.max(box.minX - body.maxX, body.minX - box.maxX);
        double y = Math.max(box.minY - body.maxY, body.minY - box.maxY);
        double z = Math.max(box.minZ - body.maxZ, body.minZ - box.maxZ);
        if (x > 0 || y > 0 || z > 0)
            return Math.sqrt(Math.max(0, x) * Math.max(0, x)
                    + Math.max(0, y) * Math.max(0, y) + Math.max(0, z) * Math.max(0, z));
        return Math.max(x, Math.max(y, z));
    }

    public static boolean progresses(Snapshot snapshot, AABB from, AABB to) {
        return snapshot.complete() && snapshot.threats().stream().allMatch(t ->
                clearance(t, to) + 1e-5 >= Math.min(1.5, clearance(t, from)));
    }

    public static Snapshot scan(CompanionPlayer body) {
        return scan(body, body, false);
    }

    public static Snapshot guardScan(CompanionPlayer body, LivingEntity owner) {
        return scan(body, owner, true);
    }

    public static boolean guardCandidate(boolean hostile, boolean neutral, boolean targeting, boolean recent) {
        return hostile && (!neutral || targeting || recent);
    }

    private static Snapshot scan(CompanionPlayer body, LivingEntity subject, boolean guarding) {
        var nearby = new ArrayList<LivingEntity>();
        body.level().getEntities(EntityTypeTest.forClass(LivingEntity.class), subject.getBoundingBox().inflate(RADIUS),
                entity -> entity != body && entity.isAlive() && !entity.isRemoved(), nearby, LIMIT + 1);
        boolean complete = nearby.size() <= LIMIT;
        if (guarding) {
            var area = subject.getBoundingBox().inflate(RADIUS);
            for (int x = ((int) Math.floor(area.minX)) >> 4; x <= ((int) Math.floor(area.maxX)) >> 4; x++)
                for (int z = ((int) Math.floor(area.minZ)) >> 4; z <= ((int) Math.floor(area.maxZ)) >> 4; z++)
                    if (body.level().getChunkSource().getChunkNow(x, z) == null) complete = false;
        }
        var threats = new ArrayList<Threat>();
        for (var mob : nearby.stream().limit(LIMIT).toList()) {
            if (body.level().getEntity(mob.getId()) != mob) continue;
            if (guarding && subject.distanceToSqr(mob) > RADIUS * RADIUS) continue;
            boolean recent = subject.getLastHurtByMob() == mob
                    && subject.tickCount >= subject.getLastHurtByMobTimestamp()
                    && subject.tickCount - subject.getLastHurtByMobTimestamp() <= 60;
            boolean armed = mob instanceof Creeper c && (c.isIgnited() || c.getSwellDir() > 0);
            boolean targeting = mob instanceof Mob source && source.getTarget() == subject;
            var described = AttackTool.describe(body, mob);
            if (guarding ? !guardCandidate(described.hostile(),
                    mob instanceof NeutralMob || mob instanceof Spider || mob instanceof Piglin, targeting, recent)
                    : !credible(targeting, recent, armed)) continue;
            var active = mob.getActiveItem().get(DataComponents.ATTACK_RANGE);
            double reach = mob instanceof Mob
                    ? active == null ? MobThreatAccess.mcbot$defaultReach() : active.effectiveMaxRange(mob)
                    : mob.entityAttackRange().effectiveMaxRange(mob) + mob.entityAttackRange().hitboxMargin();
            double blast = armed ? blastRadius((Creeper) mob) : 0;
            if (!Double.isFinite(reach) || reach < 0 || !Double.isFinite(blast) || blast < 0) {
                complete = false;
                continue;
            }
            boolean allowed = described.hostile() && !described.named() && !described.protectedTarget();
            AABB attackBox = mob instanceof Mob ? ((MobThreatAccess) mob).mcbot$attackBox(reach)
                    : new AABB(mob.getEyePosition(), mob.getEyePosition()).inflate(reach);
            threats.add(new Threat(mob, mob.getId(), mob.getUUID(), mob.position(), attackBox, blast, allowed, targeting, recent));
        }
        threats.sort(java.util.Comparator.comparingDouble((Threat t) -> t.position().distanceToSqr(body.position()))
                .thenComparingInt(Threat::id));
        return new Snapshot(threats, complete);
    }

    private static double blastRadius(Creeper creeper) {
        return Math.max(0, ((CreeperThreatAccess) creeper).mcbot$explosionRadius())
                * (creeper.isPowered() ? 4.0 : 2.0);
    }

    private ThreatFacts() { }
}
