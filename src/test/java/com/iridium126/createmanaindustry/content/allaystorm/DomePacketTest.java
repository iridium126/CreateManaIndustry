package com.iridium126.createmanaindustry.content.allaystorm;
import com.iridium126.createmanaindustry.content.allaystorm.network.*;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.DecoderException;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.neoforged.neoforge.network.connection.ConnectionType;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class DomePacketTest {
 static ClientboundStormWavePacket packet(int event,int phase,int[] roster,int[] groups){
  return new ClientboundStormWavePacket(9,77,event,43,12,5,1,12288,0,5,10,90,0,120,0,32,roster,new float[]{0,120,0,2,72,3},new float[4],1,phase,groups,2,64,3,.27f,-1,8,0);
 }
 static ClientboundStormWavePacket roundtrip(ClientboundStormWavePacket p){
  var b=new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY, ConnectionType.OTHER);
  try{ClientboundStormWavePacket.STREAM_CODEC.encode(b,p);var q=ClientboundStormWavePacket.STREAM_CODEC.decode(b);assertFalse(b.isReadable());return q;}finally{b.release();}
 }
 @Test void fixedRosterAndSingleGroup(){
  int[] roster=java.util.stream.IntStream.range(0,12288).toArray();
  for(int phase:new int[]{1,2,3,4,5}){
   var p=packet(0,phase,roster,AllayDome.groups(12288));var q=roundtrip(p);
   assertTrue(q.dome());assertEquals(phase,q.phase());assertEquals(phase>=3,q.locked());
   assertArrayEquals(p.groupEnds(),q.groupEnds());assertArrayEquals(roster,q.members());
   // START snapshot can be locked despite zero landing durations: no starfall heuristic.
   assertEquals(0,q.landings()[3]);assertEquals(-1,q.direction());assertEquals(p.centerX(),q.centerX());
  }
  assertEquals(0,roundtrip(packet(1,2,new int[0],AllayDome.groups(12288))).members().length);
  assertTrue(roundtrip(packet(2,4,new int[0],AllayDome.groups(12288))).locked());
 }
 @Test void invalidGroupsAndPhaseRejected(){
  int[] roster=java.util.stream.IntStream.range(0,12288).toArray(),bad=AllayDome.groups(12288);bad[0]++;
  assertThrows(DecoderException.class,()->roundtrip(packet(0,1,roster,bad)));
  assertThrows(DecoderException.class,()->roundtrip(packet(1,4,new int[0],AllayDome.groups(12288))));
  assertThrows(DecoderException.class,()->roundtrip(packet(2,1,new int[0],AllayDome.groups(12288))));
  assertThrows(DecoderException.class,()->roundtrip(packet(0,1,new int[12287],AllayDome.groups(12287))));
 }
 @Test void lockAndObserverSnapshotAtomicallyPreserveNonParticipants(){
  var base=packet(0,4,java.util.stream.IntStream.range(0,12288).toArray(),AllayDome.groups(12288));
  var finished=new java.util.BitSet();finished.set(4096);finished.set(12287);
  var p=new ClientboundStormWavePacket(base.waveId(),base.epoch(),base.event(),base.waveSeed(),base.targetEntityId(),base.swordTier(),base.columns(),base.perColumn(),base.startSec(),base.assembleSec(),base.lockSec(),base.diveUntilSec(),base.crownX(),base.crownY(),base.crownZ(),base.crownRadius(),base.members(),base.path(),base.landings(),base.attackType(),base.phase(),base.groupEnds(),base.centerX(),base.centerY(),base.centerZ(),base.orientation(),base.direction(),base.centerSec(),base.fallbackReason(),base.helix(),finished.toLongArray());
  var q=roundtrip(p);assertArrayEquals(p.completedRanks(),q.completedRanks());
  var slots=new StormWaveSlots();var update=slots.apply(q,1);
  assertArrayEquals(p.completedRanks(),update.packet().completedRanks());
  assertArrayEquals(p.completedRanks(),q.copy(2,4,q.lockSec(),q.diveUntilSec(),new int[0]).completedRanks());
 }
}
