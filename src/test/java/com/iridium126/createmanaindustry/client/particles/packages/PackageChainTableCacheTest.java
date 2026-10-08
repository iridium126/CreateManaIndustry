package com.iridium126.createmanaindustry.client.particles.packages;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import net.minecraft.core.BlockPos;
import com.iridium126.createmanaindustry.content.logistics.gpupackage.PackageChainTrack;
import com.iridium126.createmanaindustry.content.logistics.gpupackage.network.ClientboundChainPackagePacket;

class PackageChainTableCacheTest {
    private ClientboundChainPackagePacket.Track track(int index){return new ClientboundChainPackagePacket.Track(index,BlockPos.ZERO,null,PackageChainTrack.loop(BlockPos.ZERO,1,0,0,1),List.of());}
    @Test void receivedHeadersCanBootstrapAReplacementGpuTableFromZero(){
        var cache=new PackageChainTableCache();assertTrue(cache.track(4,track(0)));assertTrue(cache.track(4,track(1)));
        // GPU unavailable during receipt; a later incremental header must replay 0 and 1 first.
        assertTrue(cache.track(4,track(2)));assertEquals(List.of(track(0),track(1),track(2)),cache.table(4));
        assertTrue(cache.track(4,track(2)));assertEquals(3,cache.table(4).size());
    }
    @Test void closedAndOlderEpochsCannotReopenOrReplaceTheCurrentNamespace(){
        var cache=new PackageChainTableCache();cache.track(4,track(0));cache.close(4);
        assertFalse(cache.track(4,track(0)));assertFalse(cache.track(4,track(2)));assertTrue(cache.table(4).isEmpty());
        assertTrue(cache.track(5,track(0)));cache.close(4);assertTrue(cache.current(5));assertFalse(cache.track(3,track(0)));
    }
    @Test void aGapOrConflictDoesNotCreateFakeTrackEntries(){
        var cache=new PackageChainTableCache();assertThrows(IllegalStateException.class,()->cache.track(4,track(2)));assertTrue(cache.table(4).isEmpty());
        cache.track(4,track(0));assertThrows(IllegalStateException.class,()->cache.track(4,track(2)));assertEquals(1,cache.table(4).size());
        var conflict=new ClientboundChainPackagePacket.Track(0,new BlockPos(1,0,0),null,PackageChainTrack.loop(BlockPos.ZERO,1,0,0,1),List.of());
        assertThrows(IllegalStateException.class,()->cache.track(4,conflict));assertEquals(track(0),cache.table(4).getFirst());
    }
}
