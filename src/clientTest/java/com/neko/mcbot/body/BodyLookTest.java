package com.neko.mcbot.body;

import com.neko.mcbot.server.BlockActionFixtures;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BodyLookTest {
    @BeforeAll static void registries() { BlockActionFixtures.bootstrap(); }

    /** Native rotation/eye/vector methods, without constructing a world or fake player. */
    private static final class Body extends Entity {
        float head, torso;
        Body() { super(EntityType.MARKER, null); }
        @Override protected void defineSynchedData(SynchedEntityData.Builder builder) { }
        @Override public boolean hurtServer(ServerLevel level, DamageSource source, float amount) { return false; }
        @Override protected void readAdditionalSaveData(ValueInput input) { }
        @Override protected void addAdditionalSaveData(ValueOutput output) { }
        @Override public void setYHeadRot(float yaw) { head = yaw; }
        @Override public void setYBodyRot(float yaw) { torso = yaw; }
    }

    private static Vec3 point(Body body, double yaw, double pitch) {
        double y = Math.toRadians(yaw), p = Math.toRadians(pitch);
        return body.getEyePosition().add(-Math.sin(y) * Math.cos(p) * 4,
                -Math.sin(p) * 4, Math.cos(y) * Math.cos(p) * 4);
    }

    @Test void miningAndAttackAimCrossYawBoundaryWithoutAFullTurnOrResettingInterpolation() {
        for (float start : new float[]{179, -179, 899, -899}) {
            var body = new Body();
            body.setYRot(start);
            body.yRotO = start - 1;
            body.xRotO = 7;
            double desired = start + (start > 0 ? 4 : -4);
            var target = point(body, desired, 35);
            BodyLook.face(body, target);
            assertEquals(desired, body.getYRot(), 1e-4);
            assertEquals(35, body.getXRot(), 1e-4);
            assertEquals(body.getYRot(), body.head);
            assertEquals(body.getYRot(), body.torso);
            assertEquals(start - 1, body.yRotO, "native previous rotation remains available for interpolation");
            assertEquals(7, body.xRotO);
            assertTrue(body.getLookAngle().dot(target.subtract(body.getEyePosition()).normalize()) > .99999);
        }
    }

    @Test void lookingStraightUpOrDownRetainsYawAndCoincidentAimDoesNothing() {
        var body = new Body();
        body.setYRot(458);
        BodyLook.face(body, body.getEyePosition().add(0, 4, 0));
        assertEquals(458, body.getYRot());
        assertEquals(-90, body.getXRot());
        BodyLook.face(body, body.getEyePosition().add(0, -4, 0));
        assertEquals(458, body.getYRot());
        assertEquals(90, body.getXRot());
        BodyLook.face(body, body.getEyePosition());
        assertEquals(458, body.getYRot());
        assertEquals(90, body.getXRot());
    }
}
