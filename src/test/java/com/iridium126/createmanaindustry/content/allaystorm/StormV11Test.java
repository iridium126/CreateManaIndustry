package com.iridium126.createmanaindustry.content.allaystorm;
import org.junit.jupiter.api.Test;
import java.util.*;
import net.minecraft.world.phys.Vec3;
import static org.junit.jupiter.api.Assertions.*;
class StormV11Test {
 @Test void thickShellTemplateIsFixedFiniteAndEndsInsideCore(){
  assertTrue(DomeTemplate.available());Map<String,List<Vec3>> grid=new HashMap<>();double spacing=1.4;
  for(int i=0;i<8192;i++){var p=DomeTemplate.point(i,0);assertTrue(p.y>=0&&p.length()>=16&&p.length()<=32);int x=(int)Math.floor(p.x/spacing),y=(int)Math.floor(p.y/spacing),z=(int)Math.floor(p.z/spacing);
   for(int a=-1;a<=1;a++)for(int b=-1;b<=1;b++)for(int c=-1;c<=1;c++)for(var q:grid.getOrDefault((x+a)+","+(y+b)+","+(z+c),List.of()))assertTrue(p.distanceTo(q)>=1.39999);
   grid.computeIfAbsent(x+","+y+","+z,k->new ArrayList<>()).add(p);assertTrue(DomeTemplate.duration(i)>0&&DomeTemplate.duration(i)<=60);assertTrue(DomeTemplate.point(i,360).length()<6);
   int record=4+i*4,lane=(int)DomeTemplate.word(record),base=4+8192*4+lane*1024*4;double start=DomeTemplate.word(record+1);
   for(int k:new int[]{100,300,500,700,900}){double lo=DomeTemplate.word(base+k*4+3),hi=DomeTemplate.word(base+k*4+7),mid=(lo+hi)*.5;if(mid>start&&hi-lo>.001){var at=DomeTemplate.point(i,mid-start-.0001);var next=DomeTemplate.point(i,mid-start+.0001);assertEquals(1,at.distanceTo(next)/.0002,.001);assertTrue(at.y>=-.001);}}

  }
 }
 @Test void completedCodecCarries8192AndRejectsOversizedPayload(){var b=new net.minecraft.network.RegistryFriendlyByteBuf(io.netty.buffer.Unpooled.buffer(),net.minecraft.core.RegistryAccess.EMPTY,net.neoforged.neoforge.network.connection.ConnectionType.OTHER);try{var bits=new BitSet();bits.set(0);bits.set(4096);bits.set(8191);var p=new com.iridium126.createmanaindustry.content.allaystorm.network.StormAttackCompletedPacket(net.minecraft.world.level.Level.OVERWORLD.location(),12,9,bits.toLongArray());var codec=com.iridium126.createmanaindustry.content.allaystorm.network.StormAttackCompletedPacket.STREAM_CODEC;codec.encode(b,p);assertArrayEquals(p.ranks(),codec.decode(b).ranks());b.clear();codec.encode(b,new com.iridium126.createmanaindustry.content.allaystorm.network.StormAttackCompletedPacket(p.dimension(),12,9,new long[129]));assertThrows(Exception.class,()->codec.decode(b));}finally{b.release();}}
 @Test void gatherCodecPreservesIdentity(){var b=new net.minecraft.network.RegistryFriendlyByteBuf(io.netty.buffer.Unpooled.buffer(),net.minecraft.core.RegistryAccess.EMPTY,net.neoforged.neoforge.network.connection.ConnectionType.OTHER);try{var p=new com.iridium126.createmanaindustry.content.allaystorm.network.StormAttackGatheredPacket(net.minecraft.world.level.Level.NETHER.location(),123,42);var codec=com.iridium126.createmanaindustry.content.allaystorm.network.StormAttackGatheredPacket.STREAM_CODEC;codec.encode(b,p);assertEquals(p,codec.decode(b));assertFalse(b.isReadable());}finally{b.release();}}
 @Test void slotLayoutHasNoOverlap(){assertEquals(0,com.iridium126.createmanaindustry.client.allaystorm.StarfallGpuBuffers.completionOffset(0));assertEquals(768,com.iridium126.createmanaindustry.client.allaystorm.StarfallGpuBuffers.completionOffset(3));assertEquals(1024,com.iridium126.createmanaindustry.client.allaystorm.StarfallGpuBuffers.completionOffset(4));assertEquals(1150,com.iridium126.createmanaindustry.client.allaystorm.StarfallGpuBuffers.completionOffset(67));}
}
