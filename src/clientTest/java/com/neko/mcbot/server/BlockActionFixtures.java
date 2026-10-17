package com.neko.mcbot.server;

import com.google.gson.JsonObject;
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

    public static final class Drop {
        public final java.util.UUID id = java.util.UUID.randomUUID();
        public ItemStack stack;
        public int settlements;

        public Drop(ItemStack stack) { this.stack = stack; }
        public Object identity() { return this; }
        public ItemStack stack() { return stack; }
        public void settle(ItemStack remainder) {
            settlements++;
            stack = remainder.copy();
        }
    }

    public static final class Mining implements BlockMining.Access {
        public boolean environmentSafe = true;
        @Override public boolean environmentSafe() { return environmentSafe; }
        public final Inventory inventory = BlockActionFixtures.inventory();
        public final List<Drop> drops = new ArrayList<>();
        public final List<Integer> cracks = new ArrayList<>();
        public final List<Double> reaches = new ArrayList<>();
        public final HandSnapshot.Pickups pickups = new HandSnapshot.Pickups();
        public final DropLedger ledger = new DropLedger();
        public final java.util.UUID actor = java.util.UUID.randomUUID(), body = java.util.UUID.randomUUID();
        public DropLedger.Origin origin;
        public boolean captureDrops = true;
        public String dropRole = "target";
        public BlockPos dropSource = BlockPos.ZERO;
        public Runnable afterCapture = () -> {};
        public BlockState state = Blocks.STONE.defaultBlockState();
        public String expectedBlock;
        public ServerTool.Result denied;
        public ServerTool.Result sightFailure;
        public ServerTool.Result turnFailure;
        public boolean correct = true;
        public boolean permitted = true;
        public boolean reported = true;
        public float hardness = 1.5f;
        public float gain = .25f;
        public int destroys;
        public int reads;
        public int turns;
        public int swings;
        public Runnable action = () -> state = Blocks.AIR.defaultBlockState();
        public ServerTool.Result attemptFailure;
        public int attempts;
        @Override public ServerTool.Result beforeDestroy() { attempts++; return attemptFailure; }

        public Mining() {
            inventory.setItem(0, new ItemStack(Items.IRON_PICKAXE));
        }
        @Override public ServerTool.Result guard(double reach) { reaches.add(reach); return denied; }
        @Override public BlockState state() { reads++; return state; }
        @Override public String expectedBlock() { return expectedBlock; }
        @Override public float hardness(BlockState state) { return hardness; }
        @Override public boolean correctTool(BlockState state) { return correct; }
        @Override public boolean mayDestroy(BlockState state) { return permitted; }
        @Override public ServerTool.Result sight(double reach) { return sightFailure; }
        @Override public ServerTool.Result faceTarget() { turns++; return turnFailure; }
        @Override public void swing() { swings++; }
        @Override public float progress(BlockState state) { return gain; }
        @Override public int selectedSlot() { return inventory.getSelectedSlot(); }
        @Override public ItemStack held() { return inventory.getSelectedItem(); }
        @Override public HandSnapshot.Pickups handPickups() { return pickups; }
        @Override public JsonObject dropReport() {
            return origin == null ? null : ledger.report(origin);
        }
        @Override public boolean destroy() {
            destroys++;
            var existing = new java.util.HashSet<>(drops);
            action.run();
            if (captureDrops) {
                origin = ledger.begin(actor, body, dropSource, dropRole, 0);
                for (var drop : drops) {
                    if (!existing.add(drop)) continue;
                    var at = new JsonObject();
                    at.addProperty("item", net.minecraft.core.registries.BuiltInRegistries.ITEM
                            .getKey(drop.stack.getItem()).toString());
                    at.addProperty("count", drop.stack.getCount());
                    ledger.spawned(origin, new DropLedger.Sample(drop.id, drop.stack, at,
                            DropLedger.Availability.LIVE), DropLedger.Relation.SOURCE_CELL);
                }
                afterCapture.run();
            }
            return reported;
        }
        @Override public void crack(int stage) { cracks.add(stage); }
    }

    public static final class Placement implements BlockPlacement.Access {
        public final Inventory inventory = BlockActionFixtures.inventory();
        public BlockState before = Blocks.AIR.defaultBlockState();
        public BlockState after = Blocks.COBBLESTONE.defaultBlockState();
        public ServerTool.Result denied;
        public ServerTool.Result sightFailure;
        public InteractionResult result = InteractionResult.SUCCESS;
        public ServerTool.Result prepareFailure;
        public ItemStack supplied;
        public Direction face;
        public BlockPos target;
        public int consume = 1;
        public int calls;
        public int reads;
        public ServerTool.Result attemptFailure;
        public int attempts, recorded;
        @Override public ServerTool.Result beforePlace() { attempts++; return attemptFailure; }
        @Override public void recorded(BlockPos pos, BlockState before, BlockState after, int consumed) { recorded++; }
        public final List<BlockPos> prepared = new ArrayList<>();
        public java.util.function.Function<BlockPos, ServerTool.Result> prepareAt;
        public java.util.function.BiFunction<BlockPos, Direction, ServerTool.Result> sightAt;
        public JsonObject contactData;
        public final com.google.gson.JsonArray stands = new com.google.gson.JsonArray();
        public BlockPos standTarget;

        @Override public ServerTool.Result guard(BlockPos pos) { return denied; }
        @Override public Container inventory() { return inventory; }
        @Override public BlockState state(BlockPos pos) { reads++; return calls == 0 ? before : after; }
        @Override public ServerTool.Result sight(BlockPos pos, Direction face) {
            return sightAt == null ? sightFailure : sightAt.apply(pos, face);
        }
        @Override public JsonObject contact() { return contactData; }
        @Override public com.google.gson.JsonArray standingPositions(BlockPos pos, int slot, Direction face) {
            standTarget = pos;
            return stands;
        }
        @Override public ServerTool.Result prepare(BlockPos pos, ItemStack stack, Direction face) {
            prepared.add(pos);
            return prepareAt == null ? prepareFailure : prepareAt.apply(pos);
        }
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
