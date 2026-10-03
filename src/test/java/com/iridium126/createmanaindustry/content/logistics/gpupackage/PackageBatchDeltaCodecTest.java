package com.iridium126.createmanaindustry.content.logistics.gpupackage;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.ByteBuffer;
import java.util.*;
import org.junit.jupiter.api.Test;

class PackageBatchDeltaCodecTest {
    static final PackageDeltaCodec.Quantized ZERO=new PackageDeltaCodec.Quantized(0,0,0,(short)0,(short)0,(short)0,(short)0,0);
    static List<PackageDeltaCodec.Entry> normalized(List<PackageDeltaCodec.Entry> entries) {
        var bytes=ByteBuffer.allocate(entries.size()*40+8);PackageDeltaCodec.encode(bytes,entries);bytes.flip();return PackageDeltaCodec.decode(bytes);
    }
    static ByteBuffer encoded(List<PackageDeltaCodec.Entry> entries) {
        var bytes=ByteBuffer.allocate(entries.size()*PackageBatchDeltaCodec.MAX_RECORD_BYTES+8);
        PackageBatchDeltaCodec.encode(bytes,entries);return bytes.flip();
    }
    @Test void randomMasksSparseIdsAndFullRangesMatchOriginalCodecExactly() {
        var random=new Random(346783);
        for(int n:new int[]{0,1,63,64,65,512,2048})for(int wave=0;wave<12;wave++) {
            var entries=new ArrayList<PackageDeltaCodec.Entry>();int id=65536;
            for(int i=0;i<n;i++) {
                id+=random.nextInt(4)+1;int mask=random.nextInt(5)==0?16:1+random.nextInt(15);
                var value=new PackageDeltaCodec.Quantized(random.nextInt(),random.nextInt(),random.nextInt(),
                        random.nextInt(),random.nextInt(),random.nextInt(),(short)random.nextInt(),random.nextInt());
                entries.add(new PackageDeltaCodec.Entry(id,mask,value));
            }
            var bytes=encoded(entries);assertEquals(normalized(entries),PackageBatchDeltaCodec.decode(bytes));assertFalse(bytes.hasRemaining());
        }
    }
    @Test void integerDifferencesDoNotOverflowAndMaximumIdentityRemainsExact() {
        var min=new PackageDeltaCodec.Quantized(Integer.MIN_VALUE,Integer.MAX_VALUE,Integer.MIN_VALUE,Short.MIN_VALUE,Short.MAX_VALUE,Short.MIN_VALUE,Short.MIN_VALUE,-1);
        var max=new PackageDeltaCodec.Quantized(Integer.MAX_VALUE,Integer.MIN_VALUE,Integer.MAX_VALUE,Short.MAX_VALUE,Short.MIN_VALUE,Short.MAX_VALUE,Short.MAX_VALUE,Integer.MAX_VALUE);
        var entries=List.of(new PackageDeltaCodec.Entry(0,15,min),new PackageDeltaCodec.Entry(1,16,ZERO),new PackageDeltaCodec.Entry(Integer.MAX_VALUE,15,max));
        assertEquals(entries,PackageBatchDeltaCodec.decode(encoded(entries)));
    }
    @Test void packetPredictorNeverUsesAnotherMembersOrPreviousPacketsAcknowledgedState() {
        var a=new PackageDeltaCodec.Quantized(1234,-5678,9,(short)12,(short)0,(short)-99,(short)48,3);
        var b=new PackageDeltaCodec.Quantized(1240,-5678,9,(short)12,(short)0,(short)-99,(short)48,1);
        var entries=List.of(new PackageDeltaCodec.Entry(0,15,a),new PackageDeltaCodec.Entry(1,1,b),
                new PackageDeltaCodec.Entry(2,2,b),new PackageDeltaCodec.Entry(3,16,ZERO),new PackageDeltaCodec.Entry(4,15,b));
        var decoded=PackageBatchDeltaCodec.decode(encoded(entries));assertEquals(normalized(entries),decoded);
        var unrelated=new PackageDeltaCodec.Quantized(-33,44,55,(short)-22,(short)3,(short)5,(short)99,2);
        assertEquals(PackageDeltaCodec.merge(unrelated,entries.get(1)),PackageDeltaCodec.merge(unrelated,decoded.get(1)));
        assertEquals(PackageDeltaCodec.merge(unrelated,entries.get(2)),PackageDeltaCodec.merge(unrelated,decoded.get(2)));
        // A later packet decoded first must not depend on any prior writer/predictor.
        assertEquals(normalized(List.of(entries.get(4))),PackageBatchDeltaCodec.decode(encoded(List.of(entries.get(4)))));
    }
    @Test void denseSingleAxisMotionAndZeroValuePositionsAreEncodedWithoutRedundantIdOrAxes() {
        var entries=new ArrayList<PackageDeltaCodec.Entry>();
        for(int i=0;i<512;i++)entries.add(new PackageDeltaCodec.Entry(i,1,new PackageDeltaCodec.Quantized(i*4096,20480,9000,(short)0,(short)0,(short)0,(short)0,0)));
        var bytes=encoded(entries);assertTrue(bytes.remaining()<1600);assertEquals(entries,PackageBatchDeltaCodec.decode(bytes));
        // Three complete POSITION records at the packet predictor's zero still update a
        // member's acknowledged nonzero pose; zero axis selection is not "no change".
        var zeros=List.of(new PackageDeltaCodec.Entry(0,1,ZERO),new PackageDeltaCodec.Entry(1,1,ZERO),new PackageDeltaCodec.Entry(2,1,ZERO));
        assertEquals(4,encoded(zeros).remaining());assertEquals(zeros,PackageBatchDeltaCodec.decode(encoded(zeros)));
    }
    @Test void truncatedIllegalHeadersOverflowAndAllocationAttacksAreRejected() {
        var bytes=encoded(List.of(new PackageDeltaCodec.Entry(65536,15,new PackageDeltaCodec.Quantized(Integer.MAX_VALUE,-1,23456,Short.MIN_VALUE,Short.MAX_VALUE,(short)1,(short)-7,-1))));
        byte[] data=new byte[bytes.remaining()];bytes.get(data);
        for(int length=0;length<data.length;length++) {
            int truncated=length;assertThrows(RuntimeException.class,()->PackageBatchDeltaCodec.decode(ByteBuffer.wrap(Arrays.copyOf(data,truncated))));
        }
        for(byte[] invalid:new byte[][]{
                {1,0,0,1}, {1,0,1,0}, {1,17}, {1,48}, {1,34}, {1,2,8},
                {(byte)129,0}, {(byte)128,16}, {1,0,(byte)255,(byte)255,(byte)255,(byte)255,15,1}})
            assertThrows(RuntimeException.class,()->PackageBatchDeltaCodec.decode(ByteBuffer.wrap(invalid)));
        // The signed-varint velocity field now retains values beyond the former short range.
        var wide=PackageBatchDeltaCodec.decode(ByteBuffer.wrap(new byte[]{1,2,1,(byte)128,(byte)128,4}));
        assertEquals(32768,wide.getFirst().value().vx());
        assertThrows(IllegalArgumentException.class,()->PackageBatchDeltaCodec.decode(encoded(List.of(new PackageDeltaCodec.Entry(0,1,ZERO))),0));
    }
    @Test void primitiveWriterResetsAndRejectsIncompleteOrDuplicateOutput() {
        var writer=new PackageBatchDeltaCodec.Writer();var bytes=ByteBuffer.allocate(100);
        writer.reset(bytes,1);assertThrows(IllegalStateException.class,writer::finish);
        writer.entry(0,16,0,0,0,0,0,0,0,0);writer.finish();bytes.flip();assertEquals(List.of(new PackageDeltaCodec.Entry(0,16,ZERO)),PackageBatchDeltaCodec.decode(bytes));
        bytes.clear();writer.reset(bytes,2);writer.entry(0,1,0,0,0,0,0,0,0,0);
        assertThrows(IllegalArgumentException.class,()->writer.entry(0,1,0,0,0,0,0,0,0,0));
        writer.entry(1,2,0,0,0,32768,0,0,0,0);writer.finish();bytes.flip();
        assertEquals(32768,PackageBatchDeltaCodec.decode(bytes).getLast().value().vx());
    }
}
