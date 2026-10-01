package com.iridium126.createmanaindustry.content.logistics.gpupackage.network;

import static org.junit.jupiter.api.Assertions.*;
import com.iridium126.createmanaindustry.content.logistics.gpupackage.*;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.DecoderException;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

class PackageChainPacketTest {
    private static final ResourceLocation DIM=ResourceLocation.fromNamespaceAndPath("minecraft","overworld"),MODEL=ResourceLocation.fromNamespaceAndPath("create","cardboard_package_10x8");
    private static final long EPOCH=0x100000005L;
    private static final PackageChainAuthority.Baseline BASE=new PackageChainAuthority.Baseline(100001,new PackageLease.Identity(0x200000009L,0x30000000bL),
            3,5,65535,0x40000000dL,new PackageChainAuthority.State(359.1234f,999999,new PackageLease.Pose(-20000,2049.375,30,0,0,0,-170)),0x80000003);
    private static RegistryFriendlyByteBuf buffer(){return new RegistryFriendlyByteBuf(Unpooled.buffer(),RegistryAccess.EMPTY);}
    @Test void sharedTrackRoundTripPreservesLongRevisionNodesAndReverseDirection() {
        var geometry=PackageChainTrack.loop(new BlockPos(-123,2048,456),-180,0,3,BASE.trackRevision());
        var nodes=List.of(new ClientboundChainPackagePacket.Node(2,1),new ClientboundChainPackagePacket.Node(5,2),new ClientboundChainPackagePacket.Node(359,1));
        var track=new ClientboundChainPackagePacket.Track(BASE.track(),new BlockPos(-123,2048,456),null,geometry,nodes);
        var packet=new ClientboundChainPackagePacket(ClientboundChainPackagePacket.TRACK,DIM,EPOCH,track,null,null,0,0,0);var bytes=buffer();
        try{ClientboundChainPackagePacket.STREAM_CODEC.encode(bytes,packet);assertEquals(packet,ClientboundChainPackagePacket.STREAM_CODEC.decode(bytes));assertEquals(0,bytes.readableBytes());}
        finally{bytes.release();}
    }
    @Test void allOwnershipBaselinesRoundTripWithExactIdentityAndProgressBits() {
        for(int action=ClientboundChainPackagePacket.OFFER;action<=ClientboundChainPackagePacket.RELEASED;action++) {
            var packet=new ClientboundChainPackagePacket(action,DIM,EPOCH,null,BASE,action==ClientboundChainPackagePacket.OFFER?MODEL:null,
                    action==ClientboundChainPackagePacket.OFFER?1:0,action==ClientboundChainPackagePacket.OFFER?.75f:0,0);var bytes=buffer();
            try{ClientboundChainPackagePacket.STREAM_CODEC.encode(bytes,packet);var decoded=ClientboundChainPackagePacket.STREAM_CODEC.decode(bytes);
                assertEquals(packet,decoded);assertEquals(Float.floatToIntBits(BASE.state().progress()),Float.floatToIntBits(decoded.baseline().state().progress()));assertEquals(0,bytes.readableBytes());}
            finally{bytes.release();}
        }
    }
    @Test void controlsAndHeartbeatDoNotContainPositionOrInventory() {
        for(int action=ServerboundChainPackagePacket.PREPARED;action<=ServerboundChainPackagePacket.RELEASE;action++) {
            var packet=ServerboundChainPackagePacket.control(action,EPOCH,BASE,action==ServerboundChainPackagePacket.PREPARED?131071:0);var bytes=buffer();
            try{ServerboundChainPackagePacket.STREAM_CODEC.encode(bytes,packet);assertTrue(bytes.readableBytes()<64);
                var decoded=ServerboundChainPackagePacket.STREAM_CODEC.decode(bytes);assertEquals(packet.action(),decoded.action());assertEquals(packet.epoch(),decoded.epoch());
                assertEquals(BASE.identity(),decoded.identity());assertEquals(BASE.revision(),decoded.revision());assertEquals(packet.candidate(),decoded.candidate());assertEquals(0,bytes.readableBytes());}
            finally{bytes.release();}
        }
        var bytes=buffer();try {
            var heartbeat=new ServerboundChainPackagePacket(ServerboundChainPackagePacket.HEARTBEAT,EPOCH,0,null,0,0,0,0,new byte[0]);
            ServerboundChainPackagePacket.STREAM_CODEC.encode(bytes,heartbeat);assertEquals(6,bytes.readableBytes());assertEquals(0,ServerboundChainPackagePacket.STREAM_CODEC.decode(bytes).events().length);
        }finally{bytes.release();}
    }
    @Test void eventBodyIsImmutableBoundedAndInvalidNodeCountFailsBeforeAllocation() {
        byte[] source={1,2,3};var packet=new ServerboundChainPackagePacket(ServerboundChainPackagePacket.EVENTS,EPOCH,0,null,0,0,0,7,source);source[0]=99;packet.events()[0]=88;
        assertArrayEquals(new byte[]{1,2,3},packet.events());var bytes=buffer();try {
            ServerboundChainPackagePacket.STREAM_CODEC.encode(bytes,packet);var decoded=ServerboundChainPackagePacket.STREAM_CODEC.decode(bytes);assertEquals(7,decoded.sequence());assertArrayEquals(packet.events(),decoded.events());
        }finally{bytes.release();}
        assertThrows(IllegalArgumentException.class,()->new ServerboundChainPackagePacket(ServerboundChainPackagePacket.EVENTS,EPOCH,0,null,0,0,0,0,new byte[PackageChainEventCodec.MAX_WIRE_BYTES+1]));
        assertThrows(IllegalArgumentException.class,()->new ClientboundChainPackagePacket.Track(1,BlockPos.ZERO,null,PackageChainTrack.loop(BlockPos.ZERO,1,0,0,1),List.of(new ClientboundChainPackagePacket.Node(2,1))));
        var invalid=buffer();try {
            invalid.writeByte(99);invalid.writeResourceLocation(DIM);invalid.writeVarLong(EPOCH);
            assertThrows(DecoderException.class,()->ClientboundChainPackagePacket.STREAM_CODEC.decode(invalid));
        }finally{invalid.release();}
    }
    @Test void epochCloseAndExactTransactionAckHaveSmallIndependentEnvelopes() {
        for(int action:new int[]{ClientboundChainPackagePacket.ACK,ClientboundChainPackagePacket.CLOSE}) {
            var packet=new ClientboundChainPackagePacket(action,DIM,EPOCH,null,null,null,0,0,action==ClientboundChainPackagePacket.ACK?1000000000L:0);var bytes=buffer();
            try{ClientboundChainPackagePacket.STREAM_CODEC.encode(bytes,packet);assertTrue(bytes.readableBytes()<64);assertEquals(packet,ClientboundChainPackagePacket.STREAM_CODEC.decode(bytes));}
            finally{bytes.release();}
        }
    }
    @Test void pickupRequestsAndAllResultsPreserveExactIdentityAndTransactionWithoutInventory() {
        var request=new PackageChainInteraction(EPOCH,BASE.identity(),BASE.leaseEpoch(),BASE.revision(),BASE.track(),BASE.trackRevision(),0x500000011L,BASE.state().progress());
        var bytes=buffer();try {
            var up=new ServerboundChainInteractionPacket(request);ServerboundChainInteractionPacket.STREAM_CODEC.encode(bytes,up);
            assertTrue(bytes.readableBytes()<80);assertEquals(up,ServerboundChainInteractionPacket.STREAM_CODEC.decode(bytes));assertEquals(0,bytes.readableBytes());
        }finally{bytes.release();}
        for(var result:PackageChainAuthority.Result.values()) {
            bytes=buffer();try {
                var down=new ClientboundChainInteractionPacket(EPOCH,BASE.identity(),request.transaction(),result);
                ClientboundChainInteractionPacket.STREAM_CODEC.encode(bytes,down);assertTrue(bytes.readableBytes()<48);
                assertEquals(down,ClientboundChainInteractionPacket.STREAM_CODEC.decode(bytes));assertEquals(0,bytes.readableBytes());
            }finally{bytes.release();}
        }
    }
    @Test void malformedPickupProgressAndUnknownConfirmationCannotReachGameplay() {
        var bytes=buffer();try {
            ServerboundChainInteractionPacket.STREAM_CODEC.encode(bytes,new ServerboundChainInteractionPacket(new PackageChainInteraction(EPOCH,BASE.identity(),3,5,7,9,1,42)));
            bytes.setFloat(bytes.writerIndex()-4,Float.NaN);var invalid=bytes;
            assertThrows(DecoderException.class,()->ServerboundChainInteractionPacket.STREAM_CODEC.decode(invalid));
        }finally{bytes.release();}
        bytes=buffer();try {
            ClientboundChainInteractionPacket.STREAM_CODEC.encode(bytes,new ClientboundChainInteractionPacket(EPOCH,BASE.identity(),1,PackageChainAuthority.Result.ACCEPTED));
            bytes.setByte(bytes.writerIndex()-1,99);var invalid=bytes;
            assertThrows(DecoderException.class,()->ClientboundChainInteractionPacket.STREAM_CODEC.decode(invalid));
        }finally{bytes.release();}
        for(long tx:new long[]{0,-1})assertThrows(IllegalArgumentException.class,()->new PackageChainInteraction(EPOCH,BASE.identity(),3,5,7,9,tx,42));
    }
}
