package com.iridium126.createmanaindustry.client.particles.packages;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PackageEnvironmentAckBatchTest {
    @Test void interleavedBodiesKeepIndependentArrivalOrderAndFullLifecycle(){
        var batch=new PackageEnvironmentAckBatch();
        int a=batch.append(19,1,-20,4.5f,31,0x123456789L,0x234567891L,0x345678912L,7,0x456789123L,true);
        int b=batch.append(2,4,0,5,7,20,2,3,8,9,false);
        int c=batch.append(19,2,90,4,4,0x123456789L,0x234567891L,0x345678912L,7,0x456789123L,false);
        var bytes=batch.upload();assertEquals(384,bytes.remaining());
        assertEquals(19,bytes.getInt(a));assertEquals(-20,bytes.getInt(a+8));assertEquals(4.5f,bytes.getFloat(a+64));
        assertEquals(0x123456789L,bytes.getLong(a+16));assertEquals(0x234567891L,bytes.getLong(a+24));
        assertEquals(0x345678912L,bytes.getLong(a+32));assertEquals(8,bytes.getInt(a+40));
        assertEquals(0x456789123L,bytes.getLong(a+44));assertEquals(7,bytes.getInt(a+52));
        assertEquals(3,bytes.getInt(a+56));assertEquals(5,bytes.getInt(b+52));
        assertEquals(1,bytes.getInt(c+52));assertEquals(0,bytes.getInt(c+56));
    }
    @Test void boundedRowsClearLinksAndAllOptionalFieldsOnReuse(){
        var batch=new PackageEnvironmentAckBatch();
        for(int i=0;i<512;i++)batch.append(0,i,0,5,7,1,1,1,0,1,true);
        assertTrue(batch.full());assertThrows(IllegalStateException.class,()->batch.append(0,513,0,5,7,1,1,1,0,1,true));
        var bytes=batch.upload();for(int i=0;i<511;i++)assertEquals(i+2,bytes.getInt(i*128+56));
        batch.fields().putLong(112,-1);batch.clear();
        assertEquals(0,batch.size());batch.append(0,1,0,5,7,0,0,0,0,0,false);
        bytes=batch.upload();assertEquals(128,bytes.remaining());assertEquals(4,bytes.getInt(52));assertEquals(0,bytes.getInt(56));
        for(int at=68;at<128;at+=4)assertEquals(0,bytes.getInt(at));
    }
}
