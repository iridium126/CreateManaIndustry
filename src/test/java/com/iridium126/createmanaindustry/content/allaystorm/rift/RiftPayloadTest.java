package com.iridium126.createmanaindustry.content.allaystorm.rift;

import com.iridium126.createmanaindustry.content.allaystorm.AllayStormData;
import com.iridium126.createmanaindustry.content.allaystorm.StormIdentity;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceLocation;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class RiftPayloadTest {
    RegistryFriendlyByteBuf buffer() { return new RegistryFriendlyByteBuf(Unpooled.buffer(),RegistryAccess.EMPTY,net.neoforged.neoforge.network.connection.ConnectionType.OTHER); }
    @Test void summaryPreservesDoubleCenterIdentityOpeningClockAndTargetContext() {
        var context=new AllaySkyContext(8912,6000,300,.2f,.3f,1,false);
        var before=new com.iridium126.createmanaindustry.content.allaystorm.StormCenterSample(29_000_000.125,177.25,-27_000_000.375,16384,68);
        var current=new com.iridium126.createmanaindustry.content.allaystorm.StormCenterSample(29_000_001.125,177.25,-27_000_000.375,16390,88);
        var snapshot=new StormRiftSnapshot(new StormIdentity(ResourceLocation.withDefaultNamespace("overworld"),814),21,before,current,384,22,StormRiftSnapshot.OPEN,context,StormVisualDefinition.EMPTY);
        var b=buffer();try {
            RiftSnapshotPacket.STREAM_CODEC.encode(b,new RiftSnapshotPacket(snapshot));
            assertEquals(snapshot,RiftSnapshotPacket.STREAM_CODEC.decode(b).snapshot());assertFalse(b.isReadable());
        } finally { b.release(); }
    }
    @Test void arrivalManifestIsDetachedAndRoundTripsWithoutChangingTheLoadingProtocol() {
        var entities=new ArrayList<>(List.of(UUID.randomUUID()));var structures=List.of(UUID.randomUUID());
        var packet=new RiftArrivalPacket(UUID.randomUUID(),ResourceLocation.fromNamespaceAndPath("createmanaindustry","allay_dimension"),AllaySkyContext.EMPTY,entities,structures);entities.clear();assertEquals(1,packet.trackedEntities().size());
        var b=buffer();try {
            RiftArrivalPacket.STREAM_CODEC.encode(b,packet);assertEquals(packet,RiftArrivalPacket.STREAM_CODEC.decode(b));assertFalse(b.isReadable());
        } finally { b.release(); }
    }
    @Test void missingLegacyOpeningTimeRestoresMatureStormDirectlyToStableOpen() {
        var serializer=new AllayStormData.Serializer();var data=new AllayStormData();data.active=true;data.count=16384;data.finalCount=16384;data.riftOpenedAt=55;
        var tag=serializer.write(data,null);assertEquals(55,serializer.readAt(tag,100).riftOpenedAt);
        tag.remove("RiftOpenedAt");assertEquals(0,serializer.readAt(tag,100).riftOpenedAt);
        tag.putInt("Count",16383);assertEquals(-1,serializer.readAt(tag,100).riftOpenedAt);
    }
    @Test void unsolicitedReadyCannotUnfreezeOrStartAnyTransport() {
        UUID id=UUID.randomUUID();assertFalse(StormRiftTransfers.frozen(id));
        StormRiftTransfers.ready(null,id);
        assertFalse(StormRiftTransfers.frozen(id));assertTrue(StormRiftTransfers.report().contains("not released"));
    }
}
