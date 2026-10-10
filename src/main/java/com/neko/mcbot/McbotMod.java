package com.neko.mcbot;

import com.neko.mcbot.agentcore.AgentCore;
import com.neko.mcbot.body.CompanionRoster;
import com.neko.mcbot.body.SummonService;
import com.neko.mcbot.command.McbotCommands;
import com.neko.mcbot.common.Envelope;
import com.neko.mcbot.common.McbotPayloads;
import com.neko.mcbot.common.WireSize;
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

        // Global receivers survive integrated-server restarts; never capture one world's dispatcher.
        ServerPlayNetworking.registerGlobalReceiver(McbotPayloads.C2s.TYPE, (payload, context) -> {
            if (payload.oversize()) {
                LOG.warn("闸①：C2S 信封超过 {}B，丢弃（来自 {}）",
                        WireSize.MAX_ENVELOPE_BYTES, context.player().getGameProfile().name());
                return;
            }
            Envelope env = Envelope.decode(payload.json);
            if (env == null) {
                LOG.warn("信封解析失败，丢弃 (from {})", context.player().getGameProfile().name());
                return;
            }
            ServerToolDispatcher current = dispatcher;
            if (current != null && current.belongsTo(context.server())) {
                current.handle(context.player(), env);
            }
        });

        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            com.neko.mcbot.path.PathTask.clearPlanCache();
            CompanionRoster roster = new CompanionRoster(server);
            roster.load();

            ToolRegistry tools = new ToolRegistry();
            tools.register(new StatusTool());
            tools.register(new com.neko.mcbot.server.tools.InventoryTool());
            tools.register(new com.neko.mcbot.server.tools.EquipTool());
            tools.register(new com.neko.mcbot.server.tools.CraftTool());
            tools.register(new com.neko.mcbot.server.tools.SmeltTool());
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

            McbotMod.dispatcher = new ServerToolDispatcher(server, service, tools);
        });

        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            scheduler.cancelAll("服务器已关闭。");
            com.neko.mcbot.path.PathTask.clearPlanCache();
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
            // 票在 scheduler 之前：传送后的下一拍，PathTask 先见到加载好的世界再搜索
            // （自锁死陷阱见 CompanionChunkPads 注释③，不可改到别处）。
            com.neko.mcbot.body.CompanionChunkPads.tick(server);
            scheduler.tick(server);
            if (++tickCount[0] >= 60) {
                // 归零重计：旧写法过了 60 拍后**每拍**进 onTick，正常运行（无 flag）时
                // 就是每秒 20 次 Files.exists——开发工装漏进生产路径。改成每 3 秒探一次，
                // 兼顾"跑起来后才补 flag"的用法（检测延迟 ≤3s）。
                tickCount[0] = 0;
                com.neko.mcbot.body.SelfTest.onTick(server);
            }
        });

        CommandRegistrationCallback.EVENT.register(
                (dispatcher, registryAccess, environment) -> McbotCommands.register(dispatcher));
    }
}
