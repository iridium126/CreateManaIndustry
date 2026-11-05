package com.iridium126.createmanaindustry.client.particles.engine.spatial;

import com.iridium126.createmanaindustry.content.allaystorm.StarfallColumnSnapshot;
import com.iridium126.createmanaindustry.content.allaystorm.StarfallColumnRules;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static com.iridium126.createmanaindustry.client.gpu.GpuResourceConstants.*;
import static org.junit.jupiter.api.Assertions.*;

class StormControlJournalTest {
    private static final StormControlJournal.Parameters INACTIVE=StormControlJournal.Parameters.inactive(1,0);

    @Test void fullRingPreservesEveryLogicalTick() {
        var log=new StormControlJournal();
        for(int i=0;i<8;i++)log.append(900+i,INACTIVE,List.of());
        for(int i=0;i<3;i++)log.sent(log.sendable());
        assertNull(log.sendable());
        assertEquals(8,log.retained());
        assertEquals(0,log.committed());
        assertThrows(IllegalStateException.class,()->log.receive(2,true));
        log.receive(1,true);
        assertEquals(903,log.sendable().tick());
        log.sent(log.sendable());
        assertNull(log.sendable());
        log.receive(2,true);
        log.receive(3,true);
        log.receive(4,true);
        assertEquals(904,log.sendable().tick());
        assertEquals(4,log.retained());
    }

    @Test void failedReceiptDrainsLaterWorkWithoutDroppingTheUncommittedSuffix() {
        var log=new StormControlJournal();
        for(int i=0;i<4;i++)log.append(20+i,INACTIVE,List.of());
        for(int i=0;i<3;i++)log.sent(log.sendable());
        log.receive(1,true);
        log.receive(2,false);
        log.receive(3,true);
        assertNull(log.sendable());
        assertNull(log.awaitingReceipt());
        assertEquals(3,log.received());
        assertEquals(1,log.committed());
        assertEquals(3,log.retained());
    }

    @Test void worldClockCorrectionsPreserveOneLogicalStepPerSample() {
        var log=new StormControlJournal();
        long[] gameTimes={100,103,103,97,(1L<<21)-1,1L<<21};
        for(long gameTime:gameTimes){
            float seconds=StarfallColumnRules.renderSeconds(gameTime,0);
            var active=new StormControlJournal.Parameters(2,true,1,19,1,100,2,8,0,0,1,1,seconds,6,false);
            log.appendWorldSample(gameTime,active,List.of(),null,StormControlJournal.Target.ABSENT,
                    0,List.of(),false,null,List.of());
        }
        for(int i=0;i<gameTimes.length;i++){
            var input=log.sendable();
            assertEquals(i+1,input.tick());
            assertEquals(gameTimes[i],input.gameTime());
            var packet=ByteBuffer.allocate(input.bytes()).order(ByteOrder.LITTLE_ENDIAN);
            input.encode(packet);
            assertEquals(i+1,packet.getLong(24));
            assertEquals(StarfallColumnRules.renderSeconds(gameTimes[i],0),packet.getFloat(80));
            log.sent(input);
            log.receive(input.sequence(),true);
        }
        assertEquals(0,log.retained());
    }

    @Test void emptyTicksMayBeSkippedOnlyAcrossAnInactiveIdentityBoundary() {
        var log=new StormControlJournal();
        var active=new StormControlJournal.Parameters(2,true,4,19,1,100,2,8,0,0,1,1,3.5f,6,false);
        log.append(100,INACTIVE,List.of());
        assertThrows(IllegalArgumentException.class,()->log.append(102,INACTIVE,List.of()));
        log.append(120,active,List.of(StormControlJournal.Command.lifecycle(true)));
        assertThrows(IllegalArgumentException.class,()->log.append(122,active,List.of()));
        log.append(121,StormControlJournal.Parameters.inactive(3,0),List.of(StormControlJournal.Command.lifecycle(false)));
        assertEquals(3,log.retained());
    }

    @Test void anEarlierGenerationKeepsItsCommandStampWithinTheAcceptedTick() {
        var log=new StormControlJournal();
        var retiredDamage=StormControlJournal.Command.damage(0,3,0,0,7,true).inGeneration(1);
        var active=new StormControlJournal.Parameters(2,true,1,19,1,100,2,8,0,0,1,1,3.5f,6,false);
        log.append(120,active,List.of(retiredDamage,StormControlJournal.Command.lifecycle(true)));
        var tick=log.sendable();
        var out=ByteBuffer.allocate(tick.bytes()).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(tick.bytes(),tick.encode(out));
        assertEquals(2,out.getLong(32));
        assertEquals(1,out.getLong(STORM_TICK_HEADER_BYTES+16));
        assertEquals(7f,out.getFloat(STORM_TICK_HEADER_BYTES+44));
        assertEquals(2,out.getLong(STORM_TICK_HEADER_BYTES+STORM_CONTROL_BYTES+16));
    }

    @Test void clearingCoverageCarriesAnExplicitUnknownTable() {
        var log=new StormControlJournal();
        log.append(200,INACTIVE,List.of(),StormControlTerrain.capture(Map.of()));
        var tick=log.sendable();
        var out=ByteBuffer.allocate(tick.bytes()).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(tick.bytes(),tick.encode(out));
        assertEquals(200,out.getLong(24));
        assertEquals(16,out.getInt(88));
        assertEquals(0,out.getInt(92));
        for(int row=0;row<16;row++)assertEquals(-1,out.getInt(STORM_TICK_HEADER_BYTES+row*STORM_TERRAIN_ROW_BYTES+12));
    }

    @Test void columnRestoreKeepsTheLastMemberAndMovingTargetIdentity() {
        var words=new float[StarfallColumnSnapshot.FLOATS];
        int last=(STORM_COLUMN_MEMBERS-1)*StarfallColumnSnapshot.STRIDE;
        words[last]=4;
        words[last+26]=3.75f;
        words[last+27]=1.5f;
        words[last+28]=11f;
        words[last+29]=12f;
        var commands=new ArrayList<StormControlJournal.Command>();
        StormControlJournal.Command.columnRestore(commands,713,63,114,3.5f,new StarfallColumnSnapshot(words));
        var log=new StormControlJournal();
        var active=new StormControlJournal.Parameters(2,true,2048,19,1,100,2,8,0,0,1,1,3.5f,6,false);
        log.append(70,active,commands,null,new StormControlJournal.Target(701,0,2,8,1,2,-8));
        var tick=log.sendable();
        var out=ByteBuffer.allocate(tick.bytes()).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(tick.bytes(),tick.encode(out));
        assertEquals(701,out.getInt(124));
        int lastCommand=tick.bytes()-STORM_CONTROL_BYTES;
        assertEquals(STORM_CONTROL_COLUMN_RESTORE_ROW,out.getInt(lastCommand));
        assertEquals(STORM_COLUMN_MEMBERS*STORM_COLUMN_RESTORE_PARTS-1,out.getInt(lastCommand+4));
        assertEquals((63L<<32)|713L,out.getLong(lastCommand+24));
        assertEquals(3.75f,out.getFloat(lastCommand+40));
        assertEquals(1.5f,out.getFloat(lastCommand+44));
        assertEquals(11f,out.getFloat(lastCommand+48));
        assertEquals(12f,out.getFloat(lastCommand+52));
        assertEquals(0f,out.getFloat(lastCommand+56));
        assertEquals(0f,out.getFloat(lastCommand+60));
    }
}
