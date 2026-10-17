package com.iridium126.createmanaindustry.client.particles.emitter;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DirectParticleBatchTest {
    @Test void nativeTickAdmissionKeepsTheUnbornSuffixAcrossFrameCommit(){
        var batch=new DirectParticleBatch(true);
        assertTrue(batch.offer(1,0,0,0,0,0,1,-1));
        assertEquals(0,batch.ready());assertEquals(0,batch.encode(0,0).remaining());
        batch.tick();assertEquals(1,batch.ready());
        assertTrue(batch.offer(2,0,0,0,0,0,2,0));
        assertEquals(12,batch.encode(0,0).remaining());
        assertEquals(-1,Float.floatToRawIntBits(batch.encode(0,0).get(7)));
        batch.commit();assertEquals(1,batch.count());assertEquals(0,batch.ready());
        batch.tick();assertEquals(2,batch.encode(0,0).get(0));
        batch.commit();assertEquals(0,batch.count());
    }
    @Test void admissionIsIndependentOfSharedCommandLimitAndRetiredOnlyOnCommit() {
        var batch=new DirectParticleBatch();
        for(int i=0;i<DirectParticleBatch.LIMIT;i++)assertTrue(batch.offer(i,2,3,.1,i==0?0:.2,.3,0xff123456));
        assertFalse(batch.offer(0,0,0,0,0,0,0));
        var first=batch.encode(7,1);
        assertEquals(DirectParticleBatch.LIMIT*12,first.remaining());
        assertEquals(0x123456,Float.floatToRawIntBits(first.get(11)));
        assertEquals(0x80123456,Float.floatToRawIntBits(first.get(23)));
        assertEquals(2,first.get(8));
        assertEquals(first.get(5),batch.encode(7,1).get(5));
        assertEquals(DirectParticleBatch.LIMIT,batch.count());
        int[] replayed={0};
        batch.replay((x,y,z,vx,vy,vz,color)->{assertEquals(replayed[0]++,x);assertEquals(.1,vx);assertEquals(0xff123456,color);});
        assertEquals(DirectParticleBatch.LIMIT,replayed[0]);
        assertEquals(0,batch.count());
        batch.replay((x,y,z,vx,vy,vz,color)->fail("Duplicate replay"));
        assertTrue(batch.offer(0,0,0,0,0,0,0));batch.clear();assertEquals(0,batch.count());
    }
}
