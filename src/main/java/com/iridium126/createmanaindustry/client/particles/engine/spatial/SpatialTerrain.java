package com.iridium126.createmanaindustry.client.particles.engine.spatial;

import com.iridium126.createmanaindustry.client.allaystorm.*;
import com.iridium126.createmanaindustry.client.gpu.buffer.GpuBufferNames;
import java.nio.*;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.*;
import com.iridium126.createmanaindustry.client.gpu.buffer.GpuReplayCommands;
import static com.iridium126.createmanaindustry.client.allaystorm.StormCollisionPages.*;

/** Revision-driven conservative boxes. Collision occupancy is never treated as proof of opacity. */
public final class SpatialTerrain implements AutoCloseable {
    public static final int CAPACITY=16384;
    private record Box(float x,float y,float z,float sx,float sy,float sz,int mask){}
    private record Tile(long revision,List<Box> boxes,boolean incomplete){}
    private final Map<Section,Tile> tiles=new LinkedHashMap<>();
    private final LinkedHashMap<Section,Page> dirty=new LinkedHashMap<>();
    private final ByteBuffer staging=BufferUtils.createByteBuffer(CAPACITY*64).order(ByteOrder.nativeOrder());
    private int buffer,count;
    private long sourceVersion=Long.MIN_VALUE,revision;
    private boolean incomplete;
    public int buffer(){if(buffer==0){buffer=GL45.glCreateBuffers();GL45.glNamedBufferData(buffer,CAPACITY*64L,GL15.GL_DYNAMIC_COPY);}return buffer;}
    public int count(){return count;}
    public long revision(){return revision;}
    public boolean incomplete(){return incomplete;}
    public void refresh(){
        long next=StormCollisionRuntime.spatialVersion();boolean changed=false;
        if(next!=sourceVersion){sourceVersion=next;var pages=StormCollisionRuntime.spatialPages();
            changed=tiles.keySet().removeIf(k->!pages.containsKey(k));dirty.keySet().removeIf(k->!pages.containsKey(k));
            for(var entry:pages.entrySet()){var old=tiles.get(entry.getKey());if(old==null||old.revision()!=entry.getValue().revision()){tiles.remove(entry.getKey());dirty.put(entry.getKey(),entry.getValue());changed=true;}}
        }
        var level=Minecraft.getInstance().level;
        if(level!=null&&!dirty.isEmpty()){
            var entry=dirty.entrySet().iterator().next();dirty.remove(entry.getKey());var key=entry.getKey();var page=entry.getValue();var boxes=new ArrayList<Box>();
            boolean truncated=page.kind()==UNKNOWN;
            if(page.kind()!=AIR&&page.kind()!=UNKNOWN){
                byte[] cells=new byte[4096];var position=new BlockPos.MutableBlockPos();
                for(int i=0;i<4096;i++)if(page.solid(i&15,i>>>8,(i>>>4)&15)){
                    position.set(key.x()*16+(i&15),key.y()*16+(i>>>8),key.z()*16+((i>>>4)&15));var state=level.getBlockState(position);
                    cells[i]=(byte)(state.isSolidRender(level,position)&&Block.isShapeFullBlock(state.getCollisionShape(level,position))?3:1);
                }
                // Merge identical x-runs into y/z rectangles; at most 256 boxes per tile.
                for(int y=0;y<16;y++)for(int z=0;z<16;z++)for(int x=0;x<16;x++){
                    int i=x+(z<<4)+(y<<8),mask=cells[i];if(mask==0)continue;
                    int dx=1;while(x+dx<16&&cells[i+dx]==mask)dx++;
                    int dz=1;outerZ:while(z+dz<16){for(int a=0;a<dx;a++)if(cells[i+(dz<<4)+a]!=mask)break outerZ;dz++;}
                    int dy=1;outerY:while(y+dy<16){for(int b=0;b<dz;b++)for(int a=0;a<dx;a++)if(cells[i+(dy<<8)+(b<<4)+a]!=mask)break outerY;dy++;}
                    for(int b=0;b<dy;b++)for(int c=0;c<dz;c++)for(int a=0;a<dx;a++)cells[i+(b<<8)+(c<<4)+a]=0;
                    if(boxes.size()<256)boxes.add(new Box(key.x()*16+x+dx*.5f,key.y()*16+y+dy*.5f,key.z()*16+z+dz*.5f,dx*.5f,dy*.5f,dz*.5f,mask));else truncated=true;
                }
            }
            tiles.put(key,new Tile(page.revision(),List.copyOf(boxes),truncated));changed=true;
        }
        if(!changed)return;staging.clear();count=0;incomplete=!dirty.isEmpty()||tiles.values().stream().anyMatch(Tile::incomplete);
        outer:for(var tile:tiles.values())for(var b:tile.boxes()){
            if(count>=CAPACITY){incomplete=true;break outer;}int at=count*64;for(int i=0;i<64;i+=4)staging.putInt(at+i,0);
            staging.putFloat(at,b.x()).putFloat(at+4,b.y()).putFloat(at+8,b.z()).putFloat(at+16,b.sx()).putFloat(at+20,b.sy()).putFloat(at+24,b.sz()).putInt(at+44,b.mask());count++;
        }
        staging.limit(count*64);if(count>0)GpuReplayCommands.namedBufferSubData(buffer(),0,staging);revision++;
    }
    @Override public void close(){GpuBufferNames.delete(buffer);buffer=count=0;tiles.clear();dirty.clear();sourceVersion=Long.MIN_VALUE;revision++;}
}
