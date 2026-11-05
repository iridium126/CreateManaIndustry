package com.iridium126.createmanaindustry.client.particles.engine.spatial;

import com.iridium126.createmanaindustry.client.allaystorm.StormCollisionCache;
import com.iridium126.createmanaindustry.dimension.AllvrDimensionLimits;
import com.iridium126.createmanaindustry.dimension.AllvrDimensions;
import com.iridium126.createmanaindustry.dimension.cube.AllvrCoords;
import com.iridium126.createmanaindustry.dimension.cube.AllvrCubePos;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.ItemBlockRenderTypes;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.phys.Vec3;
import static com.iridium126.createmanaindustry.client.allaystorm.StormCollisionPages.*;

/** Visibility needs its own coverage even in a world without a Storm. */
public final class RenderTerrain implements AutoCloseable {
    private static final AtomicLong SCENES=new AtomicLong(1);
    private static RenderTerrain active;
    private static final int HORIZONTAL=3,VERTICAL=2;
    private static final long CAPTURE_NANOS=220_000;
    private final SpatialTerrain terrain=new SpatialTerrain(SpatialTerrain.OPAQUE);
    private final BlockPos.MutableBlockPos position=new BlockPos.MutableBlockPos();
    private StormCollisionCache cache;
    private ClientLevel level;
    private Section focus;
    private long scene;

    private final StormCollisionCache.Source source=new StormCollisionCache.Source(){
        @SuppressWarnings("deprecation")
        @Override public boolean loaded(Section key){
            return level.hasChunkAt(position.set(key.x()*16,key.y()*16,key.z()*16));
        }
        @Override public Page snapshot(Section key,long revision){
            // Cube-backed sections can contain geometry outside the vanilla array.
            if(level.dimension().equals(AllvrDimensions.ALLAY_LEVEL)&&!AllvrDimensionLimits.isVanillaSection(key.y()))return null;
            var chunk=level.getChunkSource().getChunk(key.x(),key.z(),ChunkStatus.FULL,false);
            if(chunk==null)return null;
            int i=chunk.getSectionIndexFromSectionY(key.y());
            return i<0||i>=chunk.getSections().length||chunk.getSections()[i].hasOnlyAir()?new Page(revision,AIR,null):null;
        }
        @Override public boolean solid(Section key,int index){
            position.set(key.x()*16+(index&15),key.y()*16+(index>>>8),key.z()*16+((index>>>4)&15));
            var state=level.getBlockState(position);
            return opaque(level,position,state);
        }
    };

    /** A collision box alone says nothing about the visible material. */
    @SuppressWarnings("deprecation")
    static boolean opaque(ClientLevel level,BlockPos position,BlockState state){
        return state.getRenderShape()==net.minecraft.world.level.block.RenderShape.MODEL
            &&ItemBlockRenderTypes.getChunkRenderType(state)==RenderType.solid()
            &&state.isSolidRender(level,position)
            &&Block.isShapeFullBlock(state.getOcclusionShape(level,position));
    }

    boolean prepare(Vec3 camera){
        var next=Minecraft.getInstance().level;
        if(next!=level){
            close();level=next;
            if(level!=null){cache=new StormCollisionCache();scene=SCENES.getAndIncrement();active=this;}
        }
        if(level==null)return false;
        var section=new Section((int)Math.floor(camera.x/16),(int)Math.floor(camera.y/16),(int)Math.floor(camera.z/16));
        if(!section.equals(focus)){
            focus=section;
            var requested=new ArrayList<Section>();
            for(int y=-VERTICAL;y<=VERTICAL;y++)for(int z=-HORIZONTAL;z<=HORIZONTAL;z++)for(int x=-HORIZONTAL;x<=HORIZONTAL;x++)
                requested.add(new Section(section.x()+x,section.y()+y,section.z()+z));
            requested.sort(Comparator.comparingInt(k->Math.abs(k.x()-section.x())+Math.abs(k.y()-section.y())+Math.abs(k.z()-section.z())));
            cache.demand(new LinkedHashSet<>(requested));
        }
        cache.capture(source,CAPTURE_NANOS);terrain.refreshOpaque(cache);
        return true;
    }
    SpatialTerrain terrain(){return terrain;}
    long scene(){return scene;}

    public static void blockChanged(ClientLevel level,BlockPos pos){
        var a=active;if(a==null||a.level!=level)return;
        int x=pos.getX()>>4,y=pos.getY()>>4,z=pos.getZ()>>4;
        // Block shape and solid-render tests can depend on neighboring blocks.
        for(int dx=-1;dx<=1;dx++)for(int dy=-1;dy<=1;dy++)for(int dz=-1;dz<=1;dz++)a.cache.dirty(new Section(x+dx,y+dy,z+dz));
    }
    public static void chunkChanged(ClientLevel level,int x,int z){
        var a=active;if(a==null||a.level!=level)return;
        for(int dx=-1;dx<=1;dx++)for(int dz=-1;dz<=1;dz++)a.cache.dirtyColumn(x+dx,z+dz);
    }
    public static void cubeChanged(long packed){
        var a=active;if(a==null)return;
        var cube=AllvrCubePos.fromLong(packed);int n=AllvrCoords.DIAMETER_IN_BLOCKS/16;
        for(int x=-1;x<=n;x++)for(int y=-1;y<=n;y++)for(int z=-1;z<=n;z++)
            a.cache.dirty(new Section(cube.getX()*n+x,cube.getY()*n+y,cube.getZ()*n+z));
    }
    @Override public void close(){
        if(active==this)active=null;
        terrain.close();cache=null;level=null;focus=null;scene=0;
    }
}
