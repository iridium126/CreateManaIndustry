package com.iridium126.createmanaindustry.client.particles.packages;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PackageForceSceneTest {
    private static PackageForceScene.Source source(double x){return new PackageForceScene.Source(1,x,2,3,x+1,3,4,x+.5,2,3.5,1,0,0,0,0);}
    @Test void emptyAndSingleSourceKeepExactLayoutOriginAndImmutableInput(){
        var empty=PackageForceScene.bake(4,List.of(),0,0,0);assertEquals(0,empty.nodes());assertEquals(0,empty.data().remaining());
        var input=new ArrayList<>(List.of(source(2147483648.0)));var scene=PackageForceScene.bake(7,input,2147483648.0,0,0);input.clear();
        var b=scene.data();assertTrue(b.isReadOnly());assertEquals(96,b.remaining());assertEquals(0,b.getFloat(0));assertEquals(1,b.getInt(12));assertEquals(1,b.getInt(28));
        assertEquals(1,b.getInt(44));assertEquals(.5,b.getFloat(64));b.position(4);assertEquals(0,scene.data().position());
    }
    @Test void fullBvhHasNoLostDuplicateOrTruncatedSources(){
        var sources=new ArrayList<PackageForceScene.Source>();for(int i=0;i<4096;i++)sources.add(source((i*1499)%4096*3));
        var s=PackageForceScene.bake(1,sources,0,0,0);assertEquals(8191,s.nodes());var b=s.data();var seen=new BitSet();
        for(int i=0;i<s.nodes();i++){int end=b.getInt(i*32+12),source=b.getInt(i*32+28);assertTrue(end>i&&end<=s.nodes());
            if(source>0){assertEquals(i+1,end);assertFalse(seen.get(source-1));seen.set(source-1);
                var input=sources.get(source-1);assertEquals((float)input.x0(),b.getFloat(i*32));assertEquals((float)input.x1(),b.getFloat(i*32+16));}
            else assertTrue(end>=i+3);}
        assertEquals(4096,seen.cardinality());assertEquals(s.nodes(),b.getInt(12));
    }
    @Test void fansKeepSourceOrderWhileEntitiesCanSortSpatially(){
        var sourceList=List.of(new PackageForceScene.Source(2,10,0,0,11,1,1,10,0,0,1,1,0,0,4),source(-10),
                new PackageForceScene.Source(2,-20,0,0,-19,1,1,-20,0,0,1,-1,0,0,4),source(20));
        var scene=PackageForceScene.bake(1,sourceList,0,0,0);var b=scene.data();var fanOrder=new ArrayList<Integer>();boolean fanSeen=false;
        for(int i=0;i<scene.nodes();i++){int source=b.getInt(i*32+28);if(source==0)continue;
            if(sourceList.get(source-1).kind()==2){fanSeen=true;fanOrder.add(source);}else assertFalse(fanSeen,"entity tree must precede sequential fans");}
        assertEquals(List.of(1,3),fanOrder);
    }
    @Test void nozzleSourcesPreserveRangeAndOptionalFrameInTheGpuLayout(){
        var nozzle=new PackageForceScene.Source(PackageForceScene.NOZZLE,3,4,5,7,8,9,5,6,7,1/32f,-1,0,0,4);
        var pose=new PackageMovingGeometry.Pose(0,0,-1,0,1,0,1,0,0,10,0,0);
        var scene=PackageForceScene.bake(3,List.of(nozzle,nozzle.framed(new PackageForceScene.Frame(pose,5,6,7))),0,0,0);
        var data=scene.data();int sourcesAt=scene.nodes()*PackageForceScene.NODE_BYTES;
        assertEquals(2,scene.sources());assertEquals(1,scene.frames());
        assertEquals(PackageForceScene.NOZZLE,data.getInt(sourcesAt+12));
        assertEquals(1/32f,data.getFloat(sourcesAt+44));assertEquals(-1,data.getFloat(sourcesAt+48));assertEquals(4,data.getFloat(sourcesAt+60));
        int framedAt=sourcesAt+PackageForceScene.SOURCE_BYTES;
        assertEquals(PackageForceScene.NOZZLE|4,data.getInt(framedAt+12));
        assertEquals(1/32f,data.getFloat(framedAt+44));assertEquals(-1,data.getFloat(framedAt+48));
        assertEquals(0,data.getFloat(framedAt+52));assertEquals(0,data.getFloat(framedAt+56));assertEquals(4,data.getFloat(framedAt+60));
        assertEquals(scene.nodes()*PackageForceScene.NODE_BYTES+2*PackageForceScene.SOURCE_BYTES+PackageForceScene.FRAME_BYTES,data.remaining());
    }
    @Test void forceInterestUsesWorldBoundsForStaticAndFramedSources(){
        var first=new com.iridium126.createmanaindustry.content.logistics.gpupackage.PackageRegion(0,0,0);
        var second=new com.iridium126.createmanaindustry.content.logistics.gpupackage.PackageRegion(2,0,0);
        assertTrue(PackageForceScene.intersectsRegions(source(10),List.of(first),2));
        assertTrue(PackageForceScene.intersectsRegions(source(66),List.of(first),2));
        assertFalse(PackageForceScene.intersectsRegions(source(66.01),List.of(first),2));
        var pose=new PackageMovingGeometry.Pose(1,0,0,0,1,0,0,0,1,128,0,0);
        var framed=source(0).framed(new PackageForceScene.Frame(pose,0,0,0));
        assertTrue(PackageForceScene.intersectsRegions(framed,List.of(second),2));
        assertFalse(PackageForceScene.intersectsRegions(framed,List.of(first),2));
        assertFalse(PackageForceScene.intersectsRegions(source(0),List.of(),2));
    }
    @Test void rejectMalformedUnknownAndOverflowRatherThanTreatThemAsAir(){
        assertThrows(IllegalArgumentException.class,()->PackageForceScene.bake(0,Collections.nCopies(4097,source(0)),0,0,0));
        assertThrows(IllegalArgumentException.class,()->PackageForceScene.bake(0,List.of(source(0)),Double.NaN,0,0));
        assertThrows(IllegalArgumentException.class,()->new PackageForceScene.Source(4,0,0,0,1,1,1,0,0,0,1,0,0,0,0));
        assertThrows(IllegalArgumentException.class,()->new PackageForceScene.Source(2,0,0,0,1,1,1,0,0,0,1,0,0,0,1));
        assertThrows(IllegalArgumentException.class,()->new PackageForceScene.Source(1,0,0,0,1,1,1,0,0,0,2,0,0,0,0));
        assertThrows(IllegalArgumentException.class,()->new PackageForceScene.Source(2,0,0,0,1,1,1,0,0,0,Float.POSITIVE_INFINITY,1,0,0,1));
    }
}
