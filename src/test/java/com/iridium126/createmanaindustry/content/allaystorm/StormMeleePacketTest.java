package com.iridium126.createmanaindustry.content.allaystorm;

import com.iridium126.createmanaindustry.content.allaystorm.network.*;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.DecoderException;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.connection.ConnectionType;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class StormMeleePacketTest {
    private final ResourceLocation dimension=ResourceLocation.parse("minecraft:overworld");
    private RegistryFriendlyByteBuf buffer(){return new RegistryFriendlyByteBuf(Unpooled.buffer(),RegistryAccess.EMPTY,ConnectionType.OTHER);}
    @Test void candidateRoundTripContainsOnlyIdentityTimeAndPosition() {
        var p=new ServerboundStormHitPacket(dimension,Long.MIN_VALUE,131071,9000,100000.123,128.5,-100000.321);
        var b=buffer();try{ServerboundStormHitPacket.STREAM_CODEC.encode(b,p);assertEquals(p,ServerboundStormHitPacket.STREAM_CODEC.decode(b));assertFalse(b.isReadable());}finally{b.release();}
        assertEquals(7,ServerboundStormHitPacket.class.getRecordComponents().length);
    }
    @Test void damageAndKnockbackAreNotByteClamped() {
        var p=new ClientboundStormDamagePacket(3,250.125f,8.25f,-4.125f,240,true,8,true,true,10,100000.123,80,-100000.321,dimension,77);
        var b=buffer();try{ClientboundStormDamagePacket.STREAM_CODEC.encode(b,p);assertEquals(p,ClientboundStormDamagePacket.STREAM_CODEC.decode(b));assertFalse(b.isReadable());}finally{b.release();}
    }
    @Test void invalidCandidateDoesNotDecode() {
        for(var p:new ServerboundStormHitPacket[]{new ServerboundStormHitPacket(dimension,1,-1,1,0,0,0),new ServerboundStormHitPacket(dimension,1,0,1,Double.NaN,0,0)}){
            var b=buffer();try{ServerboundStormHitPacket.STREAM_CODEC.encode(b,p);assertThrows(DecoderException.class,()->ServerboundStormHitPacket.STREAM_CODEC.decode(b));}finally{b.release();}
        }
    }
    @Test void timestampsCannotOverflowAndReplayOrFloodIsRejected() {
        assertTrue(StormMelee.timely(100,60));assertTrue(StormMelee.timely(100,102));
        for(long t:new long[]{59,103,Long.MIN_VALUE,Long.MAX_VALUE})assertFalse(StormMelee.timely(100,t));
        var attempts=new StormMelee.Attempts();assertTrue(attempts.claim(100,100));
        assertFalse(attempts.claim(100,101));assertFalse(attempts.claim(101,100));
        for(int tick=101;tick<112;tick++)assertTrue(attempts.claim(tick,tick));
        assertFalse(attempts.claim(112,112));assertTrue(attempts.claim(120,120));
    }
    @Test void historicalEyeUsesSampleInsteadOfArrival() {
        var eyes=new AllayStarfall.PlayerTrail();for(int i=0;i<50;i++)eyes.record(i,i,64,0);
        assertEquals(20,eyes.positionAt(20).x);assertNull(eyes.positionAt(0));assertNotNull(eyes.positionAt(51));assertNull(eyes.positionAt(52));
    }
}
