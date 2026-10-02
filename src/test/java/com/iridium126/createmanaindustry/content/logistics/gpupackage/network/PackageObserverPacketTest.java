package com.iridium126.createmanaindustry.content.logistics.gpupackage.network;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import com.iridium126.createmanaindustry.content.logistics.gpupackage.*;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.DecoderException;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

class PackageObserverPacketTest {
    @Test void removedNativeObserverModesCannotBeRequestedOrDecoded() {
        assertThrows(IllegalArgumentException.class,()->new ServerboundPackageObserverPacket(2,REGION,0));
        assertThrows(IllegalArgumentException.class,()->new ClientboundPackageObserverPacket(DIM,REGION,10,1,20,0,11,List.of(member(0)),List.of()));
        var bytes=buffer();try {
            bytes.writeByte(2);ServerboundPackagePacket.writeRegion(bytes,REGION);
            assertThrows(DecoderException.class,()->ServerboundPackageObserverPacket.STREAM_CODEC.decode(bytes));
            bytes.clear();var valid=new ClientboundPackageObserverPacket(DIM,REGION,10,1,20,0,3,List.of(member(0)),List.of());
            ClientboundPackageObserverPacket.STREAM_CODEC.encode(bytes,valid);
            var cursor=new RegistryFriendlyByteBuf(bytes.duplicate(),RegistryAccess.EMPTY);cursor.readResourceLocation();ServerboundPackagePacket.readRegion(cursor);
            for(int i=0;i<4;i++)cursor.readVarLong();bytes.setByte(cursor.readerIndex(),11);
            assertThrows(DecoderException.class,()->ClientboundPackageObserverPacket.STREAM_CODEC.decode(bytes));
        }finally{bytes.release();}
    }
    private static final ResourceLocation DIM=ResourceLocation.parse("minecraft:overworld");
    private static final PackageRegion REGION=new PackageRegion(-200,33554432,-1);
    private static RegistryFriendlyByteBuf buffer(){return new RegistryFriendlyByteBuf(Unpooled.buffer(),RegistryAccess.EMPTY);}
    private static PackageObserverFeed.Member<ClientboundPackageObserverPacket.Visual> member(int index) {
        var q=new PackageDeltaCodec.Quantized(123456,-234567,345678,(short)1234,(short)-2345,(short)3456,(short)-12345,3);
        var v=new ClientboundPackageObserverPacket.Visual(index,new UUID(12345678,index),ResourceLocation.parse("create:cardboard_package_10x8"),1,.75f);
        return new PackageObserverFeed.Member<>(index,new PackageLease.Identity(0x1234567800000001L+index,0x2345678900000001L),
                0x3456789000000001L,9,q,v);
    }
    @Test void fullIdentityHighRegionAndQuantizedStateRoundTripAtMaximumBatchSize() {
        var members=new ArrayList<PackageObserverFeed.Member<ClientboundPackageObserverPacket.Visual>>();
        for(int i=0;i<ClientboundPackageObserverPacket.MAX_RECORDS;i++)members.add(member(65536+i));
        var packet=new ClientboundPackageObserverPacket(DIM,REGION,0x4567890100000001L,3,0x5678901200000001L,0,3,members,List.of());
        members.clear();assertEquals(128,packet.baselines().size());
        var bytes=buffer();try {
            ClientboundPackageObserverPacket.STREAM_CODEC.encode(bytes,packet);
            assertEquals(packet,ClientboundPackageObserverPacket.STREAM_CODEC.decode(bytes));assertEquals(0,bytes.readableBytes());
            assertTrue(bytes.writerIndex()<24576);
        }finally{bytes.release();}
    }
    @Test void changedFieldsAndReleaseUseTheCompactSharedCodecWithoutModelOrContents() {
        var changes=List.of(new PackageDeltaCodec.Entry(65536,PackageDeltaCodec.VELOCITY,member(1).state()),
                new PackageDeltaCodec.Entry(65537,PackageDeltaCodec.RELEASE,member(2).state()));
        var packet=new ClientboundPackageObserverPacket(DIM,REGION,10,1,20,999,ClientboundPackageObserverPacket.COMPLETE,List.of(),changes);
        var bytes=buffer();try {
            ClientboundPackageObserverPacket.STREAM_CODEC.encode(bytes,packet);assertTrue(bytes.writerIndex()<90);
            var decoded=ClientboundPackageObserverPacket.STREAM_CODEC.decode(bytes);assertEquals(packet.stream(),decoded.stream());
            assertEquals(packet.sequence(),decoded.sequence());assertEquals(2,decoded.changes().size());
            assertEquals(PackageDeltaCodec.VELOCITY,decoded.changes().getFirst().mask());assertEquals(1234,decoded.changes().getFirst().value().vx());
            assertEquals(0,decoded.changes().getFirst().value().x());assertEquals(PackageDeltaCodec.RELEASE,decoded.changes().getLast().mask());
        }finally{bytes.release();}
    }
    @Test void subscriptionHasNoAuthorityOrInventoryFieldsAndUnsubscribeNamesTheExactStream() {
        for(int action:new int[]{ServerboundPackageObserverPacket.SUBSCRIBE,ServerboundPackageObserverPacket.UNSUBSCRIBE}) {
            var packet=new ServerboundPackageObserverPacket(action,REGION,action==0?0:0x6789012300000001L);var bytes=buffer();
            try {ServerboundPackageObserverPacket.STREAM_CODEC.encode(bytes,packet);
                assertEquals(packet,ServerboundPackageObserverPacket.STREAM_CODEC.decode(bytes));assertEquals(0,bytes.readableBytes());
            }finally{bytes.release();}
        }
        assertThrows(IllegalArgumentException.class,()->new ServerboundPackageObserverPacket(0,REGION,10));
        assertThrows(IllegalArgumentException.class,()->new ServerboundPackageObserverPacket(1,REGION,0));
    }
    @Test void malformedCountsMasksAndTrailingBytesAreRejectedBeforeAnyReplicaCommit() {
        var bytes=buffer();try {
            header(bytes);bytes.writeVarInt(129);assertThrows(DecoderException.class,()->ClientboundPackageObserverPacket.STREAM_CODEC.decode(bytes));bytes.clear();
            header(bytes);bytes.writeVarInt(0);bytes.writeByteArray(new byte[]{(byte)129,1});
            assertThrows(DecoderException.class,()->ClientboundPackageObserverPacket.STREAM_CODEC.decode(bytes));bytes.clear();
            header(bytes);bytes.writeVarInt(0);bytes.writeByteArray(new byte[]{0,99});
            assertThrows(DecoderException.class,()->ClientboundPackageObserverPacket.STREAM_CODEC.decode(bytes));bytes.clear();
            header(bytes);bytes.writeVarInt(0);bytes.writeVarInt(ClientboundPackageObserverPacket.MAX_DELTA_BYTES+1);
            assertThrows(DecoderException.class,()->ClientboundPackageObserverPacket.STREAM_CODEC.decode(bytes));bytes.clear();
            bytes.writeByte(99);ServerboundPackagePacket.writeRegion(bytes,REGION);
            assertThrows(DecoderException.class,()->ServerboundPackageObserverPacket.STREAM_CODEC.decode(bytes));
        }finally{bytes.release();}
        assertThrows(IllegalArgumentException.class,()->new ClientboundPackageObserverPacket(DIM,REGION,10,1,20,1,ClientboundPackageObserverPacket.RESET,List.of(),List.of()));
        assertThrows(IllegalArgumentException.class,()->new ClientboundPackageObserverPacket(DIM,REGION,10,1,20,0,ClientboundPackageObserverPacket.CLOSE,List.of(member(0)),List.of()));
        assertThrows(IllegalArgumentException.class,()->PackageDeltaCodec.decode(ByteBuffer.wrap(new byte[]{1,0,16}),0));
    }
    private static void header(RegistryFriendlyByteBuf b) {
        b.writeResourceLocation(DIM);ServerboundPackagePacket.writeRegion(b,REGION);b.writeVarLong(10);b.writeVarLong(1);
        b.writeVarLong(20);b.writeVarLong(0);b.writeByte(ClientboundPackageObserverPacket.RESET);
        b.writeVarLong(0);
        b.writeByte(1);
    }
    @Test void relativeStateAgesRoundTripWithoutMutableTimestampArrays() {
        var m=member(1);m=new PackageObserverFeed.Member<>(m.index(),m.identity(),m.leaseEpoch(),m.revision(),m.state(),m.metadata(),9998);
        long[] input={9999};var times=new PackageObserverTimes(input);input[0]=0;
        var packet=new ClientboundPackageObserverPacket(DIM,REGION,10,1,20,0,3,List.of(m),
                List.of(new PackageDeltaCodec.Entry(2,1,member(2).state())),10000,times);
        var bytes=buffer();try {ClientboundPackageObserverPacket.STREAM_CODEC.encode(bytes,packet);
            var decoded=ClientboundPackageObserverPacket.STREAM_CODEC.decode(bytes);
            assertEquals(10000,decoded.serverTick());assertEquals(9998,decoded.baselines().getFirst().stateTick());
            assertEquals(9999,decoded.stateTicks().get(0));assertEquals(times,decoded.stateTicks());assertEquals(0,bytes.readableBytes());
        }finally{bytes.release();}
        assertThrows(IllegalArgumentException.class,()->new ClientboundPackageObserverPacket(DIM,REGION,10,1,20,0,3,List.of(),
                packet.changes(),10000,new PackageObserverTimes(10001L)));
        assertThrows(IllegalArgumentException.class,()->new ClientboundPackageObserverPacket(DIM,REGION,10,1,20,0,3,List.of(),
                packet.changes(),10000,PackageObserverTimes.zeros(0)));
    }
    @Test void impossibleAndTruncatedAgeBodiesFailDuringDecode() {
        var bytes=buffer();try {
            header(bytes);bytes.writeVarInt(0);bytes.writeByteArray(new byte[]{1,0,16});bytes.writeVarLong(1);
            assertThrows(DecoderException.class,()->ClientboundPackageObserverPacket.STREAM_CODEC.decode(bytes));bytes.clear();
            header(bytes);bytes.writeVarInt(0);bytes.writeByteArray(new byte[]{1,0,16});
            assertThrows(DecoderException.class,()->ClientboundPackageObserverPacket.STREAM_CODEC.decode(bytes));
        }finally{bytes.release();}
    }
    @Test void equalConfirmationTimesUseOneAgeForTheEntireMaximumBatch() {
        var members=new ArrayList<PackageObserverFeed.Member<ClientboundPackageObserverPacket.Visual>>();
        for(int i=0;i<128;i++){var m=member(i);members.add(new PackageObserverFeed.Member<>(m.index(),m.identity(),m.leaseEpoch(),m.revision(),m.state(),m.metadata(),100));}
        var shared=new ClientboundPackageObserverPacket(DIM,REGION,10,1,20,0,3,members,List.of(),100,PackageObserverTimes.zeros(0));
        var bytes=buffer();try {
            ClientboundPackageObserverPacket.STREAM_CODEC.encode(bytes,shared);int sharedBytes=bytes.readableBytes();
            assertEquals(shared,ClientboundPackageObserverPacket.STREAM_CODEC.decode(bytes));bytes.clear();
            var m=members.getFirst();members.set(0,new PackageObserverFeed.Member<>(m.index(),m.identity(),m.leaseEpoch(),m.revision(),m.state(),m.metadata(),99));
            var mixed=new ClientboundPackageObserverPacket(DIM,REGION,10,1,20,0,3,members,List.of(),100,PackageObserverTimes.zeros(0));
            ClientboundPackageObserverPacket.STREAM_CODEC.encode(bytes,mixed);assertEquals(sharedBytes+127,bytes.readableBytes());
            assertEquals(mixed,ClientboundPackageObserverPacket.STREAM_CODEC.decode(bytes));
        }finally{bytes.release();}
    }
}
