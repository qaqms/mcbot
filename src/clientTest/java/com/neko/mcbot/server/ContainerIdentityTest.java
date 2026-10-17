package com.neko.mcbot.server;

import com.neko.mcbot.building.CellWitness;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ContainerIdentityTest {
    @Test void unchangedNativeContainerHasStableIdentityButReplacementsReloadAndWorldChangesDoNot() {
        var ids=new ContainerIdentity();
        var world=new Object(); var chunk=new Object(); var container=new Object(); var cell=new CellWitness();
        var pos=new BlockPos(0,70,0);
        String id=ids.identify(world,chunk,container,pos,cell);
        assertNotNull(id);
        assertEquals(id,ids.identify(world,chunk,container,pos,cell));
        assertNotEquals(id,ids.identify(world,chunk,new Object(),pos,cell));
        assertNotEquals(id,ids.identify(world,new Object(),container,pos,cell));
        assertNotEquals(id,ids.identify(new Object(),chunk,container,pos,cell));
        cell.invalidate();
        assertNull(ids.identify(world,chunk,container,pos,cell));
        assertNotEquals(id,ids.identify(world,chunk,container,pos,new CellWitness()));
    }
    @Test void missingWitnessOrFullLiveIdentityTableCannotInventAnIdentity() {
        var ids=new ContainerIdentity(); var world=new Object(); var chunk=new Object(); var container=new Object();
        assertNull(ids.identify(world,chunk,container,BlockPos.ZERO,null));
        var retained=new java.util.ArrayList<CellWitness>();
        for(int i=0;i<ContainerIdentity.LIMIT;i++) {
            var witness=new CellWitness(); retained.add(witness);
            assertNotNull(ids.identify(world,chunk,container,new BlockPos(i,70,0),witness));
        }
        assertNull(ids.identify(world,chunk,container,new BlockPos(200,70,0),new CellWitness()));
        retained.getFirst().invalidate();
        assertNotNull(ids.identify(world,chunk,container,new BlockPos(200,70,0),new CellWitness()));
    }
}
