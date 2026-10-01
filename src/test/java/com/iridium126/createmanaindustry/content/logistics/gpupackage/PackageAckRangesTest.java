package com.iridium126.createmanaindustry.content.logistics.gpupackage;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class PackageAckRangesTest {
    @Test void adjacentAndOutOfOrderRunsMergeWithoutAcknowledgingHoles() {
        var builder=new PackageAckRanges.Builder();
        assertTrue(builder.add(0));assertTrue(builder.add(2));assertTrue(builder.add(4));
        assertEquals(new PackageAckRanges(0,0,2,2,4,4),builder.snapshot());
        assertTrue(builder.add(1));assertEquals(new PackageAckRanges(0,2,4,4),builder.snapshot());
        assertTrue(builder.add(3));assertEquals(new PackageAckRanges(0,4),builder.snapshot());
        assertTrue(builder.add(2));assertEquals(5,builder.count());assertEquals(1,builder.runs());
    }
    @Test void boundsDoNotConsumeOverflowAndImmutableSnapshotSurvivesReuse() {
        var builder=new PackageAckRanges.Builder();long start=0x1234567800000001L;
        for(int i=0;i<PackageAckRanges.MAX_ACKS;i++)assertTrue(builder.add(start+i));
        var full=builder.snapshot();assertFalse(builder.add(start+PackageAckRanges.MAX_ACKS));assertEquals(full,builder.snapshot());
        assertTrue(builder.add(start));assertEquals(PackageAckRanges.MAX_ACKS,builder.count());
        builder.clear();assertTrue(builder.empty());assertThrows(IllegalStateException.class,builder::snapshot);
        assertTrue(builder.add(start+PackageAckRanges.MAX_ACKS));assertEquals(1,builder.count());assertEquals(2048,full.count());
        long[] input={5,9};var copy=new PackageAckRanges(input);input[0]=-10;assertEquals(new PackageAckRanges(5,9),copy);
    }
    @Test void randomSparseInputsAndCapacityFlushNeverLoseOrInventSequences() {
        var random=new Random(77123);var order=new ArrayList<Long>();var expected=new TreeSet<Long>();
        for(int i=0;i<2048;i++){long seq=0x1234567800000001L+i*2L;order.add(seq);expected.add(seq);}
        Collections.shuffle(order,random);var builder=new PackageAckRanges.Builder();var actual=new TreeSet<Long>();int flushes=0;
        for(long sequence:order) {
            if(!builder.add(sequence)){consume(builder.snapshot(),actual);builder.clear();flushes++;assertTrue(builder.add(sequence));}
            assertTrue(builder.add(sequence)); // duplicates must not use capacity
        }
        if(!builder.empty())consume(builder.snapshot(),actual);
        assertTrue(flushes>0);assertEquals(expected,actual);
    }
    static void consume(PackageAckRanges ranges,Set<Long> target) {
        for(int run=0;run<ranges.runs();run++)for(int i=0;i<ranges.length(run);i++)assertTrue(target.add(ranges.start(run)+i));
    }
    @Test void fullLongSequenceAndLowWordRolloverRemainExact() {
        var builder=new PackageAckRanges.Builder();
        for(long sequence:new long[]{0xffffffffL,0x100000000L,Long.MAX_VALUE,Long.MAX_VALUE-2,Long.MAX_VALUE-1})assertTrue(builder.add(sequence));
        assertEquals(new PackageAckRanges(0xffffffffL,0x100000000L,Long.MAX_VALUE-2,Long.MAX_VALUE),builder.snapshot());
        assertTrue(builder.add(Long.MAX_VALUE));assertEquals(5,builder.count());
    }
    @Test void noncanonicalRangesAndWorkBoundsAreRejected() {
        for(long[] endpoints:new long[][]{ {},{1},{-1,0},{3,2},{0,2,2,3},{0,2,3,4},{0,2048},{0,1023,2000,3024},{0,Long.MAX_VALUE} })
            assertThrows(IllegalArgumentException.class,()->new PackageAckRanges(endpoints));
        var builder=new PackageAckRanges.Builder();assertThrows(IllegalArgumentException.class,()->builder.add(-1));
        for(int i=0;i<PackageAckRanges.MAX_RUNS;i++)assertTrue(builder.add(i*2));
        var before=builder.snapshot();assertFalse(builder.add(1000));assertEquals(before,builder.snapshot());
        assertTrue(builder.add(1));assertEquals(PackageAckRanges.MAX_RUNS-1,builder.runs());assertTrue(builder.add(1000));
    }
}
