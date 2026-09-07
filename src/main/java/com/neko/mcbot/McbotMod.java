package com.neko.mcbot;

import com.neko.mcbot.agentcore.AgentCore;
import com.neko.mcbot.body.CompanionRoster;
import com.neko.mcbot.body.SummonService;
import com.neko.mcbot.command.McbotCommands;
import com.neko.mcbot.common.Envelope;
import com.neko.mcbot.common.McbotPayloads;
import com.neko.mcbot.server.ServerToolDispatcher;
import com.neko.mcbot.server.ToolRegistry;
import com.neko.mcbot.server.tools.ScanAreaTool;
import com.neko.mcbot.server.tools.StatusTool;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 服务端 + 公共入口：身体（假玩家）、工具执行、跨 tick 任务、寻路都从这里装配。 */
public final class McbotMod implements ModInitializer {

    public static final String MOD_ID = "mcbot";
    public static final Logger LOG = LoggerFactory.getLogger(MOD_ID);

    private static volatile SummonService summonService;
    private static volatile ToolRegistry toolRegistry;
    private static volatile ServerToolDispatcher dispatcher;
    private static final com.neko.mcbot.task.CompanionScheduler scheduler =
            new com.neko.mcbot.task.CompanionScheduler();

    public static SummonService summonService() {
        return summonService;
    }

    public static ToolRegistry toolRegistry() {
        return toolRegistry;
    }

    public static ServerToolDispatcher dispatcher() {
        return dispatcher;
    }

    public static com.neko.mcbot.task.CompanionScheduler scheduler() {
        return scheduler;
    }

    @Override
    public void onInitialize() {
        LOG.info("mcbot initializing; agent-core version={}", AgentCore.VERSION);

        PayloadTypeRegistry.playC2S().register(McbotPayloads.C2s.TYPE, McbotPayloads.C2s.CODEC);
        PayloadTypeRegistry.playS2C().register(McbotPayloads.S2c.TYPE, McbotPayloads.S2c.CODEC);

        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            CompanionRoster roster = new CompanionRoster(server);
            roster.load();

            ToolRegistry tools = new ToolRegistry();
            tools.register(new StatusTool());
            tools.register(new ScanAreaTool());
            tools.register(new com.neko.mcbot.server.tools.BreakBlockTool());
            tools.register(new com.neko.mcbot.server.tools.CollectTool());
            tools.register(new com.neko.mcbot.server.tools.PlaceBlockTool());
            tools.register(new com.neko.mcbot.server.tools.MoveToTool());
            tools.register(new com.neko.mcbot.server.tools.TransferTool());
            tools.register(new com.neko.mcbot.server.tools.WaitTool());
            toolRegistry = tools;

            SummonService service = new SummonService(server, roster);
            summonService = service;
            service.respawnAllFromRoster();

            ServerToolDispatcher dispatcher = new ServerToolDispatcher(server, service, tools);
            McbotMod.dispatcher = dispatcher;
            ServerPlayNetworking.registerGlobalReceiver(McbotPayloads.C2s.TYPE,
                    (payload, context) -> {
                        Envelope env = Envelope.decode(payload.json);
                        if (env == null) {
                            LOG.warn("信封解析失败，丢弃 (from {})",
                                    context.player().getGameProfile().name());
                            return;
                        }
                        dispatcher.handle(context.player(), env);
                    });
        });

        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            SummonService service = summonService;
            summonService = null;
            toolRegistry = null;
            dispatcher = null;
            if (service != null) {
                service.dismissAllForShutdown();
            }
        });

        var tickCount = new int[1];
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.END_SERVER_TICK.register(server -> {
            scheduler.tick(server);
            if (++tickCount[0] >= 60) {
                com.neko.mcbot.body.SelfTest.onTick(server);
            }
        });

        CommandRegistrationCallback.EVENT.register(
                (dispatcher, registryAccess, environment) -> McbotCommands.register(dispatcher));
    }
}
