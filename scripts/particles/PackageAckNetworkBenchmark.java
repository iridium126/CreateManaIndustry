import com.iridium126.createmanaindustry.content.logistics.gpupackage.*;
import com.iridium126.createmanaindustry.content.logistics.gpupackage.network.*;
import io.netty.buffer.Unpooled;
import java.nio.file.*;
import java.util.*;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;

/** Actual control payload serialization; no sockets/framing/compression or server/world tick.
 * New 20Hz pose encoding is unchanged. Models ACK batching for one/eight namespaces and holes. */
public final class PackageAckNetworkBenchmark {
    static final ResourceLocation DIM=ResourceLocation.parse("minecraft:overworld");
    static final long EPOCH=0x3456789000000001L;
    static final int TICKS=60;
    public static void main(String[] args) throws Exception {
        var rows=new ArrayList<String>();rows.add("packages,regions,shape,logical_seconds,acknowledged_sequences,individual_packets,batched_packets,individual_payload_bytes,batched_payload_bytes,payload_ratio");
        var out=new RegistryFriendlyByteBuf(Unpooled.buffer(),RegistryAccess.EMPTY);var builder=new PackageAckRanges.Builder();
        try {
            for(int count:new int[]{10000,65536,131072})for(int regions:new int[]{1,8})for(boolean sparse:new boolean[]{false,true}) {
                long oldBytes=0,batchBytes=0,oldPackets=0,batchPackets=0;
                int perRegion=(count+regions-1)/regions,packets=(perRegion+511)/512;
                for(int tick=0;tick<TICKS;tick++)for(int r=0;r<regions;r++) {
                    var region=new PackageRegion(r,0,0);builder.clear();
                    for(int p=0;p<packets;p++) {
                        long sequence=(long)tick*packets*(sparse?2:1)+(long)p*(sparse?2:1);
                        out.clear();ClientboundPackagePacket.STREAM_CODEC.encode(out,new ClientboundPackagePacket(ClientboundPackagePacket.ACK,DIM,region,EPOCH+r,1,sequence,null,0,null,null,0,0));
                        oldBytes+=out.readableBytes();oldPackets++;
                        if(!builder.add(sequence)) {batchBytes+=encode(out,region,EPOCH+r,builder.snapshot());batchPackets++;builder.clear();if(!builder.add(sequence))throw new AssertionError("ACK retry failed");}
                    }
                    if(!builder.empty()){batchBytes+=encode(out,region,EPOCH+r,builder.snapshot());batchPackets++;}
                }
                String row=count+","+regions+","+(sparse?"holes":"contiguous")+",3,"+oldPackets+","+oldPackets+","+batchPackets+","+oldBytes+","+batchBytes+","+String.format(Locale.ROOT,"%.6f",(double)batchBytes/oldBytes);
                rows.add(row);System.out.println(row);
            }
        }finally{out.release();}
        Files.write(Path.of("build/package-ack-network.csv"),rows);
    }
    static int encode(RegistryFriendlyByteBuf out,PackageRegion region,long epoch,PackageAckRanges ranges) {
        var packet=new ClientboundPackageAckPacket(DIM,region,epoch,1,ranges);out.clear();ClientboundPackageAckPacket.STREAM_CODEC.encode(out,packet);int bytes=out.readableBytes();
        if(!packet.equals(ClientboundPackageAckPacket.STREAM_CODEC.decode(out)) || out.readableBytes()!=0)throw new AssertionError("ACK wire mismatch");
        return bytes;
    }
}
