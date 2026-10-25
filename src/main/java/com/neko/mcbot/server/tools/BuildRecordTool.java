package com.neko.mcbot.server.tools;

import com.google.gson.JsonObject;
import com.neko.mcbot.body.CompanionPlayer;
import com.neko.mcbot.building.*;
import com.neko.mcbot.server.*;
import com.neko.mcbot.task.CompanionScheduler;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/** Explicit data edits and read-only inspection; no action replay or goal restoration. */
public final class BuildRecordTool implements ServerTool {
    @Override public String name() { return "build_record"; }
    @Override public CompletableFuture<Result> runAuthorized(CompanionPlayer body, JsonObject args,
            CompanionScheduler scheduler, ActionPermissions permissions, ActionPermissions.Context context) {
        if (body==null || context==null || !context.equals(permissions.current(context.owner(),body.getUUID(),
                context.dimension(),context.taskId(),body)) || !ActionPermissions.allowed(context,name(),args))
            return CompletableFuture.completedFuture(new Result(false,"DENIED:须当前主人、身体、任务及数据修改许可。",null));
        body.bindSpatialTask(context);
        return CompletableFuture.completedFuture(execute(BuildRecordAccess.store(body),context.owner(),body.getUUID(),
                context.dimension(),body.buildSite(),new BuildSiteAccess(body),args));
    }
    public static Result execute(BuildRecords store, UUID owner, UUID builder, String dimension,
                                 BuildSite site, BuildSite.Access access, JsonObject args) {
        try {
            String operation=BuildTarget.string(args.get("operation"),24);
            int offset=args.has("offset") ? BuildTarget.integer(args.get("offset"),0,BuildTarget.LIMIT-16) : 0;
            String id=args.has("design_id") ? BuildRecords.name(args.get("design_id")) : null;
            int designRevision=args.has("design_revision") ? BuildTarget.integer(args.get("design_revision"),1,1024) : 0;
            JsonObject data;
            switch (operation) {
                case "draft_list" -> {
                    BuildTarget.keys(args,Set.of("operation"));
                    data=site.drafts().list();
                }
                case "draft_query" -> {
                    BuildTarget.keys(args,Set.of("operation","target","offset"));
                    data=site.drafts().query(args.getAsJsonObject("target"),offset);
                }
                case "design_capture" -> {
                    BuildTarget.keys(args,Set.of("operation","design_id","target"));
                    var definition=site.drafts().resolve(args.getAsJsonObject("target")).input();
                    definition.remove("anchor"); definition.remove("rotation");
                    store.createDesign(owner,Objects.requireNonNull(id),definition);
                    data=store.queryDesign(owner,id,1,0,null);
                }
                case "design_create" -> {
                    BuildTarget.keys(args,Set.of("operation","design_id","definition"));
                    store.createDesign(owner,Objects.requireNonNull(id),args.getAsJsonObject("definition"));
                    data=store.queryDesign(owner,id,1,0,null);
                }
                case "design_patch" -> {
                    BuildTarget.keys(args,Set.of("operation","design_id","design_revision","edits"));
                    store.patchDesign(owner,id,designRevision,args.getAsJsonArray("edits"));
                    data=store.queryDesign(owner,id,store.designRevision(owner,id),0,null);
                }
                case "design_query" -> {
                    BuildTarget.keys(args,Set.of("operation","design_id","design_revision","offset","layer"));
                    data=store.queryDesign(owner,id,designRevision,offset,
                            args.has("layer") ? BuildTarget.integer(args.get("layer"),-32,32) : null);
                }
                case "design_list", "instance_list" -> {
                    BuildTarget.keys(args,Set.of("operation","offset"));
                    data=store.list(owner,builder,dimension,operation.equals("design_list"),offset);
                }
                case "instance_create" -> {
                    BuildTarget.keys(args,Set.of("operation","design_id","design_revision","anchor","rotation"));
                    var anchor=args.getAsJsonObject("anchor"); BuildTarget.keys(anchor,Set.of("x","y","z"));
                    var instance=store.createInstance(owner,builder,dimension,id,designRevision,BuildRecords.position(anchor),
                            args.has("rotation") ? BuildTarget.integer(args.get("rotation"),0,270) : 0);
                    data=store.queryInstance(instance,access,0);
                }
                case "instance_query", "instance_revise" -> {
                    BuildTarget.keys(args,operation.equals("instance_query")
                            ? Set.of("operation","instance_id","instance_revision","offset")
                            : Set.of("operation","instance_id","instance_revision","design_revision"));
                    var instance=store.instance(owner,builder,dimension,BuildTarget.string(args.get("instance_id"),36),
                            BuildTarget.integer(args.get("instance_revision"),1,1024));
                    if (operation.equals("instance_revise")) {
                        if (site.active()) throw new IllegalArgumentException("frozen_worksite");
                        store.reviseInstance(instance,designRevision);
                    }
                    data=store.queryInstance(instance,access,offset);
                }
                default -> throw new IllegalArgumentException("operation");
            }
            return new Result(true,"记录已核对；设计不自动更新实例，记录不授予动作权限，不回放或自动恢复已取消工作。",data);
        } catch (RuntimeException invalid) {
            return new Result(false,"DENIED:记录参数、身份、修订、容量或存储不满足；用tool_help核对build_record。"
                    + "未保存草稿用draft_list/draft_query，design_capture需本任务实际保留的target；旧哈希不能还原图纸。"
                    + "未执行世界动作，不重开原任务预算。",null);
        }
    }
}
