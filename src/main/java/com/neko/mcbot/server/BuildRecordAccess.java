package com.neko.mcbot.server;

import com.google.gson.JsonObject;
import com.neko.mcbot.McbotMod;
import com.neko.mcbot.body.CompanionPlayer;
import com.neko.mcbot.building.*;
import net.minecraft.core.BlockPos;
import java.util.Set;

/** Identity adapter only; all construction still uses the original body and action gates. */
public final class BuildRecordAccess {
    private BuildRecordAccess() { }
    public static BuildRecords store(CompanionPlayer body) {
        return McbotMod.buildRecords(body.level().getServer());
    }
    public static BuildRecords.Instance instance(CompanionPlayer body, JsonObject target) {
        BuildTarget.keys(target,Set.of("instance_id","instance_revision"));
        return store(body).instance(body.ownerUuid(),body.getUUID(),body.level().dimension().identifier().toString(),
                BuildTarget.string(target.get("instance_id"),36),BuildTarget.integer(target.get("instance_revision"),1,1024));
    }
    public static BuildTarget resolve(CompanionPlayer body, JsonObject target) {
        if (target!=null && target.has("instance_id")) return instance(body,target).target();
        var site=body.buildSite();
        return site.active() && site.target().input().equals(target) ? site.target() : BuildTarget.parse(target);
    }
    public static boolean protects(CompanionPlayer body, BlockPos pos) {
        var records=store(body);
        return records!=null && records.protects(body.level().dimension().identifier().toString(),pos);
    }
    public static ServerTool.Result guard(CompanionPlayer body, BlockPos pos, boolean mining, String revision) {
        var records=store(body);
        if (records==null) return null;
        if (!records.healthy()) return new ServerTool.Result(false,"RECORD_UNAVAILABLE:建筑记录不可用，先核对存档；未改世界。",null);
        var instance=body.buildSite().instance();
        boolean own=records.belongs(instance,body.level().dimension().identifier().toString(),pos);
        if (own && (!mining || body.buildSite().same(revision))
                && records.mayChange(instance,pos,new BuildSiteAccess(body)) && records.hasCapacity(instance,pos)) return null;
        return protects(body,pos) || own ? new ServerTool.Result(false,
                "DENIED:持久实例或支撑受保护；须原实例工地、有效逐格来源及原许可，不认领替换物。",null) : null;
    }
}
