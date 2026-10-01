package com.iridium126.createmanaindustry.content.logistics.gpupackage.network;

import static org.junit.jupiter.api.Assertions.*;
import com.iridium126.createmanaindustry.content.logistics.gpupackage.*;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.DecoderException;
import java.util.Arrays;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

class PackageAckPacketTest {
    static final ResourceLocation DIM=ResourceLocation.parse("minecraft:overworld");
    static final PackageRegion REGION=new PackageRegion(-200,33554432,-1);
    static final long EPOCH=0x1234567800000001L,REVISION=0x2345678900000001L;
    static RegistryFriendlyByteBuf buffer(){return new RegistryFriendlyByteBuf(Unpooled.buffer(),RegistryAccess.EMPTY);}
    static void header(RegistryFriendlyByteBuf b){b.writeResourceLocation(DIM);ServerboundPackagePacket.writeRegion(b,REGION);b.writeVarLong(EPOCH);b.writeVarLong(REVISION);}
    @Test void fullEnvelopeSparseRunsAndLongMaximumRoundTrip() {
        var ranges=new PackageAckRanges(0xffffffffL,0x100000000L,Long.MAX_VALUE-2,Long.MAX_VALUE);
        var packet=new ClientboundPackageAckPacket(DIM,REGION,EPOCH,REVISION,ranges);var bytes=buffer();
        try {ClientboundPackageAckPacket.STREAM_CODEC.encode(bytes,packet);assertEquals(packet,ClientboundPackageAckPacket.STREAM_CODEC.decode(bytes));assertEquals(0,bytes.readableBytes());}
        finally{bytes.release();}
    }
    @Test void maximumWorkAndMaximumRunShapeRoundTrip() {
        long[] endpoints=new long[PackageAckRanges.MAX_RUNS*2];
        for(int i=0;i<PackageAckRanges.MAX_RUNS;i++){endpoints[i*2]=i*17L;endpoints[i*2+1]=i*17L+15;}
        var packet=new ClientboundPackageAckPacket(DIM,REGION,EPOCH,REVISION,new PackageAckRanges(endpoints));var bytes=buffer();
        try {ClientboundPackageAckPacket.STREAM_CODEC.encode(bytes,packet);assertEquals(2048,packet.ranges().count());assertEquals(packet,ClientboundPackageAckPacket.STREAM_CODEC.decode(bytes));}
        finally{bytes.release();}
    }
    @Test void wholePacketTruncationCannotPublishAPrefix() {
        var packet=new ClientboundPackageAckPacket(DIM,REGION,EPOCH,REVISION,new PackageAckRanges(0,255,500,600));var bytes=buffer();
        byte[] encoded;
        try{ClientboundPackageAckPacket.STREAM_CODEC.encode(bytes,packet);encoded=new byte[bytes.readableBytes()];bytes.readBytes(encoded);}finally{bytes.release();}
        for(int i=0;i<encoded.length;i++) {
            var truncated=new RegistryFriendlyByteBuf(Unpooled.wrappedBuffer(Arrays.copyOf(encoded,i)),RegistryAccess.EMPTY);
            try{assertThrows(DecoderException.class,()->ClientboundPackageAckPacket.STREAM_CODEC.decode(truncated));}finally{truncated.release();}
        }
    }
    @Test void malformedRunsCannotAllocateOrIterateUnboundedWork() {
        var bytes=buffer();try {
            for(int count:new int[]{0,-1,129,Integer.MAX_VALUE}) {
                bytes.clear();header(bytes);bytes.writeVarInt(count);assertThrows(DecoderException.class,()->ClientboundPackageAckPacket.STREAM_CODEC.decode(bytes));
            }
            for(int length:new int[]{0,-1,2049,Integer.MAX_VALUE}) {
                bytes.clear();header(bytes);bytes.writeVarInt(1).writeVarLong(0).writeVarInt(length);
                assertThrows(DecoderException.class,()->ClientboundPackageAckPacket.STREAM_CODEC.decode(bytes));
            }
            bytes.clear();header(bytes);bytes.writeVarInt(2).writeVarLong(0).writeVarInt(1).writeVarLong(0).writeVarInt(1);
            assertThrows(DecoderException.class,()->ClientboundPackageAckPacket.STREAM_CODEC.decode(bytes)); // adjacent unmerged runs
            bytes.clear();header(bytes);bytes.writeVarInt(1).writeVarLong(Long.MAX_VALUE).writeVarInt(2);
            assertThrows(DecoderException.class,()->ClientboundPackageAckPacket.STREAM_CODEC.decode(bytes));
            bytes.clear();header(bytes);bytes.writeVarInt(1).writeVarLong(-1).writeVarInt(1);
            assertThrows(DecoderException.class,()->ClientboundPackageAckPacket.STREAM_CODEC.decode(bytes));
        }finally{bytes.release();}
    }
}
