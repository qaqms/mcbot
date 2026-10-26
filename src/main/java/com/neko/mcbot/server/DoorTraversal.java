package com.neko.mcbot.server;

import com.neko.mcbot.body.CompanionPlayer;
import com.neko.mcbot.path.DigAStar;
import com.neko.mcbot.path.TerrainEdits;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.Vec3;
import java.util.List;
import java.util.function.Predicate;

/** Door interaction changes collision, never terrain ownership or a dig/place allowance. */
public final class DoorTraversal {
    private DoorTraversal() { }
    public static boolean openable(BlockState state) {
        return state!=null && state.getFluidState().isEmpty()
                && (state.getBlock() instanceof DoorBlock door && door.type().canOpenByHand()
                || state.getBlock() instanceof FenceGateBlock
                || state.getBlock() instanceof TrapDoorBlock && !state.is(Blocks.IRON_TRAPDOOR));
    }
    public static BlockState flip(BlockState state) {
        return openable(state) ? state.cycle(BlockStateProperties.OPEN) : state;
    }
    public static List<Long> cells(BlockGetter world,BlockPos pos,Predicate<BlockPos> allowed) {
        if (!allowed.test(pos)) return List.of();
        var state=world.getBlockState(pos);
        if (!openable(state)) return List.of();
        if (state.getBlock() instanceof DoorBlock) {
            var other=state.getValue(DoorBlock.HALF)==DoubleBlockHalf.LOWER ? pos.above() : pos.below();
            if (!allowed.test(other)) return List.of();
            var half=world.getBlockState(other);
            if (!half.is(state.getBlock()) || half.getValue(DoorBlock.HALF)==state.getValue(DoorBlock.HALF)
                    || half.getValue(DoorBlock.OPEN)!=state.getValue(DoorBlock.OPEN)
                    || half.getValue(DoorBlock.FACING)!=state.getValue(DoorBlock.FACING)
                    || half.getValue(DoorBlock.HINGE)!=state.getValue(DoorBlock.HINGE)) return List.of();
            var lower=pos.getY()<other.getY() ? pos : other;
            return List.of(key(lower),key(lower.above()));
        }
        return List.of(key(pos));
    }
    public static long key(BlockPos pos) { return DigAStar.pack(pos.getX(),pos.getY(),pos.getZ()); }
    public static BlockPos pos(long key) {
        return new BlockPos(DigAStar.unpackX(key),DigAStar.unpackY(key),DigAStar.unpackZ(key));
    }
    public static boolean reachable(BlockGetter world,BlockAim.Access sight,StandingGeometry geometry,
                                    int x,int y,int z,long target,TerrainEdits edits,double eyeHeight,double reach) {
        var foot=geometry.foot(x,y,z,edits);
        if (foot==null) return false;
        var view=BlockAim.withToggles(sight,world,edits.toggled());
        var aimed=NavigationWork.view(view,new Vec3(x+.5,foot.height()+eyeHeight,z+.5),
                edits.removed().stream().map(DoorTraversal::pos).collect(java.util.stream.Collectors.toSet()),
                edits.placed().stream().map(DoorTraversal::pos).collect(java.util.stream.Collectors.toSet()));
        return cells(world,pos(target),sight::loaded).stream()
                .anyMatch(part -> BlockAim.mining(aimed,pos(part),reach).ok());
    }
    public static ServerTool.Result interact(CompanionPlayer body,BlockPos pos) {
        var level=body.level();
        var footprint=cells(level,pos,p->level.isLoaded(p) && level.isInWorldBounds(p)
                && level.getWorldBorder().isWithinBounds(p) && level.mayInteract(body,p));
        if (footprint.isEmpty() || !body.gameMode.isSurvival() || !body.getAbilities().mayBuild)
            return new ServerTool.Result(false,"DENIED:门的类型、两半、加载或世界交互条件已变化。",null);
        BlockAim.Aim aim=null;
        BlockPos clicked=null;
        for (long key:footprint) {
            var part=pos(key);
            var found=BlockAim.mining(BlockAim.forPlayer(body),part,body.blockInteractionRange());
            if (found.ok()) { aim=found; clicked=part; break; }
        }
        if (aim==null) return new ServerTool.Result(false,"TARGET_BLOCKED:门的实际点击面不可见或超出手距。",null);
        var before=level.getBlockState(pos);
        for (long key:footprint) {
            var part=pos(key);
            var changed=flip(level.getBlockState(part));
            for (var box:changed.getCollisionShape(level,part).toAabbs())
                if (!level.getEntities(body,box.move(part),entity->entity.isAlive()
                        && entity instanceof net.minecraft.world.entity.LivingEntity).isEmpty()
                        || box.move(part).intersects(body.getBoundingBox()))
                    return new ServerTool.Result(false,"TARGET_BLOCKED:开关后的门板会占用身体或其他实体，未交互。",null);
        }
        body.facePoint(aim.point());
        var hit=BlockAim.forPlayer(body).trace(body.getEyePosition(),aim.point());
        if (hit==null || !hit.getBlockPos().equals(clicked))
            return new ServerTool.Result(false,"TARGET_BLOCKED:门的现场视线已变化。",null);
        boolean sneaking=body.isShiftKeyDown();
        var beforeParts=new java.util.LinkedHashMap<BlockPos,BlockState>();
        footprint.forEach(key->beforeParts.put(pos(key),level.getBlockState(pos(key))));
        var siteAccess=new BuildSiteAccess(body);
        var owned=body.buildSite().interactionSources(beforeParts.keySet(),siteAccess);
        try {
            body.setShiftKeyDown(false);
            level.getBlockState(clicked).useWithoutItem(level,body,hit);
        } finally {
            body.setShiftKeyDown(sneaking);
            var afterParts=new java.util.LinkedHashMap<BlockPos,BlockState>();
            beforeParts.keySet().forEach(part->afterParts.put(part,level.getBlockState(part)));
            body.buildSite().recordInteraction(beforeParts,afterParts,owned,siteAccess);
        }
        var actual=level.getBlockState(pos);
        if (!actual.is(before.getBlock()) || !actual.hasProperty(BlockStateProperties.OPEN)
                || actual.getValue(BlockStateProperties.OPEN)==before.getValue(BlockStateProperties.OPEN))
            return new ServerTool.Result(false,"TARGET_BLOCKED:原版未接受开关门，未认定通行条件满足。",null);
        body.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
        return new ServerTool.Result(true,"门已沿原版交互改变，尚未通过。",null);
    }
}
