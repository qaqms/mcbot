package com.neko.mcbot.ui;

import com.mojang.blaze3d.platform.DepthTestFunction;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.neko.mcbot.agent.TaskBubbleState;
import com.neko.mcbot.agentcore.loop.ItemGoal;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.OrderedSubmitNodeCollector;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.SubmitNodeStorage;
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
    private static final class Capture {
        final List<Geometry> geometry = new ArrayList<>();
        final SubmitNodeStorage storage = new SubmitNodeStorage();
        final OrderedSubmitNodeCollector queue = (OrderedSubmitNodeCollector) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{OrderedSubmitNodeCollector.class}, (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "submitCustomGeometry" -> geometry.add(new Geometry(((PoseStack) args[0]).last().copy(),
                                (RenderType) args[1], (SubmitNodeCollector.CustomGeometryRenderer) args[2]));
                        case "submitText" -> method.invoke(storage, args);
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
        var texts = capture.storage.order(0).getTextSubmits();
        assertFalse(texts.isEmpty());
        assertTrue(texts.stream().allMatch(text -> text.displayMode() == Font.DisplayMode.NORMAL));
        assertTrue(texts.stream().allMatch(text -> (text.color() >>> 24) == 128));
        assertTrue(texts.stream().allMatch(text -> text.lightCoords() == LightTexture.FULL_BRIGHT));
        assertTrue(texts.stream().allMatch(text -> text.backgroundColor() == 0 && text.outlineColor() == 0));
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
    @Test void nativeTextQueueRetainsVisibleContentColorsAndLightingAcrossStatesAndFade() {
        for (var tone : TaskBubbleState.Tone.values()) {
            var progress = new ItemGoal.Progress("minecraft:cobblestone", 3, 12, true, ItemGoal.Scope.SUBTASK, false);
            var state = new TaskBubbleState.Snapshot(1, "收集材料", tone, "正在挖掘",
                    "等待实际入包", progress, 7800);
            var layout = TaskBubbleLayout.create(state, "Bot", "圆石", s -> s.length() * 9);
            for (var palette : List.of(
                    new CompanionTaskBubble.Palette(0xEDF8EE, 0xA3B9A9, 0x243B2D, 0x536B5A, 0x356F52),
                    new CompanionTaskBubble.Palette(0x222C28, 0x66786B, 0xF1F6F2, 0xC2CEC5, 0xA8D9B8))) {
                for (float opacity : new float[]{1, .5f, .1f}) {
                    var frame = new CompanionTaskBubble.Frame(new Vec3(0, 0, -5), .012f, opacity, layout, palette);
                    var storage = new SubmitNodeStorage();
                    CompanionTaskBubble.submit(new PoseStack(), storage, Vec3.ZERO, new Quaternionf(), frame);
                    var texts = storage.order(0).getTextSubmits();
                    assertEquals(layout.lines().size(), texts.size());
                    for (int i = 0; i < texts.size(); i++) {
                        var text = texts.get(i);
                        var line = layout.lines().get(i);
                        assertEquals(LightTexture.FULL_BRIGHT, text.lightCoords());
                        int rgb = switch (line.ink()) {
                            case PRIMARY -> palette.text();
                            case SECONDARY -> palette.muted();
                            case ACCENT -> palette.accent();
                        };
                        assertEquals(Math.round(255 * opacity) << 24 | rgb, text.color());
                        assertEquals(0, text.backgroundColor());
                        assertEquals(0, text.outlineColor());
                        assertEquals(Font.DisplayMode.NORMAL, text.displayMode());
                        assertFalse(text.dropShadow());
                        assertEquals(line.x(), text.x());
                        assertEquals(line.y(), text.y());
                        var content = new StringBuilder();
                        text.string().accept((index, style, codePoint) -> {
                            content.appendCodePoint(codePoint);
                            return true;
                        });
                        assertEquals(line.text(), content.toString());
                    }
                }
            }
        }
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
