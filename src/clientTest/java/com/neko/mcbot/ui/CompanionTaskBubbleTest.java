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
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;
import javax.imageio.metadata.IIOMetadataNode;
import javax.imageio.stream.FileImageOutputStream;
import javax.imageio.IIOImage;

import static org.junit.jupiter.api.Assertions.*;

class CompanionTaskBubbleTest {
    @Test void spinnerVerticesRotateWithCapturedTimeWithinTheReservedAreaAndRespectFade() {
        var state = new TaskBubbleState.Snapshot(1, "work", TaskBubbleState.Tone.WORKING,
                "模型正在思考", "", null, 0, true);
        var layout = TaskBubbleLayout.create(state, s -> s.length() * 9);
        var base = frame(Vec3.ZERO);
        var frames = new ArrayList<List<float[]>>();
        for (long ms : new long[]{0, 300}) {
            var capture = new Capture();
            CompanionTaskBubble.submit(new PoseStack(), capture.queue, Vec3.ZERO, new Quaternionf(),
                    new CompanionTaskBubble.Frame(Vec3.ZERO, 1, .5f, layout, base.palette(),
                            new TaskBubbleMotion.Sample(state.action(), false, false, 1,
                                    TaskBubbleMotion.rotation(ms), TaskBubbleMotion.catFrame(ms))));
            var vertices = new ArrayList<float[]>();
            var colors = new ArrayList<Integer>();
            VertexConsumer sink = (VertexConsumer) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{VertexConsumer.class}, (proxy, method, args) -> {
                        if (method.isDefault())
                            return java.lang.reflect.InvocationHandler.invokeDefault(proxy, method, args);
                        if (method.getName().equals("addVertex") && args.length == 3) {
                            if ((float) args[2] == .4f) vertices.add(new float[]{
                                    (float) args[0], (float) args[1], (float) args[2]});
                        }
                        if (method.getName().equals("setColor") && vertices.size() > colors.size())
                            colors.add((int) args[0]);
                        if (method.getName().equals("setLight")) return proxy;
                        return proxy;
                    });
            capture.geometry.getFirst().draw.render(new PoseStack().last(), sink);
            assertEquals(40, vertices.size());
            for (var point : vertices) {
                assertTrue(point[0] >= layout.spinnerX() - 5.2f && point[0] <= layout.spinnerX() + 5.2f);
                assertTrue(point[1] >= 8.8f && point[1] <= 19.2f);
            }
            assertTrue(colors.stream().allMatch(c -> c >>> 24 > 0 && c >>> 24 <= 128));
            frames.add(vertices);
        }
        assertNotEquals(frames.get(0).getFirst()[0], frames.get(1).getFirst()[0]);
        assertEquals(TaskBubbleMotion.rotation(0), TaskBubbleMotion.rotation(2400));
        assertTrue(TaskBubbleMotion.rotation(-1) >= 0);
    }
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
        var layout = TaskBubbleLayout.create(state, s -> s.length() * 6);
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
                            assertTrue((int) args[0] >>> 24 > 0 && (int) args[0] >>> 24 <= 128);
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
            var layout = TaskBubbleLayout.create(state, s -> s.length() * 9);
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
                new Vector3f(frame(anchor).layout().width() / 2f, frame(anchor).layout().height() + TaskBubbleLayout.TAIL, 0));
        assertEquals(3, tip.x, .0001);
        assertEquals(4, tip.y, .0001);
        assertEquals(-5, tip.z, .0001);
        var second = new Capture();
        CompanionTaskBubble.submit(pose, second.queue, camera, orientation, frame(anchor.add(1, .4, -2)));
        var next = second.geometry.getFirst().pose.pose().transformPosition(
                new Vector3f(frame(anchor).layout().width() / 2f, frame(anchor).layout().height() + TaskBubbleLayout.TAIL, 0));
        assertEquals(1, next.x - tip.x, .0001);
        assertEquals(.4, next.y - tip.y, .0001);
        assertEquals(-2, next.z - tip.z, .0001);
    }

    @Test void nativeWaitingGeometryProducesAVisibleAnimatedPreview() throws Exception {
        var state = new TaskBubbleState.Snapshot(7, "你好", TaskBubbleState.Tone.WORKING,
                "模型正在思考", "等待模型响应 · 1 秒", null, 0, true);
        var motion = new TaskBubbleMotion();
        var full = TaskBubbleLayout.create(state, CompanionTaskBubbleTest::previewWidth);
        var writer = ImageIO.getImageWritersByFormatName("gif").next();
        Path directory = Path.of("build", "bubble-preview");
        Files.createDirectories(directory);
        BufferedImage first = null, licking = null;
        try (var output = new FileImageOutputStream(directory.resolve("thinking.gif").toFile())) {
            writer.setOutput(output);
            writer.prepareWriteSequence(null);
            for (int ms = 0; ms < TaskBubbleMotion.CAT_MS; ms += 100) {
                var sample = motion.sample(state, full.message().text(), ms);
                var frame = new CompanionTaskBubble.Frame(Vec3.ZERO, 1, sample.opacity(),
                        full.reveal(sample.text(), CompanionTaskBubbleTest::previewWidth),
                        CompanionTaskBubble.palette(false, state.tone()), sample);
                BufferedImage image = rasterize(frame, 720, 240, 3);
                if (ms == 0) first = image;
                if (ms == 2300) licking = image;
                var metadata = writer.getDefaultImageMetadata(
                        javax.imageio.ImageTypeSpecifier.createFromRenderedImage(image), null);
                String format = metadata.getNativeMetadataFormatName();
                var root = (IIOMetadataNode) metadata.getAsTree(format);
                var control = (IIOMetadataNode) root.getElementsByTagName("GraphicControlExtension").item(0);
                control.setAttribute("disposalMethod", "none");
                control.setAttribute("delayTime", "10");
                if (ms == 0) {
                    var extensions = new IIOMetadataNode("ApplicationExtensions");
                    var loop = new IIOMetadataNode("ApplicationExtension");
                    loop.setAttribute("applicationID", "NETSCAPE");
                    loop.setAttribute("authenticationCode", "2.0");
                    loop.setUserObject(new byte[]{1, 0, 0});
                    extensions.appendChild(loop);
                    root.appendChild(extensions);
                }
                metadata.setFromTree(format, root);
                writer.writeToSequence(new IIOImage(image, null, metadata), null);
            }
            writer.endWriteSequence();
        } finally {
            writer.dispose();
        }
        assertNotNull(first);
        assertNotNull(licking);
        int changed = 0, ink = 0;
        for (int y = 0; y < licking.getHeight(); y++) for (int x = 0; x < licking.getWidth(); x++) {
            if (first.getRGB(x, y) != licking.getRGB(x, y)) changed++;
            if (licking.getRGB(x, y) != new Color(0xCBD7DB).getRGB()) ink++;
        }
        assertTrue(changed > 1000);
        assertTrue(ink > 10000 && ink < 720 * 120);
        ImageIO.write(licking, "png", directory.resolve("thinking.png").toFile());

        var sheet = new BufferedImage(960, 480, BufferedImage.TYPE_INT_RGB);
        var graphics = sheet.createGraphics();
        graphics.setColor(new Color(0xCBD7DB));
        graphics.fillRect(0, 0, sheet.getWidth(), sheet.getHeight());
        var examples = List.of(
                state,
                new TaskBubbleState.Snapshot(7, "", TaskBubbleState.Tone.WORKING, "正在挖掘", "",
                        new ItemGoal.Progress("minecraft:cobblestone", 3, 12, true, ItemGoal.Scope.SUBTASK, false), 0),
                new TaskBubbleState.Snapshot(7, "", TaskBubbleState.Tone.QUESTION, "想问你一下", "要继续往前走吗？", null, 7800),
                new TaskBubbleState.Snapshot(7, "", TaskBubbleState.Tone.FAILED, "任务未完成", "暂时找不到可以到达的路线", null, 0));
        for (int i = 0; i < examples.size(); i++) {
            var example = examples.get(i);
            var layout = TaskBubbleLayout.create(example, CompanionTaskBubbleTest::previewWidth);
            var sample = new TaskBubbleMotion.Sample(example.action(), false, false, 1,
                    TaskBubbleMotion.rotation(2300), 3);
            var frame = new CompanionTaskBubble.Frame(Vec3.ZERO, 1, 1, layout,
                    CompanionTaskBubble.palette(i >= 2, example.tone()), sample);
            graphics.drawImage(rasterize(frame, 480, 240, 2), i % 2 * 480, i / 2 * 240, null);
        }
        graphics.dispose();
        ImageIO.write(sheet, "png", directory.resolve("states.png").toFile());
    }

    private static int previewWidth(String text) {
        return text.codePoints().map(c -> c < 128 ? 5 : 9).sum();
    }

    /** Rasterize the production queue's actual quads and text, without a game or model. */
    private BufferedImage rasterize(CompanionTaskBubble.Frame frame, int width, int height, int scale) {
        var image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setColor(new Color(0xCBD7DB));
        graphics.fillRect(0, 0, width, height);
        graphics.translate((width - frame.layout().width() * scale) / 2, 85);
        graphics.scale(scale, scale);
        var capture = new Capture();
        CompanionTaskBubble.submit(new PoseStack(), capture.queue, Vec3.ZERO, new Quaternionf(), frame);
        var points = new ArrayList<float[]>();
        VertexConsumer sink = (VertexConsumer) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{VertexConsumer.class}, (proxy, method, args) -> {
                    if (method.isDefault()) return java.lang.reflect.InvocationHandler.invokeDefault(proxy, method, args);
                    if (method.getName().equals("addVertex"))
                        points.add(new float[]{(float) args[0], (float) args[1]});
                    if (method.getName().equals("setColor") && points.size() == 4) {
                        var polygon = new Path2D.Float();
                        polygon.moveTo(points.getFirst()[0], points.getFirst()[1]);
                        for (int i = 1; i < 4; i++) polygon.lineTo(points.get(i)[0], points.get(i)[1]);
                        polygon.closePath();
                        graphics.setColor(new Color((int) args[0], true));
                        graphics.fill(polygon);
                        points.clear();
                    }
                    return proxy;
                });
        capture.geometry.getFirst().draw.render(new PoseStack().last(), sink);
        assertTrue(points.isEmpty());
        graphics.setFont(new java.awt.Font("Microsoft YaHei", java.awt.Font.PLAIN, 9));
        graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        for (var text : capture.storage.order(0).getTextSubmits()) {
            var content = new StringBuilder();
            text.string().accept((index, style, codePoint) -> { content.appendCodePoint(codePoint); return true; });
            graphics.setColor(new Color(text.color(), true));
            graphics.drawString(content.toString(), text.x(), text.y() + 8);
        }
        graphics.dispose();
        return image;
    }
}
