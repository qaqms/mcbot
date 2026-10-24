package com.neko.mcbot.server;

import com.neko.mcbot.body.CompanionPlayer;
import com.neko.mcbot.building.*;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

/** Native observations for the task's shared worksite. Never loads a missing chunk. */
public final class BuildSiteAccess implements BuildSite.Access {
    private final CompanionPlayer body;
    private MiningTools tools;
    public BuildSiteAccess(CompanionPlayer body) { this.body = body; }
    @Override public BlockState state(BlockPos pos) {
        var level = body.level();
        return level.isInWorldBounds(pos) && level.getWorldBorder().isWithinBounds(pos) && level.isLoaded(pos)
                ? level.getBlockState(pos) : null;
    }
    @Override public boolean corridor(BlockPos feet) {
        var floor = state(feet.below());
        var lower = state(feet); var upper = state(feet.above());
        return body.getBbWidth() <= 1 && body.getBbHeight() <= 2
                && BuildSite.plainCube(floor) && lower != null && lower.isAir() && upper != null && upper.isAir();
    }
    @Override public boolean facility(BlockPos pos) {
        var state = state(pos);
        return state == null || WorksiteProtection.facility(state, state.hasBlockEntity());
    }
    @Override public boolean occupied(BlockPos pos) {
        var region = new AABB(pos).expandTowards(0, 2, 0);
        if (region.intersects(body.getBoundingBox())) return true;
        var found = new java.util.ArrayList<net.minecraft.world.entity.LivingEntity>();
        body.level().getEntities(net.minecraft.world.level.entity.EntityTypeTest.forClass(
                net.minecraft.world.entity.LivingEntity.class), region, entity -> entity.isAlive(), found, 1);
        return !found.isEmpty();
    }
    @Override public boolean material(BuildTarget.Cell cell) {
        return cell.authored().isAir() || cell.material().item() != null
                && BuildInventory.available(body.getInventory(), cell.material().item()) >= cell.material().units();
    }
    @Override public Object chunk(BlockPos pos) {
        return body.level().getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);
    }
    @Override public CellWitness watch(BlockPos pos) {
        return chunk(pos) instanceof TerrainVersion version ? version.mcbot$watchCell(pos) : null;
    }
    @Override public boolean passage(BlockPos pos) { return body.navigationTrail().protectsPassage(pos); }
    @Override public boolean passageComplete() { return body.navigationTrail().passageComplete(); }
    @Override public boolean canMine(BlockState state) {
        if (tools == null) tools = MiningTools.forPlayer(body);
        return tools.best(state) != null;
    }
}
