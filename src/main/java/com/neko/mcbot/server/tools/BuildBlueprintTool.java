package com.neko.mcbot.server.tools;

import com.google.gson.JsonObject;
import com.neko.mcbot.body.CompanionPlayer;
import com.neko.mcbot.building.*;
import com.neko.mcbot.server.*;
import com.neko.mcbot.task.CompanionScheduler;
import net.minecraft.world.level.storage.LevelResource;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/** Inspection never registers work; import stores only a fully supported data-only design. */
public final class BuildBlueprintTool implements ServerTool {
    @Override public String name() { return "build_blueprint"; }
    @Override public CompletableFuture<Result> runAuthorized(CompanionPlayer body, JsonObject args,
            CompanionScheduler scheduler, ActionPermissions permissions, ActionPermissions.Context context) {
        if (body==null || context==null || !context.equals(permissions.current(context.owner(),body.getUUID(),
                context.dimension(),context.taskId(),body)) || !ActionPermissions.allowed(context,name(),args))
            return CompletableFuture.completedFuture(new Result(false,"DENIED:蓝图操作须原任务、身体和许可。",null));
        return CompletableFuture.completedFuture(execute(new StructureBlueprint(body.level().getServer().getWorldPath(LevelResource.ROOT)),
                BuildRecordAccess.store(body),context.owner(),args));
    }
    public static Result execute(StructureBlueprint source, BuildRecords store, UUID owner, JsonObject args) {
        try {
            String operation=BuildTarget.string(args.get("operation"),16);
            if (operation.equals("list")) {
                BuildTarget.keys(args,Set.of("operation","offset"));
                return new Result(true,"蓝图目录仅观察；当前只支持世界内mcbot/blueprints的有界压缩结构.nbt。",source.list(
                        args.has("offset") ? BuildTarget.integer(args.get("offset"),0,112) : 0));
            }
            if (!Set.of("inspect","import").contains(operation)) throw new IllegalArgumentException();
            BuildTarget.keys(args,operation.equals("inspect") ? Set.of("operation","name")
                    : Set.of("operation","name","source_revision","design_id"));
            String name=BuildRecords.name(args.get("name"));
            var inspection=source.inspect(name);
            var report=inspection.report().deepCopy();
            if (operation.equals("import")) {
                String expected=BuildTarget.string(args.get("source_revision"),64);
                if (!expected.equals(inspection.sourceRevision())) return new Result(false,
                        "TARGET_REVISION_CHANGED:蓝图文件与上次观察不同，未导入设计，重新inspect后决定。",report);
                if (!inspection.importable()) return new Result(false,
                        "UNSUPPORTED_BLUEPRINT:存在非法状态、不支持格、实体或额外载荷，未创建部分设计；核对分类计数。",report);
                String id=BuildRecords.name(args.get("design_id"));
                var definition=inspection.target().input(); definition.remove("anchor"); definition.remove("rotation");
                store.createDesign(owner,id,definition);
                report.addProperty("design_id",id); report.addProperty("design_revision",1); report.addProperty("imported",true);
            } else report.addProperty("imported",false);
            return new Result(true,"蓝图只转换为同源目标数据；材料/实例落点/差异和原生施工须经原build_record及建筑入口。"
                    + "不导入实体、容器内容、文本事件、命令或任意组件，不执行世界动作。",report);
        } catch (Exception invalid) {
            return new Result(false,"DENIED:蓝图格式/文件/名称/尺寸/调色板/格数/深度/字节/时间或记录存储不满足；"
                    + "仅支持有界原版压缩结构.nbt，未创建可执行工作，不重开任何任务预算。",null);
        }
    }
}
