package com.iridium126.createmanaindustry.client.particles.packages;

import java.nio.ByteBuffer;
import java.util.function.Function;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import com.simibubi.create.content.logistics.box.PackageItem;
import org.lwjgl.BufferUtils;

/** Explicit development preview; contains no Create entities, item contents or server-side side effects. */
public final class PackagePreviewRuntime implements AutoCloseable {
    private PackagePhysicsGpu physics;
    private PackagePoolGpu pool;
    private long lastNanos;
    private double accumulator;
    private Vec3 origin;

    public void start(PackagePoolGpu pool,int count,Vec3 origin,Vec3 forward,Function<String,String> sources) {
        if(count<=0 || count>131072)throw new IllegalArgumentException("Preview count");
        double length=Math.sqrt(forward.x*forward.x+forward.z*forward.z);
        if(!Double.isFinite(length) || length<1e-6)throw new IllegalArgumentException("Preview direction");
        float fx=(float)(forward.x/length),fz=(float)(forward.z/length);
        PackageModelCache.Baked baked=PackageModelCache.bake();
        var entry=baked.styles().entrySet().stream().filter(e->e.getValue().rig()!=PackagePoolGpu.NO_MESH)
                .min(java.util.Comparator.comparing(e->e.getKey().toString()))
                .orElseThrow(()->new IllegalStateException("No compatible baked Create package model"));
        ItemStack item=new ItemStack(BuiltInRegistries.ITEM.get(entry.getKey()));
        float width=PackageItem.getWidth(item),height=PackageItem.getHeight(item),hook=PackageItem.getHookDistance(item);
        ByteBuffer bodies=BufferUtils.createByteBuffer(count*64),chains=BufferUtils.createByteBuffer(count*64);
        ByteBuffer meta=BufferUtils.createByteBuffer(count*PackagePoolGpu.META_BYTES);
        int columns=(int)Math.ceil(Math.sqrt(count));
        for(int i=0;i<count;i++) {
            int p=i*64,m=i*PackagePoolGpu.META_BYTES;
            float across=(i%columns-(columns-1)*.5f)*3,depth=(i/columns)*3,angle=(i*137.50776f)%360;
            float x=across*fz+depth*fx,z=-across*fx+depth*fz;
            float bx=x+(float)Math.sin(Math.toRadians(angle))*.875f,bz=z+(float)Math.cos(Math.toRadians(angle))*.875f;
            bodies.putFloat(p,bx).putFloat(p+4,-9/16f).putFloat(p+8,bz).putFloat(p+12,1)
                    .putFloat(p+32,width*.5f).putFloat(p+36,height*.5f).putFloat(p+40,width*.5f).putFloat(p+44,angle);
            chains.putFloat(p,x).putFloat(p+8,z).putFloat(p+12,.875f).putFloat(p+32,angle)
                    .putFloat(p+36,90).putFloat(p+40,1).putFloat(p+48,bx).putFloat(p+52,-9/16f).putFloat(p+56,bz);
            meta.putLong(m,i+1L).putLong(m+8,1).putInt(m+16,i).putInt(m+20,entry.getValue().box())
                    .putInt(m+24,entry.getValue().rig()).putInt(m+28,PackagePoolGpu.CHAIN)
                    .putFloat(m+56,hook).putInt(m+60,0x00f000f0);
        }
        PackagePhysicsGpu candidate=new PackagePhysicsGpu(count,2,sources);
        try {candidate.upload(bodies,count);candidate.uploadChains(chains);}
        catch(RuntimeException failure){candidate.close();throw failure;}
        close();this.pool=pool;
        try {baked.upload(pool);pool.uploadMetadata(meta,count);}
        catch(RuntimeException failure){candidate.close();pool.reset();throw failure;}
        physics=candidate;this.origin=origin;accumulator=0;lastNanos=System.nanoTime();refreshSource();
    }
    /** 20 Hz physics is independent of render FPS and the particle engine's automatic throttle. */
    public boolean active(){return physics!=null;}
    public float prepare(boolean paused) {
        if(physics==null)return 1;
        long now=System.nanoTime();double elapsed=Math.max(0,(now-lastNanos)*1e-9);lastNanos=now;
        if(!paused) {
            accumulator+=Math.min(.1,elapsed);
            int steps=0;
            while(accumulator>=.05 && steps++<2){physics.stepChains(.05f);accumulator-=.05;}
        }
        refreshSource();return (float)Math.clamp(accumulator/.05,0,1);
    }
    private void refreshSource() {
        pool.source(physics.stateBuffer(),physics.chainBuffer(),physics.historyBuffer(),physics.count(),
                (float)origin.x,(float)origin.y,(float)origin.z);
    }
    @Override public void close() {
        if(physics!=null)physics.close();physics=null;
        if(pool!=null)pool.reset();pool=null;
    }
}
