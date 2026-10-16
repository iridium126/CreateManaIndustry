package com.iridium126.createmanaindustry.content.allaystorm;
import com.iridium126.createmanaindustry.content.allaystorm.network.*;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.DecoderException;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.neoforged.neoforge.network.connection.ConnectionType;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class StarfallPacketTest {
 private ClientboundStormWavePacket packet(int event,int[] members){return new ClientboundStormWavePacket(4,87,event,123,5,5,1,512,0,4,10,50,1,12,2,4,members,new float[]{1,12,2,3,10,4},new float[]{3,.5f,4,3.5f});}
 @Test void rosterAndLockRoundtrip(){for(int event:new int[]{0,1,2}){var p=packet(event,event==0?new int[]{0,64,131071}:new int[0]);var b=new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY, ConnectionType.OTHER);try{ClientboundStormWavePacket.STREAM_CODEC.encode(b,p);var q=ClientboundStormWavePacket.STREAM_CODEC.decode(b);assertEquals(p.epoch(),q.epoch());assertEquals(event,q.event());assertEquals(p.waveId(),q.waveId());assertArrayEquals(p.members(),q.members());assertArrayEquals(p.path(),q.path());assertArrayEquals(p.landings(),q.landings());assertFalse(b.isReadable());}finally{b.release();}}}
 @Test void abortRoundtrip(){var b=new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY, ConnectionType.OTHER);try{ClientboundStormWavePacket.STREAM_CODEC.encode(b,ClientboundStormWavePacket.abort(9,87));var p=ClientboundStormWavePacket.STREAM_CODEC.decode(b);assertTrue(p.abort());assertEquals(87,p.epoch());assertFalse(b.isReadable());}finally{b.release();}}
 @Test void invalidRosterAndFiniteParameters(){for(var p:new ClientboundStormWavePacket[]{packet(0,new int[]{1,1}),packet(0,new int[]{-1}),new ClientboundStormWavePacket(4,87,0,123,5,5,1,512,0,4,10,50,Float.NaN,12,2,4,new int[]{1},new float[]{1,12,2,3,10,4},new float[]{3,.5f,4,3.5f})}){var b=new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY, ConnectionType.OTHER);try{ClientboundStormWavePacket.STREAM_CODEC.encode(b,p);assertThrows(DecoderException.class,()->ClientboundStormWavePacket.STREAM_CODEC.decode(b));}finally{b.release();}}}
 @Test void contactPreservesEpochSampleTimeAndAbsolutePosition(){var p=new ServerboundStormWaveContactPacket(4,87,12.05f,64,100000.25f,64.5f,-100000.75f);var b=new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY, ConnectionType.OTHER);try{ServerboundStormWaveContactPacket.STREAM_CODEC.encode(b,p);assertEquals(p,ServerboundStormWaveContactPacket.STREAM_CODEC.decode(b));assertFalse(b.isReadable());}finally{b.release();}}
}
