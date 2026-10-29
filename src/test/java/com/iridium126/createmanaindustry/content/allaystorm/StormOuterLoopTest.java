package com.iridium126.createmanaindustry.content.allaystorm;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class StormOuterLoopTest {
    @Test void matureOnlyBoundedQueueAndSmoothRate(){
        var q=new StormOuterLoop();var reserved=new BitSet();
        q.configure(99,100,99,20);assertFalse(q.offer(0,reserved));
        q.configure(100,100,100,0);assertFalse(q.offer(0,reserved));
        q.configure(1000,1000,1000,20);
        for(int i=0;i<200;i++)assertTrue(q.offer(i,reserved));
        assertFalse(q.offer(200,reserved));assertFalse(q.offer(0,reserved));
        for(int tick=0;tick<200;tick++){
            var batch=q.tick(member->true,reserved,10,80,-40,32);assertEquals(1,batch.size());
            var f=batch.getFirst();assertEquals(tick,f.member());assertTrue(reserved.get(tick));
            assertEquals(152,Math.hypot(f.x()-10,f.z()+40),.01);assertEquals(80,f.y());
            assertFalse(q.arrived(f.member(),f.cycle()+1,reserved));assertTrue(reserved.get(tick));
            assertTrue(q.arrived(f.member(),f.cycle(),reserved));assertFalse(reserved.get(tick));
            assertFalse(q.arrived(f.member(),f.cycle(),reserved));
        }
        assertEquals(0,q.waiting());assertTrue(q.flights().isEmpty());
    }
    @Test void fractionalRateDeathAndIdleNeverAccumulateBurst(){
        var q=new StormOuterLoop();var reserved=new BitSet();q.configure(100,100,100,.5);
        for(int i=0;i<1000;i++)assertTrue(q.tick(m->true,reserved,0,0,0,2).isEmpty());
        assertTrue(q.offer(4,reserved));assertTrue(q.offer(5,reserved));
        for(int i=0;i<39;i++)assertTrue(q.tick(m->true,reserved,0,0,0,2).isEmpty());
        var first=q.tick(m->true,reserved,0,0,0,2);assertEquals(1,first.size());
        q.dead(5,reserved);assertFalse(reserved.get(5));
        assertNotNull(q.dead(4,reserved));assertFalse(reserved.get(4));q.clear();assertTrue(q.flights().isEmpty());
    }
    @Test void packetRoundTripAndBounds(){
        var codec=com.iridium126.createmanaindustry.content.allaystorm.network.StormOuterPacket.STREAM_CODEC;
        var b=new net.minecraft.network.RegistryFriendlyByteBuf(io.netty.buffer.Unpooled.buffer(),net.minecraft.core.RegistryAccess.EMPTY,net.neoforged.neoforge.network.connection.ConnectionType.OTHER);
        try{
            for(int operation=0;operation<3;operation++){
                var p=com.iridium126.createmanaindustry.content.allaystorm.network.StormOuterPacket.batch(net.minecraft.world.level.Level.OVERWORLD.location(),17,operation,List.of(new StormOuterLoop.Flight(131071,Integer.MAX_VALUE,1,2,3)));
                codec.encode(b,p);var copy=codec.decode(b);assertEquals(p.epoch(),copy.epoch());assertEquals(operation,copy.operation());assertArrayEquals(p.members(),copy.members());assertArrayEquals(p.cycles(),copy.cycles());assertArrayEquals(p.targets(),copy.targets());assertFalse(b.isReadable());b.clear();
            }
            assertThrows(IllegalArgumentException.class,()->new com.iridium126.createmanaindustry.content.allaystorm.network.StormOuterPacket(net.minecraft.world.level.Level.OVERWORLD.location(),1,0,new int[1025],new int[1025],new float[3075]));
        }finally{b.release();}
    }
}
