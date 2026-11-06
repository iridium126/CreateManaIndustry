package com.iridium126.createmanaindustry.client.allaystorm;

import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.ChunkEvent;
import com.iridium126.createmanaindustry.client.packages.world.*;
import com.iridium126.createmanaindustry.content.allaystorm.network.ClientboundStormWavePacket;
import com.iridium126.createmanaindustry.client.particles.engine.StormSurfaceCapture;
import com.iridium126.createmanaindustry.client.particles.engine.FeatherSupportGpu;
import com.iridium126.createmanaindustry.client.particles.engine.spatial.RenderTerrain;
import static com.iridium126.createmanaindustry.client.allaystorm.StormCollisionPages.*;

/** Complete active geometry demand; independent capacity and compact GPU storage. */
@EventBusSubscriber(modid="createmanaindustry",value=Dist.CLIENT)
public final class StormCollisionRuntime implements AutoCloseable {
    /** Diagnostic generation of the atlas/table currently consumed by the shader. */
    public static long profileResourceGeneration(){return current==null?-1:current.gpu.revision();}
    private static StormCollisionRuntime current;
    private final ClientLevel level;
    private final StormCollisionCache cache=new StormCollisionCache();
    private final StormCollisionGpu gpu=new StormCollisionGpu();
    private long offered=-1,lastActive;
    private long nativeCaptureTick=Long.MIN_VALUE;
    private final Map<Section,Long> featherDemand=new HashMap<>();
    private long featherExpiry=Long.MAX_VALUE;
    private int[] bounds;
    private final StormCollisionDemand leases=new StormCollisionDemand();
    private Set<Section> activeDemand=Set.of();
    private long demandNanos;
    private String error="";
    private final BlockPos.MutableBlockPos position=new BlockPos.MutableBlockPos();
    private StormCollisionRuntime(ClientLevel level){this.level=level;}
    public static void refresh(boolean active,Vec3 center,float radius,ClientboundStormWavePacket[] attacks){
        var level=Minecraft.getInstance().level;if(current!=null&&current.level!=level)reset();
        if(level==null)return;
        if(current!=null&&level.getGameTime()>=current.featherExpiry){
            if(current.featherDemand.entrySet().removeIf(e->e.getValue()<=level.getGameTime()))current.bounds=null;
            current.featherExpiry=current.featherDemand.values().stream().mapToLong(Long::longValue).min().orElse(Long.MAX_VALUE);
        }
        if(!active&&(current==null||current.featherDemand.isEmpty())){if(current!=null&&level.getGameTime()-current.lastActive>20)reset();return;}
        if(current==null)current=new StormCollisionRuntime(level);
        current.lastActive=level.getGameTime();current.demand(active,center,radius,attacks);
    }
    private static final class Returning {final ClientboundStormWavePacket packet;final java.util.Set<Integer> members=new java.util.HashSet<>();Returning(ClientboundStormWavePacket p){packet=p;}}
    private final Map<Integer,Returning> returning=new HashMap<>();
    public static void clearReturns(){if(current!=null){current.returning.clear();current.bounds=null;}}
    public static boolean returning(){return current!=null&&!current.returning.isEmpty();}
    public static void completed(ClientboundStormWavePacket packet,long[] ranks){if(current==null)return;var set=java.util.BitSet.valueOf(ranks);for(int rank=set.nextSetBit(0);rank>=0;rank=set.nextSetBit(rank+1))if(rank>=packet.members().length||!AllayStormRuntime.INSTANCE.livingMember(packet.members()[rank]))set.clear(rank);if(set.isEmpty())return;StarfallGpuBuffers.INSTANCE.trackReturn(packet,set.toLongArray());var record=current.returning.computeIfAbsent(packet.waveId(),k->new Returning(packet));for(int rank=set.nextSetBit(0);rank>=0;rank=set.nextSetBit(rank+1))record.members.add(packet.members()[rank]);current.bounds=null;}
    /** Transfer ownership to the outer loop without fabricating a home-arrival ACK. */
    public static void detachReturn(int member){
        if(current==null)return;
        for(var it=current.returning.values().iterator();it.hasNext();){var record=it.next();if(record.members.remove(member)){if(record.members.isEmpty())it.remove();current.bounds=null;}}
    }
    public static void arrived(int id,int member){StarfallGpuBuffers.INSTANCE.arrived(id,member);if(current==null)return;var record=current.returning.get(id);if(record!=null&&record.members.remove(member)){if(record.members.isEmpty())current.returning.remove(id);current.bounds=null;}}
    public static void claimed(ClientboundStormWavePacket packet){if(current==null)return;for(var it=current.returning.values().iterator();it.hasNext();){var record=it.next();if(record.packet.waveId()==packet.waveId())continue;for(int m:packet.members())record.members.remove(m);if(record.members.isEmpty())it.remove();}current.bounds=null;}
    public static void dead(int member){if(current==null)return;for(var it=current.returning.values().iterator();it.hasNext();){var r=it.next();if(r.members.remove(member)&&r.members.isEmpty())it.remove();}current.bounds=null;}
    public static void retainFeathers(Collection<Section> sections,long ticks){
        var level=Minecraft.getInstance().level;if(level==null||sections.isEmpty())return;
        if(current!=null&&current.level!=level)reset();if(current==null)current=new StormCollisionRuntime(level);
        long expiry=level.getGameTime()+ticks;boolean changed=false;
        for(var section:sections)changed|=current.featherDemand.put(section,expiry)==null;
        current.featherExpiry=Math.min(current.featherExpiry,expiry);if(changed)current.bounds=null;
    }
    public static void releaseFeathers(){
        if(current==null||current.featherDemand.isEmpty())return;
        current.featherDemand.clear();current.featherExpiry=Long.MAX_VALUE;current.bounds=null;
    }
    private void demand(boolean active,Vec3 center,float radius,ClientboundStormWavePacket[] attacks){
        double pad=radius*1.15+20,minX=center.x-pad,maxX=center.x+pad,minZ=center.z-pad,maxZ=center.z+pad,minY=center.y-20,maxY=center.y+20;
        var coverage=new ArrayList<ClientboundStormWavePacket>();for(var p:attacks)if(p!=null&&!p.abort())coverage.add(p);for(var record:returning.values())coverage.add(record.packet);
        for(var p:coverage){
            double attackPad=p.helixAttack()?p.helix().radius()*1.6+20:p.column()?24:p.crownRadius()+20;
            minX=Math.min(minX,p.centerX()-attackPad);maxX=Math.max(maxX,p.centerX()+attackPad);
            minZ=Math.min(minZ,p.centerZ()-attackPad);maxZ=Math.max(maxZ,p.centerZ()+attackPad);
            minY=Math.min(minY,p.column()?p.landings()[1]-12:p.centerY()-12);maxY=Math.max(maxY,p.crownY()+20);
            if(p.dome()){maxY=Math.max(maxY,p.centerY()+40);minY=Math.min(minY,p.centerY()-20);}
            float[] route=p.helixAttack()?p.helix().controls():p.path();
            for(int i=0;i<route.length;i+=3){minX=Math.min(minX,route[i]-attackPad);maxX=Math.max(maxX,route[i]+attackPad);minY=Math.min(minY,route[i+1]-attackPad);maxY=Math.max(maxY,route[i+1]+attackPad);minZ=Math.min(minZ,route[i+2]-attackPad);maxZ=Math.max(maxZ,route[i+2]+attackPad);}
        }
        int[] next={(int)Math.floor(minX/16),(int)Math.floor(minY/16),(int)Math.floor(minZ/16),(int)Math.floor(maxX/16),(int)Math.floor(maxY/16),(int)Math.floor(maxZ/16)};
        if(!active)next=new int[]{0,0,0,-1,-1,-1};
        long tick=level.getGameTime();boolean changed=!Arrays.equals(bounds,next);
        if(!changed&&!leases.due(tick))return;
        long begin=System.nanoTime();
        if(changed){
            bounds=next;var keys=new LinkedHashSet<Section>();
            // A whole swept envelope also covers every member's return to the moving body.
            for(int y=next[1];y<=next[4];y++)for(int z=next[2];z<=next[5];z++)for(int x=next[0];x<=next[3];x++)keys.add(new Section(x,y,z));
            keys.addAll(featherDemand.keySet());activeDemand=keys;
        }
        List<Section> requested=new ArrayList<>(leases.update(activeDemand,tick));
        Vec3 eye=Minecraft.getInstance().player==null?center:Minecraft.getInstance().player.getEyePosition();
        // Prioritise nearby terrain without discarding any distant active demand.
        requested.sort(Comparator.comparingDouble(k->{double x=k.x()*16+8-eye.x,y=k.y()*16+8-eye.y,z=k.z()*16+8-eye.z;return x*x+y*y+z*z;}));
        cache.demand(new LinkedHashSet<>(requested));demandNanos=System.nanoTime()-begin;
    }
    private final StormCollisionCache.Source source=new StormCollisionCache.Source(){
        @SuppressWarnings("deprecation") public boolean loaded(Section key){return level.hasChunkAt(position.set(key.x()*16,key.y()*16,key.z()*16));}
        public Page snapshot(Section key,long revision){
            var shared=PackageCollisionRuntime.peekSnapshot(level,new PackageCollisionCache.Section(key.x(),key.y(),key.z()));
            if(shared!=null){
                if(shared.gpuEmpty())return new Page(revision,AIR,null);
                int[] bits=new int[WORDS];for(int i=0;i<4096;i++){
                    if((shared.flags(i)&PackageCollisionCache.UNSUPPORTED)!=0)return null;
                    if(shared.shapeEnd(i)>shared.shapeStart(i))bits[i>>>5]|=1<<(i&31);
                }return page(revision,bits);
            }
            if(level.dimension()==com.iridium126.createmanaindustry.dimension.AllvrDimensions.ALLAY_LEVEL&&!com.iridium126.createmanaindustry.dimension.AllvrDimensionLimits.isVanillaSection(key.y()))return null;
            var chunk=level.getChunkSource().getChunk(key.x(),key.z(),ChunkStatus.FULL,false);
            if(chunk!=null){int i=chunk.getSectionIndexFromSectionY(key.y());if(i<0||i>=chunk.getSections().length||chunk.getSections()[i].hasOnlyAir())return new Page(revision,AIR,null);}
            return null;
        }
        public boolean solid(Section key,int index){position.set(key.x()*16+(index&15),key.y()*16+(index>>>8),key.z()*16+((index>>>4)&15));return !level.getBlockState(position).getCollisionShape(level,position).isEmpty();}
    };
    private static int surfaceUploadBudget=131072;
    public static int surfaceUploadBudget(){return surfaceUploadBudget;}
    public static Page surfacePage(Section section){return current==null?null:current.cache.fresh(section);}
    public static long spatialVersion(){return current==null?-1:current.cache.version();}
    public static Object spatialSource(){return current==null?null:current.cache;}
    public static Map<Section,Page> spatialPages(){return current==null?Map.of():current.cache.ready();}
    public static void captureForNativeTick(){
        if(current!=null){
            current.cache.capture(current.source,1_000_000);
            current.nativeCaptureTick=current.level.getGameTime();
        }
    }
    public static void prepare(int program){
        long surfaceBegin=StormSurfaceCapture.enabled()?System.nanoTime():0;surfaceUploadBudget=131072;
        if(current==null){com.iridium126.createmanaindustry.client.gpu.shader.GpuUniforms.uniform1i(program,com.iridium126.createmanaindustry.client.gpu.shader.GpuUniforms.loc(program,"uStormCollisionEnabled"),0);StormSurfaceCapture.captureRemaining(1_000_000);return;}
        com.iridium126.createmanaindustry.client.gpu.shader.GpuUniforms.uniform1i(program,com.iridium126.createmanaindustry.client.gpu.shader.GpuUniforms.loc(program,"uStormCollisionEnabled"),1);
        if(!current.error.isEmpty()){
            com.iridium126.createmanaindustry.client.gpu.shader.GpuUniforms.uniform1i(program,com.iridium126.createmanaindustry.client.gpu.shader.GpuUniforms.loc(program,"uStormCollisionOn"),0);StormSurfaceCapture.captureRemaining(1_000_000);return;
        }
        try{
            if(current.nativeCaptureTick!=current.level.getGameTime())current.cache.capture(current.source,1_000_000);
            if(current.offered!=current.cache.version()){current.gpu.offer(current.cache.ready());current.offered=current.cache.version();}
            if(surfaceBegin!=0)StormSurfaceCapture.captureRemaining(Math.max(0,1_000_000-(System.nanoTime()-surfaceBegin)));
            long before=current.gpu.uploaded();current.gpu.pump(131072,500_000);surfaceUploadBudget=(int)Math.max(0,131072-(current.gpu.uploaded()-before));current.gpu.bind(program);
        }catch(RuntimeException|LinkageError failure){
            if(current.error.isEmpty())com.iridium126.createmanaindustry.CreateManaIndustry.LOGGER.error("Storm collision resource unavailable",failure);
            current.error=failure.toString();com.iridium126.createmanaindustry.client.gpu.shader.GpuUniforms.uniform1i(program,com.iridium126.createmanaindustry.client.gpu.shader.GpuUniforms.loc(program,"uStormCollisionOn"),0);
        }
    }
    /** Reuse this frame's immutable publication without a second capture budget. */
    public static void bindCurrent(int program){
        boolean available=current!=null&&current.error.isEmpty();
        for(int part:com.iridium126.createmanaindustry.client.particles.engine.DomeBodyContacts.related(program)){
            com.iridium126.createmanaindustry.client.gpu.shader.GpuUniforms.uniform1i(part,com.iridium126.createmanaindustry.client.gpu.shader.GpuUniforms.loc(part,"uStormCollisionEnabled"),current==null?0:1);
            if(!available)com.iridium126.createmanaindustry.client.gpu.shader.GpuUniforms.uniform1i(part,com.iridium126.createmanaindustry.client.gpu.shader.GpuUniforms.loc(part,"uStormCollisionOn"),0);
        }
        // The GPU owner already fans the immutable publication out to all parts.
        if(available)current.gpu.bind(program);
    }
    /** Existing immutable publication, without capture, demand or transfer. */
    public static StormCollisionGpu publication(){return current!=null&&current.error.isEmpty()?current.gpu:null;}
    public static boolean active(){return current!=null;}
    public static void submitted(){if(current!=null)current.gpu.submitted();}
    public static String status(){return current==null?"storm collision idle":current.cache.stats()+" demandMs="+current.demandNanos/1e6+" "+current.gpu.stats()+" error="+current.error;}
    public static String profilingReport(){return current==null?"":"Storm collision: "+String.format(java.util.Locale.ROOT,"demand=%.3f ms; ",current.demandNanos/1e6)
            +current.cache.profilingReport()+"\nStorm collision GPU: "+current.gpu.profilingReport()+(current.error.isEmpty()?"":"; error="+current.error);}
    public static void blockChanged(ClientLevel level,BlockPos pos){RenderTerrain.blockChanged(level,pos);StormSurfaceCapture.blockChanged(level,pos);FeatherSupportGpu.INSTANCE.blockChanged(level,pos);if(current==null||current.level!=level)return;int x=pos.getX()>>4,y=pos.getY()>>4,z=pos.getZ()>>4;for(int dx=-1;dx<=1;dx++)for(int dy=-1;dy<=1;dy++)for(int dz=-1;dz<=1;dz++)current.cache.dirty(new Section(x+dx,y+dy,z+dz));}
    public static void cubeChanged(long packed){
        RenderTerrain.cubeChanged(packed);
        var cube=com.iridium126.createmanaindustry.dimension.cube.AllvrCubePos.fromLong(packed);
        int size=com.iridium126.createmanaindustry.dimension.cube.AllvrCoords.DIAMETER_IN_BLOCKS,n=size/16;
        StormSurfaceCapture.cubeChanged(cube.getX()*size,cube.getZ()*size,size);
        FeatherSupportGpu.INSTANCE.cubeChanged(Minecraft.getInstance().level,cube.getX()*n,cube.getY()*n,cube.getZ()*n,n);
        if(current==null)return;
        for(int x=0;x<n;x++)for(int y=0;y<n;y++)for(int z=0;z<n;z++)
            current.cache.unavailable(new Section(cube.getX()*n+x,cube.getY()*n+y,cube.getZ()*n+z));
    }
    @SubscribeEvent public static void loaded(ChunkEvent.Load event){changed(event);}
    @SubscribeEvent public static void unloaded(ChunkEvent.Unload event){changed(event);}
    private static void changed(ChunkEvent event){if(event.getLevel() instanceof ClientLevel level){var p=event.getChunk().getPos();RenderTerrain.chunkChanged(level,p.x,p.z);StormSurfaceCapture.changed(level,p.x,p.z);FeatherSupportGpu.INSTANCE.chunkChanged(level,p.x,p.z);}if(current!=null&&event.getLevel()==current.level){var pos=event.getChunk().getPos();current.cache.dirtyColumn(pos.x,pos.z);}}
    public static void reset(){if(current!=null){current.close();current=null;}}
    @Override public void close(){gpu.close();}
}
