package com.iridium126.createmanaindustry.client.particles.engine;

import com.iridium126.createmanaindustry.CreateManaIndustry;
import com.iridium126.createmanaindustry.client.gpu.buffer.GpuBufferNames;
import com.iridium126.createmanaindustry.client.gpu.buffer.GpuReplayCommands;
import com.iridium126.createmanaindustry.client.gpu.shader.GpuUniforms;
import com.iridium126.createmanaindustry.client.gpu.state.GpuBindings;
import com.iridium126.createmanaindustry.client.particles.shaderpack.FeatherLeafSway;
import com.iridium126.createmanaindustry.client.particles.shaderpack.FeatherLeafMesh;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.ChunkPos;
import net.irisshaders.iris.shaderpack.materialmap.WorldRenderingSettings;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.*;

/** The ground kernel requests missing support blocks. Client lookups run under a per-tick budget. */
public final class FeatherSupportGpu implements AutoCloseable {
    public static final FeatherSupportGpu INSTANCE=new FeatherSupportGpu();
    static final int ROWS=8192, CACHE_LIMIT=ROWS/2;
    private static final int LOOKUPS=256;
    private static final FeatherLeafMesh.Face NO_FACE = new FeatherLeafMesh.Face(0, -1, 0, 0);
    private final LinkedHashMap<BlockPos,FeatherLeafMesh.Face> resolved=new LinkedHashMap<>();
    private final HashMap<Long,HashSet<BlockPos>> columns=new HashMap<>();
    private final LinkedHashSet<BlockPos> requested=new LinkedHashSet<>();
    private final int[] table=new int[ROWS*4];
    private ByteBuffer upload;
    private ClientLevel world;
    private FeatherLeafSway.Plan plan;
    private long epoch;
    private boolean dirty,staged,committed;
    private int buffer;
    private int faceCount;
    private FeatherSupportGpu(){}

    void bind(int program,ClientLevel level){
        var current=level!=null&&CreateManaIndustry.IRIS_ACTIVE?FeatherLeafSway.current():null;
        if(level!=world||current!=plan){
            reset();world=level;plan=current;
        }
        staged=plan!=null&&world!=null;
        GpuUniforms.setIntUniform(program,"uFeatherSupport",staged?1:0);
        if(!staged)return;
        var ids=WorldRenderingSettings.INSTANCE.getBlockStateIds();
        int count=0;
        for(var it=requested.iterator();it.hasNext()&&count<LOOKUPS;count++){
            var pos=it.next();it.remove();
            if(ids==null||!world.hasChunkAt(pos))continue;
            var state=world.getBlockState(pos);
            var face=FeatherLeafMesh.face(pos);
            if (face == null || !state.is(BlockTags.LEAVES) || ids.getInt(state) != face.material()) {
                face = NO_FACE;
            }
            if(resolved.size()>=CACHE_LIMIT)remove(resolved.firstEntry().getKey());
            resolved.put(pos,face);dirty=true;
            columns.computeIfAbsent(column(pos),key->new HashSet<>()).add(pos);
        }
        if(buffer==0){
            buffer=GL45.glCreateBuffers();
            GL45.glNamedBufferData(buffer,(ROWS+4L*CACHE_LIMIT)*16,GL15.GL_DYNAMIC_DRAW);
            upload=BufferUtils.createByteBuffer((ROWS+4*CACHE_LIMIT)*16);dirty=true;
        }
        publishIfDirty();
        GpuBindings.bindBase(GL43.GL_SHADER_STORAGE_BUFFER,2,buffer);
    }

    private void publishIfDirty() {
        if(dirty && buffer != 0){
            // The previous upload limited the buffer to its populated range.
            upload.clear();
            Arrays.fill(table,0);
            faceCount = 0;
            for (var entry : resolved.entrySet()) {
                var pos = entry.getKey();
                var face = entry.getValue();
                int x=pos.getX(),y=pos.getY(),z=pos.getZ();
                int h=x*0x8da6b343^y*0xd8163841^z*0xcb1ab31f;
                h^=h>>>16;
                for(int i=0;i<8;i++){
                    int at=((h+i)&(ROWS-1))*4;
                    if(table[at+3]!=0)continue;
                    table[at]=x;table[at+1]=y;table[at+2]=z;table[at+3]=1;
                    if (face != NO_FACE && FeatherLeafSway.matches(face.pass(), face.material())) {
                        int base = (ROWS + 4 * faceCount) * 16;
                        for (int corner = 0; corner < 4; corner++) {
                            int point = (face.order() >>> (corner * 2)) & 3;
                            int atCorner = base + 16 * corner;
                            upload.putFloat(atCorner, point & 1).putFloat(atCorner + 4, 1)
                                    .putFloat(atCorner + 8, point >>> 1)
                                    .putFloat(atCorner + 12, (face.sky() >>> (corner * 8)) & 255);
                        }
                        // Bits above the face ID select the actual terrain program.
                        table[at+3] = ((faceCount++ + 2) << 2) | face.pass();
                    }
                    break;
                }
            }
            upload.asIntBuffer().put(table);
            upload.limit((ROWS + 4 * faceCount) * 16);
            GpuReplayCommands.namedBufferSubData(buffer,0,upload);
            dirty=false;
        }
    }
    void request(long generation,int x,int y,int z){
        if(plan==null||generation!=epoch||requested.size()>=CACHE_LIMIT)return;
        var pos=new BlockPos(x,y,z);
        if(!resolved.containsKey(pos))requested.add(pos);
    }
    long epoch(){return epoch;}
    void commit(){committed=staged;staged=false;}
    boolean drawable(){
        if (!committed || plan == null || plan != FeatherLeafSway.current()) return false;
        publishIfDirty();
        return faceCount != 0;
    }
    int buffer(){return buffer;}

    public void meshChanged(long section) {
        if (world != null) sectionChanged(world, SectionPos.x(section), SectionPos.y(section), SectionPos.z(section));
    }
    private static long column(BlockPos pos){return ChunkPos.asLong(pos.getX()>>4,pos.getZ()>>4);}
    private void remove(BlockPos pos){
        if(resolved.remove(pos)==null)return;
        long key=column(pos);
        var entries=columns.get(key);
        if(entries!=null&&entries.remove(pos)&&entries.isEmpty())columns.remove(key);
        dirty=true;
    }
    public void blockChanged(ClientLevel level,BlockPos pos){
        if(level!=world||resolved.isEmpty())return;
        remove(pos);
    }
    public void sectionChanged(ClientLevel level,int x,int y,int z){
        if(level!=world)return;
        long key=ChunkPos.asLong(x,z);
        var entries=columns.get(key);
        if(entries==null)return;
        entries.removeIf(p->{
            if(Math.abs((p.getY()>>4)-y)>1)return false;
            resolved.remove(p);
            if(requested.size()<CACHE_LIMIT)requested.add(p);
            dirty=true;return true;
        });
        if(entries.isEmpty())columns.remove(key);
    }
    public void chunkChanged(ClientLevel level,int x,int z){
        if(level!=world)return;
        var entries=columns.remove(ChunkPos.asLong(x,z));
        if(entries!=null){entries.forEach(resolved::remove);dirty=true;}
    }
    public void cubeChanged(ClientLevel level,int x,int y,int z,int sections){
        if(level!=world||resolved.isEmpty())return;
        for(int dx=0;dx<sections;dx++)for(int dz=0;dz<sections;dz++)
            for(int dy=0;dy<sections;dy++)sectionChanged(level,x+dx,y+dy,z+dz);
    }
    void reset(){resolved.clear();columns.clear();requested.clear();world=null;plan=null;faceCount=0;dirty=true;staged=committed=false;epoch++;}
    @Override public void close(){reset();GpuBufferNames.delete(buffer);buffer=0;upload=null;}
}
