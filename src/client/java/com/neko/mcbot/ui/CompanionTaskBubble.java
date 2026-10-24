package com.neko.mcbot.ui;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.neko.mcbot.McbotClient;
import com.neko.mcbot.agent.TaskBubbleState;
import net.fabricmc.fabric.api.client.rendering.v1.RenderStateDataKey;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldExtractionContext;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.OrderedSubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionfc;

/** Owner-local, depth-tested billboard. Extraction alone reads task and entity state. */
public final class CompanionTaskBubble {
    private static final RenderStateDataKey<Frame> FRAME = RenderStateDataKey.create(() -> "mcbot:task_bubble");
    record Palette(int background, int border, int text, int muted, int accent) {}
    record Frame(Vec3 anchor, float scale, float opacity, TaskBubbleLayout.Layout layout,
                 Palette palette, float rotation) {
        Frame(Vec3 anchor, float scale, float opacity, TaskBubbleLayout.Layout layout, Palette palette) {
            this(anchor, scale, opacity, layout, palette, 0);
        }
    }
    private CompanionTaskBubble() {}
    public static void register() {
        WorldRenderEvents.END_EXTRACTION.register(CompanionTaskBubble::extract);
        WorldRenderEvents.BEFORE_ENTITIES.register(CompanionTaskBubble::render);
    }
    private static void extract(WorldExtractionContext context) {
        context.worldState().setData(FRAME, null);
        var mc = Minecraft.getInstance();
        var runner = McbotClient.runner();
        if (runner == null || mc.player == null || mc.level != context.world() || mc.screen != null || mc.options.hideGui
                || !runner.config().preferences.showHud() || runner.companionUuid() == null) return;
        var state = runner.taskBubbleSnapshot();
        if (state == null) return;
        var entity = context.world().getPlayerByUUID(runner.companionUuid());
        if (entity == null || entity.isRemoved() || !entity.isAlive() || entity.isInvisibleTo(mc.player)) return;
        float partial = context.tickCounter().getGameTimeDeltaPartialTick(false);
        Vec3 position = entity.getPosition(partial);
        Vec3 anchor = position.add(0, entity.getBbHeight() + .72, 0);
        Vec3 relative = anchor.subtract(context.camera().position());
        double distance = relative.length();
        if (distance >= TaskBubbleVisibility.MAX_DISTANCE) return;
        var forward = context.camera().forwardVector();
        boolean front = relative.x * forward.x() + relative.y * forward.y() + relative.z * forward.z() > 0;
        boolean inFrustum = context.frustum().pointInFrustum(anchor.x, anchor.y, anchor.z);
        if (!front || !inFrustum) return;
        // Test the visible body as well as the anchor: a high bubble must not reveal a bot behind a wall.
        boolean clear = context.world().clip(new ClipContext(context.camera().position(),
                position.add(0, entity.getEyeHeight(), 0), ClipContext.Block.VISUAL,
                ClipContext.Fluid.NONE, mc.player)).getType() == HitResult.Type.MISS;
        float opacity = TaskBubbleVisibility.opacity(distance, front, inFrustum, clear);
        if (opacity <= 0) return;
        String item = "";
        if (state.progress() != null) {
            var key = Identifier.tryParse(state.progress().item());
            item = key != null && BuiltInRegistries.ITEM.containsKey(key)
                    ? BuiltInRegistries.ITEM.getValue(key).getName().getString() : state.progress().item();
        }
        var layout = TaskBubbleLayout.create(state, runner.companionName(), item, mc.font::width);
        context.worldState().setData(FRAME, new Frame(anchor, TaskBubbleVisibility.scale(distance), opacity,
                layout, palette(runner.config().preferences.dark(), state.tone()), rotation(System.nanoTime())));
    }
    private static void render(WorldRenderContext context) {
        Frame frame = context.worldState().getData(FRAME);
        if (frame == null) return;
        var camera = context.worldState().cameraRenderState;
        submit(context.matrices(), context.commandQueue(), camera.pos, camera.orientation, frame);
    }
    static void submit(PoseStack pose, OrderedSubmitNodeCollector queue, Vec3 camera,
                       Quaternionfc orientation, Frame frame) {
        pose.pushPose();
        pose.translate(frame.anchor.x - camera.x, frame.anchor.y - camera.y, frame.anchor.z - camera.z);
        pose.mulPose(orientation);
        pose.scale(frame.scale, -frame.scale, frame.scale);
        pose.translate(-TaskBubbleLayout.WIDTH / 2f, -TaskBubbleLayout.HEIGHT - 6, 0);
        // The background uses POSITION_COLOR_LIGHTMAP, NORMAL text retains the world's depth test.
        queue.submitCustomGeometry(pose, RenderTypes.textBackground(), (matrix, vertices) -> background(matrix, vertices, frame));
        pose.translate(0, 0, .6);
        for (var line : frame.layout.lines()) {
            int color = switch (line.ink()) {
                case PRIMARY -> frame.palette.text;
                case SECONDARY -> frame.palette.muted;
                case ACCENT -> frame.palette.accent;
            };
            // Unlike Font.drawInBatch, the submit queue takes packed light before text color.
            queue.submitText(pose, line.x(), line.y(), Component.literal(line.text()).getVisualOrderText(),
                    false, Font.DisplayMode.NORMAL, LightTexture.FULL_BRIGHT, alpha(color, frame.opacity), 0, 0);
        }
        pose.popPose();
    }
    private static void background(PoseStack.Pose matrix, VertexConsumer vertices, Frame frame) {
        int w = TaskBubbleLayout.WIDTH, h = TaskBubbleLayout.HEIGHT;
        int edge = alpha(frame.palette.border, frame.opacity), fill = alpha(frame.palette.background, frame.opacity);
        rounded(matrix, vertices, 0, 0, w, h, 3, 0, edge);
        rounded(matrix, vertices, 1, 1, w - 1, h - 1, 2, .2f, fill);
        quad(matrix, vertices, w / 2f - 5, h, w / 2f + 5, h + 2, 0, edge);
        quad(matrix, vertices, w / 2f - 3, h + 2, w / 2f + 3, h + 4, 0, edge);
        quad(matrix, vertices, w / 2f - 1, h + 4, w / 2f + 1, h + 6, 0, edge);
        quad(matrix, vertices, w / 2f - 4, h - 1, w / 2f + 4, h + 1, .2f, fill);
        quad(matrix, vertices, w / 2f - 2, h + 1, w / 2f + 2, h + 3, .2f, fill);
        if (frame.layout.progress() >= 0) {
            quad(matrix, vertices, 8, 70, w - 8, 72, .3f, edge);
            if (frame.layout.progress() > 0)
                quad(matrix, vertices, 8, 70, 8 + (w - 16) * frame.layout.progress(), 72, .4f,
                        alpha(frame.palette.accent, frame.opacity));
        }
        if (frame.layout.spinning()) spinner(matrix, vertices, frame);
    }
    static float rotation(long nanos) {
        return Math.floorMod(nanos, 1_200_000_000L) / 1_200_000_000f * (float) (2 * Math.PI);
    }
    private static void spinner(PoseStack.Pose pose, VertexConsumer vertices, Frame frame) {
        for (int i = 0; i < 10; i++) {
            double angle = frame.rotation - i * Math.PI / 5;
            float x = (float) Math.cos(angle), y = (float) Math.sin(angle);
            float cx = TaskBubbleLayout.PADDING + 5, cy = 26;
            int color = alpha(frame.palette.accent, frame.opacity * (1 - i * .075f));
            vertices.addVertex(pose, cx + 3 * x - .6f * y, cy + 3 * y + .6f * x, .4f)
                    .setColor(color).setLight(LightTexture.FULL_BRIGHT);
            vertices.addVertex(pose, cx + 5 * x - .6f * y, cy + 5 * y + .6f * x, .4f)
                    .setColor(color).setLight(LightTexture.FULL_BRIGHT);
            vertices.addVertex(pose, cx + 5 * x + .6f * y, cy + 5 * y - .6f * x, .4f)
                    .setColor(color).setLight(LightTexture.FULL_BRIGHT);
            vertices.addVertex(pose, cx + 3 * x + .6f * y, cy + 3 * y - .6f * x, .4f)
                    .setColor(color).setLight(LightTexture.FULL_BRIGHT);
        }
    }
    private static void rounded(PoseStack.Pose pose, VertexConsumer vertices, float x, float y,
                                float right, float bottom, int radius, float z, int color) {
        quad(pose, vertices, x + radius, y, right - radius, bottom, z, color);
        quad(pose, vertices, x, y + radius, x + radius, bottom - radius, z, color);
        quad(pose, vertices, right - radius, y + radius, right, bottom - radius, z, color);
        quad(pose, vertices, x + 1, y + 1, x + radius, y + radius, z, color);
        quad(pose, vertices, right - radius, y + 1, right - 1, y + radius, z, color);
        quad(pose, vertices, x + 1, bottom - radius, x + radius, bottom - 1, z, color);
        quad(pose, vertices, right - radius, bottom - radius, right - 1, bottom - 1, z, color);
    }
    private static void quad(PoseStack.Pose pose, VertexConsumer vertices, float x, float y,
                             float right, float bottom, float z, int color) {
        vertices.addVertex(pose, x, y, z).setColor(color).setLight(LightTexture.FULL_BRIGHT);
        vertices.addVertex(pose, x, bottom, z).setColor(color).setLight(LightTexture.FULL_BRIGHT);
        vertices.addVertex(pose, right, bottom, z).setColor(color).setLight(LightTexture.FULL_BRIGHT);
        vertices.addVertex(pose, right, y, z).setColor(color).setLight(LightTexture.FULL_BRIGHT);
    }
    private static int alpha(int rgb, float opacity) {
        return Math.round(255 * opacity) << 24 | rgb & 0xFFFFFF;
    }
    private static Palette palette(boolean dark, TaskBubbleState.Tone tone) {
        int accent = switch (tone) {
            case QUESTION -> dark ? 0xF4C66D : 0x8A5B13;
            case FAILED -> dark ? 0xF3A49F : 0xA33E3B;
            case CANCELLED -> dark ? 0xB5BDC0 : 0x626B70;
            default -> dark ? 0xA8D9B8 : 0x356F52;
        };
        int background = dark ? 0x222C28 : switch (tone) {
            case QUESTION -> 0xFFF3D7;
            case FAILED -> 0xFBE9E7;
            case CANCELLED -> 0xECEEEF;
            default -> 0xEDF8EE;
        };
        return new Palette(background, dark ? 0x66786B : 0xA3B9A9,
                dark ? 0xF1F6F2 : 0x243B2D, dark ? 0xC2CEC5 : 0x536B5A, accent);
    }
}
