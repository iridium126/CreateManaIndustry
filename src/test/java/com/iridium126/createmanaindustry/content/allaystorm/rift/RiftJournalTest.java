package com.iridium126.createmanaindustry.content.allaystorm.rift;

import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.Path;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class RiftJournalTest {
    @Test void checksumRejectsCorruptionAndTruncation() throws Exception {
        CompoundTag snapshot=new CompoundTag();snapshot.putString("inventory","kept");
        byte[] encoded=RiftTransferJournal.encode(snapshot);
        assertEquals(snapshot,RiftTransferJournal.decode(encoded));
        byte[] corrupt=encoded.clone();corrupt[corrupt.length-1]^=1;
        assertThrows(IOException.class,()->RiftTransferJournal.decode(corrupt));
        assertThrows(IOException.class,()->RiftTransferJournal.decode(java.util.Arrays.copyOf(encoded,encoded.length-1)));
        byte[] badLength=encoded.clone();badLength[8]=127;
        assertThrows(IOException.class,()->RiftTransferJournal.decode(badLength));
    }
    @Test void durableStateReplacementKeepsOneDetachedSnapshot(@TempDir Path directory) throws Exception {
        UUID id=UUID.randomUUID();CompoundTag snapshot=new CompoundTag();snapshot.putString("inventory","original");
        try(var journal=new RiftTransferJournal(directory)) {
            var pending=journal.write(id,"PREPARED",snapshot);snapshot.putString("inventory","changed");pending.get();
            var record=journal.readAll().get().getFirst();assertEquals("original",record.getString("inventory"));assertEquals(id,record.getUUID("Transaction"));
            journal.write(id,"COMMITTED",record).get();
            var records=journal.readAll().get();assertEquals(1,records.size());assertEquals("COMMITTED",records.getFirst().getString("State"));
        }
    }
    @Test void releasedDecisionRetiresRecoveryPayloadWithoutLosingItsTransaction(@TempDir Path directory)throws Exception {
        UUID id=UUID.randomUUID();var snapshot=new CompoundTag();snapshot.putString("inventory","native checkpoint owns this");snapshot.putString("Source","minecraft:overworld");
        try(var journal=new RiftTransferJournal(directory)){journal.write(id,"COMMITTED",snapshot).get();assertTrue(journal.readAll().get().getFirst().contains("inventory"));journal.write(id,"RELEASED",snapshot).get();var record=journal.readAll().get().getFirst();assertEquals(id,record.getUUID("Transaction"));assertEquals("RELEASED",record.getString("State"));assertEquals("minecraft:overworld",record.getString("Source"));assertFalse(record.contains("inventory"));}
    }
}
