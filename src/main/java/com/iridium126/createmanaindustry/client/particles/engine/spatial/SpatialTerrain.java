package com.iridium126.createmanaindustry.client.particles.engine.spatial;

import com.iridium126.createmanaindustry.client.allaystorm.*;
import com.iridium126.createmanaindustry.client.gpu.buffer.GpuBufferNames;
import com.iridium126.createmanaindustry.client.gpu.buffer.GpuReplayCommands;
import java.nio.*;
import java.util.*;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.*;
import static com.iridium126.createmanaindustry.client.allaystorm.StormCollisionPages.*;

/** One material class per worker; packet revisions track changes in actual geometry. */
public final class SpatialTerrain implements AutoCloseable {
    public static final int CAPACITY=com.iridium126.createmanaindustry.client.gpu.GpuResourceConstants.STORM_SPATIAL_TERRAIN_LIMIT;
    static final int COLLISION=1,OPAQUE=2;
    private record Box(float x,float y,float z,float sx,float sy,float sz){}
    private record Tile(long revision,List<Box> boxes){}
    private final int mask;
    private final Map<Section,Tile> tiles=new LinkedHashMap<>();
    private final ByteBuffer staging=BufferUtils.createByteBuffer(CAPACITY*64).order(ByteOrder.nativeOrder());
    private int buffer,count;
    private long sourceVersion=Long.MIN_VALUE,revision;
    private Object source;
    private long uploadedRevision=Long.MIN_VALUE;
    SpatialTerrain(int mask){this.mask=mask;}
    public int buffer(){
        if(buffer==0){buffer=GL45.glCreateBuffers();GL45.glNamedBufferData(buffer,CAPACITY*64L,GL15.GL_DYNAMIC_COPY);}
        if(uploadedRevision!=revision){if(count>0)GpuReplayCommands.namedBufferSubData(buffer,0,nativeRows());uploadedRevision=revision;}
        return buffer;
    }
    public int count(){return count;}
    public long revision(){return revision;}
    public ByteBuffer nativeRows(){return staging.asReadOnlyBuffer().order(ByteOrder.nativeOrder()).position(0).limit(count*64);}
    private static List<Box> boxes(Section key,byte[] cells){
        var result=new ArrayList<Box>();
        for(int y=0;y<16;y++)for(int z=0;z<16;z++)for(int x=0;x<16;x++){
            int i=x+(z<<4)+(y<<8);if(cells[i]==0)continue;
            int dx=1;while(x+dx<16&&cells[i+dx]!=0)dx++;
            int dz=1;outerZ:while(z+dz<16){for(int a=0;a<dx;a++)if(cells[i+(dz<<4)+a]==0)break outerZ;dz++;}
            int dy=1;outerY:while(y+dy<16){for(int b=0;b<dz;b++)for(int a=0;a<dx;a++)if(cells[i+(dy<<8)+(b<<4)+a]==0)break outerY;dy++;}
            for(int b=0;b<dy;b++)for(int c=0;c<dz;c++)for(int a=0;a<dx;a++)cells[i+(b<<8)+(c<<4)+a]=0;
            if(result.size()>=256)return List.copyOf(result);
            result.add(new Box(key.x()*16+x+dx*.5f,key.y()*16+y+dy*.5f,key.z()*16+z+dz*.5f,dx*.5f,dy*.5f,dz*.5f));
        }
        return List.copyOf(result);
    }
    private static List<Box> geometry(Section key,Page page){
        if(page.kind()==AIR||page.kind()==UNKNOWN)return List.of();
        byte[] cells=new byte[4096];for(int i=0;i<4096;i++)if(page.solid(i&15,i>>>8,(i>>>4)&15))cells[i]=1;
        return boxes(key,cells);
    }
    public void refresh(){
        var nextSource=StormCollisionRuntime.spatialSource();
        long next=StormCollisionRuntime.spatialVersion();
        if(next!=sourceVersion||source!=nextSource)refresh(nextSource,next,StormCollisionRuntime.spatialPages());
    }
    void refreshOpaque(StormCollisionCache cache){
        long next=cache.version();
        if(next!=sourceVersion||source!=cache)refresh(cache,next,cache.ready());
    }
    private void refresh(Object nextSource,long next,Map<Section,Page> pages){
        boolean replaced=source!=nextSource;
        source=nextSource;
        sourceVersion=next;
        boolean changed=false;
        for(var it=tiles.entrySet().iterator();it.hasNext();){
            var entry=it.next();if(!pages.containsKey(entry.getKey())){changed|=!entry.getValue().boxes().isEmpty();it.remove();}
        }
        for(var entry:pages.entrySet()){
            var key=entry.getKey();var page=entry.getValue();var old=tiles.get(key);
            if(!replaced&&old!=null&&old.revision()==page.revision())continue;
            var geometry=geometry(key,page);
            changed|=old==null?!geometry.isEmpty():!old.boxes().equals(geometry);
            tiles.put(key,new Tile(page.revision(),geometry));
        }
        if(!changed)return;staging.clear();count=0;
        outer:for(var entry:tiles.entrySet())for(var box:entry.getValue().boxes()){
            if(count>=CAPACITY)break outer;int at=count*64;for(int i=0;i<64;i+=4)staging.putInt(at+i,0);
            var section=entry.getKey();
            staging.putFloat(at,box.x()).putFloat(at+4,box.y()).putFloat(at+8,box.z()).putFloat(at+16,box.sx()).putFloat(at+20,box.sy()).putFloat(at+24,box.sz());
            staging.putInt(at+32,section.x()).putInt(at+36,section.y()).putInt(at+40,section.z()).putInt(at+44,mask);count++;
        }
        staging.limit(count*64);revision++;
    }
    @Override public void close(){GpuBufferNames.delete(buffer);buffer=count=0;tiles.clear();source=null;sourceVersion=uploadedRevision=Long.MIN_VALUE;revision++;}
}
