package com.iridium126.createmanaindustry.client.allaystorm.rift;

import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;

/** Main-camera publication, independent of network ticks and target-sky replay uniforms. */
public record RiftRenderFrame(long number,long generation,int width,int height,float partial,
        Vec3 camera,Matrix4fc view,Matrix4fc projection,Matrix4fc inverseViewProjection) {
    private static long counter;
    private static RiftRenderFrame current;
    public RiftRenderFrame {
        view=new Matrix4f(view);projection=new Matrix4f(projection);inverseViewProjection=new Matrix4f(inverseViewProjection);
    }
    public static RiftRenderFrame current(){return current;}
    public static boolean auxiliary(){return SkyRenderScope.active()
            ||com.iridium126.createmanaindustry.CreateManaIndustry.VEIL_ACTIVE&&VeilView.active();}
    private static final class VeilView {
        static boolean active(){return foundry.veil.api.client.render.VeilLevelPerspectiveRenderer.isRenderingPerspective();}
    }
    public static RiftRenderFrame begin(long generation,int width,int height,float partial,Vec3 camera,Matrix4fc view,Matrix4fc projection){
        return current=new RiftRenderFrame(++counter,generation,width,height,partial,camera,view,projection,new Matrix4f(projection).mul(view).invert());
    }
    public static void clear(){current=null;}
    public boolean matches(long generation,int width,int height){return this.generation==generation&&this.width==width&&this.height==height;}
    @Override public String toString(){return number+" generation="+generation+" size="+width+"x"+height+"; render-frame publication";}
}
