package com.iridium126.createmanaindustry.client.particles.packages;

import java.nio.*;
import java.util.ArrayList;
import com.iridium126.createmanaindustry.content.logistics.gpupackage.*;
import com.iridium126.createmanaindustry.content.logistics.gpupackage.network.ServerboundPackagePacket;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PackageEnvironmentUplinkTest {
    private static ByteBuffer event(int id) {
        var b=ByteBuffer.allocate(1024).order(ByteOrder.LITTLE_ENDIAN);
        b.putLong(0,id).putLong(8,7).putLong(16,3).putInt(24,1).putInt(40,18).putLong(56,9);
        b.putInt(64,2).putLong(80,100).putFloat(92,5).putFloat(96,1).putFloat(100,2).putFloat(104,3).putInt(108,1);
        return b;
    }
    @Test void interleavedRegionsBatchAt24AndPreserveOrderAndRebasing() {
        var sent=new ArrayList<ServerboundPackagePacket>();var queue=new PackageEnvironmentUplink(8,sent::add);
        var a=new PackageRegion(-2,3,4);var b=new PackageRegion(7,8,9);
        for(int i=1;i<=49;i++) {
            var raw=event(i);queue.append(raw,64,128,256,a,10,11);
            assertEquals(0,raw.position());assertEquals(1,raw.getFloat(96));
            if(i%4==0)queue.append(event(100+i),64,128,256,b,20,21);
        }
        queue.flush();assertEquals(4,sent.size());
        var ae=sent.stream().filter(p->p.region().equals(a)).toList();
        assertEquals(java.util.List.of(24576,24576,1024),ae.stream().map(p->p.changesView().remaining()).toList());
        int next=1;
        for(var packet:ae)for(var item:PackageEnvironmentBatchCodec.decode(packet.changesView())) {
            assertEquals(next++,item.identity().id());assertEquals(10,packet.epoch());assertEquals(11,packet.revision());
            var sample=item.samples().getFirst();assertEquals(65,a.originX()+sample.px());
            assertEquals(66,a.originX()+sample.x());assertEquals(130,a.originY()+sample.py());
        }
        assertEquals(50,next);assertEquals(12,PackageEnvironmentBatchCodec.decode(sent.stream().filter(p->p.region().equals(b)).findFirst().orElseThrow().changesView()).size());
        queue.flush();assertEquals(4,sent.size());
    }
    @Test void routeReplacementCapacityAndWorldClearNeverMixEpochs() {
        var sent=new ArrayList<ServerboundPackagePacket>();var queue=new PackageEnvironmentUplink(1,sent::add);
        var a=new PackageRegion(0,0,0);
        queue.append(event(1),0,0,0,a,1,1);queue.append(event(2),0,0,0,a,2,3);
        assertEquals(1,sent.size());assertEquals(1,sent.getFirst().epoch());
        for(int i=1;i<20;i++)queue.append(event(i+2),0,0,0,new PackageRegion(i,0,0),i+2,1);
        queue.flush();assertEquals(21,sent.size());
        queue.append(event(500),0,0,0,a,25,1);queue.clear();queue.flush();assertEquals(21,sent.size());
        queue.append(event(501),0,0,0,a,26,1);queue.flush();assertEquals(26,sent.getLast().epoch());
        assertEquals(501,PackageEnvironmentBatchCodec.decode(sent.getLast().changesView()).getFirst().identity().id());
    }
    @Test void transportFailureClearsStagingWhileJournalRemainsResponsibleForRetry() {
        int[] attempts={0};var sent=new ArrayList<ServerboundPackagePacket>();
        var queue=new PackageEnvironmentUplink(1,p->{if(attempts[0]++==0)throw new IllegalStateException("transport");sent.add(p);});
        var region=new PackageRegion(0,0,0);queue.append(event(1),0,0,0,region,1,1);
        assertThrows(IllegalStateException.class,queue::flush);queue.flush();assertTrue(sent.isEmpty());
        queue.append(event(1),0,0,0,region,1,1);queue.flush();assertEquals(1,sent.size());
    }
}
