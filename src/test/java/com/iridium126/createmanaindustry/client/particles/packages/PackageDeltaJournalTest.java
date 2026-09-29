package com.iridium126.createmanaindustry.client.particles.packages;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import com.iridium126.createmanaindustry.content.logistics.gpupackage.PackageDeltaCodec;
import org.junit.jupiter.api.Test;

class PackageDeltaJournalTest {
    static ByteBuffer metadata(int count) {
        var data=ByteBuffer.allocate(count*32).order(ByteOrder.nativeOrder());
        for(int i=0;i<count;i++)data.putLong(i*32,0x1234567800000001L+i).putLong(i*32+8,0x2345678900000001L)
                .putInt(i*32+16,i).putInt(i*32+20,i*3+7).putInt(i*32+24,1);
        return data;
    }
    static ByteBuffer records(int first,int count,boolean release) {
        var data=ByteBuffer.allocate(count*64).order(ByteOrder.nativeOrder());
        for(int j=0;j<count;j++) {
            int i=first+j,p=j*64;
            data.putLong(p,0x1234567800000001L+i).putLong(p+8,0x2345678900000001L)
                    .putInt(p+16,i).putInt(p+20,i*3+7).putInt(p+24,release?0:15).putInt(p+28,release?1:0)
                    .putInt(p+32,Integer.MIN_VALUE+i).putInt(p+36,Integer.MAX_VALUE-i).putInt(p+40,-5)
                    .putInt(p+44,1).putInt(p+48,Short.MIN_VALUE).putInt(p+52,Short.MAX_VALUE).putInt(p+56,-2).putInt(p+60,-89);
        }
        return data;
    }
    private static void workers(ArrayDeque<Runnable> tasks,PackageDeltaJournal journal){journal.prepare();while(!tasks.isEmpty())tasks.remove().run();journal.prepare();}
    @Test void workerSortsAndEncodesWithoutChangingRetainedGpuAckRecords() {
        var tasks=new ArrayDeque<Runnable>();var journal=new PackageDeltaJournal(65,new PackageDeltaJournal.Encoder(tasks::add,4));journal.append(metadata(65),65);
        var input=records(0,65,false);byte[] first=new byte[64];input.get(0,first);byte[] last=new byte[64];input.get(64*64,last);input.put(0,last).put(64*64,first);
        assertTrue(journal.offer(9,input,1));assertEquals(0,journal.sendReady((s,b)->fail("unfinished worker was read"),1,4));
        workers(tasks,journal);List<PackageDeltaCodec.Entry> wire=new ArrayList<>();
        assertEquals(1,journal.sendReady((s,b)->{wire.addAll(PackageDeltaCodec.decode(b));return true;},10,4));
        assertEquals(65,wire.size());assertEquals(7,wire.getFirst().id());assertEquals(199,wire.getLast().id());
        assertEquals(Integer.MIN_VALUE,wire.getFirst().value().x());assertEquals(Short.MIN_VALUE,wire.getFirst().value().vx());
        var ack=journal.acknowledge(0);assertNotNull(ack);assertEquals(64,ack.records().getInt(16));assertEquals(9,ack.stamp());
        assertEquals(1,ack.queuedNanos());assertEquals(10,ack.sentNanos());journal.confirm(0);assertEquals(0,journal.pending());
        assertNull(journal.acknowledge(0));assertEquals(1,journal.ackedPackets());
    }
    @Test void fourDelayedFragmentsFillCapacityWithoutOverwritingAndOnlyAckFreesIt() {
        var tasks=new ArrayDeque<Runnable>();var encoder=new PackageDeltaJournal.Encoder(tasks::add,4);
        var journal=new PackageDeltaJournal(5,encoder);journal.append(metadata(5),5);
        for(int i=0;i<4;i++)assertTrue(journal.offer(i+1,records(i,1,false),0));
        for(int frame=0;frame<12;frame++) {
            journal.prepare();assertFalse(journal.offer(5,records(4,1,true),0));assertEquals(4,journal.pending());assertEquals(4,tasks.size());
        }
        assertFalse(journal.sent(0));assertNull(journal.acknowledge(0));workers(tasks,journal);
        List<Long> sent=new ArrayList<>();assertEquals(0,journal.sendReady((s,b)->false,1,4));assertEquals(4,journal.sendReady((s,b)->{sent.add(s);return true;},2,4));
        assertEquals(List.of(0L,1L,2L,3L),sent);assertTrue(journal.sent(0));assertFalse(journal.sent(4));
        journal.confirm(1);assertFalse(journal.offer(5,records(4,1,true),3)); // FIFO head still owns its slot
        journal.confirm(0);assertTrue(journal.offer(5,records(4,1,true),3));workers(tasks,journal);
        assertEquals(1,journal.sendReady((s,b)->{assertEquals(4,s);var release=PackageDeltaCodec.decode(b);assertEquals(16,release.getFirst().mask());return true;},4,4));
        assertNotNull(journal.acknowledge(4));assertNull(journal.acknowledge(0));
    }
    @Test void partialTransportDoesNotSkipAnUnsentSequence() {
        var tasks=new ArrayDeque<Runnable>();var journal=new PackageDeltaJournal(1025,new PackageDeltaJournal.Encoder(tasks::add,4));journal.append(metadata(1025),1025);
        assertTrue(journal.offer(1,records(0,1025,false),0));workers(tasks,journal);List<Long> attempts=new ArrayList<>();
        assertEquals(1,journal.sendReady((s,b)->{attempts.add(s);return s==0;},1,8));assertEquals(List.of(0L,1L),attempts);
        assertFalse(journal.sent(1));assertNull(journal.acknowledge(1));attempts.clear();
        assertEquals(2,journal.sendReady((s,b)->{attempts.add(s);return true;},2,8));assertEquals(List.of(1L,2L),attempts);
    }
    @Test void encoderFailureNeverPublishesPartOfAFragmentAndCandidateReservationRollsBack() {
        var tasks=new ArrayDeque<Runnable>();var journal=new PackageDeltaJournal(1025,new PackageDeltaJournal.Encoder(tasks::add,4));journal.append(metadata(1025),1025);
        var duplicate=records(0,2,false);duplicate.putInt(64+16,0);
        assertThrows(IllegalArgumentException.class,()->journal.offer(1,duplicate,0));assertEquals(0,journal.pending());
        var corrupt=records(0,1025,false);corrupt.putLong(1024*64,99);assertTrue(journal.offer(2,corrupt,0));journal.prepare();tasks.remove().run();
        assertThrows(java.util.concurrent.CompletionException.class,journal::prepare);
        assertEquals(0,journal.sendReady((s,b)->fail("partially validated group sent"),1,8));
    }
    @Test void staleWorkersRetainGlobalBudgetAfterEpochClose() {
        var tasks=new ArrayDeque<Runnable>();var encoder=new PackageDeltaJournal.Encoder(tasks::add,1);
        var previous=new PackageDeltaJournal(1,encoder);previous.append(metadata(1),1);previous.offer(1,records(0,1,false),0);previous.prepare();previous.close();
        var next=new PackageDeltaJournal(1,encoder);next.append(metadata(1),1);next.offer(1,records(0,1,false),0);next.prepare();assertEquals(1,tasks.size());assertEquals(1,encoder.activeTasks());
        tasks.remove().run();next.prepare();assertEquals(1,tasks.size());tasks.remove().run();next.prepare();
        assertEquals(1,next.sendReady((s,b)->true,1,4));assertEquals(0,encoder.activeTasks());
    }
    @Test void invalidIdentityAppendIsAtomicAndTimeBudgetReportsQueuedDelayOnly() {
        var journal=new PackageDeltaJournal(3,new PackageDeltaJournal.Encoder(Runnable::run,4));
        var invalid=metadata(2);invalid.putInt(32+20,7);assertThrows(IllegalArgumentException.class,()->journal.append(invalid,2));
        journal.append(metadata(2),2);assertEquals(0,journal.candidate(7,0x1234567800000001L,0x2345678900000001L));
        assertEquals(-1,journal.candidate(7,0x1234567800000001L,1));assertTrue(journal.offer(1,records(0,2,false),0));
        assertTrue(journal.preparationExpired(101,100));journal.prepare();journal.sendReady((s,b)->true,99,4);
        assertFalse(journal.preparationExpired(1000,100)); // network RTT is a separate metric
    }
    @Test void completedWorkerCannotPublishExpiredRecordsAndEachPacketHasItsOwnSendTime() {
        var journal=new PackageDeltaJournal(1025,new PackageDeltaJournal.Encoder(Runnable::run,4));
        journal.append(metadata(1025),1025);journal.offer(1,records(0,1025,false),0);journal.prepare();
        var time=new java.util.concurrent.atomic.AtomicLong(99);
        assertThrows(IllegalStateException.class,()->journal.sendReady((s,b)->{time.addAndGet(2);return true;},time::get,8,100));
        assertTrue(journal.sent(0));assertEquals(99,journal.acknowledge(0).sentNanos());assertFalse(journal.sent(1));
        assertEquals(1,journal.sentPackets());
        time.set(101);assertThrows(IllegalStateException.class,()->journal.sendReady((s,b)->fail("expired packet sent"),time::get,8,100));
        assertEquals(1,journal.sentPackets());
    }
    @Test void immutableTransportPreparationRunsWithEncoderAndRetriesTheSameEnvelope() {
        var tasks=new ArrayDeque<Runnable>();var preparations=new java.util.concurrent.atomic.AtomicInteger();
        var journal=new PackageDeltaJournal(1,new PackageDeltaJournal.Encoder(tasks::add,4),(seq,bytes)->{
            preparations.incrementAndGet();byte[] result=new byte[bytes.remaining()];bytes.get(result);return result;
        });
        journal.append(metadata(1),1);journal.offer(1,records(0,1,false),0);journal.prepare();
        assertEquals(0,preparations.get());tasks.remove().run();journal.prepare();assertEquals(1,preparations.get());
        var envelopes=new ArrayList<Object>();
        assertEquals(0,journal.sendReady((s,b)->{envelopes.add(journal.prepared(s));return false;},1,4));
        assertEquals(1,journal.sendReady((s,b)->{envelopes.add(journal.prepared(s));return true;},2,4));
        assertSame(envelopes.getFirst(),envelopes.getLast());assertEquals(1,preparations.get());
        assertEquals(7,PackageDeltaCodec.decode(ByteBuffer.wrap((byte[])envelopes.getFirst())).getFirst().id());
        journal.confirm(0);assertThrows(IllegalArgumentException.class,()->journal.prepared(0));
    }
    @Test void transportPreparationFailureCannotSendPartOfAnImmutableFragment() {
        var journal=new PackageDeltaJournal(1025,new PackageDeltaJournal.Encoder(Runnable::run,4),(seq,bytes)->{
            if(seq==1)throw new IllegalArgumentException("Envelope failed");return new Object();
        });
        journal.append(metadata(1025),1025);journal.offer(1,records(0,1025,false),0);
        assertThrows(java.util.concurrent.CompletionException.class,journal::prepare);
        assertEquals(0,journal.sendReady((s,b)->fail("partial prepared group sent"),1,4));
    }
}
