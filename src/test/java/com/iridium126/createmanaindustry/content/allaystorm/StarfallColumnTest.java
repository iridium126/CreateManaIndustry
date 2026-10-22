package com.iridium126.createmanaindustry.content.allaystorm;

import com.iridium126.createmanaindustry.content.allaystorm.network.*;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.DecoderException;
import java.util.*;
import java.util.stream.IntStream;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.connection.ConnectionType;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class StarfallColumnTest {
    private static ClientboundStormColumnPacket packet(int id) {
        return new ClientboundStormColumnPacket(Level.OVERWORLD.location(),87,id,false,123,0,8,32,36.34f,80,1500,64.02f,-2300,120,1500,-2300,32,IntStream.range(0,64).toArray());
    }
    @Test void sixtyFourSlotsRemainIndependentOfFourMajorAttacks(){
        var slots=new StormWaveSlots(68);
        for(int i=0;i<64;i++){var update=slots.apply(packet(i+1).snapshot(),0);assertNotNull(update);assertEquals(4+i,update.slot());}
        assertNull(slots.apply(packet(65).snapshot(),0));
        var legacy=packet(100).snapshot();
        var major=new ClientboundStormWavePacket(100,87,0,1,1,5,1,64,0,4,10,70,0,20,0,4,new int[]{0},new float[]{0,20,0,0,10,0},new float[]{0,0,0,3.5f});
        assertEquals(0,slots.apply(major,0).slot());
        assertEquals(4,slots.apply(packet(1).aborted().snapshot(),1).slot());
        assertEquals(4,slots.apply(packet(65).snapshot(),1).slot());
        assertNull(slots.apply(packet(1).snapshot().copy(1,2,32,80,new int[0]),1));
    }
    @Test void abortFadeExpiresWithoutEvictingLiveColumns(){
        var slots=new StormWaveSlots(68);slots.apply(packet(1).snapshot(),0);slots.apply(packet(1).aborted().snapshot(),1);
        assertNotNull(slots.packet(4));slots.retire(1.26f);assertNull(slots.packet(4));
    }
    @Test void extendedAssignmentsDoNotAliasThirtyTwoBitMasksOrLegacySlots(){
        Set<Integer> words=new HashSet<>();
        for(int slot=4;slot<68;slot++)for(int rank=0;rank<64;rank++){
            int word=StarfallColumnRules.assignment(slot,rank);assertTrue(words.add(word));assertEquals(slot,StarfallColumnRules.slot(word));assertEquals(rank,(word&0x3fffffff)>>>8);
        }
        for(int slot=0;slot<4;slot++)assertEquals(slot,StarfallColumnRules.slot(AllayStarfall.pack(slot,31,4095)));
    }
    @Test void exactPlayerXZIsPreferred(){
        assertEquals(new Vec3(3,0,4),StarfallColumnRules.landing(3,4,0,0,32,1,List.of(),(x,z)->true));
    }
    @Test void overlapSearchStartsOutwardAndKeepsEightBlockSpacing(){
        var occupied=List.of(new Vec3(3,0,4));
        var p=StarfallColumnRules.landing(3,4,0,0,32,1,occupied,(x,z)->true);
        assertNotNull(p);assertEquals(8,p.distanceTo(occupied.getFirst()),1e-6);assertEquals(7.8,p.x,1e-6);assertEquals(10.4,p.z,1e-6);
        assertEquals(p,StarfallColumnRules.landing(3,4,0,0,32,1,occupied,(x,z)->true));
    }
    @Test void centerSeedAndEdgeSearchAreStable(){
        var p=StarfallColumnRules.landing(0,0,0,0,32,17,List.of(Vec3.ZERO),(x,z)->true);
        assertEquals(p,StarfallColumnRules.landing(0,0,0,0,32,17,List.of(Vec3.ZERO),(x,z)->true));
        var edge=StarfallColumnRules.landing(32,0,0,0,32,17,List.of(new Vec3(32,0,0)),(x,z)->true);
        assertNotNull(edge);assertTrue(edge.horizontalDistance()<=32+1e-6);assertTrue(edge.distanceTo(new Vec3(32,0,0))>=8-1e-6);
    }
    @Test void noTerrainOrNoSpaceSkips(){
        assertNull(StarfallColumnRules.landing(0,0,0,0,32,1,List.of(),(x,z)->false));
        assertNull(StarfallColumnRules.landing(0,0,0,0,2,1,List.of(Vec3.ZERO),(x,z)->true));
    }
    @Test void sixtyFourLivingUnreservedMembersOrNothing(){
        var dead=new BitSet();dead.set(0,16);var reserved=new BitSet();reserved.set(16,32);
        int[] first=AllayStarfall.selectExact(160,dead,reserved,64,1);assertEquals(64,first.length);for(int m:first){assertFalse(dead.get(m));assertFalse(reserved.get(m));reserved.set(m);}
        int[] second=AllayStarfall.selectExact(160,dead,reserved,64,2);assertEquals(64,second.length);for(int m:second)assertFalse(reserved.get(m));
        assertEquals(63,AllayStarfall.selectExact(63,new BitSet(),new BitSet(),64,1).length);
    }
    @Test void bloomUsesExactParabolaAndFourBlockRadius(){
        Vec3 origin=new Vec3(1500,64,-2300);
        for(int rank=0;rank<64;rank++)for(int i=0;i<=300;i++){
            var p=StarfallColumnRules.bloom(origin,rank,123,i/200.);double r=p.subtract(origin).horizontalDistance();
            assertTrue(r<=4.00000001);assertEquals(.0625*r*r,p.y-origin.y,1e-9);
        }
        assertEquals(1,StarfallColumnRules.height(4));
    }
    @Test void everyMemberHasItsOwnSurfaceDirectionAndLandingPoint(){
        Set<Long> directions=new HashSet<>();var feet=new ArrayList<Vec3>();
        for(int rank=0;rank<64;rank++){
            Vec3 tip=StarfallColumnRules.bloom(Vec3.ZERO,rank,123,1.5);
            assertTrue(directions.add(Math.round(Math.atan2(tip.z,tip.x)*1e6)),"Members share a bloom trajectory");
            Vec3 foot=StarfallColumnRules.bloom(Vec3.ZERO,rank,123,0);
            for(Vec3 previous:feet)assertTrue(foot.distanceTo(previous)>.24,"Touchdown points collapse together");
            feet.add(foot);
            if(rank>0)assertTrue(StarfallColumnRules.delay(rank)>StarfallColumnRules.delay(rank-1),"Arrival timing still groups eight members");
        }
        assertEquals(64,directions.size());
    }
    @Test void normalSeamsHaveContinuousPositionAndVelocity(){
        Vec3 origin=new Vec3(1500,120,-2300),landing=new Vec3(1500,64.02,-2300);double e=1e-4;
        for(int rank=0;rank<64;rank++){
            var gather=StarfallColumnRules.gather(origin,rank,123);var root=StarfallColumnRules.bloom(landing,rank,123,0);
            assertEquals(0,gather.distanceTo(StarfallColumnRules.descent(origin,landing,rank,123,0)),1e-10);
            assertEquals(0,root.distanceTo(StarfallColumnRules.descent(origin,landing,rank,123,1)),1e-10);
            Vec3 tangent=StarfallColumnRules.descent(origin,landing,rank,123,e).subtract(gather).scale(1/(20*e));
            Vec3 orbitTangent=StarfallColumnRules.gather(origin,rank,123,20*e).subtract(gather).scale(1/(20*e));
            assertTrue(tangent.distanceTo(orbitTangent)<.01,"Orbit-to-descent tangent seam");
            assertTrue(root.distanceTo(StarfallColumnRules.descent(origin,landing,rank,123,1-e))/e<.01);
            assertEquals(6,StarfallColumnRules.bloom(landing,rank,123,e).distanceTo(root)/e,.001);
            var tip=StarfallColumnRules.bloom(landing,rank,123,1.5);
            assertTrue(tip.distanceTo(StarfallColumnRules.bloom(landing,rank,123,1.5-e))/e<.001);
        }
        var velocity=new Vec3(2,1,-3);var initial=StarfallColumnRules.bridge(origin,velocity,landing,0,20);
        assertEquals(origin,initial);assertTrue(StarfallColumnRules.bridge(origin,velocity,landing,e,20).subtract(initial).scale(1/e).distanceTo(velocity)<.001);
        assertEquals(landing,StarfallColumnRules.bridge(origin,velocity,landing,20,20));
    }
    @Test void bloomReferenceStaysWithinSixBlocksPerSecond(){
        var top=new Vec3(0,120,0);var land=new Vec3(0,64.02,0);double dt=.001,duration=StarfallColumnRules.descentSeconds(124-64.02);
        for(int rank=0;rank<64;rank++)for(double t=0;t<1.5;t+=dt)
            assertTrue(StarfallColumnRules.bloom(land,rank,123,t).distanceTo(StarfallColumnRules.bloom(land,rank,123,t+dt))/dt<6);
        // Descent fraction is geometry, not elapsed time; production arc-speed validation lives in StarfallColumnGpuValidation.
    }
    @Test void bloomUsesSixMetresOfArcPerSecondAndTransitionsImmediately(){
        for(int rank=0;rank<64;rank++){
            double duration=StarfallColumnRules.bloomSeconds(rank);assertTrue(duration>.4&&duration<.7);
            double initial=StarfallColumnRules.arc(StarfallColumnRules.root(rank));
            for(int i=0;i<100;i++){double t=duration*i/100;var point=StarfallColumnRules.bloom(Vec3.ZERO,rank,123,t);assertEquals(6*t,StarfallColumnRules.arc(point.horizontalDistance())-initial,1e-7);}
            assertEquals(4,StarfallColumnRules.bloom(Vec3.ZERO,rank,123,duration).horizontalDistance(),1e-7);
        }
    }
    @Test void renderClockInterpolatesBetweenGameTicksAndWraps(){
        float previous=-1;for(int frame=0;frame<240;frame++){double tick=frame/12.;long whole=1000+(long)tick;float partial=(float)(tick-Math.floor(tick));float time=StarfallColumnRules.renderSeconds(whole,partial);assertTrue(time>previous);previous=time;}
        assertEquals(50.025f,StarfallColumnRules.renderSeconds(1000,.5f));
        assertEquals(0,StarfallColumnRules.renderSeconds(1<<21,0));
        assertTrue(AllayStarfall.elapsed(StarfallColumnRules.renderSeconds(1<<21,.5f),StarfallColumnRules.renderSeconds((1<<21)-1,.5f))>0);
    }
    @Test void gatheringOrbitsAtAStableHeightAndHasVerticalClearance(){
        for(int rank=0;rank<64;rank++){
            Vec3 p=StarfallColumnRules.gather(Vec3.ZERO,rank,123,0),q=StarfallColumnRules.gather(Vec3.ZERO,rank,123,.1);
            assertEquals(p.y,q.y);assertEquals(.3,p.distanceTo(q),.002);
            if(rank<60)assertEquals(.7,StarfallColumnRules.gather(Vec3.ZERO,rank+4,123).y-p.y,1e-9);
        }
    }
    @Test void packetRoundtripPreservesDimensionFrozenPlanAndRoster(){
        var p=packet(1);var b=new RegistryFriendlyByteBuf(Unpooled.buffer(),RegistryAccess.EMPTY,ConnectionType.OTHER);
        try{ClientboundStormColumnPacket.STREAM_CODEC.encode(b,p);var q=ClientboundStormColumnPacket.STREAM_CODEC.decode(b);assertEquals(p.dimension(),q.dimension());assertEquals(p.epoch(),q.epoch());assertEquals(p.x(),q.x());assertEquals(p.gatherY(),q.gatherY());assertEquals(p.bloomEnd(),q.bloomEnd());assertArrayEquals(p.members(),q.members());assertFalse(b.isReadable());}finally{b.release();}
    }
    @Test void abortIsScopedAndCompact(){
        var p=packet(1).aborted();var b=new RegistryFriendlyByteBuf(Unpooled.buffer(),RegistryAccess.EMPTY,ConnectionType.OTHER);
        try{ClientboundStormColumnPacket.STREAM_CODEC.encode(b,p);assertTrue(b.readableBytes()<80);var q=ClientboundStormColumnPacket.STREAM_CODEC.decode(b);assertTrue(q.abort());assertEquals(p.dimension(),q.dimension());assertEquals(p.epoch(),q.epoch());assertEquals(p.id(),q.id());assertFalse(b.isReadable());}finally{b.release();}
    }
    @Test void malformedColumnIsRejected(){
        for(int mode=0;mode<3;mode++){
            var p=packet(1);int[] roster=p.members().clone();if(mode==0)roster[1]=roster[0];
            var invalid=new ClientboundStormColumnPacket(p.dimension(),p.epoch(),1,false,p.seed(),p.start(),p.assemble(),p.touch(),p.bloomEnd(),p.end(),mode==1?Float.NaN:p.x(),p.y(),p.z(),mode==2?60:p.gatherY(),p.centerX(),p.centerZ(),p.radius(),roster);
            var b=new RegistryFriendlyByteBuf(Unpooled.buffer(),RegistryAccess.EMPTY,ConnectionType.OTHER);
            try{ClientboundStormColumnPacket.STREAM_CODEC.encode(b,invalid);assertThrows(DecoderException.class,()->ClientboundStormColumnPacket.STREAM_CODEC.decode(b));}finally{b.release();}
        }
    }
}
