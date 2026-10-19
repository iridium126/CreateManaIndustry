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
import static com.iridium126.createmanaindustry.client.allaystorm.StormCollisionPages.*;

/** Complete active geometry demand; independent capacity and compact GPU storage. */
@EventBusSubscriber(modid="createmanaindustry",value=Dist.CLIENT)
public final class StormCollisionRuntime implements AutoCloseable {
    private static StormCollisionRuntime current;
    private final ClientLevel level;
    private final StormCollisionCache cache=new StormCollisionCache();
    private final StormCollisionGpu gpu=new StormCollisionGpu();
    private long offered=-1,lastActive;
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
        if(!active){if(current!=null&&level.getGameTime()-current.lastActive>20)reset();return;}
        if(current==null)current=new StormCollisionRuntime(level);
        current.lastActive=level.getGameTime();current.demand(center,radius,attacks);
    }
    private void demand(Vec3 center,float radius,ClientboundStormWavePacket[] attacks){
        double pad=radius*1.15+12,minX=center.x-pad,maxX=center.x+pad,minZ=center.z-pad,maxZ=center.z+pad,minY=center.y-20,maxY=center.y+20;
        for(var p:attacks)if(p!=null&&!p.abort()){
            double attackPad=p.helixAttack()?p.helix().radius()*1.6+12:p.column()?16:p.crownRadius()+12;
            minX=Math.min(minX,p.centerX()-attackPad);maxX=Math.max(maxX,p.centerX()+attackPad);
            minZ=Math.min(minZ,p.centerZ()-attackPad);maxZ=Math.max(maxZ,p.centerZ()+attackPad);
            minY=Math.min(minY,p.column()?p.landings()[1]-12:p.centerY()-12);maxY=Math.max(maxY,p.crownY()+20);
            if(p.dome())maxY=Math.max(maxY,p.centerY()+40);
            float[] route=p.helixAttack()?p.helix().controls():p.path();
            for(int i=0;i<route.length;i+=3){minX=Math.min(minX,route[i]-attackPad);maxX=Math.max(maxX,route[i]+attackPad);minY=Math.min(minY,route[i+1]-attackPad);maxY=Math.max(maxY,route[i+1]+attackPad);minZ=Math.min(minZ,route[i+2]-attackPad);maxZ=Math.max(maxZ,route[i+2]+attackPad);}
        }
        int[] next={(int)Math.floor(minX/16),(int)Math.floor(minY/16),(int)Math.floor(minZ/16),(int)Math.floor(maxX/16),(int)Math.floor(maxY/16),(int)Math.floor(maxZ/16)};
        long tick=level.getGameTime();boolean changed=!Arrays.equals(bounds,next);
        if(!changed&&!leases.due(tick))return;
        long begin=System.nanoTime();
        if(changed){
            bounds=next;var keys=new LinkedHashSet<Section>();
            // A whole swept envelope also covers every member's return to the moving body.
            for(int y=next[1];y<=next[4];y++)for(int z=next[2];z<=next[5];z++)for(int x=next[0];x<=next[3];x++)keys.add(new Section(x,y,z));
            activeDemand=keys;
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
    public static void prepare(int program){
        if(current==null){org.lwjgl.opengl.GL41.glProgramUniform1i(program,com.iridium126.createmanaindustry.client.gpu.shader.GpuUniforms.loc(program,"uStormCollisionEnabled"),0);return;}
        org.lwjgl.opengl.GL41.glProgramUniform1i(program,com.iridium126.createmanaindustry.client.gpu.shader.GpuUniforms.loc(program,"uStormCollisionEnabled"),1);
        if(!current.error.isEmpty()){
            org.lwjgl.opengl.GL41.glProgramUniform1i(program,com.iridium126.createmanaindustry.client.gpu.shader.GpuUniforms.loc(program,"uStormCollisionOn"),0);return;
        }
        try{
            current.cache.capture(current.source,1_000_000);
            if(current.offered!=current.cache.version()){current.gpu.offer(current.cache.ready());current.offered=current.cache.version();}
            current.gpu.pump(131072,500_000);current.gpu.bind(program);
        }catch(RuntimeException|LinkageError failure){
            if(current.error.isEmpty())com.iridium126.createmanaindustry.CreateManaIndustry.LOGGER.error("Storm collision resource unavailable",failure);
            current.error=failure.toString();org.lwjgl.opengl.GL41.glProgramUniform1i(program,com.iridium126.createmanaindustry.client.gpu.shader.GpuUniforms.loc(program,"uStormCollisionOn"),0);
        }
    }
    public static void submitted(){if(current!=null)current.gpu.submitted();}
    public static String status(){return current==null?"storm collision idle":current.cache.stats()+" demandMs="+current.demandNanos/1e6+" "+current.gpu.stats()+" error="+current.error;}
    public static void blockChanged(ClientLevel level,BlockPos pos){if(current==null||current.level!=level)return;int x=pos.getX()>>4,y=pos.getY()>>4,z=pos.getZ()>>4;for(int dx=-1;dx<=1;dx++)for(int dy=-1;dy<=1;dy++)for(int dz=-1;dz<=1;dz++)current.cache.dirty(new Section(x+dx,y+dy,z+dz));}
    public static void cubeChanged(long packed){if(current==null)return;var cube=com.iridium126.createmanaindustry.dimension.cube.AllvrCubePos.fromLong(packed);int size=com.iridium126.createmanaindustry.dimension.cube.AllvrCoords.DIAMETER_IN_BLOCKS;int n=size/16;for(int x=0;x<n;x++)for(int y=0;y<n;y++)for(int z=0;z<n;z++)current.cache.unavailable(new Section(cube.getX()*n+x,cube.getY()*n+y,cube.getZ()*n+z));}
    @SubscribeEvent public static void loaded(ChunkEvent.Load event){changed(event);}
    @SubscribeEvent public static void unloaded(ChunkEvent.Unload event){changed(event);}
    private static void changed(ChunkEvent event){if(current!=null&&event.getLevel()==current.level){var pos=event.getChunk().getPos();current.cache.dirtyColumn(pos.x,pos.z);}}
    public static void reset(){if(current!=null){current.close();current=null;}}
    @Override public void close(){gpu.close();}
}
