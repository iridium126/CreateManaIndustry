package com.iridium126.createmanaindustry.content.fluids.fueltank;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

class FuelTankGeometryTest {
    @Test void boundsContainLShapeAndOriginIsIndependentOfCorner() {
        Set<BlockPos> cells=new HashSet<>();
        for(int z=0;z<=10;z++)cells.add(new BlockPos(0,64,z)); cells.add(new BlockPos(1,64,0));
        var geometry=FuelTankGeometry.build(cells,8000);
        assertEquals(new BlockPos(0,64,0),geometry.minCell); assertEquals(new BlockPos(1,64,10),geometry.maxCell);
        cells=Set.of(new BlockPos(1,64,0),new BlockPos(1,65,0),new BlockPos(0,65,0));
        geometry=FuelTankGeometry.build(cells,8000);
        assertEquals(new BlockPos(1,64,0),geometry.origin);
        assertEquals(new BlockPos(0,64,0),geometry.minCell); assertFalse(cells.contains(geometry.minCell));
    }

    @Test void serializationRebasesGeometryAndPreservesExactDistributionOnTranslation() {
        Set<BlockPos> cells=Set.of(new BlockPos(1,64,0),new BlockPos(1,65,0),new BlockPos(0,65,0));
        FuelTankFluidState state=FuelTankFluidStateTest.state(cells); state.fill(new BlockPos(1,64,0),9123);
        BlockPos offset=new BlockPos(-70,12,90);
        var moved=FuelTankFluidState.read(state.write(),state.geometry().origin.offset(offset));
        assertEquals(state.total(),moved.total());
        state.cellAmounts().forEach((pos,amount)->assertEquals(amount,moved.cellAmounts().get(pos.offset(offset))));
        assertEquals(state.geometry().minCell.offset(offset),moved.geometry().minCell);
        assertThrows(UnsupportedOperationException.class,()->moved.geometry().basinByCell.clear());
        assertThrows(UnsupportedOperationException.class,()->moved.geometry().basins.getFirst().cells.clear());
    }

    @Test void multiBasinEdgesStaySparseAndDeterministic() {
        Set<BlockPos> comb=new HashSet<>();
        for(int x=0;x<512;x++) { comb.add(new BlockPos(x,65,0)); if(x%2==0)comb.add(new BlockPos(x,64,0)); }
        var geometry=FuelTankGeometry.build(comb,8000);
        assertTrue(geometry.basins.size()>100);
        assertTrue(geometry.edgeCount()<comb.size()*6);
        List<BlockPos> reverse=new ArrayList<>(comb); Collections.reverse(reverse);
        var other=FuelTankGeometry.build(new LinkedHashSet<>(reverse),8000);
        assertEquals(geometry.basinByCell,other.basinByCell);
        assertEquals(geometry.write(),other.write());
    }

    @Test void sparseVolumeUpdatesKeepInitializationSnapshotsIndependent() {
        Set<BlockPos> comb=new HashSet<>();
        for(int x=0;x<64;x++) { comb.add(new BlockPos(x,65,0)); if(x%2==0)comb.add(new BlockPos(x,64,0)); }
        var server=FuelTankFluidStateTest.state(comb); var client=FuelTankFluidState.read(server.write(),server.geometry().origin);
        server.clearSyncChanges(); server.fill(new BlockPos(0,64,0),100);
        var initial=server.write(); var update=new net.minecraft.nbt.CompoundTag(); server.writeVolumeUpdate(update);
        assertTrue(update.contains("BasinIds")); assertEquals(1,update.getIntArray("Volumes").length);
        assertFalse(update.contains("Cells")); client.readVolumeUpdate(update);
        assertArrayEquals(server.copyVolumes(),client.copyVolumes());
        assertEquals(initial,server.write()); // Taking a full snapshot never consumes pending changes.
        var again=new net.minecraft.nbt.CompoundTag(); server.writeVolumeUpdate(again); assertEquals(update,again);
    }

    @Test void defaultScaleMultiBasinGeometryHasNoDenseMatrixAndDecodesWithoutWatershed() {
        Set<BlockPos> cells=new HashSet<>();
        for(int x=0;x<2730;x++){cells.add(new BlockPos(x,65,0));if(x%2==0)cells.add(new BlockPos(x,64,0));}
        var geometry=FuelTankGeometry.build(cells,8000);
        assertEquals(4095,cells.size()); assertTrue(geometry.basins.size()>1000);
        assertTrue(geometry.edgeCount()<cells.size()*6);
        var decoded=FuelTankGeometry.read(geometry.write(),geometry.origin);
        assertEquals(geometry.basinByCell,decoded.basinByCell);
        assertEquals(geometry.edgeCount(),decoded.edgeCount());
    }
}
