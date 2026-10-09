package com.neko.mcbot;

import com.mojang.blaze3d.platform.InputConstants;
import com.neko.mcbot.agent.AgentRunner;
import com.neko.mcbot.cfg.ClientConfig;
import com.neko.mcbot.common.Envelope;
import com.neko.mcbot.common.McbotPayloads;
import com.neko.mcbot.ui.McbotPanelScreen;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.KeyMapping;
import org.lwjgl.glfw.GLFW;

/** 客户端入口：agent loop 宿主、S2C 通道与 G 面板。 */
public final class McbotClient implements ClientModInitializer {

    private static volatile AgentRunner runner;
    private static KeyMapping panelKey;

    public static AgentRunner runner() {
        return runner;
    }

    @Override
    public void onInitializeClient() {
        McbotMod.LOG.info("mcbot client part initializing");

        panelKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "key.mcbot.panel", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_G,
                KeyMapping.Category.MISC));

        ClientPlayNetworking.registerGlobalReceiver(McbotPayloads.S2c.TYPE, (payload, context) -> {
            Envelope env = Envelope.decode(payload.json);
            AgentRunner target = runner;
            if (env != null && target != null) {
                context.client().execute(() -> {
                    if (runner == target) target.handleS2c(env);
                });
            }
        });

        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            runner = new AgentRunner(ClientConfig.load());
            runner.start();
            runner.sendLifecycle("companion_status", "");
            com.neko.mcbot.bridge.BridgeHttp.ensureStarted();
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            AgentRunner old = runner;
            if (old != null) old.close();
            com.neko.mcbot.bridge.BridgeHttp.shutdown();
            runner = null;
        });

        // @bot 前缀即指令，不进入服务器聊天
        ClientSendMessageEvents.ALLOW_CHAT.register(message -> {
            if (message == null || message.isBlank() || !message.startsWith("@bot")) {
                return true;
            }
            String directive = message.substring(4).trim();
            if (directive.isEmpty()) {
                return true;
            }
            AgentRunner r = runner;
            if (r != null) {
                r.onOwnerDirective(directive);
            }
            return false;
        });

        net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.END_CLIENT_TICK
                .register(client -> {
                    AgentRunner r = runner;
                    if (r != null) {
                        r.tick();
                    }
                    while (panelKey.consumeClick()) {
                        client.setScreen(new McbotPanelScreen());
                    }
                });
    }
}
