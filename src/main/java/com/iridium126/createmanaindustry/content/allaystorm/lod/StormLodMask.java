package com.iridium126.createmanaindustry.content.allaystorm.lod;

import java.util.BitSet;
import net.minecraft.network.RegistryFriendlyByteBuf;
import com.iridium126.createmanaindustry.content.allaystorm.AllayStormData;

/** Bounded, adaptive death-set encoding. Sparse IDs use positive deltas; dense sets use bytes. */
public final class StormLodMask {
    private StormLodMask() {}
    public static void write(RegistryFriendlyByteBuf b, BitSet mask) {
        int sparse = varSize(mask.cardinality()), previous = -1;
        for (int id=mask.nextSetBit(0); id>=0; id=mask.nextSetBit(id+1)) { sparse+=varSize(id-previous); previous=id; }
        byte[] bytes=mask.toByteArray();
        boolean dense=bytes.length+varSize(bytes.length)<sparse;
        b.writeBoolean(dense);
        if(dense) b.writeByteArray(bytes);
        else {
            b.writeVarInt(mask.cardinality()); previous=-1;
            for(int id=mask.nextSetBit(0);id>=0;id=mask.nextSetBit(id+1)){b.writeVarInt(id-previous);previous=id;}
        }
    }
    public static BitSet read(RegistryFriendlyByteBuf b) {
        if(b.readBoolean()) return BitSet.valueOf(b.readByteArray((AllayStormData.MAX_COUNT+7)/8));
        int count=b.readVarInt(), previous=-1;
        if(count<0||count>AllayStormData.MAX_COUNT)throw new IllegalArgumentException("Invalid LOD death count");
        BitSet result=new BitSet();
        for(int i=0;i<count;i++) {
            int delta=b.readVarInt(); long id=(long)previous+delta;
            if(delta<=0||id>=AllayStormData.MAX_COUNT)throw new IllegalArgumentException("Invalid LOD death ID");
            previous=(int)id;result.set(previous);
        }
        return result;
    }
    private static int varSize(int n){int size=1;while((n>>>=7)!=0)size++;return size;}
}
