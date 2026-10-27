package com.neko.mcbot.ui;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.neko.mcbot.McbotClient;
import com.neko.mcbot.agent.TaskBubbleState;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.RenderStateDataKey;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldExtractionContext;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.OrderedSubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionfc;

import java.util.UUID;

/** Owner-local, depth-tested billboard. Extraction alone reads task and entity state. */
public final class CompanionTaskBubble {
    private static final RenderStateDataKey<Frame> FRAME = RenderStateDataKey.create(() -> "mcbot:task_bubble");
    private static final TaskBubbleMotion MOTION = new TaskBubbleMotion();
    private static Object boundRunner, boundWorld;
    private static UUID boundBody;
    record Palette(int background, int border, int text, int muted, int accent) {}
    record Frame(Vec3 anchor, float scale, float opacity, TaskBubbleLayout.Layout layout,
                 Palette palette, TaskBubbleMotion.Sample motion) {
        Frame(Vec3 anchor, float scale, float opacity, TaskBubbleLayout.Layout layout, Palette palette) {
            this(anchor, scale, opacity, layout, palette,
                    new TaskBubbleMotion.Sample(layout.message().text(), false, false, 1, 0));
        }
    }
    private CompanionTaskBubble() {}
    public static void register() {
        WorldRenderEvents.END_EXTRACTION.register(CompanionTaskBubble::extract);
        WorldRenderEvents.BEFORE_ENTITIES.register(CompanionTaskBubble::render);
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> clear());
    }
    private static void clear() {
        MOTION.clear();
        boundRunner = boundWorld = null;
        boundBody = null;
    }
    private static void extract(WorldExtractionContext context) {
        context.worldState().setData(FRAME, null);
        var mc = Minecraft.getInstance();
        var runner = McbotClient.runner();
        if (runner == null || mc.player == null || mc.level != context.world() || runner.companionUuid() == null) {
            clear();
            return;
        }
        if (runner != boundRunner || mc.level != boundWorld || !runner.companionUuid().equals(boundBody)) {
            MOTION.clear();
            boundRunner = runner;
            boundWorld = mc.level;
            boundBody = runner.companionUuid();
        }
        var state = runner.taskBubbleSnapshot();
        if (state == null) { MOTION.clear(); return; }
        var layout = TaskBubbleLayout.create(state, mc.font::width);
        var motion = MOTION.sample(state, layout.message().text(), System.nanoTime() / 1_000_000);
        layout = layout.reveal(motion.text(), mc.font::width);
        if (mc.screen != null || mc.options.hideGui || !runner.config().preferences.showHud()) return;
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
        context.worldState().setData(FRAME, new Frame(anchor, TaskBubbleVisibility.scale(distance),
                opacity * motion.opacity(), layout, palette(runner.config().preferences.dark(), state.tone()), motion));
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
        pose.translate(-frame.layout.width() / 2f, -frame.layout.height() - TaskBubbleLayout.TAIL, 0);
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
        int w = frame.layout.width(), h = frame.layout.height();
        int edge = alpha(frame.palette.border, frame.opacity);
        int fill = alpha(frame.palette.background, frame.opacity * .95f);
        quad(matrix, vertices, 0, 0, w, h, 0, edge);
        quad(matrix, vertices, 1, 1, w - 1, h - 1, .2f, fill);
        quad(matrix, vertices, w / 2f - 4, h, w / 2f + 4, h + 2, 0, edge);
        quad(matrix, vertices, w / 2f - 2, h + 2, w / 2f + 2, h + TaskBubbleLayout.TAIL, 0, edge);
        quad(matrix, vertices, w / 2f - 3, h - 1, w / 2f + 3, h + 1, .2f, fill);
        quad(matrix, vertices, w / 2f - 1, h + 1, w / 2f + 1, h + 3, .2f, fill);
        quad(matrix, vertices, 1, 8, 3, 20, .3f, alpha(frame.palette.accent, frame.opacity * .7f));
        if (frame.motion.caret()) {
            int x = frame.layout.caretX(), y = frame.layout.message().y();
            quad(matrix, vertices, x, y, x + 1, y + 9, .3f, alpha(frame.palette.accent, frame.opacity));
        }
        if (frame.layout.spinning()) spinner(matrix, vertices, frame);
    }
    private static void spinner(PoseStack.Pose pose, VertexConsumer vertices, Frame frame) {
        for (int i = 0; i < 10; i++) {
            double angle = frame.motion.rotation() - i * Math.PI / 5;
            float x = (float) Math.cos(angle), y = (float) Math.sin(angle);
            float cx = frame.layout.spinnerX(), cy = 14;
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
    static Palette palette(boolean dark, TaskBubbleState.Tone tone) {
        int accent = switch (tone) {
            case QUESTION -> dark ? 0xE4B1C2 : 0x985C75;
            case FAILED -> dark ? 0xEFA8A3 : 0xA34450;
            case COMPLETED -> dark ? 0xA5D6BF : 0x427C66;
            case CANCELLED -> dark ? 0xBAC4CA : 0x677681;
            default -> dark ? 0xA1CBCE : 0x507A8B;
        };
        int background = dark ? 0x293640 : switch (tone) {
            case QUESTION -> 0xEFE3EB;
            case FAILED -> 0xF1DFE2;
            case COMPLETED -> 0xDDEAE5;
            case CANCELLED -> 0xE2E6E9;
            default -> 0xDFE9ED;
        };
        return new Palette(background, dark ? 0x5D7581 : 0xA4B9C5,
                dark ? 0xE6EFF1 : 0x293F4A, dark ? 0xB9C9CF : 0x627581, accent);
    }
}
