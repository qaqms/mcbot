package com.neko.mcbot.server;

import com.google.gson.JsonObject;
import com.neko.mcbot.McbotMod;
import com.neko.mcbot.body.CompanionPlayer;
import com.neko.mcbot.common.WireSize;

/** Data-only recovery of the original protection sets and construction accounts. */
public final class SpatialCheckpoint {
    public static final int LIMIT = 16000;
    private SpatialCheckpoint() { }

    public static JsonObject snapshot(CompanionPlayer body) {
        var data = new JsonObject();
        data.add("work", body.workTargets().snapshot());
        data.add("trail", body.navigationTrail().snapshot());
        data.add("site", body.buildSite().snapshot());
        if (WireSize.utf8Bytes(data.toString()) > LIMIT) throw new IllegalArgumentException("Spatial checkpoint too large");
        return data;
    }

    public static void restore(CompanionPlayer body, ActionPermissions.Context context, JsonObject data) {
        if (data == null || WireSize.utf8Bytes(data.toString()) > LIMIT) throw new IllegalArgumentException();
        body.bindSpatialTask(context);
        body.workTargets().restore(data.getAsJsonArray("work"));
        body.navigationTrail().restore(data.getAsJsonObject("trail"));
        var site = data.getAsJsonObject("site");
        body.buildSite().restore(site, StandingGeometry.feetCell(body), new BuildSiteAccess(body));
        if (site.has("instance_id")) {
            var records = McbotMod.buildRecords(body.level().getServer());
            if (records == null) throw new IllegalArgumentException();
            var selected = records.instance(body.ownerUuid(), body.getUUID(),
                    body.level().dimension().identifier().toString(), site.get("instance_id").getAsString(),
                    site.get("instance_revision").getAsBigDecimal().intValueExact());
            body.buildSite().attach(records, selected);
        }
    }
}
