package com.iridium126.createmanaindustry.content.logistics.gpupackage;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.ByteBuffer;
import java.util.*;
import org.junit.jupiter.api.Test;

class PackageControlBatchCodecTest {
    private record Row(int action,int index,long id,long generation,long lease,long revision) {}
    static PackageAuthorityRegion.Baseline baseline(int index,long id,long generation,long lease,long revision){
        return new PackageAuthorityRegion.Baseline(index,new PackageLease.Identity(id,generation),lease,revision,null);
    }
    static byte[] encode(int action,List<PackageAuthorityRegion.Baseline> rows){
        var out=ByteBuffer.allocate(PackageControlBatchCodec.MAX_BYTES);PackageControlBatchCodec.encode(out,action,rows);
        return Arrays.copyOf(out.array(),out.position());
    }
    static List<Row> decode(byte[] body){var rows=new ArrayList<Row>();PackageControlBatchCodec.visitValidated(ByteBuffer.wrap(body),
            (a,i,id,g,l,r)->rows.add(new Row(a,i,id,g,l,r)));return rows;}
    @Test void randomFullWidthIdentitiesAndPacketLocalFieldsAreLosslessForAllControls() {
        var random=new Random(41973);
        for(int action:new int[]{1,2,4,9})for(int trial=0;trial<20;trial++) {
            var baselines=new ArrayList<PackageAuthorityRegion.Baseline>();var expected=new ArrayList<Row>();int index=0;
            for(int i=0;i<256;i++) {
                index+=1+random.nextInt(1_000_000);
                long id=i%2==0?Long.MAX_VALUE-i:1+i;
                long generation=i%3==0?Long.MAX_VALUE-i:1;
                long lease=i%5==0?Long.MAX_VALUE-i:19;
                long revision=i%7==0?Long.MAX_VALUE-i:2;
                baselines.add(baseline(index,id,generation,lease,revision));expected.add(new Row(action,index,id,generation,lease,revision));
            }
            byte[] bytes=encode(action,baselines);assertTrue(bytes.length<=PackageControlBatchCodec.MAX_BYTES);
            assertEquals(expected,decode(bytes));assertEquals(expected,decode(bytes));
        }
        assertEquals(List.of(new Row(9,Integer.MAX_VALUE,Long.MAX_VALUE,Long.MAX_VALUE,Long.MAX_VALUE,Long.MAX_VALUE)),
                decode(encode(9,List.of(baseline(Integer.MAX_VALUE,Long.MAX_VALUE,Long.MAX_VALUE,Long.MAX_VALUE,Long.MAX_VALUE)))));
    }
    @Test void truncatedAndTrailingBodiesNeverApplyAnOtherwiseValidPrefix() {
        byte[] original=encode(9,List.of(baseline(0,11,1,19,2),baseline(7,Long.MAX_VALUE,Long.MAX_VALUE,19,3),baseline(9,1,2,Long.MAX_VALUE,4)));
        for(int size=0;size<original.length;size++) {
            var called=new ArrayList<Integer>();byte[] prefix=Arrays.copyOf(original,size);
            assertThrows(IllegalArgumentException.class,()->PackageControlBatchCodec.visitValidated(ByteBuffer.wrap(prefix),(a,i,id,g,l,r)->called.add(i)));
            assertTrue(called.isEmpty());
        }
        byte[] trailing=Arrays.copyOf(original,original.length+1);var called=new ArrayList<Integer>();
        assertThrows(IllegalArgumentException.class,()->PackageControlBatchCodec.visitValidated(ByteBuffer.wrap(trailing),(a,i,id,g,l,r)->called.add(i)));
        assertTrue(called.isEmpty());
    }
    private static void unsigned(ByteBuffer b,long value){while((value&~127L)!=0){b.put((byte)((value&127)|128));value>>>=7;}b.put((byte)value);}
    private static byte[] invalid(int mode) {
        var b=ByteBuffer.allocate(128);b.put((byte)9);unsigned(b,2);unsigned(b,0);unsigned(b,mode==4?-2L:22);b.put((byte)(mode==0?0:7));
        unsigned(b,mode==1?0:1);unsigned(b,19);unsigned(b,2);
        unsigned(b,mode==2?0:1);unsigned(b,mode==4?2:mode==3?0:2);b.put((byte)(mode==3?8:0));
        return Arrays.copyOf(b.array(),b.position());
    }
    @Test void duplicateIndicesOverflowMissingFieldsAndNoncanonicalVarlongsAreRejectedAtomically() {
        for(int mode=0;mode<5;mode++) {
            byte[] bytes=invalid(mode);var called=new ArrayList<Integer>();
            assertThrows(IllegalArgumentException.class,()->PackageControlBatchCodec.visitValidated(ByteBuffer.wrap(bytes),(a,i,id,g,l,r)->called.add(i)),"mode "+mode);
            assertTrue(called.isEmpty());
        }
        assertThrows(IllegalArgumentException.class,()->decode(new byte[]{9,(byte)0x81,0}));
        byte[] overflow=new byte[12];overflow[0]=9;Arrays.fill(overflow,1,11,(byte)128);overflow[11]=2;
        assertThrows(IllegalArgumentException.class,()->decode(overflow));
    }
    @Test void actionCapacityOutputAndProcessingBudgetsCannotCertifyPartialBatches() {
        var row=baseline(0,1,1,1,2);byte[] body=encode(9,List.of(row,baseline(1,2,1,1,2)));
        for(int action:new int[]{0,3,5,6,7,8,10,255}) {
            byte[] wrong=body.clone();wrong[0]=(byte)action;assertThrows(IllegalArgumentException.class,()->decode(wrong));
        }
        var called=new ArrayList<Integer>();assertEquals(0,PackageControlBatchCodec.visitValidated(ByteBuffer.wrap(body),1,(a,i,id,g,l,r)->called.add(i)));assertTrue(called.isEmpty());
        assertEquals(2,PackageControlBatchCodec.visitValidated(ByteBuffer.wrap(body),2,(a,i,id,g,l,r)->called.add(i)));assertEquals(List.of(0,1),called);
        var out=ByteBuffer.allocate(PackageControlBatchCodec.MAX_BYTES);
        for(var rows:List.of(List.<PackageAuthorityRegion.Baseline>of(),List.of(row,row),List.of(baseline(1,1,1,1,2),row))) {
            assertThrows(IllegalArgumentException.class,()->PackageControlBatchCodec.encode(out,9,rows));assertEquals(0,out.position());
        }
        assertThrows(IllegalArgumentException.class,()->PackageControlBatchCodec.encode(ByteBuffer.allocate(10),9,List.of(row)));
        byte[] tooMany={9,(byte)129,2};assertThrows(IllegalArgumentException.class,()->decode(tooMany));
    }
}
