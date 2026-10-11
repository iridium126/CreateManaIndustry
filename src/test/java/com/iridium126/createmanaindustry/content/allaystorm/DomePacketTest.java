package com.iridium126.createmanaindustry.content.allaystorm;
import com.iridium126.createmanaindustry.content.allaystorm.network.*;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.DecoderException;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class DomePacketTest {
 static ClientboundStormWavePacket packet(int event,int phase,int[] roster,int[] groups){
  return new ClientboundStormWavePacket(9,77,event,43,12,5,7,4096,0,5,10,90,0,120,0,8,roster,new float[]{0,120,0,2,72,3},new float[28],1,phase,groups,2,64,3,.27f,-1,8,0);
 }
 static ClientboundStormWavePacket roundtrip(ClientboundStormWavePacket p){
  var b=new RegistryFriendlyByteBuf(Unpooled.buffer(),RegistryAccess.EMPTY);
  try{ClientboundStormWavePacket.STREAM_CODEC.encode(b,p);var q=ClientboundStormWavePacket.STREAM_CODEC.decode(b);assertFalse(b.isReadable());return q;}finally{b.release();}
 }
 @Test void explicitPhaseAndSevenGroups(){
  int[] roster=java.util.stream.IntStream.range(0,768).toArray();
  for(int phase:new int[]{1,2,3,4,5}){
   var p=packet(0,phase,roster,AllayDome.groups(768));var q=roundtrip(p);
   assertTrue(q.dome());assertEquals(phase,q.phase());assertEquals(phase>=3,q.locked());
   assertArrayEquals(p.groupEnds(),q.groupEnds());assertArrayEquals(roster,q.members());
   // START snapshot can be locked despite zero landing durations: no starfall heuristic.
   assertEquals(0,q.landings()[3]);assertEquals(-1,q.direction());assertEquals(p.centerX(),q.centerX());
  }
  assertEquals(0,roundtrip(packet(1,2,new int[0],AllayDome.groups(768))).members().length);
  assertTrue(roundtrip(packet(2,4,new int[0],AllayDome.groups(768))).locked());
 }
 @Test void invalidGroupsAndPhaseRejected(){
  int[] roster=java.util.stream.IntStream.range(0,768).toArray(),bad=AllayDome.groups(768);bad[0]++;
  assertThrows(DecoderException.class,()->roundtrip(packet(0,1,roster,bad)));
  assertThrows(DecoderException.class,()->roundtrip(packet(1,4,new int[0],AllayDome.groups(768))));
  assertThrows(DecoderException.class,()->roundtrip(packet(2,1,new int[0],AllayDome.groups(768))));
  assertThrows(DecoderException.class,()->roundtrip(packet(0,1,new int[767],AllayDome.groups(767))));
 }
}
