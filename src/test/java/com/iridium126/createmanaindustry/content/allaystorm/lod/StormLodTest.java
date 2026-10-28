package com.iridium126.createmanaindustry.content.allaystorm.lod;

import java.util.BitSet;
import java.util.Random;
import com.iridium126.createmanaindustry.content.allaystorm.*;
import com.iridium126.createmanaindustry.content.allaystorm.network.StormLodPacket;
import com.iridium126.createmanaindustry.content.allaystorm.rift.StormVisualDefinition;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class StormLodTest {
    private RegistryFriendlyByteBuf buffer(){return new RegistryFriendlyByteBuf(Unpooled.buffer(),RegistryAccess.EMPTY,net.neoforged.neoforge.network.connection.ConnectionType.OTHER);}
    @Test void masksRoundTripEmptySparseDenseAndMaximumIdentity(){
        for(int stride:new int[]{0,1,3,1000,131071}){
            BitSet mask=new BitSet();if(stride>0)for(int id=0;id<131072;id+=stride)mask.set(id);
            mask.set(131071);var b=buffer();try{
                StormLodMask.write(b,mask);assertTrue(b.readableBytes()<=16388);
                assertEquals(mask,StormLodMask.read(b));assertFalse(b.isReadable());
            }finally{b.release();}
        }
        var random=new Random(81);BitSet mask=new BitSet();for(int i=0;i<10000;i++)mask.set(random.nextInt(131072));
        var b=buffer();try{StormLodMask.write(b,mask);assertEquals(mask,StormLodMask.read(b));}finally{b.release();}
    }
    @Test void sparseEncodingWinsForSingleDeathAndRejectsInvalidIndices(){
        var b=buffer();try{
            BitSet dead=new BitSet();dead.set(131071);StormLodMask.write(b,dead);assertTrue(b.readableBytes()<=5);assertFalse(b.getBoolean(0));assertEquals(dead,StormLodMask.read(b));
            b.clear();b.writeBoolean(false);b.writeVarInt(1);b.writeVarInt(131073);assertThrows(IllegalArgumentException.class,()->StormLodMask.read(b));
            b.clear();b.writeBoolean(false);b.writeVarInt(2);b.writeVarInt(1);b.writeVarInt(0);assertThrows(IllegalArgumentException.class,()->StormLodMask.read(b));
        }finally{b.release();}
    }
    @Test void subscriptionInitRetainsCanonicalSamplesAndFrozenLawWhileDeltasStayConstantSize(){
        var id=new StormIdentity(ResourceLocation.withDefaultNamespace("overworld"),91);
        var previous=new StormCenterSample(29000000.125,150,-29000000.25,65536,100);
        var current=new StormCenterSample(29000001.125,150,-29000000.25,131072,120);
        var law=new StormVisualDefinition(16777215,0,2,64,128);
        BitSet dead=new BitSet();dead.set(3);dead.set(131071);
        var original=new StormLodPacket(id,7,StormLodPacket.INIT,8192,law,previous,current,dead);dead.clear();assertEquals(2,original.deaths().cardinality());
        for(var p:new StormLodPacket[]{original,new StormLodPacket(id,7,StormLodPacket.UPDATE,0,null,null,current,new BitSet()),new StormLodPacket(id,7,StormLodPacket.UPDATE,0,null,null,null,original.deaths()),new StormLodPacket(id,7,StormLodPacket.REMOVE,0,null,null,null,new BitSet())}){
            var b=buffer();try{
                StormLodPacket.STREAM_CODEC.encode(b,p);if(p.action()==StormLodPacket.UPDATE)assertTrue(b.readableBytes()<100);
                assertEquals(p,StormLodPacket.STREAM_CODEC.decode(b));assertFalse(b.isReadable());
            }finally{b.release();}
        }
    }
    @Test void staleSubscriptionsCannotReviveRemovedOrReplacedStates(){
        var stream=new StormLodStream();
        assertTrue(stream.initialize(1));assertTrue(stream.accepts(1));
        stream.clear();assertFalse(stream.initialize(1));assertFalse(stream.accepts(1));
        assertTrue(stream.initialize(2));assertFalse(stream.initialize(1));assertFalse(stream.accepts(1));
        assertTrue(stream.accepts(2));stream.clear(); // dimension change
        assertFalse(stream.initialize(2));assertTrue(stream.initialize(3));
        stream.disconnect();assertTrue(stream.initialize(1));
    }
    @Test void rangesRespectBothBudgetsAndSubscriptionHysteresis(){
        assertEquals(120,StormLodRules.near(96));assertEquals(280,StormLodRules.near(256));
        assertEquals(8192,StormLodRules.limit(99999,99999));assertEquals(0,StormLodRules.limit(8192,0));assertEquals(512,StormLodRules.limit(512,8192));
        assertEquals(1024,StormLodRules.middle(1024,120,8192));assertEquals(280,StormLodRules.middle(120,280,8192));assertEquals(200,StormLodRules.middle(1024,120,200));
        assertTrue(StormLodRules.subscribed(false,8200,80,8192));assertFalse(StormLodRules.subscribed(false,8300,80,8192));
        assertTrue(StormLodRules.subscribed(true,8300,80,8192));assertFalse(StormLodRules.subscribed(true,0,80,0));
    }
}
