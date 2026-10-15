package com.neko.mcbot.ui;

import com.mojang.blaze3d.platform.DepthTestFunction;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.neko.mcbot.agent.TaskBubbleState;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.OrderedSubmitNodeCollector;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CompanionTaskBubbleTest {
    private record Geometry(PoseStack.Pose pose, RenderType type, SubmitNodeCollector.CustomGeometryRenderer draw) {}
    private record Text(Matrix4f pose, Font.DisplayMode mode, int color) {}
    private static final class Capture {
        final List<Geometry> geometry = new ArrayList<>();
        final List<Text> texts = new ArrayList<>();
        final OrderedSubmitNodeCollector queue = (OrderedSubmitNodeCollector) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{OrderedSubmitNodeCollector.class}, (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "submitCustomGeometry" -> geometry.add(new Geometry(((PoseStack) args[0]).last().copy(),
                                (RenderType) args[1], (SubmitNodeCollector.CustomGeometryRenderer) args[2]));
                        case "submitText" -> texts.add(new Text(new Matrix4f(((PoseStack) args[0]).last().pose()),
                                (Font.DisplayMode) args[5], (int) args[6]));
                        default -> fail("unexpected submission: " + method.getName());
                    }
                    return null;
                });
    }
    private CompanionTaskBubble.Frame frame(Vec3 anchor) {
        var state = new TaskBubbleState.Snapshot(1, "task", TaskBubbleState.Tone.WORKING,
                "正在挖掘", "", null, 0);
        var layout = TaskBubbleLayout.create(state, "Bot", "", s -> s.length() * 6);
        return new CompanionTaskBubble.Frame(anchor, .012f, .5f, layout,
                new CompanionTaskBubble.Palette(0xEDF8EE, 0xA3B9A9, 0x243B2D, 0x536B5A, 0x356F52));
    }
    @Test void actualSubmissionsUseDepthTestedLightmappedQuadsAndNormalText() {
        var capture = new Capture();
        CompanionTaskBubble.submit(new PoseStack(), capture.queue, Vec3.ZERO, new Quaternionf(), frame(new Vec3(3, 4, 5)));
        assertEquals(1, capture.geometry.size());
        var geometry = capture.geometry.getFirst();
        assertSame(DefaultVertexFormat.POSITION_COLOR_LIGHTMAP, geometry.type.format());
        assertNotEquals(DepthTestFunction.NO_DEPTH_TEST, geometry.type.pipeline().getDepthTestFunction());
        assertFalse(capture.texts.isEmpty());
        assertTrue(capture.texts.stream().allMatch(text -> text.mode == Font.DisplayMode.NORMAL));
        assertTrue(capture.texts.stream().allMatch(text -> (text.color >>> 24) == 128));
        int[] positions = {0}, colors = {0}, lights = {0};
        VertexConsumer sink = (VertexConsumer) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{VertexConsumer.class}, (proxy, method, args) -> {
                    if (method.isDefault()) return java.lang.reflect.InvocationHandler.invokeDefault(proxy, method, args);
                    switch (method.getName()) {
                        case "addVertex" -> {
                            for (Object value : args) assertTrue(Float.isFinite((float) value));
                            positions[0]++;
                        }
                        case "setColor" -> {
                            assertEquals(128, (int) args[0] >>> 24);
                            colors[0]++;
                        }
                        case "setUv2" -> {
                            assertEquals(LightTexture.FULL_BRIGHT & 0xFFFF, args[0]);
                            assertEquals(LightTexture.FULL_BRIGHT >>> 16, args[1]);
                            lights[0]++;
                        }
                        default -> fail("unexpected vertex attribute: " + method.getName());
                    }
                    return proxy;
                });
        geometry.draw.render(geometry.pose, sink);
        assertTrue(positions[0] > 0);
        assertEquals(0, positions[0] % 4);
        assertEquals(positions[0], colors[0]);
        assertEquals(positions[0], lights[0]);
    }
    @Test void billboardUsesCameraRelativeAnchorRestoresPoseAndFollowsMovement() {
        var pose = new PoseStack();
        var original = new Matrix4f(pose.last().pose());
        Vec3 camera = new Vec3(100, 80, 100), anchor = new Vec3(103, 84, 95);
        var orientation = new Quaternionf().rotationY(.7f);
        var first = new Capture();
        CompanionTaskBubble.submit(pose, first.queue, camera, orientation, frame(anchor));
        assertEquals(original, pose.last().pose());
        var tip = first.geometry.getFirst().pose.pose().transformPosition(
                new Vector3f(TaskBubbleLayout.WIDTH / 2f, TaskBubbleLayout.HEIGHT + 6, 0));
        assertEquals(3, tip.x, .0001);
        assertEquals(4, tip.y, .0001);
        assertEquals(-5, tip.z, .0001);
        var second = new Capture();
        CompanionTaskBubble.submit(pose, second.queue, camera, orientation, frame(anchor.add(1, .4, -2)));
        var next = second.geometry.getFirst().pose.pose().transformPosition(
                new Vector3f(TaskBubbleLayout.WIDTH / 2f, TaskBubbleLayout.HEIGHT + 6, 0));
        assertEquals(1, next.x - tip.x, .0001);
        assertEquals(.4, next.y - tip.y, .0001);
        assertEquals(-2, next.z - tip.z, .0001);
    }
}
