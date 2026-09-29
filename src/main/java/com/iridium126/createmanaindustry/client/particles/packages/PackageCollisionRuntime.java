package com.iridium126.createmanaindustry.client.particles.packages;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import com.iridium126.createmanaindustry.CreateManaIndustry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.event.GameShuttingDownEvent;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.level.LevelEvent;

/** Lazy, client-thread collision preparation. No world access, waits or uploads on workers. */
@EventBusSubscriber(modid=CreateManaIndustry.MODID,value=Dist.CLIENT)
public final class PackageCollisionRuntime {
    private static final int MAX_SECTIONS=1024,MAX_REQUEST_SECTIONS=64;
    private static PackageCollisionRuntime current;
    private final ClientLevel level;
    private final ExecutorService workers;
    private final PackageCollisionCache cache;
    private final PackageWorldCollisionSource source;

    private PackageCollisionRuntime(ClientLevel level) {
        this.level=level;
        workers=Executors.newFixedThreadPool(2,task->{
            Thread thread=new Thread(task,"CMI package collision bake");thread.setDaemon(true);return thread;
        });
        cache=new PackageCollisionCache(workers,MAX_SECTIONS);
        source=new PackageWorldCollisionSource(level);
    }
    private static void owner() {
        if(!Minecraft.getInstance().isSameThread())throw new IllegalStateException("Package collision preparation off client thread");
    }
    public static PackageCollisionRuntime forLevel(ClientLevel level) {
        owner();if(current!=null && current.level!=level)closeCurrent();
        if(current==null)current=new PackageCollisionRuntime(level);return current;
    }
    /** Only queue section identities. The bounded tick performs every actual world query. */
    public boolean request(AABB sweptBounds) {
        owner();int[] bounds=sections(sweptBounds);if(bounds==null)return false;
        boolean accepted=true;
        for(int x=bounds[0];x<=bounds[3];x++)for(int y=bounds[1];y<=bounds[4];y++)for(int z=bounds[2];z<=bounds[5];z++)
            accepted&=cache.request(new PackageCollisionCache.Section(x,y,z));
        return accepted;
    }
    /** Coverage includes all touched sections and revokes immediately on a world change. */
    public boolean covered(AABB sweptBounds) {
        owner();int[] bounds=sections(sweptBounds);if(bounds==null)return false;
        for(int x=bounds[0];x<=bounds[3];x++)for(int y=bounds[1];y<=bounds[4];y++)for(int z=bounds[2];z<=bounds[5];z++)
            if(cache.snapshot(new PackageCollisionCache.Section(x,y,z))==null)return false;
        return true;
    }
    public PackageCollisionCache.Snapshot snapshot(PackageCollisionCache.Section section){owner();return cache.snapshot(section);}
    private static int[] sections(AABB bounds) {
        double[] coordinates={bounds.minX,bounds.minY,bounds.minZ,bounds.maxX,bounds.maxY,bounds.maxZ};
        int[] result=new int[6];long count=1;
        for(int i=0;i<6;i++) {
            double value=Math.floor(coordinates[i]/16);
            // Actual block queries use signed int coordinates (including the tall Allay dimension).
            if(!Double.isFinite(value) || value<Integer.MIN_VALUE/16 || value>Integer.MAX_VALUE/16-1)return null;
            result[i]=(int)value;
        }
        for(int i=0;i<3;i++){long extent=(long)result[i+3]-result[i]+1;if(extent<=0 || extent>MAX_REQUEST_SECTIONS)return null;count*=extent;}
        return count>MAX_REQUEST_SECTIONS?null:result;
    }
    public static void blockChanged(ClientLevel level,BlockPos position) {
        if(current!=null && current.level==level)current.cache.invalidateBlock(position.getX(),position.getY(),position.getZ());
    }
    public static String report() {
        owner();if(current==null)return "Package collisions: inactive";
        return "Package collisions: "+current.cache.readyCount()+"/"+current.cache.size()+" sections ready, capture "
                +String.format(java.util.Locale.ROOT,"%.3f",current.cache.lastCaptureNanos()/1_000_000.0)
                +" ms, p50/p95 "+String.format(java.util.Locale.ROOT,"%.3f/%.3f",current.cache.capturePercentile(.5)/1_000_000.0,current.cache.capturePercentile(.95)/1_000_000.0)
                +" ms (0.250 ms soft budget), overruns "+current.cache.overrunCount();
    }
    public static void closeCurrent() {
        owner();if(current==null)return;
        current.cache.clear();
        // Let already queued immutable packing tasks finish, so their global worker accounting
        // is released. Shutdown never waits; no task has a level reference.
        current.workers.shutdown();current=null;
    }
    @SubscribeEvent public static void tick(ClientTickEvent.Post event) {
        if(current==null)return;
        if(Minecraft.getInstance().level!=current.level){closeCurrent();return;}
        current.cache.tick(current.source,PackageCollisionCache.DEFAULT_BUDGET_NANOS);
    }
    @SubscribeEvent public static void chunkLoaded(ChunkEvent.Load event){chunkChanged(event);}
    @SubscribeEvent public static void chunkUnloaded(ChunkEvent.Unload event){chunkChanged(event);}
    private static void chunkChanged(ChunkEvent event) {
        if(current!=null && event.getLevel()==current.level) {
            var position=event.getChunk().getPos();current.cache.invalidateChunk(position.x,position.z);
        }
    }
    @SubscribeEvent public static void unloaded(LevelEvent.Unload event) {
        if(current!=null && event.getLevel()==current.level)closeCurrent();
    }
    @SubscribeEvent public static void stopped(GameShuttingDownEvent event){closeCurrent();}
}
