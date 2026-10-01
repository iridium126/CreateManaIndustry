package com.iridium126.createmanaindustry.client.particles.packages;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PackageMeshAttributesTest {
    private static ByteBuffer bytes(int size){return ByteBuffer.allocateDirect(size).order(ByteOrder.nativeOrder());}
    private static void vertex(ByteBuffer v,int i,float x,float y,float u,float w) {
        int p=i*48;v.putFloat(p,x).putFloat(p+4,y).putFloat(p+12,u).putFloat(p+16,w);
    }
    @Test void quadMidpointAndMirroredTangentUseMeshLocalTriangles() {
        var v=bytes(9*48); // one preceding triangle; quad begins at three, not six
        vertex(v,0,0,0,0,0);vertex(v,1,1,0,1,0);vertex(v,2,0,1,0,1);
        int[] order={0,1,2,2,3,0};float[][] q={{0,0,1,0},{1,0,0,0},{1,1,0,1},{0,1,1,1}};
        for(int i=0;i<6;i++)vertex(v,i+3,q[order[i]][0],q[order[i]][1],q[order[i]][2],q[order[i]][3]);
        var r=bytes(32).putInt(4,3).putInt(16,3).putInt(20,6);
        int vp=v.position(),rp=r.position();var a=PackageMeshAttributes.build(v,r,2,null);
        assertEquals(vp,v.position());assertEquals(rp,r.position());
        for(int i=3;i<9;i++) {
            int p=i*36;assertEquals(0,a.getFloat(p),1e-6);assertEquals(0,a.getFloat(p+4),1e-6);assertEquals(1,a.getFloat(p+8),1e-6);
            assertEquals(-1,a.getFloat(p+12),1e-6);assertEquals(-1,a.getFloat(p+24));
            assertEquals(.5f,a.getFloat(p+28));assertEquals(.5f,a.getFloat(p+32));
        }
        assertEquals(1/3f,a.getFloat(28),1e-6);
    }
    @Test void rawNormalSurvivesUnshadedVertexAndDegenerateUv() {
        var v=bytes(3*48);vertex(v,0,0,0,.5f,.5f);vertex(v,1,1,0,.5f,.5f);vertex(v,2,0,1,.5f,.5f);
        var normals=bytes(36);for(int i=0;i<3;i++)normals.putFloat(i*12+4,1);
        var a=PackageMeshAttributes.build(v,bytes(16).putInt(4,3),1,normals);
        for(int i=0;i<3;i++) {
            int p=i*36;assertEquals(1,a.getFloat(p+4));
            float x=a.getFloat(p+12),y=a.getFloat(p+16),z=a.getFloat(p+20);
            assertEquals(1,x*x+y*y+z*z,1e-6);assertEquals(0,y,1e-6);
            for(int j=0;j<9;j++)assertTrue(Float.isFinite(a.getFloat(p+j*4)));
        }
    }
    @Test void malformedRangesAndNormalsAreRejected() {
        var v=bytes(144);var r=bytes(16).putInt(4,3);
        assertThrows(IllegalArgumentException.class,()->PackageMeshAttributes.build(v,r,1,bytes(12)));
        assertThrows(IllegalArgumentException.class,()->PackageMeshAttributes.build(v,bytes(16).putInt(0,1).putInt(4,3),1,null));
        var raw=bytes(36).putFloat(0,Float.NaN);
        assertThrows(IllegalArgumentException.class,()->PackageMeshAttributes.build(v,r,1,raw));
    }
}
