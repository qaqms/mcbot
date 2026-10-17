package com.neko.mcbot.server.tools;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.neko.mcbot.server.ServerTool;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ContainerGoalEvidenceTest {
    @BeforeAll static void bootstrap() { com.neko.mcbot.server.BlockActionFixtures.bootstrap(); }
    private static JsonObject args(String extra) {
        return JsonParser.parseString("{\"x\":2,\"y\":90,\"z\":0,\"item\":\"diamond\"" + extra + "}").getAsJsonObject();
    }
    static final class World implements InspectBlockTool.Access {
        SimpleContainer slots=new SimpleContainer(1024);
        boolean locked,pending,valid=true,replaced,guarded;
        int calls;
        @Override public ServerTool.Result guard(BlockPos pos) {
            return guarded ? new ServerTool.Result(false,"TARGET_LOST:unloaded",null) : null;
        }
        @Override public InspectBlockTool.Target target(BlockPos pos) {
            calls++; return new InspectBlockTool.Target("minecraft:chest",slots,locked,valid,pending);
        }
        @Override public String dimension() { return "minecraft:overworld"; }
        @Override public String identity(BlockPos pos,InspectBlockTool.Target target) {
            return replaced ? null : "container-one";
        }
    }
    @Test void itemSummaryReadsEverySlotOnceInOneRequestWhileDetailsStayPagedAndPrivate() {
        var world=new World();
        var named=new ItemStack(Items.DIAMOND,3);
        named.set(DataComponents.CUSTOM_NAME,Component.literal("private-component"));
        world.slots.setItem(0,new ItemStack(Items.DIAMOND,2));
        world.slots.setItem(1023,named);
        var result=InspectBlockTool.execute(args(""),world);
        assertTrue(result.ok(),result.feedback());
        assertEquals(5,result.data().get("item_total").getAsInt());
        assertEquals(24,result.data().getAsJsonArray("slots").size());
        assertTrue(result.data().get("contents_complete").getAsBoolean());
        assertEquals("container-one",result.data().get("container_id").getAsString());
        assertFalse(result.feedback().contains("private-component"));
        assertTrue(ItemStack.matches(named,world.slots.getItem(1023)));
    }
    @Test void lockedLootInvalidUnknownOrOverlargeTargetsNeverSupplyCompleteDeliveryFacts() {
        for(int mode=0;mode<5;mode++) {
            var world=new World();
            world.locked=mode==0; world.pending=mode==1; world.valid=mode!=2;
            world.guarded=mode==3;
            if(mode==4) world.slots=new SimpleContainer(1025);
            assertFalse(InspectBlockTool.execute(args(""),world).ok());
            if(mode==3) assertEquals(0,world.calls);
        }
        var replaced=new World(); replaced.replaced=true;
        var data=InspectBlockTool.execute(args(""),replaced).data();
        assertFalse(data.get("container_identity_known").getAsBoolean());
        assertFalse(data.get("contents_complete").getAsBoolean());
    }
    @Test void unknownItemOrInvalidFieldIsRejectedBeforeAnyTargetRead() {
        for(String item:java.util.List.of("false","null","\"offline:unknown\"","\"bad::id\"")) {
            var world=new World(); var input=args("");
            input.add("item",JsonParser.parseString(item));
            assertFalse(InspectBlockTool.execute(input,world).ok());
            assertEquals(0,world.calls);
        }
    }
}
