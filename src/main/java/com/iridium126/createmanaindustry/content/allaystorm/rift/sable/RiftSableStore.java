package com.iridium126.createmanaindustry.content.allaystorm.rift.sable;

import com.iridium126.createmanaindustry.content.allaystorm.rift.RiftTransferJournal;
import com.iridium126.createmanaindustry.mixin.allaystorm.sable.*;
import dev.ryanhcode.sable.api.sublevel.*;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.storage.holding.*;
import dev.ryanhcode.sable.sublevel.storage.serialization.*;
import net.minecraft.nbt.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import java.nio.*;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.*;

/** Exclusive, asynchronous native storage checkpoint. Workers see detached NBT and storage codecs only. */
public final class RiftSableStore {
    private static final Map<SubLevelHoldingChunkMap,Job> BUSY=new IdentityHashMap<>();
    private static final Set<Job> LIVE=Collections.newSetFromMap(new IdentityHashMap<>());
    private static final Pattern REGION=Pattern.compile("r\\.(-?\\d+)\\.(-?\\d+)\\.slvlr");
    public record Located(GlobalSavedSubLevelPointer pointer,CompoundTag tag){}
    private record Input(SubLevelStorage storage,Map<ChunkPos,CompoundTag> holding,Set<GlobalSavedSubLevelPointer> deletes){}
    private record Output(Map<UUID,Located> found,Map<ChunkPos,CompoundTag> changed,Set<GlobalSavedSubLevelPointer> removed){}
    public static void poll(){for(Job job:List.copyOf(LIVE))if(job.ready()){try{job.finish();}catch(Throwable failure){com.mojang.logging.LogUtils.getLogger().error("Sable rift storage checkpoint failed; journal retained",failure);LIVE.remove(job);}}}
    /** Only server shutdown drains detached disk I/O; tick preparation never waits on a future. */
    public static void stopping(net.minecraft.server.MinecraftServer server){for(Job job:List.copyOf(LIVE)){server.managedBlock(job::ready);try{job.finish();}catch(Throwable error){com.mojang.logging.LogUtils.getLogger().error("Incomplete Sable rift checkpoint retained for recovery",error);}}}
    public static void clear(){LIVE.clear();BUSY.clear();RiftSableFreeze.clear();RiftSableRecovery.clear();RiftPlotReservations.clear();}
    public static boolean defer(SubLevelHoldingChunkMap map,Runnable action){Job job=BUSY.get(map);if(job==null)return false;job.deferred.add(action);return true;}
    public static boolean busy(SubLevelHoldingChunkMap map){return BUSY.containsKey(map);}
    public static boolean available(List<ServerLevel> levels){for(var level:levels)if(busy(SubLevelContainer.getContainer(level).getHoldingChunkMap()))return false;return true;}
    public static final class Job {
        private final List<ServerLevel> levels;
        private final List<SubLevelHoldingChunkMap> maps=new ArrayList<>();
        private final List<Runnable> deferred=new ArrayList<>();
        private final CompletableFuture<List<Output>> future;
        private List<Output> output;
        private final boolean writing;
        private final Set<UUID> ids;
        public Job(List<ServerLevel> levels,Set<UUID> ids,ServerLevel canonical,List<CompoundTag> tags,RiftTransferJournal journal) {
            this.levels=List.copyOf(levels);this.ids=Set.copyOf(ids);writing=canonical!=null;
            for(ServerLevel level:levels){var map=SubLevelContainer.getContainer(level).getHoldingChunkMap();if(BUSY.containsKey(map))throw new IllegalStateException("Sable storage checkpoint busy; retry this group");maps.add(map);}
            var inputs=new ArrayList<Input>();
            for(int i=0;i<levels.size();i++) {
                var level=levels.get(i);var map=maps.get(i);if(!(map instanceof RiftHoldingAccessor access)||!(map.getStorage() instanceof RiftStorageAccessor))throw new IllegalStateException("Sable storage ABI mixins unavailable");
                Map<ChunkPos,CompoundTag> holding=new LinkedHashMap<>();for(var chunk:access.cmi$chunks().values()){CompoundTag tag=new CompoundTag();chunk.writeTo(tag);holding.put(chunk.getChunkPos(),tag);}
                inputs.add(new Input(map.getStorage(),holding,Set.copyOf(access.cmi$deleted())));BUSY.put(map,this);
            }
            int destination=canonical==null?-1:levels.indexOf(canonical);List<CompoundTag> detached=tags.stream().map(CompoundTag::copy).toList();
            future=CompletableFuture.supplyAsync(()->{
                try {
                    var outputs=new ArrayList<Output>();for(Input input:inputs)outputs.add(scan(input,this.ids));
                    if(writing)for(int i=0;i<inputs.size();i++)write(inputs.get(i),outputs.get(i),this.ids,i==destination?detached:List.of());
                    return outputs;
                }catch(Exception error){throw new CompletionException(error);}
            },journal.executor());
            LIVE.add(this);
        }
        public boolean ready(){return future.isDone();}
        public Map<UUID,Located> found(ServerLevel level){finish();return output.get(levels.indexOf(level)).found;}
        public void finish() {
            if(output!=null)return;if(!future.isDone())throw new IllegalStateException("Storage checkpoint is still pending");
            // Keep exclusivity on a failed write. An incomplete group and its caches stay quarantined.
            try{output=future.getNow(null);}catch(Throwable error){if(!writing){for(var map:maps)BUSY.remove(map);LIVE.remove(this);for(Runnable action:List.copyOf(deferred))action.run();deferred.clear();}throw error;}
            for(int i=0;i<maps.size();i++) {
                var map=maps.get(i);var access=(RiftHoldingAccessor)map;
                if(writing) {
                    var result=output.get(i);
                    for(var entry:result.changed.entrySet()) {
                        var holding=access.cmi$chunks().get(entry.getKey().toLong());
                        if(holding==null){holding=SubLevelHoldingChunk.from(entry.getKey(),entry.getValue());access.cmi$chunks().put(entry.getKey().toLong(),holding);}
                        else {holding.getSubLevelPointers().clear();holding.getSubLevelPointers().addAll(SubLevelHoldingChunk.from(entry.getKey(),entry.getValue()).getSubLevelPointers());}
                    }
                    access.cmi$deleted().removeAll(result.removed);
                    for(UUID uuid:ids)access.cmi$holding().remove(uuid);
                    var container=SubLevelContainer.getContainer(levels.get(i));for(var entry:result.found.entrySet()){var sub=container.getSubLevel(entry.getKey());if(sub instanceof ServerSubLevel server)server.setLastSerializationPointer(entry.getValue().pointer);}
                }
                BUSY.remove(map);
            }
            LIVE.remove(this);
            for(Runnable action:List.copyOf(deferred))action.run();deferred.clear();
        }
    }
    private static Output scan(Input input,Set<UUID> ids)throws IOException {
        input.storage.flush();var access=(RiftStorageAccessor)input.storage;
        Map<ChunkPos,CompoundTag> all=new LinkedHashMap<>();var found=new LinkedHashMap<UUID,Located>();
        Path folder=input.storage.getFolder();
        if(Files.isDirectory(folder))try(var files=Files.list(folder)) {
            for(Path path:files.sorted().toList()) {
                Matcher match=REGION.matcher(path.getFileName().toString());if(!match.matches())continue;int rx=Integer.parseInt(match.group(1)),rz=Integer.parseInt(match.group(2));
                ByteBuffer header=ByteBuffer.allocate(4096);try(var in=FileChannel.open(path,StandardOpenOption.READ)){while(header.hasRemaining()&&in.read(header)>0){}}header.flip();
                for(int j=0;j<1024&&header.remaining()>=4;j++){if(header.getInt()==0)continue;ChunkPos pos=new ChunkPos(rx*32+(j&31),rz*32+(j>>5));CompoundTag tag=access.cmi$region(pos).read(j);if(tag!=null)all.put(pos,tag);}
            }
        }
        // Scan disk pointers before replacing them with the newer native in-memory holding list.
        for(var entry:all.entrySet())inspect(access,entry.getKey(),entry.getValue(),ids,found);
        for(var entry:input.holding.entrySet()){inspect(access,entry.getKey(),entry.getValue(),ids,found);all.put(entry.getKey(),entry.getValue().copy());}
        for(var pointer:input.deletes){CompoundTag t=access.cmi$data(pointer.chunkPos(),pointer.storageIndex()).read(pointer.subLevelIndex());if(t!=null&&t.hasUUID("uuid")&&ids.contains(t.getUUID("uuid")))found.put(t.getUUID("uuid"),new Located(pointer,t));}
        return new Output(found,all,new HashSet<>());
    }
    private static void inspect(RiftStorageAccessor access,ChunkPos pos,CompoundTag tag,Set<UUID> ids,Map<UUID,Located> found)throws IOException {
        for(int value:tag.getIntArray("pointers")){var p=SavedSubLevelPointer.unpack(value);CompoundTag sub=access.cmi$data(pos,p.storageIndex()).read(p.subLevelIndex());if(sub!=null&&sub.hasUUID("uuid")&&ids.contains(sub.getUUID("uuid"))){UUID uuid=sub.getUUID("uuid");Located previous=found.put(uuid,new Located(new GlobalSavedSubLevelPointer(pos,p.storageIndex(),p.subLevelIndex()),sub));if(previous!=null&&!previous.pointer.equals(found.get(uuid).pointer))throw new IOException("Duplicate structure UUID on disk: "+uuid);}}
    }
    private static void write(Input input,Output output,Set<UUID> ids,List<CompoundTag> canonical)throws IOException {
        var access=(RiftStorageAccessor)input.storage;
        for(Located old:output.found.values()) {
            output.removed.add(old.pointer);
            CompoundTag holding=output.changed.get(old.pointer.chunkPos());if(holding!=null){int remove=old.pointer.local().packed();holding.putIntArray("pointers",Arrays.stream(holding.getIntArray("pointers")).filter(v->v!=remove).toArray());}
            access.cmi$data(old.pointer.chunkPos(),old.pointer.storageIndex()).write(old.pointer.subLevelIndex(),(CompoundTag)null);
        }
        output.found.clear();
        for(CompoundTag tag:canonical) {
            var data=SubLevelSerializer.fromData(tag);if(data==null)throw new IOException("Invalid native structure snapshot");var p=data.pose().position();ChunkPos pos=new ChunkPos(BlockPos.containing(p.x,p.y,p.z));GlobalSavedSubLevelPointer pointer=null;
            for(int index=0;index<Short.MAX_VALUE;index++){var file=access.cmi$data(pos,index);int slot=file.findFreeIndex();if(slot>=file.getTotalIndexCapacity())continue;file.write(slot,tag);if(!tag.equals(file.read(slot)))throw new IOException("Native structure checkpoint readback mismatch");pointer=new GlobalSavedSubLevelPointer(pos,(short)index,(short)slot);break;}
            if(pointer==null)throw new IOException("No native storage slot");output.found.put(data.uuid(),new Located(pointer,tag));
            CompoundTag holding=output.changed.computeIfAbsent(pos,ignored->new CompoundTag());int[] old=holding.getIntArray("pointers"),next=Arrays.copyOf(old,old.length+1);next[old.length]=pointer.local().packed();holding.putIntArray("pointers",next);
        }
        for(var entry:output.changed.entrySet()) {
            var file=access.cmi$region(entry.getKey());int index=entry.getKey().getRegionLocalX()+(entry.getKey().getRegionLocalZ()<<5);
            if(entry.getValue().getIntArray("pointers").length==0)file.write(index,(CompoundTag)null);else file.write(index,entry.getValue());
        }
        input.storage.flush();
    }
    private RiftSableStore(){}
}
