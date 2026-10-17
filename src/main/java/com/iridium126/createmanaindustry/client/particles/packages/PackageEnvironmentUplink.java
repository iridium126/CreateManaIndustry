package com.iridium126.createmanaindustry.client.particles.packages;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.function.Consumer;
import com.iridium126.createmanaindustry.content.logistics.gpupackage.PackageEnvironmentBatchCodec;
import com.iridium126.createmanaindustry.content.logistics.gpupackage.PackageEnvironmentEvent;
import com.iridium126.createmanaindustry.content.logistics.gpupackage.PackageRegion;
import com.iridium126.createmanaindustry.content.logistics.gpupackage.network.ServerboundPackagePacket;

/** Render-thread staging, bounded by the runtime's authority region count. The GPU
 * journal retains retry responsibility until each exact lifecycle is acknowledged. */
public final class PackageEnvironmentUplink {
    private record Route(PackageRegion region,long epoch,long revision) {}
    private static final class Batch {
        Route route;
        final ByteBuffer bytes=ByteBuffer.allocate(PackageEnvironmentBatchCodec.MAX_BYTES);
    }
    private final int maximumRegions;
    private final LinkedHashMap<PackageRegion,Batch> batches=new LinkedHashMap<>();
    private final Consumer<ServerboundPackagePacket> sender;
    private int pendingBytes;

    public PackageEnvironmentUplink(int maximumRegions,Consumer<ServerboundPackagePacket> sender) {
        if(maximumRegions<1)throw new IllegalArgumentException("Environment route capacity");
        this.maximumRegions=maximumRegions;this.sender=Objects.requireNonNull(sender);
    }

    public void append(ByteBuffer event,int ox,int oy,int oz,PackageRegion region,long epoch,long revision) {
        if(region==null||epoch<=0||revision<=0)throw new IllegalArgumentException("Environment route");
        // The input is borrowed from a readback slot and must never be retained.
        var batch=batches.get(region);
        if(batch==null) {
            if(batches.size()==maximumRegions) {
                var oldest=batches.entrySet().iterator();batch=oldest.next().getValue();
                send(batch);oldest.remove();
            } else batch=new Batch();
            batches.put(region,batch);
        }
        if(batch.route==null||!region.equals(batch.route.region())||epoch!=batch.route.epoch()||revision!=batch.route.revision()) {
            send(batch);batch.route=new Route(region,epoch,revision);
        }
        if(!batch.bytes.hasRemaining())send(batch);
        PackageEnvironmentEvent.writeRegionPayload(event,ox,oy,oz,region,batch.bytes);
        pendingBytes+=PackageEnvironmentBatchCodec.RECORD_BYTES;
    }

    public void flush() {
        if(pendingBytes==0)return;
        try {for(var batch:batches.values())send(batch);}
        finally {clear();}
    }

    private void send(Batch batch) {
        int bytes=batch.bytes.position();if(bytes==0)return;
        var route=batch.route;
        sender.accept(new ServerboundPackagePacket(ServerboundPackagePacket.ENVIRONMENT,0,route.region(),route.epoch(),
                0,null,0,route.revision(),0,Arrays.copyOf(batch.bytes.array(),bytes)));
        pendingBytes-=bytes;
        batch.bytes.clear();
    }

    /** A failed transport or world replacement leaves retries with the owning GPU journal. */
    public void clear() {if(pendingBytes!=0){for(var batch:batches.values())batch.bytes.clear();pendingBytes=0;}}
}
