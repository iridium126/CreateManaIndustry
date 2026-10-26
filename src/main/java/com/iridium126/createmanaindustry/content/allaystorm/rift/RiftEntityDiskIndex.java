package com.iridium126.createmanaindustry.content.allaystorm.rift;

import com.iridium126.createmanaindustry.mixin.allaystorm.*;
import net.minecraft.nbt.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.SimpleRegionStorage;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.storage.LevelResource;
import java.nio.*;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.*;

/** A detached disk scan finds stale copies in previously saved chunks, not just the current entity chunk. */
public final class RiftEntityDiskIndex {
    private static final Map<SimpleRegionStorage,List<Scan>> WATCHERS=new IdentityHashMap<>();
    /** A cold scan plus subsequent native save observations; moving vehicles remain unfrozen during disk discovery. */
    public static final class Scan implements AutoCloseable {
        private final SimpleRegionStorage storage;
        private final Set<UUID> ids;
        private final Map<ChunkPos,Set<UUID>> changes=new LinkedHashMap<>();
        private final CompletableFuture<Map<UUID,Set<ChunkPos>>> result;
        public Scan(ServerLevel level,Set<UUID> ids,Executor executor){this.storage=storage(level);this.ids=Set.copyOf(ids);WATCHERS.computeIfAbsent(storage,ignored->new ArrayList<>()).add(this);result=scan(level,ids,executor);}
        public boolean ready(){return result.isDone();}
        public Map<UUID,Set<ChunkPos>> snapshot(){if(!ready())throw new IllegalStateException("Entity disk scan pending");var snapshot=new HashMap<UUID,Set<ChunkPos>>();for(var entry:result.getNow(Map.of()).entrySet())snapshot.put(entry.getKey(),new LinkedHashSet<>(entry.getValue()));for(var change:changes.entrySet()){for(var entries:snapshot.values())entries.remove(change.getKey());for(UUID id:change.getValue())snapshot.computeIfAbsent(id,ignored->new LinkedHashSet<>()).add(change.getKey());}snapshot.entrySet().removeIf(e->e.getValue().isEmpty());return snapshot;}
        public void close(){var watchers=WATCHERS.get(storage);if(watchers!=null){watchers.remove(this);if(watchers.isEmpty())WATCHERS.remove(storage);}}
    }
    public static void changed(SimpleRegionStorage storage,ChunkPos pos,java.util.stream.Stream<net.minecraft.world.entity.Entity> values){var watchers=WATCHERS.get(storage);if(watchers==null)return;var entities=values.toList();for(Scan scan:watchers){var present=new HashSet<UUID>();for(var root:entities)if(!root.isPassenger())root.getPassengersAndSelf().filter(e->!(e instanceof net.minecraft.server.level.ServerPlayer)&&scan.ids.contains(e.getUUID())).forEach(e->present.add(e.getUUID()));scan.changes.put(pos,Set.copyOf(present));}}
    public static void clear(){WATCHERS.clear();}
    private static final Pattern REGION=Pattern.compile("r\\.(-?\\d+)\\.(-?\\d+)\\.mca");
    public static SimpleRegionStorage storage(ServerLevel level){return ((RiftEntityStorageAccessor)((RiftEntityManagerAccessor)((RiftLevelEntitiesAccessor)level).cmi$entities()).cmi$storage()).cmi$region();}
    public static CompletableFuture<Map<UUID,Set<ChunkPos>>> scan(ServerLevel level,Set<UUID> ids,Executor executor) {
        Path folder=DimensionType.getStorageFolder(level.dimension(),level.getServer().getWorldPath(LevelResource.ROOT)).resolve("entities");
        SimpleRegionStorage storage=storage(level);Set<UUID> wanted=Set.copyOf(ids);
        return storage.synchronize(true).thenApplyAsync(ignored->{
            var found=new HashMap<UUID,Set<ChunkPos>>();
            try {
                if(!Files.isDirectory(folder))return found;
                try(var files=Files.list(folder)) {
                    for(Path file:files.sorted().toList()) {
                        Matcher match=REGION.matcher(file.getFileName().toString());if(!match.matches())continue;
                        int rx=Integer.parseInt(match.group(1)),rz=Integer.parseInt(match.group(2));
                        ByteBuffer header=ByteBuffer.allocate(4096);
                        try(var input=FileChannel.open(file,StandardOpenOption.READ)){while(header.hasRemaining()&&input.read(header)>0){}}
                        header.flip();for(int i=0;i<1024&&header.remaining()>=4;i++) {
                            if(header.getInt()==0)continue;ChunkPos pos=new ChunkPos(rx*32+(i&31),rz*32+(i>>5));
                            // This is the dedicated I/O worker, never the server thread or a chunk-generation future.
                            Optional<CompoundTag> tag=storage.read(pos).join();
                            if(tag.isPresent())find(tag.get().getList("Entities",Tag.TAG_COMPOUND),pos,wanted,found);
                        }
                    }
                }
                return found;
            } catch(Exception error){throw new CompletionException(error);}
        },executor);
    }
    private static void find(ListTag list,ChunkPos pos,Set<UUID> wanted,Map<UUID,Set<ChunkPos>> found) {
        for(Tag entry:list){CompoundTag tag=(CompoundTag)entry;if(tag.hasUUID("UUID")&&wanted.contains(tag.getUUID("UUID")))found.computeIfAbsent(tag.getUUID("UUID"),ignored->new LinkedHashSet<>()).add(pos);find(tag.getList("Passengers",Tag.TAG_COMPOUND),pos,wanted,found);}
    }
    public static void remove(ListTag list,Set<UUID> ids) {
        for(int i=list.size()-1;i>=0;i--){CompoundTag tag=list.getCompound(i);if(tag.hasUUID("UUID")&&ids.contains(tag.getUUID("UUID")))list.remove(i);else remove(tag.getList("Passengers",Tag.TAG_COMPOUND),ids);}
    }
    private RiftEntityDiskIndex(){}
}
