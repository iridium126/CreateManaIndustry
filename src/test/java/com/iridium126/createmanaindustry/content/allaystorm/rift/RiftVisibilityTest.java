package com.iridium126.createmanaindustry.content.allaystorm.rift;
import org.junit.jupiter.api.Test;
import org.joml.Matrix4f;
import static org.junit.jupiter.api.Assertions.*;
import com.iridium126.createmanaindustry.client.allaystorm.rift.RiftVisibility;
class RiftVisibilityTest {
    @Test void invalidBoundsFailOpen(){
        var p=new Matrix4f().perspective((float)Math.toRadians(70),1,.1f,100);
        assertNull(RiftVisibility.bounds(p,Float.NaN,0,-10,1));
        assertNull(RiftVisibility.bounds(p,0,0,-10,Float.POSITIVE_INFINITY));
        assertNull(RiftVisibility.bounds(p,0,0,-10,-1));
    }
    @Test void juliaWeakPerspectiveSamplesStayInsideConservativeProjection()throws Exception{
        var shape=com.iridium126.createmanaindustry.client.allaystorm.rift.JuliaShape.load();
        for(float distance:new float[]{1,64,129,200,1000})for(float side:new float[]{-2,-.7f,0,.7f,2}){
            var centre=new org.joml.Vector3f(distance*side,0,-distance);
            var style=com.iridium126.createmanaindustry.client.allaystorm.rift.JuliaShape.projection(centre.length());
            var ray=new org.joml.Vector3f(centre).normalize();float scale=10;
            var p=new Matrix4f().perspective((float)Math.toRadians(70),1.5f,.1f,100);
            var bounds=RiftVisibility.juliaBounds(p,shape,centre.x,centre.y,centre.z,scale,style);
            for(int i=0;i<shape.segments.length;i+=32)for(int angle=0;angle<8;angle++){
                double a=angle*Math.PI/4;var offset=new org.joml.Vector3f(shape.segments[i]*scale,(float)(shape.segments[i+1]*scale*Math.cos(a)),(float)(shape.segments[i+1]*scale*Math.sin(a)));
                offset.fma(-style.weak()*offset.dot(ray),ray).mul(style.gain()).add(centre);
                var clip=new org.joml.Vector4f(offset,1).mul(p);
                if(clip.w<=0||clip.z< -clip.w||Math.abs(clip.x)>clip.w||Math.abs(clip.y)>clip.w)continue;
                if(bounds==null)continue;
                assertTrue(bounds[0]<=clip.x/clip.w&&bounds[2]>=clip.x/clip.w);
                assertTrue(bounds[1]<=clip.y/clip.w&&bounds[3]>=clip.y/clip.w);
                assertTrue(bounds[4]<=Math.min(clip.z/clip.w,.99999f)*.5f+.5f);
            }
        }
    }
    @Test void conservativeBoundsKeepNearCrossingsAndIgnoreClampedFarPlane(){
        var projection=new Matrix4f().perspective((float)Math.toRadians(70),1,0.1f,100);
        assertNull(RiftVisibility.bounds(projection,0,0,0,1));
        assertTrue(RiftVisibility.bounds(projection,0,0,-200,1)[0]<RiftVisibility.bounds(projection,0,0,-200,1)[2]);
        assertTrue(RiftVisibility.bounds(projection,200,0,-10,1)[0]>RiftVisibility.bounds(projection,200,0,-10,1)[2]);
        assertNotNull(RiftVisibility.bounds(projection,0,0,-10,1));
    }
}
