package com.iridium126.createmanaindustry.content.allaystorm;
import com.iridium126.createmanaindustry.content.allaystorm.network.*;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.DecoderException;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class HelixPacketTest {
 static ClientboundStormWavePacket packet(int event,int phase,boolean members){var roster=AllayHelixTest.roster(8192);var f=AllayHelixTest.flight(8192);
  return new ClientboundStormWavePacket(9,87,event,1,1,5,2,4096,0,4,13,90,0,128,0,f.radius(),members?roster.members():new int[0],new float[]{0,128,0,-24,4,0},new float[8],2,phase,roster.ends(),-24,4,0,0,1,10,0,f);
 }
 static ClientboundStormWavePacket roundtrip(ClientboundStormWavePacket p){var b=new RegistryFriendlyByteBuf(Unpooled.buffer(),RegistryAccess.EMPTY);try{ClientboundStormWavePacket.STREAM_CODEC.encode(b,p);var q=ClientboundStormWavePacket.STREAM_CODEC.decode(b);assertFalse(b.isReadable());return q;}finally{b.release();}}
 @Test void startDeltaLockAndObserver(){for(int phase:new int[]{1,2,3,4,5}){var p=packet(0,phase,true);var q=roundtrip(p);assertTrue(q.helixAttack());assertFalse(q.starfall());assertFalse(q.dome());assertArrayEquals(p.members(),q.members());assertArrayEquals(p.helix().controls(),q.helix().controls());assertArrayEquals(p.helix().samples(),q.helix().samples());assertEquals(phase>=3,q.locked());}assertEquals(0,roundtrip(packet(1,2,false)).members().length);assertTrue(roundtrip(packet(2,3,false)).locked());}
 @Test void finiteArcAndGroupValidation(){var p=packet(0,1,true);p.helix().samples()[3]=Float.NaN;assertThrows(DecoderException.class,()->roundtrip(p));var bad=packet(0,1,true);bad.groupEnds()[0]=1;assertThrows(DecoderException.class,()->roundtrip(bad));}
 @Test void abortCarriesReason(){var p=roundtrip(ClientboundStormWavePacket.abort(9,87,5));assertTrue(p.abort());assertEquals(5,p.fallbackReason());}
}
