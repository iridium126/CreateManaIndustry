package com.iridium126.createmanaindustry.content.allaystorm;
import org.junit.jupiter.api.Test;
import java.util.*;
import net.minecraft.world.phys.Vec3;
import static org.junit.jupiter.api.Assertions.*;
class StormV11Test {
 @Test void thickShellTemplateIsFixedFiniteAndEndsInsideCore(){
  assertTrue(DomeTemplate.available());Map<String,List<Vec3>> grid=new HashMap<>();double spacing=1.4;
  for(int i=0;i<12288;i++){var p=DomeTemplate.point(i,0);assertTrue(p.y>=0&&p.length()>=16&&p.length()<=32);int x=(int)Math.floor(p.x/spacing),y=(int)Math.floor(p.y/spacing),z=(int)Math.floor(p.z/spacing);
   for(int a=-1;a<=1;a++)for(int b=-1;b<=1;b++)for(int c=-1;c<=1;c++)for(var q:grid.getOrDefault((x+a)+","+(y+b)+","+(z+c),List.of()))assertTrue(p.distanceTo(q)>=1.39999);
   grid.computeIfAbsent(x+","+y+","+z,k->new ArrayList<>()).add(p);assertTrue(DomeTemplate.duration(i)>0&&DomeTemplate.duration(i)<=60);assertTrue(DomeTemplate.point(i,360).length()<6);
   for(double time:new double[]{0,1,5,10,20,40,59.9})if(time<DomeTemplate.duration(i)){var q=DomeTemplate.point(i,time*6);assertTrue(Double.isFinite(q.x)&&q.y>=0&&Double.isFinite(q.z));}


  }
 }
 @Test void completedCodecCarries12288AndRejectsOversizedPayload(){var b=new net.minecraft.network.RegistryFriendlyByteBuf(io.netty.buffer.Unpooled.buffer(),net.minecraft.core.RegistryAccess.EMPTY,net.neoforged.neoforge.network.connection.ConnectionType.OTHER);try{var bits=new BitSet();bits.set(0);bits.set(4096);bits.set(12287);var p=new com.iridium126.createmanaindustry.content.allaystorm.network.StormAttackCompletedPacket(net.minecraft.world.level.Level.OVERWORLD.location(),12,9,bits.toLongArray());var codec=com.iridium126.createmanaindustry.content.allaystorm.network.StormAttackCompletedPacket.STREAM_CODEC;codec.encode(b,p);assertArrayEquals(p.ranks(),codec.decode(b).ranks());b.clear();codec.encode(b,new com.iridium126.createmanaindustry.content.allaystorm.network.StormAttackCompletedPacket(p.dimension(),12,9,new long[193]));assertThrows(Exception.class,()->codec.decode(b));}finally{b.release();}}
 @Test void gatherCodecPreservesIdentity(){var b=new net.minecraft.network.RegistryFriendlyByteBuf(io.netty.buffer.Unpooled.buffer(),net.minecraft.core.RegistryAccess.EMPTY,net.neoforged.neoforge.network.connection.ConnectionType.OTHER);try{var p=new com.iridium126.createmanaindustry.content.allaystorm.network.StormAttackGatheredPacket(net.minecraft.world.level.Level.NETHER.location(),123,42,new long[]{-1},new long[]{-1});var codec=com.iridium126.createmanaindustry.content.allaystorm.network.StormAttackGatheredPacket.STREAM_CODEC;codec.encode(b,p);var q=codec.decode(b);assertEquals(p.dimension(),q.dimension());assertEquals(p.id(),q.id());assertEquals(p.epoch(),q.epoch());assertArrayEquals(p.ready(),q.ready());assertArrayEquals(p.eligible(),q.eligible());assertFalse(b.isReadable());}finally{b.release();}}
 @Test void slotLayoutHasNoOverlap(){assertEquals(0,com.iridium126.createmanaindustry.client.allaystorm.StarfallGpuBuffers.completionOffset(0));assertEquals(1152,com.iridium126.createmanaindustry.client.allaystorm.StarfallGpuBuffers.completionOffset(3));assertEquals(1536,com.iridium126.createmanaindustry.client.allaystorm.StarfallGpuBuffers.completionOffset(4));assertEquals(1662,com.iridium126.createmanaindustry.client.allaystorm.StarfallGpuBuffers.completionOffset(67));}
}
