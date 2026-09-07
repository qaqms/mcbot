package com.neko.mcbot.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.neko.mcbot.McbotMod;
import com.neko.mcbot.body.CompanionRoster;
import com.neko.mcbot.body.SummonService;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.function.Supplier;

/** /mcbot summon|dismiss|list|ping —— 玩家与控制台均可（游戏管理员级别权限）。 */
public final class McbotCommands {

    private McbotCommands() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("mcbot")
                .requires(source -> guarded(source,
                        () -> Commands.hasPermission(Commands.LEVEL_GAMEMASTERS).test(source)))
                .then(Commands.literal("ping").executes(ctx -> guardedR(ctx, () -> {
                    reply(ctx, "pong");
                    return 1;
                })))
                .then(Commands.literal("summon")
                        .then(Commands.argument("name", StringArgumentType.word())
                                .executes(ctx -> guardedR(ctx, () -> doSummon(ctx)))))
                .then(Commands.literal("dismiss")
                        .then(Commands.argument("name", StringArgumentType.word())
                                .executes(ctx -> guardedR(ctx, () -> doDismiss(ctx)))))
                .then(Commands.literal("list").executes(ctx -> guardedR(ctx, () -> doList(ctx)))));
    }

    /** M1 调试探针：异常不再被 vanilla 控制台吞掉。稳定后可移除包装。 */
    private static boolean guarded(CommandSourceStack source, Supplier<Boolean> body) {
        try {
            return body.get();
        } catch (Throwable t) {
            McbotMod.LOG.error("mcbot 权限检查异常", t);
            return false;
        }
    }

    private static int guardedR(CommandContext<CommandSourceStack> ctx, Supplier<Integer> body) {
        try {
            return body.get();
        } catch (Throwable t) {
            McbotMod.LOG.error("mcbot 命令执行异常", t);
            fail(ctx, "内部错误，详见服务端日志");
            return 0;
        }
    }

    private static int doSummon(CommandContext<CommandSourceStack> ctx) {
        SummonService service = McbotMod.summonService();
        if (service == null) {
            return fail(ctx, "服务器尚未就绪");
        }
        String name = StringArgumentType.getString(ctx, "name");
        ServerPlayer owner = ctx.getSource().getPlayer(); // 控制台时为 null
        return reply(ctx, service.summon(owner, name));
    }

    private static int doDismiss(CommandContext<CommandSourceStack> ctx) {
        SummonService service = McbotMod.summonService();
        if (service == null) {
            return fail(ctx, "服务器尚未就绪");
        }
        String name = StringArgumentType.getString(ctx, "name");
        ServerPlayer requester = ctx.getSource().getPlayer();
        return reply(ctx, service.dismiss(requester, name));
    }

    private static int doList(CommandContext<CommandSourceStack> ctx) {
        SummonService service = McbotMod.summonService();
        if (service == null) {
            return fail(ctx, "服务器尚未就绪");
        }
        CompanionRoster roster = service.roster();
        reply(ctx, "同伴名册 (" + roster.entries().size() + ")");
        for (CompanionRoster.Entry e : roster.entries()) {
            boolean online = ctx.getSource().getServer().getPlayerList().getPlayer(e.uuid()) != null;
            reply(ctx, "  - " + e.name() + "  主人=" + e.ownerName() + (online ? "  [在线]" : "  [离线]"));
        }
        return 1;
    }

    private static int reply(CommandContext<CommandSourceStack> ctx, String text) {
        ctx.getSource().sendSuccess(() -> Component.literal(text), true);
        return 1;
    }

    private static int fail(CommandContext<CommandSourceStack> ctx, String text) {
        ctx.getSource().sendFailure(Component.literal(text));
        return 0;
    }
}
