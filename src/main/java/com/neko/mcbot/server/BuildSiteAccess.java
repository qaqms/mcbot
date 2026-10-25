package com.neko.mcbot.server;

import com.neko.mcbot.body.CompanionPlayer;
import com.neko.mcbot.building.*;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

/** Native observations for the task's shared worksite. Never loads a missing chunk. */
public final class BuildSiteAccess implements BuildSite.Access {
    private final CompanionPlayer body;
    private final BlockPos projectedFeet;
    private final com.neko.mcbot.path.TerrainEdits edits;
    private MiningTools tools;
    private StandingGeometry geometry;
    public BuildSiteAccess(CompanionPlayer body) { this(body,null,com.neko.mcbot.path.TerrainEdits.EMPTY); }
    public BuildSiteAccess(CompanionPlayer body, BlockPos feet, com.neko.mcbot.path.TerrainEdits edits) {
        this.body = body; this.projectedFeet = feet; this.edits = edits;
    }
    @Override public BlockState state(BlockPos pos) {
        var level = body.level();
        if (!level.isInWorldBounds(pos) || !level.getWorldBorder().isWithinBounds(pos) || !level.isLoaded(pos)) return null;
        long key=com.neko.mcbot.path.DigAStar.pack(pos.getX(),pos.getY(),pos.getZ());
        return edits.removed().contains(key) ? net.minecraft.world.level.block.Blocks.AIR.defaultBlockState()
                : edits.placed().contains(key) ? net.minecraft.world.level.block.Blocks.COBBLESTONE.defaultBlockState()
                : level.getBlockState(pos);
    }
    @Override public boolean corridor(BlockPos feet) {
        if (geometry == null) geometry = StandingGeometry.forPlayer(body);
        return geometry.standing(feet.getX(), feet.getY(), feet.getZ(),
                com.neko.mcbot.path.TerrainEdits.EMPTY) != null;
    }
    @Override public boolean corridorStep(BlockPos from, BlockPos to) {
        return BuildSite.Access.super.corridorStep(from, to) && walkable(geometry, from, to);
    }
    @Override public java.util.List<BlockPos> corridorBody(BlockPos feet) {
        return corridorBody(geometry, feet, body.getBbHeight());
    }
    public static java.util.List<BlockPos> corridorBody(StandingGeometry geometry, BlockPos feet, double height) {
        var foot = geometry.standing(feet.getX(), feet.getY(), feet.getZ(),
                com.neko.mcbot.path.TerrainEdits.EMPTY);
        if (foot == null) return java.util.List.of();
        var cells = new java.util.ArrayList<BlockPos>();
        for (int y = (int) Math.floor(foot.height()); y < (int) Math.ceil(foot.height() + height - 1e-7); y++)
            cells.add(new BlockPos(feet.getX(), y, feet.getZ()));
        return java.util.List.copyOf(cells);
    }
    public static boolean walkable(StandingGeometry geometry, BlockPos from, BlockPos to) {
        var edits = com.neko.mcbot.path.TerrainEdits.EMPTY;
        var a = geometry.standing(from.getX(), from.getY(), from.getZ(), edits);
        var b = geometry.standing(to.getX(), to.getY(), to.getZ(), edits);
        return a != null && b != null && geometry.motion(from.getX(), a.height(), from.getZ(),
                to.getX(), b.height(), to.getZ(), edits) != com.neko.mcbot.path.NavigationGeometry.Motion.BLOCKED;
    }
    @Override public boolean facility(BlockPos pos) {
        var state = state(pos);
        return state == null || WorksiteProtection.facility(state, state.hasBlockEntity());
    }
    @Override public boolean occupied(BlockPos pos) {
        var region = new AABB(pos).expandTowards(0, 2, 0);
        var own=projectedFeet==null ? body.getBoundingBox()
                : StandingGeometry.forPlayer(body).body(projectedFeet.getX(),projectedFeet.getY(),projectedFeet.getZ());
        if (region.intersects(own)) return true;
        var found = new java.util.ArrayList<net.minecraft.world.entity.LivingEntity>();
        body.level().getEntities(net.minecraft.world.level.entity.EntityTypeTest.forClass(
                net.minecraft.world.entity.LivingEntity.class), region, entity -> entity!=body && entity.isAlive(), found, 1);
        return !found.isEmpty();
    }
    @Override public boolean material(BuildTarget.Cell cell) {
        return cell.authored().isAir() || cell.material().item() != null
                && BuildInventory.available(body.getInventory(), cell.material().item()) >= cell.material().units();
    }
    @Override public boolean repairMaterials(java.util.List<BuildTarget.Cell> cells) {
        if (projectedFeet!=null) {
            var owed=new java.util.LinkedHashMap<BlockPos,BuildTarget.Cell>();
            cells.forEach(cell->owed.put(cell.pos(),cell));
            for (long key:edits.removed()) {
                var pos=new BlockPos(com.neko.mcbot.path.DigAStar.unpackX(key),
                        com.neko.mcbot.path.DigAStar.unpackY(key),com.neko.mcbot.path.DigAStar.unpackZ(key));
                var cell=body.buildSite().target().index().get(pos);
                if (cell!=null && !cell.authored().isAir()) owed.put(pos,cell);
            }
            cells=java.util.List.copyOf(owed.values());
        }
        var needed = new java.util.HashMap<net.minecraft.world.item.Item, Integer>();
        for (var cell : cells) {
            var material = cell.material();
            if (!material.supported() || material.item() == null) return false;
            needed.merge(material.item(), material.units(), Integer::sum);
        }
        return needed.entrySet().stream().allMatch(entry ->
                BuildInventory.available(body.getInventory(), entry.getKey()) >= entry.getValue());
    }
    @Override public boolean canLowerThrough(BlockPos pos) {
        var feet = projectedFeet==null ? StandingGeometry.feetCell(body) : projectedFeet;
        var bounds = projectedFeet==null ? body.getBoundingBox()
                : StandingGeometry.forPlayer(body).body(feet.getX(),feet.getY(),feet.getZ());
        if (projectedFeet==null && (!body.onGround() || Math.abs(body.getY() - feet.getY()) > 1e-5)
                || !pos.equals(feet.below())
                || bounds.minX < pos.getX() + 1e-5 || bounds.maxX > pos.getX() + 1 - 1e-5
                || bounds.minZ < pos.getZ() + 1e-5 || bounds.maxZ > pos.getZ() + 1 - 1e-5) return false;
        var lowering = projectedFeet==null ? StandingGeometry.forPlayer(body, body.buildSite()::inScope)
                : new StandingGeometry(new StandingGeometry.Access() {
                    @Override public boolean known(BlockPos p) { return body.buildSite().inScope(p) && state(p)!=null; }
                    @Override public java.util.List<AABB> boxes(BlockPos p) {
                        return StandingGeometry.collision(state(p),body.level(),p);
                    }
                },body.getBbWidth(),body.getBbHeight(),body.maxUpStep(),
                ()->body.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.SAFE_FALL_DISTANCE));
        if (lowering.lowerThrough(feet.getX(), feet.getY(), feet.getZ()) == null) return false;
        var found = new java.util.ArrayList<net.minecraft.world.entity.LivingEntity>();
        body.level().getEntities(net.minecraft.world.level.entity.EntityTypeTest.forClass(
                net.minecraft.world.entity.LivingEntity.class), new AABB(pos).expandTowards(0, 2, 0),
                entity -> entity != body && entity.isAlive(), found, 1);
        return found.isEmpty();
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
