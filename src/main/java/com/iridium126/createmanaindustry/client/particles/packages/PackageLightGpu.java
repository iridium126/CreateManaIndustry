package com.iridium126.createmanaindustry.client.particles.packages;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashMap;
import java.util.Map;
import org.lwjgl.opengl.*;

/** Four independent immutable light atlases. Only a completed bank is mapped for CPU writes. */
public final class PackageLightGpu implements AutoCloseable {
    public static final int BANKS=4,DEFAULT_SECTIONS=1024;
    private static final class Resident {
        final int slot,head;final PackageCollisionCache.Section section;
        long revision;PackageLightCache.Snapshot snapshot;
        Resident(int slot,int head,PackageCollisionCache.Section section){this.slot=slot;this.head=head;this.section=section;}
    }
    private final class Bank {
        int buffer;ByteBuffer mapped;long fence,serial=-1;int leases;
        final long[] revisions=new long[capacity];
    }
    private final int capacity,tableSize,dataOffset;
    private final Bank[] banks=new Bank[BANKS];
    private final Map<PackageCollisionCache.Section,Resident> residents=new HashMap<>();
    private final Resident[] table;
    private int current=-1,cursor;
    private long uploadedBytes,skipped,serial;
    private boolean closed;
    public PackageLightGpu(){this(DEFAULT_SECTIONS);}
    public PackageLightGpu(int capacity) {
        if(capacity<1 || capacity>1024)throw new IllegalArgumentException("Light GPU sections");
        this.capacity=capacity;tableSize=Integer.highestOneBit(capacity*2-1)<<1;dataOffset=tableSize*16;
        table=new Resident[tableSize];long bytes=dataOffset+(long)capacity*PackageLightCache.BYTES;
        if(bytes>GL11.glGetInteger(GL43.GL_MAX_SHADER_STORAGE_BLOCK_SIZE))throw new IllegalArgumentException("Light atlas exceeds device limit");
        try {
            for(int i=0;i<BANKS;i++) {
                var bank=banks[i]=new Bank();bank.buffer=GL15.glGenBuffers();GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,bank.buffer);
                int flags=GL30.GL_MAP_WRITE_BIT|GL44.GL_MAP_PERSISTENT_BIT|GL44.GL_MAP_COHERENT_BIT;
                GL44.glBufferStorage(GL43.GL_SHADER_STORAGE_BUFFER,bytes,flags);
                bank.mapped=GL30.glMapBufferRange(GL43.GL_SHADER_STORAGE_BUFFER,0,bytes,flags).order(ByteOrder.nativeOrder());
                for(int p=0;p<dataOffset;p+=4)bank.mapped.putInt(p,0);
            }
        }catch(RuntimeException error){close();throw error;}
    }
    public static int hash(int x,int y,int z){int h=x*0x8da6b343^y*0xd8163841^z*0xcb1ab31f;return h^(h>>>16);}
    /** Publish a numeric pending key before capture, so hash collisions cannot starve other requests. */
    public boolean reserve(PackageCollisionCache.Section section,long revision) {
        open();if(revision<=0)throw new IllegalArgumentException("Light reservation revision");
        var resident=residents.get(section);if(resident!=null)return true;
        if(residents.size()==capacity)return false;
        int head=hash(section.x(),section.y(),section.z())&(tableSize-1);
        while(table[head]!=null)head=(head+1)&(tableSize-1);
        resident=new Resident(residents.size(),head,section);resident.revision=revision;
        residents.put(section,resident);table[head]=resident;serial++;return true;
    }
    public boolean offer(PackageCollisionCache.Section section,PackageLightCache.Snapshot snapshot) {
        open();if(!reserve(section,snapshot.revision()))return false;var resident=residents.get(section);
        if(snapshot.revision()<resident.revision)return false;
        if(resident.snapshot==snapshot)return true;
        if(resident.snapshot!=null && snapshot.revision()==resident.revision)return false;
        resident.revision=snapshot.revision();resident.snapshot=snapshot;serial++;return true;
    }
    public void invalidate(PackageCollisionCache.Section section,long revision) {
        open();var resident=residents.get(section);if(resident==null || revision<resident.revision)return;
        resident.revision=revision;resident.snapshot=null;serial++;
    }
    private boolean writable(Bank bank) {
        if(bank.leases!=0)return false;if(bank.fence==0)return true;
        int status=GL32.glClientWaitSync(bank.fence,0,0);
        if(status==GL32.GL_WAIT_FAILED)throw new IllegalStateException("Light atlas fence failed");
        if(status==GL32.GL_TIMEOUT_EXPIRED)return false;
        GL32.glDeleteSync(bank.fence);bank.fence=0;return true;
    }
    /** Partial uploads publish only complete sections. An exhausted ring never waits. */
    public boolean pump(int byteBudget,long nanoBudget) {
        open();if(byteBudget<0 || nanoBudget<0)throw new IllegalArgumentException("Light upload budget");
        if(current>=0 && banks[current].serial==serial)return false;
        int headerBytes=residents.size()*16;
        if(byteBudget<headerBytes || nanoBudget==0)return false;
        long begin=System.nanoTime();int selected=-1;
        if(current>=0 && writable(banks[current]))selected=current;
        for(int i=0;selected<0 && i<BANKS;i++){int index=(cursor+i)%BANKS;if(writable(banks[index])){selected=index;break;}}
        if(selected<0){skipped++;return false;}
        var bank=banks[selected];int remaining=byteBudget-headerBytes;
        // Revoke old headers first; no partial section can be advertised by this bank.
        for(var resident:residents.values()) {
            int h=resident.head*16;var section=resident.section;
            bank.mapped.putInt(h,section.x()).putInt(h+4,section.y()).putInt(h+8,section.z());
            boolean valid=resident.snapshot!=null && bank.revisions[resident.slot]==resident.revision;
            bank.mapped.putInt(h+12,valid?resident.slot+1:-1);
        }
        uploadedBytes+=headerBytes;
        for(var resident:residents.values()) {
            if(resident.snapshot==null || bank.revisions[resident.slot]==resident.revision)continue;
            if(remaining<PackageLightCache.BYTES+4 || System.nanoTime()-begin>=nanoBudget)break;
            int offset=dataOffset+resident.slot*PackageLightCache.BYTES;
            bank.mapped.slice(offset,PackageLightCache.BYTES).put(resident.snapshot.bytes());
            bank.revisions[resident.slot]=resident.revision;bank.mapped.putInt(resident.head*16+12,resident.slot+1);
            remaining-=PackageLightCache.BYTES+4;uploadedBytes+=PackageLightCache.BYTES+4;
        }
        boolean complete=true;
        for(var resident:residents.values())if(resident.snapshot!=null && bank.revisions[resident.slot]!=resident.revision){complete=false;break;}
        bank.serial=complete?serial:-1;
        GL42.glMemoryBarrier(GL44.GL_CLIENT_MAPPED_BUFFER_BARRIER_BIT);
        current=selected;cursor=(selected+1)%BANKS;return true;
    }
    public boolean covered(PackageCollisionCache.Section section,long revision) {
        open();var resident=residents.get(section);
        return current>=0 && resident!=null && resident.snapshot!=null && resident.revision==revision
                && banks[current].revisions[resident.slot]==revision;
    }
    public View view() {
        open();if(current<0)return null;var bank=banks[current];bank.leases++;return new View(bank);
    }
    public final class View implements AutoCloseable {
        private Bank bank;private boolean used;
        private View(Bank bank){this.bank=bank;}
        public void bind(int binding){open();if(bank==null)throw new IllegalStateException("Closed light view");
            GL42.glMemoryBarrier(GL44.GL_CLIENT_MAPPED_BUFFER_BARRIER_BIT);GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER,binding,bank.buffer);used=true;}
        public int tableSize(){return tableSize;}
        public int dataWordOffset(){return dataOffset/4;}
        @Override public void close(){if(bank==null)return;bank.leases--;if(used && !closed){
            if(bank.fence!=0)GL32.glDeleteSync(bank.fence);bank.fence=GL32.glFenceSync(GL32.GL_SYNC_GPU_COMMANDS_COMPLETE,0);}
            bank=null;}
    }
    public long uploadedBytes(){return uploadedBytes;}
    public long skipped(){return skipped;}
    private void open(){if(closed)throw new IllegalStateException("Light atlas closed");}
    @Override public void close(){if(closed)return;closed=true;for(var bank:banks)if(bank!=null){
        if(bank.fence!=0)GL32.glDeleteSync(bank.fence);if(bank.buffer!=0){GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,bank.buffer);
            if(bank.mapped!=null)GL15.glUnmapBuffer(GL43.GL_SHADER_STORAGE_BUFFER);GL15.glDeleteBuffers(bank.buffer);}}}
}
