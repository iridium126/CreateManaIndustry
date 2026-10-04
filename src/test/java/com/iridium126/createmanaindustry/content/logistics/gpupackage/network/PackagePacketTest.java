package com.iridium126.createmanaindustry.content.logistics.gpupackage.network;

import static org.junit.jupiter.api.Assertions.*;
import java.util.UUID;
import com.iridium126.createmanaindustry.content.logistics.gpupackage.*;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.DecoderException;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

class PackagePacketTest {
    private static final PackageRegion REGION=new PackageRegion(-200,20000,-1);
    private static final PackageAuthorityRegion.Baseline BASELINE=new PackageAuthorityRegion.Baseline(65536,
            new PackageLease.Identity(0x1234567800000001L,0x2345678900000001L),0x3456789000000001L,9,
            new PackageAuthorityRegion.Snapshot(new PackageLease.Pose(-12798,1280002,-62,1,-2,3,-89),1));
    private static RegistryFriendlyByteBuf buffer(){return new RegistryFriendlyByteBuf(Unpooled.buffer(),RegistryAccess.EMPTY);}
    @Test void v3BatchActionKeepsEnvelopeIdentityAndLosslessDecodedFields() {
        var entries=java.util.List.of(new PackageDeltaCodec.Entry(65536,15,new PackageDeltaCodec.Quantized(Integer.MIN_VALUE,5,Integer.MAX_VALUE,
                Short.MIN_VALUE,Short.MAX_VALUE,(short)3,(short)-99,3)),new PackageDeltaCodec.Entry(65537,16,
                new PackageDeltaCodec.Quantized(0,0,0,(short)0,(short)0,(short)0,(short)0,0)));
        var wire=java.nio.ByteBuffer.allocate(100);PackageBatchDeltaCodec.encode(wire,entries);
        byte[] body=java.util.Arrays.copyOf(wire.array(),wire.position());
        for(int action:new int[]{ServerboundPackagePacket.BATCH_DELTA,ServerboundPackagePacket.RELATIVE_DELTA,ServerboundPackagePacket.PREDICTED_DELTA}) {
            var packet=new ServerboundPackagePacket(action,0,REGION,0x4567890100000001L,0,null,0,10,0x5678901200000001L,body);
            var bytes=buffer();try {
                ServerboundPackagePacket.STREAM_CODEC.encode(bytes,packet);var decoded=ServerboundPackagePacket.STREAM_CODEC.decode(bytes);
                assertEquals(action,decoded.action());assertEquals(REGION,decoded.region());
                assertEquals(packet.epoch(),decoded.epoch());assertEquals(packet.revision(),decoded.revision());assertEquals(packet.sequence(),decoded.sequence());
                assertArrayEquals(body,decoded.changes());assertEquals(entries,PackageBatchDeltaCodec.decode(java.nio.ByteBuffer.wrap(decoded.changes())));
                assertEquals(0,bytes.readableBytes());
            }finally{bytes.release();}
        }
    }
    @Test void controlRoundTripRetainsFullIdentitiesEpochAndSignedRegion() {
        for(int action:new int[]{ServerboundPackagePacket.PREPARED,ServerboundPackagePacket.FINAL_READY,ServerboundPackagePacket.VISIBLE_READY,ServerboundPackagePacket.RELEASE}) {
            var packet=ServerboundPackagePacket.control(action,REGION,0x4567890100000001L,BASELINE);var bytes=buffer();
            try {
                ServerboundPackagePacket.STREAM_CODEC.encode(bytes,packet);var decoded=ServerboundPackagePacket.STREAM_CODEC.decode(bytes);
                assertEquals(packet.action(),decoded.action());assertEquals(packet.region(),decoded.region());assertEquals(packet.epoch(),decoded.epoch());
                assertEquals(packet.index(),decoded.index());assertEquals(packet.identity(),decoded.identity());assertEquals(packet.leaseEpoch(),decoded.leaseEpoch());
                assertEquals(packet.revision(),decoded.revision());assertArrayEquals(packet.changes(),decoded.changes());assertEquals(0,bytes.readableBytes());
            }
            finally{bytes.release();}
        }
    }
    @Test void batchedControlsHaveAnExactBoundedEnvelopeAndCannotBecomePoseDeltas() {
        var body=java.nio.ByteBuffer.allocate(PackageControlBatchCodec.MAX_BYTES);
        PackageControlBatchCodec.encode(body,ServerboundPackagePacket.VISIBLE_READY,java.util.List.of(BASELINE));
        byte[] encoded=java.util.Arrays.copyOf(body.array(),body.position());
        var packet=ServerboundPackagePacket.controls(REGION,0x4567890100000001L,17,encoded);encoded[0]=100;
        assertFalse(ServerboundPackagePacket.deltaAction(packet.action()));
        var bytes=buffer();try {
            ServerboundPackagePacket.STREAM_CODEC.encode(bytes,packet);var decoded=ServerboundPackagePacket.STREAM_CODEC.decode(bytes);
            assertEquals(ServerboundPackagePacket.CONTROL_BATCH,decoded.action());assertEquals(REGION,decoded.region());
            assertEquals(packet.epoch(),decoded.epoch());assertEquals(17,decoded.revision());assertEquals(0,decoded.sequence());assertEquals(0,bytes.readableBytes());
            assertArrayEquals(packet.changes(),decoded.changes());
            assertEquals(1,PackageControlBatchCodec.visitValidated(java.nio.ByteBuffer.wrap(decoded.changes()),(a,i,id,g,l,r)->{
                assertEquals(ServerboundPackagePacket.VISIBLE_READY,a);assertEquals(BASELINE.index(),i);assertEquals(BASELINE.identity().id(),id);
                assertEquals(BASELINE.identity().generation(),g);assertEquals(BASELINE.leaseEpoch(),l);assertEquals(BASELINE.revision(),r);
            }));
            assertThrows(IllegalArgumentException.class,()->ServerboundPackagePacket.controls(REGION,1,0,packet.changes()));
            assertThrows(IllegalArgumentException.class,()->ServerboundPackagePacket.controls(REGION,1,1,new byte[PackageControlBatchCodec.MAX_BYTES+1]));
        }finally{bytes.release();}
    }
    @Test void capabilitiesHeartbeatAndDeltaHaveBoundedBodies() {
        var bytes=buffer();
        try {
            int capabilities=ServerboundPackagePacket.FREE_READY|ServerboundPackagePacket.CHAIN_READY|ServerboundPackagePacket.WIDE_VELOCITY;
            ServerboundPackagePacket.STREAM_CODEC.encode(bytes,ServerboundPackagePacket.capabilities(capabilities));assertEquals(2,bytes.readableBytes());
            assertEquals(capabilities,ServerboundPackagePacket.STREAM_CODEC.decode(bytes).capabilities());bytes.clear();
            var heartbeat=new ServerboundPackagePacket(ServerboundPackagePacket.HEARTBEAT,0,REGION,10,0,null,0,0,0,new byte[0]);
            ServerboundPackagePacket.STREAM_CODEC.encode(bytes,heartbeat);assertTrue(bytes.readableBytes()<32);
            var decoded=ServerboundPackagePacket.STREAM_CODEC.decode(bytes);assertEquals(REGION,decoded.region());assertEquals(0,decoded.changes().length);
            byte[] body={1,2,3};var delta=new ServerboundPackagePacket(ServerboundPackagePacket.DELTA,0,REGION,10,0,null,0,1,7,body);
            body[0]=99;assertArrayEquals(new byte[]{1,2,3},delta.changes());delta.changes()[0]=98;bytes.clear();
            ServerboundPackagePacket.STREAM_CODEC.encode(bytes,delta);decoded=ServerboundPackagePacket.STREAM_CODEC.decode(bytes);
            assertArrayEquals(new byte[]{1,2,3},decoded.changes());assertEquals(7,decoded.sequence());assertEquals(1,decoded.revision());
            assertThrows(IllegalArgumentException.class,()->new ServerboundPackagePacket(ServerboundPackagePacket.DELTA,0,REGION,10,0,null,0,1,7,new byte[ServerboundPackagePacket.MAX_BYTES+1]));
        }finally{bytes.release();}
    }
    @Test void serverPayloadViewIsReadOnlyAndPublicArrayAccessRemainsDefensive() {
        byte[] body={3,4,5};var packet=new ServerboundPackagePacket(ServerboundPackagePacket.DELTA,0,REGION,10,0,null,0,1,7,body);
        body[0]=9;var view=packet.changesView();
        assertTrue(view.isReadOnly());assertEquals(3,view.get(0));
        assertThrows(java.nio.ReadOnlyBufferException.class,()->view.put(0,(byte)8));
        byte[] exposed=packet.changes();exposed[0]=7;assertEquals(3,packet.changes()[0]);
    }
    @Test void initialAndFinalBaselineOnlyInitialCarriesModelAndEntityIdentity() {
        var dimension=ResourceLocation.fromNamespaceAndPath("minecraft","overworld");var model=ResourceLocation.fromNamespaceAndPath("create","cardboard_package_10x8");
        var uuid=new UUID(11,17);
        for(int action:new int[]{ClientboundPackagePacket.OFFER,ClientboundPackagePacket.FINAL_BASELINE}) {
            var packet=new ClientboundPackagePacket(action,dimension,REGION,100,3,0,BASELINE,7,uuid,model,1,.75f);var bytes=buffer();
            try {
                ClientboundPackagePacket.STREAM_CODEC.encode(bytes,packet);var decoded=ClientboundPackagePacket.STREAM_CODEC.decode(bytes);
                assertEquals(BASELINE,decoded.baseline());assertEquals(dimension,decoded.dimension());assertEquals(REGION,decoded.region());
                if(action==ClientboundPackagePacket.OFFER){assertEquals(uuid,decoded.entityUuid());assertEquals(model,decoded.model());assertEquals(1,decoded.width());}
                else{assertNull(decoded.entityUuid());assertNull(decoded.model());}
                assertEquals(0,bytes.readableBytes());
            }finally{bytes.release();}
        }
    }
    @Test void ackAndOwnershipNotificationsCarryNoCoordinates() {
        var dimension=ResourceLocation.fromNamespaceAndPath("minecraft","overworld");
        for(int action:new int[]{ClientboundPackagePacket.ACTIVE,ClientboundPackagePacket.RELEASED,ClientboundPackagePacket.ACK}) {
            var packet=new ClientboundPackagePacket(action,dimension,REGION,100,3,900,action==ClientboundPackagePacket.ACK?null:BASELINE,0,null,null,0,0);var bytes=buffer();
            try {
                ClientboundPackagePacket.STREAM_CODEC.encode(bytes,packet);var decoded=ClientboundPackagePacket.STREAM_CODEC.decode(bytes);
                assertTrue(bytes.writerIndex()<110);assertEquals(action,decoded.action());assertEquals(100,decoded.epoch());
                if(action==ClientboundPackagePacket.ACK){assertEquals(900,decoded.sequence());assertNull(decoded.baseline());}
                else{assertEquals(BASELINE.identity(),decoded.baseline().identity());assertEquals(BASELINE.leaseEpoch(),decoded.baseline().leaseEpoch());}
                assertEquals(0,bytes.readableBytes());
            }finally{bytes.release();}
        }
    }
    @Test void invalidActionsEpochsCapabilitiesAndOversizeLengthAreRejected() {
        var bytes=buffer();
        try {
            bytes.writeByte(100);assertThrows(DecoderException.class,()->ServerboundPackagePacket.STREAM_CODEC.decode(bytes));bytes.clear();
            bytes.writeByte(ServerboundPackagePacket.CAPABILITIES).writeByte(8);
            assertThrows(DecoderException.class,()->ServerboundPackagePacket.STREAM_CODEC.decode(bytes));bytes.clear();
            bytes.writeByte(ServerboundPackagePacket.HEARTBEAT);ServerboundPackagePacket.writeRegion(bytes,REGION);bytes.writeVarLong(0);
            assertThrows(DecoderException.class,()->ServerboundPackagePacket.STREAM_CODEC.decode(bytes));bytes.clear();
            bytes.writeByte(ServerboundPackagePacket.DELTA);ServerboundPackagePacket.writeRegion(bytes,REGION);bytes.writeVarLong(1);bytes.writeVarLong(1);bytes.writeVarLong(0);
            bytes.writeVarInt(ServerboundPackagePacket.MAX_BYTES+1);assertThrows(DecoderException.class,()->ServerboundPackagePacket.STREAM_CODEC.decode(bytes));
        }finally{bytes.release();}
    }
    @Test void environmentAckPreservesLifecycleAndConfirmedDamageState(){
        var dimension=ResourceLocation.fromNamespaceAndPath("minecraft","overworld");
        var packet=new ClientboundPackagePacket(ClientboundPackagePacket.ENVIRONMENT_ACK,dimension,REGION,100,3,3876543210L,BASELINE,-1,null,null,0,0,87,2.75f,1);
        var bytes=buffer();
        try{
            ClientboundPackagePacket.STREAM_CODEC.encode(bytes,packet);var decoded=ClientboundPackagePacket.STREAM_CODEC.decode(bytes);
            assertEquals(packet.sequence(),decoded.sequence());assertEquals(BASELINE.identity(),decoded.baseline().identity());
            assertEquals(BASELINE.leaseEpoch(),decoded.baseline().leaseEpoch());assertEquals(BASELINE.index(),decoded.baseline().index());assertEquals(BASELINE.revision(),decoded.baseline().revision());
            assertEquals(87,decoded.fireTicks());assertEquals(2.75f,decoded.health());assertEquals(1,decoded.environmentPermissions());assertEquals(0,bytes.readableBytes());
            bytes.clear();bytes.writeByte(5).writeResourceLocation(dimension);ServerboundPackagePacket.writeRegion(bytes,REGION);bytes.writeVarLong(100).writeVarLong(3);
            assertThrows(DecoderException.class,()->ClientboundPackagePacket.STREAM_CODEC.decode(bytes));
            assertThrows(IllegalArgumentException.class,()->new ClientboundPackagePacket(5,dimension,REGION,100,3,0,BASELINE,0,null,null,0,0));
            assertThrows(IllegalArgumentException.class,()->new ClientboundPackagePacket(ClientboundPackagePacket.ENVIRONMENT_ACK,dimension,REGION,100,3,0x1_0000_0000L,BASELINE,0,null,null,0,0));
        }finally{bytes.release();}
    }
    @Test void deltaStepAndEnvironmentBodyRoundTripWithoutTruncation(){
        for(int action:new int[]{ServerboundPackagePacket.DELTA,ServerboundPackagePacket.BATCH_DELTA,ServerboundPackagePacket.RELATIVE_DELTA,ServerboundPackagePacket.PREDICTED_DELTA,ServerboundPackagePacket.ENVIRONMENT}){
            byte[] body=new byte[1024];body[1023]=42;long step=action==ServerboundPackagePacket.ENVIRONMENT?0:9876543210L;
            var packet=new ServerboundPackagePacket(action,0,REGION,100,0,null,0,3,7,body,step);var bytes=buffer();
            try{ServerboundPackagePacket.STREAM_CODEC.encode(bytes,packet);var decoded=ServerboundPackagePacket.STREAM_CODEC.decode(bytes);
                assertEquals(step,decoded.simulationStep());assertArrayEquals(body,decoded.changes());assertEquals(action,decoded.action());assertEquals(REGION,decoded.region());assertEquals(0,bytes.readableBytes());
            }finally{bytes.release();}
        }
    }
}
