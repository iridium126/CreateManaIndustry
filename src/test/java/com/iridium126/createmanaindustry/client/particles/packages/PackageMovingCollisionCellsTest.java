package com.iridium126.createmanaindustry.client.particles.packages;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PackageMovingCollisionCellsTest {
    private static final List<PackageMovingGeometry.Box> SOLID=List.of(new PackageMovingGeometry.Box(0,0,0,1,1,1,.6f,0));
    @Test void repeatedNotificationsDoNotRevokeUnchangedGeometryButRealAndNeighborChangesDo() {
        var cells=new PackageMovingCollisionCells();var bounds=new PackageMovingGeometry.Bounds(-1,-1,-1,2,2,2);
        for(int x=-1;x<=1;x++)for(int y=-1;y<=1;y++)for(int z=-1;z<=1;z++)cells.record(x,y,z,List.of());
        cells.record(0,0,0,SOLID);
        for(int i=0;i<100;i++)assertFalse(cells.changed(0,0,0,bounds,c->c.x()==0&&c.y()==0&&c.z()==0?SOLID:List.of()));
        assertTrue(cells.changed(0,0,0,bounds,c->List.of()));
        assertTrue(cells.changed(0,0,0,bounds,c->c.x()==1&&c.y()==0&&c.z()==0?SOLID:cells.get(c.x(),c.y(),c.z())));
        assertTrue(cells.changed(0,0,0,bounds,c->null));
        cells.clear();assertTrue(cells.changed(0,0,0,bounds,c->SOLID));
    }
    @Test void signaturesIncludeMaterialsFlagsAndShapeAndAreCopied() {
        var cells=new PackageMovingCollisionCells();var bounds=new PackageMovingGeometry.Bounds(0,0,0,1,1,1);
        var input=new ArrayList<>(SOLID);cells.record(0,0,0,input);input.clear();assertEquals(SOLID,cells.get(0,0,0));
        for(var different:List.of(new PackageMovingGeometry.Box(0,0,0,1,1,1,.7f,0),
                new PackageMovingGeometry.Box(0,0,0,1,1,1,.6f,1),new PackageMovingGeometry.Box(0,0,0,1,.5f,1,.6f,0)))
            assertTrue(cells.changed(0,0,0,bounds,c->List.of(different)));
    }
    @Test void largePlotYAndSectionBoundaryAirRemainDistinctFromUnknown() {
        var cells=new PackageMovingCollisionCells();int y=2147483500;
        cells.emptySection(-1,y>>4,1);cells.record(-1,y,16,SOLID);
        cells.record(-1,y-4096,16,List.of());
        assertEquals(SOLID,cells.get(-1,y,16));assertEquals(List.of(),cells.get(-1,y-4096,16));
        assertEquals(List.of(),cells.get(-2,y,16));assertNull(cells.get(0,y,16));
        var bounds=new PackageMovingGeometry.Bounds(-1,y,16,0,y+1,17);
        assertFalse(cells.changed(-1,y,16,bounds,c->SOLID),"neighbors outside source bounds must not require nonexistent sections");
    }
    @Test void boundedSignatureOverflowFailsClosedUntilNextCapture() {
        var cells=new PackageMovingCollisionCells();
        for(int x=0;x<=PackageMovingCollisionCells.MAX_SECTIONS;x++)cells.emptySection(x,0,0);
        assertNull(cells.get(0,0,0));cells.record(0,0,0,SOLID);assertNull(cells.get(0,0,0));
        cells.clear();cells.record(0,0,0,SOLID);assertEquals(SOLID,cells.get(0,0,0));
    }
    @Test void confirmedEmptySectionReplacesItsEarlierNumericSignatures(){
        var cells=new PackageMovingCollisionCells();cells.record(0,0,0,SOLID);cells.emptySection(0,0,0);
        assertEquals(List.of(),cells.get(0,0,0));assertEquals(List.of(),cells.get(15,15,15));
    }
}
