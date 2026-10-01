package com.iridium126.createmanaindustry.content.logistics.gpupackage.network;

import java.util.function.Consumer;
import com.iridium126.createmanaindustry.CreateManaIndustry;
import com.iridium126.createmanaindustry.content.logistics.gpupackage.*;
import com.iridium126.createmanaindustry.infrastructure.network.ClientPayloadHandler;
import io.netty.handler.codec.DecoderException;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** Exact accepted sequence runs for one region namespace, not an implicit cumulative ACK. */
public record ClientboundPackageAckPacket(ResourceLocation dimension,PackageRegion region,long epoch,long revision,
        PackageAckRanges ranges) implements CustomPacketPayload {
    public static final Type<ClientboundPackageAckPacket> TYPE=new Type<>(ResourceLocation.fromNamespaceAndPath(CreateManaIndustry.MODID,"package_ack"));
    public static final StreamCodec<RegistryFriendlyByteBuf,ClientboundPackageAckPacket> STREAM_CODEC=StreamCodec.of(
            ClientboundPackageAckPacket::encode,ClientboundPackageAckPacket::decode);
    private static final ClientPayloadHandler<ClientboundPackageAckPacket> CLIENT=new ClientPayloadHandler<>();
    public ClientboundPackageAckPacket {
        if(dimension==null || region==null || epoch<=0 || revision<=0 || ranges==null)throw new IllegalArgumentException("Package ACK envelope");
    }
    private static void encode(RegistryFriendlyByteBuf b,ClientboundPackageAckPacket p) {
        b.writeResourceLocation(p.dimension);ServerboundPackagePacket.writeRegion(b,p.region);b.writeVarLong(p.epoch);b.writeVarLong(p.revision);
        var ranges=p.ranges;b.writeVarInt(ranges.runs());long previous=-1;
        for(int i=0;i<ranges.runs();i++) {
            b.writeVarLong(ranges.start(i)-(previous+1));b.writeVarInt(ranges.length(i));previous=ranges.end(i);
        }
    }
    private static ClientboundPackageAckPacket decode(RegistryFriendlyByteBuf b) {
        try {
            var dimension=b.readResourceLocation();var region=ServerboundPackagePacket.readRegion(b);
            long epoch=b.readVarLong(),revision=b.readVarLong();int runs=b.readVarInt();
            if(runs<1 || runs>PackageAckRanges.MAX_RUNS || runs>b.readableBytes()/2)throw new IllegalArgumentException("Package ACK run allocation");
            long[] endpoints=new long[runs*2];long previous=-1;int total=0;
            for(int i=0;i<runs;i++) {
                long gap=b.readVarLong();int length=b.readVarInt();
                if(gap<0 || length<1 || length>PackageAckRanges.MAX_ACKS-total || previous==Long.MAX_VALUE)
                    throw new IllegalArgumentException("Package ACK work bound/gap");
                long start=Math.addExact(previous+1,gap),end=Math.addExact(start,length-1L);
                endpoints[i*2]=start;endpoints[i*2+1]=end;previous=end;total+=length;
            }
            return new ClientboundPackageAckPacket(dimension,region,epoch,revision,new PackageAckRanges(endpoints));
        }catch(IllegalArgumentException | ArithmeticException | IndexOutOfBoundsException invalid) {
            throw new DecoderException("Invalid package ACK runs",invalid);
        }
    }
    @Override public Type<? extends CustomPacketPayload> type(){return TYPE;}
    public static void installClientHandler(Consumer<? super ClientboundPackageAckPacket> handler){CLIENT.install(handler);}
    public static void handle(ClientboundPackageAckPacket packet,IPayloadContext ctx){ctx.enqueueWork(()->CLIENT.dispatch(packet));}
}
