package com.iridium126.createmanaindustry.client.particles.packages;

import java.nio.ByteBuffer;
import java.util.*;
import java.util.function.Function;
import com.iridium126.createmanaindustry.content.logistics.gpupackage.PackageChainGpuFrame;
import com.iridium126.createmanaindustry.content.logistics.gpupackage.PackageChainSpace;
import com.iridium126.createmanaindustry.content.logistics.gpupackage.network.ClientboundChainPackagePacket;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.BufferUtils;

/** Client world/track boundary. Immutable parent poses are captured once per structure;
 * native local track offsets are registered only on membership changes, expanded on GPU.
 * The optional SDK is confined to the typed bridge and never reaches ordinary worlds. */
public final class PackageChainFrameScene implements AutoCloseable {
    public interface Parent {
        UUID id();boolean valid();PackageChainGpuFrame capture(Vec3 origin);
    }
    public interface Bridge {Parent find(ClientLevel level,BlockPos conveyor);}
    private record Group(int index,Vec3 anchor,Parent source) {}
    private final ClientLevel level;
    private final Vec3 worldOrigin;
    private final Bridge bridge;
    private final PackageChainFramesGpu gpu;
    private final Map<Integer,ClientboundChainPackagePacket.Track> tracks=new HashMap<>();
    private final Map<UUID,Group> groups=new HashMap<>();
    private final ArrayList<Group> ordered=new ArrayList<>();
    private final ByteBuffer parents=BufferUtils.createByteBuffer(PackageChainFramesGpu.MAX_PARENTS*PackageChainGpuFrame.BYTES);
    private final PackageChainGpuFrame stationary;
    public static Bridge optionalBridge(boolean loaded){return loaded?new PackageSableChainFrames():null;}
    public PackageChainFrameScene(ClientLevel level,int capacity,double ox,double oy,double oz,Function<String,String> sources,boolean sable) {
        this.level=Objects.requireNonNull(level);worldOrigin=new Vec3(ox,oy,oz);bridge=optionalBridge(sable);
        var f=new PackageChainSpace.Frame(null,worldOrigin,worldOrigin,new Vec3(1,0,0),new Vec3(0,1,0),new Vec3(0,0,1));
        stationary=new PackageChainGpuFrame(f,f);ordered.add(new Group(0,worldOrigin,null));
        gpu=new PackageChainFramesGpu(capacity,sources);
    }
    /** Called only while preparing a track/lease transition, not for active packages. Null
     * retains Create ownership while the track's exact parent/chunk has not arrived. */
    public Vec3 origin(ClientboundChainPackagePacket.Track track) {
        if(!level.hasChunkAt(track.conveyor()))return null;
        var found=bridge==null?null:bridge.find(level,track.conveyor());
        if(!Objects.equals(track.parent(),found==null?null:found.id())||found!=null&&!found.valid())return null;
        var existing=tracks.get(track.index());
        if(existing!=null) {
            if(!existing.equals(track))throw new IllegalStateException("Chain frame track namespace changed");
            return Vec3.atCenterOf(track.conveyor());
        }
        if(track.index()!=gpu.count())return null;
        Vec3 nativeOrigin=Vec3.atCenterOf(track.conveyor());Group group=track.parent()==null?ordered.getFirst():groups.get(track.parent());
        if(group==null) {
            if(ordered.size()==PackageChainFramesGpu.MAX_PARENTS)throw new IllegalStateException("Chain parent capacity exhausted");
            group=new Group(ordered.size(),nativeOrigin,found);groups.put(track.parent(),group);ordered.add(group);
        }
        var d=nativeOrigin.subtract(group.anchor);gpu.append(track.index(),group.index,d.x,d.y,d.z);tracks.put(track.index(),track);
        return nativeOrigin;
    }
    /** Acquire a new immutable frame source. Bank exhaustion cannot expose stale parent
     * matrices as current: the runtime explicitly restores Create instead of waiting. */
    public void prepare(PackagePoolGpu pool) {
        parents.clear().limit(ordered.size()*PackageChainGpuFrame.BYTES);
        for(var group:ordered) {
            var capture=group.source==null?stationary:group.source.valid()?group.source.capture(group.anchor):null;
            if(capture==null||!capture.render().localOrigin().equals(group.anchor)
                    ||!Objects.equals(capture.render().parent(),group.source==null?null:group.source.id()))
                throw new IllegalStateException("Chain parent removed/replaced or pose unavailable");
            var row=parents.duplicate();row.position(group.index*PackageChainGpuFrame.BYTES).limit((group.index+1)*PackageChainGpuFrame.BYTES);
            capture.write(row,worldOrigin.x,worldOrigin.y,worldOrigin.z);
        }
        var view=gpu.view(parents,ordered.size());
        if(view==null)throw new IllegalStateException("Chain parent upload banks exhausted");pool.chainFrames(view);
    }
    public long uploadedBytes(){return gpu.uploadedBytes();}
    public long skipped(){return gpu.skipped();}
    @Override public void close(){gpu.close();tracks.clear();groups.clear();ordered.clear();}
}
