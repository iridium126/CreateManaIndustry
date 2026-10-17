package com.iridium126.createmanaindustry.client.particles.emitter;

import java.nio.FloatBuffer;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class EmitCommandWriterTest {
    private static EmitCommandWriter.Parameters parameters(int tag) {
        return target -> { for (int i=0;i<48;i++) target.put(tag+i); };
    }

    @Test void mixedProducersKeepTokensAndParameterIndicesInThePackedAbi() {
        var writer=new EmitCommandWriter();
        int token=0xffa12345; // includes a NaN float payload; must never be numerically converted
        assertTrue(writer.tracked(1,2,token,-1,239));
        assertTrue(writer.members(2,3,new Vec3(1,2,3),117,true));
        assertTrue(writer.parameterized(3,4,4,5,6,7,8,9,10,parameters(100)));
        assertTrue(writer.basic(4,5,new Vec3(7,8,9),12));
        assertTrue(writer.parameterized(5,6,7,8,9,10,11,12,13,parameters(200)));
        var data=writer.encode(17);
        assertEquals(5*12+2*48,data.remaining());
        assertEquals(token,Float.floatToRawIntBits(data.get(7)));
        assertEquals(-1,data.get(10));
        assertEquals(117,data.get(12+9));
        assertEquals(1,data.get(12+11));
        assertEquals(0,data.get(24+7));assertEquals(1,data.get(48+7));
        assertEquals(100,data.get(60));assertEquals(247,data.get(60+48+47));
        for(int i=0,prefix=0;i<5;i++){
            assertEquals(prefix,data.get(i*12+6));prefix+=(int)data.get(i*12+3);
        }
        assertEquals(20,writer.particles());
    }

    @Test void compactionMovesWholeRowsAndParameterOwnershipTogether() {
        var writer=new EmitCommandWriter();
        writer.basic(1,1,Vec3.ZERO,99); // drops to zero
        writer.parameterized(2,5,1,2,3,4,5,6,7,parameters(500));
        writer.tracked(3,1,0x7fa12345,67,123); // drops to zero
        writer.parameterized(4,10,8,9,10,11,12,13,14,parameters(600));
        writer.limitParticles(8,false);
        var data=writer.encode(1);
        assertEquals(2,writer.size());assertEquals(6,writer.particles());
        assertEquals(2,data.get(4));assertEquals(2,data.get(3));
        assertEquals(4,data.get(16));assertEquals(4,data.get(15));
        assertEquals(0,data.get(7));assertEquals(1,data.get(19));
        assertEquals(500,data.get(24));assertEquals(600,data.get(72));
        writer.clear();
        writer.basic(7,3,Vec3.ZERO,42);
        data=writer.encode(2);
        assertEquals(12,data.remaining());
        assertEquals(42,data.get(8));
        for(int i:new int[]{7,9,10,11})assertEquals(0,data.get(i));
    }

    @Test void wholeRingColumnIsNeverPartiallyEncoded() {
        var writer=new EmitCommandWriter();writer.ring(1,2,3,4);writer.basic(2,128,Vec3.ZERO,0);
        writer.limitParticles(128,true);
        assertEquals(1,writer.size());assertEquals(64,writer.particles());
        assertEquals(2,writer.encode(1).get(4));
    }

    @Test void fullWriterRejectsWithoutMutatingAcceptedRowsAndCanBeReused() {
        var writer=new EmitCommandWriter();
        for(int i=0;i<256;i++)assertTrue(writer.basic(i,1,Vec3.ZERO,0));
        var before=writer.encode(3);float[] copy=new float[before.remaining()];before.get(copy);
        assertFalse(writer.basic(256,1,Vec3.ZERO,0));assertTrue(writer.full());
        var after=writer.encode(3);float[] same=new float[after.remaining()];after.get(same);
        assertArrayEquals(copy,same);
        writer.clear();assertTrue(writer.basic(0,1,Vec3.ZERO,0));assertEquals(1,writer.particles());
    }

    @Test void invalidInputOrParameterLayoutCannotReachAnUpload() {
        var writer=new EmitCommandWriter();
        assertFalse(writer.basic(1,Integer.MAX_VALUE,Vec3.ZERO,0));
        assertFalse(writer.basic(1,1,new Vec3(Double.NaN,0,0),0));
        assertFalse(writer.basic(-1,1,Vec3.ZERO,0));assertEquals(0,writer.size());
        writer.parameterized(1,1,0,0,0,0,0,0,0,target->target.put(1));
        assertThrows(IllegalStateException.class,()->writer.encode(1));
    }
}
