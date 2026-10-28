package com.iridium126.createmanaindustry.client.allaystorm.rift;

import com.iridium126.createmanaindustry.mixin.allaystorm.RiftCloudCacheAccess;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.vertex.*;
import net.minecraft.client.*;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.renderer.*;
import net.minecraft.util.Mth;

/** Target-only native cloud cache. Does not alternate day/night colors in the source cache. */
public final class TargetVanillaClouds implements AutoCloseable {
    private record Cache(boolean dirty,VertexBuffer buffer,int x,int y,int z,Vec3 color,CloudStatus type){
        static Cache read(RiftCloudCacheAccess a){return new Cache(a.cmi$generateClouds(),a.cmi$cloudBuffer(),a.cmi$prevCloudX(),a.cmi$prevCloudY(),a.cmi$prevCloudZ(),a.cmi$prevCloudColor(),a.cmi$prevCloudsType());}
        void apply(RiftCloudCacheAccess a){a.cmi$generateClouds(dirty);a.cmi$cloudBuffer(buffer);a.cmi$prevCloudX(x);a.cmi$prevCloudY(y);a.cmi$prevCloudZ(z);a.cmi$prevCloudColor(color);a.cmi$prevCloudsType(type);}
    }
    private Cache cache=new Cache(true,null,Integer.MIN_VALUE,Integer.MIN_VALUE,Integer.MIN_VALUE,Vec3.ZERO,null);
    private long draws,rebuilds,profileRevision=-1;
    private final com.iridium126.createmanaindustry.client.gpu.profile.GpuPassTelemetry timing=new com.iridium126.createmanaindustry.client.gpu.profile.GpuPassTelemetry();
    public String report(){return "native vanilla clouds; independent VBO; draws="+draws+" rebuilds="+rebuilds+"; replay "+timing.report();}
    public void draw(Matrix4f view,Matrix4f projection,float partial,RenderTarget output){
        var mc=Minecraft.getInstance();
        if(mc.level==null||mc.options.getCloudsType()==CloudStatus.OFF)return;
        if(!SkyRenderScope.active())throw new IllegalStateException("Target cloud replay outside target scope");
        var access=(RiftCloudCacheAccess)mc.levelRenderer;
        draws++;
        Cache source=Cache.read(access);RenderTarget sourceTarget=access.cmi$cloudsTarget();
        cache.apply(access);if(output!=null)access.cmi$cloudsTarget(output);
        try{
            if(profileRevision!=AllaySkyProvider.cloudProfileRevision()){timing.close();profileRevision=AllaySkyProvider.cloudProfileRevision();}
            timing.configure(AllaySkyProvider.cloudProfiling(),AllaySkyProvider.cloudProfiling());timing.poll();timing.begin(AllaySkyProvider.cloudProfiling());
            renderStock(access,view,projection,partial);
        }finally{
            timing.end();
            Cache next=Cache.read(access);if(next.buffer()!=cache.buffer())rebuilds++;cache=next;
            source.apply(access);access.cmi$cloudsTarget(sourceTarget);
        }
    }
    /** Reuse vanilla buildClouds, not Sodium's private shader or a duplicate geometry implementation. */
    private static void renderStock(RiftCloudCacheAccess a,Matrix4f view,Matrix4f projection,float partial){
        var mc=Minecraft.getInstance();float height=mc.level.effects().getCloudHeight();if(Float.isNaN(height))return;
        var camera=mc.gameRenderer.getMainCamera().getPosition();
        double drift=(a.cmi$cloudTicks()+partial)*.03f;
        double x=(camera.x+drift)/12,y=height-(float)camera.y+.33f,z=camera.z/12+.33f;
        x-=Mth.floor(x/2048)*2048;z-=Mth.floor(z/2048)*2048;
        int ix=(int)Math.floor(x),iy=(int)Math.floor(y/4),iz=(int)Math.floor(z);
        var color=mc.level.getCloudColor(partial);var type=mc.options.getCloudsType();
        if(ix!=a.cmi$prevCloudX()||iy!=a.cmi$prevCloudY()||iz!=a.cmi$prevCloudZ()||type!=a.cmi$prevCloudsType()||a.cmi$prevCloudColor().distanceToSqr(color)>2e-4){
            a.cmi$prevCloudX(ix);a.cmi$prevCloudY(iy);a.cmi$prevCloudZ(iz);a.cmi$prevCloudColor(color);a.cmi$prevCloudsType(type);a.cmi$generateClouds(true);
        }
        if(a.cmi$generateClouds()){
            a.cmi$generateClouds(false);if(a.cmi$cloudBuffer()!=null)a.cmi$cloudBuffer().close();
            var buffer=new VertexBuffer(VertexBuffer.Usage.STATIC);a.cmi$cloudBuffer(buffer);buffer.bind();
            try{buffer.upload(a.cmi$buildClouds(Tesselator.getInstance(),x,y,z,color));}finally{VertexBuffer.unbind();}
        }
        FogRenderer.levelFogColor();
        var pose=new PoseStack();pose.mulPose(view);pose.scale(12,1,12);
        pose.translate(-(float)(x-Mth.floor(x)),(float)(y/4-Mth.floor(y/4))*4,-(float)(z-Mth.floor(z)));
        var buffer=a.cmi$cloudBuffer();if(buffer==null)return;buffer.bind();
        try{
            for(int i=type==CloudStatus.FANCY?0:1;i<2;i++){
                var layer=i==0?RenderType.cloudsDepthOnly():RenderType.clouds();layer.setupRenderState();
                try{
                    var shader=RenderSystem.getShader();
                    if(!VanillaRiftRenderer.nativePack()&&AllaySkyShaders.clouds!=null){
                        shader=AllaySkyShaders.clouds;
                        VanillaRiftRenderer.prepareClouds(shader,view,partial,true);
                    }
                    buffer.drawWithShader(pose.last().pose(),projection,shader);
                }finally{layer.clearRenderState();}
            }
        }finally{VertexBuffer.unbind();}
    }
    @Override public void close(){
        timing.close();draws=rebuilds=0;profileRevision=-1;
        if(cache.buffer()!=null)cache.buffer().close();
        cache=new Cache(true,null,Integer.MIN_VALUE,Integer.MIN_VALUE,Integer.MIN_VALUE,Vec3.ZERO,null);
    }
}
