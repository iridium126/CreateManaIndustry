package com.iridium126.createmanaindustry.client.particles.engine;

import com.iridium126.createmanaindustry.client.allaystorm.*;
import com.iridium126.createmanaindustry.dimension.AllvrDimensionLimits;
import com.iridium126.createmanaindustry.dimension.AllvrDimensions;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import static com.iridium126.createmanaindustry.client.allaystorm.StormCollisionPages.*;

/** Reads loaded terrain on its owner thread; no gold voxel residency or worker world access. */
public final class StormSurfaceCapture implements StormSurfaceCache.Source {
    private static StormSurfaceCapture active;
    private final ClientLevel level;
    private final StormSurfaceCache cache;
    private final Thread owner=Thread.currentThread();
    private final BlockPos.MutableBlockPos pos=new BlockPos.MutableBlockPos();
    private int sx=Integer.MAX_VALUE,sy,sz,airFloor=Integer.MAX_VALUE;
    private Page page;
    private final boolean allvr;
    private StormSurfaceCapture(ClientLevel level,StormSurfaceCache cache){this.level=level;this.cache=cache;allvr=level.dimension()==AllvrDimensions.ALLAY_LEVEL;}
    public static void activate(ClientLevel level,StormSurfaceCache cache){if(active==null||active.level!=level||active.cache!=cache)active=new StormSurfaceCapture(level,cache);}
    public static void deactivate(StormSurfaceCache cache){if(active!=null&&active.cache==cache)active=null;}
    public static boolean enabled(){return active!=null;}
    public static void captureRemaining(long nanos){
        var current=active;if(current==null||!current.cache.pending()||nanos<=0)return;
        if(Thread.currentThread()!=current.owner)throw new IllegalStateException("Gold surface capture off owner thread");
        current.sx=Integer.MAX_VALUE;current.cache.capture(current,Math.min(200_000,nanos));
    }
    public static void changed(ClientLevel level,int tx,int tz){if(active!=null&&active.level==level)for(int z=tz-1;z<=tz+1;z++)for(int x=tx-1;x<=tx+1;x++)active.cache.changed(x,z,true);}
    public static void blockChanged(ClientLevel level,BlockPos p){
        if(active==null||active.level!=level)return;
        for(int z=(p.getZ()-1)>>4;z<=(p.getZ()+1)>>4;z++)for(int x=(p.getX()-1)>>4;x<=(p.getX()+1)>>4;x++)active.cache.changed(x,z,true);
    }
    public static void cubeChanged(int x,int z,int size){if(active!=null)for(int tz=(z>>4)-1;tz<=((z+size)>>4);tz++)for(int tx=(x>>4)-1;tx<=((x+size)>>4);tx++)active.cache.changed(tx,tz,true);}
    @Override public int minY(){return allvr?-AllvrDimensionLimits.Y_BOUND:level.getMinBuildHeight();}
    @Override public int startY(int x,int z,int top){
        if(allvr)return top; // Vanilla heightmaps do not describe the three-dimensional cube domain.
        var chunk=level.getChunkSource().getChunk(x>>4,z>>4,ChunkStatus.FULL,false);
        return chunk==null?top:Math.min(top,chunk.getHeight(Heightmap.Types.WORLD_SURFACE,x&15,z&15)+1);
    }
    @Override public int occupied(int x,int y,int z){
        airFloor=Integer.MAX_VALUE;int nx=x>>4,ny=y>>4,nz=z>>4;
        if(sx!=nx||sy!=ny||sz!=nz){sx=nx;sy=ny;sz=nz;page=StormCollisionRuntime.surfacePage(new Section(nx,ny,nz));}
        if(page!=null){if(page.kind()==AIR){airFloor=ny*16;return 0;}return page.solid(x,y,z)?1:0;}
        pos.set(x,y,z);if(!level.hasChunkAt(pos))return -1;
        if((x&15)==0&&!level.hasChunkAt(pos.set(x-1,y,z))||(x&15)==15&&!level.hasChunkAt(pos.set(x+1,y,z))
                ||(z&15)==0&&!level.hasChunkAt(pos.set(x,y,z-1))||(z&15)==15&&!level.hasChunkAt(pos.set(x,y,z+1))
                ||allvr&&(y&15)==0&&!level.hasChunkAt(pos.set(x,y-1,z))||allvr&&(y&15)==15&&!level.hasChunkAt(pos.set(x,y+1,z)))return -1;
        if(!allvr||AllvrDimensionLimits.isVanillaSection(ny)){
            var chunk=level.getChunkSource().getChunk(nx,nz,ChunkStatus.FULL,false);
            if(chunk!=null){int i=chunk.getSectionIndexFromSectionY(ny);if(i<0||i>=chunk.getSections().length||chunk.getSections()[i].hasOnlyAir()){airFloor=ny*16;return 0;}}
        }
        pos.set(x,y,z);return level.getBlockState(pos).getCollisionShape(level,pos).isEmpty()?0:1;
    }
    @Override public int airFloor(){return airFloor;}
}
