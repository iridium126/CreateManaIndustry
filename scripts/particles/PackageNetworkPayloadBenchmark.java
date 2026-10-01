import com.iridium126.createmanaindustry.content.logistics.gpupackage.*;
import com.iridium126.createmanaindustry.content.logistics.gpupackage.network.*;
import io.netty.buffer.Unpooled;
import java.nio.ByteBuffer;
import java.nio.file.*;
import java.util.*;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundMoveEntityPacket;
import net.minecraft.resources.ResourceLocation;

/** Actual payload-codec accounting for an explicit constant-motion fixture. Not socket bandwidth:
 * excludes packet IDs/framing/compression, initial spawn/baselines and gameplay events. Native
 * Create package update interval is three ticks; authoritative GPU fixture sends at 20Hz.
 * Both retained-native and hypothetical suppressed-native totals are reported, never omitted. */
public final class PackageNetworkPayloadBenchmark {
    static final ResourceLocation DIM=ResourceLocation.parse("minecraft:overworld");
    static final PackageRegion REGION=new PackageRegion(0,0,0);
    static final int TICKS=60,OBSERVER_BATCH=64;
    static PackageDeltaCodec.Entry change(int i,int tick) {
        var state=new PackageDeltaCodec.Quantized((i%60)*4096+(int)Math.round(tick*.05*4096),20000,9000,(short)1024,(short)0,(short)0,(short)0,1);
        return new PackageDeltaCodec.Entry(i,PackageDeltaCodec.POSITION,state);
    }
    public static void main(String[] args) throws Exception {
        var rows=new ArrayList<String>();
        rows.add("packages,observers,logical_seconds,native_motion_payload_bytes,gpu_up_payload_bytes,gpu_observer_payload_bytes,total_with_native_retained,total_if_native_suppressed,native_all_clients_payload_bytes");
        for(int count:new int[]{10000,65536,131072}) {
            var out=new RegistryFriendlyByteBuf(Unpooled.buffer(),RegistryAccess.EMPTY);var scratch=ByteBuffer.allocate(ServerboundPackagePacket.MAX_BYTES);
            long nativeBytes=0,upBytes=0,downBytes=0;var changes=new ArrayList<PackageDeltaCodec.Entry>(PackageDeltaCodec.MAX_ENTRIES);
            try {
                for(int tick=1;tick<=TICKS;tick++) {
                    if(tick%3==0)for(int i=0;i<count;i++) {
                        out.clear();ClientboundMoveEntityPacket.Pos.STREAM_CODEC.encode(out,new ClientboundMoveEntityPacket.Pos(i+1,(short)614,(short)0,(short)0,true));
                        nativeBytes+=out.readableBytes();
                    }
                    for(int first=0;first<count;first+=PackageDeltaCodec.MAX_ENTRIES) {
                        changes.clear();int end=Math.min(count,first+PackageDeltaCodec.MAX_ENTRIES);
                        for(int i=first;i<end;i++)changes.add(change(i,tick));scratch.clear();PackageDeltaCodec.encode(scratch,changes);
                        byte[] body=Arrays.copyOf(scratch.array(),scratch.position());
                        var packet=new ServerboundPackagePacket(ServerboundPackagePacket.DELTA,0,REGION,1,0,null,0,1,tick,body);
                        out.clear();ServerboundPackagePacket.STREAM_CODEC.encode(out,packet);upBytes+=out.readableBytes();
                    }
                    for(int first=0;first<count;first+=OBSERVER_BATCH) {
                        changes.clear();int end=Math.min(count,first+OBSERVER_BATCH);long[] times=new long[end-first];Arrays.fill(times,tick);
                        for(int i=first;i<end;i++)changes.add(change(i,tick));
                        var packet=new ClientboundPackageObserverPacket(DIM,REGION,1,1,2,tick,ClientboundPackageObserverPacket.COMPLETE,
                                List.of(),changes,tick,new PackageObserverTimes(times));
                        out.clear();ClientboundPackageObserverPacket.STREAM_CODEC.encode(out,packet);downBytes+=out.readableBytes();
                    }
                }
                for(int observers:new int[]{0,1,3}) {
                    long nativeAll=nativeBytes*(observers+1),gpu=upBytes+downBytes*observers;
                    String row=count+","+observers+",3,"+nativeBytes+","+upBytes+","+downBytes+","+(gpu+nativeAll)+","+gpu+","+nativeAll;
                    rows.add(row);System.out.println(row);
                }
            }finally{out.release();}
        }
        Files.write(Path.of("build/package-network-payloads.csv"),rows);
    }
}
