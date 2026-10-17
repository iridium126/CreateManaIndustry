package com.iridium126.createmanaindustry.client.particles.packages;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;

/** Bounded ACK staging. Rows for one body form a chain, preserving cumulative replay order. */
final class PackageEnvironmentAckBatch {
    static final int ROW_BYTES=128, LIMIT=512;
    static final int CHECK_IDENTITY=1, CORRECT_BODY=2, HEAD=4;
    private final ByteBuffer bytes=ByteBuffer.allocateDirect(LIMIT*ROW_BYTES).order(ByteOrder.nativeOrder());
    private final Int2IntOpenHashMap tails=new Int2IntOpenHashMap(LIMIT);
    private int size;

    PackageEnvironmentAckBatch(){tails.defaultReturnValue(-1);}
    int size(){return size;}
    boolean full(){return size==LIMIT;}
    /** Initializes every word, including optional correction fields, before linking a row. */
    int append(int body,long through,int fire,float health,int permissions,
            long id,long generation,long lease,int index,long revision,boolean correction){
        if(full())throw new IllegalStateException("Environment ACK batch full");
        int row=size++,at=row*ROW_BYTES;
        for(int p=at;p<at+ROW_BYTES;p+=8)bytes.putLong(p,0);
        int previous=tails.put(body,row);
        bytes.putInt(at,body).putInt(at+4,(int)through).putInt(at+8,fire).putInt(at+12,permissions);
        bytes.putLong(at+16,id).putLong(at+24,generation).putLong(at+32,lease);
        bytes.putInt(at+40,index+1).putInt(at+44,(int)revision).putInt(at+48,(int)(revision>>>32));
        bytes.putInt(at+52,(id==0?0:CHECK_IDENTITY)|(correction?CORRECT_BODY:0)|(previous<0?HEAD:0));
        bytes.putFloat(at+64,health);
        if(previous>=0)bytes.putInt(previous*ROW_BYTES+56,row+1);
        return at;
    }
    ByteBuffer fields(){return bytes;}
    ByteBuffer upload(){return bytes.limit(size*ROW_BYTES).position(0);}
    void clear(){size=0;tails.clear();bytes.clear();}
}
