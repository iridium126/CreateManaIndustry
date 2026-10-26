package com.iridium126.createmanaindustry.content.allaystorm.rift.sable;

import com.iridium126.createmanaindustry.content.allaystorm.rift.RiftTransferJournal;
import dev.ryanhcode.sable.sublevel.storage.*;
import dev.ryanhcode.sable.sublevel.tracking_points.SubLevelTrackingPointSavedData;
import net.minecraft.nbt.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.storage.LevelResource;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Re-sample after I/O so concurrent unrelated plot allocations cannot be lost to a stale occupancy image. */
final class RiftSableMetadataCheckpoint {
    private final List<ServerLevel> worlds;
    private final RiftTransferJournal journal;
    private Map<Path,CompoundTag> sent;
    private CompletableFuture<Void> barrier;
    RiftSableMetadataCheckpoint(List<ServerLevel> worlds,RiftTransferJournal journal){this.worlds=List.copyOf(worlds);this.journal=journal;}
    boolean step() {
        if(barrier!=null&&!barrier.isDone())return false;
        if(barrier!=null)barrier.getNow(null);
        var now=capture();if(sent!=null&&sent.equals(now))return true;sent=now;
        var detached=Map.copyOf(now);barrier=CompletableFuture.runAsync(()->{try{for(var entry:detached.entrySet())atomic(entry.getKey(),entry.getValue());}catch(Exception error){throw new CompletionException(error);}},journal.executor());return false;
    }
    private Map<Path,CompoundTag> capture(){var result=new LinkedHashMap<Path,CompoundTag>();for(var world:worlds){var folder=DimensionType.getStorageFolder(world.dimension(),world.getServer().getWorldPath(LevelResource.ROOT)).resolve("data");put(result,folder,SubLevelOccupancySavedData.FILE_ID,SubLevelOccupancySavedData.getOrLoad(world).save(new CompoundTag(),world.registryAccess()));put(result,folder,SubLevelTicketsSavedData.FILE_ID,SubLevelTicketsSavedData.getOrLoad(world).save(new CompoundTag(),world.registryAccess()));put(result,folder,SubLevelTrackingPointSavedData.FILE_ID,SubLevelTrackingPointSavedData.getOrLoad(world).save(new CompoundTag(),world.registryAccess()));}return result;}
    private static void put(Map<Path,CompoundTag> output,Path folder,String name,CompoundTag data){var tag=NbtUtils.addCurrentDataVersion(new CompoundTag());tag.put("data",data);output.put(folder.resolve(name+".dat"),tag);}
    private static void atomic(Path path,CompoundTag detached)throws Exception{Files.createDirectories(path.getParent());var temporary=path.resolveSibling(path.getFileName()+".cmi-metadata");NbtIo.writeCompressed(detached,temporary);try(var file=FileChannel.open(temporary,StandardOpenOption.WRITE)){file.force(true);}Files.move(temporary,path,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}
}
