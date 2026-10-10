package com.neko.mcbot.server;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EntityEquipment;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;

/** Registry objects are real; world observations and the vanilla action callback are controlled. */
public final class BlockActionFixtures {
    private BlockActionFixtures() {}

    public static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    public static Inventory inventory() {
        return new Inventory(null, new EntityEquipment());
    }

    public static final class Drop implements BlockMining.Drop {
        public ItemStack stack;
        public int settlements;

        public Drop(ItemStack stack) { this.stack = stack; }
        @Override public Object identity() { return this; }
        @Override public ItemStack stack() { return stack; }
        @Override public void settle(ItemStack remainder) {
            settlements++;
            stack = remainder.copy();
        }
    }

    public static final class Mining implements BlockMining.Access {
        public final Inventory inventory = BlockActionFixtures.inventory();
        public final List<Drop> drops = new ArrayList<>();
        public final List<Integer> cracks = new ArrayList<>();
        public final List<Double> reaches = new ArrayList<>();
        public BlockState state = Blocks.STONE.defaultBlockState();
        public ServerTool.Result denied;
        public boolean correct = true;
        public boolean permitted = true;
        public boolean reported = true;
        public float hardness = 1.5f;
        public float gain = .25f;
        public int destroys;
        public int reads;
        public Runnable action = () -> state = Blocks.AIR.defaultBlockState();

        public Mining() {
            inventory.setItem(0, new ItemStack(Items.IRON_PICKAXE));
        }
        @Override public ServerTool.Result guard(double reach) { reaches.add(reach); return denied; }
        @Override public BlockState state() { reads++; return state; }
        @Override public float hardness(BlockState state) { return hardness; }
        @Override public boolean correctTool(BlockState state) { return correct; }
        @Override public boolean mayDestroy(BlockState state) { return permitted; }
        @Override public float progress(BlockState state) { return gain; }
        @Override public int selectedSlot() { return inventory.getSelectedSlot(); }
        @Override public ItemStack held() { return inventory.getSelectedItem(); }
        @Override public Container inventory() { return inventory; }
        @Override public List<? extends BlockMining.Drop> nearbyDrops() { return List.copyOf(drops); }
        @Override public boolean destroy() { destroys++; action.run(); return reported; }
        @Override public void crack(int stage) { cracks.add(stage); }
    }

    public static final class Placement implements BlockPlacement.Access {
        public final Inventory inventory = BlockActionFixtures.inventory();
        public BlockState before = Blocks.AIR.defaultBlockState();
        public BlockState after = Blocks.COBBLESTONE.defaultBlockState();
        public ServerTool.Result denied;
        public InteractionResult result = InteractionResult.SUCCESS;
        public ItemStack supplied;
        public Direction face;
        public BlockPos target;
        public int consume = 1;
        public int calls;
        public int reads;

        @Override public ServerTool.Result guard(BlockPos pos) { return denied; }
        @Override public Container inventory() { return inventory; }
        @Override public BlockState state(BlockPos pos) { reads++; return calls == 0 ? before : after; }
        @Override public InteractionResult place(BlockPos pos, ItemStack stack, Direction face) {
            calls++;
            target = pos;
            supplied = stack;
            this.face = face;
            stack.shrink(consume);
            return result;
        }
    }
}
